package com.enzimos.remote

/**
 * String sanitization utilities for secure protocol communication.
 *
 * Ensures user-provided strings are safe for JSON embedding,
 * protocol transmission, and display in the UI.
 */
object StringSanitizer {

    /**
     * Remove control characters from a string.
     *
     * Strips all Unicode control characters (U+0000-U+001F, U+007F)
     * except newline, tab, and carriage return.
     */
    fun stripControlChars(input: String): String {
        return input.filter { ch ->
            ch == '\n' || ch == '\t' || ch == '\r' || !ch.isISOControl()
        }
    }

    /**
     * Remove all non-printable characters.
     */
    fun stripNonPrintable(input: String): String {
        return input.filter { ch ->
            ch >= ' ' || ch == '\n' || ch == '\t' || ch == '\r'
        }
    }

    /**
     * Truncate a string to a maximum length.
     *
     * @param input The string to truncate
     * @param maxLength Maximum length
     * @param ellipsis Whether to add "..." at the end
     * @return Truncated string
     */
    fun truncate(input: String, maxLength: Int, ellipsis: Boolean = false): String {
        if (input.length <= maxLength) return input
        return if (ellipsis && maxLength > 3) {
            input.take(maxLength - 3) + "..."
        } else {
            input.take(maxLength)
        }
    }

    /**
     * Sanitize a session ID from user input.
     *
     * Converts to lowercase, replaces invalid characters with hyphens,
     * collapses multiple hyphens, trims, and truncates.
     */
    fun sanitizeSessionId(input: String): String {
        return input.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(64)
            .ifEmpty { "session" }
    }

    /**
     * Sanitize a device ID from user input.
     *
     * Replaces invalid characters, trims, and truncates.
     */
    fun sanitizeDeviceId(input: String): String {
        return input.trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(128)
            .ifEmpty { "device" }
    }

    /**
     * Check if a string contains only safe ASCII characters.
     */
    fun isSafeAscii(input: String): Boolean {
        return input.all { it in ' '..'~' }
    }

    /**
     * Escape HTML special characters for safe display.
     */
    fun escapeHtml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
