package dev.r1ptt.workspace

import org.junit.Assert.*
import org.junit.Test

class RecoveryFenceTest {
    @Test fun healthyReplyFromBeforeWakeCannotOpenTheNewWindow() {
        val fence = RecoveryFence()
        val beforeSleep = fence.invalidate()
        val awake = fence.invalidate()
        assertFalse(fence.confirm(beforeSleep))
        assertTrue(fence.pending())
        assertFalse(fence.verified())
        assertTrue(fence.confirm(awake))
        assertFalse(fence.pending())
        assertTrue(fence.verified())
    }
    @Test fun failedConnectivityNeverOpensContentOrActionsOrClearsANewerFence() {
        val fence = RecoveryFence()
        val old = fence.invalidate()
        val latest = fence.invalidate()
        fence.offline(old)
        assertTrue(fence.pending())
        fence.offline(latest)
        assertTrue(fence.pending())
        assertFalse(fence.verified())
    }
}
