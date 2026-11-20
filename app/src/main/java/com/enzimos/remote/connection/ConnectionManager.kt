package com.enzimos.remote.connection

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import com.enzimos.remote.pairing.DeviceStore
import com.enzimos.remote.signaling.AppIconsRequest
import com.enzimos.remote.signaling.AppListRequest
import com.enzimos.remote.signaling.CatalogApp
import com.enzimos.remote.signaling.ComputerInfo
import com.enzimos.remote.signaling.ComputerInfoRequest
import com.enzimos.remote.signaling.Discovery
import com.enzimos.remote.signaling.HostMessage
import com.enzimos.remote.signaling.RetryPolicy
import com.enzimos.remote.signaling.SessionInfoItem
import com.enzimos.remote.signaling.SignalingClient
import com.enzimos.remote.signaling.UnpairMessage
import com.enzimos.remote.signaling.encodeClientMessage
import com.enzimos.remote.signaling.protocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Shares one authenticated link per computer and keeps it open while a stream holds a lease. */
class ConnectionManager private constructor(private val app: Application) {
    /** Hosts that stream H.265 (from their computer info). */
    private val codecPrefs = app.getSharedPreferences("host_codecs", android.content.Context.MODE_PRIVATE)

    enum class Status { OFFLINE, CONNECTING, ONLINE, NEEDS_PAIRING }

    /** Observable state of one paired computer. */
    data class HostState(
        val host: DeviceStore.PairedHost,
        val status: Status = Status.OFFLINE,
        val info: ComputerInfo? = null,
        /** Installed apps (cached until the first live `app_list_result`). */
        val catalog: List<CatalogApp> = emptyList(),
        val catalogRevision: String? = null,
        /** Favorite app IDs, in the order they were added. */
        val favorites: List<String> = emptyList(),
        /** Bumped when new icons arrive, to redraw tiles. */
        val iconsVersion: Int = 0,
        val sessions: List<SessionInfoItem> = emptyList(),
        val sessionsLoaded: Boolean = false,
        val nearby: Boolean = false,
        /** Address of the live connection ("host:port"), when online. */
        val address: String? = null,
    ) {
        val hostId: String get() = host.hostId
        val name: String get() = host.name
        /** Catalog, or the legacy ICED list from hosts without `app_list`. */
        val apps: List<CatalogApp>
            get() = catalog.ifEmpty {
                info?.apps.orEmpty().filter { it.available }
                    .map { CatalogApp(id = it.id, name = it.name, description = it.description, kind = "iced") }
            }

        fun app(id: String?): CatalogApp? = id?.let { wanted -> apps.firstOrNull { it.id == wanted } }

        /** App a session belongs to: host-reported `app_id`, else the ID prefix. */
        fun appFor(session: SessionInfoItem): CatalogApp? =
            app(session.appId) ?: apps.filter { session.sessionId.startsWith("${it.id}-") }.maxByOrNull { it.id.length }
    }

