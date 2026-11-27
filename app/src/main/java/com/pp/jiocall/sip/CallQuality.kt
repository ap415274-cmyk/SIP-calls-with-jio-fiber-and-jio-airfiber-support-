package com.pp.jiocall.sip

import kotlin.math.ln

/**
 * Call quality statistics sampled during an active RTP audio stream.
 * Direct port of RusTone's `sip_engine::quality`.
 */
data class CallQuality(
    val codec: String,
    val clockRate: Int,
    val isHdVoice: Boolean,
    val mos: Float,
    val packetsRx: Long,
    val packetsTx: Long,
    val lossPercent: Float,
) {
    val rating: String get() = when {
        mos <= 2.0f -> "Poor"
        mos <= 3.0f -> "Fair"
        else -> "Good"
    }

    val displayLabel: String get() {
        val codecLabel = if (isHdVoice) "HD Voice • $codec" else "$codec@${clockRate / 1000}kHz"
        return "$rating · $codecLabel"
    }
}

/**
 * Estimates MOS (Mean Opinion Score, 1.0 to 4.5) using the ITU-T E-model simplification
 * from RusTone.
 */
object QualityEstimator {
    // Local audio pipeline latency estimate (ms)
    private const val LOCAL_DELAY_MS = 30.0f + 140.0f + 100.0f

    fun calculateMos(packets: Long, lost: Long, jitterMs: Float = 10.0f, rttMs: Float = 20.0f): Float {
        val total = packets + lost
        val loss = if (total == 0L) 1.0f else (lost.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val delay = rttMs / 2.0f + LOCAL_DELAY_MS + jitterMs
        val id = 0.024f * delay + if (delay >= 177.3f) 0.11f * (delay - 177.3f) else 0.0f
        val ie = 19.8f * ln(1.0f + 29.7f * loss)
        val r = 94.2f - id - ie
        return when {
            r < 0.0f -> 1.0f
            r > 100.0f -> 4.5f
            else -> 1.0f + 0.035f * r + 7.1e-6f * r * (r - 60.0f) * (100.0f - r)
        }.coerceIn(1.0f, 4.5f)
    }
}
