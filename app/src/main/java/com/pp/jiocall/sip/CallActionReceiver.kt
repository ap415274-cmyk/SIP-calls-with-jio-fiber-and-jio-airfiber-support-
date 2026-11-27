package com.pp.jiocall.sip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles Call notification action buttons (Decline, Hang up, Mute, Speaker) synchronously
 * without relying on `PendingIntent.getService`, which Android 8-17 blocks when the service
 * is not actively foregrounded or the device is locked/dozed.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        CallService.handleNotificationAction(context, action)
    }
}
