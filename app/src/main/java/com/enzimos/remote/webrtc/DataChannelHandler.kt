package com.enzimos.remote.webrtc

import android.util.Log
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the WebRTC data channel for sending control messages
 * and receiving host responses.
 *
 * Features:
 * - Backpressure detection: drops touch "move" events when buffered
 *   amount exceeds threshold, keeping down/up to avoid stuck touches
 * - Message counting for diagnostics
 * - Thread-safe send operations
 */
class DataChannelHandler(
    private val dataChannel: DataChannel,
    private val initialMessages: List<String> = emptyList(),
    private val onMessage: ((String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "DataChannelHandler"

        /** Drop non-critical messages when buffered amount exceeds this. */
        private const val BACKPRESSURE_THRESHOLD_BYTES = 65_536L
    }

    private val messagesSent = AtomicLong(0)
    private val messagesDropped = AtomicLong(0)
    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun sendInitialMessages() {
        if (getState() == DataChannel.State.OPEN && initialized.compareAndSet(false, true)) {
            initialMessages.forEach { send(it, critical = true) }
        }
    }

    init {
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                // Monitor for backpressure
            }

            override fun onStateChange() {
                Log.i(TAG, "Data channel state: ${dataChannel.state()}")
                sendInitialMessages()
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary) {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    val text = String(data, Charsets.UTF_8)
                    onMessage?.invoke(text)
                }
            }
        })
        sendInitialMessages()
    }

    /**
     * Send a control message over the data channel.
     *
     * @param json The JSON control message
     * @param critical If true, always send even under backpressure.
     *        Touch down/up, key events are critical. Touch move is not.
     * @return true if sent, false if dropped or channel not ready
     */
    fun send(json: String, critical: Boolean = false): Boolean {
        if (getState() != DataChannel.State.OPEN) return false

        // Backpressure: drop non-critical messages when buffer is full
        if (!critical && dataChannel.bufferedAmount() > BACKPRESSURE_THRESHOLD_BYTES) {
            messagesDropped.incrementAndGet()
            return false
        }

        val buffer = DataChannel.Buffer(
            ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)),
            false  // text mode
        )
        val sent = try {
            dataChannel.send(buffer)
        } catch (e: Exception) {
            // DataChannel.send() can throw if the native channel was disposed
            // on another thread between the state check and the send call.
            Log.w(TAG, "DataChannel send failed: ${e.message}")
            false
        }
        if (sent) {
            messagesSent.incrementAndGet()
        }
        return sent
    }

    /** Total messages successfully sent. */
    fun getMessagesSent(): Long = messagesSent.get()

    /** Total messages dropped due to backpressure. */
    fun getMessagesDropped(): Long = messagesDropped.get()

    /** Current data channel state. */
    fun getState(): DataChannel.State = try {
        dataChannel.state()
    } catch (e: Exception) {
        DataChannel.State.CLOSED
    }

    /** Close the data channel and unregister observer to prevent leaks.
     *  Does NOT dispose the native DataChannel — PeerManager.close() owns
     *  that lifecycle to avoid double-dispose when both are called. */
    fun close() {
        try {
            dataChannel.unregisterObserver()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister observer: ${e.message}")
        }
        try {
            dataChannel.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close data channel: ${e.message}")
        }
    }
}
