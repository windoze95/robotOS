package dev.r1ptt.workspace

import dev.r1ptt.App
import org.json.JSONObject

/** An unsent chat points at its saved prompt. Only Send freezes text into a submission receipt. */
object PromptAttachment {
    fun open(app: App, prompt: JSONObject): String {
        check(!app.workspace.blocked) { "Workspace access is restricted" }
        check(app.workspace.identity.paired) { "Pair a host before opening a prompt in chat" }
        check(app.history.errors.value == null) { "Chat storage needs attention before opening another chat" }
        check(!app.turns.busy) { "Finish the current conversation before opening another chat" }
        validate(prompt)
        val conversation = "shared-${newId()}"
        app.workspace.store.put("chat_attachments", JSONObject().put("id", conversation).put("prompt_id", prompt.getString("id")))
        app.history.importShared(conversation, prompt.optString("title").ifBlank { "New chat" }, emptyList())
        app.history.select(conversation)
        return conversation
    }

    fun source(app: App, conversation: String = app.history.convId): JSONObject? =
        app.workspace.store.item("chat_attachments", conversation)?.let { attachment ->
            app.workspace.store.item("prompts", attachment.getString("prompt_id"))
        }

    fun attached(app: App, conversation: String = app.history.convId) =
        app.workspace.store.item("chat_attachments", conversation) != null

    data class Delivery(val text: String, val prompt: JSONObject)

    /** An uncertain Send always retries its frozen input, even if the template changes elsewhere. */
    fun delivery(app: App, conversation: String = app.history.convId): Delivery {
        check(!app.workspace.blocked) { "Workspace access is restricted" }
        val pending = app.workspace.store.item("shared_chat_links", conversation)
        pending?.optJSONObject("prompt")?.let { return Delivery(pending.getString("text"), it) }
        val prompt = source(app, conversation) ?: error("This prompt is unavailable. Sync before sending it.")
        check(app.workspace.store.item("drafts", prompt.getString("id")) == null) { "Save your edits in Prompt Builder before sending" }
        validate(prompt)
        return Delivery(WorkspaceClient.preview(prompt), reference(prompt))
    }

    fun reference(prompt: JSONObject) = JSONObject().put("id", prompt.getString("id"))
        .put("revision", prompt.getLong("revision")).put("title", prompt.optString("title"))

    fun validate(prompt: JSONObject) {
        check(!prompt.getString("id").startsWith("shared-")) { "Choose a saved prompt" }
        check(WorkspaceClient.preview(prompt).isNotBlank()) { "Add an instruction or note first" }
        check(prompt.getJSONArray("blocks").objects().none { it.optString("kind") == "audio" && it.optString("text").isBlank() }) {
            "Add a transcript for each recording before opening a chat"
        }
    }

    fun accepted(app: App, conversation: String) = app.workspace.store.remove("chat_attachments", conversation)
}
