package com.phonerouter.hotspot.hotspot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.phonerouter.hotspot.Prefs
import com.phonerouter.hotspot.ScheduleReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** One timed hotspot action. days: 1=Mon..7=Sun; empty = every day. */
data class Schedule(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val days: List<Int>,
    val action: String, // "on" | "off"
    val enabled: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("hour", hour); put("minute", minute)
        put("days", JSONArray(days)); put("action", action); put("enabled", enabled)
    }

    companion object {
        fun from(j: JSONObject): Schedule {
            val days = mutableListOf<Int>()
            j.optJSONArray("days")?.let { for (i in 0 until it.length()) days.add(it.getInt(i)) }
            return Schedule(
                id = j.optLong("id", System.currentTimeMillis()),
                hour = j.optInt("hour", 0),
                minute = j.optInt("minute", 0),
                days = days,
                action = j.optString("action", "on"),
                enabled = j.optBoolean("enabled", true),
            )
        }
    }
}

class ScheduleManager(private val context: Context) {

    private val prefs = Prefs(context)
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun list(): List<Schedule> {
        val arr = JSONArray(prefs.schedulesJson)
        return (0 until arr.length()).map { Schedule.from(arr.getJSONObject(it)) }
    }

    fun toJsonArray(): JSONArray = JSONArray().apply { list().forEach { put(it.toJson()) } }

    private fun save(items: List<Schedule>) {
        prefs.schedulesJson = JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
    }

    fun add(s: Schedule): Schedule {
        val id = if (s.id > 0) s.id else System.currentTimeMillis()
        val item = s.copy(id = id)
        save(list().filter { it.id != id } + item)
        rescheduleAll()
        return item
    }

    fun remove(id: Long) {
        save(list().filter { it.id != id })
        rescheduleAll()
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
        rescheduleAll()
    }

    /** Cancel all alarms and (re)arm the next occurrence of each enabled schedule. */
    fun rescheduleAll() {
        for (s in list()) {
            val pi = pendingIntent(s.id, s.action)
            am.cancel(pi)
            if (!s.enabled) continue
            val next = nextFireTime(s) ?: continue
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } catch (t: SecurityException) {
                am.set(AlarmManager.RTC_WAKEUP, next, pi)
            }
        }
    }

    private fun pendingIntent(id: Long, action: String): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            this.action = ScheduleReceiver.ACTION_FIRE
            putExtra(ScheduleReceiver.EXTRA_ID, id)
            putExtra(ScheduleReceiver.EXTRA_DO, action)
        }
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextFireTime(s: Schedule): Long? {
        val now = Calendar.getInstance()
        for (offset in 0..7) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, offset)
            c.set(Calendar.HOUR_OF_DAY, s.hour)
            c.set(Calendar.MINUTE, s.minute)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            if (c.before(now)) continue
            if (s.days.isEmpty() || s.days.any { it.toCalendarDow() == c.get(Calendar.DAY_OF_WEEK) }) {
                return c.timeInMillis
            }
        }
        return null
    }

    /** 1=Mon..7=Sun -> Calendar.DAY_OF_WEEK (SUN=1..SAT=7). */
    private fun Int.toCalendarDow(): Int = (this % 7) + 1
}
