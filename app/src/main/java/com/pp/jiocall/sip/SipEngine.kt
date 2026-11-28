package com.pp.jiocall.sip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.pp.jiocall.R
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.AppSettings
import com.pp.jiocall.data.CallLogEntry
import com.pp.jiocall.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class AccountStatus(val state: RegState, val reason: String? = null, val code: Int = 0)

/** What the UI shows about the call in progress. */
data class CallSnapshot(
    val callId: String,
    val accountId: Long,
    val accountTitle: String,
    val outgoing: Boolean,
    val number: String,
    val name: String,
    val state: CallState,
    val muted: Boolean,
    val onHold: Boolean,
    val recording: Boolean = false,
    val recordingPath: String? = null,
    val startedAt: Long,
    val error: String?,
    val quality: CallQuality? = null,
) {
    val answered: Boolean get() = state == CallState.ACTIVE || state == CallState.HELD

    val finished: Boolean get() = state == CallState.ENDED || state == CallState.FAILED
}

/**
 * Every enabled account's user agent, and the call the user is dealing with.
 * One engine per process, owned by the application.
 *
 * SIP callbacks arrive on socket and media threads while the UI calls in from
 * the main thread, so every state transition here is made under one lock. A
 * late callback must never put a finished call back on screen: terminal states
 * clear the snapshot, and [publish] refuses to show one.
 */
class SipEngine(private val context: Context, private val store: Store) {
    private val lock = Any()

    @Volatile private var settings = AppSettings()
    @Volatile private var accounts: List<Account> = emptyList()
    private val agents = ConcurrentHashMap<Long, SipAccount>()
    @Volatile private var active: SipCall? = null
    private var secondary: SipCall? = null
    private val locallyDeclinedCallIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var speakerOn = false

    var onCredentialRefresh: (suspend (accountId: Long, force: Boolean) -> Unit)? = null

