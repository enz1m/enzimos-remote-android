package com.enzimos.remote.webrtc

import org.webrtc.*

internal class UplinkPeer(factory: PeerConnectionFactory, private val listener: Listener) {
    interface Listener {
        fun onLocalSdp(sdp: SessionDescription)
        fun onLocalIce(candidate: IceCandidate)
        fun onState(state: PeerConnection.PeerConnectionState)
        fun onError(message: String)
    }

    @Volatile private var closed = false
    private val outbound = Any()
    private var descriptionSent = false
    private val localCandidates = java.util.ArrayDeque<IceCandidate>()
    private val peer: PeerConnection = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                synchronized(outbound) {
                    if (closed) return
                    if (descriptionSent) listener.onLocalIce(candidate)
                    else if (localCandidates.size < 128) localCandidates.addLast(candidate)
                }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                if (!closed) listener.onState(state)
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
        }
    ) ?: error("could not create the connection")
    private val remote = RemoteIce(peer)

    fun start(track: VideoTrack, screen: Boolean) {
        val transceiver = peer.addTransceiver(track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        transceiver.sender.parameters = transceiver.sender.parameters.apply {
            encodings.forEach {
                it.maxBitrateBps = if (screen) 6_000_000 else 4_000_000
                it.minBitrateBps = if (screen) 1_500_000 else 1_200_000
            }
            degradationPreference = if (screen) RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                else RtpParameters.DegradationPreference.BALANCED
        }
        offer()
    }

    internal fun offer() {
        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription) {
                if (closed) return
                peer.setLocalDescription(object : EmptySdp() {
                    override fun onSetSuccess() {
                        synchronized(outbound) {
                            if (closed) return
                            listener.onLocalSdp(offer)
                            descriptionSent = true
                            while (localCandidates.isNotEmpty()) listener.onLocalIce(localCandidates.removeFirst())
                        }
                    }
                    override fun onSetFailure(message: String) { if (!closed) listener.onError(message) }
                }, offer)
            }
            override fun onCreateFailure(message: String) { if (!closed) listener.onError(message) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(message: String) = Unit
        }, MediaConstraints())
    }

    fun answer(sdp: String) {
        val revision = remote.prepare(sdp) ?: return
        peer.setRemoteDescription(object : EmptySdp() {
            override fun onSetSuccess() { remote.applied(revision) }
            override fun onSetFailure(message: String) {
                if (remote.failed(revision) && !closed) listener.onError(message)
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun addIce(candidate: String, index: Int) = remote.add(index, candidate)

    fun close() {
        synchronized(outbound) {
            if (closed) return
            closed = true
            localCandidates.clear()
        }
        remote.close()
        try { peer.close() } finally { peer.dispose() }
    }

    private open class EmptySdp : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(message: String) = Unit
        override fun onSetFailure(message: String) = Unit
    }
}
