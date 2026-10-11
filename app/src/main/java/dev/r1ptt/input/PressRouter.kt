package dev.r1ptt.input

/** A physical press belongs to the surface selected at DOWN, even if navigation changes before UP. */
internal class PressRouter(private val select: () -> ((Boolean) -> Unit)) {
    private var captured: ((Boolean) -> Unit)? = null

    /** Security/navigation cancellation must not synthesize UP and submit the old capture. */
    fun cancel() { captured = null }

    fun edge(down: Boolean) {
        if (down) {
            if (captured != null) return
            val route = select()
            captured = route
            route(true)
        } else {
            val route = captured ?: return
            captured = null
            route(false)
        }
    }
}
