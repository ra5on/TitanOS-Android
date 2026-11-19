package io.github.ra5on.titanos.net

import java.net.URLDecoder

/** Eine Adresse aus der Kopplung mit dem SPKI-Wert, den das NAS für sie meldet (leer: keiner bekannt). */
data class LinkAddress(val address: ServerAddress, val spki: String)

/** Inhalt von `titanos://pair?v=1&c=<code>&a=<server>!<spki>&a=…` (QR-Code oder „Auf diesem Handy öffnen“). */
data class PairLink(val code: String, val addresses: List<LinkAddress>) {

    companion object {
        /** Ohne android.net.Uri, damit es in lokalen Tests läuft. null bei allem, was nicht passt. */
        fun parse(link: String): PairLink? {
            val s = link.trim()
            val prefix = "titanos://pair"
            if (!s.startsWith(prefix, ignoreCase = true)) return null
            val rest = s.substring(prefix.length).removePrefix("/")
            if (!rest.startsWith("?")) return null
            val query = rest.substring(1).substringBefore('#')
            val params = query.split('&').filter { it.isNotEmpty() }.map { part ->
                val k = part.substringBefore('=')
                val v = if (part.contains('=')) part.substringAfter('=') else ""
                decode(k) to decode(v)
            }
            if (params.any { it.first == null || it.second == null }) return null
            val values = params.map { it.first!! to it.second!! }
            if (values.firstOrNull { it.first == "v" }?.second != "1") return null
            val code = values.firstOrNull { it.first == "c" }?.second?.trim().orEmpty()
            if (code.isEmpty() || code.length > 128 || code.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) return null
            val addresses = values.filter { it.first == "a" }.mapNotNull { (_, a) ->
                val bang = a.lastIndexOf('!')
                val server = if (bang >= 0) a.substring(0, bang) else a
                val spki = if (bang >= 0) a.substring(bang + 1) else ""
                val address = ServerAddress.parse(server) ?: return@mapNotNull null
                if (spki.isNotEmpty() && Spki.decode(spki) == null) return@mapNotNull null
                LinkAddress(address, spki)
            }.distinctBy { it.address }
            if (addresses.isEmpty()) return null
            return PairLink(code, addresses)
        }

        private fun decode(s: String): String? = try {
            URLDecoder.decode(s, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
