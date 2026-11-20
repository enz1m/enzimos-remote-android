package com.enzimos.remote.signaling

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Adds jitter to capped retry delays so clients do not retry in lockstep. */
class RetryPolicy(
    val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val factor: Double = DEFAULT_FACTOR,
    val jitterFraction: Double = DEFAULT_JITTER_FRACTION,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    /**
     * Calculate the delay for a given attempt number (0-indexed).
     *
     * @param attempt The attempt number (0 = first retry)
     * @return Delay in milliseconds, or -1 if max attempts exceeded
     */
    fun delayMs(attempt: Int): Long {
        if (maxAttempts > 0 && attempt >= maxAttempts) return -1

        val exponential = baseDelayMs * factor.pow(attempt.toDouble())
        val base = min(exponential.toLong(), maxDelayMs)
        val jitter = (base * jitterFraction * Random.nextDouble()).toLong()
        return base + jitter
    }

    /**
     * Calculate the delay without jitter (for deterministic testing).
     *
     * @param attempt The attempt number (0-indexed)
     * @return Delay in milliseconds, or -1 if max attempts exceeded
     */
    fun delayMsDeterministic(attempt: Int): Long {
        if (maxAttempts > 0 && attempt >= maxAttempts) return -1
        val exponential = baseDelayMs * factor.pow(attempt.toDouble())
        return min(exponential.toLong(), maxDelayMs)
    }

    /**
     * Check if another retry attempt is allowed.
     */
    fun canRetry(attempt: Int): Boolean {
        return maxAttempts == 0 || attempt < maxAttempts
    }

    /**
     * Calculate the total time spent waiting across all attempts up to
     * the given attempt number (using deterministic delays).
     */
    fun totalDelayMs(upToAttempt: Int): Long {
        var total = 0L
        for (i in 0 until upToAttempt) {
            val delay = delayMsDeterministic(i)
            if (delay < 0) break
            total += delay
        }
        return total
    }

    companion object {
        const val DEFAULT_BASE_DELAY_MS = 1000L
        const val DEFAULT_MAX_DELAY_MS = 30_000L
        const val DEFAULT_FACTOR = 2.0
        const val DEFAULT_JITTER_FRACTION = 0.1
        const val DEFAULT_MAX_ATTEMPTS = 10

        /** Quick preset: fast retries for real-time scenarios. */
        fun fast() = RetryPolicy(
            baseDelayMs = 200,
            maxDelayMs = 5_000,
            maxAttempts = 5,
        )

        /** Default preset: balanced for signaling reconnection. */
        fun default() = RetryPolicy()

        /** Aggressive preset: more retries, longer waits. */
        fun aggressive() = RetryPolicy(
            baseDelayMs = 2000,
            maxDelayMs = 60_000,
            maxAttempts = 20,
        )
    }
}
