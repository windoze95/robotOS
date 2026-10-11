package dev.r1ptt.hermes

import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import dev.r1ptt.App
import dev.r1ptt.data.Config
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.ConfigJson
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.data.HermesLink
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [34], application = App::class)
class HermesActivityTest {
    private lateinit var app: App

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        configure { it.copy(activeProvider = "hermes", hermes = HermesLink(deviceApi = true, token = "t".repeat(40))) }
        // The real store seals with the Android Keystore, which Robolectric lacks; saves stay in memory here.
        val memory = object : ConfigBlobStorage {
            var blob: String? = null
            override fun read() = blob
            override fun write(blob: String) { this.blob = blob }
        }
        val plain = object : ConfigCipher {
            override fun seal(plain: String, allowKeyCreation: Boolean) = plain
            override fun open(blob: String) = blob
        }
        val codec = object : ConfigCodec<Config> {
            override fun encode(value: Config) = ConfigJson.toJson(value).toString()
            override fun decode(plain: String) = ConfigJson.merge(Config(), JSONObject(plain))
        }
        app.store.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.store, EncryptedConfigPersistence(app.store.value, memory, plain, codec))
    }

    @Suppress("UNCHECKED_CAST") private fun configure(update: (Config) -> Config) {
        val state = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store) as MutableStateFlow<Config>
        state.value = update(state.value)
    }

    private fun all(group: ViewGroup): List<android.view.View> = (0 until group.childCount).flatMap {
        val v = group.getChildAt(it)
        listOf(v) + if (v is ViewGroup) all(v) else emptyList()
    }
    private inline fun <reified T> views(group: ViewGroup): List<T> = all(group).filterIsInstance<T>()

    private fun screen() = Robolectric.buildActivity(HermesActivity::class.java).setup().get().let { it to (it.window.decorView as ViewGroup) }

    @Test fun savesYourNumberAndPassthroughWithoutTouchingTheToken() {
        val (activity, root) = screen()
        val (act, passthrough) = views<Switch>(root)
        assertTrue(act.isEnabled && act.isChecked)
        passthrough.isChecked = true
        views<EditText>(root).last().setText("+1 (405) 555-0123")
        views<Button>(root).first { it.text == "Save" }.performClick()
        assertTrue(activity.isFinishing)
        val link = app.store.value.hermes
        assertTrue(link.passthrough && link.deviceApi)
        assertEquals("+14055550123", link.owner)
        assertEquals("t".repeat(40), link.token)
    }

    @Test fun badNumbersAndMissingTokensChangeNothing() {
        configure { it.copy(hermes = HermesLink()) }
        val (activity, root) = screen()
        val (act, passthrough) = views<Switch>(root)
        assertFalse(act.isEnabled)
        passthrough.isChecked = true
        views<EditText>(root).last().setText("call me maybe")
        views<Button>(root).first { it.text == "Save" }.performClick()
        assertFalse(activity.isFinishing)
        assertEquals(HermesLink(), app.store.value.hermes)
    }
}
