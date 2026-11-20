package com.enzimos.remote

import com.enzimos.remote.webrtc.SdpHelper
import org.junit.Assert.*
import org.junit.Test

class SdpHelperTest {

    private val SAMPLE_SDP = """
        v=0
        o=- 0 0 IN IP4 0.0.0.0
        s=-
        t=0 0
        m=video 9 UDP/TLS/RTP/SAVPF 96 97
        a=rtpmap:96 H264/90000
        a=rtpmap:97 VP8/90000
        a=ice-ufrag:secret123
        a=ice-pwd:verysecretpassword
        a=fingerprint:sha-256 AA:BB:CC:DD
        a=candidate:1 1 UDP 2130706431 192.168.1.100 12345 typ host
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
    """.trimIndent()

    @Test
    fun `redact removes IP addresses`() {
        val redacted = SdpHelper.redact(SAMPLE_SDP)
        assertFalse(redacted.contains("192.168.1.100"))
        assertTrue(redacted.contains("[REDACTED-IP]"))
    }

    @Test
    fun `redact removes ICE credentials`() {
        val redacted = SdpHelper.redact(SAMPLE_SDP)
        assertFalse(redacted.contains("secret123"))
        assertFalse(redacted.contains("verysecretpassword"))
        assertTrue(redacted.contains("a=ice-ufrag:[REDACTED]"))
        assertTrue(redacted.contains("a=ice-pwd:[REDACTED]"))
    }

    @Test
    fun `redact removes fingerprint`() {
        val redacted = SdpHelper.redact(SAMPLE_SDP)
        assertFalse(redacted.contains("AA:BB:CC:DD"))
        assertTrue(redacted.contains("a=fingerprint:[REDACTED]"))
    }
}
