package dev.r1ptt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.r1ptt.data.Msg
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, qualifiers = "w404dp-h539dp-mdpi")
class HomeActivityTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    private fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    @Test fun unsentEditsSurviveNavigationAndLateAdmissionDoesNotEraseThem() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val input = controller.get().findViewById<EditText>(R.id.input)
        input.setText("Original draft")
        settle()
        assertEquals("Original draft", app.history.draft)
        input.setText("Edited draft")
        app.history.confirmDraft("Original draft")
        settle()
        assertEquals("Edited draft", input.text.toString())
        controller.pause().stop().destroy()
        val restored = Robolectric.buildActivity(HomeActivity::class.java).setup()
        settle()
        assertEquals("Edited draft", restored.get().findViewById<EditText>(R.id.input).text.toString())
        restored.pause().stop().destroy()
    }

    @Test fun transcriptRemainsBoundedWhenOldMessagesAreTrimmed() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        repeat(100) { app.history.add(Msg.USER, "Message $it") }
        settle()
        repeat(10) { app.history.add(Msg.ASSISTANT, "Reply $it"); settle() }
        val rows = controller.get().findViewById<LinearLayout>(R.id.list)
        assertEquals(100, rows.childCount)
        assertEquals("Message 10", (rows.getChildAt(0).tag as Msg).text)
        controller.pause().stop().destroy()
    }

    @Test fun pausedReadingPositionIsPublishedWithoutScrollEventRebuilds() {
        app.history.savePosition(145, false)
        app.history.flush()
        assertEquals(145, app.history.flow.value.scrollY)
        assertFalse(app.history.flow.value.followLatest)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun drawsNativeHomeAtDeviceProportions() {
        app.history.add(Msg.USER, "What is on my calendar today?")
        app.history.add(Msg.ASSISTANT, "Your next meeting is at 10. You have a clear hour before it.")
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        settle()
        val root = controller.get().findViewById<View>(R.id.root)
        root.measure(View.MeasureSpec.makeMeasureSpec(404, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(539, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 404, 539)
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(480f / 404f, 640f / 539f)
        root.draw(canvas)
        File("build/device-ui/home.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(controller.get().findViewById<ScrollView>(R.id.scroll).height > 200)
        controller.pause().stop().destroy()
    }
}
