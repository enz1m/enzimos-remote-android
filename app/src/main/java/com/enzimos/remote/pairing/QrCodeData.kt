package com.enzimos.remote.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Accepts legacy v1 pairing payloads. See the host signaling protocol for v2 fields. */
@Serializable
data class QrCodeData(
    /** Host address or hostname. */
    val host: String,
    /** Signaling port number. */
    val port: Int = DEFAULT_PORT,
    /** Authentication token. */
    val token: String = "",
    /** Whether TLS (wss://) is required. */
    val tls: Boolean = false,
    /** Display name for the host. */
    val name: String = "",
    /** Stable host ID (payload v2). */
    val id: String = "",
    /** Every LAN address of the host, most useful first (payload v2). */
    val addrs: List<String> = emptyList(),
    /** SHA-256 fingerprint of the host TLS certificate to pin (payload v2). */
    val fp: String? = null,
) {
    /** Candidate addresses to try, primary first, without duplicates. */
    val candidateAddresses: List<String>
        get() = (listOf(host) + addrs).filter { it.isNotBlank() }.distinct()

    /** Redact the token to prevent accidental leakage in logs/crash reports. */
    override fun toString(): String =
        "QrCodeData(host=$host, port=$port, token=***, tls=$tls, name=$name, id=$id)"

    /**
     * Validate the QR code data.
     *
     * @return null if valid, error message if invalid
     */
    fun validate(): String? {
        if (host.isBlank()) return "host is required"
        if (port < 1 || port > 65535) return "port must be between 1 and 65535"
        if (token.isBlank()) return "token is required"
        if (token.length > MAX_TOKEN_LENGTH) return "token too long"
        if (name.length > MAX_NAME_LENGTH) return "name too long"
        if (fp != null && !fp.matches(Regex("[0-9a-fA-F]{64}"))) return "invalid fingerprint"
        if (id.isNotEmpty() && !id.matches(Regex("[0-9a-f]{32}"))) return "invalid host id"
        return null
    }

    companion object {
        /** Default signaling port. */
        const val DEFAULT_PORT = 9470

        /** Maximum token length in QR code. */
        const val MAX_TOKEN_LENGTH = 512

        /** Maximum name length. */
        const val MAX_NAME_LENGTH = 128

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse QR code content into [QrCodeData].
         *
         * @param content Raw string from QR code scanner
         * @return Parsed data, or null if parsing fails
         */
        fun parse(content: String): QrCodeData? {
            return try {
                val data = json.decodeFromString<QrCodeData>(content)
                if (data.validate() != null) null else data
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Encode [QrCodeData] to JSON for QR code generation.
         *
         * @return JSON string
         */
        fun encode(data: QrCodeData): String {
            return json.encodeToString(QrCodeData.serializer(), data)
        }
    }
}
