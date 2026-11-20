package com.enzimos.remote

import com.enzimos.remote.webrtc.ServerMessageParser
import com.enzimos.remote.webrtc.ServerMessageParser.ServerMessage
import com.enzimos.remote.webrtc.ServerMessageParser.WindowInfo
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ServerMessageParser — parsing server data channel responses.
 */
class ServerMessageParserTest {

    @Test
    fun `parse clipboard with newlines in text`() {
        val json = """{"type":"clipboard","v":1,"text":"line1\nline2\nline3"}"""
        val msg = ServerMessageParser.parse(json) as ServerMessage.Clipboard
        assertEquals("line1\nline2\nline3", msg.text)
    }
}
