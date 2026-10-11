package dev.r1ptt.input

/**
 * Turns the raw press/release edges of the single side button into gestures.
 *
 * - Every press calls [Listener.onPress] at once, so recording can start before we know whether it
 *   is a hold: the first word must not be lost to the decision.
 * - Held for [holdMs]: [Listener.onHoldStart], then [Listener.onHoldEnd] on release.
 * - Released sooner: [Listener.onShortRelease] at once, then [Listener.onTap] when no second tap
 *   follows within [multiTapMs], or [Listener.onDoubleTap] on the second quick tap.
 * - Tap, then hold: the pending tap is dropped and the hold proceeds as usual.
 *
 * Pure logic: time and scheduling are injected so it can be tested without Android.
 */
class Gestures(
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Cancellable,
    private val listener: Listener,
    private val holdMs: Long = 250,
    private val multiTapMs: Long = 350,
) {
    fun interface Cancellable {
        fun cancel()
    }

    interface Listener {
        fun onPress(atMs: Long)
        fun onHoldStart()
        fun onHoldEnd()
        fun onShortRelease()
        fun onTap()
        fun onDoubleTap()
    }

    private var pressed = false
    private var held = false
    private var pendingTaps = 0
    private var holdTimer: Cancellable? = null
    private var tapTimer: Cancellable? = null

    /** Cancels the whole gesture without producing a release, tap, or other user action. */
    fun cancel() {
        holdTimer?.cancel(); holdTimer = null
        tapTimer?.cancel(); tapTimer = null
        pressed = false; held = false; pendingTaps = 0
    }

    fun down() {
        if (pressed) return // a duplicate edge (auto-repeat, or both input paths reporting)
        pressed = true
        tapTimer?.cancel() // this press continues a multi-tap in progress
        tapTimer = null
        listener.onPress(clock())
        holdTimer = schedule(holdMs) {
            holdTimer = null
            if (pressed && !held) {
                held = true
                pendingTaps = 0 // tap-then-hold: the tap is dropped
                listener.onHoldStart()
            }
        }
    }

    fun up() {
        if (!pressed) return
        pressed = false
        holdTimer?.cancel()
        holdTimer = null
        if (held) {
            held = false
            listener.onHoldEnd()
            return
        }
        listener.onShortRelease()
        pendingTaps++
        if (pendingTaps >= 2) {
            pendingTaps = 0
            listener.onDoubleTap()
            return
        }
        tapTimer = schedule(multiTapMs) {
            tapTimer = null
            if (pendingTaps == 1 && !pressed) {
                pendingTaps = 0
                listener.onTap()
            }
        }
    }
}
