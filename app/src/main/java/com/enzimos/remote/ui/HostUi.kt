package com.enzimos.remote.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.View
import androidx.core.content.ContextCompat
import com.enzimos.remote.R
import com.enzimos.remote.connection.ConnectionManager.HostState
import com.enzimos.remote.connection.ConnectionManager.Status

/** Presentation helpers shared by the home screen and computer lists. */
object HostUi {
    fun statusColor(context: Context, status: Status): Int = ContextCompat.getColor(
        context,
        when (status) {
            Status.ONLINE -> R.color.status_connected
            Status.CONNECTING -> R.color.status_connecting
            Status.OFFLINE -> R.color.status_disconnected
            Status.NEEDS_PAIRING -> R.color.status_error
        },
    )

    fun tintDot(dot: View, status: Status) {
        (dot.background?.mutate() as? GradientDrawable)?.setColor(statusColor(dot.context, status))
    }

    /** "host" or "host:port" for display, hiding the default port. */
    fun displayAddress(address: String?): String? =
        address?.removeSuffix(":${com.enzimos.remote.pairing.QrCodeData.DEFAULT_PORT}")

    fun statusText(context: Context, state: HostState, detailed: Boolean = true): String = when (state.status) {
        Status.ONLINE -> context.getString(R.string.home_status_online)
        Status.CONNECTING -> context.getString(R.string.home_status_connecting)
        Status.NEEDS_PAIRING -> context.getString(R.string.home_status_needs_pairing)
        Status.OFFLINE -> {
            val last = state.host.lastConnectedAt
            if (detailed && last > 0) {
                context.getString(
                    R.string.computer_status_line,
                    context.getString(R.string.home_status_offline),
                    context.getString(R.string.computer_last_seen, relative(last)),
                )
            } else {
                context.getString(R.string.home_status_offline)
            }
        }
    }

    fun relative(timeMs: Long): String = DateUtils.getRelativeTimeSpanString(
        timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString().replaceFirstChar { it.lowercase() }

    fun appIcon(appId: String): Int = when (appId) {
        "files-app" -> R.drawable.ic_drawer_file_manager
        "text-app" -> R.drawable.ic_drawer_text_editor
        "terminal-emulator" -> R.drawable.ic_drawer_terminal
        "task-manager-app" -> R.drawable.ic_app_monitor
        "calendar-app" -> R.drawable.ic_app_calendar
        "mail-app" -> R.drawable.ic_app_mail
        "music-app" -> R.drawable.ic_app_music
        "reader-app" -> R.drawable.ic_app_reader
        "store-app" -> R.drawable.ic_app_store
        "remote-app" -> R.drawable.ic_app_remote
        "desktop" -> R.drawable.ic_remote_desktop
        else -> R.drawable.ic_app_generic
    }

    fun duration(seconds: Long): String {
        val minutes = seconds / 60
        return when {
            minutes < 1 -> "<1 min"
            minutes < 60 -> "$minutes min"
            minutes < 24 * 60 -> "${minutes / 60} h"
            minutes < 48 * 60 -> "1 day"
            else -> "${minutes / (60 * 24)} days"
        }
    }

    /** "12th Gen Intel(R) Core(TM) i9-12900K" → "Intel Core i9-12900K". */
    fun cpuName(model: String): String = model
        .replace(Regex("""\((R|TM|tm|r)\)"""), "")
        .replace(Regex("""\b\d+(st|nd|rd|th) Gen\b"""), "")
        .replace(Regex("""\s+(CPU|Processor)\b.*$"""), "")
        .replace(Regex("""\s+@.*$"""), "")
        .replace(Regex("""\s+\d+-Core\b"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** Throughput, e.g. "84 KB/s" or "1.2 MB/s". */
    fun rate(bytesPerSecond: Long): String {
        val value = bytesPerSecond.coerceAtLeast(0).toDouble()
        return when {
            value >= 1_000_000 -> "%.1f MB/s".format(value / 1_000_000)
            value >= 1_000 -> "%.0f KB/s".format(value / 1_000)
            else -> "%.0f B/s".format(value)
        }
    }

    fun bytes(value: Long): String {
        val gb = value.coerceAtLeast(0) / 1_073_741_824.0
        return if (gb >= 100) "%.0f GB".format(gb) else "%.1f GB".format(gb)
    }
}
