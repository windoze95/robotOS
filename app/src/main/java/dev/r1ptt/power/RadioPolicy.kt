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
import android.util.Log
import dev.r1ptt.data.ConfigStore
import dev.r1ptt.sys.Root
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
class RadioPolicy(private val ctx: Context, private val store: ConfigStore) {
    private val prefs = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val alarms = ctx.getSystemService(AlarmManager::class.java)
    private val io = Executors.newSingleThreadExecutor()
    /** Reasons to keep the radios up (external power); the idle cut waits for none. */
    private val holds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val held: Boolean get() = holds.isNotEmpty() || store.value.power.stayReachable
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
        if (Root.run(cmds.joinToString("; "), 10_000) != 0) Log.w(TAG, "radio baseline incomplete (no root?)")
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
        if (Root.run(cmd, 10_000) == 0) {
            Log.i(TAG, "idle: radios off")
        } else cutByUs = previous
        if (held) wake() // A cable event may have raced the root call.
    }

    fun isOnline(): Boolean {
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** Waits up to [timeoutMs] for a network that claims internet access. */
    suspend fun awaitOnline(timeoutMs: Long): Boolean {
        if (isOnline()) return true
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val resumed = AtomicBoolean(false)
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                resumed.compareAndSet(false, true)
                            ) cont.resume(true)
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
            val cmd = if (store.value.power.cellular && !deliberateAirplane) "$AIRPLANE_OFF; $WIFI_ON" else WIFI_ON
            if (Root.run(cmd, 10_000) == 0) cutByUs = false
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

    private companion object {
        const val TAG = "r1ptt"
        const val WIFI_ON = "cmd wifi set-wifi-enabled enabled"
        const val WIFI_OFF = "cmd wifi set-wifi-enabled disabled"

        // `cmd connectivity airplane-mode` first; the settings + broadcast pair for builds without it.
        const val AIRPLANE_ON = "(cmd connectivity airplane-mode enable || (settings put global airplane_mode_on 1; " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true))"
        const val AIRPLANE_OFF = "(cmd connectivity airplane-mode disable || (settings put global airplane_mode_on 0; " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false))"
    }
}
