package io.github.ra5on.titanos.watch.data

import android.content.Context
import io.github.ra5on.titanos.net.TitanJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/** Ein Volume, das nicht in Ordnung ist: Name und Zustand des NAS (degraded, failed, missing …). */
@Serializable
data class VolumeProblem(val name: String, val state: String)

/** Was Kachel, Komplikation und Startseite zeigen. */
@Serializable
data class WatchStatus(
    val hostname: String = "",
    val version: String = "",
    val volumes: Int = 0,
    val problems: List<VolumeProblem> = emptyList(),
    val usedBytes: Long = 0,
    val sizeBytes: Long = 0,
    /** Meldungen seit dem letzten Blick in die Liste; warnings davon mit Warnung oder Fehler. */
    val newMessages: Int = 0,
    val warnings: Int = 0,
    val lastMessage: String? = null,
    /** Wann geholt (ms seit 1970). */
    val at: Long = 0,
) {
    val ok: Boolean get() = problems.isEmpty() && warnings == 0
    val usedPercent: Int? get() = if (sizeBytes > 0) ((usedBytes * 100 + sizeBytes / 2) / sizeBytes).toInt().coerceIn(0, 100) else null
    val freeBytes: Long get() = (sizeBytes - usedBytes).coerceAtLeast(0)
}

/**
 * Holt den Zustand und hält den letzten Stand für Kachel und Komplikation.
 * Keine Dauerverbindung: geholt wird nur, wenn die App sichtbar ist oder
 * Android die Kachel bzw. Komplikation neu zeichnen will.
 */
class StatusStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("status", Context.MODE_PRIVATE)

    fun cached(): WatchStatus? = prefs.getString("last", null)?.let {
        runCatching { TitanJson.decodeFromString(WatchStatus.serializer(), it) }.getOrNull()
    }

    /** Marke der zuletzt angesehenen Meldung (`<epoch>-<nummer>`). */
    var seen: String?
        get() = prefs.getString("seen", null)
        set(v) {
            prefs.edit().putString("seen", v).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    suspend fun refresh(api: WatchApi, now: Long = System.currentTimeMillis()): WatchStatus = coroutineScope {
        val system = async { api.system() }
        val storage = async { api.storage() }
        val since = seen
        val notes = async { runCatching { api.notifications(since) }.getOrNull() }
        val volumes = storage.await().volumes
        val n = notes.await()
        val fresh = when {
            n == null -> emptyList()
            // Neue Epoche (NAS neu gestartet) oder noch nie geschaut: nichts davon gilt als neu.
            n.reset || since == null -> emptyList()
            else -> n.items
        }
        if (n != null && (n.reset || since == null)) seen = n.latest
        val status = WatchStatus(
            hostname = system.await().hostname,
            version = system.await().version,
            volumes = volumes.size,
            problems = volumes.filter { it.state != "ok" || !it.mounted }.map { VolumeProblem(it.name, if (!it.mounted && it.state == "ok") "unmounted" else it.state) },
            usedBytes = volumes.filter { it.mounted }.sumOf { it.usedBytes },
            sizeBytes = volumes.filter { it.mounted }.sumOf { it.sizeBytes },
            newMessages = fresh.size,
            warnings = fresh.count { it.severity == "warning" || it.severity == "error" },
            lastMessage = fresh.lastOrNull()?.title?.takeIf { it.isNotBlank() },
            at = now,
        )
        prefs.edit().putString("last", TitanJson.encodeToString(WatchStatus.serializer(), status)).apply()
        status
    }
}
