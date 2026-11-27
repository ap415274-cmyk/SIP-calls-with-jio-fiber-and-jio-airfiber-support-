package com.pp.jiocall

import android.content.pm.ServiceInfo
import com.pp.jiocall.data.AppSettings
import com.pp.jiocall.sip.CallService
import com.pp.jiocall.sip.Codec
import com.pp.jiocall.sip.Sdp
import com.pp.jiocall.sip.SipAccount
import com.pp.jiocall.sip.SipDirection
import com.pp.jiocall.sip.SipLogBuffer
import com.pp.jiocall.sip.SipMessage
import com.pp.jiocall.sip.planMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallTest {

    /** What answer() does: plan the media, then answer with that exact plan. */
    private fun answer(
        offer: String,
        address: String = "192.168.29.121",
        port: Int = 52920,
        codecs: List<Codec> = listOf(Codec.AMR_WB, Codec.AMR_NB, Codec.PCMA, Codec.PCMU),
        sessionId: Long = 1L,
        amrOctetAligned: Boolean? = null,
    ): String? {
        val remote = Sdp.parse(offer) ?: return null
        val plan = planMedia(remote, codecs, amrOctetAligned, "192.168.29.1") ?: return null
        return Sdp.createAnswer(offer, address, port, plan, sessionId)
    }

    @Test
    fun createAnswerMatchesOfferedPayloadTypesAndCodecs() {
        val remoteOffer = """
            v=0
            o=JIO_ISBC 731155056 731155056 IN IP4 192.168.29.1
            s=-
            c=IN IP4 192.168.29.1
            t=0 0
            m=audio 49018 RTP/AVP 96 120
            b=AS:49
            b=RS:0
            a=rtpmap:96 AMR-WB/16000
            a=rtpmap:120 telephone-event/16000
            a=fmtp:120 0-16
            a=sendrecv
        """.trimIndent()

        val answer = answer(remoteOffer, sessionId = 123456789L)!!

        assertTrue(answer.contains("m=audio 52920 RTP/AVP 96 120"))
        assertTrue(answer.contains("c=IN IP4 192.168.29.121"))
        assertTrue(answer.contains("a=rtpmap:96 AMR-WB/16000"))
        assertTrue(answer.contains("a=rtpmap:120 telephone-event/16000"))
    }

    /**
     * Jio's offer usually leaves octet-align out, which RFC 4867 §8.1 and
     * pjmedia's AMR factory both read as bandwidth-efficient.  Claiming
     * octet-align=1 in the answer is what garbles an incoming call in both
     * directions.
     */
    @Test
    fun answerKeepsBandwidthEfficientFramingWhenTheOfferIsSilent() {
        val offer = """
            v=0
            o=JIO_ISBC 1 1 IN IP4 192.168.29.1
            s=-
            c=IN IP4 192.168.29.1
            t=0 0
            m=audio 49018 RTP/AVP 96 120
            a=rtpmap:96 AMR-WB/16000
            a=rtpmap:120 telephone-event/16000
            a=sendrecv
        """.trimIndent()

        assertEquals(false, Sdp.negotiateAmrAlignment(offer, listOf(Codec.AMR_WB)))
        val answer = answer(offer, codecs = listOf(Codec.AMR_WB))!!
        assertTrue("must not claim octet alignment", !answer.contains("octet-align=1"))
    }

    @Test
    fun answerMirrorsAnOctetAlignedOffer() {
        val offer = """
            v=0
            o=- 1 1 IN IP4 192.168.29.1
            s=-
            c=IN IP4 192.168.29.1
            t=0 0
            m=audio 49018 RTP/AVP 97
            a=rtpmap:97 AMR/8000
            a=fmtp:97 octet-align=1
            a=sendrecv
        """.trimIndent()

        assertEquals(true, Sdp.negotiateAmrAlignment(offer, listOf(Codec.AMR_NB)))
        val answer = answer(offer, codecs = listOf(Codec.AMR_NB), amrOctetAligned = true)!!
        assertTrue(answer.contains("a=fmtp:97 octet-align=1"))
    }

    @Test
    fun answerUsesIp6ForIpv6Media() {
        val offer = """
            v=0
            o=- 1 1 IN IP6 2401:4900::1
            s=-
            c=IN IP6 2401:4900::1
            t=0 0
            m=audio 49018 RTP/AVP 96
            a=rtpmap:96 AMR-WB/16000
            a=sendrecv
        """.trimIndent()

        val answer = answer(offer, address = "2401:4900::9", codecs = listOf(Codec.AMR_WB))!!
        assertTrue("IN IP4 in front of an IPv6 literal is invalid SDP", answer.contains("c=IN IP6 2401:4900::9"))
        assertTrue(answer.contains("o=- 1 1 IN IP6 2401:4900::9"))
    }

    @Test
    fun answerIsRefusedWhenNothingIsShared() {
        val offer = """
            v=0
            o=- 1 1 IN IP4 192.168.29.1
            s=-
            c=IN IP4 192.168.29.1
            t=0 0
            m=audio 49018 RTP/AVP 9
            a=rtpmap:9 G722/8000
            a=sendrecv
        """.trimIndent()

        assertNull(answer(offer, codecs = listOf(Codec.AMR_WB)))
    }

    @Test
    fun createAnswerAdaptsToCustomRemotePayloadType() {
        val remoteOffer = """
            v=0
            o=JIO_ISBC 731155056 731155056 IN IP4 192.168.29.1
            s=-
            c=IN IP4 192.168.29.1
            t=0 0
            m=audio 49018 RTP/AVP 104 101
            a=rtpmap:104 AMR-WB/16000
            a=rtpmap:101 telephone-event/16000
            a=sendrecv
        """.trimIndent()

        val answer = answer(remoteOffer, codecs = listOf(Codec.AMR_WB, Codec.AMR_NB), sessionId = 123456789L)!!

        assertTrue("Should use remote's AMR-WB pt 104", answer.contains("m=audio 52920 RTP/AVP 104 101"))
        assertTrue(answer.contains("a=rtpmap:104 AMR-WB/16000"))
    }

    @Test
    fun parseIncomingInviteHeaders() {
        val raw = """
            INVITE sip:+915683317456@192.168.29.121:56138;transport=TLS SIP/2.0
            Via: SIP/2.0/TLS 192.168.29.1:5068;branch=z9hG4bKabcdef123
            Record-Route: <sip:192.168.29.1:5068;transport=tls;lr>
            From: "Caller Name" <sip:+919876543210@uw.wln.ims.jio.com;transport=tls>;tag=remotetag123
            To: <sip:+915683317456@uw.wln.ims.jio.com;transport=tls>
            Call-ID: incoming-call-id-test@192.168.29.1
            CSeq: 101 INVITE
            Contact: <sip:+919876543210@192.168.29.1:5068;transport=tls>
            Content-Type: application/sdp
            Content-Length: 0

        """.trimIndent()

        val msg = SipMessage.parse(raw.trimEnd(), "")
        assertNotNull(msg)
        assertEquals("INVITE", msg!!.method)
        assertEquals("incoming-call-id-test@192.168.29.1", msg.callId())
        assertEquals("remotetag123", msg.fromTag())
        assertEquals(listOf("<sip:192.168.29.1:5068;transport=tls;lr>"), msg.headers("Record-Route"))
    }

    @Test
    fun parseCompactSipHeadersSupported() {
        val raw = """
            INVITE sip:+915683317456@192.168.29.121:5061 SIP/2.0
            v: SIP/2.0/TLS 192.168.29.1:5068;branch=z9hG4bKcompact
            f: <sip:+919876543210@ims.jio.com>;tag=ftag
            t: <sip:+915683317456@ims.jio.com>;tag=ttag
            i: compact-call-id-42@192.168.29.1
            CSeq: 12 BYE
            l: 0

        """.trimIndent()

        val msg = SipMessage.parse(raw.trimEnd(), "")!!
        assertEquals("compact-call-id-42@192.168.29.1", msg.callId())
        assertEquals("ftag", msg.fromTag())
        assertEquals("ttag", msg.toTag())
        assertEquals(1, msg.headersAny("Via", "v").size)
        assertTrue(msg.headerAny("From", "f")!!.contains("+919876543210"))
    }

    @Test
    fun sipLogBufferTracksVersionOnLogAndClear() {
        SipLogBuffer.clear()
        val v0 = SipLogBuffer.version
        SipLogBuffer.log(SipDirection.TX, "TLS", "192.168.29.1:5068", "REGISTER sip:ims.jio.com SIP/2.0")
        val v1 = SipLogBuffer.version
        assertTrue("Expected log() to advance version", v1 > v0)
        assertEquals(1, SipLogBuffer.getLogs().size)

        SipLogBuffer.clear()
        val v2 = SipLogBuffer.version
        assertTrue("Expected clear() to advance version", v2 > v1)
        assertTrue(SipLogBuffer.getLogs().isEmpty())
    }

    @Test
    fun bootServiceUsesRemoteMessagingTypeAndPreservesBootSettings() {
        val defaults = AppSettings()
        assertTrue(defaults.bootStart)
        assertTrue(defaults.backgroundService)
        assertTrue(defaults.autoRefreshRouter)

        // Android 14/15+ idle boot keepalive MUST NOT use PHONE_CALL or MICROPHONE
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            CallService.resolveForegroundType(CallService.MODE_STATUS, hasMicPermission = true, sdkInt = 37),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            CallService.resolveForegroundType(CallService.MODE_STATUS, hasMicPermission = true, sdkInt = 33),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            CallService.resolveForegroundType(CallService.MODE_INCOMING, hasMicPermission = true, sdkInt = 37),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            CallService.resolveForegroundType(CallService.MODE_ONGOING, hasMicPermission = true, sdkInt = 37),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            CallService.resolveForegroundType(CallService.MODE_ONGOING, hasMicPermission = false, sdkInt = 37),
        )
    }

    @Test
    fun declineResponse486OmitsRecordRouteAndContactHeaders() {
        val raw = """
            INVITE sip:+915683317456@192.168.29.121:56138;transport=TLS SIP/2.0
            Via: SIP/2.0/TLS 192.168.29.1:5068;branch=z9hG4bKabcdef123
            Record-Route: <sip:192.168.29.1:5068;transport=tls;lr>
            From: "Caller Name" <sip:+919876543210@uw.wln.ims.jio.com>;tag=remotetag123
            To: <sip:+915683317456@uw.wln.ims.jio.com>
            Call-ID: decline-header-test@192.168.29.1
            CSeq: 101 INVITE
            Content-Length: 0

        """.trimIndent()
        val invite = SipMessage.parse(raw.trimEnd(), "")!!

        val resp486 = SipAccount.buildSipResponse(
            request = invite,
            code = 486,
            reason = "Busy Here",
            toTag = "localtag99",
        )
        assertTrue(resp486.startsWith("SIP/2.0 486 Busy Here\r\n"))
        assertTrue(resp486.contains("Via: SIP/2.0/TLS 192.168.29.1:5068;branch=z9hG4bKabcdef123"))
        assertTrue(resp486.contains("To: <sip:+915683317456@uw.wln.ims.jio.com>;tag=localtag99"))
        assertTrue(resp486.contains("Call-ID: decline-header-test@192.168.29.1"))
        assertTrue(resp486.contains("CSeq: 101 INVITE"))
        assertTrue("486 must omit Record-Route", !resp486.contains("Record-Route:", ignoreCase = true))
        assertTrue("486 must omit Contact", !resp486.contains("Contact:", ignoreCase = true))
        assertTrue("486 must omit Allow", !resp486.contains("Allow:", ignoreCase = true))

        val resp200 = SipAccount.buildSipResponse(
            request = invite,
            code = 200,
            reason = "OK",
            toTag = "localtag99",
            contact = "<sip:+915683317456@192.168.29.121:56138;transport=TLS>",
        )
        assertTrue("200 OK keeps Record-Route", resp200.contains("Record-Route: <sip:192.168.29.1:5068;transport=tls;lr>"))
        assertTrue("200 OK keeps Contact", resp200.contains("Contact: <sip:+915683317456@192.168.29.121:56138;transport=TLS>"))
    }
}
