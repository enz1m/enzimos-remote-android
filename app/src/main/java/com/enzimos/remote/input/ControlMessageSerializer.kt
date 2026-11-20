package com.enzimos.remote.input

/** Serializes protocol v1 input messages without allocating intermediate JSON objects. */
object ControlMessageSerializer {

    private const val V = 1

    /** Ensure a Double is finite (not NaN or Infinity). */
    private fun requireFinite(value: Double, name: String): Double {
        require(value.isFinite()) { "$name must be finite, got $value" }
        return value
    }

    /** Absolute pointer position. */
    fun pointer(x: Double, y: Double, click: Boolean = false): String {
        requireFinite(x, "x")
        requireFinite(y, "y")
        return """{"type":"pointer","v":$V,"x":$x,"y":$y,"click":$click}"""
    }

    /** Relative pointer motion. */
    fun pointerRelative(dx: Double, dy: Double): String {
        requireFinite(dx, "dx")
        requireFinite(dy, "dy")
        return """{"type":"pointer_relative","v":$V,"dx":$dx,"dy":$dy}"""
    }

    /** Mouse button press/release. */
    fun button(code: Int, pressed: Boolean): String =
        """{"type":"button","v":$V,"code":$code,"pressed":$pressed}"""

    /** Keyboard key press/release. */
    fun key(code: Int, pressed: Boolean): String =
        """{"type":"key","v":$V,"code":$code,"pressed":$pressed}"""

    /** Touch event. */
    fun touch(slot: Int, phase: String, x: Double, y: Double): String {
        requireFinite(x, "x")
        requireFinite(y, "y")
        require(phase in VALID_TOUCH_PHASES) { "Invalid touch phase: $phase" }
        return """{"type":"touch","v":$V,"slot":$slot,"phase":"$phase","x":$x,"y":$y}"""
    }

    private val VALID_TOUCH_PHASES = setOf("down", "move", "up", "cancel")

    /** Scroll event. */
    fun scroll(dx: Double, dy: Double): String {
        requireFinite(dx, "dx")
        requireFinite(dy, "dy")
        return """{"type":"scroll","v":$V,"dx":$dx,"dy":$dy}"""
    }

    /** Continuous scrolling anchored in stream pixels, with an explicit end-of-gesture event. */
    fun scrollPixels(x: Double, y: Double, dx: Double, dy: Double, stop: Boolean = false): String {
        requireFinite(x, "x"); requireFinite(y, "y")
        requireFinite(dx, "dx"); requireFinite(dy, "dy")
        return """{"type":"scroll_pixels","v":$V,"x":$x,"y":$y,"dx":$dx,"dy":$dy,"stop":$stop}"""
    }

    /** Text input (from IME). */
    fun text(content: String): String {
        // Escape JSON special characters and all control characters
        val sb = StringBuilder(content.length + 16)
        for (ch in content) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f") // form feed
                else -> {
                    if (ch.code < 0x20) {
                        // Escape all other control characters as \uXXXX
                        sb.append("\\u")
                        sb.append(String.format(java.util.Locale.US, "%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return """{"type":"text","v":$V,"text":"$sb"}"""
    }

    /** Show/hide cursor. */
    fun cursorVisibility(visible: Boolean): String =
        """{"type":"cursor_visibility","v":$V,"visible":$visible}"""

    /** Request viewport resize. */
    fun resize(width: Int, height: Int): String =
        """{"type":"resize","v":$V,"width":$width,"height":$height}"""

    /** Request IDR frame. */
    fun keyframe(): String =
        """{"type":"keyframe","v":$V}"""

    /** Request bitrate change. */
    fun bitrate(targetKbps: Int): String =
        """{"type":"bitrate","v":$V,"target_kbps":$targetKbps}"""

    /** Request compositor window list for app switching. */
    fun listWindows(): String =
        """{"type":"list_windows","v":$V}"""

    /** Request focus on a specific compositor window.
     *  Address is hex-only (validated server-side), but we sanitize anyway. */
    fun focusWindow(windowAddress: String): String {
        // Only allow hex digits and 'x' prefix — reject anything else.
        val safe = windowAddress.filter { it in "0123456789abcdefABCDEFxX" }
        return """{"type":"focus_window","v":$V,"window_address":"$safe"}"""
    }

    /** Launch a desktop application by ID.
     *  Only alphanumeric characters, hyphens, and underscores are allowed. */
    fun launchApp(appId: String): String {
        val safe = appId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return """{"type":"launch_app","v":$V,"app_id":"$safe"}"""
    }

    /**
     * Report network statistics for server-side adaptive bitrate.
     *
     * @param packetLoss Fraction of packets lost (0.0 = none, 1.0 = all).
     * @param rttMs Round-trip time in milliseconds.
     * @param availableBandwidthKbps Estimated available bandwidth in kbps (0 if unknown).
     * @param jitterMs Jitter in milliseconds.
     */
    fun networkStats(
        packetLoss: Double,
        rttMs: Double,
        availableBandwidthKbps: Int = 0,
        jitterMs: Double = 0.0
    ): String =
        """{"type":"network_stats","v":$V,"packet_loss":$packetLoss,"rtt_ms":$rttMs,"available_bandwidth_kbps":$availableBandwidthKbps,"jitter_ms":$jitterMs}"""
}
