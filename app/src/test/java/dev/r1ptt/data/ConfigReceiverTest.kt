package dev.r1ptt.data

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import dev.r1ptt.App
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [34], application = App::class)
class ConfigReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    private val privateToken = "private-r1-token-".repeat(4)
    private var writes = 0
    private val plain = object : ConfigCipher {
        override fun seal(plain: String, allowKeyCreation: Boolean) = plain
        override fun open(blob: String) = blob
    }

    @Before fun setup() {
        val disk = object : ConfigBlobStorage {
            var value: String? = null
            override fun read() = value
            override fun write(blob: String) { value = blob; writes++ }
        }
        val codec = object : ConfigCodec<Config> {
            override fun encode(value: Config) = ConfigJson.toJson(value).toString()
            override fun decode(plain: String) = ConfigJson.merge(Config(), JSONObject(plain))
        }
        ReflectionHelpers.setField(app.store, "persistence", EncryptedConfigPersistence(app.store.value, disk, plain, codec))
        app.store.update { it.copy(hermes = HermesLink(token = privateToken)) }
        writes = 0
    }

    private fun pair(lost: Boolean = false, revoked: Boolean = false) {
        val identity = JSONObject().put("device_id", "paired-r1").put("lost", lost).put("revoked", revoked)
        val disk = object : ConfigBlobStorage {
            var value: String? = null
            override fun read() = value
            override fun write(blob: String) { value = blob }
        }
        val codec = object : ConfigCodec<JSONObject> {
            override fun encode(value: JSONObject) = value.toString()
            override fun decode(plain: String) = JSONObject(plain)
        }
        ReflectionHelpers.setField(app.workspace.identity, "persistence", EncryptedConfigPersistence(identity, disk, plain, codec))
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
    }

    private fun deliver(json: String): String {
        val receiver = ConfigReceiver()
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod("create", Int::class.javaPrimitiveType,
            String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        ReflectionHelpers.setField(receiver, "mPendingResult", create.invoke(null, 0, null, null, true) as BroadcastReceiver.PendingResult)
        receiver.onReceive(app, Intent(ConfigReceiver.ACTION).putExtra("json_b64",
            Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)))
        return receiver.resultData
    }

    @Test fun legacySetupCannotRestoreHostCredentialsOrSharedTokenOnAPairedR1() {
        pair()
        val previous = app.store.value
        val result = deliver("""{"agent":{"baseUrl":"https://old-host/v1","apiKey":"host-secret"},
            "hermes":{"token":"${"shared-token-".repeat(4)}","deviceApi":true},
            "openaiKey":"speech-secret","power":{"brightness":180}}""")
        assertTrue(result.contains("This R1 is paired"))
        assertTrue(result.contains("Host & devices"))
        assertTrue(result.contains("nothing was saved"))
        assertFalse(result.contains("host-secret")); assertFalse(result.contains("shared-token"))
        assertEquals(previous, app.store.value)
        assertEquals(0, writes)
    }

    @Test fun everyDirectAgentSettingIsBlockedEvenWithoutADeviceAccessSection() {
        pair()
        val previous = app.store.value
        listOf("baseUrl" to "https://old-host/v1", "apiKey" to "host-secret", "model" to "other-model",
            "provider" to "other-provider", "timeoutSec" to 120, "headers" to JSONObject().put("Authorization", "Bearer host-secret"))
            .forEach { (key, value) ->
                val result = deliver(JSONObject().put("agent", JSONObject().put(key, value)).put("quiet", true).toString())
                assertTrue("$key must remain host-managed", result.contains("This R1 is paired"))
                assertEquals(previous, app.store.value)
            }
        assertEquals(0, writes)
    }

    @Test fun pairedDevicePermissionsAndPrivateTokenRequireLocalControls() {
        pair()
        val previous = app.store.value
        listOf("token" to "imported-token".repeat(4), "deviceApi" to true, "port" to 9999,
            "passthrough" to true, "owner" to "+14055550123", "passphrase" to "otter-42", "smsSession" to "imported-session", "dailySendLimit" to 0,
            "allowSpeak" to true, "allowShow" to true, "allowVolume" to true, "allowSaveRecipient" to true).forEach { (key, value) ->
            val result = deliver(JSONObject().put("hermes", JSONObject().put(key, value)).toString())
            assertTrue("$key requires local consent", result.contains("device permissions in Hermes on the R1"))
            assertEquals(previous, app.store.value)
        }
        assertEquals(0, writes)
    }

    @Test fun pairedSpeechPowerAndDisplayProvisioningStillWorks() {
        pair()
        val result = deliver("""{"agent":{"name":"Robot"},"openaiKey":"speech-key",
            "stt":{"baseUrl":"https://speech.example/v1","apiKey":"transcription-key"},
            "power":{"brightness":80},"quiet":true,"textSizeSp":19}""")
        assertTrue(result == "ok" || result == "error: Settings saved; power policy could not be applied")
        assertEquals(1, writes)
        val saved = app.store.value
        assertEquals("Robot", saved.agent.name); assertEquals("", saved.agent.apiKey)
        assertEquals("speech-key", saved.openaiKey); assertEquals("transcription-key", saved.stt.apiKey)
        assertEquals(80, saved.power.brightness); assertTrue(saved.quiet); assertEquals(19, saved.textSizeSp)
        assertEquals(HermesLink(token = privateToken), saved.hermes)
    }

    @Test fun unverifiedLostAndRevokedDevicesRejectEvenOrdinaryProvisioning() {
        pair(); app.workspace.requireRecovery(checkNow = false)
        val previous = app.store.value
        assertEquals("error: Device access is restricted", deliver("""{"quiet":true}"""))
        pair(lost = true)
        assertEquals("error: Device access is restricted", deliver("""{"quiet":true}"""))
        pair(revoked = true)
        assertEquals("error: Device access is restricted", deliver("""{"quiet":true}"""))
        assertEquals(previous, app.store.value); assertEquals(0, writes)
    }

    @Test fun unpairedLegacyDirectSetupRemainsAvailable() {
        deliver("""{"agent":{"baseUrl":"https://direct-host/v1","apiKey":"direct-key"},
            "hermes":{"deviceApi":true,"token":"${"legacy-token".repeat(4)}"}}""")
        assertEquals("direct-key", app.store.value.agent.apiKey)
        assertTrue(app.store.value.hermes.deviceApi)
        assertEquals("legacy-token".repeat(4), app.store.value.hermes.token)
        assertEquals(1, writes)
    }

    @Test fun malformedInputNeverEchoesProvisionedSecretsOrPartiallySaves() {
        val previous = app.store.value
        assertEquals("error: Invalid settings; nothing was saved", deliver("""{"quiet":true,"agent":{"apiKey":"secret-do-not-echo"},"textSizeSp":"secret-do-not-echo"}"""))
        assertEquals(previous, app.store.value); assertEquals(0, writes)
    }
}
