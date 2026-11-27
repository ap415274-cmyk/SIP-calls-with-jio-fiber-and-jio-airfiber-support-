package com.pp.jiocall.jio

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The router's HTTP, written by hand the way RusTone's `jiofiber.rs` writes it:
 * `Host:` is the bare host (no port), the only other header is
 * `Connection: close`, there is no `Accept`, `Accept-Encoding` or `User-Agent`,
 * redirects are not followed and the body is not decompressed. Jio routers are
 * strict about this — an ordinary HTTP client gets 403.
 *
 * Kept free of Android types so it can be tested on the JVM.
 */
internal object RouterHttp {

    class Response(
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: String,
    ) {
        /** The last header named [name] (case-insensitive). */
        fun header(name: String): String? =
            headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    fun get(
        host: String,
        port: Int,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
        secure: Boolean = false,
    ): Response {
        val target = host.trim()
        val address = try {
            InetAddress.getByName(target)
        } catch (e: Exception) {
            throw JioException("cannot find $target: ${e.message}")
        }

        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = IO_TIMEOUT_MS
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw JioException("cannot reach $target: ${e.message}")
        }

        var socket: Socket = raw
        if (secure) {
            socket = try {
                (insecureContext().socketFactory.createSocket(raw, target, port, true) as SSLSocket).also {
                    runCatching { it.startHandshake() }
                }
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw JioException("cannot talk to $target: ${e.message}")
            }
        }

        try {
            val head = StringBuilder()
            head.append("GET $path HTTP/1.1\r\n")
            head.append("Host: ${JioRouter.urlHost(target)}\r\n")
            head.append("Connection: close\r\n")
            for ((name, value) in headers) head.append("$name: $value\r\n")
            head.append("\r\n")
            val output = socket.getOutputStream()
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()

            // Routers often close without TLS's close_notify; what came is the answer.
            val input = socket.getInputStream()
            val collected = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val read = runCatching { input.read(buffer) }.getOrElse { -1 }
                if (read < 0) break
                collected.write(buffer, 0, read)
            }
            val bytes = collected.toByteArray()
            if (bytes.isEmpty()) throw JioException("no answer from $target")
            return parseResponse(bytes)
        } catch (e: JioException) {
            throw e
        } catch (e: Exception) {
            throw JioException("cannot talk to $target: ${e.message}")
        } finally {
            runCatching { socket.close() }
            runCatching { raw.close() }
        }
    }

    /** Reads the status line, headers and (chunked or plain) body. */
    fun parseResponse(raw: ByteArray): Response {
        val text = String(raw, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        if (split < 0) throw JioException("the router's answer has no HTTP header")
        val head = text.substring(0, split)
        val lines = head.split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw JioException("unexpected answer: $statusLine")
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        var body = raw.copyOfRange(split + 4, raw.size)
        val chunked = headers.any { (name, value) ->
            name.equals("Transfer-Encoding", ignoreCase = true) && value.contains("chunked", ignoreCase = true)
        }
        if (chunked) body = dechunk(body)
        return Response(status, headers, String(body, Charsets.UTF_8))
    }

    /** The data of a `Transfer-Encoding: chunked` body. */
    fun dechunk(body: ByteArray): ByteArray {
        var data = body
        val out = ByteArrayOutputStream()
        while (true) {
            var index = 0
            while (index + 1 < data.size &&
                !(data[index] == '\r'.code.toByte() && data[index + 1] == '\n'.code.toByte())
            ) {
                index++
            }
            if (index + 1 >= data.size) break
            val sizeText = String(data, 0, index, Charsets.ISO_8859_1).substringBefore(';').trim()
            val size = sizeText.toIntOrNull(16) ?: break
            val start = index + 2
            if (size == 0 || start + size > data.size) break
            out.write(data, start, size)
            val next = start + size + 2
            if (next >= data.size) break
            data = data.copyOfRange(next, data.size)
        }
        return out.toByteArray()
    }

    /** Accepts the router's certificate as it is: self-signed, and an old
     *  certificate Android may not even be able to check. */
    private fun insecureContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), null)
        }
    }

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val IO_TIMEOUT_MS = 30_000
}
