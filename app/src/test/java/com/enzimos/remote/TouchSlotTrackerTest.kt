package com.enzimos.remote

import com.enzimos.remote.input.TouchSlotTracker
import org.junit.Assert.*
import org.junit.Test

class TouchSlotTrackerTest {

    @Test
    fun `17th pointer returns -1`() {
        val tracker = TouchSlotTracker()
        for (i in 0 until 16) {
            tracker.assignSlot(i)
        }
        assertEquals(-1, tracker.assignSlot(99))
    }
}
