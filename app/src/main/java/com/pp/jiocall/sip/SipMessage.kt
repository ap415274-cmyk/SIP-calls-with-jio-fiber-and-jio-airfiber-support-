package com.pp.jiocall.sip

/** A parsed SIP message: request or response. */
class SipMessage(
    val startLine: String,
    val headers: List<Pair<String, String>>,
    val body: String,
) {
    val isResponse: Boolean get() = startLine.startsWith("SIP/2.0")

    val statusCode: Int
        get() = if (isResponse) startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0 else 0

    val reason: String
        get() = if (isResponse) startLine.split(' ', limit = 3).getOrNull(2).orEmpty() else ""

    val method: String
        get() = if (isResponse) cseqMethod() else startLine.substringBefore(' ').trim()

    val requestUri: String
        get() = if (isResponse) "" else startLine.split(' ').getOrNull(1).orEmpty()

    /** The last header matching any of [names] (case-insensitive, supports compact RFC 3261 forms). */
    fun headerAny(vararg names: String): String? =
        headers.lastOrNull { (k, _) -> names.any { k.equals(it, ignoreCase = true) } }?.second

    fun headersAny(vararg names: String): List<String> =
        headers.filter { (k, _) -> names.any { k.equals(it, ignoreCase = true) } }.map { it.second }

    fun header(name: String): String? =
        headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun headers(name: String): List<String> =
        headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    fun cseq(): Long = header("CSeq")?.trim()?.split(' ')?.firstOrNull()?.toLongOrNull() ?: 0

    fun cseqMethod(): String = header("CSeq")?.trim()?.split(' ')?.getOrNull(1)?.trim().orEmpty()

    fun callId(): String = headerAny("Call-ID", "i")?.trim().orEmpty()

    fun fromTag(): String? = extractTag(headerAny("From", "f"))

    fun toTag(): String? = extractTag(headerAny("To", "t"))

    fun branch(): String? = headerAny("Via", "v")?.substringAfter("branch=", "")?.substringBefore(';')?.trim()
        ?.takeIf { it.isNotEmpty() }

    override fun toString(): String = "$startLine (${headers.size} headers, ${body.length} bytes)"

    companion object {
        /**
         * Extracts the `tag` parameter from a From/To header value.
         * Splits on `;` so that additional parameters after the tag
         * (e.g. `From: Alice <sip:alice@ex.com>;tag=abc123;other=x`)
         * don't get included in the returned value.
         */
        internal fun extractTag(header: String?): String? =
            header?.split(';')
                ?.drop(1)
                ?.mapNotNull {
                    val kv = it.trim().split('=', limit = 2)
                    if (kv.size == 2 && kv[0].trim().equals("tag", ignoreCase = true)) kv[1].trim() else null
                }
                ?.firstOrNull()
                ?.takeIf { it.isNotEmpty() }

        /** Parses a message whose header block (without the empty line) is
         *  [head] and whose body is [body]. */
        fun parse(head: String, body: String): SipMessage? {
            val lines = head.split("\r\n", "\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            val startLine = lines.first().trim()
            if (!startLine.startsWith("SIP/2.0") && startLine.split(' ').size < 3) return null
            val headers = mutableListOf<Pair<String, String>>()
            for (line in lines.drop(1)) {
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    val last = headers.removeLastOrNull() ?: continue
                    headers += last.first to (last.second + " " + line.trim())
                    continue
                }
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
            return SipMessage(startLine, headers, body)
        }

        /** Finds a header block in [data]: (start of blank line, body start). */
        fun headerEnd(data: ByteArray, offset: Int = 0): Int {
            var index = offset
            while (index + 3 < data.size) {
                if (data[index] == '\r'.code.toByte() && data[index + 1] == '\n'.code.toByte() &&
                    data[index + 2] == '\r'.code.toByte() && data[index + 3] == '\n'.code.toByte()
                ) {
                    return index
                }
                index++
            }
            return -1
        }
    }
}
