package dev.r1ptt.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.r1ptt.R
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The two bundled faces (SIL OFL 1.1, see licenses/): Space Grotesk for display, JetBrains Mono for labels. */
object Fonts {
    private val cache = HashMap<Long, Typeface>()
    fun display(context: Context, weight: Int = 600): Typeface = get(context, R.font.display, weight)
    fun mono(context: Context, weight: Int = 700): Typeface = get(context, R.font.mono, weight)

    @Synchronized private fun get(context: Context, family: Int, weight: Int): Typeface =
        cache.getOrPut(family.toLong() shl 16 or weight.toLong()) {
            val base = runCatching { context.resources.getFont(family) }.getOrNull() ?: Typeface.DEFAULT
            Typeface.create(base, weight, false)
        }
}

private fun View.px(dp: Float) = dp * resources.displayMetrics.density

/** Microphone level history as rounded bars, newest last. Redraws only when a level is pushed. */
class LevelBars @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var levels = FloatArray(28)
    private var head = 0
    private val bar = RectF()
    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }
    var bars: Int
        get() = levels.size
        set(value) { levels = FloatArray(value.coerceAtLeast(2)); head = 0; invalidate() }

    fun push(level: Float) {
        levels[head] = (sqrt(level.coerceIn(0f, 1f)) * 1.7f).coerceAtMost(1f)
        head = (head + 1) % levels.size
        invalidate()
    }

    fun reset() { levels.fill(0f); head = 0; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val n = levels.size
        val step = width / (n - .35f)
        val w = step * .62f
        val mid = height / 2f
        for (i in 0 until n) {
            val h = max(w, levels[(head + i) % n] * height)
            val x = i * step
            bar.set(x, mid - h / 2, x + w, mid + h / 2)
            canvas.drawRoundRect(bar, w / 2, w / 2, paint)
        }
    }
}

/**
 * robotOS's mark (the host console's R1 outline) centered on black, glowing while the R1 checks in
 * with its host. It stays until the check passes or it is swiped away, which [onSwiped] answers
 * with the recovery page. Moves only while it is actually shown.
 */
