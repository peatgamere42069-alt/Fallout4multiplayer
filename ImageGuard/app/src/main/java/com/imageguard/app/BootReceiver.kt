package com.imageguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts protection after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store.get(context).autoProtect && Permissions.hasStorage(context)) {
            try {
                GuardService.start(context)
            } catch (e: Exception) {
                // Some OEMs block background starts; the app restarts protection when next opened.
            }
        }
    }
}
