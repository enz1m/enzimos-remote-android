package com.enzimos.remote.input

/**
 * Tracks active touch pointer slots for multi-touch events.
 *
 * Maps Android pointer IDs (arbitrary, potentially large) to
 * protocol slot numbers (0-15). The protocol supports up to
 * 16 simultaneous touch points.
 *
 * When a pointer goes down, it is assigned the lowest available slot.
 * When it goes up, the slot is released for reuse.
 */
class TouchSlotTracker(
    /** Maximum number of slots (protocol limit). */
    private val maxSlots: Int = MAX_PROTOCOL_SLOTS,
) {
    /** Maps Android pointer ID → protocol slot number. */
    private val idToSlot = mutableMapOf<Int, Int>()

    /** Tracks which slots are in use. */
    private val slotsInUse = BooleanArray(maxSlots)

    /**
     * Assign a slot for a new pointer.
     *
     * @param pointerId Android pointer ID from MotionEvent
     * @return Assigned slot number (0-15), or -1 if all slots are full
     */
    fun assignSlot(pointerId: Int): Int {
        // If already assigned, return existing slot
        idToSlot[pointerId]?.let { return it }

        // Find the lowest available slot
        for (i in 0 until maxSlots) {
            if (!slotsInUse[i]) {
                slotsInUse[i] = true
                idToSlot[pointerId] = i
                return i
            }
        }
        return -1 // All slots full
    }

    /**
     * Get the slot for an existing pointer.
     *
     * @param pointerId Android pointer ID
     * @return Slot number, or -1 if not tracked
     */
    fun getSlot(pointerId: Int): Int {
        return idToSlot[pointerId] ?: -1
    }

    /**
     * Release a slot when a pointer goes up or is cancelled.
     *
     * @param pointerId Android pointer ID
     * @return The released slot number, or -1 if not tracked
     */
    fun releaseSlot(pointerId: Int): Int {
        val slot = idToSlot.remove(pointerId) ?: return -1
        slotsInUse[slot] = false
        return slot
    }

    /**
     * Release all slots (e.g., on ACTION_CANCEL).
     */
    fun releaseAll() {
        idToSlot.clear()
        slotsInUse.fill(false)
    }

    /**
     * Get the number of active pointers.
     */
    fun activeCount(): Int = idToSlot.size

    /**
     * Check if all slots are full.
     */
    fun isFull(): Boolean = idToSlot.size >= maxSlots

    /**
     * Check if a pointer ID is currently tracked.
     */
    fun isTracked(pointerId: Int): Boolean = idToSlot.containsKey(pointerId)

    companion object {
        /** Maximum touch slots in the protocol. */
        const val MAX_PROTOCOL_SLOTS = 16
    }
}
