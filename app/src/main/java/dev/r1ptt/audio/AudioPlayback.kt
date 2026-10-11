package dev.r1ptt.audio

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Actual AudioTrack ownership, including speech that starts before an agent finishes streaming. */
object AudioPlayback {
    private val owners = ConcurrentHashMap.newKeySet<Any>()
    private val state = MutableStateFlow(false)
    val flow: StateFlow<Boolean> = state
    val active: Boolean get() = state.value

    @Synchronized internal fun started(owner: Any) { owners.add(owner); state.value = owners.isNotEmpty() }
    @Synchronized internal fun stopped(owner: Any) { owners.remove(owner); state.value = owners.isNotEmpty() }
}
