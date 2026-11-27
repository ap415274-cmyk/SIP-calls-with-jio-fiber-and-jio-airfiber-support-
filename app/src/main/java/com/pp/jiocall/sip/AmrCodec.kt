package com.pp.jiocall.sip

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object CodecSupport {
    private const val TAG = "CodecSupport"

    val hasAmrWb: Boolean by lazy {
        findCodecInfo(Codec.AMR_WB.androidMime, encoder = true) != null &&
            findCodecInfo(Codec.AMR_WB.androidMime, encoder = false) != null
    }
    val hasAmrNb: Boolean by lazy {
        findCodecInfo(Codec.AMR_NB.androidMime, encoder = true) != null &&
            findCodecInfo(Codec.AMR_NB.androidMime, encoder = false) != null
    }

    fun isSupported(codec: Codec): Boolean = when (codec) {
        Codec.AMR_WB -> hasAmrWb
        Codec.AMR_NB -> hasAmrNb
        Codec.PCMA, Codec.PCMU -> true
    }

    fun supported(): List<Codec> = Codec.entries.filter(::isSupported)

    fun offer(preferred: List<String>): List<Codec> {
        val selected = preferred.mapNotNull { Codec.fromSetting(it) }
            .ifEmpty { listOf(Codec.AMR_WB, Codec.AMR_NB, Codec.PCMA, Codec.PCMU) }
        val avail = selected.filter(::isSupported).toMutableList()
        if (Codec.PCMA !in avail) avail += Codec.PCMA
        if (Codec.PCMU !in avail) avail += Codec.PCMU
        return avail.distinct()
    }

    /**
     * Selects deterministic Google Codec2 / OMX software AMR codecs first so flaky
     * vendor hardware AMR implementations cannot distort or corrupt RTP frames.
     */
    internal fun createPreferredCodec(mime: String, encoder: Boolean): MediaCodec {
        val preferred = findCodecInfo(mime, encoder)
        if (preferred != null) {
            return runCatching {
                MediaCodec.createByCodecName(preferred.name).also {
                    Log.d(TAG, "Selected preferred ${if (encoder) "encoder" else "decoder"}: ${preferred.name} for $mime")
                }
            }.getOrElse {
                if (encoder) MediaCodec.createEncoderByType(mime) else MediaCodec.createDecoderByType(mime)
            }
        }
        return if (encoder) MediaCodec.createEncoderByType(mime) else MediaCodec.createDecoderByType(mime)
    }

    internal fun findCodecInfo(mime: String, encoder: Boolean): MediaCodecInfo? {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull() ?: return null
        val matching = infos.filter { info ->
            info.isEncoder == encoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        if (matching.isEmpty()) return null
        return matching.firstOrNull { isGoogleSoftwareCodec(it) }
            ?: matching.firstOrNull { !isHardwareVendor(it) }
            ?: matching.first()
    }

    private fun isGoogleSoftwareCodec(info: MediaCodecInfo): Boolean {
        val lower = info.name.lowercase()
        return lower.startsWith("c2.android.") || lower.startsWith("omx.google.")
    }

    private fun isHardwareVendor(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isVendor && info.isHardwareAccelerated
        }
        val lower = info.name.lowercase()
        return !lower.startsWith("c2.android.") && !lower.startsWith("omx.google.")
    }
}

/** Fast ITU G.711 A-law / mu-law PCM16 encoder & decoder tables. */
internal object G711Codec {
    private val ALAW_DECODE = ShortArray(256) { i ->
        val inverted = i xor 0x55
        var t = (inverted and 0x0F) shl 4
        val seg = (inverted and 0x70) ushr 4
        t = when (seg) {
            0 -> t + 8
            1 -> t + 0x108
            else -> (t + 0x108) shl (seg - 1)
        }
        (if ((inverted and 0x80) != 0) t else -t).toShort()
    }

    private val ULAW_DECODE = ShortArray(256) { i ->
        val inverted = i.inv() and 0xFF
        val sign = inverted and 0x80
        val exponent = (inverted ushr 4) and 0x07
        val mantissa = inverted and 0x0F
        var sample = ((mantissa shl 3) + 0x84) shl exponent
        sample -= 0x84
        (if (sign != 0) -sample else sample).toShort()
    }

