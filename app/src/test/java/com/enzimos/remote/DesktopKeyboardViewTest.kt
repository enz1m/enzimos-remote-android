package com.enzimos.remote

import com.enzimos.remote.video.DesktopKeyboardView
import com.enzimos.remote.video.VideoZoom
import org.junit.Assert.*
import org.junit.Test

class DesktopKeyboardViewTest {
    @Test fun keyboardKeepsDesktopPixelsReadableWithoutResizingTheSource() {
        val view = DesktopKeyboardView(1232, 770, VideoZoom(), .5f, .33f)
        val size = view.size(2200)
        assertEquals(1232, size.width)
        assertEquals(770, size.height)
        val focused = view.focused(size, 2200, 184)
        assertEquals(1f, focused.scale, 0f)
        assertEquals(0f, focused.x, 0f)
        assertEquals(130.9f, focused.y, .01f)
    }
}
