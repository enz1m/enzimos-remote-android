package com.enzimos.remote

import org.junit.Assert.*
import org.junit.Test

class SessionHistoryTest {

    @Test
    fun `same session different host is separate entry`() {
        val history = SessionHistory()
        history.record("s1", "host-a", 9470, "c")
        history.record("s1", "host-b", 9470, "c")
        assertEquals(2, history.size())
    }
}
