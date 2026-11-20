package com.enzimos.remote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.enzimos.remote.input.GamepadButtons
import com.enzimos.remote.input.GamepadState
import kotlin.math.hypot
import kotlin.math.min

/**
 * Semi-transparent on-screen game controller drawn over the stream: two
 * analog sticks (centred where the thumb lands inside their zone), a d-pad
 * that slides between directions, A/B/X/Y, shoulders, analog-style triggers,
 * View/Menu/Guide and stick clicks. Multi-touch: each finger keeps the control
 * it started on. Touches that miss every control fall through to the stream.
 */
class GamepadOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    /** Receives every state change. */
    var onState: ((GamepadState) -> Unit)? = null

    /** 0.5-1.5: control size; opacity 0.15-0.9. */
    var scale = 1f
        set(value) { field = value.coerceIn(0.5f, 1.5f); layoutControls(); invalidate() }
    var opacity = 0.45f
        set(value) { field = value.coerceIn(0.15f, 0.9f); invalidate() }

    /** Moved control groups: group → (dx, dy) as fractions of the short side. */
    var offsets: Map<String, Pair<Float, Float>> = emptyMap()
        set(value) { field = value; layoutControls(); invalidate() }

    /** Layout editing: drag groups around; Done / Reset at the top. */
    var editing = false
        set(value) { field = value; held.clear(); invalidate() }
    /** Game layout (dock hidden): a small menu button for Home, Keyboard, Settings, Close. */
    var showMenuButton = false
        set(value) { field = value; invalidate() }
    var onMenu: ((android.graphics.RectF) -> Unit)? = null
    private val menuRect = RectF()

    /** Called with the new offsets when editing ends (Done or Reset). */
    var onLayoutChanged: ((Map<String, Pair<Float, Float>>) -> Unit)? = null
    private val doneRect = RectF()
    private val resetRect = RectF()
    private var dragGroup: String? = null
    private var dragX = 0f
    private var dragY = 0f

    private sealed class Control {
        abstract fun hit(x: Float, y: Float): Boolean
    }
    private class Button(val bit: Int, val label: String, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f) : Control() {
        override fun hit(x: Float, y: Float) = hypot(x - cx, y - cy) <= r * 1.25f
    }
    private class Trigger(val left: Boolean, val label: String, val rect: RectF = RectF()) : Control() {
        override fun hit(x: Float, y: Float) = rect.contains(x, y)
    }
    private class Stick(val left: Boolean, val zone: RectF = RectF(), var homeX: Float = 0f, var homeY: Float = 0f, var r: Float = 0f) : Control() {
        var cx = 0f; var cy = 0f; var kx = 0f; var ky = 0f
        override fun hit(x: Float, y: Float) = zone.contains(x, y)
    }
    private class Pad(var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f) : Control() {
        override fun hit(x: Float, y: Float) = hypot(x - cx, y - cy) <= r * 1.2f
    }

    private val buttons = listOf(
        Button(GamepadButtons.A, "A"), Button(GamepadButtons.B, "B"),
        Button(GamepadButtons.X, "X"), Button(GamepadButtons.Y, "Y"),
        Button(GamepadButtons.LB, "LB"), Button(GamepadButtons.RB, "RB"),
        Button(GamepadButtons.BACK, "⧉"), Button(GamepadButtons.START, "☰"),
        Button(GamepadButtons.GUIDE, "⌂"),
        Button(GamepadButtons.LS, "L3"), Button(GamepadButtons.RS, "R3"),
    )
    private val triggers = listOf(Trigger(true, "LT"), Trigger(false, "RT"))
    private val leftStick = Stick(true)
    private val rightStick = Stick(false)
    private val pad = Pad()
    private val controls: List<Control> = buttons + triggers + listOf(pad, leftStick, rightStick)

    /** Pointer id → the control it holds. */
    private val held = HashMap<Int, Control>()
    private var state = GamepadState()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutControls()

    private fun layoutControls() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val unit = min(w, h) * 0.085f * scale
        fun place(bit: Int, x: Float, y: Float, r: Float = unit * 0.62f) =
            buttons.first { it.bit == bit }.apply { cx = x; cy = y; this.r = r }
        // Shoulders and triggers in the top corners; View / Guide / Menu centred.
        place(GamepadButtons.LB, unit * 3.4f, unit * 1.2f, unit * 0.55f)
        place(GamepadButtons.RB, w - unit * 3.4f, unit * 1.2f, unit * 0.55f)
        triggers[0].rect.set(unit * 0.5f, unit * 0.5f, unit * 2.4f, unit * 1.9f)
        triggers[1].rect.set(w - unit * 2.4f, unit * 0.5f, w - unit * 0.5f, unit * 1.9f)
        place(GamepadButtons.BACK, w / 2 - unit * 1.6f, unit * 1.1f, unit * 0.45f)
        place(GamepadButtons.GUIDE, w / 2, unit * 1.1f, unit * 0.5f)
        place(GamepadButtons.START, w / 2 + unit * 1.6f, unit * 1.1f, unit * 0.45f)
        leftStick.r = unit * 1.15f
        rightStick.r = unit * 1.15f
        val (fx, fy) = if (w > h) {
            // Landscape: d-pad and face buttons mid-height, sticks in the lower corners.
            pad.cx = unit * 2.4f; pad.cy = h - unit * 3.8f
            leftStick.homeX = unit * 4.6f; leftStick.homeY = h - unit * 1.6f
            leftStick.zone.set(unit * 3.1f, h - unit * 3.2f, w * 0.45f, h)
            rightStick.homeX = w - unit * 4.9f; rightStick.homeY = h - unit * 1.6f
            rightStick.zone.set(w * 0.55f, h - unit * 2.6f, w - unit * 3.5f, h)
            place(GamepadButtons.LS, leftStick.homeX + unit * 1.9f, h - unit * 0.7f, unit * 0.4f)
            place(GamepadButtons.RS, rightStick.homeX - unit * 1.9f, h - unit * 0.7f, unit * 0.4f)
            (w - unit * 2.4f) to (h - unit * 3.6f)
        } else {
            // Portrait: two columns in the lower part, sticks at the bottom.
            pad.cx = unit * 2.4f; pad.cy = h - unit * 6.4f
            leftStick.homeX = unit * 2.4f; leftStick.homeY = h - unit * 2.6f
            leftStick.zone.set(0f, h - unit * 4.6f, w / 2 - unit * 1.6f, h)
            rightStick.homeX = w - unit * 2.4f; rightStick.homeY = h - unit * 2.6f
            rightStick.zone.set(w / 2 + unit * 1.6f, h - unit * 4.6f, w, h)
            place(GamepadButtons.LS, w / 2 - unit * 0.9f, h - unit * 1.4f, unit * 0.45f)
            place(GamepadButtons.RS, w / 2 + unit * 0.9f, h - unit * 1.4f, unit * 0.45f)
            (w - unit * 2.4f) to (h - unit * 6.4f)
        }
        pad.r = unit * 1.25f
        place(GamepadButtons.A, fx, fy + unit * 1.15f)
        place(GamepadButtons.B, fx + unit * 1.15f, fy)
        place(GamepadButtons.X, fx - unit * 1.15f, fy)
        place(GamepadButtons.Y, fx, fy - unit * 1.15f)
        // User-moved groups.
        val side = min(w, h)
        for (control in controls) {
            val (ox, oy) = offsets[group(control)] ?: continue
            val dx = ox * side; val dy = oy * side
            when (control) {
                is Button -> { control.cx += dx; control.cy += dy }
                is Trigger -> control.rect.offset(dx, dy)
                is Pad -> { control.cx += dx; control.cy += dy }
                is Stick -> { control.homeX += dx; control.homeY += dy; control.zone.offset(dx, dy) }
            }
        }
        menuRect.set(w / 2 - unit * 0.6f, unit * 1.9f, w / 2 + unit * 0.6f, unit * 2.7f)
        val pill = unit * 0.55f
        doneRect.set(w / 2 + unit * 0.2f, h * 0.42f - pill, w / 2 + unit * 2.6f, h * 0.42f + pill)
        resetRect.set(w / 2 - unit * 2.6f, h * 0.42f - pill, w / 2 - unit * 0.2f, h * 0.42f + pill)
        for (stick in listOf(leftStick, rightStick)) if (held.values.none { it === stick }) {
            stick.cx = stick.homeX; stick.cy = stick.homeY; stick.kx = stick.cx; stick.ky = stick.cy
        }
    }

    /** Controls that move together when editing the layout. */
    private fun group(control: Control): String = when (control) {
        is Pad -> "dpad"
        is Stick -> if (control.left) "left_stick" else "right_stick"
        is Trigger -> if (control.left) "left_shoulder" else "right_shoulder"
        is Button -> when (control.bit) {
            GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y -> "face"
            GamepadButtons.LB -> "left_shoulder"
            GamepadButtons.RB -> "right_shoulder"
            GamepadButtons.LS -> "left_stick"
            GamepadButtons.RS -> "right_stick"
            else -> "menu"
        }
    }

    private fun editTouch(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        val side = min(width, height).toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when {
                    doneRect.contains(x, y) -> { editing = false; onLayoutChanged?.invoke(offsets); return true }
                    resetRect.contains(x, y) -> { offsets = emptyMap(); editing = false; onLayoutChanged?.invoke(offsets); return true }
                }
                dragGroup = controls.firstOrNull { it.hit(x, y) }?.let(::group)
                dragX = x; dragY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val name = dragGroup ?: return true
                val (ox, oy) = offsets[name] ?: (0f to 0f)
                offsets = offsets + (name to ((ox + (x - dragX) / side) to (oy + (y - dragY) / side)))
                dragX = x; dragY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragGroup = null
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) return editTouch(event)
        if (showMenuButton && event.actionMasked == MotionEvent.ACTION_DOWN && menuRect.contains(event.x, event.y)) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onMenu?.invoke(RectF(menuRect))
            return true
        }
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(index); val y = event.getY(index)
                // Small targets first so a stick zone does not swallow L3/R3.
                val control = controls.firstOrNull { it.hit(x, y) }
                if (control == null) return event.actionMasked != MotionEvent.ACTION_DOWN
                held[event.getPointerId(index)] = control
                if (control is Stick) { control.cx = x; control.cy = y }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                move(event)
            }
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP -> { release(event.getPointerId(index)); move(event, skip = index) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { held.keys.toList().forEach(::release); move(event, skip = -2) }
        }
        return true
    }

    private fun release(pointer: Int) {
        val control = held.remove(pointer) as? Stick ?: return
        control.cx = control.homeX; control.cy = control.homeY
        control.kx = control.cx; control.ky = control.cy
    }

    /** Recompute the whole state from the fingers still down. */
    private fun move(event: MotionEvent, skip: Int = -1) {
        var bits = 0
        var lt = 0f; var rt = 0f
        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f
        for (i in 0 until event.pointerCount) {
            if (i == skip || skip == -2) continue
            val control = held[event.getPointerId(i)] ?: continue
            val x = event.getX(i); val y = event.getY(i)
            when (control) {
                is Button -> bits = bits or control.bit
                is Trigger -> if (control.left) lt = 1f else rt = 1f
                is Pad -> {
                    val dx = (x - control.cx) / control.r; val dy = (y - control.cy) / control.r
                    if (hypot(dx, dy) > 0.25f) {
                        if (dx < -0.38f) bits = bits or GamepadButtons.LEFT
                        if (dx > 0.38f) bits = bits or GamepadButtons.RIGHT
                        if (dy < -0.38f) bits = bits or GamepadButtons.UP
                        if (dy > 0.38f) bits = bits or GamepadButtons.DOWN
                    }
                }
                is Stick -> {
                    var dx = (x - control.cx) / control.r; var dy = (y - control.cy) / control.r
                    val length = hypot(dx, dy)
                    if (length > 1f) { dx /= length; dy /= length }
                    control.kx = control.cx + dx * control.r; control.ky = control.cy + dy * control.r
                    if (control.left) { lx = dx; ly = dy } else { rx = dx; ry = dy }
                }
            }
        }
        val next = GamepadState(bits, lx, ly, rx, ry, lt, rt)
        if (next.buttons and state.buttons.inv() != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (next != state) {
            state = next
            onState?.invoke(next)
        }
        invalidate()
    }

    /** Let go of everything (overlay hidden or stream paused). */
    fun reset() {
        held.clear()
        for (stick in listOf(leftStick, rightStick)) { stick.cx = stick.homeX; stick.cy = stick.homeY; stick.kx = stick.cx; stick.ky = stick.cy }
        if (state != GamepadState()) { state = GamepadState(); onState?.invoke(state) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val base = (opacity * 255).toInt()
        val unit = min(width, height) * 0.085f * scale
        stroke.strokeWidth = unit * 0.06f
        text.textSize = unit * 0.5f
        fun colors(pressed: Boolean) {
            fill.color = if (pressed) Color.argb(min(255, base + 70), 255, 255, 255) else Color.argb(base / 2, 20, 20, 24)
            stroke.color = Color.argb(base, 255, 255, 255)
            text.color = if (pressed) Color.argb(230, 20, 20, 24) else Color.argb(min(255, base + 60), 255, 255, 255)
        }
        for (button in buttons) {
            colors(state.buttons and button.bit != 0)
            canvas.drawCircle(button.cx, button.cy, button.r, fill)
            canvas.drawCircle(button.cx, button.cy, button.r, stroke)
            text.textSize = button.r * (if (button.label.length > 1) 0.7f else 0.9f)
            canvas.drawText(button.label, button.cx, button.cy - (text.descent() + text.ascent()) / 2, text)
        }
        text.textSize = unit * 0.5f
        for (trigger in triggers) {
            colors(if (trigger.left) state.lt > 0f else state.rt > 0f)
            canvas.drawRoundRect(trigger.rect, unit * 0.4f, unit * 0.4f, fill)
            canvas.drawRoundRect(trigger.rect, unit * 0.4f, unit * 0.4f, stroke)
            canvas.drawText(trigger.label, trigger.rect.centerX(), trigger.rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
        // D-pad: four arms.
        val arm = pad.r * 0.42f
        for ((bit, dx, dy) in listOf(Triple(GamepadButtons.UP, 0f, -1f), Triple(GamepadButtons.DOWN, 0f, 1f),
            Triple(GamepadButtons.LEFT, -1f, 0f), Triple(GamepadButtons.RIGHT, 1f, 0f))) {
            colors(state.buttons and bit != 0)
            val cx = pad.cx + dx * pad.r * 0.62f; val cy = pad.cy + dy * pad.r * 0.62f
            val rect = RectF(cx - arm, cy - arm, cx + arm, cy + arm)
            canvas.drawRoundRect(rect, arm * 0.35f, arm * 0.35f, fill)
            canvas.drawRoundRect(rect, arm * 0.35f, arm * 0.35f, stroke)
        }
        for (stick in listOf(leftStick, rightStick)) {
            colors(false)
            canvas.drawCircle(stick.cx, stick.cy, stick.r, fill)
            canvas.drawCircle(stick.cx, stick.cy, stick.r, stroke)
            colors(held.values.any { it === stick })
            canvas.drawCircle(stick.kx, stick.ky, stick.r * 0.45f, fill)
            canvas.drawCircle(stick.kx, stick.ky, stick.r * 0.45f, stroke)
        }
        if (showMenuButton && !editing) {
            colors(false)
            val r = menuRect.height() / 2
            canvas.drawRoundRect(menuRect, r, r, fill)
            canvas.drawRoundRect(menuRect, r, r, stroke)
            fill.color = Color.argb(min(255, base + 80), 255, 255, 255)
            for (i in -1..1) canvas.drawCircle(menuRect.centerX() + i * r * 0.7f, menuRect.centerY(), r * 0.16f, fill)
        }
        if (editing) {
            val pill = doneRect.height() / 2
            fill.color = Color.argb(230, 255, 255, 255)
            canvas.drawRoundRect(doneRect, pill, pill, fill)
            fill.color = Color.argb(200, 40, 40, 44)
            canvas.drawRoundRect(resetRect, pill, pill, fill)
            text.textSize = pill * 0.8f
            text.color = Color.argb(255, 20, 20, 24)
            canvas.drawText(context.getString(com.enzimos.remote.R.string.controller_layout_done), doneRect.centerX(), doneRect.centerY() - (text.descent() + text.ascent()) / 2, text)
            text.color = Color.WHITE
            canvas.drawText(context.getString(com.enzimos.remote.R.string.controller_layout_reset), resetRect.centerX(), resetRect.centerY() - (text.descent() + text.ascent()) / 2, text)
            text.textSize = pill * 0.62f
            canvas.drawText(context.getString(com.enzimos.remote.R.string.controller_layout_hint), width / 2f, doneRect.top - pill, text)
        }
    }
}
