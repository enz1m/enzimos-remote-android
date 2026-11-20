package com.enzimos.remote

import java.net.URI

/**
 * Handles deep link URIs for the enzimos remote client.
 *
 * Supports the `enzremote://` URI scheme for:
 * - Direct connection: `enzremote://connect?host=X&port=Y&token=Z`
 * - Session resume: `enzremote://resume?host=X&session=Y`
 * - QR pairing: `enzremote://pair?data=BASE64`
 *
 * All parameters are validated before use.
 */
object UriHelper {

    /** Custom URI scheme. */
    const val SCHEME = "enzremote"

    /** URI actions. */
    const val ACTION_CONNECT = "connect"
    const val ACTION_RESUME = "resume"
    const val ACTION_PAIR = "pair"

    /** URI parameter keys. */
    const val PARAM_HOST = "host"
    const val PARAM_PORT = "port"
    const val PARAM_TOKEN = "token"
    const val PARAM_SESSION = "session"
    const val PARAM_DATA = "data"
    const val PARAM_TLS = "tls"

    /**
     * Parse a deep link from a string URI.
     *
     * @param uriString The URI string to parse
     * @return Parsed deep link, or null if invalid
     */
    fun parse(uriString: String): DeepLink? {
        if (uriString.isBlank()) return null
        val uri = try {
            URI(uriString)
        } catch (_: Exception) {
            return null
        }

        if (uri.scheme != SCHEME) return null
        val action = uri.host ?: return null
        val params = parseQueryParams(uri.rawQuery ?: "")

        return when (action) {
            ACTION_CONNECT -> {
                val host = params[PARAM_HOST] ?: return null
                if (host.isBlank()) return null
                DeepLink.Connect(
                    host = host,
                    port = params[PARAM_PORT]?.toIntOrNull() ?: 9470,
                    token = params[PARAM_TOKEN] ?: "",
                    tls = params[PARAM_TLS] == "true",
                )
            }
            ACTION_RESUME -> {
                val host = params[PARAM_HOST] ?: return null
                val session = params[PARAM_SESSION] ?: return null
                if (host.isBlank() || session.isBlank()) return null
                DeepLink.Resume(
                    host = host,
                    port = params[PARAM_PORT]?.toIntOrNull() ?: 9470,
                    sessionId = session,
                )
            }
            ACTION_PAIR -> {
                val data = params[PARAM_DATA] ?: return null
                if (data.isBlank()) return null
                DeepLink.Pair(data = data)
            }
            else -> null
        }
    }

    /**
     * Build a connect deep link URI string.
     */
    fun buildConnectUri(host: String, port: Int = 9470, token: String = "", tls: Boolean = false): String {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val sb = StringBuilder("$SCHEME://$ACTION_CONNECT?$PARAM_HOST=${enc(host)}&$PARAM_PORT=$port")
        if (token.isNotEmpty()) {
            sb.append("&$PARAM_TOKEN=${enc(token)}")
        }
        if (tls) {
            sb.append("&$PARAM_TLS=true")
        }
        return sb.toString()
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').associate { param ->
            val parts = param.split('=', limit = 2)
            if (parts.size == 2) {
                java.net.URLDecoder.decode(parts[0], "UTF-8") to
                    java.net.URLDecoder.decode(parts[1], "UTF-8")
            } else {
                java.net.URLDecoder.decode(parts[0], "UTF-8") to ""
            }
        }
    }

    /** Deep link action types. */
    sealed class DeepLink {
        data class Connect(
            val host: String,
            val port: Int,
            val token: String,
            val tls: Boolean,
        ) : DeepLink()

        data class Resume(
            val host: String,
            val port: Int,
            val sessionId: String,
        ) : DeepLink()

        data class Pair(
            val data: String,
        ) : DeepLink()
    }
}
