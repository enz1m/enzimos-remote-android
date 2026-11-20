package com.enzimos.remote.remote

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.enzimos.remote.signaling.PROTOCOL_VERSION
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Second display: every finger is forwarded as touch on the computer's virtual
 * monitor (`display_input`), so the desktop handles taps, scrolling and long
 * presses itself. Motion is merged per finger and sent once per frame.
 */
class DisplayTouchForwarder(
    private val view: View,
    private val sessionId: String,
    private val size: () -> Pair<Int, Int>,
    /** Forward one finger only; further fingers belong to local zoom/pan/scroll. */
    private val singleFinger: Boolean = false,
    private val send: (String) -> Boolean,
) {
    /** Set when a second finger joined; nothing is forwarded until all are up. */
    private var multi = false
    /** The first finger's `down`, held briefly so a pinch never taps the desktop. */
    private var held: JsonObject? = null
    private val releaseHeld = Runnable {
        held?.let { pending += it }
        held = null
        schedule()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<JsonObject>()
    private val moves = LinkedHashMap<Int, Pair<Double, Double>>()
    private val down = mutableSetOf<Int>()
    private var scheduled = false

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) multi = false
                if (singleFinger && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    // A pinch or two-finger scroll: drop a held finger, else lift it.
                    multi = true
                    if (held != null) {
                        handler.removeCallbacks(releaseHeld)
                        held = null
                        down.clear()
                        moves.clear()
                    } else {
                        cancel()
                    }
                    return true
                }
                if (multi) return true
                val index = event.actionIndex
                val slot = event.getPointerId(index)
                if (slot > MAX_SLOT) return true
                flushMoves()
                val (x, y) = map(event.getX(index), event.getY(index))
                val press = buildJsonObject { put("kind", "down"); put("slot", slot); put("x", x); put("y", y) }
                down += slot
                if (singleFinger) {
                    held = press
                    handler.postDelayed(releaseHeld, HOLD_MS)
                    return true
                }
                pending += press
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val slot = event.getPointerId(i)
                if (slot in down) moves[slot] = map(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val slot = event.getPointerId(event.actionIndex)
                if (held != null) {
                    handler.removeCallbacks(releaseHeld)
                    releaseHeld.run()
                }
                if (down.remove(slot)) {
                    flushMoves()
                    pending += buildJsonObject { put("kind", "up"); put("slot", slot) }
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        schedule()
        return true
    }

    /** Scroll the computer by ([dx], [dy]) view pixels at ([x], [y]) in view pixels. */
    fun scroll(x: Float, y: Float, dx: Double, dy: Double) {
        if (view.width <= 0 || view.height <= 0) return
        val (w, h) = size()
        val (px, py) = map(x, y)
        pending += buildJsonObject { put("kind", "pointer"); put("x", px); put("y", py) }
        pending += buildJsonObject { put("kind", "scroll"); put("dx", dx / view.width * w); put("dy", dy / view.height * h) }
        schedule()
    }

    fun cancel() {
        if (held != null) {
            // Never sent: nothing to lift.
            handler.removeCallbacks(releaseHeld)
            held = null
            down.clear()
            moves.clear()
            return
        }
        flushMoves()
        down.forEach { slot -> pending += buildJsonObject { put("kind", "up"); put("slot", slot) } }
        down.clear()
        schedule()
    }

    private fun map(x: Float, y: Float): Pair<Double, Double> {
        val (w, h) = size()
        if (view.width <= 0 || view.height <= 0) return 0.0 to 0.0
        return (x / view.width * w).toDouble().coerceIn(0.0, w - 1.0) to (y / view.height * h).toDouble().coerceIn(0.0, h - 1.0)
    }

    private fun flushMoves() {
        for ((slot, position) in moves) {
            pending += buildJsonObject { put("kind", "move"); put("slot", slot); put("x", position.first); put("y", position.second) }
        }
        moves.clear()
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            // Moves wait for their held `down` (releaseHeld schedules again).
            if (held != null) return@postDelayed
            flushMoves()
            while (pending.isNotEmpty()) {
                val batch = pending.take(64)
                pending.subList(0, batch.size).clear()
                send(buildJsonObject {
                    put("type", "display_input"); put("v", PROTOCOL_VERSION); put("session_id", sessionId)
                    put("events", buildJsonArray { batch.forEach { add(it) } })
                }.toString())
            }
        }, 16)
    }

    private companion object {
        const val MAX_SLOT = 9
        /** Long enough for a second finger of a pinch, short enough to feel direct. */
        const val HOLD_MS = 70L
    }
}