    inner class HostConnection(initial: DeviceStore.PairedHost) {
        val hostId = initial.hostId
        val client = SignalingClient(
            deviceId = store.getDeviceId(),
            token = initial.token,
            retryPolicy = RetryPolicy(baseDelayMs = 500, maxDelayMs = 8_000, factor = 1.8, maxAttempts = 0),
            pin = initial.fingerprint,
        ).also { it.offerH265 = codecPrefs.getBoolean(initial.hostId, false) }
        private var host = initial
        private val jobs = mutableListOf<Job>()
        private var pollJob: Job? = null

        init {
            client.onAuthenticated = { ack, address ->
                val ackId = ack.hostId
                if (ackId != null && hostId.startsWith("legacy:")) {
                    store.rekey(hostId, ackId, ack.hostName)
                    scope.launch { reloadHosts() }
                } else {
                    if (host.fingerprint == null && client.observedFingerprint != null) {
                        // Trust-on-first-use for pre-pinning pairings.
                        host = host.copy(fingerprint = client.observedFingerprint)
                        store.saveHost(host)
                        client.pin = host.fingerprint
                    }
                    // Keep the name as paired or renamed by the user.
                    store.recordConnected(hostId, address, null)
                }
            }
            jobs += scope.launch {
                client.state.collect { state ->
                    val status = when (state) {
                        SignalingClient.State.CONNECTED -> Status.ONLINE
                        // Keep retrying quietly, but after two failed rounds
                        // (~1.5 s) tell the user it is offline.
                        SignalingClient.State.CONNECTING,
                        SignalingClient.State.AUTHENTICATING,
                        SignalingClient.State.RECONNECTING ->
                            if (client.currentReconnectAttempt() >= 2) Status.OFFLINE else Status.CONNECTING
                        SignalingClient.State.DISCONNECTED ->
                            if (client.lastReject.value?.notPaired == true) Status.NEEDS_PAIRING else Status.OFFLINE
                    }
                    if (status == Status.NEEDS_PAIRING) store.markNeedsPairing(hostId, true)
                    updateState(hostId) { it.copy(status = status, address = client.connectedAddress.takeIf { status == Status.ONLINE }) }
                    if (status == Status.ONLINE) {
                        refresh()
                        com.enzimos.remote.share.ShareManager.get(app).onConnected(client)
                        com.enzimos.remote.phone.PhoneFeatures.get(app).onConnected(client)
                        com.enzimos.remote.phone.SmsBridge.get(app).onConnected(client)
                    }
                    syncPolling()
                }
            }
            jobs += scope.launch {
                client.messages.collect { message ->
                    when (message) {
                        is HostMessage.ComputerInfoResult -> {
                            val info = message.msg.computer
                            updateState(hostId) { it.copy(info = info) }
                            // Learned for the next connection's hello.
                            val h265 = "h265" in info.videoCodecs
                            if (h265 != client.offerH265) {
                                client.offerH265 = h265
                                codecPrefs.edit().putBoolean(hostId, h265).apply()
                            }
                            val revision = info.appRevision
                            if (revision != null && revision != state(hostId)?.catalogRevision) requestCatalog()
                        }
                        is HostMessage.AppListResult -> {
                            catalogRequested = false
                            val result = message.msg
                            updateState(hostId) { it.copy(catalog = result.apps, catalogRevision = result.revision) }
                            store.saveCatalog(hostId, protocolJson.encodeToString(CachedCatalog.serializer(), CachedCatalog(result.revision, result.apps)))
                            requestMissingIcons(result.apps)
                        }
                        is HostMessage.AppIconsResult -> scope.launch {
                            var stored = 0
                            for (icon in message.msg.icons) if (icons.store(icon.icon, icon.png)) stored++
                            if (stored > 0) updateState(hostId) { it.copy(iconsVersion = it.iconsVersion + 1) }
                        }
                        is HostMessage.SessionListResult ->
                            updateState(hostId) { it.copy(sessions = message.msg.sessions, sessionsLoaded = true) }
                        is HostMessage.SessionEvent, is HostMessage.SessionStopped,
                        is HostMessage.SessionCreated, is HostMessage.StreamStopped -> client.listSessions()
                        is HostMessage.File, is HostMessage.Clipboard ->
                            com.enzimos.remote.share.ShareManager.get(app).onMessage(hostId, message)
                        is HostMessage.Uplink -> com.enzimos.remote.uplink.UplinkService.onMessage(message)
                        is HostMessage.SmsRequest -> com.enzimos.remote.phone.SmsBridge.get(app).handle(hostId, message.msg)
                        is HostMessage.NotificationAction, is HostMessage.NotificationDismiss, is HostMessage.Ring ->
                            com.enzimos.remote.phone.PhoneFeatures.get(app).onMessage(message)
                        is HostMessage.Unpaired -> {
                            store.markNeedsPairing(hostId, true)
                            updateState(hostId) { it.copy(status = Status.NEEDS_PAIRING) }
                        }
                        else -> Unit
                    }
                }
            }
        }

        private var catalogRequested = false

        private fun requestCatalog() {
            if (catalogRequested || client.state.value != SignalingClient.State.CONNECTED) return
            catalogRequested = true
            client.sendRaw(encodeClientMessage(AppListRequest()))
        }

        /** Fetch icons not cached yet, 32 per request (rate limit friendly). */
        private fun requestMissingIcons(apps: List<CatalogApp>) {
            val missing = apps.filter { app -> app.icon?.let { !icons.has(it) } ?: false }.map { it.id }
            for (chunk in missing.chunked(32)) client.sendRaw(encodeClientMessage(AppIconsRequest(appIds = chunk)))
        }

        fun update(record: DeviceStore.PairedHost) {
            val credentialsChanged = record.token != host.token || record.fingerprint != host.fingerprint
            host = record
            client.updateAddresses(record.addresses)
            if (credentialsChanged) {
                client.token = record.token
                client.pin = record.fingerprint
                client.disconnect()
                if (active) start()
            }
        }

        fun start() {
            if (host.needsPairing || host.addresses.isEmpty()) return
            if (client.state.value == SignalingClient.State.DISCONNECTED) {
                client.connect(host.addresses)
            } else {
                client.reconnectNow()
            }
        }

        fun stop() {
            pollJob?.cancel(); pollJob = null
            client.disconnect()
        }

        /** Ask for fresh resources/catalog and sessions now. */
        fun refresh() {
            if (client.state.value != SignalingClient.State.CONNECTED) return
            client.sendRaw(encodeClientMessage(ComputerInfoRequest()))
            client.listSessions()
            if (state(hostId)?.catalogRevision == null) requestCatalog()
        }

        fun syncPolling() {
            val wanted = hostId in pollers && client.state.value == SignalingClient.State.CONNECTED
            if (wanted && pollJob?.isActive != true) {
                pollJob = scope.launch {
                    while (true) {
                        refresh()
                        delay(POLL_INTERVAL_MS)
                    }
                }
            } else if (!wanted) {
                pollJob?.cancel(); pollJob = null
            }
        }

        fun destroy() {
            jobs.forEach { it.cancel() }
            pollJob?.cancel()
            client.destroy()
        }
    }

