package dev.r1ptt.messages

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.HomeActivity
import dev.r1ptt.TurnController
import dev.r1ptt.data.Config as AppConfig
import dev.r1ptt.data.Msg
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, qualifiers = "w320dp-h426dp-mdpi")
class MessagesActivityTest {
    private lateinit var app: App
    private lateinit var turns: TurnController
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        app.deleteDatabase("messages.db")
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
        assertTrue(app.radio.isOnline())
        @Suppress("UNCHECKED_CAST")
        val state = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store) as MutableStateFlow<AppConfig>
        state.value = state.value.copy(tts = state.value.tts.copy(enabled = false))
        turns = app.turns
    }
    @After fun cleanup() { turns.shutdown() }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("UI condition was not reached", condition())
    }

    @Test fun openingMessagesDoesNotRequestPermissionsAndButtonCannotStartAiDictation() {
        val c = Robolectric.buildActivity(MessagesActivity::class.java).setup()
        val a = c.get()
        await { views(a.window.decorView).filterIsInstance<Button>().any { it.text == "New text" && it.isEnabled } }
        assertNull(shadowOf(a).lastRequestedPermission)
        assertFalse(a.canDictate())
        app.history.add(Msg.USER, "Existing AI conversation")
        val before = app.history.messages
        app.turns.onPress(0); app.turns.onHoldStart(); app.turns.onHoldEnd()
        app.turns.onDoubleTap()
        assertEquals(before, app.history.messages)
        assertFalse(app.turns.busy)
        c.pause()
        assertNull(app.turns.target)
        // First wake press can precede Activity.onResume. It must not open a chat microphone.
        app.turns.onPress(1000); app.turns.onHoldStart(); app.turns.onHoldEnd(); app.turns.onDoubleTap()
        assertFalse(app.turns.busy)
        assertEquals(before, app.history.messages)
        c.stop().destroy()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun composeFitsSmallScreenAndProducesSyntheticPreview() {
        val intent = Intent(app, MessagesActivity::class.java).putExtra(SmsExternalDraft.PEER, "+15551234567")
            .putExtra(SmsExternalDraft.BODY, "I’m on my way. See you soon!")
        val c = Robolectric.buildActivity(MessagesActivity::class.java, intent).setup()
        val a = c.get()
        await { views(a.window.decorView).filterIsInstance<EditText>().any { it.contentDescription == "SMS draft" } }
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(426, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 320, 426)
        val review = views(content).filterIsInstance<Button>().single { it.text == "Review text" }
        assertTrue(review.bottom <= 426)
        assertTrue(review.width >= 250)
        val bitmap = Bitmap.createBitmap(320, 426, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        val preview = File("build/messages-ui/compose.png").apply { requireNotNull(parentFile).mkdirs() }
        preview.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        c.pause().stop().destroy()
    }
}
