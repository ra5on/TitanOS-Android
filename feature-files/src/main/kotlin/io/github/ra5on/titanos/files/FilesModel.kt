package io.github.ra5on.titanos.files

import android.content.Context
import android.content.Intent
import android.net.Uri
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
    data object ReadOnly : FilesError
    data object NoSpace : FilesError
    data object SameFolder : FilesError
    data object IntoItself : FilesError
    data object NotText : FilesError
    data object InvalidName : FilesError
    data object NoIndex : FilesError
    data object DownloadLimit : FilesError
    data object DownloadBlocked : FilesError
    data object DownloadFailed : FilesError
    data object NotFound : FilesError
    data object Locked : FilesError
    data class Other(val detail: String) : FilesError
}

/** Kopierte oder ausgeschnittene Einträge eines Ordners. */
data class Clip(val from: Location, val names: List<String>, val move: Boolean)

enum class UndoKind { Rename, Move, Trash }

data class UndoOffer(val id: String, val kind: UndoKind, val count: Int)

data class Notice(val text: Int)

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
    val offline: OfflineStore,
) {
    init {
        // Ein fertiger Upload im Hintergrund lädt die offene Ordneransicht neu.
        FilesRuntime.onUploaded = { root, dir ->
            val f = folder
            if (f != null && f.location.root.id == root && f.location.path == dir) scope.launch { reload() }
        }
    }
    var roots by mutableStateOf(RootsState())
        private set
    var folder by mutableStateOf<FolderState?>(null)
        private set
    var actionError by mutableStateOf<FilesError?>(null)

    private val jobs = ConcurrentHashMap<Long, Job>()

    val appContext: Context get() = context

    /** Die weiteren Routen (Vorschau, Kopieren, Papierkorb …); null ohne Kopplung. */
    // Bei jedem Zugriff neu: das Modell kann vor dem Anschluss der Laufzeit entstehen (AppGraph im Start der App).
    val access: FilesAccess? get() = FilesRuntime.access()
    val files: FilesApi? get() = access?.api
    private val prefsApi: PrefsApi? get() = access?.let { PrefsApi(it.connection) }

    /** Werkzeuge für Besitzer und Administratoren (USB, Partitionen, Docker) und „Vergleichen“. */
    val admin: AdminApi? get() = access?.let { AdminApi(it.connection) }

    /** Ob das Konto die Werkzeuge für Administratoren sieht (wie im Web; das NAS prüft selbst). */
    val isAdmin: Boolean get() = access?.sessions?.user?.role.let { it == "owner" || it == "admin" }

    /** Docker-Apps, die Dateien öffnen, mit ihren Endungen. */
    var dockerTypes by mutableStateOf<List<OpenerTypes>>(emptyList())
        private set

    fun dockerFor(name: String): Boolean = Kinds.ext(name).let { e -> e.isNotEmpty() && dockerTypes.any { e in it.types } }

    /** Einstellungen des Dateimanagers im Konto (mit dem Web geteilt). */
    var prefs by mutableStateOf(FilePrefs())
        private set

    /** Kopiert oder ausgeschnitten, zum Einfügen in einem anderen Ordner. */
    var clipboard by mutableStateOf<Clip?>(null)

    /** Aufträge des NAS (Kopieren, Verschieben, aus dem Netz laden, Entpacken). */
    var nasJobs by mutableStateOf<List<io.github.ra5on.titanos.files.Job>>(emptyList())
        private set

    /** Was sich gerade rückgängig machen lässt (30 Minuten am NAS). */
    var undo by mutableStateOf<UndoOffer?>(null)

    /** Kurze Meldung nach einer Aktion (Text-ID und Wert). */
    var notice by mutableStateOf<Notice?>(null)

    private var polling: Job? = null

    // ---------- Seiten ----------

    private val stack = androidx.compose.runtime.mutableStateListOf<FilesRoute>(FilesRoute.Roots)

    /** Die oberste Seite im Reiter. */
    val route: FilesRoute get() = stack.last()

    fun push(r: FilesRoute) {
        stack.add(r)
    }

    /** In einem weiteren Fenster: zurück bis zum Start schließt das Fenster. */
    var onLeaveRoots: (() -> Unit)? = null

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (route !is FilesRoute.Folder) close()
        if (route == FilesRoute.Roots) onLeaveRoots?.invoke()
    }

    /** Zweites Tippen auf „Dateien“ im Dock (PLAN 117): zurück zu den Freigaben, ohne Fenster zu schließen. */
    fun toRoot() {
        if (stack.size <= 1) return
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        selection = emptySet()
        close()
    }

    /** Datei in der Vorschau neben der Liste (ab „Split“); null: keine. */
    var previewing by mutableStateOf<String?>(null)

    fun replaceTop(r: FilesRoute) {
        stack[stack.lastIndex] = r
    }

    /** Einen Ordner öffnen: von einer anderen Seite aus oben drauf, im Ordner an seiner Stelle. */
    fun openFolder(location: Location) {
        selection = emptySet()
        if (route is FilesRoute.Folder) replaceTop(FilesRoute.Folder(location)) else push(FilesRoute.Folder(location))
    }

    /** Markierte Einträge des offenen Ordners. */
    var selection by mutableStateOf<Set<String>>(emptySet())

    /** Eigenschaften einer Datei anzeigen (Name, Pfad); die offene Seite zeigt das Blatt. */
    var showProperties by mutableStateOf<Pair<String, String>?>(null)

    // ---------- Office ----------

    var webapps by mutableStateOf<List<WebApp>>(emptyList())
        private set
    private var fileApps: Map<String, String> = emptyMap()

    fun loadOffice() {
        val api = files ?: return
        scope.launch {
            runCatching { api.webapps() }.onSuccess { webapps = it }
            runCatching { api.fileApps() }.onSuccess { fileApps = it }
            admin?.let { a -> runCatching { a.openerTypes() }.onSuccess { dockerTypes = it } }
        }
    }

    /** Das Office-Programm für eine Datei, wenn TitanOS eines dafür hat. */
    fun officeFor(name: String): WebApp? = OfficeChoice.any(name, webapps, fileApps)

    /** Name des Kontos (der eigene Papierkorb liegt unter diesem Namen). */
    val userName: String? get() = access?.sessions?.user?.name

    suspend fun loadRoots() {
        roots = try {
            RootsState(api.roots())
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

    suspend fun rename(location: Location, entry: FileEntry, newName: String): Boolean = renameTo(location, entry, newName)

    suspend fun delete(location: Location, entry: FileEntry): Boolean = deleteMany(location, listOf(entry))

    /** In Downloads speichern (MediaStore, ohne Berechtigung) – als Auftrag, der das Schließen der App übersteht. */
    fun saveToDownloads(location: Location, entry: FileEntry, paired: Boolean = false) {
        TransferQueue.enqueueDownload(context, location.root.id, location.path, location.place, entry.name, entry.size, paired)
    }

    /** Teilen: erst in den Cache laden, dann mit eigener Leseerlaubnis weitergeben; [via] statt der Verbindung des Dateimanagers. */
    fun share(location: Location, entry: FileEntry, chooserTitle: String, via: TitanApi? = null) = handOver(location, listOf(entry), chooserTitle, view = false, via = via)

    /** Mehrere Dateien auf einmal teilen (ACTION_SEND_MULTIPLE). */
    fun shareMany(location: Location, entries: List<FileEntry>, chooserTitle: String) = handOver(location, entries.filter { !it.dir }, chooserTitle, view = false)

    /** Mit einer anderen App öffnen (ACTION_VIEW, nur lesend). */
    fun openWith(location: Location, entry: FileEntry, chooserTitle: String, via: TitanApi? = null) = handOver(location, listOf(entry), chooserTitle, view = true, via = via)

    private fun handOver(location: Location, entries: List<FileEntry>, chooserTitle: String, view: Boolean, via: TitanApi? = null) {
        if (entries.isEmpty()) return
        val label = if (entries.size == 1) entries[0].name else entries.joinToString(", ") { it.name }
        val t = transfers.add(Transfer(0, label, Direction.Share, location.place, location.root.id, location.path, total = entries.sumOf { it.size }))
        run(t) {
            val dir = File(context.cacheDir, "shared/${t.id}").apply { mkdirs() }
            var before = 0L
            val uris = entries.map { entry ->
                val file = File(dir, entry.name.replace('/', '_'))
                file.outputStream().use { out ->
                    (via ?: api).download(location.root.id, location.full(entry.name), out) { done, _ -> progress(t.id, before + done, t.total) }
                }
                before += entry.size
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
            val intent = when {
                view -> Intent(Intent.ACTION_VIEW).apply { setDataAndType(uris[0], mimeOf(entries[0].name)) }
                uris.size == 1 -> Intent(Intent.ACTION_SEND).apply { type = mimeOf(entries[0].name); putExtra(Intent.EXTRA_STREAM, uris[0]) }
                else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = entries.map { mimeOf(it.name) }.distinct().singleOrNull() ?: "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }.apply {
                clipData = android.content.ClipData.newRawUri(entries[0].name, uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
    }

    /**
     * Hochladen über „+“: Name und Größe aus dem Dokument, Konflikte mit neuem
     * Namen „(2)“. Der Auftrag braucht die Quelle über das Schließen der App
     * hinaus: dauerhafte Leseerlaubnis, sonst eine eigene Kopie.
     */
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
        val kept = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        if (kept) {
            TransferQueue.enqueueUpload(context, location.root.id, location.path, location.place, name, size, uri, conflict = FilesSettings.get(context).uploadConflict)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val copy = Outbox.copy(context, uri, name)
                TransferQueue.enqueueUpload(context, location.root.id, location.path, location.place, name, copy.length(), Uri.fromFile(copy), ownCopy = true, conflict = FilesSettings.get(context).uploadConflict)
            } catch (e: IOException) {
                actionError = FilesError.Other("read")
            }
        }
    }

    /**
     * Ordner hochladen (SAF-Ordnerauswahl): Unterordner bleiben erhalten, jede
     * Datei ist ein eigener, fortsetzbarer Auftrag wie die übrigen Uploads.
     */
    fun uploadTree(location: Location, tree: Uri) {
        val resolver = context.contentResolver
        runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val conflict = FilesSettings.get(context).uploadConflict
        scope.launch(Dispatchers.IO) {
            act {
                val top = android.provider.DocumentsContract.getTreeDocumentId(tree)
                val name = resolver.query(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, top), arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Ordner"
                walkTree(location, tree, top, location.full(name.replace('/', '_')), conflict, 0)
                reload()
            }
        }
    }

    private suspend fun walkTree(location: Location, tree: Uri, docId: String, nasDir: String, conflict: String, depth: Int) {
        if (depth > 32) return
        try {
            files!!.mkdir(location.root.id, nasDir)
        } catch (e: ApiException) {
            if (!(e.status == 409 && e.code == "file_exists")) throw e
        }
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            android.provider.DocumentsContract.Document.COLUMN_SIZE,
        )
        val list = ArrayList<Array<String?>>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) list += arrayOf(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3).toString())
        }
        val place = location.copy(path = nasDir).place
        for ((id, name, mime, size) in list) {
            if (id == null || name.isNullOrBlank()) continue
            val clean = name.replace('/', '_')
            if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                walkTree(location, tree, id, "$nasDir/$clean", conflict, depth + 1)
            } else {
                TransferQueue.enqueueUpload(
                    context, location.root.id, nasDir, place, clean, size?.toLongOrNull() ?: -1L,
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id), conflict = conflict,
                )
            }
        }
    }

    fun cancel(id: Long) {
        // Teilen läuft im Speicher, alles andere als Auftrag.
        jobs.remove(id)?.cancel()
        if (transfers.get(id)?.direction == Direction.Share) {
            transfers.update(id) { it.copy(state = TransferState.Cancelled, finishedAtMs = System.currentTimeMillis()) }
        } else {
            TransferQueue.cancel(context, id, api)
        }
    }

    fun retry(id: Long) = TransferQueue.retry(context, id)

    // ---------- offline ----------

    fun isOffline(location: Location, entry: FileEntry) = offline.isPinned(location.root.id, location.full(entry.name))

    fun setOffline(location: Location, entry: FileEntry, on: Boolean) {
        val path = location.full(entry.name)
        if (on) offline.pin(location.root.id, location.root.name, path, entry.dir) else offline.unpin(location.root.id, path)
        if (on) OfflineSync.now(context, offline)
    }

    fun syncOfflineNow() = OfflineSync.now(context, offline)

    fun setOfflineWifiOnly(on: Boolean) {
        offline.changeWifiOnly(on)
        OfflineSync.schedule(context, offline)
    }

    /** Eine Offline-Kopie lesend mit einer anderen App öffnen. */
    fun openOffline(root: String, path: String, chooserTitle: String): Boolean {
        val file = offline.copyOf(root, path) ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, TransferWorker.mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(view, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }

    // ---------- Ansicht ----------

    fun loadPrefs() {
        val api = prefsApi ?: return
        scope.launch { runCatching { api.load() }.onSuccess { prefs = it } }
    }

    fun updatePrefs(next: FilePrefs) {
        prefs = next
        val api = prefsApi ?: return
        scope.launch { runCatching { api.save(next) }.onFailure { notice = Notice(R.string.files_prefs_failed) } }
    }

    /** Die Einträge des offenen Ordners in der gewählten Reihenfolge. */
    fun sorted(location: Location, entries: List<FileEntry>): List<FileEntry> {
        val (_, sort, desc) = prefs.forFolder(location.root.id, location.path)
        return sortEntries(entries, localSort ?: sort, desc, prefs.showHidden)
    }

    /** „Typ“ kennt das Web nicht; die App merkt es sich nur für sich. */
    var localSort by mutableStateOf<SortBy?>(null)

    // ---------- Anlegen ----------

    suspend fun mkdir(location: Location, name: String): Boolean = act {
        files!!.mkdir(location.root.id, location.full(name.trim()))
        reload()
    }

    suspend fun createFile(location: Location, name: String): Boolean = act {
        files!!.create(location.root.id, location.full(name.trim()))
        reload()
    }

    // ---------- Löschen und Umbenennen mit Rückgängig ----------

    suspend fun deleteMany(location: Location, entries: List<FileEntry>): Boolean = act {
        val trash = entries.all { hasTrash(location, it.name) }
        val r = api.delete(location.root.id, entries.map { location.full(it.name) }, permanent = !trash)
        if (r.undo.isNotEmpty()) undo = UndoOffer(r.undo, UndoKind.Trash, entries.size)
        reload()
    }

    /** Löscht Pfade einer Ablage (etwa aus „Doppelte“). */
    suspend fun deletePaths(root: FileRoot, paths: List<String>, trash: Boolean): Boolean = act {
        val r = api.delete(root.id, paths, permanent = !trash)
        if (r.undo.isNotEmpty()) undo = UndoOffer(r.undo, UndoKind.Trash, paths.size)
        reload()
    }

    suspend fun renameTo(location: Location, entry: FileEntry, newName: String): Boolean = act {
        val r = api.rename(location.root.id, location.full(entry.name), location.full(newName.trim()))
        if (r.undo.isNotEmpty()) undo = UndoOffer(r.undo, UndoKind.Rename, 1)
        reload()
    }

    suspend fun undoLast(): Boolean {
        val u = undo ?: return false
        undo = null
        return act {
            files!!.undo(u.id)
            reload()
            notice = Notice(R.string.files_undone)
        }
    }

    // ---------- Kopieren und Verschieben ----------

    fun copyToClipboard(location: Location, entries: List<FileEntry>, move: Boolean) {
        clipboard = Clip(location, entries.map { it.name }, move)
    }

    /** Vor dem Einfügen: was im Ziel schon liegt (wie CopyDialog im Web). */
    suspend fun inspectPaste(target: Location): Inspection? {
        val clip = clipboard ?: return null
        return try {
            files!!.inspect(InspectRequest(clip.from.root.id, clip.names.map { clip.from.full(it) }, target.root.id, target.path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            actionError = errorOf(e)
            null
        }
    }

    /** Einfügen als Auftrag am NAS; [conflict] ist rename, skip, replace oder missing. */
    suspend fun paste(target: Location, conflict: String): Boolean {
        val clip = clipboard ?: return false
        return act {
            val job = files!!.copy(CopyRequest(clip.from.root.id, clip.names.map { clip.from.full(it) }, target.root.id, target.path, clip.move, conflict))
            if (clip.move) clipboard = null
            nasJobs = listOf(job) + nasJobs.filter { it.id != job.id }
            watchJobs(started = true)
        }
    }

    /** Abgelegtes aus TitanOS (auch aus einem anderen Fenster): am NAS kopieren bzw. in derselben Ablage verschieben. */
    suspend fun dropNas(target: Location, p: DragDrop.Payload, copy: Boolean = false): Boolean {
        if (p.root == target.root.id && p.paths.all { it.substringBeforeLast('/', "") == target.path }) return false
        if (p.root == target.root.id && p.paths.any { target.path == it || target.path.startsWith("$it/") }) {
            actionError = FilesError.IntoItself
            return false
        }
        return act {
            val job = files!!.copy(CopyRequest(p.root, p.paths, target.root.id, target.path, move = !copy && p.root == target.root.id, conflict = "rename"))
            nasJobs = listOf(job) + nasJobs.filter { it.id != job.id }
            watchJobs(started = true)
        }
    }

    /** Fragt die Aufträge ab, solange einer läuft; danach lädt der Ordner neu. */
    fun watchJobs(force: Boolean = false, started: Boolean = false) {
        val api = files ?: return
        if (force) {
            polling?.cancel()
            polling = null
        }
        if (polling?.isActive == true && !started) return
        if (started) polling?.cancel()
        polling = scope.launch {
            // Gerade gestartet: auch ein sofort fertiger Auftrag lädt den Ordner neu.
            var wasRunning = started
            while (true) {
                val list = runCatching { api.jobs() }.getOrNull() ?: break
                nasJobs = list
                val running = list.any { it.running }
                if (wasRunning && !running) reload()
                if (!running) {
                    if (wasRunning) {
                        list.firstOrNull()?.let { j ->
                            notice = when (j.state) {
                                "done" -> Notice(if (j.failed > 0) R.string.jobs_done_with_problems else R.string.jobs_done)
                                "failed" -> Notice(R.string.jobs_failed)
                                else -> null
                            }
                        }
                    }
                    break
                }
                wasRunning = true
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    suspend fun cancelJob(id: String) = act { files!!.cancelJob(id); watchJobsNow() }
    suspend fun retryJob(id: String) = act { files!!.retryJob(id); watchJobsNow() }
    suspend fun pauseJob(id: String, paused: Boolean) = act { files!!.pauseJob(id, paused); watchJobsNow() }

    private fun watchJobsNow() {
        polling?.cancel()
        polling = null
        watchJobs()
    }

    // ---------- Aus dem Netz ----------

    suspend fun probeUrl(location: Location, url: String): UrlProbe? = try {
        files!!.probeUrl(location.root.id, location.path, url.trim())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        actionError = errorOf(e)
        null
    }

    suspend fun downloadUrl(location: Location, url: String, name: String, rename: Boolean): Boolean = act {
        val job = files!!.downloadUrl(UrlDownloadRequest(location.root.id, location.path, url.trim(), name.trim(), if (rename) "rename" else ""))
        nasJobs = listOf(job) + nasJobs
        watchJobs(started = true)
    }

    /**
     * In Downloads speichern wie im Web: eine Datei als sie selbst, Ordner und
     * mehrere Einträge zusammen als ZIP (`files/zip`).
     */
    fun saveManyToDownloads(location: Location, entries: List<FileEntry>) {
        val single = entries.singleOrNull()
        if (single != null && !single.dir) return saveToDownloads(location, single)
        if (entries.isEmpty()) return
        val base = single?.name ?: location.segments.lastOrNull() ?: location.root.name
        val name = cleanZipName(base)
        val t = transfers.add(Transfer(0, name, Direction.Download, location.place, location.root.id, location.path, total = entries.sumOf { it.size }))
        run(t) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("no Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { out -> files!!.zipTo(location.root.id, entries.map { location.full(it.name) }, name, out) { progress(t.id, it, -1) } }
                resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    private fun cleanZipName(base: String): String {
        val n = base.replace(Regex("[/\\\u0000\r\n]"), "_").take(190).ifBlank { "TitanOS" }
        return "$n.zip"
    }

    /** Führt eine Aktion aus und hält den Fehler fest; true bei Erfolg. */
    private suspend fun act(block: suspend () -> Unit): Boolean = try {
        actionError = null
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        actionError = errorOf(e)
        false
    }

    private fun progress(id: Long, done: Long, total: Long) {
        transfers.update(id) { it.copy(done = done, total = if (total > 0) total else it.total) }
    }

    private fun run(t: Transfer, work: suspend () -> Unit) {
        val job = scope.launch(Dispatchers.IO) {
            transfers.update(t.id) { it.copy(state = TransferState.Running) }
            try {
                work()
                transfers.update(t.id) { it.copy(state = TransferState.Done, done = it.total, finishedAtMs = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                transfers.update(t.id) { it.copy(state = TransferState.Cancelled, finishedAtMs = System.currentTimeMillis()) }
            } catch (e: Exception) {
                val err = errorOf(e)
                transfers.update(t.id) {
                    it.copy(
                        state = TransferState.Failed,
                        offline = err is FilesError.Offline,
                        reason = (err as? FilesError.Other)?.detail,
                        finishedAtMs = System.currentTimeMillis(),
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

    fun errorOf(e: Throwable): FilesError = when {
        e is ApiException && e.status == 409 && e.code == "file_exists" -> FilesError.Exists
        e is ApiException && e.status == 403 && e.code == "read_only_share" -> FilesError.ReadOnly
        e is ApiException && e.status == 507 -> FilesError.NoSpace
        e is ApiException && e.status == 409 && e.code == "copy_same_folder" -> FilesError.SameFolder
        e is ApiException && e.status == 400 && e.code == "copy_into_itself" -> FilesError.IntoItself
        e is ApiException && e.status == 400 && e.code == "not_text" -> FilesError.NotText
        e is ApiException && e.status == 400 && e.code == "invalid_path" -> FilesError.InvalidName
        e is ApiException && e.status == 409 && e.code == "index_unavailable" -> FilesError.NoIndex
        e is ApiException && e.status == 429 && e.code == "download_limit" -> FilesError.DownloadLimit
        e is ApiException && e.status == 403 && e.code == "download_blocked" -> FilesError.DownloadBlocked
        e is ApiException && e.status == 502 -> FilesError.DownloadFailed
        e is ApiException && e.status == 404 -> FilesError.NotFound
        e is ApiException && e.status == 423 -> FilesError.Locked
        e is ApiException -> FilesError.Other("${e.status} ${e.code}".trim())
        e is IOException -> FilesError.Offline
        else -> FilesError.Other(e.javaClass.simpleName)
    }
}
