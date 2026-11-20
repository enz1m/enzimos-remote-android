package org.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/** Pinned to the M150 AAR's package-private codec-wrapper API; compile on every AAR upgrade. */
object RemoteDecoderFactory {
    fun create(context: EglBase.Context?, codec: VideoCodecInfo): VideoDecoder? {
        if (context == null) return null
        val type = when (codec.name.uppercase()) {
            "H264" -> VideoCodecMimeType.H264
            "H265" -> VideoCodecMimeType.H265
            else -> return null
        }
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && MediaCodecUtils.isHardwareAccelerated(it) && MediaCodecUtils.codecSupportsType(it, type)
        } ?: return null
        val capabilities = info.getCapabilitiesForType(type.mimeType())
        val color = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, capabilities) ?: return null
        val lowLatency = Build.VERSION.SDK_INT >= 30 && capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        val factory = object : MediaCodecWrapperFactory {
            override fun createByCodecName(name: String): MediaCodecWrapper {
                val platform = MediaCodec.createByCodecName(name)
                val vendorLowLatency = if (Build.VERSION.SDK_INT >= 31) {
                    try { platform.supportedVendorParameters.contains("vendor.qti-ext-dec-low-latency.enable") }
                    catch (_: RuntimeException) { false }
                } else false
                return object : MediaCodecWrapper {
                    override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                        val options = com.enzimos.remote.webrtc.DecoderLatencyPolicy.options(name, Build.VERSION.SDK_INT, lowLatency, vendorLowLatency)
                        options.forEach { (key, value) -> format.setInteger(key, value) }
                        Log.i("RemoteDecoder", "configure hardware=$name advertised_low_latency=$lowLatency requested=${options.keys} surface=${surface != null}")
                        platform.configure(format, surface, crypto, flags)
                    }
                    override fun start() = platform.start()
                    override fun flush() = platform.flush()
                    override fun stop() = platform.stop()
                    override fun release() = platform.release()
                    override fun dequeueInputBuffer(timeoutUs: Long) = platform.dequeueInputBuffer(timeoutUs)
                    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) = platform.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
                    override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long) = platform.dequeueOutputBuffer(info, timeoutUs)
                    override fun releaseOutputBuffer(index: Int, render: Boolean) = platform.releaseOutputBuffer(index, render)
                    override fun getInputFormat() = platform.inputFormat
                    override fun getOutputFormat() = platform.outputFormat
                    override fun getOutputFormat(index: Int) = platform.getOutputFormat(index)
                    override fun getInputBuffer(index: Int) = platform.getInputBuffer(index)
                    override fun getOutputBuffer(index: Int) = platform.getOutputBuffer(index)
                    override fun createInputSurface() = platform.createInputSurface()
                    override fun setParameters(parameters: android.os.Bundle) = platform.setParameters(parameters)
                    override fun getCodecInfo() = platform.codecInfo
                }
            }
        }
        return AndroidVideoDecoder(factory, info.name, type, color, context)
    }
}
