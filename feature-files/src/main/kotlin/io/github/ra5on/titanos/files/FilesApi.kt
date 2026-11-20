package io.github.ra5on.titanos.files

import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.InputStream

// Antworten und Anfragen der Dateirouten, wie sie die Weboberfläche nutzt
// (TitanOS backend/internal/api/files.go, file_tools.go, file_media.go,
// file_download.go, catalog_files.go). Felder wie in den JSON-Namen des NAS.

@Serializable
data class Access(val read: Boolean = false, val write: Boolean = false, val delete: Boolean = false, val reason: String = "")

/** `GET /files/properties`. */
@Serializable
data class Properties(
    val version: String = "",
    val access: Access = Access(),
    val path: String = "",
    val kind: String = "",
    val size: Long = 0,
    val changed: String = "",
    val accessed: String = "",
    val modified: String = "",
    val permissions: String = "",
    val uid: Long = 0,
    val gid: Long = 0,
    val sha256: String? = null,
)

/** `GET/PUT /files/text`: Inhalt und Version (SHA-256 des Inhalts). */
@Serializable
data class TextDoc(val content: String = "", val version: String = "")

@Serializable
data class TextSave(val root: String, val path: String, val content: String, val version: String)

/** Antwort von `PUT /files/content`. */
@Serializable
data class ContentSaved(val modified: String = "", val mtime: String = "", val size: Long = 0)

/** Ein Bild oder Video aus `GET /files/photos` (ein Ordner, nicht rekursiv). */
@Serializable
data class PhotoEntry(
    val name: String,
    val size: Long = 0,
    val modified: String = "",
    val taken: String? = null,
    val kind: String = "image",
)

@Serializable
data class PhotoList(val entries: List<PhotoEntry> = emptyList(), val truncated: Boolean = false)

@Serializable
data class PathRequest(val root: String, val path: String)

@Serializable
data class CopyRequest(
    val root: String,
    val paths: List<String>,
    val toRoot: String,
    val toDir: String,
    val move: Boolean = false,
    val conflict: String = "rename",
)

@Serializable
data class InspectRequest(val root: String, val paths: List<String>, val toRoot: String, val toDir: String)

@Serializable
data class Conflict(
    val identical: Boolean = false,
    val sourcePath: String = "",
    val path: String = "",
    val source: FileEntry? = null,
    val target: FileEntry? = null,
)

/** Prüfung vor dem Kopieren: was im Ziel schon liegt. */
@Serializable
data class Inspection(
    val files: Long = 0,
    val bytes: Long = 0,
    val conflicts: List<Conflict> = emptyList(),
    val truncated: Boolean = false,
    val identical: Long = 0,
    val different: Long = 0,
    val typeMismatch: Long = 0,
    val unreadableCount: Long = 0,
)

@Serializable
data class JobProblem(val path: String = "", val code: String = "")

/** Ein Auftrag des NAS (Kopieren, Verschieben, Laden aus dem Netz, Entpacken). */
@Serializable
data class Job(
    val id: String,
    val operation: String = "",
    val move: Boolean = false,
    val state: String = "running",
    val planning: Boolean = false,
    val paused: Boolean = false,
    val from: String = "",
    val to: String = "",
    val dir: String = "",
    val names: List<String>? = null,
    val files: Long = 0,
    val done: Long = 0,
    val bytes: Long = 0,
    val copied: Long = 0,
    val current: String = "",
    val error: String = "",
    val errorCode: String = "",
    val failed: Long = 0,
    val kept: Long = 0,
    val identical: Long = 0,
    val problems: List<JobProblem>? = null,
    val retryable: Boolean = false,
    val started: String = "",
    val finished: String = "",
) {
    val running: Boolean get() = state == "running"
    val fraction: Float get() = when {
        bytes > 0 -> (copied.toDouble() / bytes).toFloat()
        files > 0 -> (done.toDouble() / files).toFloat()
        else -> 0f
    }
}

@Serializable
data class JobControl(val paused: Boolean? = null)

@Serializable
data class SearchRequest(
    val root: String,
    val path: String,
    val query: String,
    val content: Boolean = false,
    val showHidden: Boolean = false,
    val extension: String? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val after: String? = null,
    val before: String? = null,
)

