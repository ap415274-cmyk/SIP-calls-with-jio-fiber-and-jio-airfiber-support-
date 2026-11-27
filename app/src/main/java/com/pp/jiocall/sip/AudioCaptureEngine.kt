package com.pp.jiocall.sip

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

data class CaptureCandidate(
    val audioSource: Int,
    val sampleRate: Int,
)

/**
 * Real-time microphone capture engine modeled on WebRTC `WebRtcAudioRecord`
 * and Baresip `aaudio`/`opensles` capture:
 *
 * - Runs on a dedicated `THREAD_PRIORITY_URGENT_AUDIO` thread.
 * - Reads exact 20 ms blocking frames via `READ_BLOCKING` accumulation so the
 *   downlink resampler and AMR/G.711 encoder never see ragged chunk sizes.
 * - Attaches hardware-only AEC/NS via [AudioEffectsController] (blocklisting
 *   buggy AOSP SW effects) before `startRecording()`.
 * - Monitors `AudioRecordingCallback.isClientSilenced` (API 29+) and digital
 *   flatline silence (`peak == 0` while unmuted for ~900 ms), automatically
 *   stepping down the fallback ladder (`VOICE_COMMUNICATION` -> `MIC` ->
 *   `VOICE_RECOGNITION`) so another device's quirky HAL never stays silent.
 */