class LogoGlow(context: Context) : View(context) {
    /** Called once a swipe (or an accessibility click) has sent the cover away. */
    var onSwiped: (() -> Unit)? = null
    private val accent = context.getColor(R.color.accent)
    private val mark = requireNotNull(context.getDrawable(R.drawable.robot_mark)).mutate()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = accent }
    private var glow: Bitmap? = null
    private var glowX = 0f
    private var glowY = 0f
    private var reach = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        contentDescription = "robotOS is checking in with your host. Swipe away for recovery options."
        isClickable = true // touches never reach the hidden screen underneath
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val markH = (min(w, h) * .42f).toInt().coerceAtLeast(1)
        val markW = markH * 42 / 48
        val left = (w - markW) / 2
        val top = (h - markH) / 2
        // The glow is the mark's own silhouette, blurred, so the logo itself shines.
        val pad = markH / 3
        val source = Bitmap.createBitmap(markW + 2 * pad, markH + 2 * pad, Bitmap.Config.ARGB_8888)
        mark.setBounds(pad, pad, pad + markW, pad + markH)
        mark.draw(Canvas(source))
        val offset = IntArray(2)
        glow?.recycle()
        glow = source.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(markH * .09f, BlurMaskFilter.Blur.NORMAL) }, offset)
        source.recycle()
        glowX = (left - pad + offset[0]).toFloat()
        glowY = (top - pad + offset[1]).toFloat()
        mark.setBounds(left, top, left + markW, top + markH)
        reach = markH * 1.35f
        fun tint(alpha: Int) = (accent and 0xFFFFFF) or (alpha shl 24)
        halo.shader = RadialGradient(w / 2f, h / 2f, reach, intArrayOf(tint(95), tint(30), Color.TRANSPARENT),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !animator.isStarted) animator.start() else if (!isVisible) animator.cancel()
    }

    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val t = if (animator.isRunning) animator.animatedValue as Float else 0f
        // Starts dim and swells, so the glow rises as the cover appears.
        drawLogo(canvas, (sin(2 * PI * (t - .25f)) * .5 + .5).toFloat())
    }

    private fun drawLogo(canvas: Canvas, glow: Float) {
        val cx = width / 2f
        val cy = height / 2f
        halo.alpha = (70 + 185 * glow).toInt()
        val scale = .88f + .16f * glow
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawCircle(cx, cy, reach, halo)
        canvas.restore()
        shine.alpha = (110 + 145 * glow).toInt()
        this.glow?.let { canvas.drawBitmap(it, glowX, glowY, shine) }
        mark.draw(canvas)
    }

    /**
     * Follows the finger; far enough and it leaves the way it was swiped, else it settles back. A
     * tap does nothing (only a deliberate swipe leaves); accessibility uses [performClick].
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dx = e.rawX - downX
        val dy = e.rawY - downY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                tracking = true
                dragging = false
                animate().cancel()
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (!dragging && hypot(dx, dy) > slop) dragging = true
                if (dragging) {
                    translationX = dx
                    translationY = dy
                    alpha = 1f - .5f * min(hypot(dx, dy) / throwDistance(), 1f)
                }
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false
                if (hypot(dx, dy) >= throwDistance()) throwAway(dx, dy)
                else animate().translationX(0f).translationY(0f).alpha(1f).setDuration(160).start()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> if (tracking) {
                tracking = false
                animate().translationX(0f).translationY(0f).alpha(1f).setDuration(160).start()
            }
        }
        return true
    }

    /** TalkBack's double tap stands in for the swipe. */
    override fun performClick(): Boolean {
        super.performClick()
        onSwiped?.invoke()
        return true
    }

    /** How far a swipe must go: about a third of the screen. */
    private fun throwDistance() = max(min(width, height) * .3f, slop * 4f)

    private fun throwAway(dx: Float, dy: Float) {
        val far = hypot(width.toFloat(), height.toFloat()) / hypot(dx, dy)
        animate().translationX(dx * far).translationY(dy * far).alpha(0f).setDuration(200)
            .withEndAction { onSwiped?.invoke() }.start()
    }

    companion object {
        /** The cover at full glow as a picture, for Android's lock screen ([LockScreenLook]). */
        fun still(context: Context, width: Int, height: Int): Bitmap {
            val logo = LogoGlow(context)
            logo.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            logo.layout(0, 0, width, height)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { picture ->
                val canvas = Canvas(picture)
                canvas.drawColor(context.getColor(R.color.bg))
                logo.drawLogo(canvas, 1f)
            }
        }
    }
}

/** Three dots for "working" or four bars for "speaking". Moves only while it is actually shown. */
class PulseView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Mode { DOTS, BARS }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = RectF()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    var mode = Mode.DOTS
        set(value) { field = value; invalidate() }
    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    init { paint.color = context.getColor(R.color.accent) }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !animator.isStarted) animator.start() else if (!isVisible) animator.cancel()
    }

    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val t = if (animator.isRunning) animator.animatedValue as Float else .25f
        if (mode == Mode.DOTS) {
            val r = min(height / 2f, width / 9f)
            val gap = (width - 6 * r) / 2f
            for (i in 0..2) {
                val wave = sin(PI * ((t - i * .16f + 1f) % 1f)).toFloat().let { it * it }
                paint.alpha = (90 + 165 * wave).toInt()
                canvas.drawCircle(r + i * (2 * r + gap), height / 2f, r * (.62f + .38f * wave), paint)
            }
            paint.alpha = 255
        } else {
            val w = width / 6.5f
            for (i in 0..3) {
                val wave = (sin(2 * PI * (t + i * .27f)) * .5 + .5).toFloat()
                val h = height * (.28f + .72f * wave)
                val x = i * w * 1.8f
                shape.set(x, (height - h) / 2, x + w, (height + h) / 2)
                canvas.drawRoundRect(shape, w / 2, w / 2, paint)
            }
        }
    }
}

