package com.pp.jiocall.sip

/**
 * RFC 4867 AMR-WB & AMR-NB framing and storage-format parser/packer verified
 * against PJSIP `amr_helper.h`, Baresip `amr.c`, and 3GPP TS 26.101 / TS 26.201.
 *
 * Both PJSIP (`reorder=0`) and Baresip use RFC 3267/4867 Class-sorted bit order
 * natively (`ToC byte [F|FT|Q|00] + speech octets`), which matches Android's
 * `MediaCodec` (`c2.android.amrwb.*` / `c2.android.amrnb.*`).
 */
internal object AmrFraming {

    // Exact speech bit counts per FT (3GPP TS 26.201 / TS 26.101, pj_amr_helper.h)
    private val WB_SPEECH_BITS = intArrayOf(132, 177, 253, 285, 317, 365, 397, 461, 477, 40)
    private val NB_SPEECH_BITS = intArrayOf(95, 103, 118, 134, 148, 159, 204, 244, 39)

    // Total storage frame size including 1-byte ToC header: 1 + ceil(bits / 8)
    // Verified for WB FT=3 (285 bits): ceil(285/8) = 36 speech bytes -> 37 bytes total.
    private val WB_OA_FRAME_BYTES = intArrayOf(18, 24, 33, 37, 41, 47, 51, 59, 61, 6)
    private val NB_OA_FRAME_BYTES = intArrayOf(13, 14, 16, 18, 20, 21, 27, 32, 6)

    private val STORAGE_HDR_WB = "#!AMR-WB\n".toByteArray(Charsets.US_ASCII)
    private val STORAGE_HDR_NB = "#!AMR\n".toByteArray(Charsets.US_ASCII)

    data class UnpackResult(
        val frames: List<ByteArray>,
        val cmr: Int,
        val wasOctetAligned: Boolean,
    )

    fun sidFrameType(isWideband: Boolean): Int = if (isWideband) 9 else 8

    fun speechBitsForFt(ft: Int, isWideband: Boolean): Int {
        val tbl = if (isWideband) WB_SPEECH_BITS else NB_SPEECH_BITS
        return if (ft in tbl.indices) tbl[ft] else 0
    }

    fun storageFrameLengthForFt(ft: Int, isWideband: Boolean): Int {
        if (ft == 14 || ft == 15) return 1
        val tbl = if (isWideband) WB_OA_FRAME_BYTES else NB_OA_FRAME_BYTES
        return if (ft in tbl.indices) tbl[ft] else 0
    }

    fun speechBytesForFt(ft: Int, isWideband: Boolean): Int {
        val total = storageFrameLengthForFt(ft, isWideband)
        return if (total > 0) total - 1 else 0
    }

    fun isValidFt(ft: Int, isWideband: Boolean): Boolean =
        ft <= sidFrameType(isWideband) || ft == 14 || ft == 15

    /**
     * Splits a MediaCodec storage buffer (`#!AMR-WB\n` / `#!AMR\n` optional,
     * possibly containing multiple concatenated `[ToC][speech]` frames) into
     * clean single-frame byte arrays.
     */
    fun splitStorageFrames(raw: ByteArray, isWideband: Boolean): List<ByteArray> {
        if (raw.isEmpty()) return emptyList()
        var offset = 0
        val hdr = if (isWideband) STORAGE_HDR_WB else STORAGE_HDR_NB
        if (hasHeader(raw, hdr)) {
            offset = hdr.size
        }
        val out = ArrayList<ByteArray>(2)
        while (offset < raw.size) {
            val toc = raw[offset].toInt() and 0xFF
            val ft = (toc ushr 3) and 0x0F
            val q = (toc ushr 2) and 0x01
            val frameLen = storageFrameLengthForFt(ft, isWideband)
            if (frameLen <= 0 || offset + frameLen > raw.size) {
                break
            }
            val frame = raw.copyOfRange(offset, offset + frameLen)
            // Normalize storage header: F=0, FT, Q preserved, trailing bits 00
            frame[0] = (((ft and 0x0F) shl 3) or (q shl 2)).toByte()
            out.add(frame)
            offset += frameLen
        }
        return out
    }

    /** Packs one storage frame (`[ToC][speech...]`) into RFC 4867 Octet-Aligned RTP payload. */
    fun toOctetAligned(frame: ByteArray, cmr: Int = 15): ByteArray {
        if (frame.isEmpty()) return byteArrayOf(((cmr and 0x0F) shl 4).toByte(), 0x78)
        val out = ByteArray(frame.size + 1)
        out[0] = ((cmr and 0x0F) shl 4).toByte()
        // Clear F bit (single frame) and padding bits 1..0 while keeping FT and Q
        val toc = frame[0].toInt() and 0x7C
        out[1] = toc.toByte()
        if (frame.size > 1) {
            System.arraycopy(frame, 1, out, 2, frame.size - 1)
        }
        return out
    }

