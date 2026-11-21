package io.github.ra5on.titanos.photos

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Eine gesicherte Aufnahme: Stand am Handy, Prüfsumme, Ziel und Ort am NAS. */
@Serializable
data class Saved(val size: Long, val modified: Long, val sha256: String, val target: String, val path: String)

/** Zwischengespeicherte Prüfsumme je Aufnahme und Stand. */
@Serializable
data class Hashed(val size: Long, val modified: Long, val sha256: String)

/** Ein angefangener Upload, damit der nächste Lauf dort weitermacht. */
@Serializable
data class Started(val id: String, val dir: String, val name: String, val size: Long, val sha256: String)

@Serializable
data class StateFile(
    val saved: Map<Long, Saved> = emptyMap(),
    val hashes: Map<Long, Hashed> = emptyMap(),
    val uploads: Map<Long, Started> = emptyMap(),
    /** Übersprungen, weil am NAS schon eine andere Datei so heißt („Überspringen“); nie für „Speicher freigeben“. */
    val skipped: Map<Long, Saved> = emptyMap(),
    val generations: Map<String, Long> = emptyMap(),
    val versions: Map<String, String> = emptyMap(),
    /** Zuletzt etwas gesichert, Millisekunden. */
    val lastSaved: Long = 0,
    /** Letzter Lauf, Millisekunden. */
    val lastRun: Long = 0,
    val problem: Problem? = null,
)

/**
 * Was die App über die Sicherung weiß, als JSON in ihrem privaten Speicher.
 * Geschrieben wird atomar (temporäre Datei, dann umbenennen) und nicht nach
 * jeder Aufnahme, sondern höchstens alle paar Sekunden und am Ende eines Laufs.
 */
class BackupStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var state: StateFile = read()
    private var dirty = false
    private var lastWrite = 0L

    @Synchronized
    fun snapshot(): StateFile = state

    @Synchronized
    fun update(change: (StateFile) -> StateFile) {
        state = change(state)
        dirty = true
        val now = System.currentTimeMillis()
        if (now - lastWrite > 5_000) flush()
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(StateFile.serializer(), state))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return
        }
        dirty = false
        lastWrite = System.currentTimeMillis()
    }

    /** Alles vergessen, z. B. nach dem Aufheben der Kopplung. */
    @Synchronized
    fun clear() {
        state = StateFile()
        dirty = false
        file.delete()
    }

    private fun read(): StateFile = try {
        if (file.exists()) json.decodeFromString(StateFile.serializer(), file.readText()) else StateFile()
    } catch (_: Exception) {
        // Unlesbar: neu anfangen; das NAS erkennt Gesichertes am Inhalt wieder.
        StateFile()
    }

    companion object {
        @Volatile
        private var instance: BackupStore? = null

        fun get(context: Context): BackupStore = instance ?: synchronized(this) {
            instance ?: BackupStore(File(context.applicationContext.filesDir, "photos-state.json")).also { instance = it }
        }
    }
}

/** Die Marken für die Regeln (Rules.kt). */
fun StateFile.marks(): Map<Long, SavedMark> = saved.mapValues { (_, s) -> SavedMark(s.size, s.modified, s.sha256, s.target) }

/** Für die Auswahl: gesichert oder bewusst übersprungen. */
fun StateFile.handled(): Map<Long, SavedMark> = skipped.mapValues { (_, s) -> SavedMark(s.size, s.modified, s.sha256, s.target) } + marks()
