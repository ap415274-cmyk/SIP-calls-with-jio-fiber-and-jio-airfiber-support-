package com.pp.jiocall

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.AppSettings
import com.pp.jiocall.data.JioMode
import com.pp.jiocall.data.Provisioning
import com.pp.jiocall.data.Store
import com.pp.jiocall.data.Transport
import com.pp.jiocall.sip.CallState
import com.pp.jiocall.sip.RegState
import com.pp.jiocall.sip.SipAccount
import com.pp.jiocall.sip.SipCall
import com.pp.jiocall.sip.SipEngine
import com.pp.jiocall.sip.SipMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end JVM recovery tests over real loopback TCP SIP sockets covering long-idle zombie
 * transports, outgoing calls dialed while unregistered, 403 credential refresh + INVITE retry,
 * and SipEngine in-place credential rotation without dropping the active/recovering call.
 */
class SipLifecycleRecoveryTest {

    @Test
    fun ensureHealthyRegistrationDropsSilentZombieSocketAndReconnects() {
        val states = CopyOnWriteArrayList<RegState>()
        val registerCountOnConn1 = AtomicInteger(0)
        val server = FakeTcpSipServer { connId, _, out, msg ->
            if (msg.method == "REGISTER") {
                if (connId == 1) {
                    val count = registerCountOnConn1.incrementAndGet()
                    if (count == 1) {
                        sendSipResponse(
                            out = out,
                            req = msg,
                            code = 200,
                            reason = "OK",
                            extraHeaders = mapOf("Expires" to "60"),
                        )
                    }
                    // Subsequent REGISTER probes on conn #1 intentionally go unanswered (zombie socket).
                } else {
                    sendSipResponse(
                        out = out,
                        req = msg,
                        code = 200,
                        reason = "OK",
                        extraHeaders = mapOf("Expires" to "60"),
                    )
                }
            }
        }

        server.use {
            val agent = SipAccount(
                context = newTestContext(),
                initial = testAccount(server.port),
                settings = { AppSettings() },
                onRegistration = { _, state, _, _ -> states += state },
                onIncoming = { _, _ -> },
                onCallUpdate = { _, _ -> },
            )
            try {
                agent.start()
                awaitCondition(5_000L, "initial REGISTER 200 OK; states=$states") {
                    states.contains(RegState.REGISTERED)
                }

                // Simulate 10 minutes of idle Doze silence so hasStaleTransport() triggers a probe.
                agent.markStaleForTesting(idleMs = 600_000L)
                assertTrue(agent.hasStaleTransport(300_000L))
                assertTrue(agent.isRegistrationStale())

                agent.ensureHealthyRegistration(forceProbe = true)

                // Wait past the 10s watchdog for conn #1 to drop and conn #2 to complete REGISTER.
                awaitCondition(16_000L, "watchdog reconnect onto socket #2; conns=${server.acceptedCount}, states=$states") {
                    server.acceptedCount >= 2 &&
                        states.count { it == RegState.REGISTERED } >= 2
                }
            } finally {
                agent.stop(unregister = false)
            }
        }
    }

