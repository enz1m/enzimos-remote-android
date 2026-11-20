package com.enzimos.remote.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.PeerConnection.*

/** Owns WebRTC peer resources. Call [close] to retire the peer and [dispose] to release the factory. */
class PeerManager(
    private val listener: Listener,
) {
    companion object {
        private const val TAG = "PeerManager"
        @Volatile
        private var factoryInitialized = false

        /** Initialize the WebRTC library once per process. */
        fun ensureInitialized(context: Context) {
            synchronized(PeerManager::class.java) {
                if (!factoryInitialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
                    )
                    factoryInitialized = true
                }
            }
        }
    }

    interface Listener {
        /** Called when a local SDP answer is created and should be sent to the host. */
        fun onLocalSdp(sdp: SessionDescription)

        /** Called when a local ICE candidate is generated and should be sent to the host. */
        fun onLocalIceCandidate(candidate: IceCandidate)

        /** Called when the first video frame is received and rendering begins. */
        fun onVideoTrackReady()

        /** Called when a data channel is opened for control messages. */
        fun onDataChannelReady(dataChannel: DataChannel)

        /** Called when the connection state changes. */
        fun onConnectionStateChanged(state: IceConnectionState)

        /** Called on error. */
        fun onError(message: String)
    }

    private var factory: PeerConnectionFactory? = null

    private var audioModule: org.webrtc.audio.AudioDeviceModule? = null
    @Volatile private var peerConnection: PeerConnection? = null
    private var remoteIce: RemoteIce? = null
    private var videoSink: VideoSink? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var dataChannel: DataChannel? = null
    private var eglBase: EglBase? = null
    @Volatile private var closed = false
    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong()

    /**
     * Initialize the PeerConnectionFactory. Call once per application lifecycle.
     */
    fun initialize(context: Context) {
        ensureInitialized(context)

        factory?.dispose()
        factory = null
        eglBase?.release()
        val egl = EglBase.create()
        eglBase = egl

        val encoderFactory = DefaultVideoEncoderFactory(
            egl.eglBaseContext, true, true
        )
        val decoderFactory = if (com.enzimos.remote.settings.Preferences(context).compatibilityDecoder) {
            DefaultVideoDecoderFactory(egl.eglBaseContext)
        } else {
            LowLatencyDecoderFactory(egl.eglBaseContext)
        }

        Log.i(TAG, "Receiver video codecs: ${decoderFactory.supportedCodecs.joinToString { it.name }}")

        // Use the media volume and routing for playback from the computer.
        audioModule?.release()
        val audio = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_GAME)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoOutput(true)
            // Game sound: the smallest AudioTrack buffer the device allows.
            .setUseLowLatency(true)
            .createAudioDeviceModule()
        audioModule = audio
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audio)
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory initialized")
    }

    /**
     * Create a new PeerConnection for receiving media.
     *
     * @param iceServers ICE servers for NAT traversal (empty for LAN-only)
     */
    fun createPeerConnection(iceServers: List<IceServer> = emptyList()) {
        val factory = this.factory ?: run {
            listener.onError("PeerConnectionFactory not initialized")
            return
        }

        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Optimize for low latency on LAN
            iceTransportsType = IceTransportsType.ALL
        }

        if (peerConnection != null) close()
        remoteIce?.close()
        remoteIce = null
        closed = false
        val generation = connectionGeneration.incrementAndGet()
        fun current() = !closed && connectionGeneration.get() == generation
        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: SignalingState) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: IceConnectionState) {
                if (!current()) return
                Log.i(TAG, "ICE connection state: $state")
                listener.onConnectionStateChanged(state)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE receiving: $receiving")
            }

            override fun onIceGatheringChange(state: IceGatheringState) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                if (!current()) return
                // SDP contains ICE credentials.
                Log.d(TAG, "Local ICE candidate generated (index=${candidate.sdpMLineIndex})")
                listener.onLocalIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                Log.d(TAG, "ICE candidates removed: ${candidates.size}")
            }

            override fun onAddStream(stream: MediaStream) {
                // Deprecated in unified plan, use onTrack instead
            }

            override fun onRemoveStream(stream: MediaStream) {
                // Deprecated in unified plan
            }

            override fun onDataChannel(dc: DataChannel) {
                if (!current()) {
                    try { dc.close() } catch (_: Exception) {}
                    try { dc.dispose() } catch (_: Exception) {}
                    return
                }
                Log.i(TAG, "Data channel received: ${dc.label()}")
                // Release the previous native channel before replacing it.
                dataChannel?.let { old ->
                    try { old.close() } catch (e: Exception) { Log.w(TAG, "DataChannel close failed", e) }
                    try { old.dispose() } catch (e: Exception) { Log.w(TAG, "DataChannel dispose failed", e) }
                }
                dataChannel = dc
                listener.onDataChannelReady(dc)
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                if (!current()) return
                Log.i(TAG, "Track added: ${receiver.track()?.kind()}")
                val track = receiver.track()
                if (track is VideoTrack) {
                    val prevSink = videoSink
                    remoteVideoTrack?.let { prev ->
                        if (prevSink != null) {
                            try { prev.removeSink(prevSink) } catch (e: Exception) { Log.w(TAG, "removeSink failed", e) }
                        }
                    }
                    remoteVideoTrack = track
                    if (prevSink != null) {
                        track.addSink(prevSink)
                        listener.onVideoTrackReady()
                    }
                }
            }
        })

        remoteIce = peerConnection?.let(::RemoteIce)
        Log.i(TAG, "PeerConnection created")
    }

    /**
     * Set the video sink (Surface) for rendering received video.
     * Must be called before the video track arrives.
     */
    fun setVideoSink(sink: VideoSink) {
        videoSink = sink
    }

    /**
     * Set the remote SDP (offer from host).
     * After setting, creates an answer automatically.
     */
    fun setRemoteDescription(sdpType: String, sdp: String) {
        val pc = peerConnection ?: run {
            listener.onError("No PeerConnection")
            return
        }

        val type = when (sdpType.lowercase()) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            "pranswer" -> SessionDescription.Type.PRANSWER
            else -> {
                listener.onError("Unknown SDP type: $sdpType")
                return
            }
        }

        val sessionDescription = SessionDescription(type, sdp)
        val ice = remoteIce ?: return
        val description = ice.prepare(sdp) ?: return

        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() {
                if (closed || peerConnection !== pc) return
                if (!ice.applied(description)) return
                Log.i(TAG, "Remote description set ($sdpType)")
                if (type == SessionDescription.Type.OFFER) {
                    createAnswer(pc)
                }
            }
            override fun onCreateFailure(msg: String) {}
            override fun onSetFailure(msg: String) {
                if (closed || peerConnection !== pc) return
                if (!ice.failed(description)) return
                // "Called in wrong state: stable" is expected when receiving
                // a duplicate SDP after negotiation completes. Log as debug.
                if (msg.contains("stable", ignoreCase = true)) {
                    Log.d(TAG, "Ignoring stale SDP: $msg")
                } else {
                    listener.onError("Failed to set remote description: $msg")
                }
            }
        }, sessionDescription)
    }

    /**
     * Add a remote ICE candidate.
     */
    fun addIceCandidate(candidate: String, sdpMLineIndex: Int) {
        if (!closed) remoteIce?.add(sdpMLineIndex, candidate)
    }

    /**
     * Send data over the control data channel.
     *
     * @return true if sent, false if channel not ready or send failed
     */
    fun sendControlMessage(json: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)),
            false  // text mode
        )
        return dc.send(buffer)
    }

    /**
     * Close the PeerConnection (but not the factory).
     * Call this on stream stop or reconnect.
     */
    fun close() {
        connectionGeneration.incrementAndGet()
        closed = true
        remoteIce?.close()
        remoteIce = null
        // Detach the sink before its renderer can be destroyed.
        val sink = videoSink
        remoteVideoTrack?.let { track ->
            if (sink != null) {
                try { track.removeSink(sink) } catch (e: Exception) { Log.w(TAG, "removeSink failed", e) }
            }
        }
        remoteVideoTrack = null

        dataChannel?.let { dc ->
            try { dc.close() } catch (e: Exception) { Log.w(TAG, "DataChannel close failed", e) }
            try { dc.dispose() } catch (e: Exception) { Log.w(TAG, "DataChannel dispose failed", e) }
        }
        dataChannel = null

        // Close and dispose peer connection (close stops ICE/DTLS, dispose
        // frees the native C++ PeerConnection and all associated objects).
        peerConnection?.let { pc ->
            try { pc.close() } catch (e: Exception) { Log.w(TAG, "PeerConnection close failed", e) }
            try { pc.dispose() } catch (e: Exception) { Log.w(TAG, "PeerConnection dispose failed", e) }
        }
        peerConnection = null

        Log.i(TAG, "PeerConnection closed and disposed")
    }

    /**
     * Dispose the factory and all resources.
     * Call this when the app is done with WebRTC entirely.
     */
    fun dispose() {
        close()
        videoSink = null
        factory?.dispose()
        factory = null
        audioModule?.release()
        audioModule = null
        eglBase?.release()
        eglBase = null
        Log.i(TAG, "PeerConnectionFactory disposed")
    }

    /** Get the EGL context for SurfaceView rendering. */
    fun getEglBaseContext(): EglBase.Context? = eglBase?.eglBaseContext

    /**
     * Collect WebRTC stats asynchronously.
     *
     * @param callback Called with the stats report, or null on failure.
     */
    fun getStats(callback: (RTCStatsReport?) -> Unit) {
        val pc = peerConnection
        if (pc == null) {
            callback(null)
            return
        }
        pc.getStats { report -> callback(report) }
    }

    private fun createAnswer(pc: PeerConnection) {
        if (closed || peerConnection !== pc) return

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(created: SessionDescription) {
                if (closed || peerConnection !== pc) return
                Log.i(TAG, "Answer created")
                // Request stereo Opus for desktop audio.
                val sdp = SessionDescription(created.type, SdpMunging.stereoOpus(created.description))
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onSetSuccess() {
                        if (closed || peerConnection !== pc) return
                        Log.i(TAG, "Local description set (answer)")
                        listener.onLocalSdp(sdp)
                    }
                    override fun onCreateFailure(msg: String) {}
                    override fun onSetFailure(msg: String) {
                        if (closed || peerConnection !== pc) return
                        listener.onError("Failed to set local description: $msg")
                    }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(msg: String) {
                if (closed || peerConnection !== pc) return
                listener.onError("Failed to create answer: $msg")
            }
            override fun onSetFailure(msg: String) {}
        }, constraints)
    }
}
