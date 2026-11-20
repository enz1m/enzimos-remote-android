package com.enzimos.remote.signaling

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Discovers Phone Link computers via the `_enzremote._tcp` mDNS service. */
class Discovery(context: Context) {
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "Discovery"
        const val SERVICE_TYPE = "_enzremote._tcp."
    }

    /** Represents a discovered enzremote host. */
    data class HostInfo(
        /** mDNS service instance name (unique on the LAN). */
        val name: String,
        val address: String,
        val port: Int,
        val version: Int = 0,
        /** Stable host ID from TXT `id`, empty for older hosts. */
        val hostId: String = "",
        /** Computer name from TXT `name`, else the instance name. */
        val displayName: String = name,
        /** Certificate fingerprint from TXT `fp` (a hint; pairing verifies it). */
        val fingerprint: String? = null,
        /** Every resolved IPv4 address (API 34+ reports several). */
        val addresses: List<String> = listOf(address),
    ) {
        /** Address string suitable for SignalingClient.connect(). */
        val connectAddress: String get() = "$address:$port"
        val connectAddresses: List<String> get() = addresses.map { "$it:$port" }
    }

    /** Events emitted during discovery. */
    sealed class DiscoveryEvent {
        data class Found(val host: HostInfo) : DiscoveryEvent()
        data class Lost(val name: String) : DiscoveryEvent()
        data class Error(val message: String) : DiscoveryEvent()
    }

    /**
     * Start mDNS discovery and emit events as a cold Flow.
     *
     * The flow acquires a WiFi multicast lock while active and releases
     * it on cancellation. The NSD listener is stopped when the flow
     * collector is cancelled.
     */
    private fun toHostInfo(serviceInfo: NsdServiceInfo): HostInfo? {
        val resolved: List<java.net.InetAddress> = if (android.os.Build.VERSION.SDK_INT >= 34) {
            serviceInfo.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(serviceInfo.host)
        }
        val addresses = resolved.filterIsInstance<java.net.Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { it.isNotEmpty() && !it.startsWith("127.") }
            .distinct()
        if (addresses.isEmpty()) return null
        val txt = try {
            serviceInfo.attributes.mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) } }
        } catch (_: Exception) {
            emptyMap()
        }
        val hostId = txt["id"]?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: ""
        return HostInfo(
            name = serviceInfo.serviceName,
            address = addresses.first(),
            port = serviceInfo.port,
            version = txt["v"]?.toIntOrNull() ?: 0,
            hostId = hostId,
            displayName = txt["name"]?.takeIf { it.isNotBlank() }?.take(64) ?: serviceInfo.serviceName,
            fingerprint = txt["fp"]?.takeIf { it.matches(Regex("[0-9a-f]{64}")) },
            addresses = addresses,
        )
    }

    fun discover(): Flow<DiscoveryEvent> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("enzremote-discovery")
        multicastLock.setReferenceCounted(true)
        multicastLock.acquire()

        // Android before 14 rejects concurrent resolves (FAILURE_ALREADY_ACTIVE),
        // so resolve one service at a time. Each call needs a fresh listener.
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        lateinit var resolveNext: () -> Unit

        fun newResolveListener() = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: error $errorCode")
                synchronized(queue) { resolving = false }
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                synchronized(queue) { resolving = false }
                toHostInfo(serviceInfo)?.let { hostInfo ->
                    Log.i(TAG, "Resolved: ${hostInfo.name} at ${hostInfo.connectAddress}")
                    trySend(DiscoveryEvent.Found(hostInfo))
                }
                resolveNext()
            }
        }

        resolveNext = {
            val next = synchronized(queue) {
                if (resolving) null else queue.removeFirstOrNull()?.also { resolving = true }
            }
            if (next != null) {
                try {
                    @Suppress("DEPRECATION")  // resolveService deprecated API 34+; we support API 26+
                    nsdManager.resolveService(next, newResolveListener())
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Resolve rejected", e)
                    synchronized(queue) { resolving = false }
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "Discovery started for $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                synchronized(queue) {
                    if (queue.none { it.serviceName == serviceInfo.serviceName }) queue.addLast(serviceInfo)
                }
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                trySend(DiscoveryEvent.Lost(serviceInfo.serviceName))
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                trySend(DiscoveryEvent.Error("Discovery start failed: $errorCode"))
                // Close the flow — no events will arrive since discovery failed to start.
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery stop failed: $errorCode")
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            Log.i(TAG, "Stopping discovery")
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: IllegalArgumentException) {
                // Already stopped
            }
            if (multicastLock.isHeld) {
                multicastLock.release()
            }
        }
    }
}