    /**
     * Packs one storage frame (`[ToC][speech...]`) into RFC 4867 Bandwidth-Efficient
     * RTP payload (`4-bit CMR + 6-bit ToC + K speech bits`, bit-exact with Baresip `pack_be`
     * and PJSIP `pjmedia_codec_amr_pack`).
     */
    fun toBandwidthEfficient(frame: ByteArray, isWideband: Boolean, cmr: Int = 15): ByteArray {
        if (frame.isEmpty()) {
            return byteArrayOf(((cmr and 0x0F) shl 4 or 0x07).toByte(), 0x80.toByte())
        }
        val toc = frame[0].toInt() and 0xFF
        val ft = (toc ushr 3) and 0x0F
        val q = (toc ushr 2) and 0x01
        if (ft == 14 || ft == 15) {
            return byteArrayOf(
                (((cmr and 0x0F) shl 4) or ((ft ushr 1) and 0x07)).toByte(),
                (((ft and 0x01) shl 7) or (q shl 6)).toByte(),
            )
        }
        val speechBits = speechBitsForFt(ft, isWideband)
        if (speechBits <= 0) {
            return byteArrayOf(((cmr and 0x0F) shl 4 or 0x07).toByte(), 0x80.toByte())
        }
        val totalBits = 10 + speechBits
        val out = ByteArray((totalBits + 7) / 8)

        // First 10 bits: CMR(4) | F=0(1) | FT[3..1](3) || FT[0](1) | Q(1)
        out[0] = (((cmr and 0x0F) shl 4) or ((ft ushr 1) and 0x07)).toByte()
        out[1] = (((ft and 0x01) shl 7) or (q shl 6)).toByte()

        // Speech bits follow immediately at bit offset 10 (6 bits in out[1], remaining shifted left 2)
        val availSpeechBytes = minOf(frame.size - 1, (speechBits + 7) / 8)
        for (bit in 0 until speechBits) {
            val srcByteIdx = 1 + (bit ushr 3)
            if (srcByteIdx - 1 >= availSpeechBytes) break
            val b = (frame[srcByteIdx].toInt() ushr (7 - (bit and 7))) and 1
            if (b != 0) {
                val dstPos = 10 + bit
                val dstIdx = dstPos ushr 3
                val dstShift = 7 - (dstPos and 7)
                out[dstIdx] = (out[dstIdx].toInt() or (1 shl dstShift)).toByte()
            }
        }
        return out
    }

    /** Unpacks a Bandwidth-Efficient RTP payload (supporting multi-frame ToC chaining) into storage frames. */
    fun fromBandwidthEfficient(
        data: ByteArray,
        offset: Int,
        length: Int,
        isWideband: Boolean,
    ): ByteArray? = unpackBandwidthEfficient(data, offset, length, isWideband)?.frames?.firstOrNull()

    private fun hasHeader(raw: ByteArray, hdr: ByteArray): Boolean {
        if (raw.size < hdr.size) return false
        for (i in hdr.indices) {
            if (raw[i] != hdr[i]) return false
        }
        return true
    }

    fun unpackBandwidthEfficient(
        data: ByteArray,
        offset: Int,
        length: Int,
        isWideband: Boolean,
        requireExactLength: Boolean = false,
    ): UnpackResult? {
        if (length < 2 || offset < 0 || offset + length > data.size) return null

        fun readBit(bitIndex: Int): Int {
            val byteIdx = offset + (bitIndex ushr 3)
            if (byteIdx >= offset + length) return 0
            return (data[byteIdx].toInt() ushr (7 - (bitIndex and 7))) and 1
        }

        val cmr = (data[offset].toInt() and 0xF0) ushr 4
        var bitPos = 4
        val totalPayloadBits = length * 8

        var packedTocs = IntArray(4)
        var tocCount = 0
        var expectedSpeechBits = 0
        while (bitPos + 6 <= totalPayloadBits) {
            val f = readBit(bitPos)
            val ft = (readBit(bitPos + 1) shl 3) or
                (readBit(bitPos + 2) shl 2) or
                (readBit(bitPos + 3) shl 1) or
                readBit(bitPos + 4)
            val q = readBit(bitPos + 5)
            bitPos += 6
            if (!isValidFt(ft, isWideband)) return null
            if (tocCount == packedTocs.size) packedTocs = packedTocs.copyOf(packedTocs.size * 2)
            packedTocs[tocCount++] = (ft shl 1) or q
            if (ft != 14 && ft != 15) {
                expectedSpeechBits += speechBitsForFt(ft, isWideband)
            }
            if (f == 0) break
        }
        if (tocCount == 0) return null
        if (requireExactLength && ((bitPos + expectedSpeechBits + 7) / 8) != length) {
            return null
        }

        val frames = ArrayList<ByteArray>(tocCount)
        for (idx in 0 until tocCount) {
            val packed = packedTocs[idx]
            val ft = packed ushr 1
            val q = packed and 0x01
            if (ft == 14 || ft == 15) continue
            val speechBits = speechBitsForFt(ft, isWideband)
            if (speechBits <= 0 || bitPos + speechBits > totalPayloadBits) {
                if (frames.isEmpty()) return null
                break
            }
            val frame = ByteArray(1 + (speechBits + 7) / 8)
            frame[0] = ((ft shl 3) or (q shl 2)).toByte()
            for (bit in 0 until speechBits) {
                if (readBit(bitPos + bit) != 0) {
                    val dstIdx = 1 + (bit ushr 3)
                    frame[dstIdx] = (frame[dstIdx].toInt() or (1 shl (7 - (bit and 7)))).toByte()
                }
            }
            bitPos += speechBits
            frames.add(frame)
        }
        if (frames.isEmpty()) return null
        return UnpackResult(frames = frames, cmr = cmr, wasOctetAligned = false)
    }

