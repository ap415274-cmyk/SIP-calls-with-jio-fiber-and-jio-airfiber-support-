package com.pp.jiocall

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.AppSettings
import com.pp.jiocall.data.CallLogEntry
import com.pp.jiocall.data.ContactItem
import com.pp.jiocall.data.DeviceContacts
import com.pp.jiocall.data.JioMode
import com.pp.jiocall.data.Store
import com.pp.jiocall.jio.JioClient
import com.pp.jiocall.jio.JioException
import com.pp.jiocall.jio.JioRouter
import com.pp.jiocall.sip.CallService
import com.pp.jiocall.sip.RegState
import com.pp.jiocall.sip.SipEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var engine: SipEngine
        private set
    lateinit var jio: JioClient
        private set

    lateinit var accountsFlow: MutableStateFlow<List<Account>>
        private set
    lateinit var settingsFlow: MutableStateFlow<AppSettings>
        private set
    lateinit var callsFlow: MutableStateFlow<List<CallLogEntry>>
        private set
    lateinit var contactsFlow: MutableStateFlow<List<ContactItem>>
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val engineLock = Any()
    @Volatile private var storeLoaded = false
    private var refreshJob: Job? = null
    private var serviceJob: Job? = null
    private var networkRecoveryJob: Job? = null
    private var contactsImportJob: Job? = null
    @Volatile private var networkWatcherRegistered = false
    @Volatile private var contactsObserverRegistered = false
    @Volatile private var contactsSyncedInSession = false
    private var lastStatusText: String? = null
    private var engineStarted = false
    private val cooldownUntil = ConcurrentHashMap<Long, Long>()

    val deviceName: String by lazy {
        val model = Build.MODEL.trim()
        val brand = Build.BRAND.replaceFirstChar { it.uppercase() }
        if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        engine = SipEngine(this, store)
        jio = JioClient(this)
        accountsFlow = MutableStateFlow(emptyList())
        settingsFlow = MutableStateFlow(AppSettings())
        callsFlow = MutableStateFlow(emptyList())
        contactsFlow = MutableStateFlow(emptyList())

        reloadFromStoreIfNeeded()
        if (storeLoaded) {
            val settings = settingsFlow.value
            val accounts = accountsFlow.value
            if (settings.bootStart && settings.backgroundService && accounts.any { it.enabled }) {
                Log.i("JioCallApp", "Auto-starting background engine on application launch")
                startBackgroundEngine(ensureService = true)
            }
        }
    }

    fun reloadFromStoreIfNeeded() {
        val unlocked = runCatching { getSystemService(UserManager::class.java)?.isUserUnlocked != false }.getOrDefault(true)
        if (!unlocked) return
        synchronized(engineLock) {
            if (storeLoaded) return
            runCatching {
                accountsFlow.value = store.loadAccounts()
                settingsFlow.value = store.loadSettings()
                callsFlow.value = store.loadCalls()
                contactsFlow.value = store.loadContacts()
                storeLoaded = true
            }.onFailure { err ->
                Log.w("JioCallApp", "Deferred store load until user unlock: ${err.message}")
            }
        }
    }

    fun startBackgroundEngine(ensureService: Boolean = true) {
        reloadFromStoreIfNeeded()
        val shouldStartService = synchronized(engineLock) {
            if (!engineStarted) {
                engineStarted = true
                engine.apply(accountsFlow.value, settingsFlow.value)
            }
            ensureService && settingsFlow.value.backgroundService && accountsFlow.value.any { it.enabled }
        }
        if (shouldStartService) {
            CallService.start(this)
        }
        startServiceWatcher()
        startNetworkWatcher()
        startRouterRefresh()
    }

    fun saveAccount(account: Account) {
        val updated = synchronized(engineLock) {
            val current = accountsFlow.value
            val existing = current.firstOrNull { it.id == account.id }
            val merged = if (existing?.provisioning != null && account.provisioning != null) {
                account.copy(
                    provisioning = account.provisioning.copy(
                        expiresAt = account.provisioning.expiresAt ?: existing.provisioning.expiresAt,
                    ),
                )
            } else {
                account
            }
            store.upsert(current, merged).also { next ->
                accountsFlow.value = next
                store.saveAccounts(next)
                engineStarted = true
                engine.apply(next, settingsFlow.value)
            }
        }
        Log.d("JioCallApp", "Saved account list (${updated.size} total)")
    }

    fun deleteAccount(id: Long) {
        synchronized(engineLock) {
            val updated = accountsFlow.value.filterNot { it.id == id }
            accountsFlow.value = updated
            store.saveAccounts(updated)
            engineStarted = true
            engine.apply(updated, settingsFlow.value)
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        synchronized(engineLock) {
            settingsFlow.value = newSettings
            store.saveSettings(newSettings)
            engineStarted = true
            engine.apply(accountsFlow.value, newSettings)
        }
    }

    fun refreshCalls() {
        scope.launch {
            val loaded = store.loadCalls()
            if (loaded != callsFlow.value) {
                callsFlow.value = loaded
            }
        }
    }

    fun clearCalls() {
        callsFlow.value = emptyList()
        scope.launch {
            store.clearCalls()
        }
    }

    fun importContactsIfNeeded() {
        ensureContactsObserver()
        if (contactsSyncedInSession && contactsFlow.value.isNotEmpty()) return
        importContacts()
    }

    fun importContacts(onComplete: ((Int) -> Unit)? = null) {
        ensureContactsObserver()
        contactsImportJob?.cancel()
        contactsImportJob = scope.launch {
            val list = DeviceContacts.readDeviceContacts(this@App)
            contactsSyncedInSession = true
            if (list != contactsFlow.value) {
                store.saveContacts(list)
                contactsFlow.value = list
            }
            withContext(Dispatchers.Main) {
                onComplete?.invoke(list.size)
            }
        }
    }

    private fun ensureContactsObserver() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        synchronized(engineLock) {
            if (contactsObserverRegistered) return
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    contactsImportJob?.cancel()
                    contactsImportJob = scope.launch {
                        delay(250)
                        val list = DeviceContacts.readDeviceContacts(this@App)
                        store.saveContacts(list)
                        contactsFlow.value = list
                    }
                }
            }
            val registered = runCatching {
                contentResolver.registerContentObserver(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    true,
                    observer,
                )
            }.isSuccess
            if (registered) contactsObserverRegistered = true
        }
    }

    private fun startServiceWatcher() {
        if (serviceJob?.isActive == true) return
        serviceJob = scope.launch {
            combine(accountsFlow, engine.statuses, engine.call, settingsFlow) { accounts, statuses, call, settings ->
                Triple(accounts, statuses, call to settings)
            }.collect { (accounts, statuses, rest) ->
                val (call, settings) = rest
                // CallService itself observes engine.call + engine.speaker during active/ringing calls.
                if (call != null && !call.finished) return@collect
                val enabled = accounts.any { it.enabled }
                if (enabled && settings.backgroundService) {
                    val registered = statuses.values.count { it.state == RegState.REGISTERED }
                    val text = if (registered > 0) "Registered — $registered account(s)" else "Connecting…"
                    if (text != lastStatusText || call?.finished == true) {
                        lastStatusText = text
                        CallService.update(this@App, getString(R.string.app_name), text)
                    }
                } else {
                    lastStatusText = null
                    CallService.stop(this@App)
                }
            }
        }
    }

    private fun startNetworkWatcher() {
        synchronized(engineLock) {
            if (networkWatcherRegistered) return
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            fun newCallback() = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scheduleNetworkRecovery()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    scheduleNetworkRecovery()
                }

                override fun onLost(network: Network) {
                    scheduleNetworkRecovery()
                }
            }
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val okDefault = runCatching { cm.registerDefaultNetworkCallback(newCallback()) }.isSuccess
            val okWifi = runCatching { cm.registerNetworkCallback(wifiRequest, newCallback()) }.isSuccess
            if (okDefault || okWifi) {
                networkWatcherRegistered = true
            }
        }
    }

    private fun scheduleNetworkRecovery() {
        networkRecoveryJob?.cancel()
        networkRecoveryJob = scope.launch {
            delay(450)
            reloadFromStoreIfNeeded()
            if (accountsFlow.value.none { it.enabled }) return@launch
            Log.i("JioCallApp", "Network state/link updated; verifying router credentials & SIP registration")
            refreshStaleCredentials(clearNetworkBackoff = true)
            engine.onNetworkChanged()
        }
    }

    private fun startRouterRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (isActive) {
                refreshStaleCredentials(clearNetworkBackoff = false)
                delay(60_000)
            }
        }
    }

    private suspend fun refreshStaleCredentials(clearNetworkBackoff: Boolean) {
        val settings = settingsFlow.value
        if (!settings.autoRefreshRouter) return
        val accounts = accountsFlow.value
        val now = System.currentTimeMillis() / 1000
        val statuses = engine.statuses.value
        for (account in accounts) {
            val provisioning = account.provisioning ?: continue
            if (!account.enabled || !provisioning.autoRefresh) continue
            val status = statuses[account.id]
            val failed = status?.state == RegState.FAILED
            val authFailed = failed && status?.reason?.contains("Authentication rejected", ignoreCase = true) == true
            val shouldRenew = when (provisioning.mode) {
                JioMode.TEMPORARY -> {
                    val expired = provisioning.expiresAt?.let { it <= now } ?: false
                    val expiring = provisioning.expiresAt?.let { it - now <= 60 } ?: false
                    failed || expiring || expired
                }
                JioMode.PERMANENT -> authFailed
            }
            if (!shouldRenew) continue

            val nextAllowed = cooldownUntil[account.id] ?: 0L
            if (clearNetworkBackoff && nextAllowed - now <= 20L) {
                cooldownUntil.remove(account.id)
            } else if (now < nextAllowed) {
                continue
            }

            try {
                val credentials = withContext(Dispatchers.IO) {
                    jio.renewCredentials(provisioning.router, deviceName, provisioning.mode)
                }
                cooldownUntil[account.id] = now + 60L
                val updated = JioRouter.applyCredentials(account, credentials, provisioning.router)
                    .copy(provisioning = provisioning.copy(expiresAt = credentials.validity?.let { now + it }))
                saveAccount(updated)
            } catch (refused: JioException) {
                Log.w("JioCallApp", "Router credential renewal refused for ${account.title()}: ${refused.message}")
                cooldownUntil[account.id] = now + 300L
            } catch (ioError: Exception) {
                // Transient offline/Wi-Fi startup error: allow fast retry as soon as NetworkCallback fires.
                Log.d("JioCallApp", "Router unreachable during renewal for ${account.title()}: ${ioError.message}")
                cooldownUntil[account.id] = now + 10L
            }
        }
    }
}
