package com.enzimos.remote.input

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator

class LongPressIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density // 4dp
        strokeCap = Paint.Cap.ROUND
        color = 0xBBFFFFFF.toInt() // Semi-transparent white
    }

    private val arcRect = RectF()
    private var animator: ValueAnimator? = null
    private var completeRunnable: Runnable? = null
    private var sweepAngle = 0f
    private var centerX = 0f
    private var centerY = 0f
    private val radius = 24f * resources.displayMetrics.density // 24dp radius

    init {
        visibility = GONE
    }

    /**
     * Show the progress ring at the given coordinates.
     *
     * @param x Touch X in view coordinates.
     * @param y Touch Y in view coordinates.
     * @param durationMs How long the progress ring takes to complete. Defaults
     *        to the system long-press timeout for consistency with GestureDetector.
     */
    fun show(x: Float, y: Float, durationMs: Long = ViewConfiguration.getLongPressTimeout().toLong()) {
        cancel()
        centerX = x
        centerY = y
        sweepAngle = 0f
        visibility = VISIBLE

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                sweepAngle = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Cancel the progress ring (finger moved too far or released early). */
    fun cancel() {
        animator?.cancel()
        animator = null
        completeRunnable?.let { removeCallbacks(it) }
        completeRunnable = null
        sweepAngle = 0f
        arcPaint.color = 0xBBFFFFFF.toInt()
        visibility = GONE
    }

    fun readyForDrag() {
        arcPaint.color = com.google.android.material.color.MaterialColors.getColor(
            this, androidx.appcompat.R.attr.colorPrimary, 0xFF81C784.toInt())
        invalidate()
    }

    /** Called when the long-press completes successfully. */
    fun complete() {
        animator?.cancel()
        animator = null
        completeRunnable?.let { removeCallbacks(it) }
        // Brief flash at full circle then hide.
        sweepAngle = 360f
        arcPaint.color = 0xFFFFFFFF.toInt() // Full white flash
        invalidate()
        val runnable = Runnable {
            arcPaint.color = 0xBBFFFFFF.toInt()
            visibility = GONE
            sweepAngle = 0f
            completeRunnable = null
        }
        completeRunnable = runnable
        postDelayed(runnable, 150)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sweepAngle <= 0f) return

        arcRect.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )
        // Draw from top (-90 degrees) clockwise.
        canvas.drawArc(arcRect, -90f, sweepAngle, false, arcPaint)
    }
}
