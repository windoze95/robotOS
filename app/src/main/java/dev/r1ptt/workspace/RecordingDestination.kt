package dev.r1ptt.workspace

/** Retains the editor that started a recording; replacing that editor leaves the capture in Notes. */
internal class RecordingDestination<T : Any>(private val screen: String, prompt: T?) {
    private val target = prompt.takeIf { screen == "builder" }

    fun promptIfCurrent(current: T?): T? = target?.takeIf { it === current }
    fun canShowResult(currentScreen: String, current: T?): Boolean =
        currentScreen == screen && (target == null || target === current)
}
