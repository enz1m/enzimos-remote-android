package com.enzimos.remote

import org.junit.Assert.*
import org.junit.Test

class SessionErrorRecoveryTest {
    private fun action(reason: String, established: Boolean = true, desktop: Boolean = true,
                       response: String = "active") = SessionErrorRecovery.action(
        "active", response, reason, established, desktop)

    @Test fun aLostSharedSourceRequiresANewHomeSelection() {
        listOf("session not found", "No such file or directory (os error 2)")
            .forEach { assertEquals(SessionErrorRecovery.Action.END_DESKTOP, action(it)) }
    }

    @Test fun thePrivateSessionFallbackIsRetained() {
        assertEquals(SessionErrorRecovery.Action.RECREATE_PRIVATE, action("SESSION NOT FOUND", desktop = false))
    }

    @Test fun unrelatedAndUncorrelatedErrorsDoNothing() {
        listOf("old", "", "Active").forEach { response ->
            listOf("session not found", "already exists", "ICE connection failed").forEach { reason ->
                assertEquals(SessionErrorRecovery.Action.IGNORE, action(reason, response = response))
            }
        }
        assertEquals(SessionErrorRecovery.Action.IGNORE,
            SessionErrorRecovery.action("", "", "not found", true, true))
    }

    @Test fun conflictsResumeRatherThanCreate() {
        for (desktop in listOf(false, true)) {
            assertEquals(SessionErrorRecovery.Action.RESUME, action("Session already exists", desktop = desktop))
        }
    }
}
