package com.enzimos.remote.signaling

/**
 * Represents a discovered or manually added enzimos remote host.
 *
 * Hosts can be discovered via mDNS/NSD or added manually by the user.
 * Each host is uniquely identified by its address and port combination.
 */
data class HostInfo(
    /** Display name (from mDNS service name or user input). */
    val name: String,
    /** IP address or hostname. */
    val address: String,
    /** Signaling port number. */
    val port: Int = NetworkUtils.DEFAULT_PORT,
    /** Whether this host was discovered via mDNS (vs manually added). */
    val discovered: Boolean = false,
    /** Whether TLS (wss://) should be used. */
    val tls: Boolean = false,
    /** Optional auth token for this host. */
    val token: String? = null,
    /** Last time this host was seen (epoch ms, 0 = never). */
    val lastSeenMs: Long = 0,
) {
    /** Build the WebSocket URL for this host. */
    val wsUrl: String
        get() = NetworkUtils.buildWsUrl(address, port, tls)

    /** A unique key for this host (address:port). */
    val key: String
        get() = "$address:$port"

    /** Whether this host has an auth token configured. */
    val hasToken: Boolean
        get() = !token.isNullOrEmpty()

    /** Whether this host was recently seen (within the last 60 seconds). */
    val isRecent: Boolean
        get() = lastSeenMs > 0 && System.currentTimeMillis() - lastSeenMs < 60_000

    /**
     * Create a copy with updated last-seen time.
     */
    fun withLastSeen(): HostInfo = copy(lastSeenMs = System.currentTimeMillis())

    /**
     * Create a copy with a token.
     */
    fun withToken(newToken: String): HostInfo = copy(token = newToken)
}
