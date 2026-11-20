package com.enzimos.remote.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.Locale
import kotlin.math.abs

/**
 * Game controllers for the computer: physical controllers connected to the
 * phone and the on-screen controller. Each appears on the computer as its own
 * virtual Xbox-style gamepad. State snapshots are sent on change over the
 * stream's data channel: `{"type":"gamepad","v":1,"id","buttons","lx","ly",
 * "rx","ry","lt","rt"}` (sticks -1..1 with +y down, triggers 0..1), and
 * `{"type":"gamepad","v":1,"id","connected":false}` when one goes away.
 */
object GamepadButtons {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val X = 1 shl 2
    const val Y = 1 shl 3
    const val LB = 1 shl 4
    const val RB = 1 shl 5
    const val BACK = 1 shl 6
    const val START = 1 shl 7
    const val GUIDE = 1 shl 8
    const val LS = 1 shl 9
    const val RS = 1 shl 10
    const val UP = 1 shl 11
    const val DOWN = 1 shl 12
    const val LEFT = 1 shl 13
    const val RIGHT = 1 shl 14
}

data class GamepadState(
    val buttons: Int = 0,
    val lx: Float = 0f, val ly: Float = 0f,
    val rx: Float = 0f, val ry: Float = 0f,
    val lt: Float = 0f, val rt: Float = 0f,
) {
    fun json(id: Int): String = String.format(
        Locale.US,
        """{"type":"gamepad","v":1,"id":%d,"buttons":%d,"lx":%.3f,"ly":%.3f,"rx":%.3f,"ry":%.3f,"lt":%.3f,"rt":%.3f}""",
        id, buttons, lx, ly, rx, ry, lt, rt,
    )
}

/**
 * Assigns gamepad slots (0-3) to sources and sends their state. Button
 * changes are sent reliably; stick motion is coalesced to ~120 Hz and resent
 * if the channel dropped it, so the computer always ends on the final state.
 */
