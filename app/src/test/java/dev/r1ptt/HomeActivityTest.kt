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

    @Test fun passiveNotesStaySeparateFromTheConversationAndCanBeDismissed() {
        app.history.saveDraft("Keep this draft")
        val conversation = app.history.convId
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        settle()
        val notice = app.notices.add("The download finished.")
        settle()
        val banner = controller.get().findViewById<android.widget.TextView>(R.id.hermes_notice)
        assertEquals(View.VISIBLE, banner.visibility)
        assertTrue(banner.text.contains(notice.text))
        assertEquals(conversation, app.history.convId)
        assertEquals("Keep this draft", app.history.draft)
        assertTrue(app.history.messages.isEmpty())
        banner.performClick()
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        settle()
        assertEquals(View.GONE, banner.visibility)
        assertTrue(app.notices.state.value.isEmpty())
        controller.pause().stop().destroy()
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
    @Test fun pairedHomeHidesTheOldDirectTaskWithoutErasingItsRecord() {
        val old = dev.r1ptt.hermes.HermesRun("old-operation", "direct-connection", "old-session", "{}", 1,
            true, 60, true, true, runId = "old-run", status = "running")
        val ledger = dev.r1ptt.hermes.HermesRunLedger(File(app.filesDir, "hermes-run.json"))
        ledger.write(old)
        @Suppress("UNCHECKED_CAST")
        val state = app.hermesRuns.javaClass.getDeclaredField("mutable").apply { isAccessible = true }.get(app.hermesRuns)
            as kotlinx.coroutines.flow.MutableStateFlow<dev.r1ptt.hermes.HermesRunState>
        state.value = dev.r1ptt.hermes.HermesRunState(run = old)
        val controller = Robolectric.buildActivity(HomeActivity::class.java).create()
        val home = controller.get()
        val draw = home.javaClass.getDeclaredMethod("drawTask", dev.r1ptt.hermes.HermesRunState::class.java).apply { isAccessible = true }
        val stop = home.javaClass.getDeclaredMethod("stopLabel").apply { isAccessible = true }
        draw.invoke(home, state.value)
        assertEquals(View.VISIBLE, home.findViewById<View>(R.id.hermes_task).visibility)
        assertEquals("Stop task", stop.invoke(home))
        pairForHomeUi(); draw.invoke(home, state.value)
        assertEquals(View.GONE, home.findViewById<View>(R.id.hermes_task).visibility)
        assertEquals("Disconnect", stop.invoke(home))
        assertEquals(old, ledger.read())
        controller.destroy()
    }
    private fun pairForHomeUi() {
        val identity = org.json.JSONObject().put("device_id", "paired-r1").put("base_url", "https://hub.example")
        val disk = object : dev.r1ptt.data.ConfigBlobStorage {
            var value: String? = null
            override fun read() = value
            override fun write(blob: String) { value = blob }
        }
        val cipher = object : dev.r1ptt.data.ConfigCipher {
            override fun seal(plain: String, allowKeyCreation: Boolean) = plain
            override fun open(blob: String) = blob
        }
        val codec = object : dev.r1ptt.data.ConfigCodec<org.json.JSONObject> {
            override fun encode(value: org.json.JSONObject) = value.toString()
            override fun decode(plain: String) = org.json.JSONObject(plain)
        }
        app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.workspace.identity, dev.r1ptt.data.EncryptedConfigPersistence(identity, disk, cipher, codec))
    }
    @Suppress("UNCHECKED_CAST")
    private fun configureHome(update: (dev.r1ptt.data.Config) -> dev.r1ptt.data.Config) {
        val state = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store)
            as kotlinx.coroutines.flow.MutableStateFlow<dev.r1ptt.data.Config>
        state.value = update(state.value)
    }
    private fun renderHomePrerequisites(activity: HomeActivity, empty: Boolean) {
        // Exercise both actual Home surfaces without starting microphone or network services.
        if (empty) activity.javaClass.getDeclaredMethod("renderEmpty").apply { isAccessible = true }.invoke(activity)
        else {
            activity.findViewById<View>(R.id.empty).visibility = View.GONE
            activity.javaClass.getDeclaredMethod("renderStrip", TurnState::class.java).apply { isAccessible = true }.invoke(activity, TurnState())
        }
    }
    @Test fun pairedHomeDoesNotAskForTheDirectHermesCredentialRemovedByPairing() {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        pairForHomeUi()
        configureHome { it.copy(agent = it.agent.copy(baseUrl = "", apiKey = ""), openaiKey = "test-speech-key") }
        val controller = Robolectric.buildActivity(HomeActivity::class.java).create()
        val home = controller.get()
        renderHomePrerequisites(home, empty = true)
        assertEquals("Release to send it to Hermes.", home.findViewById<android.widget.TextView>(R.id.empty_body).text.toString())
        assertNull(home.findViewById<LinearLayout>(R.id.empty).findViewWithTag<View>("setup"))
        renderHomePrerequisites(home, empty = false)
        assertNotEquals(app.getString(R.string.need_hermes), home.findViewById<android.widget.TextView>(R.id.status).text.toString())
        assertNotEquals(app.getString(R.string.need_key), home.findViewById<android.widget.TextView>(R.id.status).text.toString())
        controller.destroy()
    }
    @Test fun unpairedHomeNeedsHermesAndPairedHomeStillNeedsItsLocalSpeechConfiguration() {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        configureHome { it.copy(agent = it.agent.copy(baseUrl = "", apiKey = ""), openaiKey = "test-speech-key") }
        val controller = Robolectric.buildActivity(HomeActivity::class.java).create()
        val home = controller.get()
        renderHomePrerequisites(home, empty = true)
        assertEquals("Connect Hermes to start.", home.findViewById<android.widget.TextView>(R.id.empty_body).text.toString())
        renderHomePrerequisites(home, empty = false)
        assertEquals(app.getString(R.string.need_hermes), home.findViewById<android.widget.TextView>(R.id.status).text.toString())
        pairForHomeUi()
        configureHome { it.copy(openaiKey = "") }
        renderHomePrerequisites(home, empty = true)
        assertEquals("Add a speech key to start.", home.findViewById<android.widget.TextView>(R.id.empty_body).text.toString())
        renderHomePrerequisites(home, empty = false)
        assertEquals(app.getString(R.string.need_key), home.findViewById<android.widget.TextView>(R.id.status).text.toString())
        configureHome { it.copy(stt = it.stt.copy(baseUrl = "https://speech.example/v1"), tts = it.tts.copy(enabled = false)) }
        renderHomePrerequisites(home, empty = true)
        assertEquals("Release to send it to Hermes.", home.findViewById<android.widget.TextView>(R.id.empty_body).text.toString())
        controller.destroy()
    }

}
