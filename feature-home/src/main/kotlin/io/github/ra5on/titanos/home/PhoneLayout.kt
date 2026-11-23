package io.github.ra5on.titanos.home

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Der Startbildschirm der App (PLAN 117, Entwürfe 02–07): Seiten, Ordner,
 * Widgets und Dock. Gleiche Form wie das Handy im Web (web/src/lib/desktop/
 * phone-home.ts, Einstellung `desktop.phone`), gespeichert aber je Konto in
 * der Einstellung `androiddesktop` unter `phone` (Inhaber 23.11.2026: „die App
 * hat ihre eigene Einrichtung“). Nur die App kennt dazu: ein Dock mit fünf
 * Plätzen samt „immer anzeigen“ und „Beschriftung“, die Liste `hidden` (mit ×
 * vom Startbildschirm genommen) und den Schalter `useWeb` („Einrichtung des
 * Webs übernehmen“). Mit `useWeb` zeigt die App Seiten, Ordner und Widgets aus
 * `desktop.phone`; ihr Dock und ihre Schalter bleiben die eigenen.
 *
 * Schlüssel wie im Web: Programme „app:<id>“ (Kennungen aus apps.ts), Ordner
 * „folder:<id>“, Widgets „widget:<id>“. Was die App nicht zeigen kann (ein
 * Programm ohne Recht, ein Widget nur fürs Web), bleibt in der Liste und
 * fällt erst beim Zeichnen heraus.
 */

/** Ein Widget: „l“ füllt die Zeile (4 × 2 Zellen), „s“ die Hälfte (2 × 2). [raw]: wie gelesen (Felder des Webs bleiben). */
data class PhoneWidget(val id: String, val kind: String, val size: String = "l", val raw: JsonObject? = null)

data class PhoneFolder(val id: String, val name: String, val apps: List<String>)

/**
 * Das Dock der App. [apps] sind Plätze wie „app:home“, „app:files“,
 * „app:photos“, „app:notifications“ oder ein Programm; „Start“ ist immer
 * dabei, vorn. Gespeichert die ersten fünf unter `apps` (wie bis 1.01, eine
 * ältere Fassung liest nur sie) und bis zu zehn weitere unter `more`. [always]: auch in Programmen und auf Unterseiten (ab Werk an).
 */
data class PhoneDock(
    val show: Boolean = true,
    val apps: List<String> = emptyList(),
    val custom: Boolean = false,
    val always: Boolean = true,
    val labels: Boolean = true,
)

data class PhonePrefs(
    /** Selbst angeordnet (false: aus den Programmen gebaut). */
    val custom: Boolean = false,
    /** Seiten als Zellen in Lesereihenfolge (vier Spalten), `null` ist eine Lücke (1.02). */
    val pages: List<List<String?>> = emptyList(),
    val folders: List<PhoneFolder> = emptyList(),
    val widgets: List<PhoneWidget> = listOf(PhoneWidget(DEFAULT_STATUS_ID, "status", "l")),
    val dock: PhoneDock = PhoneDock(),
    /** Programme, die mit × vom Startbildschirm genommen wurden (bleiben unter „Hinzufügen“). */
    val hidden: List<String> = emptyList(),
    /** „Einrichtung des Webs übernehmen“ (ab Werk aus). */
    val useWeb: Boolean = false,
)

const val DEFAULT_STATUS_ID = "p-status"

/** Spalten des Rasters am Handy (wie PHONE_COLUMNS im Web). */
const val PHONE_COLUMNS = 4

/** Plätze im Dock der App (1.02: bis zu 15, je [DOCK_PAGE] auf einer Seite des Docks). */
const val DOCK_MAX = 15

/** So viele Plätze zeigt das Dock auf einmal; weitere auf den nächsten Seiten (wischen). */
const val DOCK_PAGE = 5

const val DOCK_HOME = "app:home"
const val DOCK_FILES = "app:files"
const val DOCK_PHOTOS = "app:photos"
const val DOCK_NOTIFICATIONS = "app:notifications"

/** Ab Werk: Start, Dateien, Fotos, Mitteilungen und Docker (Docker nur, wer es öffnen darf; sonst bleibt der Platz frei). */
val DOCK_DEFAULT = listOf(DOCK_HOME, DOCK_FILES, DOCK_PHOTOS, DOCK_NOTIFICATIONS, "app:docker")

/** Plätze, die es nur im Dock gibt (keine Programme der Startseite). */
val DOCK_ONLY = setOf(DOCK_HOME, DOCK_NOTIFICATIONS)

/** Widgets, die die App zeichnet und unter „Hinzufügen“ anbietet (Entwurf 04). „photos“ gibt es nur in der App. */
val HOME_WIDGET_KINDS = listOf("status", "volumes", "photos", "docker", "vms")

/** Programme, die ab Werk nicht im Raster stehen, weil sie im Dock sind (Entwurf 02; unter „Hinzufügen“ zu finden). */
val DEFAULT_OFF_GRID = setOf(DOCK_FILES, DOCK_PHOTOS)

private val ID = Regex("^[a-z0-9][a-z0-9:._-]{0,95}$", RegexOption.IGNORE_CASE)
private val KIND = Regex("^[a-z][a-z0-9]{0,31}$")

