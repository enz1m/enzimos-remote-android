package com.enzimos.remote.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.enzimos.remote.R
import com.google.android.material.color.MaterialColors
import kotlin.math.min

/** Opaque corner caps work over SurfaceView video without changing the hardware rendering path. */
class VideoCornerMask @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val mask = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val bounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@VideoCornerMask, com.google.android.material.R.attr.colorSurface, Color.BLACK)
    }
    private val requestedRadius = resources.getDimension(R.dimen.stream_top_corner_radius)
    private var radius = 0f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radius = min(requestedRadius, min(w, h) / 2f)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        mask.reset()
        mask.fillType = Path.FillType.EVEN_ODD
        mask.addRect(bounds, Path.Direction.CW)
        mask.addRoundRect(bounds, floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) { canvas.drawPath(mask, paint) }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        event.actionMasked != MotionEvent.ACTION_DOWN || !containsVideoPoint(event.x, event.y, width.toFloat(), height.toFloat(), radius)

    companion object {
        internal fun containsVideoPoint(x: Float, y: Float, width: Float, height: Float, radius: Float): Boolean {
            if (x < 0 || y < 0 || x >= width || y >= height) return false
            val r = radius.coerceIn(0f, min(width, height) / 2f)
            if (y >= r || (x >= r && x <= width - r)) return true
            val cx = if (x < r) r else width - r
            return (x - cx) * (x - cx) + (y - r) * (y - r) <= r * r
        }
    }
}
