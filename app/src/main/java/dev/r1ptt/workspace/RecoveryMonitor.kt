package dev.r1ptt.workspace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.r1ptt.App

/** Event driven: no polling or battery exception is needed to recover when the R1 wakes/connects. */
class RecoveryMonitor(private val app: App) {
    private var started = false
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> app.workspace.fenceUnlessFresh()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> app.workspace.wake()
            }
        }
    }
    private val network = object : ConnectivityManager.NetworkCallback() {
        private var available: Network? = null
        private var usable = false
        override fun onAvailable(network: Network) {
            available = network
            usable = false
            app.workspace.wake()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != available) return
            // Tailscale's VPN stays the default network, so a radio cut, a reconnect and every
            // bandwidth update all land here rather than in onLost/onAvailable. Check on a real
            // reconnect or to retry a pending check; the radio state itself is never touched here.
            val wasUsable = usable
            usable = dev.r1ptt.power.RadioPolicy.usable(caps)
            if (usable && (!wasUsable || app.workspace.recoveryWanted)) app.workspace.recoverNow()
        }
        override fun onLost(network: Network) {
            if (network == available) {
                available = null
                // Pocket mode deliberately cuts Wi-Fi. Fence unless still fresh, but never acquire a
                // radio hold that would undo that cut; wake/onAvailable will verify.
                app.workspace.fenceUnlessFresh()
            }
        }
    }
    fun start() {
        if (started) return
        started = true
        app.registerReceiver(events, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_NOT_EXPORTED)
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        if (app.workspace.identity.paired) { app.workspace.requireRecovery(); app.workspace.start() }
    }
}

/** Starts a bounded recovery check, never a microphone service, after boot or app replacement. */
class HubRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as App
        if (!app.workspace.identity.paired) return
        app.workspace.requireRecovery()
        app.workspace.management.scheduleImmediateRecovery()
    }
}
