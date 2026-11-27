package com.pp.jiocall.sip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.pp.jiocall.App
import com.pp.jiocall.MainActivity
import com.pp.jiocall.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while it is registered or in a call, and manages incoming
 * call heads-up notifications and full-screen intents.
 */
class CallService : Service() {
    private var isForeground = false
    private var currentType = -1
    private var currentNotificationId = -1
    private var lastMode: Int? = null
    private var lastTitle: String? = null
    private var lastText: String? = null
    private var lastStartedAt: Long? = null
    private var lastIsMuted: Boolean? = null
    private var lastIsSpeaker: Boolean? = null

    private var lastStatusTitle: String? = null
    private var lastStatusText: String? = null
    private var cachedAvatarKey: String? = null
    private var cachedAvatarBitmap: Bitmap? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var callJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        ensureChannels()
        observeCall()
    }

    private fun observeCall() {
        val app = application as? App ?: return
        val engine = app.engine
        callJob?.cancel()
        callJob = serviceScope.launch {
            combine(engine.call, engine.speaker) { call, isSpeaker ->
                call to isSpeaker
            }.collect { (call, isSpeaker) ->
                if (call != null && !call.finished) {
                    val callerName = call.name.ifEmpty { call.number.ifEmpty { "Call" } }
                    if (!call.outgoing && call.state == com.pp.jiocall.sip.CallState.RINGING) {
                        dispatchNotification(MODE_INCOMING, callerName, "Incoming call", 0L, false, false)
                    } else {
                        val statusText = when (call.state) {
                            com.pp.jiocall.sip.CallState.DIALING -> "Dialing…"
                            com.pp.jiocall.sip.CallState.RINGING -> "Ringing…"
                            com.pp.jiocall.sip.CallState.HELD -> if (call.recording) "On hold • REC" else "Call on hold"
                            com.pp.jiocall.sip.CallState.ACTIVE -> if (call.recording) "Ongoing call • REC" else "Ongoing call"
                            else -> "Call in progress"
                        }
                        dispatchNotification(MODE_ONGOING, callerName, statusText, call.startedAt, call.muted, isSpeaker)
                    }
                } else {
                    val settings = app.store.loadSettings()
                    val hasEnabled = app.store.loadAccounts().any { it.enabled }
                    if (!settings.backgroundService || !hasEnabled) {
                        clearForegroundFlags()
                        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                        runCatching { getSystemService(NotificationManager::class.java)?.cancel(CALL_NOTIFICATION_ID) }
                        stopSelf()
                    } else if (isForeground) {
                        val (statusTitle, statusText) = resolveIdleStatus(app)
                        dispatchNotification(
                            MODE_STATUS,
                            lastStatusTitle ?: statusTitle,
                            lastStatusText ?: statusText,
                            0L,
                            false,
                            false,
                        )
                        runCatching { getSystemService(NotificationManager::class.java)?.cancel(CALL_NOTIFICATION_ID) }
                    } else {
                        runCatching { getSystemService(NotificationManager::class.java)?.cancel(CALL_NOTIFICATION_ID) }
                        lastMode = null
                        lastTitle = null
                        lastText = null
                    }
                }
            }
        }
    }

    private fun clearForegroundFlags() {
        isForeground = false
        setMicrophoneForegroundActive(false)
        currentType = -1
        currentNotificationId = -1
        lastMode = null
        lastTitle = null
        lastText = null
        lastStartedAt = null
        lastIsMuted = null
        lastIsSpeaker = null
    }

    private fun resolveIdleStatus(app: App): Pair<String, String> {
        val registered = app.engine.statuses.value.values.count { it.state == RegState.REGISTERED }
        val text = if (registered > 0) "Registered — $registered account(s)" else "Connecting…"
        return getString(R.string.app_name) to text
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        callJob?.cancel()
        clearForegroundFlags()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_ANSWER -> {
                val engine = (application as? App)?.engine
                val current = engine?.call?.value
                val callerName = current?.name?.ifEmpty { current.number.ifEmpty { "Call" } } ?: "Call"
                // Promote to MICROPHONE foreground service BEFORE engine.answer() starts AudioRecord,
                // preventing Android 11-16 from muting mic input when answered from notification/background.
                dispatchNotification(
                    mode = MODE_ONGOING,
                    title = callerName,
                    text = "Connecting…",
                    startedAt = current?.startedAt ?: 0L,
                    isMuted = false,
                    isSpeaker = engine?.speaker?.value ?: false,
                )
                engine?.answer()
                val open = Intent(this, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_OPEN_CALL, true)
                }
                runCatching { startActivity(open) }
                return START_STICKY
            }

            ACTION_DECLINE -> {
                handleDeclineInternal()
                return START_STICKY
            }

            ACTION_TOGGLE_MUTE -> {
                (application as? App)?.engine?.toggleMute()
                return START_STICKY
            }

            ACTION_TOGGLE_SPEAKER -> {
                (application as? App)?.engine?.toggleSpeaker()
                return START_STICKY
            }

            ACTION_START -> {
                (application as? App)?.startBackgroundEngine(ensureService = false)
            }
        }

        val app = application as? App
        if (intent == null && app != null) {
            // OS restarted START_STICKY service after process kill/update: re-hydrate engine if still enabled.
            app.reloadFromStoreIfNeeded()
            val settings = app.settingsFlow.value
            val hasEnabled = app.accountsFlow.value.any { it.enabled }
            if (!settings.backgroundService || !hasEnabled) {
                isForeground = false
                setMicrophoneForegroundActive(false)
                currentNotificationId = -1
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            app.startBackgroundEngine(ensureService = false)
        }

        val mode = intent?.getIntExtra(
            EXTRA_MODE,
            if (intent.getBooleanExtra(EXTRA_IS_INCOMING, false)) MODE_INCOMING else MODE_STATUS,
        ) ?: MODE_STATUS
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val text = intent?.getStringExtra(EXTRA_TEXT)
            ?: (app?.let { resolveIdleStatus(it).second } ?: "Connecting…")
        val startedAt = intent?.getLongExtra(EXTRA_CALL_STARTED_AT, 0L) ?: 0L
        val isMuted = intent?.getBooleanExtra(EXTRA_IS_MUTED, false) ?: false
        val isSpeaker = intent?.getBooleanExtra(EXTRA_IS_SPEAKER, false) ?: false

        if (mode == MODE_INCOMING || mode == MODE_ONGOING) {
            val currentCall = app?.engine?.call?.value
            val stillValid = if (mode == MODE_INCOMING) {
                currentCall != null && !currentCall.outgoing && currentCall.state == com.pp.jiocall.sip.CallState.RINGING
            } else {
                currentCall != null && !currentCall.finished
            }
            if (!stillValid) {
                val shouldStay = app?.let {
                    it.store.loadSettings().backgroundService && it.store.loadAccounts().any { acc -> acc.enabled }
                } == true
                if (shouldStay && app != null) {
                    val (statusTitle, statusText) = resolveIdleStatus(app)
                    dispatchNotification(
                        MODE_STATUS,
                        lastStatusTitle ?: statusTitle,
                        lastStatusText ?: statusText,
                        0L,
                        false,
                        false,
                    )
                } else {
                    if (!isForeground) {
                        runCatching {
                            val fallbackStatus = statusNotification(
                                getString(R.string.app_name),
                                app?.let { resolveIdleStatus(it).second } ?: "Ready",
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                startForeground(
                                    STATUS_NOTIFICATION_ID,
                                    fallbackStatus,
                                    resolveForegroundType(MODE_STATUS, false, Build.VERSION.SDK_INT),
                                )
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startForeground(STATUS_NOTIFICATION_ID, fallbackStatus, 0)
                            } else {
                                startForeground(STATUS_NOTIFICATION_ID, fallbackStatus)
                            }
                        }
                    }
                    clearForegroundFlags()
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    val mgr = getSystemService(NotificationManager::class.java)
                    runCatching { mgr?.cancel(CALL_NOTIFICATION_ID) }
                    runCatching { mgr?.cancel(STATUS_NOTIFICATION_ID) }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        if (mode == MODE_STATUS) {
            lastStatusTitle = title
            lastStatusText = text
            // If a call is currently active, DO NOT overwrite ongoing call notification with registration status!
            val currentCall = (application as? App)?.engine?.call?.value
            if (currentCall != null && currentCall.state != com.pp.jiocall.sip.CallState.ENDED && currentCall.state != com.pp.jiocall.sip.CallState.FAILED) {
                return START_STICKY
            }
        }

        dispatchNotification(mode, title, text, startedAt, isMuted, isSpeaker)
        return START_STICKY
    }

    private fun dispatchNotification(
        mode: Int,
        title: String,
        text: String,
        startedAt: Long,
        isMuted: Boolean,
        isSpeaker: Boolean,
    ) {
        if (isForeground &&
            mode == lastMode &&
            title == lastTitle &&
            text == lastText &&
            startedAt == lastStartedAt &&
            isMuted == lastIsMuted &&
            isSpeaker == lastIsSpeaker
        ) {
            return
        }

        lastMode = mode
        lastTitle = title
        lastText = text
        lastStartedAt = startedAt
        lastIsMuted = isMuted
        lastIsSpeaker = isSpeaker

        val notif = when (mode) {
            MODE_INCOMING -> incomingNotification(title)
            MODE_ONGOING -> ongoingNotification(title, text, startedAt, isMuted, isSpeaker)
            else -> statusNotification(title, text)
        }

        val targetId = if (mode == MODE_STATUS) STATUS_NOTIFICATION_ID else CALL_NOTIFICATION_ID
        val prevId = currentNotificationId
        val manager = getSystemService(NotificationManager::class.java)

        // When returning to idle status notification, always cancel any call/incoming notification
        // immediately even if startForeground transition later degrades on background restriction.
        if (mode == MODE_STATUS) {
            runCatching { manager?.cancel(CALL_NOTIFICATION_ID) }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val hasMicPermission = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                // Use remoteMessaging for idle registration keepalive so Android 15+ BOOT_COMPLETED
                // never blocks startup; promote to PHONE_CALL / MICROPHONE only for active calls.
                val type = resolveForegroundType(mode, hasMicPermission, Build.VERSION.SDK_INT)
                if (!isForeground || currentType != type || currentNotificationId != targetId) {
                    val appliedType = try {
                        startForeground(targetId, notif, type)
                        type
                    } catch (se: SecurityException) {
                        // Android 14+ may block adding MICROPHONE FGS from a strictly background transition;
                        // keep call alive with PHONE_CALL FGS rather than leaving REMOTE_MESSAGING.
                        val fallbackType = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
                        if (fallbackType != 0 && fallbackType != type) {
                            Log.w("CallService", "MICROPHONE FGS blocked (${se.message}); promoting PHONE_CALL only")
                            startForeground(targetId, notif, fallbackType)
                            fallbackType
                        } else {
                            throw se
                        }
                    }
                    isForeground = true
                    currentType = appliedType
                    currentNotificationId = targetId
                    setMicrophoneForegroundActive((appliedType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0)
                } else {
                    manager?.notify(targetId, notif)
                    setMicrophoneForegroundActive((currentType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (!isForeground || currentNotificationId != targetId) {
                    startForeground(targetId, notif, 0)
                    isForeground = true
                    currentNotificationId = targetId
                } else {
                    manager?.notify(targetId, notif)
                }
                setMicrophoneForegroundActive(mode == MODE_ONGOING)
            } else {
                if (!isForeground || currentNotificationId != targetId) {
                    startForeground(targetId, notif)
                    isForeground = true
                    currentNotificationId = targetId
                } else {
                    manager?.notify(targetId, notif)
                }
                setMicrophoneForegroundActive(mode == MODE_ONGOING)
            }
            if (prevId != -1 && prevId != targetId) {
                manager?.cancel(prevId)
            }
        } catch (e: Exception) {
            setMicrophoneForegroundActive(false)
            Log.e("CallService", "Failed to start or update foreground service: ${e.message}", e)
            // Never post an ongoing CallStyle notification without a running FGS, and always clear
            // previous incoming/call notification if degrading back to idle status.
            if (mode == MODE_STATUS) {
                runCatching { manager?.cancel(CALL_NOTIFICATION_ID) }
                if (isForeground) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    isForeground = false
                    currentType = -1
                    currentNotificationId = -1
                }
                runCatching { manager?.notify(targetId, notif) }
            } else if (mode == MODE_INCOMING) {
                runCatching { manager?.notify(targetId, notif) }
                    .onFailure {
                        runCatching { manager?.notify(targetId, statusNotification(title, "Incoming call")) }
                    }
            } else {
                val fallback = statusNotification(title, text)
                runCatching { manager?.notify(targetId, fallback) }
            }
        }
    }

    internal fun handleDeclineInternal() {
        val app = application as? App
        app?.engine?.hangup()
        val remaining = app?.engine?.call?.value
        if (remaining == null || remaining.finished) {
            lastMode = null
            lastTitle = null
            lastText = null
            val settings = app?.store?.loadSettings()
            val hasEnabled = app?.store?.loadAccounts()?.any { it.enabled } == true
            if (settings?.backgroundService == true && hasEnabled) {
                val (statusTitle, statusText) = resolveIdleStatus(app)
                dispatchNotification(
                    MODE_STATUS,
                    lastStatusTitle ?: statusTitle,
                    lastStatusText ?: statusText,
                    0L,
                    false,
                    false,
                )
            } else {
                clearForegroundFlags()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
            runCatching {
                getSystemService(NotificationManager::class.java)?.cancel(CALL_NOTIFICATION_ID)
            }
        }
    }

    private fun actionBroadcastPending(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(this, CallActionReceiver::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun statusNotification(title: String, text: String): Notification {
        ensureChannels()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_call)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun incomingNotification(callerName: String): Notification {
        ensureChannels()
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CALL, true)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this,
            1,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val answerIntent = Intent(this, CallService::class.java).apply { action = ACTION_ANSWER }
        val answerPending = PendingIntent.getForegroundService(
            this,
            2,
            answerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val declinePending = actionBroadcastPending(3, ACTION_DECLINE)

        val avatarIcon = IconCompat.createWithBitmap(callerAvatarBitmap(callerName, 0xFF1A73E8.toInt()))
        val caller = androidx.core.app.Person.Builder()
            .setName(callerName)
            .setIcon(avatarIcon)
            .setImportant(true)
            .build()

        val style = NotificationCompat.CallStyle.forIncomingCall(caller, declinePending, answerPending)

        return NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setStyle(style)
            .addPerson(caller)
            .setContentTitle("Incoming call")
            .setContentText(callerName)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(0xFF1B72E8.toInt())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSortKey("0000")
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)
            .build()
    }

    private fun ongoingNotification(
        callerName: String,
        statusText: String,
        startedAt: Long,
        isMuted: Boolean,
        isSpeaker: Boolean,
    ): Notification {
        ensureChannels()

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_OPEN_CALL, true)
        }
        val openPending = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val hangupPending = actionBroadcastPending(11, ACTION_DECLINE)
        val mutePending = actionBroadcastPending(12, ACTION_TOGGLE_MUTE)
        val speakerPending = actionBroadcastPending(13, ACTION_TOGGLE_SPEAKER)

        val accentGreen = 0xFF1E8E3E.toInt()
        val avatarIcon = IconCompat.createWithBitmap(callerAvatarBitmap(callerName, accentGreen))
        val caller = androidx.core.app.Person.Builder()
            .setName(callerName)
            .setIcon(avatarIcon)
            .setImportant(true)
            .build()

        val style = NotificationCompat.CallStyle.forOngoingCall(caller, hangupPending)

        val builder = NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setStyle(style)
            .addPerson(caller)
            .setContentTitle(callerName)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(accentGreen)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSortKey("0000")
            .setOngoing(true)
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)

        // Live One-UI-style chronometer timer in the status pill and expanded call card
        if (startedAt > 0) {
            builder.setWhen(startedAt)
                .setUsesChronometer(true)
                .setShowWhen(true)
        }

        // Quick action toggles in shade (CallStyle already supplies the red Hang up action)
        val muteLabel = if (isMuted) "Unmute" else "Mute"
        val muteIcon = if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic
        builder.addAction(muteIcon, muteLabel, mutePending)

        val speakerLabel = if (isSpeaker) "Earpiece" else "Speaker"
        builder.addAction(R.drawable.ic_volume_up, speakerLabel, speakerPending)

        return builder.build()
    }

    private fun callerAvatarBitmap(name: String, bgColor: Int): Bitmap {
        val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "J"
        val cacheKey = "${initial}_$bgColor"
        cachedAvatarBitmap?.takeIf { !it.isRecycled && cachedAvatarKey == cacheKey }?.let { return it }

        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val yPos = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initial, size / 2f, yPos, textPaint)
        cachedAvatarKey = cacheKey
        cachedAvatarBitmap = bmp
        return bmp
    }

    private fun ensureChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL_STATUS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STATUS,
                    getString(R.string.call_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.call_channel_description)
                    setShowBadge(false)
                },
            )
        }

        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    "Ongoing Calls",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Active call timer, quick controls, and live pill"
                    setShowBadge(true)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }

        if (manager.getNotificationChannel(CHANNEL_INCOMING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_INCOMING,
                    "Incoming Calls",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Incoming call alerts and heads-up banner"
                    setShowBadge(true)
                    // Sound and vibration are handled continuously by SipEngine so updates never re-ding
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_STATUS = "calls"
        private const val CHANNEL_ONGOING = "calls_ongoing_v6"
        private const val CHANNEL_INCOMING = "calls_incoming_v5"
        private const val STATUS_NOTIFICATION_ID = 42
        private const val CALL_NOTIFICATION_ID = 43

        const val EXTRA_OPEN_CALL = "open_call"

        const val MODE_STATUS = 0
        const val MODE_INCOMING = 1
        const val MODE_ONGOING = 2

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_IS_INCOMING = "is_incoming"
        private const val EXTRA_CALL_STARTED_AT = "call_started_at"
        private const val EXTRA_IS_MUTED = "is_muted"
        private const val EXTRA_IS_SPEAKER = "is_speaker"

        const val ACTION_ANSWER = "com.pp.jiocall.ANSWER"
        const val ACTION_DECLINE = "com.pp.jiocall.DECLINE"
        const val ACTION_TOGGLE_MUTE = "com.pp.jiocall.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.pp.jiocall.TOGGLE_SPEAKER"
        const val ACTION_START = "com.pp.jiocall.START"

        private val micFgsLock = Object()
        @Volatile
        private var micFgsActive = false
        @Volatile
        private var activeInstance: CallService? = null

        internal fun cancelCallNotification(context: Context) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)?.cancel(CALL_NOTIFICATION_ID)
            }
            activeInstance?.let { svc ->
                svc.lastMode = null
                svc.lastTitle = null
                svc.lastText = null
            }
        }

        internal fun handleNotificationAction(context: Context, action: String) {
            val app = context.applicationContext as? App ?: return
            when (action) {
                ACTION_DECLINE -> {
                    val running = activeInstance
                    if (running != null) {
                        running.handleDeclineInternal()
                    } else {
                        app.engine.hangup()
                        val remaining = app.engine.call.value
                        if (remaining == null || remaining.finished) {
                            cancelCallNotification(context)
                        }
                    }
                }

                ACTION_TOGGLE_MUTE -> {
                    app.engine.toggleMute()
                }

                ACTION_TOGGLE_SPEAKER -> {
                    app.engine.toggleSpeaker()
                }
            }
        }

        private fun setMicrophoneForegroundActive(active: Boolean) {
            synchronized(micFgsLock) {
                micFgsActive = active
                if (active) micFgsLock.notifyAll()
            }
        }

        /**
         * Waits briefly for [CallService] to finish promoting to `FOREGROUND_SERVICE_TYPE_MICROPHONE`
         * before opening `AudioRecord` so Android 11-17 does not silence the capture stream when
         * transitioning out of the idle `REMOTE_MESSAGING` service type.
         */
        fun awaitMicrophoneForeground(context: Context, timeoutMs: Long = 1200L): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) return false
            if (micFgsActive) return true
            // Actively request ongoing MIC+PHONE_CALL foreground promotion in case AsyncFlow hasn't dispatched yet
            val snap = (context.applicationContext as? App)?.engine?.call?.value
            val speaker = (context.applicationContext as? App)?.engine?.speaker?.value ?: false
            if (snap != null && !snap.finished) {
                val title = snap.name.ifEmpty { snap.number.ifEmpty { "Call" } }
                val status = if (snap.answered) "Ongoing call" else "Connecting…"
                showOngoing(context, title, status, snap.startedAt, snap.muted, speaker)
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(micFgsLock) {
                while (!micFgsActive) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    runCatching { micFgsLock.wait(remaining) }
                }
                return micFgsActive
            }
        }

        internal fun resolveForegroundType(mode: Int, hasMicPermission: Boolean, sdkInt: Int): Int = when {
            mode == MODE_STATUS && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            mode == MODE_ONGOING && hasMicPermission ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }

        fun start(context: Context) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, MODE_STATUS)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun update(context: Context, title: String, text: String) {
            val intent = Intent(context, CallService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_STATUS)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun showIncoming(context: Context, callerName: String) {
            val intent = Intent(context, CallService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_INCOMING)
                putExtra(EXTRA_TITLE, callerName)
                putExtra(EXTRA_IS_INCOMING, true)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun showOngoing(
            context: Context,
            callerName: String,
            statusText: String,
            startedAt: Long,
            isMuted: Boolean,
            isSpeaker: Boolean,
        ) {
            val intent = Intent(context, CallService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_ONGOING)
                putExtra(EXTRA_TITLE, callerName)
                putExtra(EXTRA_TEXT, statusText)
                putExtra(EXTRA_CALL_STARTED_AT, startedAt)
                putExtra(EXTRA_IS_MUTED, isMuted)
                putExtra(EXTRA_IS_SPEAKER, isSpeaker)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallService::class.java)) }
        }
    }
}
