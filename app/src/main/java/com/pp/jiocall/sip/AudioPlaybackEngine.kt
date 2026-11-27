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
    @Volatile private var primedFrames: Long = 0L

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

        val candidates = linkedSetOf(nativeOut, 48_000, primaryCodec.clockRate, 16_000, 8_000)
        for (rate in candidates) {
            val track = createTrack(rate, lowLatency = true) ?: createTrack(rate, lowLatency = false)
            if (track != null) {
                audioTrack = track
                outputRate = rate
                var writtenWarm = 0L
                runCatching {
                    track.play()
                    // Write one silent 20ms warmup frame so hardware AEC locks onto an active RX stream immediately
                    val prime = ShortArray(rate / 50)
                    val w = track.write(prime, 0, prime.size, AudioTrack.WRITE_NON_BLOCKING)
                    if (w > 0) writtenWarm = w.toLong()
                }
                primedFrames = writtenWarm
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
        var outRate = outputRate
        var outFrameSamples = outRate / 50
        var silence = ShortArray(outFrameSamples)
        var outBuf = ShortArray(outFrameSamples * 2)
        var concealer = PcmConcealer(primaryCodec.clockRate)
        var activeCodec = primaryCodec
        var nextTickNs = System.nanoTime()
        var frameCount = 0L
        var lastPeak = 0

        var writtenFrames = primedFrames
        var lastRawHead = 0L
        var totalHeadFrames = 0L

        while (active.get() && isRunning()) {
            var track = audioTrack
            if (track != null) {
                val rawHead = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                val headDelta = (rawHead - lastRawHead) and 0xFFFF_FFFFL
                if (headDelta in 1L..0x4000_0000L) {
                    totalHeadFrames += headDelta
                    lastRawHead = rawHead
                }
            }
            val queuedFrames = (writtenFrames - totalHeadFrames).coerceAtLeast(0L)

            // If HAL audio backed up >240ms (e.g. emulator audio proxy stall), flush stale buffer immediately
            if (track != null && totalHeadFrames > 0L && queuedFrames > outFrameSamples * 12L) {
                runCatching {
                    track.pause()
                    track.flush()
                    track.play()
                }
                writtenFrames = 0L
                totalHeadFrames = 0L
                lastRawHead = 0L
                jitterBuffer.trimBacklog(maxKeepFrames = 2)
                nextTickNs = System.nanoTime()
            }

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

            if (track != null) {
                val written: Int = if (pcmAndRate != null) {
                    val (rawPcm, srcRate) = pcmAndRate
                    onDecodedPcm(rawPcm, srcRate)
                    val targetOutLen = ((rawPcm.size.toLong() * outRate) / srcRate).toInt().coerceAtLeast(1)
                    if (outBuf.size < targetOutLen) {
                        outBuf = ShortArray(targetOutLen)
                    }
                    val outLen = PcmResampler.resampleInto(rawPcm, srcRate, outRate, rawPcm.size, outBuf)
                    val gain = if (isSpeakerOn()) AudioGain.SPEAKER else AudioGain.EARPIECE
                    AudioGain.apply(outBuf, outLen, gain)
                    lastPeak = maxOf(lastPeak, AudioGain.peakAmplitude(outBuf, outLen))
                    writeNonBlocking(track, outBuf, outLen)
                } else {
                    // Only keep a minimal 60ms cushion when waiting for packets; never stuff HAL buffer with silence
                    val shouldPadSilence = (totalHeadFrames == 0L && writtenFrames < outFrameSamples * 2L) ||
                        (totalHeadFrames > 0L && queuedFrames < outFrameSamples * 3L)
                    if (shouldPadSilence) {
                        writeNonBlocking(track, silence, silence.size)
                    } else {
                        0
                    }
                }

                if (written > 0) {
                    writtenFrames += written.toLong()
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
                    outRate = outputRate
                    outFrameSamples = outRate / 50
                    if (silence.size != outFrameSamples) silence = ShortArray(outFrameSamples)
                    writtenFrames = primedFrames
                    totalHeadFrames = 0L
                    lastRawHead = 0L
                    if (track == null) Thread.sleep(20L)
                }
            }

            nextTickNs += 20_000_000L
            val now = System.nanoTime()
            val sleepNs = nextTickNs - now
            if (sleepNs > 500_000L) {
                LockSupport.parkNanos(sleepNs)
            } else if (-sleepNs > 80_000_000L) {
                // Re-anchor drift and shed stale RTP packets after a CPU/emulator stall
                nextTickNs = now
                jitterBuffer.trimBacklog(maxKeepFrames = 2)
            }
        }
    }

    private fun writeNonBlocking(track: AudioTrack, data: ShortArray, length: Int): Int {
        if (length <= 0) return 0
        var total = 0
        val first = track.write(data, 0, length, AudioTrack.WRITE_NON_BLOCKING)
        if (first < 0) return first
        total += first
        if (total < length && active.get() && isRunning()) {
            LockSupport.parkNanos(4_000_000L)
            val second = track.write(data, total, length - total, AudioTrack.WRITE_NON_BLOCKING)
            if (second < 0) return if (total > 0) total else second
            total += second
        }
        return total
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
