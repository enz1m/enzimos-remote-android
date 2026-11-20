package com.enzimos.remote.signaling

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Reports connectivity and transport changes for the caller’s reconnect policy. */
class NetworkMonitor(context: Context) {

    // Use applicationContext to avoid holding an Activity reference.
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "NetworkMonitor"

        /**
         * Determine transport type from NetworkCapabilities.
         * Public for testing.
         */
        fun transportFromCapabilities(capabilities: NetworkCapabilities): TransportType {
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportType.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportType.ETHERNET
                else -> TransportType.OTHER
            }
        }
    }

    /** Network transport type. */
    enum class TransportType {
        WIFI,
        CELLULAR,
        ETHERNET,
        OTHER,
        NONE,
    }

    /** Network events emitted by the monitor. */
    sealed class NetworkEvent {
        data class Available(val transport: TransportType) : NetworkEvent()
        data object Lost : NetworkEvent()
        data class CapabilitiesChanged(
            val transport: TransportType,
            val downstreamBandwidthKbps: Int,
            val upstreamBandwidthKbps: Int,
        ) : NetworkEvent()
    }

    /**
     * Observe network changes as a cold Flow.
     *
     * The flow registers a network callback while active and
     * unregisters on cancellation.
     */
    fun observe(): Flow<NetworkEvent> = callbackFlow {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val transport = getTransportType(connectivityManager, network)
                Log.i(TAG, "Network available: $transport")
                trySend(NetworkEvent.Available(transport))
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost")
                trySend(NetworkEvent.Lost)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val transport = transportFromCapabilities(capabilities)
                val downstream = capabilities.linkDownstreamBandwidthKbps
                val upstream = capabilities.linkUpstreamBandwidthKbps
                trySend(NetworkEvent.CapabilitiesChanged(transport, downstream, upstream))
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose {
            Log.i(TAG, "Stopping network monitor")
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    /**
     * Get the current network transport type.
     */
    fun getCurrentTransport(): TransportType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return TransportType.NONE
        return getTransportType(cm, network)
    }

    /**
     * Check if currently on a metered connection (cellular, etc.).
     */
    fun isMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Suggest a maximum bitrate based on current network conditions.
     *
     * @param maxBitrateKbps The configured maximum bitrate
     * @return Suggested bitrate (may be lower on metered connections)
     */
    fun suggestBitrate(maxBitrateKbps: Int): Int {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return maxBitrateKbps / 4

        val transport = transportFromCapabilities(capabilities)
        val downstream = capabilities.linkDownstreamBandwidthKbps

        return when (transport) {
            TransportType.WIFI -> maxBitrateKbps
            TransportType.ETHERNET -> maxBitrateKbps
            TransportType.CELLULAR -> {
                // Conservative: use 50% of available bandwidth, capped at max
                val suggested = (downstream * 0.5).toInt()
                suggested.coerceIn(200, maxBitrateKbps)
            }
            TransportType.OTHER -> maxBitrateKbps / 2
            TransportType.NONE -> 200 // Minimum
        }
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private fun getTransportType(cm: ConnectivityManager, network: Network): TransportType {
        val capabilities = cm.getNetworkCapabilities(network) ?: return TransportType.NONE
        return transportFromCapabilities(capabilities)
    }
}
