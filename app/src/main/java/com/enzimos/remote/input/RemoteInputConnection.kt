package com.enzimos.remote.input

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

/**
 * Append-only IME bridge. Only uncommitted composition is mirrored locally;
 * the remote editor's text and selection are not available to Android.
 */
class RemoteInputConnection(
    targetView: View,
    private val onText: (String) -> Unit,
) : BaseInputConnection(targetView, true) {
    // Full-editor mode prevents BaseInputConnection from synthesizing key events
    // for text that we already send through the control channel.
    private val draft = SpannableStringBuilder()
    private var closed = false
    private val localKeyUps = mutableSetOf<Int>()

    init { Selection.setSelection(draft, 0) }

    override fun getEditable(): Editable = draft
    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed || text == null) return false
        val result = super.commitText(text, newCursorPosition)
        flushDraft()
        return result
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed || text == null) return false
        return super.setComposingText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        if (closed) return false
        val result = super.finishComposingText()
        flushDraft()
        return result
    }

    override fun closeConnection() {
        closed = true
        clearDraft()
        localKeyUps.clear()
        super.closeConnection()
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        deleteAround(beforeLength, afterLength, codePoints = false)

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteAround(beforeLength, afterLength, codePoints = true)

    private fun deleteAround(before: Int, after: Int, codePoints: Boolean): Boolean {
        if (closed || before !in 0..4096 || after !in 0..4096) return false
        var start = minOf(Selection.getSelectionStart(draft), Selection.getSelectionEnd(draft)).coerceAtLeast(0)
        var end = maxOf(Selection.getSelectionStart(draft), Selection.getSelectionEnd(draft)).coerceAtLeast(0)
        val composingStart = getComposingSpanStart(draft)
        val composingEnd = getComposingSpanEnd(draft)
        if (composingStart >= 0 && composingEnd >= 0) {
            start = minOf(start, composingStart)
            end = maxOf(end, composingEnd)
        }
        val localBefore = if (codePoints) Character.codePointCount(draft, 0, start) else start
        val localAfter = if (codePoints) Character.codePointCount(draft, end, draft.length) else draft.length - end
        val result = if (codePoints) super.deleteSurroundingTextInCodePoints(before, after)
            else super.deleteSurroundingText(before, after)
        if (result) {
            repeat((before - localBefore).coerceAtLeast(0)) { tapKey(14) }
            repeat((after - localAfter).coerceAtLeast(0)) { tapKey(111) }
        }
        return result
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (closed) return false
        if (event.action == KeyEvent.ACTION_UP && localKeyUps.remove(event.keyCode)) return true
        val deletion = event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL
        if (event.action == KeyEvent.ACTION_DOWN && deletion && draft.isNotEmpty()) {
            val start = minOf(Selection.getSelectionStart(draft), Selection.getSelectionEnd(draft)).coerceAtLeast(0)
            val end = maxOf(Selection.getSelectionStart(draft), Selection.getSelectionEnd(draft)).coerceAtLeast(0)
            when {
                start != end -> draft.delete(start, end)
                event.keyCode == KeyEvent.KEYCODE_DEL && start > 0 -> {
                    val from = Character.offsetByCodePoints(draft, start, -1)
                    draft.delete(from, start)
                    Selection.setSelection(draft, from)
                }
                event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL && end < draft.length ->
                    draft.delete(end, Character.offsetByCodePoints(draft, end, 1))
            }
            localKeyUps.add(event.keyCode)
            return true
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
            finishComposingText()
            sendCommitted(event.characters)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val code = KeyboardHandler.androidToLinuxKeycode(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            localKeyUps.remove(event.keyCode)
            finishComposingText()
        }
        onText(ControlMessageSerializer.key(code, event.action == KeyEvent.ACTION_DOWN))
        return true
    }

    private fun flushDraft() {
        val text = draft.toString()
        clearDraft()
        if (text.isNotEmpty()) sendCommitted(text)
    }

    private fun clearDraft() {
        removeComposingSpans(draft)
        draft.clear()
        Selection.setSelection(draft, 0)
    }

    private fun sendCommitted(text: String) {
        // The host's line-based text command cannot carry newlines. Keep editing
        // keys distinct from text rather than turning Enter into a space.
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        var start = 0
        normalized.forEachIndexed { index, char ->
            if (char == '\n' || char == '\t') {
                if (index > start) onText(ControlMessageSerializer.text(normalized.substring(start, index)))
                tapKey(if (char == '\n') 28 else 15)
                start = index + 1
            }
        }
        if (start < normalized.length) onText(ControlMessageSerializer.text(normalized.substring(start)))
    }

    private fun tapKey(code: Int) {
        onText(ControlMessageSerializer.key(code, true))
        onText(ControlMessageSerializer.key(code, false))
    }
}
