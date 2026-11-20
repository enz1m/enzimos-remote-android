package com.enzimos.remote.remote

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.enzimos.remote.R
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.databinding.ActivityRemoteControlBinding
import com.enzimos.remote.signaling.HostMessage
import com.enzimos.remote.signaling.MediaCommandRequest
import com.enzimos.remote.signaling.MediaState
import com.enzimos.remote.signaling.MediaStateRequest
import com.enzimos.remote.signaling.SignalingClient
import com.enzimos.remote.signaling.encodeClientMessage
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Media controls, touchpad and keyboard, and presenter remote for a computer. */
class RemoteControlActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRemoteControlBinding
    private lateinit var hostId: String
    private lateinit var input: DesktopInput
    private var media = MediaState()
    private var mediaAt = 0L
    private var artId: String? = null
    private var chosenPlayer: String? = null
    private var seeking = false
    private var adjustingVolume = false
    private var laser: SensorEventListener? = null

    private val client: SignalingClient? get() = ConnectionManager.get(this).connection(hostId)?.client

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityRemoteControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        hostId = intent.getStringExtra(EXTRA_HOST_ID).orEmpty()
        binding.toolbar.title = ConnectionManager.get(this).state(hostId)?.name ?: getString(R.string.remote_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        input = DesktopInput { client }
        binding.touchpad.input = input
        binding.keyCatcher.input = input

        binding.tabs.addOnButtonCheckedListener { _, id, checked -> if (checked) showTab(id) }
        binding.tabs.check(savedInstanceState?.getInt("tab") ?: R.id.tabMedia)
        setUpMedia()
        setUpTouchpad()
        setUpPresenter()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { client?.messages?.collect { if (it is HostMessage.Media) runOnUiThread { render(it.state) } } }
                launch {
                    while (true) {
                        if (binding.mediaPanel.isVisible) requestMedia()
                        delay(1000)
                        if (binding.mediaPanel.isVisible) tickPosition()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", binding.tabs.checkedButtonId)
    }

    override fun onPause() {
        stopLaser()
        super.onPause()
    }

    private fun showTab(id: Int) {
        binding.mediaPanel.isVisible = id == R.id.tabMedia
        binding.touchpadPanel.isVisible = id == R.id.tabTouchpad
        binding.presenterPanel.isVisible = id == R.id.tabPresenter
        if (id == R.id.tabMedia) requestMedia()
    }

    // ── Media ──

    private fun requestMedia() {
        client?.sendRaw(encodeClientMessage(MediaStateRequest(player = chosenPlayer, knownArt = artId)))
    }

    private fun command(command: String, value: Double? = null) {
        client?.sendRaw(encodeClientMessage(MediaCommandRequest(player = media.player, command = command, value = value)))
    }

    private fun setUpMedia() {
        binding.playPause.setOnClickListener { command("play_pause") }
        binding.next.setOnClickListener { command("next") }
        binding.previous.setOnClickListener { command("previous") }
        binding.mute.setOnClickListener { command("toggle_mute") }
        binding.seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { seeking = true }
            override fun onStopTrackingTouch(slider: Slider) {
                seeking = false
                command("seek", slider.value.toDouble())
            }
        })
        binding.volume.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { adjustingVolume = true }
            override fun onStopTrackingTouch(slider: Slider) {
                adjustingVolume = false
                command("set_volume", slider.value.toDouble())
            }
        })
        binding.volume.setLabelFormatter { "${it.toInt()}%" }
        binding.player.setOnClickListener { view ->
            val menu = PopupMenu(this, view)
            media.players.forEachIndexed { index, player -> menu.menu.add(0, index, index, player.name) }
            menu.setOnMenuItemClickListener { item ->
                chosenPlayer = media.players.getOrNull(item.itemId)?.id
                artId = null
                requestMedia()
                true
            }
            menu.show()
        }
        render(media)
    }

    private fun render(state: MediaState) {
        media = state
        mediaAt = System.currentTimeMillis()
        val playing = state.status == "Playing"
        val hasPlayer = state.player != null
        val name = ConnectionManager.get(this).state(hostId)?.name.orEmpty()
        binding.mediaTitle.text = state.title.ifBlank { if (hasPlayer) getString(R.string.remote_unknown_title) else getString(R.string.remote_nothing_playing, name) }
        binding.mediaArtist.text = state.artist
        binding.playPause.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        listOf(binding.playPause, binding.next, binding.previous).forEach { it.isEnabled = hasPlayer }
        binding.player.isVisible = state.players.size > 1
        binding.player.text = state.players.firstOrNull { it.id == state.player }?.name?.let { "$it ▾" }
        if (state.art != null && state.artId != artId) {
            val bytes = Base64.decode(state.art, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            binding.art.setImageBitmap(bitmap)
            // Browsers share tiny thumbnails: don't blow them up into a blur.
            val small = bitmap != null && maxOf(bitmap.width, bitmap.height) < 240
            val inset = if (small) (binding.art.width * 0.22f).toInt() else 0
            binding.art.setPadding(inset, inset, inset, inset)
            binding.art.scaleType = if (small) android.widget.ImageView.ScaleType.FIT_CENTER else android.widget.ImageView.ScaleType.CENTER_CROP
            artId = state.artId
        } else if (state.artId == null) {
            binding.art.setImageDrawable(null)
            artId = null
        }
        binding.artPlaceholder.isVisible = binding.art.drawable == null
        val seekable = state.canSeek && state.lengthMs > 0
        binding.seek.isEnabled = seekable
        // Streams and some browsers report no length: no empty progress bar.
        binding.seek.isVisible = state.lengthMs > 0
        binding.timeRow.isVisible = state.lengthMs > 0
        if (!seeking) {
            binding.seek.valueTo = state.lengthMs.coerceAtLeast(1).toFloat()
            binding.seek.value = state.positionMs.coerceIn(0, state.lengthMs.coerceAtLeast(1)).toFloat()
        }
        binding.position.text = time(state.positionMs)
        binding.length.text = if (state.lengthMs > 0) time(state.lengthMs) else ""
        if (!adjustingVolume) binding.volume.value = state.volume.coerceIn(0, 150).toFloat()
        binding.mute.setIconResource(if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
    }

    /** Advance the shown position between polls while playing. */
    private fun tickPosition() {
        if (media.status != "Playing" || seeking || media.lengthMs <= 0) return
        val position = (media.positionMs + System.currentTimeMillis() - mediaAt).coerceAtMost(media.lengthMs)
        binding.seek.value = position.toFloat()
        binding.position.text = time(position)
    }

    private fun time(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%d:%02d".format(seconds / 60, seconds % 60)
    }

    // ── Touchpad and keyboard ──

    private fun setUpTouchpad() {
        holdButton(binding.leftButton, "left")
        holdButton(binding.rightButton, "right")
        val keys = listOf(
            "Esc" to { input.tap(DesktopInput.KEY_ESC) },
            "Tab" to { input.tap(DesktopInput.KEY_TAB) },
            "←" to { input.tap(DesktopInput.KEY_LEFT) },
            "↑" to { input.tap(DesktopInput.KEY_UP) },
            "↓" to { input.tap(DesktopInput.KEY_DOWN) },
            "→" to { input.tap(DesktopInput.KEY_RIGHT) },
            "⌨" to { binding.keyCatcher.toggleKeyboard() },
        )
        for ((label, action) in keys) {
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                contentDescription = when (label) {
                    "⌨" -> getString(R.string.show_keyboard)
                    "←" -> getString(R.string.remote_key_left)
                    "→" -> getString(R.string.remote_key_right)
                    "↑" -> getString(R.string.remote_key_up)
                    "↓" -> getString(R.string.remote_key_down)
                    else -> label
                }
                setOnClickListener { action() }
            }
            binding.keyRow.addView(button, android.widget.LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).toInt(), 1f)
                .apply { marginStart = 3; marginEnd = 3 })
        }
    }

    /** Pressed while held, so the button can also drag. */
    @Suppress("ClickableViewAccessibility")
    private fun holdButton(view: View, button: String) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { input.button(button, true); v.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { input.button(button, false); v.isPressed = false }
            }
            true
        }
    }

    // ── Presenter ──

    private fun setUpPresenter() {
        binding.slideNext.setOnClickListener { input.tap(DesktopInput.KEY_RIGHT) }
        binding.slidePrevious.setOnClickListener { input.tap(DesktopInput.KEY_LEFT) }
        binding.slideStart.setOnClickListener { input.tap(DesktopInput.KEY_F5) }
        binding.slideEnd.setOnClickListener { input.tap(DesktopInput.KEY_ESC) }
        binding.slideBlack.setOnClickListener { input.tap(DesktopInput.KEY_B) }
        @Suppress("ClickableViewAccessibility")
        binding.laser.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; startLaser() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; stopLaser() }
            }
            true
        }
    }

    /** Point the phone like a laser pointer: rotation moves the computer's pointer. */
    private fun startLaser() {
        val sensors = getSystemService(SensorManager::class.java)
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (last != 0L) {
                    val dt = (event.timestamp - last) / 1e9
                    input.move(-event.values[2] * dt * LASER_GAIN, -event.values[0] * dt * LASER_GAIN)
                }
                last = event.timestamp
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        laser = listener
    }

    private fun stopLaser() {
        laser?.let { getSystemService(SensorManager::class.java).unregisterListener(it) }
        laser = null
    }

    /** Volume keys change slides while the presenter is shown. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.presenterPanel.isVisible && event.keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                input.tap(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) DesktopInput.KEY_RIGHT else DesktopInput.KEY_LEFT)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_HOST_ID = "host_id"
        /** Desktop pixels per radian of phone rotation. */
        private const val LASER_GAIN = 1400.0

        fun intent(context: Context, hostId: String): Intent =
            Intent(context, RemoteControlActivity::class.java).putExtra(EXTRA_HOST_ID, hostId)
    }
}
