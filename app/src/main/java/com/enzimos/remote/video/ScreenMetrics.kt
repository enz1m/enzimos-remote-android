package com.enzimos.remote.video

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Utility for querying device screen metrics to determine optimal
 * session viewport parameters.
 *
 * Provides:
 * - Real screen resolution (includes navigation bar area)
 * - Display density for HiDPI scale calculation
 * - Refresh rate for smooth video matching
 * - Orientation detection
 */
object ScreenMetrics {

    /** Screen dimensions in pixels. */
    data class Dimensions(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val refreshHz: Int,
    ) {
        /** Scale factor for HiDPI (1.0 for mdpi, 2.0 for xhdpi, etc.) */
        val scale: Double
            get() = (densityDpi.toDouble() / DisplayMetrics.DENSITY_DEFAULT)
                .coerceIn(0.5, 4.0)

        /** Whether the device is in landscape orientation. */
        val isLandscape: Boolean get() = width > height

        /** Recommended session width (always the smaller dimension for portrait). */
        val sessionWidth: Int get() = minOf(width, height)

        /** Recommended session height (always the larger dimension for portrait). */
        val sessionHeight: Int get() = maxOf(width, height)
    }

    /**
     * Get the real screen dimensions of the device.
     *
     * @param context Android context
     * @return Screen dimensions including density and refresh rate
     */
    fun getRealDimensions(context: Context): Dimensions {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val width: Int
        val height: Int
        val refreshHz: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
            refreshHz = context.display?.refreshRate?.toInt() ?: 60
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            @Suppress("DEPRECATION")
            refreshHz = wm.defaultDisplay.refreshRate.toInt()
        }

        val densityDpi = context.resources.displayMetrics.densityDpi

        return Dimensions(
            width = width,
            height = height,
            densityDpi = densityDpi,
            refreshHz = refreshHz.coerceIn(15, 240),
        )
    }

    /**
     * Determine the session scale from device density.
     *
     * Maps Android density to a clean scale factor:
     * - mdpi (160): 1.0
     * - hdpi (240): 1.5
     * - xhdpi (320): 2.0
     * - xxhdpi (480): 3.0
     * - xxxhdpi (640): 4.0
     */
    fun scaleFromDensity(densityDpi: Int): Double {
        return (densityDpi.toDouble() / DisplayMetrics.DENSITY_DEFAULT)
            .coerceIn(0.5, 4.0)
    }

    /**
     * Determine if the current orientation is landscape.
     */
    fun isLandscape(context: Context): Boolean {
        return context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
}
