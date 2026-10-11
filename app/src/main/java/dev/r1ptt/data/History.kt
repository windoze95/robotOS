package dev.r1ptt.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class Msg(val role: String, val text: String, val at: Long = System.currentTimeMillis(), val deliveryKey: String = "") {
    companion object { const val USER = "user"; const val ASSISTANT = "assistant" }
}

/** Local conversations. SQLite commits each snapshot atomically; legacy history remains untouched. */
class History(dir: File) {
    data class Conversation(val id: String, val title: String, val preview: String, val updatedAt: Long)
    data class Snapshot(val convId: String, val messages: List<Msg>, val draft: String = "", val title: String = "New chat",
                        val scrollY: Int = 0, val followLatest: Boolean = true, val chats: List<Conversation> = emptyList())
    private data class Record(val id: String, val title: String = "New chat", val messages: List<Msg> = emptyList(),
                              val draft: String = "", val updatedAt: Long = System.currentTimeMillis(),
                              val scrollY: Int = 0, val followLatest: Boolean = true)
    private val io = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private class PendingWrite { var future: ScheduledFuture<*>? = null }
    private val pending = mutableMapOf<String, PendingWrite>()
    private val records = linkedMapOf<String, Record>()
    private val dirty = mutableSetOf<String>()
    private var active = ""
    private var database: SQLiteDatabase? = null
    private val failures = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = failures
    private val state: MutableStateFlow<Snapshot>

