package com.enzimos.remote

internal object SessionErrorRecovery {
    enum class Action { IGNORE, RECREATE_PRIVATE, RESUME, END_DESKTOP, REPORT }

    fun action(
        sessionId: String,
        responseSessionId: String,
        reason: String,
        established: Boolean,
        borrowedDesktop: Boolean,
    ): Action {
        if (sessionId.isBlank() || responseSessionId != sessionId) return Action.IGNORE
        val missing = reason.contains("not found", ignoreCase = true) ||
            reason.contains("no such file", ignoreCase = true)
        return when {
            established && missing && borrowedDesktop -> Action.END_DESKTOP
            established && missing -> Action.RECREATE_PRIVATE
            reason.contains("already exists", ignoreCase = true) -> Action.RESUME
            else -> Action.REPORT
        }
    }
}
