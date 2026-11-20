package com.enzimos.remote.signaling

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Paired links use pinned TLS. Plaintext is available only on unpinned loopback. */
class SignalingClient(
    private val deviceId: String,
    @Volatile var token: String = DEFAULT_ANONYMOUS_TOKEN,
    private val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = MAX_RECONNECT_ATTEMPTS),
    /** Pinned certificate fingerprint; null means unpinned (loopback/legacy). */
    @Volatile var pin: String? = null,
) {
    /** Keepalive period: short on screen (fast dead-link detection), long in
     *  the background (the host pings every 30 s; fewer radio wakeups). */
    @Volatile var pingIntervalMs: Long = PING_INTERVAL_MS

    /** The host said it streams H.265 (older hosts reject unknown codecs in hello). */
    @Volatile var offerH265: Boolean = false

    companion object {
        private const val TAG = "SignalingClient"
        const val PING_INTERVAL_MS = 5_000L
        const val BACKGROUND_PING_INTERVAL_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 15
        private const val CONNECT_TIMEOUT_MS = 4_000L
        /** Delay between starting successive candidate addresses. */
        private const val CANDIDATE_STAGGER_MS = 250L
        /** Token sent when no auth/pairing token is available. The server
         *  protocol layer requires a non-empty token (1-512 chars); when
         *  auth is disabled any non-empty value is accepted. */
        const val DEFAULT_ANONYMOUS_TOKEN = "anonymous"

        /** WebSocket URL for an address, choosing TLS by pin and loopback. */
        fun urlFor(address: String, pinned: Boolean): String = when {
            address.startsWith("ws://") || address.startsWith("wss://") -> address
            pinned || !HostTls.isLoopback(address) -> "wss://$address/v1"
            else -> "ws://$address/v1"
        }
    }

    /** Connection state. */
    enum class State {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATING,
        CONNECTED,
        RECONNECTING,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    // Emit from the OkHttp callback to preserve SDP and ICE order.
    private val _messages = MutableSharedFlow<HostMessage>(extraBufferCapacity = 512)
    val messages: SharedFlow<HostMessage> = _messages

    private val _rttMs = MutableStateFlow(-1L)
    /** Last measured round-trip time in ms, or -1 if unknown. */
    val rttMs: StateFlow<Long> = _rttMs

    private val _lastReject = MutableStateFlow<HelloRejectMessage?>(null)
    /** The most recent authentication rejection (cleared on success). */
    val lastReject: StateFlow<HelloRejectMessage?> = _lastReject

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var addresses: List<String> = emptyList()
    /** Address of the current/last successful connection. */
    @Volatile
    var connectedAddress: String? = null
        private set
    /** Certificate fingerprint observed on the current connection, if TLS. */
    @Volatile
    var observedFingerprint: String? = null
        private set
    @Volatile
    private var shouldReconnect = false
    /** Guard against double-reconnect from concurrent onClosed + onFailure. */
    private val reconnecting = AtomicBoolean(false)
    /** Current reconnect attempt count (reset on successful connect). */
    private val reconnectAttempts = AtomicInteger(0)
    /** Identifies the live socket so stale callbacks are ignored. */
    private val generation = AtomicInteger(0)
    @Volatile
    private var pingJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var retryJob: kotlinx.coroutines.Job? = null

    /**
     * Callback invoked when a device token is received from pairing.
     * The caller should persist this token via [DeviceStore].
     */
    @Volatile
    var onDeviceTokenReceived: ((String) -> Unit)? = null

    /** Invoked after authentication with the host identity and the address used. */
    @Volatile
    var onAuthenticated: ((hello: HelloAckMessage, address: String) -> Unit)? = null

    @Volatile
    private var tls = HostTls.clientWithTrust(pin, CONNECT_TIMEOUT_MS)
    private val httpClient: OkHttpClient get() = tls.first
    @Volatile
    private var clientPin: String? = pin

    private fun clientFor(currentPin: String?): OkHttpClient {
        if (currentPin != clientPin) {
            tls = HostTls.clientWithTrust(currentPin, CONNECT_TIMEOUT_MS)
            clientPin = currentPin
        }
        return httpClient
    }

    /**
     * Connect to the signaling server.
     *
     * @param address Server address in format "host:port" or "ws://host:port/v1"
     * @param autoReconnect Enable automatic reconnection on disconnect
     */
    fun connect(address: String, autoReconnect: Boolean = true) = connect(listOf(address), autoReconnect)

    /**
     * Connect using candidate [addresses] for the same host, tried in order.
     * Each failed attempt advances to the next address; backoff applies once
     * every address of a round has failed.
     */
    fun connect(addresses: List<String>, autoReconnect: Boolean = true) {
        val candidates = addresses.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return
        this.addresses = candidates
        if (_state.value != State.DISCONNECTED && _state.value != State.RECONNECTING) {
            Log.d(TAG, "Already connected or connecting; updated address candidates")
            return
        }
        retryJob?.cancel()
        shouldReconnect = autoReconnect
        reconnectAttempts.set(0)
        reconnecting.set(false)
        doConnect()
    }

    /** Replace address candidates without interrupting a live connection. */
    fun updateAddresses(addresses: List<String>) {
        val candidates = addresses.filter { it.isNotBlank() }.distinct()
        if (candidates.isNotEmpty()) this.addresses = candidates
    }

    /**
     * Retry immediately (network regained, app foregrounded, user Retry).
     * No-op while connected or mid-handshake.
     */
    fun reconnectNow() {
        if (addresses.isEmpty()) return
        when (_state.value) {
            State.CONNECTED, State.AUTHENTICATING, State.CONNECTING -> return
            else -> Unit
        }
        retryJob?.cancel()
        reconnecting.set(false)
        reconnectAttempts.set(0)
        shouldReconnect = true
        doConnect()
    }

    @Volatile
    private var lastPongAt = 0L

    /**
     * Confirm a CONNECTED link still works (after the app was backgrounded,
     * when Android may have blocked its network). Without a pong within
     * [timeoutMs] the socket is dropped and normal reconnection starts, so
     * the UI never shows a stale "Connected".
     */
    fun verifyAlive(timeoutMs: Long = 1_500) {
        if (_state.value != State.CONNECTED) return
        val ws = webSocket ?: return
        val sentAt = System.currentTimeMillis()
        sendPing()
        scope.launch {
            delay(timeoutMs)
            if (webSocket === ws && _state.value == State.CONNECTED && lastPongAt < sentAt) {
                Log.i(TAG, "Link did not answer after resume; reconnecting")
                ws.cancel()
            }
        }
    }

    /** Disconnect and stop reconnection attempts. */
    fun disconnect() {
        shouldReconnect = false
        reconnecting.set(false)
        reconnectAttempts.set(0)
        retryJob?.cancel()
        pingJob?.cancel()
        pingJob = null
        generation.incrementAndGet()
        val ws = webSocket
        webSocket = null
        _state.value = State.DISCONNECTED
        ws?.close(1000, "Client disconnect")
    }

    /** Send a serialized client message. */
    fun sendRaw(json: String): Boolean {
        val ws = webSocket ?: return false
        return try {
            ws.send(json)
        } catch (_: Exception) {
            // WebSocket may have been closed/cancelled between the null
            // check and the send call. Treat as send failure.
            false
        }
    }

    /** Send a Hello message to authenticate. */
    fun sendHello() {
        val msg = HelloMessage(
            deviceId = deviceId,
            token = token,
            capabilities = Capabilities(
                videoCodecs = com.enzimos.remote.webrtc.LowLatencyDecoderFactory.decodableCodecs()
                    .filter { it != "h265" || offerH265 },
                input = listOf("touch", "keyboard", "pointer"),
            ),
        )
        sendRaw(encodeClientMessage(msg))
    }

    /** Request a new session. Validates all parameters before sending. */
    fun requestSession(sessionId: String, width: Int, height: Int, command: List<String>, scale: Double = DEFAULT_SCALE, refreshHz: Int = DEFAULT_REFRESH_HZ) {
        require(validateSessionId(sessionId)) { "Invalid session_id: $sessionId" }
        require(validateDimension(width)) { "Invalid width: $width" }
        require(validateDimension(height)) { "Invalid height: $height" }
        require(command.isNotEmpty()) { "command must not be empty" }
        require(validateScale(scale)) { "Invalid scale: $scale" }
        require(validateRefreshHz(refreshHz)) { "Invalid refresh_hz: $refreshHz" }
        val msg = SessionRequestMessage(
            sessionId = sessionId,
            width = width,
            height = height,
            command = command,
            scale = scale,
            refreshHz = refreshHz,
        )
        sendRaw(encodeClientMessage(msg))
    }

    /** Resume an existing session. Validates session_id before sending. */
    fun resumeSession(sessionId: String) {
        require(validateSessionId(sessionId)) { "Invalid session_id: $sessionId" }
        val msg = SessionResumeMessage(sessionId = sessionId)
        sendRaw(encodeClientMessage(msg))
    }

    /** Resize a session. Validates all parameters before sending. */
    fun resizeSession(sessionId: String, width: Int, height: Int, scale: Double = DEFAULT_SCALE, refreshHz: Int = DEFAULT_REFRESH_HZ) {
        require(validateSessionId(sessionId)) { "Invalid session_id: $sessionId" }
        require(validateDimension(width)) { "Invalid width: $width" }
        require(validateDimension(height)) { "Invalid height: $height" }
        require(validateScale(scale)) { "Invalid scale: $scale" }
        require(validateRefreshHz(refreshHz)) { "Invalid refresh_hz: $refreshHz" }
        val msg = SessionResizeMessage(
            sessionId = sessionId,
            width = width,
            height = height,
            scale = scale,
            refreshHz = refreshHz,
        )
        sendRaw(encodeClientMessage(msg))
    }

    /** Stop a session. */
    fun stopSession(sessionId: String) {
        require(validateSessionId(sessionId)) { "Invalid session_id: $sessionId" }
        val msg = SessionStopMessage(sessionId = sessionId)
        sendRaw(encodeClientMessage(msg))
    }

    /** Inspect a session. */
    fun inspectSession(sessionId: String) {
        require(validateSessionId(sessionId)) { "Invalid session_id: $sessionId" }
        val msg = SessionInspectMessage(sessionId = sessionId)
        sendRaw(encodeClientMessage(msg))
    }

    /** List all sessions. */
    fun listSessions() {
        val msg = SessionListMessage()
        sendRaw(encodeClientMessage(msg))
    }

    /** Send SDP (answer). */
    fun sendSdp(sessionId: String, sdpType: String, sdp: String) {
        val msg = SdpMessage(
            sessionId = sessionId,
            sdpType = sdpType,
            sdp = sdp,
        )
        sendRaw(encodeClientMessage(msg))
    }

    /** Send ICE candidate. */
    fun sendIce(sessionId: String, candidate: String, sdpMLineIndex: Int = 0) {
        val msg = IceMessage(
            sessionId = sessionId,
            candidate = candidate,
            sdpMLineIndex = sdpMLineIndex,
        )
        sendRaw(encodeClientMessage(msg))
    }

    /** Start streaming for a session. */
    fun startStream(sessionId: String) {
        val msg = StreamStartMessage(sessionId = sessionId)
        sendRaw(encodeClientMessage(msg))
    }

    /** Stop streaming for a session. */
    fun stopStream(sessionId: String) {
        val msg = StreamStopMessage(sessionId = sessionId)
        sendRaw(encodeClientMessage(msg))
    }

    fun pauseStream(sessionId: String) {
        sendRaw(encodeClientMessage(StreamPauseMessage(sessionId = sessionId)))
    }

    /** Send ping for RTT measurement. */
    fun sendPing() {
        val msg = PingMessage(timestampMs = System.currentTimeMillis())
        sendRaw(encodeClientMessage(msg))
    }

    /** Probe server status. */
    fun probe() {
        val msg = ProbeMessage()
        sendRaw(encodeClientMessage(msg))
    }

    /** Release all resources. Call when done with this client. */
    fun destroy() {
        disconnect()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    /** Current reconnect attempt count. Useful for UI indicators. */
    fun currentReconnectAttempt(): Int = reconnectAttempts.get()

    /**
     * Race every candidate address, staggered by [CANDIDATE_STAGGER_MS]
     * (happy-eyeballs style): the first socket to open wins and the others
     * are cancelled, so a dead address never delays a live one.
     */
    private fun doConnect() {
        val candidates = addresses
        if (candidates.isEmpty()) return
        val currentPin = pin
        _state.value = State.CONNECTING
        val gen = generation.incrementAndGet()
        val won = AtomicBoolean(false)
        val remaining = AtomicInteger(candidates.size)
        val client = clientFor(currentPin)
        val trust = tls.second
        candidates.forEachIndexed { index, address ->
            scope.launch {
                if (index > 0) delay(index * CANDIDATE_STAGGER_MS)
                if (gen != generation.get() || won.get()) {
                    remaining.decrementAndGet()
                    return@launch
                }
                openCandidate(client, trust, address, currentPin != null, gen, won, remaining, index, candidates.size)
            }
        }
    }

    private fun openCandidate(
        client: OkHttpClient,
        trust: HostTls.PinningTrustManager,
        address: String,
        pinned: Boolean,
        gen: Int,
        won: AtomicBoolean,
        remaining: AtomicInteger,
        index: Int,
        total: Int,
    ) {
        val wsUrl = urlFor(address, pinned)
        // Query parameters can contain a credential.
        Log.i(TAG, "Connecting to ${if (wsUrl.startsWith("wss")) "wss" else "ws"}://*:*/v1 (candidate ${index + 1}/$total)")
        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Skipping malformed candidate address")
            if (remaining.decrementAndGet() <= 0 && !won.get() && gen == generation.get()) handleDisconnect()
            return
        }
        client.newWebSocket(request, object : WebSocketListener() {
            private var mine = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation.get() || !won.compareAndSet(false, true)) {
                    webSocket.cancel()
                    return
                }
                mine = true
                this@SignalingClient.webSocket = webSocket
                Log.i(TAG, "WebSocket opened (candidate ${index + 1}/$total)")
                observedFingerprint = if (wsUrl.startsWith("wss://")) trust.lastSeen else null
                connectedAddress = address
                _state.value = State.AUTHENTICATING
                sendHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (mine && gen == generation.get()) handleMessage(parseHostMessage(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                if (mine && gen == generation.get()) handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation.get()) return
                if (mine) {
                    if (shouldReconnect) Log.w(TAG, "WebSocket failure: ${t.javaClass.simpleName}: ${t.message}")
                    handleDisconnect()
                } else if (!won.get()) {
                    Log.i(TAG, "Candidate ${index + 1}/$total unreachable: ${t.javaClass.simpleName}")
                    // Last candidate of the round failed: back off.
                    if (remaining.decrementAndGet() <= 0 && !won.get()) handleDisconnect()
                }
            }
        })
    }

    private fun handleMessage(msg: HostMessage) {
        when (msg) {
            is HostMessage.HelloAck -> {
                Log.i(TAG, "Authenticated (server ${msg.msg.serverVersion})")
                val dt = msg.msg.deviceToken
                if (dt != null) {
                    Log.i(TAG, "Received device token from pairing")
                    onDeviceTokenReceived?.invoke(dt)
                }
                reconnectAttempts.set(0)
                reconnecting.set(false)
                _lastReject.value = null
                connectedAddress?.let { onAuthenticated?.invoke(msg.msg, it) }
                _state.value = State.CONNECTED
                startPingLoop()
            }
            is HostMessage.HelloReject -> {
                Log.w(TAG, "Authentication rejected: ${msg.msg.reason}")
                _lastReject.value = msg.msg
                shouldReconnect = false
                reconnecting.set(false)
                reconnectAttempts.set(0)
                // Invalidate before close to prevent a handleDisconnect race.
                generation.incrementAndGet()
                val ws = webSocket
                webSocket = null
                _state.value = State.DISCONNECTED
                ws?.close(1000, "Auth rejected")
                // Still emit so UI can show the rejection reason
            }
            is HostMessage.Unpaired -> {
                Log.w(TAG, "This device was unpaired by the computer")
                _lastReject.value = HelloRejectMessage(reason = "unpaired", code = "not_paired")
                shouldReconnect = false
            }
            is HostMessage.Pong -> {
                val rtt = (System.currentTimeMillis() - msg.msg.timestampMs)
                    .coerceAtLeast(0)
                _rttMs.value = rtt
                lastPongAt = System.currentTimeMillis()
            }
            else -> { /* handled by subscriber */ }
        }
        if (!_messages.tryEmit(msg)) Log.w(TAG, "Dropped ${msg.javaClass.simpleName}: collectors too slow")
    }

    private fun handleDisconnect() {
        webSocket = null
        pingJob?.cancel()

        // A socket can report both closure and failure.
        if (!shouldReconnect) {
            _state.value = State.DISCONNECTED
            return
        }

        if (!reconnecting.compareAndSet(false, true)) {
            Log.d(TAG, "Reconnect already in progress, skipping duplicate")
            return
        }

        val attempt = reconnectAttempts.getAndIncrement()  // 0-indexed for RetryPolicy
        val delayMs = retryPolicy.delayMs(attempt)
        if (delayMs < 0) {
            Log.w(TAG, "Max reconnect attempts (${retryPolicy.maxAttempts}) reached, giving up")
            shouldReconnect = false
            reconnecting.set(false)
            _state.value = State.DISCONNECTED
            return
        }

        _state.value = State.RECONNECTING
        retryJob = scope.launch {
            Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt ${attempt + 1})")
            delay(delayMs)
            reconnecting.set(false)
            if (shouldReconnect) doConnect()
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (_state.value == State.CONNECTED) {
                delay(pingIntervalMs)
                if (_state.value == State.CONNECTED) {
                    sendPing()
                }
            }
        }
    }
}
