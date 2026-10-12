package dev.r1ptt.ui

import android.content.Context
import android.content.Intent
import dev.r1ptt.ChatsActivity
import dev.r1ptt.HomeActivity
import dev.r1ptt.QuickControlsActivity
import dev.r1ptt.SettingsActivity
import dev.r1ptt.hermes.HermesActivity
import dev.r1ptt.messages.MessagesActivity
import dev.r1ptt.update.UpdateActivity
import dev.r1ptt.workspace.HubSettingsActivity
import dev.r1ptt.workspace.WorkspaceActivity

/** Explicit robotOS destinations: no package names, arbitrary intents or automatic actions. */
internal object AppDestination {
    val names = listOf("home", "workspace", "notes", "prompt_builder", "chats", "messages", "hermes",
        "settings", "quick_controls", "updates", "host_devices")

    fun collection(app: String): String? = when (app) {
        "notes" -> "notes"
        "prompt_builder" -> "prompts"
        else -> null
    }

    fun intent(context: Context, app: String, itemId: String? = null, deviceToken: String? = null): Intent = when (app) {
        "home" -> Intent(context, HomeActivity::class.java)
        "workspace" -> Intent(context, WorkspaceActivity::class.java)
        "notes" -> WorkspaceActivity.notesIntent(context, itemId)
        "prompt_builder" -> if (itemId == null) WorkspaceActivity.promptsIntent(context) else WorkspaceActivity.savedPromptIntent(context, itemId)
        "chats" -> Intent(context, ChatsActivity::class.java)
        "messages" -> Intent(context, MessagesActivity::class.java)
        "hermes" -> Intent(context, HermesActivity::class.java)
        "settings" -> Intent(context, SettingsActivity::class.java)
        "quick_controls" -> Intent(context, QuickControlsActivity::class.java)
        "updates" -> Intent(context, UpdateActivity::class.java)
        "host_devices" -> Intent(context, HubSettingsActivity::class.java)
        else -> throw IllegalArgumentException("Choose a supported robotOS app")
    }.also { if (deviceToken != null && collection(app) != null) WorkspaceActivity.authorizeNavigation(it, deviceToken) }
}
