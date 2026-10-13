package dev.r1ptt.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigJsonTest {
    @Test
    fun roundTripsTheDefaults() {
        assertEquals(Config(), ConfigJson.merge(Config(), ConfigJson.toJson(Config())))
    }

    @Test fun newPreferencesPreserveLegacyAccessAndPocketInterval() {
        val legacy = ConfigJson.merge(Config(), JSONObject("""{"power":{"wifiIdleMinutes":7},"hermes":{"deviceApi":true,"token":"${"x".repeat(32)}"}}"""))
        assertTrue(legacy.hermes.deviceApi)
        assertFalse(legacy.hermes.allowSpeak)
        assertFalse(legacy.hermes.allowShow)
        assertFalse(legacy.hermes.allowVolume)
        assertFalse(legacy.hermes.allowSaveRecipient)
        assertFalse(legacy.hermes.allowOpenApps)
        val navigation = ConfigJson.merge(legacy, JSONObject("""{"hermes":{"allowOpenApps":true}}"""))
        assertTrue(navigation.hermes.allowOpenApps)
        assertEquals(navigation, ConfigJson.merge(Config(), ConfigJson.toJson(navigation)))
        val reachable = ConfigJson.merge(legacy, JSONObject("""{"power":{"stayReachable":true},"quiet":true,"agent":{"provider":"configured-provider","model":"configured-model"}}"""))
        assertTrue(reachable.power.stayReachable)
        assertEquals(7, reachable.power.wifiIdleMinutes)
        assertTrue(reachable.quiet)
        assertEquals(reachable, ConfigJson.merge(Config(), ConfigJson.toJson(reachable)))
        assertEquals(7, ConfigJson.merge(reachable, JSONObject("""{"power":{"stayReachable":false}}""")).power.wifiIdleMinutes)
    }

    @Test fun aRelayPassphraseRoundTripsAndMustBeTypeable() {
        val token = "x".repeat(32)
        val gated = ConfigJson.merge(Config(), JSONObject("""{"hermes":{"deviceApi":true,"token":"$token","passphrase":"otter-42"}}"""))
        assertEquals("otter-42", gated.hermes.passphrase)
        assertEquals(gated, ConfigJson.merge(Config(), ConfigJson.toJson(gated)))
        assertEquals("otter-42", ConfigJson.merge(gated, JSONObject("""{"hermes":{"dailySendLimit":5}}""")).hermes.passphrase)
        assertEquals("", ConfigJson.merge(gated, JSONObject("""{"hermes":{"passphrase":""}}""")).hermes.passphrase)
        listOf("short", " otter-42", "otter\n42", "x".repeat(HermesLink.PASSPHRASE_MAX + 1)).forEach { bad ->
            assertFalse(bad, HermesLink.validPassphrase(bad))
            assertTrue(bad, runCatching { ConfigJson.merge(gated, JSONObject().put("hermes", JSONObject().put("passphrase", bad))) }.isFailure)
        }
    }

    @Test fun theAgentsNameIsTidiedAndAnInvalidOneIsIgnored() {
        assertEquals("Robot", Agent.cleanName("  Robot "))
        assertEquals("Mr. Robot", Agent.cleanName("Mr.   Robot"))
        assertEquals("R2-D2", Agent.cleanName("R2-D2"))
        assertEquals("Zoë", Agent.cleanName("Zoë"))
        assertEquals("Robot Maker", Agent.cleanName("Robot\nMaker"))
        listOf("", "   ", "-Robot", "<b>Robot</b>", "Robot\u0000", "x".repeat(25)).forEach { assertEquals(null, Agent.cleanName(it)) }
        val named = ConfigJson.merge(Config(), JSONObject("""{"agent":{"name":"Robot"}}"""))
        assertEquals("Robot", named.agent.name)
        assertEquals("Robot", named.agent.displayName)
        assertEquals(named, ConfigJson.merge(Config(), ConfigJson.toJson(named)))
        assertEquals("Robot", ConfigJson.merge(named, JSONObject("""{"agent":{"apiKey":"k"}}""")).agent.name)
        assertEquals("Robot", ConfigJson.merge(named, JSONObject("""{"agent":{"name":"<b>"}}""")).agent.name)
        val cleared = ConfigJson.merge(named, JSONObject("""{"agent":{"name":""}}"""))
        assertEquals("", cleared.agent.name)
        assertEquals("Hermes", cleared.agent.displayName)
    }

    @Test
    fun partialImportOnlyTouchesWhatItNames() {
        val c = ConfigJson.merge(Config(), JSONObject("""{"agent":{"apiKey":"k-1"},"power":{"wifiIdleMinutes":0}}"""))
        assertEquals("k-1", c.agent.apiKey)
        assertEquals("hermes-agent", c.agent.model)
        assertEquals(3, c.power.wifiIdleMinutes)
        assertTrue(c.power.stayReachable)
        assertEquals(Config().power.screenTimeoutSec, c.power.screenTimeoutSec)
        val more = ConfigJson.merge(c, JSONObject("""{"agent":{"baseUrl":"https://h.example/v1","headers":{"X-Test":"1"},"timeoutSec":90}}"""))
        assertEquals("k-1", more.agent.apiKey)
        assertEquals(mapOf("X-Test" to "1"), more.agent.headers)
        assertEquals(90, more.agent.timeoutSec)
    }

    @Test fun legacyNeverSleepBecomesReachableAndCanReturnToPocketMode() {
        val legacy = ConfigJson.merge(Config(), JSONObject("""{"power":{"wifiIdleMinutes":0,"stayReachable":false}}"""))
        assertTrue(legacy.power.stayReachable)
        assertEquals(3, legacy.power.wifiIdleMinutes)
        assertEquals(legacy, ConfigJson.merge(Config(), ConfigJson.toJson(legacy)))
        val pocket = ConfigJson.merge(legacy, JSONObject("""{"power":{"stayReachable":false}}"""))
        assertFalse(pocket.power.reachableOnBattery)
        assertEquals(3, pocket.power.wifiIdleMinutes)
    }

    @Test fun importingNeverSleepPreservesAnExistingPositivePocketDelay() {
        val previous = Config(power = Power(wifiIdleMinutes = 7))
        val reachable = ConfigJson.merge(previous, JSONObject("""{"power":{"wifiIdleMinutes":0}}"""))
        assertTrue(reachable.power.stayReachable)
        assertEquals(7, reachable.power.wifiIdleMinutes)
        assertEquals(Power(wifiIdleMinutes = 7), reachable.power.withReachability(false))
        val runtimeLegacy = Config(power = Power(wifiIdleMinutes = 0))
        assertTrue(ConfigJson.merge(runtimeLegacy, JSONObject("""{"quiet":true}""")).power.stayReachable)
        assertFalse(ConfigJson.merge(runtimeLegacy, JSONObject("""{"power":{"stayReachable":false}}""")).power.reachableOnBattery)
    }

    @Test
    fun theOpenAiKeyIsOnlyForOpenAiSpeech() {
        val c = ConfigJson.merge(Config(), JSONObject("""{"openaiKey":"sk-o","agent":{"apiKey":"tok"}}"""))
        assertEquals("sk-o", c.keyFor(c.stt))
        assertEquals("sk-o", c.keyFor(c.tts.endpoint))
        assertEquals("own", c.keyFor(c.stt.copy(apiKey = "own")))
        assertEquals("", c.keyFor(Endpoint("http://speaches.lan:8000/v1", model = "x")))
        assertFalse(c.missingSpeechKey)
        assertTrue(Config().missingSpeechKey)
        val selfHosted = Endpoint("http://speaches.lan:8000/v1", model = "x")
        assertFalse(Config(stt = selfHosted, tts = Tts(endpoint = selfHosted)).missingSpeechKey)
        assertTrue(Config(stt = selfHosted).missingSpeechKey) // replies are still read aloud by OpenAI
        assertFalse(Config(stt = selfHosted, tts = Tts(enabled = false)).missingSpeechKey)
    }
}