    fun unpackOctetAligned(
        data: ByteArray,
        offset: Int,
        length: Int,
        isWideband: Boolean,
    ): UnpackResult? {
        if (length < 2 || offset < 0 || offset + length > data.size) return null

        // Check whether CMR byte is present or omitted
        val hasCmr = looksLikeOctetAlignedWithCmr(data, offset, length, isWideband)
        var pos = offset
        val end = offset + length
        val cmr = if (hasCmr) {
            val c = (data[pos].toInt() and 0xF0) ushr 4
            pos++
            c
        } else {
            15
        }

        val tocs = ArrayList<Int>(2)
        while (pos < end) {
            val toc = data[pos++].toInt() and 0xFF
            val ft = (toc ushr 3) and 0x0F
            if (!isValidFt(ft, isWideband)) return null
            tocs.add(toc)
            if ((toc and 0x80) == 0) break
        }
        if (tocs.isEmpty()) return null

        val frames = ArrayList<ByteArray>(tocs.size)
        for (toc in tocs) {
            val ft = (toc ushr 3) and 0x0F
            val q = (toc ushr 2) and 0x01
            if (ft == 14 || ft == 15) continue
            val speechBytes = speechBytesForFt(ft, isWideband)
            if (speechBytes <= 0 || pos + speechBytes > end) {
                if (frames.isEmpty()) return null
                break
            }
            val frame = ByteArray(1 + speechBytes)
            frame[0] = ((ft shl 3) or (q shl 2)).toByte()
            System.arraycopy(data, pos, frame, 1, speechBytes)
            pos += speechBytes
            frames.add(frame)
        }
        if (frames.isEmpty()) return null
        return UnpackResult(frames = frames, cmr = cmr, wasOctetAligned = true)
    }

    private fun looksLikeOctetAlignedWithCmr(
        data: ByteArray,
        offset: Int,
        length: Int,
        isWideband: Boolean,
    ): Boolean {
        val end = offset + length
        // Match exact CMR-prefixed OA packet sizes across all ToCs
        if ((data[offset].toInt() and 0x0F) == 0 && offset + 1 < end) {
            var pos = offset + 1
            var expectedSpeech = 0
            var valid = true
            while (pos < end) {
                val toc = data[pos++].toInt() and 0xFF
                val ft = (toc ushr 3) and 0x0F
                if (!isValidFt(ft, isWideband) || (toc and 0x03) != 0) {
                    valid = false
                    break
                }
                if (ft != 14 && ft != 15) expectedSpeech += speechBytesForFt(ft, isWideband)
                if ((toc and 0x80) == 0) break
            }
            if (valid && pos + expectedSpeech == end) return true
        }
        return false
    }

    /**
     * Unpacks an incoming RTP AMR payload into individual storage frames
     * regardless of whether the remote gateway strictly honors `octet-align`
     * negotiation or falls back between OA and BE mid-call.
     */
    fun unpackRtpFrames(
        data: ByteArray,
        offset: Int,
        length: Int,
        isWideband: Boolean,
        negotiatedOctetAligned: Boolean,
    ): UnpackResult? {
        if (length < 2 || offset < 0 || offset + length > data.size) return null

        val tryPrimary = if (negotiatedOctetAligned) {
            unpackOctetAligned(data, offset, length, isWideband)
        } else {
            // Even when SDP says BE, verify packet length against BE in one pass before trusting it;
            // some IMS SBCs emit OA anyway (check Q=1 bug from AmrFramingTest!).
            unpackBandwidthEfficient(data, offset, length, isWideband, requireExactLength = true)
        }
        if (tryPrimary != null) return tryPrimary

        val fallback = if (negotiatedOctetAligned) {
            unpackBandwidthEfficient(data, offset, length, isWideband)
        } else {
            unpackOctetAligned(data, offset, length, isWideband)
        }
        return fallback
    }

    fun extractFrame(
        data: ByteArray,
        offset: Int,
        length: Int,
        isWideband: Boolean,
        octetAligned: Boolean,
    ): ByteArray? = unpackRtpFrames(data, offset, length, isWideband, octetAligned)?.frames?.firstOrNull()

    fun unpackRtpPayload(
        data: ByteArray,
        offset: Int,
        length: Int,
        isWideband: Boolean,
        octetAligned: Boolean,
    ): List<ByteArray> = unpackRtpFrames(data, offset, length, isWideband, octetAligned)?.frames ?: emptyList()

    fun unpackRtpPayloadWithMode(
        data: ByteArray,
        offset: Int,
        length: Int,
        isWideband: Boolean,
        octetAligned: Boolean,
    ): UnpackResult? = unpackRtpFrames(data, offset, length, isWideband, octetAligned)
}
