package com.pp.jiocall

import com.pp.jiocall.jio.RouterHttp
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * The router's requests, against a real local HTTP server: they must look
 * exactly like RusTone's — no port in `Host`, no extra headers, no redirect
 * following, and a chunked body still readable.
 */
class RouterHttpTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val seenHost = AtomicReference<String?>(null)
    private val seenAgent = AtomicReference<String?>(null)
    private val redirectTarget = AtomicReference<String?>(null)

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/request_account") { exchange ->
            seenHost.set(exchange.requestHeaders.getFirst("Host"))
            seenAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            val body = """{"msisdn":"5550100001","mode":"JFV","SerialNumber":"TEST0001","VoiceMailSupport":"true"}"""
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0) // chunked
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/refused") { exchange ->
            val body = "device not allowed".toByteArray()
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/redirected")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/redirected") { exchange ->
            redirectTarget.set("hit")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun hostHasNoPortAndNoBrowserHeaders() {
        val response = RouterHttp.get("127.0.0.1", port, "/request_account")
        assertEquals(200, response.status)
        assertEquals("127.0.0.1", seenHost.get())
        assertNull("an ordinary HTTP client's User-Agent must not be sent", seenAgent.get())
        assertTrue(response.body.contains("\"msisdn\":\"5550100001\""))
    }

    @Test
    fun chunkedBodiesAreRead() {
        val response = RouterHttp.get("127.0.0.1", port, "/request_account")
        assertTrue(
            "chunked body should arrive whole, was: ${response.body}",
            response.body.endsWith("}"),
        )
        assertTrue(response.headers.any { (name, value) ->
            name.equals("Transfer-Encoding", true) && value.contains("chunked", true)
        })
    }

    @Test
    fun refusalsKeepTheirStatusAndExplanation() {
        val response = RouterHttp.get("127.0.0.1", port, "/refused")
        assertEquals(403, response.status)
        assertEquals("device not allowed", response.body)
    }

    @Test
    fun redirectsAreNotFollowed() {
        val response = RouterHttp.get("127.0.0.1", port, "/redirect")
        assertEquals(302, response.status)
        assertNull("HttpURLConnection would have followed this", redirectTarget.get())
        assertEquals("/redirected", response.header("Location"))
    }

}
