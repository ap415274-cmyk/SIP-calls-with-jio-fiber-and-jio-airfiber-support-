package com.pp.jiocall.sip

import android.content.Context
import android.util.Log
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.AppSettings
import com.pp.jiocall.data.Transport
import com.pp.jiocall.jio.JioClient
import com.pp.jiocall.jio.JioRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class RegState { DISABLED, REGISTERING, REGISTERED, FAILED, UNREGISTERED }

enum class CallState { DIALING, RINGING, ACTIVE, HELD, ENDED, FAILED }

/** One call, incoming or outgoing. */
class SipCall internal constructor(
    val callId: String,
    val outgoing: Boolean,
    val accountId: Long,
    val accountTitle: String,
) {
    var remoteNumber: String = ""
    var remoteName: String = ""
    var remoteUri: String = ""
    var state: CallState = if (outgoing) CallState.DIALING else CallState.RINGING
    var muted: Boolean = false
    var onHold: Boolean = false
    var recording: Boolean = false
    var recordingPath: String? = null
    var startedAt: Long = 0L
    var endedAt: Long = 0L
    var error: String? = null

    internal var localTag: String = randomTag(8)
    internal var remoteTag: String? = null
    internal var remoteTarget: String = ""
    internal var inviteCseq: Long = 1L
    internal var dialogCseq: Long = 1L
    internal var branch: String = ""
    internal var media: RtpAudio? = null
    internal var pendingInvite: SipMessage? = null
    internal var latestRemoteSdp: String? = null
    internal var lastInvite: String? = null
    internal var authChallenge: Digest.Challenge? = null
    internal var authChallengeIsProxy: Boolean = false
    internal var authNonceCount: Int = 0
    internal var inviteAuthAttempts: Int = 0
    internal var recoveryAttempts: Int = 0
    @Volatile
    internal var preparingInvite: Boolean = false
    @Volatile
    internal var gotSipResponse: Boolean = false
    internal var confirmed: Boolean = false
    /** CSeq of the most recent re-INVITE sent for hold/unhold. */
    internal var holdCseq: Long = 0L
    @Volatile
    internal var answering: Boolean = false
    @Volatile
    internal var hasEarlyMedia: Boolean = false
    @Volatile
    internal var supportsRfc2833: Boolean = true

    val answered: Boolean get() = state == CallState.ACTIVE || state == CallState.HELD
}

/**
 * The SIP user agent for one account: registration, calls and the audio that
 * belongs to them.
 */
