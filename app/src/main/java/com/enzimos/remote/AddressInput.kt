package com.enzimos.remote

import com.enzimos.remote.pairing.QrCodeData

/** Validation for a computer address typed by the user. */
object AddressInput {
    private val hostname = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?$")
    private val ipv4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

    /**
     * Accepts `host`, `host:port`, `[ipv6]`, `[ipv6]:port` or a bare IPv6
     * address (optionally with a ws/wss scheme). Returns `host:port`, or null.
     */
    fun normalize(raw: String): String? {
        val text = raw.trim().removePrefix("wss://").removePrefix("ws://").substringBefore('/')
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val host: String
        val port: Int
        when {
            text.startsWith("[") -> {
                val close = text.indexOf(']')
                if (close < 0) return null
                host = text.substring(1, close)
                val rest = text.substring(close + 1)
                port = if (rest.isEmpty()) QrCodeData.DEFAULT_PORT else rest.removePrefix(":").toIntOrNull() ?: return null
                if (!rest.isEmpty() && !rest.startsWith(":")) return null
                if (!isIpv6(host)) return null
                return if (port in 1..65535) "[$host]:$port" else null
            }
            text.count { it == ':' } > 1 -> {
                // Bare IPv6 without a port.
                return if (isIpv6(text)) "[$text]:${QrCodeData.DEFAULT_PORT}" else null
            }
            text.contains(':') -> {
                host = text.substringBefore(':')
                port = text.substringAfter(':').toIntOrNull() ?: return null
            }
            else -> {
                host = text
                port = QrCodeData.DEFAULT_PORT
            }
        }
        if (port !in 1..65535) return null
        val validHost = ipv4.matches(host) || (hostname.matches(host) && !host.all { it.isDigit() || it == '.' })
        return if (validHost) "$host:$port" else null
    }

    private fun isIpv6(value: String): Boolean {
        val address = value.substringBefore('%')
        if (!address.contains(':') || address.count { it == ':' } > 7) return false
        if (address.indexOf("::") != address.lastIndexOf("::")) return false
        val groups = address.split(':')
        return groups.withIndex().all { (i, group) ->
            group.isEmpty() ||
                (group.length <= 4 && group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) ||
                (i == groups.lastIndex && ipv4.matches(group))
        }
    }
}
