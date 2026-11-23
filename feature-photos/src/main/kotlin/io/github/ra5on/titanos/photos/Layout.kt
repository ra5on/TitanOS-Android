package io.github.ra5on.titanos.photos

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

// Wo eine Aufnahme am NAS landet: Ordnerstruktur, Monatsformat und
// Dateiname nach Wahl (ohne Android, damit Unit-Tests es prüfen).

/** Ordnerstruktur unter dem Ordner der Sicherung. */
enum class Structure {
    /** Alles in einen Ordner. */
    Flat,
    Year,
    YearMonth,
    YearMonthDay,

    /** Nach Album (Ordner am Handy, etwa „Camera“, „WhatsApp Images“). */
    Album,
    AlbumYear,
    AlbumYearMonth,
    YearAlbum,
    YearMonthAlbum,

    /** Eigenes Muster wie `{Jahr}/{Monat}`. */
    Custom,
}

/** Wie der Monat im Ordnernamen steht. */
enum class MonthFormat {
    /** „03“ */
    Number,

    /** „03 März“ */
    NumberName,

    /** „März“ */
    Name,

    /** „2026-03“ */
    YearDash,
}

/** Wie die Datei am NAS heißt. */
enum class Naming {
    /** Wie am Handy. */
    Original,

    /** Nach Aufnahmezeit, „2026-03-14 15.30.12.jpg“. */
    Taken,

    /** Aufnahmezeit und Originalname, „2026-03-14 15.30.12 IMG_1234.jpg“. */
    TakenAndOriginal,

    /** Eigenes Muster. */
    Custom,
}

/** Was bei gleichem Namen mit anderem Inhalt geschieht (gleicher Inhalt gilt immer als gesichert). */
enum class SameName {
    /** „IMG_1 (2).jpg“ */
    Rename,
    Skip,

    /** Die Datei am NAS wird ersetzt. */
    Replace,
    ;

    /** `conflict` beim Start des Uploads (files/uploads.go). */
    val wire: String get() = when (this) {
        Rename -> "rename"
        Replace -> "replace"
        Skip -> ""
    }
}

/** Platzhalter der Muster; deutsch und englisch. */
object Tokens {
    val ALL = listOf(
        "{Jahr}", "{Monat}", "{MonatZahl}", "{MonatName}", "{Tag}", "{Stunde}", "{Minute}", "{Sekunde}",
        "{Album}", "{Ordner}", "{Name}", "{Art}", "{Gerät}",
    )
    private val ALIASES = mapOf(
        "year" to "jahr", "month" to "monat", "month_number" to "monatzahl", "monthnumber" to "monatzahl",
        "month_name" to "monatname", "monthname" to "monatname", "day" to "tag", "hour" to "stunde",
        "second" to "sekunde", "album" to "album", "folder" to "ordner", "name" to "name", "kind" to "art",
        "device" to "gerät", "geraet" to "gerät",
    )

    fun canonical(token: String): String = token.lowercase().let { ALIASES[it] ?: it }
}

/** Alles, was die Ablage braucht. */
data class LayoutChoice(
    val structure: Structure = Structure.YearMonth,
    val pattern: String = "{Jahr}/{Monat}",
    val month: MonthFormat = MonthFormat.Number,
    val naming: Naming = Naming.Original,
    val namePattern: String = "{Jahr}-{MonatZahl}-{Tag} {Stunde}.{Minute}.{Sekunde}",
)

/** Muster der festen Strukturen. */
fun Structure.pattern(custom: String): String = when (this) {
    Structure.Flat -> ""
    Structure.Year -> "{Jahr}"
    Structure.YearMonth -> "{Jahr}/{Monat}"
    Structure.YearMonthDay -> "{Jahr}/{Monat}/{Tag}"
    Structure.Album -> "{Album}"
    Structure.AlbumYear -> "{Album}/{Jahr}"
    Structure.AlbumYearMonth -> "{Album}/{Jahr}/{Monat}"
    Structure.YearAlbum -> "{Jahr}/{Album}"
    Structure.YearMonthAlbum -> "{Jahr}/{Monat}/{Album}"
    Structure.Custom -> custom
}

/** Ob das Monatsformat für die Wahl eine Rolle spielt. */
fun LayoutChoice.usesMonth(): Boolean = structure.pattern(pattern).contains("{monat}", ignoreCase = true) ||
    structure.pattern(pattern).contains("{month}", ignoreCase = true)

/** Was ein Platzhalter für eine Aufnahme bedeutet. */
data class Facts(
    val taken: Instant,
    val name: String,
    val album: String,
    val folder: String,
    val kind: Kind,
    val device: String,
    val kindNames: Pair<String, String> = "Fotos" to "Videos",
)

private val TOKEN = Regex("\\{([^{}]+)\\}")

