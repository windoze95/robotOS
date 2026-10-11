package dev.r1ptt.workspace

import android.content.Context
import dev.r1ptt.data.AtomicConfigFile
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.data.Sealed
import org.json.JSONObject
import java.io.File

/** Separate enrollment credentials survive clearing the shared workspace. No token is rendered. */
class HubIdentity(context: Context) {
    private val persistence = EncryptedConfigPersistence(JSONObject(),
        AtomicConfigFile(File(context.filesDir, "workspace-identity.enc")), Sealed,
        object : ConfigCodec<JSONObject> {
            override fun encode(value: JSONObject) = value.toString()
            override fun decode(plain: String) = JSONObject(plain)
        })
    val value: JSONObject get() = persistence.value.copyJson()
    val paired: Boolean get() = value.optString("device_id").isNotBlank()
    val error: String? get() = persistence.loadError?.message
    fun update(transform: (JSONObject) -> Unit) { persistence.update { it.copyJson().apply(transform) } }
    fun allowed(name: String): Boolean = value.optJSONObject("permissions")?.optBoolean(name) == true
    val quarantined: Boolean get() = value.optBoolean("lost") || value.optBoolean("revoked") || value.optBoolean("local_workspace_cleared")
    fun permission(name: String, enabled: Boolean) = update { root ->
        root.put("permissions", (root.optJSONObject("permissions") ?: JSONObject()).put(name, enabled))
    }
}
