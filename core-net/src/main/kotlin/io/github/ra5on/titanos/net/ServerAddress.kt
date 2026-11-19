package io.github.ra5on.titanos.net

import okhttp3.HttpUrl

/** Eine Adresse des NAS, so wie sie die Kopplung nennt: `host`, `host:port` oder `[v6]:port`; ab Werk Port 443. */
data class ServerAddress(val host: String, val port: Int = 443) {

    val server: String get() = buildString {
        append(if (host.contains(':')) "[$host]" else host)
        if (port != 443) append(':').append(port)
    }

    val kind: AddressKind get() = AddressKind.of(host)

    /** Tailscale-Namen haben ein öffentliches Zertifikat (Let's Encrypt), dann entfällt der Vergleich. */
    val hasPublicName: Boolean get() = host.lowercase().endsWith(".ts.net")

    fun baseUrl(): HttpUrl = HttpUrl.Builder().scheme("https").host(host).port(port).build()

    companion object {
        /** Wie `splitServer` am NAS; null für alles, was keine reine Adresse ist. */
        fun parse(raw: String): ServerAddress? {
            val s = raw.trim()
            if (s.isEmpty() || s.any { it in "/?#@ \\" || it.isISOControl() }) return null
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 2) return null
                val host = s.substring(1, end)
                val rest = s.substring(end + 1)
                val port = when {
                    rest.isEmpty() -> 443
                    rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                    else -> return null
                }
                return if (port in 1..65535 && host.contains(':')) ServerAddress(host, port) else null
            }
            val colons = s.count { it == ':' }
            if (colons > 1) return if (s.all { it.isLetterOrDigit() || it == ':' || it == '.' }) ServerAddress(s) else null
            if (colons == 1) {
                val host = s.substringBefore(':')
                val port = s.substringAfter(':').toIntOrNull() ?: return null
                if (host.isEmpty() || port !in 1..65535) return null
                return ServerAddress(host, port)
            }
            return ServerAddress(s)
        }
    }
}

enum class AddressKind {
    /** Im Heimnetz: wird bevorzugt. */
    LAN,
    /** Unterwegs über Tailscale. */
    TAILSCALE,
    OTHER;

    companion object {
        fun of(host: String): AddressKind {
            val h = host.lowercase()
            if (h.endsWith(".ts.net")) return TAILSCALE
            val v4 = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: -1 }
            if (v4 != null && v4.all { it in 0..255 }) {
                val (a, b) = v4[0] to v4[1]
                return when {
                    a == 100 && b in 64..127 -> TAILSCALE
                    a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) -> LAN
                    else -> OTHER
                }
            }
            if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.startsWith("fd") || h.startsWith("fe80:")) return LAN
            if (h.startsWith("fd7a:115c:a1e0:")) return TAILSCALE
            return OTHER
        }
    }
}
