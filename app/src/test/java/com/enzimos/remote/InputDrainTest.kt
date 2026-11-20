package com.enzimos.remote

import org.junit.Assert.*
import org.junit.Test

class InputDrainTest {
    @Test fun releaseWaitsForTheMatchingAcknowledgement() {
        val drain = InputDrain()
        val serial = drain.begin()
        assertEquals(serial, drain.begin())
        assertEquals(InputDrain.Result.IGNORE, drain.acknowledge(serial + 1, true))
        assertEquals(InputDrain.Result.COMPLETE, drain.acknowledge(serial, true))
        assertEquals(InputDrain.Result.IGNORE, drain.acknowledge(serial, true))
    }

    @Test fun timeoutReleasesOnlyOnceAndRejectsLateAcknowledgements() {
        val drain = InputDrain()
        val serial = drain.begin()
        assertEquals(InputDrain.Result.FAILED, drain.timeout(serial))
        assertEquals(InputDrain.Result.IGNORE, drain.acknowledge(serial, true))
    }

    @Test fun staleTimerCannotReleaseANewerRequest() {
        val drain = InputDrain()
        val old = drain.begin()
        drain.cancel()
        val current = drain.begin()
        assertNotEquals(old, current)
        assertEquals(InputDrain.Result.IGNORE, drain.timeout(old))
        assertEquals(InputDrain.Result.COMPLETE, drain.acknowledge(current, true))
    }

    @Test fun rejectedInputDoesNotBecomeASuccessfulDrain() {
        val drain = InputDrain()
        assertEquals(InputDrain.Result.FAILED, drain.acknowledge(drain.begin(), false))
    }
}
