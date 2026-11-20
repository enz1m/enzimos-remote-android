package com.enzimos.remote.webrtc

/**
 * Utilities for WebRTC Session Description Protocol (SDP) manipulation.
 *
 * Provides helpers to modify SDP for optimal streaming performance:
 * - Set preferred video codec
 * - Set maximum bitrate
 * - Extract media information
 * - Redact sensitive information for logging
 */
object SdpHelper {

    /** Preserve media-line order when translating protocol ICE indexes to MIDs. */
    fun mediaIds(sdp: String): List<String?> {
        val result = mutableListOf<String?>()
        for (line in sdp.lineSequence()) {
            if (line.startsWith("m=")) result.add(null)
            else if (line.startsWith("a=mid:") && result.isNotEmpty()) {
                result[result.lastIndex] = line.removePrefix("a=mid:").trim().takeIf { it.isNotEmpty() }
            }
        }
        return result
    }


    /**
     * Extract the first video codec from an SDP.
     *
     * @param sdp The SDP string
     * @return Codec name (e.g., "H264", "VP8") or null if not found
     */
    fun extractVideoCodec(sdp: String): String? {
        val lines = sdp.lines()
        val videoMLine = lines.find { it.startsWith("m=video") } ?: return null
        val payloadTypes = videoMLine.split(' ').drop(3) // Skip "m=video port proto"
        if (payloadTypes.isEmpty()) return null

        val firstPt = payloadTypes[0]
        // Find the rtpmap for this payload type
        val rtpmap = lines.find {
            it.startsWith("a=rtpmap:$firstPt ") ||
                it.startsWith("a=rtpmap:$firstPt/")
        } ?: return null

        // Format: a=rtpmap:96 H264/90000
        val codec = rtpmap.substringAfter(' ').substringBefore('/')
        return codec.uppercase()
    }

    /**
     * Check if the SDP contains a video m-line.
     */
    fun hasVideo(sdp: String): Boolean {
        return sdp.lines().any { it.startsWith("m=video") }
    }

    /**
     * Check if the SDP contains an audio m-line.
     */
    fun hasAudio(sdp: String): Boolean {
        return sdp.lines().any { it.startsWith("m=audio") }
    }

    /**
     * Check if the SDP contains a data channel m-line.
     */
    fun hasDataChannel(sdp: String): Boolean {
        return sdp.lines().any {
            it.startsWith("m=application") && it.contains("webrtc-datachannel")
        }
    }

    /**
     * Count the number of media lines in the SDP.
     */
    fun mediaLineCount(sdp: String): Int {
        return sdp.lines().count { it.startsWith("m=") }
    }

    /**
     * Extract ICE candidates from an SDP.
     *
     * @return List of ICE candidate attribute lines
     */
    fun extractIceCandidates(sdp: String): List<String> {
        return sdp.lines().filter { it.startsWith("a=candidate:") }
    }

    /**
     * Redact sensitive information from an SDP for safe logging.
     *
     * Replaces:
     * - IP addresses with [REDACTED]
     * - ICE credentials with [REDACTED]
     * - Fingerprints with [REDACTED]
     */
    fun redact(sdp: String): String {
        var result = sdp
        // Redact IPv4 addresses
        result = result.replace(
            Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"),
            "[REDACTED-IP]"
        )
        // Redact ICE credentials
        result = result.replace(
            Regex("a=ice-ufrag:.*"),
            "a=ice-ufrag:[REDACTED]"
        )
        result = result.replace(
            Regex("a=ice-pwd:.*"),
            "a=ice-pwd:[REDACTED]"
        )
        // Redact DTLS fingerprint
        result = result.replace(
            Regex("a=fingerprint:.*"),
            "a=fingerprint:[REDACTED]"
        )
        return result
    }

    /**
     * Check if an SDP string is syntactically valid (basic check).
     *
     * Verifies:
     * - Starts with "v=0"
     * - Contains at least one m= line
     * - Not empty
     */
    fun isValid(sdp: String): Boolean {
        if (sdp.isBlank()) return false
        val lines = sdp.lines().map { it.trim() }
        if (!lines.any { it == "v=0" }) return false
        if (!lines.any { it.startsWith("m=") }) return false
        return true
    }
}
