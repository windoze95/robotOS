package dev.r1ptt.ui

import android.graphics.Bitmap
import dev.r1ptt.App
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class LockScreenLookTest {
    private val app get() = RuntimeEnvironment.getApplication() as App

    /** Applied once, so a wallpaper the owner picks afterwards is never replaced. */
    @Test fun theLockScreenGetsTheCoverOnce() {
        val worn = mutableListOf<Bitmap>()
        LockScreenLook.applyOnce(app) { worn += it }
        LockScreenLook.applyOnce(app) { worn += it }
        assertEquals(1, worn.size)
        val screen = app.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        assertEquals(screen.width(), worn.single().width)
        assertEquals(screen.height(), worn.single().height)
    }
}
