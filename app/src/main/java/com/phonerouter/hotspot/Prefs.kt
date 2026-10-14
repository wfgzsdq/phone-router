package com.phonerouter.hotspot

import android.content.Context

/** Lightweight SharedPreferences wrapper for app settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("phone_router", Context.MODE_PRIVATE)

    var webPort: Int
        get() = sp.getInt("web_port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("web_port", v).apply()

    /** Web management password (plain; LAN-only tool). Empty = no auth. */
    var webPassword: String
        get() = sp.getString("web_password", DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        set(v) = sp.edit().putString("web_password", v).apply()

    /** Whether to automatically start the hotspot on boot / service start. */
    var autoStartHotspot: Boolean
        get() = sp.getBoolean("autostart_hotspot", true)
        set(v) = sp.edit().putBoolean("autostart_hotspot", v).apply()

    // ---- data plan / balance -------------------------------------------------

    /** Monthly mobile-data quota in MB (0 = not configured). */
    var monthlyQuotaMb: Long
        get() = sp.getLong("quota_mb", 0L)
        set(v) = sp.edit().putLong("quota_mb", v).apply()

    /** Billing cycle reset day of month (1..28). */
    var cycleResetDay: Int
        get() = sp.getInt("cycle_reset_day", 1)
        set(v) = sp.edit().putInt("cycle_reset_day", v.coerceIn(1, 28)).apply()

    /** Cached last-known used bytes this cycle and when it was measured. */
    var cachedUsedBytes: Long
        get() = sp.getLong("used_bytes", -1L)
        set(v) = sp.edit().putLong("used_bytes", v).apply()

    var usageUpdatedAt: Long
        get() = sp.getLong("usage_updated_at", 0L)
        set(v) = sp.edit().putLong("usage_updated_at", v).apply()

    // ---- speed limit (Kbps, 0 = unlimited) -----------------------------------

    var downKbps: Int
        get() = sp.getInt("down_kbps", 0)
        set(v) = sp.edit().putInt("down_kbps", v).apply()

    var upKbps: Int
        get() = sp.getInt("up_kbps", 0)
        set(v) = sp.edit().putInt("up_kbps", v).apply()

    // ---- scheduled on/off (JSON array) ---------------------------------------

    var schedulesJson: String
        get() = sp.getString("schedules", "[]") ?: "[]"
        set(v) = sp.edit().putString("schedules", v).apply()

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_PASSWORD = "admin"
        const val GATEWAY = "192.168.43.1"
        const val AP_IFACE = "wlan1"
    }
}
