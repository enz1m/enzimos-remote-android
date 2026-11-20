package com.enzimos.remote

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import com.enzimos.remote.BuildConfig
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.enzimos.remote.databinding.ActivityStreamBinding
import com.enzimos.remote.input.AppTouchController
import com.enzimos.remote.input.MobileGesture
import com.enzimos.remote.input.MouseInputController
import com.enzimos.remote.input.ControlMessageSerializer
import com.enzimos.remote.input.CursorController
import com.enzimos.remote.input.HapticFeedback
import com.enzimos.remote.input.KeyboardHandler
import com.enzimos.remote.input.TouchHandler
import com.enzimos.remote.settings.Preferences
import com.enzimos.remote.signaling.HostMessage
import com.enzimos.remote.signaling.NetworkMonitor
import com.enzimos.remote.signaling.SignalingClient
import com.enzimos.remote.video.PerformanceOverlay
import com.enzimos.remote.video.PipHelper
import com.enzimos.remote.video.StreamInsets
import com.enzimos.remote.video.StreamSystemBars
import com.enzimos.remote.webrtc.ConnectionStats
import com.enzimos.remote.webrtc.DataChannelHandler
import com.enzimos.remote.webrtc.PeerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.SessionDescription

class StreamActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StreamActivity"
        /** Toolbar auto-hide delay in milliseconds. */
        private const val TOOLBAR_AUTO_HIDE_MS = 4000L
        /** ICE disconnect timeout: escalate to reconnect after this many ms. */
        private const val ICE_DISCONNECT_TIMEOUT_MS = 5_000L

        internal fun usesMobileGestures(intent: android.content.Intent): Boolean =
            !intent.getBooleanExtra("raw_touch", false)

        /**
         * Map raw server error strings to user-friendly messages.
         * Delegates to ErrorCatalog for comprehensive mapping.
         * Internal visibility for testing.
         */
        internal fun friendlyError(raw: String): String {
            val (title, suggestion) = ErrorCatalog.userMessage(raw)
            return if (title == "Error") {
                // ErrorCatalog didn't match — try our own patterns.
                when {
                    raw.contains("max_sessions", ignoreCase = true) ||
                        raw.contains("maximum sessions", ignoreCase = true) ->
                        "Too many active sessions. Try again later."
                    raw.contains("not found", ignoreCase = true) ||
                        raw.contains("no such", ignoreCase = true) ->
                        "Session is no longer available. Tap Retry."
                    raw.contains("auth", ignoreCase = true) ||
                        raw.contains("token", ignoreCase = true) ->
                        "Authentication failed. Check your pairing."
                    raw.contains("rate limit", ignoreCase = true) ->
                        "Sending too fast. Please wait."
                    raw.contains("timeout", ignoreCase = true) ->
                        "Connection timed out. Tap Retry."
                    else -> raw // Pass through unrecognized errors
                }
            } else {
                "$title: $suggestion"
            }
        }
    }

    private lateinit var binding: ActivityStreamBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var signalingClient: SignalingClient? = null
    @Volatile private var peerManager: PeerManager? = null
    @Volatile private var dataChannelHandler: DataChannelHandler? = null
    private var touchHandler: TouchHandler? = null
    private var appTouch: AppTouchController? = null
    private var appMouse: MouseInputController? = null
    private var keyboardHandler: KeyboardHandler? = null
    private var cursorController: CursorController? = null
    private var perfOverlay: PerformanceOverlay? = null
    private var pipHelper: PipHelper? = null
    private var hapticFeedback: HapticFeedback? = null
    private var wakeLockManager: WakeLockManager? = null
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && !leaving && !controlReturned && StreamNotificationControl.owns(notificationOwner.token)) {
            startService(StreamService.createStartIntent(this, hostName, notificationOwner.token))
        }
    }
    private val notificationOwner = object : StreamNotificationControl.Owner {
        override val token = java.util.UUID.randomUUID().toString()
        override fun requestStop() { runOnUiThread { returnHome() } }
    }
    private var clipboardHandler: com.enzimos.remote.input.ClipboardHandler? = null
    private var networkMonitor: NetworkMonitor? = null
    private var networkJob: Job? = null
    private var statsJob: Job? = null
    private val connectionStats = ConnectionStats()
    private lateinit var preferences: Preferences

    /** Adapter for the running windows list inside the app drawer. */
    private var drawerWindowAdapter: WindowListAdapter? = null
    /** True when the window list was requested by the toolbar button (show bottom sheet). */
    @Volatile private var pendingSheetShow = false

    @Volatile private var hostAddress: String = ""
    /** Paired computer this stream belongs to; its shared connection carries signaling. */
    private var hostId: String = ""
    private var hostName: String = ""
    private var connectionLease = false
    private val signalingJobs = mutableListOf<kotlinx.coroutines.Job>()
    @Volatile private var sessionId: String = ""
    @Volatile private var sessionWidth: Int = 0
    @Volatile private var sessionHeight: Int = 0
    private var deviceId: String = ""
    /** Whether the renderer has been initialized (for cleanup). */
    @Volatile private var rendererInitialized = false
    private var videoWidth = 0
    private var videoHeight = 0
    /** Prevents stacked reconnect coroutines from racing. Thread-safe since
     *  reconnect() may be called from the WebRTC signaling thread via ICE
     *  state callbacks while the main thread also reads/writes this flag. */
    private val reconnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mediaRecovery = MediaRecovery()
    private var mediaRecoveryJob: Job? = null
    /** Whether a session has been created at least once. When true, the
     *  signaling state observer uses resumeSession() instead of requestSession()
     *  to preserve the running compositor and app. */
    @Volatile private var sessionEstablished = false
    private var appId = ""
    private lateinit var appSettingsStore: AppStreamSettingsStore
    /** Input, size and shape the user chose for this app on this computer. */
    private var appSettings = AppStreamSettings()
    private var trackpad: com.enzimos.remote.input.TrackpadController? = null
    /** Game controllers (physical and on-screen) sent to the computer as gamepads. */
    private val gamepads = com.enzimos.remote.input.GamepadHub { json, critical -> gestureChannel()?.invoke(json, critical) ?: false }
    private val physicalGamepads = com.enzimos.remote.input.PhysicalGamepads(gamepads)
    private val gamepadDevices = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = android.view.InputDevice.getDevice(deviceId) ?: return
            if (com.enzimos.remote.input.PhysicalGamepads.isGamepad(device) && !device.isVirtual) {
                android.widget.Toast.makeText(this@StreamActivity, getString(R.string.gamepad_connected, device.name), android.widget.Toast.LENGTH_SHORT).show()
                // A real controller replaces the on-screen one until it goes away.
                applyController()
            }
        }
        override fun onInputDeviceRemoved(deviceId: Int) {
            physicalGamepads.onDeviceRemoved(deviceId)
            applyController()
        }
        override fun onInputDeviceChanged(deviceId: Int) = Unit
    }
    /** This phone extends the computer's desktop (virtual monitor). */
    private var secondDisplay = false
    /** A second-display session that shows an existing monitor (desktop view). */
    private var desktopView = false
    private var desktopOutput: String? = null
    private var displayTouch: com.enzimos.remote.remote.DisplayTouchForwarder? = null
    private var displayKeys: com.enzimos.remote.remote.DesktopInput? = null
    private var borrowedDesktop = false
    private var controlReturned = false
    private var desktopSessionUnavailable = false
    private var wholeDesktop = false
    private var imeVisible = false
    private var imeOcclusion = 0
    private var desktopKeyboardView: com.enzimos.remote.video.DesktopKeyboardView? = null
    private var desktopKeyboardAnchored = false
    private var desktopTapCandidate = false
    private var desktopAnchorX = .5f
    private var desktopAnchorY = .33f
    private var mobileTouch = true
    private var appName = ""
    private var sessionScale = 1.0
    private var leaving = false
    private var homeStopSent = false
    private var inputDrainSupported = false
    private val homeInputDrain = InputDrain()
    private var homeDrainTimeout: Job? = null
    private var viewportJob: Job? = null
    private var pendingViewport: AppViewport.Size? = null
    private var resumeAfterPause = false
    private var mediaPauseTimeout: Job? = null
    private var contentResize: ContentResize? = null
    private var contentResizeDisabled = false
    private var contentResizeTimeout: Job? = null
    private var controlChannel: DataChannel? = null
    private val launchedAtMs = android.os.SystemClock.elapsedRealtime()
    private val intervalStats = com.enzimos.remote.webrtc.ReceiverStatsTracker()

    // ── Zoom & Pan state ──────────────────────────────────────────────
    /** Current zoom level (1.0 = fit, up to 5.0). */
    private var zoomScale = 1.0f
    /** Pan offset X (in view pixels, pre-zoom). */
    private var panX = 0f
    /** Pan offset Y (in view pixels, pre-zoom). */
    private var panY = 0f
    /** Whether a scale gesture is in progress (suppresses touch forwarding). */
    private var isScaling = false
    /** Last two-finger midpoint for pan tracking during pinch. */
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    /** Whether a two-finger scroll gesture is in progress (not zoomed). */
    private var isTwoFingerScrolling = false
    /** Previous two-finger midpoint Y for scroll delta calculation. */
    private var lastScrollFocusY = 0f
    private var lastScrollFocusX = 0f
    /** Initial touch-down position for long-press movement cancellation. */
    private var longPressStartX = 0f
    private var longPressStartY = 0f
    /** Movement threshold for cancelling long-press (10dp). */
    private val longPressMoveThreshold by lazy { 10f * resources.displayMetrics.density }

    /** Auto-hide handler for the toolbar overlay. */
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideToolbarOverlay() }

    /** ICE disconnect timeout: if ICE stays DISCONNECTED for 5s, trigger reconnect. */
    private val iceDisconnectHandler = Handler(Looper.getMainLooper())
    private val iceDisconnectRunnable = Runnable {
        Log.w(TAG, "ICE disconnected for ${ICE_DISCONNECT_TIMEOUT_MS}ms, triggering reconnect")
        showConnectingOverlay("Connection lost", showRetry = true)
        reconnect()
    }

    /** Gesture detector for double-tap and long-press. */
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoomScale > 1.01f) {
                    // Double-tap resets zoom when zoomed in
                    resetZoom()
                } else {
                    toggleToolbarOverlay()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Long press → right click at the touch position.
                if (isScaling) return
                val viewW = binding.surfaceVideo.width
                val viewH = binding.surfaceVideo.height
                if (viewW <= 0 || viewH <= 0) return

                val center = viewW / 2.0
                val logicalX = (e.x - center) / zoomScale - panX + center
                val sessionX = (logicalX / viewW) * sessionWidth

                val centerY = viewH / 2.0
                val logicalY = (e.y - centerY) / zoomScale - panY + centerY
                val sessionY = (logicalY / viewH) * sessionHeight

                val messages = com.enzimos.remote.input.GestureMapper
                    .rightClickMessages(sessionX, sessionY)
                messages.forEach { json ->
                    dataChannelHandler?.send(json, critical = true)
                }

                hapticFeedback?.longPress(binding.surfaceVideo)
                binding.longPressIndicator.complete()

                // Cancel any ongoing touch to avoid stale touch-up events
                val cancelMsg = com.enzimos.remote.input.ControlMessageSerializer
                    .touch(0, "cancel", 0.0, 0.0)
                dataChannelHandler?.send(cancelMsg, critical = true)
            }
        }).apply {
            setIsLongpressEnabled(true)
        }
    }

    /** Scale gesture detector for pinch-to-zoom. */
    private val scaleGestureDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Near-unity scaling lets a two-finger drag scroll at base zoom.
                if (desktopView && zoomScale <= 1.01f && detector.scaleFactor in 0.97f..1.03f) {
                    if (!isTwoFingerScrolling) {
                        isTwoFingerScrolling = true
                        lastScrollFocusX = detector.focusX
                        lastScrollFocusY = detector.focusY
                    }
                    val view = binding.surfaceVideo
                    displayTouch?.scroll(detector.focusX - view.left, detector.focusY - view.top,
                        (lastScrollFocusX - detector.focusX).toDouble(), (lastScrollFocusY - detector.focusY).toDouble())
                    lastScrollFocusX = detector.focusX
                    lastScrollFocusY = detector.focusY
                    return true
                }
                if (!mobileTouch && zoomScale <= 1.01f) {
                    val sf = detector.scaleFactor
                    if (sf in 0.97f..1.03f) {
                        // Parallel drag → remote scroll
                        if (!isTwoFingerScrolling) {
                            isTwoFingerScrolling = true
                            lastScrollFocusX = detector.focusX
                            lastScrollFocusY = detector.focusY
                        }
                        val dy = (detector.focusY - lastScrollFocusY).toDouble()
                        val dx = (detector.focusX - lastScrollFocusX).toDouble()
                        lastScrollFocusX = detector.focusX
                        lastScrollFocusY = detector.focusY

                        // One wheel step represents about 60 pixels of movement.
                        val speed = preferences.scrollSpeed.toDouble()
                        val scrollDy = -dy / 60.0 * speed
                        val scrollDx = dx / 60.0 * speed
                        if (kotlin.math.abs(scrollDy) > 0.01 || kotlin.math.abs(scrollDx) > 0.01) {
                            val msg = com.enzimos.remote.input.GestureMapper.scrollMessage(scrollDx, scrollDy)
                            dataChannelHandler?.send(msg, critical = false)
                        }
                        return true
                    }
                }
                isTwoFingerScrolling = false

                val view = binding.surfaceVideo
                val next = com.enzimos.remote.video.VideoZoom(zoomScale, panX * zoomScale, panY * zoomScale)
                    .scaled(detector.scaleFactor, lastFocusX, lastFocusY, detector.focusX, detector.focusY,
                        view.left + view.width / 2f, view.top + view.height / 2f)
                zoomScale = next.scale
                panX = next.x / next.scale
                panY = next.y / next.scale

                lastFocusX = detector.focusX
                lastFocusY = detector.focusY

                clampPan()
                applyZoomPan()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                isTwoFingerScrolling = false
                // Snap to 1.0 if nearly unzoomed
                if (zoomScale < 1.05f) {
                    resetZoom()
                }
            }
        }).apply {
            // Avoid eating quick-scale double-taps
            isQuickScaleEnabled = false
        }
    }

    /** Touch listener extracted for PiP toggle. */
    @Suppress("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { view, event ->
        if (leaving) return@OnTouchListener true
        if (secondDisplay) {
            if (binding.overlayConnecting.isVisible) return@OnTouchListener true
            // Desktop view: one finger touches the desktop; two fingers zoom,
            // pan, or (unzoomed) scroll it.
            if (desktopView) transformGesture(view, event)
            return@OnTouchListener displayTouch?.onTouch(event) ?: true
        }
        if (mobileTouch) {
            if (binding.overlayConnecting.isVisible || leaving) return@OnTouchListener true
            if (CursorController.isMouseInput(event.source)) return@OnTouchListener appMouse?.onEvent(event) ?: true
            if (AppTouchController.isFinger(event) && appSettings.trackpad && trackpad != null) {
                appMouse?.cancel()
                return@OnTouchListener trackpad?.onTouchEvent(event) ?: true
            }
            if (AppTouchController.isFinger(event)) {
                rememberDesktopTap(view, event)
                appMouse?.cancel()
                // Cancel single-finger intent before the scale detector consumes a second contact.
                appTouch?.onTouchEvent(event)
                transformGesture(view, event)
                return@OnTouchListener true
            }
        }
        transformGesture(view, event)

        // Long-press visual indicator lifecycle.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressStartX = event.x
                longPressStartY = event.y
                if (event.pointerCount == 1) {
                    binding.longPressIndicator.show(event.x, event.y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - longPressStartX
                val dy = event.y - longPressStartY
                if (dx * dx + dy * dy > longPressMoveThreshold * longPressMoveThreshold) {
                    binding.longPressIndicator.cancel()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                binding.longPressIndicator.cancel()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch — cancel long-press indicator.
                binding.longPressIndicator.cancel()
            }
        }

        // Check for double-tap gesture
        if (!mobileTouch) gestureDetector.onTouchEvent(event)

        // Don't forward touch input while scaling or two-finger panning
        if (isScaling || (zoomScale > 1.01f && event.pointerCount >= 2)) {
            return@OnTouchListener true
        }

        cursorController?.onInputEvent(event)

        val viewW = binding.surfaceVideo.width
        val viewH = binding.surfaceVideo.height
        val messages = touchHandler?.onTouchEvent(
            event,
            viewW,
            viewH,
            if (!mobileTouch) zoomScale else 1f,
            if (!mobileTouch) panX else 0f,
            if (!mobileTouch) panY else 0f
        ) ?: emptyList()

        messages.forEach { json ->
            val isCritical = !json.contains("\"phase\":\"move\"")
            dataChannelHandler?.send(json, critical = isCritical)
        }
        // Accessibility: call performClick on touch up for screen readers.
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
            view.performClick()
        }
        true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hostAddress = intent.getStringExtra("host_address") ?: ""
        hostId = intent.getStringExtra("host_id").orEmpty()
        hostName = intent.getStringExtra("host_name").orEmpty().ifEmpty { hostAddress.substringBeforeLast(':') }
        sessionId = savedInstanceState?.getString("session_id") ?: intent.getStringExtra("session_id").orEmpty()
        sessionWidth = savedInstanceState?.getInt("width")?.takeIf { it > 0 } ?: intent.getIntExtra("width", 1080)
        sessionHeight = savedInstanceState?.getInt("height")?.takeIf { it > 0 } ?: intent.getIntExtra("height", 2400)
        deviceId = intent.getStringExtra("device_id") ?: ""
        appId = intent.getStringExtra("app_id").orEmpty()
        wholeDesktop = intent.getBooleanExtra("whole_desktop", false)
        secondDisplay = intent.getBooleanExtra("second_display", false)
        desktopView = secondDisplay && intent.getBooleanExtra("desktop_view", false)
        desktopOutput = intent.getStringExtra("desktop_output")
        appSettingsStore = AppStreamSettingsStore(this)
        appSettings = appSettingsStore.get(hostId, settingsKey())
        borrowedDesktop = wholeDesktop || intent.getBooleanExtra("shared_desktop", false)
        controlReturned = savedInstanceState?.getBoolean("control_returned") ?: false
        desktopSessionUnavailable = savedInstanceState?.getBoolean("desktop_session_unavailable") ?: false
        mobileTouch = usesMobileGestures(intent)
        appName = intent.getStringExtra("app_name").orEmpty()
        sessionScale = savedInstanceState?.getDouble("scale")?.takeIf { it > 0 } ?: intent.getDoubleExtra("scale", 1.0)
        sessionEstablished = savedInstanceState?.getBoolean("resume_session") ?: intent.getBooleanExtra("resume_session", false)

        if (hostId.isEmpty() || sessionId.isEmpty() || deviceId.isEmpty() ||
            com.enzimos.remote.connection.ConnectionManager.get(this).connection(hostId) == null) {
            Log.e(TAG, "Missing required intent extras, finishing")
            finish()
            return
        }
        if (!com.enzimos.remote.signaling.validateSessionId(sessionId)) {
            Log.e(TAG, "Invalid session_id in intent, finishing")
            finish()
            return
        }

        preferences = Preferences(this)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        binding.streamDock.dockKeyboard.isCheckable = true
        binding.streamDock.dockKeyboard.setToggleCheckedStateOnClick(false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            updateDesktopKeyboard(keyboardVisible)
            binding.streamDock.root.setKeyboardCompact(keyboardVisible && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            val padding = StreamInsets.contentPadding(insets, isInPictureInPictureMode)
            imeOcclusion = (padding.bottom - StreamInsets.contentPadding(insets, isInPictureInPictureMode, ignoreIme = true).bottom).coerceAtLeast(0)
            val content = binding.streamContent
            if (content.paddingLeft != padding.left || content.paddingTop != padding.top ||
                content.paddingRight != padding.right || content.paddingBottom != padding.bottom) {
                content.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                scheduleViewportChange()
            }
            binding.streamDock.dockKeyboard.isChecked = keyboardVisible
            val description = getString(if (keyboardVisible) R.string.hide_keyboard else R.string.show_keyboard)
            binding.streamDock.dockKeyboard.contentDescription = description
            binding.streamDock.dockKeyboard.tooltipText = description
            insets
        }
        binding.videoContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                fitVideoSurface()
                scheduleViewportChange()
            }
        }
        binding.root.doOnLayout { ViewCompat.requestApplyInsets(it) }
        configureSystemBars()

        binding.surfaceVideo.keepScreenOn = preferences.keepScreenOn && !controlReturned
        if (binding.surfaceVideo.keepScreenOn) {
            wakeLockManager = WakeLockManager(this)
            wakeLockManager?.acquirePartial()
        }

        keyboardHandler = KeyboardHandler()
        touchHandler = TouchHandler(sessionWidth, sessionHeight)
        if (mobileTouch) {
            appTouch = AppTouchController(binding.surfaceVideo, { sessionWidth to sessionHeight },
                { gestureChannel() }, { preferences.scrollSpeed },
                { x, y, duration -> binding.longPressIndicator.show(x, y, duration) },
                { binding.longPressIndicator.cancel() },
                { phase ->
                    when (phase) {
                        MobileGesture.Feedback.DRAG_READY -> {
                            hapticFeedback?.tap(binding.surfaceVideo)
                            binding.longPressIndicator.readyForDrag()
                        }
                        MobileGesture.Feedback.MENU -> {
                            hapticFeedback?.longPress(binding.surfaceVideo)
                            binding.longPressIndicator.complete()
                        }
                    }
                })
            appMouse = MouseInputController(binding.surfaceVideo, { sessionWidth to sessionHeight },
                { gestureChannel() }, { preferences.scrollSpeed }, { appTouch?.cancel(); trackpad?.cancel() })
            trackpad = com.enzimos.remote.input.TrackpadController(binding.surfaceVideo, { sessionWidth to sessionHeight },
                { gestureChannel() }, { preferences.scrollSpeed }, { hapticFeedback?.tap(binding.surfaceVideo) })
            applyInputMode()
            applyController()
            binding.surfaceVideo.setOnGenericMotionListener { _, event ->
                if (binding.overlayConnecting.isVisible || leaving) true else appMouse?.onEvent(event) ?: false
            }
            binding.surfaceVideo.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (r - l != oldR - oldL || b - t != oldB - oldT) {
                    appTouch?.cancel(); appMouse?.cancel(); trackpad?.cancel()
                }
            }
        }
        binding.surfaceVideo.setOnTouchListener(touchListener)

        binding.surfaceVideo.onTextMessage = { msg ->
            if (secondDisplay) forwardDisplayKey(msg)
            else if (!leaving && !controlReturned) dataChannelHandler?.send(msg, critical = true)
        }
        if (secondDisplay) {
            displayTouch = com.enzimos.remote.remote.DisplayTouchForwarder(binding.surfaceVideo, sessionId,
                { sessionWidth to sessionHeight }, singleFinger = desktopView) { json -> signalingClient?.sendRaw(json) == true }
            displayKeys = com.enzimos.remote.remote.DesktopInput { signalingClient }
        }

        if (preferences.lockOrientation) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }

        cursorController = CursorController { json ->
            dataChannelHandler?.send(json, critical = true)
        }

        // App sessions do not grant clipboard access; keep host/phone clipboards separate.

        // Performance overlay — always on in debug builds for testing
        perfOverlay = PerformanceOverlay(binding.tvPerfOverlay)
        if (preferences.showPerfOverlay) {
            perfOverlay?.show()
        }

        hapticFeedback = HapticFeedback(this)

        pipHelper = PipHelper(this) { isInPip ->
            if (isInPip) { appTouch?.cancel(); appMouse?.cancel() }
            binding.streamDock.root.isVisible = !isInPip
            binding.videoCorners.isVisible = !isInPip
            if (isInPip) binding.toolbarOverlay.isVisible = false
            binding.surfaceVideo.setOnTouchListener(if (isInPip) null else touchListener)
            ViewCompat.requestApplyInsets(binding.root)
            fitVideoSurface()
            if (!isInPip) { configureSystemBars(); scheduleViewportChange() }
        }
        pipHelper?.updateSessionSize(sessionWidth, sessionHeight)

        binding.tvStreamHost.text = hostName
        binding.tvStreamSession.text = appName.ifEmpty { sessionId }
        binding.tvRtt.text = ""
        binding.btnBack.setOnClickListener { returnHome() }
        binding.btnSwitchApp.setOnClickListener { returnHome() }
        binding.streamDock.dockHome.setOnClickListener { returnHome() }
        binding.streamDock.dockSettings.setOnClickListener { showAppSettings() }
        binding.streamDock.dockKeyboard.setOnClickListener { binding.surfaceVideo.toggleKeyboard() }
        binding.streamDock.dockClose.isVisible = !borrowedDesktop && !secondDisplay
        binding.streamDock.dockSettings.isVisible = !secondDisplay
        binding.streamDock.dockDivider.isVisible = binding.streamDock.dockClose.isVisible
        binding.streamDock.dockClose.contentDescription = getString(R.string.close_named_app, appName.ifEmpty { sessionId })
        binding.streamDock.dockClose.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.close_named_app, appName.ifEmpty { sessionId }))
                .setMessage(R.string.close_app_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.close_app) { _, _ ->
                    signalingClient?.stopSession(sessionId)
                    showConnectingOverlay("Closing app…")
                }.show()
        }
        binding.btnKeyboard.setOnClickListener {
            binding.surfaceVideo.toggleKeyboard()
        }
        binding.btnDisconnect.setOnClickListener { returnHome() }
        binding.btnRetry.setOnClickListener { retryConnection() }

        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        showConnectingOverlay("Connecting to $hostName…")

        // Handle system back gesture: close drawer first, then disconnect.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
                        binding.drawerLayout.closeDrawer(GravityCompat.END)
                    } else {
                        returnHome()
                    }
                }
            }
        )

        if (controlReturned) {
            showConnectingOverlay(desktopEndMessage(), showRetry = false)
            return
        }

        if (StreamNotificationPermission.requestOnce(this)) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        StreamNotificationControl.claim(notificationOwner)
        startService(StreamService.createStartIntent(this, hostName, notificationOwner.token))

        initializeWebRTC()

        connectSignaling()

        networkMonitor = NetworkMonitor(this)
        networkJob = scope.launch {
            networkMonitor?.observe()?.collect { event ->
                if (controlReturned || leaving) return@collect
                when (event) {
                    is NetworkMonitor.NetworkEvent.Lost -> {
                        Log.w(TAG, "Network lost")
                        runOnUiThread {
                            showConnectingOverlay("Network connection lost...")
                        }
                    }
                    is NetworkMonitor.NetworkEvent.Available -> {
                        Log.i(TAG, "Network restored: ${event.transport}")
                        // Auto-reconnect if signaling is disconnected (and preference enabled).
                        if (preferences.autoReconnect) {
                            val currentState = signalingClient?.state?.value
                            if (currentState != SignalingClient.State.CONNECTED) {
                                Log.i(TAG, "Reconnecting signaling after network restore")
                                signalingClient?.reconnectNow()
                            }
                        }
                    }
                    is NetworkMonitor.NetworkEvent.CapabilitiesChanged -> {
                        // Logged for diagnostics; bitrate adjustment is handled
                        // by server-side ABR via NetworkStats data channel messages.
                        Log.d(TAG, "Network: ${event.transport}, down=${event.downstreamBandwidthKbps}kbps")
                    }
                }
            }
        }
    }

    private fun gestureChannel(): ((String, Boolean) -> Boolean)? {
        if (leaving || controlReturned) return null
        val current = dataChannelHandler?.takeIf { it.getState() == DataChannel.State.OPEN } ?: return null
        return { json, critical -> current.send(json, critical) }
    }

    override fun onPause() {
        appTouch?.cancel(); appMouse?.cancel(); trackpad?.cancel()
        // Let go of every gamepad so nothing stays held on the computer.
        binding.gamepadOverlay.reset()
        gamepads.removeAll()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { appTouch?.cancel(); appMouse?.cancel() }
    }

    private fun returnHome() {
        if (leaving) return
        if (secondDisplay) {
            // An extra monitor is not kept for later: remove it.
            displayTouch?.cancel()
            leaving = true
            signalingClient?.stopSession(sessionId)
            finish()
            return
        }
        appTouch?.cancel(); appMouse?.cancel()
        binding.surfaceVideo.finishInput()
        leaving = true
        listOf(binding.streamDock.dockHome, binding.streamDock.dockSettings,
            binding.streamDock.dockKeyboard, binding.streamDock.dockClose).forEach { it.isEnabled = false }
        viewportJob?.cancel(); contentResizeTimeout?.cancel()
        mediaRecoveryJob?.cancel(); mediaPauseTimeout?.cancel()
        val channel = dataChannelHandler
        if (!inputDrainSupported || channel?.getState() != DataChannel.State.OPEN ||
            pendingViewport != null || resumeAfterPause || controlReturned) {
            stopForHome()
            return
        }
        val serial = homeInputDrain.begin()
        Log.i(TAG, "Input drain requested: $serial")
        showConnectingOverlay(getString(R.string.returning_control))
        if (!channel.send("""{"type":"input_drain","v":1,"serial":$serial}""", critical = true)) {
            stopForHome(applied = false)
            return
        }
        homeDrainTimeout = scope.launch {
            delay(1500)
            if (homeInputDrain.timeout(serial) == InputDrain.Result.FAILED) {
                Log.w(TAG, "Input drain timed out: $serial")
                stopForHome(applied = false)
            }
        }
    }

    private fun stopForHome(applied: Boolean = true) {
        if (homeStopSent) return
        homeStopSent = true
        homeInputDrain.cancel()
        homeDrainTimeout?.cancel(); homeDrainTimeout = null
        if (!applied) android.widget.Toast.makeText(this, R.string.input_drain_failed, android.widget.Toast.LENGTH_LONG).show()
        if (!controlReturned) signalingClient?.stopStream(sessionId)
        autoHideHandler.postDelayed({ finish() }, 400)
    }

    override fun onDestroy() {
        homeInputDrain.cancel()
        homeDrainTimeout?.cancel()
        controlChannel = null
        contentResize = null
        contentResizeTimeout?.cancel()
        appTouch?.cancel(); appMouse?.cancel()
        trackpad?.cancel()
        appTouch = null; appMouse = null; trackpad = null
        if (!controlReturned && (!leaving || !homeStopSent) && sessionEstablished) signalingClient?.stopStream(sessionId)
        // Cancel producers before closing stream resources.
        statsJob?.cancel()
        networkJob?.cancel()
        scope.cancel()
        autoHideHandler.removeCallbacksAndMessages(null)
        iceDisconnectHandler.removeCallbacksAndMessages(null)
        wakeLockManager?.releaseAll()
        clipboardHandler?.stop()
        clipboardHandler = null
        stopNotificationService()
        // Clean shutdown order: data channel → peer → signaling → renderer
        dataChannelHandler?.close()
        dataChannelHandler = null
        peerManager?.dispose()
        peerManager = null
        releaseSignaling()
        pipHelper = null
        hapticFeedback = null
        networkMonitor = null
        touchHandler = null
        keyboardHandler = null
        cursorController = null
        perfOverlay = null
        drawerWindowAdapter = null
        if (rendererInitialized) {
            binding.surfaceVideo.release()
            rendererInitialized = false
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("session_id", sessionId)
        outState.putInt("width", sessionWidth)
        outState.putInt("height", sessionHeight)
        outState.putDouble("scale", sessionScale)
        outState.putBoolean("resume_session", sessionEstablished)
        outState.putBoolean("control_returned", controlReturned)
        outState.putBoolean("desktop_session_unavailable", desktopSessionUnavailable)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::preferences.isInitialized) configureSystemBars()
    }

    override fun onStart() {
        super.onStart()
        getSystemService(android.hardware.input.InputManager::class.java).registerInputDeviceListener(gamepadDevices, null)
    }

    override fun onStop() {
        getSystemService(android.hardware.input.InputManager::class.java).unregisterInputDeviceListener(gamepadDevices)
        super.onStop()
    }

    /** Controllers attached to the phone go to the computer as gamepads. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!leaving && !controlReturned && physicalGamepads.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (!leaving && !controlReturned && physicalGamepads.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::binding.isInitialized) applyGameLayout()
        ViewCompat.requestApplyInsets(binding.root)
        fitVideoSurface()
        scheduleViewportChange()
    }

    private fun scheduleViewportChange() {
        if (wholeDesktop || secondDisplay || controlReturned) return
        viewportJob?.cancel()
        viewportJob = scope.launch {
            kotlinx.coroutines.delay(if (contentResize != null) 20 else 350)
            if (!sessionEstablished || leaving || isInPictureInPictureMode || signalingClient?.state?.value != SignalingClient.State.CONNECTED) return@launch
            val viewport = currentViewport()
            val content = contentResize
            val workArea = content?.withinSurface(viewport, binding.videoContainer.width, binding.videoContainer.height, imeVisible || content.cropping)
            if (content != null && workArea != null && pendingViewport == null) {
                if (content.height != workArea.height) { appTouch?.cancel(); appMouse?.cancel() }
                content.update(workArea)
                fitVideoSurface()
                sendContentResize(content.nextRequest())
                return@launch
            }
            if (viewport.width == sessionWidth && viewport.height == sessionHeight && viewport.scale == sessionScale) return@launch
            beginViewportResize(currentViewport(forCanvas = true))
        }
    }

    private fun beginViewportResize(viewport: AppViewport.Size) {
        if (wholeDesktop || secondDisplay) return
        contentResizeTimeout?.cancel()
        contentResize = null
        appTouch?.cancel(); appMouse?.cancel(); trackpad?.cancel()
        val alreadyResizing = pendingViewport != null
        pendingViewport = viewport
        if (!alreadyResizing) {
            showConnectingOverlay("Fitting app to your screen…")
            if (borrowedDesktop) {
                pauseBorrowedMedia()
            } else {
                closeLocalMedia()
                signalingClient?.stopStream(sessionId)
            }
        }
    }

    private fun pauseBorrowedMedia() {
        mediaPauseTimeout?.cancel()
        val client = signalingClient ?: return
        client.pauseStream(sessionId)
        mediaPauseTimeout = scope.launch {
            kotlinx.coroutines.delay(3000)
            if (!leaving && client === signalingClient) {
                pendingViewport = null
                resumeAfterPause = false
                reconnecting.set(false)
                closeLocalMedia()
                client.stopStream(sessionId)
                showConnectingOverlay("Desktop stream did not pause", showRetry = true)
            }
        }
    }

    private fun closeLocalMedia() {
        inputDrainSupported = false
        controlChannel = null
        dataChannelHandler?.close(); dataChannelHandler = null
        peerManager?.close()
    }

    private fun fallbackContentResize() {
        contentResizeDisabled = true
        contentResize = null
        contentResizeTimeout?.cancel()
        if (!leaving && sessionEstablished) beginViewportResize(currentViewport(forCanvas = true))
    }

    private fun sendContentResize(request: ContentResize.Request?) {
        if (request == null) return
        val content = contentResize ?: return
        val sent = dataChannelHandler?.send(
            """{"type":"content_size","v":1,"width":${request.width},"height":${request.height},"serial":${request.serial}}""", critical = true,
        ) == true
        if (!sent) { fallbackContentResize(); return }
        contentResizeTimeout?.cancel()
        contentResizeTimeout = scope.launch {
            kotlinx.coroutines.delay(1000)
            if (contentResize === content && content.pending?.serial == request.serial) fallbackContentResize()
        }
    }

    private fun currentViewport(forCanvas: Boolean = false): AppViewport.Size {
        if (wholeDesktop || secondDisplay || isInPictureInPictureMode) return AppViewport.Size(sessionWidth, sessionHeight, sessionScale)
        val dm = resources.displayMetrics
        val density = appSettings.density(dm.density.toDouble())
        if (binding.videoContainer.width > 0 && binding.videoContainer.height > 0) {
            val (width, height) = appSettings.canvas(binding.videoContainer.width,
                binding.videoContainer.height + (if (forCanvas && imeVisible) imeOcclusion else 0))
            return AppViewport.forSurface(width, height, density, AppViewport.resolutionLimit(preferences.resolution))
        }
        val content = binding.streamContent
        return AppViewport.create((content.width - content.paddingLeft - content.paddingRight).takeIf { it > 0 } ?: dm.widthPixels,
            (content.height - content.paddingTop - content.paddingBottom).takeIf { it > 0 } ?: dm.heightPixels,
            density, AppViewport.resolutionLimit(preferences.resolution))
    }

    private fun sendViewport(viewport: AppViewport.Size) {
        signalingClient?.resizeSession(sessionId, viewport.width, viewport.height, viewport.scale, preferences.maxFps)
    }

    private fun startAtCurrentViewport() {
        val viewport = currentViewport(forCanvas = true)
        if (viewport.width != sessionWidth || viewport.height != sessionHeight || viewport.scale != sessionScale) {
            pendingViewport = viewport
            showConnectingOverlay("Fitting app to your screen…")
            sendViewport(viewport)
        } else {
            showConnectingOverlay("Starting stream…")
            signalingClient?.startStream(sessionId)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter PiP when user presses home during streaming
        pipHelper?.enterPipMode()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipHelper?.onPipModeChanged(isInPictureInPictureMode)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (leaving) return true
        if (controlReturned) return super.onKeyDown(keyCode, event)
        val msg = keyboardHandler?.onKeyEvent(keyCode, event)
        if (msg != null) {
            dataChannelHandler?.send(msg, critical = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (leaving) return true
        if (controlReturned) return super.onKeyUp(keyCode, event)
        val msg = keyboardHandler?.onKeyEvent(keyCode, event)
        if (msg != null) {
            dataChannelHandler?.send(msg, critical = true)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // -----------------------------------------------------------------------
    // WebRTC setup
    // -----------------------------------------------------------------------

    private fun initializeWebRTC() {
        peerManager = PeerManager(object : PeerManager.Listener {
            override fun onLocalSdp(sdp: SessionDescription) {
                Log.i(TAG, "Sending SDP answer to host")
                signalingClient?.sendSdp(
                    sessionId = sessionId,
                    sdpType = sdp.type.canonicalForm(),
                    sdp = sdp.description,
                )
            }

            override fun onLocalIceCandidate(candidate: IceCandidate) {
                signalingClient?.sendIce(
                    sessionId = sessionId,
                    candidate = candidate.sdp,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                )
            }

            override fun onVideoTrackReady() {
                Log.i(TAG, "Video track ready, rendering started")
                startStatsPolling()
            }

            override fun onDataChannelReady(dc: DataChannel) {
                Log.i(TAG, "Data channel ready: ${dc.label()}")
                runOnUiThread {
                    if (leaving || isDestroyed) return@runOnUiThread
                    contentResize = null
                    contentResizeTimeout?.cancel()
                    inputDrainSupported = false
                    controlChannel = dc
                    dataChannelHandler = DataChannelHandler(dc, initialMessages = listOf(
                        """{"type":"stream_config","v":1}""",
                        ControlMessageSerializer.bitrate(preferences.maxBitrateKbps),
                    )) { message -> runOnUiThread {
                        if (controlChannel === dc && dataChannelHandler?.getState() == DataChannel.State.OPEN) handleServerMessage(message)
                    } }
                }
            }

            override fun onConnectionStateChanged(state: IceConnectionState) {
                Log.i(TAG, "ICE state: $state")
                runOnUiThread {
                    if (controlReturned || leaving) return@runOnUiThread
                    when (state) {
                        IceConnectionState.CONNECTED,
                        IceConnectionState.COMPLETED -> {
                            // Cancel any pending disconnect timeout.
                            iceDisconnectHandler.removeCallbacks(iceDisconnectRunnable)
                            mediaRecoveryJob?.cancel()
                            mediaRecoveryJob = null
                            mediaRecovery.reset()
                            binding.surfaceVideo.addFrameListener({ hideConnectingOverlay() }, 0f)
                        }
                        IceConnectionState.DISCONNECTED -> {
                            showConnectingOverlay("Reconnecting...")
                            // Schedule reconnect if ICE doesn't recover.
                            iceDisconnectHandler.removeCallbacks(iceDisconnectRunnable)
                            if (preferences.autoReconnect) {
                                iceDisconnectHandler.postDelayed(
                                    iceDisconnectRunnable,
                                    ICE_DISCONNECT_TIMEOUT_MS
                                )
                            }
                        }
                        IceConnectionState.FAILED -> {
                            iceDisconnectHandler.removeCallbacks(iceDisconnectRunnable)
                            showConnectingOverlay("Connection failed", showRetry = true)
                            hapticFeedback?.vibrate(HapticFeedback.DISCONNECT_VIBRATE_MS)
                            scheduleMediaRecovery()
                        }
                        IceConnectionState.CLOSED -> {
                            iceDisconnectHandler.removeCallbacks(iceDisconnectRunnable)
                            showConnectingOverlay("Connection closed", showRetry = true)
                        }
                        else -> {}
                    }
                }
            }

            override fun onError(message: String) {
                Log.e(TAG, "PeerManager error: $message")
                runOnUiThread {
                    if (!controlReturned) showConnectingOverlay("Error: $message")
                }
            }
        })

        peerManager?.initialize(this)

        val eglCtx = peerManager?.getEglBaseContext()
        if (eglCtx == null) {
            Log.e(TAG, "EGL context is null — GPU initialization failed")
            showConnectingOverlay("GPU initialization failed", showRetry = true)
            return
        }
        binding.surfaceVideo.init(eglCtx, object : org.webrtc.RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                Log.i(TAG, "First video frame rendered launch_to_frame_ms=${android.os.SystemClock.elapsedRealtime() - launchedAtMs}")
                hideConnectingOverlay()
            }
            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                Log.i(TAG, "Video resolution: ${videoWidth}x${videoHeight} rotation=$rotation")
                runOnUiThread {
                    this@StreamActivity.videoWidth = if (rotation % 180 == 0) videoWidth else videoHeight
                    this@StreamActivity.videoHeight = if (rotation % 180 == 0) videoHeight else videoWidth
                    pipHelper?.updateSessionSize(this@StreamActivity.videoWidth, this@StreamActivity.videoHeight)
                    fitVideoSurface()
                }
            }
        })
        binding.surfaceVideo.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.surfaceVideo.setEnableHardwareScaler(false)
        rendererInitialized = true

        peerManager?.setVideoSink(binding.surfaceVideo)
    }

    // -----------------------------------------------------------------------
    // Signaling
    // -----------------------------------------------------------------------

    /**
     * Attach to the computer's shared, already-authenticated connection
     * (owned by [com.enzimos.remote.connection.ConnectionManager]). The
     * stream never opens or closes the link itself; it holds a lease so the
     * link survives while the stream runs in the background.
     */
    private fun connectSignaling() {
        val manager = com.enzimos.remote.connection.ConnectionManager.get(this)
        val connection = manager.connection(hostId)
        if (connection == null) {
            showConnectingOverlay(getString(R.string.computer_unreachable), showRetry = false)
            return
        }
        val client = connection.client
        signalingClient = client
        if (!connectionLease) {
            manager.acquireLease()
            connectionLease = true
        }

        signalingJobs += scope.launch {
            client.state.collect { state ->
                if (controlReturned) {
                    showConnectingOverlay(desktopEndMessage(), showRetry = false)
                    return@collect
                }
                when (state) {
                    SignalingClient.State.CONNECTED -> {
                        if (sessionEstablished) {
                            // Reconnection — try resuming the existing session
                            // (keeps compositor and app alive).
                            showConnectingOverlay("Resuming session...")
                            client.resumeSession(sessionId)
                        } else {
                            // First connection — create a new session.
                            showConnectingOverlay("Creating session...")
                            requestAppSession()
                        }
                    }
                    SignalingClient.State.RECONNECTING -> {
                        showConnectingOverlay("Reconnecting to signaling...")
                    }
                    SignalingClient.State.DISCONNECTED -> {
                        showConnectingOverlay("Disconnected", showRetry = true)
                    }
                    else -> {}
                }
            }
        }

        signalingJobs += scope.launch {
            client.messages.collect { msg ->
                handleHostMessage(msg)
            }
        }

        signalingJobs += scope.launch {
            client.rttMs.collect { rtt ->
                if (rtt >= 0) {
                    runOnUiThread {
                        binding.tvRtt.text = getString(R.string.stream_rtt, rtt)
                    }
                }
            }
        }

        if (client.state.value != SignalingClient.State.CONNECTED) connection.start()
    }

    /** Stop observing the shared connection; it stays open for the home screen. */
    private fun releaseSignaling() {
        signalingJobs.forEach { it.cancel() }
        signalingJobs.clear()
        signalingClient = null
        if (connectionLease) {
            connectionLease = false
            com.enzimos.remote.connection.ConnectionManager.get(this).releaseLease()
        }
    }

    /**
     * Handle a server message received via the data channel.
     *
     * Dispatches to [ServerMessageParser] and updates UI state accordingly.
     */
    private fun handleServerMessage(json: String) {
        // Message payloads can contain credentials or shared content.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Server data channel message (${json.length} chars)")
        }
        val msg = com.enzimos.remote.webrtc.ServerMessageParser.parse(json) ?: return
        when (msg) {
            is com.enzimos.remote.webrtc.ServerMessageParser.ServerMessage.InputDrainAck -> {
                Log.i(TAG, "Input drain acknowledged: ${msg.serial}, applied=${msg.applied}")
                when (homeInputDrain.acknowledge(msg.serial, msg.applied)) {
                    InputDrain.Result.COMPLETE -> stopForHome()
                    InputDrain.Result.FAILED -> stopForHome(applied = false)
                    InputDrain.Result.IGNORE -> {}
                }
            }
            is com.enzimos.remote.webrtc.ServerMessageParser.ServerMessage.StreamConfig -> {
                inputDrainSupported = msg.inputDrain
                if (!leaving && !wholeDesktop && msg.contentResize && !contentResizeDisabled && pendingViewport == null) {
                    contentResize = ContentResize(AppViewport.Size(sessionWidth, sessionHeight, sessionScale))
                    fitVideoSurface()
                    scheduleViewportChange()
                }
            }
            is com.enzimos.remote.webrtc.ServerMessageParser.ServerMessage.ContentSizeAck -> {
                val content = contentResize ?: return
                if (content.pending?.serial != msg.serial) return
                contentResizeTimeout?.cancel()
                if (!msg.applied) { fallbackContentResize(); return }
                content.acknowledge(msg.serial)
                sendContentResize(content.nextRequest())
            }
            is com.enzimos.remote.webrtc.ServerMessageParser.ServerMessage.AbrStatus -> {
                runOnUiThread {
                    perfOverlay?.updateServerBitrate(msg.currentKbps)
                }
            }
            is com.enzimos.remote.webrtc.ServerMessageParser.ServerMessage.WindowList -> {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Window list received: ${msg.windows.size} windows")
                }
                runOnUiThread {
                    updateDrawerWindows(msg.windows)
                    if (pendingSheetShow) {
                        pendingSheetShow = false
                        showWindowSwitcher(msg.windows)
                    }
                }
            }
            is com.enzimos.remote.webrtc.ServerMessageParser.ServerMessage.Clipboard -> {
                clipboardHandler?.setFromHost(msg.text)
            }
            is com.enzimos.remote.webrtc.ServerMessageParser.ServerMessage.GamepadRumble -> rumble(msg)
            is com.enzimos.remote.webrtc.ServerMessageParser.ServerMessage.Unknown -> {
                Log.w(TAG, "Unknown server message type: ${msg.type}")
            }
        }
    }

    private fun handleHostMessage(msg: HostMessage) {
        val sourceSessionId = when (msg) {
            is HostMessage.SessionCreated -> msg.msg.sessionId
            is HostMessage.SessionResumed -> msg.msg.sessionId
            is HostMessage.SessionResized -> msg.msg.sessionId
            is HostMessage.SessionStopped -> msg.msg.sessionId
            is HostMessage.SessionError -> msg.msg.sessionId
            is HostMessage.SessionEvent -> msg.msg.sessionId
            is HostMessage.StreamStarted -> msg.msg.sessionId
            is HostMessage.StreamStopped -> msg.msg.sessionId
            is HostMessage.Sdp -> msg.msg.sessionId
            is HostMessage.Ice -> msg.msg.sessionId
            else -> null
        }
        if (sourceSessionId != null && sourceSessionId != sessionId) return
        if (leaving && msg !is HostMessage.StreamStopped && msg !is HostMessage.SessionStopped) {
            if (msg is HostMessage.SessionError ||
                (msg is HostMessage.SessionEvent && msg.msg.event == "stopped_externally")) stopForHome(applied = false)
            return
        }
        if (controlReturned && !leaving) return
        when (msg) {
            is HostMessage.SessionCreated -> {
                Log.i(TAG, "Session created: ${msg.msg.sessionId}")
                sessionEstablished = true
                sessionWidth = msg.msg.width
                sessionHeight = msg.msg.height
                touchHandler?.updateSessionSize(sessionWidth, sessionHeight)
                sessionScale = msg.msg.scale
                // A landscape monitor fills the phone sideways; the user can still lock rotation.
                if (desktopView && !preferences.lockOrientation) {
                    requestedOrientation = if (sessionWidth >= sessionHeight) ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                        else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                }
                startAtCurrentViewport()
            }

            is HostMessage.SessionResumed -> {
                Log.i(TAG, "Session resumed: ${msg.msg.sessionId} (${msg.msg.width}x${msg.msg.height})")
                sessionEstablished = true
                if (msg.msg.width > 0 && msg.msg.height > 0) {
                    sessionWidth = msg.msg.width
                    sessionHeight = msg.msg.height
                    touchHandler?.updateSessionSize(sessionWidth, sessionHeight)
                }
                sessionScale = msg.msg.scale
                startAtCurrentViewport()
            }

            is HostMessage.StreamStarted -> {
                Log.i(TAG, "Stream started for $sessionId")
                showConnectingOverlay("Negotiating WebRTC...")
                intervalStats.reset()
                binding.surfaceVideo.addFrameListener({ hideConnectingOverlay() }, 0f)
                // Create peer connection and wait for SDP offer
                peerManager?.createPeerConnection()
            }

            is HostMessage.Sdp -> {
                Log.i(TAG, "Received SDP ${msg.msg.sdpType}")
                peerManager?.setRemoteDescription(msg.msg.sdpType, msg.msg.sdp)
            }

            is HostMessage.Ice -> {
                peerManager?.addIceCandidate(msg.msg.candidate, msg.msg.sdpMLineIndex)
            }

            is HostMessage.StreamStopped -> {
                Log.i(TAG, "Stream stopped: ${msg.msg.reason}")
                mediaPauseTimeout?.cancel()
                mediaPauseTimeout = null
                if (leaving) {
                    if (!homeStopSent) stopForHome(applied = false)
                    runOnUiThread { finish() }
                    return
                }
                pendingViewport?.let { closeLocalMedia(); sendViewport(it); return }
                if (resumeAfterPause) {
                    resumeAfterPause = false
                    closeLocalMedia()
                    reconnecting.set(false)
                    signalingClient?.resumeSession(sessionId)
                    return
                }
                showConnectingOverlay("Stream stopped: ${msg.msg.reason}", showRetry = true)
                peerManager?.close()
                dataChannelHandler?.close()
                dataChannelHandler = null
            }

            is HostMessage.SessionStopped -> {
                Log.i(TAG, "Session stopped: ${msg.msg.reason}")
                runOnUiThread { finish() }
            }

            is HostMessage.SessionResized -> {
                Log.i(TAG, "Session resized: ${msg.msg.width}x${msg.msg.height}")
                sessionWidth = msg.msg.width
                sessionHeight = msg.msg.height
                sessionScale = msg.msg.scale
                touchHandler?.updateSessionSize(sessionWidth, sessionHeight)
                resetZoom()
                val requested = pendingViewport
                if (requested != null) {
                    if (requested.width != sessionWidth || requested.height != sessionHeight || requested.scale != sessionScale) {
                        sendViewport(requested)
                    } else {
                        pendingViewport = null
                        signalingClient?.startStream(sessionId)
                    }
                }
            }

            is HostMessage.SessionEvent -> {
                Log.i(TAG, "Session event: ${msg.msg.event}")
                when (msg.msg.event) {
                    "stopped_externally" -> if (borrowedDesktop && msg.msg.sessionId == sessionId) {
                        endDesktopControl(unavailable = false)
                    }
                    "app_exited", "compositor_crashed" -> if (msg.msg.sessionId == sessionId && !borrowedDesktop) {
                        val name = appName.ifEmpty { getString(R.string.app_generic_name) }
                        val detail = msg.msg.detail?.trim().orEmpty()
                        runOnUiThread {
                            endSession(when {
                                msg.msg.event == "compositor_crashed" -> getString(R.string.app_crashed, name)
                                detail.isNotEmpty() -> getString(R.string.app_failed_to_start, name, detail)
                                else -> getString(R.string.app_closed_on_computer, name)
                            })
                        }
                    }
                    else -> {}
                }
            }

            is HostMessage.SessionError -> {
                val reason = msg.msg.error
                Log.w(TAG, "Session error for ${msg.msg.sessionId}: $reason")
                when (SessionErrorRecovery.action(sessionId, msg.msg.sessionId, reason, sessionEstablished, borrowedDesktop)) {
                    SessionErrorRecovery.Action.IGNORE -> Unit
                    SessionErrorRecovery.Action.END_DESKTOP -> endDesktopControl(unavailable = true)
                    SessionErrorRecovery.Action.RECREATE_PRIVATE -> {
                        sessionEstablished = false
                        requestAppSession()
                    }
                    SessionErrorRecovery.Action.RESUME -> {
                        sessionEstablished = true
                        signalingClient?.resumeSession(sessionId)
                    }
                    SessionErrorRecovery.Action.REPORT -> runOnUiThread {
                        showConnectingOverlay(friendlyError(reason), showRetry = true)
                        if (MediaRecovery.isTransportFailure(reason)) scheduleMediaRecovery()
                    }
                }
            }

            is HostMessage.Error -> {
                Log.e(TAG, "Server error: ${msg.msg.error}")
                runOnUiThread {
                    showConnectingOverlay(friendlyError(msg.msg.error), showRetry = true)
                }
            }

            is HostMessage.Pong -> {
            }

            else -> {
                Log.d(TAG, "Unhandled message type: ${msg.javaClass.simpleName}")
            }
        }
    }

    private fun stopNotificationService() {
        if (StreamNotificationControl.release(notificationOwner)) {
            stopService(android.content.Intent(this, StreamService::class.java))
        }
    }

    private fun desktopEndMessage(): String = getString(
        if (desktopSessionUnavailable) R.string.desktop_session_unavailable else R.string.desktop_control_returned)

    private fun endDesktopControl(unavailable: Boolean) {
        desktopSessionUnavailable = unavailable
        if (unavailable) signalingClient?.stopStream(sessionId)
        endSession(desktopEndMessage())
    }

    /** The session is over (app gone, desktop returned): stop media and offer Home only. */
    private fun endSession(message: String) {
        controlReturned = true
        mediaRecoveryJob?.cancel(); mediaRecoveryJob = null
        mediaPauseTimeout?.cancel(); mediaPauseTimeout = null
        viewportJob?.cancel(); contentResizeTimeout?.cancel()
        pendingViewport = null; resumeAfterPause = false; reconnecting.set(false)
        statsJob?.cancel(); networkJob?.cancel()
        closeLocalMedia()
        binding.surfaceVideo.keepScreenOn = false
        wakeLockManager?.releaseAll()
        stopNotificationService()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.surfaceVideo.windowToken, 0)
        showConnectingOverlay(message, showRetry = false)
    }

    /** Second display: keyboard text and keys go to the desktop's focused window. */
    private fun forwardDisplayKey(json: String) {
        val message = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return
        val type = (message["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val keys = displayKeys ?: return
        when (type) {
            "text" -> (message["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(keys::text)
            "key" -> {
                val code = (message["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: return
                val pressed = (message["pressed"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                keys.key(code, pressed)
            }
        }
    }

    private fun requestAppSession() {
        if (leaving || controlReturned) return
        if (secondDisplay) {
            val client = signalingClient
            if (desktopView) {
                // Stream at most the phone's own pixels (landscape); zoom shows detail.
                val metrics = resources.displayMetrics
                val long = maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(1280, 3840)
                val short = minOf(metrics.widthPixels, metrics.heightPixels).coerceIn(720, 2160)
                // A phone screen is narrower than 16:9; allow a 16:9 monitor its full height.
                val bound = maxOf(short, long * 9 / 16)
                client?.sendRaw(com.enzimos.remote.signaling.encodeClientMessage(
                    com.enzimos.remote.signaling.DesktopViewStartMessage(sessionId = sessionId, width = long, height = bound, output = desktopOutput)))
                return
            }
            binding.videoContainer.doOnLayout {
                if (leaving || sessionEstablished || client !== signalingClient) return@doOnLayout
                // About 1.5 desktop pixels per phone dp: readable desktop UI, sharp video.
                val density = resources.displayMetrics.density.coerceAtLeast(1f)
                fun size(px: Int) = ((px * 1.5f / density).toInt() and 1.inv()).coerceIn(320, 4096)
                sessionWidth = size(binding.videoContainer.width)
                sessionHeight = size(binding.videoContainer.height)
                sessionScale = 1.0
                client?.sendRaw(com.enzimos.remote.signaling.encodeClientMessage(
                    com.enzimos.remote.signaling.SecondDisplayStartMessage(sessionId = sessionId, width = sessionWidth, height = sessionHeight)))
            }
            return
        }
        if (wholeDesktop) {
            signalingClient?.sendRaw(com.enzimos.remote.signaling.encodeClientMessage(
                com.enzimos.remote.signaling.DesktopAttachMessage(sessionId = sessionId)))
            return
        }
        if (appId.isBlank()) {
            showConnectingOverlay("This app session is no longer available. Return home to open it again.", showRetry = false)
            return
        }
        val client = signalingClient
        binding.videoContainer.doOnLayout {
            if (leaving || controlReturned || sessionEstablished || client !== signalingClient) return@doOnLayout
            val viewport = if (isInPictureInPictureMode) AppViewport.Size(sessionWidth, sessionHeight, sessionScale) else currentViewport()
            sessionWidth = viewport.width; sessionHeight = viewport.height; sessionScale = viewport.scale
            touchHandler?.updateSessionSize(sessionWidth, sessionHeight)
            client?.sendRaw(com.enzimos.remote.signaling.encodeClientMessage(
                com.enzimos.remote.signaling.AppLaunchMessage(
                    sessionId = sessionId, appId = appId, width = sessionWidth, height = sessionHeight,
                    scale = sessionScale, refreshHz = preferences.maxFps,
                )
            ))
        }
    }

    private fun retryConnection() {
        if (leaving || controlReturned || isDestroyed) return
        Log.i(TAG, "User initiated retry")
        mediaRecoveryJob?.cancel()
        mediaRecoveryJob = null
        mediaRecovery.reset()
        showConnectingOverlay("Reconnecting to $hostName…")

        dataChannelHandler?.close()
        dataChannelHandler = null
        releaseSignaling()
        reconnecting.set(false)
        intervalStats.reset()

        // dispose() calls close() internally — no need for separate close()
        peerManager?.dispose()
        peerManager = null
        if (rendererInitialized) {
            binding.surfaceVideo.release()
            rendererInitialized = false
        }
        initializeWebRTC()
        connectSignaling()
    }

    private fun scheduleMediaRecovery() {
        if (leaving || controlReturned || isDestroyed || !preferences.autoReconnect || pendingViewport != null || mediaRecoveryJob?.isActive == true) return
        val delayMs = mediaRecovery.nextDelayMs() ?: return
        val client = signalingClient
        val recoveringSession = sessionId
        Log.i(TAG, "Scheduling transport recovery in ${delayMs}ms")
        showConnectingOverlay("Connection lost; retrying…", showRetry = true)
        mediaRecoveryJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!leaving && !isDestroyed && preferences.autoReconnect && pendingViewport == null &&
                signalingClient === client && sessionId == recoveringSession) {
                reconnect()
            }
        }
    }

    private fun reconnect() {
        if (leaving || controlReturned || isDestroyed) return
        // Only one retry may handle a burst of ICE state changes.
        if (!reconnecting.compareAndSet(false, true)) {
            Log.d(TAG, "reconnect() already in progress, skipping")
            return
        }

        if (borrowedDesktop && signalingClient?.state?.value == SignalingClient.State.CONNECTED) {
            resumeAfterPause = true
            pauseBorrowedMedia()
            return
        }

        dataChannelHandler?.close()
        dataChannelHandler = null
        peerManager?.close()

        // A media retry resumes its session; lost shared sources require a new Home selection.
        scope.launch {
            try {
                val client = signalingClient ?: return@launch
                val connected = withTimeoutOrNull(30_000L) {
                    client.state.first {
                        it == com.enzimos.remote.signaling.SignalingClient.State.CONNECTED
                    }
                }
                if (controlReturned || leaving) return@launch
                if (connected != null) {
                    Log.i(TAG, "Signaling reconnected, resuming session")
                    client.resumeSession(sessionId)
                } else {
                    Log.w(TAG, "Signaling not reconnected after 30s, giving up stream restart")
                    runOnUiThread {
                        showConnectingOverlay("Connection lost", showRetry = true)
                    }
                }
            } finally {
                reconnecting.set(false)
            }
        }
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    /** Map raw server error strings to user-friendly messages. */
    private fun friendlyError(raw: String): String = Companion.friendlyError(raw)

    private fun showConnectingOverlay(status: String, showRetry: Boolean = false) {
        runOnUiThread {
            appTouch?.cancel(); appMouse?.cancel()
            binding.overlayConnecting.visibility = View.VISIBLE
            binding.tvOverlayStatus.text = status
            binding.btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
            binding.btnDisconnect.isVisible = !leaving
            binding.btnDisconnect.setText(R.string.remote_home)
            binding.streamDock.dockKeyboard.isEnabled = !controlReturned && !leaving
            binding.btnKeyboard.isEnabled = !controlReturned && !leaving
            binding.progressConnecting.isVisible = !controlReturned && !showRetry
        }
    }

    private fun hideConnectingOverlay() {
        runOnUiThread {
            if (!controlReturned && !leaving) binding.overlayConnecting.visibility = View.GONE
        }
    }

    /** Start polling WebRTC stats every 2 seconds for the perf overlay. */
    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (true) {
                // Poll every 1s for faster adaptive bitrate response.
                kotlinx.coroutines.delay(1000)
                peerManager?.getStats { report ->
                    if (report == null) return@getStats
                    var framesDecoded = 0L
                    var framesDropped = 0L
                    var bytesReceived = 0L
                    var rtt = signalingClient?.rttMs?.value ?: -1L
                    var frameWidth = 0
                    var frameHeight = 0
                    var codec = ""
                    var packetLoss = 0.0
                    var receiverSample: com.enzimos.remote.webrtc.ReceiverStatsTracker.Sample? = null
                    val selectedPair = report.statsMap.values.firstOrNull { it.type == "transport" }
                        ?.members?.get("selectedCandidatePairId") as? String

                    // RTT and jitter from RTCP feedback (remote-inbound-rtp).
                    var remoteRttMs = 0.0
                    var remoteJitterMs = 0.0
                    var audioLevel = -1.0

                    for ((_, stats) in report.statsMap) {
                        when (stats.type) {
                            "inbound-rtp" -> {
                                val members = stats.members
                                if ((members["kind"] ?: members["mediaType"]) == "audio") {
                                    audioLevel = (members["audioLevel"] as? Number)?.toDouble() ?: audioLevel
                                    continue
                                }
                                if ((members["kind"] ?: members["mediaType"]) != "video") continue
                                framesDecoded += (members["framesDecoded"] as? Number)?.toLong() ?: 0
                                framesDropped += (members["framesDropped"] as? Number)?.toLong() ?: 0
                                bytesReceived += (members["bytesReceived"] as? Number)?.toLong() ?: 0
                                frameWidth = (members["frameWidth"] as? Number)?.toInt() ?: frameWidth
                                frameHeight = (members["frameHeight"] as? Number)?.toInt() ?: frameHeight
                                val lost = (members["packetsLost"] as? Number)?.toLong() ?: 0
                                val received = (members["packetsReceived"] as? Number)?.toLong() ?: 0
                                receiverSample = com.enzimos.remote.webrtc.ReceiverStatsTracker.Sample(
                                    streamId = stats.id,
                                    timestampMs = android.os.SystemClock.elapsedRealtime(),
                                    packetsReceived = received, packetsLost = lost,
                                    framesDecoded = framesDecoded, bytesReceived = bytesReceived,
                                    totalDecodeSeconds = (members["totalDecodeTime"] as? Number)?.toDouble() ?: 0.0,
                                    jitterBufferSeconds = (members["jitterBufferDelay"] as? Number)?.toDouble() ?: 0.0,
                                    jitterBufferEmitted = (members["jitterBufferEmittedCount"] as? Number)?.toLong() ?: 0,
                                    framesDropped = (members["framesDropped"] as? Number)?.toLong(),
                                    nacks = (members["nackCount"] as? Number)?.toLong(),
                                    plis = (members["pliCount"] as? Number)?.toLong(),
                                    retransmissions = (members["retransmittedPacketsReceived"] as? Number)?.toLong(),
                                    freezes = (members["freezeCount"] as? Number)?.toLong(),
                                    freezeSeconds = (members["totalFreezesDuration"] as? Number)?.toDouble(),
                                )
                                // Codec ID reference for resolution
                                val decoderImpl = members["decoderImplementation"] as? String
                                if (decoderImpl != null) codec = decoderImpl
                                // Jitter from inbound stats (in seconds, convert to ms)
                                val jitter = (members["jitter"] as? Number)?.toDouble() ?: 0.0
                                if (jitter > 0.0) remoteJitterMs = jitter * 1000.0
                            }
                            "candidate-pair" -> {
                                if (stats.id != selectedPair) continue
                                val members = stats.members
                                val roundTrip = (members["currentRoundTripTime"] as? Number)?.toDouble() ?: 0.0
                                if (roundTrip > 0.0) remoteRttMs = roundTrip * 1000.0
                            }
                            else -> {}
                        }
                    }

                    // The host uses these reports as ABR heartbeats even when packet loss is zero.
                    val interval = receiverSample?.let(intervalStats::update)
                    packetLoss = interval?.packetLoss ?: 0.0
                    if (remoteRttMs > 0) rtt = remoteRttMs.toLong()
                    if (BuildConfig.DEBUG && interval != null) {
                        Log.d(TAG, "receiver_stats fps=${interval.fps} kbps=${interval.bitrateKbps} loss=$packetLoss rtt_ms=$rtt decode_ms=${interval.decodeMs} jitter_buffer_ms=${interval.jitterBufferMs} ${interval.recoveryFields()} audio_level=$audioLevel")
                    }
                    val handler = dataChannelHandler
                    if (handler != null && interval != null) {
                        val statsMsg = ControlMessageSerializer.networkStats(
                            packetLoss = packetLoss,
                            rttMs = if (remoteRttMs > 0.0) remoteRttMs else (rtt.toDouble().coerceAtLeast(0.0)),
                            jitterMs = remoteJitterMs
                        )
                        handler.send(statsMsg, critical = false)
                    }

                    connectionStats.record(ConnectionStats.Sample(
                        timestampMs = android.os.SystemClock.elapsedRealtime(),
                        rttMs = rtt,
                        packetLoss = packetLoss,
                        jitterMs = remoteJitterMs,
                        framesDecoded = framesDecoded,
                        framesDropped = framesDropped,
                        bytesReceived = bytesReceived,
                    ))

                    val overlay = perfOverlay ?: return@getStats
                    val (fps, bitrateKbps) = overlay.calculateMetrics(framesDecoded, bytesReceived)
                    val resolution = if (frameWidth > 0 && frameHeight > 0) {
                        "${frameWidth}x${frameHeight}"
                    } else {
                        "${sessionWidth}x${sessionHeight}"
                    }
                    runOnUiThread {
                        overlay.update(
                            com.enzimos.remote.video.PerformanceOverlay.Stats(
                                fps = fps,
                                rttMs = rtt,
                                bitrateKbps = bitrateKbps,
                                framesDecoded = framesDecoded,
                                framesDropped = framesDropped,
                                messagesSent = dataChannelHandler?.getMessagesSent() ?: 0,
                                messagesDropped = dataChannelHandler?.getMessagesDropped() ?: 0,
                                resolution = resolution,
                                codec = codec,
                                packetLoss = packetLoss,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun toggleToolbarOverlay() {
        if (binding.toolbarOverlay.isVisible) {
            hideToolbarOverlay()
        } else {
            showToolbarOverlay()
        }
    }

    private fun showToolbarOverlay() {
        binding.toolbarOverlay.visibility = View.VISIBLE
        autoHideHandler.removeCallbacks(autoHideRunnable)
        autoHideHandler.postDelayed(autoHideRunnable, TOOLBAR_AUTO_HIDE_MS)
    }

    private fun hideToolbarOverlay() {
        binding.toolbarOverlay.visibility = View.GONE
        autoHideHandler.removeCallbacks(autoHideRunnable)
    }

    /**
     * Request the compositor window list for app switching (toolbar button).
     * Sets a flag so the response also shows the bottom sheet.
     */
    private fun requestWindowList() {
        pendingSheetShow = true
        val msg = com.enzimos.remote.input.ControlMessageSerializer.listWindows()
        dataChannelHandler?.send(msg, critical = true)
    }

    /**
     * Request the compositor window list for the app drawer only.
     * Does not show the bottom sheet when the response arrives.
     */
    private fun requestWindowListForDrawer() {
        val msg = com.enzimos.remote.input.ControlMessageSerializer.listWindows()
        dataChannelHandler?.send(msg, critical = true)
    }

    /**
     * Show the window switching bottom sheet with the given window list.
     * Only shows mapped (visible) windows.
     */
    private fun showWindowSwitcher(windows: List<com.enzimos.remote.webrtc.ServerMessageParser.WindowInfo>) {
        val mappedWindows = windows.filter { it.mapped }
        val sheet = WindowSwitcherSheet.newInstance(mappedWindows)
        sheet.onWindowSelected = { address ->
            val msg = com.enzimos.remote.input.ControlMessageSerializer.focusWindow(address)
            dataChannelHandler?.send(msg, critical = true)
        }
        sheet.show(supportFragmentManager, "window_switcher")
    }

    // -----------------------------------------------------------------------
    // App Drawer (right-edge swipe)
    // -----------------------------------------------------------------------

    /**
     * Set up the right-edge swipe app drawer with running windows
     * and app launchers.
     */
    private fun setupAppDrawer() {
        val drawerLayout = binding.drawerLayout

        // Widen the right edge touch zone to ~40dp for easier swipe discovery
        widenDrawerEdge(drawerLayout, edgeDp = 40)

        // Request window list when drawer opens
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                requestWindowListForDrawer()
            }
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })

        drawerWindowAdapter = WindowListAdapter { address ->
            val msg = com.enzimos.remote.input.ControlMessageSerializer.focusWindow(address)
            dataChannelHandler?.send(msg, critical = true)
            drawerLayout.closeDrawer(GravityCompat.END)
        }
        binding.rvDrawerWindows.layoutManager = LinearLayoutManager(this)
        binding.rvDrawerWindows.adapter = drawerWindowAdapter

        val launchers = listOf(
            AppLauncher("terminal", "Terminal", R.drawable.ic_drawer_terminal),
            AppLauncher("calculator", "Calculator", R.drawable.ic_drawer_calculator),
            AppLauncher("text-editor", "Text Editor", R.drawable.ic_drawer_text_editor),
            AppLauncher("file-manager", "Files", R.drawable.ic_drawer_file_manager),
            AppLauncher("system-monitor", "Monitor", R.drawable.ic_drawer_system_monitor),
            AppLauncher("clock", "Clock", R.drawable.ic_drawer_clock),
        )
        val launcherAdapter = AppLauncherAdapter(launchers) { appId ->
            val msg = com.enzimos.remote.input.ControlMessageSerializer.launchApp(appId)
            dataChannelHandler?.send(msg, critical = true)
            drawerLayout.closeDrawer(GravityCompat.END)
        }
        binding.rvDrawerLaunchers.layoutManager = GridLayoutManager(this, 3)
        binding.rvDrawerLaunchers.adapter = launcherAdapter

        // Exclude right edge from system back gesture so the drawer swipe
        // takes priority (Android 10+ gesture navigation).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.drawerLayout.post {
                val edgeWidth = (40 * resources.displayMetrics.density).toInt()
                val rootW = binding.drawerLayout.width
                val rootH = binding.drawerLayout.height
                if (rootW > 0 && rootH > 0) {
                    binding.drawerLayout.systemGestureExclusionRects = listOf(
                        android.graphics.Rect(rootW - edgeWidth, 0, rootW, rootH)
                    )
                }
            }
        }
    }

    /**
     * Update the running windows list in the app drawer.
     */
    private fun updateDrawerWindows(
        windows: List<com.enzimos.remote.webrtc.ServerMessageParser.WindowInfo>,
    ) {
        val mappedWindows = windows.filter { it.mapped }
        if (mappedWindows.isEmpty()) {
            binding.tvDrawerEmptyWindows.visibility = View.VISIBLE
            binding.rvDrawerWindows.visibility = View.GONE
        } else {
            binding.tvDrawerEmptyWindows.visibility = View.GONE
            binding.rvDrawerWindows.visibility = View.VISIBLE
            drawerWindowAdapter?.updateWindows(mappedWindows)
        }
    }

    /**
     * Widen the DrawerLayout right-edge detection zone via reflection.
     * Falls back silently to the default ~20dp if reflection fails.
     */
    private fun widenDrawerEdge(drawerLayout: DrawerLayout, edgeDp: Int = 40) {
        try {
            val draggerField = DrawerLayout::class.java.getDeclaredField("mRightDragger")
            draggerField.isAccessible = true
            val dragger = draggerField.get(drawerLayout) ?: return
            val edgeField = dragger.javaClass.getDeclaredField("mEdgeSize")
            edgeField.isAccessible = true
            edgeField.setInt(dragger, (edgeDp * resources.displayMetrics.density).toInt())
        } catch (e: Exception) {
            Log.d(TAG, "Could not widen drawer edge: ${e.message}")
        }
    }

    private fun configureSystemBars() {
        if (!isInPictureInPictureMode) StreamSystemBars.configure(window, preferences.immersiveMode != "never")
    }

    private fun fitVideoSurface() {
        val width = binding.videoContainer.width
        val height = binding.videoContainer.height
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val fixedCanvas = !isInPictureInPictureMode && contentResize?.let { it.withinSurface(currentViewport(), width, height, imeVisible || it.cropping) } != null
        val keyboardView = desktopKeyboardView.takeIf { wholeDesktop && imeVisible }
        val size = keyboardView?.size(width) ?: if (fixedCanvas) {
            com.enzimos.remote.video.VideoLayout.fitWorkArea(videoWidth, videoHeight,
                contentResize?.height?.coerceIn(1, videoHeight) ?: videoHeight, width, height)
        } else com.enzimos.remote.video.VideoLayout.fit(videoWidth, videoHeight, width, height, false)
        if (keyboardView != null && !desktopKeyboardAnchored && height < size.height) {
            setVideoZoom(keyboardView.focused(size, width, height))
            desktopKeyboardAnchored = true
        }
        // A filling canvas stays anchored under the status bar while the keyboard reserves its
        // bottom; a chosen shape is letterboxed in the middle.
        val gravity = if (fixedCanvas && appSettings.shape == AppStreamSettings.SHAPE_FILL) android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            else android.view.Gravity.CENTER
        val targetW = size.width
        val targetH = size.height
        val lp = binding.surfaceVideo.layoutParams as android.widget.FrameLayout.LayoutParams
        if (lp.width != targetW || lp.height != targetH || lp.gravity != gravity) {
            lp.width = targetW; lp.height = targetH; lp.gravity = gravity
            binding.surfaceVideo.layoutParams = lp
        }
    }

    // ── Zoom & Pan ────────────────────────────────────────────────────

    /** Track two-finger pan when zoomed in. */
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false

    private fun handleTwoFingerPan(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val midX = (event.getX(0) + event.getX(1)) / 2f
        val midY = (event.getY(0) + event.getY(1)) / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastPanX = midX
                lastPanY = midY
                isPanning = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning) {
                    panX += (midX - lastPanX) / zoomScale
                    panY += (midY - lastPanY) / zoomScale
                    lastPanX = midX
                    lastPanY = midY
                    clampPan()
                    applyZoomPan()
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                isPanning = false
            }
        }
    }

    private fun updateDesktopKeyboard(visible: Boolean) {
        if (wholeDesktop && visible != imeVisible) {
            if (visible && binding.surfaceVideo.width > 0 && binding.surfaceVideo.height > 0) {
                desktopKeyboardView = com.enzimos.remote.video.DesktopKeyboardView(
                    binding.surfaceVideo.width, binding.surfaceVideo.height,
                    com.enzimos.remote.video.VideoZoom(zoomScale, panX * zoomScale, panY * zoomScale),
                    desktopAnchorX, desktopAnchorY)
                desktopKeyboardAnchored = false
            } else if (!visible) {
                desktopKeyboardView?.let { setVideoZoom(it.previous) }
                desktopKeyboardView = null
            }
        }
        imeVisible = visible
    }

    private fun rememberDesktopTap(view: View, event: MotionEvent) {
        if (!wholeDesktop) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> desktopTapCandidate = true
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> desktopTapCandidate = false
            MotionEvent.ACTION_UP -> {
                if (desktopTapCandidate && view.width > 0 && view.height > 0) {
                    desktopAnchorX = (event.x / view.width).coerceIn(0f, 1f)
                    desktopAnchorY = (event.y / view.height).coerceIn(0f, 1f)
                }
                desktopTapCandidate = false
            }
        }
    }

    private fun setVideoZoom(value: com.enzimos.remote.video.VideoZoom) {
        zoomScale = value.scale
        panX = value.x / value.scale
        panY = value.y / value.scale
        applyZoomPan()
    }

    private fun transformGesture(view: View, event: MotionEvent) {
        val stable = MotionEvent.obtain(event)
        try {
            stable.transform(view.matrix)
            stable.offsetLocation(view.left.toFloat(), view.top.toFloat())
            scaleGestureDetector.onTouchEvent(stable)
            if (isScaling) isPanning = false
            else if (zoomScale > 1.01f && stable.pointerCount >= 2) handleTwoFingerPan(stable)
        } finally { stable.recycle() }
    }

    /** Apply zoom scale and pan translation to the video surface. */
    private fun applyZoomPan() {
        binding.surfaceVideo.scaleX = zoomScale
        binding.surfaceVideo.scaleY = zoomScale
        binding.surfaceVideo.translationX = panX * zoomScale
        binding.surfaceVideo.translationY = panY * zoomScale
    }

    // ── Per-app settings ──────────────────────────────────────────────

    /** The desktop keeps its own settings; it is not a catalog app. */
    private fun settingsKey(): String = if (wholeDesktop) "desktop" else appId

    /** Rumble on the controller that holds the slot, or the phone for the on-screen one. */
    private fun rumble(msg: com.enzimos.remote.webrtc.ServerMessageParser.ServerMessage.GamepadRumble) {
        val source = gamepads.source(msg.id) ?: return
        val device = (source as? Int)?.let { android.view.InputDevice.getDevice(it) }
        val vibrator: android.os.Vibrator = when {
            device != null && android.os.Build.VERSION.SDK_INT >= 31 -> device.vibratorManager.defaultVibrator
            device != null -> @Suppress("DEPRECATION") device.vibrator
            android.os.Build.VERSION.SDK_INT >= 31 -> getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
            else -> @Suppress("DEPRECATION") (getSystemService(android.os.Vibrator::class.java) ?: return)
        }
        if (!vibrator.hasVibrator()) return
        val strength = maxOf(msg.strong, msg.weak)
        if (msg.durationMs <= 0 || strength <= 0.0) { vibrator.cancel(); return }
        val amplitude = (strength * 255).toInt().coerceIn(1, 255)
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(msg.durationMs.coerceAtMost(5000).toLong(), amplitude))
    }

    /** Landscape with the on-screen controller: the dock gives way to a menu button. */
    /** A physical controller is attached to the phone. */
    private fun physicalControllerPresent() = android.view.InputDevice.getDeviceIds().any { id ->
        android.view.InputDevice.getDevice(id)?.let { com.enzimos.remote.input.PhysicalGamepads.isGamepad(it) && !it.isVirtual } == true
    }

    private fun gameLayout() = appSettings.controller && !controlReturned && !physicalControllerPresent() &&
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyGameLayout() {
        val game = gameLayout()
        binding.gamepadOverlay.showMenuButton = game
        val dock = binding.streamDock.root
        if (dock.isVisible == !game) return
        dock.isVisible = !game
        // One resize when the mode changes, never for the popup menu.
        fitVideoSurface()
        scheduleViewportChange()
    }

    private fun showGameMenu(anchor: android.graphics.RectF) {
        val view = View(this)
        val overlay = binding.gamepadOverlay
        (overlay.parent as android.view.ViewGroup).addView(view, android.widget.FrameLayout.LayoutParams(anchor.width().toInt(), anchor.height().toInt()).apply {
            leftMargin = anchor.left.toInt(); topMargin = anchor.bottom.toInt()
        })
        val dock = binding.streamDock
        val menu = android.widget.PopupMenu(this, view)
        menu.menu.add(0, 1, 0, R.string.dock_home_description)
        menu.menu.add(0, 2, 1, R.string.show_keyboard)
        menu.menu.add(0, 3, 2, R.string.app_settings)
        menu.menu.add(0, 4, 3, R.string.dock_close_description)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> dock.dockHome.performClick()
                2 -> dock.dockKeyboard.performClick()
                3 -> dock.dockSettings.performClick()
                4 -> dock.dockClose.performClick()
            }
            true
        }
        menu.setOnDismissListener { (view.parent as? android.view.ViewGroup)?.removeView(view) }
        menu.show()
    }

    /** Show or hide the on-screen controller for this app. */
    private fun applyController() {
        val overlay = binding.gamepadOverlay
        overlay.onMenu = ::showGameMenu
        applyGameLayout()
        val show = appSettings.controller && !controlReturned && !physicalControllerPresent()
        overlay.onState = { state -> gamepads.update(overlay, state) }
        overlay.scale = appSettings.controllerSize / 100f
        overlay.opacity = appSettings.controllerOpacity / 100f
        overlay.offsets = appSettings.controllerLayout.mapValues { (_, v) -> v[0] to v[1] }
        overlay.onLayoutChanged = { offsets ->
            appSettings = appSettings.copy(controllerLayout = offsets.mapValues { (_, v) -> listOf(v.first, v.second) })
            appSettingsStore.set(hostId, settingsKey(), appSettings)
        }
        if (show) {
            overlay.isVisible = true
        } else if (overlay.isVisible) {
            overlay.reset()
            overlay.isVisible = false
            gamepads.remove(overlay)
        }
    }

    private fun applyInputMode() {
        if (appSettings.trackpad && trackpad != null) { appTouch?.cancel(); resetZoom(); trackpad?.ensureCursor() }
        else trackpad?.cancel()
    }

    private fun showAppSettings() {
        if (leaving || controlReturned) return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheet = com.enzimos.remote.databinding.SheetAppSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)
        val resizable = !wholeDesktop && !borrowedDesktop
        sheet.title.text = getString(R.string.app_settings_named, appName.ifEmpty { getString(R.string.app_generic_name) })
        sheet.sizeSection.isVisible = resizable
        sheet.inputGroup.isVisible = trackpad != null
        sheet.inputHint.isVisible = trackpad != null
        val shapes = mapOf(sheet.shapeFill.id to AppStreamSettings.SHAPE_FILL, sheet.shapeSquare.id to "1:1",
            sheet.shape43.id to "4:3", sheet.shape1610.id to "16:10")
        var applyJob: kotlinx.coroutines.Job? = null

        fun render(settings: AppStreamSettings) {
            sheet.inputGroup.check(if (settings.trackpad) sheet.inputTrackpad.id else sheet.inputTouch.id)
            sheet.inputHint.setText(if (settings.trackpad) R.string.app_settings_trackpad_hint else R.string.app_settings_touch_hint)
            sheet.sizeSlider.value = AppStreamSettings.SIZES.indexOf(settings.sizePercent).coerceAtLeast(0).toFloat()
            sheet.sizeValue.text = getString(R.string.percent_value, settings.sizePercent)
            shapes.entries.firstOrNull { it.value == settings.shape }?.let { sheet.shapeGroup.check(it.key) }
            if (resizable) {
                val viewport = currentViewport()
                sheet.canvasInfo.text = getString(R.string.app_settings_canvas,
                    (viewport.width / viewport.scale).toInt(), (viewport.height / viewport.scale).toInt())
            } else sheet.canvasInfo.setText(R.string.app_settings_desktop)
            sheet.reset.isEnabled = !settings.isDefault
        }

        fun update(next: AppStreamSettings) {
            val previous = appSettings
            if (next == previous) return
            appSettings = next
            appSettingsStore.set(hostId, settingsKey(), next)
            render(next)
            if (next.input != previous.input) applyInputMode()
            if (next.controller != previous.controller || next.controllerSize != previous.controllerSize ||
                next.controllerOpacity != previous.controllerOpacity) applyController()
            if (resizable && (next.sizePercent != previous.sizePercent || next.shape != previous.shape)) {
                // Coalesce slider steps into one resize of the app's screen.
                applyJob?.cancel()
                applyJob = scope.launch {
                    kotlinx.coroutines.delay(450)
                    if (sessionEstablished && !leaving && !controlReturned) {
                        val viewport = currentViewport(forCanvas = true)
                        if (viewport.width != sessionWidth || viewport.height != sessionHeight || viewport.scale != sessionScale) {
                            beginViewportResize(viewport)
                        }
                    }
                }
            }
        }

        render(appSettings)
        sheet.inputGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) update(appSettings.copy(input = if (id == sheet.inputTrackpad.id) AppStreamSettings.INPUT_TRACKPAD else AppStreamSettings.INPUT_TOUCH))
        }
        sheet.sizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) update(appSettings.copy(sizePercent = AppStreamSettings.SIZES[value.toInt().coerceIn(0, AppStreamSettings.SIZES.lastIndex)]))
        }
        sheet.sizeSlider.setLabelFormatter { getString(R.string.percent_value, AppStreamSettings.SIZES[it.toInt().coerceIn(0, AppStreamSettings.SIZES.lastIndex)]) }
        sheet.shapeGroup.setOnCheckedStateChangeListener { _, ids ->
            ids.firstOrNull()?.let { shapes[it] }?.let { update(appSettings.copy(shape = it)) }
        }
        sheet.controllerSwitch.isChecked = appSettings.controller
        sheet.controllerSection.isVisible = appSettings.controller
        sheet.controllerSize.value = appSettings.controllerSize.coerceIn(60, 140).toFloat()
        sheet.controllerOpacity.value = (appSettings.controllerOpacity.coerceIn(20, 90) / 5 * 5).toFloat()
        sheet.controllerSize.setLabelFormatter { getString(R.string.percent_value, it.toInt()) }
        sheet.controllerOpacity.setLabelFormatter { getString(R.string.percent_value, it.toInt()) }
        sheet.controllerSwitch.setOnCheckedChangeListener { _, checked ->
            sheet.controllerSection.isVisible = checked
            update(appSettings.copy(controller = checked))
        }
        sheet.controllerSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) update(appSettings.copy(controllerSize = value.toInt()))
        }
        sheet.controllerOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) update(appSettings.copy(controllerOpacity = value.toInt()))
        }
        sheet.controllerEdit.setOnClickListener {
            dialog.dismiss()
            binding.gamepadOverlay.reset()
            binding.gamepadOverlay.editing = true
        }
        sheet.reset.setOnClickListener { update(AppStreamSettings()); sheet.controllerSwitch.isChecked = false }
        dialog.show()
    }

    /** Reset zoom to 1x and center. */
    private fun resetZoom() {
        zoomScale = 1.0f
        panX = 0f
        panY = 0f
        applyZoomPan()
    }

    /** Clamp pan so the video doesn't scroll off screen. */
    private fun clampPan() {
        val viewW = binding.surfaceVideo.width.toFloat()
        val viewH = binding.surfaceVideo.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return

        val constrained = com.enzimos.remote.video.VideoZoom(zoomScale, panX * zoomScale, panY * zoomScale)
            .constrained(viewW, viewH, binding.videoContainer.width.toFloat(), binding.videoContainer.height.toFloat())
        panX = constrained.x / zoomScale
        panY = constrained.y / zoomScale
    }
}
