package com.enzimos.remote

import com.enzimos.remote.signaling.HostResolver
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for HostResolver.
 *
 * Note: actual DNS resolution tests require network access.
 * These tests focus on the pure-logic methods.
 */
class HostResolverTest {

    @Test
    fun `isIpv6 bracketed`() {
        assertTrue(HostResolver.isIpv6("[::1]"))
    }

    @Test
    fun `resolved host falls back to ipv6`() {
        val rh = HostResolver.ResolvedHost("host", null, "::1")
        assertEquals("::1", rh.preferredAddress)
    }
}
