package com.enzimos.remote.webrtc

/**
 * Configuration for ICE (STUN/TURN) servers used by WebRTC.
 *
 * STUN servers help discover the device's public IP address.
 * TURN servers relay media when direct connections fail.
 *
 * For LAN-only connections, no ICE servers are needed.
 * For WAN connections, at least a STUN server is recommended.
 */
data class IceServerConfig(
    /** STUN/TURN server URL. */
    val url: String,
    /** Username for TURN authentication (empty for STUN). */
    val username: String = "",
    /** Credential for TURN authentication (empty for STUN). */
    val credential: String = "",
) {
    /** Whether this is a TURN server (requires credentials). */
    val isTurn: Boolean
        get() = url.startsWith("turn:") || url.startsWith("turns:")

    /** Whether this is a STUN server. */
    val isStun: Boolean
        get() = url.startsWith("stun:") || url.startsWith("stuns:")

    /** Whether credentials are provided. */
    val hasCredentials: Boolean
        get() = username.isNotEmpty() && credential.isNotEmpty()

    /**
     * Validate the configuration.
     *
     * @return null if valid, error message if invalid
     */
    fun validate(): String? {
        if (url.isBlank()) return "URL is required"
        if (!isStun && !isTurn) return "URL must start with stun:, stuns:, turn:, or turns:"
        if (isTurn && !hasCredentials) return "TURN servers require username and credential"
        return null
    }

    companion object {
        /** Google's public STUN server. */
        val GOOGLE_STUN = IceServerConfig("stun:stun.l.google.com:19302")

        /** Default ICE servers for WAN connections. */
        val DEFAULT_SERVERS = listOf(GOOGLE_STUN)

        /** Empty list for LAN-only connections. */
        val LAN_ONLY = emptyList<IceServerConfig>()

        /**
         * Create a TURN server configuration.
         */
        fun turn(url: String, username: String, credential: String): IceServerConfig {
            return IceServerConfig(url, username, credential)
        }

        /**
         * Create a STUN server configuration.
         */
        fun stun(url: String): IceServerConfig {
            return IceServerConfig(url)
        }
    }
}
