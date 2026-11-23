package io.github.ra5on.titanos.photos

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.model.PhotoItem
import io.github.ra5on.titanos.model.PhotoVerifyRequest
import io.github.ra5on.titanos.net.PhotosApi
import io.github.ra5on.titanos.net.TitanApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Zustand der Seiten „Fotos“ und „Foto-Sicherung“ für eine Kopplung.
 * [titan] und [gallery] meinen das gekoppelte NAS selbst, auch wenn in der
 * App ein anderer Knoten des Clusters gewählt ist (dorthin wird gesichert).
 */
class PhotosModel(
    private val app: Application,
    private val titan: TitanApi,
    private val photos: PhotosApi,
    private val scope: CoroutineScope,
    /** Das NAS dieser Seite; jedes hat eigene Einstellungen und einen eigenen Stand. */
    val nas: String = io.github.ra5on.titanos.net.NasRegistry.LEGACY_ID,
    /** Galerie „Auf dem NAS“ am gekoppelten NAS; null: die Verbindung des Dateimanagers. */
    val gallery: io.github.ra5on.titanos.files.FilesApi? = null,
) {
    /** Für Teilen und Öffnen aus der Galerie: dieselbe Verbindung wie die Sicherung. */
    val pairedApi: TitanApi get() = titan

    private val settingsStore = SettingsStore(app, nas)
    private val store = BackupStore.get(app, nas)
    private val scanner = MediaScanner(app)
    private var items: List<MediaItem> = emptyList()
    private var trash: List<Uri> = emptyList()
    private var watching: Job? = null

    var settings by mutableStateOf(settingsStore.load())
        private set
    var view by mutableStateOf(PhotosView(settings = settings, loading = true))
        private set
    var folders by mutableStateOf<List<LocalFolder>>(emptyList())
        private set

    /** Freigaben mit Schreibrecht für „Ziel auf dem NAS“; null solange sie laden. */
    var roots by mutableStateOf<List<FileRoot>?>(null)
        private set
    var rootsFailed by mutableStateOf(false)
        private set

    var free by mutableStateOf<FreeState?>(null)
    var message by mutableStateOf<Int?>(null)

    @OptIn(FlowPreview::class)
    fun start() {
        refresh()
        if (watching != null) return
        watching = scope.launch {
            merge(PhotosRuntime.changes, PhotosRuntime.progress).debounce(400).collect { refresh() }
        }
    }

    fun stop() {
        watching?.cancel()
        watching = null
    }

    fun refresh() {
        scope.launch {
            val s = settingsStore.load()
            val access = MediaAccess.access(app)
            val scanned = if (access == Access.None) emptyList() else withContext(Dispatchers.IO) { runCatching { scanner.scan() }.getOrDefault(emptyList()) }
            items = scanned
            val state = store.snapshot()
            val marks = state.marks()
            val handled = state.handled()
            val media = scanned.map { it.media }
            val pending = Kind.entries.associateWith { selectForUpload(media, handled, s, setOf(it)).size }
            val device = withContext(Dispatchers.IO) { deviceState(app) }
            val waits = Kind.entries.associateWith { k ->
                waitReason(pending.getValue(k), s.enabled, access != Access.None, s.hasTarget, s.whenOf(k), s.mobileData, device, state.problem)
            }
            val chosen = media.filter { s.folders?.contains(it.folder) ?: defaultFolder(it.folder) }
            val tiles = chosen.sortedByDescending { it.taken }.take(TILES).map { m ->
                Tile(m.id, m.kind, isSaved(m, marks[m.id], s.targetOf(m.kind)), m.taken, missing = m.id in state.missing, ignored = m.id in state.ignored)
            }
            val present = media.associate { it.id to it.kind }
            settings = s
            folders = scanner.folders(scanned)
            view = PhotosView(
                settings = s,
                access = access,
                savedCount = state.saved.values.count { it.target == s.target || it.target == s.targetOf(Kind.Video) },
                lastSaved = state.lastSaved.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                pending = pending,
                waits = waits,
                tiles = tiles,
                progress = PhotosRuntime.progress.value.takeIf { it.nas == nas || it.nas.isEmpty() } ?: Progress(),
                loading = false,
                paused = PhotosRuntime.paused(app),
                lastBatch = state.lastBatch,
                missing = missingView(state, present),
                ignored = state.ignored.keys.count { it in present },
            )
        }
    }

    /** Neue Einstellungen speichern und die Aufträge danach einplanen. */
    fun update(next: PhotoSettings) {
        // „Überspringen“ abgewählt: die übersprungenen Aufnahmen kommen beim nächsten Lauf wieder dran.
        if (settings.sameName == SameName.Skip && next.sameName != SameName.Skip) store.update { it.copy(skipped = emptyMap()) }
        settingsStore.save(next)
        settings = next
        PhotoScheduler.arm(app, nas, settingsChanged = true)
        refresh()
    }

    /** Medien-Zugriff ist geklärt: Aufträge (neu) einplanen und neu lesen. */
    fun accessChanged() {
        PhotoScheduler.armAll(app)
        refresh()
    }

    fun loadRoots() {
        roots = null
        rootsFailed = false
        scope.launch {
            try {
                roots = titan.roots().filter { it.kind == "share" && it.writable }
            } catch (_: Exception) {
                rootsFailed = true
                roots = emptyList()
            }
        }
    }

    /** Nach „Pausieren“ (Schnelleinstellung) fortsetzen. */
    fun resume() {
        PhotoScheduler.resume(app)
        refresh()
    }

    /** „Jetzt sichern“, solange die App sichtbar ist. */
    fun backupNow() {
        val s = settingsStore.load()
        val bytes = selectForUpload(items.map { it.media }, store.snapshot().handled(), s, Kind.entries.toSet()).sumOf { it.size }
        message = if (PhotoScheduler.backupNow(app, listOf(nas), bytes)) R.string.photos_now_started else R.string.photos_now_refused
    }

    /** Die fehlenden Aufnahmen, die noch auf dem Handy sind. */
    private fun missingHere(): Set<Long> {
        val here = items.mapTo(HashSet()) { it.id }
        return store.snapshot().missing.keys.filterTo(HashSet()) { it in here }
    }

    /** „Erneut sichern“: die am NAS fehlenden Aufnahmen wieder in die Sicherung und gleich loslegen. */
    fun resaveMissing() {
        val ids = missingHere()
        store.update { resaveMissing(it, ids) }
        store.flush()
        backupNow()
        refresh()
    }

    /** „Nicht mehr sichern“: die fehlenden Aufnahmen auslassen (in den Einstellungen zurückzunehmen). */
    fun ignoreMissing() {
        val ids = missingHere()
        store.update { ignoreMissing(it, ids) }
        store.flush()
        refresh()
    }

    /** „Ausblenden“: das Symbol oben warnt nicht mehr wegen der jetzt fehlenden Aufnahmen. */
    fun dismissMissing() {
        val ids = missingHere()
        store.update { dismissMissing(it, ids) }
        store.flush()
        refresh()
    }

    /** Einstellungen: die ausgelassenen Aufnahmen wieder sichern. */
    fun unignoreAll() {
        store.update { unignoreAll(it) }
        store.flush()
        PhotoScheduler.enqueueUploads(app, nas)
        refresh()
    }

    /** Speicher freigeben: nur, was das NAS jetzt bestätigt, nie automatisch. */
    fun prepareFree() {
        free = FreeState.Checking
        scope.launch {
            try {
                val marks = store.snapshot().marks()
                val byId = items.associateBy { it.id }
                val candidates = freeCandidates(items.map { it.media }, marks, Instant.now())
                val answers = HashMap<String, String>()
                for (chunk in candidates.chunked(VERIFY_BATCH)) {
                    val asked = chunk.mapNotNull { m -> marks[m.id]?.let { PhotoItem(it.sha256, m.size) } }.distinctBy { it.sha256 }
                    photos.verify(PhotoVerifyRequest(asked)).items.forEach { answers[it.sha256] = it.state }
                    if (free != FreeState.Checking) return@launch
                }
                val confirmed = confirmedOnly(candidates, marks, answers)
                val round = confirmed.take(TRASH_ROUND)
                trash = round.mapNotNull { byId[it.id]?.uri }
                free = if (round.isEmpty()) FreeState.Nothing else FreeState.Ready(round.size, round.sumOf { it.size }, confirmed.size > round.size)
            } catch (_: Exception) {
                trash = emptyList()
                free = FreeState.Failed
            }
        }
    }

    /** Android fragt danach selbst noch einmal; gelöscht wird in den Papierkorb des Handys. */
    fun trashRequest(): IntentSender? =
        trash.takeIf { it.isNotEmpty() }?.let { MediaStore.createTrashRequest(app.contentResolver, it, true).intentSender }

    fun trashed(ok: Boolean) {
        trash = emptyList()
        free = if (ok) FreeState.Done else null
        refresh()
    }

    /** Die Aufnahme zu einer Kachel (für das Vorschaubild). */
    fun item(id: Long): MediaItem? = items.firstOrNull { it.id == id }

    companion object {
        const val TILES = 48
        const val VERIFY_BATCH = 500

        /** So viele Aufnahmen gibt die App Android je Rückfrage mit. */
        const val TRASH_ROUND = 1000
    }
}
