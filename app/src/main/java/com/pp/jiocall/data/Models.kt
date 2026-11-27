package com.pp.jiocall.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** How SIP signalling is carried. */
enum class Transport(val label: String) {
    UDP("UDP"),
    TCP("TCP"),
    TLS("TLS");

    companion object {
        fun from(value: String?): Transport = entries.firstOrNull { it.name.equals(value, true) } ?: UDP
    }
}

/** Which credentials the router hands out. */
enum class JioMode(val label: String, val wire: String) {
    /** Issued without an OTP; they expire and can be requested again. */
    TEMPORARY("temporary", "eth"),

    /** Needs this device registered with an OTP once; they last. */
    PERMANENT("permanent", "wifi");

    companion object {
        fun from(value: String?): JioMode = entries.firstOrNull { it.name.equals(value, true) } ?: TEMPORARY
    }
}

/**
 * How an account was set up from a JioFiber/AirFiber router: the editor keeps
 * the router's fields locked and the credentials can be requested again.
 */
@Immutable
data class Provisioning(
    val router: String = "",
    val mode: JioMode = JioMode.TEMPORARY,
    val autoRefresh: Boolean = true,
    /** Unix seconds, if the router said. */
    val expiresAt: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("router", router)
        put("mode", mode.name.lowercase())
        put("auto_refresh", autoRefresh)
        if (expiresAt != null) put("expires_at", expiresAt) else put("expires_at", JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject?): Provisioning? {
            if (o == null || o == JSONObject.NULL) return null
            return Provisioning(
                router = o.optString("router", ""),
                mode = JioMode.from(o.optString("mode", "temporary")),
                autoRefresh = o.optBoolean("auto_refresh", true),
                expiresAt = if (o.isNull("expires_at")) null else o.optLong("expires_at").takeIf { it > 0 },
            )
        }
    }
}

/** A SIP account: every field of RusTone's `Account`, minus the parts this
 *  engine does not implement (SRTP, ICE). */
