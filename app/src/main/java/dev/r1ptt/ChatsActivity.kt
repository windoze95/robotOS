package dev.r1ptt

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import dev.r1ptt.data.History
import dev.r1ptt.ui.DeviceActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Local R1 conversations; removing one never implies deleting the Hermes server's history. */
class ChatsActivity : DeviceActivity() {
    private val app get() = application as App
    private lateinit var body: LinearLayout
    private var observations: kotlinx.coroutines.CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = ui.page("Chats") { finish() }
        body = page.body
        wheelScrollTarget = page.scroll
        setContentView(page.root)
    }
    override fun onResume() {
        super.onResume()
        observations = MainScope().also { scope -> scope.launch { app.history.flow.collect(::render) } }
    }
    override fun onPause() { observations?.cancel(); observations = null; super.onPause() }

    private fun render(snapshot: History.Snapshot) {
        body.removeAllViews()
        body.addView(ui.button("New chat", primary = true) { app.turns.newConversation(); finish() })
        body.addView(ui.section("On this R1"))
        snapshot.chats.forEach { chat ->
            val title = chat.title + if (chat.id == snapshot.convId) "  •" else ""
            val row = ui.actionRow(title, chat.preview.ifEmpty { "No messages yet" }) {
                app.history.select(chat.id); finish()
            }
            row.setOnLongClickListener { actions(chat); true }
            row.contentDescription = "${chat.title}. ${if (chat.id == snapshot.convId) "Current chat. " else ""}Long press for chat options"
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(row, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(ui.button("⋮") { actions(chat) }.apply { contentDescription = "Options for ${chat.title}" },
                LinearLayout.LayoutParams(ui.dp(48), ui.dp(56)).apply { marginStart = ui.dp(8) })
            body.addView(line)

        }
    }

    private fun actions(chat: History.Conversation) {
        showDeviceDialog(AlertDialog.Builder(this).setTitle(chat.title).setItems(arrayOf("Rename", "Remove from this R1")) { _, which ->
            if (which == 0) {
                val name = ui.field("Chat name", chat.title)
                showDeviceDialog(AlertDialog.Builder(this).setTitle("Rename chat").setView(name)
                    .setPositiveButton("Save") { _, _ -> app.history.rename(chat.id, name.text.toString()) }
                    .setNegativeButton("Cancel", null))
            } else {
                val run = app.hermesRuns.state.value.run
                if (app.turns.busy || (run != null && run.sessionId.removePrefix("r1ptt-") == chat.id && (run.active || !run.delivered))) {
                    Toast.makeText(this, "Finish or stop the current task before removing a chat", Toast.LENGTH_LONG).show()
                    return@setItems
                }
                showDeviceDialog(AlertDialog.Builder(this).setTitle("Remove this chat?")
                    .setMessage("The draft and saved messages will be removed from this R1. Hermes keeps its server history.")
                    .setPositiveButton("Remove") { _, _ -> app.history.remove(chat.id) }
                    .setNegativeButton("Cancel", null))
            }
        })
    }
}
