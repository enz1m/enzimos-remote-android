package com.enzimos.remote

import android.app.Notification
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class StreamNotificationServiceTest {
    private class Owner(override val token: String) : StreamNotificationControl.Owner {
        var stops = 0
        override fun requestStop() { stops++ }
    }
    private val first = Owner("first-generation")
    private val second = Owner("second-generation")
    private lateinit var controller: ServiceController<StreamService>
    private lateinit var service: StreamService

    @Before fun setUp() {
        controller = Robolectric.buildService(StreamService::class.java).create()
        service = controller.get()
    }
    @After fun tearDown() {
        StreamNotificationControl.release(first)
        StreamNotificationControl.release(second)
        controller.destroy()
    }
    private fun start(owner: Owner, host: String, id: Int) {
        StreamNotificationControl.claim(owner)
        service.onStartCommand(StreamService.createStartIntent(service, host, owner.token), 0, id)
    }
    private fun action() = shadowOf(service).lastForegroundNotification.actions.single().actionIntent

    @Test fun disconnectRequestsHomeButKeepsProtectionWhileTheOwnerDrains() {
        start(first, "fixture", 1)
        val intent = shadowOf(action()).savedIntent
        assertEquals(StreamService.ACTION_STOP, intent.action)
        service.onStartCommand(intent, 0, 2)
        assertEquals(1, first.stops)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun staleNotificationCannotStopANewerOwner() {
        start(first, "old-host", 1)
        val old = shadowOf(action()).savedIntent
        start(second, "new-host", 2)
        service.onStartCommand(old, 0, 3)
        assertEquals(0, first.stops)
        assertEquals(0, second.stops)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun generationIdentityKeepsAnOldPendingIntentImmutable() {
        start(first, "old-host", 1)
        val old = action()
        val oldData = shadowOf(old).savedIntent.data
        start(second, "new-host", 2)
        val latest = action()
        assertTrue(shadowOf(old).isImmutable)
        assertTrue(shadowOf(latest).isImmutable)
        assertNotEquals(old, latest)
        assertNotEquals(oldData, shadowOf(latest).savedIntent.data)
        assertEquals(oldData, shadowOf(old).savedIntent.data)
    }
}
