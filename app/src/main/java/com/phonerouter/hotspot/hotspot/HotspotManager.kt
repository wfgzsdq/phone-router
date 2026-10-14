package com.phonerouter.hotspot.hotspot

import android.content.Context
import com.phonerouter.hotspot.Prefs
import com.phonerouter.hotspot.root.PrivClient
import com.phonerouter.hotspot.root.RootShell
import org.json.JSONObject
import java.util.Calendar

/**
 * High-level facade used by the UI, the web server and the boot flow. Wraps the
 * privileged shim, tethering control and client enumeration. LSPosed fixes the IP
 * inside the system tethering process, independently of this app's lifecycle.
 */
class HotspotManager(context: Context) {

    val prefs = Prefs(context)
    private val priv = PrivClient(context)
    private val tether = TetherController(priv)

    @Volatile private var startedAt = 0L

    /** Deploy the shim and verify root. Safe to call repeatedly. */
    fun init(): Boolean {
        if (!RootShell.hasRoot()) return false
        return priv.ensureDeployed()
    }

    fun hasRoot(): Boolean = RootShell.hasRoot()

    fun readConfig(): SoftApConfig = SoftApConfig.from(priv.getConfig())

    fun isActive(): Boolean = tether.isActive()

    /** Turn the hotspot on (and apply the speed limit) or off. */
    fun setHotspot(enabled: Boolean): Boolean {
        return if (enabled) {
            val ok = tether.start()
            if (ok) {
                startedAt = System.currentTimeMillis()
                // Allow the system to bring up the interface before applying tc.
                Thread.sleep(1200)
                SpeedLimit.apply(prefs.downKbps, prefs.upKbps)
            }
            ok
        } else {
            startedAt = 0L
            SpeedLimit.clear()
            tether.stop()
        }
    }

    // ---- speed limit ---------------------------------------------------------

    fun setSpeedLimit(downKbps: Int, upKbps: Int): JSONObject {
        prefs.downKbps = downKbps.coerceAtLeast(0)
        prefs.upKbps = upKbps.coerceAtLeast(0)
        val ok = if (isActive()) SpeedLimit.apply(prefs.downKbps, prefs.upKbps) else true
        return JSONObject().put("ok", ok).put("downKbps", prefs.downKbps).put("upKbps", prefs.upKbps)
    }

    fun speedLimitJson(): JSONObject = JSONObject()
        .put("ok", true).put("downKbps", prefs.downKbps).put("upKbps", prefs.upKbps)

    // ---- mobile data balance -------------------------------------------------

    /** Epoch ms of the current billing cycle start (reset day in prefs). */
    fun cycleStart(): Long {
        val day = prefs.cycleResetDay
        val now = Calendar.getInstance()
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val maxDay = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        c.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(maxDay))
        if (c.after(now)) c.add(Calendar.MONTH, -1)
        return c.timeInMillis
    }

    /** Query NetworkStatsManager for this cycle's usage and cache it. */
    fun refreshDataUsage(): JSONObject {
        val start = cycleStart()
        val now = System.currentTimeMillis()
        val res = priv.dataUsage(start, now)
        if (res.optBoolean("ok", false)) {
            prefs.cachedUsedBytes = res.optLong("totalBytes", 0L)
            prefs.usageUpdatedAt = now
        }
        return dataUsageJson()
    }

    fun dataUsageJson(): JSONObject {
        val quotaBytes = prefs.monthlyQuotaMb * 1024L * 1024L
        val used = prefs.cachedUsedBytes
        val remaining = if (quotaBytes > 0 && used >= 0) (quotaBytes - used).coerceAtLeast(0) else -1
        return JSONObject().apply {
            put("ok", true)
            put("usedBytes", used)
            put("quotaBytes", quotaBytes)
            put("remainingBytes", remaining)
            put("quotaMb", prefs.monthlyQuotaMb)
            put("resetDay", prefs.cycleResetDay)
            put("cycleStart", cycleStart())
            put("updatedAt", prefs.usageUpdatedAt)
        }
    }

    fun setDataPlan(quotaMb: Long, resetDay: Int): JSONObject {
        prefs.monthlyQuotaMb = quotaMb.coerceAtLeast(0)
        prefs.cycleResetDay = resetDay
        return refreshDataUsage()
    }

    fun applyConfig(
        ssid: String?, passphrase: String?, security: Int?,
        band: Int?, hidden: Boolean?, maxClients: Int?,
    ): JSONObject {
        val res = priv.setConfig(ssid, passphrase, security, band, hidden, maxClients)
        val ok = res.optBoolean("ok", false)
        if (ok && isActive()) {
            tether.restart()
            afterRestart()
        }
        return res
    }

    fun blockClient(mac: String, block: Boolean): JSONObject {
        val res = priv.blockClient(mac, block)
        if (res.optBoolean("ok", false) && isActive()) {
            tether.restart()
            afterRestart()
        }
        return res
    }

    fun rebootHotspot(): Boolean {
        val ok = tether.restart()
        if (ok) {
            startedAt = System.currentTimeMillis()
            afterRestart()
        }
        return ok
    }

    /** Re-apply speed limit after the AP link was torn down & brought back. */
    private fun afterRestart() {
        Thread.sleep(1200)
        SpeedLimit.apply(prefs.downKbps, prefs.upKbps)
    }

    /** Full status snapshot for the dashboard / API. */
    fun status(): JSONObject {
        val active = isActive()
        if (active && startedAt == 0L) startedAt = System.currentTimeMillis()
        if (!active) startedAt = 0L

        val iface = NetUtils.apInterface()
        val t = NetUtils.traffic(iface)
        val cfg = readConfig()
        val clients = ClientsManager.list(cfg.blocked)

        return JSONObject().apply {
            put("active", active)
            put("gateway", Prefs.GATEWAY)
            put("iface", iface)
            put("uptimeSeconds", if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else 0)
            put("rxBytes", t.rxBytes)
            put("txBytes", t.txBytes)
            put("clientCount", clients.size)
            put("config", cfg.toJson())
            put("root", true)
        }
    }

    fun clientsJson(): JSONObject {
        val cfg = readConfig()
        val clients = ClientsManager.list(cfg.blocked)
        return JSONObject().put("clients", ClientsManager.toJson(clients, cfg.blocked))
    }
}
