package dev.r1ptt.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.BatteryManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.ChatsActivity
import dev.r1ptt.HomeActivity
import dev.r1ptt.Phase
import dev.r1ptt.QuickControlsActivity
import dev.r1ptt.R
import dev.r1ptt.SettingsActivity
import dev.r1ptt.TurnController
import dev.r1ptt.TurnState
import dev.r1ptt.data.Config as DeviceConfig
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.ConfigJson
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.data.Msg
import dev.r1ptt.hermes.HermesActivity
import dev.r1ptt.hermes.HermesApproval
import dev.r1ptt.hermes.HermesRun
import dev.r1ptt.hermes.HermesRunState
import dev.r1ptt.messages.MessagesActivity
import dev.r1ptt.update.UpdateActivity
import java.io.File
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Renders robotOS's screens at the R1's real panel (480×640 px at the system's 190 dpi) into
 * build/ui-snapshots/ for review, and checks the physical sizing promises the design makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, qualifiers = "w404dp-h539dp-190dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensSnapshotTest {
    private lateinit var app: App
    private val out = File("build/ui-snapshots").apply { mkdirs() }

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        val memory = object : ConfigBlobStorage {
            var blob: String? = null
            override fun read() = blob
            override fun write(blob: String) { this.blob = blob }
        }
        val plain = object : ConfigCipher {
            override fun seal(plain: String, allowKeyCreation: Boolean) = plain
            override fun open(blob: String) = blob
        }
        val codec = object : ConfigCodec<DeviceConfig> {
            override fun encode(value: DeviceConfig) = ConfigJson.toJson(value).toString()
            override fun decode(plain: String) = ConfigJson.merge(DeviceConfig(), JSONObject(plain))
        }
        app.store.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.store, EncryptedConfigPersistence(app.store.value, memory, plain, codec))
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 82).putExtra(BatteryManager.EXTRA_SCALE, 100))
    }

    @After fun cleanup() {
        turn(TurnState())
        app.turns.shutdown()
    }

    private fun configured() = app.store.update {
        it.copy(agent = it.agent.copy(baseUrl = "https://hermes.example.ts.net/v1", apiKey = "test-key"), openaiKey = "sk-test")
    }

    private fun conversation() {
        app.history.add(Msg.USER, "What's on my calendar today?")
        app.history.add(Msg.ASSISTANT, "Your next meeting is at 10 with Sam about the launch. You have a clear hour before it, so I moved the gym to 6.")
        app.history.add(Msg.USER, "Text Sam that I'm running five minutes late.")
        app.history.add(Msg.ASSISTANT, "Done. I texted Sam: \"Running about five minutes late, see you soon.\"")
    }

    @Suppress("UNCHECKED_CAST")
    private fun turn(state: TurnState) {
        val field = TurnController::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (field.get(app.turns) as MutableStateFlow<TurnState>).value = state
        settle()
    }

    private fun settle(ms: Long = 120) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun online(activity: HomeActivity) {
        HomeActivity::class.java.getDeclaredField("networkAvailable").apply { isAccessible = true }.setBoolean(activity, true)
        HomeActivity::class.java.getDeclaredMethod("renderConnectivity").apply { isAccessible = true }.invoke(activity)
        settle()
    }

    private fun <T : Activity> open(type: Class<T>, intent: Intent? = null): ActivityController<T> =
        (if (intent == null) Robolectric.buildActivity(type) else Robolectric.buildActivity(type, intent)).setup().also { settle() }

    private fun snap(activity: Activity, name: String): Bitmap {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 480, 640)
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        content.draw(canvas)
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun snapDialog(dialog: AlertDialog, name: String) {
        val decor = dialog.window!!.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, 480, decor.measuredHeight)
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(14, 14, 14))
        canvas.translate(0f, (640 - decor.measuredHeight) / 2f)
        decor.draw(canvas)
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    @Test fun theAppLaysItselfOutForTheR1PanelWithoutChangingAndroid() {
        val r1 = Configuration().apply { densityDpi = 190; smallestScreenWidthDp = 404; screenWidthDp = 404; screenHeightDp = 539 }
        val scaled = DeviceScale.override(r1)!!
        assertEquals(255, scaled.densityDpi)
        assertEquals(301, scaled.screenWidthDp)
        // A screen that is already narrow is never shrunk, and nothing is scaled by more than half again.
        assertNull(DeviceScale.override(Configuration().apply { densityDpi = 320; smallestScreenWidthDp = 300 }))
        assertEquals(630, DeviceScale.override(Configuration().apply { densityDpi = 420; smallestScreenWidthDp = 700 })!!.densityDpi)
        val home = open(HomeActivity::class.java)
        assertEquals(255, home.get().resources.configuration.densityDpi)
        assertEquals(190, app.resources.configuration.densityDpi)
        home.pause().stop().destroy()
    }

    @Test fun homeWalksThroughATurn() {
        configured()
        val controller = open(HomeActivity::class.java)
        val home = controller.get()
        online(home)
        snap(home, "home-01-ready")
        turn(TurnState(phase = Phase.LISTENING, level = .18f, heard = "What's on my calendar today and can you move the gym"))
        repeat(24) { turn(TurnState(phase = Phase.LISTENING, level = (it % 7) * .05f + .02f, heard = "What's on my calendar today and can you move the gym")) }
        assertTrue(home.findViewById<VoiceStage>(R.id.stage).showing)
        snap(home, "home-02-listening")
        app.history.add(Msg.USER, "What's on my calendar today and can you move the gym?")
        turn(TurnState(phase = Phase.THINKING, note = "Checking your calendar"))
        assertFalse(home.findViewById<VoiceStage>(R.id.stage).showing)
        snap(home, "home-03-thinking")
        turn(TurnState(phase = Phase.ANSWERING, reply = "Your next meeting is at 10 with Sam. I moved the gym to 6"))
        snap(home, "home-04-answering")
        app.history.add(Msg.ASSISTANT, "Your next meeting is at 10 with Sam. I moved the gym to 6 so you keep a clear hour before it.")
        turn(TurnState(phase = Phase.SPEAKING))
        val action = home.findViewById<TextView>(R.id.send)
        assertTrue(action.text.isNotEmpty()) // a labelled stop control while a turn can be stopped
        snap(home, "home-05-speaking")
        turn(TurnState(phase = Phase.ERROR, note = "Hermes didn't answer. Check the server in Settings."))
        snap(home, "home-06-error")
        controller.pause().stop().destroy()
    }

    @Test fun homeConversationMenuAndCards() {
        configured()
        conversation()
        val controller = open(HomeActivity::class.java)
        val home = controller.get()
        online(home)
        snap(home, "home-07-conversation")
        app.notices.add("Your package was delivered to the front porch.")
        settle()
        snap(home, "home-08-note")
        home.findViewById<View>(R.id.menu).performClick()
        settle(400)
        val tiles = all(home.findViewById(R.id.menu_sheet)).filter { it.contentDescription in setOf("New chat", "Chats", "Read reply", "Messages", "Hermes", "Settings") }
        assertEquals(6, tiles.size)
        val minimum = 48 * home.resources.displayMetrics.density
        tiles.forEach { assertTrue("${it.contentDescription} height", it.height >= minimum) }
        snap(home, "home-09-menu")
        controller.pause().stop().destroy()
    }

    @Test fun homeFirstRunAndDictation() {
        val controller = open(HomeActivity::class.java)
        val home = controller.get()
        snap(home, "home-10-first-run")
        configured()
        online(home)
        turn(TurnState(phase = Phase.DICTATING, level = .2f))
        snap(home, "home-11-dictating")
        controller.pause().stop().destroy()
    }

    @Test fun screensAndDialogs() {
        configured()
        conversation()
        open(QuickControlsActivity::class.java).also { snap(it.get(), "quick-controls"); it.pause().stop().destroy() }
        open(SettingsActivity::class.java).also { c ->
            snap(c.get(), "settings")
            all(c.get().window.decorView).first { it.contentDescription?.toString()?.startsWith("Display & controls.") == true }.performClick()
            settle()
            snap(c.get(), "settings-display")
            all(c.get().window.decorView).first { it.contentDescription?.toString()?.startsWith("Text size.") == true }.performClick()
            settle()
            snapDialog(ShadowAlertDialog.getLatestAlertDialog(), "dialog-choice")
            ShadowAlertDialog.getLatestAlertDialog().dismiss()
            c.get().onBackPressed()
            settle()
            all(c.get().window.decorView).first { it.contentDescription?.toString()?.startsWith("Connectivity & power.") == true }.performClick()
            settle()
            snap(c.get(), "settings-power")
            c.pause().stop().destroy()
        }
        open(ChatsActivity::class.java).also { snap(it.get(), "chats"); it.pause().stop().destroy() }
        open(HermesActivity::class.java).also { snap(it.get(), "hermes"); it.pause().stop().destroy() }
        open(UpdateActivity::class.java).also { snap(it.get(), "updates"); it.pause().stop().destroy() }
        open(MessagesActivity::class.java).also { c ->
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            snap(c.get(), "messages")
            c.pause().stop().destroy()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun task(state: HermesRunState) {
        val field = app.hermesRuns.javaClass.getDeclaredField("mutable").apply { isAccessible = true }
        (field.get(app.hermesRuns) as MutableStateFlow<HermesRunState>).value = state
        settle()
    }

    @Test fun approvalIsUnmissableAndReviewedByTouch() {
        configured()
        conversation()
        task(HermesRunState(HermesRun("op-1", "connection", "r1ptt-${app.history.convId}", "{}", System.currentTimeMillis(), true, 600,
            canStop = true, canApprove = true, runId = "run-1", status = "waiting_for_approval",
            approval = HermesApproval("request-1", "Send a text to Sam: Running about five minutes late.", "send_sms +15551234567", setOf("once", "deny")))))
        val home = open(HomeActivity::class.java)
        online(home.get())
        assertEquals(View.VISIBLE, home.get().findViewById<View>(R.id.hermes_task).visibility)
        snap(home.get(), "home-12-approval")
        home.pause().stop().destroy()
        val hermes = open(HermesActivity::class.java)
        snap(hermes.get(), "hermes-approval")
        all(hermes.get().window.decorView).filterIsInstance<Button>().first { it.text == "Review request" }.performClick()
        settle()
        snapDialog(ShadowAlertDialog.getLatestAlertDialog(), "dialog-approval")
        ShadowAlertDialog.getLatestAlertDialog().dismiss()
        hermes.pause().stop().destroy()
        task(HermesRunState())
    }

    @Test fun keyboardAndLargeText() {
        configured()
        conversation()
        app.store.update { it.copy(textSizeSp = 23) }
        val controller = open(HomeActivity::class.java)
        val home = controller.get()
        online(home)
        snap(home, "home-13-large-text")
        HomeActivity::class.java.getDeclaredField("imeVisible").apply { isAccessible = true }.setBoolean(home, true)
        HomeActivity::class.java.getDeclaredMethod("render", TurnState::class.java).apply { isAccessible = true }.invoke(home, TurnState())
        settle()
        snap(home, "home-14-keyboard-open")
        controller.pause().stop().destroy()
        open(SettingsActivity::class.java).also { snap(it.get(), "settings-large-text"); it.pause().stop().destroy() }
        open(ChatsActivity::class.java).also { c ->
            all(c.get().window.decorView).first { it.contentDescription?.toString()?.startsWith("Options for") == true }.performClick()
            settle()
            ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 1, 1)
            settle()
            snapDialog(ShadowAlertDialog.getLatestAlertDialog(), "dialog-confirm")
            ShadowAlertDialog.getLatestAlertDialog().dismiss()
            c.pause().stop().destroy()
        }
    }

    @Test fun controlsMeetTheR1TouchTarget() {
        configured()
        val minimum = 48 * 255 / 160f // 48 dp at robotOS's scale: about 7 mm on the R1's panel
        listOf(QuickControlsActivity::class.java, SettingsActivity::class.java, HermesActivity::class.java).forEach { type ->
            val c = open(type)
            val content = c.get().findViewById<ViewGroup>(android.R.id.content)
            content.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 480, 640)
            all(content).filter { it.isClickable && it.visibility == View.VISIBLE && it.width > 0 && (it is Button || it is CompoundButton || it.contentDescription != null) }
                .forEach { assertTrue("${type.simpleName} ${it.contentDescription ?: (it as? TextView)?.text} is ${it.height}px", it.height >= minimum - 1) }
            c.pause().stop().destroy()
        }
    }
}
