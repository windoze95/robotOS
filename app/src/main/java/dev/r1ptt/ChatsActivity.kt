package dev.r1ptt

import android.app.AlertDialog
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.widget.LinearLayout
import dev.r1ptt.data.History
import dev.r1ptt.ui.DeviceActivity
import dev.r1ptt.ui.Fonts
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
        snapshot.chats.forEach { chat -> body.addView(row(chat, chat.id == snapshot.convId)) }
    }

    /** Title, when it was last used, and the start of its latest message; the current chat is outlined. */
    private fun row(chat: History.Conversation, current: Boolean) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = "${chat.title}. ${if (current) "Current chat. " else ""}Long press for chat options"
        background = ui.pressable(ui.background(R.color.field, 20).apply { if (current) setStroke(ui.dp(2), getColor(R.color.accent)) }, 20)
        minimumHeight = ui.dp(76)
        setPadding(ui.dp(16), ui.dp(12), ui.dp(6), ui.dp(12))
        addView(ui.column().apply {
            addView(ui.title(chat.title, 17f, 600).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (current) {
                    setCompoundDrawablesRelative(ui.dot(R.color.accent, 9).apply { setBounds(0, 0, ui.dp(9), ui.dp(9)) }, null, null, null)
                    compoundDrawablePadding = ui.dp(8)
                }
            })
            val stamp = if (current) "Current" else DateUtils.getRelativeTimeSpanString(chat.updatedAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
            addView(ui.text("", 14f, muted = true).apply {
                text = SpannableStringBuilder().apply {
                    append(stamp.uppercase(), TypefaceSpan(Fonts.mono(this@ChatsActivity, 700)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(getColor(if (current) R.color.accent else R.color.faint)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(.8f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("  ")
                    append(chat.preview.ifEmpty { "No messages yet" })
                }
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, ui.dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.iconButton(R.drawable.ic_more, "Options for ${chat.title}", R.color.dim, R.color.field) { actions(chat) },
            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginStart = ui.dp(2) })
        setOnClickListener { app.history.select(chat.id); finish() }
        setOnLongClickListener { actions(chat); true }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }
    }

    private fun actions(chat: History.Conversation) {
        showDeviceDialog(AlertDialog.Builder(this).setTitle(chat.title).setItems(arrayOf("Rename", "Remove from this R1")) { _, which ->
            if (which == 0) {
                val name = ui.field("Chat name", chat.title).apply { setSelection(text.length) }
                val frame = LinearLayout(this).apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0); addView(name, LinearLayout.LayoutParams(-1, -2)) }
                showDeviceDialog(AlertDialog.Builder(this).setTitle("Rename chat").setView(frame)
                    .setPositiveButton("Save") { _, _ -> app.history.rename(chat.id, name.text.toString()) }
                    .setNegativeButton("Cancel", null))
            } else {
                val run = app.hermesRuns.state.value.run
                if (app.turns.busy || (run != null && run.sessionId.removePrefix("r1ptt-") == chat.id && (run.active || !run.delivered))) {
                    showToast("Finish or stop the current task before removing a chat")
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
