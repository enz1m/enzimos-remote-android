package com.enzimos.remote

object AppViewport {
    const val DOCK_HEIGHT_DP = 68
    data class Size(val width: Int, val height: Int, val scale: Double)
    fun resolutionLimit(preference: String): Int? = preference.split('x').mapNotNull(String::toIntOrNull)
        .takeIf { it.size == 2 && it.all { value -> value >= 240 } }?.minOrNull()

    fun create(widthPixels: Int, heightPixels: Int, density: Double, maxShortEdge: Int? = null): Size {
        val deviceDensity = (density.takeIf { it.isFinite() } ?: 1.0).coerceIn(1.0, 4.0)
        return forSurface(widthPixels, heightPixels - (DOCK_HEIGHT_DP * deviceDensity).toInt(), deviceDensity, maxShortEdge)
    }

    /** Uses the measured video area: status/cutout, dock and IME space have already been removed. */
    fun forSurface(widthPixels: Int, heightPixels: Int, density: Double, maxShortEdge: Int? = null): Size {
        val deviceDensity = (density.takeIf { it.isFinite() } ?: 1.0).coerceIn(1.0, 4.0)
        val usableHeight = heightPixels.coerceAtLeast(240)
        val ratio = maxShortEdge?.let { (it.toDouble() / minOf(widthPixels.coerceAtLeast(240), usableHeight)).coerceAtMost(1.0) } ?: 1.0
        val quarterScale = kotlin.math.floor((deviceDensity * ratio).coerceIn(0.5, 4.0) * 4).toInt()
        val scale = quarterScale / 4.0
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val divisor = quarterScale / gcd(quarterScale, 4)
        val alignment = divisor * 2 / gcd(divisor, 2)
        // The compositor requires integral logical dimensions; H.264 requires even pixels.
        fun align(value: Int): Int {
            val minimum = ((240 + alignment - 1) / alignment) * alignment
            val maximum = (8192 / alignment) * alignment
            val bounded = value.coerceIn(minimum, maximum)
            return (((bounded + alignment / 2) / alignment) * alignment).coerceIn(minimum, maximum)
        }
        return Size(align((widthPixels * scale / deviceDensity).toInt()), align((usableHeight * scale / deviceDensity).toInt()), scale)
    }
}
