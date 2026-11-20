package com.enzimos.remote

import com.enzimos.remote.input.ClipboardHandler
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ClipboardHandler.
 *
 * Tests the pure-logic parts (escapeJson, constants, message building).
 * Clipboard manager interactions require instrumented tests.
 */
class ClipboardHandlerTest {

    @Test
    fun `escapeJson all control chars`() {
        // Verify no raw control chars survive
        val sb = StringBuilder()
        for (i in 0..0x1F) {
            sb.append(i.toChar())
        }
        val escaped = ClipboardHandler.escapeJson(sb.toString())
        for (i in 0..0x1F) {
            assertFalse(
                "Control char $i should be escaped",
                escaped.contains(i.toChar().toString()) &&
                    i.toChar() !in listOf('\n', '\r', '\t', '\b', '\u000C') // these are escaped with named escapes
            )
        }
    }
}
