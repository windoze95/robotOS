package dev.r1ptt.hermes

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DeviceNotice(val id: String, val text: String, val createdAt: Long)

/** Passive, bounded Home notices. Adding a notice never wakes the display or changes a chat. */
class DeviceNotices(context: Context) {
    private val prefs = context.getSharedPreferences("device_notices", 0)
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<List<DeviceNotice>> = mutable

    @Synchronized fun add(text: String): DeviceNotice {
        require(text.isNotBlank() && text.length <= 2000)
        val notice = DeviceNotice(UUID.randomUUID().toString(), text.trim(), System.currentTimeMillis())
        save((mutable.value + notice).takeLast(20))
        return notice
    }

    @Synchronized fun dismiss(id: String) = save(mutable.value.filterNot { it.id == id })

    private fun read(): List<DeviceNotice> = runCatching {
        val items = JSONArray(prefs.getString("items", "[]"))
        (0 until items.length()).map { i -> items.getJSONObject(i).let {
            DeviceNotice(it.getString("id"), it.getString("text"), it.getLong("at"))
        } }.takeLast(20)
    }.getOrDefault(emptyList())

    private fun save(items: List<DeviceNotice>) {
        check(prefs.edit().putString("items", JSONArray(items.map {
            JSONObject().put("id", it.id).put("text", it.text).put("at", it.createdAt)
        }).toString()).commit()) { "The notice could not be saved." }
        mutable.value = items
    }
}
