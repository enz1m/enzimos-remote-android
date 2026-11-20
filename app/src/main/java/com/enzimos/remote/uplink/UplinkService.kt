package com.enzimos.remote.uplink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.core.app.ServiceCompat
import com.enzimos.remote.R
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.share.ShareManager
import com.enzimos.remote.signaling.HostMessage
import com.enzimos.remote.signaling.UplinkIceMessage
import com.enzimos.remote.signaling.UplinkStartMessage
import com.enzimos.remote.signaling.UplinkStopMessage
import com.enzimos.remote.signaling.encodeClientMessage
import com.enzimos.remote.webrtc.PeerManager
import com.enzimos.remote.webrtc.UplinkPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Sends this phone's screen (mirroring) or camera (webcam) to a computer over
 * WebRTC. Runs as a foreground service so it continues while other apps are
 * used; the computer shows a window or a PipeWire camera.
 */
class UplinkService : Service() {
    enum class Kind(val wire: String) { SCREEN("screen"), CAMERA("camera") }
    data class Active(val hostId: String, val hostName: String, val kind: Kind, val connected: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: VideoCapturer? = null
    private var textures: SurfaceTextureHelper? = null
    private var peer: UplinkPeer? = null
    private var generation = 0L
    private var leased = false
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var uplinkId = ""
    private var hostId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopUplink(notifyHost = true)
            return START_NOT_STICKY
        }
        val kind = if (intent?.getStringExtra(EXTRA_KIND) == Kind.CAMERA.wire) Kind.CAMERA else Kind.SCREEN
        val requestedHost = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        val hostName = ConnectionManager.get(this).state(requestedHost)?.name ?: "computer"
        val type = if (Build.VERSION.SDK_INT >= 30) {
            if (kind == Kind.SCREEN) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(kind, hostName), type)
        } catch (error: Exception) {
            Log.w(TAG, "Could not start in the foreground: ${error.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        stopUplink(notifyHost = true, keepService = true)
        hostId = requestedHost
        instance = this
        ConnectionManager.get(this).acquireLease()
        leased = true
        _active.value = Active(hostId, hostName, kind)
        try {
            start(kind, intent?.let { projectionData(it) }, intent?.getBooleanExtra(EXTRA_FRONT, true) ?: true)
        } catch (error: Exception) {
            Log.w(TAG, "Could not start sending video: ${error.message}")
            stopUplink(notifyHost = true)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java) else intent.getParcelableExtra(EXTRA_PROJECTION)

    private fun start(kind: Kind, projection: Intent?, front: Boolean) {
        PeerManager.ensureInitialized(this)
        val mine = generation
        val egl = EglBase.create().also { this.egl = it }
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
            .also { this.factory = it }
        val capturer: VideoCapturer = when (kind) {
            Kind.SCREEN -> MirrorCapturer(projection ?: error("no screen permission")) {
                main.post { if (generation == mine) stopUplink(notifyHost = true) }
            }
            Kind.CAMERA -> {
                val cameras = Camera2Enumerator(this)
                val name = cameras.deviceNames.firstOrNull { cameras.isFrontFacing(it) == front } ?: cameras.deviceNames.first()
                cameras.createCapturer(name, null)
            }
        }
        this.capturer = capturer
        val textures = SurfaceTextureHelper.create("uplink", egl.eglBaseContext).also { this.textures = it }
        val source = factory.createVideoSource(capturer.isScreencast).also { videoSource = it }
        capturer.initialize(textures, this, source.capturerObserver)
        val (width, height) = captureSize(kind)
        capturer.startCapture(width, height, 30)
        val track = factory.createVideoTrack("uplink-video", source).also { videoTrack = it }

        val forHost = hostId
        val forUplink = ShareManager.newId().also { uplinkId = it }
        val connection = UplinkPeer(factory, object : UplinkPeer.Listener {
            override fun onLocalSdp(sdp: SessionDescription) {
                sendTo(forHost, encodeClientMessage(UplinkStartMessage(uplinkId = forUplink, kind = kind.wire, sdp = sdp.description)))
            }
            override fun onLocalIce(candidate: IceCandidate) {
                sendTo(forHost, encodeClientMessage(UplinkIceMessage(uplinkId = forUplink, candidate = candidate.sdp, sdpMLineIndex = candidate.sdpMLineIndex)))
            }
            override fun onState(state: PeerConnection.PeerConnectionState) {
                main.post {
                    if (mine != generation) return@post
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> _active.value = _active.value?.copy(connected = true)
                        PeerConnection.PeerConnectionState.FAILED -> stopUplink(notifyHost = true)
                        else -> Unit
                    }
                }
            }
            override fun onError(message: String) {
                main.post {
                    if (mine != generation) return@post
                    Log.w(TAG, "Uplink negotiation failed")
                    stopUplink(notifyHost = true)
                }
            }
        })
        peer = connection
        connection.start(track, kind == Kind.SCREEN)
    }

    /** About 720p for the camera; the screen at most 1600 px on the long side. */
    private fun captureSize(kind: Kind): Pair<Int, Int> {
        if (kind == Kind.CAMERA) return 1280 to 720
        // The whole panel in its current rotation, system bars included.
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val scale = (1600f / maxOf(metrics.widthPixels, metrics.heightPixels)).coerceAtMost(1f)
        return ((metrics.widthPixels * scale).toInt() and 1.inv()) to ((metrics.heightPixels * scale).toInt() and 1.inv())
    }

    private fun send(json: String) = sendTo(hostId, json)

    private fun sendTo(computer: String, json: String) {
        ConnectionManager.get(this).connection(computer)?.client?.sendRaw(json)
    }

    private fun handle(message: HostMessage.Uplink) {
        if (message.msg.uplinkId != uplinkId) return
        Log.i(TAG, "${message.kind} (${message.msg.sdp.length} sdp bytes)")
        when (message.kind) {
            "uplink_answer" -> peer?.answer(message.msg.sdp)
            "uplink_ice" -> peer?.addIce(message.msg.candidate, message.msg.sdpMLineIndex)
            "uplink_input" -> message.msg.event?.let { MirrorControl.instance?.handle(it) }
            "uplink_stopped" -> stopUplink(notifyHost = false)
        }
    }

    private fun stopUplink(notifyHost: Boolean, keepService: Boolean = false) {
        generation++
        if (uplinkId.isNotEmpty() && notifyHost) send(encodeClientMessage(UplinkStopMessage(uplinkId = uplinkId)))
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); capturer = null
        peer?.close(); peer = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        textures?.dispose(); textures = null
        factory?.dispose(); factory = null
        egl?.release(); egl = null
        uplinkId = ""
        if (leased) {
            leased = false
            ConnectionManager.get(this).releaseLease()
        }
        if (!keepService) {
            _active.value = null
            if (instance === this) instance = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** A mirrored screen follows the phone's rotation. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val capturer = capturer as? MirrorCapturer ?: return
        val (width, height) = captureSize(Kind.SCREEN)
        capturer.changeCaptureFormat(width, height, 30)
    }

    override fun onDestroy() {
        stopUplink(notifyHost = true, keepService = true)
        _active.value = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun notification(kind: Kind, hostName: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.uplink_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, UplinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val title = getString(if (kind == Kind.SCREEN) R.string.uplink_screen_active else R.string.uplink_camera_active, hostName)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(title)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.uplink_stop), stop).build())
            .build()
    }



    companion object {
        private const val TAG = "UplinkService"
        private const val CHANNEL = "enzremote_uplink"
        private const val NOTIFICATION_ID = 7403
        private const val ACTION_STOP = "stop"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PROJECTION = "projection"
        private const val EXTRA_FRONT = "front"
        @Volatile private var instance: UplinkService? = null
        private val _active = MutableStateFlow<Active?>(null)
        val active: StateFlow<Active?> = _active.asStateFlow()

        fun startScreen(context: Context, hostId: String, projection: Intent) {
            context.startForegroundService(Intent(context, UplinkService::class.java)
                .putExtra(EXTRA_KIND, Kind.SCREEN.wire).putExtra(EXTRA_HOST, hostId).putExtra(EXTRA_PROJECTION, projection))
        }

        fun startCamera(context: Context, hostId: String, front: Boolean) {
            context.startForegroundService(Intent(context, UplinkService::class.java)
                .putExtra(EXTRA_KIND, Kind.CAMERA.wire).putExtra(EXTRA_HOST, hostId).putExtra(EXTRA_FRONT, front))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, UplinkService::class.java).setAction(ACTION_STOP))
        }

        fun onMessage(message: HostMessage.Uplink) {
            val service = instance ?: return
            service.main.post { service.handle(message) }
        }
    }
}

