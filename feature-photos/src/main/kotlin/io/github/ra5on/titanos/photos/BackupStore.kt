package io.github.ra5on.titanos.photos

import android.content.Context
import io.github.ra5on.titanos.net.NasNames
import io.github.ra5on.titanos.net.NasRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Eine gesicherte Aufnahme: Stand am Handy, Prüfsumme, Ziel und Ort am NAS.
 * [verifiedAt]: wann das NAS sie zuletzt bestätigt hat (hochgeladen, gefunden
 * oder nachgeprüft, 1.03); 0 heißt noch nie nachgeprüft.
 */
@Serializable
data class Saved(val size: Long, val modified: Long, val sha256: String, val target: String, val path: String, val verifiedAt: Long = 0)

/** Zwischengespeicherte Prüfsumme je Aufnahme und Stand. */
@Serializable
data class Hashed(val size: Long, val modified: Long, val sha256: String)

/** Ein angefangener Upload, damit der nächste Lauf dort weitermacht. */
@Serializable
data class Started(val id: String, val dir: String, val name: String, val size: Long, val sha256: String)

/**
 * Was der letzte Lauf hochgeladen hat, der etwas hochgeladen hat (vom NAS
 * abgeschlossen, nicht nur am NAS gefunden): Anzahl je Art und die Ordner,
 * wie man sie in den Dateien sieht („Fotos/Handy Zeus/2026/11“).
 */
@Serializable
data class LastBatch(val photos: Int = 0, val videos: Int = 0, val folders: List<String> = emptyList(), val at: Long = 0) {
    val count: Int get() = photos + videos
}

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
    /** Zuletzt hochgeladen: wie viele und wohin. */
    val lastBatch: LastBatch? = null,
    /**
     * Gesichert, aber laut NAS nicht mehr dort (1.03, Nachprüfung im Lauf). Sie
     * werden nicht von selbst neu hochgeladen: vielleicht hat man sie am NAS
     * absichtlich gelöscht. „Erneut sichern“ nimmt sie wieder in die Sicherung.
     */
    val missing: Map<Long, Saved> = emptyMap(),
    /** „Nicht mehr sichern“: diese Aufnahmen lässt die Sicherung aus, bis man es in den Einstellungen zurücknimmt. */
    val ignored: Map<Long, Saved> = emptyMap(),
    /** Fehlende, zu denen man „Ausblenden“ gewählt hat: das Symbol oben warnt nicht mehr wegen ihnen. */
    val missingDismissed: Set<Long> = emptySet(),
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
        private val instances = HashMap<String, BackupStore>()

        /** Stand der Sicherung an ein NAS ([nas]); die alte Kopplung behält `photos-state.json`. */
        fun get(context: Context, nas: String = NasRegistry.LEGACY_ID): BackupStore = synchronized(instances) {
            instances.getOrPut(nas) { BackupStore(File(context.applicationContext.filesDir, "photos-state" + NasNames.suffix(nas) + ".json")) }
        }
    }
}

/** Die Marken für die Regeln (Rules.kt). */
fun StateFile.marks(): Map<Long, SavedMark> = saved.mapValues { (_, s) -> SavedMark(s.size, s.modified, s.sha256, s.target) }

/**
 * Für die Auswahl: gesichert, bewusst übersprungen, am NAS fehlend oder „nicht
 * mehr sichern“. Fehlende lädt die Sicherung nie von selbst neu hoch (1.03).
 */
fun StateFile.handled(): Map<Long, SavedMark> =
    (skipped + ignored + missing).mapValues { (_, s) -> SavedMark(s.size, s.modified, s.sha256, s.target) } + marks()
