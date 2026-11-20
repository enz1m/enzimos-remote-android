package com.enzimos.remote

import org.junit.Assert.*
import org.junit.Test

class MediaRecoveryTest {
    @Test fun backoffIsFiniteAndCapped() {
        val recovery = MediaRecovery()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 8000L, 8000L, 8000L, 8000L),
            List(8) { recovery.nextDelayMs() })
        repeat(20) { assertNull(recovery.nextDelayMs()) }
    }

    @Test fun successfulConnectionOrManualRetryStartsNewBudget() {
        val recovery = MediaRecovery()
        repeat(8) { recovery.nextDelayMs() }
        recovery.reset()
        assertEquals(1000L, recovery.nextDelayMs())
    }

    @Test fun otherFailuresDoNotStartARetryLoop() {
        listOf("permission denied", "unsupported codec", "session not found",
            "WebRTC pipeline process exited (exit status: 0)", "", "camera ICE connection failed")
            .forEach { assertFalse(MediaRecovery.isTransportFailure(it)) }
    }
}
