package com.enzimos.remote.video

import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/** Insets are unions, not sums: the IME already includes the navigation-bar region. */
object StreamInsets {
    fun contentPadding(insets: WindowInsetsCompat, inPictureInPicture: Boolean = false, ignoreIme: Boolean = false): Insets {
        if (inPictureInPicture) return Insets.NONE
        val cutout = insets.displayCutout
        return merge(
            insets.getInsets(WindowInsetsCompat.Type.systemBars()),
            insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()),
            cutout?.let { Insets.of(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom) } ?: Insets.NONE,
            cutout?.waterfallInsets ?: Insets.NONE,
            if (ignoreIme) Insets.NONE else insets.getInsets(WindowInsetsCompat.Type.ime()),
        )
    }

    internal fun merge(vararg regions: Insets): Insets = regions.fold(Insets.NONE) { a, b ->
        Insets.of(max(a.left, b.left), max(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
    }
}