    @Test
    fun dialWhileUnregisteredWaitsForRegistrationAndReachesActive() {
        val states = CopyOnWriteArrayList<RegState>()
        val callStates = CopyOnWriteArrayList<CallState>()
        val byeReceived = AtomicBoolean(false)
        val server = FakeTcpSipServer { connId, socket, out, msg ->
            when (msg.method) {
                "REGISTER" -> {
                    if (connId == 1) {
                        sendSipResponse(out, msg, 503, "Service Unavailable")
                        socket.close()
                    } else {
                        sendSipResponse(out, msg, 200, "OK", extraHeaders = mapOf("Expires" to "60"))
                    }
                }
                "INVITE" -> {
                    sendSipResponse(out, msg, 100, "Trying")
                    sendSipResponse(out, msg, 180, "Ringing")
                    sendSipResponse(
                        out = out,
                        req = msg,
                        code = 200,
                        reason = "OK",
                        extraHeaders = mapOf(
                            "Contact" to "<sip:srv@127.0.0.1:${socket.localPort};transport=tcp>",
                            "Content-Type" to "application/sdp",
                        ),
                        body = LOOPBACK_PCMU_SDP,
                    )
                }
                "BYE" -> {
                    byeReceived.set(true)
                    sendSipResponse(out, msg, 200, "OK")
                }
            }
        }

        server.use {
            val agent = SipAccount(
                context = newTestContext(),
                initial = testAccount(server.port),
                settings = { AppSettings() },
                onRegistration = { _, state, _, _ -> states += state },
                onIncoming = { _, _ -> },
                onCallUpdate = { _, call -> callStates += call.state },
            )
            try {
                agent.start()
                awaitCondition(5_000L, "conn #1 failure before dial; states=$states") {
                    states.contains(RegState.FAILED)
                }

                // Dial while unregistered: must recover registration instead of ending immediately.
                val call = agent.dial("+919876543210")
                assertNotNull("Expected outgoing call handle even while unregistered", call)
                call!!

                awaitCondition(8_000L, "outgoing call reaching ACTIVE; callStates=$callStates, regStates=$states") {
                    call.state == CallState.ACTIVE
                }

                agent.hangup(call)
                awaitCondition(3_000L, "BYE acknowledged after hangup") {
                    byeReceived.get() && call.state == CallState.ENDED
                }
            } finally {
                agent.stop(unregister = false)
            }
        }
    }

    @Test
    fun invite403RefreshesCredentialsReRegistersAndRetriesCall() {
        val states = CopyOnWriteArrayList<RegState>()
        val forceRefreshCalls = AtomicInteger(0)
        val inviteCount = AtomicInteger(0)
        val agentRef = AtomicReference<SipAccount?>()
        val initialAccount = testAccount(port = 0, password = "stale-pass")

        val server = FakeTcpSipServer { _, socket, out, msg ->
            when (msg.method) {
                "REGISTER" -> {
                    val auth = msg.header("Authorization")
                    if (auth == null) {
                        sendSipResponse(
                            out = out,
                            req = msg,
                            code = 401,
                            reason = "Unauthorized",
                            extraHeaders = mapOf(
                                "WWW-Authenticate" to "Digest realm=\"127.0.0.1\", nonce=\"nonce1\", algorithm=MD5",
                            ),
                        )
                    } else if (matchesDigest(auth, "REGISTER", "911234567890", "127.0.0.1", "stale-pass") ||
                        matchesDigest(auth, "REGISTER", "911234567890", "127.0.0.1", "fresh-pass")
                    ) {
                        sendSipResponse(out, msg, 200, "OK", extraHeaders = mapOf("Expires" to "60"))
                    } else {
                        sendSipResponse(out, msg, 403, "Forbidden")
                    }
                }
                "INVITE" -> {
                    val attempt = inviteCount.incrementAndGet()
                    if (attempt == 1) {
                        sendSipResponse(out, msg, 403, "Forbidden")
                    } else {
                        sendSipResponse(out, msg, 180, "Ringing")
                        sendSipResponse(
                            out = out,
                            req = msg,
                            code = 200,
                            reason = "OK",
                            extraHeaders = mapOf(
                                "Contact" to "<sip:srv@127.0.0.1:${socket.localPort};transport=tcp>",
                                "Content-Type" to "application/sdp",
                            ),
                            body = LOOPBACK_PCMU_SDP,
                        )
                    }
                }
                "BYE" -> sendSipResponse(out, msg, 200, "OK")
            }
        }

        server.use {
            val account = initialAccount.copy(
                server = "127.0.0.1:${server.port}",
            )
            val agent = SipAccount(
                context = newTestContext(),
                initial = account,
                settings = { AppSettings() },
                onRegistration = { _, state, _, _ -> states += state },
                onIncoming = { _, _ -> },
                onCallUpdate = { _, _ -> },
                onCredentialRefreshRequested = { _, force ->
                    if (force) {
                        forceRefreshCalls.incrementAndGet()
                        agentRef.get()?.updateAccount(account.copy(password = "fresh-pass"))
                    }
                },
            )
            agentRef.set(agent)
            try {
                agent.start()
                awaitCondition(5_000L, "initial registration before INVITE; states=$states") {
                    states.contains(RegState.REGISTERED)
                }

                val call = agent.dial("+919876543210")!!
                awaitCondition(8_000L, "INVITE retry reaching ACTIVE; state=${call.state}, invites=${inviteCount.get()}") {
                    call.state == CallState.ACTIVE
                }
                assertEquals(1, forceRefreshCalls.get())
                assertEquals(2, inviteCount.get())
                agent.hangup(call)
            } finally {
                agent.stop(unregister = false)
            }
        }
    }

