package com.enzimos.remote.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpMungingTest {
    @Test fun addsParametersWhenMissingAndLeavesVideoAlone() {
        val sdp = "a=rtpmap:97 OPUS/48000/2\r\na=rtpmap:96 H265/90000\r\n"
        assertEquals("a=rtpmap:97 OPUS/48000/2\r\na=fmtp:97 stereo=1\r\na=rtpmap:96 H265/90000\r\n", SdpMunging.stereoOpus(sdp))
        assertEquals("v=0\r\n", SdpMunging.stereoOpus("v=0\r\n"))
    }
}
