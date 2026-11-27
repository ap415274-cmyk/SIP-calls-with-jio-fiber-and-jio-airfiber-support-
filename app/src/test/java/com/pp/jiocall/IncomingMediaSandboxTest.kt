package com.pp.jiocall

import com.pp.jiocall.sip.Codec
import com.pp.jiocall.sip.Sdp
import com.pp.jiocall.sip.planMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incoming-call decision, sandboxed: no Context, no sockets, no
 * microphone, no telephony stack.  Everything here is the production code
 * path answer() takes — parse the offer, plan the media, build the answer —
 * driven from real-world SDP shapes.
 */
class IncomingMediaSandboxTest {

    private val offered = listOf(Codec.AMR_WB, Codec.AMR_NB, Codec.PCMA, Codec.PCMU)

    /** What answer() does: one plan, and an answer built from that same plan. */
    private fun answerTo(
        offer: String,
        address: String = "10.0.2.15",
        port: Int = 40000,
        codecs: List<Codec> = offered,
        fallbackHost: String = "192.168.29.1",
    ): Pair<com.pp.jiocall.sip.MediaPlan, String>? {
        val remote = Sdp.parse(offer) ?: return null
        val align = Sdp.negotiateAmrAlignment(offer, codecs)
        val plan = planMedia(remote, codecs, align, fallbackHost) ?: return null
        val sdp = Sdp.createAnswer(offer, address, port, plan, 987654321L) ?: return null
        return plan to sdp
    }

    /** Jio's incoming offer as captured: AMR-WB at 96, no a=fmtp at all. */
    private val jioWideband = """
        v=0
        o=JIO_ISBC 731155056 731155056 IN IP4 192.168.29.1
        s=-
        c=IN IP4 192.168.29.1
        t=0 0
        m=audio 49018 RTP/AVP 96 120
        a=rtpmap:96 AMR-WB/16000
        a=rtpmap:120 telephone-event/16000
        a=fmtp:120 0-15
        a=sendrecv
    """.trimIndent()

    @Test
    fun jioWidebandOfferIsAnsweredBandwidthEfficient() {
        val (plan, sdp) = answerTo(jioWideband)!!
        assertEquals(Codec.AMR_WB, plan.codec)
        assertEquals(96, plan.payloadType)
        assertFalse("an offer with no a=fmtp is bandwidth-efficient", plan.octetAligned)
        assertEquals("192.168.29.1", plan.host)
        assertEquals(49018, plan.port)
        assertEquals(120, plan.dtmfPt)
        assertEquals(16000, plan.dtmfRate)

        // The answer has to describe exactly the stream the plan will run.
        assertTrue(sdp.contains("m=audio 40000 RTP/AVP 96 120"))
        assertTrue(sdp.contains("a=rtpmap:96 AMR-WB/16000"))
        assertTrue("bandwidth-efficient needs no a=fmtp", !sdp.contains("a=fmtp:96"))
        assertTrue(sdp.contains("a=rtpmap:120 telephone-event/16000"))
        assertTrue(sdp.contains("a=sendrecv"))
    }

    @Test
    fun octetAlignedOfferIsAnsweredOctetAligned() {
        val offer = """
            v=0
            o=- 1 1 IN IP4 192.168.29.1
            s=-
            c=IN IP4 192.168.29.1
            t=0 0
            m=audio 49048 RTP/AVP 97 121
            a=rtpmap:97 AMR/8000
            a=fmtp:97 octet-align=1
            a=rtpmap:121 telephone-event/8000
            a=fmtp:121 0-15
            a=sendrecv
        """.trimIndent()
        val (plan, sdp) = answerTo(offer)!!
        assertEquals(Codec.AMR_NB, plan.codec)
        assertEquals(97, plan.payloadType)
        assertTrue(plan.octetAligned)
        assertTrue(sdp.contains("a=fmtp:97 octet-align=1"))
        assertEquals(121, plan.dtmfPt)
        assertEquals(8000, plan.dtmfRate)
    }

    @Test
    fun answerPayloadTypesAreAlwaysASubsetOfTheOffer() {
        for (offer in listOf(jioWideband, offerWithPcma, offerWithIpv6Media)) {
            val (plan, sdp) = answerTo(offer)!!
            val offeredPts = Sdp.parse(offer)!!.payloads.toSet()
            val answered = sdp.line("m=audio").removePrefix("m=audio ")
                .split(' ').drop(2).mapNotNull { it.toIntOrNull() }
            assertTrue(
                "answer listed $answered, offer listed $offeredPts",
                answered.all { it in offeredPts },
            )
            assertTrue("the codec we run must be in the answer", plan.payloadType in answered)
            assertTrue("the DTMF type must be in the answer", plan.dtmfPt in answered)
        }
    }

