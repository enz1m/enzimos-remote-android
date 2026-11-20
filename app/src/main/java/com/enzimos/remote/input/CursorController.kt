package com.enzimos.remote.input

import android.view.InputDevice
import android.view.MotionEvent

/** Sends cursor visibility changes when switching between direct touch and pointer input. */
class CursorController(
    private val onVisibilityChange: (String) -> Unit,
) {
    @Volatile private var cursorVisible = false
    @Volatile private var lastInputSource = -1

    /**
     * Update cursor visibility based on the input event source.
     *
     * @param event The MotionEvent that triggered this check
     * @return true if cursor visibility changed
     */
    fun onInputEvent(event: MotionEvent): Boolean {
        val source = event.source
        if (source == lastInputSource) return false
        lastInputSource = source

        val shouldShow = isMouseInput(source)

        if (shouldShow != cursorVisible) {
            cursorVisible = shouldShow
            onVisibilityChange(ControlMessageSerializer.cursorVisibility(cursorVisible))
            return true
        }
        return false
    }

    /** Check if the current cursor is visible on the remote side. */
    fun isRemoteCursorVisible(): Boolean = cursorVisible

    /**
     * Force cursor visibility to a specific state.
     *
     * @param visible Whether the cursor should be visible
     * @return true if the state changed
     */
    fun setVisible(visible: Boolean): Boolean {
        if (visible == cursorVisible) return false
        cursorVisible = visible
        onVisibilityChange(ControlMessageSerializer.cursorVisibility(cursorVisible))
        return true
    }

    companion object {
        /**
         * Determine if the input source is a mouse/trackpad.
         *
         * @param source The MotionEvent source flags
         * @return true if the input is from a mouse or trackpad
         */
        fun isMouseInput(source: Int): Boolean {
            return (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
        }

        /**
         * Determine if the input source is touch (finger).
         */
        fun isTouchInput(source: Int): Boolean {
            return (source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
        }

        /**
         * Determine if the input source is a stylus.
         */
        fun isStylusInput(source: Int): Boolean {
            return (source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
        }
    }
}
