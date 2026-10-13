package dev.r1ptt.power

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import dev.r1ptt.App
import dev.r1ptt.sys.AdminReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class ScreenPolicyTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    private fun admin() = shadowOf(app.getSystemService(DevicePolicyManager::class.java))
        .setActiveAdmin(ComponentName(app, AdminReceiver::class.java))

    /** lockNow() would demand the PIN on every tap; the sleep key keeps Android's lock-after delay. */
    @Test fun tapToSleepNeverHardLocksWhenRootWorks() {
        admin()
        var locks = 0
        val screen = ScreenPolicy(app, sleepKey = { slept -> slept(true) }).apply { lockNow = { locks++ } }
        screen.sleepNow()
        assertEquals(0, locks)
    }

    @Test fun withoutRootDeviceAdminStillTurnsTheScreenOff() {
        var locks = 0
        val screen = ScreenPolicy(app, sleepKey = { slept -> slept(false) }).apply { lockNow = { locks++ } }
        screen.sleepNow()
        assertEquals(0, locks) // No admin: nothing else to try.
        admin()
        screen.sleepNow()
        assertEquals(1, locks)
    }
}
