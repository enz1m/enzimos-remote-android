package com.enzimos.remote

import com.enzimos.remote.signaling.HostTls
import com.enzimos.remote.signaling.SignalingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTlsTest {
    @Test
    fun `verification code matches host known vector`() {
        // Same vector as crates/signaling/src/identity.rs.
        assertEquals("767855", HostTls.verificationCode("00ff", "client", "host"))
        assertEquals("767855", HostTls.verificationCode("00FF", "client", "host"))
    }

    @Test
    fun `urls use tls unless unpinned loopback`() {
        assertEquals("wss://192.168.1.2:9470/v1", SignalingClient.urlFor("192.168.1.2:9470", pinned = false))
        assertEquals("ws://127.0.0.1:9470/v1", SignalingClient.urlFor("127.0.0.1:9470", pinned = false))
        assertEquals("wss://127.0.0.1:9470/v1", SignalingClient.urlFor("127.0.0.1:9470", pinned = true))
    }
}
