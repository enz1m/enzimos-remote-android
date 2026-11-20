package com.enzimos.remote

import com.enzimos.remote.webrtc.DecoderLatencyPolicy
import org.junit.Assert.*
import org.junit.Test

class DecoderLatencyPolicyTest {
    @Test fun standardCapabilityNeedsNoVendorWorkaround() {
        val keys = DecoderLatencyPolicy.options("c2.qti.avc.decoder", 35, true, false)
        assertEquals(mapOf("priority" to 0, "low-latency" to 1), keys)
    }
    @Test fun unrelatedDevicesDoNotGetQualcommKeys() {
        val keys = DecoderLatencyPolicy.options("c2.vendor.avc.decoder", 35, false, false)
        assertEquals(mapOf("priority" to 0), keys)
    }
    @Test fun oldAndroidUsesOnlySupportedExtensionInterface() {
        val keys = DecoderLatencyPolicy.options("OMX.qcom.video.decoder.avc", 26, false, false)
        assertFalse(keys.containsKey("low-latency"))
        assertEquals(1, keys[DecoderLatencyPolicy.QTI_LOW_LATENCY])
        assertEquals(mapOf("priority" to 0), DecoderLatencyPolicy.options("OMX.qcom.video.decoder.avc", 25, false, false))
    }
}
