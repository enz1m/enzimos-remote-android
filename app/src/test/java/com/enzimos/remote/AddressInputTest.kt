package com.enzimos.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressInputTest {
    @Test
    fun `accepts hosts and ports`() {
        assertEquals("192.168.1.20:9470", AddressInput.normalize("192.168.1.20"))
        assertEquals("192.168.1.20:9471", AddressInput.normalize(" 192.168.1.20:9471 "))
        assertEquals("studio.local:9470", AddressInput.normalize("studio.local"))
        assertEquals("pc:9470", AddressInput.normalize("wss://pc:9470/v1"))
        assertEquals("[fe80::1]:9470", AddressInput.normalize("fe80::1"))
        assertEquals("[fd00::2]:9500", AddressInput.normalize("[fd00::2]:9500"))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(AddressInput.normalize(""))
        assertNull(AddressInput.normalize("127.0.0.1:19472" + "192.0.2.20:9471"))
        assertNull(AddressInput.normalize("host:0"))
        assertNull(AddressInput.normalize("host:99999"))
        assertNull(AddressInput.normalize("300.1.1.1"))
        assertNull(AddressInput.normalize("a b"))
        assertNull(AddressInput.normalize("[fd00::2"))
    }
}