/** Battery charge as a filled outline with the percentage printed inside it. */
class BatteryGlyph(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val fg = context.getColor(R.color.fg)
    private val ink = context.getColor(R.color.on_accent)
    private val charge = context.getColor(R.color.accent)
    private val low = context.getColor(R.color.error)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.mono(context, 800)
        textAlign = Paint.Align.CENTER
        textSize = 11.5f * density
    }
    private val rect = RectF()
    var percent = -1
    var charging = false

    override fun getIntrinsicWidth() = (38 * density).toInt()
    override fun getIntrinsicHeight() = (20 * density).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val nub = 2.6f * density
        val stroke = 1.5f * density
        val radius = 6f * density
        val right = b.right - nub - 1.2f * density
        val inset = stroke + 1.6f * density
        val level = percent.coerceIn(0, 100)
        val fillEnd = b.left + inset + (right - b.left - 2 * inset) * level / 100f
        paint.style = Paint.Style.FILL
        paint.color = when { charging -> charge; percent in 0..15 -> low; else -> fg }
        if (percent >= 0) canvas.drawRoundRect(b.left + inset, b.top + inset, fillEnd, b.bottom - inset, radius - inset, radius - inset, paint)
        val label = if (percent < 0) "–" else "$percent"
        val baseline = b.exactCenterY() - (digits.descent() + digits.ascent()) / 2
        val middle = (b.left + right) / 2
        canvas.save()
        canvas.clipRect(fillEnd, b.top.toFloat(), right, b.bottom.toFloat())
        digits.color = fg
        canvas.drawText(label, middle, baseline, digits)
        canvas.restore()
        canvas.save()
        canvas.clipRect(b.left.toFloat(), b.top.toFloat(), fillEnd, b.bottom.toFloat())
        digits.color = ink
        canvas.drawText(label, middle, baseline, digits)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = fg
        paint.alpha = 150
        rect.set(b.left + stroke / 2, b.top + stroke / 2, right - stroke / 2, b.bottom - stroke / 2)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        rect.set(b.right - nub, b.top + b.height() * .34f, b.right.toFloat(), b.bottom - b.height() * .34f)
        canvas.drawRoundRect(rect, nub / 2, nub / 2, paint)
        paint.alpha = 255
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A vertical volume readout at the screen's right edge, beside the R1's wheel. Fades by itself. */
class VolumeHud(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = RectF()
    private val icon = context.getDrawable(R.drawable.ic_speaker)!!.mutate()
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.mono(context, 800)
        textAlign = Paint.Align.CENTER
        textSize = px(14f)
    }
    private val hide = Runnable { animate().alpha(0f).setDuration(220).withEndAction { visibility = GONE } }
    private var level = 0
    private var max = 1

    init { alpha = 0f; visibility = GONE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(level: Int, max: Int) {
        this.level = level
        this.max = max.coerceAtLeast(1)
        invalidate()
        removeCallbacks(hide)
        visibility = VISIBLE
        animate().cancel()
        animate().alpha(1f).setDuration(120).start()
        postDelayed(hide, 1400)
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        paint.color = context.getColor(R.color.raised)
        shape.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(shape, r, r, paint)
        val inset = px(5f)
        val top = px(34f)
        val track = height - top - inset
        val fill = track * level / max
        paint.color = context.getColor(R.color.accent)
        if (fill > 0) {
            shape.set(inset, height - inset - max(fill, width - 2 * inset), width - inset, height - inset)
            canvas.drawRoundRect(shape, r - inset, r - inset, paint)
        }
        number.color = context.getColor(R.color.fg)
        canvas.drawText("$level", width / 2f, px(24f), number)
        val size = px(22f).toInt()
        val left = (width - size) / 2
        val bottom = height - inset.toInt() - px(8f).toInt()
        icon.setBounds(left, bottom - size, left + size, bottom)
        icon.setTint(context.getColor(if (fill >= px(30f)) R.color.on_accent else R.color.fg))
        icon.draw(canvas)
    }

    override fun onDetachedFromWindow() { removeCallbacks(hide); super.onDetachedFromWindow() }
}

