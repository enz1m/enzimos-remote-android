package com.enzimos.remote

internal class MediaRecovery {
    private var attempts = 0

    fun nextDelayMs(): Long? {
        if (attempts >= 8) return null
        return (1_000L shl attempts++).coerceAtMost(8_000L)
    }

    fun reset() { attempts = 0 }

    companion object {
        fun isTransportFailure(reason: String): Boolean =
            reason.trim().equals("ICE connection failed", ignoreCase = true) ||
                reason.trim().equals("peer connection failed", ignoreCase = true)
    }
}
