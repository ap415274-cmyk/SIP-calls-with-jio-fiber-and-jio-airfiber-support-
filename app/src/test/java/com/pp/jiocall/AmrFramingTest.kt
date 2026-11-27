package com.pp.jiocall

import com.pp.jiocall.sip.RtpAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * RFC 4867 framing, the part of the audio path that turns a call into noise
 * when it is off by a bit: MediaCodec hands us octet-aligned frames (ToC byte
 * + speech), while Jio's incoming offers negotiate bandwidth-efficient
 * framing.  Both directions have to round-trip exactly.
 */
class AmrFramingTest {

    private fun speechBits(isWideband: Boolean, ft: Int): Int {
        val bits = if (isWideband) {
            intArrayOf(132, 177, 253, 285, 317, 365, 397, 461, 477)
        } else {
            intArrayOf(95, 103, 118, 134, 148, 159, 204, 244)
        }
        return bits[ft]
    }

    /** An octet-aligned frame: ToC byte + byte-aligned speech, tail bits zero. */
    private fun oaFrame(ft: Int, isWideband: Boolean): ByteArray {
        val speech = speechBits(isWideband, ft)
        val size = 1 + (speech + 7) / 8
        val frame = ByteArray(size)
        frame[0] = (ft shl 3).toByte()
        for (i in 1 until size) frame[i] = (i * 37 + 11).toByte()
        val pad = size * 8 - 8 - speech
        if (pad > 0) frame[size - 1] = (frame[size - 1].toInt() and (0xFF shl pad)).toByte()
        return frame
    }

    @Test
    fun bandwidthEfficientPayloadLengthComesFromTheMode() {
        for ((ft, wideband) in listOf(2 to true, 8 to true, 7 to false)) {
            val frame = oaFrame(ft, wideband)
            val be = RtpAudio.amrToBandwidthEfficient(frame, wideband)
            val expected = (10 + speechBits(wideband, ft) + 7) / 8
            assertEquals(
                "FT=$ft wideband=$wideband: CMR+F+FT+Q plus speech, no octet padding",
                expected,
                be.size,
            )
            assertEquals("no stray trailing byte", expected, frame.size)
            assertEquals("CMR must not request a mode change", 0x0F, be[0].toInt() and 0xFF ushr 4)
        }
    }

    @Test
    fun bandwidthEfficientRoundTripsBitForBit() {
        for ((ft, wideband) in listOf(2 to true, 8 to true, 7 to false)) {
            val frame = oaFrame(ft, wideband)
            val be = RtpAudio.amrToBandwidthEfficient(frame, wideband)
            val recovered = RtpAudio.bandwidthEfficientToAmr(be, 0, be.size, wideband)
            assertNotNull("FT=$ft wideband=$wideband", recovered)
            assertArrayEquals("FT=$ft wideband=$wideband", frame, recovered!!)
        }
    }

    @Test
    fun amrFrameReadsOctetAlignedWithAndWithoutCmr() {
        val frame = oaFrame(2, true)
        val withCmr = ByteArray(frame.size + 1).also {
            it[0] = 0xF0.toByte()
            frame.copyInto(it, 1)
        }
        assertArrayEquals(frame, RtpAudio.amrFrame(withCmr, 0, withCmr.size, true, true))
        assertArrayEquals(frame, RtpAudio.amrFrame(frame, 0, frame.size, true, true))
    }

    /**
     * A peer whose SDP says bandwidth-efficient but whose packets are
     * octet-aligned must still be heard: the quality bit is set on every
     * normal speech frame, so it cannot be part of the detection.
     */
    @Test
    fun amrFrameReadsOctetAlignedWhenBandwidthEfficientWasNegotiated() {
        val speech = speechBits(true, 2)
        val size = 1 + (speech + 7) / 8
        val frame = ByteArray(size).also { f ->
            f[0] = ((2 shl 3) or 0x04).toByte() // FT = 2, Q = 1
            for (i in 1 until size) f[i] = (i * 37 + 11).toByte()
            val pad = size * 8 - 8 - speech
            if (pad > 0) f[size - 1] = (f[size - 1].toInt() and (0xFF shl pad)).toByte()
        }
        val withCmr = ByteArray(frame.size + 1).also {
            it[0] = 0xF0.toByte()
            frame.copyInto(it, 1)
        }
        val extracted = RtpAudio.amrFrame(withCmr, 0, withCmr.size, true, false)
        assertNotNull(extracted)
        assertArrayEquals(frame, extracted!!)
    }

    @Test
    fun amrFrameReadsABandwidthEfficientPayload() {
        val frame = oaFrame(2, true)
        val be = RtpAudio.amrToBandwidthEfficient(frame, true)
        val extracted = RtpAudio.amrFrame(be, 0, be.size, true, false)
        assertNotNull(extracted)
        assertArrayEquals(frame, extracted!!)
    }

    @Test
    fun holePunchPacketsAskForNoModeChange() {
        // CMR 15 in both framings; the payload must also fit the framing it
        // claims (octet-aligned: CMR byte + ToC byte, bandwidth-efficient: 10
        // bits of header and no speech).
        val oa = byteArrayOf(0xF0.toByte(), 0x78.toByte())
        assertEquals(0x0F, oa[0].toInt() and 0xFF ushr 4)
        val be = byteArrayOf(0xF7.toByte(), 0x80.toByte())
        assertEquals(0x0F, be[0].toInt() and 0xFF ushr 4)
        assertEquals(15, (((be[0].toInt() and 0x07) shl 1) or ((be[1].toInt() and 0xFF) ushr 7)))
    }
}
