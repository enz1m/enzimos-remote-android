package com.enzimos.remote.webrtc

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** The native executor refuses pre-description ICE and aborts on a null MID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeerManagerIceTest {
    @Test fun earlyCandidatesReachOnlyThePreparedPeerWithNonNullMediaIds() {
        val factory = mockk<PeerConnectionFactory>()
        val peer = mockk<PeerConnection>(relaxed = true)
        val replacementPeer = mockk<PeerConnection>(relaxed = true)
        val callbacks = slot<SdpObserver>()
        val observers = mutableListOf<PeerConnection.Observer>()
        val localCandidates = mutableListOf<String>()
        val listener = mockk<PeerManager.Listener>(relaxed = true)
        every { listener.onLocalIceCandidate(any()) } answers { localCandidates += firstArg<IceCandidate>().sdp }
        val delivered = mutableListOf<Pair<Int, String>>()
        var remoteReady = false
        every { factory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), capture(observers)) } returnsMany listOf(peer, replacementPeer)
        every { peer.setRemoteDescription(capture(callbacks), any()) } answers { }
        every { peer.addIceCandidate(any<IceCandidate>()) } answers {
            check(remoteReady) { "ICE reached native code before its remote description" }
            val candidate = firstArg<IceCandidate>()
            assertNotNull("The bundled native JNI requires a non-null MID", candidate.sdpMid)
            assertEquals(listOf("video", "audio")[candidate.sdpMLineIndex], candidate.sdpMid)
            delivered += candidate.sdpMLineIndex to candidate.sdp
            true
        }
        val manager = PeerManager(listener)
        PeerManager::class.java.getDeclaredField("factory").apply { isAccessible = true }.set(manager, factory)
        manager.createPeerConnection()
        manager.addIceCandidate("first-candidate", 0)
        manager.setRemoteDescription("offer", "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:video\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:audio\r\n")
        manager.addIceCandidate("second-candidate", 1)
        assertEquals(emptyList<Pair<Int, String>>(), delivered)
        val retiredDescription = callbacks.captured
        manager.setRemoteDescription("offer", "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:video\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:audio\r\n")
        retiredDescription.onSetSuccess()
        assertEquals(emptyList<Pair<Int, String>>(), delivered)
        remoteReady = true
        callbacks.captured.onSetSuccess()
        assertEquals(listOf(0 to "first-candidate", 1 to "second-candidate"), delivered)
        manager.addIceCandidate("third-candidate", 0)
        assertEquals(0 to "third-candidate", delivered.last())
        manager.close()
        manager.addIceCandidate("retired-peer-candidate", 0)
        assertEquals(3, delivered.size)
        manager.createPeerConnection()
        observers.first().onIceCandidate(IceCandidate("video", 0, "retired-local-candidate"))
        observers.last().onIceCandidate(IceCandidate("video", 0, "current-local-candidate"))
        assertEquals(listOf("current-local-candidate"), localCandidates)
        manager.close()
    }
    @Test fun uplinkPublishesItsDescriptionBeforeIceAndRetiresItsPeer() {
        val factory = mockk<PeerConnectionFactory>()
        val peer = mockk<PeerConnection>(relaxed = true)
        val next = mockk<PeerConnection>(relaxed = true)
        val observers = mutableListOf<PeerConnection.Observer>()
        every { factory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), capture(observers)) } returnsMany listOf(peer, next)
        val offer = slot<SdpObserver>()
        val local = slot<SdpObserver>()
        val remote = slot<SdpObserver>()
        every { peer.createOffer(capture(offer), any()) } answers { }
        every { peer.setLocalDescription(capture(local), any()) } answers { }
        every { peer.setRemoteDescription(capture(remote), any()) } answers { }
        val output = mutableListOf<String>()
        fun listener(prefix: String) = object : UplinkPeer.Listener {
            override fun onLocalSdp(sdp: SessionDescription) { output += "$prefix-description" }
            override fun onLocalIce(candidate: IceCandidate) { output += "$prefix-${candidate.sdp}" }
            override fun onState(state: PeerConnection.PeerConnectionState) { output += "$prefix-state" }
            override fun onError(message: String) { throw AssertionError(message) }
        }
        var prepared = false
        val delivered = mutableListOf<String>()
        every { peer.addIceCandidate(any<IceCandidate>()) } answers {
            check(prepared) { "Uplink ICE reached native code before its answer" }
            val candidate = firstArg<IceCandidate>()
            assertEquals("uplink-video", candidate.sdpMid)
            delivered += candidate.sdp
            true
        }
        val owner = UplinkPeer(factory, listener("first"))
        owner.offer()
        val description = SessionDescription(SessionDescription.Type.OFFER,
            "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:uplink-video\r\n")
        offer.captured.onCreateSuccess(description)
        observers.first().onIceCandidate(IceCandidate("uplink-video", 0, "early-local"))
        assertEquals(emptyList<String>(), output)
        local.captured.onSetSuccess()
        assertEquals(listOf("first-description", "first-early-local"), output)
        owner.addIce("early-remote", 0)
        owner.answer(description.description)
        owner.addIce("second-remote", 0)
        assertEquals(emptyList<String>(), delivered)
        prepared = true
        remote.captured.onSetSuccess()
        assertEquals(listOf("early-remote", "second-remote"), delivered)
        owner.close()
        owner.addIce("retired-remote", 0)
        observers.first().onIceCandidate(IceCandidate("uplink-video", 0, "retired-local"))
        observers.first().onConnectionChange(PeerConnection.PeerConnectionState.FAILED)
        assertEquals(2, delivered.size)
        assertEquals(listOf("first-description", "first-early-local"), output)
        val nextOffer = slot<SdpObserver>()
        val nextLocal = slot<SdpObserver>()
        every { next.createOffer(capture(nextOffer), any()) } answers { }
        every { next.setLocalDescription(capture(nextLocal), any()) } answers { }
        val replacement = UplinkPeer(factory, listener("next"))
        replacement.offer()
        nextOffer.captured.onCreateSuccess(description)
        nextLocal.captured.onSetSuccess()
        observers.last().onIceCandidate(IceCandidate("uplink-video", 0, "current-local"))
        assertEquals(listOf("first-description", "first-early-local", "next-description", "next-current-local"), output)
        val nextRemote = slot<SdpObserver>()
        every { next.setRemoteDescription(capture(nextRemote), any()) } answers { }
        val nextDelivered = mutableListOf<String>()
        every { next.addIceCandidate(any<IceCandidate>()) } answers {
            nextDelivered += firstArg<IceCandidate>().sdp
            true
        }
        repeat(300) { replacement.addIce("bounded-$it", 0) }
        replacement.answer(description.description)
        nextRemote.captured.onSetSuccess()
        assertEquals(128, nextDelivered.size)
        assertEquals("bounded-0", nextDelivered.first())
        assertEquals("bounded-127", nextDelivered.last())
        replacement.close()
    }

}
