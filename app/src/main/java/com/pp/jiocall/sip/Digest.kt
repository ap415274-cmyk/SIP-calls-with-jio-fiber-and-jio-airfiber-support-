package com.pp.jiocall.sip

import java.security.MessageDigest

/** MD5 digest authentication (RFC 2617), the scheme Jio's IMS and most PBXs use. */
object Digest {
    data class Challenge(
        val realm: String,
        val nonce: String,
        val algorithm: String = "MD5",
        val qop: List<String> = emptyList(),
        val opaque: String? = null,
        val stale: Boolean = false,
    )

    fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it) }
    }

    /** Reads a `WWW-Authenticate` / `Proxy-Authenticate` value. */
    fun parseChallenge(header: String): Challenge? {
        val text = header.trim().substringAfter("Digest", "").ifEmpty { return null }
        val fields = mutableMapOf<String, String>()
        val regex = Regex("([a-zA-Z0-9_-]+)=(\"([^\"]*)\"|[^,\\s]+)")
        regex.findAll(text).forEach { match ->
            fields[match.groupValues[1].lowercase()] = match.groupValues[3].ifEmpty { match.groupValues[2] }
        }
        val realm = fields["realm"] ?: return null
        val nonce = fields["nonce"] ?: return null
        return Challenge(
            realm = realm,
            nonce = nonce,
            algorithm = fields["algorithm"] ?: "MD5",
            qop = fields["qop"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            opaque = fields["opaque"],
            stale = fields["stale"].equals("true", ignoreCase = true),
        )
    }

    /** The value of an `Authorization` (or `Proxy-Authorization`) header. */
    fun authorization(
        challenge: Challenge,
        username: String,
        password: String,
        method: String,
        uri: String,
        cnonce: String,
        nonceCount: Int,
    ): String {
        val ha1 = md5("$username:${challenge.realm}:$password")
        val ha2 = md5("$method:$uri")
        val qop = when {
            challenge.qop.any { it.equals("auth", true) } -> "auth"
            else -> null
        }
        val nc = String.format("%08x", nonceCount)
        val response = if (qop != null) {
            md5("$ha1:${challenge.nonce}:$nc:$cnonce:$qop:$ha2")
        } else {
            md5("$ha1:${challenge.nonce}:$ha2")
        }
        val parts = mutableListOf(
            "username=\"$username\"",
            "realm=\"${challenge.realm}\"",
            "nonce=\"${challenge.nonce}\"",
            "uri=\"$uri\"",
            "response=\"$response\"",
            "algorithm=MD5",
        )
        if (qop != null) {
            parts += "qop=$qop"
            parts += "nc=$nc"
            parts += "cnonce=\"$cnonce\""
        }
        challenge.opaque?.let { parts += "opaque=\"$it\"" }
        return "Digest " + parts.joinToString(", ")
    }
}
