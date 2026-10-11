package dev.r1ptt.workspace

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.SettingsActivity
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.hermes.HermesActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class PairedConnectionUiTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    private fun pair() {
        // No network credentials: these tests exercise only the native navigation surfaces.
        val identity = JSONObject().put("device_id", "paired-r1").put("base_url", "https://workspace.example")
        val disk = object : ConfigBlobStorage { var value: String? = null; override fun read() = value; override fun write(blob: String) { value = blob } }
        val cipher = object : ConfigCipher { override fun seal(plain: String, allowKeyCreation: Boolean) = plain; override fun open(blob: String) = blob }
        val codec = object : ConfigCodec<JSONObject> { override fun encode(value: JSONObject) = value.toString(); override fun decode(plain: String) = JSONObject(plain) }
        app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.workspace.identity, EncryptedConfigPersistence(identity, disk, cipher, codec))
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
    }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun buttons(view: View) = all(view).filterIsInstance<Button>()
    private fun connections(activity: SettingsActivity) {
        all(activity.window.decorView).single { it.contentDescription?.toString()?.startsWith("Connections.") == true }.performClick()
    }

    @Test fun pairedHermesUsesTheHostAndSharedRunsWhileKeepingLocalPermissions() {
        pair()
        val controller = Robolectric.buildActivity(HermesActivity::class.java).create()
        val activity = controller.get()
        val root = activity.window.decorView
        val text = all(root).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue("workspace.example" in text)
        assertTrue("Managed by your host" in text)
        assertFalse(buttons(root).any { it.text == "Check connection" || it.text == "Choose model" })
        val permissions = all(root).filterIsInstance<Switch>().map { it.text.toString() }
        assertTrue(permissions.containsAll(listOf("Let Hermes access this R1", "Relay my texts to Hermes", "Speak announcements", "Show messages on screen", "Change volume", "Save recipients")))
        buttons(root).single { it.text == "Host & devices" }.performClick()
        assertEquals(HubSettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        buttons(root).single { it.text == "Workspace runs" }.performClick()
        val route = shadowOf(activity).nextStartedActivity
        assertEquals(WorkspaceActivity::class.java.name, route.component!!.className)
        val workspace = Robolectric.buildActivity(WorkspaceActivity::class.java, route).create()
        assertEquals("runs", currentScreen(workspace.get()))
        workspace.destroy(); controller.destroy()
    }

    @Test fun pairedSettingsReplacesDirectCredentialsAndKeepsSpeechSettings() {
        pair()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create()
        val activity = controller.get()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        connections(activity)
        assertTrue(all(activity.window.decorView).filterIsInstance<EditText>().isEmpty())
        buttons(activity.window.decorView).single { it.text == "Host & devices" }.performClick()
        assertEquals(HubSettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        buttons(activity.window.decorView).single { it.text == "Workspace runs" }.performClick()
        assertEquals(WorkspaceActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        @Suppress("DEPRECATION") activity.onBackPressed()
        all(activity.window.decorView).single { it.contentDescription?.toString()?.startsWith("Speech services.") == true }.performClick()
        assertTrue(all(activity.window.decorView).filterIsInstance<EditText>().any { it.contentDescription == "OpenAI speech API key" })
        controller.destroy()
    }

    @Test fun unpairedDevicesKeepDirectConnectionAndModelControls() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).create()
        connections(settings.get())
        assertTrue(all(settings.get().window.decorView).filterIsInstance<EditText>().any { it.contentDescription == "Hermes API key" })
        assertTrue(buttons(settings.get().window.decorView).any { it.text == "Save connection" })
        val hermes = Robolectric.buildActivity(HermesActivity::class.java).create()
        assertTrue(buttons(hermes.get().window.decorView).any { it.text == "Choose model" })
        assertTrue(buttons(hermes.get().window.decorView).any { it.text == "Check connection" })
        hermes.destroy(); settings.destroy()
    }

    @Test fun sharedRunsDestinationWaitsForRecoveryVerification() {
        pair(); app.workspace.requireRecovery(checkNow = false)
        val controller = Robolectric.buildActivity(WorkspaceActivity::class.java, WorkspaceActivity.runsIntent(app)).create()
        val activity = controller.get()
        assertEquals("restricted", currentScreen(activity))
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
        activity.javaClass.getDeclaredMethod("landingPage").apply { isAccessible = true }.invoke(activity)
        assertEquals("runs", currentScreen(activity))
        controller.destroy()
    }

    private fun currentScreen(activity: WorkspaceActivity) = activity.javaClass.getDeclaredField("screen").apply { isAccessible = true }.get(activity)
}