    fun encodeAlaw(pcm: ShortArray, out: ByteArray, samples: Int = minOf(pcm.size, out.size)) {
        for (i in 0 until samples) {
            out[i] = linearToAlaw(pcm[i].toInt())
        }
    }

    fun decodeAlaw(data: ByteArray, offset: Int, length: Int): ShortArray =
        ShortArray(length) { idx -> ALAW_DECODE[data[offset + idx].toInt() and 0xFF] }

    fun encodeUlaw(pcm: ShortArray, out: ByteArray, samples: Int = minOf(pcm.size, out.size)) {
        for (i in 0 until samples) {
            out[i] = linearToUlaw(pcm[i].toInt())
        }
    }

    fun decodeUlaw(data: ByteArray, offset: Int, length: Int): ShortArray =
        ShortArray(length) { idx -> ULAW_DECODE[data[offset + idx].toInt() and 0xFF] }

    private fun linearToAlaw(sample: Int): Byte {
        var pcm = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val mask: Int
        if (pcm >= 0) {
            mask = 0xD5
        } else {
            mask = 0x55
            pcm = (-pcm - 1).coerceAtLeast(0)
        }
        val aval = if (pcm >= 256) {
            var seg = 1
            var shifted = pcm ushr 5
            while (seg < 7 && shifted >= 32) {
                shifted = shifted ushr 1
                seg++
            }
            (seg shl 4) or ((pcm ushr (seg + 3)) and 0x0F)
        } else {
            pcm ushr 4
        }
        return (aval xor mask).toByte()
    }

    private fun linearToUlaw(sample: Int): Byte {
        var pcm = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val sign = if (pcm < 0) {
            pcm = (-pcm).coerceAtMost(Short.MAX_VALUE.toInt())
            0x80
        } else {
            0
        }
        pcm = (pcm + 0x84).coerceAtMost(0x7FFF)
        var exponent = 7
        var expMask = 0x4000
        while ((pcm and expMask) == 0 && exponent > 0) {
            exponent--
            expMask = expMask ushr 1
        }
        val mantissa = (pcm ushr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }
}

/**
 * Synchronous single-frame AMR-WB / AMR-NB `MediaCodec` encoder.
 *
 * Uses strict 20ms frame chunks (`frameSamples * 2` bytes) and monotonic
 * `20_000 us` presentation timestamps so Codec2 (`C2SoftAmrWbEnc` /
 * `C2SoftAmrNbEnc`) never drops, merges, or mis-times frames.
 */
internal class AmrEncoder(
    private val codec: Codec,
    modeSet: Set<Int>? = null,
) {
    private val lock = Any()
    private val allowedModes: Set<Int>? = modeSet?.filter { it in validModes(codec) }?.toSet()?.takeIf { it.isNotEmpty() }
    private var activeMode: Int = pickInitialMode(codec, allowedModes)
    private var mediaCodec: MediaCodec? = null
    private var presentationUs: Long = 0L
    private val pendingFrames = ArrayDeque<ByteArray>()
    private val scratchBytes = ByteArray(codec.samplesPerFrame * 2)

    init {
        openCodec(bitrateForMode(codec, activeMode))
    }

    private fun openCodec(bitrate: Int) {
        val candidates = candidateBitrates(codec, bitrate, allowedModes)
        var lastError: Exception? = null
        val androidMime = codec.androidMime
        for (rate in candidates) {
            val instance = runCatching {
                val mc = CodecSupport.createPreferredCodec(androidMime, encoder = true)
                val fmt = MediaFormat.createAudioFormat(androidMime, codec.clockRate, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, rate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, codec.samplesPerFrame * 4)
                }
                mc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mc.start()
                mc
            }.getOrElse {
                lastError = it as? Exception
                Log.w(TAG, "Encoder config $androidMime @${rate}bps failed: ${it.message}")
                null
            }
            if (instance != null) {
                mediaCodec = instance
                activeMode = modeForBitrate(codec, rate)
                Log.i(TAG, "Started $androidMime encoder mode=$activeMode bitrate=$rate allowed=${allowedModes ?: "any"}")
                return
            }
        }
        throw IllegalStateException("Could not initialize $androidMime encoder", lastError)
    }

