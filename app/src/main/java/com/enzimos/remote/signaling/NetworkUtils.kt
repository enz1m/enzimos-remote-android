package com.enzimos.remote.signaling

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Network utility functions for WebSocket and mDNS connectivity.
 *
 * Provides:
 * - WebSocket URL construction and validation
 * - Local IP address discovery (for debug/diagnostic display)
 * - Host address formatting (IPv4 and IPv6)
 */
object NetworkUtils {

    /** Default signaling port. */
    const val DEFAULT_PORT = 9470

    /** WebSocket scheme for unencrypted connections. */
    const val WS_SCHEME = "ws"

    /** WebSocket scheme for TLS connections. */
    const val WSS_SCHEME = "wss"

    /**
     * Build a WebSocket URL from host, port, and TLS preference.
     *
     * @param host Hostname or IP address
     * @param port Port number (default 9470)
     * @param tls Whether to use wss:// (default false)
     * @return WebSocket URL string
     */
    fun buildWsUrl(host: String, port: Int = DEFAULT_PORT, tls: Boolean = false): String {
        val scheme = if (tls) WSS_SCHEME else WS_SCHEME
        val formattedHost = formatHost(host)
        return "$scheme://$formattedHost:$port"
    }

    /**
     * Format a host address for URL construction.
     *
     * IPv6 addresses are wrapped in brackets: `[::1]`.
     * IPv4 addresses and hostnames are returned as-is.
     */
    fun formatHost(host: String): String {
        return if (host.contains(':') && !host.startsWith('[')) {
            "[$host]"
        } else {
            host
        }
    }

    /** Returns an error message for an invalid WebSocket endpoint, or null for a valid one. */
    fun validateWsUrl(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return "invalid URL syntax"
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != WS_SCHEME && scheme != WSS_SCHEME) {
            return "scheme must be ws:// or wss://"
        }

        if (uri.host.isNullOrBlank()) {
            return "host is required"
        }

        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) {
            return "port must be between 1 and 65535"
        }

        return null // valid
    }

    /**
     * Check if a URL uses TLS (wss://).
     */
    fun isTls(url: String): Boolean {
        return url.lowercase(Locale.US).startsWith("$WSS_SCHEME://")
    }

    /**
     * Get the local IPv4 addresses (non-loopback).
     *
     * Useful for debug/diagnostic display to help the user identify
     * which network the device is connected to.
     *
     * @return List of local IPv4 addresses as strings
     */
    fun localIpv4Addresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress }
                        .map { it.hostAddress ?: "" }
                        .filter { it.isNotEmpty() }
                } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Extract the host from a WebSocket URL.
     *
     * @return The host, or null if the URL is invalid
     */
    fun extractHost(url: String): String? {
        return try {
            URI(url).host
        } catch (_: URISyntaxException) {
            null
        }
    }

    /**
     * Extract the port from a WebSocket URL.
     *
     * @return The port, or [DEFAULT_PORT] if not specified
     */
    fun extractPort(url: String): Int {
        return try {
            val port = URI(url).port
            if (port == -1) DEFAULT_PORT else port
        } catch (_: URISyntaxException) {
            DEFAULT_PORT
        }
    }
}