    val statuses = MutableStateFlow<Map<Long, AccountStatus>>(emptyMap())
    val call = MutableStateFlow<CallSnapshot?>(null)
    val secondaryCall = MutableStateFlow<CallSnapshot?>(null)
    val speaker = MutableStateFlow(false)
    val quality = MutableStateFlow<CallQuality?>(null)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var qualityJob: Job? = null
    private var terminalClearJob: Job? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val proximityLock: PowerManager.WakeLock? by lazy {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "JioCall:Proximity")
            } else {
                null
            }
        }.getOrNull()
    }

    /** Starts, restarts and stops accounts to match this list. */
    fun apply(newAccounts: List<Account>, newSettings: AppSettings) {
        val toStart = synchronized(lock) {
            settings = newSettings
            accounts = newAccounts
            if (active == null) {
                speakerOn = newSettings.speakerphone
                speaker.value = speakerOn
            }

            val wanted = newAccounts.filter { it.enabled && it.isUsable() }
            val wantedIds = wanted.map { it.id }.toSet()
            for (id in agents.keys.toList()) {
                if (id !in wantedIds) {
                    agents.remove(id)?.stop()
                    val account = newAccounts.firstOrNull { it.id == id }
                    val state = if (account == null || !account.enabled) RegState.DISABLED else RegState.UNREGISTERED
                    statuses.update { it + (id to AccountStatus(state)) }
                }
            }

            val start = mutableListOf<Account>()
            for (account in wanted) {
                val existing = agents[account.id]
                if (existing != null && canUpdateInPlace(existing, account)) {
                    // Keep the live SipAccount (and any recovering/active calls) intact while
                    // updateAccount applies rotated router credentials or setting changes.
                    existing.updateAccount(account)
                    continue
                }
                existing?.stop()
                agents.remove(account.id)
                start += account
            }
            for (account in newAccounts.filter { !it.enabled }) {
                statuses.update { it + (account.id to AccountStatus(RegState.DISABLED)) }
            }
            start
        }

        // Outside the lock: starting connects sockets and registers.
        for (account in toStart) {
            val agent = SipAccount(
                context = context,
                initial = account,
                settings = { settings },
                onRegistration = ::onRegistration,
                onIncoming = ::onIncoming,
                onCallUpdate = ::onCallUpdate,
                onCredentialRefreshRequested = { accountId, force ->
                    onCredentialRefresh?.invoke(accountId, force)
                },
            )
            agents[account.id] = agent
            agent.start()
        }
    }

    /** Dials from the given account, or the first usable one. */
    fun dial(accountId: Long, number: String): String? = synchronized(lock) {
        val account = accounts.firstOrNull { it.id == accountId && it.enabled }
            ?: accounts.firstOrNull { it.enabled && it.isUsable() }
            ?: return "Add an account first"
        val agent = agents[account.id]
            ?: return "${account.title()} is not registered"

        // A call that failed while this dial was being prepared must not lock
        // the dialer: drop the leftover and let the new call through.
        terminalClearJob?.cancel()
        terminalClearJob = null
        val current = active
        if (current != null) {
            val done = current.state == CallState.ENDED || current.state == CallState.FAILED
            val live = !done && agentFor(current)?.isLive(current.callId) == true
            if (live) return "Hang up the current call first"
            active = null
            this.call.value = null
        }

        speakerOn = settings.speakerphone
        speaker.value = speakerOn
        agent.setSpeaker(speakerOn)
        val selectedSpeaker = speakerOn
        // Promote CallService to PHONE_CALL | MICROPHONE before INVITE/183 starts AudioRecord.
        val preDialName = com.pp.jiocall.data.PhoneUtils.resolveName(number, store.loadContacts()) ?: number
        CallService.showOngoing(context, preDialName.ifEmpty { "Call" }, "Dialing…", 0L, false, speakerOn)

        val call = agent.dial(number) ?: run {
            CallService.update(context, context.getString(com.pp.jiocall.R.string.app_name), "Ready")
            return "Could not start the call"
        }
        if (call.remoteName.isBlank()) {
            val resolved = com.pp.jiocall.data.PhoneUtils.resolveName(call.remoteNumber, store.loadContacts())
                ?: com.pp.jiocall.data.PhoneUtils.resolveName(number, store.loadContacts())
            if (resolved != null) call.remoteName = resolved
        }
        if (call.state == CallState.ENDED || call.state == CallState.FAILED) {
            // It failed before we could take it over; report the reason instead
            // of leaving a dead call or stuck ongoing notification.
            CallService.update(context, context.getString(com.pp.jiocall.R.string.app_name), "Ready")
            return call.error ?: "Call failed"
        }
        active = call
        quality.value = null
        publish(call)
        scope.launch(Dispatchers.IO) {
            ToneGenerator.applyCommunicationRoute(context, selectedSpeaker)
            updateProximityLock()
        }
        val callerName = call.remoteName.ifEmpty { call.remoteNumber.ifEmpty { "Call" } }
        CallService.showOngoing(context, callerName, "Dialing…", 0L, call.muted, speakerOn)
        null
    }

    fun answer() {
        synchronized(lock) {
            stopRingtone(resetMode = false)
            val call = active ?: return
            val agent = agentFor(call) ?: return
            val callerName = call.remoteName.ifEmpty { call.remoteNumber.ifEmpty { "Call" } }
            CallService.showOngoing(context, callerName, "Connecting…", call.startedAt, call.muted, speakerOn)
            agent.setSpeaker(speakerOn)
            agent.answer(call)
            updateProximityLock()
        }
    }

    fun answerWaiting() {
        synchronized(lock) {
            val waiting = secondary ?: return
            val waitAgent = agentFor(waiting) ?: return
            if (waiting.outgoing || waiting.state != CallState.RINGING) return

            val currentActive = active
            if (currentActive != null && currentActive.answered) {
                agentFor(currentActive)?.setHold(currentActive, true)
                secondary = currentActive
                secondaryCall.value = toSnapshot(currentActive)
            } else {
                if (currentActive != null) {
                    agentFor(currentActive)?.hangup(currentActive)
                }
                secondary = null
                secondaryCall.value = null
            }

            active = waiting
            publish(waiting)
            val waitName = waiting.remoteName.ifEmpty { waiting.remoteNumber.ifEmpty { "Call" } }
            CallService.showOngoing(context, waitName, "Connecting…", waiting.startedAt, waiting.muted, speakerOn)
            waitAgent.setSpeaker(speakerOn)
            waitAgent.answer(waiting)
            updateProximityLock()
        }
    }

    fun declineWaiting() {
        synchronized(lock) {
            val waiting = secondary ?: return
            locallyDeclinedCallIds.add(waiting.callId)
            secondary = null
            secondaryCall.value = null
            agentFor(waiting)?.hangup(waiting)
        }
    }

    fun swapCalls() {
        synchronized(lock) {
            val currentActive = active ?: return
            val other = secondary ?: return
            if (!currentActive.answered || !other.answered) return
            agentFor(currentActive)?.setHold(currentActive, true)
            agentFor(other)?.setHold(other, false)
            active = other
            secondary = currentActive
            publish(other)
            secondaryCall.value = toSnapshot(currentActive)
            updateProximityLock()
        }
    }

    fun silenceRinger() {
        synchronized(lock) {
            stopRingtone(resetMode = true)
        }
    }

    fun hangup() = synchronized(lock) {
        stopRingtone(resetMode = true)
        terminalClearJob?.cancel()
        terminalClearJob = null
        val call = active
        if (call == null) {
            // Nothing active; never leave a finished call or stuck incoming notification on screen.
            this.call.value = null
            releaseProximityLock()
            CallService.cancelCallNotification(context)
            return
        }
        val isRingingIncoming = !call.outgoing && !call.answered
        val agent = agentFor(call)
        if (isRingingIncoming) {
            locallyDeclinedCallIds.add(call.callId)
            active = null
            releaseProximityLock()
            if (agent == null) {
                if (this.call.value?.callId == call.callId) {
                    this.call.value = null
                }
            } else {
                agent.hangup(call)
                if (active == null && this.call.value?.callId == call.callId) {
                    this.call.value = null
                }
            }
            if (active == null) {
                CallService.cancelCallNotification(context)
            }
        } else if (agent == null || call.state == CallState.ENDED || call.state == CallState.FAILED) {
            active = null
            this.call.value = null
            releaseProximityLock()
            CallService.cancelCallNotification(context)
        } else {
            agent.hangup(call)
        }
    }

    /** Closes the call screen, ending the call if it is somehow still up. */
    fun dismissCall() = synchronized(lock) {
        stopRingtone(resetMode = true)
        terminalClearJob?.cancel()
        terminalClearJob = null
        qualityJob?.cancel()
        qualityJob = null
        quality.value = null
        secondary?.let { waiting -> agentFor(waiting)?.hangup(waiting) }
        secondary = null
        secondaryCall.value = null
        active?.let { call -> agentFor(call)?.hangup(call) }
        active = null
        this.call.value = null
        releaseProximityLock()
    }

    fun toggleMute(): Boolean = synchronized(lock) {
        val call = active ?: return false
        val muted = !call.muted
        agentFor(call)?.setMuted(call, muted)
        call.muted = muted
        publish(call)
        muted
    }

    fun toggleHold(): Boolean = synchronized(lock) {
        val call = active ?: return false
        val hold = !call.onHold
        agentFor(call)?.setHold(call, hold)
        hold
    }

    fun toggleSpeaker(): Boolean {
        val newSpeaker: Boolean
        synchronized(lock) {
            speakerOn = !speakerOn
            newSpeaker = speakerOn
            speaker.value = newSpeaker
        }
        scope.launch(Dispatchers.IO) {
            agents.values.forEach { it.setSpeaker(newSpeaker) }
            val current = active
            if (current?.outgoing == true && current.media == null) {
                ToneGenerator.applyCommunicationRoute(context, newSpeaker)
            }
            updateProximityLock()
        }
        return newSpeaker
    }

    fun toggleRecord(): Boolean = synchronized(lock) {
        val call = active ?: return false
        val agent = agents[call.accountId] ?: return false
        val result = agent.toggleRecording(call)
        publish(call)
        result
    }

    fun isRecording(): Boolean = synchronized(lock) {
        active?.recording == true
    }

    fun sendDtmf(digit: Char) = synchronized(lock) {
        ToneGenerator.playDtmf(digit)
        active?.let { agentFor(it)?.sendDtmf(it, digit) }
    }

    /**
     * Checks all enabled SIP accounts and recovers any stale transport or expired registration.
     */
    fun ensureHealthyRegistration(forceProbe: Boolean = false) {
        forEachAgent { it.ensureHealthyRegistration(forceProbe) }
    }

    /**
     * Suspends until every enabled SIP account finishes any triggered recovery/registration check.
     */
    suspend fun ensureHealthyRegistrationAndWait(forceProbe: Boolean = false) {
        val currentAgents = synchronized(lock) { agents.values.toList() }
        currentAgents.forEach { agent ->
            runCatching { agent.ensureHealthyRegistrationAndWait(forceProbe = forceProbe) }
        }
    }

    fun onNetworkChanged() {
        forEachAgent { it.onNetworkChanged() }
    }

    private fun forEachAgent(action: suspend (SipAccount) -> Unit) {
        val currentAgents = synchronized(lock) { agents.values.toList() }
        if (currentAgents.isEmpty()) return
        scope.launch {
            currentAgents.forEach { agent ->
                runCatching { action(agent) }
            }
        }
    }

    fun stopAll() = synchronized(lock) {
        qualityJob?.cancel()
        qualityJob = null
        terminalClearJob?.cancel()
        terminalClearJob = null
        agents.values.forEach { it.stop() }
        agents.clear()
        stopRingtone()
        ToneGenerator.stopRingback()
        ToneGenerator.releaseDtmf()
        secondary = null
        secondaryCall.value = null
        active = null
        call.value = null
        quality.value = null
        releaseProximityLock()
    }

    private fun canUpdateInPlace(existing: SipAccount, incoming: Account): Boolean {
        val current = existing.account
        if (current.sameAs(incoming)) return true
        if (active?.accountId == incoming.id || secondary?.accountId == incoming.id) return true
        return current.provisioning != null &&
            incoming.provisioning != null &&
            current.server == incoming.server &&
            current.proxy == incoming.proxy &&
            current.domain == incoming.domain &&
            current.transport == incoming.transport
    }

    private fun agentFor(call: SipCall): SipAccount? = agents[call.accountId]

    private fun onRegistration(agent: SipAccount, state: RegState, reason: String?, code: Int) {
        statuses.update { it + (agent.account.id to AccountStatus(state, reason, code)) }
    }

    private fun onIncoming(agent: SipAccount, call: SipCall) = synchronized(lock) {
        terminalClearJob?.cancel()
        terminalClearJob = null
        // Never let a finished/stale call block a brand new incoming call: the
        // old snapshot may still be referenced while the agent has already
        // dropped the dialog, and rejecting here makes every new call fail.
        val current = active
        if (current != null) {
            val stale = current.state == CallState.ENDED ||
                current.state == CallState.FAILED ||
                !agent.isLive(current.callId)
            if (!stale) {
                val hasRoomForWaiting = settings.callWaiting && secondary == null
                if (!hasRoomForWaiting) {
                    Log.i("JioCall", "INCOMING ${call.remoteNumber} rejected: busy (${current.remoteNumber} is ${current.state})")
                    agent.hangup(call)
                    return
                }
                Log.i("JioCall", "INCOMING CALL WAITING: ${call.remoteNumber}")
                if (call.remoteName.isBlank()) {
                    val resolved = com.pp.jiocall.data.PhoneUtils.resolveName(call.remoteNumber, store.loadContacts())
                    if (resolved != null) call.remoteName = resolved
                }
                secondary = call
                secondaryCall.value = toSnapshot(call)
                ToneGenerator.playCallWaitingBeep()
                return
            }
            Log.i("JioCall", "INCOMING: clearing stale active call ${current.remoteNumber} (${current.state})")
            active = null
            stopRingtone()
            this.call.value = null
        }
        Log.i("JioCall", "INCOMING ${call.remoteNumber} (name=${call.remoteName}, sdp=${call.latestRemoteSdp != null})")
        if (call.remoteName.isBlank()) {
            val resolved = com.pp.jiocall.data.PhoneUtils.resolveName(call.remoteNumber, store.loadContacts())
            if (resolved != null) call.remoteName = resolved
        }
        speakerOn = settings.speakerphone
        speaker.value = speakerOn
        agent.setSpeaker(speakerOn)
        active = call
        publish(call)
        updateProximityLock()
        playRingtone()
        CallService.showIncoming(context, call.remoteName.ifEmpty { call.remoteNumber.ifEmpty { "Incoming call" } })
    }

    private fun onCallUpdate(agent: SipAccount, call: SipCall) = synchronized(lock) {
        val isActive = call.callId == active?.callId
        val isSecondary = call.callId == secondary?.callId
        val finished = call.state == CallState.ENDED || call.state == CallState.FAILED
        if (isActive && call.state != CallState.RINGING) stopRingtone(resetMode = finished)

        if (finished) {
            ToneGenerator.stopRingback()
            val finishedMedia = call.media
            call.media = null

            if (isSecondary) {
                secondary = null
                secondaryCall.value = null
                scope.launch(Dispatchers.IO) {
                    finishedMedia?.stop()
                    runCatching { logCall(call) }
                }
                return
            }
            if (isActive) {
                qualityJob?.cancel()
                qualityJob = null
                quality.value = null
                val remaining = secondary
                if (remaining != null && remaining.state != CallState.ENDED && remaining.state != CallState.FAILED) {
                    secondary = null
                    secondaryCall.value = null
                    active = remaining
                    if (!remaining.outgoing && remaining.state == CallState.RINGING) {
                        publish(remaining)
                        playRingtone()
                        CallService.showIncoming(
                            context,
                            remaining.remoteName.ifEmpty { remaining.remoteNumber.ifEmpty { "Incoming call" } },
                        )
                    } else {
                        if (remaining.onHold) agentFor(remaining)?.setHold(remaining, false)
                        publish(remaining)
                    }
                    scope.launch(Dispatchers.IO) {
                        finishedMedia?.stop()
                        runCatching { logCall(call) }
                        updateProximityLock()
                    }
                    return
                }

                active = null
                ToneGenerator.releaseDtmf()
                releaseProximityLock()
                if (this.call.value?.callId == call.callId) {
                    if (locallyDeclinedCallIds.remove(call.callId)) {
                        this.call.value = null
                    } else {
                        this.call.value = toSnapshot(call)
                        val lingerMs = if (call.state == CallState.FAILED) 2_600L else 1_200L
                        terminalClearJob?.cancel()
                        terminalClearJob = scope.launch {
                            delay(lingerMs)
                            synchronized(lock) {
                                if (active == null && this@SipEngine.call.value?.callId == call.callId) {
                                    this@SipEngine.call.value = null
                                }
                            }
                        }
                    }
                } else {
                    locallyDeclinedCallIds.remove(call.callId)
                }
                scope.launch(Dispatchers.IO) {
                    finishedMedia?.stop()
                    runCatching { logCall(call) }
                    if (synchronized(lock) { active == null }) {
                        runCatching {
                            val manager = context.getSystemService(AudioManager::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                manager?.clearCommunicationDevice()
                            }
                            manager?.mode = AudioManager.MODE_NORMAL
                        }
                    }
                }
            } else {
                if (this.call.value?.callId == call.callId) {
                    this.call.value = null
                }
                scope.launch(Dispatchers.IO) {
                    finishedMedia?.stop()
                    runCatching { logCall(call) }
                }
            }
            return
        }
        if (isSecondary) {
            secondaryCall.value = toSnapshot(call)
            return
        }
        if (!isActive) return
        publish(call)
        updateProximityLock()
        if (call.state == CallState.ACTIVE) {
            if (qualityJob?.isActive != true) {
                qualityJob = scope.launch {
                    while (this.isActive) {
                        delay(1000)
                        val current = active ?: break
                        if (current.answered) {
                            quality.value = current.media?.getQuality()
                        }
                    }
                }
            }
        }
    }

    private fun toSnapshot(call: SipCall) = CallSnapshot(
        callId = call.callId,
        accountId = call.accountId,
        accountTitle = call.accountTitle,
        outgoing = call.outgoing,
        number = call.remoteNumber,
        name = call.remoteName,
        state = call.state,
        muted = call.muted,
        onHold = call.onHold,
        recording = call.recording,
        recordingPath = call.recordingPath,
        startedAt = call.startedAt,
        error = call.error,
        quality = if (call.answered) call.media?.getQuality() else null,
    )

    private fun publish(call: SipCall) {
        // A finished call must never overwrite an ongoing active call.
        if (call.state == CallState.ENDED || call.state == CallState.FAILED) return
        this.call.value = toSnapshot(call)
    }

    private fun updateProximityLock() {
        val current = active
        val needLock = current != null &&
            (current.answered || current.outgoing) &&
            !speakerOn &&
            !hasExternalHeadset()
        if (needLock) {
            runCatching {
                val wl = proximityLock ?: return
                if (!wl.isHeld) wl.acquire(2 * 60 * 60 * 1000L)
            }
        } else {
            releaseProximityLock()
        }
    }

    private fun releaseProximityLock() {
        runCatching {
            val wl = proximityLock ?: return
            if (wl.isHeld) wl.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        }
    }

    private fun hasExternalHeadset(): Boolean = runCatching {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.availableCommunicationDevices.map { it.type }
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }
        types.any { RtpAudio.isExternalCommDeviceType(it) }
    }.getOrDefault(false)

    private fun logCall(call: SipCall) {
        val duration = if (call.startedAt > 0) {
            ((call.endedAt.takeIf { it > 0 } ?: System.currentTimeMillis()) - call.startedAt) / 1000
        } else {
            0
        }
        val name = call.remoteName.ifEmpty {
            com.pp.jiocall.data.PhoneUtils.resolveName(call.remoteNumber, store.loadContacts()).orEmpty()
        }
        store.addCall(
            CallLogEntry(
                number = call.remoteNumber,
                name = name,
                accountTitle = call.accountTitle,
                outgoing = call.outgoing,
                missed = !call.outgoing && call.startedAt == 0L && call.error != "Declined",
                startedAt = if (call.startedAt > 0) call.startedAt else System.currentTimeMillis(),
                durationSeconds = duration,
                recordingPath = call.recordingPath,
            ),
        )
    }

    private fun playRingtone() {
        stopRingtone(resetMode = false)
        runCatching {
            val manager = context.getSystemService(AudioManager::class.java)
            val ringerMode = manager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

            if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Vibrator::class.java)
                }
                if (vib?.hasVibrator() == true) {
                    val effect = VibrationEffect.createWaveform(longArrayOf(0L, 650L, 950L), 0)
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    @Suppress("DEPRECATION")
                    vib.vibrate(effect, attrs)
                    vibrator = vib
                }
            }

            if (settings.ringtone && ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                manager?.mode = AudioManager.MODE_RINGTONE
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
                ringtone = RingtoneManager.getRingtone(context, uri)?.also { rt ->
                    rt.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    rt.isLooping = true
                    rt.play()
                }
            }
        }
    }

    private fun stopRingtone(resetMode: Boolean = true) {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        if (resetMode && active?.answered != true) {
            runCatching {
                val manager = context.getSystemService(AudioManager::class.java)
                if (manager?.mode == AudioManager.MODE_RINGTONE) {
                    manager.mode = AudioManager.MODE_NORMAL
                }
            }
        }
    }
}
