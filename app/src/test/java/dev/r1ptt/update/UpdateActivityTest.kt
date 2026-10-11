package dev.r1ptt.update

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.r1ptt.App
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, qualifiers = "w404dp-h539dp-mdpi")
class UpdateActivityTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    @After fun cleanup() { app.turns.shutdown() }

    @Test fun onlyTheCurrentUpdateActionIsShownAndOpeningDoesNotStartACheck() {
        val c = Robolectric.buildActivity(UpdateActivity::class.java).setup()
        val activity = c.get()
        assertEquals(UpdatePhase.IDLE, app.updates.state.value.phase)
        fun actions() = all(activity.window.decorView).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }
        assertTrue("Check for updates" in actions())
        assertFalse("Download update" in actions())
        assertFalse("Install update" in actions())
        @Suppress("UNCHECKED_CAST")
        val state = app.updates.javaClass.getDeclaredField("_state").apply { isAccessible = true }.get(app.updates) as MutableStateFlow<UpdateState>
        state.value = UpdateState(UpdatePhase.DOWNLOADING, "Downloading and verifying…")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Cancel update" in actions())
        assertFalse("Check for updates" in actions())
        assertFalse("Download update" in actions())
        assertFalse("Install update" in actions())
        state.value = UpdateState(UpdatePhase.READY, "Verified update ready")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Install update" in actions())
        assertFalse("Cancel update" in actions())
        c.pause().stop().destroy()
    }

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
}
