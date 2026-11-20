package com.enzimos.remote.webrtc

/** Small, tested edits to locally created SDP. */
object SdpMunging {
    /** Add `stereo=1` to the Opus format parameters of an SDP. */
    fun stereoOpus(sdp: String): String {
        val payload = Regex("a=rtpmap:(\\d+) opus/48000", RegexOption.IGNORE_CASE).find(sdp)?.groupValues?.get(1) ?: return sdp
        val fmtp = Regex("(a=fmtp:$payload [^\\r\\n]*)")
        return if (fmtp.containsMatchIn(sdp)) {
            fmtp.replace(sdp) { match -> if ("stereo=1" in match.value) match.value else match.value + ";stereo=1" }
        } else {
            sdp.replace(Regex("(a=rtpmap:$payload opus/48000[^\\r\\n]*)", RegexOption.IGNORE_CASE), "$1\r\na=fmtp:$payload stereo=1")
        }
    }
}
