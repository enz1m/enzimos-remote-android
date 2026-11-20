package com.enzimos.remote.input

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/** Main-thread finger gestures. A gesture and its inertia stay bound to the channel where they began. */
class AppTouchController(
    private val view: View,
    private val sessionSize: () -> Pair<Int, Int>,
    private val channel: () -> ((String, Boolean) -> Boolean)?,
    private val scrollSpeed: () -> Float,
    private val showHold: (Float, Float, Long) -> Unit,
    private val hideHold: () -> Unit,
    private val feedback: (MobileGesture.Feedback) -> Unit,
) {
    private val config = ViewConfiguration.get(view.context)
    private val handler = Handler(Looper.getMainLooper())
    private val scroller = OverScroller(view.context)
    private var machine = newMachine()
    private var sink: ((String, Boolean) -> Boolean)? = null
    private var velocity: VelocityTracker? = null
    private var pointerId = -1
    private var blocked = false
    private var scaleX = 1.0
    private var scaleY = 1.0
    private var speed = 1.0
    private var flingAnchor: MobileGesture.Point? = null
    private var flingX = 0
    private var flingY = 0

    private fun newMachine() = MobileGesture(
        config.scaledTouchSlop.toDouble() / view.scaleX.coerceAtLeast(1f),
        ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(500),
    )

    private val holdTimer = Runnable { dispatch(machine.advance(SystemClock.uptimeMillis())) }
    private val flingFrame = object : Runnable {
        override fun run() {
            val anchor = flingAnchor ?: return
            if (!scroller.computeScrollOffset()) {
                finishFling()
                return
            }
            val dx = scroller.currX - flingX
            val dy = scroller.currY - flingY
            flingX = scroller.currX
            flingY = scroller.currY
            if ((dx != 0 || dy != 0) && !sendScroll(anchor, dx.toDouble(), dy.toDouble())) {
                finishFling()
                return
            }
            view.postOnAnimation(this)
        }
    }

    private fun point(event: MotionEvent, index: Int) =
        MobileGesture.Point(event.getX(index).toDouble(), event.getY(index).toDouble())

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val stoppedFling = flingAnchor != null
                cancel()
                blocked = stoppedFling
                if (stoppedFling) return true
                val size = sessionSize()
                if (view.width <= 0 || view.height <= 0 || size.first <= 0 || size.second <= 0) {
                    blocked = true
                    return true
                }
                sink = channel()
                if (sink == null) { blocked = true; return true }
                // Android already inverse-transforms MotionEvent into the scaled View's local coordinates.
                scaleX = size.first.toDouble() / view.width
                scaleY = size.second.toDouble() / view.height
                speed = scrollSpeed().toDouble().coerceIn(0.1, 3.0)
                machine = newMachine()
                pointerId = event.getPointerId(0)
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                dispatch(machine.down(point(event, 0), event.eventTime))
                showHold(event.x, event.y, machine.menuDelayMs)
                handler.postAtTime(holdTimer, event.eventTime + machine.dragDelayMs)
                handler.postAtTime(holdTimer, event.eventTime + machine.menuDelayMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancel()
                blocked = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (blocked) return true
                val index = event.findPointerIndex(pointerId)
                if (index < 0) { cancel(); blocked = true; return true }
                velocity?.addMovement(event)
                for (h in 0 until event.historySize) {
                    dispatch(machine.move(MobileGesture.Point(event.getHistoricalX(index, h).toDouble(),
                        event.getHistoricalY(index, h).toDouble()), event.getHistoricalEventTime(h)))
                }
                dispatch(machine.move(point(event, index), event.eventTime))
                if (machine.mode != MobileGesture.Mode.PENDING && machine.mode != MobileGesture.Mode.DRAG_READY) {
                    handler.removeCallbacks(holdTimer)
                    hideHold()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (blocked) { cancel(); return true }
                val index = event.findPointerIndex(pointerId)
                val bounds = Rect()
                val outside = view.getGlobalVisibleRect(bounds) && !bounds.contains(event.rawX.toInt(), event.rawY.toInt())
                if (index < 0 || outside) { cancel(); return true }
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity / view.scaleX.coerceAtLeast(1f))
                val vx = velocity?.getXVelocity(pointerId) ?: 0f
                val vy = velocity?.getYVelocity(pointerId) ?: 0f
                dispatch(machine.up(point(event, index), event.eventTime), vx, vy)
                releaseTouch()
                hideHold()
                if (flingAnchor == null) sink = null
                view.performClick()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
            // A remaining finger from a pinch must not become a new click or drag.
            MotionEvent.ACTION_POINTER_UP -> { if (!blocked) { cancel(); blocked = true } }
        }
        return true
    }

    private fun dispatch(actions: List<MobileGesture.Action>, vx: Float = 0f, vy: Float = 0f) {
        for (action in actions) {
            when (action) {
                is MobileGesture.Action.Pointer -> sink?.invoke(ControlMessageSerializer.pointer(
                    action.at.x * scaleX, action.at.y * scaleY), action.critical)
                is MobileGesture.Action.Button -> sink?.invoke(ControlMessageSerializer.button(GestureMapper.BTN_LEFT, action.pressed), true)
                is MobileGesture.Action.Click -> {
                    val x = action.at.x * scaleX
                    val y = action.at.y * scaleY
                    if (action.right) GestureMapper.rightClickMessages(x, y).forEach { sink?.invoke(it, true) }
                    else sink?.invoke(ControlMessageSerializer.pointer(x, y, click = true), true)
                }
                is MobileGesture.Action.Scroll -> sendScroll(action.at, action.dx, action.dy)
                is MobileGesture.Action.ScrollStop -> {
                    val threshold = config.scaledMinimumFlingVelocity / view.scaleX.coerceAtLeast(1f)
                    if (abs(vx) >= threshold || abs(vy) >= threshold) {
                        flingAnchor = action.at
                        flingX = 0; flingY = 0
                        scroller.fling(0, 0, (-vx).toInt(), (-vy).toInt(), -1_000_000, 1_000_000, -1_000_000, 1_000_000)
                        view.postOnAnimation(flingFrame)
                    } else sendScroll(action.at, 0.0, 0.0, stop = true)
                }
                is MobileGesture.Action.Notify -> feedback(action.feedback)
                MobileGesture.Action.Escape -> {
                    sink?.invoke(ControlMessageSerializer.key(1, true), true)
                    sink?.invoke(ControlMessageSerializer.key(1, false), true)
                }
            }
        }
    }

    private fun sendScroll(at: MobileGesture.Point, dx: Double, dy: Double, stop: Boolean = false): Boolean =
        sink?.invoke(ControlMessageSerializer.scrollPixels(at.x * scaleX, at.y * scaleY,
            (dx * scaleX * speed).coerceIn(-8192.0, 8192.0), (dy * scaleY * speed).coerceIn(-8192.0, 8192.0), stop), stop) ?: false

    private fun releaseTouch() {
        handler.removeCallbacks(holdTimer)
        velocity?.recycle()
        velocity = null
        pointerId = -1
    }

    private fun finishFling() {
        scroller.abortAnimation()
        view.removeCallbacks(flingFrame)
        flingAnchor?.let { sendScroll(it, 0.0, 0.0, stop = true) }
        flingAnchor = null
        sink = null
    }

    fun cancel() {
        dispatch(machine.cancel())
        finishFling()
        releaseTouch()
        hideHold()
    }

    companion object {
        fun isFinger(event: MotionEvent): Boolean = event.pointerCount == 0 ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER ||
            (event.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN && !CursorController.isMouseInput(event.source))
    }
}
