package com.enzimos.remote.video

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.enzimos.remote.R
import kotlin.math.min

/** Floating stream toolbar; tighter while the keyboard is up in landscape. */
class StreamDock @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private var compact = false

    fun setKeyboardCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
        val card = findViewById<com.google.android.material.card.MaterialCardView>(R.id.controlDock)
        (card.layoutParams as MarginLayoutParams).also { it.topMargin = dp(if (value) 2 else 6); it.bottomMargin = it.topMargin; card.layoutParams = it }
        card.getChildAt(0).setPadding(dp(8), dp(if (value) 0 else 4), dp(8), dp(if (value) 0 else 4))
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.stream_dock_max_width)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val width = if (mode == MeasureSpec.UNSPECIFIED) maxWidth else min(MeasureSpec.getSize(widthMeasureSpec), maxWidth)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}
