package dev.r1ptt.power

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.os.PowerManager
import android.provider.Settings
import dev.r1ptt.data.ConfigStore
import dev.r1ptt.data.EventLog
import dev.r1ptt.sys.Root
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Radio housekeeping: the biggest lever on idle drain.
 *
 * - The cellular modem stays off (airplane mode) unless the config asks for it. Airplane mode is
 *   set to leave Wi-Fi alone, so Wi-Fi is ours to manage.
 * - After `power.wifiIdleMinutes` with the screen off, Wi-Fi goes off too. A button press or
 *   screen-on brings it back; reconnecting overlaps the user speaking, so it rarely adds delay.
 *
 * Switching radios needs root; every command goes through [Root] with a timeout, off the main thread.
 */
class RadioPolicy(
    private val ctx: Context,
    private val store: ConfigStore,
    private val root: (String, Long) -> Int = Root::run,
    private val io: Executor = Executors.newSingleThreadExecutor(),
) {
    private val prefs = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val alarms = ctx.getSystemService(AlarmManager::class.java)
    /** Reasons to keep the radios up (external power); the idle cut waits for none. */
    private val holds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val held: Boolean get() = holds.isNotEmpty() || store.value.power.reachableOnBattery
    val deliberateAirplane: Boolean get() = store.value.power.cellular && (prefs.getBoolean("manual_airplane", false) || !cutByUs) &&
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    /** One power exception shared with the existing policy; never a competing radio loop. */
    fun hold(reason: String, active: Boolean) {
        val changed = if (active) holds.add(reason) else holds.remove(reason)
        if (active) wake()
        else if (changed && !held && !ctx.getSystemService(PowerManager::class.java).isInteractive) schedule()
    }


    /** Whether Wi-Fi is off because we turned it off (so we know to turn it back on). */
    private var cutByUs: Boolean
        get() = prefs.getBoolean("cut", false)
        set(v) = prefs.edit().putBoolean("cut", v).apply()

    /** The steady state for the current config: run at service start and after config changes. */
    fun applyBaseline() = io.execute {
        // A newly selected reachability policy must immediately replace the pending idle alarm.
        if (held || store.value.power.wifiIdleMinutes <= 0) wake()
        else if (!ctx.getSystemService(PowerManager::class.java).isInteractive) schedule()
        val cellular = store.value.power.cellular
        val cmds = mutableListOf(
            "settings put global airplane_mode_radios cell,bluetooth,nfc,wimax,uwb",
        )
        val wasCellular = prefs.getBoolean("cellular", cellular)
        if (!cellular) cmds += AIRPLANE_ON
        else if (!deliberateAirplane || !wasCellular) cmds += AIRPLANE_OFF
        prefs.edit().putBoolean("cellular", cellular).apply()
        if (!cutByUs) cmds += WIFI_ON
        if (root(cmds.joinToString("; "), 10_000) != 0) EventLog.record("radio baseline incomplete (no root?)")
    }

    fun onScreenOff() = schedule()

    fun onScreenOn() = wake()

    /** A button press: the radios must be on by the time the user lets go. */
    fun onActivity() {
        wake()
        if (!ctx.getSystemService(PowerManager::class.java).isInteractive && !held) schedule()
    }

    /** The idle alarm fired. Called off the main thread. */
    fun onIdleAlarm(screenOn: Boolean, busy: Boolean) {
        if (screenOn || held || store.value.power.wifiIdleMinutes <= 0) return
        if (busy) { schedule(); return }
        prefs.edit().putBoolean("manual_airplane", deliberateAirplane).apply()
        val cmd = if (store.value.power.cellular) "$WIFI_OFF; $AIRPLANE_ON" else WIFI_OFF
        val previous = cutByUs
        cutByUs = true // Mark our change before Android emits AIRPLANE_MODE.
        if (root(cmd, 10_000) == 0) {
            EventLog.record("idle: radios off")
        } else {
            cutByUs = previous
            EventLog.record("idle: radio cut failed")
        }
        if (held) wake() // A cable event may have raced the root call.
    }

    fun isOnline(): Boolean {
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.let(::usable) == true
    }

    /** Waits up to [timeoutMs] for a network that can actually carry traffic. */
    suspend fun awaitOnline(timeoutMs: Long): Boolean {
        if (isOnline()) return true
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val resumed = AtomicBoolean(false)
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            if (usable(caps) && resumed.compareAndSet(false, true)) cont.resume(true)
                        }
                    }
                    callback = cb
                    cm.registerDefaultNetworkCallback(cb)
                }
            } ?: false
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    private fun wake() {
        cancel()
        if (!cutByUs) return
        io.execute {
            // A wake's button press, screen-on and recovery check all queue here before the first
            // root call clears the flag; the later ones must not toggle airplane mode again.
            if (!cutByUs) return@execute
            val cmd = if (store.value.power.cellular && !deliberateAirplane) "$AIRPLANE_OFF; $WIFI_ON" else WIFI_ON
            if (root(cmd, 10_000) == 0) {
                cutByUs = false
                EventLog.record("wake: radios on")
            } else EventLog.record("wake: radios on failed")
        }
    }

    private fun schedule() {
        if (held) { cancel(); return }
        val minutes = store.value.power.wifiIdleMinutes
        if (minutes <= 0) return
        val at = SystemClock.elapsedRealtime() + minutes * 60_000L
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending())
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending())
        }
    }

    private fun cancel() = alarms.cancel(pending())

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, RadioAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private val PHYSICAL = intArrayOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_USB, NetworkCapabilities.TRANSPORT_BLUETOOTH)

        /**
         * Tailscale's VPN stays the default network, still claiming internet, while the radios are
         * cut or mobile data is down. It is only usable when it rides on a real transport.
         */
        fun usable(caps: NetworkCapabilities): Boolean =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || PHYSICAL.any(caps::hasTransport))

        private const val WIFI_ON = "cmd wifi set-wifi-enabled enabled"
        private const val WIFI_OFF = "cmd wifi set-wifi-enabled disabled"

        // `cmd connectivity airplane-mode` first; the settings + broadcast pair for builds without it.
        private const val AIRPLANE_ON = "(cmd connectivity airplane-mode enable || (settings put global airplane_mode_on 1; " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true))"
        private const val AIRPLANE_OFF = "(cmd connectivity airplane-mode disable || (settings put global airplane_mode_on 0; " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false))"
    }
}
