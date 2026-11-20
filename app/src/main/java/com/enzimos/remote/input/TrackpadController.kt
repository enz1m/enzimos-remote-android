package com.enzimos.remote.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Laptop-trackpad input for apps that need a real cursor: one finger moves the
 * cursor, tap clicks, tap then slide (or hold then slide) drags, two fingers
 * scroll, two-finger tap right-clicks. Buttons are only pressed for a resolved
 * click or drag, never on finger-down. The computer draws the pointer into the
 * stream, so it shows the app's own shapes (text, resize) without a local copy.
 */
class TrackpadController(
    private val view: View,
    private val sessionSize: () -> Pair<Int, Int>,
    private val channel: () -> ((String, Boolean) -> Boolean)?,
    private val scrollSpeed: () -> Float,
    private val onDragReady: () -> Unit,
) {
    private val config = ViewConfiguration.get(view.context)
    private val slop = config.scaledTouchSlop.toFloat()
    private val handler = Handler(Looper.getMainLooper())
    private var sink: ((String, Boolean) -> Boolean)? = null

    /** Cursor in stream pixels; starts centered. */
    var cursorX = -1.0; private set
    var cursorY = -1.0; private set

    private enum class Mode { IDLE, PENDING, MOVE, DRAG, SCROLL, DONE }
    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var lastTapUpAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var tapDragCandidate = false
    private var twoFingerTap = false
    private var buttonDown = false
    private var lastMoveAt = 0L

    private val holdToDrag = Runnable {
        if (mode == Mode.PENDING) {
            mode = Mode.DRAG
            press(true)
            onDragReady()
        }
    }

    /** Start in the middle of the app (shown there right away), or keep the last position. */
    fun ensureCursor() {
        val (w, h) = sessionSize()
        if (w <= 0 || h <= 0) return
        val fresh = cursorX < 0 || cursorY < 0
        if (fresh) { cursorX = w / 2.0; cursorY = h / 2.0 }
        cursorX = cursorX.coerceIn(0.0, w - 1.0); cursorY = cursorY.coerceIn(0.0, h - 1.0)
        if (fresh) (sink ?: channel())?.invoke(ControlMessageSerializer.pointer(cursorX, cursorY), false)
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (view.width <= 0 || view.height <= 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sink = channel()
                ensureCursor()
                mode = Mode.PENDING
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                downAt = event.eventTime
                twoFingerTap = false
                tapDragCandidate = event.eventTime - lastTapUpAt < DOUBLE_TAP_MS &&
                    hypot(event.x - lastTapX, event.y - lastTapY) < slop * 4
                handler.postDelayed(holdToDrag, HOLD_TO_DRAG_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(holdToDrag)
                if (event.pointerCount == 2 && mode == Mode.PENDING && !buttonDown) {
                    mode = Mode.SCROLL
                    twoFingerTap = event.eventTime - downAt < TAP_MS
                    lastX = midX(event); lastY = midY(event)
                    downX = lastX; downY = lastY
                } else if (mode != Mode.DRAG && mode != Mode.MOVE) {
                    mode = Mode.DONE
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PENDING, Mode.MOVE, Mode.DRAG -> {
                    if (mode == Mode.PENDING && hypot(event.x - downX, event.y - downY) > slop) {
                        handler.removeCallbacks(holdToDrag)
                        if (tapDragCandidate) { mode = Mode.DRAG; press(true) } else mode = Mode.MOVE
                        lastX = event.x; lastY = event.y
                    }
                    if (mode != Mode.PENDING) moveBy(event.x - lastX, event.y - lastY, event)
                    lastX = event.x; lastY = event.y
                }
                Mode.SCROLL -> if (event.pointerCount >= 2) {
                    val x = midX(event); val y = midY(event)
                    if (hypot(x - downX, y - downY) > slop) twoFingerTap = false
                    scroll(lastX - x, lastY - y)
                    lastX = x; lastY = y
                }
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.SCROLL) {
                if (twoFingerTap && event.eventTime - downAt < TAP_MS * 2) click(GestureMapper.BTN_RIGHT)
                else scroll(0f, 0f, stop = true)
                mode = Mode.DONE
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdToDrag)
                when (mode) {
                    Mode.PENDING -> if (event.eventTime - downAt < TAP_MS) {
                        click(GestureMapper.BTN_LEFT)
                        lastTapUpAt = if (tapDragCandidate) 0L else event.eventTime
                        lastTapX = event.x; lastTapY = event.y
                    }
                    Mode.DRAG -> press(false)
                    Mode.SCROLL -> scroll(0f, 0f, stop = true)
                    else -> Unit
                }
                mode = Mode.IDLE
                sink = null
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    fun cancel() {
        handler.removeCallbacks(holdToDrag)
        if (buttonDown) press(false)
        if (mode == Mode.SCROLL) scroll(0f, 0f, stop = true)
        mode = Mode.IDLE
        sink = null
    }

    private fun moveBy(dx: Float, dy: Float, event: MotionEvent) {
        val (w, h) = sessionSize()
        if (w <= 0 || h <= 0) return
        // Gentle acceleration: slow movements stay precise, flicks cross the screen.
        val elapsed = (event.eventTime - lastMoveAt).coerceIn(8, 100)
        lastMoveAt = event.eventTime
        val speed = hypot(dx, dy) / elapsed * 1000 / view.resources.displayMetrics.density
        val gain = (1.0 + (speed - 150.0).coerceAtLeast(0.0) / 600.0).coerceAtMost(2.5) * BASE_GAIN
        val perPixel = w.toDouble() / view.width
        cursorX = (cursorX + dx * perPixel * gain).coerceIn(0.0, w - 1.0)
        cursorY = (cursorY + dy * perPixel * gain).coerceIn(0.0, h - 1.0)
        sink?.invoke(ControlMessageSerializer.pointer(cursorX, cursorY), false)
    }

    private fun scroll(dx: Float, dy: Float, stop: Boolean = false) {
        val (w, _) = sessionSize()
        val perPixel = if (view.width > 0) w.toDouble() / view.width else 1.0
        val speed = scrollSpeed().toDouble().coerceIn(0.1, 3.0)
        sink?.invoke(ControlMessageSerializer.scrollPixels(cursorX, cursorY,
            (dx * perPixel * speed).coerceIn(-8192.0, 8192.0), (dy * perPixel * speed).coerceIn(-8192.0, 8192.0), stop), stop)
    }

    private fun click(code: Int) {
        val target = sink ?: channel() ?: return
        target(ControlMessageSerializer.pointer(cursorX, cursorY), true)
        target(ControlMessageSerializer.button(code, true), true)
        target(ControlMessageSerializer.button(code, false), true)
    }

    private fun press(down: Boolean) {
        val target = sink ?: channel()
        if (down) target?.invoke(ControlMessageSerializer.pointer(cursorX, cursorY), true)
        target?.invoke(ControlMessageSerializer.button(GestureMapper.BTN_LEFT, down), true)
        buttonDown = down
    }

    private fun midX(event: MotionEvent) = (event.getX(0) + event.getX(1)) / 2f
    private fun midY(event: MotionEvent) = (event.getY(0) + event.getY(1)) / 2f

    companion object {
        const val TAP_MS = 220L
        const val DOUBLE_TAP_MS = 300L
        const val HOLD_TO_DRAG_MS = 450L
        const val BASE_GAIN = 1.2
    }
}
