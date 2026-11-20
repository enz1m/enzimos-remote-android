package com.enzimos.remote

import com.enzimos.remote.signaling.*
import org.junit.Assert.*
import org.junit.Test

class ComputerProtocolTest {
    @Test fun computerInfoParsesWithoutStartingAStream() {
        val result = parseHostMessage("""{"type":"computer_info_result","v":1,"computer":{"hostname":"Desk","apps":[{"id":"text-app","name":"Text","available":true}]}}""")
        assertTrue(result is HostMessage.ComputerInfoResult)
        val info = (result as HostMessage.ComputerInfoResult).msg.computer
        assertEquals("Desk", info.hostname)
        assertNull(info.cpuUsagePercent)
        assertEquals("text-app", info.apps.single().id)
        assertEquals("computer_info", protocolJson.parseToJsonElement(encodeClientMessage(ComputerInfoRequest())).let {
            kotlinx.serialization.json.Json.decodeFromString<ComputerInfoRequest>(it.toString()).type
        })
    }
    @Test fun desktopAttachDoesNotRequestPhoneGeometryOrLaunchACommand() {
        val message = encodeClientMessage(DesktopAttachMessage(sessionId = "desktop-test"))
        assertTrue(message.contains("\"type\":\"desktop_attach\""))
        assertFalse(message.contains("width"))
        assertFalse(message.contains("scale"))
        assertFalse(message.contains("command"))
    }
    @Test fun mediaPauseAndUserReturnHaveDifferentWireIntents() {
        assertTrue(encodeClientMessage(StreamPauseMessage(sessionId = "text-test")).contains("stream_pause"))
        assertTrue(encodeClientMessage(StreamStopMessage(sessionId = "text-test")).contains("stream_stop"))
    }
    @Test fun launchUsesAnAppIdNotAHostCommand() {
        val message = encodeClientMessage(AppLaunchMessage(sessionId = "text-app-123", appId = "text-app", width = 1080, height = 2224, scale = 2.75, refreshHz = 60))
        assertTrue(message.contains("\"app_id\":\"text-app\""))
        assertFalse(message.contains("command"))
    }
    @Test fun measuredSurfaceDoesNotSubtractDockOrInsetsTwice() {
        val viewport = AppViewport.forSurface(1080, 2052, 2.75)
        assertEquals(1078, viewport.width)
        assertEquals(2046, viewport.height)
        assertEquals(0.0, viewport.height / viewport.scale % 1, 0.0)
    }
    @Test fun lowerResolutionPreservesResponsiveLogicalSize() {
        val native = AppViewport.create(1080, 2400, 2.75)
        val limited = AppViewport.create(1080, 2400, 2.75, AppViewport.resolutionLimit("720x1600"))
        assertTrue(limited.width <= 720)
        assertEquals(native.width / native.scale, limited.width / limited.scale, 4.0)
        assertEquals(native.height / native.scale, limited.height / limited.scale, 4.0)
        assertNull(AppViewport.resolutionLimit("native"))
    }
}
