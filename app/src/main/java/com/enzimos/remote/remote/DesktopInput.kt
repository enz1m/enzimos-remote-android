package com.enzimos.remote.remote

import android.os.Handler
import android.os.Looper
import com.enzimos.remote.signaling.PROTOCOL_VERSION
import com.enzimos.remote.signaling.SignalingClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Batches touchpad / keyboard / presenter input for the computer's desktop
 * (`remote_input`): pointer motion is merged and sent at most once per frame.
 */
class DesktopInput(private val client: () -> SignalingClient?) {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<JsonObject>()
    private var moveX = 0.0
    private var moveY = 0.0
    private var scrollX = 0.0
    private var scrollY = 0.0
    private var scheduled = false

    fun move(dx: Double, dy: Double) {
        moveX += dx
        moveY += dy
        schedule()
    }

    fun scroll(dx: Double, dy: Double) {
        scrollX += dx
        scrollY += dy
        schedule()
    }

    fun scrollStop() = event("scroll") { put("dx", 0.0); put("dy", 0.0); put("stop", true) }

    fun click(button: String = "left") = event("click") { put("button", button) }

    fun button(button: String, pressed: Boolean) = event("button") { put("button", button); put("pressed", pressed) }

    /** Press and release an evdev key, optionally with modifiers held. */
    fun tap(code: Int, vararg modifiers: Int) {
        modifiers.forEach { key(it, true) }
        key(code, true)
        key(code, false)
        modifiers.reversed().forEach { key(it, false) }
    }

    fun key(code: Int, pressed: Boolean) = event("key") { put("code", code); put("pressed", pressed) }

    fun text(text: String) = event("text") { put("text", text) }

    private fun event(kind: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        flushMotion()
        pending += buildJsonObject { put("kind", kind); fields() }
        schedule()
    }

    private fun flushMotion() {
        if (moveX != 0.0 || moveY != 0.0) {
            pending += buildJsonObject { put("kind", "move"); put("dx", moveX); put("dy", moveY) }
            moveX = 0.0; moveY = 0.0
        }
        if (scrollX != 0.0 || scrollY != 0.0) {
            pending += buildJsonObject { put("kind", "scroll"); put("dx", scrollX); put("dy", scrollY) }
            scrollX = 0.0; scrollY = 0.0
        }
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed(::flush, FRAME_MS)
    }

    private fun flush() {
        scheduled = false
        flushMotion()
        if (pending.isEmpty()) return
        val batch = pending.take(MAX_BATCH)
        pending.subList(0, batch.size).clear()
        val json = buildJsonObject {
            put("type", "remote_input")
            put("v", PROTOCOL_VERSION)
            put("events", buildJsonArray { batch.forEach { add(it) } })
        }.toString()
        client()?.sendRaw(json)
        if (pending.isNotEmpty()) schedule()
    }

    companion object {
        private const val FRAME_MS = 16L
        private const val MAX_BATCH = 64
        // evdev key codes.
        const val KEY_ESC = 1
        const val KEY_BACKSPACE = 14
        const val KEY_TAB = 15
        const val KEY_ENTER = 28
        const val KEY_LEFTCTRL = 29
        const val KEY_LEFTSHIFT = 42
        const val KEY_LEFTALT = 56
        const val KEY_B = 48
        const val KEY_F5 = 63
        const val KEY_UP = 103
        const val KEY_LEFT = 105
        const val KEY_RIGHT = 106
        const val KEY_DOWN = 108
        const val KEY_DELETE = 111
        const val KEY_VOLUMEDOWN = 114
        const val KEY_VOLUMEUP = 115
        const val KEY_LEFTMETA = 125
    }
}