class SipAccount(
    private val context: Context,
    initial: Account,
    private val settings: () -> AppSettings,
    private val onRegistration: (SipAccount, RegState, String?, Int) -> Unit,
    private val onIncoming: (SipAccount, SipCall) -> Unit,
    private val onCallUpdate: (SipAccount, SipCall) -> Unit,
    private val onCredentialRefreshRequested: (suspend (accountId: Long, force: Boolean) -> Unit)? = null,
) {
    /** The last saved account; swapped in place when only its credentials moved. */
    var account: Account = initial
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val calls = ConcurrentHashMap<String, SipCall>()
    private data class TerminatedInvite(
        val code: Int,
        val reason: String,
        val localTag: String,
        val atMs: Long,
    )
    private val terminatedInvites = ConcurrentHashMap<String, TerminatedInvite>()
    @Volatile
    private var transport: SipTransport? = null

    private val deviceName: String by lazy { JioClient(context).hostname() }
    private val instanceId: String by lazy { JioRouter.instanceId(deviceName) }
    private val debugLogEnabled: Boolean by lazy {
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private var regCallId = randomTag(16)
    private var regTag = randomTag(8)
    private val regCseq = AtomicLong(0L)
    private var regChallenge: Digest.Challenge? = null
    private var regChallengeIsProxy = false
    private var regNonceCount = 0
    private var regAuthAttempts = 0
    private var regJob: Job? = null
    private var keepAliveJob: Job? = null
    private var restartJob: Job? = null
    private var registerWatchdog: Job? = null
    private var failures = 0
    private var grantedExpires = 300
    @Volatile
    private var lastRegisterOkMs = 0L
    @Volatile
    private var lastRegisterProbeMs = 0L
    @Volatile
    private var awaitingRegisterReply = false
    @Volatile
    private var lastRegCode = 0
    @Volatile
    private var pendingReconnectAfterCall = false
    @Volatile
    private var registration = RegState.DISABLED
    private val cseq = AtomicLong(System.currentTimeMillis() % 100_000)
    @Volatile
    private var discoveredPublicAddr: String? = null

    val registrationState: RegState get() = registration

    fun start() {
        if (!account.isUsable()) {
            publish(RegState.FAILED, "Enter a username and a server")
            return
        }
        val (host, port) = account.signallingTarget()
        if (host.isEmpty()) {
            publish(RegState.FAILED, "Enter a server")
            return
        }
        if (transport?.let { !it.isClosed() } == true && hasProtectedLiveCall()) {
            Log.i("JioCall", "Deferring transport reconnect for ${account.title()} until active call finishes")
            pendingReconnectAfterCall = true
            reregisterInPlaceOnLiveTransport()
            return
        }
        val connection: SipTransport = synchronized(stateLock) {
            restartJob?.cancel()
            restartJob = null
            transport?.close()
            transport = null
            regCallId = "callid_" + System.currentTimeMillis() + "_" + randomTag(8)
            regTag = randomTag(8)
            regCseq.set(0L)
            regChallenge = null
            regChallengeIsProxy = false
            regNonceCount = 0
            regAuthAttempts = 0
            publish(RegState.REGISTERING, null)
            lastRegCode = 0
            awaitingRegisterReply = false
            lateinit var created: SipTransport
            created = SipTransport(
                transport = account.transport,
                host = host,
                port = port,
                trustAll = account.provisioning != null,
                onMessage = ::handle,
                onClosed = { reason -> dropConnection(reason, created) },
                debugLogging = debugLogEnabled,
            )
            transport = created
            created
        }
        scope.launch {
            runCatching { connection.connect() }
                .onFailure { dropConnection(it.message ?: "Cannot reach $host", connection) }
                .onSuccess {
                    if (transport === connection) {
                        sendRegister(account.registerRefresh.coerceIn(60, 3_600))
                        armRegisterWatchdog()
                    } else {
                        connection.close()
                    }
                }
        }
        if (keepAliveJob?.isActive != true) startKeepAlive()
    }

    /**
     * Re-evaluates the signalling socket when Android reports network/Wi-Fi readiness or IP changes
     * (e.g. shortly after device reboot when Wi-Fi associates to JioFiber).
     */
    fun onNetworkChanged() {
        ensureHealthyRegistration(forceProbe = true, allowOnActiveCall = true)
    }

    /**
     * Verifies registration freshness and TCP/TLS socket health after Doze sleep, network hops,
     * or foreground resume so long-lived idle sessions do not leave stale registrations behind.
     */
    fun ensureHealthyRegistration(forceProbe: Boolean = false, allowOnActiveCall: Boolean = false) {
        if (!account.enabled || !account.isUsable()) return
        if (!allowOnActiveCall && calls.values.any { it.answered || it.state == CallState.DIALING || it.state == CallState.RINGING }) {
            transport?.keepAlive()
            return
        }
        val activeIps = activeInterfaceIps()
        val (connection, state) = synchronized(stateLock) { transport to registration }
        val needsRegistrar = account.server.trim().isNotEmpty()
        val localIp = connection?.let { normalizeIp(it.localIp) }.orEmpty()
        val isLoopbackLocal = localIp == "::1" || localIp == "0:0:0:0:0:0:0:1" || localIp.startsWith("127.")
        when {
            connection == null || connection.isClosed() || state == RegState.FAILED || state == RegState.UNREGISTERED -> {
                synchronized(stateLock) {
                    failures = 0
                    restartJob?.cancel()
                    restartJob = null
                }
                start()
            }
            localIp.isNotEmpty() && !isLoopbackLocal && activeIps.isNotEmpty() && localIp !in activeIps -> {
                synchronized(stateLock) {
                    failures = 0
                    restartJob?.cancel()
                    restartJob = null
                }
                start()
            }
            connection.localIp.isEmpty() -> {
                // Socket connect/TLS handshake in flight; start()'s onSuccess sends initial REGISTER.
            }
            state == RegState.REGISTERING && awaitingRegisterReply && (elapsedOrWallMs() - lastRegisterProbeMs) <= 15_000L -> {
                // An initial or handshake REGISTER is already in flight and awaiting response.
            }
            needsRegistrar && lastRegisterOkMs > 0L && (elapsedOrWallMs() - lastRegisterOkMs) >= grantedExpires * 1_000L -> {
                // Registration expired while Doze suspended coroutines; establish a clean socket + REGISTER.
                Log.i("JioCall", "Registration expired during idle/sleep for ${account.title()}; reconnecting")
                start()
            }
            needsRegistrar && isRegistrationStale(marginMs = 180_000L) -> {
                scope.launch {
                    if (sendRegister(account.registerRefresh.coerceIn(60, 3_600))) {
                        armRegisterWatchdog(timeoutMs = 12_000L)
                    } else {
                        dropConnection("Transport unavailable", connection)
                    }
                }
            }
            forceProbe && account.transport != Transport.UDP && hasStaleTransport(idleThresholdMs = 300_000L) -> {
                // Verify silent TLS/TCP connections after long idle periods instead of assuming socket liveness.
                scope.launch {
                    connection.keepAlive()
                    if (needsRegistrar) {
                        if (sendRegister(account.registerRefresh.coerceIn(60, 3_600))) {
                            armRegisterWatchdog(timeoutMs = 10_000L)
                        } else {
                            dropConnection("Transport unavailable", connection)
                        }
                    }
                }
            }
            forceProbe && awaitingRegisterReply && (elapsedOrWallMs() - lastRegisterProbeMs) > 15_000L -> {
                dropConnection("Registration stale after sleep", connection)
            }
            state == RegState.REGISTERED && forceProbe -> {
                scope.launch {
                    connection.keepAlive()
                }
            }
        }
    }

    /**
     * Triggers [ensureHealthyRegistration] and waits briefly for any initiated connect/REGISTER
     * (including a Doze watchdog reconnect) to settle before releasing caller WakeLocks.
     */
    suspend fun ensureHealthyRegistrationAndWait(
        forceProbe: Boolean = false,
        allowOnActiveCall: Boolean = false,
        timeoutMs: Long = 16_000L,
    ) {
        ensureHealthyRegistration(forceProbe = forceProbe, allowOnActiveCall = allowOnActiveCall)
        if (!account.enabled || !account.isUsable()) return
        val needsRegistrar = account.server.trim().isNotEmpty()
        val deadline = elapsedOrWallMs() + timeoutMs
        while (elapsedOrWallMs() < deadline && account.enabled) {
            val conn = transport
            val busy = awaitingRegisterReply ||
                registration == RegState.REGISTERING ||
                restartJob?.isActive == true ||
                (conn != null && !conn.isClosed() && conn.localIp.isEmpty())
            val doneRegistered = !needsRegistrar || (registration == RegState.REGISTERED && !isRegistrationStale(marginMs = 120_000L))
            if (!busy && (doneRegistered || registration == RegState.DISABLED)) break
            delay(50L)
        }
    }

    internal fun isRegistrationStale(marginMs: Long = 120_000L): Boolean {
        if (account.server.trim().isEmpty()) return false
        if (registration != RegState.REGISTERED || lastRegisterOkMs <= 0L) return true
        val refreshWindowMs = (grantedExpires * 1_000L - marginMs).coerceAtLeast(30_000L)
        return (elapsedOrWallMs() - lastRegisterOkMs) >= refreshWindowMs
    }

    internal fun hasStaleTransport(idleThresholdMs: Long = 300_000L): Boolean {
        val conn = transport ?: return true
        if (conn.isClosed()) return true
        if (account.transport == Transport.UDP) return false
        val lastRx = conn.lastRxMs
        return lastRx > 0L && (elapsedOrWallMs() - lastRx) >= idleThresholdMs
    }

    internal fun markStaleForTesting(idleMs: Long) {
        transport?.markIdleForTesting(idleMs)
        lastRegisterOkMs = (elapsedOrWallMs() - idleMs).coerceAtLeast(1L)
    }

    fun stop(unregister: Boolean = true) {
        val connection = synchronized(stateLock) {
            regJob?.cancel()
            regJob = null
            keepAliveJob?.cancel()
            keepAliveJob = null
            restartJob?.cancel()
            restartJob = null
            registerWatchdog?.cancel()
            registerWatchdog = null
            transport.also { transport = null }
        }
        calls.values.toList().forEach { endCall(it, "Signed out") }
        if (connection != null) {
            if (unregister) {
                // Off the caller's thread: a write to a dead socket can block.
                regCseq.incrementAndGet()
                val text = buildRegister(0, connection)
                scope.launch {
                    runCatching { connection.send(text) }
                    connection.drain()
                    connection.close()
                }
            } else {
                connection.close()
            }
        }
        publish(RegState.UNREGISTERED, null)
    }

    /** New credentials from the router, without dropping the connection or any running call. */
    fun updateAccount(updated: Account) {
        val old = account
        account = updated
        if (!updated.enabled) {
            stop()
            return
        }
        val endpointChanged = old.server != updated.server ||
            old.proxy != updated.proxy ||
            old.domain != updated.domain ||
            old.transport != updated.transport
        val authChanged = old.username != updated.username ||
            old.password != updated.password ||
            old.authId != updated.authId

        if (hasProtectedLiveCall() && transport?.let { !it.isClosed() } == true) {
            if (endpointChanged) {
                Log.i("JioCall", "Endpoint changed during live call; deferring reconnect until hangup")
                pendingReconnectAfterCall = true
            } else {
                Log.i("JioCall", "Refreshing SIP registration in-place during live call without closing dialog socket")
                reregisterInPlaceOnLiveTransport()
            }
            return
        }

        if (endpointChanged || authChanged || registration == RegState.FAILED || registration == RegState.UNREGISTERED || transport == null) {
            start()
        } else {
            reregisterInPlaceOnLiveTransport()
        }
    }

    private fun hasProtectedLiveCall(): Boolean = calls.values.any {
        it.answered ||
            !it.outgoing ||
            it.hasEarlyMedia ||
            (it.outgoing && it.state == CallState.RINGING)
    }

    private fun reregisterInPlaceOnLiveTransport(): Boolean {
        val conn = transport?.takeIf { !it.isClosed() && it.localIp.isNotEmpty() } ?: return false
        failures = 0
        regAuthAttempts = 0
        regChallenge = null
        regChallengeIsProxy = false
        regNonceCount = 0
        if (account.server.trim().isEmpty()) return true
        return if (sendRegister(account.registerRefresh.coerceIn(60, 3_600))) {
            armRegisterWatchdog(timeoutMs = 15_000L)
            true
        } else {
            pendingReconnectAfterCall = true
            false
        }
    }

    // ------------------------------------------------------------------ calls

    fun dial(rawNumber: String): SipCall? {
        val number = applyDialPlan(rawNumber.trim())
        if (number.isEmpty()) return null
        val target = dialTarget(number)
        val media = RtpAudio(context, transport?.localIp.orEmpty())
        if (media.port == 0) return null
        val call = SipCall(UUID.randomUUID().toString(), true, account.id, account.title()).apply {
            remoteNumber = number
            remoteUri = target
            remoteTarget = target
            this.media = media
            inviteCseq = 1L
            dialogCseq = 1L
            branch = "z9hG4bK" + randomTag(12)
        }
        calls[call.callId] = call
        val conn = transport
        val needsRegistrar = account.server.trim().isNotEmpty()
        val provisioningExpired = account.provisioning?.expiresAt?.let {
            it - (System.currentTimeMillis() / 1000L) <= 60L
        } ?: false
        val needsRecovery = provisioningExpired ||
            conn == null ||
            conn.isClosed() ||
            (needsRegistrar && registration != RegState.REGISTERED) ||
            isRegistrationStale(marginMs = 120_000L) ||
            hasStaleTransport(idleThresholdMs = 300_000L)

        if (needsRecovery) {
            call.preparingInvite = true
            scope.launch(Dispatchers.IO) {
                try {
                    val ready = ensureReadyForCall(forceCredentialRenew = provisioningExpired, timeoutMs = 10_000L)
                    if (!calls.containsKey(call.callId) || call.state != CallState.DIALING) return@launch
                    if (!ready) {
                        endWithError(call, "Not connected to ${account.host()}")
                        return@launch
                    }
                    if (!sendInvite(call)) {
                        endWithError(call, "Not connected to ${account.host()}")
                    }
                } finally {
                    call.preparingInvite = false
                }
            }
        } else if (!sendInvite(call)) {
            if (account.enabled && call.recoveryAttempts == 0) {
                recoverAndRetryOutgoingCall(call, forceCredentialRenew = account.provisioning != null)
            } else {
                endWithError(call, "Not connected to ${account.host()}")
                return call
            }
        }
        notify(call)
        // A server that never answers must not leave the call dialing forever.
        scope.launch {
            delay(32_000)
            if (calls.containsKey(call.callId) && call.state == CallState.DIALING) {
                endWithError(call, "No answer from ${account.host()}")
            }
        }
        return call
    }

    /** True while the call is still tracked here (dialing, ringing, talking). */
    fun isLive(callId: String): Boolean = calls.containsKey(callId)

    fun answer(call: SipCall) {
        val invite = call.pendingInvite ?: run {
            Log.w("JioCall", "answer: no pending INVITE for ${call.remoteNumber} (state=${call.state}, answered=${call.answered})")
            return
        }
        if (call.answered || call.answering || call.state == CallState.ENDED || call.state == CallState.FAILED) {
            return
        }
        call.answering = true
        scope.launch(Dispatchers.IO) {
            try {
                answerInternal(call, invite)
            } finally {
                call.answering = false
            }
        }
    }

    private fun answerInternal(call: SipCall, invite: SipMessage) {
        if (!calls.containsKey(call.callId) || call.state == CallState.ENDED || call.state == CallState.FAILED) {
            return
        }
        val media = call.media ?: RtpAudio(context, transport?.localIp.orEmpty()).also { call.media = it }
        val sdpOffer = call.latestRemoteSdp ?: invite.body
        val remote = sdpOffer.takeIf { it.isNotBlank() }?.let { Sdp.parse(it) }
        // An IPv6 peer needs an IPv6 RTP socket before the answer advertises
        // our port: an IPv4 socket can neither send nor receive there.
        remote?.address?.takeIf { it.isNotBlank() }?.let { media.prepareFor(it) }
        // With an IPv6 peer our answer has to point back at an IPv6 address.
        val address = media.preferredLocalAddress() ?: advertisedAddress(media)
        val preferred = CodecSupport.offer(settings().codecs())
        if (remote == null) {
            if (sdpOffer.isNotBlank()) {
                Log.w("JioCall", "answering ${call.remoteNumber}: unreadable SDP offer")
                reply(invite, 488, "Not Acceptable Here", call.localTag)
                endCall(call, "Unreadable SDP offer")
                return
            }
            val body = Sdp.offer(address, media.port, preferred, System.currentTimeMillis())
            Log.i("JioCall", "answering ${call.remoteNumber}: the INVITE held no offer, ours goes in the 200 OK")
            reply(invite, 200, "OK", call.localTag, body, "application/sdp")
            call.startedAt = System.currentTimeMillis()
            call.state = CallState.ACTIVE
            if (settings().autoRecord && !call.recording) startRecording(call)
            notify(call)
            return
        }
        val amrOctetAligned = Sdp.negotiateAmrAlignment(sdpOffer, preferred)
        val plan = planMedia(remote, preferred, amrOctetAligned, signallingHost())
        if (plan == null) {
            Log.w("JioCall", "answering ${call.remoteNumber}: no common codec (offered=${remote.codecs})")
            reply(invite, 488, "Not Acceptable Here", call.localTag)
            endCall(call, "No common audio codec")
            return
        }
        val body = Sdp.createAnswer(sdpOffer, address, media.port, plan, System.currentTimeMillis())
        if (body == null) {
            Log.w("JioCall", "answering ${call.remoteNumber}: the offer cannot be answered")
            reply(invite, 488, "Not Acceptable Here", call.localTag)
            endCall(call, "No common audio codec")
            return
        }
        if (!calls.containsKey(call.callId) || call.state == CallState.ENDED || call.state == CallState.FAILED) {
            return
        }
        // Open sound device and punch NAT before sending 200 OK (matches pjsua's answer())
        startMedia(call, plan)
        Log.i(
            "JioCall",
            "answering ${call.remoteNumber}: ${plan.codec.mime} pt=${plan.payloadType} " +
                "octetAligned=${plan.octetAligned}, offer=${sdpOffer.length} bytes",
        )
        synchronized(call) { call.pendingInvite = null }
        recordTerminatedInvite(call.callId, 487, "Call Already Answered", call.localTag)
        reply(invite, 200, "OK", call.localTag, body, "application/sdp")
        call.startedAt = System.currentTimeMillis()
        call.state = CallState.ACTIVE
        if (settings().autoRecord && !call.recording) {
            startRecording(call)
        }
        notify(call)
    }

    fun hangup(call: SipCall) {
        if (call.state == CallState.ENDED || call.state == CallState.FAILED) return
        when {
            call.outgoing && (call.state == CallState.DIALING || call.state == CallState.RINGING) -> {
                if (call.lastInvite != null) sendCancel(call)
                endCall(call, "Cancelled")
            }

            !call.outgoing && !call.answered -> {
                val invite = synchronized(call) {
                    call.pendingInvite.also { call.pendingInvite = null }
                }
                recordTerminatedInvite(call.callId, 486, "Busy Here", call.localTag)
                invite?.let { reply(it, 486, "Busy Here", call.localTag) }
                endCall(call, "Declined")
            }

            else -> {
                sendBye(call)
                endCall(call, "Hung up")
            }
        }
    }

    private fun recordTerminatedInvite(callId: String, code: Int, reason: String, localTag: String) {
        val now = System.currentTimeMillis()
        terminatedInvites[callId] = TerminatedInvite(code, reason, localTag, now)
        if (terminatedInvites.size > 64) {
            terminatedInvites.entries.removeIf { now - it.value.atMs > 60_000L }
        }
    }

    fun setMuted(call: SipCall, muted: Boolean) {
        call.muted = muted
        call.media?.setMuted(muted || call.onHold)
        notify(call)
    }

    @Volatile
    private var currentSpeaker: Boolean = settings().speakerphone

    fun setSpeaker(speaker: Boolean) {
        currentSpeaker = speaker
        calls.values.forEach { it.media?.setSpeaker(speaker) }
    }

    fun sendDtmf(call: SipCall, digit: Char) {
        if (!call.answered) return
        scope.launch(Dispatchers.IO) {
            // Match RusTone DtmfMethod::Auto: use RFC 4733 RTP telephone-event alone when negotiated,
            // and fall back to SIP INFO only when RFC 4733 was not negotiated or could not be sent
            // (sending both causes double-digit triggers on IVRs like 198).
            val sentRtp = if (call.supportsRfc2833) call.media?.sendDtmf(digit) == true else false
            if (!sentRtp) {
                runCatching {
                    val request = synchronized(call) {
                        val target = call.remoteTarget.ifEmpty { call.remoteUri }
                        call.dialogCseq += 1
                        val infoBody = "Signal=$digit\r\nDuration=160\r\n"
                        buildDialogRequest("INFO", target, call, infoBody, "application/dtmf-relay")
                    }
                    transport?.send(request)
                }
            }
        }
    }

    /** Re-INVITE with `a=sendonly` (or back to `a=sendrecv`). */
    fun setHold(call: SipCall, hold: Boolean) {
        if (!call.answered) return
        synchronized(call) {
            call.onHold = hold
            call.media?.setMuted(call.muted || hold)
            call.media?.setRemotePaused(hold)
            call.state = if (hold) CallState.HELD else CallState.ACTIVE
        }
        notify(call)
        scope.launch(Dispatchers.IO) {
            synchronized(call) {
                val media = call.media ?: return@launch
                if (call.state == CallState.ENDED || call.state == CallState.FAILED) return@launch
                val address = advertisedAddress(media)
                val body = Sdp.offer(address, media.port, CodecSupport.offer(settings().codecs()), System.currentTimeMillis())
                    .replace("a=sendrecv", if (hold) "a=sendonly" else "a=sendrecv")
                val target = call.remoteTarget.ifEmpty { call.remoteUri }
                call.dialogCseq += 1
                call.holdCseq = call.dialogCseq  // remember which CSeq this re-INVITE used
                val request = buildDialogRequest("INVITE", target, call, body)
                transport?.send(request)
            }
        }
    }

    fun isRecording(callId: String): Boolean = calls[callId]?.media?.isRecording == true

    /**
     * Starts call recording to a local WAV file.
     * SILENT: ABSOLUTELY ZERO AUDIO / BEEP IS SENT OVER THE WIRE TO THE OTHER PARTY.
     */
    fun startRecording(call: SipCall): File? {
        val media = call.media ?: return null
        val file = CallRecorder.createRecordingFile(context, call.remoteNumber, call.outgoing)
        media.startRecording(file)
        call.recording = true
        call.recordingPath = file.absolutePath
        notify(call)
        return file
    }

    fun stopRecording(call: SipCall): File? {
        val media = call.media ?: return null
        val file = media.stopRecording()
        call.recording = false
        if (file != null && file.exists() && file.length() > 44) {
            call.recordingPath = file.absolutePath
        }
        notify(call)
        return file
    }

    fun toggleRecording(call: SipCall): Boolean {
        return if (call.media?.isRecording == true) {
            stopRecording(call)
            false
        } else {
            startRecording(call) != null
        }
    }

    // ---------------------------------------------------------- registration

    private fun startKeepAlive() {
        val interval = account.keepAlive
        if (interval <= 0) return
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(interval.coerceAtLeast(5) * 1_000L)
                transport?.keepAlive()
            }
        }
    }

    private fun sendRegister(expires: Int): Boolean {
        val connection = transport ?: return false
        if (connection.isClosed()) return false
        regCseq.incrementAndGet()
        if (expires > 0) {
            awaitingRegisterReply = true
            lastRegisterProbeMs = elapsedOrWallMs()
        }
        return connection.send(buildRegister(expires, connection))
    }

    private fun connectionEndpoint(connection: SipTransport): String {
        val ip = connection.localIp.trim().substringBefore('%').ifEmpty { "0.0.0.0" }
        val formattedIp = if (ip.contains(':') && !ip.startsWith('[')) "[$ip]" else ip
        return "$formattedIp:${connection.localPort}"
    }

    private fun buildRegister(expires: Int, connection: SipTransport): String {
        // pjsua's reg_uri(): the registrar, not the proxy.
        val registrar = account.server.trim().ifEmpty { account.host() }
        val aor = account.host()
        val user = account.number().ifEmpty { "phone" }
        val local = connectionEndpoint(connection)
        val grant = if (expires == 0) 0 else expires
        val builder = StringBuilder()
        builder.append("REGISTER sip:$registrar${transportSuffix()} SIP/2.0\r\n")
        builder.append("Via: SIP/2.0/${viaTransport()} $local;rport;branch=z9hG4bK${randomTag(12)};alias\r\n")
        routeHeader()?.let { builder.append("Route: $it\r\n") }
        builder.append("Max-Forwards: 70\r\n")
        builder.append("From: <sip:$user@$aor${transportSuffix()}>;tag=$regTag\r\n")
        builder.append("To: <sip:$user@$aor${transportSuffix()}>\r\n")
        builder.append("Call-ID: $regCallId\r\n")
        builder.append("CSeq: ${regCseq.get()} REGISTER\r\n")
        builder.append("Contact: ${contactHeader(connection, isRegister = true)}\r\n")
        builder.append("Expires: $grant\r\n")
        builder.append("Allow: INVITE, ACK, CANCEL, BYE, OPTIONS, INFO, UPDATE\r\n")
        builder.append("Supported: path, outbound\r\n")
        builder.append("User-Agent: RusTone/1.0.0\r\n")
        regChallenge?.let { challenge ->
            regNonceCount += 1
            val header = Digest.authorization(
                challenge, account.authUser(), account.password, "REGISTER",
                "sip:$registrar${transportSuffix()}", randomTag(8), regNonceCount,
            )
            val headerName = if (regChallengeIsProxy) "Proxy-Authorization" else "Authorization"
            builder.append("$headerName: $header\r\n")
        }
        builder.append("Content-Length: 0\r\n\r\n")
        return builder.toString()
    }

    private fun handleRegisterResponse(message: SipMessage) {
        awaitingRegisterReply = false
        registerWatchdog?.cancel()
        lastRegCode = message.statusCode
        when {
            message.statusCode == 401 || message.statusCode == 407 -> {
                regAuthAttempts++
                if (regAuthAttempts > 2) {
                    publish(RegState.FAILED, "Authentication rejected (${message.statusCode} ${message.reason})", message.statusCode)
                    regChallenge = null
                    regChallengeIsProxy = false
                    regAuthAttempts = 0
                    if (handleMidCallRegisterFailure(message.statusCode, triggerCredRefresh = true)) {
                        return
                    }
                    transport?.close()
                    transport = null
                    scope.launch { onCredentialRefreshRequested?.invoke(account.id, false) }
                    scheduleRestart()
                    return
                }
                val header = if (message.statusCode == 401) {
                    message.header("WWW-Authenticate")
                } else {
                    message.header("Proxy-Authenticate")
                }
                val challenge = header?.let { Digest.parseChallenge(it) }
                if (challenge == null) {
                    publish(RegState.FAILED, "The server's authentication challenge could not be read", message.statusCode)
                    return
                }
                regChallenge = challenge
                regChallengeIsProxy = message.statusCode == 407
                regNonceCount = 0
                sendRegister(account.registerRefresh.coerceIn(60, 3_600))
                armRegisterWatchdog(timeoutMs = 15_000L)
            }

            message.statusCode in 200..299 -> {
                failures = 0
                pendingReconnectAfterCall = false
                regAuthAttempts = 0
                regChallenge = null
                regChallengeIsProxy = false
                lastRegisterOkMs = elapsedOrWallMs()
                val contacts = message.headers("Contact")
                val ownContact = contacts.firstOrNull { it.contains(instanceId, ignoreCase = true) }
                    ?: contacts.firstOrNull()
                val contactExpires = ownContact?.let {
                    Regex("expires=(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
                }
                val rawExpires = message.header("Expires")?.toIntOrNull()
                    ?: contactExpires
                    ?: account.registerRefresh
                grantedExpires = rawExpires.coerceIn(60, account.registerRefresh.coerceIn(60, 3_600))
                val via = message.header("Via")
                val received = Regex("received=([0-9a-fA-F.:]+)", RegexOption.IGNORE_CASE).find(via.orEmpty())?.groupValues?.get(1)
                if (!received.isNullOrEmpty()) {
                    discoveredPublicAddr = received
                }
                publish(RegState.REGISTERED, null, message.statusCode)
                scheduleRefresh()
            }

            else -> {
                publish(RegState.FAILED, "Registration failed (${message.statusCode} ${message.reason})", message.statusCode)
                if (handleMidCallRegisterFailure(message.statusCode, triggerCredRefresh = message.statusCode == 403)) {
                    return
                }
                transport?.close()
                transport = null
                if (message.statusCode == 403) {
                    scope.launch { onCredentialRefreshRequested?.invoke(account.id, false) }
                }
                scheduleRestart()
            }
        }
    }

    private fun handleMidCallRegisterFailure(statusCode: Int, triggerCredRefresh: Boolean): Boolean {
        if (!hasProtectedLiveCall()) return false
        pendingReconnectAfterCall = true
        if (triggerCredRefresh) {
            scope.launch { onCredentialRefreshRequested?.invoke(account.id, false) }
        }
        return true
    }

    private fun scheduleRefresh() {
        regJob?.cancel()
        val seconds = (grantedExpires * 9 / 10).coerceAtLeast(30)
        regJob = scope.launch {
            delay(seconds * 1_000L)
            if (!account.enabled || registration == RegState.DISABLED) return@launch
            if (sendRegister(account.registerRefresh.coerceIn(60, 3_600))) {
                armRegisterWatchdog(timeoutMs = 15_000L)
            } else {
                dropConnection("Transport unavailable", transport)
            }
        }
    }

    private fun publish(state: RegState, reason: String?, code: Int = 0) {
        registration = state
        runCatching { onRegistration(this, state, reason, code) }
    }

    private fun dropConnection(reason: String, closing: SipTransport? = transport) {
        val connection = closing ?: return
        val isCurrent = synchronized(stateLock) {
            if (transport !== connection) {
                false
            } else {
                transport = null
                awaitingRegisterReply = false
                registerWatchdog?.cancel()
                registerWatchdog = null
                true
            }
        }
        connection.close()
        if (!isCurrent) return
        publish(RegState.FAILED, reason)
        val liveCalls = calls.values.toList()
        for (call in liveCalls) {
            if (call.preparingInvite) continue
            if (call.outgoing && call.state == CallState.DIALING && !call.confirmed && !call.hasEarlyMedia && call.recoveryAttempts == 0) {
                recoverAndRetryOutgoingCall(call, forceCredentialRenew = account.provisioning != null)
            } else {
                endCall(call, reason)
            }
        }
        scheduleRestart()
    }

    private fun scheduleRestart() {
        if (!account.enabled) return
        if (restartJob?.isActive == true) return
        failures = (failures + 1).coerceAtMost(8)
        val wait = minOf(60_000L, 3_000L * failures)
        restartJob = scope.launch {
            delay(wait)
            start()
        }
    }

    private fun activeInterfaceIps(): Set<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.let(::normalizeIp) }
            .filter { it.isNotEmpty() }
            .toSet()
    }.getOrDefault(emptySet())

    private fun normalizeIp(ip: String): String =
        ip.trim().removePrefix("[").substringBefore("]").substringBefore('%')

    /** A REGISTER that never gets an answer must not leave the agent stuck. */
    private fun armRegisterWatchdog(timeoutMs: Long = 35_000L) {
        registerWatchdog?.cancel()
        val watched = transport ?: return
        registerWatchdog = scope.launch {
            delay(timeoutMs)
            if (awaitingRegisterReply && transport === watched) {
                dropConnection("No answer from ${account.server.trim().ifEmpty { account.host() }}", watched)
            }
        }
    }

    private suspend fun ensureReadyForCall(forceCredentialRenew: Boolean, timeoutMs: Long = 10_000L): Boolean {
        if (!account.enabled || !account.isUsable()) return false
        val beforeConn = transport
        val beforePass = account.password
        runCatching { onCredentialRefreshRequested?.invoke(account.id, forceCredentialRenew) }
        val updatedInPlace = transport !== beforeConn || account.password != beforePass

        val needsRegistrar = account.server.trim().isNotEmpty()
        if (!updatedInPlace) {
            val conn = transport
            val connectInFlight = registration == RegState.REGISTERING &&
                conn != null &&
                !conn.isClosed() &&
                (conn.localIp.isEmpty() || awaitingRegisterReply)
            val shouldStartFresh = forceCredentialRenew ||
                conn == null ||
                conn.isClosed() ||
                registration == RegState.FAILED ||
                registration == RegState.UNREGISTERED ||
                hasStaleTransport(idleThresholdMs = 300_000L) ||
                (needsRegistrar && lastRegisterOkMs > 0L && (elapsedOrWallMs() - lastRegisterOkMs) >= grantedExpires * 1_000L)
            if (shouldStartFresh) {
                synchronized(stateLock) {
                    failures = 0
                    restartJob?.cancel()
                    restartJob = null
                }
                start()
            } else if (needsRegistrar && !connectInFlight &&
                (registration != RegState.REGISTERED || isRegistrationStale(marginMs = 120_000L))
            ) {
                if (sendRegister(account.registerRefresh.coerceIn(60, 3_600))) {
                    armRegisterWatchdog(timeoutMs = 10_000L)
                } else {
                    start()
                }
            }
        }

        val deadline = elapsedOrWallMs() + timeoutMs
        var retriedAuth = forceCredentialRenew
        while (elapsedOrWallMs() < deadline && account.enabled) {
            val curConn = transport
            val ready = curConn != null &&
                !curConn.isClosed() &&
                curConn.localIp.isNotEmpty() &&
                (!needsRegistrar || (registration == RegState.REGISTERED && !isRegistrationStale(marginMs = 60_000L) && !awaitingRegisterReply))
            if (ready) return true

            if (registration == RegState.FAILED && !retriedAuth && account.provisioning != null &&
                (lastRegCode == 401 || lastRegCode == 403 || lastRegCode == 407)
            ) {
                retriedAuth = true
                runCatching { onCredentialRefreshRequested?.invoke(account.id, true) }
                if (transport == null || transport?.isClosed() == true) start()
            }
            delay(50L)
        }
        val finalConn = transport
        return finalConn != null && !finalConn.isClosed() && (!needsRegistrar || registration == RegState.REGISTERED)
    }

    private fun recoverAndRetryOutgoingCall(call: SipCall, forceCredentialRenew: Boolean) {
        if (call.recoveryAttempts > 0 || call.state != CallState.DIALING || call.confirmed || call.hasEarlyMedia) {
            return
        }
        call.recoveryAttempts += 1
        call.preparingInvite = true
        scope.launch(Dispatchers.IO) {
            try {
                val ready = ensureReadyForCall(forceCredentialRenew = forceCredentialRenew, timeoutMs = 10_000L)
                if (!calls.containsKey(call.callId) || call.state != CallState.DIALING) return@launch
                if (!ready) {
                    endWithError(call, "Not connected to ${account.host()}")
                    return@launch
                }
                synchronized(call) {
                    call.gotSipResponse = false
                    call.authChallenge = null
                    call.authNonceCount = 0
                    call.inviteAuthAttempts = 0
                    call.inviteCseq += 1
                    call.dialogCseq = call.inviteCseq
                    call.branch = "z9hG4bK" + randomTag(12)
                }
                if (!sendInvite(call)) {
                    endWithError(call, "Not connected to ${account.host()}")
                }
            } finally {
                call.preparingInvite = false
            }
        }
    }

    // ------------------------------------------------------------- messages

    private fun handle(message: SipMessage) {
        if (message.isResponse) {
            when (message.cseqMethod()) {
                "REGISTER" -> handleRegisterResponse(message)
                "INVITE", "BYE", "CANCEL", "PRACK", "UPDATE" -> handleCallResponse(message)
                else -> Unit
            }
        } else {
            when (message.method) {
                "INVITE" -> onInvite(message)
                "UPDATE" -> onUpdate(message)
                "ACK" -> calls[message.callId()]?.let { call ->
                    call.confirmed = true
                    if (call.startedAt == 0L) call.startedAt = System.currentTimeMillis()
                    if (message.body.isNotBlank()) {
                        negotiateAndStart(call, message.body)
                    }
                    notify(call)
                }

                "BYE" -> {
                    val call = calls[message.callId()]
                    if (call != null) {
                        reply(message, 200, "OK", call.localTag)
                        endCall(call, "Remote hung up")
                    } else {
                        reply(message, 481, "Call/Transaction Does Not Exist", randomTag(8))
                    }
                }

                "CANCEL" -> {
                    val call = calls[message.callId()]
                    if (call != null) {
                        val invite = synchronized(call) { call.pendingInvite.also { call.pendingInvite = null } }
                        recordTerminatedInvite(call.callId, 487, "Request Terminated", call.localTag)
                        reply(message, 200, "OK", call.localTag)
                        invite?.let { reply(it, 487, "Request Terminated", call.localTag) }
                        endCall(call, "Cancelled")
                    } else {
                        val prev = terminatedInvites[message.callId()]
                        if (prev != null) {
                            reply(message, 200, "OK", prev.localTag)
                        } else {
                            reply(message, 481, "Call/Transaction Does Not Exist", randomTag(8))
                        }
                    }
                }

                "PRACK" -> {
                    val call = calls[message.callId()]
                    if (call != null) {
                        reply(message, 200, "OK", call.localTag)
                    } else {
                        reply(message, 481, "Call/Transaction Does Not Exist", randomTag(8))
                    }
                }

                "OPTIONS" -> reply(message, 200, "OK", randomTag(8))
                "INFO" -> reply(message, 200, "OK", randomTag(8))
                "NOTIFY" -> reply(message, 200, "OK", randomTag(8))
                // REFER (call transfer) is not yet implemented.  Reply 501 so the
                // transferring side knows not to treat the call as transferred.
                "REFER" -> reply(message, 501, "Not Implemented", randomTag(8))
                else -> reply(message, 501, "Not Implemented", randomTag(8))
            }
        }
    }

    private fun handleCallResponse(message: SipMessage) {
        val call = calls[message.callId()] ?: return
        when (message.cseqMethod()) {
            "INVITE" -> {
                if (message.statusCode >= 100) call.gotSipResponse = true
                when {
                message.statusCode == 401 || message.statusCode == 407 -> {
                    sendNon2xxAck(call, message)
                    call.inviteAuthAttempts++
                    if (call.inviteAuthAttempts > 2) {
                        if (call.outgoing && call.state == CallState.DIALING && call.recoveryAttempts == 0 && account.provisioning != null) {
                            Log.i("JioCall", "INVITE auth rejected (${message.statusCode}); refreshing credentials and retrying once")
                            recoverAndRetryOutgoingCall(call, forceCredentialRenew = true)
                        } else {
                            endCall(call, "Call authentication failed (${message.statusCode} ${message.reason})")
                        }
                        return
                    }
                    val header = if (message.statusCode == 401) {
                        message.header("WWW-Authenticate")
                    } else {
                        message.header("Proxy-Authenticate")
                    }
                    val challenge = header?.let { Digest.parseChallenge(it) } ?: run {
                        endCall(call, "Cannot read authentication challenge")
                        return
                    }
                    call.authChallenge = challenge
                    call.authChallengeIsProxy = message.statusCode == 407
                    call.authNonceCount = 0
                    resendInvite(call)
                }

                message.statusCode == 180 || message.statusCode == 183 -> {
                    if (call.state == CallState.DIALING) {
                        call.state = CallState.RINGING
                        notify(call)
                    }
                    if (call.remoteTag.isNullOrEmpty()) {
                        call.remoteTag = message.toTag()
                    }
                    message.header("Contact")?.let { call.remoteTarget = contactUri(it) }
                    val rseq = message.header("RSeq")
                    if (rseq != null) {
                        sendPrack(call, message, rseq)
                    }
                    if (message.body.isNotBlank()) {
                        call.hasEarlyMedia = true
                        ToneGenerator.stopRingback()
                        negotiateAndStart(call, message.body)
                    } else if (call.outgoing && message.statusCode == 180 && !call.hasEarlyMedia) {
                        ToneGenerator.startRingback()
                    }
                }

                message.statusCode in 200..299 -> {
                    ToneGenerator.stopRingback()
                    if (call.remoteTag.isNullOrEmpty()) {
                        call.remoteTag = message.toTag()
                    }
                    message.header("Contact")?.let { call.remoteTarget = contactUri(it) }
                    val respondingCseq = message.cseq()
                    val isHoldReInvite = respondingCseq == call.holdCseq && call.holdCseq > 0
                    if (isHoldReInvite) {
                        // Re-INVITE for hold/unhold: send ACK using the dialog CSeq,
                        // then update audio if SDP is present.  Do NOT change call state
                        // again — it was already set in setHold().
                        sendAckForReInvite(call, message, respondingCseq)
                        call.holdCseq = 0L
                        if (message.body.isNotBlank()) {
                            negotiateAndStart(call, message.body)
                        }
                        if (settings().autoRecord && !call.recording) {
                            startRecording(call)
                        }
                        notify(call)
                    } else {
                        // Initial INVITE 200 OK
                        sendAck(call, message)
                        call.state = if (call.onHold) CallState.HELD else CallState.ACTIVE
                        if (call.startedAt == 0L) {
                            call.startedAt = System.currentTimeMillis()
                        }
                        if (message.body.isNotBlank()) {
                            negotiateAndStart(call, message.body)
                        }
                        if (settings().autoRecord && !call.recording) {
                            startRecording(call)
                        }
                        notify(call)
                    }
                }

                message.statusCode >= 300 -> {
                    ToneGenerator.stopRingback()
                    sendNon2xxAck(call, message)
                    if (message.statusCode == 403 &&
                        call.outgoing &&
                        call.state == CallState.DIALING &&
                        !call.confirmed &&
                        !call.hasEarlyMedia &&
                        call.recoveryAttempts == 0
                    ) {
                        Log.i("JioCall", "INVITE got 403 Forbidden; re-registering/renewing credentials and retrying once")
                        recoverAndRetryOutgoingCall(call, forceCredentialRenew = account.provisioning != null)
                        return
                    }
                    if (message.statusCode == 486 || message.statusCode == 600 || message.statusCode == 480) {
                        ToneGenerator.playBusyTone()
                    }
                    endCall(call, "Call failed (${message.statusCode} ${message.reason})")
                }
                }
            }

            "BYE" -> if (message.statusCode >= 200) {
                endCall(call, "Call ended")
            }

            "CANCEL" -> Unit
            "PRACK" -> Unit
            "UPDATE" -> Unit
        }
    }

    private fun onInvite(message: SipMessage) {
        val incomingCallId = message.callId()
        val existing = calls[incomingCallId]
        if (existing != null) {
            if (!existing.answered && !existing.outgoing) {
                // Retransmitted initial INVITE while ringing: never treat as re-INVITE (which sends 200 OK!)
                if (!existing.answering) {
                    reply(message, 180, "Ringing", existing.localTag)
                }
            } else {
                onReInvite(message, existing)
            }
            return
        }

        val prevTerminated = terminatedInvites[incomingCallId]
        if (prevTerminated != null && System.currentTimeMillis() - prevTerminated.atMs <= 60_000L) {
            Log.i("JioCall", "Ignoring retransmitted/forked INVITE for ended call $incomingCallId -> ${prevTerminated.code}")
            reply(message, prevTerminated.code, prevTerminated.reason, prevTerminated.localTag)
            return
        }

        // Do Not Disturb (DND): reject with 486
        if (settings().dnd) {
            val tag = randomTag(8)
            recordTerminatedInvite(incomingCallId, 486, "Do Not Disturb", tag)
            reply(message, 486, "Do Not Disturb", tag)
            return
        }

        // Call Forwarding: redirect with SIP 302 Moved Temporarily
        if (settings().forwarding && settings().forwardingNumber.isNotBlank()) {
            val rawForward = settings().forwardingNumber.trim()
            val forwardTarget = if (rawForward.contains('@')) {
                "sip:$rawForward${if (rawForward.contains("transport=")) "" else transportSuffix()}"
            } else {
                val formatted = applyDialPlan(rawForward).ifEmpty { rawForward }
                val host = account.host().ifEmpty { account.signallingTarget().first }
                "sip:$formatted@$host${transportSuffix()}"
            }
            val tag = randomTag(8)
            recordTerminatedInvite(incomingCallId, 302, "Moved Temporarily", tag)
            reply(message, 302, "Moved Temporarily", tag, extraContact = "<$forwardTarget>")
            return
        }

        // Call Waiting: if already in a call and CW disabled, reject with 486 Busy Here
        val hasActiveCall = calls.values.any { it.answered }
        if (hasActiveCall && !settings().callWaiting) {
            val tag = randomTag(8)
            recordTerminatedInvite(incomingCallId, 486, "Busy Here", tag)
            reply(message, 486, "Busy Here", tag)
            return
        }
        val pai = message.header("P-Asserted-Identity")
        val rpid = message.header("Remote-Party-ID")
        val from = message.headerAny("From", "f").orEmpty()
        val preferred = listOfNotNull(pai, rpid, from.ifEmpty { null }).firstOrNull() ?: from
        val call = SipCall(message.callId(), false, account.id, account.title()).apply {
            remoteTag = message.fromTag()
            // Preserve RFC 3261 dialog From URI for subsequent in-dialog To headers (BYE/re-INVITE)
            remoteUri = headerUri(from).ifEmpty { headerUri(preferred) }
            remoteNumber = numberFrom(preferred).ifEmpty { numberFrom(from) }
            remoteName = displayNameFrom(preferred).ifEmpty { displayNameFrom(from) }
            remoteTarget = message.headerAny("Contact", "m")?.let { contactUri(it) } ?: message.requestUri
            pendingInvite = message
            if (message.body.isNotBlank()) latestRemoteSdp = message.body
            inviteCseq = message.cseq()
            dialogCseq = message.cseq()
            media = RtpAudio(context, transport?.localIp.orEmpty())
        }
        calls[call.callId] = call
        runCatching { onIncoming(this, call) }
        // Only send 180 Ringing if the engine accepted the call (did not reject due to busy/call limit)
        if (calls.containsKey(call.callId) && call.state == CallState.RINGING) {
            reply(message, 180, "Ringing", call.localTag)
            notify(call)
        }
    }

    /**
     * A re-INVITE inside an existing dialog: Jio re-offers media before and
     * during a call.  RFC 3261 §14 makes the 200 OK carry an SDP *answer*, and
     * a re-INVITE left unanswered (or answered with a fresh offer) leaves the
     * dialog with no usable media, which is a silent call in both directions.
     */
    private fun onReInvite(message: SipMessage, call: SipCall) {
        val media = call.media ?: RtpAudio(context, transport?.localIp.orEmpty()).also { call.media = it }
        if (message.body.isBlank()) {
            reply(message, 200, "OK", call.localTag)
            return
        }
        call.latestRemoteSdp = message.body
        val offer = Sdp.parse(message.body)
        if (offer == null) {
            reply(message, 488, "Not Acceptable Here", call.localTag)
            return
        }
        offer.address.takeIf { it.isNotBlank() }?.let { media.prepareFor(it) }
        val address = media.preferredLocalAddress() ?: advertisedAddress(media)
        val preferred = CodecSupport.offer(settings().codecs())
        // Keep the framing the stream is already running with once the call
        // is up: an answer that changes it mid-call garbles both directions.
        val align = if (call.answered) media.amrOctetAligned else Sdp.negotiateAmrAlignment(message.body, preferred)
        val plan = planMedia(offer, preferred, align, signallingHost())
        val answer = plan?.let { Sdp.createAnswer(message.body, address, media.port, it, System.currentTimeMillis()) }
        if (plan == null || answer == null) {
            Log.w("JioCall", "re-INVITE for ${call.remoteNumber}: no common codec (offered=${offer.codecs})")
            reply(message, 488, "Not Acceptable Here", call.localTag)
            return
        }
        reply(message, 200, "OK", call.localTag, answer, "application/sdp")
        if (call.answered) scope.launch(Dispatchers.IO) { startMedia(call, plan) }
    }

    private fun onUpdate(message: SipMessage) {
        val call = calls[message.callId()]
        if (call == null) {
            reply(message, 481, "Call/Transaction Does Not Exist", randomTag(8))
            return
        }
        if (call.remoteTag.isNullOrEmpty()) {
            call.remoteTag = message.fromTag()
        }
        message.header("Contact")?.let { call.remoteTarget = contactUri(it) }
        if (message.body.isBlank()) {
            reply(message, 200, "OK", call.localTag)
            return
        }
        call.latestRemoteSdp = message.body
        val media = call.media ?: RtpAudio(context, transport?.localIp.orEmpty()).also { call.media = it }
        val offer = Sdp.parse(message.body)
        if (offer == null) {
            reply(message, 488, "Not Acceptable Here", call.localTag)
            return
        }
        offer.address.takeIf { it.isNotBlank() }?.let { media.prepareFor(it) }
        val address = media.preferredLocalAddress() ?: advertisedAddress(media)
        val preferred = CodecSupport.offer(settings().codecs())
        val align = if (call.answered) media.amrOctetAligned else Sdp.negotiateAmrAlignment(message.body, preferred)
        val plan = planMedia(offer, preferred, align, signallingHost())
        val answer = plan?.let { Sdp.createAnswer(message.body, address, media.port, it, System.currentTimeMillis()) }
        if (plan == null || answer == null) {
            Log.w("JioCall", "UPDATE for ${call.remoteNumber}: no common codec (offered=${offer.codecs})")
            reply(message, 488, "Not Acceptable Here", call.localTag)
            return
        }
        reply(message, 200, "OK", call.localTag, answer, "application/sdp")
        if (call.answered) scope.launch(Dispatchers.IO) { startMedia(call, plan) }
    }

    private fun retransmitOk(call: SipCall) {
        val invite = call.pendingInvite ?: return
        val media = call.media ?: return
        val address = media.preferredLocalAddress() ?: advertisedAddress(media)
        val sdpOffer = call.latestRemoteSdp ?: invite.body
        val offered = CodecSupport.offer(settings().codecs())
        val plan = planMedia(Sdp.parse(sdpOffer) ?: return, offered, media.amrOctetAligned, signallingHost()) ?: return
        val body = Sdp.createAnswer(sdpOffer, address, media.port, plan, System.currentTimeMillis()) ?: return
        reply(invite, 200, "OK", call.localTag, body, "application/sdp")
    }

    private fun negotiateAndStart(
        call: SipCall,
        sdpBody: String?,
        amrOctetAligned: Boolean? = null,
    ) {
        scope.launch(Dispatchers.IO) {
            if (call.state == CallState.ENDED || call.state == CallState.FAILED) return@launch
            val remote = sdpBody?.takeIf { it.isNotBlank() }?.let { Sdp.parse(it) } ?: run {
                Log.w("JioCall", "negotiateAndStart: no usable SDP: $sdpBody")
                return@launch
            }
            val plan = planMedia(remote, CodecSupport.offer(settings().codecs()), amrOctetAligned, signallingHost()) ?: run {
                Log.w("JioCall", "negotiateAndStart: no common codec! offered=${CodecSupport.offer(settings().codecs())}, remoteCodecs=${remote.codecs}")
                return@launch
            }
            if (call.state == CallState.ENDED || call.state == CallState.FAILED) return@launch
            startMedia(call, plan)
        }
    }

    private fun startMedia(call: SipCall, plan: MediaPlan) {
        if (call.state == CallState.ENDED || call.state == CallState.FAILED) return
        val media = call.media ?: return
        call.supportsRfc2833 = plan.remote.dtmfPt != null
        media.prepareFor(plan.host)
        media.setDtmfParams(if (call.supportsRfc2833) plan.dtmfPt else null, plan.dtmfRate)
        Log.i(
            "JioCall",
            "media for ${call.remoteNumber}: codec=${plan.codec.mime} pt=${plan.payloadType} " +
                "octetAligned=${plan.octetAligned} peer=${plan.host}:${plan.port} local=${media.port}",
        )
        media.start(
            plan.host,
            plan.port,
            plan.codec,
            plan.payloadType,
            plan.octetAligned,
            currentSpeaker,
            settings().echoCancellation,
            plan.remote.codecs,
            plan.remote.octetAligned,
            plan.modeSet,
            plan.explicitOctetAligned,
        )
        media.setMuted(call.muted || call.onHold)
        media.setRemotePaused(call.onHold)
    }

    private fun advertisedAddress(media: RtpAudio): String {
        val configured = account.publicAddr.trim()
        if (configured.isNotEmpty()) return media.advertisedAddress(configured)
        val local = transport?.localIp?.trim().orEmpty()
        if (local.isNotEmpty() && local != "0.0.0.0") return media.advertisedAddress(local)
        val discovered = discoveredPublicAddr?.trim().orEmpty()
        if (discovered.isNotEmpty() && discovered != "0.0.0.0") return media.advertisedAddress(discovered)
        return media.advertisedAddress("")
    }

    /** The address to fall back on when an offer names no usable media host. */
    private fun signallingHost(): String = account.signallingTarget().first.ifEmpty { account.host() }

    // --------------------------------------------------------------- building

    private fun sendInvite(call: SipCall): Boolean {
        val media = call.media ?: return false
        val connection = transport ?: return false
        if (connection.isClosed()) return false
        val address = advertisedAddress(media)
        val body = Sdp.offer(address, media.port, CodecSupport.offer(settings().codecs()), System.currentTimeMillis())
        val request = buildInvite(call, body)
        if (debugLogEnabled) Log.i("JioCall", "sending INVITE:\n$request")
        call.lastInvite = request
        val queued = connection.send(request)
        if (!queued) {
            Log.w("JioCall", "INVITE could not be queued (transport closed)")
            return false
        }
        armInviteBlackHoleWatchdog(call, connection)
        return true
    }

    private fun armInviteBlackHoleWatchdog(call: SipCall, connection: SipTransport) {
        if (account.transport == Transport.UDP || call.recoveryAttempts > 0) return
        scope.launch {
            delay(5_000L)
            if (calls.containsKey(call.callId) &&
                call.outgoing &&
                call.state == CallState.DIALING &&
                !call.gotSipResponse &&
                !call.preparingInvite &&
                !call.confirmed &&
                !call.hasEarlyMedia &&
                call.recoveryAttempts == 0 &&
                transport === connection
            ) {
                Log.w(
                    "JioCall",
                    "No SIP response (100 Trying) within 5s on ${account.transport}; dropping stale socket and retrying call",
                )
                dropConnection("No SIP response from ${account.host()}", connection)
            }
        }
    }

    private fun resendInvite(call: SipCall) {
        call.inviteCseq += 1
        call.dialogCseq = call.inviteCseq
        call.branch = "z9hG4bK" + randomTag(12)
        sendInvite(call)
    }

    private fun buildInvite(call: SipCall, body: String): String {
        val connection = transport ?: return ""
        val local = connectionEndpoint(connection)
        val target = call.remoteTarget.ifEmpty { call.remoteUri }
        val builder = StringBuilder()
        builder.append("INVITE $target SIP/2.0\r\n")
        builder.append("Via: SIP/2.0/${viaTransport()} $local;rport;branch=${call.branch};alias\r\n")
        builder.append("Max-Forwards: 70\r\n")
        routeHeader()?.let { builder.append("Route: $it\r\n") }
        builder.append("From: ${fromHeader(call)}\r\n")
        builder.append("To: ${toHeader(call)}\r\n")
        builder.append("Contact: ${contactHeader()}\r\n")
        builder.append("Call-ID: ${call.callId}\r\n")
        builder.append("CSeq: ${call.inviteCseq} INVITE\r\n")
        builder.append("Allow: PRACK, INVITE, ACK, BYE, CANCEL, UPDATE, INFO, SUBSCRIBE, NOTIFY, REFER, MESSAGE, OPTIONS\r\n")
        builder.append("Supported: replaces, 100rel, timer, norefersub\r\n")
        builder.append("Session-Expires: 1800\r\n")
        builder.append("Min-SE: 90\r\n")
        builder.append("User-Agent: RusTone/1.0.0\r\n")
        if (account.hideCid) builder.append("Privacy: id\r\n")
        call.authChallenge?.let { challenge ->
            call.authNonceCount += 1
            val header = Digest.authorization(
                challenge, account.authUser(), account.password, "INVITE",
                target, randomTag(8), call.authNonceCount,
            )
            val headerName = if (call.authChallengeIsProxy) "Proxy-Authorization" else "Authorization"
            builder.append("$headerName: $header\r\n")
        }
        builder.append("Content-Type: application/sdp\r\n")
        builder.append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
        builder.append(body)
        return builder.toString()
    }

    private fun buildDialogRequest(
        method: String,
        target: String,
        call: SipCall,
        body: String = "",
        contentType: String = "application/sdp",
    ): String {
        val connection = transport ?: return ""
        val local = connectionEndpoint(connection)
        val builder = StringBuilder()
        builder.append("$method $target SIP/2.0\r\n")
        builder.append("Via: SIP/2.0/${viaTransport()} $local;rport;branch=z9hG4bK${randomTag(12)}\r\n")
        builder.append("Max-Forwards: 70\r\n")
        routeHeader()?.let { builder.append("Route: $it\r\n") }
        builder.append("From: ${fromHeader(call)}\r\n")
        builder.append("To: <${call.remoteUri}>;tag=${call.remoteTag.orEmpty()}\r\n")
        builder.append("Call-ID: ${call.callId}\r\n")
        builder.append("CSeq: ${call.dialogCseq} $method\r\n")
        builder.append("Contact: ${contactHeader()}\r\n")
        builder.append("User-Agent: RusTone/1.0.0\r\n")
        if (body.isNotEmpty()) {
            builder.append("Content-Type: $contentType\r\n")
            builder.append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
            builder.append(body)
        } else {
            builder.append("Content-Length: 0\r\n\r\n")
        }
        return builder.toString()
    }

    private fun sendAck(call: SipCall, response: SipMessage) {
        val connection = transport ?: return
        val local = connectionEndpoint(connection)
        val target = call.remoteTarget.ifEmpty { call.remoteUri }
        val toHeader = response.header("To") ?: run {
            val toTag = response.toTag() ?: call.remoteTag
            "<${call.remoteUri}>${if (!toTag.isNullOrEmpty()) ";tag=$toTag" else ""}"
        }
        val request = buildString {
            append("ACK $target SIP/2.0\r\n")
            append("Via: SIP/2.0/${viaTransport()} $local;rport;branch=z9hG4bK${randomTag(12)}\r\n")
            append("Max-Forwards: 70\r\n")
            routeHeader()?.let { append("Route: $it\r\n") }
            append("From: ${fromHeader(call)}\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: ${call.callId}\r\n")
            append("CSeq: ${call.inviteCseq} ACK\r\n")
            append("User-Agent: RusTone/1.0.0\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
        if (debugLogEnabled) Log.i("JioCall", "sending 2xx ACK:\n$request")
        connection.send(request)
    }

    /**
     * ACK for a re-INVITE 200 OK (hold/unhold).  RFC 3261 §13.2.2.4: the ACK
     * for a re-INVITE is an in-dialog request whose CSeq matches the INVITE.
     */
    private fun sendAckForReInvite(call: SipCall, response: SipMessage, cseq: Long) {
        val connection = transport ?: return
        val local = connectionEndpoint(connection)
        val target = call.remoteTarget.ifEmpty { call.remoteUri }
        val toHeader = response.header("To") ?: run {
            val toTag = response.toTag() ?: call.remoteTag
            "<${call.remoteUri}>${if (!toTag.isNullOrEmpty()) ";tag=$toTag" else ""}"
        }
        val request = buildString {
            append("ACK $target SIP/2.0\r\n")
            append("Via: SIP/2.0/${viaTransport()} $local;rport;branch=z9hG4bK${randomTag(12)}\r\n")
            append("Max-Forwards: 70\r\n")
            routeHeader()?.let { append("Route: $it\r\n") }
            append("From: ${fromHeader(call)}\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: ${call.callId}\r\n")
            append("CSeq: $cseq ACK\r\n")
            append("User-Agent: RusTone/1.0.0\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
        if (debugLogEnabled) Log.i("JioCall", "sending re-INVITE ACK (cseq=$cseq):\n$request")
        connection.send(request)
    }

    private fun sendNon2xxAck(call: SipCall, response: SipMessage) {
        val connection = transport ?: return
        val local = connectionEndpoint(connection)
        val target = call.remoteTarget.ifEmpty { call.remoteUri }
        val toHeader = response.header("To") ?: run {
            val toTag = response.toTag() ?: call.remoteTag
            "<${call.remoteUri}>${if (!toTag.isNullOrEmpty()) ";tag=$toTag" else ""}"
        }
        val request = buildString {
            append("ACK $target SIP/2.0\r\n")
            append("Via: SIP/2.0/${viaTransport()} $local;rport;branch=${call.branch}\r\n")
            append("Max-Forwards: 70\r\n")
            routeHeader()?.let { append("Route: $it\r\n") }
            append("From: ${fromHeader(call)}\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: ${call.callId}\r\n")
            append("CSeq: ${call.inviteCseq} ACK\r\n")
            append("User-Agent: RusTone/1.0.0\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
        if (debugLogEnabled) Log.i("JioCall", "sending non-2xx ACK:\n$request")
        connection.send(request)
    }

    private fun sendPrack(call: SipCall, response: SipMessage, rseq: String) {
        val connection = transport ?: return
        val local = connectionEndpoint(connection)
        val target = call.remoteTarget.ifEmpty { call.remoteUri }
        val toHeader = response.header("To") ?: run {
            val toTag = response.toTag() ?: call.remoteTag
            "<${call.remoteUri}>${if (!toTag.isNullOrEmpty()) ";tag=$toTag" else ""}"
        }
        call.dialogCseq += 1
        val request = buildString {
            append("PRACK $target SIP/2.0\r\n")
            append("Via: SIP/2.0/${viaTransport()} $local;rport;branch=z9hG4bK${randomTag(12)};alias\r\n")
            append("Max-Forwards: 70\r\n")
            routeHeader()?.let { append("Route: $it\r\n") }
            append("From: ${fromHeader(call)}\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: ${call.callId}\r\n")
            append("CSeq: ${call.dialogCseq} PRACK\r\n")
            append("RAck: $rseq ${call.inviteCseq} INVITE\r\n")
            append("User-Agent: RusTone/1.0.0\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
        if (debugLogEnabled) Log.i("JioCall", "sending PRACK:\n$request")
        connection.send(request)
    }

    private fun sendBye(call: SipCall) {
        val target = call.remoteTarget.ifEmpty { call.remoteUri }
        call.dialogCseq += 1
        transport?.send(buildDialogRequest("BYE", target, call))
    }

    private fun sendCancel(call: SipCall) {
        val connection = transport ?: return
        val local = connectionEndpoint(connection)
        val target = call.remoteTarget.ifEmpty { call.remoteUri }
        val request = buildString {
            append("CANCEL $target SIP/2.0\r\n")
            append("Via: SIP/2.0/${viaTransport()} $local;rport;branch=${call.branch}\r\n")
            append("Max-Forwards: 70\r\n")
            routeHeader()?.let { append("Route: $it\r\n") }
            append("From: ${fromHeader(call)}\r\n")
            append("To: ${toHeader(call)}\r\n")
            append("Call-ID: ${call.callId}\r\n")
            append("CSeq: ${call.inviteCseq} CANCEL\r\n")
            append("User-Agent: RusTone/1.0.0\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
        connection.send(request)
    }

    private fun reply(
        request: SipMessage,
        code: Int,
        reason: String,
        toTag: String,
        body: String = "",
        contentType: String? = null,
        extraContact: String? = null,
    ) {
        val contact = if (code < 400) (extraContact ?: contactHeader()) else extraContact
        val text = buildSipResponse(request, code, reason, toTag, body, contentType, contact)
        if (debugLogEnabled) Log.i("JioCall", "sending response $code $reason:\n$text")
        transport?.send(text)
    }

    private fun fromHeader(call: SipCall): String {
        val name = account.displayName.trim()
        val uri = aorUri()
        val namePart = if (name.isNotEmpty()) "\"$name\" " else ""
        if (account.hideCid) return "\"Anonymous\" <sip:anonymous@anonymous.invalid>;tag=${call.localTag}"
        return "$namePart<$uri>;tag=${call.localTag}"
    }

    private fun toHeader(call: SipCall): String {
        val domain = account.domain.trim().ifEmpty { account.host() }
        val number = call.remoteNumber
        val base = if (number.contains('@')) number else "$number@$domain"
        val suffix = transportSuffix()
        val target = if (base.contains("transport=")) base else base + suffix
        val tag = if (call.remoteTag.isNullOrEmpty()) "" else ";tag=${call.remoteTag}"
        return "<sip:$target>$tag"
    }

    /** pjsua's id_uri(): the account's own address, with its transport. */
    private fun aorUri(): String {
        val base = account.sipUri()
        return if (base.contains("transport=")) base else base + transportSuffix()
    }

    /**
     * Every Contact carries `+sip.instance`: pjsua's RFC 5626 instance id, and
     * the value the JioFiber/AirFiber router whitelists this device by. It is
     * appended to UAC, UAS and REGISTER Contacts alike.
     * When [isRegister] is true, RFC 5626 `reg-id=1` is included so the router
     * binds this TLS connection to the registration for incoming calls.
     */
    private fun contactHeader(connection: SipTransport? = transport, isRegister: Boolean = false): String {
        val localIp = connection?.localIp?.trim()?.substringBefore('%').orEmpty()
        val host = account.publicAddr.trim().substringBefore('%').ifEmpty {
            if (localIp.isNotEmpty() && localIp != "0.0.0.0") localIp else discoveredPublicAddr.orEmpty().substringBefore('%')
        }.ifEmpty { "0.0.0.0" }
        val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val port = connection?.localPort ?: 5060
        val local = "$formattedHost:$port"
        val user = account.number().ifEmpty { "phone" }
        val suffix = when (account.transport) {
            Transport.UDP -> ""
            Transport.TCP -> ";transport=TCP;ob"
            Transport.TLS -> ";transport=TLS;ob"
        }
        val regParam = if (isRegister) ";reg-id=1" else ""
        return "<sip:$user@$local$suffix>;+sip.instance=\"<$instanceId>\"$regParam"
    }

    /** pjsua's transport_suffix(): only TCP and TLS name themselves. */
    private fun transportSuffix(): String = when (account.transport) {
        Transport.UDP -> ""
        Transport.TCP -> ";transport=tcp"
        Transport.TLS -> ";transport=tls"
    }

    private fun viaTransport(): String = when (account.transport) {
        Transport.UDP -> "UDP"
        Transport.TCP -> "TCP"
        Transport.TLS -> "TLS"
    }

    private fun transportParam(): String = when (account.transport) {
        Transport.UDP -> "udp"
        Transport.TCP -> "tcp"
        Transport.TLS -> "tls"
    }

    /** The Request-URI for a dialled number, with the account's transport. */
    private fun dialTarget(number: String): String {
        val domain = account.dialHost()
        val target = if (number.contains('@')) "sip:$number" else "sip:$number@$domain"
        return if (target.contains("transport=")) target else target + transportSuffix()
    }

    /**
     * RusTone's `proxy_uris()`: the outbound proxy as a loose route, e.g.
     * `<sip:192.168.29.1:5068;transport=tls;lr>`. The ONT expects requests to
     * carry it; without it the call is refused at the transport level.
     */
    private fun routeHeader(): String? {
        val proxy = account.proxy.trim()
        if (proxy.isEmpty()) return null
        val hostPort = if (proxy.contains(':')) proxy else "$proxy:${account.defaultPort()}"
        return "<sip:$hostPort${transportSuffix()};lr>"
    }

    private fun nextCseq(): Long = cseq.incrementAndGet()

    private fun applyDialPlan(number: String): String {
        val rawTrimmed = number.trim()
        if (rawTrimmed.isEmpty()) return ""
        val prefix = account.dialingPrefix.trim()
        val customPlan = settings().customDialPlan.ifBlank { account.dialPlan }
        if (customPlan.isNotBlank() && (customPlan.contains('<') || customPlan.contains('x') || customPlan.contains('X') || customPlan.contains('['))) {
            // Custom Linksys/Cisco dial plan receives raw input just like RusTone's dialplan.rs,
            // and returning null means no rule matched (blocked call).
            return DialPlan.formatNumber(rawTrimmed, prefix, customPlan).orEmpty()
        }
        val rawClean = com.pp.jiocall.data.PhoneUtils.cleanDialInput(rawTrimmed)
        val isShortOrUssd = rawClean.startsWith("*") || rawClean.startsWith("#") || rawClean.length <= 5
        val sanitized = if (
            prefix.startsWith("+") &&
            rawClean.isNotEmpty() &&
            !rawClean.startsWith("+") &&
            !rawClean.startsWith("00") &&
            !isShortOrUssd
        ) {
            rawClean.trimStart('0').ifEmpty { rawClean }
        } else {
            com.pp.jiocall.data.PhoneUtils.normalizeForDialing(rawTrimmed)
        }
        if (sanitized.isEmpty()) return ""
        var result = if (prefix.isEmpty() || sanitized.startsWith("+") || sanitized.startsWith("*") || sanitized.startsWith("#") || sanitized.length <= 5) {
            sanitized
        } else {
            prefix + sanitized
        }
        val rules = account.dialPlan.split('|').map { it.trim() }.filter { it.contains("=>") }
        for (rule in rules) {
            val (pattern, replacement) = rule.split("=>", limit = 2)
            result = runCatching {
                Regex("^${pattern.trim()}$").replace(result) { match ->
                    replacement.trim().replace("#", match.value).replace("$1", match.groupValues.getOrNull(1).orEmpty())
                }
            }.getOrDefault(result)
        }
        return result.trim()
    }

    private fun notify(call: SipCall) {
        runCatching { onCallUpdate(this, call) }
            .onFailure { Log.e("JioCall", "call update failed", it) }
    }

    private fun endCall(call: SipCall, reason: String) {
        ToneGenerator.stopRingback()
        if (call.state == CallState.ENDED) return
        Log.i("JioCall", "call ${call.remoteNumber} ended: $reason")
        call.state = CallState.ENDED
        call.endedAt = System.currentTimeMillis()
        val oldMedia = call.media
        call.media = null
        if (call.recording && oldMedia != null) {
            val file = oldMedia.stopRecording()
            call.recording = false
            if (file != null && file.exists() && file.length() > 44) {
                call.recordingPath = file.absolutePath
            }
        }
        if (oldMedia != null) {
            scope.launch(Dispatchers.IO) { oldMedia.stop() }
        }
        if (call.error == null) call.error = reason
        calls.remove(call.callId)
        notify(call)
        if (pendingReconnectAfterCall && !hasProtectedLiveCall() && calls.isEmpty() && account.enabled) {
            pendingReconnectAfterCall = false
            scope.launch { ensureHealthyRegistration(forceProbe = true) }
        }
    }

    private fun endWithError(call: SipCall, reason: String) {
        call.state = CallState.FAILED
        call.error = reason
        endCall(call, reason)
    }

    internal companion object {
        fun numberFrom(header: String): String {
            val uri = headerUri(header)
            return uri.removePrefix("sip:").removePrefix("sips:").substringBefore('@').trim()
        }

        internal fun displayNameFrom(header: String): String {
            val trimmed = header.trim()
            if (trimmed.startsWith("\"")) {
                val end = trimmed.indexOf('"', 1)
                return if (end > 0) trimmed.substring(1, end).trim() else ""
            }
            val lt = trimmed.indexOf('<')
            if (lt > 0) {
                val raw = trimmed.substring(0, lt).trim().removePrefix("\"").removeSuffix("\"").trim()
                if (raw.isNotEmpty() && !raw.startsWith("sip:", true) && !raw.startsWith("sips:", true)) {
                    return raw
                }
            }
            return ""
        }

        fun headerUri(header: String): String {
            val start = header.indexOf('<')
            if (start >= 0) {
                val end = header.indexOf('>', start)
                if (end > start) return header.substring(start + 1, end).trim()
            }
            return header.substringBefore(';').trim()
        }

        fun contactUri(header: String): String = headerUri(header)

        /**
         * Formats an RFC 3261 SIP response. Error responses (4xx-6xx) omit dialog-creating
         * `Record-Route`, `Contact` (unless explicitly supplied), `Allow`, and `Supported`
         * headers so proxies/SBCs (such as Jio's ISBC) accept `486 Busy Here` cleanly.
         */
        internal fun buildSipResponse(
            request: SipMessage,
            code: Int,
            reason: String,
            toTag: String,
            body: String = "",
            contentType: String? = null,
            contact: String? = null,
        ): String {
            val builder = StringBuilder()
            builder.append("SIP/2.0 $code $reason\r\n")
            request.headersAny("Via", "v").forEach { builder.append("Via: $it\r\n") }
            if (code < 400) {
                request.headers("Record-Route").forEach { builder.append("Record-Route: $it\r\n") }
            }
            request.headerAny("From", "f")?.let { builder.append("From: $it\r\n") }
            val to = request.headerAny("To", "t").orEmpty()
            builder.append("To: ${if (Regex(";tag=", RegexOption.IGNORE_CASE).containsMatchIn(to)) to else "$to;tag=$toTag"}\r\n")
            request.headerAny("Call-ID", "i")?.let { builder.append("Call-ID: $it\r\n") }
            request.header("CSeq")?.let { builder.append("CSeq: $it\r\n") }
            if (contact != null) {
                builder.append("Contact: $contact\r\n")
            }
            if (code < 400) {
                builder.append("Allow: PRACK, INVITE, ACK, BYE, CANCEL, UPDATE, INFO, SUBSCRIBE, NOTIFY, REFER, MESSAGE, OPTIONS\r\n")
                builder.append("Supported: replaces, 100rel, timer, norefersub\r\n")
            }
            builder.append("User-Agent: RusTone/1.0.0\r\n")
            if (contentType != null) builder.append("Content-Type: $contentType\r\n")
            builder.append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
            builder.append(body)
            return builder.toString()
        }
    }

}

/** A short random token for tags, branches and Call-IDs. */
internal fun randomTag(length: Int): String {
    val chars = "0123456789abcdef"
    return (1..length).map { chars[sharedRng.nextInt(chars.length)] }.joinToString("")
}

/** Shared RNG so we never create a new Random() per character (which risks
 *  duplicate seeds on fast successive calls). */
private val sharedRng = java.util.Random()
