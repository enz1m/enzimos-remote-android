package com.enzimos.remote.input

import android.view.MotionEvent

/** Handles physical mouse and trackpad input separately from touch gestures. */
class ScrollHandler {

    companion object {
        /** Left mouse button (BTN_LEFT in Linux). */
        const val BTN_LEFT = 272
        /** Right mouse button (BTN_RIGHT in Linux). */
        const val BTN_RIGHT = 273
        /** Middle mouse button (BTN_MIDDLE in Linux). */
        const val BTN_MIDDLE = 274
    }

    /** Scroll speed multiplier (1.0 = normal). Set from app preferences. */
    var scrollSpeedMultiplier: Float = 1.0f

    /**
     * Process a generic motion event (scroll, hover).
     *
     * @param event The MotionEvent
     * @return List of control protocol JSON messages (may be empty)
     */
    fun onGenericMotionEvent(event: MotionEvent): List<String> {
        val messages = mutableListOf<String>()

        when (event.action) {
            MotionEvent.ACTION_SCROLL -> {
                val dx = event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble() * scrollSpeedMultiplier
                val dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble() * scrollSpeedMultiplier
                if (dx != 0.0 || dy != 0.0) {
                    messages.add(ControlMessageSerializer.scroll(dx, dy))
                }
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                // Track relative motion for external mice/trackpads
                // Note: Relative motion requires pointer capture (API 26+)
                // which is not enabled by default. This handles trackpad
                // gestures that come as hover events.
            }
        }

        return messages
    }

    /**
     * Map Android mouse button to Linux button code.
     *
     * @param androidButton The Android button constant (e.g., MotionEvent.BUTTON_PRIMARY)
     * @return Linux button code, or null if unmapped
     */
    fun mapButton(androidButton: Int): Int? = when (androidButton) {
        MotionEvent.BUTTON_PRIMARY -> BTN_LEFT
        MotionEvent.BUTTON_SECONDARY -> BTN_RIGHT
        MotionEvent.BUTTON_TERTIARY -> BTN_MIDDLE
        else -> null
    }

    /**
     * Process a mouse button event.
     *
     * @param event The MotionEvent (ACTION_BUTTON_PRESS or ACTION_BUTTON_RELEASE)
     * @return Control protocol JSON message, or null if unmapped
     */
    fun onButtonEvent(event: MotionEvent): String? {
        val pressed = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
        val code = mapButton(event.actionButton) ?: return null
        return ControlMessageSerializer.button(code, pressed)
    }
}
