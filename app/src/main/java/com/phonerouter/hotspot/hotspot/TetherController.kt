package com.phonerouter.hotspot.hotspot

import com.phonerouter.hotspot.root.PrivClient

/** Starts / stops internet-sharing WiFi tethering via the privileged shim. */
class TetherController(private val priv: PrivClient) {

    /** A gateway IP present on some interface means the AP link is up. */
    fun isActive(): Boolean = NetUtils.gatewayPresent()

    fun start(): Boolean = priv.tetherStart().optBoolean("ok", false)

    fun stop(): Boolean = priv.tetherStop().optBoolean("ok", false)

    /** Stop, wait for the link to drop, then start again (needed to apply config changes). */
    fun restart(): Boolean {
        stop()
        Thread.sleep(1500)
        return start()
    }
}
