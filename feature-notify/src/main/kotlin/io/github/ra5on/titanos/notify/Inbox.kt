package io.github.ra5on.titanos.notify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Was die App von den Mitteilungen weiß; ohne NAS zeigt die Liste diesen Stand. */
@Serializable
data class InboxState(
    /** Marke der neuesten Mitteilung, nach der das nächste Mal gefragt wird. */
    val marker: String = "",
    /** Älteste zuerst. */
    val items: List<Note> = emptyList(),
    /** Gelesen (nur am Handy; das NAS kennt keine Gelesen-Marke). */
    val read: Set<String> = emptySet(),
    /** Einmal abgeglichen: erst danach wird Neues als Android-Mitteilung gezeigt. */
    val primed: Boolean = false,
)

/** Ergebnis eines Abgleichs: der neue Stand und was davon klingeln darf. */
data class Merged(val state: InboxState, val fresh: List<Note>)

object Inbox {
    /** Höchstens so viele Mitteilungen bleiben am Handy, keine älter als [KEEP_MILLIS]. */
    const val MAX_ITEMS = 200
    const val KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** Höchstens so viele Android-Mitteilungen auf einmal (nach langer Pause kein Schwall). */
    const val MAX_POST = 5

    /** Älteres als das klingelt nicht mehr, es steht nur in der Liste. */
    const val POST_WITHIN_MILLIS = 12L * 60 * 60 * 1000

    /**
     * Nimmt [incoming] in den Stand auf. Doppelte (gleiche Marke) zählen
     * einmal. Neu und zum Klingeln ist, was nicht [quiet] ist, schon einmal
     * abgeglichen wurde, nicht zu alt ist; höchstens [MAX_POST], die neuesten.
     * [quiet] gilt für den ersten Abgleich nach dem Koppeln und für einen
     * Neubeginn des NAS (reset): dann steht alles nur in der Liste.
     */
    fun merge(state: InboxState, incoming: List<Note>, latest: String, quiet: Boolean, now: Long): Merged {
        val known = state.items.mapTo(HashSet()) { it.id }
        val added = incoming.filter { known.add(it.id) }
        val cut = now - KEEP_MILLIS
        val items = (state.items + added)
            .filter { it.millis == 0L || it.millis >= cut }
            .let { if (it.size > MAX_ITEMS) it.takeLast(MAX_ITEMS) else it }
        val ids = items.mapTo(HashSet()) { it.id }
        val ring = if (quiet || !state.primed) {
            emptyList()
        } else {
            added.filter { it.id in ids && it.id !in state.read && (it.millis == 0L || now - it.millis <= POST_WITHIN_MILLIS) }.takeLast(MAX_POST)
        }
        val marker = if (latest.isEmpty()) state.marker else Marker.newer(state.marker, latest)
        return Merged(
            InboxState(marker = marker, items = items, read = state.read.filterTo(HashSet()) { it in ids }, primed = true),
            ring,
        )
    }

    fun markRead(state: InboxState, ids: Collection<String>): InboxState =
        state.copy(read = state.read + ids.filter { id -> state.items.any { it.id == id } })

    fun unread(state: InboxState): Int = state.items.count { it.id !in state.read }
}

/**
 * Der Stand als JSON im privaten Speicher der App, atomar geschrieben
 * (temporäre Datei, dann umbenennen). Strom, Auftrag und Öffnen der App
 * laufen im selben Prozess und gehen über [update].
 */
class InboxStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var state: InboxState = read()

    @Synchronized
    fun snapshot(): InboxState = state

    @Synchronized
    fun <T> update(change: (InboxState) -> Pair<InboxState, T>): T {
        val (next, out) = change(state)
        if (next != state) {
            state = next
            write()
        }
        return out
    }

    @Synchronized
    fun clear() {
        state = InboxState()
        file.delete()
    }

    private fun write() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(InboxState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun read(): InboxState = try {
        if (file.exists()) json.decodeFromString(InboxState.serializer(), file.readText()) else InboxState()
    } catch (_: Exception) {
        InboxState()
    }
}
