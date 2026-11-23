package io.github.ra5on.titanos.files

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.net.Uploader
import io.github.ra5on.titanos.ui.Format
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * TitanOS in der Dateiauswahl jeder App und in „Dateien“ (Entscheidung des
 * Inhabers 21.11.2026): Freigaben als Wurzeln, lesen, schreiben, anlegen,
 * umbenennen, löschen (in den Papierkorb, wo es einen gibt), Vorschaubilder,
 * Suche und zuletzt verwendet. Gelesen wird über eine Kopie im Cache (oder
 * die Offline-Kopie); geschrieben über den Upload in Stücken wie im Web.
 * Rechte prüft immer das NAS.
 *
 * Kennung eines Dokuments: `<Freigabe>|<Pfad>`, z. B. `share/Docs|Briefe/a.docx`.
 */
class NasDocumentsProvider : DocumentsProvider() {

    private lateinit var io: Handler

    override fun onCreate(): Boolean {
        io = Handler(HandlerThread("titanos-documents").apply { start() }.looper)
        return true
    }

    private val ctx: Context get() = context!!
    private fun api(): FilesApi = FilesRuntime.access()?.api ?: throw FileNotFoundException("not paired")
    private fun titan(): TitanApi = FilesRuntime.api() ?: throw FileNotFoundException("not paired")

    @Volatile private var cached: Pair<Long, List<FileRoot>>? = null

