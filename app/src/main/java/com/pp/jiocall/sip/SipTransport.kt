package com.pp.jiocall.sip

import android.util.Log
import com.pp.jiocall.data.Transport
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * One SIP connection: datagrams for UDP, a byte stream for TCP and TLS.
 * Messages come back through [onMessage]; the reader threads are daemons.
 */
internal class SipTransport(
    private val transport: Transport,
    private val host: String,
    private val port: Int,
    private val trustAll: Boolean,
    private val onMessage: (SipMessage) -> Unit,
    private val onClosed: (String) -> Unit,
    private val debugLogging: Boolean = false,
) {
    @Volatile var localIp: String = ""
        private set
    @Volatile var localPort: Int = 0
        private set

    private var datagram: DatagramSocket? = null
    private var stream: Socket? = null
    private var output: OutputStream? = null
    private val closed = AtomicBoolean(false)
    private var remoteAddress: InetAddress? = null
    private val outbox = java.util.concurrent.LinkedBlockingQueue<String>()

    @Throws(Exception::class)
    fun connect() {
        val all = runCatching { InetAddress.getAllByName(host) }.getOrNull().orEmpty()
        val address = all.firstOrNull { it is java.net.Inet4Address }
            ?: all.firstOrNull()
            ?: InetAddress.getByName(host)
        remoteAddress = address
        when (transport) {
            Transport.UDP -> {
                val probe = DatagramSocket()
                runCatching { probe.connect(address, port) }
                localIp = runCatching { probe.localAddress?.hostAddress.orEmpty() }.getOrDefault("")
                probe.close()
                val socket = DatagramSocket()
                datagram = socket
                localPort = socket.localPort
                if (localIp.isEmpty()) {
                    localIp = runCatching { socket.localAddress?.hostAddress.orEmpty() }.getOrDefault("")
                }
                startDaemon("sip-udp-rx") { receiveDatagrams(socket) }
            }

            Transport.TCP, Transport.TLS -> {
                val raw = Socket()
                raw.tcpNoDelay = true
                raw.keepAlive = true
                raw.soTimeout = 8_000
                raw.connect(InetSocketAddress(address, port), 5_000)
                val socket: Socket = if (transport == Transport.TLS) {
                    val useRouterTrust = trustAll && (address.isSiteLocalAddress || address.isLoopbackAddress)
                    val factory: SSLSocketFactory = if (useRouterTrust) trustAllFactory() else SSLSocketFactory.getDefault() as SSLSocketFactory
                    val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
                    // Restrict to TLS 1.2+ BEFORE the handshake — setting it after has no effect.
                    runCatching { ssl.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3") }
                    ssl.startHandshake()
                    ssl
                } else {
                    raw
                }
                socket.soTimeout = 0
                stream = socket
                output = socket.outputStream
                localIp = socket.localAddress?.hostAddress.orEmpty()
                localPort = socket.localPort
                startDaemon("sip-stream-rx") { receiveStream(socket.getInputStream()) }
            }
        }
        // Writes happen on their own thread: calling code runs on the UI thread,
        // and Android throws NetworkOnMainThreadException for a socket write
        // there (that used to kill every call the moment it was dialled).
        startDaemon("sip-tx") { writeLoop() }
    }

    /** Queues a message; the sip-tx thread owns every write. Never blocks. */
    fun send(text: String) {
        if (text.isEmpty() || closed.get()) return
        outbox.offer(text)
    }

    /** Waits (bounded) for queued messages to reach the socket. */
    fun drain(timeoutMillis: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (outbox.isNotEmpty() && !closed.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        outbox.clear()
        runCatching { datagram?.close() }
        runCatching { stream?.close() }
        datagram = null
        stream = null
        output = null
    }

    private fun writeLoop() {
        while (!closed.get()) {
            val text = runCatching { outbox.take() }.getOrNull() ?: continue
            if (closed.get()) return
            if (text.isNotBlank()) {
                if (debugLogging) Log.d(TAG, ">>> " + text.take(2_000))
                SipLogBuffer.log(SipDirection.TX, transport.name, "$host:$port", text)
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            when (transport) {
                Transport.UDP -> {
                    val socket = datagram ?: return
                    val address = remoteAddress ?: return
                    runCatching { socket.send(DatagramPacket(bytes, bytes.size, address, port)) }
                        .onFailure { Log.w(TAG, "send failed", it) }
                }

                else -> {
                    val out = output ?: return
                    val sent = runCatching {
                        out.write(bytes)
                        out.flush()
                    }.onFailure { Log.w(TAG, "send failed", it) }
                    if (sent.isFailure) {
                        if (!closed.get()) onClosed(sent.exceptionOrNull()?.message ?: "connection lost")
                        return
                    }
                }
            }
        }
    }

    /** TCP and TLS keep the connection open; an idle socket needs traffic. */
    fun keepAlive() {
        if (transport == Transport.UDP) return
        send("\r\n\r\n")
    }

    private fun receiveDatagrams(socket: DatagramSocket) {
        val buffer = ByteArray(65_535)
        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            val read = runCatching { socket.receive(packet) }
            if (read.isFailure) break
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            deliver(text)
        }
        if (!closed.get()) onClosed("connection closed")
    }

    private fun receiveStream(input: InputStream) {
        val buffer = ByteArray(8_192)
        val pending = java.io.ByteArrayOutputStream()
        var failure: String? = null
        while (!closed.get()) {
            val read = runCatching { input.read(buffer) }
                .onFailure { failure = it.message ?: it.javaClass.simpleName }
                .getOrElse { -1 }
            if (read < 0) break
            pending.write(buffer, 0, read)
            val rawData = pending.toByteArray()
            var cursor = 0
            while (cursor < rawData.size) {
                while (cursor < rawData.size &&
                    (rawData[cursor] == '\r'.code.toByte() || rawData[cursor] == '\n'.code.toByte())
                ) {
                    cursor++
                }
                if (cursor >= rawData.size) break
                val headerEnd = SipMessage.headerEnd(rawData, cursor)
                if (headerEnd < 0) break
                val head = String(rawData, cursor, headerEnd - cursor, Charsets.ISO_8859_1)
                val length = contentLength(head)
                val totalEnd = headerEnd + 4 + length
                if (rawData.size < totalEnd) break
                val body = String(rawData, headerEnd + 4, length, Charsets.UTF_8)
                cursor = totalEnd
                if (debugLogging) {
                    Log.d(TAG, "<<< " + head.take(1_500) + if (body.isNotEmpty()) "\n\n" + body.take(1_000) else "")
                }
                val fullPacket = if (body.isNotEmpty()) "$head\r\n\r\n$body" else head
                SipLogBuffer.log(SipDirection.RX, transport.name, "$host:$port", fullPacket)
                SipMessage.parse(head, body)?.let { message -> runCatching { onMessage(message) } }
            }
            pending.reset()
            if (cursor < rawData.size) {
                pending.write(rawData, cursor, rawData.size - cursor)
            }
        }
        if (!closed.get()) {
            val why = failure?.let { "connection lost: $it" } ?: "connection closed by the server"
            Log.w(TAG, "stream ended: $why")
            onClosed(why)
        }
    }

    private fun deliver(text: String) {
        val trim = text.trimStart('\r', '\n', ' ')
        if (trim.isEmpty()) return
        val headerEnd = trim.indexOf("\r\n\r\n")
        val head: String
        val body: String
        if (headerEnd < 0) {
            head = trim
            body = ""
        } else {
            head = trim.substring(0, headerEnd)
            body = trim.substring(headerEnd + 4)
        }
        if (debugLogging) Log.d(TAG, "<<< " + head.take(1_500))
        val fullPacket = if (body.isNotEmpty()) "$head\r\n\r\n$body" else head
        SipLogBuffer.log(SipDirection.RX, transport.name, "$host:$port", fullPacket)
        SipMessage.parse(head, body)?.let { message ->
            runCatching { onMessage(message) }
        }
    }

    private fun contentLength(head: String): Int =
        Regex("(?im)^(?:Content-Length|l)\\s*:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun startDaemon(name: String, body: () -> Unit) {
        Thread({
            runCatching { body() }
        }, name).apply { isDaemon = true; start() }
    }

    private companion object {
        const val TAG = "JioCallSip"
    }

    private fun trustAllFactory(): SSLSocketFactory {
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAllManager), null)
        return context.socketFactory
    }
}
