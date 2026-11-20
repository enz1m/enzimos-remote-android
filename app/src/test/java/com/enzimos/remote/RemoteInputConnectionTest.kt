package com.enzimos.remote

import android.app.Application
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import org.robolectric.RuntimeEnvironment
import com.enzimos.remote.input.ControlMessageSerializer
import com.enzimos.remote.input.RemoteInputConnection
import com.enzimos.remote.video.StreamSurfaceView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
class RemoteInputConnectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sent = mutableListOf<String>()
    private fun connection() = RemoteInputConnection(View(context), sent::add)
    private fun text(value: String) = ControlMessageSerializer.text(value)
    private fun key(code: Int, down: Boolean) = ControlMessageSerializer.key(code, down)

    @Test fun repeatedIdenticalTapsAreNotDeduplicated() {
        val c = connection()
        repeat(5) { c.commitText("a", 1) }
        assertEquals(List(5) { text("a") }, sent)
        assertEquals("", c.getTextBeforeCursor(100, 0).toString())
    }

    @Test fun compositionUpdatesCommitExactlyOnce() {
        val c = connection()
        listOf("h", "he", "hello").forEach { c.setComposingText(it, 1) }
        assertTrue(sent.isEmpty())
        assertEquals("hello", c.getTextBeforeCursor(20, 0).toString())
        c.commitText("hello", 1)
        c.finishComposingText()
        assertEquals(listOf(text("hello")), sent)
    }

    @Test fun closeDiscardsDraftAndRejectsLateImeCalls() {
        val c = connection()
        c.setComposingText("draft", 1)
        c.closeConnection()
        assertFalse(c.commitText("stale", 1))
        assertFalse(c.setComposingText("stale", 1))
        assertFalse(c.finishComposingText())
        assertFalse(c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        assertFalse(c.deleteSurroundingText(1, 0))
        assertTrue(sent.isEmpty())
    }

    @Test fun committedNewlinesAndTabsRemainEditingKeys() {
        connection().commitText("a\r\nb\tc", 1)
        assertEquals(listOf(text("a"), key(28, true), key(28, false), text("b"), key(15, true), key(15, false), text("c")), sent)
    }

    @Test fun editorRestartClosesTheOldConnection() {
        val view = StreamSurfaceView(context)
        view.onTextMessage = sent::add
        val first = view.onCreateInputConnection(EditorInfo())
        first.setComposingText("old", 1)
        val second = view.onCreateInputConnection(EditorInfo())
        assertFalse(first.commitText("stale", 1))
        second.commitText("new", 1)
        assertEquals(listOf(text("new")), sent)
    }
}
