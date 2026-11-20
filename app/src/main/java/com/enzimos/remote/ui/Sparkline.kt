package com.enzimos.remote.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * Recent history (0..1 values, oldest first) as a smooth line with a fading
 * fill. A new sample slides in from the right instead of jumping.
 */
class Sparkline @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val color = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = this@Sparkline.color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@Sparkline.color }
    private val path = Path()
    private val area = Path()
    private var values: List<Float> = emptyList()
    /** Number of samples the width holds. */
    var capacity = 40
    private var shift = 0f
    private var animator: ValueAnimator? = null

    fun setValues(next: List<Float>) {
        if (next.takeLast(capacity + 1) == values) return
        val added = next.size > values.size || (next.size == values.size && next.size >= capacity && next != values)
        values = next.takeLast(capacity + 1)
        animator?.cancel()
        if (added && isLaidOut && values.size > 1) {
            animator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 600
                interpolator = DecelerateInterpolator()
                addUpdateListener { shift = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            shift = 0f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            ColorUtils.setAlphaComponent(color, 90), ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (values.size < 2) return
        val pad = 3f * density
        val step = (width - pad) / (capacity - 1).toFloat()
        val h = height - 2 * pad
        // The newest sample sits at the right edge; while sliding in, everything is offset by a step.
        val right = width - pad + shift * step
        fun x(i: Int) = right - (values.size - 1 - i) * step
        fun y(i: Int) = pad + h * (1f - values[i].coerceIn(0f, 1f))
        path.reset()
        path.moveTo(x(0), y(0))
        for (i in 1 until values.size) {
            // Smooth with a midpoint quadratic curve.
            val mx = (x(i - 1) + x(i)) / 2
            val my = (y(i - 1) + y(i)) / 2
            path.quadTo(x(i - 1), y(i - 1), mx, my)
        }
        path.lineTo(x(values.size - 1), y(values.size - 1))
        area.reset()
        area.addPath(path)
        area.lineTo(x(values.size - 1), height.toFloat())
        area.lineTo(x(0), height.toFloat())
        area.close()
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
        canvas.drawCircle(x(values.size - 1), y(values.size - 1), 3.5f * density, dot)
        canvas.restore()
    }
}