@Immutable
data class Account(
    val id: Long = 0,
    /** Name shown in menus; falls back to display name / username. */
    val label: String = "",
    /** Registrar host[:port]; empty means no registration. */
    val server: String = "",
    /** Outbound proxies, space-separated host[:port]. */
    val proxy: String = "",
    /** Domain appended to numbers that have no `@`. */
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    /** Authentication user if different from `username`. */
    val authId: String = "",
    val displayName: String = "",
    /** Prefix added to dialled numbers. */
    val dialingPrefix: String = "",
    /** Dial plan rules, `|`-separated: `pattern=>replacement`. */
    val dialPlan: String = "",
    /** Hide our caller ID on calls: `Privacy: id` and an anonymous From. */
    val hideCid: Boolean = false,
    /** Number dialled by the voicemail button. */
    val voicemailNumber: String = "",
    val transport: Transport = Transport.UDP,
    /** Public address to advertise in SDP. */
    val publicAddr: String = "",
    /** Registration expiry, seconds. */
    val registerRefresh: Int = 300,
    /** Keep-alive interval, seconds (0 disables it). */
    val keepAlive: Int = 15,
    val enabled: Boolean = true,
    val provisioning: Provisioning? = null,
) {
    /** The account's own number: the username without `sip:` or `@host`. */
    fun number(): String {
        val user = username.trim().removePrefix("sip:")
        return user.substringBefore('@')
    }

    /** The account's SIP address: `sip:user@host`, the host being the
     *  username's own or else the domain. */
    fun sipUri(): String {
        val user = username.trim().removePrefix("sip:")
        val host = domain.trim()
        return if (user.contains('@') || host.isEmpty()) "sip:$user" else "sip:$user@$host"
    }

    /** The host part of [sipUri], or the domain/server. */
    fun host(): String {
        val user = username.trim().removePrefix("sip:")
        if (user.contains('@')) return user.substringAfter('@').trim()
        return domain.trim().ifEmpty { server.trim() }
    }

    /** The host dialled requests go to: the account domain, as RusTone's
     *  `sip_uri()` does, falling back to the identity's host. */
    fun dialHost(): String = domain.trim().ifEmpty { host() }

    /** The name to show: label, else display name, else username, else server. */
    fun title(): String = listOf(label, displayName, username, server)
        .firstOrNull { it.trim().isNotEmpty() } ?: "Unnamed account"

    fun isUsable(): Boolean = username.trim().isNotEmpty() &&
        (server.trim().isNotEmpty() || domain.trim().isNotEmpty())

    fun proxies(): List<String> = proxy.split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Where signalling goes: the proxy if set, else the registrar. */
    fun signallingTarget(): Pair<String, Int> {
        val target = proxies().firstOrNull() ?: server.trim().ifEmpty { domain.trim() }
        val (host, port) = splitHostPort(target, defaultPort())
        return host to port
    }

    fun defaultPort(): Int = when (transport) {
        Transport.UDP, Transport.TCP -> 5060
        Transport.TLS -> 5061
    }

    /** The user to log in as: `authId`, else the username without `+`. */
    fun authUser(): String {
        val id = authId.trim()
        if (id.isNotEmpty()) return id
        return username.trim().removePrefix("sip:").substringBefore('@').removePrefix("+")
    }

    /** Whether `other` has exactly the same settings: every field but the id,
     *  and for router-made accounts but the credentials' expiry. */
    fun sameAs(other: Account): Boolean {
        fun comparable(a: Account) = a.copy(id = 0, provisioning = a.provisioning?.copy(expiresAt = null))
        return comparable(this) == comparable(other)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("server", server)
        put("proxy", proxy)
        put("domain", domain)
        put("username", username)
        put("password", password)
        put("auth_id", authId)
        put("display_name", displayName)
        put("dialing_prefix", dialingPrefix)
        put("dial_plan", dialPlan)
        put("hide_cid", hideCid)
        put("voicemail_number", voicemailNumber)
        put("transport", transport.name.lowercase())
        put("public_addr", publicAddr)
        put("register_refresh", registerRefresh)
        put("keep_alive", keepAlive)
        put("enabled", enabled)
        put("provisioning", provisioning?.toJson() ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Account = Account(
            id = o.optLong("id", 0),
            label = o.optString("label", ""),
            server = o.optString("server", ""),
            proxy = o.optString("proxy", ""),
            domain = o.optString("domain", ""),
            username = o.optString("username", ""),
            password = o.optString("password", ""),
            authId = o.optString("auth_id", ""),
            displayName = o.optString("display_name", ""),
            dialingPrefix = o.optString("dialing_prefix", ""),
            dialPlan = o.optString("dial_plan", ""),
            hideCid = o.optBoolean("hide_cid", false),
            voicemailNumber = o.optString("voicemail_number", ""),
            transport = Transport.from(o.optString("transport", "udp")),
            publicAddr = o.optString("public_addr", ""),
            registerRefresh = o.optInt("register_refresh", 300),
            keepAlive = o.optInt("keep_alive", 15),
            enabled = o.optBoolean("enabled", true),
            provisioning = Provisioning.fromJson(o.opt("provisioning") as? JSONObject),
        )

        fun fromArray(array: JSONArray): List<Account> = (0 until array.length()).mapNotNull { index ->
            (array.opt(index) as? JSONObject)?.let { runCatching { fromJson(it) }.getOrNull() }
        }
    }
}

/** App-wide settings (RusTone's `settings.toml`). */
@Immutable
data class AppSettings(
    /** Codecs to offer, in order, as `NAME/clock/rate` (e.g. `AMR-WB/16000/1`). */
    val audioCodecs: String = "AMR-WB/16000/1 AMR/8000/1 PCMA/8000/1 PCMU/8000/1",
    /** Default to earpiece on voice calls unless user enables loudspeaker by default. */
    val speakerphone: Boolean = false,
    /** Cancel the echo of our own voice; off if the device's AEC is bad. */
    val echoCancellation: Boolean = true,
    val ringtone: Boolean = true,
    /** Request new temporary router credentials automatically. */
    val autoRefreshRouter: Boolean = true,
    /** Keep registered in the background with a foreground service. */
    val backgroundService: Boolean = true,
    /** Automatically start background service on device reboot. */
    val bootStart: Boolean = true,
    /** Automatically record answered calls locally as WAV files. */
    val autoRecord: Boolean = false,
    /** Dark mode theme (Google Phone app dark style). */
    val darkMode: Boolean = false,
    /** Do Not Disturb: silently rejects incoming calls with 486. */
    val dnd: Boolean = false,
    /** Call waiting: alert user with in-call beeps when a second call arrives. */
    val callWaiting: Boolean = true,
    /** Call forwarding: forward incoming calls via SIP 302. */
    val forwarding: Boolean = false,
    val forwardingNumber: String = "",
    /** Custom dial plan rules (Linksys/Cisco format, e.g. <0:91>x. or x.). */
    val customDialPlan: String = "",
) {
    fun codecs(): List<String> = audioCodecs.split(Regex("[,\\s]+"))
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("audio_codecs", audioCodecs)
        put("speakerphone", speakerphone)
        put("echo_cancellation", echoCancellation)
        put("ringtone", ringtone)
        put("auto_refresh_router", autoRefreshRouter)
        put("background_service", backgroundService)
        put("boot_start", bootStart)
        put("auto_record", autoRecord)
        put("dark_mode", darkMode)
        put("dnd", dnd)
        put("call_waiting", callWaiting)
        put("forwarding", forwarding)
        put("forwarding_number", forwardingNumber)
        put("custom_dial_plan", customDialPlan)
    }

    companion object {
        fun fromJson(o: JSONObject): AppSettings = AppSettings(
            audioCodecs = o.optString("audio_codecs", AppSettings().audioCodecs),
            speakerphone = o.optBoolean("speakerphone", false),
            echoCancellation = o.optBoolean("echo_cancellation", true),
            ringtone = o.optBoolean("ringtone", true),
            autoRefreshRouter = o.optBoolean("auto_refresh_router", true),
            backgroundService = o.optBoolean("background_service", true),
            bootStart = o.optBoolean("boot_start", true),
            autoRecord = o.optBoolean("auto_record", false),
            darkMode = o.optBoolean("dark_mode", false),
            dnd = o.optBoolean("dnd", false),
            callWaiting = o.optBoolean("call_waiting", true),
            forwarding = o.optBoolean("forwarding", false),
            forwardingNumber = o.optString("forwarding_number", ""),
            customDialPlan = o.optString("custom_dial_plan", ""),
        )
    }
}

