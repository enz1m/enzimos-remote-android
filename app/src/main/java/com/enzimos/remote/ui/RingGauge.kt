package com.enzimos.remote.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * A round usage gauge: a track, an arc that sweeps to [fraction] and the
 * percentage in the middle. Changes animate from the shown value, and the arc
 * shifts from the primary to the error colour as usage gets high.
 */
class RingGauge @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val stroke = 7f * density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = MaterialColors.getColor(this@RingGauge, com.google.android.material.R.attr.colorSurfaceContainerHighest)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
        color = MaterialColors.getColor(this@RingGauge, com.google.android.material.R.attr.colorOnSurface)
    }
    private val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val tertiary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
    private val error = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
    private val bounds = RectF()
    private var shown = 0f
    private var known = false
    private var animator: ValueAnimator? = null

    /** Usage 0..1, or null while unknown (shows a dash). */
    fun setFraction(value: Float?, animate: Boolean = true) {
        val target = value?.coerceIn(0f, 1f)
        if (target == null) {
            known = false; animator?.cancel(); shown = 0f; invalidate(); return
        }
        if (!known || !animate || !isLaidOut) {
            known = true; animator?.cancel(); shown = target; invalidate(); return
        }
        if (kotlin.math.abs(target - shown) < 0.001f) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 650
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resolveSize((64 * density).toInt(), widthMeasureSpec)
        setMeasuredDimension(size, resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val inset = stroke / 2 + density
        bounds.set((width - size) / 2 + inset, (height - size) / 2 + inset, (width + size) / 2 - inset, (height + size) / 2 - inset)
        canvas.drawArc(bounds, 0f, 360f, false, track)
        if (known && shown > 0.002f) {
            arc.color = color(shown)
            canvas.drawArc(bounds, -90f, 360f * shown, false, arc)
        }
        val label = if (known) "${(shown * 100).toInt()}%" else "—"
        canvas.drawText(label, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2, text)
    }

    /** Primary up to 60 %, blending through tertiary to the error colour at 90 %. */
    private fun color(value: Float): Int = when {
        value <= 0.6f -> primary
        value <= 0.8f -> ColorUtils.blendARGB(primary, tertiary, (value - 0.6f) / 0.2f)
        else -> ColorUtils.blendARGB(tertiary, error, ((value - 0.8f) / 0.1f).coerceAtMost(1f))
    }
}
