package com.enzimos.remote

import android.os.Build
import java.util.Locale
import java.util.UUID

/**
 * Provides device identification information for the signaling protocol.
 *
 * The device_id in the Hello message uniquely identifies this device
 * to the host. It is derived from the Android model name and a
 * persistent UUID to ensure uniqueness across identical device models.
 */
object DeviceInfo {

    /**
     * Generate a human-readable device name.
     *
     * Format: "Manufacturer Model" (e.g., "Samsung SM-G998B", "Google Pixel 7")
     */
    fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim() ?: "Unknown"
        val model = Build.MODEL?.trim() ?: "Unknown"

        // If model already starts with manufacturer, don't duplicate.
        return if (model.lowercase(Locale.US).startsWith(manufacturer.lowercase(Locale.US))) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    /**
     * Generate a device_id suitable for the signaling protocol.
     *
     * Format: lowercase model name with spaces/special chars replaced
     * by hyphens, truncated to 64 chars.
     *
     * @param uuid Optional UUID suffix for uniqueness
     */
    fun deviceId(uuid: String? = null): String {
        val base = deviceName()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]"), "-")
            .replace(Regex("-+"), "-") // collapse multiple hyphens
            .trim('-')

        val id = if (uuid != null) {
            val shortUuid = uuid.take(8) // first 8 chars of UUID
            "$base-$shortUuid"
        } else {
            base
        }

        return id.take(64) // protocol max
    }

    /**
     * Generate a random UUID string for device uniqueness.
     */
    fun randomUuid(): String = UUID.randomUUID().toString()

    /**
     * Get the Android version string (e.g., "14", "15").
     */
    fun androidVersion(): String = Build.VERSION.RELEASE ?: "unknown"

    /**
     * Get the Android SDK level (e.g., 34, 35).
     */
    fun sdkLevel(): Int = Build.VERSION.SDK_INT

    /**
     * Get a human-readable system description for diagnostics.
     *
     * Format: "Device Name (Android X, API Y)"
     */
    fun systemDescription(): String {
        return "${deviceName()} (Android ${androidVersion()}, API ${sdkLevel()})"
    }
}
