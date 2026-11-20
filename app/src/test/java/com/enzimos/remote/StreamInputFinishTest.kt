package com.enzimos.remote

import android.view.inputmethod.EditorInfo
import com.enzimos.remote.video.StreamSurfaceView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class StreamInputFinishTest {
    @Test fun leavingCommitsTheDraftBeforeClosingItsInputConnection() {
        val surface = StreamSurfaceView(RuntimeEnvironment.getApplication())
        val messages = mutableListOf<String>()
        surface.onTextMessage = { messages.add(it) }
        val connection = surface.onCreateInputConnection(EditorInfo())
        assertTrue(connection.setComposingText("draft", 1))
        assertTrue(messages.isEmpty())
        surface.finishInput()
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("draft"))
        assertFalse(connection.commitText("late", 1))
        surface.finishInput()
        assertEquals(1, messages.size)
        surface.release()
    }
}
