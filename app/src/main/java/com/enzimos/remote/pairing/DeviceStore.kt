@file:Suppress("DEPRECATION") // EncryptedSharedPreferences + MasterKey: stable API, no non-deprecated replacement yet

package com.enzimos.remote.pairing

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Device identity and paired computers.
 *
 * A paired computer is keyed by its stable host ID (from `hello_ack`/mDNS),
 * never by address: addresses are remembered as hints and refreshed by
 * discovery, so DHCP changes or USB forwarding do not create duplicates.
 * Records, including the device credential and pinned certificate
 * fingerprint, live in Keystore-backed encrypted preferences.
 */
class DeviceStore(private val context: Context) {

    companion object {
        private const val TAG = "DeviceStore"
        private const val PREFS_NAME = "enzremote_device"
        private const val ENCRYPTED_PREFS_NAME = "enzremote_tokens"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_HOST_PREFIX = "host_"
        private const val LEGACY_TOKEN_PREFIX = "token_"
        private const val LEGACY_NAME_PREFIX = "name_"
        private const val MAX_ADDRESSES = 6
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Volatile
        private var volatileCleared = false

        /** Change listeners across store instances (all share one file). */
        private val listeners = mutableSetOf<() -> Unit>()
    }

    /** Paired computer. */
    @Serializable
    data class PairedHost(
        /** Stable host ID, or `legacy:<address>` for pre-identity pairings. */
        val hostId: String,
        val name: String,
        val token: String,
        /** Address hints, most recently successful first ("host:port"). */
        val addresses: List<String> = emptyList(),
        /** Pinned SHA-256 certificate fingerprint (lowercase hex), if TLS. */
        val fingerprint: String? = null,
        val pairedAt: Long = 0,
        val lastConnectedAt: Long = 0,
        /** The host rejected the credential; the user must pair again. */
        val needsPairing: Boolean = false,
    ) {
        /** Redact the token to prevent accidental leakage in logs/crash reports. */
        override fun toString(): String =
            "PairedHost(hostId=$hostId, name=$name, token=***, addresses=$addresses, pinned=${fingerprint != null})"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether encrypted storage is available. False if Keystore is broken. */
    var encryptionAvailable: Boolean = true
        private set

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // SECURITY: Do NOT fall back to plaintext. Keep an in-memory
            // store; the user must re-pair after restart, which is safe.
            Log.e(TAG, "Encrypted storage unavailable (Keystore error); pairings will not persist", e)
            encryptionAvailable = false
            // Volatile store: wiped once per process (not per instance — several
            // components share it) so nothing persists across restarts.
            val volatile = context.getSharedPreferences(ENCRYPTED_PREFS_NAME + "_volatile", Context.MODE_PRIVATE)
            synchronized(listeners) {
                if (!volatileCleared) {
                    volatileCleared = true
                    volatile.edit(commit = true) { clear() }
                }
            }
            volatile
        }
    }

    /**
     * Unique device ID, created on first launch.
     *
     * Format: "android-XXXXXXXXXXXXXXXX" (64 random bits). Persisted across
     * restarts but not reinstalls (backup is disabled).
     */
    fun getDeviceId(): String {
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = "android-${UUID.randomUUID().toString().replace("-", "").take(16)}"
            prefs.edit { putString(KEY_DEVICE_ID, id) }
            Log.i(TAG, "Generated new device ID")
            id
        }
    }

    /** Name the computer shows when this phone asks to pair. */
    fun deviceName(): String {
        val userSet = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        if (!userSet.isNullOrBlank()) return userSet.take(64)
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return (if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model").take(64)
    }

    /** All paired computers, most recently used first. */
    fun listPairedHosts(): List<PairedHost> {
        migrateLegacy()
        return encryptedPrefs.all
            .filter { it.key.startsWith(KEY_HOST_PREFIX) }
            .mapNotNull { (_, value) ->
                try {
                    json.decodeFromString<PairedHost>(value as String)
                } catch (e: Exception) {
                    Log.w(TAG, "Dropping unreadable host record", e)
                    null
                }
            }
            .sortedWith(compareByDescending<PairedHost> { it.lastConnectedAt }.thenBy { it.name.lowercase() })
    }

    fun getHost(hostId: String): PairedHost? = listPairedHosts().firstOrNull { it.hostId == hostId }

    /** Insert or replace a host record. */
    fun saveHost(host: PairedHost) {
        encryptedPrefs.edit(commit = true) {
            putString(KEY_HOST_PREFIX + host.hostId, json.encodeToString(PairedHost.serializer(), host))
        }
        notifyChanged()
    }

    /** Record a successful connection through [address]. */
    fun recordConnected(hostId: String, address: String, name: String?) {
        val host = getHost(hostId) ?: return
        saveHost(
            host.copy(
                name = name?.takeIf { it.isNotBlank() } ?: host.name,
                addresses = withAddress(host.addresses, address),
                lastConnectedAt = System.currentTimeMillis(),
                needsPairing = false,
            ),
        )
    }

    /** Merge discovered address hints without changing their recency order much. */
    fun addAddressHints(hostId: String, addresses: List<String>) {
        val host = getHost(hostId) ?: return
        val merged = (host.addresses + addresses).distinct().take(MAX_ADDRESSES)
        if (merged != host.addresses) saveHost(host.copy(addresses = merged))
    }

    /** Replace a legacy address-keyed record with its real host ID. */
    fun rekey(oldId: String, newId: String, name: String?) {
        if (oldId == newId) return
        val host = getHost(oldId) ?: return
        encryptedPrefs.edit(commit = true) { remove(KEY_HOST_PREFIX + oldId) }
        val existing = getHost(newId)
        saveHost(
            host.copy(
                hostId = newId,
                name = name ?: host.name,
                addresses = (host.addresses + (existing?.addresses ?: emptyList())).distinct().take(MAX_ADDRESSES),
            ),
        )
    }

    fun markNeedsPairing(hostId: String, needs: Boolean) {
        val host = getHost(hostId) ?: return
        if (host.needsPairing != needs) saveHost(host.copy(needsPairing = needs))
    }

    fun rename(hostId: String, name: String) {
        val host = getHost(hostId) ?: return
        saveHost(host.copy(name = name.trim().take(64).ifEmpty { host.name }))
    }

    /** Forget a paired computer. */
    fun deleteHost(hostId: String) {
        encryptedPrefs.edit(commit = true) { remove(KEY_HOST_PREFIX + hostId) }
        prefs.edit { remove("catalog_$hostId"); remove("favorites_$hostId") }
        Log.i(TAG, "Forgot a paired computer")
        notifyChanged()
    }

    /** Favorite catalog app IDs for a computer (ordered as added). */
    fun favorites(hostId: String): List<String> =
        prefs.getString("favorites_$hostId", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun setFavorite(hostId: String, appId: String, favorite: Boolean) {
        val current = favorites(hostId).toMutableList()
        current.remove(appId)
        if (favorite) current.add(appId)
        prefs.edit { putString("favorites_$hostId", current.joinToString("\n")) }
    }

    /** Last known app catalog JSON (not secret), for instant home rendering. */
    fun cachedCatalog(hostId: String): String? = prefs.getString("catalog_$hostId", null)

    fun saveCatalog(hostId: String, catalogJson: String) {
        if (prefs.getString("catalog_$hostId", null) != catalogJson) {
            prefs.edit { putString("catalog_$hostId", catalogJson) }
        }
    }

    fun addChangeListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }
    fun removeChangeListener(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }
    private fun notifyChanged() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    /** Clear all stored data (device ID + all pairings). */
    fun clearAll() {
        prefs.edit { clear() }
        encryptedPrefs.edit(commit = true) { clear() }
        Log.i(TAG, "All device data cleared")
        notifyChanged()
    }

    /** Convert pre-identity `token_<address>` entries into host records. */
    private fun migrateLegacy() {
        val legacy = encryptedPrefs.all.filter { it.key.startsWith(LEGACY_TOKEN_PREFIX) }
        if (legacy.isEmpty()) return
        encryptedPrefs.edit(commit = true) {
            for ((key, value) in legacy) {
                val address = key.removePrefix(LEGACY_TOKEN_PREFIX)
                val token = value as? String ?: continue
                val name = encryptedPrefs.getString(LEGACY_NAME_PREFIX + address, address) ?: address
                val host = PairedHost(
                    hostId = "legacy:$address",
                    name = name,
                    token = token,
                    addresses = listOf(address),
                )
                putString(KEY_HOST_PREFIX + host.hostId, json.encodeToString(PairedHost.serializer(), host))
                remove(key)
                remove(LEGACY_NAME_PREFIX + address)
            }
        }
    }

    private fun withAddress(addresses: List<String>, address: String) =
        (listOf(address) + addresses).distinct().take(MAX_ADDRESSES)
}
