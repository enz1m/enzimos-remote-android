package com.enzimos.remote.video

import kotlin.math.max

/** Translation and focal points use the untransformed video-container coordinate space. */
data class VideoZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun scaled(factor: Float, oldFocusX: Float, oldFocusY: Float, focusX: Float, focusY: Float,
               centerX: Float, centerY: Float): VideoZoom {
        if (!factor.isFinite() || factor <= 0 || !listOf(oldFocusX, oldFocusY, focusX, focusY, centerX, centerY).all { it.isFinite() }) return this
        val next = (scale * factor).coerceIn(1f, 5f)
        val ratio = next / scale
        return VideoZoom(next,
            focusX - centerX - (oldFocusX - centerX - x) * ratio,
            focusY - centerY - (oldFocusY - centerY - y) * ratio)
    }

    fun constrained(width: Float, height: Float, viewportWidth: Float, viewportHeight: Float): VideoZoom {
        val maxX = max(0f, (width * scale - viewportWidth) / 2f)
        val maxY = max(0f, (height * scale - viewportHeight) / 2f)
        return copy(x = x.coerceIn(-maxX, maxX), y = y.coerceIn(-maxY, maxY))
    }
}
