package com.enzimos.remote.webrtc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory

/** Configures MediaCodec low-latency options according to platform and decoder support. */
class LowLatencyDecoderFactory(
    private val eglContext: EglBase.Context?,
) : VideoDecoderFactory {

    companion object {
        private const val TAG = "LowLatencyDecoder"

        /** MIME types for supported codecs. */
        private const val MIME_H264 = "video/avc"
        private const val MIME_VP8 = "video/x-vnd.on2.vp8"
        private const val MIME_VP9 = "video/x-vnd.on2.vp9"
        private const val MIME_AV1 = "video/av01"

        /**
         * The host can offer only codecs the active WebRTC factory negotiates.
         * A platform MediaCodec alone does not establish WebRTC support.
         */
        fun decodableCodecs(): List<String> = decodable

        private val decodable: List<String> by lazy {
            val supported = HardwareVideoDecoderFactory(null).supportedCodecs.map { it.name.lowercase() }
            if ("h265" in supported && hasHardwareDecoder("video/hevc")) listOf("h264", "h265") else listOf("h264")
        }

        /**
         * Check if a hardware decoder is available for the given MIME type.
         */
        fun hasHardwareDecoder(mimeType: String): Boolean {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            return codecList.codecInfos.any { info ->
                !info.isEncoder &&
                    isHardwareCodec(info) &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        }

        /**
         * Check if a codec is hardware-accelerated.
         */
        fun isHardwareCodec(info: MediaCodecInfo): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                // Heuristic for pre-Q: hardware codecs usually don't have
                // "OMX.google." or "c2.android." prefixes
                val name = info.name.lowercase()
                !name.startsWith("omx.google.") &&
                    !name.startsWith("c2.android.") &&
                    !name.contains("sw")
            }
        }

        /**
         * Apply low-latency settings to a MediaFormat.
         *
         * @param format The MediaFormat to modify (in-place)
         */
        fun applyLowLatencySettings(format: MediaFormat) {
            // API 30+ (Android 11): official low-latency key
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                Log.d(TAG, "Set KEY_LOW_LATENCY=1")
            }

            // API 31+ (Android 12): real-time priority
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = real-time
                Log.d(TAG, "Set KEY_PRIORITY=0 (real-time)")
            }

            // Vendor fallback for older SoCs (Qualcomm, Samsung)
            try {
                format.setInteger("low-latency", 1)
                Log.d(TAG, "Set vendor 'low-latency'=1")
            } catch (e: Exception) {
                // Some codecs reject unknown keys; safe to ignore
            }

            // Disable frame reordering (important for Baseline profile)
            try {
                format.setInteger("max-ref-frames", 1)
            } catch (_: Exception) {
                // Ignore if not supported
            }
        }

        /**
         * Get a summary of available hardware decoders.
         */
        fun getDecoderSummary(): Map<String, Boolean> = mapOf(
            "H.264" to hasHardwareDecoder(MIME_H264),
            "VP8" to hasHardwareDecoder(MIME_VP8),
            "VP9" to hasHardwareDecoder(MIME_VP9),
            "AV1" to hasHardwareDecoder(MIME_AV1),
        )
    }

    private val delegate = DefaultVideoDecoderFactory(eglContext)

    override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
        Log.i(TAG, "Creating low-latency decoder for ${codecInfo.name}")
        return try {
            val lowLatency = org.webrtc.RemoteDecoderFactory.create(eglContext, codecInfo)
                ?: return delegate.createDecoder(codecInfo)
            val fallback = delegate.createDecoder(codecInfo)
            if (fallback != null) org.webrtc.VideoDecoderFallback(fallback, lowLatency) else lowLatency
        } catch (error: RuntimeException) {
            Log.w(TAG, "Hardware low-latency selection unavailable; using standard decoder", error)
            delegate.createDecoder(codecInfo)
        }
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        return delegate.supportedCodecs
    }
}
