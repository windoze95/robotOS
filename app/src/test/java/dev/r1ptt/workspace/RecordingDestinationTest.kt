package dev.r1ptt.workspace

import org.junit.Assert.*
import org.junit.Test

class RecordingDestinationTest {
    @Test fun navigatingToAnotherPromptDoesNotAttachOrReopenTheOriginalEditor() {
        val original = Any()
        val destination = RecordingDestination("builder", original)
        val next = Any()
        assertSame(original, destination.promptIfCurrent(original))
        assertNull(destination.promptIfCurrent(next))
        assertFalse(destination.canShowResult("builder", next))
        assertNull(destination.promptIfCurrent(null))
    }

    @Test fun previewKeepsOriginalPromptAttachmentWithoutChangingTheScreen() {
        val original = Any()
        val destination = RecordingDestination("builder", original)
        assertSame(original, destination.promptIfCurrent(original))
        assertFalse(destination.canShowResult("preview", original))
        assertTrue(destination.canShowResult("builder", original))
    }

    @Test fun notesCaptureDoesNotAttachToAnEditorOpenedBeforeRelease() {
        val staleEditor = Any()
        val destination = RecordingDestination("notes", staleEditor)
        assertNull(destination.promptIfCurrent(staleEditor))
        assertNull(destination.promptIfCurrent(Any()))
        assertFalse(destination.canShowResult("builder", staleEditor))
        assertTrue(destination.canShowResult("notes", staleEditor))
    }
}
