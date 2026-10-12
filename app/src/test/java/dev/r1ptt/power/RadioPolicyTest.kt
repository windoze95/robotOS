package dev.r1ptt.power

import dev.r1ptt.App
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class RadioPolicyTest {
    private val app get() = RuntimeEnvironment.getApplication() as App

    /** One wake's button press, screen-on and recovery check all ask before the first root call returns. */
    @Test fun oneWakeSwitchesTheRadiosOnOnce() {
        val commands = mutableListOf<String>()
        val queued = ArrayDeque<Runnable>()
        val radio = RadioPolicy(app, app.store, root = { cmd, _ -> commands += cmd; 0 }, io = Executor { queued.addLast(it) })
        app.getSharedPreferences("radio", 0).edit().putBoolean("cut", true).commit()
        radio.onActivity()
        radio.onScreenOn()
        radio.hold("workspace-wake-recovery", true)
        assertEquals(3, queued.size)
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals(1, commands.size)
        assertFalse(app.getSharedPreferences("radio", 0).getBoolean("cut", true))
        assertEquals(1, dev.r1ptt.data.EventLog.lines().count { it.endsWith(" wake: radios on") })
    }

    @Test fun aFailedWakeIsRetriedByTheNextTrigger() {
        var attempts = 0
        val queued = ArrayDeque<Runnable>()
        val radio = RadioPolicy(app, app.store, root = { _, _ -> if (++attempts == 1) -1 else 0 }, io = Executor { queued.addLast(it) })
        app.getSharedPreferences("radio", 0).edit().putBoolean("cut", true).commit()
        radio.onActivity()
        radio.onScreenOn()
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals(2, attempts)
        assertFalse(app.getSharedPreferences("radio", 0).getBoolean("cut", true))
    }
}
