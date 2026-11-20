package com.enzimos.remote

import com.enzimos.remote.signaling.PROTOCOL_VERSION

/**
 * Application and protocol version information.
 *
 * Provides version strings for:
 * - App version (from BuildConfig)
 * - Protocol version (from signaling constants)
 * - User-Agent string for WebSocket connections
 */
object VersionInfo {

    /** Protocol version supported by this client. */
    val protocolVersion: Int = PROTOCOL_VERSION

    /** Application version name. */
    val appVersion: String = BuildConfig.VERSION_NAME

    /** Application version code. */
    val appVersionCode: Int = BuildConfig.VERSION_CODE

    /** Whether this is a debug build. */
    val isDebug: Boolean = BuildConfig.DEBUG

    /**
     * Build a User-Agent string for WebSocket connections.
     *
     * Format: "enzremote-android/VERSION (API LEVEL; MODEL)"
     */
    fun userAgent(): String {
        return "enzremote-android/$appVersion (API ${DeviceInfo.sdkLevel()}; ${DeviceInfo.deviceName()})"
    }

    /**
     * Build a version info string for display.
     */
    fun displayString(): String {
        val buildType = if (isDebug) " (debug)" else ""
        return "v$appVersion$buildType | Protocol v$protocolVersion"
    }

    /**
     * Check if the client protocol version is compatible with a host version.
     *
     * Currently, only exact version match is supported.
     *
     * @param hostVersion The protocol version reported by the host
     * @return true if compatible
     */
    fun isCompatible(hostVersion: Int): Boolean {
        return hostVersion == protocolVersion
    }

    /**
     * Get a short version string (e.g., "1.0.0").
     */
    fun shortVersion(): String = appVersion
}
