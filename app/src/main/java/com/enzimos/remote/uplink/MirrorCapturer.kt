package com.enzimos.remote.uplink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Captures the phone screen through a private auto-mirror display.
 *
 * WebRTC's ScreenCapturerAndroid creates a presentation display, which apps
 * such as SecondScreen treat as a newly connected monitor (offering to change
 * resolution and rotation). A plain mirror is invisible to them. The display
 * can also be resized when the phone rotates.
 */
class MirrorCapturer(
    private val permission: Intent,
    private val onStopped: () -> Unit,
) : VideoCapturer, VideoSink {
    private lateinit var context: Context
    private lateinit var textures: SurfaceTextureHelper
    private lateinit var observer: CapturerObserver
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null

    override fun initialize(textures: SurfaceTextureHelper, context: Context, observer: CapturerObserver) {
        this.context = context
        this.textures = textures
        this.observer = observer
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(Activity.RESULT_OK, permission) ?: error("screen permission expired")
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = onStopped()
        }, textures.handler)
        textures.setTextureSize(width, height)
        val surface = Surface(textures.surfaceTexture).also { this.surface = it }
        display = projection.createVirtualDisplay(
            "enzimOS Remote mirror", width, height, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, textures.handler,
        )
        observer.onCapturerStarted(true)
        textures.startListening(this)
    }

    override fun stopCapture() {
        textures.stopListening()
        display?.release(); display = null
        surface?.release(); surface = null
        projection?.stop(); projection = null
        observer.onCapturerStopped()
    }

    /** Follows a rotation: same projection, display resized in place. */
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        textures.handler.post {
            val display = display ?: return@post
            textures.setTextureSize(width, height)
            display.resize(width, height, context.resources.displayMetrics.densityDpi)
        }
    }

    override fun dispose() = Unit

    override fun isScreencast() = true

    override fun onFrame(frame: VideoFrame) = observer.onFrameCaptured(frame)
}
