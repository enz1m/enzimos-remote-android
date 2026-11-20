package com.enzimos.remote

import com.enzimos.remote.webrtc.WebRtcConfig
import org.junit.Assert.*
import org.junit.Test

class WebRtcConfigTest {

    @Test
    fun `ordered channel is negotiated`() {
        val opts = WebRtcConfig.dataChannelOrdered()
        assertEquals(true, opts["ordered"])
        assertEquals(true, opts["negotiated"])
        assertEquals(0, opts["id"])
    }

    @Test
    fun `unreliable channel has different ID`() {
        val opts = WebRtcConfig.dataChannelUnreliable()
        assertNotEquals(WebRtcConfig.DATA_CHANNEL_ID, opts["id"])
    }
}