/** One line of the call log. */
@Immutable
data class CallLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val number: String = "",
    val name: String = "",
    val accountTitle: String = "",
    val outgoing: Boolean = true,
    val missed: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0,
    val recordingPath: String? = null,
) {
    val durationSec: Long get() = durationSeconds

    fun formattedTimestamp(now: Long = System.currentTimeMillis()): String {
        val diff = now - startedAt
        return when {
            diff < 60_000L -> "Just now"
            diff < 3600_000L -> "${(diff / 60_000L).coerceAtLeast(1)}m ago"
            diff < 86400_000L -> TIME_FMT.get()!!.format(Date(startedAt))
            diff < 7 * 86400_000L -> WEEK_FMT.get()!!.format(Date(startedAt))
            else -> DATE_FMT.get()!!.format(Date(startedAt))
        }
    }

    fun dayBucket(nowMs: Long = System.currentTimeMillis()): String {
        val diff = nowMs - startedAt
        if (diff < 0) return "Today"
        val calNow = CALENDAR_TL.get()!!.apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = calNow.timeInMillis
        val yesterdayStart = todayStart - 86_400_000L
        return when {
            startedAt >= todayStart -> "Today"
            startedAt >= yesterdayStart -> "Yesterday"
            else -> "Earlier"
        }
    }

    fun formattedDuration(): String {
        val mins = durationSeconds / 60
        val secs = durationSeconds % 60
        return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("number", number)
        put("name", name)
        put("account", accountTitle)
        put("outgoing", outgoing)
        put("missed", missed)
        put("started_at", startedAt)
        put("duration", durationSeconds)
        if (recordingPath != null) put("recording_path", recordingPath)
    }

    companion object {
        private val TIME_FMT = ThreadLocal.withInitial { SimpleDateFormat("h:mm a", Locale.getDefault()) }
        private val WEEK_FMT = ThreadLocal.withInitial { SimpleDateFormat("EEE, h:mm a", Locale.getDefault()) }
        private val DATE_FMT = ThreadLocal.withInitial { SimpleDateFormat("MMM d", Locale.getDefault()) }
        private val CALENDAR_TL = ThreadLocal.withInitial { Calendar.getInstance() }

        fun fromJson(o: JSONObject): CallLogEntry = CallLogEntry(
            id = o.optString("id", UUID.randomUUID().toString()),
            number = o.optString("number", ""),
            name = o.optString("name", ""),
            accountTitle = o.optString("account", ""),
            outgoing = o.optBoolean("outgoing", true),
            missed = o.optBoolean("missed", false),
            startedAt = o.optLong("started_at", System.currentTimeMillis()),
            durationSeconds = o.optLong("duration", 0),
            recordingPath = if (o.has("recording_path") && !o.isNull("recording_path")) o.optString("recording_path").takeIf { it.isNotBlank() } else null,
        )
    }
}

/** Splits `host[:port]`, bracketing IPv6, and returns the default port if absent. */
fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int> {
    val text = value.trim().removePrefix("sip:").removePrefix("sips:")
    if (text.isEmpty()) return "" to defaultPort
    if (text.startsWith("[")) {
        val end = text.indexOf(']')
        if (end > 0) {
            val host = text.substring(1, end)
            val rest = text.substring(end + 1)
            val port = rest.removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port
        }
    }
    val colon = text.lastIndexOf(':')
    if (colon > 0 && text.indexOf(':') == colon) {
        val port = text.substring(colon + 1).toIntOrNull()
        if (port != null) return text.substring(0, colon) to port
    }
    return text to defaultPort
}
