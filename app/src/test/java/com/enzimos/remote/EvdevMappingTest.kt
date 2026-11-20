package com.enzimos.remote

import android.view.KeyEvent
import com.enzimos.remote.input.KeyboardHandler
import org.junit.Assert.*
import org.junit.Test

/**
 * Verify that Android keycode → Linux evdev mappings match the
 * actual Linux kernel input-event-codes.h values.
 *
 * These values are from linux/input-event-codes.h and must match
 * exactly for the remote EIS input worker to generate correct events.
 */
class EvdevMappingTest {

    // linux/input-event-codes.h reference values
    private val EVDEV = mapOf(
        "KEY_ESC" to 1,
        "KEY_1" to 2,
        "KEY_2" to 3,
        "KEY_3" to 4,
        "KEY_4" to 5,
        "KEY_5" to 6,
        "KEY_6" to 7,
        "KEY_7" to 8,
        "KEY_8" to 9,
        "KEY_9" to 10,
        "KEY_0" to 11,
        "KEY_MINUS" to 12,
        "KEY_EQUAL" to 13,
        "KEY_BACKSPACE" to 14,
        "KEY_TAB" to 15,
        "KEY_Q" to 16,
        "KEY_W" to 17,
        "KEY_E" to 18,
        "KEY_R" to 19,
        "KEY_T" to 20,
        "KEY_Y" to 21,
        "KEY_U" to 22,
        "KEY_I" to 23,
        "KEY_O" to 24,
        "KEY_P" to 25,
        "KEY_LEFTBRACE" to 26,
        "KEY_RIGHTBRACE" to 27,
        "KEY_ENTER" to 28,
        "KEY_LEFTCTRL" to 29,
        "KEY_A" to 30,
        "KEY_S" to 31,
        "KEY_D" to 32,
        "KEY_F" to 33,
        "KEY_G" to 34,
        "KEY_H" to 35,
        "KEY_J" to 36,
        "KEY_K" to 37,
        "KEY_L" to 38,
        "KEY_SEMICOLON" to 39,
        "KEY_APOSTROPHE" to 40,
        "KEY_GRAVE" to 41,
        "KEY_LEFTSHIFT" to 42,
        "KEY_BACKSLASH" to 43,
        "KEY_Z" to 44,
        "KEY_X" to 45,
        "KEY_C" to 46,
        "KEY_V" to 47,
        "KEY_B" to 48,
        "KEY_N" to 49,
        "KEY_M" to 50,
        "KEY_COMMA" to 51,
        "KEY_DOT" to 52,
        "KEY_SLASH" to 53,
        "KEY_RIGHTSHIFT" to 54,
        "KEY_LEFTALT" to 56,
        "KEY_SPACE" to 57,
        "KEY_CAPSLOCK" to 58,
        "KEY_F1" to 59,
        "KEY_F2" to 60,
        "KEY_F3" to 61,
        "KEY_F4" to 62,
        "KEY_F5" to 63,
        "KEY_F6" to 64,
        "KEY_F7" to 65,
        "KEY_F8" to 66,
        "KEY_F9" to 67,
        "KEY_F10" to 68,
        "KEY_F11" to 87,
        "KEY_F12" to 88,
        "KEY_RIGHTCTRL" to 97,
        "KEY_RIGHTALT" to 100,
        "KEY_HOME" to 102,
        "KEY_UP" to 103,
        "KEY_PAGEUP" to 104,
        "KEY_LEFT" to 105,
        "KEY_RIGHT" to 106,
        "KEY_END" to 107,
        "KEY_DOWN" to 108,
        "KEY_PAGEDOWN" to 109,
        "KEY_INSERT" to 110,
        "KEY_DELETE" to 111,
        "KEY_LEFTMETA" to 125,
        "KEY_RIGHTMETA" to 126,
    )

    @Test
    fun `all mapped keycodes match Linux evdev reference`() {
        // Verify all mappings in KeyboardHandler against the evdev reference
        val androidToEvdev = mapOf(
            KeyEvent.KEYCODE_A to EVDEV["KEY_A"],
            KeyEvent.KEYCODE_Z to EVDEV["KEY_Z"],
            KeyEvent.KEYCODE_0 to EVDEV["KEY_0"],
            KeyEvent.KEYCODE_9 to EVDEV["KEY_9"],
            KeyEvent.KEYCODE_ESCAPE to EVDEV["KEY_ESC"],
            KeyEvent.KEYCODE_TAB to EVDEV["KEY_TAB"],
            KeyEvent.KEYCODE_ENTER to EVDEV["KEY_ENTER"],
            KeyEvent.KEYCODE_SPACE to EVDEV["KEY_SPACE"],
            KeyEvent.KEYCODE_DEL to EVDEV["KEY_BACKSPACE"],
            KeyEvent.KEYCODE_FORWARD_DEL to EVDEV["KEY_DELETE"],
            KeyEvent.KEYCODE_SHIFT_LEFT to EVDEV["KEY_LEFTSHIFT"],
            KeyEvent.KEYCODE_SHIFT_RIGHT to EVDEV["KEY_RIGHTSHIFT"],
            KeyEvent.KEYCODE_CTRL_LEFT to EVDEV["KEY_LEFTCTRL"],
            KeyEvent.KEYCODE_CTRL_RIGHT to EVDEV["KEY_RIGHTCTRL"],
            KeyEvent.KEYCODE_ALT_LEFT to EVDEV["KEY_LEFTALT"],
            KeyEvent.KEYCODE_ALT_RIGHT to EVDEV["KEY_RIGHTALT"],
            KeyEvent.KEYCODE_META_LEFT to EVDEV["KEY_LEFTMETA"],
            KeyEvent.KEYCODE_META_RIGHT to EVDEV["KEY_RIGHTMETA"],
            KeyEvent.KEYCODE_CAPS_LOCK to EVDEV["KEY_CAPSLOCK"],
            KeyEvent.KEYCODE_F1 to EVDEV["KEY_F1"],
            KeyEvent.KEYCODE_F12 to EVDEV["KEY_F12"],
        )

        androidToEvdev.forEach { (android, expectedEvdev) ->
            val actual = KeyboardHandler.androidToLinuxKeycode(android)
            assertEquals(
                "Android keycode $android should map to evdev $expectedEvdev",
                expectedEvdev,
                actual,
            )
        }
    }
}