    /** Ablagen des Kontos, 30 Sekunden zwischengespeichert (Rechte gelten am NAS ohnehin bei jeder Anfrage). */
    private fun roots(): List<FileRoot> {
        cached?.let { (at, list) -> if (System.currentTimeMillis() - at < 30_000) return list }
        val list = runBlocking { runCatching { titan().roots() }.getOrDefault(emptyList()) }
            .filter { it.kind == "share" || it.kind == "volume" || it.kind == "usb" }
        cached = System.currentTimeMillis() to list
        return list
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        if (FilesRuntime.access() == null) return c
        for (r in roots()) {
            var flags = Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_RECENTS
            if (r.writable) flags = flags or Root.FLAG_SUPPORTS_CREATE
            c.newRow().apply {
                add(Root.COLUMN_ROOT_ID, r.id)
                add(Root.COLUMN_DOCUMENT_ID, docId(r.id, ""))
                add(Root.COLUMN_TITLE, r.name)
                add(Root.COLUMN_SUMMARY, "TitanOS · " + Format.bytes(r.freeBytes))
                add(Root.COLUMN_FLAGS, flags)
                add(Root.COLUMN_ICON, ctx.applicationInfo.icon)
                add(Root.COLUMN_AVAILABLE_BYTES, r.freeBytes)
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        val (root, path) = split(documentId)
        if (path.isEmpty()) {
            val r = roots().firstOrNull { it.id == root }
            row(c, root, "", FileEntry(r?.name ?: root, dir = true), r?.writable ?: false)
            return c
        }
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val entry = runBlocking { runCatching { api().list(root, parent) }.getOrNull() }?.firstOrNull { it.name == name }
            ?: throw FileNotFoundException(documentId)
        row(c, root, path, entry, writable(root))
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        val (root, path) = split(parentDocumentId)
        val list = runBlocking { runCatching { api().list(root, path) }.getOrElse { throw FileNotFoundException(it.message) } }
        val w = writable(root)
        list.filter { !hiddenName(it.name) }.forEach { row(c, root, join(path, it.name), it, w) }
        c.setNotificationUri(ctx.contentResolver, DocumentsContract.buildChildDocumentsUri(authority(ctx), parentDocumentId))
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (pr, pp) = split(parentDocumentId)
        val (r, p) = split(documentId)
        return pr == r && (pp.isEmpty() || p.startsWith("$pp/"))
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        val found = runBlocking { runCatching { api().search(SearchRequest(rootId, "", query)) }.getOrNull() } ?: return c
        val w = writable(rootId)
        found.entries.take(200).forEach { f -> row(c, rootId, f.path, FileEntry(f.name, f.dir, f.size, f.modified), w) }
        return c
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        Recents.list(ctx).filter { it.root == rootId }.forEach { r -> row(c, r.root, r.path, FileEntry(r.path.substringAfterLast('/'), false, r.size, r.modified), writable(rootId)) }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val (root, path) = split(documentId)
        val write = mode.contains('w')
        val entry = runBlocking { runCatching { api().list(root, path.substringBeforeLast('/', "")) }.getOrNull() }?.firstOrNull { it.name == path.substringAfterLast('/') }
        val file = local(root, path, entry, needContent = !write || mode.contains('r') || mode.contains('a'))
        Recents.add(ctx, root, path, entry?.size ?: file.length(), entry?.modified.orEmpty())
        if (!write) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val m = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, m, io) { e ->
            if (e == null) upload(root, path, file)
        }
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        val (root, path) = split(documentId)
        val box = maxOf(sizeHint?.x ?: 320, sizeHint?.y ?: 320)
        val bytes = runBlocking { runCatching { api().thumb(root, path, box) }.getOrNull() } ?: throw FileNotFoundException("no thumbnail")
        val f = File(ctx.cacheDir, "doc-thumbs").apply { mkdirs() }.let { File(it, "${documentId.hashCode()}-$box.jpg") }
        f.writeBytes(bytes)
        return AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, f.length())
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val (root, dir) = split(parentDocumentId)
        val name = displayName.replace('/', '_').trim().ifEmpty { "Neu" }
        return runBlocking {
            if (mimeType == Document.MIME_TYPE_DIR) {
                var n = name
                var i = 2
                while (true) {
                    val ok = runCatching { api().mkdir(root, join(dir, n)) }
                    if (ok.isSuccess) break
                    if (i > 99) throw FileNotFoundException("exists")
                    n = "$name ($i)"; i++
                }
                docId(root, join(dir, n))
            } else {
                val withExt = if (!name.contains('.')) MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { "$name.$it" } ?: name else name
                val t = titan()
                val start = t.uploadStart(root, dir, withExt, 0, conflict = "rename")
                t.uploadFinish(start.id)
                docId(root, join(dir, start.name.ifEmpty { withExt }))
            }
        }.also { notifyChange(parentDocumentId) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (root, path) = split(documentId)
        val to = join(path.substringBeforeLast('/', ""), displayName.replace('/', '_'))
        runBlocking { titan().rename(root, path, to) }
        notifyChange(docId(root, path.substringBeforeLast('/', "")))
        return docId(root, to)
    }

    override fun deleteDocument(documentId: String) {
        val (root, path) = split(documentId)
        val r = roots().firstOrNull { it.id == root }
        val trash = r != null && (r.recycle || r.bins.orEmpty().any { path.startsWith("$it/") })
        runBlocking { titan().delete(root, listOf(path), permanent = !trash) }
        notifyChange(docId(root, path.substringBeforeLast('/', "")))
    }

    override fun getDocumentType(documentId: String): String {
        val (_, path) = split(documentId)
        return if (path.isEmpty()) Document.MIME_TYPE_DIR else super.getDocumentType(documentId)
    }

    // ---- Hilfen ----

    private fun writable(root: String) = roots().firstOrNull { it.id == root }?.writable ?: false

    /** [path] ist der volle Pfad; „“ ist die Wurzel. */
    private fun row(c: MatrixCursor, root: String, path: String, e: FileEntry, writable: Boolean) {
        var flags = 0
        if (writable) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            flags = flags or if (e.dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        }
        if (!e.dir && Kinds.hasThumb(e.name)) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId(root, path))
            add(Document.COLUMN_DISPLAY_NAME, e.name)
            add(Document.COLUMN_MIME_TYPE, if (e.dir) Document.MIME_TYPE_DIR else mimeOf(e.name))
            add(Document.COLUMN_SIZE, if (e.dir) null else e.size)
            add(Document.COLUMN_LAST_MODIFIED, Format.instant(e.modified)?.toEpochMilli())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    /** Eine lesbare Kopie: die Offline-Kopie, eine noch gültige im Cache oder frisch geladen. */
    private fun local(root: String, path: String, entry: FileEntry?, needContent: Boolean): File {
        if (!needContent) return File(ctx.cacheDir, "doc-write").apply { mkdirs() }.let { File(it, "${System.nanoTime()}-${path.substringAfterLast('/')}") }
        FilesRuntime.offline?.copyOf(root, path)?.takeIf { it.exists() && (entry == null || it.length() == entry.size) }?.let { offline ->
            val copy = File(ctx.cacheDir, "doc-write").apply { mkdirs() }.let { File(it, "${System.nanoTime()}-${offline.name}") }
            offline.copyTo(copy, overwrite = true)
            return copy
        }
        val f = NasImages.cacheFile(ctx, root, path, entry?.modified.orEmpty() + System.nanoTime())
        runBlocking { api().downloadTo(root, path, f) }
        return f
    }

    private fun upload(root: String, path: String, file: File) {
        runCatching {
            runBlocking {
                val t = titan()
                val start = t.uploadStart(root, path.substringBeforeLast('/', ""), path.substringAfterLast('/'), file.length(), conflict = "replace")
                Uploader(t).upload(start, { file.inputStream() })
            }
        }
        file.delete()
        notifyChange(docId(root, path.substringBeforeLast('/', "")))
    }

    private fun notifyChange(parentId: String) {
        ctx.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(ctx), parentId), null)
    }

    companion object {
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_MIME_TYPES,
        )
        val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun authority(context: Context) = "${context.packageName}.documents"

        fun docId(root: String, path: String) = "$root|$path"

        fun split(id: String): Pair<String, String> {
            val i = id.indexOf('|')
            if (i <= 0) throw FileNotFoundException(id)
            val path = id.substring(i + 1).trim('/')
            if (path.split('/').any { it == ".." }) throw FileNotFoundException(id)
            return id.substring(0, i) to path
        }

        fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(Kinds.ext(name)) ?: "application/octet-stream"

        /** Inhalts-Adresse einer Datei am NAS, etwa für Ziehen in eine andere App. */
        fun uriOf(context: Context, root: String, path: String) = DocumentsContract.buildDocumentUri(authority(context), docId(root, path))
    }
}

/** Zuletzt über den Anbieter oder in der App geöffnet (nur auf dem Handy, höchstens 50). */
object Recents {
    data class Item(val root: String, val path: String, val size: Long, val modified: String)

    private fun prefs(c: Context) = c.getSharedPreferences("files-recents", Context.MODE_PRIVATE)

    fun list(c: Context): List<Item> = prefs(c).getString("items", "").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { l ->
        val p = l.split('\t')
        if (p.size < 4) null else Item(p[0], p[1], p[2].toLongOrNull() ?: 0, p[3])
    }

    @Synchronized
    fun add(c: Context, root: String, path: String, size: Long, modified: String) {
        val next = (listOf(Item(root, path, size, modified)) + list(c).filterNot { it.root == root && it.path == path }).take(50)
        prefs(c).edit().putString("items", next.joinToString("\n") { "${it.root}\t${it.path.replace('\t', ' ')}\t${it.size}\t${it.modified}" }).apply()
    }
}
