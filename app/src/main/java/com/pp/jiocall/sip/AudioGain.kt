package com.pp.jiocall.sip

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Clean, unity-first PCM level conditioning for telephony audio.
 *
 * Unlike aggressive dynamic noise gates or high-ratio compressors that chop
 * soft speech and inject metallic/robotic distortion into CELP codecs (AMR-WB /
 * AMR-NB), this helper preserves natural waveforms:
 * - normal speech at 1.0x passes completely untouched,
 * - soft-knee peak limiting only engages above -3 dBFS (~24,000), preventing
 *   hard-clipping splattering in AMR LPC filters,
 * - optional 1-pole DC blocker removes sub-sonic mic offset without altering speech.
 */
internal object AudioGain {
    const val MIC = 1.0f
    const val EARPIECE = 1.0f
    const val SPEAKER = 1.35f

    private const val SOFT_KNEE_START = 24_000f
    private const val SOFT_KNEE_HEADROOM = 32_767f - SOFT_KNEE_START

    fun apply(pcm: ShortArray, length: Int, gain: Float) {
        if (gain == 1.0f) return
        val count = minOf(length, pcm.size)
        for (i in 0 until count) {
            val scaled = pcm[i].toFloat() * gain
            pcm[i] = softLimitSample(scaled)
        }
    }

    /** Soft-knee ceiling limiter above [SOFT_KNEE_START] to avoid hard clipping. */
    fun softLimitSample(value: Float): Short {
        val absVal = abs(value)
        if (absVal <= SOFT_KNEE_START) {
            return value.roundToInt().toShort()
        }
        val excess = (absVal - SOFT_KNEE_START) / SOFT_KNEE_HEADROOM
        val compressed = SOFT_KNEE_START + SOFT_KNEE_HEADROOM * tanh(excess.toDouble()).toFloat()
        val signed = if (value >= 0f) compressed else -compressed
        return signed.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /** Computes peak absolute amplitude across [length] samples. */
    fun peakAmplitude(pcm: ShortArray, length: Int = pcm.size): Int {
        val count = minOf(length, pcm.size)
        var peak = 0
        for (i in 0 until count) {
            val s = pcm[i].toInt()
            val a = if (s == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else abs(s)
            if (a > peak) peak = a
        }
        return peak
    }

    /** Computes RMS level in dBFS (-96.0 for digital silence, 0.0 for full-scale). */
    fun rmsDbfs(pcm: ShortArray, length: Int = pcm.size): Float {
        val count = minOf(length, pcm.size)
        if (count <= 0) return -96.0f
        var sumSq = 0.0
        for (i in 0 until count) {
            val x = pcm[i].toDouble() / 32768.0
            sumSq += x * x
        }
        val rms = sqrt(sumSq / count)
        return if (rms <= 1e-5) -96.0f else (20.0 * log10(rms)).toFloat().coerceIn(-96.0f, 0.0f)
    }

    /**
     * Gentle 1-pole DC-blocking high-pass filter (`fc ~= 25 Hz` @ 16 kHz)
     * removing microphone DC bias and electrical thumps without coloring speech.
     */
    class DcBlocker {
        private var prevX = 0f
        private var prevY = 0f

        fun process(pcm: ShortArray, length: Int = pcm.size) {
            val count = minOf(length, pcm.size)
            var x1 = prevX
            var y1 = prevY
            for (i in 0 until count) {
                val x = pcm[i].toFloat()
                val y = (x - x1) + 0.992f * y1
                x1 = x
                y1 = y
                pcm[i] = softLimitSample(y)
            }
            prevX = x1
            prevY = y1
        }

        fun reset() {
            prevX = 0f
            prevY = 0f
        }
    }

    /**
     * Gentle upward TX trim for faint microphones without any noise gate or
     * heavy pumping. Normal speech passes near 1.0x; consistently faint mics
     * lift smoothly up to [maxGain], immediately ducking below clipping.
     */
    class AdaptiveTxTrim(
        private val maxGain: Float = 2.2f,
    ) {
        private var gain = 1.0f

        fun currentGain(): Float = gain

        fun process(pcm: ShortArray, length: Int = pcm.size) {
            val count = minOf(length, pcm.size)
            if (count <= 0) return

            val peak = peakAmplitude(pcm, count)
            if (peak <= 0) return

            val rms = rmsDbfs(pcm, count)
            val desiredGain = when {
                peak * gain > 28_000f -> (22_000f / peak).coerceIn(0.65f, 1.0f)
                rms in -50.0f..-34.0f -> 1.8f.coerceAtMost(maxGain)
                rms > -34.0f && gain > 1.15f -> 1.0f
                else -> gain
            }
            val alpha = if (desiredGain < gain) 0.35f else 0.05f
            gain = (gain + (desiredGain - gain) * alpha).coerceIn(0.65f, maxGain)

            if (abs(gain - 1.0f) > 0.02f) {
                apply(pcm, count, gain)
            }
        }

        fun reset() {
            gain = 1.0f
        }
    }
}
