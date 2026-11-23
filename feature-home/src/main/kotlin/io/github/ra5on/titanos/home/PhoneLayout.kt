package io.github.ra5on.titanos.home

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * dabei. [always]: auch in Programmen und auf Unterseiten (ab Werk an).
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
    val pages: List<List<String>> = emptyList(),
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

/** Plätze im Dock der App. */
const val DOCK_MAX = 5

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
    val pages = v["pages"].arr().take(12).map { keys(it, 80) }
    val d = v["dock"].obj() ?: JsonObject(emptyMap())
    return PhonePrefs(
        custom = v["custom"].bool() == true,
        pages = pages,
        folders = folders,
        widgets = widgets,
        dock = PhoneDock(
            show = d["show"].bool() != false,
            apps = keys(d["apps"], DOCK_MAX),
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
    out["pages"] = JsonArray(pages.map(::jstr))
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
            "apps" to jstr(dock.apps),
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

data class PhoneShown(val pages: List<List<String>>, val folders: List<PhoneFolder>)

/** Zellen eines Eintrags auf seiner Seite (Programm und Ordner 1, Widget „s“ 4, „l“ 8; wie cellsOf im Web). */
fun cellsOf(key: String, prefs: PhonePrefs): Int {
    if (!key.startsWith("widget:")) return 1
    val w = prefs.widgets.firstOrNull { "widget:" + it.id == key }
    return if (w?.size == "s") 4 else 8
}

private fun paginate(list: List<String>, prefs: PhonePrefs, cells: Int): List<List<String>> {
    val pages = mutableListOf<List<String>>()
    var page = mutableListOf<String>()
    var used = 0
    for (key in list) {
        val c = cellsOf(key, prefs)
        if (page.isNotEmpty() && used + c > cells) {
            pages += page
            page = mutableListOf()
            used = 0
        }
        page += key
        used += c
    }
    if (page.isNotEmpty()) pages += page
    return pages
}

/** Eine Seite mit mehr, als passt (kleineres Fenster, größere Schrift, ein Widget dazu), gibt den Rest an die nächste ab; nichts fällt weg. */
private fun spill(pages: List<List<String>>, prefs: PhonePrefs, cells: Int): List<List<String>> {
    val out = mutableListOf<List<String>>()
    var carry = emptyList<String>()
    for (page in pages) {
        val fitted = paginate(carry + page, prefs, cells)
        out += fitted.firstOrNull() ?: emptyList()
        carry = fitted.drop(1).flatten()
    }
    if (carry.isNotEmpty()) out += paginate(carry, prefs, cells)
    return out.filter { it.isNotEmpty() }
}

/**
 * Der Startbildschirm, wie er erscheint (phoneLayout im Web, ohne den
 * Ausschluss der Programme im Dock: das Dock der App ist die Leiste unten und
 * steht unabhängig vom Raster, Entwurf 02).
 *
 * [programs]: Schlüssel der Programme, die das Konto öffnen darf, in der
 * Reihenfolge des Werkszustands. [widgetKinds]: Widgets, die gezeichnet werden.
 * [cells]: Zellen einer Seite. Programme, die die Anordnung noch nicht kennt
 * (ein neues Programm), kommen ans Ende; was weggefallen ist, fehlt; jedes
 * Programm steht höchstens einmal da. Ab Werk fehlen [DEFAULT_OFF_GRID], selbst
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
    var pages: List<List<String>> = if (prefs.custom) {
        prefs.pages.map { page -> page.filter { k -> usable(k).also { if (it) seen += k } } }
    } else {
        (widgetKeys + programs.filter { it !in off } + folderKeys)
            .filter { k -> usable(k).also { if (it) seen += k } }
            .let { listOf(it) }
    }
    val rest = programs.filter { it !in seen && it !in off } + (folderKeys + widgetKeys).filter { it !in seen }
    seen += rest
    pages = pages.filter { it.isNotEmpty() }
    if (rest.isNotEmpty()) {
        val last = pages.lastOrNull().orEmpty()
        pages = pages.dropLast(if (pages.isEmpty()) 0 else 1) + paginate(last + rest, prefs, cells)
    }
    pages = spill(pages, prefs, maxOf(1, cells))
    if (pages.isEmpty()) pages = listOf(emptyList())
    return PhoneShown(pages, folders)
}

/** Programme, die nicht auf dem Startbildschirm stehen (für „Hinzufügen“), in der Reihenfolge von [programs]. */
fun programsOffHome(shown: PhoneShown, programs: List<String>): List<String> {
    val on = shown.pages.flatten().toSet() + shown.folders.flatMap { it.apps }
    return programs.filter { it !in on }
}

/**
 * Die Anordnung, wie sie zu sehen ist, wird die eigene (bei der ersten
 * Änderung). Einträge, die gerade nicht zu sehen sind (ein Programm ohne
 * Recht, ein Widget nur fürs Web), bleiben am Ende ihrer Seite stehen;
 * Programme, die nirgends stehen, gelten als mit × genommen.
 */
fun ownLayout(prefs: PhonePrefs, shown: PhoneShown, programs: List<String>): PhonePrefs {
    val visible = shown.pages.flatten().toSet()
    val pages = shown.pages.map { it.toMutableList() }.toMutableList()
    if (prefs.custom) {
        prefs.pages.forEachIndexed { i, raw ->
            val keep = raw.filter { it !in visible && !it.startsWith("folder:") && it !in programs && pages.none { p -> it in p } }
            if (keep.isEmpty()) return@forEachIndexed
            while (pages.size <= minOf(i, 11)) pages.add(mutableListOf())
            pages[minOf(i, pages.lastIndex)] += keep
        }
    }
    val inFolders = shown.folders.flatMap { it.apps }.toSet()
    val shownFolderIds = shown.folders.map { it.id }.toSet()
    // Ordner, deren Programme alle gerade nicht zu sehen sind, bleiben unverändert.
    val folders = shown.folders.map { f -> prefs.folders.firstOrNull { it.id == f.id }?.let { raw -> f.copy(apps = f.apps + raw.apps.filter { it !in f.apps && it !in programs }) } ?: f } +
        prefs.folders.filter { it.id !in shownFolderIds && it.apps.none { a -> a in programs } }
    val off = programs.filter { it !in visible && it !in inFolders }
    val hidden = ((if (prefs.custom) prefs.hidden.filter { it !in programs } else emptyList()) + off).distinct()
    return prefs.copy(custom = true, pages = pages, folders = folders, hidden = hidden)
}

// ---------- Ändern (wie phone-home.ts) ----------

/** Nimmt einen Eintrag von seiner Stelle (Seite, Ordner); ein Ordner mit nur noch einem Programm löst sich auf. */
private fun takeOut(p: PhonePrefs, key: String): PhonePrefs {
    var next = p.copy(
        pages = p.pages.map { page -> page.filter { it != key } },
        folders = p.folders.map { f -> f.copy(apps = f.apps.filter { it != key }) },
    )
    for (f in next.folders) if (f.apps.size <= 1 && p.folders.any { it.id == f.id && key in it.apps }) next = dissolveFolder(next, f.id)
    return next
}

/** Legt [key] auf Seite [page] an [index] (ans Ende, wenn der Index dahinter liegt); er gilt nicht mehr als entfernt. */
fun moveTo(p: PhonePrefs, key: String, page: Int, index: Int): PhonePrefs {
    val next = takeOut(p, key)
    val pages = next.pages.map { it.toMutableList() }.toMutableList()
    while (pages.size <= page) pages.add(mutableListOf())
    val target = pages[page]
    target.add(index.coerceIn(0, target.size), key)
    return next.copy(pages = pages, hidden = next.hidden - key)
}

/** Legt [key] direkt vor [before] (Ziehen und Ablegen); [after] true: direkt dahinter. */
fun moveNextTo(p: PhonePrefs, key: String, other: String, after: Boolean = false): PhonePrefs {
    if (key == other) return p
    val next = takeOut(p, key)
    val (page, index) = placeOf(next, other) ?: return p
    return moveTo(next, key, page, if (after) index + 1 else index)
}

/** Seite und Stelle eines Eintrags (auch eines Ordners), sonst null. */
fun placeOf(p: PhonePrefs, key: String): Pair<Int, Int>? {
    p.pages.forEachIndexed { i, page -> page.indexOf(key).takeIf { it >= 0 }?.let { return i to it } }
    return null
}

/**
 * Eine Stelle vor ([delta] -1) oder zurück (+1); am Anfang bzw. Ende einer
 * Seite auf die Seite davor bzw. danach (der Weg ohne Ziehen, Entwurf 03).
 */
fun moveBy(p: PhonePrefs, key: String, delta: Int): PhonePrefs {
    val (page, index) = placeOf(p, key) ?: return p
    val size = p.pages[page].size
    return when {
        delta < 0 && index > 0 -> moveTo(p, key, page, index - 1)
        delta < 0 && page > 0 -> moveTo(p, key, page - 1, p.pages[page - 1].size)
        delta > 0 && index < size - 1 -> moveTo(p, key, page, index + 1)
        delta > 0 -> moveTo(p, key, page + 1, 0)
        else -> p
    }
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

/** Ein Programm aus seinem Ordner kommt direkt hinter den Ordner. */
fun outOfFolder(p: PhonePrefs, key: String, folderId: String): PhonePrefs {
    val at = placeOf(p, "folder:$folderId")
    val page = at?.first ?: 0
    val index = at?.second?.plus(1) ?: 0
    val next = takeOut(p, key)
    // Der Ordner kann sich aufgelöst haben: sein letztes Programm steht dann an seiner Stelle.
    val pages = next.pages.map { it.toMutableList() }.toMutableList()
    while (pages.size <= page) pages.add(mutableListOf())
    val after = pages[page].indexOf("folder:$folderId")
    pages[page].add(if (after >= 0) after + 1 else minOf(index, pages[page].size), key)
    return next.copy(pages = pages)
}

/** Der Ordner geht; seine Programme stehen an seiner Stelle. */
fun dissolveFolder(p: PhonePrefs, folderId: String): PhonePrefs {
    val folder = p.folders.firstOrNull { it.id == folderId } ?: return p
    val key = "folder:$folderId"
    var placed = false
    val pages = p.pages.map { page ->
        page.flatMap { k ->
            if (k != key) {
                listOf(k)
            } else {
                placed = true
                folder.apps
            }
        }
    }.toMutableList()
    if (!placed && folder.apps.isNotEmpty()) {
        if (pages.isEmpty()) pages += emptyList<String>()
        pages[pages.lastIndex] = pages.last() + folder.apps
    }
    return p.copy(pages = pages, folders = p.folders.filter { it.id != folderId })
}

fun renameFolder(p: PhonePrefs, folderId: String, to: String): PhonePrefs {
    val clean = cleanName(to)
    if (clean.isEmpty()) return p
    return p.copy(folders = p.folders.map { if (it.id == folderId) it.copy(name = clean) else it })
}

/** Ein neues Widget am Anfang von Seite [page]. */
fun addWidget(p: PhonePrefs, kind: String, page: Int, id: String): PhonePrefs {
    val pages = p.pages.map { it.toMutableList() }.toMutableList()
    while (pages.size <= page) pages.add(mutableListOf())
    pages[page].add(0, "widget:$id")
    return p.copy(pages = pages, widgets = p.widgets + PhoneWidget(id, kind, "l"))
}

fun removeWidget(p: PhonePrefs, id: String): PhonePrefs = p.copy(
    pages = p.pages.map { page -> page.filter { it != "widget:$id" } },
    widgets = p.widgets.filter { it.id != id },
)

/** Größe „s“ (halb) oder „l“ (ganz). */
fun resizeWidget(p: PhonePrefs, id: String, size: String): PhonePrefs =
    p.copy(widgets = p.widgets.map { if (it.id == id) it.copy(size = if (size == "s") "s" else "l") else it })

/** × an einem Programm: es verlässt den Startbildschirm und bleibt unter „Hinzufügen“. */
fun removeProgram(p: PhonePrefs, key: String): PhonePrefs {
    val next = takeOut(p, key)
    return next.copy(hidden = (next.hidden + key).distinct())
}

/** Ein Programm aus „Hinzufügen“ ans Ende von Seite [page]. */
fun addProgram(p: PhonePrefs, key: String, page: Int): PhonePrefs =
    moveTo(p, key, page, Int.MAX_VALUE)

/** Leere Seiten nach dem Anordnen gehen (eine Seite bleibt immer). */
fun dropEmptyPages(p: PhonePrefs): PhonePrefs {
    val pages = p.pages.filter { it.isNotEmpty() }
    return p.copy(pages = pages.ifEmpty { listOf(emptyList()) })
}

/** Eine neue, leere Seite am Ende (der „+“-Punkt im Bearbeiten). */
fun addPage(p: PhonePrefs): PhonePrefs = p.copy(pages = p.pages + listOf(emptyList()))

// ---------- Dock ----------

/**
 * Die Plätze im Dock, wie sie erscheinen: ab Werk [DOCK_DEFAULT], sonst die
 * eigenen; nur, was das Konto öffnen kann ([available]), höchstens fünf,
 * „Start“ immer (vorn, wenn es fehlte).
 */
fun dockEntries(dock: PhoneDock, available: Collection<String>): List<String> {
    val list = (if (dock.custom) dock.apps else DOCK_DEFAULT).filter { it == DOCK_HOME || it in available }.distinct()
    val withHome = if (DOCK_HOME in list) list else listOf(DOCK_HOME) + list
    return withHome.take(DOCK_MAX)
}

/** Das Dock bekommt [apps] (Start bleibt, höchstens fünf, keine doppelten). */
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
