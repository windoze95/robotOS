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
        val reachable = ConfigJson.merge(legacy, JSONObject("""{"power":{"stayReachable":true},"quiet":true,"agent":{"provider":"configured-provider","model":"configured-model"}}"""))
        assertTrue(reachable.power.stayReachable)
        assertEquals(7, reachable.power.wifiIdleMinutes)
        assertTrue(reachable.quiet)
        assertEquals(reachable, ConfigJson.merge(Config(), ConfigJson.toJson(reachable)))
        assertEquals(7, ConfigJson.merge(reachable, JSONObject("""{"power":{"stayReachable":false}}""")).power.wifiIdleMinutes)
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