@Serializable
data class Found(
    val name: String,
    val dir: Boolean = false,
    val size: Long = 0,
    val modified: String = "",
    val path: String = "",
    val excerpt: String? = null,
)

@Serializable
data class SearchResult(val entries: List<Found> = emptyList(), val scanned: Int = 0, val truncated: Boolean = false)

/** Ein Eintrag im Papierkorb (`flat=1`): path ist relativ zu .recycle, original der alte Ort. */
@Serializable
data class TrashEntry(
    val name: String,
    val dir: Boolean = false,
    val size: Long = 0,
    val modified: String = "",
    val path: String = "",
    val original: String = "",
)

@Serializable
data class TrashRequest(val root: String, val paths: List<String>, val bin: String? = null)

@Serializable
data class UndoItem(val id: String, val kind: String = "", val count: Int = 0, val created: String = "", val busy: Boolean = false)

@Serializable
data class UrlProbeRequest(val root: String, val dir: String, val url: String)

@Serializable
data class UrlProbe(val name: String = "", val size: Long = -1, val type: String? = null, val exists: Boolean = false, val free: Long = 0)

@Serializable
data class UrlDownloadRequest(val root: String, val dir: String, val url: String, val name: String, val conflict: String = "")

@Serializable
data class ArchiveEntry(val path: String, val size: Long = 0, val dir: Boolean = false)

@Serializable
data class ArchiveList(val entries: List<ArchiveEntry> = emptyList(), val bytes: Long = 0, val version: String = "")

@Serializable
data class ExtractRequest(
    val root: String,
    val path: String,
    val version: String,
    val paths: List<String>,
    val toRoot: String,
    val toDir: String,
    val name: String,
)

@Serializable
data class DuplicatesRequest(val root: String, val path: String, val showHidden: Boolean = false)

@Serializable
data class DuplicateGroup(val sha256: String = "", val size: Long = 0, val paths: List<String> = emptyList(), val versions: Map<String, String> = emptyMap())

@Serializable
data class Duplicates(val groups: List<DuplicateGroup> = emptyList(), val scanned: Int = 0, val hashed: Int = 0)

@Serializable
data class UsageRequest(val root: String, val path: String, val showHidden: Boolean = false)

@Serializable
data class UsageItem(val path: String = "", val bytes: Long = 0, val files: Long = 0, val dir: Boolean = false, val modified: String? = null)

@Serializable
data class Usage(
    val bytes: Long = 0,
    val files: Long = 0,
    val folders: Long = 0,
    val largest: List<UsageItem> = emptyList(),
    val children: List<UsageItem> = emptyList(),
    val old: List<UsageItem> = emptyList(),
    val olderThan: String = "",
)

@Serializable
data class RenamePreviewRequest(
    val root: String,
    val dir: String,
    val names: List<String>,
    val find: String = "",
    val replace: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val number: Boolean = false,
    val start: Int = 1,
    val digits: Int = 2,
)

@Serializable
data class RenameChange(val from: String, val to: String, val version: String = "", val problem: String = "")

@Serializable
data class RenameBatchRequest(val root: String, val changes: List<RenameChange>)

@Serializable
data class RenameBatchResult(val done: Int = 0, val undo: String = "", val error: String = "")

@Serializable
data class WebAppsList(val apps: List<WebApp> = emptyList())

/** Ein Office-Programm von TitanOS (`GET /api/v1/webapps`). */
@Serializable
data class WebApp(
    val id: String,
    val name: String = "",
    val version: String = "",
    val installed: Boolean = false,
    val state: String = "",
    val bridge: String = "host",
    val types: List<String> = emptyList(),
    @SerialName("default") val isDefault: Boolean = false,
    val url: String? = null,
    val trusted: Boolean? = null,
    val successor: String? = null,
    val startNew: Boolean? = null,
) {
    /** Nur fertige Programme mit einer Adresse unter /webapps/ (wie protocol.ts ownAppUrl). */
    val ready: Boolean get() = installed && state == "ready" && url != null && OWN_URL.matches(url) && successor == null

    companion object {
        val OWN_URL = Regex("^/webapps/[a-z0-9][a-z0-9-]*/[A-Za-z0-9._-]+/?$")
    }
}

