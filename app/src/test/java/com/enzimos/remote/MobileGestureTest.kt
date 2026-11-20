package com.enzimos.remote

import com.enzimos.remote.input.MobileGesture
import com.enzimos.remote.input.MobileGesture.Action
import com.enzimos.remote.input.MobileGesture.Point
import org.junit.Assert.*
import org.junit.Test

class MobileGestureTest {
    private fun gesture() = MobileGesture(10.0)
    private val start = Point(100.0, 300.0)

    @Test fun slowScrollNeverTurnsIntoDragOrContextMenu() {
        val g = gesture(); g.down(start, 0); g.move(Point(100.0, 260.0), 100)
        assertTrue(g.advance(2000).isEmpty())
        assertTrue(g.move(Point(100.0, 250.0), 2500).single() is Action.Scroll)
    }
    @Test fun dragBeginsAtOriginalPointOnlyAfterHoldAndMovement() {
        val g = gesture(); g.down(start, 0); g.advance(500)
        val target = Point(160.0, 300.0)
        assertEquals(listOf(Action.Pointer(start, true), Action.Button(true), Action.Pointer(target)), g.move(target, 600))
        val end = g.up(target, 700)
        assertEquals(Action.Pointer(target, true), end[end.size - 2])
        assertEquals(Action.Button(false), end.last())
        assertFalse(end.any { it is Action.Click })
    }
    @Test fun dragCancellationEscapesBeforeReleasingButton() {
        val g = gesture(); g.down(start, 0); g.move(Point(150.0, 300.0), 600)
        assertEquals(listOf(Action.Escape, Action.Button(false)), g.cancel())
        assertTrue(g.up(start, 1000).isEmpty())
    }
    @Test fun scrollCancellationEndsTheAxisGesture() {
        val g = gesture(); g.down(start, 0); g.move(Point(100.0, 250.0), 50)
        assertEquals(listOf(Action.ScrollStop(start)), g.cancel())
    }
    @Test fun lateHistoricalMotionWinsOverAnArmedTimer() {
        val g = gesture(); g.down(start, 0); g.advance(500)
        assertTrue(g.move(Point(100.0, 260.0), 100).single() is Action.Scroll)
    }
    @Test fun twoQuickTapsRemainTwoClicksForTheApp() {
        val g = gesture(); g.down(start, 0)
        val first = g.up(start, 50); g.down(start, 100)
        assertEquals(listOf(Action.Click(start), Action.Click(start)), first + g.up(start, 150))
    }
    @Test fun customAccessibilityHoldDelayIsHonored() {
        val g = MobileGesture(10.0, 1500); g.down(start, 0)
        assertTrue(g.advance(1000).isEmpty())
        assertEquals(Action.Notify(MobileGesture.Feedback.DRAG_READY), g.advance(1500).single())
        assertTrue(g.advance(3000).first() is Action.Click)
    }
}