    @Test
    fun ipv6MediaIsAnsweredOverIpv6() {
        val (plan, sdp) = answerTo(offerWithIpv6Media, address = "2405:201:6843:a030::9")!!
        assertEquals("2401:4900::1", plan.host)
        assertTrue(sdp.contains("c=IN IP6 2405:201:6843:a030::9"))
        assertTrue(sdp.contains("o=- 987654321 987654321 IN IP6 2405:201:6843:a030::9"))
        assertFalse(sdp.contains("IN IP4"))
    }

    @Test
    fun g711OfferIsAnsweredWithoutFmtp() {
        val (plan, sdp) = answerTo(offerWithPcma)!!
        assertEquals(Codec.PCMA, plan.codec)
        assertEquals(8, plan.payloadType)
        assertFalse(plan.octetAligned)
        assertFalse(sdp.contains("a=fmtp:8"))
    }

    @Test
    fun offerWithNoCommonCodecHasNoPlanAndNoAnswer() {
        val g722 = """
            v=0
            o=- 1 1 IN IP4 192.168.29.1
            s=-
            c=IN IP4 192.168.29.1
            t=0 0
            m=audio 49018 RTP/AVP 9
            a=rtpmap:9 G722/8000
            a=sendrecv
        """.trimIndent()
        assertNull("answer() must refuse here instead of a silent 200 OK", answerTo(g722))
    }

    @Test
    fun peerThatNamesUnusableMediaAddressFallsBackToTheSignallingHost() {
        val holdOffer = """
            v=0
            o=- 1 1 IN IP4 0.0.0.0
            s=-
            c=IN IP4 0.0.0.0
            t=0 0
            m=audio 49018 RTP/AVP 96
            a=rtpmap:96 AMR-WB/16000
            a=sendrecv
        """.trimIndent()
        val plan = planMedia(Sdp.parse(holdOffer)!!, offered, null, "192.168.29.1")!!
        assertEquals("192.168.29.1", plan.host)
    }

    @Test
    fun unreadableOfferParsesToNothing() {
        assertNull(Sdp.parse("not sdp at all"))
        assertNull(Sdp.parse("v=0\r\nm=audio 0 RTP/AVP 96\r\nc=IN IP4 1.2.3.4\r\n"))
        assertNull(Sdp.parse(""))
    }

    /**
     * An independent, strict RFC 4867 bandwidth-efficient receiver: it does
     * not reuse the production parser, requires the payload length to be
     * exactly CMR+F+FT+Q plus the mode's speech bits, and rebuilds the
     * octet-aligned frame from the bit stream.
     */
    @Test
    fun ourBandwidthEfficientPacketsPassAStrictIndependentReceiver() {
        val wbBits = intArrayOf(132, 177, 253, 285, 317, 365, 397, 461, 477)
        for (ft in listOf(0, 2, 5, 8)) {
            val speech = wbBits[ft]
            // Octet-aligned: one ToC byte, then the speech byte-aligned.
            val frameSize = 1 + (speech + 7) / 8
            val frame = ByteArray(frameSize)
            frame[0] = (ft shl 3).toByte() // F=0, FT, Q=0
            for (i in 1 until frameSize) frame[i] = ((i * 53 + 7) and 0xFF).toByte()
            val pad = frameSize * 8 - 8 - speech
            if (pad > 0) frame[frameSize - 1] = (frame[frameSize - 1].toInt() and (0xFF shl pad)).toByte()

            val payload = com.pp.jiocall.sip.RtpAudio.amrToBandwidthEfficient(frame, true)

            // Strict length: 10 bits of header plus the mode's speech bits.
            assertEquals("FT=$ft", (10 + speech + 7) / 8, payload.size)
            val cmr = (payload[0].toInt() and 0xF0) ushr 4
            assertEquals("CMR must not request a mode", 0x0F, cmr)
            val rxFt = ((payload[0].toInt() and 0x07) shl 1) or ((payload[1].toInt() and 0x80) ushr 7)
            assertEquals("FT must survive the conversion", ft, rxFt)
            val q = (payload[1].toInt() and 0x40) ushr 6
            assertEquals(0, q)

            // Rebuild the speech bits from the bit stream, independently.
            val bits = StringBuilder()
            for (bit in 0 until speech) {
                val position = 10 + bit
                val byte = payload[position / 8].toInt() and 0xFF
                bits.append((byte ushr (7 - position % 8)) and 1)
            }
            val expected = StringBuilder()
            for (bit in 0 until speech) {
                val position = 8 + bit
                val byte = frame[position / 8].toInt() and 0xFF
                expected.append((byte ushr (7 - position % 8)) and 1)
            }
            assertEquals("FT=$ft speech bits must match", expected.toString(), bits.toString())
        }
    }

