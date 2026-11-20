package com.enzimos.remote

import com.enzimos.remote.signaling.PingTracker
import org.junit.Assert.*
import org.junit.Test

class PingTrackerTest {

    @Test
    fun `pong with wrong timestamp returns -1`() {
        val tracker = PingTracker()
        tracker.pingSent(1000)
        assertEquals(-1, tracker.pongReceived(999))
        assertTrue(tracker.isPending()) // still pending
    }
}
