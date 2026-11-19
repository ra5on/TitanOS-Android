package io.github.ra5on.titanos.files

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Eigene Kopien zum Hochladen (`files/outbox/`): für Dateien aus dem
 * Teilen-Menü (deren Leseerlaubnis endet mit der Activity) und für Quellen
 * ohne dauerhafte Leseerlaubnis. Der Auftrag löscht die Kopie danach.
 */
object Outbox {
    fun dir(context: Context) = File(context.filesDir, "outbox").apply { mkdirs() }

    @Throws(IOException::class)
    fun copy(context: Context, uri: Uri, name: String): File {
        val folder = File(dir(context), System.nanoTime().toString()).apply { mkdirs() }
        val target = File(folder, name.replace('/', '_').ifBlank { "upload" })
        try {
            (context.contentResolver.openInputStream(uri) ?: throw IOException("cannot read")).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            target.delete()
            folder.delete()
            throw if (e is IOException) e else IOException(e)
        }
        return target
    }

    /** Kopien, zu denen kein Auftrag mehr gehört (App beendet, Auftrag gelöscht). */
    fun prune(context: Context, store: TransferStore) {
        val live = store.items.filter { it.active && it.ownCopy }.mapNotNull { it.source?.let(Uri::parse)?.path }.toSet()
        dir(context).listFiles()?.forEach { folder ->
            val files = folder.listFiles().orEmpty()
            if (files.none { it.path in live }) {
                files.forEach { it.delete() }
                folder.delete()
            }
        }
    }
}
