package com.enzimos.remote.input

/**
 * Provides throttle and debounce utilities for input events.
 *
 * - **Throttle**: ensures an action runs at most once per interval.
 *   Used for pointer move events to limit data channel messages.
 * - **Debounce**: delays action until input stops for a given period.
 *   Used for resize events to avoid sending rapid intermediate sizes.
 */
class ThrottleHelper {

    /**
     * Throttle: executes the action at most once per [intervalMs].
     *
     * @param intervalMs Minimum time between executions in milliseconds
     */
    class Throttle(private val intervalMs: Long) {
        private var lastExecutionMs: Long = 0
        private val lock = Any()

        /**
         * Attempt to execute the action.
         *
         * @return true if the action should be executed, false if throttled
         */
        fun tryExecute(): Boolean {
            return tryExecute(System.currentTimeMillis())
        }

        /**
         * Attempt to execute at a specific time (for testing).
         */
        fun tryExecute(nowMs: Long): Boolean {
            synchronized(lock) {
                if (nowMs - lastExecutionMs >= intervalMs) {
                    lastExecutionMs = nowMs
                    return true
                }
                return false
            }
        }

        /**
         * Force the next execution to succeed regardless of timing.
         */
        fun reset() {
            synchronized(lock) {
                lastExecutionMs = 0
            }
        }
    }

    /**
     * Debounce: delays execution until no calls for [delayMs].
     *
     * Tracks the latest scheduled value and only executes when
     * the delay expires without a new call.
     *
     * @param delayMs Delay in milliseconds after the last call
     */
    class Debounce<T>(private val delayMs: Long) {
        private var latestValue: T? = null
        private var lastCallMs: Long = 0
        private val lock = Any()

        /**
         * Schedule a value for debounced execution.
         */
        fun schedule(value: T) {
            schedule(value, System.currentTimeMillis())
        }

        /**
         * Schedule at a specific time (for testing).
         */
        fun schedule(value: T, nowMs: Long) {
            synchronized(lock) {
                latestValue = value
                lastCallMs = nowMs
            }
        }

        /**
         * Check if the debounce delay has expired and return the value.
         *
         * @return The debounced value if ready, null otherwise
         */
        fun poll(): T? {
            return poll(System.currentTimeMillis())
        }

        /**
         * Poll at a specific time (for testing).
         */
        fun poll(nowMs: Long): T? {
            synchronized(lock) {
                val value = latestValue ?: return null
                if (nowMs - lastCallMs >= delayMs) {
                    latestValue = null
                    return value
                }
                return null
            }
        }

        /**
         * Cancel any pending debounced value.
         */
        fun cancel() {
            synchronized(lock) {
                latestValue = null
                lastCallMs = 0
            }
        }

        /**
         * Whether there is a pending value.
         */
        fun hasPending(): Boolean {
            synchronized(lock) {
                return latestValue != null
            }
        }
    }
}
