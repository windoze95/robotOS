package dev.r1ptt.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.R

/**
 * robotOS's controls: black glass, warm white type, and the R1's orange for whatever is live or
 * primary. Sizes assume [DeviceScale]'s canvas. Nothing here animates or works in the background.
 */
class DeviceUi(val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private val scale: Float get() = ((context.applicationContext as? App)?.store?.value?.textSizeSp ?: 17).coerceIn(14, 26) / 17f
    fun color(id: Int) = context.getColor(id)

    /** Reading text in the system face. */
    fun text(value: String, size: Float = 17f, muted: Boolean = false): TextView = TextView(context).apply {
        text = value
        textSize = size * scale
        setTextColor(color(if (muted) R.color.dim else R.color.fg))
        setLineSpacing(dp(2).toFloat(), 1f)
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = true
    }

    /** A Space Grotesk headline. */
    fun title(value: String, size: Float = 22f, weight: Int = 700): TextView = text(value, size).apply {
        typeface = Fonts.display(context, weight)
        letterSpacing = -.01f
    }

    /** JetBrains Mono capitals: section labels, readouts and status. */
    fun mono(value: String, tint: Int = R.color.dim, size: Float = 12f, weight: Int = 700): TextView = TextView(context).apply {
        text = value
        isAllCaps = true
        typeface = Fonts.mono(context, weight)
        textSize = size * scale
        letterSpacing = .08f
        setTextColor(color(tint))
        gravity = Gravity.CENTER_VERTICAL
    }

    fun background(color: Int = R.color.field, radius: Int = 20, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(context.getColor(color))
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), context.getColor(R.color.outline))
    }

    /** [content] with a press ripple: orange on dark surfaces, a shadow on orange ones. */
    fun pressable(content: Drawable?, radius: Int, onAccent: Boolean = false) = RippleDrawable(
        ColorStateList.valueOf(if (onAccent) 0x38000000 else color(R.color.press)),
        content, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(radius).toFloat() },
    )

    fun dot(tint: Int, size: Int = 8) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(tint))
        setSize(dp(size), dp(size))
    }

    fun icon(res: Int, tint: Int = R.color.fg): Drawable = requireNotNull(context.getDrawable(res)).mutate().apply { setTint(color(tint)) }

    fun iconView(res: Int, tint: Int = R.color.fg) = ImageView(context).apply {
        setImageDrawable(icon(res, tint))
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A round 48 dp icon button. */
    fun iconButton(res: Int, description: String, tint: Int = R.color.fg, fill: Int = R.color.raised, action: () -> Unit) =
        ImageButton(context).apply {
            setImageDrawable(icon(res, tint))
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            background = pressable(background(fill, 24), 24, onAccent = fill == R.color.accent)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { action() }
        }

    private fun buttonBackground(primary: Boolean) = pressable(StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), background(R.color.field, 28).apply { setStroke(dp(1), color(R.color.outline)) })
        addState(intArrayOf(), background(if (primary) R.color.accent else R.color.raised, 28))
    }, 28, onAccent = primary)

    /** A 56 dp pill. Primary is orange with black type; [danger] marks irreversible device actions. */
    fun button(label: String, primary: Boolean = false, danger: Boolean = false, action: () -> Unit): Button = Button(context).apply {
        text = label
        contentDescription = label
        isAllCaps = false
        textSize = 17f * scale
        typeface = Fonts.display(context, if (primary) 700 else 600)
        letterSpacing = 0f
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.faint), color(when { primary -> R.color.on_accent; danger -> R.color.error; else -> R.color.fg })),
        ))
        background = buttonBackground(primary)
        stateListAnimator = null
        minHeight = dp(56)
        minimumHeight = dp(56)
        minWidth = dp(56)
        minimumWidth = dp(56)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        setOnClickListener { action() }
    }

    fun fieldBackground(radius: Int = 18) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), background(R.color.field, radius).apply { setStroke(dp(2), color(R.color.accent)) })
        addState(intArrayOf(), background(R.color.field, radius).apply { setStroke(dp(1), color(R.color.outline)) })
    }

    fun field(label: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(context).apply {
        hint = label
        contentDescription = label
        inputType = type
        setText(value)
        textSize = 17f * scale
        setTextColor(color(R.color.fg))
        setHintTextColor(color(R.color.dim))
        highlightColor = color(R.color.press)
        background = fieldBackground()
        textCursorDrawable = GradientDrawable().apply { setColor(color(R.color.accent)); setSize(dp(2), dp(20)) }
        minHeight = dp(56)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun switchTrack() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), background(R.color.accent, 14).apply { setSize(dp(52), dp(28)) })
        addState(intArrayOf(), background(R.color.raised, 14).apply { setStroke(dp(2), color(R.color.faint)); setSize(dp(52), dp(28)) })
    }

    private fun switchThumb() = StateListDrawable().apply {
        fun knob(tint: Int) = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(Color.TRANSPARENT); setSize(dp(26), dp(28)) },
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(tint)) },
        )).apply { setLayerSize(1, dp(20), dp(20)); setLayerGravity(1, Gravity.CENTER) }
        addState(intArrayOf(android.R.attr.state_checked), knob(R.color.on_accent))
        addState(intArrayOf(), knob(R.color.dim))
    }

    /** A setting that switches: the whole card toggles. */
    fun toggle(label: String, checked: Boolean, changed: ((Boolean) -> Unit)? = null): Switch = Switch(context).apply {
        text = label
        contentDescription = label
        textSize = 17f * scale
        typeface = Fonts.display(context, 500)
        setTextColor(color(R.color.fg))
        background = pressable(background(R.color.field, 20), 20)
        trackDrawable = switchTrack()
        thumbDrawable = switchThumb()
        trackTintList = null
        thumbTintList = null
        showText = false
        minHeight = dp(64)
        setPadding(dp(16), dp(10), dp(14), dp(10))
        switchPadding = dp(14)
        isChecked = checked
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        changed?.let { listener -> setOnCheckedChangeListener { _, on -> listener(on) } }
    }

    /** A small orange square and a mono caption that opens a group of settings. */
    fun section(label: String): TextView = mono(label).apply {
        if (label.isNotEmpty()) {
            setCompoundDrawablesRelative(GradientDrawable().apply {
                setColor(color(R.color.accent))
                cornerRadius = dp(1).toFloat()
                setBounds(0, 0, dp(7), dp(7))
            }, null, null, null)
            compoundDrawablePadding = dp(9)
        }
        setPadding(dp(4), dp(20), 0, dp(10))
    }

    /** Explanatory text under a control. */
    fun note(value: String) = text(value, 14f, muted = true).apply { setPadding(dp(4), dp(2), dp(4), dp(12)) }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
    }

    /** A surface card for grouping related content. */
    fun card() = column().apply {
        background = background(R.color.field, 20)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    /** An orange icon in a round well. */
    fun iconWell(res: Int, tint: Int = R.color.accent, fill: Int = R.color.raised) = ImageView(context).apply {
        setImageDrawable(icon(res, tint))
        scaleType = ImageView.ScaleType.CENTER
        background = background(fill, 20)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A tappable card: optional icon, title, summary and a chevron. */
    fun actionRow(title: String, summary: String = "", icon: Int? = null, action: () -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = if (summary.isEmpty()) title else "$title. $summary"
        background = pressable(background(R.color.field, 20), 20)
        minimumHeight = dp(68)
        setPadding(dp(14), dp(12), dp(10), dp(12))
        icon?.let { addView(iconWell(it), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }) }
        addView(column().apply {
            addView(text(title, 17f).apply { typeface = Fonts.display(context, 500) })
            if (summary.isNotEmpty()) addView(text(summary, 14f, muted = true).apply {
                setPadding(0, dp(2), 0, 0)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(iconView(R.drawable.ic_chevron_right, R.color.faint), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    /** A large square destination: icon above a short label. Primary is orange. */
    fun navTile(label: String, icon: Int, summary: String = "", primary: Boolean = false, action: () -> Unit) = column().apply {
        isClickable = true
        isFocusable = true
        contentDescription = if (summary.isEmpty()) label else "$label. $summary"
        background = pressable(background(if (primary) R.color.accent else R.color.field, 20).apply {
            if (!primary) setStroke(dp(1), color(R.color.outline))
        }, 20, onAccent = primary)
        setPadding(dp(14), dp(14), dp(10), dp(12))
        minimumHeight = dp(92)
        addView(iconView(icon, if (primary) R.color.on_accent else R.color.accent), LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(View(context), LinearLayout.LayoutParams(0, dp(10), 1f))
        addView(TextView(context).apply {
            text = label
            textSize = 16f
            typeface = Fonts.display(context, 600)
            setTextColor(color(if (primary) R.color.on_accent else R.color.fg))
            setLineSpacing(0f, .95f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        setOnClickListener { action() }
    }

    /** Tiles two to a row. */
    fun grid(tiles: List<View>, height: Int = 92) = column().apply {
        tiles.chunked(2).forEach { pair ->
            addView(LinearLayout(context).apply {
                isBaselineAligned = false
                pair.forEachIndexed { index, tile ->
                    addView(tile, LinearLayout.LayoutParams(0, dp(height), 1f).apply { if (index > 0) marginStart = dp(10) })
                }
                if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(10) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    /** A square on/off control for Quick controls. */
    fun tile(label: String, glyph: Int, checked: Boolean): CompoundButton = Tile(context, icon(glyph, R.color.accent)).apply {
        text = label
        contentDescription = label
        textSize = 15f * scale.coerceAtMost(1.15f)
        setLineSpacing(0f, .95f)
        isChecked = checked
        background = pressable(StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), background(R.color.accent, 20))
            addState(intArrayOf(), background(R.color.field, 20).apply { setStroke(dp(1), color(R.color.outline)) })
        }, 20)
        minHeight = dp(92)
    }

    data class Page(val root: LinearLayout, val body: LinearLayout, val scroll: ScrollView, val header: LinearLayout)

    fun page(title: String, onBack: () -> Unit): Page {
        val root = column().apply {
            setBackgroundColor(color(R.color.bg))
            isFocusableInTouchMode = true
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }
        header.addView(iconButton(R.drawable.ic_back, "Back", action = onBack), LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(context).apply {
            text = title
            typeface = Fonts.display(context, 700)
            setTextColor(color(R.color.fg))
            letterSpacing = -.01f
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(16, 25, 1, TypedValue.COMPLEX_UNIT_SP)
            setPadding(dp(14), 0, dp(4), 0)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)
        val body = column().apply { setPadding(dp(14), dp(8), dp(14), dp(28)) }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            verticalScrollbarThumbDrawable = background(R.color.accent, 2).apply { alpha = 150 }
            scrollBarSize = dp(3)
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(18))
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return Page(root, body, scroll, header)
    }

    data class Slider(val root: LinearLayout, val seek: SeekBar, val value: TextView)

    /** Persist once on release; keyboard/accessibility changes commit immediately. */
    fun slider(label: String, value: Int, max: Int, format: (Int) -> String = { "$it" }, icon: Int? = null,
               onPreview: (Int) -> Unit = {}, onCommit: (Int) -> Unit): Slider {
        val idle = background(R.color.field, 20)
        val selected = background(R.color.field, 20).apply { setStroke(dp(2), color(R.color.accent)) }
        val root = column().apply {
            background = idle
            setPadding(dp(16), dp(12), dp(10), dp(2))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, dp(6), 0) }
        icon?.let { head.addView(iconView(it, R.color.accent), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }) }
        head.addView(text(label, 16f).apply { typeface = Fonts.display(context, 500) }, LinearLayout.LayoutParams(0, -2, 1f))
        val readout = mono(format(value), R.color.accent, 15f, 800)
        head.addView(readout)
        root.addView(head)
        var dragging = false
        val seek = SeekBar(context).apply {
            this.max = max
            progress = value
            contentDescription = label
            minimumHeight = dp(48)
            isFocusableInTouchMode = true
            progressDrawable = LayerDrawable(arrayOf(
                background(R.color.raised, 5),
                ClipDrawable(background(R.color.accent, 5), Gravity.START, ClipDrawable.HORIZONTAL),
            )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
            thumb = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(color(R.color.fg)); setStroke(dp(4), color(R.color.accent)); setSize(dp(30), dp(30))
                })
                addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.fg)); setSize(dp(26), dp(26)) })
            }
            splitTrack = false
            minHeight = dp(10)
            maxHeight = dp(10)
            setPadding(dp(13), 0, dp(19), 0)
            background = null
            setOnFocusChangeListener { _, focused -> root.background = if (focused) selected else idle }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { dragging = true; bar.requestFocus() }
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    readout.text = format(progress)
                    if (fromUser) {
                        onPreview(progress)
                        if (!dragging) onCommit(progress)
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; onCommit(bar.progress) }
            })
        }
        root.addView(seek, LinearLayout.LayoutParams(-1, dp(46)))
        return Slider(root, seek, readout)
    }
}
