package com.pp.jiocall.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Accounts, settings and the call log, kept in the app's private preferences. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jiocall", Context.MODE_PRIVATE)
    private val lock = Any()

    fun loadSettings(): AppSettings = synchronized(lock) {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        runCatching { AppSettings.fromJson(JSONObject(raw)) }.getOrElse { AppSettings() }
    }

    fun saveSettings(settings: AppSettings) = synchronized(lock) {
        prefs.edit().putString(KEY_SETTINGS, settings.toJson().toString()).apply()
    }

    fun loadAccounts(): List<Account> = synchronized(lock) {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        runCatching { Account.fromArray(JSONArray(raw)) }.getOrElse { emptyList() }
    }

    fun saveAccounts(accounts: List<Account>) = synchronized(lock) {
        val array = JSONArray()
        accounts.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    /** Puts the account in place: by id if it has one and exists, else new. */
    fun upsert(accounts: List<Account>, account: Account): List<Account> {
        if (accounts.any { it.id == account.id && account.id != 0L }) {
            return accounts.map { if (it.id == account.id) account else it }
        }
        val id = (accounts.maxOfOrNull { it.id } ?: 0L) + 1L
        return accounts + account.copy(id = id)
    }

    fun loadCalls(): List<CallLogEntry> = synchronized(lock) {
        val raw = prefs.getString(KEY_CALLS, null) ?: return emptyList()
        runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? JSONObject)?.let { runCatching { CallLogEntry.fromJson(it) }.getOrNull() }
            }
        }.getOrElse { emptyList() }
    }

    fun addCall(entry: CallLogEntry): List<CallLogEntry> = synchronized(lock) {
        val calls = (listOf(entry) + loadCalls()).take(MAX_CALLS)
        val array = JSONArray()
        calls.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CALLS, array.toString()).apply()
        calls
    }

    fun clearCalls() = synchronized(lock) {
        loadCalls().mapNotNull { it.recordingPath }.distinct().forEach { path ->
            runCatching { java.io.File(path).takeIf { it.exists() }?.delete() }
        }
        prefs.edit().remove(KEY_CALLS).apply()
    }

    fun loadContacts(): List<ContactItem> = synchronized(lock) {
        val raw = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? JSONObject)?.let { runCatching { ContactItem.fromJson(it) }.getOrNull() }
            }
        }.getOrElse { emptyList() }
    }

    fun saveContacts(contacts: List<ContactItem>) = synchronized(lock) {
        val array = JSONArray()
        contacts.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CONTACTS, array.toString()).apply()
    }

    fun clearContacts() = synchronized(lock) {
        prefs.edit().remove(KEY_CONTACTS).apply()
    }

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_SETTINGS = "settings"
        const val KEY_CALLS = "calls"
        const val KEY_CONTACTS = "contacts"
        const val MAX_CALLS = 200
    }
}
