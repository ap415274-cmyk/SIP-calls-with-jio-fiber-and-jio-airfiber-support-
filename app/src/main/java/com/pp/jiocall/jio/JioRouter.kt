package com.pp.jiocall.jio

import android.util.Xml
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.JioMode
import com.pp.jiocall.data.Provisioning
import com.pp.jiocall.data.Transport
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * JioFiber / AirFiber routers: SIP credentials for this device.
 *
 * The router hands out SIP credentials over HTTPS (port 8443) for a device
 * identified by its name and a "MAC address" derived from it. There are two
 * kinds:
 *
 *  * **Temporary** (`nwk_intf=eth`): issued at once, no OTP; they expire.
 *  * **Permanent** (`nwk_intf=wifi`): the device has to be registered first
 *    (`op_type=add`), which sends an OTP to the line's mobile number.
 *
 * The answer is an XML document of `<parm name=… value=…>` elements with the
 * IMS server, user and password. The router itself is the SIP proxy, on port
 * 5068 over TLS. Before any of that, `http://<router>:8080/request_account`
 * tells whether the router has a voice line and which.
 *
 * This object is the pure part: the device identity, the requests'
 * parameters, and reading the router's answers.
 */
object JioRouter {
    /** Names the router answers to, tried in this order (the default gateway
     *  is tried after the first). */
    const val ROUTER_NAME = "jiofiber.local.html"
    val ROUTER_FALLBACK_IPS = listOf("192.168.29.1", "192.168.31.1")

    /** `http://<router>:ACCOUNT_PORT/request_account` describes the voice line. */
    const val ACCOUNT_PORT = 8080

    /** The credentials service (HTTPS, self-signed certificate). */
    const val PROVISIONING_PORT = 8443

    /** The router's SIP proxy (TLS). */
    const val SIP_PORT = 5068

