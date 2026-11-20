package com.enzimos.remote.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
class GamepadTest {
    @Test fun snapshotMatchesTheHostFormat() {
        val json = GamepadState(buttons = GamepadButtons.A or GamepadButtons.UP, lx = -0.5f, rt = 1f).json(2)
        assertEquals(
            """{"type":"gamepad","v":1,"id":2,"buttons":2049,"lx":-0.500,"ly":0.000,"rx":0.000,"ry":0.000,"lt":0.000,"rt":1.000}""",
            json,
        )
    }

    @Test fun buttonsAreSentReliablyAndSticksCoalesced() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val hub = GamepadHub { json, critical -> sent += json to critical; true }
        hub.update("pad", GamepadState(buttons = GamepadButtons.B))
        assertEquals(1, sent.size)
        assertTrue(sent[0].second)
        hub.update("pad", GamepadState(buttons = GamepadButtons.B, lx = 0.2f))
        hub.update("pad", GamepadState(buttons = GamepadButtons.B, lx = 0.4f))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
        // Only the latest stick position is sent, best effort.
        assertEquals(2, sent.size)
        assertTrue(sent[1].first.contains("\"lx\":0.400"))
        assertEquals(false, sent[1].second)
    }

    @Test fun droppedStickUpdatesAreRetried() {
        var accept = false
        val sent = mutableListOf<String>()
        val hub = GamepadHub { json, critical -> if (critical || accept) { sent += json; true } else false }
        hub.update("pad", GamepadState(lx = 1f))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
        assertTrue(sent.isEmpty())
        accept = true
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
        assertEquals(1, sent.size)
    }

    @Test fun slotsAreReusedAndUnplugged() {
        val sent = mutableListOf<String>()
        val hub = GamepadHub { json, _ -> sent += json; true }
        assertEquals(0, hub.slot("a"))
        assertEquals(1, hub.slot("b"))
        hub.remove("a")
        assertTrue(sent.last().contains("\"id\":0,\"connected\":false"))
        assertEquals(0, hub.slot("c"))
    }
}
