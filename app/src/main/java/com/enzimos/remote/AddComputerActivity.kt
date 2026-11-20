package com.enzimos.remote

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.databinding.ActivityAddComputerBinding
import com.enzimos.remote.pairing.QrCodeData
import com.enzimos.remote.settings.Preferences
import com.enzimos.remote.ui.ComputerRow
import com.enzimos.remote.ui.ComputerRowAdapter
import com.enzimos.remote.ui.HostUi
import com.enzimos.remote.ui.PairingFlow
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Add a computer: nearby computers found by mDNS (tap to pair with desktop
 * approval), a host-displayed QR code, or a typed address.
 */
class AddComputerActivity : AppCompatActivity() {
    companion object {
        /** Result extra: host ID of the newly paired computer. */
        const val EXTRA_PAIRED_HOST_ID = "paired_host_id"
    }

    private lateinit var binding: ActivityAddComputerBinding
    private lateinit var manager: ConnectionManager
    private lateinit var pairing: PairingFlow
    private val adapter = ComputerRowAdapter(onClick = ::onNearbyClicked)
    private var nearbyByKey = emptyMap<String, com.enzimos.remote.signaling.Discovery.HostInfo>()
    private var pairedIds = emptySet<String>()

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val raw = result.data?.getStringExtra(PairingActivity.EXTRA_QR) ?: return@registerForActivityResult
        val data = QrCodeData.parse(raw) ?: return@registerForActivityResult
        pairing.start(
            PairingFlow.Target(
                displayName = data.name.ifEmpty { data.host },
                addresses = data.candidateAddresses.map { "$it:${data.port}" },
                hostId = data.id.ifEmpty { null },
                fingerprint = data.fp,
                qrToken = data.token,
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAddComputerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        manager = ConnectionManager.get(this)
        pairing = PairingFlow(this) { host ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PAIRED_HOST_ID, host.hostId))
            finish()
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.nearby.layoutManager = LinearLayoutManager(this)
        binding.nearby.adapter = adapter

        binding.scanQr.icon.setImageResource(R.drawable.ic_qr_code)
        binding.scanQr.title.setText(R.string.add_scan_qr)
        binding.scanQr.subtitle.setText(R.string.add_scan_qr_hint)
        binding.scanQr.root.setOnClickListener { scanLauncher.launch(Intent(this, PairingActivity::class.java)) }
        binding.manual.icon.setImageResource(R.drawable.ic_link)
        binding.manual.title.setText(R.string.add_manual)
        binding.manual.subtitle.setText(R.string.add_manual_hint)
        binding.manual.root.setOnClickListener { askForAddress() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(manager.nearby, manager.hosts) { nearby, hosts -> nearby to hosts }.collect { (nearby, hosts) ->
                    render(nearby, hosts)
                }
            }
        }
    }

    override fun onDestroy() {
        pairing.cancel()
        super.onDestroy()
    }

    private fun render(
        nearby: List<com.enzimos.remote.signaling.Discovery.HostInfo>,
        hosts: List<ConnectionManager.HostState>,
    ) {
        pairedIds = hosts.filter { it.status != ConnectionManager.Status.NEEDS_PAIRING }.map { it.hostId }.toSet()
        // One row per computer, even when it is advertised on several interfaces.
        val unique = nearby.groupBy { it.hostId.ifEmpty { it.name } }.mapValues { (_, list) ->
            list.first().copy(addresses = list.flatMap { it.addresses }.distinct())
        }
        nearbyByKey = unique
        val rows = unique.map { (key, info) ->
            val paired = info.hostId.isNotEmpty() && info.hostId in pairedIds
            ComputerRow(
                key = key,
                title = info.displayName,
                subtitle = info.connectAddresses.mapNotNull(HostUi::displayAddress).joinToString(", "),
                status = if (paired) ConnectionManager.Status.ONLINE else ConnectionManager.Status.OFFLINE,
                actionLabel = getString(if (paired) R.string.add_paired else R.string.add_pair),
                actionEnabled = !paired,
                showStatus = paired,
            )
        }.sortedBy { it.title.lowercase() }
        adapter.submitList(rows)
        binding.nearbyEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        binding.nearbyEmpty.setText(R.string.add_searching)
        binding.searching.visibility = View.VISIBLE
        // After a few seconds without results, explain what to check.
        binding.nearbyEmpty.postDelayed(nothingFound, 6_000)
    }

    override fun onStop() {
        binding.nearbyEmpty.removeCallbacks(nothingFound)
        super.onStop()
    }

    private val nothingFound = Runnable {
        binding.searching.visibility = View.INVISIBLE
        if (adapter.itemCount == 0) binding.nearbyEmpty.setText(R.string.add_nothing_found)
    }

    private fun onNearbyClicked(row: ComputerRow) {
        val info = nearbyByKey[row.key] ?: return
        if (info.hostId.isNotEmpty() && info.hostId in pairedIds) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PAIRED_HOST_ID, info.hostId))
            finish()
            return
        }
        pairing.start(
            PairingFlow.Target(
                displayName = info.displayName,
                addresses = info.connectAddresses,
                hostId = info.hostId.ifEmpty { null },
            ),
        )
    }

    private fun askForAddress() {
        val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.add_manual_field)
            setPadding(0, 0, 0, 0)
        }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
            setText(Preferences(this@AddComputerActivity).lastHostAddress.takeIf { it.isNotBlank() })
            // Typing replaces a remembered address instead of appending to it.
            setSelectAllOnFocus(true)
            selectAll()
        }
        layout.addView(input)
        val container = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_manual_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add_continue, null)
            .show()
        val submit = {
            val address = normalizeAddress(input.text?.toString().orEmpty())
            if (address == null) {
                layout.error = getString(R.string.add_manual_invalid)
            } else {
                dialog.dismiss()
                Preferences(this).lastHostAddress = address
                pairing.start(PairingFlow.Target(displayName = address.substringBeforeLast(':'), addresses = listOf(address)))
            }
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { submit(); true } else false
        }
        input.requestFocus()
    }

    /** "host" or "host:port" → "host:port"; null when invalid. */
    private fun normalizeAddress(raw: String): String? = AddressInput.normalize(raw)
}
