package dev.r1ptt.data

import dev.r1ptt.App
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class EventLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val limit = EventLog.maxBytes
    @After fun restore() { EventLog.maxBytes = limit }

    @Test fun keepsTheNewestEventsOldestFirstInTwoBoundedFiles() {
        val dir = temporary.newFolder()
        EventLog.start(dir)
        EventLog.maxBytes = 1_024
        repeat(200) { EventLog.record("radio event %03d".format(it)) }
        val lines = EventLog.lines()
        assertTrue(lines.last().endsWith("radio event 199"))
        val numbers = lines.map { it.takeLast(3).toInt() }
        assertEquals(numbers.sorted(), numbers)
        assertTrue("the oldest events were dropped", numbers.first() > 0)
        assertTrue(lines.all { it.matches(Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} [+-]\d{4} radio event \d{3}""")) })
        val kept = dir.listFiles()!!.filter { it.name.startsWith("events") }
        assertEquals(setOf("events.log", "events.1.log"), kept.map(File::getName).toSet())
        assertTrue(kept.all { it.length() <= 1_024 + 100 })
    }
}