    /**
     * The offer Jio's gateway actually sent on a failed incoming call: AMR-WB
     * (110, octet-aligned) and AMR-NB (96, octet-aligned) in one m= line.
     *
     * pjmedia answers with ONE format, and with the caller's order preferred
     * (PJMEDIA_SDP_NEG_ANSWER_MULTIPLE_CODECS 0, PREFER_REMOTE_CODEC_ORDER 1),
     * so the answer names AMR-WB on 110 alone.  Our old multi-format answer
     * (`110 96 107`) is what let the gateway send narrowband on 96 while we
     * transmitted wideband on 110.
     */
    @Test
    fun dualAmrOfferIsAnsweredWithTheCallersFirstCodecAlone() {
        val (plan, sdp) = answerTo(jioDualOffer)!!
        assertEquals(Codec.AMR_WB, plan.codec)
        assertEquals(110, plan.payloadType)
        assertTrue(plan.octetAligned)
        assertEquals(107, plan.dtmfPt)
        assertTrue(sdp.contains("m=audio 40000 RTP/AVP 110 107"))
        assertTrue(sdp.contains("a=rtpmap:110 AMR-WB/16000"))
        assertTrue(sdp.contains("a=fmtp:110 octet-align=1"))
        assertFalse("a second audio format is what the gateway mis-picks", sdp.contains("m=audio 40000 RTP/AVP 96"))
        assertFalse("the narrowband format must not be offered", sdp.contains("a=rtpmap:96"))
        assertTrue(sdp.contains("a=fmtp:107 0-15"))
    }

    private val jioDualOffer = """
        v=0
        o=JIO_ISBC 731155056 731155056 IN IP4 192.168.29.1
        s=-
        c=IN IP4 192.168.29.1
        t=0 0
        m=audio 49006 RTP/AVP 110 120 96 118 111 107
        a=rtpmap:110 AMR-WB/16000/1
        a=rtpmap:120 AMR-WB/16000/1
        a=rtpmap:96 AMR/8000/1
        a=fmtp:96 octet-align=1;mode-change-capability=2;max-red=0
        a=rtpmap:118 AMR/8000/1
        a=fmtp:118 mode-change-capability=2;max-red=0
        a=rtpmap:111 telephone-event/16000
        a=fmtp:111 0-15
        a=rtpmap:107 telephone-event/8000
        a=fmtp:107 0-15
        a=fmtp:110 mode-set=0,1,2,3;octet-align=1;mode-change-capability=2;max-red=0
        a=fmtp:120 mode-set=0,1,2,3;mode-change-capability=2;max-red=0
        a=sendrecv
    """.trimIndent()

    private val offerWithPcma = """
        v=0
        o=- 1 1 IN IP4 192.168.29.1
        s=-
        c=IN IP4 192.168.29.1
        t=0 0
        m=audio 49020 RTP/AVP 8 101
        a=rtpmap:8 PCMA/8000
        a=rtpmap:101 telephone-event/8000
        a=sendrecv
    """.trimIndent()

    private val offerWithIpv6Media = """
        v=0
        o=- 1 1 IN IP6 2401:4900::1
        s=-
        c=IN IP6 2401:4900::1
        t=0 0
        m=audio 49022 RTP/AVP 96 120
        a=rtpmap:96 AMR-WB/16000
        a=rtpmap:120 telephone-event/16000
        a=sendrecv
    """.trimIndent()

    private fun String.line(prefix: String): String =
        split("\r\n", "\n").first { it.trim().startsWith(prefix) }.trim()

    @Test
    fun planAndAnswerAgreeOnFramingInEveryCase() {
        for (offer in listOf(jioWideband, offerWithPcma, offerWithIpv6Media)) {
            val (plan, sdp) = answerTo(offer)!!
            val fmtp = sdp.split("\r\n").firstOrNull { it.startsWith("a=fmtp:${plan.payloadType} ") }
            assertEquals(
                "answer must announce octet alignment exactly when we code it",
                plan.octetAligned,
                fmtp?.contains("octet-align=1") == true,
            )
        }
    }
}
