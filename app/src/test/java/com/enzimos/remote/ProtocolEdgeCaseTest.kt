package com.enzimos.remote

import com.enzimos.remote.signaling.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolEdgeCaseTest {

    // device_id boundary cases

    @Test
    fun `device_id with slash rejected`() {
        assertFalse(validateDeviceId("path/traversal"))
    }

    // session_id boundary cases

    // token boundary cases

    @Test
    fun `token with carriage return rejected`() {
        assertFalse(validateToken("has\rreturn"))
    }

    // dimension boundary cases

    // scale boundary cases

    // refresh_hz boundary cases

    // Serialization edge cases

    // Deserialization edge cases
}