/** A square toggle: orange when on. It is a [CheckBox] so accessibility announces its state. */
@SuppressLint("ViewConstructor") // built in code with its icon; never inflated
class Tile(context: Context, private val glyph: Drawable) : CheckBox(context) {
    private val state = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.mono(context, 800)
        textSize = px(11f)
        textAlign = Paint.Align.RIGHT
        letterSpacing = .08f
    }

    init {
        buttonDrawable = null
        gravity = Gravity.BOTTOM or Gravity.START
        typeface = Fonts.display(context, 600)
        setPadding(px(14f).toInt(), px(46f).toInt(), px(12f).toInt(), px(12f).toInt())
        refreshColors()
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        @Suppress("SENSELESS_COMPARISON") if (state != null) refreshColors()
    }

    private fun refreshColors() {
        val on = isChecked
        setTextColor(context.getColor(if (on) R.color.on_accent else R.color.fg))
        glyph.setTint(context.getColor(if (on) R.color.on_accent else R.color.accent))
        state.color = context.getColor(if (on) R.color.on_accent else R.color.faint)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = px(26f).toInt()
        val left = paddingStart
        val top = px(14f).toInt()
        glyph.setBounds(left, top, left + size, top + size)
        glyph.draw(canvas)
        canvas.drawText(if (isChecked) "ON" else "OFF", width - px(12f), top + px(11f), state)
    }
}

/** A rule ending in an arrowhead at the screen edge: "the button is over here". */
class EdgeArrow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.accent)
    }
    private val head = Path()
    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        paint.strokeWidth = px(3f)
        val y = height / 2f
        val end = width - px(3f)
        val size = min(height / 2f - px(2f), px(9f))
        canvas.drawLine(px(2f), y, end, y, paint)
        head.reset()
        head.moveTo(end - size, y - size)
        head.lineTo(end, y)
        head.lineTo(end - size, y + size)
        canvas.drawPath(head, paint)
    }
}

/**
 * Full-screen orange while the button is held for Hermes: live words, a level meter, and an arrow
 * toward the side button. It floods in from the right edge, where the button is. Until the words
 * are handed on, a tap keeps them to edit instead and a swipe throws the turn away.
 */
