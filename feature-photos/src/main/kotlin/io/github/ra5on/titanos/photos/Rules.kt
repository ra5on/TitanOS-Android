package io.github.ra5on.titanos.photos

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Regeln der Foto-Sicherung ohne Android, damit sie Unit-Tests prüfen
// (TECHNIK §3, Entwürfe 30–33).

enum class Kind { Photo, Video }

/** Wann eine Art gesichert wird (Entwurf 32). */
enum class When { Immediate, Wifi, WifiCharging, Manual }

/** Welches Netz ein Upload braucht. */
enum class NetNeed { NotRoaming, Unmetered }

/** Bedingungen eines Hintergrund-Uploads; Roaming nie. */
data class Need(val network: NetNeed, val charging: Boolean)

/**
 * Bedingungen je Einstellung. „Sofort“ nutzt mobile Daten nur, wenn der
 * Schalter „Auch über mobile Daten“ an ist, und nie im Ausland. Null: nur
 * von Hand, kein Auftrag im Hintergrund.
 */
fun needFor(w: When, mobileData: Boolean): Need? = when (w) {
    When.Immediate -> Need(if (mobileData) NetNeed.NotRoaming else NetNeed.Unmetered, charging = false)
    When.Wifi -> Need(NetNeed.Unmetered, charging = false)
    When.WifiCharging -> Need(NetNeed.Unmetered, charging = true)
    When.Manual -> null
}

/** „Jetzt sichern“: ohne Ladebedingung, das Netz wie bei „Sofort“. */
fun needForNow(mobileData: Boolean): Need = Need(if (mobileData) NetNeed.NotRoaming else NetNeed.Unmetered, charging = false)

/** Was das Handy gerade bietet. */
data class DeviceState(
    val online: Boolean,
    val unmetered: Boolean,
    val roaming: Boolean,
    val charging: Boolean,
)

/** Was beim letzten Versuch schiefging. */
enum class Problem { NasUnreachable, NoVolume, Certificate, Locked, Failed }

/** Warum etwas noch nicht gesichert ist; die App zeigt es immer in Worten. */
enum class Wait {
    None, Off, NoPermission, NoTarget, Manual,
    Network, Wifi, Roaming, Charging,
    NasUnreachable, NoVolume, Certificate, Locked, Failed,

    /** Alle Bedingungen erfüllt; Android startet den Auftrag selbst. */
    Android,
}

/**
 * Der Grund in der Reihenfolge, in der man ihn beheben würde: erst die
 * Einstellungen, dann das NAS, dann die Bedingungen von Android.
 */
fun waitReason(
    pending: Int,
    enabled: Boolean,
    permitted: Boolean,
    hasTarget: Boolean,
    w: When,
    mobileData: Boolean,
    device: DeviceState,
    problem: Problem?,
): Wait {
    if (pending <= 0) return Wait.None
    if (!enabled) return Wait.Off
    if (!permitted) return Wait.NoPermission
    if (!hasTarget) return Wait.NoTarget
    val need = needFor(w, mobileData) ?: return Wait.Manual
    when (problem) {
        Problem.NoVolume -> return Wait.NoVolume
        Problem.Certificate -> return Wait.Certificate
        Problem.Locked -> return Wait.Locked
        else -> Unit
    }
    unmet(need, device)?.let { return it }
    return when (problem) {
        Problem.NasUnreachable -> Wait.NasUnreachable
        Problem.Failed -> Wait.Failed
        else -> Wait.Android
    }
}

/** Die erste Bedingung, die das Handy gerade nicht erfüllt, sonst null. */
fun unmet(need: Need, device: DeviceState): Wait? = when {
    !device.online -> Wait.Network
    need.network == NetNeed.Unmetered && !device.unmetered -> Wait.Wifi
    need.network == NetNeed.NotRoaming && device.roaming && !device.unmetered -> Wait.Roaming
    need.charging && !device.charging -> Wait.Charging
    else -> null
}

/** Ordner am NAS relativ zur Freigabe: der Ordner der Sicherung, ggf. mit Jahr/Monat. */
fun targetDir(dir: String, yearMonth: Boolean, taken: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val base = dir.trim('/')
    if (!yearMonth) return base
    val d = taken.atZone(zone).toLocalDate()
    val sub = "%04d/%02d".format(d.year, d.monthValue)
    return if (base.isEmpty()) sub else "$base/$sub"
}

/** Die Ordner, die für [path] anzulegen sind, von oben nach unten. */
fun parents(path: String): List<String> {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    return parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
}

/**
 * Name bei gleichem Namen mit anderem Inhalt, wie das NAS ihn vergibt
 * (files/uploads.go renameUpload): „IMG_1 (2).jpg“. Die Endung ist ab dem
 * letzten Punkt, wie Gos path.Ext.
 */
