package com.enzimos.remote

import com.enzimos.remote.webrtc.ServerMessageParser
import org.junit.Assert.*
import org.junit.Test

class ContentResizeTest {
    private val canvas = AppViewport.Size(1078, 2046, 2.75)
    private val keyboard = canvas.copy(height = 1144)

    @Test fun keyboardAndRestoreKeepTheSameCanvas() {
        val fit = ContentResize(canvas)
        fit.update(keyboard)
        assertTrue(fit.cropping)
        val shrink = fit.nextRequest()!!
        assertEquals(1144, shrink.height)
        assertTrue(fit.acknowledge(shrink.serial))
        fit.update(canvas)
        assertFalse(fit.cropping)
        assertEquals(2046, fit.nextRequest()!!.height)
        assertEquals(canvas, fit.canvas)
    }

    @Test fun coalescesRapidKeyboardChangesBehindOneRequest() {
        val fit = ContentResize(canvas)
        fit.update(keyboard)
        val first = fit.nextRequest()!!
        fit.update(canvas.copy(height = 1320))
        fit.update(canvas)
        assertNull(fit.nextRequest())
        assertFalse(fit.acknowledge(first.serial + 1))
        assertEquals(first, fit.pending)
        assertTrue(fit.acknowledge(first.serial))
        val last = fit.nextRequest()!!
        assertEquals(2046, last.height)
        assertTrue(fit.acknowledge(last.serial))
        assertNull(fit.nextRequest())
    }

    @Test fun cannotUseWindowOnlyResizeForRotationGrowthOrScaleChange() {
        val fit = ContentResize(canvas)
        assertFalse(fit.canFit(canvas.copy(width = 2288, height = 770)))
        assertFalse(fit.canFit(canvas.copy(height = 2178)))
        assertFalse(fit.canFit(canvas.copy(scale = 2.5)))
        assertFalse(fit.canFit(canvas.copy(height = 0)))
        assertTrue(fit.canFit(keyboard))
        assertNull(fit.nextRequest())
    }

    @Test fun keyboardCropsRatherThanScalingDownTheWholeVideo() {
        val full = com.enzimos.remote.video.VideoLayout.fit(1078, 2046, 1080, 2052, true)
        val keyboard = com.enzimos.remote.video.VideoLayout.fit(1078, 2046, 1080, 1146, true)
        assertEquals(full, keyboard)
        assertEquals(1080, keyboard.width)
        assertTrue(keyboard.height > 1146)
        val fallback = com.enzimos.remote.video.VideoLayout.fit(1078, 2046, 1080, 1146, false)
        assertTrue(fallback.width < 1080)
        assertTrue(fallback.height <= 1146)
    }

    @Test fun capabilitiesAreExplicitAndAcknowledgementsRequireResultAndSerial() {
        assertEquals(ServerMessageParser.ServerMessage.StreamConfig(true),
            ServerMessageParser.parse("""{"type":"stream_config","content_resize":true}"""))
        assertEquals(ServerMessageParser.ServerMessage.StreamConfig(false),
            ServerMessageParser.parse("""{"type":"stream_config"}"""))
        assertEquals(ServerMessageParser.ServerMessage.ContentSizeAck(2, false),
            ServerMessageParser.parse("""{"type":"content_size_ack","serial":2,"applied":false}"""))
        assertNull(ServerMessageParser.parse("""{"type":"content_size_ack","serial":2}"""))
    }
}
