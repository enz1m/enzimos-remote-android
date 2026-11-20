package com.enzimos.remote

import com.enzimos.remote.signaling.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {

    // device_id validation

    // session_id validation

    // token validation

    // dimension validation

    // scale validation

    // refresh_hz validation

    // Message serialization

    // Message deserialization (host messages)

    @Test
    fun `parse HelloReject`() {
        val json = """{"type":"hello_reject","reason":"invalid token"}"""
        val msg = parseHostMessage(json)
        assertTrue(msg is HostMessage.HelloReject)
        assertEquals("invalid token", (msg as HostMessage.HelloReject).msg.reason)
    }

    @Test
    fun `parse error redacts raw content`() {
        // session_created has required fields (sessionId, width, height) with
        // no defaults. If the JSON omits them, parsing fails. The raw content
        // must not leak into the Unknown.raw field because it could contain
        // sensitive data from adjacent messages or partial tokens.
        val json = """{"type":"session_created","v":1,"device_token":"SUPER_SECRET"}"""
        val msg = parseHostMessage(json)
        assertTrue(msg is HostMessage.Unknown)
        val unknown = msg as HostMessage.Unknown
        assertEquals("parse_error", unknown.type)
        assertFalse(
            "raw field must not contain original JSON",
            unknown.raw.contains("SUPER_SECRET"),
        )
        assertEquals("<parse_error>", unknown.raw)
    }

    // Client → Host: previously untested message serialization

    @Test
    fun `SessionResizeMessage serializes correctly`() {
        val msg = SessionResizeMessage(sessionId = "s1", width = 800, height = 600, scale = 1.5)
        val json = encodeClientMessage(msg)
        assertTrue(json.contains("\"type\":\"session_resize\""))
        assertTrue(json.contains("\"session_id\":\"s1\""))
        assertTrue(json.contains("\"width\":800"))
        assertTrue(json.contains("\"height\":600"))
        assertTrue(json.contains("\"scale\":1.5"))
    }

    // Host → Client: previously untested message deserialization

    @Test
    fun `parse SessionResumed`() {
        val json = """{"type":"session_resumed","v":1,"session_id":"s1"}"""
        val msg = parseHostMessage(json)
        assertTrue(msg is HostMessage.SessionResumed)
        assertEquals("s1", (msg as HostMessage.SessionResumed).msg.sessionId)
    }

    // Cross-protocol JSON fixtures — verify Kotlin can parse JSON as Rust
    // would serialize it (matching exact field names and types)

    @Test
    fun `cross-protocol hello_ack with device_token`() {
        // Exact JSON as Rust's server.rs would serialize a HelloAck
        val json = """{"type":"hello_ack","v":1,"capabilities":{"video_codecs":["h264"],"input":["touch","keyboard","pointer"],"resize":false,"clipboard":false,"audio":false},"device_token":"abc123"}"""
        val msg = parseHostMessage(json)
        assertTrue(msg is HostMessage.HelloAck)
        val ack = (msg as HostMessage.HelloAck).msg
        assertEquals("abc123", ack.deviceToken)
        assertEquals(listOf("h264"), ack.capabilities.videoCodecs)
        assertFalse(ack.capabilities.resize)
    }

    @Test
    fun `cross-protocol session_created with all Rust fields`() {
        // session_created as Rust sends it (includes v field which Kotlin ignores)
        val json = """{"type":"session_created","v":1,"session_id":"s42","width":1920,"height":1080,"scale":1.0,"refresh_hz":60}"""
        val msg = parseHostMessage(json)
        assertTrue(msg is HostMessage.SessionCreated)
        val created = (msg as HostMessage.SessionCreated).msg
        assertEquals("s42", created.sessionId)
        assertEquals(1920, created.width)
        assertEquals(1080, created.height)
    }

    // Capabilities serialization
}
