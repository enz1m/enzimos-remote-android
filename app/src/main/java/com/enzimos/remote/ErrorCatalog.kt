package com.enzimos.remote

/**
 * Maps protocol error codes and conditions to user-friendly messages.
 *
 * The host sends various error strings that may contain technical details
 * not suitable for end users. This catalog maps known error patterns to
 * actionable, localized suggestions.
 */
object ErrorCatalog {

    /**
     * Convert a host error string to a user-friendly message and suggestion.
     *
     * @param error The raw error string from the host
     * @return Pair of (title, suggestion)
     */
    fun userMessage(error: String): Pair<String, String> {
        val lower = error.lowercase()
        return when {
            // Authentication errors
            lower.contains("invalid token") || lower.contains("authentication failed") ->
                "Authentication Failed" to "Check your pairing token or re-scan the QR code."

            lower.contains("duplicate device") ->
                "Already Connected" to "This device is already connected. Disconnect first or use a different device ID."

            // Session errors
            lower.contains("max") && lower.contains("session") ->
                "Session Limit Reached" to "The host has too many active sessions. Stop an existing session first."

            lower.contains("not found") && lower.contains("session") ->
                "Session Not Found" to "The session may have been stopped or expired. Refresh the session list."

            lower.contains("not the owner") ->
                "Not Session Owner" to "Another device owns this session. Use 'Resume' to take ownership."

            // Connection errors
            lower.contains("rate limit") ->
                "Too Many Requests" to "Slow down and try again in a few seconds."

            lower.contains("timeout") ->
                "Connection Timeout" to "The host did not respond in time. Check network connectivity."

            lower.contains("version") && lower.contains("unsupported") ->
                "Version Mismatch" to "The host and client protocol versions don't match. Update the app."

            // Validation errors
            lower.contains("invalid") && lower.contains("dimension") ->
                "Invalid Screen Size" to "The requested screen dimensions are not supported."

            lower.contains("invalid") && lower.contains("session_id") ->
                "Invalid Session Name" to "Session names must use lowercase letters, numbers, hyphens, or underscores."

            lower.contains("invalid") && lower.contains("scale") ->
                "Invalid Scale" to "Scale must be between 0.5 and 4.0."

            // WebRTC errors
            lower.contains("sdp") || lower.contains("offer") || lower.contains("answer") ->
                "Negotiation Error" to "WebRTC session negotiation failed. Try reconnecting."

            lower.contains("ice") && lower.contains("failed") ->
                "Connection Failed" to "Could not establish a peer-to-peer connection. Check firewall settings."

            // Generic
            lower.contains("connection") && lower.contains("error") ->
                "Connection Error" to "An error occurred. Check your network and try again."

            else ->
                "Error" to error
        }
    }

    /**
     * Check if an error is recoverable (worth retrying automatically).
     */
    fun isRecoverable(error: String): Boolean {
        val lower = error.lowercase()
        return lower.contains("timeout") ||
            lower.contains("rate limit") ||
            lower.contains("connection") ||
            lower.contains("network")
    }

    /**
     * Check if the error requires user action (re-pairing, etc.).
     */
    fun requiresUserAction(error: String): Boolean {
        val lower = error.lowercase()
        return lower.contains("invalid token") ||
            lower.contains("authentication failed") ||
            lower.contains("version") ||
            lower.contains("not the owner")
    }
}