    /** pjproject's string hash (`hash * 33 + byte`, 32 bits, unsigned). */
    fun deviceHash(name: String): Long {
        var hash = 0L
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            hash = (hash * 33L + (byte.toInt() and 0xFF)) and 0xFFFF_FFFFL
        }
        return hash
    }

    /** The "MAC address" the router knows this device by: two zero bytes, then
     *  the hash's little-endian bytes, e.g. `00:00:4e:1a:c0:93`. */
    fun deviceMac(name: String): String {
        val hash = deviceHash(name)
        val b = longArrayOf(
            hash and 0xFF,
            (hash shr 8) and 0xFF,
            (hash shr 16) and 0xFF,
            (hash shr 24) and 0xFF,
        )
        return "00:00:" + b.joinToString(":") { String.format("%02x", it) }
    }

    /**
     * The `+sip.instance` value the router whitelists this device by.
     *
     * JFC's pjproject patches `init_outbound_setting()` in
     * `pjsua_acc.c` to build
     * `;+sip.instance="<00000000-0000-1000-8000-0000CCDDEEFF>"` where CC…FF
     * are the four bytes of pjproject's hostname hash in memory (little-endian)
     * order, in **upper-case** hex, with no `urn:uuid:` prefix. Commit
     * 507e1e3a in JFC-pjproject adds the UUID version/variant digits (`1000`,
     * `8000`); the ONT accepts the value with them and answers
     * `403 Device not whitelisted` for anything else (no instance, the older
     * all-zero form, or a `urn:uuid:` prefix).
     */
    fun instanceId(name: String): String {
        val hash = deviceHash(name)
        val bytes = longArrayOf(
            hash and 0xFF,
            (hash shr 8) and 0xFF,
            (hash shr 16) and 0xFF,
            (hash shr 24) and 0xFF,
        )
        val tail = bytes.joinToString("") { String.format("%02X", it) }
        return "00000000-0000-1000-8000-0000$tail"
    }

    /** The query parameters for a credentials request, in the router's order.
     *  `register` adds `op_type=add`: register this device (sends an OTP). */
    fun credentialsQuery(name: String, mode: JioMode, register: Boolean): List<Pair<String, String>> {
        val query = mutableListOf(
            "terminal_sw_version" to "RCSAndrd",
            "terminal_vendor" to name,
            "terminal_model" to name,
            "SMS_port" to "0",
            "act_type" to "volatile",
            "IMSI" to "",
            "msisdn" to "",
            "IMEI" to "",
            "vers" to "0",
            "token" to "",
            "rcs_state" to "0",
            "rcs_version" to "5.1B",
            "rcs_profile" to "joyn_blackbird",
            "client_vendor" to "JUIC",
            "default_sms_app" to "2",
            "default_vvm_app" to "0",
            "device_type" to "vvm",
            "client_version" to "JSEAndrd-1.0",
            "mac_address" to deviceMac(name),
            "alias" to name,
            "nwk_intf" to mode.wire,
        )
        if (register) query += "op_type" to "add"
        return query
    }

    /** What `request_account` says about the router's voice line. */
    data class RouterAccount(
        val msisdn: String = "",
        val imsi: String = "",
        /** The router type, e.g. `JFV`. */
        val mode: String = "",
        val serialNumber: String = "",
        val macAddress: String = "",
        /** The line's services that are switched on (e.g. "Voice mail"). */
        val services: List<String> = emptyList(),
    )

    /** Reads `request_account`'s JSON: null unless it describes a voice line. */
    fun parseRouterAccount(json: String): RouterAccount? {
        val objectValue = runCatching { JSONObject(json) }.getOrNull() ?: return null
        fun text(key: String) = objectValue.optString(key, "").trim()
        val services = listOf(
            "VoiceMailSupport" to "Voice mail",
            "CentralizedCallWaiting" to "Call waiting",
            "CentralizedCallBlocking" to "Call blocking",
            "JTCAutoWhitelist" to "Auto whitelist",
        ).filter { (key, _) -> text(key).equals("true", ignoreCase = true) }.map { it.second }
        val account = RouterAccount(
            msisdn = text("msisdn"),
            imsi = text("imsi"),
            mode = text("mode"),
            serialNumber = text("SerialNumber"),
            macAddress = text("mac_address"),
            services = services,
        )
        return account.takeIf { it.msisdn.isNotEmpty() }
    }

    /** The SIP credentials from the router's configuration XML. */
    data class Credentials(
        /** The IMS user, e.g. `910000000000@ims.example`. */
        val username: String = "",
        val password: String = "",
        /** The IMS home domain: the registrar. */
        val homeDomain: String = "",
        val realm: String = "",
        /** How long the credentials last, in seconds, if the router said. */
        val validity: Long? = null,
    )

    /** Reads the router's configuration XML (`<parm name=… value=…/>`
     *  elements, at any depth; names compared without case). */
    fun parseCredentials(xml: String): Credentials {
        var username = ""
        var password = ""
        var homeDomain = ""
        var realm = ""
        var validity: Long? = null
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "parm") {
                    val name = parser.getAttributeValue(null, "name")
                    val value = parser.getAttributeValue(null, "value")?.trim()
                    if (name != null && value != null) {
                        when (name.lowercase()) {
                            "username" -> username = value
                            "userpwd" -> password = value
                            "home_network_domain_name" -> homeDomain = value
                            "realm" -> realm = value
                            "validity" -> validity = value.toLongOrNull()?.takeIf { it > 0 }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            throw JioException("the router's answer isn't valid XML: ${e.message}")
        }
        if (username.isEmpty() || password.isEmpty() || homeDomain.isEmpty()) {
            throw JioException("the router's answer has no SIP user, password or server")
        }
        return Credentials(username, password, homeDomain, realm, validity)
    }

    /** Fills in what the router decides: the IMS server, the router as proxy
     *  and domain (TLS, port 5068), the user (`+<number>@<domain>`, logging in
     *  without the `+`) and the password. The rest of `account` stays. */
    fun applyCredentials(account: Account, credentials: Credentials, router: String): Account {
        val proxy = "$router:$SIP_PORT"
        val user = credentials.username.trim().trimStart('+')
        return account.copy(
            server = credentials.homeDomain,
            proxy = proxy,
            domain = proxy,
            username = "+$user",
            authId = user,
            password = credentials.password,
            transport = Transport.TLS,
        )
    }

    /** A new, unsaved account with the router's credentials. */
    fun newAccount(router: String, mode: JioMode, credentials: Credentials, nowSeconds: Long = System.currentTimeMillis() / 1000): Account {
        val provisioned = applyCredentials(
            account = Account(label = "JioFiber", registerRefresh = 3600, enabled = true),
            credentials = credentials,
            router = router,
        )
        val expiresAt = credentials.validity?.let { nowSeconds + it }
        return provisioned.copy(
            provisioning = Provisioning(
                router = router,
                mode = mode,
                autoRefresh = mode == JioMode.TEMPORARY,
                expiresAt = expiresAt,
            ),
        )
    }

    /** Percent-encodes a query value like a form (`-._~` kept). */
    fun encode(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%').append(String.format("%02X", c))
            }
        }
        return out.toString()
    }

    /** `key=value&…`, percent-encoded like a form. */
    fun encodedQuery(query: List<Pair<String, String>>): String =
        query.joinToString("&") { (key, value) -> "$key=${encode(value)}" }

    /** A host for a URL: IPv6 addresses in brackets. */
    fun urlHost(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
}

class JioException(message: String) : Exception(message)
