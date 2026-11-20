package com.enzimos.remote.video

import kotlin.math.min

/** A temporary client-side crop; the desktop's capture and application geometry do not change. */
data class DesktopKeyboardView(val width: Int, val height: Int, val previous: VideoZoom,
                               val anchorX: Float, val anchorY: Float) {
    fun size(viewportWidth: Int): VideoLayout.Size {
        val ratio = min(1f, viewportWidth.toFloat() / width)
        return VideoLayout.Size((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
    }

    fun focused(size: VideoLayout.Size, viewportWidth: Int, viewportHeight: Int): VideoZoom {
        val y = if (anchorY < .06f) .33f else anchorY.coerceIn(0f, 1f)
        return VideoZoom(previous.scale,
            -(anchorX.coerceIn(0f, 1f) - .5f) * size.width * previous.scale,
            -(y - .5f) * size.height * previous.scale)
            .constrained(size.width.toFloat(), size.height.toFloat(), viewportWidth.toFloat(), viewportHeight.toFloat())
    }
}
