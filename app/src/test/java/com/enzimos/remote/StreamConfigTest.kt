package com.enzimos.remote

import com.enzimos.remote.webrtc.IceServerConfig
import org.junit.Assert.*
import org.junit.Test

class StreamConfigTest {

    private fun validConfig() = StreamConfig(
        wsUrl = "ws://192.168.1.100:9470",
        token = "secret",
        deviceId = "my-phone",
        sessionId = "test-session",
        command = listOf("weston-terminal"),
    )

    @Test
    fun `invalid ws URL rejected`() {
        val config = validConfig().copy(wsUrl = "http://bad")
        assertNotNull(config.validate())
    }

    @Test
    fun `empty command accepted for resume`() {
        val config = validConfig().copy(command = emptyList(), resume = true)
        assertNull(config.validate())
    }
}
