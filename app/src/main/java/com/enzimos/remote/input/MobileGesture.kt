package com.enzimos.remote.input

import kotlin.math.hypot

/** Finger intent is resolved before any mouse press reaches the remote app. Coordinates are view pixels. */
class MobileGesture(
    private val slop: Double,
    val dragDelayMs: Long = 500,
    val menuDelayMs: Long = dragDelayMs * 2,
) {
    enum class Mode { IDLE, PENDING, DRAG_READY, SCROLL, DRAG, MENU }
    enum class Feedback { DRAG_READY, MENU }
    data class Point(val x: Double, val y: Double)
    sealed interface Action {
        data class Pointer(val at: Point, val critical: Boolean = false) : Action
        data class Button(val pressed: Boolean) : Action
        data class Click(val at: Point, val right: Boolean = false) : Action
        data class Scroll(val at: Point, val dx: Double, val dy: Double) : Action
        data class ScrollStop(val at: Point) : Action
        data class Notify(val feedback: Feedback) : Action
        data object Escape : Action
    }

    var mode = Mode.IDLE
        private set
    private var origin = Point(0.0, 0.0)
    private var last = origin
    private var beganAt = 0L

    init {
        require(slop.isFinite() && slop > 0)
        require(dragDelayMs > 0 && menuDelayMs > dragDelayMs)
    }

    fun down(point: Point, now: Long): List<Action> {
        val cleanup = cancel()
        origin = point
        last = point
        beganAt = now
        mode = Mode.PENDING
        return cleanup
    }

    fun advance(now: Long): List<Action> {
        if (mode != Mode.PENDING && mode != Mode.DRAG_READY) return emptyList()
        val held = now - beganAt
        if (held >= menuDelayMs) {
            mode = Mode.MENU
            return listOf(Action.Click(origin, right = true), Action.Notify(Feedback.MENU))
        }
        if (held >= dragDelayMs && mode == Mode.PENDING) {
            mode = Mode.DRAG_READY
            return listOf(Action.Notify(Feedback.DRAG_READY))
        }
        return emptyList()
    }

    fun move(point: Point, now: Long): List<Action> {
        val actions = mutableListOf<Action>()
        val crossedSlop = hypot(point.x - origin.x, point.y - origin.y) > slop
        if ((mode == Mode.PENDING || mode == Mode.DRAG_READY) && crossedSlop) {
            if (now - beganAt >= dragDelayMs) {
                mode = Mode.DRAG
                actions += Action.Pointer(origin, critical = true)
                actions += Action.Button(true)
            } else {
                mode = Mode.SCROLL
            }
        }
        when (mode) {
            Mode.SCROLL -> {
                val dx = last.x - point.x
                val dy = last.y - point.y
                if (dx != 0.0 || dy != 0.0) actions += Action.Scroll(origin, dx, dy)
            }
            Mode.DRAG -> actions += Action.Pointer(point)
            else -> {}
        }
        // Pending jitter is retained in the first scroll delta, not turned into a click or drag.
        if (mode == Mode.SCROLL || mode == Mode.DRAG) last = point
        return actions
    }

    fun up(point: Point, now: Long): List<Action> {
        if ((mode == Mode.PENDING || mode == Mode.DRAG_READY) &&
            hypot(point.x - origin.x, point.y - origin.y) > slop) mode = Mode.SCROLL
        val actions = move(point, now).toMutableList()
        actions += advance(now)
        when (mode) {
            Mode.PENDING -> actions += Action.Click(point)
            Mode.SCROLL -> actions += Action.ScrollStop(origin)
            Mode.DRAG -> {
                actions += Action.Pointer(point, critical = true)
                actions += Action.Button(false)
            }
            else -> {}
        }
        mode = Mode.IDLE
        return actions
    }

    fun cancel(): List<Action> {
        val actions = when (mode) {
            Mode.DRAG -> listOf(Action.Escape, Action.Button(false))
            Mode.SCROLL -> listOf(Action.ScrollStop(origin))
            else -> emptyList()
        }
        mode = Mode.IDLE
        return actions
    }
}