    private val store = DeviceStore(app)
    private val icons = com.enzimos.remote.ui.IconCache.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connections = mutableMapOf<String, HostConnection>()
    private val pollers = mutableSetOf<String>()

    private val _hosts = MutableStateFlow<List<HostState>>(emptyList())
    /** Paired computers in recency order. */
    val hosts: StateFlow<List<HostState>> = _hosts.asStateFlow()

    private val _nearby = MutableStateFlow<List<Discovery.HostInfo>>(emptyList())
    /** Every computer discovered on the LAN (paired or not). */
    val nearby: StateFlow<List<Discovery.HostInfo>> = _nearby.asStateFlow()

    private var startedActivities = 0
    private var leases = 0
    private var active = false
    private var backgroundJob: Job? = null
    private var backgroundSince = 0L
    private var discoveryJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        reloadHosts()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) onBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun connection(hostId: String): HostConnection? = connections[hostId]

    fun state(hostId: String): HostState? = _hosts.value.firstOrNull { it.hostId == hostId }

    /** Re-read paired computers (after pairing, forgetting, renaming). */
    fun reloadHosts() {
        val records = store.listPairedHosts()
        val ids = records.map { it.hostId }.toSet()
        connections.keys.filter { it !in ids }.forEach { connections.remove(it)?.destroy() }
        for (record in records) {
            val existing = connections[record.hostId]
            if (existing == null) {
                connections[record.hostId] = HostConnection(record)
                if (active) connections[record.hostId]?.start()
            } else {
                existing.update(record)
            }
        }
        val previous = _hosts.value.associateBy { it.hostId }
        _hosts.value = records.map { record ->
            val old = previous[record.hostId]
            val status = when {
                record.needsPairing -> Status.NEEDS_PAIRING
                old != null && old.status != Status.NEEDS_PAIRING -> old.status
                else -> Status.OFFLINE
            }
            val cached = if (old == null) cachedCatalog(record.hostId) else null
            (old ?: HostState(record, catalog = cached?.apps.orEmpty())).copy(
                host = record,
                status = status,
                nearby = _nearby.value.any { it.hostId == record.hostId },
                favorites = store.favorites(record.hostId),
            )
        }
    }

    /** Store a newly paired computer and connect to it immediately. */
    fun addPaired(host: DeviceStore.PairedHost) {
        val existing = store.getHost(host.hostId)
        store.saveHost(
            host.copy(addresses = (host.addresses + (existing?.addresses ?: emptyList())).distinct()),
        )
        reloadHosts()
        connections[host.hostId]?.start()
    }

    /** Forget a computer here and, if reachable, revoke this phone on it. */
    fun forget(hostId: String) {
        val connection = connections[hostId]
        if (connection?.client?.state?.value == SignalingClient.State.CONNECTED) {
            connection.client.sendRaw(encodeClientMessage(UnpairMessage()))
        }
        scope.launch {
            // Give the unpair message a moment to leave before closing.
            delay(300)
            store.deleteHost(hostId)
            com.enzimos.remote.AppStreamSettingsStore(app).forget(hostId)
            reloadHosts()
        }
    }

    fun rename(hostId: String, name: String) {
        store.rename(hostId, name)
        reloadHosts()
    }

    /** Keep resources/sessions fresh for a visible home screen. */
    fun setPolling(hostId: String, enabled: Boolean) {
        if (enabled) pollers += hostId else pollers -= hostId
        connections[hostId]?.syncPolling()
    }

    /** User asked to retry, or something suggests the network changed. */
    fun reconnectAll() = connections.values.forEach { it.start() }

    /** Keep connections alive in the background (an active stream). */
    fun acquireLease() {
        leases++
        backgroundJob?.cancel()
    }

    fun releaseLease() {
        leases = (leases - 1).coerceAtLeast(0)
        if (leases == 0 && startedActivities == 0) onBackground()
    }

    /** Wait briefly for any computer to come online (launch selection). */
    suspend fun awaitAnyOnline(timeoutMs: Long): HostState? = withTimeoutOrNull(timeoutMs) {
        hosts.first { list -> list.any { it.status == Status.ONLINE } }.first { it.status == Status.ONLINE }
    }

    /**
     * Keep computer links up without any screen (notification mirroring, find
     * my phone): the notification listener keeps the process alive.
     */
    fun startBackgroundLink() {
        acquireLease()
        if (!active) {
            active = true
            registerNetworkCallback()
        }
        reconnectAll()
    }

    fun stopBackgroundLink() = releaseLease()

    private fun onForeground() {
        backgroundJob?.cancel()
        val wasAway = backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > 5_000
        backgroundSince = 0
        connections.values.forEach { it.client.pingIntervalMs = SignalingClient.PING_INTERVAL_MS }
        if (!active) {
            active = true
            registerNetworkCallback()
        }
        startDiscovery()
        if (wasAway) connections.values.forEach { it.client.verifyAlive() }
        reconnectAll()
    }

    private fun onBackground() {
        if (backgroundSince == 0L) backgroundSince = System.currentTimeMillis()
        stopDiscovery()
        connections.values.forEach { it.client.pingIntervalMs = SignalingClient.BACKGROUND_PING_INTERVAL_MS }
        if (leases > 0) return
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(BACKGROUND_GRACE_MS)
            if (startedActivities == 0 && leases == 0) {
                Log.i(TAG, "App idle in background; closing computer connections")
                active = false
                unregisterNetworkCallback()
                connections.values.forEach { it.stop() }
            }
        }
    }

    private fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            Discovery(app).discover().collect { event ->
                when (event) {
                    is Discovery.DiscoveryEvent.Found -> onDiscovered(event.host)
                    is Discovery.DiscoveryEvent.Lost -> {
                        _nearby.update { list -> list.filterNot { it.name == event.name } }
                        refreshNearbyFlags()
                    }
                    is Discovery.DiscoveryEvent.Error -> Log.w(TAG, "Discovery: ${event.message}")
                }
            }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    private fun onDiscovered(found: Discovery.HostInfo) {
        // The same service resolves once per interface: merge its addresses.
        _nearby.update { list ->
            val previous = list.firstOrNull { it.name == found.name }
            val merged = if (previous == null) found else found.copy(addresses = (found.addresses + previous.addresses).distinct())
            list.filterNot { it.name == found.name } + merged
        }
        val info = _nearby.value.first { it.name == found.name }
        if (info.hostId.isNotEmpty() && connections.containsKey(info.hostId)) {
            store.addAddressHints(info.hostId, info.connectAddresses)
            val record = store.getHost(info.hostId) ?: return
            val connection = connections[info.hostId] ?: return
            // Discovered addresses first: they are known reachable right now.
            connection.client.updateAddresses((info.connectAddresses + record.addresses).distinct())
            if (connection.client.state.value != SignalingClient.State.CONNECTED) connection.start()
        }
        refreshNearbyFlags()
    }

    private fun refreshNearbyFlags() {
        val near = _nearby.value.map { it.hostId }.toSet()
        _hosts.update { list -> list.map { it.copy(nearby = it.hostId in near) } }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    // Let routes settle, then retry offline computers at once.
                    delay(400)
                    reconnectAll()
                }
            }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Network callback unavailable", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    /** Revision is not restored: the cache only bridges until a live list arrives. */
    private fun cachedCatalog(hostId: String): CachedCatalog? = try {
        store.cachedCatalog(hostId)?.let { protocolJson.decodeFromString(CachedCatalog.serializer(), it) }
    } catch (_: Exception) {
        null
    }

    fun setFavorite(hostId: String, appId: String, favorite: Boolean) {
        store.setFavorite(hostId, appId, favorite)
        updateState(hostId) { it.copy(favorites = store.favorites(hostId)) }
    }

    @kotlinx.serialization.Serializable
    data class CachedCatalog(val revision: String, val apps: List<CatalogApp>)

    private fun updateState(hostId: String, transform: (HostState) -> HostState) {
        _hosts.update { list -> list.map { if (it.hostId == hostId) transform(it) else it } }
    }

    companion object {
        private const val TAG = "ConnectionManager"
        private const val POLL_INTERVAL_MS = 3_000L
        /**
         * Keep links up this long in the background for quick app switches.
         * Android 15+ blocks network for backgrounded apps without a
         * foreground service, so longer would only retry in vain; returning
         * to the app reconnects in well under a second. A running stream holds
         * a lease (its foreground service keeps network access).
         */
        const val BACKGROUND_GRACE_MS = 60_000L

        @Volatile
        private var instance: ConnectionManager? = null

        /** Process-wide instance. Rebinds if the Application changes (unit tests only). */
        fun init(app: Application): ConnectionManager =
            instance?.takeIf { it.app === app } ?: synchronized(this) {
                instance?.takeIf { it.app === app } ?: ConnectionManager(app).also { instance = it }
            }

        fun get(context: Context): ConnectionManager = init(context.applicationContext as Application)
    }
}
