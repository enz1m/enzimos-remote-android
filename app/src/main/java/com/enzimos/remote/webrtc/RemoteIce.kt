package com.enzimos.remote.webrtc

import android.util.Log
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

internal class RemoteIce(private val peer: PeerConnection) {
    private var closed = false
    private var revision = 0L
    private var ready = false
    private var mediaIds: List<String?> = emptyList()
    private var previous: Pair<Boolean, List<String?>> = false to emptyList()
    private val pending = java.util.ArrayDeque<Pair<Int, String>>()

    @Synchronized fun prepare(sdp: String): Long? {
        if (closed) return null
        previous = ready to mediaIds
        ready = false
        mediaIds = SdpHelper.mediaIds(sdp)
        return ++revision
    }

    @Synchronized fun applied(description: Long): Boolean {
        if (closed || revision != description) return false
        ready = true
        while (pending.isNotEmpty()) {
            val (index, candidate) = pending.removeFirst()
            deliver(index, candidate)
        }
        return true
    }

    @Synchronized fun failed(description: Long): Boolean {
        if (closed || revision != description) return false
        pending.clear()
        ready = previous.first
        mediaIds = previous.second
        return true
    }

    @Synchronized fun add(index: Int, candidate: String) {
        if (closed || candidate.isBlank()) return
        if (ready) {
            deliver(index, candidate)
        } else if (pending.size < 128) {
            pending.addLast(index to candidate)
        } else {
            Log.w("RemoteIce", "Discarding excess candidates before the remote description")
        }
    }

    private fun deliver(index: Int, candidate: String) {
        val mid = mediaIds.getOrNull(index)
        if (mid == null) {
            Log.w("RemoteIce", "Discarding ICE for an unknown media section")
            return
        }
        // Native JNI requires a non-null MID and resolves it before the index.
        if (!peer.addIceCandidate(IceCandidate(mid, index, candidate))) {
            Log.w("RemoteIce", "Remote ICE refused; retaining other network paths")
        }
    }

    @Synchronized fun close() {
        closed = true
        revision++
        pending.clear()
        mediaIds = emptyList()
        previous = false to emptyList()
    }
}
