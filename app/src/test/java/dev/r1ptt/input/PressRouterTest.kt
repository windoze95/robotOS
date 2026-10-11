package dev.r1ptt.input

import org.junit.Assert.assertEquals
import org.junit.Test

class PressRouterTest {
    @Test fun homePressReleasesHomeAfterWorkspaceOpens() {
        val events = mutableListOf<String>()
        var surface = "home"
        val router = PressRouter {
            val selected = surface
            { down -> events += "$selected:$down" }
        }
        router.edge(true)
        surface = "workspace"
        router.edge(false)
        router.edge(true)
        router.edge(false)
        assertEquals(listOf("home:true", "home:false", "workspace:true", "workspace:false"), events)
    }

    @Test fun workspaceReleaseUsesOriginalCallbackAfterActivityChanges() {
        val events = mutableListOf<String>()
        var callback: (Boolean) -> Unit = { events += "old:$it" }
        val router = PressRouter { callback }
        router.edge(true)
        callback = { events += "new:$it" }
        router.edge(false)
        assertEquals(listOf("old:true", "old:false"), events)
    }

    @Test fun ringDismissalConsumesWholePressIncludingDuplicateEdges() {
        val events = mutableListOf<String>()
        var ringing = true
        val router = PressRouter {
            if (ringing) {
                { down -> if (down) { events += "stop ring"; ringing = false } }
            } else { down -> events += "ptt:$down" }
        }
        router.edge(false)
        router.edge(true)
        router.edge(true)
        router.edge(false)
        router.edge(false)
        assertEquals(listOf("stop ring"), events)
        router.edge(true)
        router.edge(false)
        assertEquals(listOf("stop ring", "ptt:true", "ptt:false"), events)
    }
    @Test fun recoveryCancellationNeverReleasesTheOldCaptureAndAcceptsTheNextPress() {
        val events = mutableListOf<String>()
        var surface = "old"
        val router = PressRouter { val target = surface; { down -> events += "$target:$down" } }
        router.edge(true)
        router.cancel()
        router.edge(false) // physical release arrives while restricted; never submits the old note
        surface = "restored"
        router.edge(true); router.edge(false)
        assertEquals(listOf("old:true", "restored:true", "restored:false"), events)
    }

}