class GamepadHub(private val send: (String, Boolean) -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val slots = arrayOfNulls<Any>(MAX)
    private val states = arrayOfNulls<GamepadState>(MAX)
    private val sent = arrayOfNulls<GamepadState>(MAX)
    private var lastSend = 0L
    private var scheduled = false
    private val flush = Runnable { scheduled = false; flushNow() }

    /** Slot for a source (an InputDevice id, or the on-screen controller). */
    fun slot(source: Any): Int? {
        slots.indexOf(source).takeIf { it >= 0 }?.let { return it }
        val free = slots.indexOfFirst { it == null }.takeIf { it >= 0 } ?: return null
        slots[free] = source
        states[free] = GamepadState()
        sent[free] = null
        return free
    }

    /** The source holding slot [id], if any. */
    fun source(id: Int): Any? = slots.getOrNull(id)

    fun update(source: Any, state: GamepadState) {
        val id = slot(source) ?: return
        val previous = states[id]
        states[id] = state
        if (previous?.buttons != state.buttons) {
            // Buttons: immediately and reliably (with the current sticks).
            if (send(state.json(id), true)) sent[id] = state
            return
        }
        schedule()
    }

    fun remove(source: Any) {
        val id = slots.indexOf(source).takeIf { it >= 0 } ?: return
        slots[id] = null
        states[id] = null
        sent[id] = null
        send("""{"type":"gamepad","v":1,"id":$id,"connected":false}""", true)
    }

    fun removeAll() {
        for (source in slots.filterNotNull()) remove(source)
        main.removeCallbacks(flush)
        scheduled = false
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        val wait = (lastSend + INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        main.postDelayed(flush, wait)
    }

    private fun flushNow() {
        lastSend = SystemClock.uptimeMillis()
        var pending = false
        for (id in 0 until MAX) {
            val state = states[id] ?: continue
            if (state == sent[id]) continue
            if (send(state.json(id), false)) sent[id] = state else pending = true
        }
        if (pending) schedule()
    }

    companion object {
        const val MAX = 4
        private const val INTERVAL_MS = 8L
    }
}

/** Maps a physical controller's key and motion events to [GamepadState]. */
class PhysicalGamepads(private val hub: GamepadHub) {
    private val states = HashMap<Int, GamepadState>()

    fun onKey(event: KeyEvent): Boolean {
        if (!isGamepad(event.device)) return false
        val id = event.deviceId
        val current = states[id] ?: GamepadState()
        // Digital triggers (analog ones also report motion, which wins).
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
            val value = if (event.action == KeyEvent.ACTION_DOWN) 1f else 0f
            val next = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2) current.copy(lt = value) else current.copy(rt = value)
            states[id] = next
            hub.update(id, next)
            return true
        }
        val bit = buttonFor(event.keyCode) ?: return false
        val buttons = when (event.action) {
            KeyEvent.ACTION_DOWN -> current.buttons or bit
            KeyEvent.ACTION_UP -> current.buttons and bit.inv()
            else -> return true
        }
        val next = current.copy(buttons = buttons)
        states[id] = next
        hub.update(id, next)
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!isGamepad(event.device) || event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device
        val id = event.deviceId
        val current = states[id] ?: GamepadState()
        fun axis(vararg axes: Int): Float {
            for (axis in axes) {
                val range = device.getMotionRange(axis, event.source) ?: continue
                val value = event.getAxisValue(axis)
                return if (abs(value) > range.flat) value else 0f
            }
            return 0f
        }
        // Hat switches report the d-pad as axes on many controllers.
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var buttons = current.buttons
        if (device.getMotionRange(MotionEvent.AXIS_HAT_X, event.source) != null) {
            buttons = buttons and (GamepadButtons.LEFT or GamepadButtons.RIGHT or GamepadButtons.UP or GamepadButtons.DOWN).inv()
            if (hatX < -0.5f) buttons = buttons or GamepadButtons.LEFT
            if (hatX > 0.5f) buttons = buttons or GamepadButtons.RIGHT
            if (hatY < -0.5f) buttons = buttons or GamepadButtons.UP
            if (hatY > 0.5f) buttons = buttons or GamepadButtons.DOWN
        }
        val next = current.copy(
            buttons = buttons,
            lx = axis(MotionEvent.AXIS_X), ly = axis(MotionEvent.AXIS_Y),
            rx = axis(MotionEvent.AXIS_Z, MotionEvent.AXIS_RX), ry = axis(MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY),
            lt = axis(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE).coerceIn(0f, 1f),
            rt = axis(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS).coerceIn(0f, 1f),
        )
        if (next != current) {
            states[id] = next
            hub.update(id, next)
        }
        return true
    }

    /** A controller was unplugged. */
    fun onDeviceRemoved(deviceId: Int) {
        if (states.remove(deviceId) != null) hub.remove(deviceId)
    }

    companion object {
        /** A real controller: gamepad buttons, or a joystick with stick axes.
         *  (Phones label some sensors joysticks, e.g. a fingerprint reader.) */
        fun isGamepad(device: InputDevice?): Boolean {
            val sources = device?.sources ?: return false
            if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) return true
            return sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
                device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK) != null &&
                device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK) != null
        }

        private fun buttonFor(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> GamepadButtons.A
            KeyEvent.KEYCODE_BUTTON_B -> GamepadButtons.B
            KeyEvent.KEYCODE_BUTTON_X -> GamepadButtons.X
            KeyEvent.KEYCODE_BUTTON_Y -> GamepadButtons.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> GamepadButtons.LB
            KeyEvent.KEYCODE_BUTTON_R1 -> GamepadButtons.RB
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> GamepadButtons.BACK
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> GamepadButtons.START
            KeyEvent.KEYCODE_BUTTON_MODE -> GamepadButtons.GUIDE
            KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadButtons.LS
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadButtons.RS
            KeyEvent.KEYCODE_DPAD_UP -> GamepadButtons.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> GamepadButtons.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> GamepadButtons.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadButtons.RIGHT
            else -> null
        }
    }
}