    @Test
    fun sipEngineApplyRotatesProvisionedCredentialsWithoutKillingRecoveringCall() {
        val inviteCount = AtomicInteger(0)
        val refreshCount = AtomicInteger(0)
        val freshAuthAccepted = AtomicBoolean(false)
        val server = FakeTcpSipServer { _, socket, out, msg ->
            when (msg.method) {
                "REGISTER" -> {
                    val auth = msg.header("Authorization")
                    when {
                        auth == null -> sendSipResponse(
                            out = out,
                            req = msg,
                            code = 401,
                            reason = "Unauthorized",
                            extraHeaders = mapOf(
                                "WWW-Authenticate" to "Digest realm=\"127.0.0.1\", nonce=\"nonce2\", algorithm=MD5",
                            ),
                        )
                        matchesDigest(auth, "REGISTER", "911234567890", "127.0.0.1", "fresh-pass") -> {
                            freshAuthAccepted.set(true)
                            sendSipResponse(out, msg, 200, "OK", extraHeaders = mapOf("Expires" to "60"))
                        }
                        matchesDigest(auth, "REGISTER", "911234567890", "127.0.0.1", "stale-pass") -> {
                            sendSipResponse(out, msg, 200, "OK", extraHeaders = mapOf("Expires" to "60"))
                        }
                        else -> sendSipResponse(out, msg, 403, "Forbidden")
                    }
                }
                "INVITE" -> {
                    if (inviteCount.incrementAndGet() == 1) {
                        sendSipResponse(out, msg, 403, "Forbidden")
                    } else {
                        sendSipResponse(out, msg, 180, "Ringing")
                        sendSipResponse(
                            out = out,
                            req = msg,
                            code = 200,
                            reason = "OK",
                            extraHeaders = mapOf(
                                "Contact" to "<sip:srv@127.0.0.1:${socket.localPort};transport=tcp>",
                                "Content-Type" to "application/sdp",
                            ),
                            body = LOOPBACK_PCMU_SDP,
                        )
                    }
                }
                "BYE" -> sendSipResponse(out, msg, 200, "OK")
            }
        }

        server.use {
            val ctx = newTestContext()
            val engine = SipEngine(ctx, Store(ctx))
            val initial = testAccount(server.port, password = "stale-pass")
            engine.onCredentialRefresh = { _, force ->
                if (force) {
                    refreshCount.incrementAndGet()
                    // Mirrors App.saveAccount(updated): re-applying new credentials must update in-place.
                    engine.apply(listOf(initial.copy(password = "fresh-pass")), AppSettings())
                }
            }

            try {
                engine.apply(listOf(initial), AppSettings())
                awaitCondition(5_000L, "SipEngine initial REGISTERED") {
                    engine.statuses.value[initial.id]?.state == RegState.REGISTERED
                }

                val err = engine.dial(initial.id, "+919876543210")
                assertEquals(null, err)

                awaitCondition(
                    8_000L,
                    "SipEngine call recovering to ACTIVE; snap=${engine.call.value}, invites=${inviteCount.get()}",
                ) {
                    engine.call.value?.state == CallState.ACTIVE
                }
                assertTrue("Expected re-register with rotated fresh-pass", freshAuthAccepted.get())
                assertEquals(1, refreshCount.get())
                assertEquals(2, inviteCount.get())
                engine.hangup()
            } finally {
                engine.stopAll()
            }
        }
    }

