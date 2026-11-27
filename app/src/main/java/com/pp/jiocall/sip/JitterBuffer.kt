package com.pp.jiocall.sip

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
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

    /**
     * Sheds excess buffered packets when playout falls behind wall clock so
     * emulator/CPU stalls recover immediately instead of playing seconds late.
     */
    fun trimBacklog(maxKeepFrames: Int = 2) = synchronized(lock) {
        if (queue.size <= maxKeepFrames) return
        while (queue.size > maxKeepFrames) {
            val oldest = findOldestSeq() ?: break
            queue.remove(oldest)
            nextSeq = (oldest + 1) and 0xFFFF
        }
        primed = queue.isNotEmpty()
        consecutiveConceals = 0
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
 * Anti-aliased precomputed polyphase Blackman-Sinc / Catmull-Rom PCM resampler
 * supporting arbitrary sample-rate conversions (`48000 <-> 16000`, `48000 <-> 8000`,
 * `44100 <-> 16000`, `16000 <-> 8000`).
 *
 * Precomputes each downsampling FIR kernel once per `(srcRate, dstRate)` pair so
 * the 20 ms real-time audio loop performs zero `sin`/`cos` calls.
 */
internal object PcmResampler {
    private const val SINC_HALF_TAPS = 8
    private const val MAX_HALF_TAPS = 65
    private const val FRACTIONAL_PHASES = 128

    private class DownsampleKernel(
        val support: Int,
        val taps: Int,
        val phases: Int,
        val exactIntStep: Int,
        val coeffs: FloatArray,
    )

    private val kernelCache = ConcurrentHashMap<Long, DownsampleKernel>()

    private fun kernelFor(srcRate: Int, dstRate: Int): DownsampleKernel {
        val key = (srcRate.toLong() shl 32) or (dstRate.toLong() and 0xFFFF_FFFFL)
        return kernelCache.getOrPut(key) { buildKernel(srcRate, dstRate) }
    }

    private fun buildKernel(srcRate: Int, dstRate: Int): DownsampleKernel {
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val cutoff = minOf(0.92, ratio * 0.92)
        val support = (10.0 / cutoff).toInt().coerceIn(SINC_HALF_TAPS, MAX_HALF_TAPS)
        val taps = support * 2 + 1
        val exactIntStep = if (srcRate % dstRate == 0) srcRate / dstRate else 0
        val phases = if (exactIntStep > 0) 1 else FRACTIONAL_PHASES
        val coeffs = FloatArray(phases * taps)

        for (p in 0 until phases) {
            val frac = if (phases == 1) 0.0 else p.toDouble() / phases.toDouble()
            val rowOff = p * taps
            var weightSum = 0.0
            for (k in -support..support) {
                val dist = k.toDouble() - frac
                val norm = dist / support
                if (abs(norm) >= 1.0) continue
                val win = 0.42 + 0.50 * cos(PI * norm) + 0.08 * cos(2.0 * PI * norm)
                val x = cutoff * dist
                val sinc = if (abs(x) < 1e-6) 1.0 else sin(PI * x) / (PI * x)
                val w = sinc * win
                coeffs[rowOff + k + support] = w.toFloat()
                weightSum += w
            }
            if (weightSum > 1e-6) {
                val inv = (1.0 / weightSum).toFloat()
                for (t in 0 until taps) {
                    coeffs[rowOff + t] *= inv
                }
            } else {
                coeffs[rowOff + support] = 1.0f
            }
        }
        return DownsampleKernel(support, taps, phases, exactIntStep, coeffs)
    }

    fun resample(input: ShortArray, srcRate: Int, dstRate: Int, length: Int = input.size): ShortArray {
        val count = minOf(length, input.size)
        if (count <= 0 || srcRate <= 0 || dstRate <= 0) return ShortArray(0)
        if (srcRate == dstRate) return input.copyOf(count)

        val outLen = ((count.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        resampleInto(input, srcRate, dstRate, count, out)
        return out
    }

    fun resampleInto(
        input: ShortArray,
        srcRate: Int,
        dstRate: Int,
        length: Int,
        out: ShortArray,
    ): Int {
        val count = minOf(length, input.size)
        if (count <= 0 || srcRate <= 0 || dstRate <= 0) return 0
        if (srcRate == dstRate) {
            val copyLen = minOf(count, out.size)
            System.arraycopy(input, 0, out, 0, copyLen)
            return copyLen
        }

        val outLen = ((count.toLong() * dstRate) / srcRate).toInt().coerceIn(1, out.size)

        if (dstRate < srcRate) {
            val kernel = kernelFor(srcRate, dstRate)
            val support = kernel.support
            val taps = kernel.taps
            val coeffs = kernel.coeffs
            val intStep = kernel.exactIntStep
            val step = srcRate.toDouble() / dstRate.toDouble()
            val maxInIdx = count - 1

            for (i in 0 until outLen) {
                val base: Int
                val rowOff: Int
                if (intStep > 0) {
                    base = i * intStep
                    rowOff = 0
                } else {
                    val pos = i * step
                    base = pos.toInt()
                    val phase = ((pos - base) * kernel.phases).toInt().coerceIn(0, kernel.phases - 1)
                    rowOff = phase * taps
                }

                var sum = 0.0f
                val startIn = base - support
                if (startIn >= 0 && base + support <= maxInIdx) {
                    for (t in 0 until taps) {
                        sum += input[startIn + t].toFloat() * coeffs[rowOff + t]
                    }
                } else {
                    for (t in 0 until taps) {
                        val idx = (startIn + t).coerceIn(0, maxInIdx)
                        sum += input[idx].toFloat() * coeffs[rowOff + t]
                    }
                }
                out[i] = sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        } else {
            val step = srcRate.toFloat() / dstRate.toFloat()
            val maxInIdx = count - 1
            for (i in 0 until outLen) {
                val pos = i * step
                val i1 = pos.toInt().coerceIn(0, maxInIdx)
                val t = pos - i1
                val p0 = input[(i1 - 1).coerceAtLeast(0)].toFloat()
                val p1 = input[i1].toFloat()
                val p2 = input[(i1 + 1).coerceAtMost(maxInIdx)].toFloat()
                val p3 = input[(i1 + 2).coerceAtMost(maxInIdx)].toFloat()
                val t2 = t * t
                val t3 = t2 * t
                val valInterp = 0.5f * (
                    (2.0f * p1) +
                        (-p0 + p2) * t +
                        (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 +
                        (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3
                    )
                out[i] = valInterp.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return outLen
    }
}
