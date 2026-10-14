package com.phonerouter.hotspot.hotspot

import com.phonerouter.hotspot.root.RootShell
import org.json.JSONArray
import org.json.JSONObject

/** Enumerates devices connected to the hotspot and resolves their hostnames best-effort. */
object ClientsManager {

    data class Client(
        val ip: String,
        val mac: String,
        val state: String,
        val hostname: String?,
        val blocked: Boolean,
    )

    private val leaseFiles = listOf(
        "/data/misc/dhcp/dnsmasq.leases",
        "/data/misc/dhcp/dnsmasq.leases.wlan1",
        "/data/misc/wifi/softap.leases",
    )

    fun list(blockedMacs: List<String>): List<Client> {
        val iface = NetUtils.apInterface()
        val prefixes = NetUtils.apIPv4Prefixes(iface)
        val hostnames = readLeaseHostnames()
        val blockedLower = blockedMacs.map { it.lowercase() }.toSet()

        val res = RootShell.run("ip neigh show dev $iface 2>/dev/null")
        val clients = mutableListOf<Client>()
        if (res.ok) {
            for (raw in res.out.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || !line.contains("lladdr")) continue
                val parts = line.split(Regex("\\s+"))
                val ip = parts[0]
                if (prefixes.isNotEmpty()) {
                    if (prefixes.none { ip.startsWith(it) }) continue
                } else if (!ip.startsWith("192.168.")) {
                    // Fallback if we couldn't read the AP's own address: still restrict
                    // to private hotspot-style ranges so random IPv6/public hosts don't leak in.
                    continue
                }
                val macIdx = parts.indexOf("lladdr")
                if (macIdx < 0 || macIdx + 1 >= parts.size) continue
                val mac = parts[macIdx + 1].lowercase()
                val state = parts.last()
                if (state == "FAILED" || state == "INCOMPLETE") continue
                clients.add(
                    Client(
                        ip = ip,
                        mac = mac,
                        state = state,
                        hostname = hostnames[mac] ?: hostnames[ip],
                        blocked = blockedLower.contains(mac),
                    )
                )
            }
        }
        return clients.distinctBy { it.mac }
    }

    /** Also surface blocked devices that aren't currently in the neighbour table. */
    fun toJson(clients: List<Client>, blockedMacs: List<String>): JSONArray {
        val arr = JSONArray()
        val seen = mutableSetOf<String>()
        for (c in clients) {
            seen.add(c.mac.lowercase())
            arr.put(
                JSONObject().apply {
                    put("ip", c.ip)
                    put("mac", c.mac)
                    put("state", c.state)
                    put("hostname", c.hostname ?: "")
                    put("blocked", c.blocked)
                    put("online", true)
                }
            )
        }
        for (m in blockedMacs) {
            if (seen.contains(m.lowercase())) continue
            arr.put(
                JSONObject().apply {
                    put("ip", "")
                    put("mac", m.lowercase())
                    put("state", "OFFLINE")
                    put("hostname", "")
                    put("blocked", true)
                    put("online", false)
                }
            )
        }
        return arr
    }

    private fun readLeaseHostnames(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (path in leaseFiles) {
            val content = RootShell.run("cat '$path' 2>/dev/null")
            if (!content.ok || content.out.isBlank()) continue
            // dnsmasq.leases: "<expiry> <mac> <ip> <hostname> <clientid>"
            for (line in content.out.lines()) {
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 4) {
                    val mac = p[1].lowercase()
                    val ip = p[2]
                    val host = p[3]
                    if (host != "*") {
                        map[mac] = host
                        map[ip] = host
                    }
                }
            }
        }
        return map
    }
}
