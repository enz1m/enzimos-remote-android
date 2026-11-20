package com.enzimos.remote

import com.enzimos.remote.video.QualityController
import org.junit.Assert.*
import org.junit.Test

class QualityControllerTest {

    private val sentMessages = mutableListOf<String>()

    private fun createController(
        maxBitrate: Int = 5000,
        minBitrate: Int = 200,
    ) = QualityController(
        maxBitrateKbps = maxBitrate,
        minBitrateKbps = minBitrate,
    ) { msg -> sentMessages.add(msg) }

    // Tier classification

    // Bitrate adjustments

    @Test
    fun `POOR tier sends keyframe request`() {
        val controller = createController()
        sentMessages.clear()
        controller.update(packetLossRate = 0.10, rttMs = 300)
        assertTrue(sentMessages.any { it.contains("\"type\":\"keyframe\"") })
    }

    @Test
    fun `excellent streak increases bitrate after 5 polls`() {
        val controller = createController(maxBitrate = 5000)
        // First drop to fair
        controller.update(packetLossRate = 0.03, rttMs = 50)
        val afterFair = controller.getCurrentBitrateKbps()
        assertTrue(afterFair < 5000)

        sentMessages.clear()
        // Now 5 excellent polls
        repeat(5) {
            controller.update(packetLossRate = 0.0, rttMs = 5)
        }
        assertTrue(controller.getCurrentBitrateKbps() > afterFair)
    }

    // Edge cases

    @Test
    fun `consecutive FAIR calls only reduce once`() {
        val controller = createController(maxBitrate = 4000)
        controller.update(packetLossRate = 0.03, rttMs = 50)
        val firstReduction = controller.getCurrentBitrateKbps()
        sentMessages.clear()
        // Second FAIR call should not reduce further (already at fair level)
        controller.update(packetLossRate = 0.03, rttMs = 50)
        assertEquals(firstReduction, controller.getCurrentBitrateKbps())
    }
}
