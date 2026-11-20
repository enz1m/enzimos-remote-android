package com.enzimos.remote.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Application preferences for enzremote client.
 *
 * Wraps SharedPreferences with typed accessors and sensible defaults.
 */
class Preferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "enzremote_settings"

        // Video
        private const val KEY_MAX_BITRATE_KBPS = "max_bitrate_kbps"
        private const val DEFAULT_MAX_BITRATE_KBPS = 5000
        private const val KEY_PREFERRED_CODEC = "preferred_codec"
        private const val DEFAULT_PREFERRED_CODEC = "h264"
        private const val KEY_MAX_FPS = "max_fps"
        private const val DEFAULT_MAX_FPS = 60
        private const val KEY_RESOLUTION = "resolution"
        /** "native" = use compositor's native resolution; otherwise "WIDTHxHEIGHT". */
        private const val DEFAULT_RESOLUTION = "native"

        // Display
        private const val KEY_SHOW_PERF_OVERLAY = "show_perf_overlay"
        private const val KEY_LOCK_ORIENTATION = "lock_orientation"

        // Input
        private const val KEY_HIDE_CURSOR_ON_TOUCH = "hide_cursor_on_touch"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val DEFAULT_SCROLL_SPEED = 1.0f

        // Behaviour
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_IMMERSIVE_MODE = "immersive_mode"
        private const val DEFAULT_IMMERSIVE_MODE = "during_stream"

        // Network
        private const val KEY_LAST_HOST_ADDRESS = "last_host_address"
        private const val KEY_CONNECTION_HISTORY = "connection_history"
        /** Maximum number of entries kept in connection history. */
        private const val MAX_HISTORY_SIZE = 10

        /** Predefined resolution options (label to value). */
        val RESOLUTION_OPTIONS = listOf(
            "Native" to "native",
            "1080p (Full HD)" to "1080x2400",
            "720p (HD)" to "720x1600",
            "540p (qHD)" to "540x1200",
            "480p" to "480x1066",
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -----------------------------------------------------------------------
    // Video
    // -----------------------------------------------------------------------

    var maxBitrateKbps: Int
        get() = prefs.getInt(KEY_MAX_BITRATE_KBPS, DEFAULT_MAX_BITRATE_KBPS)
        set(value) = prefs.edit { putInt(KEY_MAX_BITRATE_KBPS, value.coerceIn(128, 100_000)) }

    var compatibilityDecoder: Boolean
        get() = prefs.getBoolean("compatibility_decoder", false)
        set(value) = prefs.edit { putBoolean("compatibility_decoder", value) }

    var preferredCodec: String
        get() = prefs.getString(KEY_PREFERRED_CODEC, DEFAULT_PREFERRED_CODEC) ?: DEFAULT_PREFERRED_CODEC
        set(value) = prefs.edit { putString(KEY_PREFERRED_CODEC, value) }

    var maxFps: Int
        get() = prefs.getInt(KEY_MAX_FPS, DEFAULT_MAX_FPS)
        set(value) = prefs.edit { putInt(KEY_MAX_FPS, value.coerceIn(15, 240)) }

    /**
     * Preferred stream resolution.
     *
     * Values: "native" (use compositor resolution), or "WIDTHxHEIGHT"
     * (e.g. "1080x2400", "720x1600", "540x1200").
     */
    var resolution: String
        get() = prefs.getString(KEY_RESOLUTION, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION
        set(value) = prefs.edit { putString(KEY_RESOLUTION, value) }

    // -----------------------------------------------------------------------
    // Display
    // -----------------------------------------------------------------------

    var showPerfOverlay: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PERF_OVERLAY, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_PERF_OVERLAY, value) }

    var lockOrientation: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ORIENTATION, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCK_ORIENTATION, value) }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    var hideCursorOnTouch: Boolean
        get() = prefs.getBoolean(KEY_HIDE_CURSOR_ON_TOUCH, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_CURSOR_ON_TOUCH, value) }

    /** Scroll speed multiplier (0.5x - 3.0x, default 1.0x). */
    var scrollSpeed: Float
        get() = prefs.getFloat(KEY_SCROLL_SPEED, DEFAULT_SCROLL_SPEED)
        set(value) = prefs.edit { putFloat(KEY_SCROLL_SPEED, value.coerceIn(0.5f, 3.0f)) }

    // -----------------------------------------------------------------------
    // Behaviour
    // -----------------------------------------------------------------------

    /** Auto-reconnect on connection loss. */
    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_RECONNECT, value) }

    /** Keep screen on during streaming. */
    /** Mirror notifications to computers (needs notification access). */
    var mirrorNotifications: Boolean
        get() = prefs.getBoolean("mirror_notifications", true)
        set(value) = prefs.edit { putBoolean("mirror_notifications", value) }

    /** Let computers read and send this phone's text messages. */
    var smsSharing: Boolean
        get() = prefs.getBoolean("sms_sharing", false)
        set(value) = prefs.edit { putBoolean("sms_sharing", value) }

    /** Two-way clipboard sharing with connected computers. */
    var clipboardSharing: Boolean
        get() = prefs.getBoolean("clipboard_sharing", true)
        set(value) = prefs.edit { putBoolean("clipboard_sharing", value) }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    /**
     * Immersive mode preference: "always", "during_stream", or "never".
     */
    var immersiveMode: String
        get() = prefs.getString(KEY_IMMERSIVE_MODE, DEFAULT_IMMERSIVE_MODE) ?: DEFAULT_IMMERSIVE_MODE
        set(value) = prefs.edit { putString(KEY_IMMERSIVE_MODE, value) }

    // -----------------------------------------------------------------------
    // Network
    // -----------------------------------------------------------------------

    var lastHostAddress: String
        get() = prefs.getString(KEY_LAST_HOST_ADDRESS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LAST_HOST_ADDRESS, value) }

    /** Computer shown on the home screen (host ID), if any. */
    var selectedHostId: String?
        get() = prefs.getString("selected_host_id", null)
        set(value) = prefs.edit { if (value == null) remove("selected_host_id") else putString("selected_host_id", value) }

    // -----------------------------------------------------------------------
    // Connection History
    // -----------------------------------------------------------------------

    /**
     * Get the list of recently connected host addresses, most recent first.
     */
    fun getConnectionHistory(): List<String> {
        val raw = prefs.getString(KEY_CONNECTION_HISTORY, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    /**
     * Add a host address to the connection history (moves it to the front
     * if already present). Trims to [MAX_HISTORY_SIZE] entries.
     */
    fun addToConnectionHistory(address: String) {
        if (address.isBlank()) return
        val current = getConnectionHistory().toMutableList()
        current.remove(address) // Remove duplicate if present
        current.add(0, address) // Add to front (most recent)
        val trimmed = current.take(MAX_HISTORY_SIZE)
        prefs.edit { putString(KEY_CONNECTION_HISTORY, trimmed.joinToString("\n")) }
    }

    /**
     * Remove a host address from the connection history.
     */
    fun removeFromConnectionHistory(address: String) {
        val current = getConnectionHistory().toMutableList()
        current.remove(address)
        prefs.edit { putString(KEY_CONNECTION_HISTORY, current.joinToString("\n")) }
    }

    /**
     * Clear the entire connection history.
     */
    fun clearConnectionHistory() {
        prefs.edit { remove(KEY_CONNECTION_HISTORY) }
    }
}
