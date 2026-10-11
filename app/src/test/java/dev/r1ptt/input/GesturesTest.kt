package dev.r1ptt.input

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GesturesTest {
    private class FakeTime {
        var now = 0L
        private class Task(val at: Long, val action: () -> Unit) {
            var cancelled = false
        }
        private val tasks = mutableListOf<Task>()

        fun schedule(delay: Long, action: () -> Unit): Gestures.Cancellable {
            val t = Task(now + delay, action)
            tasks += t
            return Gestures.Cancellable { t.cancelled = true }
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                now = next.at
                next.action()
            }
            now = end
        }
    }

    private val events = mutableListOf<String>()
    private lateinit var time: FakeTime
    private lateinit var g: Gestures

    @Before
    fun setUp() {
        time = FakeTime()
        g = Gestures(
            clock = { time.now },
            schedule = time::schedule,
            listener = object : Gestures.Listener {
                override fun onPress(atMs: Long) { events += "press" }
                override fun onHoldStart() { events += "hold" }
                override fun onHoldEnd() { events += "release" }
                override fun onShortRelease() { events += "short" }
                override fun onTap() { events += "tap" }
                override fun onDoubleTap() { events += "double" }
            },
        )
    }

    private fun press(ms: Long) {
        g.down()
        time.advance(ms)
        g.up()
    }

    @Test
    fun holdStartsAfterThresholdAndEndsOnRelease() {
        g.down()
        time.advance(249)
        assertEquals(listOf("press"), events)
        time.advance(1)
        assertEquals(listOf("press", "hold"), events)
        time.advance(2000)
        g.up()
        assertEquals(listOf("press", "hold", "release"), events)
        time.advance(1000)
        assertEquals(listOf("press", "hold", "release"), events)
    }

    @Test
    fun tapResolvesAfterTheMultiTapWindow() {
        press(100)
        assertEquals(listOf("press", "short"), events)
        time.advance(349)
        assertEquals(listOf("press", "short"), events)
        time.advance(1)
        assertEquals(listOf("press", "short", "tap"), events)
    }

    @Test
    fun twoQuickTapsAreADoubleTapAndNoSingleTap() {
        press(80)
        time.advance(150)
        press(80)
        time.advance(1000)
        assertEquals(listOf("press", "short", "press", "short", "double"), events)
    }

    @Test
    fun tapThenHoldDropsTheTap() {
        press(80)
        time.advance(150)
        g.down()
        time.advance(300)
        g.up()
        time.advance(1000)
        assertEquals(listOf("press", "short", "press", "hold", "release"), events)
    }

    @Test
    fun slowTapsAreSeparateTaps() {
        press(80)
        time.advance(500)
        press(80)
        time.advance(500)
        assertEquals(listOf("press", "short", "tap", "press", "short", "tap"), events)
    }

    @Test
    fun duplicateEdgesAreIgnored() {
        g.down()
        g.down()
        time.advance(300)
        g.up()
        g.up()
        assertEquals(listOf("press", "hold", "release"), events)
    }
    @Test fun recoveryCancellationDropsHeldAndPendingTapTimersWithoutSynthesizingActions() {
        g.down(); time.advance(100); g.cancel(); time.advance(1000); g.up()
        assertEquals(listOf("press"), events)
        press(60); g.cancel(); time.advance(1000)
        assertEquals(listOf("press", "press", "short"), events)
        press(300)
        assertEquals(listOf("press", "press", "short", "press", "hold", "release"), events)
    }

}
