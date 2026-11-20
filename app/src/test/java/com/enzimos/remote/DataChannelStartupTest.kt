package com.enzimos.remote

import com.enzimos.remote.webrtc.DataChannelHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import org.webrtc.DataChannel

class DataChannelStartupTest {
    @Test fun initialSettingsWaitForOpenAndAreSentOnce() {
        val channel = mockk<DataChannel>(relaxed = true)
        val observer = slot<DataChannel.Observer>()
        var state = DataChannel.State.CONNECTING
        every { channel.state() } answers { state }
        every { channel.registerObserver(capture(observer)) } returns Unit
        every { channel.send(any()) } returns true
        DataChannelHandler(channel, initialMessages = listOf("initial-bitrate"))
        verify(exactly = 0) { channel.send(any()) }
        state = DataChannel.State.OPEN
        observer.captured.onStateChange()
        observer.captured.onStateChange()
        verify(exactly = 1) { channel.send(any()) }
    }

    @Test fun alreadyOpenChannelSendsSettingsDuringAttachment() {
        val channel = mockk<DataChannel>(relaxed = true)
        every { channel.state() } returns DataChannel.State.OPEN
        every { channel.send(any()) } returns true
        DataChannelHandler(channel, initialMessages = listOf("initial-bitrate"))
        verify(exactly = 1) { channel.send(any()) }
    }
}
