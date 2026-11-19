package io.github.ra5on.titanos.files

import androidx.compose.runtime.mutableStateListOf
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

enum class Direction { Upload, Download, Share }

enum class TransferState { Waiting, Running, Done, Failed, Cancelled }

/** Eine Übertragung (Entwurf 23). Einfache Fassung: lebt, solange die App läuft. */
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
    val finishedAt: Instant? = null,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Liste aller Übertragungen; die Oberfläche liest sie direkt (Compose-Zustand). */
class TransferStore {
    private val next = AtomicLong(1)
    val items = mutableStateListOf<Transfer>()

    fun add(t: Transfer): Transfer {
        val withId = t.copy(id = next.getAndIncrement())
        synchronized(this) { items.add(0, withId) }
        return withId
    }

    fun update(id: Long, change: (Transfer) -> Transfer) {
        synchronized(this) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = change(items[i])
        }
    }

    fun get(id: Long): Transfer? = items.firstOrNull { it.id == id }

    val running: Int get() = items.count { it.state == TransferState.Running || it.state == TransferState.Waiting }
    val finished: Int get() = items.count { it.state == TransferState.Done }
}
