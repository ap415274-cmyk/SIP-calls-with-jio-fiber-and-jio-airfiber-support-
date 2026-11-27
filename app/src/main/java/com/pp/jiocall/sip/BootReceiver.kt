package com.pp.jiocall.sip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.pp.jiocall.App

/**
 * Automatically starts the app and its incoming call engine after device reboot,
 * system startup, or app updates without requiring the user to open the UI.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i("BootReceiver", "Received boot/startup action: $action")
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
        if (action !in validActions) return

        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked == false) {
            Log.i("BootReceiver", "Deferring startup until user storage unlocks")
            return
        }

        val app = context.applicationContext as? App ?: return
        runCatching {
            app.reloadFromStoreIfNeeded()
            val settings = app.store.loadSettings()
            val accounts = app.store.loadAccounts()
            if (settings.bootStart && settings.backgroundService && accounts.any { it.enabled }) {
                Log.i("BootReceiver", "Auto-starting JioCall background incoming engine after $action")
                app.startBackgroundEngine(ensureService = true)
            }
        }.onFailure { err ->
            Log.e("BootReceiver", "Boot startup failed: ${err.message}", err)
        }
    }
}