    @Test
    fun dialBlackHoleZombieSocketRecoversAfterNoProvisionalResponse() {
        val states = CopyOnWriteArrayList<RegState>()
        val inviteAttempts = AtomicInteger(0)
        val server = FakeTcpSipServer { connId, socket, out, msg ->
            when (msg.method) {
                "REGISTER" -> sendSipResponse(out, msg, 200, "OK", extraHeaders = mapOf("Expires" to "60"))
                "INVITE" -> {
                    val attempt = inviteAttempts.incrementAndGet()
                    if (connId == 1) {
                        // Simulate half-open NAT zombie: swallow INVITE #1 silently (no 100 Trying).
                        return@FakeTcpSipServer
                    }
                    if (attempt >= 2) {
                        sendSipResponse(out, msg, 100, "Trying")
                        sendSipResponse(out, msg, 180, "Ringing")
                        sendSipResponse(
                            out = out,
                            req = msg,
                            code = 200,
                            reason = "OK",
                            extraHeaders = mapOf(
                                "Contact" to "<sip:srv@127.0.0.1:${socket.localPort};transport=tcp>",
                                "Content-Type" to "application/sdp",
                            ),
                            body = LOOPBACK_PCMU_SDP,
                        )
                    }
                }
                "BYE" -> sendSipResponse(out, msg, 200, "OK")
            }
        }

        server.use {
            val agent = SipAccount(
                context = newTestContext(),
                initial = testAccount(server.port),
                settings = { AppSettings() },
                onRegistration = { _, state, _, _ -> states += state },
                onIncoming = { _, _ -> },
                onCallUpdate = { _, _ -> },
            )
            try {
                agent.start()
                awaitCondition(5_000L, "initial registration before black-hole INVITE; states=$states") {
                    states.contains(RegState.REGISTERED)
                }

                // Socket looks freshly registered, then drops INVITE #1 silently without RST.
                val call = agent.dial("+919876543210")!!
                awaitCondition(
                    20_000L,
                    "5s INVITE watchdog + reconnect + retry reaching ACTIVE; conns=${server.acceptedCount}, invites=${inviteAttempts.get()}, state=${call.state}",
                ) {
                    call.state == CallState.ACTIVE
                }
                assertTrue("Expected reconnect onto socket #2", server.acceptedCount >= 2)
                assertTrue("Expected INVITE retry", inviteAttempts.get() >= 2)
                agent.hangup(call)
            } finally {
                agent.stop(unregister = false)
            }
        }
    }

    @Test
    fun dialDuringConnectHandshakeNeverSendsZeroAddressRegister() {
        val sawZeroAddress = AtomicBoolean(false)
        val states = CopyOnWriteArrayList<RegState>()
        val server = FakeTcpSipServer { _, socket, out, msg ->
            val rawVia = msg.headers("Via").joinToString("\n")
            val rawContact = msg.headers("Contact").joinToString("\n")
            if (rawVia.contains("0.0.0.0:0") || rawContact.contains("0.0.0.0:0")) {
                sawZeroAddress.set(true)
            }
            when (msg.method) {
                "REGISTER" -> {
                    Thread.sleep(150L)
                    sendSipResponse(out, msg, 200, "OK", extraHeaders = mapOf("Expires" to "60"))
                }
                "INVITE" -> {
                    sendSipResponse(out, msg, 100, "Trying")
                    sendSipResponse(out, msg, 180, "Ringing")
                    sendSipResponse(
                        out = out,
                        req = msg,
                        code = 200,
                        reason = "OK",
                        extraHeaders = mapOf(
                            "Contact" to "<sip:srv@127.0.0.1:${socket.localPort};transport=tcp>",
                            "Content-Type" to "application/sdp",
                        ),
                        body = LOOPBACK_PCMU_SDP,
                    )
                }
                "BYE" -> sendSipResponse(out, msg, 200, "OK")
            }
        }

        server.use {
            val agent = SipAccount(
                context = newTestContext(),
                initial = testAccount(server.port),
                settings = { AppSettings() },
                onRegistration = { _, state, _, _ -> states += state },
                onIncoming = { _, _ -> },
                onCallUpdate = { _, _ -> },
            )
            try {
                agent.start()
                // Immediately dial while connect/REGISTER handshake is still in flight.
                val call = agent.dial("+919876543210")!!
                awaitCondition(8_000L, "call reaching ACTIVE after handshake settles; states=$states") {
                    call.state == CallState.ACTIVE
                }
                assertTrue("Must never send 0.0.0.0:0 Via/Contact REGISTER", !sawZeroAddress.get())
                agent.hangup(call)
            } finally {
                agent.stop(unregister = false)
            }
        }
    }