internal class AudioCaptureEngine(
    private val context: Context,
    private val codec: Codec,
    private val echoCancellation: Boolean,
    private val isRunning: () -> Boolean,
    private val isMuted: () -> Boolean,
    private val isRemotePaused: () -> Boolean,
    private val onEncodedFrame: (pcm: ShortArray, mutedFrame: Boolean) -> Unit,
) {

    private val effects = AudioEffectsController()
    private val dcBlocker = AudioGain.DcBlocker()
    private val txTrim = AudioGain.AdaptiveTxTrim()
    private val active = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var currentRecord: AudioRecord? = null
    @Volatile private var silencedByOs: Boolean = false

    fun start() {
        if (!active.compareAndSet(false, true)) return
        thread = Thread(::captureLoop, "JioCall-TX-Capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        active.set(false)
        runCatching { currentRecord?.stop() }
        val t = thread
        thread = null
        if (t != null && Thread.currentThread() !== t) {
            runCatching { t.join(800L) }
        }
        releaseCurrentRecord()
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val nativeRate = queryNativeRate()
        val candidates = buildCaptureCandidates(codec.clockRate, nativeRate, echoCancellation)

        var candidateIdx = 0
        var zeroFallbackExhausted = false
        while (active.get() && isRunning()) {
            if (candidateIdx >= candidates.size) {
                Log.w(TAG, "Capture candidates cycled; holding steady on MIC fallback")
                sleepQuietly(300L)
                if (!active.get() || !isRunning()) break
                candidateIdx = candidates.indexOfFirst { it.audioSource == MediaRecorder.AudioSource.MIC }
                    .takeIf { it >= 0 } ?: 0
                zeroFallbackExhausted = true
            }

            val cand = candidates[candidateIdx]
            val record = openCandidate(cand)
            if (record == null) {
                candidateIdx++
                continue
            }

            currentRecord = record
            val needRestart = streamCandidate(record, cand, allowZeroSwitch = !zeroFallbackExhausted)
            releaseCurrentRecord()

            if (!active.get() || !isRunning()) break
            if (needRestart) {
                candidateIdx++
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCandidate(cand: CaptureCandidate): AudioRecord? {
        val frameSamples = cand.sampleRate / 50 // 20 ms
        val minBuf = AudioRecord.getMinBufferSize(
            cand.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize failed for ${sourceName(cand.audioSource)}@${cand.sampleRate}: $minBuf")
            return null
        }

        val bufferBytes = maxOf(minBuf * 2, frameSamples * 2 * 4)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(cand.audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(cand.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.onFailure {
            Log.w(TAG, "AudioRecord.Builder failed ${sourceName(cand.audioSource)}@${cand.sampleRate}: ${it.message}")
        }.getOrNull() ?: return null

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized for ${sourceName(cand.audioSource)}@${cand.sampleRate}")
            runCatching { record.release() }
            return null
        }

        val sessionId = record.audioSessionId
        val useHwPreproc = echoCancellation && cand.audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
        effects.attach(sessionId, enableAec = useHwPreproc, enableNs = useHwPreproc)

        silencedByOs = false
        val recCallback = registerSilenceWatcher(record)

        val started = runCatching {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.onFailure {
            Log.w(TAG, "startRecording threw for ${sourceName(cand.audioSource)}@${cand.sampleRate}: ${it.message}")
        }.getOrDefault(false)

        if (!started) {
            unregisterSilenceWatcher(record, recCallback)
            effects.release()
            runCatching { record.release() }
            return null
        }

        recCallbackTag = recCallback
        Log.i(
            TAG,
            "Opened mic capture: source=${sourceName(cand.audioSource)} rate=${cand.sampleRate}Hz session=$sessionId bufBytes=$bufferBytes (codecRate=${codec.clockRate}Hz)",
        )
        return record
    }

    private var recCallbackTag: AudioManager.AudioRecordingCallback? = null

    private fun registerSilenceWatcher(record: AudioRecord): AudioManager.AudioRecordingCallback? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val mine = configs?.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                if (mine != null && mine.isClientSilenced) {
                    Log.w(TAG, "AudioRecordingCallback: OS silenced mic session=${record.audioSessionId}")
                    silencedByOs = true
                }
            }
        }
        runCatching { am.registerAudioRecordingCallback(cb, null) }
        return cb
    }

    private fun unregisterSilenceWatcher(record: AudioRecord?, cb: AudioManager.AudioRecordingCallback?) {
        if (cb == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching { am.unregisterAudioRecordingCallback(cb) }
        record?.hashCode()
    }

    /**
     * Streams 20 ms frames from [record]. Returns `true` if the capture stream
     * became dead / OS-silenced and the loop should switch to the next fallback
     * candidate.
     */
    private fun streamCandidate(
        record: AudioRecord,
        cand: CaptureCandidate,
        allowZeroSwitch: Boolean,
    ): Boolean {
        val captureFrameSamples = cand.sampleRate / 50
        val captureBuf = ShortArray(captureFrameSamples)
        val silentFrame = ShortArray(codec.samplesPerFrame)
        dcBlocker.reset()
        txTrim.reset()

        var consecutiveZeroFrames = 0
        var errorFrames = 0
        var nonZeroSeen = false

        while (active.get() && isRunning()) {
            val readCount = readExactFrame(record, captureBuf, captureFrameSamples)
            if (!active.get() || !isRunning()) return false

            if (readCount <= 0) {
                errorFrames++
                if (errorFrames >= 5) {
                    Log.w(TAG, "AudioRecord read error=$readCount on ${sourceName(cand.audioSource)}@${cand.sampleRate}; switching candidate")
                    return true
                }
                sleepQuietly(10L)
                continue
            }
            errorFrames = 0

            if (silencedByOs) {
                Log.w(TAG, "OS silenced ${sourceName(cand.audioSource)}@${cand.sampleRate}; promoting fallback")
                return true
            }

            if (isRemotePaused()) {
                continue
            }

            if (isMuted()) {
                consecutiveZeroFrames = 0
                dcBlocker.reset()
                txTrim.reset()
                onEncodedFrame(silentFrame, true)
                continue
            }

            val rawPeak = AudioGain.peakAmplitude(captureBuf, readCount)
            if (rawPeak == 0) {
                consecutiveZeroFrames++
                if (allowZeroSwitch && !nonZeroSeen && consecutiveZeroFrames >= 45) {
                    Log.w(
                        TAG,
                        "Detected $consecutiveZeroFrames dead-zero frames on ${sourceName(cand.audioSource)}@${cand.sampleRate}; trying next source",
                    )
                    return true
                }
            } else {
                nonZeroSeen = true
                consecutiveZeroFrames = 0
            }

            val codecPcm = if (cand.sampleRate == codec.clockRate) {
                if (readCount == codec.samplesPerFrame) captureBuf else captureBuf.copyOf(codec.samplesPerFrame)
            } else {
                val resampled = PcmResampler.resample(captureBuf, cand.sampleRate, codec.clockRate, readCount)
                if (resampled.size == codec.samplesPerFrame) resampled else resampled.copyOf(codec.samplesPerFrame)
            }

            dcBlocker.process(codecPcm, codecPcm.size)
            txTrim.process(codecPcm, codecPcm.size)
            onEncodedFrame(codecPcm, false)
        }
        return false
    }

    private fun readExactFrame(record: AudioRecord, dst: ShortArray, targetSamples: Int): Int {
        var offset = 0
        var emptyReads = 0
        while (offset < targetSamples && active.get() && isRunning()) {
            val n = record.read(dst, offset, targetSamples - offset, AudioRecord.READ_BLOCKING)
            when {
                n > 0 -> {
                    offset += n
                    emptyReads = 0
                }
                n == 0 -> {
                    emptyReads++
                    if (emptyReads >= 8) return if (offset > 0) offset else 0
                    sleepQuietly(5L)
                }
                else -> return n
            }
        }
        return offset
    }

    private fun releaseCurrentRecord() {
        val r = currentRecord
        currentRecord = null
        val cb = recCallbackTag
        recCallbackTag = null
        unregisterSilenceWatcher(r, cb)
        effects.release()
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
    }

    private fun queryNativeRate(): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val prop = runCatching {
            am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }.getOrNull()
        return prop?.takeIf { it in 8_000..48_000 } ?: 48_000
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "AudioCaptureEngine"

        fun buildCaptureCandidates(
            codecRate: Int,
            nativeRate: Int = 48_000,
            preferHardwareAec: Boolean = true,
        ): List<CaptureCandidate> {
            val rates = linkedSetOf(codecRate, nativeRate, 48_000, 16_000, 8_000)
                .filter { it > 0 }

            val list = ArrayList<CaptureCandidate>(8)
            fun addUnique(source: Int, rate: Int) {
                val cand = CaptureCandidate(source, rate)
                if (cand !in list) list.add(cand)
            }

            if (preferHardwareAec) {
                // Start with VOICE_COMMUNICATION @ codecRate (for hardware AEC compatibility),
                // then VOICE_COMMUNICATION @ nativeRate (for HALs with native-only clock),
                // then MIC / VOICE_RECOGNITION fallbacks if HAL silences VOICE_COMMUNICATION.
                addUnique(MediaRecorder.AudioSource.VOICE_COMMUNICATION, codecRate)
                addUnique(MediaRecorder.AudioSource.VOICE_COMMUNICATION, nativeRate)
                for (r in rates) addUnique(MediaRecorder.AudioSource.VOICE_COMMUNICATION, r)
            }

            addUnique(MediaRecorder.AudioSource.MIC, nativeRate)
            addUnique(MediaRecorder.AudioSource.MIC, codecRate)
            addUnique(MediaRecorder.AudioSource.VOICE_RECOGNITION, nativeRate)
            addUnique(MediaRecorder.AudioSource.VOICE_RECOGNITION, codecRate)
            if (!preferHardwareAec) {
                addUnique(MediaRecorder.AudioSource.VOICE_COMMUNICATION, codecRate)
            }
            return list
        }

        private fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            else -> "SRC($source)"
        }
    }
}
