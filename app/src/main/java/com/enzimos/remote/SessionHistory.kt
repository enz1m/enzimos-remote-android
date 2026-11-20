package com.enzimos.remote

/**
 * In-memory session history for recently connected sessions.
 *
 * Stores up to [maxEntries] session records for quick reconnect.
 * Entries are ordered by last-connected time (most recent first).
 *
 * For persistent storage, this data should be serialized to
 * SharedPreferences or a database.
 */
class SessionHistory(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    /** A session history entry. */
    data class Entry(
        val sessionId: String,
        val hostAddress: String,
        val port: Int,
        val command: String,
        val lastConnectedMs: Long,
        val totalConnections: Int = 1,
    )

    private val entries = mutableListOf<Entry>()
    private val lock = Any()

    /**
     * Record a session connection.
     *
     * If the session was previously connected, updates the timestamp
     * and increments the connection count.
     */
    fun record(sessionId: String, hostAddress: String, port: Int, command: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val existing = entries.indexOfFirst {
                it.sessionId == sessionId && it.hostAddress == hostAddress && it.port == port
            }

            if (existing >= 0) {
                val old = entries.removeAt(existing)
                entries.add(0, old.copy(
                    lastConnectedMs = now,
                    totalConnections = old.totalConnections + 1,
                    command = command,
                ))
            } else {
                entries.add(0, Entry(sessionId, hostAddress, port, command, now))
                while (entries.size > maxEntries) {
                    entries.removeAt(entries.size - 1)
                }
            }
        }
    }

    /**
     * Get all history entries, most recent first.
     */
    fun all(): List<Entry> {
        synchronized(lock) {
            return entries.toList()
        }
    }

    /**
     * Get the most recent entry, or null if empty.
     */
    fun mostRecent(): Entry? {
        synchronized(lock) {
            return entries.firstOrNull()
        }
    }

    /**
     * Get the number of entries.
     */
    fun size(): Int {
        synchronized(lock) {
            return entries.size
        }
    }

    /**
     * Remove a specific entry.
     */
    fun remove(sessionId: String, hostAddress: String, port: Int): Boolean {
        synchronized(lock) {
            return entries.removeAll {
                it.sessionId == sessionId && it.hostAddress == hostAddress && it.port == port
            }
        }
    }

    /**
     * Clear all history.
     */
    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    companion object {
        /** Default maximum history entries. */
        const val DEFAULT_MAX_ENTRIES = 20
    }
}