    @Test
    fun credentialRefreshAndReregistrationDuringActiveCallPreservesCallAndDialog() {
        val inviteCount = AtomicInteger(0)
        val byeConnId = AtomicInteger(-1)
        val freshRegOk = AtomicBoolean(false)
        val allowFreshReg = AtomicBoolean(false)
        val server = FakeTcpSipServer { connId, socket, out, msg ->
            when (msg.method) {
                "REGISTER" -> {
                    val auth = msg.header("Authorization")
                    when {
                        auth == null -> sendSipResponse(
                            out = out,
                            req = msg,
                            code = 401,
                            reason = "Unauthorized",
                            extraHeaders = mapOf(
                                "WWW-Authenticate" to "Digest realm=\"127.0.0.1\", nonce=\"nonce-live\", algorithm=MD5",
                            ),
                        )
                        allowFreshReg.get() &&
                            matchesDigest(auth, "REGISTER", "911234567890", "127.0.0.1", "rotated-midcall") -> {
                            freshRegOk.set(true)
                            sendSipResponse(out, msg, 200, "OK", extraHeaders = mapOf("Expires" to "60"))
                        }
                        !allowFreshReg.get() &&
                            matchesDigest(auth, "REGISTER", "911234567890", "127.0.0.1", "stale-pass") -> {
                            sendSipResponse(out, msg, 200, "OK", extraHeaders = mapOf("Expires" to "60"))
                        }
                        else -> sendSipResponse(out, msg, 403, "Forbidden")
                    }
                }
                "INVITE" -> {
                    inviteCount.incrementAndGet()
                    sendSipResponse(out, msg, 100, "Trying")
                    sendSipResponse(out, msg, 180, "Ringing")
                    sendSipResponse(
                        out = out,
                        req = msg,
                        code = 200,
                        reason = "OK",
                        extraHeaders = mapOf(
                            "Contact" to "<sip:srv@127.0.0.1:${socket.localPort};transport=tcp>",
                            "Content-Type" to "application/sdp",
                        ),
                        body = LOOPBACK_PCMU_SDP,
                    )
                }
                "BYE" -> {
                    byeConnId.set(connId)
                    sendSipResponse(out, msg, 200, "OK")
                }
            }
        }

        server.use {
            val ctx = newTestContext()
            val engine = SipEngine(ctx, Store(ctx))
            val initial = testAccount(server.port, password = "stale-pass")
            try {
                engine.apply(listOf(initial), AppSettings())
                awaitCondition(5_000L, "initial REGISTERED") {
                    engine.statuses.value[initial.id]?.state == RegState.REGISTERED
                }

                assertEquals(null, engine.dial(initial.id, "198"))
                awaitCondition(5_000L, "198 call ACTIVE before mid-call credential rotation") {
                    engine.call.value?.state == CallState.ACTIVE
                }

                // Rotate password & re-register while 198 call is actively running.
                allowFreshReg.set(true)
                engine.apply(listOf(initial.copy(password = "rotated-midcall")), AppSettings())

                awaitCondition(5_000L, "mid-call re-REGISTER 200 OK with rotated password") {
                    freshRegOk.get() && engine.statuses.value[initial.id]?.state == RegState.REGISTERED
                }
                // Live call MUST still be ACTIVE on the exact same TCP connection (no socket drop/reconnect).
                assertEquals(CallState.ACTIVE, engine.call.value?.state)
                assertEquals(1, server.acceptedCount)

                // Ending the live call sends BYE cleanly over that same original socket.
                engine.hangup()
                awaitCondition(3_000L, "BYE sent on original socket after mid-call re-registration") {
                    byeConnId.get() == 1
                }
            } finally {
                engine.stopAll()
            }
        }
    }

