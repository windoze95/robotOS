package dev.r1ptt.sys

import android.app.KeyguardManager
import dev.r1ptt.App
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class DeviceLockTest {
    private val app get() = RuntimeEnvironment.getApplication() as App

    /** Many R1 builds have no device admin; root's lockdown locks at once and requires the screen lock. */
    @Test fun withoutDeviceAdminRootLocksThroughLockdown() {
        val commands = mutableListOf<String>()
        val lock = DeviceLock(app, rootAvailable = { true }, root = { cmd, _ -> commands += cmd; 0 })
        assertFalse(lock.available()) // No Android screen lock: locking would protect nothing.
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        assertTrue(lock.available())
        assertTrue(lock.usesRoot())
        assertTrue(lock.lockNow())
        assertTrue(commands.single().contains("require-strong-auth STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN"))
        assertTrue(commands.single().contains("input keyevent 223"))
    }

    @Test fun withNeitherAdminNorRootThereIsNoRemoteLock() {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        val lock = DeviceLock(app, rootAvailable = { false }, root = { _, _ -> -1 })
        assertFalse(lock.available())
        assertFalse(lock.lockNow())
    }
}
