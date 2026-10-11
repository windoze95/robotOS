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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [34], application = App::class)
class HermesActivityTest {
    private lateinit var app: App

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        configure { it.copy(hermes = HermesLink(deviceApi = true, token = "t".repeat(40))) }
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

    private fun identity(value: JSONObject) {
        val disk = object : ConfigBlobStorage { var stored: String? = null; override fun read() = stored; override fun write(blob: String) { stored = blob } }
        val cipher = object : ConfigCipher { override fun seal(plain: String, allowKeyCreation: Boolean) = plain; override fun open(blob: String) = blob }
        val codec = object : ConfigCodec<JSONObject> { override fun encode(value: JSONObject) = value.toString(); override fun decode(plain: String) = JSONObject(plain) }
        app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.workspace.identity, EncryptedConfigPersistence(value, disk, cipher, codec))
    }

    @Test fun anOlderPairedDeviceCanExplicitlyEnableAccessWithoutComputerProvisioning() {
        app.store.update { it.copy(hermes = HermesLink()) }
        identity(JSONObject().put("device_id", "paired-r1"))
        val unchanged = Robolectric.buildActivity(HermesActivity::class.java).create()
        val root = unchanged.get().window.decorView as ViewGroup
        val access = views<Switch>(root).first()
        assertTrue(access.isEnabled); assertFalse(access.isChecked)
        views<Button>(root).single { it.text == "Save" }.performClick()
        assertEquals(HermesLink(), app.store.value.hermes)
        unchanged.destroy()
        val optedIn = Robolectric.buildActivity(HermesActivity::class.java).create()
        val controls = optedIn.get().window.decorView as ViewGroup
        views<Switch>(controls).first().isChecked = true
        views<Button>(controls).single { it.text == "Save" }.performClick()
        assertTrue(optedIn.get().isFinishing)
        assertTrue(app.store.value.hermes.deviceApi)
        assertEquals(64, app.store.value.hermes.token.length)
        assertEquals(HermesLink(), app.store.value.hermes.copy(deviceApi = false, token = ""))
        optedIn.destroy()
    }

    @Test fun freshEnrollmentProvisionsAPrivateTokenWithoutEnablingDevicePermissions() {
        app.store.update { it.copy(hermes = HermesLink(), agent = it.agent.copy(apiKey = "old-direct-key")) }
        identity(JSONObject())
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.path!!.endsWith("/enrollments/claim")) okhttp3.mockwebserver.MockResponse().setBody(
                        """{"device_id":"fresh-r1","device_token":"content-token","recovery_token":"recovery-token"}""")
                    else okhttp3.mockwebserver.MockResponse().setResponseCode(503)
            }
            server.start()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                // A later sync outage must not undo the successful enrollment or grant permissions.
                val paired = executor.submit<Boolean> { kotlinx.coroutines.runBlocking {
                    runCatching { app.workspace.pair(server.url("/").toString(), "123456", "Second R1") }.isFailure
                } }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (!paired.isDone && System.nanoTime() < deadline) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(5) }
                assertTrue(paired.get(1, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("fresh-r1", app.workspace.identity.value.getString("device_id"))
                assertEquals("", app.store.value.agent.apiKey)
                assertEquals(64, app.store.value.hermes.token.length)
                assertEquals(HermesLink(), app.store.value.hermes.copy(token = ""))
                assertTrue(app.workspace.blocked)
            } finally { executor.shutdownNow() }
        }
    }

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

    @Test fun theAgentsNameSavesOnlyWhenValidAndEmptyMeansHermes() {
        val (activity, root) = screen()
        val name = views<EditText>(root).single { it.contentDescription == "What you call Hermes" }
        name.setText("<b>")
        views<Button>(root).first { it.text == "Save" }.performClick()
        assertFalse(activity.isFinishing)
        assertEquals("", app.store.value.agent.name)
        name.setText("  Robot ")
        views<Button>(root).first { it.text == "Save" }.performClick()
        assertTrue(activity.isFinishing)
        assertEquals("Robot", app.store.value.agent.name)
        val (_, renamed) = screen()
        assertTrue(views<Switch>(renamed).any { it.text == "Let Robot access this R1" })
        views<EditText>(renamed).single { it.contentDescription == "What you call Hermes" }.setText("")
        views<Button>(renamed).first { it.text == "Save" }.performClick()
        assertEquals("Hermes", app.store.value.agent.displayName)
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

    @Test fun savePreservesUntouchedSettingsChangedWhileTheFormWasOpen() {
        val (activity, root) = screen()
        app.store.update { it.copy(hermes = it.hermes.copy(deviceApi = false, token = "n".repeat(40),
            owner = "+14055550199", dailySendLimit = 25, allowShow = true)) }
        views<Switch>(root).first { it.text == "Speak announcements" }.isChecked = true
        views<Button>(root).first { it.text == "Save" }.performClick()
        assertTrue(activity.isFinishing)
        val link = app.store.value.hermes
        assertFalse(link.deviceApi)
        assertTrue(link.allowSpeak && link.allowShow)
        assertEquals("n".repeat(40), link.token)
        assertEquals("+14055550199", link.owner)
        assertEquals(25, link.dailySendLimit)
    }
}