fun collisionName(name: String, n: Int): String {
    val dot = name.lastIndexOf('.')
    val ext = if (dot >= 0) name.substring(dot) else ""
    return "${name.removeSuffix(ext)} ($n)$ext"
}

/** Ob [finalName] der Name von [name] oder einer seiner Ausweichnamen ist. */
fun isNameOf(finalName: String, name: String): Boolean =
    finalName == name || (2..9999).any { collisionName(name, it) == finalName }

/** Eine Aufnahme am Handy, nur die Felder, die die Regeln brauchen. */
data class Media(
    val id: Long,
    val kind: Kind,
    val name: String,
    val folder: String,
    val size: Long,
    /** Aufnahmezeit, sonst Zeitpunkt des Hinzufügens. */
    val taken: Instant,
    /** DATE_MODIFIED in Sekunden. */
    val modified: Long,
    /** Album am Handy (BUCKET_DISPLAY_NAME), etwa „Camera“. */
    val bucket: String = "",
)

/** Was die App von einer gesicherten Aufnahme weiß. */
data class SavedMark(val size: Long, val modified: Long, val sha256: String, val target: String)

/** Ob eine Aufnahme mit diesem Stand für dieses Ziel gesichert ist. */
fun isSaved(m: Media, mark: SavedMark?, target: String): Boolean =
    mark != null && mark.size == m.size && mark.modified == m.modified && mark.target == target

/** Standard der Ordner am Handy: die Kamera (alles unter DCIM/). */
fun defaultFolder(folder: String): Boolean = folder.startsWith("DCIM/")

/**
 * Was hochzuladen ist: in einem gewählten Ordner, von einer gewählten Art,
 * nicht schon gesichert; das Neueste zuerst.
 */
fun selectForUpload(
    items: List<Media>,
    saved: Map<Long, SavedMark>,
    target: String,
    kinds: Set<Kind>,
    folders: Set<String>?,
    include: Include = Include(),
    targetOf: (Kind) -> String = { target },
): List<Media> = items.asSequence()
    .filter { it.kind in kinds }
    .filter { folders?.contains(it.folder) ?: defaultFolder(it.folder) }
    .filter { include.accepts(it) }
    .filter { !isSaved(it, saved[it.id], targetOf(it.kind)) }
    .sortedByDescending { it.taken }
    .toList()

/** Ab Werk bleiben die Aufnahmen der letzten 30 Tage auf dem Handy. */
const val KEEP_DAYS = 30L

/**
 * Was „Speicher freigeben“ vorschlägt: nur Aufnahmen, die die App als
 * gesichert kennt (gleicher Stand, irgendein Ziel), älter als [keepDays].
 * Gelöscht wird erst, wenn das NAS jede davon bestätigt (verify).
 */
fun freeCandidates(items: List<Media>, saved: Map<Long, SavedMark>, now: Instant, keepDays: Long = KEEP_DAYS): List<Media> {
    val cut = now.minus(keepDays, ChronoUnit.DAYS)
    return items.filter { m ->
        val mark = saved[m.id]
        mark != null && mark.size == m.size && mark.modified == m.modified && m.taken.isBefore(cut)
    }
}

/** Aus den Antworten von verify: nur die bestätigten Aufnahmen (state „saved“ mit derselben Prüfsumme). */
fun confirmedOnly(candidates: List<Media>, saved: Map<Long, SavedMark>, answers: Map<String, String>): List<Media> =
    candidates.filter { m -> saved[m.id]?.sha256?.let { answers[it] } == "saved" }

/**
 * Ob ein Abgleich nötig ist: Android hat für einen Speicher eine neue
 * Generation, oder seine Version hat sich geändert (dann sagen die
 * Generationen nichts mehr und alles wird neu gelesen).
 */
fun mediaChanged(
    before: Map<String, Long>,
    versionsBefore: Map<String, String>,
    now: Map<String, Long>,
    versionsNow: Map<String, String>,
): Boolean = now.keys != before.keys || versionsNow != versionsBefore || now.any { (v, g) -> before[v] != g }

/** Auswahl nach allen Einstellungen; übersprungene (gleicher Name) zählen wie gesichert, aber nur hier. */
fun selectForUpload(items: List<Media>, marks: Map<Long, SavedMark>, s: PhotoSettings, kinds: Set<Kind>): List<Media> =
    selectForUpload(items, marks, s.target, kinds.intersect(s.kindsOn()), s.folders, s.include, s::targetOf)

/** Ort relativ zum Ordner der Sicherung, z. B. `2026/03/IMG_1.jpg` (für check mit path). */
fun pathInTarget(s: PhotoSettings, m: Media, device: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), locale: java.util.Locale = java.util.Locale.getDefault()): Pair<String, String> {
    val f = Facts(m.taken, m.name, m.bucket, m.folder, m.kind, device)
    return subDir(s.layout, f, zone, locale) to fileName(s.layout, f, zone, locale)
}
