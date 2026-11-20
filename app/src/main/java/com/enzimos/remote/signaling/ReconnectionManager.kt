package com.enzimos.remote.signaling

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/** Emits resume and recovery actions. The signaling client owns the connection. */
class ReconnectionManager(
    private val scope: CoroutineScope,
    private val signalingClient: SignalingClient,
    private val sessionId: String,
) {
    companion object {
        private const val TAG = "ReconnectionManager"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_RETRIES = 10
    }

    /** Reconnection state. */
    enum class State {
        IDLE,
        RECONNECTING,
        WAITING_FOR_RESUME,
        RESUMED,
        FAILED,
    }

    /** Actions the caller should execute. */
    sealed class Action {
        data class ResumeSession(val sessionId: String) : Action()
        data class RestartStream(val sessionId: String) : Action()
        data class ReduceBitrate(val targetKbps: Int) : Action()
        data object GiveUp : Action()
    }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile private var retryCount = 0
    @Volatile private var backoffMs = INITIAL_BACKOFF_MS
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var onAction: ((Action) -> Unit)? = null

    /**
     * Set the callback for reconnection actions.
     */
    fun setActionHandler(handler: (Action) -> Unit) {
        onAction = handler
    }

    /**
     * Notify that the signaling connection was lost.
     * Starts the reconnection sequence.
     */
    fun onDisconnected() {
        if (state == State.RECONNECTING) return

        Log.i(TAG, "Connection lost, starting reconnection")
        state = State.RECONNECTING
        retryCount = 0
        backoffMs = INITIAL_BACKOFF_MS
        scheduleReconnect()
    }

    /**
     * Notify that the signaling connection was restored and authenticated.
     * Sends session_resume to reclaim the session.
     */
    fun onReconnected() {
        Log.i(TAG, "Connection restored, resuming session $sessionId")
        state = State.WAITING_FOR_RESUME
        retryCount = 0
        backoffMs = INITIAL_BACKOFF_MS

        // Resume the session (reclaim ownership)
        signalingClient.resumeSession(sessionId)
        onAction?.invoke(Action.ResumeSession(sessionId))
    }

    /**
     * Notify that the session was successfully resumed.
     * Triggers stream restart.
     */
    fun onSessionResumed() {
        Log.i(TAG, "Session $sessionId resumed, restarting stream")
        state = State.RESUMED

        // Re-negotiate WebRTC
        signalingClient.startStream(sessionId)
        onAction?.invoke(Action.RestartStream(sessionId))
    }

    /**
     * Notify that the session resume failed (session gone, etc.).
     */
    fun onResumeFailed(reason: String) {
        Log.e(TAG, "Session resume failed: $reason")
        state = State.FAILED
        onAction?.invoke(Action.GiveUp)
    }

    /**
     * Notify of a network type change (e.g., WiFi → cellular).
     * Reduces bitrate preemptively.
     */
    fun onNetworkDowngrade() {
        Log.i(TAG, "Network downgrade detected, reducing bitrate")
        onAction?.invoke(Action.ReduceBitrate(1000)) // Conservative bitrate for cellular
    }

    /**
     * Cancel any pending reconnection and reset state.
     */
    fun cancel() {
        reconnectJob?.cancel()
        reconnectJob = null
        state = State.IDLE
        retryCount = 0
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private fun scheduleReconnect() {
        if (retryCount >= MAX_RETRIES) {
            Log.e(TAG, "Max retries ($MAX_RETRIES) exceeded, giving up")
            state = State.FAILED
            onAction?.invoke(Action.GiveUp)
            return
        }

        reconnectJob = scope.launch {
            delay(backoffMs)
            retryCount++
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)

            Log.i(TAG, "Reconnect attempt $retryCount/$MAX_RETRIES")
            // The SignalingClient handles the actual reconnect internally.
            // We just track state and retry if it fails again.
        }
    }
}
