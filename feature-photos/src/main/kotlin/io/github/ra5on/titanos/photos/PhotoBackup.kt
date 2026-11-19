package io.github.ra5on.titanos.photos

import android.content.Context
import io.github.ra5on.titanos.model.PhotoCheckRequest
import io.github.ra5on.titanos.model.PhotoItem
import io.github.ra5on.titanos.model.UploadStatus
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.NasNotReadyException
import io.github.ra5on.titanos.net.PhotosApi
import io.github.ra5on.titanos.net.SessionLockedException
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.net.Uploader
import io.github.ra5on.titanos.net.certificateChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Wie ein Lauf endete. */
enum class Outcome { Done, Skipped, RetryLater }

/**
 * Ein Lauf der Sicherung: Aufnahmen lesen, Prüfsummen bilden, beim NAS
 * fragen, was schon da ist (`app/photos/check`), den Rest über den Upload in
 * Stücken hochladen und mit der Prüfsumme abschließen. Was ein Lauf schafft,
 * bleibt: der nächste macht beim selben Upload weiter.
 */
class PhotoBackup(
    private val context: Context,
    private val titan: TitanApi,
    private val photos: PhotosApi,
    private val settings: PhotoSettings = SettingsStore(context).load(),
    private val store: BackupStore = BackupStore.get(context),
    private val scanner: MediaScanner = MediaScanner(context),
) {
    suspend fun run(kinds: Set<Kind>, stopped: () -> Boolean = { false }): Outcome = withContext(Dispatchers.IO) {
        if (!settings.enabled || !settings.hasTarget || MediaAccess.access(context) == Access.None) return@withContext Outcome.Skipped
        val (gens, versions) = scanner.generations()
        val items = scanner.scan()
        val byId = items.associateBy { it.id }
        val todo = selectForUpload(items.map { it.media }, store.snapshot().marks(), settings.target, kinds, settings.folders)
            .mapNotNull { byId[it.id] }
        PhotosRuntime.report(Progress(running = true, total = todo.size))
        var done = 0
        val made = HashSet<String>()
        try {
            for (batch in todo.chunked(BATCH)) {
                if (stopped()) return@withContext Outcome.RetryLater
                val sums = batch.associateWith { sumOf(it) }
                val answers = if (settings.skipDuplicates) {
                    photos.check(
                        PhotoCheckRequest(
                            settings.root, settings.dir.trim('/'),
                            batch.map { PhotoItem(sums.getValue(it).first, sums.getValue(it).second, pathInDir(it)) },
                        ),
                    ).items.associateBy { it.sha256 }
                } else {
                    emptyMap()
                }
                for (item in batch) {
                    if (stopped()) return@withContext Outcome.RetryLater
                    val (sha, size) = sums.getValue(item)
                    val known = answers[sha]
                    if (known?.state == "saved") {
                        mark(item, sha, known.path)
                    } else {
                        PhotosRuntime.report(Progress(running = true, done = done, total = todo.size, current = item.media.name))
                        upload(item, sha, size, made)
                    }
                    done++
                    PhotosRuntime.report(Progress(running = true, done = done, total = todo.size))
                }
            }
            store.update { it.copy(generations = gens, versions = versions, problem = null, lastRun = System.currentTimeMillis()) }
            Outcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val problem = problemOf(e)
            store.update { it.copy(problem = problem, lastRun = System.currentTimeMillis()) }
            if (problem == Problem.Failed) Outcome.Done else Outcome.RetryLater
        } finally {
            store.flush()
            PhotosRuntime.report(Progress())
            PhotosRuntime.changed()
        }
    }

    /** Ort relativ zum Ordner der Sicherung, z. B. `2026/11/IMG_1.jpg`. */
    private fun pathInDir(item: MediaItem): String {
        val sub = targetDir("", settings.yearMonth, item.media.taken)
        return if (sub.isEmpty()) item.media.name else "$sub/${item.media.name}"
    }

    private fun sumOf(item: MediaItem): Pair<String, Long> {
        val m = item.media
        store.snapshot().hashes[m.id]?.let { if (it.size == m.size && it.modified == m.modified) return it.sha256 to it.size }
        val (sha, size) = scanner.hash(item)
        store.update { it.copy(hashes = it.hashes + (m.id to Hashed(m.size, m.modified, sha))) }
        return sha to size
    }

    private fun mark(item: MediaItem, sha: String, path: String) {
        val m = item.media
        store.update {
            it.copy(
                saved = it.saved + (m.id to Saved(m.size, m.modified, sha, settings.target, path)),
                uploads = it.uploads - m.id,
                lastSaved = System.currentTimeMillis(),
            )
        }
        PhotosRuntime.changed()
    }

    private suspend fun upload(item: MediaItem, sha: String, size: Long, made: MutableSet<String>) {
        val dir = targetDir(settings.dir, settings.yearMonth, item.media.taken)
        for (p in parents(dir)) {
            if (p !in made) {
                photos.mkdir(settings.root, p)
                made += p
            }
        }
        val start = resume(item, dir, sha, size) ?: titan.uploadStart(settings.root, dir, item.media.name, size, conflict = "rename").also { s ->
            store.update { it.copy(uploads = it.uploads + (item.id to Started(s.id, dir, item.media.name, size, sha))) }
        }
        val sent = Uploader(titan).send(start, { scanner.open(item) })
        try {
            // Bei gleichem Namen mit anderem Inhalt vergibt das NAS „IMG_1 (2).jpg“ (collisionName).
            val finished = photos.finish(sent.id, sha)
            mark(item, finished.sha256, finished.path)
        } catch (e: ApiException) {
            if (e.status == 409 && e.code == "upload_hash_mismatch") {
                // Die Aufnahme hat sich beim Hochladen geändert: neu, mit neuer Prüfsumme.
                runCatching { titan.uploadAbort(sent.id) }
                store.update { it.copy(uploads = it.uploads - item.id, hashes = it.hashes - item.id) }
                return
            }
            throw e
        }
    }

    /** Der Upload eines früheren Laufs, wenn das NAS ihn noch kennt. */
    private suspend fun resume(item: MediaItem, dir: String, sha: String, size: Long): UploadStatus? {
        val started = store.snapshot().uploads[item.id] ?: return null
        if (started.dir != dir || started.size != size || started.sha256 != sha) {
            runCatching { titan.uploadAbort(started.id) }
            store.update { it.copy(uploads = it.uploads - item.id) }
            return null
        }
        return try {
            titan.uploadStatus(started.id)
        } catch (e: ApiException) {
            if (e.status != 404) throw e
            // Nach einem Neustart des NAS: in seinem Ordner wiederfinden.
            photos.uploads(settings.root, dir).firstOrNull { it.id == started.id }
                ?: null.also { store.update { s -> s.copy(uploads = s.uploads - item.id) } }
        }
    }

    companion object {
        const val BATCH = 50

        fun problemOf(e: Throwable): Problem = when {
            e.certificateChange() != null -> Problem.Certificate
            e is SessionLockedException -> Problem.Locked
            e is NasNotReadyException && e.code == "photos_unavailable" -> Problem.NoVolume
            e is NasNotReadyException -> Problem.NasUnreachable
            e is ApiException -> Problem.Failed
            e is IOException -> Problem.NasUnreachable
            else -> Problem.Failed
        }
    }
}
