package com.pp.jiocall

import com.pp.jiocall.data.JioMode
import com.pp.jiocall.jio.JioRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same vectors as RusTone's `jiofiber.rs` tests. */
class JioRouterTest {

    @Test
    fun hostnamesHashLikePjproject() {
        assertEquals(0L, JioRouter.deviceHash(""))
        assertEquals(97L, JioRouter.deviceHash("a"))
        assertEquals(97L * 33 + 98, JioRouter.deviceHash("ab"))
    }

    @Test
    fun theMacIsTheHashInMemoryOrder() {
        assertEquals("00:00:e3:0c:00:00", JioRouter.deviceMac("ab"))
    }

    @Test
    fun theInstanceIdIsWhatTheRouterWhitelists() {
        // JFC's pjproject patch: 00000000-0000-1000-8000-0000 + the hash's
        // little-endian bytes in upper-case hex, no urn:uuid: prefix.
        assertEquals("00000000-0000-1000-8000-0000E30C0000", JioRouter.instanceId("ab"))
        assertEquals(
            "00000000-0000-1000-8000-0000" + JioRouter.deviceMac("ab").removePrefix("00:00:").replace(":", "").uppercase(),
            JioRouter.instanceId("ab"),
        )
    }

    @Test
    fun queriesPickTheKindOfCredentials() {
        val temporary = JioRouter.credentialsQuery("pc", JioMode.TEMPORARY, register = false)
        assertEquals("terminal_sw_version" to "RCSAndrd", temporary.first())
        assertTrue(temporary.contains("nwk_intf" to "eth"))
        assertTrue(temporary.contains("mac_address" to JioRouter.deviceMac("pc")))
        assertTrue(temporary.none { it.first == "op_type" })

        val register = JioRouter.credentialsQuery("pc", JioMode.PERMANENT, register = true)
        assertTrue(register.contains("nwk_intf" to "wifi"))
        assertEquals("op_type" to "add", register.last())
    }

    @Test
    fun queriesAreFormEncoded() {
        val query = JioRouter.encodedQuery(
            listOf(
                "mac_address" to "00:00:12:ab:cd:ef",
                "alias" to "My PC",
                "IMSI" to "",
            ),
        )
        assertEquals("mac_address=00%3A00%3A12%3Aab%3Acd%3Aef&alias=My%20PC&IMSI=", query)
    }

    @Test
    fun settingsMapToCodecs() {
        // Which codecs a device can really encode needs MediaCodec, so only the
        // name mapping is checked here.
        assertEquals(com.pp.jiocall.sip.Codec.AMR_WB, com.pp.jiocall.sip.Codec.fromSetting("AMR-WB/16000/1"))
        assertEquals(com.pp.jiocall.sip.Codec.AMR_NB, com.pp.jiocall.sip.Codec.fromSetting("AMR/8000/1"))
        assertEquals(com.pp.jiocall.sip.Codec.PCMA, com.pp.jiocall.sip.Codec.fromSetting("PCMA/8000/1"))
        assertEquals(null, com.pp.jiocall.sip.Codec.fromSetting("OPUS/48000/2"))
    }
}
