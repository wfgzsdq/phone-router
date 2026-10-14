package com.phonerouter.hotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phonerouter.hotspot.hotspot.HotspotManager
import com.phonerouter.hotspot.hotspot.ScheduleManager
import kotlin.concurrent.thread

/** Fires on a scheduled time: toggles the hotspot, then re-arms the next occurrence. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE) return
        val doAction = intent.getStringExtra(EXTRA_DO) ?: return
        val pending = goAsync()
        thread {
            try {
                val mgr = HotspotManager(context.applicationContext)
                if (mgr.init()) {
                    mgr.setHotspot(doAction == "on")
                }
                // ensure service is running so the web UI stays up when turning on
                if (doAction == "on") HotspotService.start(context, autoStart = false)
                ScheduleManager(context.applicationContext).rescheduleAll()
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.phonerouter.hotspot.SCHEDULE_FIRE"
        const val EXTRA_ID = "id"
        const val EXTRA_DO = "do"
    }
}
