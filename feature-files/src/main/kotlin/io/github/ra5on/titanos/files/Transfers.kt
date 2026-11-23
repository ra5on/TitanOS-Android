package io.github.ra5on.titanos.files

import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

enum class Direction { Upload, Download, Share }

enum class TransferState { Waiting, Running, Done, Failed, Cancelled }

/** Warum eine Übertragung gerade wartet (Entwurf 23: „wartet …, weil …“). */
enum class WaitReason { None, Network, Nas }

/**
 * Wer eine Übertragung ausführt: [Queue] ein WorkManager-Auftrag (lässt sich
 * neu versuchen), [App] im Speicher der App (ZIP, Teilen; ein neuer Versuch
 * hieße neu anstoßen), [Backup] ein Lauf der Foto-Sicherung (nur Eintrag).
 */
enum class TransferSource { Queue, App, Backup }

/**
 * Eine Übertragung (Entwurf 23). Uploads und „In Downloads speichern“ laufen
 * als WorkManager-Auftrag und überstehen das Schließen der App; der Stand
 * liegt in `transfers.json` im privaten Speicher der App.
 */
@Serializable
data class Transfer(
    val id: Long,
    val name: String,
    val direction: Direction,
    /** Freigabe und Ordner, z. B. „Documents/Privat“. */
    val place: String,
    val root: String,
    val dir: String,
    val done: Long = 0,
    val total: Long = 0,
    val state: TransferState = TransferState.Waiting,
    val reason: String? = null,
    val offline: Boolean = false,
    val finishedAtMs: Long? = null,
    /** Upload: die Quelle (content:// mit dauerhafter Leseerlaubnis oder eine Datei der App). */
    val source: String? = null,
    /** Upload: „rename“ oder „replace“. */
    val conflict: String = "rename",
    /** Upload: die Kennung am NAS, damit ein neuer Versuch dort weitermacht. */
    val uploadId: String? = null,
    /** Upload: Quelle ist eine Kopie der App (Teilen), nach dem Hochladen löschen. */
    val ownCopy: Boolean = false,
    val waiting: WaitReason = WaitReason.None,
    /** Das NAS, zu dem die Übertragung gehört (mehrere NAS); leer: aus der Zeit davor, die alte Kopplung. */
    val nas: String = "",
    /** Am gekoppelten NAS selbst, nicht am gewählten Cluster-Knoten (Galerie der Foto-Sicherung). */
    val paired: Boolean = false,
    /** Download: wo die Datei liegt (content:// in Downloads); Android benennt bei gleichem Namen um. */
    val uri: String? = null,
    val runner: TransferSource = TransferSource.Queue,
    /** Foto-Sicherung: hochgeladene Fotos und Videos dieses Laufs; [place] ist der Zielordner. */
    val photos: Int = 0,
    val videos: Int = 0,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val finishedAt: Instant? get() = finishedAtMs?.let(Instant::ofEpochMilli)
    val active: Boolean get() = state == TransferState.Running || state == TransferState.Waiting

    /** Ein Lauf der Foto-Sicherung (Filter „Fotos“ in Übertragungen). */
    val backup: Boolean get() = runner == TransferSource.Backup

    /** „Erneut“ geht nur bei einem gescheiterten Auftrag der Warteschlange. */
    val retryable: Boolean get() = state == TransferState.Failed && runner == TransferSource.Queue && direction != Direction.Share

    /** Fertig geladen und noch auffindbar: „Öffnen“, „Teilen“, „Zeigen“. */
    val openable: Boolean get() = state == TransferState.Done && direction == Direction.Download && uri != null
}

/** Was gerade läuft, zusammengefasst (Ring in Dateien, laufende Mitteilung). */
data class TransferSummary(val count: Int, val done: Long, val total: Long, val downloads: Int, val uploads: Int, val first: String?) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

@Serializable
private data class TransferFile(val next: Long, val items: List<Transfer>)

/**
 * Liste aller Übertragungen; die Oberfläche liest sie direkt (Compose-Zustand),
 * Worker schreiben über [update]. Fertige bleiben [KEEP_DAYS] Tage, höchstens
 * [KEEP_DONE] Einträge. [file] = null hält sie nur im Speicher (Tests).
 * Wer auf Änderungen hört ([listen]: Mitteilungen, Meldung „gespeichert“),
 * bekommt sie außerhalb der Sperre.
 */
