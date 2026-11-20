package io.github.ra5on.titanos.net

import io.github.ra5on.titanos.model.DeleteRequest
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.model.PublicSession
import io.github.ra5on.titanos.model.RenameRequest
import io.github.ra5on.titanos.model.StorageInfo
import io.github.ra5on.titanos.model.MonitorSample
import io.github.ra5on.titanos.model.SystemInfo
import io.github.ra5on.titanos.model.UndoResponse
import io.github.ra5on.titanos.model.UploadStartRequest
import io.github.ra5on.titanos.model.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream

/**
 * Die Routen, die auch die Weboberfläche für Start und Dateien nutzt
 * (web/src/lib/storage.svelte.ts, system.svelte.ts, session.svelte.ts,
 * routes/files, lib/uploads.svelte.ts; backend/internal/api/files.go).
 */
class TitanApi(private val nas: NasConnection) {

    private fun url(base: HttpUrl, path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1/$path").apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, path, query)).build() }.decode<T>() }

    private suspend inline fun <reified B, reified T> post(path: String, body: B): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, path)).post(jsonBody(body)).build() }.decode<T>() }

    // Start: Zustand wie die Karte „Status“ im Web (WidgetView.svelte).
    suspend fun publicSession(): PublicSession = get("session")
    suspend fun system(): SystemInfo = get("system")
    suspend fun storage(): StorageInfo = get("storage")

    /** Messwerte der letzten Stunde; 403 für Konten ohne Verwaltungsrecht, leer ohne Monitoring. */
    suspend fun monitoring(hours: Int = 1): List<MonitorSample> = get("monitoring/history", listOf("hours" to hours.toString()))

    /** `GET /services/state`: welche Bereiche eingeschaltet sind (docker, vms, lxc, agent, mail, calendar, contacts). */
    suspend fun services(): Map<String, Boolean> = get("services/state")

    // Dateien.
    suspend fun roots(): List<FileRoot> = get("files/roots")

    suspend fun list(root: String, path: String): List<FileEntry> = get("files/list", listOf("root" to root, "path" to path))

    /** from und to sind volle Pfade innerhalb der Freigabe; überschreibt nie (409 file_exists). */
    suspend fun rename(root: String, from: String, to: String): UndoResponse = post("files/rename", RenameRequest(root, from, to))

    /** permanent=false legt in den Papierkorb der Freigabe; nur wo es einen gibt. */
    suspend fun delete(root: String, paths: List<String>, permanent: Boolean): UndoResponse =
        post("files/delete", DeleteRequest(root, paths, permanent))

    /** Lädt eine Datei; [progress] bekommt die geschriebenen Bytes und die Gesamtgröße (oder -1). */
    suspend fun download(root: String, path: String, out: OutputStream, progress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            nas.execute { Request.Builder().url(url(it, "files/download", listOf("root" to root, "path" to path))).build() }.use { r ->
                r.throwIfError()
                val total = r.body.contentLength()
                var done = 0L
                r.body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        }

    // Hochladen in Stücken (handleUploadStart/Chunk/Finish).
    suspend fun uploadStart(root: String, dir: String, name: String, size: Long, conflict: String = ""): UploadStatus =
        post("files/uploads", UploadStartRequest(root, dir, name, size, conflict))

    suspend fun uploadChunk(id: String, offset: Long, bytes: ByteArray, length: Int): UploadStatus = withContext(Dispatchers.IO) {
        val body = bytes.toRequestBody(OCTETS, 0, length)
        nas.execute {
            Request.Builder().url(url(it, "files/uploads/$id", listOf("offset" to offset.toString()))).patch(body).build()
        }.decode<UploadStatus>()
    }

    suspend fun uploadStatus(id: String): UploadStatus = get("files/uploads/$id")

    suspend fun uploadFinish(id: String): UploadStatus = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "files/uploads/$id/finish")).post(ByteArray(0).toRequestBody(null)).build() }
            .decode<UploadStatus>()
    }

    suspend fun uploadAbort(id: String) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "files/uploads/$id")).delete().build() }.use { it.throwIfError() }
    }

    private companion object {
        val OCTETS = "application/octet-stream".toMediaType()
    }
}
