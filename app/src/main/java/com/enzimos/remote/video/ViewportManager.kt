package com.enzimos.remote.video

import com.enzimos.remote.signaling.MIN_HEIGHT
import com.enzimos.remote.signaling.MIN_WIDTH
import com.enzimos.remote.signaling.MAX_HEIGHT
import com.enzimos.remote.signaling.MAX_WIDTH

/**
 * Manages viewport dimensions and transformations for the streaming session.
 *
 * Handles:
 * - Coordinate mapping from client view to server viewport
 * - Aspect ratio calculations for letterboxing/pillarboxing
 * - Orientation swaps
 * - Dimension validation and clamping
 */
class ViewportManager(
    initialWidth: Int = 1920,
    initialHeight: Int = 1080,
) {
    /** Current server-side viewport width. */
    @Volatile
    var width: Int = initialWidth
        private set

    /** Current server-side viewport height. */
    @Volatile
    var height: Int = initialHeight
        private set

    /** Aspect ratio (width / height). */
    val aspectRatio: Double
        get() = if (height > 0) width.toDouble() / height else 1.0

    /** Whether the viewport is in landscape orientation. */
    val isLandscape: Boolean
        get() = width > height

    /**
     * Update the viewport dimensions.
     *
     * @param newWidth New width (clamped to protocol bounds)
     * @param newHeight New height (clamped to protocol bounds)
     * @return true if dimensions changed
     */
    fun update(newWidth: Int, newHeight: Int): Boolean {
        val w = newWidth.coerceIn(MIN_WIDTH, MAX_WIDTH)
        val h = newHeight.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        if (w == width && h == height) return false
        width = w
        height = h
        return true
    }

    /**
     * Swap width and height (for orientation change).
     *
     * @return true if dimensions changed (always true unless square)
     */
    fun swapOrientation(): Boolean {
        if (width == height) return false
        val tmp = width
        width = height
        height = tmp
        return true
    }

    /** Maps view coordinates to the host viewport, returning null in the letterbox margins. */
    fun mapToViewport(
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): Pair<Double, Double>? {
        if (viewWidth <= 0 || viewHeight <= 0) return null

        val viewAspect = viewWidth.toDouble() / viewHeight
        val serverAspect = aspectRatio

        val renderWidth: Double
        val renderHeight: Double
        val offsetX: Double
        val offsetY: Double

        if (viewAspect > serverAspect) {
            // View is wider → pillarbox (bars on left/right)
            renderHeight = viewHeight.toDouble()
            renderWidth = renderHeight * serverAspect
            offsetX = (viewWidth - renderWidth) / 2
            offsetY = 0.0
        } else {
            // View is taller → letterbox (bars on top/bottom)
            renderWidth = viewWidth.toDouble()
            renderHeight = renderWidth / serverAspect
            offsetX = 0.0
            offsetY = (viewHeight - renderHeight) / 2
        }

        val relX = (viewX - offsetX) / renderWidth
        val relY = (viewY - offsetY) / renderHeight

        // Check if the point is within the rendered area
        if (relX < 0 || relX > 1 || relY < 0 || relY > 1) return null

        return (relX * width) to (relY * height)
    }

    /**
     * Calculate the render rectangle within a view.
     *
     * @param viewWidth Client view width
     * @param viewHeight Client view height
     * @return RenderRect with offset and dimensions
     */
    fun calculateRenderRect(viewWidth: Int, viewHeight: Int): RenderRect {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return RenderRect(0.0, 0.0, 0.0, 0.0)
        }

        val viewAspect = viewWidth.toDouble() / viewHeight
        val serverAspect = aspectRatio

        return if (viewAspect > serverAspect) {
            val renderHeight = viewHeight.toDouble()
            val renderWidth = renderHeight * serverAspect
            val offsetX = (viewWidth - renderWidth) / 2
            RenderRect(offsetX, 0.0, renderWidth, renderHeight)
        } else {
            val renderWidth = viewWidth.toDouble()
            val renderHeight = renderWidth / serverAspect
            val offsetY = (viewHeight - renderHeight) / 2
            RenderRect(0.0, offsetY, renderWidth, renderHeight)
        }
    }

    /** Rectangle describing the rendered video area within the view. */
    data class RenderRect(
        val offsetX: Double,
        val offsetY: Double,
        val width: Double,
        val height: Double,
    )
}
