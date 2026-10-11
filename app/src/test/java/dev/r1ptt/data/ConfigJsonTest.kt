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

    @Test
    fun partialImportOnlyTouchesWhatItNames() {
        val c = ConfigJson.merge(Config(), JSONObject("""{"agent":{"apiKey":"k-1"},"power":{"wifiIdleMinutes":0}}"""))
        assertEquals("k-1", c.agent.apiKey)
        assertEquals("hermes-agent", c.agent.model)
        assertEquals(0, c.power.wifiIdleMinutes)
        assertEquals(Config().power.screenTimeoutSec, c.power.screenTimeoutSec)
        val more = ConfigJson.merge(c, JSONObject("""{"agent":{"baseUrl":"https://h.example/v1","headers":{"X-Test":"1"},"timeoutSec":90}}"""))
        assertEquals("k-1", more.agent.apiKey)
        assertEquals(mapOf("X-Test" to "1"), more.agent.headers)
        assertEquals(90, more.agent.timeoutSec)
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
