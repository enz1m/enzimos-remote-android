package com.enzimos.remote

import com.enzimos.remote.webrtc.ReceiverStatsTracker
import org.junit.Assert.*
import org.junit.Test

class ReceiverStatsTrackerTest {
    private fun sample(time: Long, received: Long, lost: Long = 0, id: String = "video") =
        ReceiverStatsTracker.Sample(id, time, received, lost, received, received * 1000, received * .002, received * .005, received)

    @Test fun measuresRecentLossNotLifetimeAverage() {
        val tracker = ReceiverStatsTracker()
        assertNull(tracker.update(sample(1000, 10000)))
        val interval = tracker.update(sample(2000, 10090, 10))!!
        assertEquals(.1, interval.packetLoss, .000001)
        assertEquals(90.0, interval.fps, .000001)
        assertEquals(2.0, interval.decodeMs, .000001)
        assertEquals(5.0, interval.jitterBufferMs, .000001)
        assertEquals(0.0, tracker.update(sample(3000, 10190, 10))!!.packetLoss, .000001)
    }
    @Test fun recoveryCountersAreDeltasAndMissingDataStaysUnknown() {
        val tracker = ReceiverStatsTracker()
        tracker.update(sample(1000, 100).copy(nacks = 40, plis = 2, retransmissions = 10, framesDropped = 3, freezes = 1, freezeSeconds = 0.5))
        val interval = tracker.update(sample(2000, 150).copy(nacks = 44, plis = 3, retransmissions = 14, framesDropped = 5, freezes = 2, freezeSeconds = 1.75))!!
        assertEquals(4L, interval.nacks)
        assertEquals(1L, interval.plis)
        assertEquals(4L, interval.retransmissions)
        assertEquals(2L, interval.framesDropped)
        assertEquals(1L, interval.freezes)
        assertEquals(1250.0, interval.freezeMs!!, 0.0)
        assertTrue(interval.recoveryFields().contains("nacks=4"))
        val unknown = tracker.update(sample(3000, 200))!!
        assertNull(unknown.nacks)
        assertEquals("", unknown.recoveryFields())
    }

    @Test fun reconnectAndCounterResetsNeedNewBaseline() {
        val tracker = ReceiverStatsTracker()
        tracker.update(sample(1000, 100))
        assertNull(tracker.update(sample(2000, 5)))
        assertNull(tracker.update(sample(3000, 20, id = "new-video")))
        tracker.reset()
        assertNull(tracker.update(sample(4000, 30)))
    }
}
