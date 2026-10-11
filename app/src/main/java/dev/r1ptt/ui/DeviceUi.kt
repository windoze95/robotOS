package dev.r1ptt.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.R

/** Compact native controls shared by the device screens. No animation or background work. */
class DeviceUi(val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private val scale: Float get() = ((context.applicationContext as? App)?.store?.value?.textSizeSp ?: 17).coerceIn(14, 26) / 17f

    fun text(value: String, size: Float = 17f, muted: Boolean = false): TextView = TextView(context).apply {
        text = value
        textSize = size * scale
        setTextColor(context.getColor(if (muted) R.color.dim else R.color.fg))
        setLineSpacing(dp(2).toFloat(), 1f)
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = true
    }

    fun background(color: Int = R.color.field, radius: Int = 8, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(context.getColor(color))
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), context.getColor(R.color.outline))
    }

    private fun touchBackground(primary: Boolean = false) = RippleDrawable(
        ColorStateList.valueOf(context.getColor(R.color.press)),
        background(if (primary) R.color.accent else R.color.field), null,
    )

    fun button(label: String, primary: Boolean = false, action: () -> Unit): Button = Button(context).apply {
        text = label
        contentDescription = label
        isAllCaps = false
        textSize = 15f * scale
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(context.getColor(R.color.dim), context.getColor(if (primary) R.color.bg else R.color.fg)),
        ))
        background = touchBackground(primary)
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = dp(48)
        minimumWidth = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        setOnClickListener { action() }
    }

    fun field(label: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(context).apply {
        hint = label
        contentDescription = label
        inputType = type
        setText(value)
        textSize = 16f * scale
        setTextColor(context.getColor(R.color.fg))
        setHintTextColor(context.getColor(R.color.dim))
        background = background(stroke = true)
        minHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    fun toggle(label: String, checked: Boolean, changed: ((Boolean) -> Unit)? = null): Switch = Switch(context).apply {
        text = label
        contentDescription = label
        textSize = 16f * scale
        setTextColor(context.getColor(R.color.fg))
        minHeight = dp(56)
        setPadding(0, dp(8), 0, dp(8))
        switchPadding = dp(12)
        isChecked = checked
        changed?.let { listener -> setOnCheckedChangeListener { _, on -> listener(on) } }
    }

    fun section(label: String) = text(label, 15f).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(20), 0, dp(8))
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
    }

    fun actionRow(title: String, summary: String = "", action: () -> Unit): LinearLayout = column().apply {
        isClickable = true
        isFocusable = true
        contentDescription = if (summary.isEmpty()) title else "$title. $summary"
        background = touchBackground()
        minimumHeight = dp(56)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(text(title, 17f))
        if (summary.isNotEmpty()) addView(text(summary, 14f, muted = true).apply { setPadding(0, dp(2), 0, 0) })
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    data class Page(val root: LinearLayout, val body: LinearLayout, val scroll: ScrollView)

    fun page(title: String, onBack: () -> Unit): Page {
        val root = column().apply {
            setBackgroundColor(context.getColor(R.color.bg))
            setPadding(dp(12), dp(4), dp(12), dp(8))
            isFocusableInTouchMode = true
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹", action = onBack).apply { contentDescription = "Back" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(text(title, 20f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        val body = column().apply { setPadding(0, dp(12), 0, dp(16)) }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return Page(root, body, scroll)
    }

    data class Slider(val root: LinearLayout, val seek: SeekBar, val value: TextView)

    /** Persist once on release; keyboard/accessibility changes commit immediately. */
    fun slider(label: String, value: Int, max: Int, format: (Int) -> String = { "$it" },
               onPreview: (Int) -> Unit = {}, onCommit: (Int) -> Unit): Slider {
        val root = column()
        val readout = text("$label  ${format(value)}", 15f)
        root.addView(readout)
        var dragging = false
        val seek = SeekBar(context).apply {
            this.max = max
            progress = value
            contentDescription = label
            minimumHeight = dp(48)
            isFocusableInTouchMode = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { dragging = true; bar.requestFocus() }
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    readout.text = "$label  ${format(progress)}"
                    if (fromUser) {
                        onPreview(progress)
                        if (!dragging) onCommit(progress)
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; onCommit(bar.progress) }
            })
        }
        root.addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        return Slider(root, seek, readout)
    }
}
