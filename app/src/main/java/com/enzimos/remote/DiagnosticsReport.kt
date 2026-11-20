package com.enzimos.remote

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formats device, connection, and performance diagnostics for sharing as plain text. */
object DiagnosticsReport {

    /**
     * Build a diagnostic report string.
     *
     * @param sections Key-value sections to include
     * @return Formatted report text
     */
    fun build(vararg sections: Section): String {
        val sb = StringBuilder()
        sb.appendLine("=== enzimos remote diagnostic report ===")
        sb.appendLine("Generated: ${formatTimestamp(System.currentTimeMillis())}")
        sb.appendLine()

        for (section in sections) {
            sb.appendLine("--- ${section.title} ---")
            for ((key, value) in section.entries) {
                sb.appendLine("  $key: $value")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Create a device info section.
     */
    fun deviceSection(): Section {
        return Section(
            "Device",
            listOf(
                "Model" to DeviceInfo.deviceName(),
                "Android" to DeviceInfo.androidVersion(),
                "SDK" to Build.VERSION.SDK_INT.toString(),
                "Device ID" to DeviceInfo.deviceId(),
            ),
        )
    }

    /**
     * Create a connection section.
     */
    fun connectionSection(
        host: String,
        port: Int,
        state: String,
        sessionId: String?,
    ): Section {
        val entries = mutableListOf(
            "Host" to host,
            "Port" to port.toString(),
            "State" to state,
        )
        if (sessionId != null) {
            entries.add("Session" to sessionId)
        }
        return Section("Connection", entries)
    }

    /**
     * Create a performance section.
     */
    fun performanceSection(
        fps: Double,
        rttMs: Long,
        bitrateKbps: Int,
        framesDropped: Long,
    ): Section {
        return Section(
            "Performance",
            listOf(
                "FPS" to String.format(Locale.US, "%.1f", fps),
                "RTT" to "${rttMs}ms",
                "Bitrate" to "${bitrateKbps} kbps",
                "Frames dropped" to framesDropped.toString(),
            ),
        )
    }

    /**
     * Format a timestamp for the report.
     */
    fun formatTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(epochMs))
    }

    /** A section in the diagnostic report. */
    data class Section(
        val title: String,
        val entries: List<Pair<String, String>>,
    )
}
