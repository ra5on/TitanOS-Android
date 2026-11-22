package io.github.ra5on.titanos.agent.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.ra5on.titanos.net.IncomingUri
import java.io.File

/** Höchstens so groß nimmt das NAS einen Anhang (maxAttachment, 20 MB). */
const val MAX_ATTACHMENT = 20L * 1024 * 1024

/**
 * Kopiert eine geteilte oder gewählte Datei sofort in den Cache der App:
 * Die Leseerlaubnis für fremde content://-Adressen endet mit der Activity
 * (TECHNIK §6). null, wenn sie nicht lesbar oder zu groß ist – und für
 * file:// und die eigenen Anbieter: die läse die App mit ihren eigenen
 * Rechten (Teilen, Ziehen und Wählen kommen von anderen Apps).
 */
fun copyToCache(context: Context, uri: Uri, mime: String? = null): Attachment? {
    if (!IncomingUri.accepts(context, uri)) return null
    val resolver = context.contentResolver
    var name = "Datei"
    var size = -1L
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    } catch (_: Exception) {
        // Ohne Namen geht es mit „Datei“ weiter.
    }
    if (size > MAX_ATTACHMENT) return null
    // Nur der Name zählt, kein Pfad aus der fremden App.
    name = name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_").trimStart('.').ifEmpty { "Datei" }
    val dir = File(context.cacheDir, "agent-share").apply { mkdirs() }
    val target = try {
        // Das Präfix braucht mindestens drei Zeichen.
        File.createTempFile("share", null, dir)
    } catch (_: Exception) {
        return null
    }
    return try {
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ATTACHMENT) throw IllegalStateException("too large")
                    out.write(buf, 0, n)
                }
            }
        } ?: throw IllegalStateException("unreadable")
        Attachment(name, target, mime ?: resolver.getType(uri))
    } catch (_: Exception) {
        target.delete()
        null
    }
}

/** Ein Wunsch von außen: Text (und Bild) aus dem Teilen-Menü oder vom Widget. */
data class AskRequest(val text: String = "", val voice: Boolean = false, val attachments: List<Attachment> = emptyList(), val failed: Boolean = false)