class TransferStore(private val file: File? = null, private val clock: () -> Long = System::currentTimeMillis) {
    private val next = AtomicLong(1)
    val items = mutableStateListOf<Transfer>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Transfer?, Transfer) -> Unit>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString(TransferFile.serializer(), f.readText()) }.getOrNull()?.let { saved ->
                next.set(saved.next)
                // Was beim letzten Mal lief, wartet jetzt auf seinen Auftrag.
                items.addAll(saved.items.map { if (it.state == TransferState.Running) it.copy(state = TransferState.Waiting) else it })
                trim()
            }
        }
    }

    /** Hört auf jede neue oder geänderte Übertragung (vorher: null bei neuen). */
    fun listen(l: (before: Transfer?, after: Transfer) -> Unit) {
        listeners += l
    }

    fun unlisten(l: (Transfer?, Transfer) -> Unit) {
        listeners -= l
    }

    fun add(t: Transfer): Transfer {
        val withId = t.copy(id = next.getAndIncrement())
        synchronized(this) {
            items.add(0, withId)
            trim()
            save()
        }
        listeners.forEach { runCatching { it(null, withId) } }
        return withId
    }

    fun update(id: Long, change: (Transfer) -> Transfer) {
        var changed: Pair<Transfer, Transfer>? = null
        synchronized(this) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) {
                val before = items[i]
                val after = change(before)
                items[i] = after
                changed = before to after
                // Fortschritt allein schreibt nicht bei jedem Stück.
                if (after.state != before.state || after.uploadId != before.uploadId || after.name != before.name ||
                    after.waiting != before.waiting || after.uri != before.uri || after.done - lastSaved.getOrDefault(id, 0L) > SAVE_EVERY_BYTES
                ) {
                    lastSaved[id] = after.done
                    save()
                }
            }
        }
        changed?.let { (b, a) -> if (a != b) listeners.forEach { runCatching { it(b, a) } } }
    }

    /** Laufende und wartende Übertragungen zusammen (ohne Teilen, das gleich wieder vorbei ist). */
    fun summary(): TransferSummary {
        val list = synchronized(this) { items.filter { it.active && it.direction != Direction.Share } }
        return TransferSummary(
            count = list.size,
            done = list.sumOf { it.done },
            total = list.sumOf { maxOf(it.total, it.done) },
            downloads = list.count { it.direction == Direction.Download },
            uploads = list.count { it.direction == Direction.Upload },
            first = list.lastOrNull()?.name,
        )
    }

    /** Die neueste Übertragung zu einer Datei des Ordners (Zeile in Dateien: Fortschritt oder „in Downloads“). */
    fun latestFor(root: String, dir: String, name: String): Transfer? = synchronized(this) {
        items.firstOrNull { it.root == root && it.dir == dir && it.name == name && it.direction != Direction.Share && !it.backup }
    }

    fun get(id: Long): Transfer? = synchronized(this) { items.firstOrNull { it.id == id } }

    /** Fertige aus der Liste nehmen („Liste leeren“). */
    fun clearFinished() {
        synchronized(this) {
            items.removeAll { !it.active }
            save()
        }
    }

    val running: Int get() = items.count { it.active }
    val finished: Int get() = items.count { it.state == TransferState.Done }

    private val lastSaved = HashMap<Long, Long>()

    /** Fertige älter als [KEEP_DAYS] Tage gehen, und nie mehr als [KEEP_DONE]. */
    private fun trim() {
        val cut = clock() - KEEP_DAYS * DAY_MS
        items.removeAll { !it.active && (it.finishedAtMs ?: Long.MAX_VALUE) < cut }
        val done = items.filter { !it.active }
        if (done.size > KEEP_DONE) items.removeAll(done.drop(KEEP_DONE).toSet())
    }

    /** Alte Einträge jetzt vergessen (beim Öffnen von Übertragungen). */
    fun prune() {
        synchronized(this) {
            val before = items.size
            trim()
            if (items.size != before) save()
        }
    }

    private fun save() {
        val f = file ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            tmp.writeText(json.encodeToString(TransferFile.serializer(), TransferFile(next.get(), items.toList())))
            tmp.renameTo(f)
        }
    }

    companion object {
        const val KEEP_DONE = 50
        /** Wie lange fertige Einträge bleiben (Entwurf 23: „Einträge bleiben 7 Tage“). */
        const val KEEP_DAYS = 7L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val SAVE_EVERY_BYTES = 32L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
