package dev.r1ptt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.r1ptt.data.EventLog
import dev.r1ptt.input.Gestures
import dev.r1ptt.input.PressRouter
import dev.r1ptt.input.SideButton
import dev.r1ptt.power.DeviceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Always-on foreground service (type microphone). It owns the side-button reader and keeps the
 * process alive, and because Android 14 only lets a microphone service start while the app is in
 * the foreground, the launcher starts it. Idle, it costs nothing: the button reader is blocked in
 * a read and nothing polls.
 */
/** What the button may do right now. Hermes can send texts, so nothing runs past Android's lock. */
internal enum class PressGate { LOCKED, RECOVERY, OPEN }
internal fun pressGate(app: App): PressGate = when {
    app.screen.deviceLocked() -> PressGate.LOCKED
    app.workspace.blocked -> PressGate.RECOVERY
    else -> PressGate.OPEN
}

class PttService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var gestures: Gestures
    private lateinit var button: SideButton
    private var receiverRegistered = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** On external power the radios stay up, so texts and Hermes reach the R1; this enables nothing else. */
    private val powerEvents = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val connected = i.action == Intent.ACTION_POWER_CONNECTED
            EventLog.record(if (connected) "power connected" else "power disconnected")
            (application as App).radio.hold("power", connected)
        }
    }

    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val app = application as App
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    EventLog.record("screen off")
                    app.screen.onScreenOff()
                    app.radio.onScreenOff()
                    app.battery.record(c, "screen_off")
                }
                Intent.ACTION_SCREEN_ON -> {
                    EventLog.record("screen on")
                    app.screen.onScreenOn()
                    app.radio.onScreenOn()
                    app.battery.record(c, "screen_on")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!startInForeground()) {
            stopSelf()
            return
        }
        instance = this
        val app = application as App
        gestures = Gestures(
            clock = SystemClock::uptimeMillis,
            schedule = { delay, action ->
                val r = Runnable(action)
                main.postDelayed(r, delay)
                Gestures.Cancellable { main.removeCallbacks(r) }
            },
            listener = app.turns,
        )
        button = SideButton(deviceName = { app.store.value.buttonDevice }) { down ->
            if (instance === this) routeButton(down)
        }
        button.start()
        registerReceiver(
            screenEvents,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            RECEIVER_NOT_EXPORTED,
        )
        registerReceiver(powerEvents, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        app.radio.hold("power", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
        scope.launch { app.store.flow.map { it.hermes }.distinctUntilChanged().collect { app.deviceApi.apply(it) } }
        scope.launch { app.store.flow.map { it.quiet }.distinctUntilChanged().collect { if (it) app.turns.stopSpeech() } }
        app.workspace.start()
        app.hermesTexts.resume()
        app.radio.applyBaseline()
        DeviceSettings.apply(app.store.value)
        app.battery.record(this, "service_start")
        TurnMetrics.event("service_started")
    }

    /** Button edges from the launcher's own key handling; used only while the root reader is down. */
    fun frameworkButton(down: Boolean) {
        if (button.alive) return
        routeButton(down)
    }

    private val pressRouter = PressRouter {
        val management = (application as App).workspace.management
        if (management.ringing) {
            { down -> if (down) management.stopRinging() }
        } else dev.r1ptt.workspace.WorkspaceActivity.button
            ?: { down -> if (down) gestures.down() else gestures.up() }
    }
    fun cancelPressForRecovery() {
        pressRouter.cancel()
        if (::gestures.isInitialized) gestures.cancel()
    }
    private fun routeButton(down: Boolean) {
        val app = application as App
        when (pressGate(app)) {
            PressGate.LOCKED -> {
                // Android's lock screen is up: the press only wakes it. A ringing R1 can still be silenced.
                cancelPressForRecovery()
                if (down) { app.workspace.management.stopRinging(); EventLog.record("button ignored: Android is locked") }
            }
            PressGate.RECOVERY -> {
                cancelPressForRecovery()
                if (down) { app.workspace.recoverNow(); app.workspace.management.stopRinging() }
            }
            PressGate.OPEN -> pressRouter.edge(down)
        }
    }

    // Not sticky: Android 14 won't let a microphone service restart from the background. If the
    // process ever dies, the launcher restarts the service the next time it comes to the front.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground().
        if (instance === this) startInForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** `adb shell dumpsys activity service dev.r1ptt/.PttService` prints the on-device event record. */
    override fun dump(fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        val app = application as App
        writer.println("robotOS ${app.updates.installedVersion()} event log, oldest first. ${app.battery.summary()}")
        EventLog.lines().forEach(writer::println)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        val owned = instance === this
        if (owned) instance = null
        if (::button.isInitialized) button.stop()
        scope.cancel()
        if (receiverRegistered) { unregisterReceiver(screenEvents); unregisterReceiver(powerEvents) }
        if (owned) (application as App).deviceApi.stop()
        if (owned) {
            (application as App).turns.shutdown()
            TurnMetrics.event("service_stopped")
        }
        super.onDestroy()
    }

    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Push-to-talk", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Push-to-talk ready")
            .setContentText("Hold the side button to talk")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        return try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            true
        } catch (e: Exception) {
            // No mic permission yet, or started from the background: the launcher retries.
            Log.w("r1ptt", "could not start the push-to-talk service", e)
            false
        }
    }

    companion object {
        private const val CHANNEL = "ptt"

        @Volatile var instance: PttService? = null
            private set

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, PttService::class.java)) }
                .onFailure { Log.w("r1ptt", "startForegroundService failed", it) }
        }
    }
}
