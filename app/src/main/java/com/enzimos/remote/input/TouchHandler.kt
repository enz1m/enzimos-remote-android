package com.enzimos.remote.input

import android.view.MotionEvent

/** Maps up to 16 Android touch contacts into the remote session’s coordinate space. */
class TouchHandler(
    sessionWidth: Int,
    sessionHeight: Int,
) {
    @Volatile private var sessionWidth: Int = sessionWidth
    @Volatile private var sessionHeight: Int = sessionHeight

    companion object {
        /** Maximum number of simultaneous touch pointers. */
        const val MAX_POINTERS = 16
    }

    /** Update session dimensions (e.g., after resize). */
    fun updateSessionSize(width: Int, height: Int) {
        sessionWidth = width
        sessionHeight = height
    }

    /** Uses pre-zoom view pixels for the pan offsets. */
    fun onTouchEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
        zoom: Float = 1.0f,
        panOffX: Float = 0f,
        panOffY: Float = 0f,
    ): List<String> {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return emptyList()

        val messages = mutableListOf<String>()
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val slot = event.getPointerId(actionIndex)
                if (slot < MAX_POINTERS) {
                    val x = mapX(event.getX(actionIndex), surfaceWidth, zoom, panOffX)
                    val y = mapY(event.getY(actionIndex), surfaceHeight, zoom, panOffY)
                    messages.add(ControlMessageSerializer.touch(slot, "down", x, y))
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // Emit move for every changed pointer, including historical samples
                for (pointerIdx in 0 until event.pointerCount) {
                    val slot = event.getPointerId(pointerIdx)
                    if (slot >= MAX_POINTERS) continue

                    // Historical samples for higher-frequency input
                    for (h in 0 until event.historySize) {
                        val x = mapX(event.getHistoricalX(pointerIdx, h), surfaceWidth, zoom, panOffX)
                        val y = mapY(event.getHistoricalY(pointerIdx, h), surfaceHeight, zoom, panOffY)
                        messages.add(ControlMessageSerializer.touch(slot, "move", x, y))
                    }

                    // Current sample
                    val x = mapX(event.getX(pointerIdx), surfaceWidth, zoom, panOffX)
                    val y = mapY(event.getY(pointerIdx), surfaceHeight, zoom, panOffY)
                    messages.add(ControlMessageSerializer.touch(slot, "move", x, y))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val slot = event.getPointerId(actionIndex)
                if (slot < MAX_POINTERS) {
                    val x = mapX(event.getX(actionIndex), surfaceWidth, zoom, panOffX)
                    val y = mapY(event.getY(actionIndex), surfaceHeight, zoom, panOffY)
                    messages.add(ControlMessageSerializer.touch(slot, "up", x, y))
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // Cancel all active pointers
                for (pointerIdx in 0 until event.pointerCount) {
                    val slot = event.getPointerId(pointerIdx)
                    if (slot >= MAX_POINTERS) continue
                    messages.add(ControlMessageSerializer.touch(slot, "cancel", 0.0, 0.0))
                }
            }
        }

        return messages
    }

    /**
     * Map surface X coordinate to session logical X, accounting for zoom and pan.
     *
     * When zoomed, the view transform is: displayX = (logicalX + panOff) * zoom + center
     * So: logicalX = (displayX - center) / zoom - panOff
     * Then map from view space to session space: sessionX = logicalX / surfaceWidth * sessionWidth
     */
    private fun mapX(surfaceX: Float, surfaceWidth: Int, zoom: Float = 1f, panOff: Float = 0f): Double {
        val center = surfaceWidth / 2.0
        val logicalX = (surfaceX - center) / zoom - panOff + center
        return (logicalX / surfaceWidth) * sessionWidth
    }

    /** Map surface Y coordinate to session logical Y, accounting for zoom and pan. */
    private fun mapY(surfaceY: Float, surfaceHeight: Int, zoom: Float = 1f, panOff: Float = 0f): Double {
        val center = surfaceHeight / 2.0
        val logicalY = (surfaceY - center) / zoom - panOff + center
        return (logicalY / surfaceHeight) * sessionHeight
    }
}
