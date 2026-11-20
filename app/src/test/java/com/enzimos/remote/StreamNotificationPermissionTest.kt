package com.enzimos.remote

import android.Manifest
import android.content.Context
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class StreamNotificationPermissionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        app.getSharedPreferences("stream_notifications", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun onlySupportedSystemsPromptAndOnlyOnce() {
        assertEquals(Build.VERSION.SDK_INT >= 33, StreamNotificationPermission.requestOnce(app))
        assertFalse(StreamNotificationPermission.requestOnce(app))
    }
}
