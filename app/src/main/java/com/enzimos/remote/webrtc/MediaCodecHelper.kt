package com.enzimos.remote.webrtc

/**
 * Determines optimal MediaCodec settings for hardware video decoding.
 *
 * Different devices have different decoder capabilities. This helper
 * provides safe defaults and optimal settings based on known constraints.
 */
object MediaCodecHelper {

    /** Supported video codec MIME types. */
    const val MIME_H264 = "video/avc"
    const val MIME_VP8 = "video/x-vnd.on2.vp8"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"
    const val MIME_AV1 = "video/av01"

    /** H.264 profile and level constants. */
    const val H264_PROFILE_BASELINE = 1
    const val H264_PROFILE_MAIN = 2
    const val H264_PROFILE_HIGH = 8
    const val H264_LEVEL_31 = 512 // 1280x720@30fps
    const val H264_LEVEL_40 = 2048 // 2048x1024@30fps
    const val H264_LEVEL_42 = 8192 // 2048x1088@60fps
    const val H264_LEVEL_50 = 16384 // 3840x2160@30fps

    /**
     * Determine the recommended H.264 level for given resolution and frame rate.
     *
     * @param width Session width
     * @param height Session height
     * @param fps Target frame rate
     * @return Appropriate H.264 level constant
     */
    fun recommendedH264Level(width: Int, height: Int, fps: Int): Int {
        val pixels = width.toLong() * height
        val macroblocks = (pixels + 255) / 256 // 16x16 macroblocks
        val mbPerSec = macroblocks * fps

        return when {
            mbPerSec <= 108_000 -> H264_LEVEL_31 // 720p@30
            mbPerSec <= 245_760 -> H264_LEVEL_40 // 1080p@30
            mbPerSec <= 522_240 -> H264_LEVEL_42 // 1080p@60
            else -> H264_LEVEL_50 // 4K@30
        }
    }

    /** Estimates a target video bitrate in kbps from resolution and frame rate. */
    fun recommendedBitrateKbps(width: Int, height: Int, fps: Int = 60): Int {
        val pixels = width.toLong() * height
        val baseBitrate = when {
            pixels <= 921_600 -> 3000  // ≤720p: 3 Mbps
            pixels <= 2_073_600 -> 6000 // ≤1080p: 6 Mbps
            pixels <= 3_686_400 -> 10000 // ≤1440p: 10 Mbps
            else -> 20000 // 4K+: 20 Mbps
        }
        // Scale for frame rate: base assumes 30fps
        val fpsMultiplier = if (fps > 30) 1.5 else 1.0
        return (baseBitrate * fpsMultiplier).toInt()
    }

    /**
     * Get the MIME type for a codec name from the protocol.
     *
     * @param codecName Protocol codec name ("h264", "vp8", "av1")
     * @return MIME type string, or null if unsupported
     */
    fun mimeTypeForCodec(codecName: String): String? {
        return when (codecName.lowercase()) {
            "h264", "avc" -> MIME_H264
            "vp8" -> MIME_VP8
            "vp9" -> MIME_VP9
            "av1" -> MIME_AV1
            else -> null
        }
    }

    /**
     * Check if a MIME type is a supported video codec.
     */
    fun isSupportedCodec(mimeType: String): Boolean {
        return mimeType in listOf(MIME_H264, MIME_VP8, MIME_VP9, MIME_AV1)
    }
}
