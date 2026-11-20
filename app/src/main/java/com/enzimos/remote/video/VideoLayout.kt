package com.enzimos.remote.video

object VideoLayout {
    data class Size(val width: Int, val height: Int)

    fun fitWorkArea(videoWidth: Int, videoHeight: Int, contentHeight: Int, width: Int, height: Int): Size {
        require(videoWidth > 0 && videoHeight > 0 && contentHeight in 1..videoHeight && width > 0 && height > 0)
        val ratio = minOf(width.toDouble() / videoWidth, height.toDouble() / contentHeight)
        return Size((videoWidth * ratio).toInt().coerceAtLeast(1), (videoHeight * ratio).toInt().coerceAtLeast(1))
    }

    fun fit(videoWidth: Int, videoHeight: Int, width: Int, height: Int, cropBottom: Boolean): Size {
        require(videoWidth > 0 && videoHeight > 0 && width > 0 && height > 0)
        val ratio = if (cropBottom) width.toDouble() / videoWidth
            else minOf(width.toDouble() / videoWidth, height.toDouble() / videoHeight)
        return Size((videoWidth * ratio).toInt().coerceAtLeast(1), (videoHeight * ratio).toInt().coerceAtLeast(1))
    }
}
