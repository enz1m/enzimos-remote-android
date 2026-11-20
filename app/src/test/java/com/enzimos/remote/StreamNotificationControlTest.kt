package com.enzimos.remote

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class StreamNotificationControlTest {
    private class Owner(override val token: String) : StreamNotificationControl.Owner {
        var stops = 0
        override fun requestStop() { stops++ }
    }
    private val first = Owner("first-generation")
    private val second = Owner("second-generation")

    @After fun release() {
        StreamNotificationControl.release(first)
        StreamNotificationControl.release(second)
    }

    @Test fun staleActionsCannotStopANewerStream() {
        StreamNotificationControl.claim(first)
        StreamNotificationControl.claim(second)
        assertFalse(StreamNotificationControl.requestStop(first.token))
        assertFalse(StreamNotificationControl.requestStop(null))
        assertFalse(StreamNotificationControl.requestStop(""))
        assertEquals(0, first.stops)
        assertEquals(0, second.stops)
        assertTrue(StreamNotificationControl.requestStop(second.token))
        assertEquals(1, second.stops)
    }

    @Test fun staleActivityDestructionDoesNotReleaseTheNewOwner() {
        StreamNotificationControl.claim(first)
        StreamNotificationControl.claim(second)
        assertFalse(StreamNotificationControl.release(first))
        assertTrue(StreamNotificationControl.owns(second.token))
    }
}
