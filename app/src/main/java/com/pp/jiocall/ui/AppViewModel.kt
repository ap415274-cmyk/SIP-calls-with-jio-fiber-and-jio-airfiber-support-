package com.pp.jiocall.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pp.jiocall.App
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.AppSettings
import com.pp.jiocall.data.CallLogEntry
import com.pp.jiocall.data.PhoneUtils
import com.pp.jiocall.jio.JioRouter
import com.pp.jiocall.sip.AccountStatus
import com.pp.jiocall.sip.SipEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the screens show, and the actions they trigger. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app: App get() = getApplication()
    private val engine: SipEngine get() = app.engine
    private val context: android.content.Context get() = app

    val jio = app.jio
    val deviceName: String = app.deviceName

    val accounts = app.accountsFlow.asStateFlow()
    val settings = app.settingsFlow.asStateFlow()
    val calls = app.callsFlow.asStateFlow()
    val contacts = app.contactsFlow.asStateFlow()

    val contactLookup: StateFlow<ContactLookupIndex> = contacts
        .map { ContactLookupIndex(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ContactLookupIndex(emptyList()))

    val availableRecordings: StateFlow<Set<String>> = calls
        .map { list ->
            val paths = list.mapNotNull { it.recordingPath }.distinct()
            if (paths.isEmpty()) emptySet() else paths.filterTo(mutableSetOf()) { File(it).exists() }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val recentsUi: StateFlow<List<RecentsItemUi>> =
        combine(calls, contactLookup, availableRecordings) { callList, index, recs ->
            UiIndexBuilder.buildRecents(callList, index, recs)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val contactRowsUi: StateFlow<List<ContactRowUi>> =
        combine(contacts, calls) { contactList, callList ->
            UiIndexBuilder.buildContacts(contactList, callList)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val statuses = engine.statuses
    val activeCall = engine.call
    val secondaryCall = engine.secondaryCall
    val speaker = engine.speaker
    val callQuality = engine.quality

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _routerNote = MutableStateFlow<String?>(null)
    val routerNote = _routerNote.asStateFlow()

    init {
        app.startBackgroundEngine()
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            app.importContactsIfNeeded()
        }
    }

    // --------------------------------------------------------------- accounts

    fun saveAccount(account: Account) = app.saveAccount(account)

    fun deleteAccount(id: Long) = app.deleteAccount(id)

    fun setEnabled(account: Account, enabled: Boolean) = saveAccount(account.copy(enabled = enabled))

    /** Asks the router for new credentials for an account it made. */
    fun renewCredentials(account: Account) {
        val provisioning = account.provisioning ?: return
        viewModelScope.launch {
            _routerNote.value = "Requesting new credentials from ${provisioning.router}…"
            runCatching {
                withContext(Dispatchers.IO) {
                    jio.renewCredentials(provisioning.router, deviceName, provisioning.mode)
                }
            }
                .onSuccess { credentials ->
                    val now = System.currentTimeMillis() / 1000
                    val updated = JioRouter.applyCredentials(account, credentials, provisioning.router)
                        .copy(provisioning = provisioning.copy(expiresAt = credentials.validity?.let { now + it }))
                    saveAccount(updated)
                    _routerNote.value = "New credentials saved"
                }
                .onFailure { _routerNote.value = it.message ?: "Could not get new credentials" }
        }
    }

    // ---------------------------------------------------------------- calling

    fun dial(number: String, preferredAccountId: Long? = null): String? {
        val enabledAccounts = accounts.value.filter { it.enabled }
        val accountId = enabledAccounts.firstOrNull { it.id == preferredAccountId }?.id
            ?: enabledAccounts.firstOrNull()?.id
        if (accountId == null) {
            _message.value = "Add an account first"
            return "Add an account first"
        }
        val error = engine.dial(accountId, number)
        if (error != null) _message.value = error
        return error
    }

    fun dialVoicemail(preferredAccountId: Long? = null): String? {
        val enabledAccounts = accounts.value.filter { it.enabled }
        val account = enabledAccounts.firstOrNull { it.id == preferredAccountId }
            ?: enabledAccounts.firstOrNull()
        if (account == null) {
            _message.value = "Add an account first"
            return "Add an account first"
        }
        val vm = account.voicemailNumber.trim()
        if (vm.isEmpty()) {
            val msg = "Set a voicemail number in Account settings"
            _message.value = msg
            return msg
        }
        return dial(vm, account.id)
    }

    fun callBack(entry: CallLogEntry) = dial(entry.number)

    fun answer() = engine.answer()

    fun answerWaiting() = engine.answerWaiting()

    fun declineWaiting() = engine.declineWaiting()

    fun swapCalls() = engine.swapCalls()

    fun hangup() = engine.hangup()

    /** Closes the call page without leaving anything behind. */
    fun dismissCall() = engine.dismissCall()

    fun toggleMute(): Boolean = engine.toggleMute()

    fun toggleHold(): Boolean = engine.toggleHold()

    fun toggleSpeaker(): Boolean = engine.toggleSpeaker()

    fun toggleRecord(): Boolean = engine.toggleRecord()

    fun sendDtmf(digit: Char) = engine.sendDtmf(digit)

    fun silenceRinger() = engine.silenceRinger()

    // ------------------------------------------------------------------ other

    fun updateSettings(newSettings: AppSettings) = app.updateSettings(newSettings)

    fun toggleDarkMode() {
        val current = settings.value
        updateSettings(current.copy(darkMode = !current.darkMode))
    }

    fun clearCalls() = app.clearCalls()

    fun refreshCalls() = app.refreshCalls()

    fun importContactsIfNeeded() = app.importContactsIfNeeded()

    fun importContacts(onComplete: ((Int) -> Unit)? = null) = app.importContacts(onComplete)

    fun resolveName(number: String): String? =
        contactLookup.value.resolveName(number) ?: PhoneUtils.resolveName(number, contacts.value)

    fun resolvePhotoUri(number: String): String? {
        if (number.isBlank()) return null
        return contactLookup.value.resolvePhotoUri(number)
            ?: contacts.value.firstOrNull { PhoneUtils.matches(number, it.number) }?.photoUri
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearRouterNote() {
        _routerNote.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun statusOf(account: Account): AccountStatus? = statuses.value[account.id]
}
