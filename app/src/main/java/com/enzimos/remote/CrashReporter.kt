package com.enzimos.remote

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures uncaught exceptions and generates crash reports.
 *
 * Crash reports include:
 * - Exception type and message
 * - Full stack trace
 * - Device info
 * - Connection state at time of crash
 * - Truncated to prevent excessive log size
 */
object CrashReporter {

    /** Maximum stack trace length in characters. */
    const val MAX_STACKTRACE_LENGTH = 8192

    /** Maximum report length in characters. */
    const val MAX_REPORT_LENGTH = 16384

    /**
     * Generate a crash report from a throwable.
     *
     * @param throwable The exception
     * @param connectionState Optional connection state info
     * @param sessionId Optional active session ID
     * @return Formatted crash report
     */
    fun generateReport(
        throwable: Throwable,
        connectionState: String? = null,
        sessionId: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== enzimos remote crash report ===")
        sb.appendLine()
        sb.appendLine("Exception: ${throwable.javaClass.simpleName}")
        sb.appendLine("Message: ${throwable.message ?: "(no message)"}")
        sb.appendLine()

        // Device info
        sb.appendLine("Device: ${DeviceInfo.systemDescription()}")
        sb.appendLine("App: ${VersionInfo.displayString()}")
        sb.appendLine()

        // Connection state
        if (connectionState != null) {
            sb.appendLine("Connection: $connectionState")
        }
        if (sessionId != null) {
            sb.appendLine("Session: $sessionId")
        }
        sb.appendLine()

        // Stack trace
        val stackTrace = getStackTrace(throwable)
        sb.appendLine("Stack trace:")
        sb.appendLine(StringSanitizer.truncate(stackTrace, MAX_STACKTRACE_LENGTH, ellipsis = true))

        return StringSanitizer.truncate(sb.toString(), MAX_REPORT_LENGTH, ellipsis = true)
    }

    /**
     * Get the full stack trace as a string.
     */
    fun getStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /**
     * Extract the root cause of a throwable chain.
     */
    fun rootCause(throwable: Throwable): Throwable {
        var cause = throwable
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause ?: break
        }
        return cause
    }

    /**
     * Get a short description of the exception.
     *
     * Format: "ExceptionType: message"
     */
    fun shortDescription(throwable: Throwable): String {
        val name = throwable.javaClass.simpleName
        val message = throwable.message ?: "(no message)"
        return "$name: $message"
    }

    /**
     * Check if an exception is likely a network-related error.
     */
    fun isNetworkError(throwable: Throwable): Boolean {
        val name = throwable.javaClass.name.lowercase()
        val message = throwable.message?.lowercase() ?: ""
        return name.contains("socket") ||
            name.contains("connect") ||
            name.contains("timeout") ||
            name.contains("network") ||
            message.contains("connection") ||
            message.contains("unreachable") ||
            message.contains("refused")
    }
}