    /** Honors remote CMR mode changes (0..8 for WB, 0..7 for NB) within SDP mode-set. */
    fun adaptToCmr(cmr: Int) {
        val valid = validModes(codec)
        if (cmr !in valid) return
        val targetMode = if (allowedModes != null) {
            if (cmr in allowedModes) cmr else allowedModes.filter { it <= cmr }.maxOrNull() ?: return
        } else {
            cmr
        }
        if (targetMode == activeMode) return
        synchronized(lock) {
            if (targetMode == activeMode) return
            val targetRate = bitrateForMode(codec, targetMode)
            runCatching {
                mediaCodec?.stop()
                mediaCodec?.release()
            }
            mediaCodec = null
            pendingFrames.clear()
            presentationUs = 0L
            runCatching {
                openCodec(targetRate)
                Log.i(TAG, "Adapted ${codec.mime} encoder to remote CMR=$cmr -> mode=$activeMode (${targetRate}bps)")
            }.onFailure {
                Log.w(TAG, "Failed adapting to CMR=$cmr, restoring default: ${it.message}")
                openCodec(bitrateForMode(codec, pickInitialMode(codec, allowedModes)))
            }
        }
    }

    fun encode(pcm: ShortArray): ByteArray? = encodeFrames(pcm).firstOrNull()

    fun encodeFrames(pcm: ShortArray): List<ByteArray> = synchronized(lock) {
        val mc = mediaCodec ?: return@synchronized emptyList()
        if (pcm.size != codec.samplesPerFrame) return@synchronized emptyList()

        // Always feed the incoming 20ms frame so real-time voice is never dropped
        for (i in 0 until codec.samplesPerFrame) {
            val s = pcm[i].toInt()
            scratchBytes[i shl 1] = (s and 0xFF).toByte()
            scratchBytes[(i shl 1) + 1] = ((s ushr 8) and 0xFF).toByte()
        }

        val inIdx = mc.dequeueInputBuffer(20_000L)
        if (inIdx >= 0) {
            val inBuf = mc.getInputBuffer(inIdx)
            if (inBuf != null) {
                inBuf.clear()
                inBuf.put(scratchBytes)
                mc.queueInputBuffer(inIdx, 0, scratchBytes.size, presentationUs, 0)
                presentationUs += 20_000L
            }
        } else {
            Log.w(TAG, "Input buffer unavailable for ${codec.androidMime}")
        }

        val isWideband = codec == Codec.AMR_WB
        val info = MediaCodec.BufferInfo()
        var waitUs = 15_000L
        var retries = 0
        while (retries < 16) {
            retries++
            val outIdx = mc.dequeueOutputBuffer(info, waitUs)
            when {
                outIdx >= 0 -> {
                    val outBuf = mc.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val raw = ByteArray(info.size)
                        outBuf.get(raw)
                        pendingFrames.addAll(AmrFraming.splitStorageFrames(raw, isWideband))
                    }
                    mc.releaseOutputBuffer(outIdx, false)
                    waitUs = 0L
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> break
            }
        }
        if (pendingFrames.isEmpty()) return@synchronized emptyList()
        val drained = ArrayList<ByteArray>(pendingFrames.size)
        while (pendingFrames.isNotEmpty()) {
            drained.add(pendingFrames.removeFirst())
        }
        return@synchronized drained
    }

    fun release() = synchronized(lock) {
        pendingFrames.clear()
        runCatching {
            mediaCodec?.stop()
            mediaCodec?.release()
        }
        mediaCodec = null
    }

