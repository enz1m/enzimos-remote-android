package com.enzimos.remote.input

/** Converts touch gestures to Linux desktop input sequences. */
object GestureMapper {

    /** Linux button codes matching the host protocol. */
    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112

    /** Actions that can result from a gesture. */
    sealed class Action {
        /** Single left click at position. */
        data class LeftClick(val x: Double, val y: Double) : Action()

        /** Double left click at position. */
        data class DoubleClick(val x: Double, val y: Double) : Action()

        /** Right click at position. */
        data class RightClick(val x: Double, val y: Double) : Action()

        /** Middle click at position. */
        data class MiddleClick(val x: Double, val y: Double) : Action()

        /** Scroll event. */
        data class Scroll(val dx: Double, val dy: Double) : Action()

        /** Zoom (ctrl+scroll). */
        data class Zoom(val factor: Double) : Action()

        /** No action (gesture not recognized or in progress). */
        data object None : Action()
    }

    /**
     * Generate the control message sequence for a left click.
     *
     * @param x Normalized x position
     * @param y Normalized y position
     * @return List of JSON control messages (button down, button up)
     */
    fun leftClickMessages(x: Double, y: Double): List<String> {
        return listOf(
            ControlMessageSerializer.pointer(x, y),
            ControlMessageSerializer.button(BTN_LEFT, pressed = true),
            ControlMessageSerializer.button(BTN_LEFT, pressed = false),
        )
    }

    /**
     * Generate the control message sequence for a right click.
     *
     * @param x Normalized x position
     * @param y Normalized y position
     * @return List of JSON control messages
     */
    fun rightClickMessages(x: Double, y: Double): List<String> {
        return listOf(
            ControlMessageSerializer.pointer(x, y),
            ControlMessageSerializer.button(BTN_RIGHT, pressed = true),
            ControlMessageSerializer.button(BTN_RIGHT, pressed = false),
        )
    }

    /**
     * Generate the control message sequence for a middle click.
     *
     * @param x Normalized x position
     * @param y Normalized y position
     * @return List of JSON control messages
     */
    fun middleClickMessages(x: Double, y: Double): List<String> {
        return listOf(
            ControlMessageSerializer.pointer(x, y),
            ControlMessageSerializer.button(BTN_MIDDLE, pressed = true),
            ControlMessageSerializer.button(BTN_MIDDLE, pressed = false),
        )
    }

    /**
     * Generate control messages for a double click.
     *
     * @param x Normalized x position
     * @param y Normalized y position
     * @return List of JSON control messages (two click sequences)
     */
    fun doubleClickMessages(x: Double, y: Double): List<String> {
        return leftClickMessages(x, y) + leftClickMessages(x, y)
    }

    /**
     * Generate a scroll control message.
     */
    fun scrollMessage(dx: Double, dy: Double): String {
        return ControlMessageSerializer.scroll(dx, dy)
    }

    /**
     * Map a pinch zoom factor to scroll messages with Ctrl held.
     *
     * @param factor Zoom factor (>1 = zoom in, <1 = zoom out)
     * @return List of control messages (Ctrl down, scroll, Ctrl up)
     */
    fun zoomMessages(factor: Double): List<String> {
        val scrollDy = if (factor > 1.0) -1.0 else 1.0
        return listOf(
            // KEY_LEFTCTRL = 29
            ControlMessageSerializer.key(29, pressed = true),
            ControlMessageSerializer.scroll(0.0, scrollDy),
            ControlMessageSerializer.key(29, pressed = false),
        )
    }

    /**
     * Determine the number of active pointers from a pointer count.
     *
     * @param pointerCount Number of active touch points
     * @return The button code to use for a tap gesture
     */
    fun buttonForTapCount(pointerCount: Int): Int {
        return when (pointerCount) {
            1 -> BTN_LEFT
            2 -> BTN_RIGHT
            3 -> BTN_MIDDLE
            else -> BTN_LEFT
        }
    }
}
