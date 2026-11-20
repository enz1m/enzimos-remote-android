package com.enzimos.remote

/** Serializes connection transitions and notifies listeners outside the lock. */
class ConnectionStateMachine {

    /** Connection states. */
    enum class State {
        /** Not connected to any host. */
        DISCONNECTED,
        /** WebSocket connection in progress. */
        CONNECTING,
        /** WebSocket open, Hello sent, awaiting HelloAck. */
        AUTHENTICATING,
        /** Authenticated, session list available. */
        CONNECTED,
        /** WebRTC stream active. */
        STREAMING,
        /** Connection lost, auto-reconnect in progress. */
        RECONNECTING,
        /** An error occurred. */
        ERROR,
    }

    /** Transition events that drive state changes. */
    enum class Event {
        CONNECT,
        WEBSOCKET_OPEN,
        AUTH_SUCCESS,
        AUTH_FAILURE,
        STREAM_STARTED,
        STREAM_STOPPED,
        DISCONNECTED,
        ERROR,
        RECONNECT,
        RECONNECT_FAILED,
    }

    private var _state: State = State.DISCONNECTED
    private var _error: String? = null
    private var _reconnectAttempts: Int = 0
    private val lock = Any()

    /** Callback invoked on state transitions. */
    var onStateChanged: ((old: State, new: State) -> Unit)? = null

    /** Current state. */
    val state: State get() = synchronized(lock) { _state }

    /** Current error message, if in ERROR state. */
    val error: String? get() = synchronized(lock) { _error }

    /** Number of reconnect attempts since last successful connection. */
    val reconnectAttempts: Int get() = synchronized(lock) { _reconnectAttempts }

    /**
     * Process an event and transition to the appropriate state.
     *
     * @param event The event that occurred
     * @param errorMsg Optional error message (for ERROR events)
     * @return The new state after the transition
     */
    fun transition(event: Event, errorMsg: String? = null): State {
        val oldState: State
        val newState: State
        val callback: ((State, State) -> Unit)?
        synchronized(lock) {
            oldState = _state
            newState = nextState(oldState, event)

            if (newState == oldState && event != Event.RECONNECT) {
                return oldState // No transition
            }

            _state = newState

            when (newState) {
                State.ERROR -> _error = errorMsg ?: "Unknown error"
                State.CONNECTED, State.STREAMING -> {
                    _error = null
                    _reconnectAttempts = 0
                }
                State.RECONNECTING -> _reconnectAttempts++
                State.DISCONNECTED -> {
                    _error = null
                    _reconnectAttempts = 0
                }
                else -> {}
            }

            // Capture callback ref inside lock, invoke outside to prevent deadlock.
            callback = if (oldState != newState) onStateChanged else null
        }
        // Invoke outside the lock so that re-entrant transition() calls from
        // the callback don't deadlock.
        callback?.invoke(oldState, newState)
        return newState
    }

    /**
     * Reset to DISCONNECTED state.
     */
    fun reset() {
        synchronized(lock) {
            val oldState = _state
            _state = State.DISCONNECTED
            _error = null
            _reconnectAttempts = 0
            if (oldState != State.DISCONNECTED) {
                onStateChanged?.invoke(oldState, State.DISCONNECTED)
            }
        }
    }

    /**
     * Whether the connection is in an active state (CONNECTED or STREAMING).
     */
    fun isActive(): Boolean = synchronized(lock) {
        _state == State.CONNECTED || _state == State.STREAMING
    }

    /**
     * Whether the connection can be retried.
     */
    fun canReconnect(): Boolean = synchronized(lock) {
        _state == State.ERROR || _state == State.DISCONNECTED
    }

    companion object {
        /**
         * Compute the next state given a current state and event.
         *
         * Invalid transitions return the current state (no-op).
         */
        fun nextState(current: State, event: Event): State {
            return when (current) {
                State.DISCONNECTED -> when (event) {
                    Event.CONNECT -> State.CONNECTING
                    else -> current
                }
                State.CONNECTING -> when (event) {
                    Event.WEBSOCKET_OPEN -> State.AUTHENTICATING
                    Event.ERROR -> State.ERROR
                    Event.DISCONNECTED -> State.DISCONNECTED
                    else -> current
                }
                State.AUTHENTICATING -> when (event) {
                    Event.AUTH_SUCCESS -> State.CONNECTED
                    Event.AUTH_FAILURE -> State.ERROR
                    Event.ERROR -> State.ERROR
                    Event.DISCONNECTED -> State.DISCONNECTED
                    else -> current
                }
                State.CONNECTED -> when (event) {
                    Event.STREAM_STARTED -> State.STREAMING
                    Event.DISCONNECTED -> State.DISCONNECTED
                    Event.ERROR -> State.ERROR
                    else -> current
                }
                State.STREAMING -> when (event) {
                    Event.STREAM_STOPPED -> State.CONNECTED
                    Event.DISCONNECTED -> State.RECONNECTING
                    Event.ERROR -> State.RECONNECTING
                    else -> current
                }
                State.RECONNECTING -> when (event) {
                    Event.CONNECT -> State.CONNECTING
                    Event.RECONNECT_FAILED -> State.ERROR
                    Event.DISCONNECTED -> State.DISCONNECTED
                    else -> current
                }
                State.ERROR -> when (event) {
                    Event.RECONNECT -> State.RECONNECTING
                    Event.CONNECT -> State.CONNECTING
                    Event.DISCONNECTED -> State.DISCONNECTED
                    else -> current
                }
            }
        }
    }
}
