package com.enzimos.remote.webrtc

import android.util.Log

/**
 * Parses JSON messages received from the server via the data channel.
 *
 * Currently supported message types:
 * - `window_list`: list of compositor windows for app switching
 * - `abr_status`: adaptive bitrate controller status
 *
 * Uses simple regex/manual parsing to avoid Android-only `org.json`
 * dependency, making it testable in plain JUnit tests.
 */
object ServerMessageParser {

    private const val TAG = "ServerMessageParser"

    /**
     * Parsed server message.
     */
    sealed class ServerMessage {
        /** List of windows available in the compositor. */
        data class WindowList(val windows: List<WindowInfo>) : ServerMessage()

        /** Adaptive bitrate status update. */
        data class AbrStatus(
            val currentKbps: Int,
            val maxKbps: Int,
            val enabled: Boolean,
        ) : ServerMessage()

        /** Clipboard text from the host. */
        data class Clipboard(val text: String) : ServerMessage()

        data class StreamConfig(val contentResize: Boolean, val inputDrain: Boolean = false) : ServerMessage()
        data class ContentSizeAck(val serial: Int, val applied: Boolean) : ServerMessage()
        data class InputDrainAck(val serial: Int, val applied: Boolean) : ServerMessage()
        /** A game asked a gamepad to rumble (magnitudes 0..1; 0 ms stops). */
        data class GamepadRumble(val id: Int, val strong: Double, val weak: Double, val durationMs: Int) : ServerMessage()

        /** Unknown or unparseable message. */
        data class Unknown(val type: String) : ServerMessage()
    }

    /**
     * Info about a single compositor window.
     */
    data class WindowInfo(
        val address: String,
        val title: String,
        val className: String,
        val mapped: Boolean,
        val focusHistoryId: Int,
    )

    /**
     * Parse a JSON string from the server data channel.
     *
     * @return parsed [ServerMessage], or null if the JSON is invalid
     */
    fun parse(json: String): ServerMessage? {
        if (json.isBlank()) return null
        return try {
            val type = extractString(json, "type") ?: return null
            when (type) {
                "stream_config" -> ServerMessage.StreamConfig(
                    extractBoolean(json, "content_resize") == true,
                    extractBoolean(json, "input_drain") == true,
                )
                "input_drain_ack" -> ServerMessage.InputDrainAck(
                    extractInt(json, "serial")?.takeIf { it > 0 } ?: return null,
                    extractBoolean(json, "applied") ?: return null,
                )
                "content_size_ack" -> ServerMessage.ContentSizeAck(
                    extractInt(json, "serial") ?: return null,
                    extractBoolean(json, "applied") ?: return null,
                )
                "gamepad_rumble" -> ServerMessage.GamepadRumble(
                    extractInt(json, "id") ?: return null,
                    extractDouble(json, "strong") ?: 0.0,
                    extractDouble(json, "weak") ?: 0.0,
                    extractInt(json, "duration_ms") ?: 0,
                )
                "window_list" -> parseWindowList(json)
                "abr_status" -> parseAbrStatus(json)
                "clipboard" -> ServerMessage.Clipboard(
                    text = extractString(json, "text") ?: ""
                )
                else -> {
                    Log.w(TAG, "Unknown server message type: $type")
                    ServerMessage.Unknown(type)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse server message: ${e.message}")
            null
        }
    }

    private fun parseWindowList(json: String): ServerMessage.WindowList {
        // Extract the "windows" array content.
        val windowsStart = json.indexOf("\"windows\"")
        if (windowsStart < 0) return ServerMessage.WindowList(emptyList())

        val arrayStart = json.indexOf('[', windowsStart)
        if (arrayStart < 0) return ServerMessage.WindowList(emptyList())

        // Find matching close bracket.
        var depth = 0
        var arrayEnd = -1
        for (i in arrayStart until json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrayEnd = i
                        break
                    }
                }
            }
        }
        if (arrayEnd < 0) return ServerMessage.WindowList(emptyList())

        val arrayContent = json.substring(arrayStart + 1, arrayEnd).trim()
        if (arrayContent.isEmpty()) return ServerMessage.WindowList(emptyList())

        // Split into individual window objects.
        val windows = mutableListOf<WindowInfo>()
        var objDepth = 0
        var objStart = -1
        for (i in arrayContent.indices) {
            when (arrayContent[i]) {
                '{' -> {
                    if (objDepth == 0) objStart = i
                    objDepth++
                }
                '}' -> {
                    objDepth--
                    if (objDepth == 0 && objStart >= 0) {
                        val objStr = arrayContent.substring(objStart, i + 1)
                        windows.add(parseWindowInfo(objStr))
                        objStart = -1
                    }
                }
            }
        }

        return ServerMessage.WindowList(windows)
    }

    private fun parseWindowInfo(json: String): WindowInfo {
        return WindowInfo(
            address = extractString(json, "address") ?: "",
            title = extractString(json, "title") ?: "",
            className = extractString(json, "class") ?: "",
            mapped = extractBoolean(json, "mapped") ?: true,
            focusHistoryId = extractInt(json, "focusHistoryID") ?: -1,
        )
    }

    private fun parseAbrStatus(json: String): ServerMessage.AbrStatus {
        return ServerMessage.AbrStatus(
            currentKbps = extractInt(json, "current_kbps") ?: 0,
            maxKbps = extractInt(json, "max_kbps") ?: 0,
            enabled = extractBoolean(json, "enabled") ?: true,
        )
    }

    // Simple JSON value extractors (no external JSON library dependency).

    internal fun extractString(json: String, key: String): String? {
        // Match JSON string values, handling escaped characters (e.g. \", \\).
        val pattern = "\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
        val match = Regex(pattern).find(json) ?: return null
        return unescapeJson(match.groupValues[1])
    }

    /** Unescape JSON string escape sequences. */
    private fun unescapeJson(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    else -> { sb.append(s[i]); i++ }
                }
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    internal fun extractInt(json: String, key: String): Int? {
        val pattern = "\"$key\"\\s*:\\s*(-?\\d+)"
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    internal fun extractDouble(json: String, key: String): Double? {
        val pattern = "\"$key\"\\s*:\\s*(-?[0-9.eE+-]+)"
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    internal fun extractBoolean(json: String, key: String): Boolean? {
        val pattern = "\"$key\"\\s*:\\s*(true|false)"
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1].toBoolean()
    }
}
