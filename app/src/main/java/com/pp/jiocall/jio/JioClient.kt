package com.pp.jiocall.jio

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import com.pp.jiocall.data.JioMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.util.Locale

/**
 * Talking to a JioFiber/AirFiber router for SIP credentials.
 *
 * The requests themselves live in [RouterHttp]: hand-written HTTP/1.1, byte
 * for byte what RusTone sends, because the router refuses anything else. The
 * credentials service uses HTTPS with the router's self-signed certificate, so
 * its TLS accepts any certificate: these requests only ever go to the router
 * on the local network. Everything blocks, so it runs on IO.
 */
class JioClient(private val context: Context) {

    /** This device's name, as the router and app know it. */
    fun hostname(): String {
        return (context.applicationContext as? com.pp.jiocall.App)?.deviceName ?: run {
            val model = Build.MODEL.trim()
            val brand = Build.BRAND.replaceFirstChar { it.uppercase() }
            if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
        }
    }

    /** Where to look for the router: its name, this device's default gateway,
     *  then the usual addresses; without repeats. */
    fun candidates(): List<String> {
        val hosts = mutableListOf(JioRouter.ROUTER_NAME)
        gateway()?.let { hosts += it }
        hosts += JioRouter.ROUTER_FALLBACK_IPS
        return hosts.distinct()
    }

    /** The active network's default gateway, if Android knows it. */
    private fun gateway(): String? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val properties = manager.getLinkProperties(network) ?: return null
        properties.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }.getOrNull()

    /** The first candidate that describes a voice line. */
    suspend fun discover(): Pair<String, JioRouter.RouterAccount>? = withContext(Dispatchers.IO) {
        for (host in candidates()) {
            val account = runCatching { requestAccount(host) }.getOrNull()
            if (account != null) return@withContext host to account
        }
        null
    }

    /** `http://<host>:8080/request_account`: the router's voice line. */
    suspend fun requestAccount(host: String): JioRouter.RouterAccount = withContext(Dispatchers.IO) {
        val target = host.trim()
        if (target.isEmpty()) throw JioException("enter the router's address")
        val response = RouterHttp.get(target, JioRouter.ACCOUNT_PORT, "/request_account")
        if (response.status !in 200..299) throw JioException("$target answered ${response.status}")
        JioRouter.parseRouterAccount(response.body)
            ?: throw JioException("$target has no SIP voice line")
    }

    /** What a credentials request got. */
    sealed interface CredentialsAnswer {
        data class Credentials(val credentials: JioRouter.Credentials) : CredentialsAnswer

        /** The router refused (for permanent credentials: this device isn't
         *  registered yet). [detail] is the router's own answer, if it said. */
        data class Refused(val status: Int, val detail: String = "") : CredentialsAnswer
    }

    /** Asks the router for this device's credentials. */
    suspend fun requestCredentials(host: String, name: String, mode: JioMode): CredentialsAnswer =
        withContext(Dispatchers.IO) {
            val query = JioRouter.encodedQuery(JioRouter.credentialsQuery(name, mode, register = false))
            val response = httpsGet(host, "/?$query")
            if (response.status != 200) {
                return@withContext CredentialsAnswer.Refused(response.status, shortDetail(response.body))
            }
            CredentialsAnswer.Credentials(JioRouter.parseCredentials(response.body))
        }

    /** New credentials of the kind an account already has (no OTP: a device
     *  the router no longer knows has to be connected again). */
    suspend fun renewCredentials(host: String, name: String, mode: JioMode): JioRouter.Credentials =
        when (val answer = requestCredentials(host, name, mode)) {
            is CredentialsAnswer.Credentials -> answer.credentials
            is CredentialsAnswer.Refused -> if (mode == JioMode.PERMANENT) {
                throw JioException(
                    "the router no longer has this device registered (${answer.status}); use Connect JioFiber/AirFiber again",
                )
            } else {
                throw JioException("the router refused new credentials (${answer.status})${detailSuffix(answer.detail)}")
            }
        }

    /** An OTP on its way: where to, and the router's session for checking it. */
    data class OtpSession(val sentTo: String?, internal val cookie: String)

    /** Registers this device for permanent credentials; the router sends an
     *  OTP to the line's mobile number. */
    suspend fun registerDevice(host: String, name: String): OtpSession = withContext(Dispatchers.IO) {
        // URL-encode spaces/special chars so multi-word Android model names don't break HTTP request lines,
        // while keeping the router's expected trailing `&`.
        val query = JioRouter.encodedQuery(JioRouter.credentialsQuery(name, JioMode.PERMANENT, register = true)) + "&"
        val response = httpsGet(host, "/?$query")
        if (response.status != 200) {
            throw JioException("the router didn't register this device (${response.status})${detailSuffix(response.body)}")
        }
        OtpSession(
            sentTo = response.header("X-AMN"),
            cookie = response.header("Set-Cookie").orEmpty(),
        )
    }

    /** Checks the OTP; on success the device is registered. */
    suspend fun verifyOtp(host: String, session: OtpSession, otp: String) = withContext(Dispatchers.IO) {
        val code = otp.trim()
        if (code.isEmpty() || !code.all { it.isDigit() }) throw JioException("enter the OTP's digits")
        val headers = if (session.cookie.isNotEmpty()) listOf("Cookie" to session.cookie) else emptyList()
        val response = httpsGet(host, "/?OTP=$code", headers)
        if (response.status != 200) {
            throw JioException("the router didn't accept the OTP (${response.status})${detailSuffix(response.body)}")
        }
        Unit
    }

    /** Whether the router still answers; used by the credential refresh. */
    suspend fun isReachable(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { requestAccount(host) }.isSuccess
    }

    private fun httpsGet(
        host: String,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
    ): RouterHttp.Response = RouterHttp.get(
        host = host,
        port = JioRouter.PROVISIONING_PORT,
        path = path,
        headers = headers,
        secure = true,
    )

    private fun detailSuffix(body: String): String {
        val text = shortDetail(body)
        return if (text.isEmpty()) "" else ": $text"
    }

    private fun shortDetail(body: String): String {
        val text = body.trim().replace(Regex("\\s+"), " ")
        return if (text.isEmpty() || text.length > 200) "" else text
    }
}