    companion object {
        private const val TAG = "AmrEncoder"

        private val WB_BITRATES = intArrayOf(6600, 8850, 12650, 14250, 15850, 18250, 19850, 23050, 23850)
        private val NB_BITRATES = intArrayOf(4750, 5150, 5900, 6700, 7400, 7950, 10200, 12200)

        fun validModes(codec: Codec): IntRange =
            if (codec == Codec.AMR_WB) 0..8 else 0..7

        fun bitrateForMode(codec: Codec, mode: Int): Int {
            val tbl = if (codec == Codec.AMR_WB) WB_BITRATES else NB_BITRATES
            return tbl[mode.coerceIn(0, tbl.size - 1)]
        }

        fun modeForBitrate(codec: Codec, bitrate: Int): Int {
            val tbl = if (codec == Codec.AMR_WB) WB_BITRATES else NB_BITRATES
            var bestIdx = 0
            var bestDiff = Int.MAX_VALUE
            for (i in tbl.indices) {
                val diff = kotlin.math.abs(tbl[i] - bitrate)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestIdx = i
                }
            }
            return bestIdx
        }

        /**
         * Matches PJSIP/Baresip mode selection: when `mode-set` is unrestricted,
         * use 23.85 kbps (or 12.65/12.2 kbps cleanly); when restricted, strictly
         * pick the highest negotiated mode inside `mode-set`.
         */
        fun pickInitialMode(codec: Codec, allowed: Set<Int>?): Int {
            if (allowed != null && allowed.isNotEmpty()) {
                return allowed.max()
            }
            return if (codec == Codec.AMR_WB) 8 else 7
        }

        private fun candidateBitrates(codec: Codec, preferred: Int, allowed: Set<Int>?): List<Int> {
            val tbl = if (codec == Codec.AMR_WB) WB_BITRATES else NB_BITRATES
            val list = LinkedHashSet<Int>()
            list.add(preferred)
            if (allowed != null && allowed.isNotEmpty()) {
                for (m in allowed.sortedDescending()) {
                    list.add(bitrateForMode(codec, m))
                }
            } else {
                list.add(if (codec == Codec.AMR_WB) 12650 else 12200)
                list.add(if (codec == Codec.AMR_WB) 23850 else 7950)
                for (i in tbl.indices.reversed()) {
                    list.add(tbl[i])
                }
            }
            return list.toList()
        }
    }
}

/**
 * Synchronous single-frame AMR-WB / AMR-NB `MediaCodec` decoder.
 */
internal class AmrDecoder(private val codec: Codec) {
    private val lock = Any()
    private val mc: MediaCodec = CodecSupport.createPreferredCodec(codec.androidMime, encoder = false).also {
        val fmt = MediaFormat.createAudioFormat(codec.androidMime, codec.clockRate, 1).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 128)
        }
        it.configure(fmt, null, null, 0)
        it.start()
        Log.i(TAG, "Started ${codec.androidMime} decoder @${codec.clockRate}Hz")
    }
    private var presentationUs: Long = 0L

    fun decode(frame: ByteArray): ShortArray? = synchronized(lock) {
        if (frame.isEmpty()) return@synchronized null

        val inIdx = mc.dequeueInputBuffer(10_000L)
        if (inIdx < 0) {
            Log.w(TAG, "Input buffer unavailable for ${codec.mime}")
            return@synchronized null
        }
        val inBuf = mc.getInputBuffer(inIdx) ?: return@synchronized null
        inBuf.clear()
        inBuf.put(frame)
        mc.queueInputBuffer(inIdx, 0, frame.size, presentationUs, 0)
        presentationUs += 20_000L

        val info = MediaCodec.BufferInfo()
        var waitUs = 15_000L
        var decoded: ShortArray? = null
        var retries = 0
        while (retries < 16) {
            retries++
            val outIdx = mc.dequeueOutputBuffer(info, waitUs)
            when {
                outIdx >= 0 -> {
                    val outBuf: ByteBuffer? = mc.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val count = info.size / 2
                        val pcm = ShortArray(count)
                        outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                        decoded = pcm
                    }
                    mc.releaseOutputBuffer(outIdx, false)
                    if (decoded != null) return@synchronized decoded
                    waitUs = 0L
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> break
            }
        }
        return@synchronized decoded
    }

    fun release() = synchronized(lock) {
        runCatching {
            mc.stop()
            mc.release()
        }
    }

    companion object {
        private const val TAG = "AmrDecoder"
    }
}
