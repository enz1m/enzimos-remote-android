package com.enzimos.remote

import com.enzimos.remote.video.VideoZoom
import org.junit.Assert.*
import org.junit.Test

class VideoZoomTest {
    @Test fun aFixedPinchFocusKeepsTheSameSourcePoint() {
        val old = VideoZoom(1.5f, 20f, -30f)
        val next = old.scaled(2f, 350f, 240f, 350f, 240f, 500f, 400f)
        val sourceX = (350f - 500f - old.x) / old.scale
        val sourceY = (240f - 400f - old.y) / old.scale
        assertEquals(350f, 500f + sourceX * next.scale + next.x, .001f)
        assertEquals(240f, 400f + sourceY * next.scale + next.y, .001f)
    }
    @Test fun letterboxedAxesDoNotPanIntoMoreEmptySpace() {
        val next = VideoZoom(1.5f, 500f, 500f).constrained(1200f, 750f, 2200f, 750f)
        assertEquals(0f, next.x, 0f)
        assertEquals(187.5f, next.y, 0f)
    }
}
