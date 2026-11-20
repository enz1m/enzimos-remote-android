package com.enzimos.remote

/** Work-area changes keep the encoded canvas and its input coordinate system fixed. */
class ContentResize(val canvas: AppViewport.Size) {
    data class Request(val serial: Int, val width: Int, val height: Int)
    var height = canvas.height
        private set
    var pending: Request? = null
        private set
    private var appliedHeight = canvas.height
    private var serial = 0
    val cropping: Boolean get() = height < canvas.height

    fun canFit(target: AppViewport.Size): Boolean = target.width == canvas.width &&
        target.scale == canvas.scale && target.height in 240..canvas.height

    fun withinSurface(target: AppViewport.Size, width: Int, height: Int, allowNarrowing: Boolean = false): AppViewport.Size? {
        val adjusted = if (allowNarrowing && target.scale == canvas.scale &&
            target.width in (canvas.width * .75).toInt()..canvas.width) {
            target.copy(width = canvas.width, height = minOf(canvas.height,
                (target.height.toDouble() * canvas.width / target.width).toInt()))
        } else target
        if (!canFit(adjusted) || width <= 0 || height <= 0) return null
        val quarters = kotlin.math.round(canvas.scale * 4).toInt()
        if (quarters <= 0) return null
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val step = quarters / gcd(quarters, 4)
        val maximum = kotlin.math.floor(height.toDouble() * canvas.width / width).toInt()
        val safeHeight = minOf(adjusted.height, maximum / step * step)
        val minimum = if (allowNarrowing) minOf(canvas.height,
            kotlin.math.ceil(160 * canvas.scale / step).toInt() * step) else 0
        return adjusted.copy(height = maxOf(safeHeight, minimum)).takeIf { canFit(it) }
    }

    fun update(target: AppViewport.Size) {
        require(canFit(target))
        height = target.height
    }

    fun nextRequest(): Request? {
        if (pending != null || height == appliedHeight) return null
        serial = if (serial == Int.MAX_VALUE) 1 else serial + 1
        return Request(serial, canvas.width, height).also { pending = it }
    }

    fun acknowledge(serial: Int): Boolean {
        val sent = pending?.takeIf { it.serial == serial } ?: return false
        appliedHeight = sent.height
        pending = null
        return true
    }
}
