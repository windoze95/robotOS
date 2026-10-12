package dev.r1ptt.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import dev.r1ptt.messages.SmsRecord
import dev.r1ptt.messages.SmsStore
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
        File(out, "$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
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

    private fun snapView(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 480, 640)
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { it.drawColor(Color.BLACK); view.draw(it) }
        File(out, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun recoveryOpensOnTheGlowingLogo() {
        // Robolectric runs an endless animation's frames back to back, which would race the clock
        // past the logo; a paused choreographer draws frames only as the test advances time.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        try {
            val data = JSONObject().put("device_id", "r1-a").put("base_url", "http://127.0.0.1:9")
                .put("device_token", "content-token").put("recovery_token", "recovery-token")
            val disk = object : ConfigBlobStorage { var value: String? = null; override fun read() = value; override fun write(blob: String) { value = blob } }
            val cipher = object : ConfigCipher { override fun seal(plain: String, allowKeyCreation: Boolean) = plain; override fun open(blob: String) = blob }
            val codec = object : ConfigCodec<JSONObject> { override fun encode(value: JSONObject) = value.toString(); override fun decode(plain: String) = JSONObject(plain) }
            app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
                .set(app.workspace.identity, EncryptedConfigPersistence(data, disk, cipher, codec))
            val controller = Robolectric.buildActivity(DeviceActivity::class.java).create().start().resume().visible()
            settle(40)
            val gate = all(controller.get().window.decorView).first { it is LogoGlow }.parent as View
            snapView(gate, "recovery-cover-start")
            // Half a breath: the glow at its brightest. A paused choreographer does not step the
            // animation itself, so place it there directly.
            val glow = all(gate).first { it is LogoGlow }
            (LogoGlow::class.java.getDeclaredField("animator").apply { isAccessible = true }.get(glow) as android.animation.ValueAnimator).currentPlayTime = 1_300
            snapView(gate, "recovery-cover-glow")
            settle(DeviceActivity.COVER_MS)
            snapView(gate, "recovery-page")
            controller.pause().stop().destroy()
        } finally { org.robolectric.shadows.ShadowChoreographer.setPaused(false) }
    }

    @Test fun workspaceAndBuilderFitTheR1Canvas() {
        configured()
        val note = app.workspace.store.capture("Release thoughts", "Check reconnect behavior before the next release.")
        val workspace = open(dev.r1ptt.workspace.WorkspaceActivity::class.java)
        try {
            snap(workspace.get(), "workspace-home")
            dev.r1ptt.workspace.WorkspaceActivity::class.java.getDeclaredMethod("notes").apply { isAccessible = true }.invoke(workspace.get())
            snap(workspace.get(), "workspace-notes")
            val draft = JSONObject().put("id", "preview-prompt").put("title", "Next release")
                .put("instruction", "Turn these observations into a test plan.").put("revision", 1).put("lease_token", "preview")
                .put("blocks", org.json.JSONArray().put(dev.r1ptt.workspace.WorkspaceClient.block(note)))
            dev.r1ptt.workspace.WorkspaceActivity::class.java.getDeclaredMethod("builder", JSONObject::class.java)
                .apply { isAccessible = true }.invoke(workspace.get(), draft)
            snap(workspace.get(), "workspace-builder")
            val controls = all(workspace.get().window.decorView)
            assertTrue(controls.filterIsInstance<android.widget.EditText>().any { it.text.toString() == "Next release" })
            controls.filterIsInstance<Button>().first { it.text == "Preview" }.performClick()
            snap(workspace.get(), "workspace-preview")
            assertTrue(all(workspace.get().window.decorView).filterIsInstance<TextView>().any { it.text.contains("Check reconnect behavior") })
            app.workspace.store.put("prompts", JSONObject(draft.toString()).put("last_run_id", "preview-reply").put("state", "ran"))
            app.workspace.store.put("runs", JSONObject().put("id", "preview-reply").put("prompt_id", "preview-prompt")
                .put("status", "completed").put("prompt_revision", 1).put("snapshot", draft)
                .put("output", "Start with reconnect, then test a saved prompt on both devices."))
            dev.r1ptt.workspace.WorkspaceActivity::class.java.getDeclaredMethod("promptDetail", String::class.java)
                .apply { isAccessible = true }.invoke(workspace.get(), "preview-prompt")
            snap(workspace.get(), "workspace-saved-prompt")
            assertTrue(all(workspace.get().window.decorView).filterIsInstance<TextView>().any { it.text == "Run in new chat" })
        } finally { workspace.pause().stop().destroy() }
        val host = open(dev.r1ptt.workspace.HubSettingsActivity::class.java)
        try { snap(host.get(), "workspace-pairing") } finally { host.pause().stop().destroy() }
    }

    @Test fun promptAttachmentFitsTheR1ComposerAndLeavesNormalTypingAfterSend() {
        configured()
        val prompt = JSONObject().put("id", "staged-builder").put("title", "Next release")
            .put("instruction", "Turn these observations into a test plan.").put("revision", 2).put("blocks", org.json.JSONArray())
        app.workspace.store.put("prompts", prompt)
        app.workspace.store.put("chat_attachments", JSONObject().put("id", "shared-staged").put("prompt_id", "staged-builder"))
        app.history.importShared("shared-staged", "Next release", emptyList())
        app.history.select("shared-staged")
        val home = open(HomeActivity::class.java)
        try {
            online(home.get())
            snap(home.get(), "workspace-chat-attachment")
            val attachment = home.get().findViewById<TextView>(R.id.prompt_attachment)
            assertEquals(View.VISIBLE, attachment.visibility)
            assertTrue(attachment.width > 240)
            assertTrue(attachment.height >= 72)
            assertEquals(View.GONE, home.get().findViewById<View>(R.id.input).visibility)
            assertEquals("Send", home.get().findViewById<View>(R.id.send).contentDescription)
            dev.r1ptt.workspace.PromptAttachment.accepted(app, "shared-staged")
            app.history.importShared("shared-staged", "Next release", listOf(
                Msg(Msg.USER, "Turn these observations into a test plan.", promptId = "staged-builder", promptTitle = "Next release", promptRevision = 2),
                Msg(Msg.ASSISTANT, "Start with reconnect, then test editing on both devices.")))
            settle()
            snap(home.get(), "workspace-prompt-chat")
            assertEquals(View.GONE, attachment.visibility)
            assertEquals(View.VISIBLE, home.get().findViewById<View>(R.id.input).visibility)
        } finally { home.pause().stop().destroy() }
    }

    /** Public screenshots use only these staged fixtures, never a connected device's data. */
    @Test fun githubGallery() {
        configured()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
        assertTrue(app.radio.isOnline())
        shadowOf(app.getSystemService(BatteryManager::class.java))
            .setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 82)

        val home = open(HomeActivity::class.java)
        try {
            online(home.get())
            snap(home.get(), "github/home")
            val request = "Text Sam I'll be there in five minutes."
            repeat(24) {
                turn(TurnState(phase = Phase.LISTENING, level = (it % 7) * .05f + .02f, heard = request))
            }
            snap(home.get(), "github/listening")
            turn(TurnState())
            // Seed the saved transcript directly. This does not ask Hermes or send an SMS.
            app.history.add(Msg.USER, request)
            app.history.add(Msg.ASSISTANT, "Sent to Sam: \"I'll be there in five minutes.\"")
            app.history.rename(app.history.convId, "On my way")
            settle()
            snap(home.get(), "github/conversation")
            home.get().findViewById<View>(R.id.menu).performClick()
            settle(400)
            snap(home.get(), "github/menu")
        } finally { home.pause().stop().destroy() }

        val current = app.history.convId
        app.history.newConversation()
        app.history.add(Msg.USER, "What do I need for dinner?")
        app.history.add(Msg.ASSISTANT, "Pasta, tomatoes, basil and parmesan.")
        app.history.rename(app.history.convId, "Dinner plans")
        app.history.select(current)
        listOf(
            ChatsActivity::class.java to "chats",
            QuickControlsActivity::class.java to "quick-controls",
            SettingsActivity::class.java to "settings",
        ).forEach { (type, name) ->
            val controller = open(type)
            try { snap(controller.get(), "github/$name") }
            finally { controller.pause().stop().destroy() }
        }

        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_SMS)
        app.sms.receiveEnabled = true
        SmsStore(app).use { db ->
            listOf(
                Triple("Sam", "+12025550101", "See you in a few!"),
                Triple("Alex", "+12025550102", "Coffee tomorrow at 9?"),
            ).forEachIndexed { index, (name, number, body) ->
                db.addRecipient(name, number)
                db.insert(SmsRecord("demo-$index", number, body, System.currentTimeMillis() - index * 60_000L, 1, true))
            }
        }
        val messages = open(MessagesActivity::class.java)
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            fun loaded(): Boolean {
                val labels = all(messages.get().window.decorView).map { it.contentDescription?.toString().orEmpty() }
                return labels.any { it.startsWith("● Sam.") } && labels.any { it.startsWith("● Alex.") }
            }
            while (!loaded() && System.nanoTime() < deadline) { settle(); Thread.sleep(5) }
            assertTrue("Demo message names and threads must finish loading before capture", loaded())
            snap(messages.get(), "github/messages")
        } finally { messages.pause().stop().destroy() }
    }

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
        // A tap on the stage keeps the words to edit instead of sending them.
        turn(TurnState(phase = Phase.LISTENING, level = .12f, heard = "What's on my calendar today and can you move the gym", editing = true))
        snap(home, "home-02-listening-edit")
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

    @Test fun theAgentsNameReplacesHermesWhereYouTalkToIt() {
        configured()
        conversation()
        app.store.update { it.copy(agent = it.agent.copy(name = "Robot")) }
        val controller = open(HomeActivity::class.java)
        val home = controller.get()
        online(home)
        assertEquals("Message Robot", home.findViewById<android.widget.EditText>(R.id.input).hint.toString())
        val labels = all(home.findViewById(R.id.list)).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.any { it.startsWith("ROBOT  ·") })
        assertFalse(labels.any { it.startsWith("HERMES") })
        app.notices.add("Your package was delivered.")
        settle()
        assertTrue(home.findViewById<TextView>(R.id.hermes_notice).text.startsWith("NOTE FROM ROBOT"))
        snap(home, "home-15-named")
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
        open(HermesActivity::class.java).also { c ->
            snap(c.get(), "hermes")
            val field = all(c.get().window.decorView).first { it.contentDescription == "What you call Hermes" }
            all(c.get().window.decorView).filterIsInstance<android.widget.ScrollView>().first().scrollTo(0, field.top - 160)
            snap(c.get(), "hermes-name")
            c.pause().stop().destroy()
        }
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
