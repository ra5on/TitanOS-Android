package io.github.ra5on.titanos.docker.compose

import java.net.URI

// web/src/lib/compose/image.ts und web/src/lib/app-links.ts.

data class ImageRef(val repository: String, val tag: String, val simple: Boolean)

/** Ein festes Image zeigt seinen Tag; Ausdrücke und Digests bleiben ganz. */
fun imageReference(image: String): ImageRef {
    val raw = image.trim()
    if (raw.isEmpty() || raw.contains('$') || raw.contains('@')) return ImageRef(raw, "", false)
    val colon = raw.lastIndexOf(':')
    val tagged = colon > raw.lastIndexOf('/')
    return ImageRef(if (tagged) raw.substring(0, colon) else raw, if (tagged) raw.substring(colon + 1) else "latest", true)
}

fun validImage(image: String): Boolean {
    val raw = image.trim()
    if (raw.isEmpty() || Regex("\\s").containsMatchIn(raw)) return false
    if (raw.contains('$')) return true
    val ref = imageReference(raw)
    return !ref.simple || (ref.repository.isNotEmpty() && Regex("^[\\w][\\w.-]{0,127}$").matches(ref.tag))
}

fun withImageTag(image: String, tag: String): String {
    val ref = imageReference(image)
    return if (ref.simple) "${ref.repository}:${tag.trim()}" else image
}

/** Nur aufrufbare Web-Adressen; Zugangsdaten und Steuerzeichen sind nie Links. Leer, wenn nicht. */
fun publicURL(value: String): String {
    val text = value.trim()
    if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text) || Regex("[\\x00-\\x20\\x7f]").containsMatchIn(text)) return ""
    return try {
        val uri = URI(text)
        val host = uri.host ?: uri.rawAuthority?.substringAfterLast('@')?.substringBefore(':')
        if (host.isNullOrEmpty() || uri.rawUserInfo != null) "" else uri.normalize().toString().let { if (uri.rawPath.isNullOrEmpty()) "$it/" else it }
    } catch (_: Exception) {
        ""
    }
}

/** Adresse der Weboberfläche einer App: Port („8096“, „8096/web“) oder URL mit „[host]“. */
fun appURL(value: String, host: String, tailnet: Boolean = false): String {
    val text = value.trim()
    val portable = Regex("^\\d+(?:[/?#].*)?$").matches(text) || Regex("^https?://\\[host]:\\d+(?:[/?#].*)?$", RegexOption.IGNORE_CASE).matches(text)
    if (tailnet && (host.isEmpty() || !portable)) return ""
    val address = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return publicURL(if (text.firstOrNull()?.isDigit() == true) "http://$address:$text" else text.replace("[host]", address))
}
