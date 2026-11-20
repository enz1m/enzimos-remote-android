package com.enzimos.remote

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.pairing.DeviceStore
import com.enzimos.remote.settings.Preferences
import com.enzimos.remote.signaling.SignalingClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

import com.enzimos.remote.share.ShareManager
import com.enzimos.remote.signaling.ClipboardText
import com.enzimos.remote.signaling.FileChunkRequest
import com.enzimos.remote.signaling.HostMessage
import com.enzimos.remote.signaling.encodeClientMessage
import com.enzimos.remote.signaling.parseHostMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareProtocolTest {
    @Test fun fileMessagesParseIntoOneTypeKeyedByTransfer() {
        val offer = parseHostMessage("""{"type":"file_offer","v":1,"transfer_id":"abc12345","name":"a.jpg","size":5,"sha256":"00"}""")
        assertTrue(offer is HostMessage.File)
        offer as HostMessage.File
        assertEquals("file_offer", offer.msg.type)
        assertEquals(5L, offer.msg.size)
        val data = parseHostMessage("""{"type":"file_data","v":1,"transfer_id":"abc12345","offset":40960,"data":"AA==","eof":true}""") as HostMessage.File
        assertEquals(40960L, data.msg.offset)
        assertTrue(data.msg.eof)
        val clip = parseHostMessage("""{"type":"clipboard","v":1,"text":"hi"}""") as HostMessage.Clipboard
        assertEquals("hi", clip.msg.text)
    }

    @Test fun requestsUseTheHostFieldNames() {
        val chunk = encodeClientMessage(FileChunkRequest(transferId = "abc12345", offset = 3, data = "AA=="))
        assertTrue(chunk.contains("\"type\":\"file_chunk\"") && chunk.contains("\"transfer_id\":\"abc12345\""))
        assertTrue(encodeClientMessage(ClipboardText(text = "x")).contains("\"type\":\"clipboard\""))
    }

    @Test fun receivedNamesCannotEscapeDownloads() {
        assertEquals("passwd", ShareManager.safeName("../../etc/passwd"))
        assertEquals("bashrc", ShareManager.safeName(".bashrc"))
        assertEquals("file", ShareManager.safeName(" "))
        assertEquals(24, ShareManager.newId().length)
    }
    @Test fun clipboardFocusDoesNotReplayExistingTextOrShareSensitiveCopies() {
        val app = RuntimeEnvironment.getApplication()
        Preferences(app).clipboardSharing = true
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        val manager = mockk<ConnectionManager>()
        val hosts = listOf("one", "two").map { id ->
            ConnectionManager.HostState(DeviceStore.PairedHost(id, id, "fixture-token"), ConnectionManager.Status.ONLINE)
        }
        every { manager.hosts } returns MutableStateFlow(hosts)
        val messages = mutableListOf<Pair<String, String>>()
        for (id in listOf("one", "two")) {
            val client = mockk<SignalingClient>()
            val connection = mockk<ConnectionManager.HostConnection>()
            every { connection.client } returns client
            every { manager.connection(id) } returns connection
            every { client.sendRaw(any()) } answers { messages += id to firstArg<String>(); true }
        }
        mockkObject(ConnectionManager.Companion)
        try {
            every { ConnectionManager.get(any()) } returns manager
            val share = ShareManager::class.java.getDeclaredConstructor(Application::class.java)
                .apply { isAccessible = true }.newInstance(app)
            fun copy(text: String, sensitive: Boolean = false) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(10))
                clipboard.setPrimaryClip(ClipData.newPlainText("Fixture", text).apply {
                    if (sensitive) description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                })
            }
            copy("old fixture")
            share.syncClipboardFromPhone()
            assertTrue("Starting the app replayed an existing clipboard", messages.isEmpty())
            assertTrue(share.sendClipboardNow("one"))
            assertEquals(listOf("one" to encodeClientMessage(ClipboardText(text = "old fixture"))), messages)
            messages.clear()
            copy("fresh fixture")
            share.syncClipboardFromPhone()
            assertEquals(listOf("one", "two"), messages.map { it.first })
            assertTrue(messages.all { it.second == encodeClientMessage(ClipboardText(text = "fresh fixture")) })
            messages.clear()
            copy("sensitive fixture", true)
            share.syncClipboardFromPhone()
            assertTrue("Sensitive clipboard crossed the automatic sharing boundary", messages.isEmpty())
        } finally { unmockkObject(ConnectionManager.Companion) }
    }

}
