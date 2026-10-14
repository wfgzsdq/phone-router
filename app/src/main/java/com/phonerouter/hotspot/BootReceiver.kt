package com.phonerouter.hotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the foreground service (and hotspot) after boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            HotspotService.start(context, autoStart = true)
        }
    }
}
