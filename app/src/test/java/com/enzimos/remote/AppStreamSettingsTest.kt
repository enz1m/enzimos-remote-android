package com.enzimos.remote

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppStreamSettingsTest {
    @Test fun storedSettingsArePerComputerAndAppAndNormalized() {
        val store = AppStreamSettingsStore(RuntimeEnvironment.getApplication())
        store.set("host-a", "fsearch", AppStreamSettings(input = "trackpad", sizePercent = 73, shape = "16:10"))
        assertEquals(AppStreamSettings("trackpad", 70, "16:10"), store.get("host-a", "fsearch"))
        assertEquals(AppStreamSettings(), store.get("host-b", "fsearch"))
        assertEquals(AppStreamSettings(), store.get("host-a", "other"))
        store.set("host-a", "fsearch", AppStreamSettings())
        assertEquals(AppStreamSettings(), store.get("host-a", "fsearch"))
        store.set("host-a", "x", AppStreamSettings(shape = "bogus", input = "pen"))
        assertEquals(AppStreamSettings(), store.get("host-a", "x"))
        store.set("host-a", "y", AppStreamSettings(sizePercent = 60))
        store.forget("host-a")
        assertEquals(AppStreamSettings(), store.get("host-a", "y"))
    }
}
