package com.enzimos.remote

import com.enzimos.remote.pairing.QrCodeData
import org.junit.Assert.*
import org.junit.Test

class QrCodeDataTest {

    @Test
    fun `port 65536 rejected`() {
        val data = QrCodeData("host", 65536, "token")
        assertNotNull(data.validate())
    }

    @Test
    fun `token too long rejected`() {
        val data = QrCodeData("host", 9470, "a".repeat(513))
        assertNotNull(data.validate())
    }

    @Test
    fun `parse JSON without token returns null`() {
        val json = """{"host":"10.0.0.1"}"""
        assertNull(QrCodeData.parse(json))
    }

    @Test
    fun `parse with TLS true`() {
        val json = """{"host":"h","token":"tok","tls":true}"""
        val data = QrCodeData.parse(json)!!
        assertTrue(data.tls)
    }
}
