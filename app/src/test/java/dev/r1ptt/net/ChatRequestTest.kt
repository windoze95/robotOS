package dev.r1ptt.net

import dev.r1ptt.data.Agent
import dev.r1ptt.data.Config
import dev.r1ptt.data.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestTest {
    private val conversation = listOf(
        Msg(Msg.USER, "my name is Sam"),
        Msg(Msg.ASSISTANT, "Hi Sam."),
        Msg(Msg.USER, "what's my name?"),
    )
    private val cfg = Config(agent = Agent(baseUrl = "https://hermes.example/v1/", apiKey = "k"))

    @Test
    fun hermesGetsOnlyTheNewMessageAndContinuesItsSession() {
        val b = ChatRequest.build(cfg, conversation, "abc")
        assertEquals("https://hermes.example/v1/chat/completions", b.url)
        assertEquals("Bearer k", b.headers["Authorization"])
        assertEquals("r1ptt-abc", b.headers["X-Hermes-Session-Id"])
        assertEquals("hermes-agent", b.body.getString("model"))
        assertTrue(b.body.getBoolean("stream"))
        val msgs = b.body.getJSONArray("messages")
        assertEquals(2, msgs.length()) // system + the newest user message
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("what's my name?", msgs.getJSONObject(1).getString("content"))
        assertEquals(300, b.timeoutSec)
    }

    @Test
    fun aSessionCanBeChosenForTexts() {
        assertEquals("robotos-sms", ChatRequest.build(cfg, conversation, "abc", session = "robotos-sms").headers["X-Hermes-Session-Id"])
    }

    @Test
    fun noKeyMeansNoAuthHeaderAndExtraHeadersPassThrough() {
        val c = cfg.copy(agent = cfg.agent.copy(apiKey = "", headers = mapOf("CF-Access-Client-Id" to "id")), systemPrompt = "")
        val b = ChatRequest.build(c, conversation, "abc")
        assertNull(b.headers["Authorization"])
        assertEquals("id", b.headers["CF-Access-Client-Id"])
        assertEquals(1, b.body.getJSONArray("messages").length())
        assertFalse(b.body.has("user"))
    }
}
