package com.phonerouter.hotspot.web

import android.content.Context
import com.phonerouter.hotspot.feature.FileRepo
import com.phonerouter.hotspot.feature.SmsRepo
import com.phonerouter.hotspot.hotspot.HotspotManager
import com.phonerouter.hotspot.hotspot.Schedule
import com.phonerouter.hotspot.hotspot.ScheduleManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.SecureRandom
import java.util.Collections

/**
 * Embedded management server. Serves the static web UI from assets/web and a JSON API
 * guarded by a token from /api/login. Bound to all interfaces, reachable at
 * http://192.168.43.1:<port> from any hotspot client.
 */
class WebServer(
    private val context: Context,
    private val manager: HotspotManager,
    port: Int,
    private val password: String,
) : NanoHTTPD(port) {

    private val tokens = Collections.synchronizedSet(HashSet<String>())
    private val rng = SecureRandom()
    private val schedules = ScheduleManager(context)
    private val sms = SmsRepo(context)
    private val files = FileRepo(context)

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri
            when {
                uri.startsWith("/api/") -> handleApi(session, uri)
                else -> serveStatic(uri)
            }
        } catch (t: Throwable) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", t.message ?: "error"))
        }
    }

    // ---- API ----------------------------------------------------------------

    private fun handleApi(session: IHTTPSession, uri: String): Response {
        if (uri == "/api/login" && session.method == Method.POST) {
            val pw = readJson(session).optString("password")
            return if (pw == password) {
                val token = newToken()
                tokens.add(token)
                json(Response.Status.OK, JSONObject().put("ok", true).put("token", token))
            } else {
                json(Response.Status.FORBIDDEN, JSONObject().put("ok", false).put("error", "密码错误"))
            }
        }

        if (!authorized(session)) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("ok", false).put("error", "未授权"))
        }

        val get = session.method == Method.GET
        val post = session.method == Method.POST
        return when {
            uri == "/api/status" && get -> json(Response.Status.OK, manager.status())

            // hotspot config
            uri == "/api/config" && get ->
                json(Response.Status.OK, JSONObject().put("ok", true).put("config", manager.readConfig().toJson()))
            uri == "/api/config" && post -> {
                val b = readJson(session)
                json(Response.Status.OK, manager.applyConfig(
                    ssid = b.optStringOrNull("ssid"),
                    passphrase = b.optStringOrNull("passphrase"),
                    security = b.optIntOrNull("security"),
                    band = b.optIntOrNull("band"),
                    hidden = if (b.has("hidden")) b.optBoolean("hidden") else null,
                    maxClients = b.optIntOrNull("maxClients"),
                ))
            }
            uri == "/api/hotspot" && post -> {
                val enabled = readJson(session).optBoolean("enabled", false)
                json(Response.Status.OK, JSONObject().put("ok", manager.setHotspot(enabled)).put("active", manager.isActive()))
            }
            uri == "/api/reboot-hotspot" && post ->
                json(Response.Status.OK, JSONObject().put("ok", manager.rebootHotspot()))

            // clients
            uri == "/api/clients" && get -> json(Response.Status.OK, manager.clientsJson().put("ok", true))
            uri == "/api/clients/block" && post -> {
                val b = readJson(session)
                json(Response.Status.OK, manager.blockClient(b.optString("mac"), b.optBoolean("block", true)))
            }

            // data balance
            uri == "/api/data-usage" && get -> json(Response.Status.OK, manager.dataUsageJson())
            uri == "/api/data-usage/refresh" && post -> json(Response.Status.OK, manager.refreshDataUsage())
            uri == "/api/data-usage/config" && post -> {
                val b = readJson(session)
                json(Response.Status.OK, manager.setDataPlan(b.optLong("quotaMb", 0), b.optInt("resetDay", 1)))
            }

            // speed limit
            uri == "/api/speed-limit" && get -> json(Response.Status.OK, manager.speedLimitJson())
            uri == "/api/speed-limit" && post -> {
                val b = readJson(session)
                json(Response.Status.OK, manager.setSpeedLimit(b.optInt("downKbps", 0), b.optInt("upKbps", 0)))
            }

            // schedules
            uri == "/api/schedules" && get ->
                json(Response.Status.OK, JSONObject().put("ok", true).put("schedules", schedules.toJsonArray()))
            uri == "/api/schedules" && post -> {
                val b = readJson(session)
                val days = mutableListOf<Int>()
                b.optJSONArray("days")?.let { for (i in 0 until it.length()) days.add(it.getInt(i)) }
                val s = schedules.add(
                    Schedule(
                        id = b.optLong("id", 0),
                        hour = b.optInt("hour", 0),
                        minute = b.optInt("minute", 0),
                        days = days,
                        action = b.optString("action", "on"),
                        enabled = b.optBoolean("enabled", true),
                    )
                )
                json(Response.Status.OK, JSONObject().put("ok", true).put("schedule", s.toJson()))
            }
            uri == "/api/schedules/delete" && post -> {
                schedules.remove(readJson(session).optLong("id", 0)); okResp()
            }
            uri == "/api/schedules/toggle" && post -> {
                val b = readJson(session)
                schedules.setEnabled(b.optLong("id", 0), b.optBoolean("enabled", true)); okResp()
            }

            // SMS
            uri == "/api/sms/subs" && get ->
                json(Response.Status.OK, JSONObject().put("ok", true).put("subs", sms.subs()))
            uri == "/api/sms" && get -> {
                val box = param(session, "box") ?: "inbox"
                val limit = param(session, "limit")?.toIntOrNull() ?: 50
                json(Response.Status.OK, JSONObject().put("ok", true).put("messages", sms.messages(box, limit)))
            }
            uri == "/api/sms/send" && post -> {
                val b = readJson(session)
                json(Response.Status.OK, sms.send(b.optInt("subId", 0), b.optString("to"), b.optString("body")))
            }

            // files
            uri == "/api/files" && get -> json(Response.Status.OK, files.list(param(session, "path")))
            uri == "/api/files/download" && get -> downloadFile(param(session, "path"))
            uri == "/api/files/mkdir" && post -> {
                val b = readJson(session); json(Response.Status.OK, files.mkdir(b.optString("path", "/"), b.optString("name")))
            }
            uri == "/api/files/delete" && post ->
                json(Response.Status.OK, files.delete(readJson(session).optString("path")))
            uri == "/api/files/rename" && post -> {
                val b = readJson(session); json(Response.Status.OK, files.rename(b.optString("path"), b.optString("newName")))
            }
            uri == "/api/files/upload" && post -> uploadFile(session)

            else -> json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not found"))
        }
    }

    private fun downloadFile(path: String?): Response {
        val f = files.prepareDownload(path) ?: return json(
            Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "文件不可读")
        )
        val resp = newFixedLengthResponse(
            Response.Status.OK, "application/octet-stream", FileInputStream(f), f.length()
        )
        resp.addHeader("Content-Disposition", "attachment; filename*=UTF-8''${encode(f.name)}")
        return resp
    }

    private fun uploadFile(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val params = session.parameters
        val targetDir = params["path"]?.firstOrNull() ?: "/"
        val fname = params["file"]?.firstOrNull() ?: "upload.bin"
        val tmpPath = map["file"] ?: return json(
            Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "无文件")
        )
        return json(Response.Status.OK, files.saveUpload(targetDir, fname, File(tmpPath)))
    }

    private fun authorized(session: IHTTPSession): Boolean {
        session.headers["x-auth"]?.let { if (tokens.contains(it)) return true }
        param(session, "token")?.let { if (tokens.contains(it)) return true }
        session.headers["cookie"]?.let { cookie ->
            val tok = cookie.split(";").map { it.trim() }
                .firstOrNull { it.startsWith("pr_token=") }?.substringAfter("pr_token=")
            if (tok != null && tokens.contains(tok)) return true
        }
        return false
    }

    private fun newToken(): String {
        val bytes = ByteArray(24); rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---- static --------------------------------------------------------------

    private fun serveStatic(uri: String): Response {
        val path = if (uri == "/" || uri.isEmpty()) "web/index.html" else "web${uri}"
        return try {
            val bytes = context.assets.open(path).use { it.readBytes() }
            newFixedLengthResponse(Response.Status.OK, mime(path), bytes.inputStream(), bytes.size.toLong())
        } catch (t: Throwable) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    private fun mime(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }

    // ---- helpers --------------------------------------------------------------

    private fun param(session: IHTTPSession, key: String): String? =
        session.parameters[key]?.firstOrNull()

    private fun readJson(session: IHTTPSession): JSONObject {
        return try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val raw = map["postData"] ?: session.queryParameterString
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        } catch (t: Throwable) {
            JSONObject()
        }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())

    private fun okResp(): Response = json(Response.Status.OK, JSONObject().put("ok", true))

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null
}