class VoiceStage @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val ink = context.getColor(R.color.on_accent)
    private val title = TextView(context)
    private val timer = TextView(context)
    private val gestures = TextView(context)
    private val words = TextView(context)
    private val meter = LevelBars(context)
    private val hint = TextView(context)
    private val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink); setBounds(0, 0, px(10f).toInt(), px(10f).toInt()) }
    private val keyboard = context.getDrawable(R.drawable.ic_keyboard)!!.mutate().apply { setTint(ink); setBounds(0, 0, px(16f).toInt(), px(16f).toInt()) }
    private var shownEditing = false
    var showing = false
        private set
    /** A swipe throws the turn away. Returns false once that is too late; the stage settles back. */
    var onCancel: (() -> Boolean)? = null
    /** A tap keeps the words to edit instead of sending them. Returns false once that is too late. */
    var onEdit: (() -> Boolean)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var tracking = false
    private var dragging = false

    init {
        setBackgroundColor(context.getColor(R.color.accent))
        isClickable = true // touches don't reach the conversation underneath
        visibility = GONE
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f).toInt(), px(18f).toInt(), px(20f).toInt(), px(18f).toInt())
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(title, timer, hint, gestures).forEach {
            it.typeface = Fonts.mono(context, 800)
            it.textSize = 13f
            it.letterSpacing = .1f
            it.setTextColor(ink)
            it.isAllCaps = true
        }
        title.setCompoundDrawablesRelative(dot, null, null, null)
        title.compoundDrawablePadding = px(8f).toInt()
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(timer)
        column.addView(top)
        gestures.apply { typeface = Fonts.mono(context, 700); textSize = 11f; alpha = .7f }
        column.addView(gestures, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(6f).toInt() })
        words.apply {
            typeface = Fonts.display(context, 600)
            textSize = 27f
            setTextColor(ink)
            setLineSpacing(0f, 1.02f)
            letterSpacing = -.01f
            gravity = Gravity.BOTTOM or Gravity.START
        }
        column.addView(words, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(12f).toInt() })
        meter.color = ink
        column.addView(meter, LinearLayout.LayoutParams(-1, px(64f).toInt()).apply { topMargin = px(16f).toInt(); bottomMargin = px(14f).toInt() })
        val bottom = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(hint, LinearLayout.LayoutParams(-2, -2))
        bottom.addView(EdgeArrow(context).apply { color = ink },
            LinearLayout.LayoutParams(0, px(20f).toInt(), 1f).apply { marginStart = px(12f).toInt() })
        column.addView(bottom)
        addView(column, LayoutParams(-1, -1))
    }

    /** Floods in from the right edge. */
    fun show() {
        if (showing) return
        showing = true
        tracking = false
        meter.reset()
        animate().cancel()
        translationX = 0f
        translationY = 0f
        alpha = 1f
        visibility = VISIBLE
        if (isAttachedToWindow && width > 0) runCatching {
            ViewAnimationUtils.createCircularReveal(this, width, height / 2, 0f, hypot(width.toFloat(), height.toFloat()))
                .apply { duration = 260; interpolator = DecelerateInterpolator() }.start()
        }
    }

    fun hide() {
        if (!showing) return
        showing = false
        tracking = false
        animate().alpha(0f).setDuration(180).withEndAction { if (!showing) visibility = GONE }.start()
    }

    fun update(heard: String, level: Float, sending: Boolean, editing: Boolean, elapsedMs: Long) {
        title.setIfChanged(when { editing -> "Dictating"; sending -> "Sending"; else -> "Listening" })
        hint.setIfChanged(when { sending -> "Transcribing"; editing -> "Release to edit"; else -> "Release to send" })
        gestures.setIfChanged(if (editing) "Swipe to cancel" else "Tap to edit · swipe to cancel")
        if (editing != shownEditing) {
            shownEditing = editing
            title.setCompoundDrawablesRelative(if (editing) keyboard else dot, null, null, null)
        }
        val seconds = (elapsedMs / 1000).coerceAtLeast(0)
        timer.setIfChanged("%d:%02d".format(seconds / 60, seconds % 60))
        val said = heard.trim()
        words.alpha = if (said.isEmpty()) .5f else 1f
        words.setIfChanged(when {
            said.isEmpty() -> if (sending) "…" else "Listening…"
            said.length > 160 -> "…" + said.takeLast(160).substringAfter(' ')
            else -> said
        })
        if (!sending) meter.push(level)
    }

    /**
     * The side button is held throughout, so a touch here is a second contact, and a thumb resting
     * on the glass is the likely accident. A quick tap edits, which one button tap undoes; only a
     * deliberate swipe cancels. A long touch or a second finger does neither.
     */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!showing) return true // still swallowed while it fades out
        val dx = e.rawX - downX
        val dy = e.rawY - downY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                downAt = e.eventTime
                tracking = true
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (!dragging && hypot(dx, dy) > slop) dragging = true
                if (dragging) follow(dx, dy)
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false
                if (hypot(dx, dy) > slop) dragging = true
                when {
                    !dragging -> if (e.eventTime - downAt < ViewConfiguration.getLongPressTimeout()) performClick()
                    hypot(dx, dy) >= reach() && onCancel?.invoke() == true -> throwAway(dx, dy)
                    else -> snapBack()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> if (tracking) {
                tracking = false
                snapBack()
            }
        }
        return true
    }

    /** A tap, or an accessibility click: keep the words to edit. */
    override fun performClick(): Boolean {
        super.performClick()
        return onEdit?.invoke() == true
    }

    /** How far a swipe must go to cancel: about a third of the screen. */
    private fun reach() = max(min(width, height) * .3f, slop * 4f)

    /** The sheet follows the finger, fading as it nears the distance that cancels. */
    private fun follow(dx: Float, dy: Float) {
        translationX = dx
        translationY = dy
        alpha = 1f - .5f * min(hypot(dx, dy) / reach(), 1f)
    }

    private fun snapBack() {
        animate().translationX(0f).translationY(0f).alpha(1f).setDuration(160).start()
    }

    /** Off the screen the way it was swiped. */
    private fun throwAway(dx: Float, dy: Float) {
        showing = false
        val far = hypot(width.toFloat(), height.toFloat()) / hypot(dx, dy)
        animate().translationX(dx * far).translationY(dy * far).alpha(0f).setDuration(200)
            .withEndAction { if (!showing) visibility = GONE }.start()
    }

    /** Level updates arrive many times a second; only real text changes should cost a layout. */
    private fun TextView.setIfChanged(value: String) { if (text.toString() != value) text = value }
}