    @Test
    fun ensureHealthyRegistrationSkipsDuplicateAndUnboundRegisterWhileConnecting() {
        val registerContacts = CopyOnWriteArrayList<String>()
        val server = FakeTcpSipServer { _, _, out, msg ->
            if (msg.method == "REGISTER") {
                registerContacts.add(msg.header("Contact").orEmpty())
                if (msg.header("Authorization") == null) {
                    sendSipResponse(
                        out,
                        msg,
                        401,
                        "Unauthorized",
                        extraHeaders = mapOf("WWW-Authenticate" to "Digest realm=\"127.0.0.1\", nonce=\"n0\""),
                    )
                } else {
                    sendSipResponse(out, msg, 200, "OK", extraHeaders = mapOf("Expires" to "3600"))
                }
            }
        }

        server.use {
            val ctx = newTestContext()
            val engine = SipEngine(ctx, Store(ctx))
            val initial = testAccount(server.port, password = "secret")
            try {
                engine.apply(listOf(initial), AppSettings())
                // Simulate BootReceiver / NetworkCallback / MainActivity calling recovery while TLS/TCP connect runs.
                repeat(5) {
                    engine.ensureHealthyRegistration(forceProbe = true)
                    Thread.sleep(5L)
                }
                awaitCondition(5_000L, "REGISTERED cleanly after concurrent recovery calls") {
                    engine.statuses.value[initial.id]?.state == RegState.REGISTERED
                }
                assertEquals(1, server.acceptedCount)
                assertFalse(
                    "Unbound Contact @0.0.0.0:0 must never be queued during connect()",
                    registerContacts.any { it.contains("0.0.0.0:0") },
                )
                assertEquals(2, registerContacts.size)
            } finally {
                engine.stopAll()
            }
        }
    }

    private fun testAccount(port: Int, password: String = "stale-pass") = Account(
        id = 1L,
        label = "Test Fiber",
        server = "127.0.0.1:$port",
        proxy = "",
        domain = "127.0.0.1",
        username = "+911234567890",
        password = password,
        authId = "",
        displayName = "Test",
        dialingPrefix = "",
        dialPlan = "",
        hideCid = false,
        voicemailNumber = "",
        transport = Transport.TCP,
        publicAddr = "",
        registerRefresh = 60,
        keepAlive = 15,
        enabled = true,
        provisioning = Provisioning(
            router = "http://192.168.29.1",
            mode = JioMode.TEMPORARY,
            autoRefresh = true,
            expiresAt = (System.currentTimeMillis() / 1000L) + 3600L,
        ),
    )

