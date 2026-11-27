package com.pp.jiocall.sip

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Adaptive VoIP jitter buffer modeled on RusTone/PJSIP PJMEDIA jbuf.
 *
 * Smooths UDP network burstiness using a 16-bit sequence-number ordered queue,
 * adaptive target prefill (40ms to 200ms), automatic DTX / talkspurt reset on
 * RTP timestamp discontinuities, and accurate loss accounting.
 */
internal class RtpJitterBuffer(
    private val minPrefillFrames: Int = 2,
    private val maxCapacityFrames: Int = 24,
) {
    data class Packet(
        val seq: Int,
        val timestamp: Long,
        val codec: Codec,
        val frame: ByteArray,
    )

    sealed class PopResult {
        data class Frame(val packet: Packet) : PopResult()
        data object Conceal : PopResult()
        data object Wait : PopResult()
    }

    private val lock = Any()
    private val queue = java.util.TreeMap<Int, Packet>()
    private var nextSeq: Int = -1
    private var lastPushTs: Long = -1L
    private var targetPrefill = minPrefillFrames
    private var primed = false
    private var consecutiveConceals = 0
    private var consecutiveGood = 0

    var totalReceived: Long = 0L
        private set
    var totalLost: Long = 0L
        private set

    fun reset() = synchronized(lock) {
        queue.clear()
        nextSeq = -1
        lastPushTs = -1L
        targetPrefill = minPrefillFrames
        primed = false
        consecutiveConceals = 0
        consecutiveGood = 0
    }

    fun push(packet: Packet) = synchronized(lock) {
        val seq = packet.seq and 0xFFFF
        totalReceived++

        // Detect DTX silence resumption or SSRC/talkspurt discontinuity
        val seqDiff = if (nextSeq >= 0) signedSeqDiff(seq, nextSeq) else 0
        val tsDiff = if (lastPushTs >= 0L) packet.timestamp - lastPushTs else 0L
        val expectedSamplesPerFrame = packet.codec.clockRate / 50L
        if (nextSeq >= 0 && (seqDiff > 16 || tsDiff > expectedSamplesPerFrame * 8L || tsDiff < -expectedSamplesPerFrame * 8L)) {
            if (queue.isEmpty()) {
                nextSeq = seq
                primed = true
            }
        }
        lastPushTs = packet.timestamp

        if (nextSeq == -1) {
            nextSeq = seq
            primed = false
        } else {
            val rel = signedSeqDiff(seq, nextSeq)
            if (rel < 0 && queue.isNotEmpty()) {
                if (!primed && rel > -500) {
                    // Out-of-order initial burst while prefilling: rewind cursor to earliest packet
                    nextSeq = seq
                } else {
                    // Late packet arriving behind active playout cursor
                    return
                }
            }
        }

        // Convert keys cleanly around 16-bit sequence wrap using relative offset when sorting
        queue[seq] = packet.copy(seq = seq)
        while (queue.size > maxCapacityFrames) {
            val oldest = findOldestSeq() ?: break
            queue.remove(oldest)
            if (signedSeqDiff(oldest, nextSeq) >= 0) {
                nextSeq = (oldest + 1) and 0xFFFF
                totalLost++
            }
        }
    }

    fun popNext(): PopResult = synchronized(lock) {
        if (nextSeq < 0) return PopResult.Wait

        if (!primed) {
            if (queue.size < targetPrefill) {
                return PopResult.Wait
            }
            primed = true
            findOldestSeq()?.let { oldest -> nextSeq = oldest }
        }

        // Drain surplus backlog cleanly if latency grew too large (keep playout tight)
        while (queue.size > (targetPrefill + 4).coerceAtMost(maxCapacityFrames)) {
            val oldest = findOldestSeq() ?: break
            queue.remove(oldest)
            nextSeq = (oldest + 1) and 0xFFFF
        }

        val match = queue.remove(nextSeq)
        if (match != null) {
            nextSeq = (nextSeq + 1) and 0xFFFF
            consecutiveConceals = 0
            consecutiveGood++
            if (consecutiveGood >= 150 && targetPrefill > minPrefillFrames) {
                targetPrefill--
                consecutiveGood = 0
            }
            return PopResult.Frame(match)
        }

        // Packet missing at nextSeq
        if (queue.isEmpty()) {
            consecutiveConceals++
            consecutiveGood = 0
            if (consecutiveConceals >= 3) {
                primed = false
                targetPrefill = (targetPrefill + 1).coerceAtMost(6)
                return PopResult.Wait
            }
            return PopResult.Conceal
        }

        // A future packet already arrived, so nextSeq was dropped or skipped
        totalLost++
        nextSeq = (nextSeq + 1) and 0xFFFF
        consecutiveConceals++
        consecutiveGood = 0
        if (consecutiveConceals >= 3) {
            // Jump playout cursor straight to next queued packet
            findOldestSeq()?.let { nextSeq = it }
            val jump = queue.remove(nextSeq)
            if (jump != null) {
                nextSeq = (nextSeq + 1) and 0xFFFF
                consecutiveConceals = 0
                return PopResult.Frame(jump)
            }
        }
        return PopResult.Conceal
    }

    private fun findOldestSeq(): Int? {
        val base = nextSeq.coerceAtLeast(0)
        var bestKey: Int? = null
        var bestDelta = Int.MAX_VALUE
        for (key in queue.keys) {
            val d = signedSeqDiff(key, base)
            if (d < bestDelta) {
                bestDelta = d
                bestKey = key
            }
        }
        return bestKey
    }

    private fun signedSeqDiff(a: Int, b: Int): Int {
        val d = (a - b) and 0xFFFF
        return if (d > 32768) d - 65536 else d
    }
}

