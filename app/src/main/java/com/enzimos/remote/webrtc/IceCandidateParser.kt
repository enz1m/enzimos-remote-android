package com.enzimos.remote.webrtc

/**
 * Parses and classifies ICE candidates for diagnostics.
 *
 * ICE candidates represent possible network paths for WebRTC.
 * This parser extracts useful information for debugging and
 * for the connection quality indicator.
 */
object IceCandidateParser {

    /** ICE candidate types. */
    enum class CandidateType {
        HOST,
        SERVER_REFLEXIVE, // srflx
        PEER_REFLEXIVE,   // prflx
        RELAY,            // relay
        UNKNOWN,
    }

    /** ICE transport protocol. */
    enum class TransportProtocol {
        UDP,
        TCP,
        UNKNOWN,
    }

    /** Parsed ICE candidate information. */
    data class CandidateInfo(
        val foundation: String,
        val component: Int,
        val protocol: TransportProtocol,
        val priority: Long,
        val address: String,
        val port: Int,
        val type: CandidateType,
    )

    /**
     * Parse an ICE candidate string.
     *
     * Format: candidate:foundation component protocol priority address port typ type
     *
     * @param candidate ICE candidate attribute string
     * @return Parsed candidate info, or null if parsing fails
     */
    fun parse(candidate: String): CandidateInfo? {
        val stripped = candidate.removePrefix("a=").removePrefix("candidate:")
        val parts = stripped.split(' ')
        if (parts.size < 8) return null

        val foundation = parts[0]
        val component = parts[1].toIntOrNull() ?: return null
        val protocol = when (parts[2].uppercase()) {
            "UDP" -> TransportProtocol.UDP
            "TCP" -> TransportProtocol.TCP
            else -> TransportProtocol.UNKNOWN
        }
        val priority = parts[3].toLongOrNull() ?: return null
        val address = parts[4]
        val port = parts[5].toIntOrNull() ?: return null

        // parts[6] should be "typ"
        if (parts[6] != "typ") return null

        val type = when (parts[7]) {
            "host" -> CandidateType.HOST
            "srflx" -> CandidateType.SERVER_REFLEXIVE
            "prflx" -> CandidateType.PEER_REFLEXIVE
            "relay" -> CandidateType.RELAY
            else -> CandidateType.UNKNOWN
        }

        return CandidateInfo(foundation, component, protocol, priority, address, port, type)
    }

    /**
     * Classify a list of candidates to determine connection quality.
     *
     * - HOST only: excellent (direct LAN connection)
     * - HOST + SRFLX: good (NAT traversal possible)
     * - RELAY present: fair (TURN relay needed)
     * - No candidates: poor (ICE failed)
     */
    fun classifyQuality(candidates: List<CandidateInfo>): String {
        if (candidates.isEmpty()) return "Poor"
        val types = candidates.map { it.type }.toSet()
        return when {
            types.contains(CandidateType.HOST) && !types.contains(CandidateType.RELAY) -> "Excellent"
            types.contains(CandidateType.SERVER_REFLEXIVE) -> "Good"
            types.contains(CandidateType.RELAY) -> "Fair"
            else -> "Unknown"
        }
    }
}