    private fun awaitCondition(timeoutMs: Long, message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25L)
        }
        assertTrue("Timed out after ${timeoutMs}ms: $message", predicate())
    }

    private fun newTestContext(): Context {
        val backing = ConcurrentHashMap<String, Any?>()
        val prefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = backing.toMutableMap()
            override fun getString(key: String?, defValue: String?): String? =
                (backing[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
                defValues
            override fun getInt(key: String?, defValue: Int): Int = (backing[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (backing[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (backing[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean =
                (backing[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = backing.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private val edits = HashMap<String, Any?>()
                private var clearAll = false
                override fun putString(key: String?, value: String?) = apply {
                    if (key != null) edits[key] = value
                }
                override fun putStringSet(key: String?, values: MutableSet<String>?) = this
                override fun putInt(key: String?, value: Int) = apply { if (key != null) edits[key] = value }
                override fun putLong(key: String?, value: Long) = apply { if (key != null) edits[key] = value }
                override fun putFloat(key: String?, value: Float) = apply { if (key != null) edits[key] = value }
                override fun putBoolean(key: String?, value: Boolean) = apply {
                    if (key != null) edits[key] = value
                }
                override fun remove(key: String?) = apply { if (key != null) edits[key] = null }
                override fun clear() = apply { clearAll = true }
                override fun commit(): Boolean {
                    apply()
                    return true
                }
                override fun apply() {
                    if (clearAll) backing.clear()
                    edits.forEach { (k, v) -> if (v == null) backing.remove(k) else backing[k] = v }
                }
            }
            override fun registerOnSharedPreferenceChangeListener(
                listener: SharedPreferences.OnSharedPreferenceChangeListener?,
            ) = Unit
            override fun unregisterOnSharedPreferenceChangeListener(
                listener: SharedPreferences.OnSharedPreferenceChangeListener?,
            ) = Unit
        }
        return object : ContextWrapper(null) {
            private val info = ApplicationInfo()
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo(): ApplicationInfo = info
            override fun getPackageName(): String = "com.pp.jiocall"
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
    }

    private fun matchesDigest(
        authHeader: String,
        method: String,
        username: String,
        realm: String,
        password: String,
    ): Boolean {
        val params = parseAuthParams(authHeader)
        val gotUser = params["username"] ?: return false
        val gotResponse = params["response"] ?: return false
        val nonce = params["nonce"] ?: return false
        val uri = params["uri"] ?: return false
        if (gotUser != username) return false

        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val qop = params["qop"]
        val expected = if (!qop.isNullOrEmpty()) {
            val nc = params["nc"] ?: return false
            val cnonce = params["cnonce"] ?: return false
            md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }
        return expected.equals(gotResponse, ignoreCase = true)
    }

    private fun parseAuthParams(header: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("""(\w+)=("([^"]*)"|([^,\s]+))""").findAll(header).forEach { m ->
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }
            map[key] = value
        }
        return map
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sendSipResponse(
        out: OutputStream,
        req: SipMessage,
        code: Int,
        reason: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: String = "",
    ) {
        val sb = StringBuilder()
        sb.append("SIP/2.0 ").append(code).append(' ').append(reason).append("\r\n")
        req.headers("Via").forEach { sb.append("Via: ").append(it).append("\r\n") }
        req.header("From")?.let { sb.append("From: ").append(it).append("\r\n") }
        val to = req.header("To")
        if (to != null) {
            val taggedTo = if (to.contains(";tag=", ignoreCase = true)) to else "$to;tag=srvtag"
            sb.append("To: ").append(taggedTo).append("\r\n")
        }
        req.header("Call-ID")?.let { sb.append("Call-ID: ").append(it).append("\r\n") }
        req.header("CSeq")?.let { sb.append("CSeq: ").append(it).append("\r\n") }
        extraHeaders.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n\r\n")
        val headBytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        synchronized(out) {
            out.write(headBytes)
            if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
            out.flush()
        }
    }

    private class FakeTcpSipServer(
        private val onRequest: (connId: Int, socket: Socket, out: OutputStream, msg: SipMessage) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val connSeq = AtomicInteger(0)
        private val sockets = CopyOnWriteArrayList<Socket>()
        @Volatile private var running = true

        val port: Int get() = server.localPort
        val acceptedCount: Int get() = connSeq.get()

        init {
            Thread({
                while (running) {
                    val sock = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    sockets += sock
                    val connId = connSeq.incrementAndGet()
                    Thread({
                        serveSocket(connId, sock)
                    }, "FakeSipConn-$connId").apply { isDaemon = true }.start()
                }
            }, "FakeSipAccept").apply { isDaemon = true }.start()
        }

        private fun serveSocket(connId: Int, sock: Socket) {
            try {
                val input = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                while (running && !sock.isClosed) {
                    val rawHead = readHeaders(input) ?: break
                    if (rawHead.isBlank()) continue
                    val contentLength = Regex(
                        "(?im)^Content-Length\\s*:\\s*(\\d+)",
                    ).find(rawHead)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(bodyBytes, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < contentLength) break
                    val body = String(bodyBytes, 0, read, StandardCharsets.UTF_8)
                    val msg = SipMessage.parse(rawHead, body) ?: continue
                    onRequest(connId, sock, out, msg)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { sock.close() }
            }
        }

        private fun readHeaders(input: BufferedInputStream): String? {
            val buf = ByteArrayOutputStream()
            var prev3 = 0
            var prev2 = 0
            var prev1 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString(StandardCharsets.UTF_8.name())
                // Skip leading keep-alive CRLF probes before headers begin.
                if (buf.size() == 0 && (b == '\r'.code || b == '\n'.code)) continue
                buf.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                    return buf.toString(StandardCharsets.UTF_8.name())
                }
                prev3 = prev2
                prev2 = prev1
                prev1 = b
            }
        }

        override fun close() {
            running = false
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        private val LOOPBACK_PCMU_SDP = """
            v=0
            o=- 1 1 IN IP4 127.0.0.1
            s=-
            c=IN IP4 127.0.0.1
            t=0 0
            m=audio 40000 RTP/AVP 0
            a=rtpmap:0 PCMU/8000
            a=sendrecv
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }
}
