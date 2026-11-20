package com.enzimos.remote

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import com.enzimos.remote.databinding.ActivityStreamBinding
import com.enzimos.remote.signaling.HostMessage
import com.enzimos.remote.signaling.SessionErrorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class StreamSessionLossTest {
    private lateinit var activity: StreamActivity
    private lateinit var binding: ActivityStreamBinding

    private fun field(name: String) = StreamActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(name: String) = StreamActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun message(value: HostMessage) {
        val method = StreamActivity::class.java.getDeclaredMethod("handleHostMessage", HostMessage::class.java).apply { isAccessible = true }
        method.invoke(activity, value)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun error(id: String, reason: String) =
        message(HostMessage.SessionError(SessionErrorMessage(sessionId = id, error = reason)))
    private fun parsedMessage(json: String) {
        val value = com.enzimos.remote.signaling.parseHostMessage(json)
        assertFalse(value is HostMessage.Unknown)
        message(value)
    }

    @Before fun setUp() {
        activity = Robolectric.buildActivity(StreamActivity::class.java).get()
        activity.setTheme(R.style.Theme_Enzremote_Stream)
        binding = ActivityStreamBinding.inflate(activity.layoutInflater)
        field("binding").set(activity, binding)
        field("sessionId").set(activity, "active")
        field("appId").set(activity, "fixture")
        field("borrowedDesktop").setBoolean(activity, true)
        field("sessionEstablished").setBoolean(activity, true)
    }

    @After fun tearDown() {
        (field("scope").get(activity) as CoroutineScope).cancel()
        binding.surfaceVideo.release()
    }

    @Test fun missingSharedSessionEndsWithoutAnAppCreationFallback() {
        error("active", "No such file or directory (os error 2)")
        assertTrue(field("controlReturned").getBoolean(activity))
        assertTrue(field("sessionEstablished").getBoolean(activity))
        assertTrue(field("desktopSessionUnavailable").getBoolean(activity))
        assertEquals(activity.getString(R.string.desktop_session_unavailable), binding.tvOverlayStatus.text.toString())
        assertEquals(View.GONE, binding.btnRetry.visibility)
        assertEquals(View.GONE, binding.progressConnecting.visibility)
        assertEquals(activity.getString(R.string.remote_home), binding.btnDisconnect.text.toString())
        assertFalse(binding.streamDock.dockKeyboard.isEnabled)
        assertFalse(binding.btnKeyboard.isEnabled)
    }

    @Test fun aTerminalSourceReleasesWakeAndScreenProtection() {
        val wake = WakeLockManager(activity)
        wake.acquirePartial()
        field("wakeLockManager").set(activity, wake)
        binding.surfaceVideo.keepScreenOn = true
        assertTrue(wake.isHeld())
        error("active", "session not found")
        assertFalse(wake.isHeld())
        assertFalse(binding.surfaceVideo.keepScreenOn)
    }

    @Test fun terminalRecreationDoesNotStartMediaSignalingOrWakeProtection() {
        val app = RuntimeEnvironment.getApplication()
        com.enzimos.remote.pairing.DeviceStore(app).saveHost(
            com.enzimos.remote.pairing.DeviceStore.PairedHost("fixture-host", "Fixture", "fixture-token", listOf("127.0.0.1:19472")),
        )
        com.enzimos.remote.connection.ConnectionManager.get(app).reloadHosts()
        val intent = Intent(app, StreamActivity::class.java)
            .putExtra("host_id", "fixture-host")
            .putExtra("host_address", "127.0.0.1:19472")
            .putExtra("session_id", "active")
            .putExtra("device_id", "fixture-device")
            .putExtra("shared_desktop", true)
        val saved = Bundle().apply {
            putBoolean("control_returned", true)
            putBoolean("desktop_session_unavailable", true)
            putBoolean("resume_session", true)
        }
        val controller = Robolectric.buildActivity(StreamActivity::class.java, intent).create(saved)
        try {
            val restored = controller.get()
            val views = field("binding").get(restored) as ActivityStreamBinding
            assertNull(field("peerManager").get(restored))
            assertNull(field("signalingClient").get(restored))
            assertNull(field("wakeLockManager").get(restored))
            assertFalse(views.surfaceVideo.keepScreenOn)
            assertEquals(restored.getString(R.string.desktop_session_unavailable), views.tvOverlayStatus.text.toString())
        } finally {
            controller.destroy()
        }
    }

    @Test fun foreignStopEventsCannotInterruptHomeInputDrain() {
        field("leaving").setBoolean(activity, true)
        for (json in listOf(
            """{"type":"session_event","session_id":"other","event":"stopped_externally"}""",
            """{"type":"stream_stopped","session_id":"other"}""",
            """{"type":"session_stopped","session_id":"other"}""",
        )) parsedMessage(json)
        assertFalse(field("homeStopSent").getBoolean(activity))
        assertFalse(activity.isFinishing)
    }

    @Test fun lateRetryAndLayoutCallbacksCannotReopenOrHideTheLostSource() {
        error("active", "session not found")
        invoke("retryConnection")
        invoke("requestAppSession")
        invoke("hideConnectingOverlay")
        binding.videoContainer.layout(0, 0, 1080, 2046)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(field("controlReturned").getBoolean(activity))
        assertEquals(View.VISIBLE, binding.overlayConnecting.visibility)
        assertEquals(activity.getString(R.string.desktop_session_unavailable), binding.tvOverlayStatus.text.toString())
        assertEquals(View.GONE, binding.btnRetry.visibility)
    }
}
