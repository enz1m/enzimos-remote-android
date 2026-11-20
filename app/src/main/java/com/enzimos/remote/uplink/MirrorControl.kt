package com.enzimos.remote.uplink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Lets the computer control this phone while its screen is mirrored: taps,
 * swipes and scrolling as gestures, typing into the focused field, Back,
 * Home and Recents. Only active when the user enables it in Accessibility.
 */
class MirrorControl : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun handle(event: JsonObject) {
        fun num(key: String) = (event[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun str(key: String) = (event[key] as? JsonPrimitive)?.content.orEmpty()
        val metrics = resources.displayMetrics
        val (w, h) = metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
        when (str("kind")) {
            "tap" -> gesture(num("x") * w, num("y") * h, num("x") * w, num("y") * h, 60)
            "swipe" -> gesture(num("x") * w, num("y") * h, num("x2") * w, num("y2") * h, 250)
            "scroll" -> {
                // Wheel down scrolls content down: swipe up in the middle.
                val distance = (num("dy") * h / 6).coerceIn(-h / 3.0, h / 3.0)
                gesture(w / 2.0, h / 2.0, w / 2.0, h / 2.0 - distance, 200)
            }
            "global" -> performGlobalAction(when (str("action")) {
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                else -> GLOBAL_ACTION_BACK
            })
            "text" -> editFocused { it + str("text") }
            "key" -> when (str("key")) {
                "backspace" -> editFocused { it.dropLast(1) }
                "enter" -> focused()?.let { node ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    } else {
                        editFocused { it + "\n" }
                    }
                }
            }
        }
    }

    private fun gesture(x1: Double, y1: Double, x2: Double, y2: Double, duration: Long) {
        val path = Path().apply {
            moveTo(x1.toFloat().coerceAtLeast(0f), y1.toFloat().coerceAtLeast(0f))
            lineTo(x2.toFloat().coerceAtLeast(0f), y2.toFloat().coerceAtLeast(0f))
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }

    private fun focused(): AccessibilityNodeInfo? = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun editFocused(change: (String) -> String) {
        val node = focused()?.takeIf { it.isEditable } ?: return
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, change(current))
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        @Volatile var instance: MirrorControl? = null
            private set

        fun enabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(context, MirrorControl::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun settings(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
