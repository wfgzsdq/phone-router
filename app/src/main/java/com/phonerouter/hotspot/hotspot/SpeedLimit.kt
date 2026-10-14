package com.phonerouter.hotspot.hotspot

import com.phonerouter.hotspot.root.RootShell

/**
 * Global hotspot bandwidth cap via `tc` on the AP interface.
 *  - download (phone -> clients): egress HTB shaping
 *  - upload   (clients -> phone): ingress policing (drops excess)
 * Values are in kilobit/s; 0 means unlimited for that direction. tc state is cleared
 * whenever the AP link drops, so this is re-applied after every (re)start.
 */
object SpeedLimit {

    fun clear(iface: String = NetUtils.apInterface()): Boolean {
        val r = RootShell.run(
            listOf(
                "tc qdisc del dev $iface root 2>/dev/null || true",
                "tc qdisc del dev $iface ingress 2>/dev/null || true",
            )
        )
        return r.ok
    }

    fun apply(downKbps: Int, upKbps: Int, iface: String = NetUtils.apInterface()): Boolean {
        val cmds = mutableListOf(
            "tc qdisc del dev $iface root 2>/dev/null || true",
            "tc qdisc del dev $iface ingress 2>/dev/null || true",
        )
        if (downKbps > 0) {
            cmds += "tc qdisc add dev $iface root handle 1: htb default 10"
            cmds += "tc class add dev $iface parent 1: classid 1:10 htb rate ${downKbps}kbit ceil ${downKbps}kbit burst 15k"
        }
        if (upKbps > 0) {
            cmds += "tc qdisc add dev $iface handle ffff: ingress"
            cmds += "tc filter add dev $iface parent ffff: protocol all prio 1 u32 match u32 0 0 " +
                "police rate ${upKbps}kbit burst 15k mtu 1500 drop flowid :1"
        }
        cmds += "echo DONE"
        val r = RootShell.run(cmds)
        return r.out.contains("DONE")
    }
}