/** Setzt die Platzhalter ein; Unbekanntes bleibt stehen, wie es ist. */
fun fill(pattern: String, f: Facts, month: MonthFormat, zone: ZoneId, locale: Locale): String {
    val t: ZonedDateTime = f.taken.atZone(zone)
    val mm = "%02d".format(t.monthValue)
    val monthName = t.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
    val base = f.name.substringBeforeLast('.', f.name)
    return TOKEN.replace(pattern) { m ->
        when (Tokens.canonical(m.groupValues[1])) {
            "jahr" -> "%04d".format(t.year)
            "monat" -> when (month) {
                MonthFormat.Number -> mm
                MonthFormat.NumberName -> "$mm $monthName"
                MonthFormat.Name -> monthName
                MonthFormat.YearDash -> "%04d-%s".format(t.year, mm)
            }
            "monatzahl" -> mm
            "monatname" -> monthName
            "tag" -> "%02d".format(t.dayOfMonth)
            "stunde" -> "%02d".format(t.hour)
            "minute" -> "%02d".format(t.minute)
            "sekunde" -> "%02d".format(t.second)
            "album" -> f.album.ifBlank { f.folder.trim('/').substringAfterLast('/') }.ifBlank { "Handy" }
            "ordner" -> f.folder.trim('/').ifBlank { "Handy" }
            "name" -> base
            "art" -> if (f.kind == Kind.Video) f.kindNames.second else f.kindNames.first
            "gerät" -> f.device
            else -> m.value
        }
    }
}

/**
 * Ein Ordner- oder Dateiname, wie ihn NAS und SMB vertragen: keine
 * Schrägstriche oder Zeichen, die Windows verbietet, kein Punkt am Anfang
 * (versteckt), nicht „.“/„..“, höchstens 255 Bytes.
 */
fun cleanSegment(raw: String): String {
    var s = raw.map { ch -> if (ch < ' ' || ch in "\\:*?\"<>|/") '_' else ch }.joinToString("").trim()
    while (s.startsWith('.')) s = "_" + s.drop(1)
    s = s.trimEnd('.', ' ')
    while (s.toByteArray(Charsets.UTF_8).size > 255) s = s.dropLast(1)
    return s
}

/** Pfad aus Segmenten; leere fallen weg, jedes wird gesäubert. */
fun cleanPath(raw: String): String = raw.split('/').map(::cleanSegment).filter { it.isNotEmpty() }.joinToString("/")

/** Unterordner relativ zum Ordner der Sicherung, z. B. `2026/03 März`. */
fun subDir(choice: LayoutChoice, f: Facts, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    cleanPath(fill(choice.structure.pattern(choice.pattern), f, choice.month, zone, locale))

/** Dateiname am NAS; die Endung bleibt immer die des Originals. */
fun fileName(choice: LayoutChoice, f: Facts, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val ext = f.name.substringAfterLast('.', "").let { if (it.isEmpty() || it == f.name) "" else ".$it" }
    val stem = when (choice.naming) {
        Naming.Original -> return cleanSegment(f.name).ifEmpty { "Aufnahme$ext" }
        Naming.Taken -> fill("{Jahr}-{MonatZahl}-{Tag} {Stunde}.{Minute}.{Sekunde}", f, choice.month, zone, locale)
        Naming.TakenAndOriginal -> fill("{Jahr}-{MonatZahl}-{Tag} {Stunde}.{Minute}.{Sekunde} {Name}", f, choice.month, zone, locale)
        Naming.Custom -> fill(choice.namePattern, f, choice.month, zone, locale).replace('/', '_')
    }
    val clean = cleanSegment(stem).ifEmpty { cleanSegment(f.name.substringBeforeLast('.')) }.ifEmpty { "Aufnahme" }
    return cleanSegment(clean + ext)
}

/** Ob ein eigenes Muster brauchbar ist: keine leeren Klammern, nichts außerhalb der Freigabe. */
fun validPattern(pattern: String): Boolean {
    if (pattern.isBlank()) return true
    if (pattern.count { it == '{' } != pattern.count { it == '}' }) return false
    return pattern.split('/').none { it.trim() == ".." }
}

// ---- Was gesichert wird ----

private val RAW = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "raf", "pef", "srw", "x3f", "3fr", "erf", "kdc", "mrw", "raw")

fun isRaw(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in RAW

/** Bildschirmfotos: im Ordner „Screenshots“ oder mit dem Namen „Screenshot…“. */
fun isScreenshot(m: Media): Boolean =
    m.folder.split('/').any { it.equals("Screenshots", ignoreCase = true) || it.equals("Screenshot", ignoreCase = true) } ||
        m.name.startsWith("Screenshot", ignoreCase = true)

/** Bewegtbilder (Motion Photo, Live-Foto): ein Foto mit eingebettetem Video, am Namen erkannt. */
fun isMotionPhoto(m: Media): Boolean {
    val n = m.name.uppercase()
    return m.kind == Kind.Photo && (n.startsWith("MVIMG_") || n.contains(".MP.") || n.endsWith(".MP.JPG") || n.contains("_MP.") || n.contains("MOTION"))
}

/** Die Auswahl nach Art (Bewegtbilder, Screenshots, RAW, ab einem Tag). */
data class Include(
    val motion: Boolean = true,
    val screenshots: Boolean = true,
    val raw: Boolean = true,
    /** Nur Aufnahmen ab diesem Zeitpunkt (ms); 0 = alle. */
    val sinceMs: Long = 0,
)

fun Include.accepts(m: Media): Boolean = when {
    !motion && isMotionPhoto(m) -> false
    !screenshots && isScreenshot(m) -> false
    !raw && isRaw(m.name) -> false
    sinceMs > 0 && m.taken.toEpochMilli() < sinceMs -> false
    else -> true
}
