package com.enzimos.remote

class InputDrain {
    enum class Result { IGNORE, COMPLETE, FAILED }

    private var nextSerial = 0
    private var pending: Int? = null

    fun begin(): Int {
        pending?.let { return it }
        nextSerial = if (nextSerial == Int.MAX_VALUE) 1 else nextSerial + 1
        return nextSerial.also { pending = it }
    }

    fun acknowledge(serial: Int, applied: Boolean): Result {
        if (pending != serial) return Result.IGNORE
        pending = null
        return if (applied) Result.COMPLETE else Result.FAILED
    }

    fun timeout(serial: Int): Result = acknowledge(serial, false)

    fun cancel() { pending = null }
}