// ---------- Kennungen der App ↔ Schlüssel des Webs ----------

/** Wo die Kennung der App anders heißt als im Web (apps.ts): Plugins sind dort das App-Center. */
private val WEB_IDS = mapOf("plugins" to "appcenter")
private val APP_IDS = WEB_IDS.entries.associate { (a, w) -> w to a }

/** „app:<web-id>“ für ein Programm der App. */
fun programKey(id: String): String = "app:" + (WEB_IDS[id] ?: id)

/** Die Kennung der App zu „app:<web-id>“, sonst null. */
fun programIdOf(key: String): String? = key.removePrefix("app:").takeIf { key.startsWith("app:") }?.let { APP_IDS[it] ?: it }

// ---------- Lesen ----------

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun keys(v: JsonElement?, max: Int): List<String> =
    v.arr().mapNotNull { it.str()?.takeIf(ID::matches) }.distinct().take(max)

/** Zellen einer Seite: Schlüssel oder `null` (Lücke); doppelte und kaputte werden zur Lücke, keine Lücken am Ende. */
private fun cells(v: JsonElement?): List<String?> {
    val seen = HashSet<String>()
    var count = 0
    // Höchstens 240 Zellen (60 Zeilen) und 80 Einträge je Seite.
    return v.arr().take(PHONE_COLUMNS * 60).map { e -> e.str()?.takeIf { ID.matches(it) && count < 80 && seen.add(it) }?.also { count++ } }
        .dropLastWhile { it == null }
}

private fun cleanName(v: String?): String =
    v?.replace(Regex("[\\x00-\\x1f\\x7f]"), "")?.trim()?.take(60).orEmpty()

/** Liest, was das NAS schickte (wie sanitizePhone im Web); Kaputtes fällt auf den Werkszustand zurück. */
fun sanitizePhone(input: JsonElement?): PhonePrefs {
    val base = PhonePrefs()
    val v = input.obj() ?: return base
    val widgets = if (v["widgets"] is JsonArray) {
        v["widgets"].arr().mapNotNull { it.obj() }
            .filter { w -> w["id"].str()?.let(ID::matches) == true && w["kind"].str()?.let(KIND::matches) == true }
            .map { w -> PhoneWidget(w["id"].str()!!, w["kind"].str()!!, if (w["size"].str() == "s") "s" else "l", w) }
            .distinctBy { it.id }
            .take(30)
    } else {
        base.widgets
    }
    val folders = v["folders"].arr().mapNotNull { it.obj() }
        .filter { it["id"].str()?.let(ID::matches) == true }
        .map { PhoneFolder(it["id"].str()!!, cleanName(it["name"].str()).ifEmpty { "Ordner" }, keys(it["apps"], 40)) }
        .distinctBy { it.id }
        .take(40)
    val pages = v["pages"].arr().take(12).map(::cells)
    val d = v["dock"].obj() ?: JsonObject(emptyMap())
    return PhonePrefs(
        custom = v["custom"].bool() == true,
        pages = pages,
        folders = folders,
        widgets = widgets,
        dock = PhoneDock(
            show = d["show"].bool() != false,
            // Die ersten fünf in `apps` (so liest 1.01 sie), weitere in `more` (1.02).
            apps = (keys(d["apps"], DOCK_PAGE) + keys(d["more"], DOCK_MAX - DOCK_PAGE)).distinct().take(DOCK_MAX),
            custom = d["custom"].bool() == true,
            always = d["always"].bool() != false,
            labels = d["labels"].bool() != false,
        ),
        hidden = keys(v["hidden"], 200),
        useWeb = v["useWeb"].bool() == true,
    )
}

// ---------- Schreiben ----------

private fun jstr(list: List<String>) = JsonArray(list.map(::JsonPrimitive))

/**
 * Als JSON für `androiddesktop.phone`. Felder, die die App nicht kennt
 * ([stored]: der Stand am NAS), bleiben stehen; ebenso Felder eines Widgets.
 */
