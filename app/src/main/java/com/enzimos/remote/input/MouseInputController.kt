package com.enzimos.remote.input

import android.view.MotionEvent
import android.view.View

/** Mouse buttons are immediate; they do not use the finger's hold-to-drag policy. */
class MouseInputController(
    private val view: View,
    private val sessionSize: () -> Pair<Int, Int>,
    private val channel: () -> ((String, Boolean) -> Boolean)?,
    private val scrollSpeed: () -> Float,
    private val beforeInput: () -> Unit,
) {
    private var buttons = 0
    private var sink: ((String, Boolean) -> Boolean)? = null
    private val buttonCodes = listOf(MotionEvent.BUTTON_PRIMARY to GestureMapper.BTN_LEFT,
        MotionEvent.BUTTON_SECONDARY to GestureMapper.BTN_RIGHT, MotionEvent.BUTTON_TERTIARY to GestureMapper.BTN_MIDDLE)

    fun onEvent(event: MotionEvent): Boolean {
        if (!CursorController.isMouseInput(event.source)) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { cancel(); return true }
        if (event.actionMasked !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_SCROLL,
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)) return false
        beforeInput()
        if (buttons == 0) sink = channel()
        if (sink == null || view.width <= 0 || view.height <= 0) return true
        val size = sessionSize()
        val x = (event.x.toDouble() / view.width * size.first).coerceIn(0.0, (size.first - 1).coerceAtLeast(0).toDouble())
        val y = (event.y.toDouble() / view.height * size.second).coerceIn(0.0, (size.second - 1).coerceAtLeast(0).toDouble())
        var next = event.buttonState and (MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (next == 0) next = MotionEvent.BUTTON_PRIMARY
            MotionEvent.ACTION_UP -> next = 0
            MotionEvent.ACTION_BUTTON_PRESS -> next = next or event.actionButton
            MotionEvent.ACTION_BUTTON_RELEASE -> next = next and event.actionButton.inv()
        }
        val change = buttons xor next
        sink?.invoke(ControlMessageSerializer.pointer(x, y), change != 0 || event.actionMasked == MotionEvent.ACTION_SCROLL)
        for ((mask, code) in buttonCodes) {
            if (change and mask != 0) sink?.invoke(ControlMessageSerializer.button(code, next and mask != 0), true)
        }
        buttons = next
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val speed = scrollSpeed().toDouble()
            val dx = event.getAxisValue(MotionEvent.AXIS_HSCROLL) * speed
            val dy = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * speed
            if (dx != 0.0 || dy != 0.0) sink?.invoke(ControlMessageSerializer.scroll(dx, dy), false)
        }
        return true
    }

    fun cancel() {
        if (buttons and MotionEvent.BUTTON_PRIMARY != 0) {
            sink?.invoke(ControlMessageSerializer.key(1, true), true)
            sink?.invoke(ControlMessageSerializer.key(1, false), true)
        }
        for ((mask, code) in buttonCodes) if (buttons and mask != 0) sink?.invoke(ControlMessageSerializer.button(code, false), true)
        buttons = 0
        sink = null
    }
}
