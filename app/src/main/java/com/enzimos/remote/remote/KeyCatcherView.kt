package com.enzimos.remote.remote

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Invisible target for the soft keyboard. One input path: committed text is
 * sent as `text`, deletions and editor keys as evdev keys; nothing is also
 * synthesized as key events.
 */
class KeyCatcherView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var input: DesktopInput? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (text.isNotEmpty()) input?.text(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 64)) { input?.tap(DesktopInput.KEY_BACKSPACE) }
                repeat(afterLength.coerceIn(0, 64)) { input?.tap(DesktopInput.KEY_DELETE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> input?.tap(DesktopInput.KEY_BACKSPACE)
                    KeyEvent.KEYCODE_FORWARD_DEL -> input?.tap(DesktopInput.KEY_DELETE)
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> input?.tap(DesktopInput.KEY_ENTER)
                    KeyEvent.KEYCODE_TAB -> input?.tap(DesktopInput.KEY_TAB)
                    else -> event.unicodeChar.takeIf { it > 0 }?.let { input?.text(it.toChar().toString()) }
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                input?.tap(DesktopInput.KEY_ENTER)
                return true
            }
        }
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (hasFocus() && imm.isActive(this)) {
            imm.hideSoftInputFromWindow(windowToken, 0)
            clearFocus()
        } else {
            requestFocus()
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