fun PhonePrefs.toJson(stored: JsonElement? = null): JsonObject {
    val out = (stored as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    out["custom"] = JsonPrimitive(custom)
    // Lücken als null (ältere Fassungen und das Web überspringen sie).
    out["pages"] = JsonArray(pages.map { page -> JsonArray(page.map { k -> if (k == null) JsonNull else JsonPrimitive(k) }) })
    out["folders"] = JsonArray(folders.map { JsonObject(mapOf("id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name), "apps" to jstr(it.apps))) })
    out["widgets"] = JsonArray(
        widgets.map { w ->
            JsonObject((w.raw ?: JsonObject(emptyMap())) + mapOf("id" to JsonPrimitive(w.id), "kind" to JsonPrimitive(w.kind), "size" to JsonPrimitive(w.size)))
        },
    )
    val storedDock = (out["dock"] as? JsonObject) ?: JsonObject(emptyMap())
    out["dock"] = JsonObject(
        storedDock + mapOf(
            "show" to JsonPrimitive(dock.show),
            "apps" to jstr(dock.apps.take(DOCK_PAGE)),
            "more" to jstr(dock.apps.drop(DOCK_PAGE)),
            "custom" to JsonPrimitive(dock.custom),
            "always" to JsonPrimitive(dock.always),
            "labels" to JsonPrimitive(dock.labels),
        ),
    )
    out["hidden"] = jstr(hidden)
    out["useWeb"] = JsonPrimitive(useWeb)
    return JsonObject(out)
}

// ---------- Anzeigen ----------

/**
 * Freie Plätze (1.02, Inhaber: „Ich will die Symbole frei setzen, wo ich will“):
 * Eine Seite ist eine Liste von Zellen in Lesereihenfolge, vier Spalten je
 * Zeile, `null` ist eine leere Zelle. Ein Eintrag steht an der Zelle seiner
 * linken oberen Ecke; ein Widget belegt 2 × 2 („s“) bzw. 4 × 2 („l“) Zellen,
 * die übrigen Zellen darunter sind `null`. Eine Seite ohne Lücken (Web,
 * Fassungen bis 1.01) wird wie bisher der Reihe nach gefüllt. Ältere Fassungen
 * lesen die Lücken nicht (sie überspringen `null`) und zeigen dieselben
 * Einträge in derselben Reihenfolge, nur ohne Lücken.
 */
data class Placed(val key: String, val row: Int, val col: Int, val w: Int, val h: Int) {
    val index: Int get() = row * PHONE_COLUMNS + col
}

data class PhoneShown(val pages: List<List<String?>>, val folders: List<PhoneFolder>)

/** Zellen eines Eintrags auf seiner Seite (Programm und Ordner 1, Widget „s“ 4, „l“ 8; wie cellsOf im Web). */
fun cellsOf(key: String, prefs: PhonePrefs): Int = spanOf(key, prefs).let { (w, h) -> w * h }

/** Breite und Höhe in Zellen: Programm und Ordner 1 × 1, Widget „s“ 2 × 2, „l“ 4 × 2. */
fun spanOf(key: String, prefs: PhonePrefs): Pair<Int, Int> {
    if (!key.startsWith("widget:")) return 1 to 1
    val w = prefs.widgets.firstOrNull { "widget:" + it.id == key }
    return (if (w?.size == "s") 2 else 4) to 2
}

/** Eine Seite als Zellen (Einträge an ihrer Stelle, sonst `null`, ohne Lücken am Ende). */
fun encodePage(placed: List<Placed>): List<String?> {
    if (placed.isEmpty()) return emptyList()
    val out = arrayOfNulls<String>(placed.maxOf { it.index } + 1)
    for (p in placed) out[p.index] = p.key
    return out.toList()
}

/** Belegung der Zellen einer Seite. */
internal class Occupancy(private val rows: Int, private val cols: Int = PHONE_COLUMNS) {
    private val taken = HashMap<Int, String>()

    fun free(row: Int, col: Int, w: Int, h: Int, except: String? = null): Boolean {
        if (row < 0 || col < 0 || col + w > cols || row + h > rows) return false
        for (r in row until row + h) for (c in col until col + w) {
            val k = taken[r * cols + c]
            if (k != null && k != except) return false
        }
        return true
    }

    fun put(key: String, row: Int, col: Int, w: Int, h: Int) {
        for (r in row until row + h) for (c in col until col + w) taken[r * cols + c] = key
    }

    fun at(index: Int): String? = taken[index]

    /** Erste Stelle ab Zelle [from] (Lesereihenfolge), an der [w] × [h] frei ist; Widgets nur an Spalte 0 bzw. 2. */
    fun firstFit(w: Int, h: Int, from: Int = 0): Pair<Int, Int>? {
        for (i in maxOf(0, from) until rows * cols) {
            val r = i / cols
            val c = i % cols
            if (c % w != 0 && w > 1) continue
            if (free(r, c, w, h)) return r to c
        }
        return null
    }
}

/** Spalte eines Widgets: „l“ immer 0, „s“ 0 oder 2. */
private fun snapCol(col: Int, w: Int): Int = if (w >= PHONE_COLUMNS) 0 else col - col % w

/**
 * Legt die Einträge einer Seite in ein Raster mit [rows] Zeilen. Mit Lücken
 * stehen sie an ihrer Zelle; was dort nicht passt (belegt, zu weit unten),
 * kommt an die erste freie Stelle. Ohne Lücken der Reihe nach wie bis 1.01
 * (ein Widget beginnt eine neue Zeile, zwei halbe teilen sich eine). Was gar
 * nicht passt, kommt zurück (für die nächste Seite).
 */
fun arrange(cells: List<String?>, prefs: PhonePrefs, rows: Int): Pair<List<Placed>, List<String>> {
    val grid = Occupancy(rows)
    val placed = ArrayList<Placed>()
    val rest = ArrayList<String>()
    if (null !in cells) {
        // Der Reihe nach, nie vor die zuletzt belegte Stelle zurück.
        var cursor = 0
        for (k in cells.filterNotNull()) {
            val (w, h) = spanOf(k, prefs)
            val at = grid.firstFit(w, h, cursor)
            if (at == null) {
                rest += k
                cursor = rows * PHONE_COLUMNS
                continue
            }
            grid.put(k, at.first, at.second, w, h)
            placed += Placed(k, at.first, at.second, w, h)
            cursor = at.first * PHONE_COLUMNS + at.second + w
        }
        return placed to rest
    }
    val later = ArrayList<String>()
    cells.forEachIndexed { i, k ->
        if (k == null) return@forEachIndexed
        val (w, h) = spanOf(k, prefs)
        val r = i / PHONE_COLUMNS
        val c = snapCol(i % PHONE_COLUMNS, w)
        if (grid.free(r, c, w, h)) {
            grid.put(k, r, c, w, h)
            placed += Placed(k, r, c, w, h)
        } else {
            later += k
        }
    }
    for (k in later) {
        val (w, h) = spanOf(k, prefs)
        val at = grid.firstFit(w, h)
        if (at == null) {
            rest += k
        } else {
            grid.put(k, at.first, at.second, w, h)
            placed += Placed(k, at.first, at.second, w, h)
        }
    }
    return placed.sortedBy { it.index } to rest
}

/** Zeilen, die eine Seite höchstens braucht (für ein Raster ohne Grenze). */
internal fun rowsNeeded(cells: List<String?>, prefs: PhonePrefs): Int =
    cells.size / PHONE_COLUMNS + 1 + cells.filterNotNull().sumOf { spanOf(it, prefs).second }

/** Verteilt Seiten auf Raster mit [rows] Zeilen: was nicht passt, kommt an die ersten freien Stellen der nächsten Seite. */
private fun fitPages(pages: List<List<String?>>, prefs: PhonePrefs, rows: Int): List<List<String?>> {
    val out = mutableListOf<List<String?>>()
    var carry = emptyList<String>()
    var i = 0
    while (i < pages.size || carry.isNotEmpty()) {
        val own = pages.getOrNull(i).orEmpty()
        // Der Übertrag kommt nach den eigenen Einträgen der Seite (an deren erste freie Stellen).
        val (placed, rest) = arrange(own, prefs, rows)
        val grid = Occupancy(rows).also { g -> placed.forEach { g.put(it.key, it.row, it.col, it.w, it.h) } }
        val all = placed.toMutableList()
        val left = ArrayList<String>()
        for (k in carry + rest) {
            val (w, h) = spanOf(k, prefs)
            val at = grid.firstFit(w, h)
            if (at == null) {
                left += k
            } else {
                grid.put(k, at.first, at.second, w, h)
                all += Placed(k, at.first, at.second, w, h)
            }
        }
        // Passt etwas auf keine leere Seite (zu hoch), steht es allein auf einer.
        if (all.isEmpty() && left.isNotEmpty()) {
            val k = left.removeAt(0)
            val (w, h) = spanOf(k, prefs)
            all += Placed(k, 0, 0, w, h)
        }
        out += encodePage(all)
        carry = left
        i++
    }
    return out
}

/**
 * Der Startbildschirm, wie er erscheint (phoneLayout im Web, ohne den
 * Ausschluss der Programme im Dock: das Dock der App ist die Leiste unten und
 * steht unabhängig vom Raster, Entwurf 02).
 *
 * [programs]: Schlüssel der Programme, die das Konto öffnen darf, in der
 * Reihenfolge des Werkszustands. [widgetKinds]: Widgets, die gezeichnet werden.
 * [cells]: Zellen einer Seite (vier Spalten). Programme, die die Anordnung
 * noch nicht kennt (ein neues Programm), kommen an die ersten freien Stellen
 * der letzten Seite; was weggefallen ist, lässt eine Lücke; jedes Programm
 * steht höchstens einmal da. Ab Werk fehlen [DEFAULT_OFF_GRID], selbst
 * angeordnet die mit × genommenen ([PhonePrefs.hidden]).
 */
fun phoneLayout(prefs: PhonePrefs, programs: List<String>, cells: Int, widgetKinds: Collection<String> = HOME_WIDGET_KINDS): PhoneShown {
    val known = programs.toSet()
    val seen = HashSet<String>()
    val folders = mutableListOf<PhoneFolder>()
    for (f in prefs.folders) {
        val apps = f.apps.filter { it in known && it !in seen }
        seen += apps
        if (apps.isNotEmpty()) folders += f.copy(apps = apps)
    }
    val folderKeys = folders.map { "folder:" + it.id }
    val widgetKeys = prefs.widgets.filter { it.kind in widgetKinds }.map { "widget:" + it.id }
    val usable = { k: String -> k !in seen && (k in known || k in folderKeys || k in widgetKeys) }
    val off = if (prefs.custom) prefs.hidden.toSet() else DEFAULT_OFF_GRID
    var pages: List<List<String?>> = if (prefs.custom) {
        // Nicht Erlaubtes lässt seine Zelle leer (die anderen bleiben, wo sie sind).
        prefs.pages.map { page -> page.map { k -> k?.takeIf { usable(it) }?.also { seen += it } }.trimEnd() }
    } else {
        (widgetKeys + programs.filter { it !in off } + folderKeys)
            .filter { k -> usable(k).also { if (it) seen += k } }
            .let { listOf(it) }
    }
    val rest = programs.filter { it !in seen && it !in off } + (folderKeys + widgetKeys).filter { it !in seen }
    seen += rest
    pages = pages.filter { p -> p.any { it != null } }
    if (rest.isNotEmpty()) {
        val last = pages.lastOrNull().orEmpty()
        // Neues kommt an die ersten freien Stellen der letzten Seite (der Übertrag auf die nächste).
        pages = pages.dropLast(if (pages.isEmpty()) 0 else 1) + listOf(if (null in last) appendFree(last, rest, prefs) else last + rest)
    }
    val rows = maxOf(1, cells / PHONE_COLUMNS)
    pages = fitPages(pages, prefs, rows).filter { p -> p.any { it != null } }
    if (pages.isEmpty()) pages = listOf(emptyList())
    return PhoneShown(pages, folders)
}

/** Ohne Lücken am Ende. */
fun List<String?>.trimEnd(): List<String?> = dropLastWhile { it == null }

/** Setzt [keys] an die ersten freien Stellen einer Seite mit Lücken (ohne Grenze nach unten). */
private fun appendFree(cells: List<String?>, keys: List<String>, prefs: PhonePrefs): List<String?> {
    val rows = rowsNeeded(cells, prefs) + keys.sumOf { spanOf(it, prefs).second }
    val (placed, _) = arrange(cells, prefs, rows)
    val grid = Occupancy(rows).also { g -> placed.forEach { g.put(it.key, it.row, it.col, it.w, it.h) } }
    val all = placed.toMutableList()
    for (k in keys) {
        val (w, h) = spanOf(k, prefs)
        val at = grid.firstFit(w, h) ?: continue
        grid.put(k, at.first, at.second, w, h)
        all += Placed(k, at.first, at.second, w, h)
    }
    return encodePage(all)
}

/** Programme, die nicht auf dem Startbildschirm stehen (für „Hinzufügen“), in der Reihenfolge von [programs]. */
fun programsOffHome(shown: PhoneShown, programs: List<String>): List<String> {
    val on = shown.pages.flatten().filterNotNull().toSet() + shown.folders.flatMap { it.apps }
    return programs.filter { it !in on }
}

/**
 * Die Anordnung, wie sie zu sehen ist, wird die eigene (bei der ersten
 * Änderung). Einträge, die gerade nicht zu sehen sind (ein Programm ohne
 * Recht, ein Widget nur fürs Web), behalten ihre Zelle, wenn sie frei ist,
 * sonst kommen sie ans Ende ihrer Seite; Programme, die nirgends stehen,
 * gelten als mit × genommen.
 */
fun ownLayout(prefs: PhonePrefs, shown: PhoneShown, programs: List<String>): PhonePrefs {
    val visible = shown.pages.flatten().filterNotNull().toSet()
    val pages = shown.pages.map { it.toMutableList() }.toMutableList()
    if (prefs.custom) {
        prefs.pages.forEachIndexed { i, raw ->
            val keep = raw.withIndex().filter { (_, k) -> k != null && k !in visible && !k.startsWith("folder:") && k !in programs && pages.none { p -> k in p } }
            if (keep.isEmpty()) return@forEachIndexed
            while (pages.size <= minOf(i, 11)) pages.add(mutableListOf())
            val page = pages[minOf(i, pages.lastIndex)]
            for ((j, k) in keep) {
                while (page.size <= j) page.add(null)
                if (page[j] == null) page[j] = k else page.add(k)
            }
        }
    }
    val inFolders = shown.folders.flatMap { it.apps }.toSet()
    val shownFolderIds = shown.folders.map { it.id }.toSet()
    // Ordner, deren Programme alle gerade nicht zu sehen sind, bleiben unverändert.
    val folders = shown.folders.map { f -> prefs.folders.firstOrNull { it.id == f.id }?.let { raw -> f.copy(apps = f.apps + raw.apps.filter { it !in f.apps && it !in programs }) } ?: f } +
        prefs.folders.filter { it.id !in shownFolderIds && it.apps.none { a -> a in programs } }
    val off = programs.filter { it !in visible && it !in inFolders }
    val hidden = ((if (prefs.custom) prefs.hidden.filter { it !in programs } else emptyList()) + off).distinct()
    return prefs.copy(custom = true, pages = pages.map { it.trimEnd() }, folders = folders, hidden = hidden)
}

// ---------- Ändern (wie phone-home.ts, mit freien Plätzen) ----------

private fun PhonePrefs.withPage(i: Int, cells: List<String?>): PhonePrefs {
    val pages = pages.toMutableList()
    while (pages.size <= i) pages.add(emptyList())
    pages[i] = cells.trimEnd()
    return copy(pages = pages)
}

/** Belegung einer Seite ohne Grenze nach unten (zum Ändern). */
private fun layoutOf(p: PhonePrefs, page: Int, extraRows: Int = 2): Triple<List<Placed>, Occupancy, Int> {
    val cells = p.pages.getOrNull(page).orEmpty()
    val rows = rowsNeeded(cells, p) + extraRows
    val (placed, _) = arrange(cells, p, rows)
    val grid = Occupancy(rows).also { g -> placed.forEach { g.put(it.key, it.row, it.col, it.w, it.h) } }
    return Triple(placed, grid, rows)
}

/** Nimmt einen Eintrag von seiner Stelle (Seite, Ordner); seine Zelle bleibt leer. Ein Ordner mit nur noch einem Programm löst sich auf. */
private fun takeOut(p: PhonePrefs, key: String): PhonePrefs {
    var next = p.copy(
        pages = p.pages.map { page -> page.map { if (it == key) null else it }.trimEnd() },
        folders = p.folders.map { f -> f.copy(apps = f.apps.filter { it != key }) },
    )
    for (f in next.folders) if (f.apps.size <= 1 && p.folders.any { it.id == f.id && key in it.apps }) next = dissolveFolder(next, f.id)
    return next
}

/** Seite und Zelle eines Eintrags (auch eines Ordners), sonst null. */
fun placeOf(p: PhonePrefs, key: String): Pair<Int, Int>? {
    p.pages.forEachIndexed { i, page -> page.indexOf(key).takeIf { it >= 0 }?.let { return i to it } }
    return null
}

/**
 * Ablegen (Ziehen, „Bewegen …“ mit Zeile und Spalte): [key] kommt auf Seite
 * [page] an Zelle [index]. Ist sie frei, bleibt alles andere, wo es ist. Ist
 * sie belegt, rückt das Symbol dort (und, wenn nötig, die folgenden) eine
 * Zelle weiter bis zur nächsten Lücke, wie bei Android; ein Widget schiebt,
 * was unter ihm läge, an die nächsten freien Stellen. Er gilt nicht mehr als
 * entfernt.
 */
fun placeAt(p: PhonePrefs, key: String, page: Int, index: Int): PhonePrefs {
    val next = takeOut(p, key)
    val (w, h) = spanOf(key, next)
    val (placed, _, rows) = layoutOf(next, page, extraRows = h + 2)
    val row = maxOf(0, index) / PHONE_COLUMNS
    val col = snapCol(maxOf(0, index) % PHONE_COLUMNS, w)
    val result = ArrayList<Placed>()
    val grid = Occupancy(rows + 4)
    if (w == 1 && h == 1) {
        // Ein Symbol: die Kette der Symbole ab der Zelle rückt bis zur nächsten Lücke weiter; Widgets bleiben stehen.
        val byIndex = HashMap<Int, Placed>()
        for (q in placed) {
            if (q.w > 1 || q.h > 1) {
                grid.put(q.key, q.row, q.col, q.w, q.h)
                result += q
            } else {
                byIndex[q.index] = q
            }
        }
        var at = row * PHONE_COLUMNS + col
        // Auf einem Widget abgelegt: an die nächste freie Zelle danach.
        while (grid.at(at) != null) at++
        var moving: String? = key
        while (moving != null) {
            while (grid.at(at) != null) at++
            val r = at / PHONE_COLUMNS
            val c = at % PHONE_COLUMNS
            val there = byIndex.remove(at)
            grid.put(moving, r, c, 1, 1)
            result += Placed(moving, r, c, 1, 1)
            moving = there?.key
            at++
        }
        for (q in byIndex.values) {
            grid.put(q.key, q.row, q.col, 1, 1)
            result += q
        }
    } else {
        grid.put(key, row, col, w, h)
        result += Placed(key, row, col, w, h)
        val pushed = ArrayList<Placed>()
        for (q in placed) {
            if (grid.free(q.row, q.col, q.w, q.h)) {
                grid.put(q.key, q.row, q.col, q.w, q.h)
                result += q
            } else {
                pushed += q
            }
        }
        for (q in pushed) {
            val to = grid.firstFit(q.w, q.h, row * PHONE_COLUMNS + col) ?: grid.firstFit(q.w, q.h) ?: continue
            grid.put(q.key, to.first, to.second, q.w, q.h)
            result += Placed(q.key, to.first, to.second, q.w, q.h)
        }
    }
    return next.withPage(page, encodePage(result)).copy(hidden = next.hidden - key)
}

/** Erste freie Zelle (für [key]) auf Seite [page]. */
fun firstFree(p: PhonePrefs, key: String, page: Int): Int {
    val (w, h) = spanOf(key, p)
    val (_, grid, _) = layoutOf(p, page, extraRows = h + 1)
    return grid.firstFit(w, h)?.let { it.first * PHONE_COLUMNS + it.second } ?: p.pages.getOrNull(page).orEmpty().size
}

/** Legt [key] auf Seite [page]: an Zelle [index], mit [Int.MAX_VALUE] an die erste freie. */
fun moveTo(p: PhonePrefs, key: String, page: Int, index: Int): PhonePrefs {
    if (index != Int.MAX_VALUE) return placeAt(p, key, page, index)
    val next = takeOut(p, key)
    return placeAt(next, key, page, firstFree(next, key, page))
}

/**
 * Eine Zelle vor ([delta] -1) oder zurück (+1), ein Widget um seine Breite;
 * steht dort etwas, tauschen beide. Am Anfang einer Seite auf die Seite davor
 * (ans Ende), hinter der letzten Zelle ([cells]) auf die nächste (der Weg
 * ohne Ziehen, Entwurf 03).
 */
fun moveBy(p: PhonePrefs, key: String, delta: Int, cells: Int = Int.MAX_VALUE): PhonePrefs {
    val (page, index) = placeOf(p, key) ?: return p
    val (w, _) = spanOf(key, p)
    val target = index + delta * w
    return when {
        target < 0 && page > 0 -> moveTo(p, key, page - 1, Int.MAX_VALUE)
        target < 0 -> p
        target >= cells -> moveTo(p, key, page + 1, Int.MAX_VALUE)
        else -> swapTo(p, key, page, target)
    }
}

/** [key] an Zelle [target]; ein Symbol dort kommt an die alte Stelle (Tauschen statt Schieben). */
private fun swapTo(p: PhonePrefs, key: String, page: Int, target: Int): PhonePrefs {
    val (from, index) = placeOf(p, key) ?: return p
    val cells = p.pages[page]
    val other = cells.getOrNull(target)
    if (other == null || from != page || spanOf(other, p) != 1 to 1 || spanOf(key, p) != 1 to 1) return placeAt(p, key, page, target)
    val list = cells.toMutableList()
    list[index] = other
    list[target] = key
    return p.withPage(page, list)
}

/** Legt [key] direkt an die Stelle von [other] (der rückt weiter); [after] true: dahinter. */
fun moveNextTo(p: PhonePrefs, key: String, other: String, after: Boolean = false): PhonePrefs {
    if (key == other) return p
    val (page, index) = placeOf(takeOut(p, key), other) ?: return p
    return placeAt(p, key, page, if (after) index + 1 else index)
}

/**
 * Ein Programm auf ein anderes gezogen ergibt an dessen Stelle einen Ordner;
 * auf einen Ordner gezogen kommt es hinein. Ordner und Widgets kommen in keinen Ordner.
 */
fun combine(p: PhonePrefs, dragged: String, target: String, folderName: String, newId: String): PhonePrefs {
    if (dragged == target || dragged.startsWith("folder:") || dragged.startsWith("widget:")) return p
    if (target.startsWith("widget:")) return p
    if (target.startsWith("folder:")) {
        val id = target.removePrefix("folder:")
        val next = takeOut(p, dragged)
        return next.copy(
            folders = next.folders.map { f -> if (f.id == id && dragged !in f.apps) f.copy(apps = f.apps + dragged) else f },
            hidden = next.hidden - dragged,
        )
    }
    if (placeOf(p, target) == null) return p
    val next = takeOut(p, dragged)
    val folder = PhoneFolder(newId, cleanName(folderName).ifEmpty { "Ordner" }, listOf(target, dragged))
    return next.copy(
        pages = next.pages.map { page -> page.map { if (it == target) "folder:$newId" else it } },
        folders = next.folders + folder,
        hidden = next.hidden - dragged,
    )
}

/** Ein Programm aus seinem Ordner kommt an die erste freie Zelle hinter dem Ordner. */
fun outOfFolder(p: PhonePrefs, key: String, folderId: String): PhonePrefs {
    val at = placeOf(p, "folder:$folderId")
    val page = at?.first ?: 0
    val next = takeOut(p, key)
    // Der Ordner kann sich aufgelöst haben: sein letztes Programm steht dann an seiner Stelle.
    val from = at?.second?.plus(1) ?: 0
    return placeFree(next, key, page, from).copy(hidden = next.hidden - key)
}

/** [key] an die erste freie Zelle ab [from] auf Seite [page] (nichts rückt). */
private fun placeFree(p: PhonePrefs, key: String, page: Int, from: Int): PhonePrefs {
    val (w, h) = spanOf(key, p)
    val (placed, grid, _) = layoutOf(p, page, extraRows = h + 1)
    val to = grid.firstFit(w, h, from) ?: grid.firstFit(w, h) ?: return p
    return p.withPage(page, encodePage(placed + Placed(key, to.first, to.second, w, h)))
}

/** Der Ordner geht; das erste Programm steht an seiner Stelle, die übrigen an den nächsten freien Zellen. */
fun dissolveFolder(p: PhonePrefs, folderId: String): PhonePrefs {
    val folder = p.folders.firstOrNull { it.id == folderId } ?: return p
    val key = "folder:$folderId"
    val at = placeOf(p, key)
    var next = p.copy(folders = p.folders.filter { it.id != folderId })
    if (folder.apps.isEmpty()) return next.copy(pages = next.pages.map { page -> page.map { if (it == key) null else it }.trimEnd() })
    if (at == null) {
        val last = maxOf(0, next.pages.lastIndex)
        for (a in folder.apps) next = placeFree(next, a, last, 0)
        return next
    }
    val (page, index) = at
    next = next.copy(pages = next.pages.mapIndexed { i, cells -> if (i == page) cells.map { if (it == key) folder.apps.first() else it } else cells })
    for (a in folder.apps.drop(1)) next = placeFree(next, a, page, index + 1)
    return next
}

fun renameFolder(p: PhonePrefs, folderId: String, to: String): PhonePrefs {
    val clean = cleanName(to)
    if (clean.isEmpty()) return p
    return p.copy(folders = p.folders.map { if (it.id == folderId) it.copy(name = clean) else it })
}

/** Ein neues Widget an die erste freie Stelle (4 × 2) von Seite [page]. */
fun addWidget(p: PhonePrefs, kind: String, page: Int, id: String): PhonePrefs {
    val next = p.copy(widgets = p.widgets + PhoneWidget(id, kind, "l"))
    return placeFree(next.withPage(page, next.pages.getOrNull(page).orEmpty()), "widget:$id", page, 0)
}

fun removeWidget(p: PhonePrefs, id: String): PhonePrefs = p.copy(
    pages = p.pages.map { page -> page.map { if (it == "widget:$id") null else it }.trimEnd() },
    widgets = p.widgets.filter { it.id != id },
)

/** Größe „s“ (halb) oder „l“ (ganz); was unter der neuen Fläche läge, rückt an die nächsten freien Stellen. */
fun resizeWidget(p: PhonePrefs, id: String, size: String): PhonePrefs {
    val next = p.copy(widgets = p.widgets.map { if (it.id == id) it.copy(size = if (size == "s") "s" else "l") else it })
    val (page, index) = placeOf(next, "widget:$id") ?: return next
    return placeAt(next, "widget:$id", page, index)
}

/** × an einem Programm: es verlässt den Startbildschirm und bleibt unter „Hinzufügen“. */
fun removeProgram(p: PhonePrefs, key: String): PhonePrefs {
    val next = takeOut(p, key)
    return next.copy(hidden = (next.hidden + key).distinct())
}

/** Ein Programm aus „Hinzufügen“ an die erste freie Zelle von Seite [page]. */
fun addProgram(p: PhonePrefs, key: String, page: Int): PhonePrefs =
    moveTo(p, key, page, Int.MAX_VALUE)

/** Leere Seiten nach dem Anordnen gehen (eine Seite bleibt immer). */
fun dropEmptyPages(p: PhonePrefs): PhonePrefs {
    val pages = p.pages.filter { page -> page.any { it != null } }.map { it.trimEnd() }
    return p.copy(pages = pages.ifEmpty { listOf(emptyList()) })
}

/** Eine neue, leere Seite am Ende (der „+“-Punkt im Bearbeiten). */
fun addPage(p: PhonePrefs): PhonePrefs = p.copy(pages = p.pages + listOf(emptyList()))

// ---------- Dock ----------

/**
 * Die Plätze im Dock, wie sie erscheinen: ab Werk [DOCK_DEFAULT], sonst die
 * eigenen; nur, was das Konto öffnen kann ([available]), höchstens 15,
 * „Start“ immer auf der ersten Seite des Docks (vorn, wenn es fehlte oder
 * weiter hinten stand).
 */
fun dockEntries(dock: PhoneDock, available: Collection<String>): List<String> {
    val list = (if (dock.custom) dock.apps else DOCK_DEFAULT).filter { it == DOCK_HOME || it in available }.distinct()
    val withHome = if (DOCK_HOME in list.take(DOCK_PAGE)) list else listOf(DOCK_HOME) + (list - DOCK_HOME)
    return withHome.take(DOCK_MAX)
}

/** Ein Platz an Stelle [slot] ins Dock (Ziehen von der Startseite); steht er schon drin, wandert er dorthin. Unverändert, wenn es voll ist. */
fun dockInsert(p: PhonePrefs, entries: List<String>, key: String, slot: Int): PhonePrefs {
    if (key !in entries && entries.size >= DOCK_MAX) return p
    val list = (entries - key).toMutableList()
    list.add(slot.coerceIn(0, list.size), key)
    return setDock(p, list)
}

/** Das Dock bekommt [apps] (Start bleibt, höchstens 15, keine doppelten). */
fun setDock(p: PhonePrefs, apps: List<String>): PhonePrefs {
    val clean = apps.filter { ID.matches(it) }.distinct()
    val withHome = if (DOCK_HOME in clean) clean else listOf(DOCK_HOME) + clean
    return p.copy(dock = p.dock.copy(apps = withHome.take(DOCK_MAX), custom = true))
}

/** Einen Platz ins Dock (am Ende); unverändert, wenn es voll ist. */
fun dockAdd(p: PhonePrefs, entries: List<String>, key: String): PhonePrefs =
    if (key in entries || entries.size >= DOCK_MAX) p else setDock(p, entries + key)

/** Einen Platz aus dem Dock; „Start“ bleibt immer. */
fun dockRemove(p: PhonePrefs, entries: List<String>, key: String): PhonePrefs =
    if (key == DOCK_HOME) p else setDock(p, entries - key)

/** Einen Platz um [delta] verschieben. */
fun dockMove(p: PhonePrefs, entries: List<String>, key: String, delta: Int): PhonePrefs {
    val i = entries.indexOf(key)
    val j = i + delta
    if (i < 0 || j !in entries.indices) return p
    val list = entries.toMutableList()
    list[i] = list[j].also { list[j] = list[i] }
    return setDock(p, list)
}

/** „Auf Werkseinstellung zurücksetzen“: alles wie beim ersten Start, nur „Einrichtung des Webs übernehmen“ aus. */
fun factoryPhone(): PhonePrefs = PhonePrefs()
