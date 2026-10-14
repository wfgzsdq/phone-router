package com.phonerouter.hotspot.feature

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SmsManager
import com.phonerouter.hotspot.root.PrivClient
import com.phonerouter.hotspot.root.RootShell
import org.json.JSONArray
import org.json.JSONObject

/**
 * SMS access. Reading uses a root `content query` (no READ_SMS runtime permission
 * needed); sending uses SmsManager (requires SEND_SMS). SIM list is parsed from
 * `dumpsys isub` via root to avoid READ_PHONE_STATE.
 */
class SmsRepo(private val context: Context) {

    /** SIM subscriptions: subId, slot, name, number. */
    fun subs(): JSONArray {
        val res = RootShell.run("dumpsys isub 2>/dev/null")
        val arr = JSONArray()
        if (!res.ok) return arr
        val seen = HashSet<Int>()
        // Each active sub prints a line containing id=.. simSlotIndex=.. displayName=.. mNumber=..
        for (line in res.out.lines()) {
            if (!line.contains("id=") || !line.contains("simSlotIndex=")) continue
            val id = field(line, "id")?.toIntOrNull() ?: continue
            if (!seen.add(id)) continue
            val slot = field(line, "simSlotIndex")?.toIntOrNull() ?: -1
            val name = field(line, "displayName") ?: "SIM$slot"
            var number = field(line, "mNumber") ?: ""
            if (number == "null" || number.startsWith("[")) number = ""
            arr.put(JSONObject().put("subId", id).put("slot", slot).put("name", name).put("number", number))
        }
        return arr
    }

    private fun field(line: String, key: String): String? {
        val idx = line.indexOf("$key=")
        if (idx < 0) return null
        val start = idx + key.length + 1
        var end = start
        while (end < line.length && line[end] != ' ' && line[end] != ',' && line[end] != '}') end++
        return line.substring(start, end)
    }

    /** box: inbox | sent. Returns newest first. */
    fun messages(box: String, limit: Int): JSONArray {
        val uri = if (box == "sent") "content://sms/sent" else "content://sms/inbox"
        // body is placed last because it may contain commas.
        val res = RootShell.run(
            "content query --uri $uri --projection _id:address:date:read:type:body --sort 'date DESC' 2>/dev/null"
        )
        val arr = JSONArray()
        if (!res.ok) return arr
        // Split on row markers; a body may span lines, so don't split purely by \n.
        val chunks = res.out.split(Regex("(?=Row: \\d+ )"))
        for (chunk in chunks) {
            if (!chunk.startsWith("Row:")) continue
            if (arr.length() >= limit) break
            val address = between(chunk, "address=", ", date=") ?: between(chunk, "address=", ", ")
            val date = between(chunk, "date=", ", read=")?.toLongOrNull() ?: 0L
            val read = between(chunk, "read=", ", type=")
            val bodyIdx = chunk.indexOf("body=")
            val body = if (bodyIdx >= 0) chunk.substring(bodyIdx + 5).trim() else ""
            arr.put(
                JSONObject().apply {
                    put("address", address ?: "")
                    put("date", date)
                    put("read", read == "1")
                    put("body", body)
                }
            )
        }
        return arr
    }

    private fun between(s: String, a: String, b: String): String? {
        val i = s.indexOf(a); if (i < 0) return null
        val start = i + a.length
        val j = s.indexOf(b, start); if (j < 0) return null
        return s.substring(start, j)
    }

    /**
     * Sends via the privileged shim (system uid) first so MIUI does not raise its
     * per-send confirmation for non-default SMS apps (the device owner opted into this).
     * Falls back to SmsManager if the privileged path is unavailable.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun send(subId: Int, to: String, body: String): JSONObject {
        try {
            val pc = PrivClient(context)
            pc.ensureDeployed()
            val r = pc.sendSms(subId, to, body)
            if (r.optBoolean("ok", false)) return r
        } catch (_: Throwable) {
        }
        return try {
            val sm = if (subId > 0) {
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                SmsManager.getDefault()
            }
            val parts = sm.divideMessage(body)
            if (parts.size <= 1) {
                sm.sendTextMessage(to, null, body, null, null)
            } else {
                sm.sendMultipartTextMessage(to, null, parts, null, null)
            }
            JSONObject().put("ok", true)
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: "发送失败")
        }
    }
}
