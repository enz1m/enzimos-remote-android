package com.enzimos.remote

import com.enzimos.remote.signaling.*
import com.enzimos.remote.video.ScreenMetrics
import java.util.Locale

/**
 * Builds session creation parameters from device characteristics.
 *
 * Combines screen metrics, user preferences, and protocol constraints
 * to generate valid session creation messages.
 */
object SessionCreator {

    /**
     * Session creation parameters.
     */
    data class Params(
        val sessionId: String,
        val command: List<String>,
        val width: Int,
        val height: Int,
        val scale: Double,
        val refreshHz: Int,
    ) {
        /**
         * Validate against protocol constraints.
         *
         * @return null if valid, error message if invalid
         */
        fun validate(): String? {
            if (!validateSessionId(sessionId)) {
                return "Invalid session ID: must be 1-64 lowercase letters, digits, hyphens, underscores"
            }
            if (command.isEmpty()) {
                return "Command cannot be empty"
            }
            if (width < MIN_WIDTH || width > MAX_WIDTH) {
                return "Width must be between $MIN_WIDTH and $MAX_WIDTH"
            }
            if (height < MIN_HEIGHT || height > MAX_HEIGHT) {
                return "Height must be between $MIN_HEIGHT and $MAX_HEIGHT"
            }
            if (scale < MIN_SCALE || scale > MAX_SCALE) {
                return "Scale must be between $MIN_SCALE and $MAX_SCALE"
            }
            if (refreshHz < MIN_REFRESH_HZ || refreshHz > MAX_REFRESH_HZ) {
                return "Refresh rate must be between $MIN_REFRESH_HZ and $MAX_REFRESH_HZ"
            }
            return null
        }
    }

    /**
     * Generate a session ID from a human-readable name.
     *
     * Converts to lowercase, replaces spaces/special chars with hyphens,
     * truncates to 64 chars.
     *
     * @param name Human-readable session name
     * @return Protocol-valid session ID
     */
    fun sessionIdFromName(name: String): String {
        return name
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(64)
            .ifEmpty { "session" }
    }

    /** Creates a viewport sized to the phone screen within the host protocol limits. */
    fun fromScreenMetrics(
        sessionName: String,
        command: List<String>,
        dims: ScreenMetrics.Dimensions,
    ): Params {
        return Params(
            sessionId = sessionIdFromName(sessionName),
            command = command,
            width = dims.sessionWidth.coerceIn(MIN_WIDTH, MAX_WIDTH),
            height = dims.sessionHeight.coerceIn(MIN_HEIGHT, MAX_HEIGHT),
            scale = dims.scale.coerceIn(MIN_SCALE, MAX_SCALE),
            refreshHz = dims.refreshHz.coerceIn(MIN_REFRESH_HZ, MAX_REFRESH_HZ),
        )
    }

    fun custom(
        sessionName: String,
        command: List<String>,
        width: Int,
        height: Int,
        scale: Double = 1.0,
        refreshHz: Int = 60,
    ): Params {
        return Params(
            sessionId = sessionIdFromName(sessionName),
            command = command,
            width = width,
            height = height,
            scale = scale,
            refreshHz = refreshHz,
        )
    }
}
