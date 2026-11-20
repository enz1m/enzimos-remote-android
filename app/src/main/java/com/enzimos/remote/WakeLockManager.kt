package com.enzimos.remote

import android.content.Context
import android.os.PowerManager
import android.util.Log

/** Holds CPU and screen wake locks independently as the stream moves between foreground and background. */
class WakeLockManager(context: Context) {

    // Use applicationContext to avoid leaking an Activity reference.
    private val powerManager =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var partialLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null

    /**
     * Acquire a partial wake lock (CPU only).
     *
     * @param timeoutMs Maximum hold time in milliseconds (default 4 hours)
     */
    @Synchronized
    fun acquirePartial(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (partialLock?.isHeld == true) return
        // Release timed-out lock before creating a new one.
        releasePartialInternal()

        partialLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            PARTIAL_TAG,
        ).apply {
            acquire(timeoutMs)
        }
        Log.d(TAG, "Partial wake lock acquired (timeout=${timeoutMs}ms)")
    }

    /**
     * Acquire a screen dim wake lock (CPU + screen dim).
     *
     * @param timeoutMs Maximum hold time in milliseconds (default 4 hours)
     */
    @Suppress("DEPRECATION") // SCREEN_DIM_WAKE_LOCK is deprecated but still functional
    @Synchronized
    fun acquireScreenDim(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (screenLock?.isHeld == true) return
        // Release timed-out lock before creating a new one.
        releaseScreenDimInternal()

        screenLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            SCREEN_TAG,
        ).apply {
            acquire(timeoutMs)
        }
        Log.d(TAG, "Screen dim wake lock acquired (timeout=${timeoutMs}ms)")
    }

    /**
     * Release the partial wake lock.
     */
    @Synchronized
    fun releasePartial() {
        releasePartialInternal()
    }

    /**
     * Release the screen dim wake lock.
     */
    @Synchronized
    fun releaseScreenDim() {
        releaseScreenDimInternal()
    }

    /**
     * Release all wake locks.
     */
    @Synchronized
    fun releaseAll() {
        releasePartialInternal()
        releaseScreenDimInternal()
    }

    // Internal release helpers — caller must hold the intrinsic lock.
    private fun releasePartialInternal() {
        partialLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Partial wake lock released")
            }
        }
        partialLock = null
    }

    private fun releaseScreenDimInternal() {
        screenLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Screen dim wake lock released")
            }
        }
        screenLock = null
    }

    /**
     * Check if any wake lock is currently held.
     */
    @Synchronized
    fun isHeld(): Boolean {
        return partialLock?.isHeld == true || screenLock?.isHeld == true
    }

    /**
     * Check if the partial wake lock is held.
     */
    @Synchronized
    fun isPartialHeld(): Boolean = partialLock?.isHeld == true

    /**
     * Check if the screen dim wake lock is held.
     */
    @Synchronized
    fun isScreenHeld(): Boolean = screenLock?.isHeld == true

    companion object {
        private const val TAG = "WakeLockManager"

        /** Wake lock tag for partial CPU lock. */
        const val PARTIAL_TAG = "enzremote:stream-partial"

        /** Wake lock tag for screen dim lock. */
        const val SCREEN_TAG = "enzremote:stream-screen"

        /** Default timeout: 4 hours in milliseconds. */
        const val DEFAULT_TIMEOUT_MS = 4L * 60 * 60 * 1000
    }
}