/**
 * Die weiteren Dateirouten der Weboberfläche über die Verbindung der App.
 * Rechte prüft immer das NAS; hier steht nur, was die App schickt.
 */
class FilesApi(private val nas: NasConnection) {

    private fun url(base: HttpUrl, path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1/$path").apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, path, query)).build() }.decode<T>() }

    private suspend inline fun <reified B, reified T> post(path: String, body: B): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, path)).post(jsonBody(body)).build() }.decode<T>() }

    private suspend inline fun <reified B> postEmpty(path: String, body: B) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, path)).post(jsonBody(body)).build() }.use { it.throwIfError() }
        Unit
    }

    private suspend fun postNothing(path: String) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, path)).post(ByteArray(0).toRequestBody(null)).build() }.use { it.throwIfError() }
        Unit
    }

    // ---- Lesen ----

    suspend fun properties(root: String, path: String, checksum: Boolean = false): Properties =
        get("files/properties", listOfNotNull("root" to root, "path" to path, if (checksum) "checksum" to "sha256" else null))

    suspend fun photos(root: String, path: String): PhotoList = get("files/photos", listOf("root" to root, "path" to path))

    /** Vorschaubild (JPEG/PNG) in einer Kiste von höchstens [size] Pixeln; null bei 415 (keine Vorschau). */
    suspend fun thumb(root: String, path: String, size: Int, version: String = ""): ByteArray? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            try {
                return@withContext nas.execute {
                    Request.Builder().url(url(it, "files/thumb", listOf("root" to root, "path" to path, "size" to size.toString(), "v" to version))).build()
                }.use { r -> r.throwIfError(); r.body.bytes() }
            } catch (e: ApiException) {
                when {
                    e.status == 415 -> return@withContext null
                    e.status == 503 && e.code == "thumb_busy" && attempt++ < 4 -> delay(1000L * (e.retryAfterSeconds ?: 2))
                    else -> throw e
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /** Lädt die Datei ganz in [file] (Cache), höchstens [limit] Bytes. */
    suspend fun downloadTo(root: String, path: String, file: File, limit: Long = Long.MAX_VALUE, progress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            nas.execute { Request.Builder().url(url(it, "files/download", listOf("root" to root, "path" to path))).build() }.use { r ->
                r.throwIfError()
                val total = r.body.contentLength()
                if (total > limit) throw TooLargeException(total)
                val tmp = File(file.parentFile, file.name + ".part")
                r.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > limit) throw TooLargeException(done)
                            out.write(buf, 0, n)
                            progress(done, total)
                        }
                    }
                }
                if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
            }
        }

    /** Ein Stück der Datei ab [offset] (HTTP-Range) – für Video und Ton zum Spulen. */
    fun openRange(root: String, path: String, offset: Long, length: Long): Response {
        val range = if (length > 0) "bytes=$offset-${offset + length - 1}" else "bytes=$offset-"
        return nas.execute {
            Request.Builder().url(url(it, "files/download", listOf("root" to root, "path" to path))).header("Range", range).build()
        }.throwIfError()
    }

    /** Der Inhalt einer Datei als Strom (für kleine Dateien wie Text). */
    suspend fun bytes(root: String, path: String, limit: Long): ByteArray = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "files/download", listOf("root" to root, "path" to path))).build() }.use { r ->
            r.throwIfError()
            val total = r.body.contentLength()
            if (total > limit) throw TooLargeException(total)
            readLimited(r.body.byteStream(), limit)
        }
    }

    suspend fun text(root: String, path: String): TextDoc = get("files/text", listOf("root" to root, "path" to path))

    suspend fun previousText(root: String, path: String): TextDoc = get("files/text/previous", listOf("root" to root, "path" to path))

    /** Speichert Text; 409 text_conflict, wenn sich die Datei seit [version] geändert hat. */
    suspend fun saveText(root: String, path: String, content: String, version: String): TextDoc =
        withContext(Dispatchers.IO) {
            nas.execute {
                Request.Builder().url(url(it, "files/text")).put(jsonBody(TextSave(root, path, content, version))).build()
            }.decode<TextDoc>()
        }

    // ---- Anlegen, Kopieren, Verschieben ----

    suspend fun mkdir(root: String, path: String) = postEmpty("files/mkdir", PathRequest(root, path))

    /** Neue leere Textdatei (.txt, .md, .json). */
    suspend fun create(root: String, path: String) = postEmpty("files/create", PathRequest(root, path))

    suspend fun inspect(request: InspectRequest): Inspection = post("files/inspect", request)

    /** Kopieren oder Verschieben als Auftrag des NAS; den Stand liefert [jobs]. */
    suspend fun copy(request: CopyRequest): Job = post("files/copy", request)

    suspend fun jobs(): List<Job> = get("files/jobs")

    suspend fun cancelJob(id: String) = postNothing("files/jobs/$id/cancel")

    suspend fun retryJob(id: String): Job = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "files/jobs/$id/retry")).post(ByteArray(0).toRequestBody(null)).build() }.decode<Job>()
    }

    suspend fun pauseJob(id: String, paused: Boolean) = postEmpty("files/jobs/$id/control", JobControl(paused))

    suspend fun undoList(): List<UndoItem> = get("files/undo")

    suspend fun undo(id: String) = postNothing("files/undo/$id")

    // ---- Suche ----

    suspend fun search(request: SearchRequest): SearchResult = post("files/search", request)

    // ---- Papierkorb ----

    /** Der eigene Papierkorb der Freigabe als flache Liste (Pfade ab .recycle, erstes Glied ist das Konto). */
    suspend fun trash(root: String, user: String): List<TrashEntry> =
        get("files/trash", listOf("root" to root, "path" to user, "flat" to "1"))

    suspend fun restore(root: String, paths: List<String>) = postEmpty("files/trash/restore", TrashRequest(root, paths))

    suspend fun deleteFromTrash(root: String, paths: List<String>) = postEmpty("files/trash/delete", TrashRequest(root, paths))

    // ---- Aus dem Netz laden ----

    suspend fun probeUrl(root: String, dir: String, url: String): UrlProbe = post("files/url-download/probe", UrlProbeRequest(root, dir, url))

    suspend fun downloadUrl(request: UrlDownloadRequest): Job = post("files/url-download", request)

    // ---- Werkzeuge ----

    suspend fun archive(root: String, path: String): ArchiveList = post("files/archive", PathRequest(root, path))

    suspend fun extract(request: ExtractRequest): Job = post("files/extract", request)

    suspend fun duplicates(root: String, path: String, showHidden: Boolean): Duplicates =
        post("files/duplicates", DuplicatesRequest(root, path, showHidden))

    suspend fun usage(root: String, path: String, showHidden: Boolean): Usage = post("files/usage", UsageRequest(root, path, showHidden))

    suspend fun renamePreview(request: RenamePreviewRequest): List<RenameChange> = post("files/rename-preview", request)

    suspend fun renameBatch(root: String, changes: List<RenameChange>): RenameBatchResult =
        post("files/rename-batch", RenameBatchRequest(root, changes))

    // ---- Office ----

    suspend fun webapps(): List<WebApp> = get<WebAppsList>("webapps").apps

    /** Gewählte Programme je Endung (`fileapps` in den Einstellungen des Kontos): „webapp:<id>“ oder „editor“. */
    suspend fun fileApps(): Map<String, String> = try {
        val prefs: JsonObject = get("preferences")
        (prefs["fileapps"] as? JsonObject)?.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }.orEmpty()
    } catch (e: ApiException) {
        emptyMap()
    }

    /** Ganze Datei schreiben (Office): [expect] ist die Änderungszeit in ns, eine RFC-3339-Zeit oder „none“ für neu. */
    suspend fun putContent(root: String, path: String, expect: String, bytes: ByteArray): ContentSaved = withContext(Dispatchers.IO) {
        nas.execute {
            Request.Builder().url(url(it, "files/content", listOf("root" to root, "path" to path, "expect" to expect)))
                .put(bytes.toRequestBody(OCTETS)).build()
        }.decode<ContentSaved>()
    }

    companion object {
        val OCTETS = "application/octet-stream".toMediaType()

        fun readLimited(input: InputStream, limit: Long): ByteArray = input.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw TooLargeException(total)
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }
}

/** Die Datei ist für die Vorschau zu groß. */
class TooLargeException(val size: Long) : java.io.IOException("too large: $size")
