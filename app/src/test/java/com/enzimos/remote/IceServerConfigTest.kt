package com.enzimos.remote

import com.enzimos.remote.webrtc.IceServerConfig
import org.junit.Assert.*
import org.junit.Test

class IceServerConfigTest {

    @Test
    fun `TURN without credentials rejected`() {
        assertNotNull(IceServerConfig("turn:turn.example.com").validate())
    }
}
