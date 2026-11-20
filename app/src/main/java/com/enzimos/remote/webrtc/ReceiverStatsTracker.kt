package com.enzimos.remote.webrtc

/** RTCStats counters are cumulative and reset when the inbound SSRC changes. */
class ReceiverStatsTracker {
    data class Sample(
        val streamId: String,
        val timestampMs: Long,
        val packetsReceived: Long,
        val packetsLost: Long,
        val framesDecoded: Long,
        val bytesReceived: Long,
        val totalDecodeSeconds: Double = 0.0,
        val jitterBufferSeconds: Double = 0.0,
        val jitterBufferEmitted: Long = 0,
        val framesDropped: Long? = null,
        val nacks: Long? = null,
        val plis: Long? = null,
        val retransmissions: Long? = null,
        val freezes: Long? = null,
        val freezeSeconds: Double? = null,
    )
    data class Interval(
        val packetLoss: Double, val fps: Double, val bitrateKbps: Double,
        val decodeMs: Double, val jitterBufferMs: Double,
        val framesDropped: Long?, val nacks: Long?, val plis: Long?,
        val retransmissions: Long?, val freezes: Long?, val freezeMs: Double?,
    ) {
        fun recoveryFields() = listOfNotNull(
            framesDropped?.let { "dropped_frames=$it" }, nacks?.let { "nacks=$it" },
            plis?.let { "plis=$it" }, retransmissions?.let { "retransmissions=$it" },
            freezes?.let { "freezes=$it" }, freezeMs?.let { "freeze_ms=$it" },
        ).joinToString(" ")
    }
    private var previous: Sample? = null

    @Synchronized fun reset() { previous = null }

    @Synchronized fun update(sample: Sample): Interval? {
        val old = previous
        previous = sample
        if (old == null || old.streamId != sample.streamId || sample.timestampMs <= old.timestampMs ||
            sample.packetsReceived < old.packetsReceived || sample.framesDecoded < old.framesDecoded ||
            sample.bytesReceived < old.bytesReceived) return null
        val received = sample.packetsReceived - old.packetsReceived
        val lost = (sample.packetsLost - old.packetsLost).coerceAtLeast(0)
        val frames = sample.framesDecoded - old.framesDecoded
        val emitted = sample.jitterBufferEmitted - old.jitterBufferEmitted
        val seconds = (sample.timestampMs - old.timestampMs) / 1000.0
        fun averageMs(delta: Double, count: Long) = if (count > 0 && delta.isFinite()) (delta * 1000 / count).coerceAtLeast(0.0) else 0.0
        fun delta(current: Long?, prior: Long?) = if (current != null && prior != null) (current - prior).coerceAtLeast(0) else null
        return Interval(
            packetLoss = if (received + lost > 0) lost.toDouble() / (received + lost) else 0.0,
            fps = frames / seconds,
            bitrateKbps = (sample.bytesReceived - old.bytesReceived) * 8 / (seconds * 1000),
            decodeMs = averageMs(sample.totalDecodeSeconds - old.totalDecodeSeconds, frames),
            jitterBufferMs = averageMs(sample.jitterBufferSeconds - old.jitterBufferSeconds, emitted),
            framesDropped = delta(sample.framesDropped, old.framesDropped),
            nacks = delta(sample.nacks, old.nacks), plis = delta(sample.plis, old.plis),
            retransmissions = delta(sample.retransmissions, old.retransmissions),
            freezes = delta(sample.freezes, old.freezes),
            freezeMs = sample.freezeSeconds?.let { current -> old.freezeSeconds?.let { prior ->
                ((current - prior) * 1000).takeIf { it.isFinite() }?.coerceAtLeast(0.0)
            } },
        )
    }
}