    init {
        try {
            dir.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(File(dir, "conversations.db"), null)
            database = db
            db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY, title TEXT NOT NULL, messages TEXT NOT NULL, draft TEXT NOT NULL, updated INTEGER NOT NULL, scroll_y INTEGER NOT NULL, follow_latest INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS history_meta (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.beginTransaction()
            try {
                if (db.version == 0) {
                    val legacy = File(dir, "history.json")
                    val first = if (legacy.exists()) {
                        val old = JSONObject(legacy.readText())
                        Record(old.getString("convId"), messages = parseMessages(old.getJSONArray("messages"))).let {
                            it.copy(title = titleFor(it.messages))
                        }
                    } else Record(newId())
                    write(db, first)
                    setActive(db, first.id)
                    db.version = 1
                }
                require(db.version == 1) { "Unsupported conversation database" }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.rawQuery("SELECT id,title,messages,draft,updated,scroll_y,follow_latest FROM conversations ORDER BY updated DESC", null).use { c ->
                while (c.moveToNext()) {
                    val record = Record(c.getString(0), c.getString(1), parseMessages(JSONArray(c.getString(2))),
                        c.getString(3), c.getLong(4), c.getInt(5), c.getInt(6) != 0)
                    records[record.id] = record
                }
            }
            db.rawQuery("SELECT value FROM history_meta WHERE name='active'", null).use { if (it.moveToFirst()) active = it.getString(0) }
        } catch (_: Exception) {
            failures.value = "Saved chats could not be opened. Existing history is preserved; restart to retry."
        }
        if (records.isEmpty()) Record(newId()).let { records[it.id] = it }
        if (active !in records) active = records.keys.first()
        state = MutableStateFlow(snapshot())
    }

    val flow: StateFlow<Snapshot> = state
    val convId: String get() = state.value.convId
    val messages: List<Msg> get() = state.value.messages
    val draft: String get() = state.value.draft

    /** Reconcile the host transcript while preserving this device's unsent draft and scroll. */
    @Synchronized fun importShared(id: String, title: String, messages: List<Msg>) {
        require(id.startsWith("shared-"))
        val old = records[id] ?: Record(id)
        val next = old.copy(title = title, messages = messages.takeLast(MAX_MESSAGES),
            updatedAt = messages.maxOfOrNull { it.at } ?: old.updatedAt)
        if (records[id] == next) return
        records[id] = next
        publish(); persist(id)
    }

    @Synchronized fun add(role: String, text: String, conversation: String = convId, deliveryKey: String = "") {
        val old = records[conversation] ?: return
        if (deliveryKey.isNotEmpty() && old.messages.any { it.deliveryKey == deliveryKey }) return
        val messages = (old.messages + Msg(role, text, deliveryKey = deliveryKey)).takeLast(MAX_MESSAGES)
        records[conversation] = old.copy(messages = messages, updatedAt = System.currentTimeMillis(),
            title = if (old.title == "New chat") titleFor(messages) else old.title)
        publish(); persist(conversation)
    }

    @Synchronized fun newConversation(): String {
        val next = Record(newId())
        records[next.id] = next
        active = next.id
        publish(); persist(next.id)
        return next.id
    }

    @Synchronized fun select(id: String): Boolean {
        if (id !in records) return false
        active = id
        publish(); persist(id)
        return true
    }

    @Synchronized fun rename(id: String, title: String) {
        val old = records[id] ?: return
        records[id] = old.copy(title = title.trim().take(80).ifEmpty { titleFor(old.messages) })
        publish(); persist(id)
    }

    @Synchronized fun remove(id: String) {
        if (records.remove(id) == null) return
        pending.remove(id)?.future?.cancel(false)
        dirty.remove(id)
        if (records.isEmpty()) Record(newId()).let { records[it.id] = it }
        if (active == id) active = records.values.maxBy { it.updatedAt }.id
        publish()
        io.execute {
            if (failures.value != null) return@execute
            runCatching {
                database?.let { db ->
                    val current = synchronized(this) { records.getValue(active) }
                    db.beginTransaction()
                    try { db.delete("conversations", "id=?", arrayOf(id)); write(db, current); setActive(db, current.id); db.setTransactionSuccessful() }
                    finally { db.endTransaction() }
                }
            }.onFailure { failures.value = "Could not save chats. Existing saved history is preserved." }
        }
    }

    @Synchronized fun saveDraft(text: String, conversation: String = convId) {
        val old = records[conversation] ?: return
        if (old.draft == text) return
        records[conversation] = old.copy(draft = text)
        publish(); persist(conversation, 300)
    }

    /** Adds words to the end of a chat's draft, after a space if needed. */
    @Synchronized fun appendDraft(text: String, conversation: String = convId) {
        val old = records[conversation]?.draft ?: return
        saveDraft(if (old.isEmpty() || old.last().isWhitespace()) old + text else "$old $text", conversation)
    }

    @Synchronized fun confirmDraft(text: String, conversation: String = convId) {
        val old = records[conversation] ?: return
        if (old.draft.trim() != text.trim()) return
        records[conversation] = old.copy(draft = "")
        publish(); persist(conversation)
    }

    @Synchronized fun savePosition(y: Int, following: Boolean, conversation: String = convId) {
        val old = records[conversation] ?: return
        records[conversation] = old.copy(scrollY = y.coerceAtLeast(0), followLatest = following)
        // Avoid emitting a transcript snapshot for each scroll pixel.
        persist(conversation, 300)
    }

    /** Flush a pending draft on pause without blocking the UI on disk. */
    @Synchronized fun flush() { publish(); dirty.toList().forEach { persist(it) } }

    private fun snapshot(): Snapshot {
        val current = records.getValue(active)
        val chats = records.values.sortedByDescending { it.updatedAt }.map {
            Conversation(it.id, it.title, it.messages.lastOrNull()?.text.orEmpty().replace('\n', ' ').take(100), it.updatedAt)
        }
        return Snapshot(current.id, current.messages, current.draft, current.title, current.scrollY, current.followLatest, chats)
    }
    private fun publish() { state.value = snapshot() }

    private fun persist(id: String, delay: Long = 0) {
        dirty.add(id)
        // Bound the save delay from the first edit; continuous scrolling cannot postpone a draft.
        if (delay > 0 && pending[id]?.future?.isDone == false) return
        pending.remove(id)?.future?.cancel(false)
        val job = PendingWrite()
        pending[id] = job
        job.future = io.schedule({
            try {
                if (failures.value != null) return@schedule
                val (record, selected) = synchronized(this) {
                    if (pending[id] !== job) return@schedule
                    dirty.remove(id)
                    records[id] to active
                }
                if (record == null) return@schedule
                runCatching {
                    database?.let { db ->
                        db.beginTransaction()
                        try { write(db, record); setActive(db, selected); db.setTransactionSuccessful() }
                        finally { db.endTransaction() }
                    }
                }.onFailure { failures.value = "Could not save chats. Existing saved history is preserved." }
            } finally {
                synchronized(this) {
                    // A delayed edit can arrive after this job captured its snapshot. Save it
                    // next, unless an immediate write or deletion already replaced this job.
                    if (pending[id] === job) {
                        pending.remove(id)
                        if (id in dirty && id in records && failures.value == null) persist(id)
                    }
                }
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    /** Used by deterministic persistence tests and orderly shutdown, never for UI events. */
    fun awaitWrites() {
        // This transaction cannot be cancelled/rescheduled by a later scroll event. A recovered run
        // may only be marked delivered after this barrier succeeds.
        io.submit {
            check(failures.value == null) { failures.value.orEmpty() }
            val (saved, selected) = synchronized(this) { records.values.toList() to active }
            val db = checkNotNull(database)
            try {
                db.beginTransaction()
                try { saved.forEach { write(db, it) }; setActive(db, selected); db.setTransactionSuccessful() }
                finally { db.endTransaction() }
            } catch (e: Exception) {
                failures.value = "Could not save chats. Existing saved history is preserved."
                throw e
            }
        }.get(5, TimeUnit.SECONDS)
    }
    fun close() {
        try { if (failures.value == null) { flush(); awaitWrites() } }
        finally { io.shutdownNow(); database?.close() }
    }

    private fun write(db: SQLiteDatabase, record: Record) {
        val messages = JSONArray()
        record.messages.forEach { messages.put(JSONObject().put("role", it.role).put("text", it.text).put("at", it.at).put("deliveryKey", it.deliveryKey)) }
        val values = ContentValues().apply {
            put("id", record.id); put("title", record.title); put("messages", messages.toString()); put("draft", record.draft)
            put("updated", record.updatedAt); put("scroll_y", record.scrollY); put("follow_latest", if (record.followLatest) 1 else 0)
        }
        db.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    private fun setActive(db: SQLiteDatabase, id: String) {
        db.execSQL("INSERT OR REPLACE INTO history_meta(name,value) VALUES('active',?)", arrayOf(id))
    }
    private fun parseMessages(array: JSONArray) = (maxOf(0, array.length() - MAX_MESSAGES) until array.length()).map {
        array.getJSONObject(it).let { m -> Msg(m.getString("role"), m.getString("text"), m.optLong("at"), m.optString("deliveryKey")) }
    }
    private fun titleFor(messages: List<Msg>) = messages.firstOrNull { it.role == Msg.USER }?.text?.replace('\n', ' ')?.take(48) ?: "New chat"
    private fun newId() = UUID.randomUUID().toString()
    private companion object { const val MAX_MESSAGES = 100 }
}
