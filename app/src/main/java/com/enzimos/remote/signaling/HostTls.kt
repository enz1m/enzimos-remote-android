package com.enzimos.remote.signaling

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS for enzremote hosts, which present a persistent self-signed identity.
 *
 * Trust comes from pairing, not from a CA: a paired host's certificate
 * fingerprint is pinned, and any other certificate is refused. Before pairing
 * ([pin] == null) any certificate is accepted *for the pairing handshake
 * only*; the six-digit code the user compares with the computer binds the
 * certificate that was actually seen, which defeats a man in the middle.
 */
object HostTls {
    /** Lowercase hex SHA-256 of a certificate's DER encoding. */
    fun fingerprint(cert: java.security.cert.Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** Trust manager that accepts exactly the pinned certificate (or any, when unpinned). */
    class PinningTrustManager(private val pin: String?) : X509TrustManager {
        /** Fingerprint of the last certificate presented (the response handshake is not always exposed). */
        @Volatile
        var lastSeen: String? = null
            private set

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client certificates are not used")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
            val seen = fingerprint(leaf)
            lastSeen = seen
            if (pin != null && !seen.equals(pin, ignoreCase = true)) {
                throw CertificateException("certificate does not match the paired computer")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Build a client for one host. Hostname verification is replaced by the
     * pin: hosts are reached by changing LAN IPs, so names cannot be checked.
     */
    fun client(pin: String?, connectTimeoutMs: Long = 4_000): OkHttpClient =
        clientWithTrust(pin, connectTimeoutMs).first

    /** Like [client], also returning the trust manager to read [PinningTrustManager.lastSeen]. */
    fun clientWithTrust(pin: String?, connectTimeoutMs: Long = 4_000): Pair<OkHttpClient, PinningTrustManager> {
        val trust = PinningTrustManager(pin?.lowercase())
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build() to trust
    }

    /** Whether an address is loopback (allowed to use plaintext, e.g. `adb reverse`). */
    fun isLoopback(address: String): Boolean {
        val host = address.substringBeforeLast(':').trim('[', ']')
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    /**
     * Six-digit verification code shared with the host
     * (see docs/signaling-protocol.md).
     */
    fun verificationCode(fingerprint: String, clientNonce: String, hostNonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "enzremote-pair-v1\n${fingerprint.lowercase()}\n$clientNonce\n$hostNonce".toByteArray(),
        )
        val value = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
        return "%06d".format(value % 1_000_000)
    }

    fun randomNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
