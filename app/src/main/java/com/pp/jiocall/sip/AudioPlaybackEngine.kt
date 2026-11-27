package com.pp.jiocall.sip

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Real-time VoIP downlink playback engine modeled on WebRTC `WebRtcAudioTrack`
 * and PJSIP `android_jni_dev`:
 *
 * - Opened and started BEFORE [AudioCaptureEngine] so OEM hardware AEC has an
 *   active `USAGE_VOICE_COMMUNICATION` render stream reference immediately.
 * - Runs on a dedicated `THREAD_PRIORITY_URGENT_AUDIO` thread on a drift-free
 *   20 ms deadline clock, reading from [RtpJitterBuffer].
 * - Switches dynamically across multi-codec RX decoders (`AMR-WB`, `AMR-NB`,
 *   `PCMA`, `PCMU`) and applies smooth PLC ([PcmConcealer]) + clean gain/resampling.
 */
internal class AudioPlaybackEngine(
    private val primaryCodec: Codec,
    private val jitterBuffer: RtpJitterBuffer,
    private val isRunning: () -> Boolean,
    private val isSpeakerOn: () -> Boolean,
    private val onDecodedPcm: (pcm: ShortArray, sampleRate: Int) -> Unit,
) {
    private val decoders = ConcurrentHashMap<Codec, AmrDecoder>()
    private val active = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var outputRate: Int = primaryCodec.clockRate

    fun start() {
        if (!active.compareAndSet(false, true)) return
        // Pre-warm primary codec decoder before playback begins so initial RX frames decode in <1 ms
        if (primaryCodec.isAmr) {
            runCatching { decoders.getOrPut(primaryCodec) { AmrDecoder(primaryCodec) } }
        }
        openTrackAndPrime()
        thread = Thread(::playbackLoop, "JioCall-RX-Playout").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        active.set(false)
        val t = thread
        thread = null
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        if (t != null && Thread.currentThread() !== t) {
            runCatching { t.join(800L) }
        }
        releaseTrack()
        decoders.values.forEach { runCatching { it.release() } }
        decoders.clear()
    }

    private fun openTrackAndPrime() {
        val nativeOut = runCatching {
            AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_VOICE_CALL)
        }.getOrDefault(48_000).takeIf { it in 8_000..48_000 } ?: 48_000

        val candidates = linkedSetOf(primaryCodec.clockRate, nativeOut, 48_000, 16_000, 8_000)
        for (rate in candidates) {
            val track = createTrack(rate, lowLatency = true) ?: createTrack(rate, lowLatency = false)
            if (track != null) {
                audioTrack = track
                outputRate = rate
                runCatching {
                    track.play()
                    // Write one silent 20ms warmup frame so hardware AEC locks onto an active RX stream immediately
                    val prime = ShortArray(rate / 50)
                    track.write(prime, 0, prime.size, AudioTrack.WRITE_NON_BLOCKING)
                }
                Log.i(TAG, "Opened playback AudioTrack @${rate}Hz session=${track.audioSessionId} primary=${primaryCodec.mime}")
                return
            }
        }
        Log.e(TAG, "Could not initialize any playback AudioTrack!")
    }

    private fun createTrack(sampleRate: Int, lowLatency: Boolean): AudioTrack? {
        val frameSamples = sampleRate / 50
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) return null
        val bufferBytes = maxOf(minBytes, frameSamples * 2 * 3)

        return runCatching {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (lowLatency) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val t = builder.build()
            if (t.state == AudioTrack.STATE_INITIALIZED) {
                t
            } else {
                runCatching { t.release() }
                null
            }
        }.getOrNull()
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val outRate = outputRate
        val outFrameSamples = outRate / 50
        val silence = ShortArray(outFrameSamples)
        var concealer = PcmConcealer(primaryCodec.clockRate)
        var activeCodec = primaryCodec
        var nextTickNs = System.nanoTime()
        var frameCount = 0L
        var lastPeak = 0

        while (active.get() && isRunning()) {
            val popped = jitterBuffer.popNext()
            val pcmAndRate: Pair<ShortArray, Int>? = when (popped) {
                is RtpJitterBuffer.PopResult.Frame -> {
                    val pkt = popped.packet
                    if (pkt.codec != activeCodec) {
                        activeCodec = pkt.codec
                        concealer = PcmConcealer(activeCodec.clockRate)
                    }
                    val decoded = decodePacket(pkt)
                    if (decoded != null) {
                        concealer.recordGood(decoded)
                        decoded to activeCodec.clockRate
                    } else {
                        val concealed = concealer.conceal()
                        concealed to activeCodec.clockRate
                    }
                }
                RtpJitterBuffer.PopResult.Conceal -> {
                    concealer.conceal() to activeCodec.clockRate
                }
                RtpJitterBuffer.PopResult.Wait -> {
                    null
                }
            }

            var track = audioTrack
            if (track != null) {
                val written = if (pcmAndRate != null) {
                    val (rawPcm, srcRate) = pcmAndRate
                    onDecodedPcm(rawPcm, srcRate)
                    val outPcm = if (srcRate == outRate) rawPcm else PcmResampler.resample(rawPcm, srcRate, outRate)
                    val gain = if (isSpeakerOn()) AudioGain.SPEAKER else AudioGain.EARPIECE
                    AudioGain.apply(outPcm, outPcm.size, gain)
                    lastPeak = maxOf(lastPeak, AudioGain.peakAmplitude(outPcm))
                    track.write(outPcm, 0, outPcm.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                }
                frameCount++
                if (frameCount == 1L || frameCount % 250L == 0L) {
                    Log.i(TAG, "PLAYBACK: $frameCount frames @${outRate}Hz, maxAmp=$lastPeak")
                    lastPeak = 0
                }
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write failed ($written); reopening playback track")
                    releaseTrack()
                    openTrackAndPrime()
                    track = audioTrack
                    if (track == null) Thread.sleep(20L)
                }
            }

            nextTickNs += 20_000_000L
            val now = System.nanoTime()
            val sleepNs = nextTickNs - now
            if (sleepNs > 500_000L && track == null) {
                LockSupport.parkNanos(sleepNs)
            } else if (now - nextTickNs > 100_000_000L) {
                // Re-anchor drift after a stall
                nextTickNs = now
            }
        }
    }

    private fun decodePacket(pkt: RtpJitterBuffer.Packet): ShortArray? {
        return when (pkt.codec) {
            Codec.AMR_WB, Codec.AMR_NB -> {
                val dec = runCatching {
                    decoders.getOrPut(pkt.codec) { AmrDecoder(pkt.codec) }
                }.getOrNull() ?: return null
                dec.decode(pkt.frame)
            }
            Codec.PCMA -> G711Codec.decodeAlaw(pkt.frame, 0, pkt.frame.size)
            Codec.PCMU -> G711Codec.decodeUlaw(pkt.frame, 0, pkt.frame.size)
        }
    }

    private fun releaseTrack() {
        val t = audioTrack
        audioTrack = null
        if (t != null) {
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    companion object {
        private const val TAG = "AudioPlaybackEngine"
    }
}
