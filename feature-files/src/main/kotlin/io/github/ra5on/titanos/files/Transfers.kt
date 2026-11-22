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
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val finishedAt: Instant? get() = finishedAtMs?.let(Instant::ofEpochMilli)
    val active: Boolean get() = state == TransferState.Running || state == TransferState.Waiting
}

@Serializable
private data class TransferFile(val next: Long, val items: List<Transfer>)

/**
 * Liste aller Übertragungen; die Oberfläche liest sie direkt (Compose-Zustand),
 * Worker schreiben über [update]. Fertige werden nach [KEEP_DONE] Einträgen
 * vergessen. [file] = null hält sie nur im Speicher (Tests).
 */
class TransferStore(private val file: File? = null) {
    private val next = AtomicLong(1)
    val items = mutableStateListOf<Transfer>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString(TransferFile.serializer(), f.readText()) }.getOrNull()?.let { saved ->
                next.set(saved.next)
                // Was beim letzten Mal lief, wartet jetzt auf seinen Auftrag.
                items.addAll(saved.items.map { if (it.state == TransferState.Running) it.copy(state = TransferState.Waiting) else it })
            }
        }
    }

    fun add(t: Transfer): Transfer {
        val withId = t.copy(id = next.getAndIncrement())
        synchronized(this) {
            items.add(0, withId)
            trim()
            save()
        }
        return withId
    }

    fun update(id: Long, change: (Transfer) -> Transfer) {
        synchronized(this) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) {
                val before = items[i]
                val after = change(before)
                items[i] = after
                // Fortschritt allein schreibt nicht bei jedem Stück.
                if (after.state != before.state || after.uploadId != before.uploadId || after.name != before.name ||
                    after.waiting != before.waiting || after.done - lastSaved.getOrDefault(id, 0L) > SAVE_EVERY_BYTES
                ) {
                    lastSaved[id] = after.done
                    save()
                }
            }
        }
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

    private fun trim() {
        val done = items.filter { !it.active }
        if (done.size > KEEP_DONE) items.removeAll(done.drop(KEEP_DONE).toSet())
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
        private const val SAVE_EVERY_BYTES = 32L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