/**
 * Simple PJMEDIA-style packet loss concealment fading out the last decoded
 * frame over 120 ms (6 x 20 ms frames) so brief UDP drops never click.
 */
internal class PcmConcealer(private val sampleRate: Int) {
    private val frameSamples = sampleRate / 50
    private var lastFrame = ShortArray(frameSamples)
    private var concealCount = 0

    fun recordGood(pcm: ShortArray, length: Int = pcm.size) {
        val copyLen = minOf(length, frameSamples, pcm.size)
        System.arraycopy(pcm, 0, lastFrame, 0, copyLen)
        if (copyLen < frameSamples) {
            lastFrame.fill(0, copyLen, frameSamples)
        }
        concealCount = 0
    }

    fun conceal(length: Int = frameSamples): ShortArray {
        val outLen = minOf(length, frameSamples)
        concealCount++
        if (concealCount > 6) {
            return ShortArray(outLen)
        }
        val attenuation = (1.0f - concealCount * 0.16f).coerceAtLeast(0f)
        return ShortArray(outLen) { idx ->
            (lastFrame[idx] * attenuation).roundToInt().toShort()
        }
    }
}

/**
 * Anti-aliased Windowed-Sinc / Cubic PCM resampler supporting arbitrary sample-rate
 * conversions (`48000 <-> 16000`, `48000 <-> 8000`, `44100 <-> 16000`, `16000 <-> 8000`).
 *
 * Crucially applies a Hamming-windowed sinc low-pass kernel when downsampling so
 * high-frequency energy does not alias back into the speech band as metallic or
 * robotic distortion.
 */
internal object PcmResampler {
    private const val SINC_HALF_TAPS = 8

    fun resample(input: ShortArray, srcRate: Int, dstRate: Int, length: Int = input.size): ShortArray {
        val count = minOf(length, input.size)
        if (count <= 0 || srcRate <= 0 || dstRate <= 0) return ShortArray(0)
        if (srcRate == dstRate) return input.copyOf(count)

        val outLen = ((count.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val cutoff = minOf(0.92, ratio * 0.92)
        val step = srcRate.toDouble() / dstRate.toDouble()

        if (dstRate < srcRate) {
            // Downsampling with anti-aliasing Blackman-windowed sinc kernel
            val support = (10.0 / cutoff).toInt().coerceAtLeast(SINC_HALF_TAPS)
            for (i in 0 until outLen) {
                val center = i * step
                val base = center.toInt()
                var sum = 0.0
                var weightSum = 0.0
                for (j in (base - support)..(base + support)) {
                    val dist = j - center
                    val norm = dist / support
                    if (kotlin.math.abs(norm) >= 1.0) continue
                    val win = 0.42 + 0.50 * cos(PI * norm) + 0.08 * cos(2.0 * PI * norm)
                    val x = cutoff * dist
                    val sinc = if (kotlin.math.abs(x) < 1e-6) 1.0 else sin(PI * x) / (PI * x)
                    val w = sinc * win
                    val sampleVal = input[j.coerceIn(0, count - 1)].toDouble()
                    sum += sampleVal * w
                    weightSum += w
                }
                val sample = if (weightSum > 1e-6) sum / weightSum else input[base.coerceIn(0, count - 1)].toDouble()
                out[i] = sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        } else {
            // Upsampling with Catmull-Rom cubic interpolation for smooth waveform reconstruction
            for (i in 0 until outLen) {
                val pos = i * step
                val i1 = pos.toInt().coerceIn(0, count - 1)
                val t = pos - i1
                val p0 = input[(i1 - 1).coerceAtLeast(0)].toDouble()
                val p1 = input[i1].toDouble()
                val p2 = input[(i1 + 1).coerceAtMost(count - 1)].toDouble()
                val p3 = input[(i1 + 2).coerceAtMost(count - 1)].toDouble()
                val t2 = t * t
                val t3 = t2 * t
                val valInterp = 0.5 * (
                    (2.0 * p1) +
                        (-p0 + p2) * t +
                        (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                        (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
                    )
                out[i] = valInterp.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return out
    }
}
