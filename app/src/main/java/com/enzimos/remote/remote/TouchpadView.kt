package com.enzimos.remote.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.google.android.material.color.MaterialColors
import kotlin.math.hypot

/**
 * Laptop-style touchpad for the computer's desktop: slide to move, tap to
 * click, tap then slide to drag, two fingers to scroll, two-finger tap for a
 * right click. Buttons are only pressed for resolved clicks and drags.
 */
class TouchpadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var input: DesktopInput? = null
    var speed = 1.6f

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val density = resources.displayMetrics.density
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@TouchpadView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        textAlign = Paint.Align.CENTER
        textSize = 15 * resources.displayMetrics.scaledDensity
        alpha = 140
    }

    private enum class Mode { IDLE, PENDING, MOVE, DRAG, SCROLL, DONE }
    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var downAt = 0L
    private var lastTapAt = 0L
    private var twoFingerTap = false

    init {
        isFocusable = true
        contentDescription = context.getString(com.enzimos.remote.R.string.remote_touchpad_hint)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText(context.getString(com.enzimos.remote.R.string.remote_touchpad_hint), width / 2f, height / 2f, hint)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val input = input ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.PENDING
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                downAt = event.eventTime; lastTime = event.eventTime
                twoFingerTap = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2 && mode == Mode.PENDING) {
                mode = Mode.SCROLL
                twoFingerTap = event.eventTime - downAt < TAP_MS
                lastX = midX(event); lastY = midY(event); downX = lastX; downY = lastY
            } else if (mode != Mode.DRAG && mode != Mode.MOVE) {
                mode = Mode.DONE
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PENDING, Mode.MOVE, Mode.DRAG -> {
                    if (mode == Mode.PENDING && hypot(event.x - downX, event.y - downY) > slop) {
                        // Tap then slide: drag with the left button held.
                        if (event.eventTime - lastTapAt < DOUBLE_TAP_MS) {
                            mode = Mode.DRAG
                            input.button("left", true)
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } else {
                            mode = Mode.MOVE
                        }
                        lastX = event.x; lastY = event.y; lastTime = event.eventTime
                    }
                    if (mode != Mode.PENDING) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        val elapsed = (event.eventTime - lastTime).coerceIn(8, 100)
                        val velocity = hypot(dx, dy) / density / elapsed * 1000
                        val gain = speed * (1f + ((velocity - 150f).coerceAtLeast(0f) / 600f)).coerceAtMost(2.5f)
                        input.move((dx / density * gain).toDouble(), (dy / density * gain).toDouble())
                        lastX = event.x; lastY = event.y; lastTime = event.eventTime
                    }
                }
                Mode.SCROLL -> if (event.pointerCount >= 2) {
                    val x = midX(event); val y = midY(event)
                    if (hypot(x - downX, y - downY) > slop) twoFingerTap = false
                    // Natural scrolling: content follows the fingers.
                    input.scroll(((lastX - x) / density * SCROLL_GAIN).toDouble(), ((lastY - y) / density * SCROLL_GAIN).toDouble())
                    lastX = x; lastY = y
                }
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.SCROLL) {
                if (twoFingerTap && event.eventTime - downAt < TAP_MS * 2) {
                    input.click("right")
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                } else {
                    input.scrollStop()
                }
                mode = Mode.DONE
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.PENDING -> if (event.eventTime - downAt < TAP_MS) {
                        input.click("left")
                        lastTapAt = event.eventTime
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    Mode.DRAG -> input.button("left", false)
                    else -> Unit
                }
                mode = Mode.IDLE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAG) input.button("left", false)
                mode = Mode.IDLE
            }
        }
        return true
    }

    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f

    private companion object {
        const val TAP_MS = 220L
        const val DOUBLE_TAP_MS = 300L
        const val SCROLL_GAIN = 2.2f
    }
}
