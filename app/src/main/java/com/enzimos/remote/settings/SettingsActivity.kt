package com.enzimos.remote.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import androidx.core.view.updatePadding
import com.enzimos.remote.BuildConfig
import com.enzimos.remote.R
import com.enzimos.remote.databinding.ActivitySettingsBinding
import com.enzimos.remote.pairing.DeviceStore

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: Preferences
    private lateinit var deviceStore: DeviceStore
    private val smsPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = com.enzimos.remote.phone.SmsBridge.hasPermission(this)
        preferences.smsSharing = granted
        binding.switchSmsSharing.isChecked = granted
        com.enzimos.remote.phone.SmsBridge.get(this).settingChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Handle system bar insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = insets.bottom)
            windowInsets
        }

        preferences = Preferences(this)
        deviceStore = DeviceStore(this)

        // Initialize values from preferences.
        val mbps = (preferences.maxBitrateKbps / 1000).coerceIn(1, 30)
        binding.sliderMaxBitrate.value = mbps.toFloat()
        binding.tvMaxBitrateValue.text = getString(R.string.settings_max_bitrate_value, mbps)
        binding.sliderMaxBitrate.setLabelFormatter { getString(R.string.settings_max_bitrate_value, it.toInt()) }
        val fpsButtons = mapOf(30 to binding.fps30, 60 to binding.fps60, 90 to binding.fps90, 120 to binding.fps120)
        val fps = fpsButtons.keys.minBy { kotlin.math.abs(it - preferences.maxFps) }
        binding.fpsGroup.check(fpsButtons.getValue(fps).id)
        binding.switchCompatibilityDecoder.isChecked = preferences.compatibilityDecoder
        binding.switchPerfOverlay.isChecked = preferences.showPerfOverlay
        binding.switchLockOrientation.isChecked = preferences.lockOrientation
        binding.sliderScrollSpeed.value = preferences.scrollSpeed
        binding.tvScrollSpeedValue.text = String.format(Locale.US, "%.1fx", preferences.scrollSpeed.toDouble())
        binding.switchAutoReconnect.isChecked = preferences.autoReconnect
        binding.switchKeepScreenOn.isChecked = preferences.keepScreenOn
        binding.switchClipboardSharing.isChecked = preferences.clipboardSharing
        binding.switchMirrorNotifications.isChecked = preferences.mirrorNotifications
        binding.switchSmsSharing.isChecked = preferences.smsSharing && com.enzimos.remote.phone.SmsBridge.hasPermission(this)

        // Resolution dropdown.
        val resLabels = Preferences.RESOLUTION_OPTIONS.map { it.first }
        val resValues = Preferences.RESOLUTION_OPTIONS.map { it.second }
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, resLabels)
        binding.dropdownResolution.setAdapter(resAdapter)
        val resIdx = resValues.indexOf(preferences.resolution).coerceAtLeast(0)
        binding.dropdownResolution.setText(resLabels[resIdx], false)
        binding.dropdownResolution.setOnItemClickListener { _, _, position, _ ->
            preferences.resolution = resValues[position]
        }

        val immersiveOptions = listOf(getString(R.string.navigation_hide_until_swipe), getString(R.string.navigation_always_show))
        val immersiveValues = listOf("during_stream", "never")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, immersiveOptions)
        binding.dropdownImmersiveMode.setAdapter(adapter)
        val currentIdx = if (preferences.immersiveMode == "never") 1 else 0
        binding.dropdownImmersiveMode.setText(immersiveOptions[currentIdx], false)
        binding.dropdownImmersiveMode.setOnItemClickListener { _, _, position, _ ->
            preferences.immersiveMode = immersiveValues[position]
        }

        // Device and version info.
        binding.tvVersionInfo.text = getString(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        binding.tvDeviceId.text = getString(R.string.settings_device_id, deviceStore.getDeviceId())
        updatePairedCount()

        // Save on change.
        binding.switchCompatibilityDecoder.setOnCheckedChangeListener { _, isChecked ->
            preferences.compatibilityDecoder = isChecked
        }
        binding.switchPerfOverlay.setOnCheckedChangeListener { _, isChecked ->
            preferences.showPerfOverlay = isChecked
        }
        binding.switchLockOrientation.setOnCheckedChangeListener { _, isChecked ->
            preferences.lockOrientation = isChecked
        }
        binding.sliderScrollSpeed.addOnChangeListener { _, value, _ ->
            preferences.scrollSpeed = value
            binding.tvScrollSpeedValue.text = String.format(Locale.US, "%.1fx", value.toDouble())
        }
        binding.switchAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            preferences.autoReconnect = isChecked
        }
        binding.switchMirrorNotifications.setOnCheckedChangeListener { _, isChecked ->
            preferences.mirrorNotifications = isChecked
            com.enzimos.remote.phone.NotificationMirror.instance?.updateLink()
            if (isChecked && !com.enzimos.remote.phone.NotificationMirror.hasAccess(this)) {
                startActivity(com.enzimos.remote.phone.NotificationMirror.accessSettings())
            }
            updateNotificationAccess()
        }
        binding.btnNotificationAccess.setOnClickListener {
            startActivity(com.enzimos.remote.phone.NotificationMirror.accessSettings())
        }
        binding.switchSmsSharing.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !com.enzimos.remote.phone.SmsBridge.hasPermission(this)) {
                smsPermission.launch(com.enzimos.remote.phone.SmsBridge.PERMISSIONS)
                return@setOnCheckedChangeListener
            }
            preferences.smsSharing = isChecked
            com.enzimos.remote.phone.SmsBridge.get(this).settingChanged()
        }
        binding.switchClipboardSharing.setOnCheckedChangeListener { _, isChecked ->
            preferences.clipboardSharing = isChecked
            com.enzimos.remote.share.ShareManager.get(this).clipboardSharingChanged()
        }
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            preferences.keepScreenOn = isChecked
        }

        binding.sliderMaxBitrate.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            preferences.maxBitrateKbps = value.toInt() * 1000
            binding.tvMaxBitrateValue.text = getString(R.string.settings_max_bitrate_value, value.toInt())
        }
        binding.fpsGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            preferences.maxFps = when (id) {
                binding.fps30.id -> 30
                binding.fps90.id -> 90
                binding.fps120.id -> 120
                else -> 60
            }
        }

        // Clear pairings.
        binding.btnClearPairings.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Forget all computers?")
                .setMessage("Connected computers also remove this phone. You can pair again at any time.")
                .setPositiveButton("Forget all") { _, _ ->
                    val manager = com.enzimos.remote.connection.ConnectionManager.get(this)
                    for (host in deviceStore.listPairedHosts()) manager.forget(host.hostId)
                    binding.root.postDelayed({ updatePairedCount() }, 500)
                    Toast.makeText(this, getString(R.string.settings_cleared), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Factory reset.
        binding.btnFactoryReset.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Factory Reset")
                .setMessage("Clear all settings, pairings, and device identity? This cannot be undone.")
                .setPositiveButton("Reset") { _, _ ->
                    deviceStore.clearAll()
                    com.enzimos.remote.connection.ConnectionManager.get(this).reloadHosts()
                    preferences.maxBitrateKbps = 5000
                    preferences.maxFps = 60
                    preferences.compatibilityDecoder = false
                    preferences.showPerfOverlay = false
                    preferences.lockOrientation = false
                    preferences.hideCursorOnTouch = true
                    preferences.autoReconnect = true
                    preferences.keepScreenOn = true
                    preferences.scrollSpeed = 1.0f
                    preferences.immersiveMode = "during_stream"
                    preferences.resolution = "native"
                    preferences.lastHostAddress = ""
                    preferences.clearConnectionHistory()
                    Toast.makeText(this, getString(R.string.settings_reset_done), Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updatePairedCount() {
        val count = deviceStore.listPairedHosts().size
        binding.tvPairedCount.text = resources.getQuantityString(R.plurals.settings_paired_count, count, count)
    }

    /**
     * Get a summary of current settings for display.
     */
    fun getSettingsSummary(): Map<String, String> = mapOf(
        "Device ID" to deviceStore.getDeviceId(),
        "Max Bitrate" to "${preferences.maxBitrateKbps} kbps",
        "Preferred Codec" to preferences.preferredCodec,
        "Max FPS" to "${preferences.maxFps}",
        "Resolution" to preferences.resolution,
        "Compatibility Decoder" to if (preferences.compatibilityDecoder) "On" else "Off",
        "Perf Overlay" to if (preferences.showPerfOverlay) "On" else "Off",
        "Lock Orientation" to if (preferences.lockOrientation) "On" else "Off",
        "App cursor" to "Hidden in video; native pointer for mouse",
        "Auto-Reconnect" to if (preferences.autoReconnect) "On" else "Off",
        "Keep Screen On" to if (preferences.keepScreenOn) "On" else "Off",
        "Scroll Speed" to "${preferences.scrollSpeed}x",
        "System navigation bar" to if (preferences.immersiveMode == "never") "Always show" else "Hide until swiped",
        "Paired Hosts" to "${deviceStore.listPairedHosts().size}",
    )

    override fun onResume() {
        super.onResume()
        updateNotificationAccess()
    }

    /** Show whether notification access still has to be granted. */
    private fun updateNotificationAccess() {
        val granted = com.enzimos.remote.phone.NotificationMirror.hasAccess(this)
        binding.btnNotificationAccess.setText(
            if (granted) R.string.settings_notification_access_granted else R.string.settings_notification_access)
        binding.btnNotificationAccess.isEnabled = !granted
        binding.btnNotificationAccess.visibility =
            if (preferences.mirrorNotifications) android.view.View.VISIBLE else android.view.View.GONE
    }
}
