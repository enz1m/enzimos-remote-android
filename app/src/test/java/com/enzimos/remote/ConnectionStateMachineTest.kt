package com.enzimos.remote

import org.junit.Assert.*
import org.junit.Test

class ConnectionStateMachineTest {

    private fun sm() = ConnectionStateMachine()

    @Test
    fun `full happy path lifecycle`() {
        val sm = sm()
        assertEquals(ConnectionStateMachine.State.CONNECTING, sm.transition(ConnectionStateMachine.Event.CONNECT))
        assertEquals(ConnectionStateMachine.State.AUTHENTICATING, sm.transition(ConnectionStateMachine.Event.WEBSOCKET_OPEN))
        assertEquals(ConnectionStateMachine.State.CONNECTED, sm.transition(ConnectionStateMachine.Event.AUTH_SUCCESS))
        assertEquals(ConnectionStateMachine.State.STREAMING, sm.transition(ConnectionStateMachine.Event.STREAM_STARTED))
        assertEquals(ConnectionStateMachine.State.CONNECTED, sm.transition(ConnectionStateMachine.Event.STREAM_STOPPED))
        assertEquals(ConnectionStateMachine.State.DISCONNECTED, sm.transition(ConnectionStateMachine.Event.DISCONNECTED))
    }

    @Test
    fun `auth failure goes to error`() {
        val sm = sm()
        sm.transition(ConnectionStateMachine.Event.CONNECT)
        sm.transition(ConnectionStateMachine.Event.WEBSOCKET_OPEN)
        val state = sm.transition(ConnectionStateMachine.Event.AUTH_FAILURE, "bad token")
        assertEquals(ConnectionStateMachine.State.ERROR, state)
        assertEquals("bad token", sm.error)
    }

    @Test
    fun `streaming disconnect triggers reconnect`() {
        val sm = sm()
        sm.transition(ConnectionStateMachine.Event.CONNECT)
        sm.transition(ConnectionStateMachine.Event.WEBSOCKET_OPEN)
        sm.transition(ConnectionStateMachine.Event.AUTH_SUCCESS)
        sm.transition(ConnectionStateMachine.Event.STREAM_STARTED)
        val state = sm.transition(ConnectionStateMachine.Event.DISCONNECTED)
        assertEquals(ConnectionStateMachine.State.RECONNECTING, state)
    }

    @Test
    fun `callback can re-enter transition without deadlock`() {
        // Verify that the onStateChanged callback is invoked outside the lock
        // so that calling transition() from within the callback does not deadlock.
        val sm = ConnectionStateMachine()
        var callbackCount = 0
        sm.onStateChanged = { _, newState ->
            callbackCount++
            if (newState == ConnectionStateMachine.State.CONNECTING) {
                // Re-entrant: transition to AUTHENTICATING from within the callback.
                sm.transition(ConnectionStateMachine.Event.WEBSOCKET_OPEN)
            }
        }
        sm.transition(ConnectionStateMachine.Event.CONNECT)
        // The first transition (DISCONNECTED→CONNECTING) fires the callback
        // which triggers a second transition (CONNECTING→AUTHENTICATING).
        assertEquals(2, callbackCount)
        assertEquals(ConnectionStateMachine.State.AUTHENTICATING, sm.state)
    }
}
