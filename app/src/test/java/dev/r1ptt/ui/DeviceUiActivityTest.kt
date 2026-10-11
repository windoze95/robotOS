package dev.r1ptt.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.app.AlertDialog
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.QuickControlsActivity
import dev.r1ptt.SettingsActivity
import dev.r1ptt.audio.AudioPlayback
import dev.r1ptt.data.Config as DeviceConfig
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.ConfigJson
import dev.r1ptt.data.EncryptedConfigPersistence
import java.io.File
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, qualifiers = "w404dp-h539dp-mdpi")
class DeviceUiActivityTest {
    private lateinit var app: App

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
    }

    @After fun cleanup() { app.turns.shutdown() }

    @Test fun savingOneConnectionFieldPreservesConcurrentChangesAndUnrelatedSettings() {
        app.store.update { it.copy(agent = it.agent.copy(baseUrl = "https://old.example/v1", apiKey = "before", model = "old")) }
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        all(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("Connections.") == true }.performClick()
        all(activity.window.decorView).filterIsInstance<EditText>().single { it.contentDescription == "Model" }.setText("edited")
        app.store.update { it.copy(agent = it.agent.copy(baseUrl = "https://new.example/v1", apiKey = "rotated"), quiet = true) }
        all(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Save connection" }.performClick()
        assertEquals("edited", app.store.value.agent.model)
        assertEquals("https://new.example/v1", app.store.value.agent.baseUrl)
        assertEquals("rotated", app.store.value.agent.apiKey)
        assertTrue(app.store.value.quiet)
        controller.pause().stop().destroy()
    }

    @Test fun quietModeKeepsTheUsersSpeechAndEarconPreferences() {
        val controller = Robolectric.buildActivity(QuickControlsActivity::class.java).setup()
        val activity = controller.get()
        all(activity.window.decorView).filterIsInstance<Switch>().single { it.text == "Quiet mode" }.isChecked = true
        assertTrue(app.store.value.quiet)
        assertTrue(app.store.value.tts.enabled)
        assertTrue(app.store.value.earcons)
        controller.pause().stop().destroy()
    }

    @Test fun quickControlsShowsLegacyNeverSleepAsReachableAndTurningItOffWorks() {
        app.store.update { it.copy(power = it.power.copy(wifiIdleMinutes = 0, stayReachable = false)) }
        val controller = Robolectric.buildActivity(QuickControlsActivity::class.java).setup()
        val control = all(controller.get().window.decorView).filterIsInstance<Switch>().single { it.text == "Stay reachable" }
        assertTrue(control.isChecked)
        control.isChecked = false
        assertFalse(app.store.value.power.reachableOnBattery)
        assertEquals(3, app.store.value.power.wifiIdleMinutes)
        controller.pause().stop().destroy()
    }

    @Test fun powerSettingsKeepThePocketDelayWhenReachabilityChanges() {
        app.store.update { it.copy(power = it.power.copy(wifiIdleMinutes = 7, stayReachable = true)) }
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        all(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("Connectivity & power.") == true }.performClick()
        val control = all(activity.window.decorView).filterIsInstance<Switch>().single { it.text == "Stay reachable on battery" }
        control.isChecked = false
        assertEquals(7, app.store.value.power.wifiIdleMinutes)
        assertFalse(app.store.value.power.reachableOnBattery)
        control.isChecked = true
        assertEquals(7, app.store.value.power.wifiIdleMinutes)
        all(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("Radio sleep delay.") == true }.performClick()
        val dialog: AlertDialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val choices = dialog.listView.adapter
        assertFalse((0 until choices.count).map { choices.getItem(it).toString() }.contains("Never"))
        dialog.listView.performItemClick(null, 0, 0)
        assertEquals(1, app.store.value.power.wifiIdleMinutes)
        assertTrue(app.store.value.power.reachableOnBattery)
        controller.pause().stop().destroy()
    }

    @Test fun brightnessChangesAreDebouncedAndMergeTheLatestPreferences() {
        val controller = Robolectric.buildActivity(QuickControlsActivity::class.java).setup()
        val activity = controller.get()
        val brightness = all(activity.window.decorView).filterIsInstance<SeekBar>().single { it.contentDescription == "Brightness" }
        val initial = app.store.value.power.brightness
        fun set(value: Float) = brightness.performAccessibilityAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) },
        )
        assertTrue(set(40f))
        assertTrue(set(70f))
        assertEquals(initial, app.store.value.power.brightness)
        app.store.update { it.copy(quiet = true) }
        shadowOf(Looper.getMainLooper()).idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(70 * 254 / 99 + 1, app.store.value.power.brightness)
        assertTrue(app.store.value.quiet)
        controller.pause().stop().destroy()
    }

    @Test fun hardwareWheelScrollsAtIdleAndChangesVolumeDuringActualPlayback() {
        val controller = Robolectric.buildActivity(DeviceActivity::class.java).setup()
        val activity = controller.get()
        val scroll = ScrollView(activity).apply {
            addView(TextView(activity).apply { text = "Long conversation"; minimumHeight = 2000 })
        }
        activity.setContentView(scroll)
        scroll.measure(View.MeasureSpec.makeMeasureSpec(404, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(539, View.MeasureSpec.EXACTLY))
        scroll.layout(0, 0, 404, 539)
        activity.wheelScrollTarget = scroll
        val audio = activity.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        assertTrue(activity.handleWheel(wheel(up = false)))
        assertEquals(48, scroll.scrollY)
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        val owner = Any()
        AudioPlayback.started(owner)
        try {
            assertTrue(activity.handleWheel(wheel(up = true)))
            assertEquals(48, scroll.scrollY)
            assertEquals(4, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally { AudioPlayback.stopped(owner) }
        assertFalse(WheelPolicy.isWheel(KeyEvent.KEYCODE_VOLUME_UP, 0, "External keyboard"))
        assertFalse(WheelPolicy.isWheel(KeyEvent.KEYCODE_DPAD_DOWN, 108, "External keyboard"))
        controller.pause().stop().destroy()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun quickControlsHaveReachableLargeControlsAtDeviceWidthAndLargeText() {
        app.store.update { it.copy(textSizeSp = 23) }
        val controller = Robolectric.buildActivity(QuickControlsActivity::class.java).setup()
        val activity = controller.get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(404, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(463, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 404, 463)
        val scroll = all(content).filterIsInstance<ScrollView>().single()
        assertTrue(scroll.canScrollVertically(1))
        all(content).filterIsInstance<Button>().forEach { assertTrue("${it.text} touch height", it.height >= 48) }
        all(content).filterIsInstance<Switch>().forEach { assertTrue("${it.text} touch height", it.height >= 48) }
        scroll.fullScroll(View.FOCUS_DOWN)
        val sleep = all(content).filterIsInstance<Button>().single { it.text == "Sleep" }
        assertTrue(sleep.width >= 48)
        val bitmap = Bitmap.createBitmap(404, 463, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        File("build/device-ui/quick-controls-large.png").apply { requireNotNull(parentFile).mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    private fun wheel(up: Boolean) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
        if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0, -1, if (up) 103 else 108)
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
}
