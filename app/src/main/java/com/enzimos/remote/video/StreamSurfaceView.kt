package com.enzimos.remote.video

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.enzimos.remote.input.RemoteInputConnection
import org.webrtc.SurfaceViewRenderer

/** Exposes an input connection so the IME can send committed text to the remote session. */
class StreamSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceViewRenderer(context, attrs) {

    /** Callback for text committed by the IME. */
    var onTextMessage: ((String) -> Unit)? = null
    private var inputConnection: RemoteInputConnection? = null

    init {
        // Make focusable so the IME can attach
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE

        inputConnection?.closeConnection()
        return RemoteInputConnection(this) { msg ->
            onTextMessage?.invoke(msg)
        }.also { inputConnection = it }
    }

    fun finishInput() {
        inputConnection?.finishComposingText()
        inputConnection?.closeConnection()
        inputConnection = null
        hideKeyboard()
    }

    override fun onDetachedFromWindow() {
        inputConnection?.closeConnection()
        inputConnection = null
        super.onDetachedFromWindow()
    }

    /**
     * Show the soft keyboard.
     */
    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        keyboardVisible = true
    }

    /**
     * Hide the soft keyboard.
     */
    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
        keyboardVisible = false
    }

    /** Whether the keyboard is currently visible (best-effort tracking). */
    private var keyboardVisible = false

    /**
     * Toggle the soft keyboard. Tracks visibility state internally since
     * [InputMethodManager.isAcceptingText] is unreliable across devices.
     */
    fun toggleKeyboard() {
        val visible = androidx.core.view.ViewCompat.getRootWindowInsets(this)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) ?: keyboardVisible
        if (visible) hideKeyboard() else showKeyboard()
    }
}
