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
                Intent.ACTION_SCREEN_OFF -> app.workspace.requireRecovery(checkNow = false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> app.workspace.requireRecovery()
            }
        }
    }
    private val network = object : ConnectivityManager.NetworkCallback() {
        private var available: Network? = null
        override fun onAvailable(network: Network) {
            available = network
            app.workspace.requireRecovery()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == available && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                app.workspace.recoverNow()
        }
        override fun onLost(network: Network) {
            if (network == available) {
                available = null
                // Pocket mode deliberately cuts Wi-Fi. Fence now, but do not acquire a radio
                // hold that would undo that cut; wake/onAvailable will verify immediately.
                app.workspace.requireRecovery(checkNow = false)
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
