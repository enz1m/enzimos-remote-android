package com.enzimos.remote

import com.enzimos.remote.input.ControlMessageSerializer
import org.junit.Assert.*
import org.junit.Test

class ControlMessageTest {

    @Test
    fun `text message escapes all control characters`() {
        // Build a string with all control chars 0x00-0x1F
        val sb = StringBuilder()
        for (i in 0..0x1F) {
            sb.append(i.toChar())
        }
        val json = ControlMessageSerializer.text(sb.toString())
        // No raw control characters should appear in the JSON
        for (i in 0..0x1F) {
            val ch = i.toChar()
            // The escaped form should be present, not the raw char
            // (except for \n, \r, \t which have named escapes)
            when (ch) {
                '\n' -> assertTrue(json.contains("\\n"))
                '\r' -> assertTrue(json.contains("\\r"))
                '\t' -> assertTrue(json.contains("\\t"))
                '\b' -> assertTrue(json.contains("\\b"))
                '\u000C' -> assertTrue(json.contains("\\f"))
                else -> {
                    // Should be escaped as \uXXXX
                    val expected = String.format("\\u%04x", i)
                    assertTrue("Control char $i not escaped", json.contains(expected))
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pointer rejects NaN x`() {
        ControlMessageSerializer.pointer(Double.NaN, 0.0)
    }

    @Test
    fun `focusWindow strips quotes and backslashes`() {
        val json = ControlMessageSerializer.focusWindow("0x1234\"}")
        assertFalse(json.contains("\\\""))
        assertTrue(json.contains("0x1234"))
    }
}
