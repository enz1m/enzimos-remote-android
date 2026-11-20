package com.enzimos.remote.pairing

import android.util.Log
import com.enzimos.remote.signaling.Capabilities
import com.enzimos.remote.signaling.HelloMessage
import com.enzimos.remote.signaling.HostMessage
import com.enzimos.remote.signaling.HostTls
import com.enzimos.remote.signaling.PairRequestMessage
import com.enzimos.remote.signaling.encodeClientMessage
import com.enzimos.remote.signaling.parseHostMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Manual pairing shows a code bound to the received certificate.
 * QR pairing uses a one-time token and the supplied certificate fingerprint.
 */
class PairingSession(
    private val deviceId: String,
    private val deviceName: String,
) {
    sealed class Event {
        /** Connected; waiting for someone to approve on the computer. */
        data class AwaitingApproval(val hostName: String, val code: String, val timeoutSecs: Int) : Event()
        data class Paired(val host: DeviceStore.PairedHost) : Event()
        data class Failed(val reason: String, val unreachable: Boolean = false) : Event()
    }

    /**
     * Run the handshake over [addresses] ("host:port"), first reachable wins.
     * Cancel the collector to cancel the request on the computer as well.
     */
    fun pair(
        addresses: List<String>,
        expectedHostId: String? = null,
        expectedFingerprint: String? = null,
        qrToken: String? = null,
    ): Flow<Event> = callbackFlow {
        val nonce = HostTls.randomNonce()
        val (client, trust) = HostTls.clientWithTrust(expectedFingerprint, connectTimeoutMs = 5_000)
        // wss everywhere; loopback (adb reverse) may also fall back to ws.
        val attempts = addresses.distinct().flatMap { address ->
            if (HostTls.isLoopback(address) && expectedFingerprint == null) {
                listOf("wss://$address/v1" to address, "ws://$address/v1" to address)
            } else {
                listOf("wss://$address/v1" to address)
            }
        }
        var index = 0
        var socket: WebSocket? = null
        var finished = false

        fun finish(event: Event) {
            if (finished) return
            finished = true
            trySend(event)
            socket?.close(1000, null)
            close()
        }

        fun attempt() {
            if (index >= attempts.size) {
                finish(Event.Failed("Couldn't reach the computer. Check that it's on and on the same network.", unreachable = true))
                return
            }
            val (url, address) = attempts[index++]
            var fingerprint = ""
            var pendingHostId: String? = null
            var pendingHostName = ""
            var token: String? = null
            val request = try {
                Request.Builder().url(url).build()
            } catch (_: IllegalArgumentException) {
                attempt()
                return
            }
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Plaintext (loopback ws://) has no certificate: empty fingerprint.
                    fingerprint = if (url.startsWith("wss://")) trust.lastSeen.orEmpty() else ""
                    webSocket.send(
                        encodeClientMessage(
                            PairRequestMessage(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                clientNonce = nonce,
                                pairingToken = qrToken,
                            ),
                        ),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val message = parseHostMessage(text)) {
                        is HostMessage.PairPending -> {
                            val pending = message.msg
                            if (expectedHostId != null && pending.hostId != expectedHostId) {
                                finish(Event.Failed("A different computer answered at this address."))
                                return
                            }
                            pendingHostId = pending.hostId
                            pendingHostName = pending.hostName
                            trySend(
                                Event.AwaitingApproval(
                                    pending.hostName,
                                    HostTls.verificationCode(fingerprint, nonce, pending.hostNonce),
                                    pending.timeoutSecs,
                                ),
                            )
                        }
                        is HostMessage.PairResult -> {
                            val result = message.msg
                            if (!result.accepted || result.deviceToken.isNullOrEmpty()) {
                                finish(Event.Failed(result.reason ?: "The computer declined pairing."))
                                return
                            }
                            token = result.deviceToken
                            webSocket.send(
                                encodeClientMessage(
                                    HelloMessage(
                                        deviceId = deviceId,
                                        token = result.deviceToken,
                                        capabilities = Capabilities(
                                            videoCodecs = listOf("h264"),
                                            input = listOf("touch", "keyboard", "pointer"),
                                        ),
                                    ),
                                ),
                            )
                        }
                        is HostMessage.HelloAck -> {
                            val ack = message.msg
                            val hostId = ack.hostId ?: pendingHostId ?: expectedHostId ?: "legacy:$address"
                            val now = System.currentTimeMillis()
                            finish(
                                Event.Paired(
                                    DeviceStore.PairedHost(
                                        hostId = hostId,
                                        name = ack.hostName ?: pendingHostName.ifEmpty { address.substringBeforeLast(':') },
                                        token = token ?: return,
                                        addresses = listOf(address) + addresses.filter { it != address },
                                        fingerprint = fingerprint.ifEmpty { null },
                                        pairedAt = now,
                                        lastConnectedAt = now,
                                    ),
                                ),
                            )
                        }
                        is HostMessage.HelloReject -> finish(Event.Failed(message.msg.reason))
                        is HostMessage.Error -> finish(Event.Failed(message.msg.error))
                        else -> Unit
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (finished) return
                    Log.i(TAG, "Pairing attempt failed: ${t.javaClass.simpleName}")
                    if (pendingHostId != null) {
                        finish(Event.Failed("The connection to the computer was lost."))
                    } else if (t is javax.net.ssl.SSLPeerUnverifiedException || t.cause is java.security.cert.CertificateException) {
                        finish(Event.Failed("This computer's identity doesn't match the QR code."))
                    } else {
                        attempt()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!finished) finish(Event.Failed(reason.ifEmpty { "The computer closed the connection." }))
                }
            })
        }

        attempt()
        awaitClose {
            finished = true
            socket?.cancel()
            client.dispatcher.executorService.shutdown()
        }
    }

    private companion object {
        const val TAG = "PairingSession"
    }
}
