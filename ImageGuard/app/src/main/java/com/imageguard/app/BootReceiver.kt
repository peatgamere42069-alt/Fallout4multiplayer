package com.imageguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Restarts protection after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store.get(context)
        if (store.webFilter && VpnService.prepare(context) == null) {
            try {
                WebFilterService.start(context)
            } catch (e: Exception) {
                // Turning on "Always-on VPN" in Settings makes Android start it instead.
            }
        }
        if (store.autoProtect && Permissions.hasStorage(context)) {
            try {
                GuardService.start(context)
            } catch (e: Exception) {
                // Some OEMs block background starts; the app restarts protection when next opened.
            }
        }
    }
}
