package dev.r1ptt.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class Msg(val role: String, val text: String, val at: Long = System.currentTimeMillis()) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/**
 * The current conversation, kept small and saved to disk so it survives restarts. [convId] doubles
 * as Hermes's session key, so a new conversation is a new Hermes session too.
 */
class History(dir: File) {
    data class Snapshot(val convId: String, val messages: List<Msg>)

    private val file = File(dir, "history.json")
    private val io = Executors.newSingleThreadExecutor()
    private val state = MutableStateFlow(load() ?: Snapshot(newId(), emptyList()))

    val flow: StateFlow<Snapshot> = state
    val convId: String get() = state.value.convId
    val messages: List<Msg> get() = state.value.messages

    @Synchronized
    fun add(role: String, text: String) {
        val s = state.value
        state.value = s.copy(messages = (s.messages + Msg(role, text)).takeLast(MAX))
        persist()
    }

    @Synchronized
    fun newConversation() {
        state.value = Snapshot(newId(), emptyList())
        persist()
    }

    private fun load(): Snapshot? = runCatching {
        if (!file.exists()) return null
        val j = JSONObject(file.readText())
        val arr = j.getJSONArray("messages")
        val msgs = (0 until arr.length()).map {
            val m = arr.getJSONObject(it)
            Msg(m.getString("role"), m.getString("text"), m.optLong("at"))
        }
        Snapshot(j.getString("convId"), msgs)
    }.onFailure { Log.w("r1ptt", "history unreadable, starting fresh", it) }.getOrNull()

    private fun persist() {
        val snap = state.value
        io.execute {
            runCatching {
                val arr = JSONArray()
                snap.messages.forEach { arr.put(JSONObject().put("role", it.role).put("text", it.text).put("at", it.at)) }
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(JSONObject().put("convId", snap.convId).put("messages", arr).toString())
                tmp.renameTo(file)
            }.onFailure { Log.w("r1ptt", "could not save history", it) }
        }
    }

    private companion object {
        const val MAX = 100
        fun newId() = UUID.randomUUID().toString().take(8)
    }
}
