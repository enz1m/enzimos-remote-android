package com.enzimos.remote

import com.enzimos.remote.signaling.ReconnectionManager
import com.enzimos.remote.signaling.SignalingClient
import org.junit.Assert.*
import org.junit.Test

class ReconnectionManagerTest {

    private fun createManager(sessionId: String = "test-session"): ReconnectionManager {
        val client = SignalingClient(deviceId = "test-device")
        return ReconnectionManager(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            signalingClient = client,
            sessionId = sessionId,
        )
    }

    @Test
    fun `action handler receives GiveUp on resume failure`() {
        val actions = mutableListOf<ReconnectionManager.Action>()
        val manager = createManager()
        manager.setActionHandler { action -> actions.add(action) }

        manager.onDisconnected()
        manager.onReconnected()
        manager.onResumeFailed("gone")

        assertTrue(actions.any { it is ReconnectionManager.Action.GiveUp })
    }

    @Test
    fun `full reconnect cycle produces correct action sequence`() {
        val actions = mutableListOf<ReconnectionManager.Action>()
        val manager = createManager(sessionId = "full-cycle")
        manager.setActionHandler { action -> actions.add(action) }

        // 1. Disconnect
        manager.onDisconnected()
        assertEquals(ReconnectionManager.State.RECONNECTING, manager.state)

        // 2. Reconnect
        manager.onReconnected()
        assertEquals(ReconnectionManager.State.WAITING_FOR_RESUME, manager.state)

        // 3. Resume succeeds
        manager.onSessionResumed()
        assertEquals(ReconnectionManager.State.RESUMED, manager.state)

        // Verify action sequence
        assertEquals(2, actions.size)
        assertTrue(actions[0] is ReconnectionManager.Action.ResumeSession)
        assertTrue(actions[1] is ReconnectionManager.Action.RestartStream)
    }
}
