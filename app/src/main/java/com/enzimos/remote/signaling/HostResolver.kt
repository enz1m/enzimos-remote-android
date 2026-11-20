package com.enzimos.remote.signaling

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Resolves hostnames to IP addresses for WebSocket connections.
 *
 * Prefers IPv4 addresses for compatibility with most WebRTC
 * ICE implementations. Falls back to IPv6 if no IPv4 is available.
 *
 * All resolution methods are synchronous and should be called
 * from a background thread.
 */
object HostResolver {

    /** Resolution result. */
    data class ResolvedHost(
        val hostname: String,
        val ipv4: String?,
        val ipv6: String?,
    ) {
        /** Best address for connection (prefers IPv4). */
        val preferredAddress: String?
            get() = ipv4 ?: ipv6

        /** Whether resolution was successful. */
        val isResolved: Boolean
            get() = preferredAddress != null
    }

    /**
     * Resolve a hostname to IPv4 and IPv6 addresses.
     *
     * @param hostname Hostname or mDNS name to resolve
     * @return Resolution result with IPv4 and/or IPv6 addresses
     */
    fun resolve(hostname: String): ResolvedHost {
        return try {
            val addresses = InetAddress.getAllByName(hostname)
            val ipv4 = addresses.filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress
            val ipv6 = addresses.filterIsInstance<Inet6Address>()
                .firstOrNull()?.hostAddress
            ResolvedHost(hostname, ipv4, ipv6)
        } catch (_: UnknownHostException) {
            ResolvedHost(hostname, null, null)
        }
    }

    /**
     * Check if a string is already an IP address (no resolution needed).
     *
     * @param host The host string to check
     * @return true if it's an IPv4 or IPv6 address
     */
    fun isIpAddress(host: String): Boolean {
        return isIpv4(host) || isIpv6(host)
    }

    /**
     * Check if a string is an IPv4 address.
     */
    fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255
        }
    }

    /**
     * Check if a string is an IPv6 address.
     */
    fun isIpv6(host: String): Boolean {
        // Simple check: contains at least two colons
        val stripped = host.removePrefix("[").removeSuffix("]")
        if (stripped.count { it == ':' } < 2) return false
        // Validate hex groups
        val parts = stripped.split(':')
        return parts.size in 3..8 && parts.all { part ->
            part.isEmpty() || part.length <= 4 && part.all { c ->
                c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            }
        }
    }

    /**
     * Normalize a host string for connection.
     *
     * If it's already an IP address, returns it as-is.
     * If it's a hostname, attempts resolution.
     *
     * @param host Hostname or IP address
     * @return Resolved IP address, or the original host if resolution fails
     */
    fun normalizeForConnection(host: String): String {
        if (isIpAddress(host)) return host
        val resolved = resolve(host)
        return resolved.preferredAddress ?: host
    }
}
