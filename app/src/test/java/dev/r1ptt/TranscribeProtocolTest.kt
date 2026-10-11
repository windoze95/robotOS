package dev.r1ptt

import dev.r1ptt.net.TranscribeProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscribeProtocolTest {
    @Test
    fun sessionUpdateIsAManualTranscriptionSession() {
        val s = JSONObject(TranscribeProtocol.sessionUpdate("gpt-live-transcribe", "low", listOf("en")))
            .getJSONObject("session")
        assertEquals("transcription", s.getString("type"))
        val input = s.getJSONObject("audio").getJSONObject("input")
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals(24_000, input.getJSONObject("format").getInt("rate"))
        assertTrue(input.isNull("turn_detection"))
        val t = input.getJSONObject("transcription")
        assertEquals("gpt-live-transcribe", t.getString("model"))
        assertEquals("low", t.getString("delay"))
        assertEquals("en", t.getJSONArray("languages").getString(0))
        val noLang = JSONObject(TranscribeProtocol.sessionUpdate("m", "low", emptyList()))
        assertFalse(noLang.getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("transcription").has("languages"))
    }

    @Test
    fun parsesTranscriptionEvents() {
        assertEquals(TranscribeProtocol.Event.Ready, TranscribeProtocol.parse("""{"type":"session.updated","session":{}}"""))
        assertEquals(TranscribeProtocol.Event.Delta(" Search"), TranscribeProtocol.parse("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"i","delta":" Search"}"""))
        assertEquals(
            TranscribeProtocol.Event.Completed("Search the web."),
            TranscribeProtocol.parse("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"i","transcript":"Search the web."}"""),
        )
        assertEquals(TranscribeProtocol.Event.Failed("too short"), TranscribeProtocol.parse("""{"type":"error","error":{"message":"too short"}}"""))
        assertEquals(TranscribeProtocol.Event.Other, TranscribeProtocol.parse("""{"type":"input_audio_buffer.committed"}"""))
    }
}
