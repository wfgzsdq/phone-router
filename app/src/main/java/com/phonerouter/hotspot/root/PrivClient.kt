package com.phonerouter.hotspot.root

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * Talks to the privileged shim (com.phonerouter.shim.Main) which is executed as the
 * system uid (1000) via `su 1000 app_process`. The shim exposes the hidden SoftAp /
 * TetheringManager APIs and returns a single JSON object on stdout.
 */
class PrivClient(private val context: Context) {

    private val shimRemote = "/data/local/tmp/phonerouter_shim.jar"

    /** Copy shim.jar out of assets to a world-readable path the system uid can exec. */
    @Synchronized
    fun ensureDeployed(): Boolean {
        val local = File(context.filesDir, "shim.jar")
        try {
            context.assets.open("shim.jar").use { input ->
                local.outputStream().use { input.copyTo(it) }
            }
        } catch (t: Throwable) {
            return false
        }
        val res = RootShell.run(
            listOf(
                "cp '${local.absolutePath}' '$shimRemote'",
                "chmod 644 '$shimRemote'",
                "chcon u:object_r:shell_data_file:s0 '$shimRemote' 2>/dev/null || true",
                "ls -l '$shimRemote'"
            )
        )
        return res.ok && res.out.contains("phonerouter_shim.jar")
    }

    private fun invoke(args: String): JSONObject {
        val cmd = "CLASSPATH=$shimRemote app_process /system/bin com.phonerouter.shim.Main $args 2>/dev/null"
        val res = RootShell.run(cmd, uid = 1000)
        val jsonLine = res.out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("{") && it.endsWith("}") }
            ?: return JSONObject().put("ok", false)
                .put("error", "no json output (code=${res.code})")
        return try {
            JSONObject(jsonLine)
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", "bad json: $jsonLine")
        }
    }

    fun getConfig(): JSONObject = invoke("get-config")

    fun tetherStart(): JSONObject = invoke("tether-start")

    fun tetherStop(): JSONObject = invoke("tether-stop")

    fun setConfig(
        ssid: String? = null,
        passphrase: String? = null,
        security: Int? = null,
        band: Int? = null,
        hidden: Boolean? = null,
        maxClients: Int? = null,
    ): JSONObject {
        val sb = StringBuilder("set-config")
        if (ssid != null) sb.append(" ssidb64=").append(b64(ssid))
        if (passphrase != null) sb.append(" passb64=").append(b64(passphrase))
        if (security != null) sb.append(" security=").append(security)
        if (band != null) sb.append(" band=").append(band)
        if (hidden != null) sb.append(" hidden=").append(hidden)
        if (maxClients != null) sb.append(" maxclients=").append(maxClients)
        return invoke(sb.toString())
    }

    fun blockClient(mac: String, block: Boolean): JSONObject =
        invoke((if (block) "block-client" else "unblock-client") + " mac=$mac")

    /** Mobile data used between [startMs] and [endMs] from NetworkStatsManager. */
    fun dataUsage(startMs: Long, endMs: Long): JSONObject =
        invoke("data-usage start=$startMs end=$endMs")

    /** Send an SMS from the system uid (no MIUI per-send confirmation). */
    fun sendSms(subId: Int, to: String, body: String): JSONObject =
        invoke("send-sms sub=$subId tob64=${b64(to)} bodyb64=${b64(body)}")

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
