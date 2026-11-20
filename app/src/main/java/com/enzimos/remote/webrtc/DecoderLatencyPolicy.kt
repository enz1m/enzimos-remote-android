package com.enzimos.remote.webrtc

object DecoderLatencyPolicy {
    const val QTI_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
    const val QTI_PICTURE_ORDER = "vendor.qti-ext-dec-picture-order.enable"

    fun options(codecName: String, sdk: Int, standardSupported: Boolean, vendorSupported: Boolean): Map<String, Int> {
        val options = linkedMapOf("priority" to 0)
        val qualcomm = codecName.startsWith("c2.qti.", ignoreCase = true) ||
            codecName.startsWith("OMX.qcom.", ignoreCase = true)
        if (sdk >= 30 && (standardSupported || qualcomm)) options["low-latency"] = 1
        // Qualcomm's Android 8+ extension predates feature/vendor-parameter discovery.
        // A keyless decoder is retained as fallback if configure/init rejects these hints.
        if (sdk >= 26 && !standardSupported) {
            if (qualcomm || vendorSupported) options[QTI_LOW_LATENCY] = 1
            if (qualcomm) options[QTI_PICTURE_ORDER] = 1
        }
        return options
    }
}
