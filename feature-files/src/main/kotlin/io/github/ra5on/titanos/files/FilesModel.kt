package io.github.ra5on.titanos.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.net.Uploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Wo man gerade ist: Freigabe und Pfad darin („“ = oberste Ebene). */
data class Location(val root: FileRoot, val path: String = "") {
    val segments: List<String> get() = path.split('/').filter { it.isNotEmpty() }
    fun child(name: String) = copy(path = if (path.isEmpty()) name else "$path/$name")
    fun upTo(count: Int) = copy(path = segments.take(count).joinToString("/"))
    fun full(name: String) = if (path.isEmpty()) name else "$path/$name"
    val place: String get() = (listOf(root.name) + segments).joinToString("/")
}

sealed interface FilesError {
    data object Offline : FilesError
    data object Exists : FilesError
    data class Other(val detail: String) : FilesError
}

data class RootsState(val roots: List<FileRoot>? = null, val error: FilesError? = null)

data class FolderState(
    val location: Location,
    val entries: List<FileEntry>? = null,
    val error: FilesError? = null,
)

/**
 * Dateien über die Routen des Webs: `/files/roots`, `/files/list`,
 * `/files/rename`, `/files/delete`, `/files/download`, Upload in Stücken.
 * Übertragungen laufen in [scope] der App weiter, wenn man den Ordner verlässt.
 */
class FilesModel(
    private val context: Context,
    private val api: TitanApi,
    val transfers: TransferStore,
    private val scope: CoroutineScope,
) {
    var roots by mutableStateOf(RootsState())
        private set
    var folder by mutableStateOf<FolderState?>(null)
        private set
    var actionError by mutableStateOf<FilesError?>(null)

    private val jobs = ConcurrentHashMap<Long, Job>()

    suspend fun loadRoots() {
        roots = try {
            RootsState(api.roots().filter { it.kind == "share" || it.kind.isEmpty() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            roots.copy(error = errorOf(e))
        }
    }

    fun open(location: Location) {
        folder = FolderState(location)
        scope.launch { reload() }
    }

    fun close() {
        folder = null
    }

    suspend fun reload() {
        val f = folder ?: return
        folder = try {
            val list = api.list(f.location.root.id, f.location.path)
            if (folder?.location != f.location) return
            f.copy(entries = list, error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            f.copy(error = errorOf(e))
        }
    }

    /** Papierkorb gibt es nur, wo die Freigabe einen hat (wie commands.ts im Web). */
    fun hasTrash(location: Location, name: String): Boolean {
        val path = location.full(name)
        return location.root.recycle || location.root.bins.orEmpty().any { path.startsWith("$it/") }
    }

    suspend fun rename(location: Location, entry: FileEntry, newName: String): Boolean = try {
        api.rename(location.root.id, location.full(entry.name), location.full(newName.trim()))
        reload()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        actionError = errorOf(e)
        false
    }

    suspend fun delete(location: Location, entry: FileEntry): Boolean = try {
        api.delete(location.root.id, listOf(location.full(entry.name)), permanent = !hasTrash(location, entry.name))
        reload()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        actionError = errorOf(e)
        false
    }

    /** In Downloads speichern (MediaStore, ohne Berechtigung). */
    fun saveToDownloads(location: Location, entry: FileEntry) {
        val t = transfers.add(Transfer(0, entry.name, Direction.Download, location.place, location.root.id, location.path, total = entry.size))
        run(t) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(entry.name))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("no Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { out ->
                    api.download(location.root.id, location.full(entry.name), out) { done, total -> progress(t.id, done, total) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    /** Teilen: erst in den Cache laden, dann mit eigener Leseerlaubnis weitergeben. */
    fun share(location: Location, entry: FileEntry, chooserTitle: String) {
        val t = transfers.add(Transfer(0, entry.name, Direction.Share, location.place, location.root.id, location.path, total = entry.size))
        run(t) {
            val dir = File(context.cacheDir, "shared/${t.id}").apply { mkdirs() }
            val file = File(dir, entry.name.replace('/', '_'))
            file.outputStream().use { out ->
                api.download(location.root.id, location.full(entry.name), out) { done, total -> progress(t.id, done, total) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(entry.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri(entry.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
    }

    /** Hochladen über „+“: Name und Größe aus dem Dokument, Konflikte mit neuem Namen „(2)“. */
    fun upload(location: Location, uri: Uri) {
        val resolver = context.contentResolver
        var name = "upload"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val t = transfers.add(Transfer(0, name, Direction.Upload, location.place, location.root.id, location.path, total = maxOf(size, 0)))
        run(t) {
            val total = if (size >= 0) size else resolver.openInputStream(uri)!!.use { s -> s.skip(Long.MAX_VALUE) }
            transfers.update(t.id) { it.copy(total = total) }
            val start = api.uploadStart(location.root.id, location.path, name, total, conflict = "rename")
            transfers.update(t.id) { it.copy(name = start.name) }
            val done = Uploader(api).upload(start, { resolver.openInputStream(uri) ?: throw IOException("cannot read") }) { d, all ->
                progress(t.id, d, all)
            }
            transfers.update(t.id) { it.copy(name = done.name) }
            if (folder?.location == location) reload()
        }
    }

    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        transfers.update(id) { it.copy(state = TransferState.Cancelled, finishedAt = Instant.now()) }
    }

    private fun progress(id: Long, done: Long, total: Long) {
        transfers.update(id) { it.copy(done = done, total = if (total > 0) total else it.total) }
    }

    private fun run(t: Transfer, work: suspend () -> Unit) {
        val job = scope.launch(Dispatchers.IO) {
            transfers.update(t.id) { it.copy(state = TransferState.Running) }
            try {
                work()
                transfers.update(t.id) { it.copy(state = TransferState.Done, done = it.total, finishedAt = Instant.now()) }
            } catch (e: CancellationException) {
                transfers.update(t.id) { it.copy(state = TransferState.Cancelled, finishedAt = Instant.now()) }
            } catch (e: Exception) {
                val err = errorOf(e)
                transfers.update(t.id) {
                    it.copy(
                        state = TransferState.Failed,
                        offline = err is FilesError.Offline,
                        reason = (err as? FilesError.Other)?.detail,
                        finishedAt = Instant.now(),
                    )
                }
            } finally {
                jobs.remove(t.id)
            }
        }
        jobs[t.id] = job
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun errorOf(e: Throwable): FilesError = when {
        e is ApiException && e.status == 409 && e.code == "file_exists" -> FilesError.Exists
        e is ApiException -> FilesError.Other("${e.status} ${e.code}".trim())
        e is IOException -> FilesError.Offline
        else -> FilesError.Other(e.javaClass.simpleName)
    }
}
