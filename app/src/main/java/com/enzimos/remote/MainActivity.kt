package com.enzimos.remote

import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.connection.ConnectionManager.HostState
import com.enzimos.remote.connection.ConnectionManager.Status
import com.enzimos.remote.databinding.ActivityMainBinding
import com.enzimos.remote.databinding.ItemGaugeBinding
import com.enzimos.remote.databinding.ItemHomeResourcesBinding
import com.enzimos.remote.databinding.SheetComputersBinding
import com.enzimos.remote.pairing.DeviceStore
import com.enzimos.remote.settings.Preferences
import com.enzimos.remote.signaling.HostMessage
import com.enzimos.remote.signaling.CatalogApp
import com.enzimos.remote.signaling.SessionInfoItem
import com.enzimos.remote.signaling.SignalingClient
import com.enzimos.remote.ui.ComputerRow
import com.enzimos.remote.ui.ComputerRowAdapter
import com.enzimos.remote.ui.HomeAdapter
import com.enzimos.remote.ui.HomeItem
import com.enzimos.remote.ui.HostUi
import com.enzimos.remote.ui.PairingFlow
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Home: the selected computer, already connected. Shows running apps, the
 * installed app catalog and live resources. A header switcher lists every
 * paired computer with its live status. First run shows a welcome screen.
 *
 * Connecting never starts video; choosing an app opens [StreamActivity].
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var manager: ConnectionManager
    private lateinit var preferences: Preferences
    private lateinit var store: DeviceStore
    private lateinit var pairing: PairingFlow
    private lateinit var homeAdapter: HomeAdapter
    private lateinit var iconCache: com.enzimos.remote.ui.IconCache
    private var searchQuery = ""
    private var selectedId: String? = null
    // Bind asynchronous consent/pickers to the computer chosen when they open.
    private var filesForHost: String? = null
    private var screenForHost: String? = null
    private var cameraForHost: String? = null
    private var current: HostState? = null
    private var hosts: List<HostState> = emptyList()
    private var pendingClose: String? = null
    private var messagesJob: Job? = null
    private var messagesFor: String? = null
    private var sheet: BottomSheetDialog? = null
    private var sheetAdapter: ComputerRowAdapter? = null
    private var openingStream = false

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val hostId = filesForHost.also { filesForHost = null } ?: return@registerForActivityResult
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val name = manager.state(hostId)?.name.orEmpty()
        val started = com.enzimos.remote.share.ShareManager.get(this).sendFiles(hostId, uris)
        android.widget.Toast.makeText(this, resources.getQuantityString(R.plurals.share_files_sending, started, started, name),
            android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun sendClipboard() {
        val hostId = selectedId ?: return
        val sent = com.enzimos.remote.share.ShareManager.get(this).sendClipboardNow(hostId)
        val message = if (sent) getString(R.string.home_clipboard_sent, manager.state(hostId)?.name.orEmpty())
            else getString(R.string.home_clipboard_empty)
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the focused app read the clipboard.
        if (hasFocus) com.enzimos.remote.share.ShareManager.get(this).syncClipboardFromPhone()
    }

    private val addLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(AddComputerActivity.EXTRA_PAIRED_HOST_ID)?.let { select(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        manager = ConnectionManager.get(this)
        preferences = Preferences(this)
        store = DeviceStore(this)
        pairing = PairingFlow(this) { select(it.hostId) }
        selectedId = savedInstanceState?.getString("selected") ?: preferences.selectedHostId
        filesForHost = savedInstanceState?.getString("files_for_host")
        screenForHost = savedInstanceState?.getString("screen_for_host")
        cameraForHost = savedInstanceState?.getString("camera_for_host")

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.list.updatePadding(left = bars.left + dp(12), right = bars.right + dp(12), bottom = bars.bottom + dp(24))
            binding.welcomeContent.updatePadding(top = bars.top + dp(32), bottom = bars.bottom + dp(32))
            insets
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add_computer -> { openAddComputer(); true }
                R.id.action_settings -> { startActivity(Intent(this, com.enzimos.remote.settings.SettingsActivity::class.java)); true }
                else -> false
            }
        }
        binding.switcher.setOnClickListener { showComputerSheet() }
        binding.welcomeAdd.setOnClickListener { openAddComputer() }

        iconCache = com.enzimos.remote.ui.IconCache.get(this)
        homeAdapter = HomeAdapter(
            icons = ::bindIcon,
            onApp = { launch(it) },
            onAppLongPress = ::showAppMenu,
            onResume = { item ->
                val desktop = item.session.sessionId.startsWith("desktop-")
                openStream(item.session.sessionId, item.app?.id.orEmpty(), item.name, item.session.width, item.session.height,
                    item.session.scale, resume = true, desktop = desktop)
            },
            onClose = { confirmClose(it.session.sessionId, it.name) },
            onDesktop = { openDesktop() },
            bindResources = ::bindResources,
            onSendFiles = { selectedId?.let { filesForHost = it; pickFiles.launch(arrayOf("*/*")) } },
            onSendClipboard = ::sendClipboard,
            onSecondDisplay = ::openSecondDisplay,
            onMirror = ::toggleMirror,
            onWebcam = ::toggleWebcam,
            onOpenRemote = { selectedId?.let { startActivity(com.enzimos.remote.remote.RemoteControlActivity.intent(this, it)) } },
        )
        val columns = ((resources.configuration.screenWidthDp - 24) / 84).coerceIn(4, 8)
        homeAdapter.spanLookup.columns = columns
        binding.list.layoutManager = GridLayoutManager(this, columns).apply { spanSizeLookup = homeAdapter.spanLookup }
        binding.list.adapter = homeAdapter
        binding.list.itemAnimator = null
        setUpSearch()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(manager.hosts, com.enzimos.remote.uplink.UplinkService.active) { hosts, _ -> hosts }
                    .collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        openingStream = false
        selectedId?.let { manager.setPolling(it, true) }
    }

    override fun onStop() {
        selectedId?.let { manager.setPolling(it, false) }
        super.onStop()
    }

    override fun onDestroy() {
        pairing.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected", selectedId)
        outState.putString("files_for_host", filesForHost)
        outState.putString("screen_for_host", screenForHost)
        outState.putString("camera_for_host", cameraForHost)
    }

    // ---------------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------------

    private fun select(hostId: String) {
        if (hostId == selectedId) {
            render(manager.hosts.value)
            return
        }
        selectedId?.let { manager.setPolling(it, false) }
        selectedId = hostId
        preferences.selectedHostId = hostId
        pendingClose = null
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) manager.setPolling(hostId, true)
        render(manager.hosts.value)
        binding.list.scrollToPosition(0)
    }

    private fun render(list: List<HostState>) {
        hosts = list
        renderSheet()
        if (list.isEmpty()) {
            binding.welcome.visibility = View.VISIBLE
            binding.appBar.visibility = View.GONE
            current = null
            return
        }
        binding.welcome.visibility = View.GONE
        binding.appBar.visibility = View.VISIBLE

        var state = list.firstOrNull { it.hostId == selectedId }
            ?: selectedId?.let { store.getHost(it) }?.let { HostState(it) }
        if (state == null) {
            state = list.firstOrNull { it.status == Status.ONLINE } ?: list.first()
            select(state.hostId)
            return
        }
        // Keep the selected computer through an outage. An automatic switch
        // could send the next file, clipboard or input to another computer.
        current = state
        observeMessages(state.hostId)
        renderHeader(state)
        val layout = binding.list.layoutManager as GridLayoutManager
        val atTop = layout.findFirstCompletelyVisibleItemPosition() <= 0
        homeAdapter.submitList(buildItems(state, list)) {
            // New sections (favorites, running) above the grid stay visible.
            if (atTop) binding.list.scrollToPosition(0)
        }
    }

    private fun renderHeader(state: HostState) {
        binding.computerName.text = state.name
        binding.computerStatus.text = HostUi.statusText(this, state, detailed = false)
        HostUi.tintDot(binding.statusDot, state.status)
        binding.connectingProgress.visibility =
            if (state.status == Status.CONNECTING) View.VISIBLE else View.INVISIBLE
        binding.switcher.contentDescription =
            "${state.name}, ${binding.computerStatus.text}. ${getString(R.string.home_switch_computer)}"
    }

    /** Launcher sections: status, favorites, running, all apps (or search results), desktop, resources. */
    private fun buildItems(state: HostState, list: List<HostState>): List<HomeItem> {
        val items = mutableListOf<HomeItem>()
        statusItem(state, list)?.let(items::add)
        val online = state.status == Status.ONLINE
        if (searchQuery.isBlank()) {
            desktopItem(state)?.let(items::add)
            val active = com.enzimos.remote.uplink.UplinkService.active.value?.takeIf { it.hostId == state.hostId }
            items += HomeItem.Actions(
                enabled = online,
                secondDisplay = state.info?.secondDisplay == true,
                uplink = state.info?.uplink == true,
                active = active?.kind?.wire,
            )
            state.info.takeIf { online }?.let { items += HomeItem.Resources(it, cpuHistory(state.hostId, it)) }
        }
        val sessions = if (online) mySessions(state) else emptyList()
        val runningIds = sessions.mapNotNull { state.appFor(it)?.id }.toSet()
        fun tile(app: CatalogApp, section: String) = HomeItem.App(
            app, section, favorite = app.id in state.favorites, running = app.id in runningIds,
            enabled = online, iconsVersion = state.iconsVersion,
        )
        val query = searchQuery.trim()
        if (query.isNotEmpty()) {
            val matches = state.apps.filter { it.matches(query) }
                .sortedBy { if (it.name.startsWith(query, ignoreCase = true)) 0 else 1 }
            items += HomeItem.Section(getString(R.string.home_search_results), matches.size.toString())
            items += matches.map { tile(it, "search") }
            if (matches.isEmpty()) items += HomeItem.Note(getString(R.string.home_no_results, query))
            return items
        }
        val favorites = state.favorites.mapNotNull { state.app(it) }
        if (favorites.isNotEmpty()) {
            items += HomeItem.Section(getString(R.string.home_favorites))
            items += favorites.map { tile(it, "favorites") }
        }
        if (sessions.isNotEmpty()) {
            items += HomeItem.Section(getString(R.string.home_running))
            val shared = state.info?.sharedDesktop == true
            items += sessions.map { session ->
                val app = state.appFor(session)
                val desktop = session.sessionId.startsWith("desktop-")
                HomeItem.Running(
                    session, app,
                    name = if (desktop) getString(R.string.desktop_title) else app?.name ?: session.sessionId,
                    closable = !shared,
                )
            }
        }
        if (state.apps.isNotEmpty()) {
            items += HomeItem.Section(getString(R.string.home_all_apps), state.apps.size.toString())
            items += state.apps.map { tile(it, "all") }
        } else if (state.info != null) {
            items += HomeItem.Note(getString(R.string.home_apps_empty))
        } else if (online) {
            items += HomeItem.Note(getString(R.string.home_loading_apps))
        }
        return items
    }

    /** The whole desktop: the shared enzUI desktop, or a monitor shown on the phone (GNOME). */
    private fun desktopItem(state: HostState): HomeItem? {
        val info = state.info ?: return null
        if (state.status != Status.ONLINE) return null
        if (info.sharedDesktop) return HomeItem.Desktop(getString(R.string.desktop_hint))
        val main = info.monitors.firstOrNull() ?: return null
        val name = main.name.ifBlank { main.connector }
        val detail = getString(R.string.desktop_view_detail, name, main.width, main.height)
        return HomeItem.Desktop(if (info.monitors.size > 1) getString(R.string.desktop_view_more, detail, info.monitors.size - 1) else detail)
    }

    /** Recent processor load per computer, one sample per new `computer_info`. */
    private val cpuSamples = HashMap<String, Pair<com.enzimos.remote.signaling.ComputerInfo, List<Float>>>()

    private fun cpuHistory(hostId: String, info: com.enzimos.remote.signaling.ComputerInfo): List<Float> {
        val previous = cpuSamples[hostId]
        if (previous != null && previous.first === info) return previous.second
        val sample = info.cpuUsagePercent?.let { (it / 100.0).toFloat() } ?: return previous?.second.orEmpty()
        val history = (previous?.second.orEmpty() + sample).takeLast(CPU_HISTORY)
        cpuSamples[hostId] = info to history
        return history
    }

    private fun statusItem(state: HostState, list: List<HostState>): HomeItem? {
        if (state.status == Status.ONLINE || state.status == Status.CONNECTING) return null
        val alternative = list.firstOrNull { it.hostId != state.hostId && it.status == Status.ONLINE }
        return HomeItem.Status(token = state.status to alternative?.hostId, bind = { card ->
            if (state.status == Status.NEEDS_PAIRING) {
                card.statusIcon.setImageResource(R.drawable.ic_phonelink)
                card.statusTitle.text = getString(R.string.home_needs_pairing_title, state.name)
                card.statusBody.setText(R.string.home_needs_pairing_body)
                card.statusAction.setText(R.string.home_pair_again)
                card.statusAction.setOnClickListener { repair(state) }
            } else {
                card.statusIcon.setImageResource(R.drawable.ic_link_off)
                card.statusTitle.text = getString(R.string.home_offline_title, state.name)
                card.statusBody.setText(R.string.home_offline_body)
                card.statusAction.setText(R.string.home_try_now)
                card.statusAction.setOnClickListener {
                    manager.connection(state.hostId)?.start()
                    Snackbar.make(binding.root, R.string.home_status_connecting, Snackbar.LENGTH_SHORT).show()
                }
            }
            if (alternative != null) {
                card.statusSecondary.visibility = View.VISIBLE
                card.statusSecondary.text = getString(R.string.home_switch_to) + " · " + alternative.name
                card.statusSecondary.setOnClickListener { select(alternative.hostId) }
            } else {
                card.statusSecondary.visibility = View.GONE
            }
        })
    }

    private fun mySessions(state: HostState): List<SessionInfoItem> {
        val me = store.getDeviceId()
        return state.sessions.filter { it.active && (it.ownerDeviceId.isEmpty() || it.ownerDeviceId == me) }
    }

    private fun bindResources(card: ItemHomeResourcesBinding, item: HomeItem.Resources) {
        val info = item.info
        card.systemSummary.text = listOf(info.os, HostUi.cpuName(info.cpuModel), getString(R.string.home_uptime, HostUi.duration(info.uptimeSecs)))
            .filter { it.isNotBlank() }.joinToString(" · ")
        val cpu = info.cpuUsagePercent
        gauge(card.cpuGauge, R.string.home_processor, cpu?.let { (it / 100).toFloat() },
            if (info.cpuThreads > 0) resources.getQuantityString(R.plurals.home_threads, info.cpuThreads, info.cpuThreads) else "")
        val memUsed = (info.memoryTotalBytes - info.memoryAvailableBytes).coerceAtLeast(0)
        gauge(card.memoryGauge, R.string.home_memory, fraction(memUsed, info.memoryTotalBytes),
            getString(R.string.home_memory_short, HostUi.bytes(memUsed).removeSuffix(" GB"), HostUi.bytes(info.memoryTotalBytes)))
        val diskUsed = info.diskTotalBytes - info.diskAvailableBytes
        gauge(card.diskGauge, R.string.home_storage, fraction(diskUsed, info.diskTotalBytes),
            getString(R.string.home_storage_short, HostUi.bytes(info.diskAvailableBytes)))
        card.historyRow.isVisible = item.cpuHistory.size >= 2
        card.cpuHistory.capacity = CPU_HISTORY
        card.cpuHistory.setValues(item.cpuHistory)
        val rx = info.netRxBytesPerSec
        val tx = info.netTxBytesPerSec
        card.network.isVisible = rx != null && tx != null
        if (rx != null && tx != null) {
            card.network.text = getString(R.string.home_network, HostUi.rate(rx), HostUi.rate(tx))
        }
    }

    private fun gauge(gauge: ItemGaugeBinding, label: Int, value: Float?, detail: String) {
        gauge.label.setText(label)
        gauge.detail.text = detail
        gauge.ring.setFraction(value)
        gauge.root.contentDescription = buildString {
            append(getString(label)).append(": ")
            append(value?.let { "${(it * 100).toInt()}%" } ?: "—")
            if (detail.isNotEmpty()) append(", ").append(detail)
        }
    }

    private fun fraction(used: Long, total: Long): Float? = if (total > 0) (used.toDouble() / total).toFloat() else null

    /** Computer icon if cached, else a glyph; loads from disk without blocking. */
    private fun bindIcon(app: CatalogApp, view: android.widget.ImageView) {
        val hash = app.icon
        view.tag = hash
        val cached = hash?.let { iconCache.cached(it) }
        if (cached != null) {
            view.imageTintList = null
            view.setImageBitmap(cached)
            return
        }
        view.setImageResource(if (app.phoneReady) HostUi.appIcon(app.id) else R.drawable.ic_app_generic)
        view.imageTintList = android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary),
        )
        hash ?: return
        lifecycleScope.launch {
            val bitmap = iconCache.load(hash) ?: return@launch
            if (view.tag == hash) {
                view.imageTintList = null
                view.setImageBitmap(bitmap)
            }
        }
    }

    private fun showAppMenu(app: CatalogApp, anchor: View) {
        val state = current ?: return
        val favorite = app.id in state.favorites
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.app_open)
            menu.add(0, 2, 1, if (favorite) R.string.app_remove_favorite else R.string.app_add_favorite)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> launch(app)
                    2 -> {
                        manager.setFavorite(state.hostId, app.id, !favorite)
                        if (!favorite) Snackbar.make(binding.root, getString(R.string.favorite_added, app.name), Snackbar.LENGTH_SHORT).show()
                    }
                }
                true
            }
        }.show()
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun setUpSearch() {
        val item = binding.toolbar.menu.findItem(R.id.action_search) ?: return
        val search = item.actionView as? androidx.appcompat.widget.SearchView ?: return
        search.queryHint = getString(R.string.search_apps)
        search.maxWidth = Int.MAX_VALUE
        search.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                // Enter opens the single best match.
                val state = current ?: return true
                val best = state.apps.filter { it.matches(query.orEmpty()) }
                    .minByOrNull { if (it.name.startsWith(query.orEmpty(), ignoreCase = true)) 0 else 1 }
                if (best != null) launch(best)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty()
                render(manager.hosts.value)
                return true
            }
        })
        item.setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: android.view.MenuItem): Boolean {
                binding.switcher.visibility = View.GONE
                return true
            }

            override fun onMenuItemActionCollapse(item: android.view.MenuItem): Boolean {
                binding.switcher.visibility = View.VISIBLE
                searchQuery = ""
                render(manager.hosts.value)
                return true
            }
        })
    }

    // ---------------------------------------------------------------------
    // Computer switcher
    // ---------------------------------------------------------------------

    private fun showComputerSheet() {
        val sheetBinding = SheetComputersBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        val adapter = ComputerRowAdapter(
            onClick = { row ->
                dialog.dismiss()
                select(row.key)
            },
            onMore = { row, anchor -> showComputerMenu(row.key, anchor) },
        )
        sheetBinding.list.layoutManager = LinearLayoutManager(this)
        sheetBinding.list.adapter = adapter
        sheetBinding.add.setOnClickListener {
            dialog.dismiss()
            openAddComputer()
        }
        dialog.setContentView(sheetBinding.root)
        dialog.setOnDismissListener { sheet = null; sheetAdapter = null }
        sheet = dialog
        sheetAdapter = adapter
        renderSheet()
        dialog.show()
    }

    private fun renderSheet() {
        val adapter = sheetAdapter ?: return
        adapter.submitList(hosts.map { state ->
            val subtitle = HostUi.statusText(this, state).let { text ->
                val address = HostUi.displayAddress(state.address)
                if (state.status == Status.ONLINE && address != null) getString(R.string.computer_status_line, text, address) else text
            }
            ComputerRow(
                key = state.hostId,
                title = state.name,
                subtitle = subtitle,
                status = state.status,
                selected = state.hostId == selectedId,
                showMore = true,
            )
        })
    }

    private fun showComputerMenu(hostId: String, anchor: View) {
        val state = hosts.firstOrNull { it.hostId == hostId } ?: return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.rename_computer)
            if (state.status == Status.NEEDS_PAIRING) menu.add(0, 3, 1, R.string.home_pair_again)
            menu.add(0, 2, 2, R.string.action_forget)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> rename(state)
                    2 -> confirmForget(state)
                    3 -> { sheet?.dismiss(); repair(state) }
                }
                true
            }
        }.show()
    }

    private fun rename(state: HostState) {
        val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.computer_name_hint)
        }
        val input = TextInputEditText(layout.context).apply {
            setSingleLine()
            setText(state.name)
            selectAll()
        }
        layout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(layout)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_computer_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                input.text?.toString()?.takeIf { it.isNotBlank() }?.let { manager.rename(state.hostId, it) }
            }
            .show()
        input.requestFocus()
    }

    private fun confirmForget(state: HostState) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.forget_computer, state.name))
            .setMessage(getString(R.string.forget_computer_body, state.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_forget) { _, _ ->
                sheet?.dismiss()
                if (state.hostId == selectedId) {
                    selectedId = null
                    preferences.selectedHostId = null
                }
                manager.forget(state.hostId)
                Snackbar.make(binding.root, getString(R.string.forgot_computer, state.name), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun repair(state: HostState) {
        pairing.start(
            PairingFlow.Target(
                displayName = state.name,
                addresses = state.host.addresses,
                hostId = state.hostId.takeUnless { it.startsWith("legacy:") },
                fingerprint = state.host.fingerprint,
            ),
        )
    }

    private fun openAddComputer() = addLauncher.launch(Intent(this, AddComputerActivity::class.java))

    // ---------------------------------------------------------------------
    // Apps and sessions
    // ---------------------------------------------------------------------

    private fun connectedClient(): SignalingClient? {
        val id = selectedId ?: return null
        return manager.connection(id)?.client?.takeIf { it.state.value == SignalingClient.State.CONNECTED }
    }

    private fun observeMessages(hostId: String) {
        if (messagesFor == hostId && messagesJob?.isActive == true) return
        messagesJob?.cancel()
        messagesFor = hostId
        val client = manager.connection(hostId)?.client ?: return
        messagesJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                client.messages.collect { message ->
                    when (message) {
                        is HostMessage.SessionResumed ->
                            if (pendingClose == message.msg.sessionId) client.stopSession(message.msg.sessionId)
                        is HostMessage.SessionStopped -> if (pendingClose == message.msg.sessionId) pendingClose = null
                        is HostMessage.SessionError -> if (pendingClose != null && !openingStream) {
                            pendingClose = null
                            Snackbar.make(binding.root, message.msg.error, Snackbar.LENGTH_LONG).show()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun confirmClose(sessionId: String, name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.close_named_app, name))
            .setMessage(R.string.close_app_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.close_app) { _, _ ->
                val client = connectedClient() ?: return@setPositiveButton
                pendingClose = sessionId
                // Ownership must be taken before stopping a session created earlier.
                client.resumeSession(sessionId)
            }
            .show()
    }

    private fun launch(app: CatalogApp) {
        val state = current ?: return
        if (state.status != Status.ONLINE) {
            Snackbar.make(binding.root, HostUi.statusText(this, state), Snackbar.LENGTH_SHORT).show()
            return
        }
        val running = mySessions(state).firstOrNull { state.appFor(it)?.id == app.id }
        if (running != null) {
            openStream(running.sessionId, app.id, app.name, running.width, running.height, running.scale, resume = true)
            return
        }
        val dm = resources.displayMetrics
        val viewport = AppViewport.create(
            dm.widthPixels, binding.root.height, dm.density.toDouble(),
            AppViewport.resolutionLimit(preferences.resolution),
        )
        openStream("${app.id}-${System.currentTimeMillis().toString(36)}", app.id, app.name, viewport.width, viewport.height, viewport.scale, resume = false)
    }

    private fun openDesktop() {
        val state = current ?: return
        if (state.status != Status.ONLINE) return
        val monitors = state.info?.monitors.orEmpty()
        if (state.info?.sharedDesktop != true) {
            when (monitors.size) {
                0 -> return
                1 -> openDesktopView(state, null)
                else -> com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.desktop_view_pick)
                    .setItems(monitors.map { monitor ->
                        val name = monitor.name.ifBlank { monitor.connector }
                        val size = "${monitor.width} × ${monitor.height}"
                        if (monitor.primary) "$name · $size · ${getString(R.string.desktop_view_main)}" else "$name · $size"
                    }.toTypedArray()) { _, which -> openDesktopView(state, monitors[which].connector) }
                    .show()
            }
            return
        }
        val running = mySessions(state).firstOrNull { it.sessionId.startsWith("desktop-") }
        openStream(
            running?.sessionId ?: "desktop-${System.currentTimeMillis().toString(36)}", "", getString(R.string.desktop_title),
            running?.width ?: 1440, running?.height ?: 900, running?.scale ?: 1.0, running != null, desktop = true,
        )
    }

    private val screenCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val hostId = screenForHost.also { screenForHost = null } ?: return@registerForActivityResult
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        com.enzimos.remote.uplink.UplinkService.startScreen(this, hostId, data)
        if (!com.enzimos.remote.uplink.MirrorControl.enabled(this)) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mirror_control_prompt_title)
                .setMessage(R.string.mirror_control_prompt)
                .setPositiveButton(R.string.open_settings) { _, _ -> startActivity(com.enzimos.remote.uplink.MirrorControl.settings()) }
                .setNegativeButton(R.string.not_now, null)
                .show()
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val hostId = cameraForHost.also { cameraForHost = null } ?: return@registerForActivityResult
        if (granted) com.enzimos.remote.uplink.UplinkService.startCamera(this, hostId, front = true)
    }

    private fun toggleMirror() {
        val active = com.enzimos.remote.uplink.UplinkService.active.value
        if (active?.hostId == selectedId && active?.kind == com.enzimos.remote.uplink.UplinkService.Kind.SCREEN) {
            com.enzimos.remote.uplink.UplinkService.stop(this)
            return
        }
        screenForHost = selectedId ?: return
        val projection = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        screenCapture.launch(projection.createScreenCaptureIntent())
    }

    private fun toggleWebcam() {
        val active = com.enzimos.remote.uplink.UplinkService.active.value
        if (active?.hostId == selectedId && active?.kind == com.enzimos.remote.uplink.UplinkService.Kind.CAMERA) {
            com.enzimos.remote.uplink.UplinkService.stop(this)
            return
        }
        val hostId = selectedId ?: return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            com.enzimos.remote.uplink.UplinkService.startCamera(this, hostId, front = true)
        } else {
            cameraForHost = hostId
            cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    /** Show one of the computer's monitors ([output] connector, null = main) and control it. */
    private fun openDesktopView(state: HostState, output: String?) {
        if (state.status != Status.ONLINE || openingStream) return
        openingStream = true
        startActivity(Intent(this, StreamActivity::class.java).apply {
            putExtra("host_id", state.hostId)
            putExtra("host_name", state.name)
            putExtra("host_address", state.host.addresses.firstOrNull().orEmpty())
            putExtra("device_id", store.getDeviceId())
            putExtra("session_id", "view-${System.currentTimeMillis().toString(36)}")
            putExtra("app_name", getString(R.string.desktop_title))
            putExtra("second_display", true)
            putExtra("desktop_view", true)
            putExtra("desktop_output", output)
        })
    }

    /** Extend the computer's desktop onto this phone (a new virtual monitor). */
    private fun openSecondDisplay() {
        val state = current ?: return
        if (state.status != Status.ONLINE || openingStream) return
        openingStream = true
        startActivity(Intent(this, StreamActivity::class.java).apply {
            putExtra("host_id", state.hostId)
            putExtra("host_name", state.name)
            putExtra("host_address", state.host.addresses.firstOrNull().orEmpty())
            putExtra("device_id", store.getDeviceId())
            putExtra("session_id", "display-${System.currentTimeMillis().toString(36)}")
            putExtra("app_name", getString(R.string.second_display_name))
            putExtra("second_display", true)
        })
    }

    private fun openStream(id: String, appId: String, name: String, width: Int, height: Int, scale: Double, resume: Boolean, desktop: Boolean = false) {
        val state = current ?: return
        if (openingStream) return
        openingStream = true
        startActivity(Intent(this, StreamActivity::class.java).apply {
            putExtra("host_id", state.hostId)
            putExtra("host_name", state.name)
            putExtra("host_address", state.host.addresses.firstOrNull().orEmpty())
            putExtra("device_id", store.getDeviceId())
            putExtra("session_id", id); putExtra("app_id", appId); putExtra("app_name", name)
            putExtra("width", width); putExtra("height", height); putExtra("scale", scale)
            putExtra("resume_session", resume)
            putExtra("shared_desktop", state.info?.sharedDesktop == true)
            putExtra("whole_desktop", desktop)
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Two minutes of processor samples at the 3-second home poll. */
private const val CPU_HISTORY = 40
