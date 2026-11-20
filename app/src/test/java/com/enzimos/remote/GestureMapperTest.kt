package com.enzimos.remote

import com.enzimos.remote.input.GestureMapper
import org.junit.Assert.*
import org.junit.Test

class GestureMapperTest {

    @Test
    fun `zoom in sends ctrl down then scroll then ctrl up`() {
        val msgs = GestureMapper.zoomMessages(1.5)
        assertTrue(msgs[0].contains("\"type\":\"key\""))
        assertTrue(msgs[0].contains("\"pressed\":true"))
        assertTrue(msgs[1].contains("\"type\":\"scroll\""))
        assertTrue(msgs[2].contains("\"type\":\"key\""))
        assertTrue(msgs[2].contains("\"pressed\":false"))
    }
}
