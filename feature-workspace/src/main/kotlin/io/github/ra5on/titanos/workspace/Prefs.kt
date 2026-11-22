package io.github.ra5on.titanos.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Was der Arbeitsplatz je Konto am NAS hält (Einstellung „desktop“,
 * web/src/lib/desktop/prefs.ts). Gelesen wird wie sanitizeDesktop im Web:
 * Unbekanntes oder Kaputtes fällt auf den Werkszustand zurück. Geschrieben
 * werden nur die Felder, die die App geändert hat (siehe DesktopStore).
 * Offene Fenster bleiben auf dem Gerät (WindowStore), wie im Web im Browser.
 */

/** Die Widget-Arten wie WIDGET_KINDS im Web. */
val WIDGET_KINDS = listOf(
    "status", "cpuram", "network", "volumes", "docker", "vms", "backups", "temperatures",
    "clock", "homeassistant", "tasks", "agent", "igpu", "npu", "gpu", "camera",
)
val GPU_WIDGET_KINDS = setOf("igpu", "npu", "gpu")
val GPU_VALUES = listOf("usage", "history", "temperature", "memory", "power", "clock")

/** Ein Widget auf dem Arbeitsplatz: Lage und Größe in Zellen von 20 px; x < 0 zählt von rechts. */
data class Widget(
    val id: String,
    val kind: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val hide: List<String> = emptyList(),
    val device: String = "",
    val camera: String = "",
)

data class Folder(val id: String, val name: String, val apps: List<String>)

data class Wallpaper(val custom: Boolean, val id: String, val version: Long, val live: Boolean)

data class Cell(val c: Int, val r: Int)

data class LayoutWindow(val app: String, val href: String, val x: Int, val y: Int, val w: Int, val h: Int)
data class SavedLayout(val id: String, val name: String, val windows: List<LayoutWindow>)

data class Dock(
    val show: Boolean = true,
    val pinned: List<String> = emptyList(),
    val customPinned: Boolean = false,
    val size: Int = 52,
    val opacity: Int = 90,
    val magnify: Int = 1,
    val position: String = "bottom",
    val border: Boolean = true,
)

val DOCK_DEFAULT_PINNED = listOf("app:files", "app:docker", "app:vms", "app:agent", "app:monitor", "app:settings")

val WALLPAPERS = listOf("wave", "aurora", "dunes", "peaks", "grid", "calm", "nordlicht", "bergsee", "kueste", "nebelwald", "wueste", "weltraum")

fun defaultWidgets() = listOf(
    Widget("w-status", "status", -17, 0, 17, 9),
    Widget("w-cpuram", "cpuram", -17, 9, 17, 6),
    Widget("w-volumes", "volumes", -17, 15, 17, 9),
    Widget("w-docker", "docker", -17, 24, 17, 12),
)

data class DesktopPrefs(
    val icons: List<String> = emptyList(),
    val customIcons: Boolean = false,
    val folders: List<Folder> = emptyList(),
    val widgets: List<Widget> = defaultWidgets(),
    val customWidgets: Boolean = false,
    val sort: String = "kind",
    val iconSize: String = "m",
    val wallpaper: Wallpaper = Wallpaper(false, "wave", 0, true),
    val folded: List<String> = emptyList(),
    val layouts: List<SavedLayout> = emptyList(),
    val recent: List<String> = emptyList(),
    val iconPos: Map<String, Cell> = emptyMap(),
    val widgetsHidden: Boolean = false,
    val dock: Dock = Dock(),
    val barSize: String = "m",
    val barLoad: Boolean = true,
    val menuOrder: List<String> = emptyList(),
)

private val ID = Regex("^[a-z0-9][a-z0-9:._-]{0,95}$", RegexOption.IGNORE_CASE)
private val SORTS = listOf("free", "name", "kind", "recent")
private val SIZES = listOf("s", "m", "l")

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

private fun int(v: JsonElement?, lo: Int, hi: Int, fallback: Int): Int =
    v.num()?.let { min(hi.toDouble(), max(lo.toDouble(), Math.round(it).toDouble())).toInt() } ?: fallback

private fun long(v: JsonElement?, lo: Long, hi: Long, fallback: Long): Long =
    v.num()?.let { min(hi.toDouble(), max(lo.toDouble(), it)).roundToLong() } ?: fallback

private fun strings(v: JsonElement?, limit: Int = 200): List<String> =
    v.arr().mapNotNull { it.str()?.takeIf(ID::matches) }.distinct().take(limit)

private fun text(v: JsonElement?, limit: Int = 60): String =
    v.str()?.replace(Regex("[\\x00-\\x1f\\x7f]"), "")?.trim()?.take(limit) ?: ""

/** Liest, was das NAS schickte, wie sanitizeDesktop im Web. */
fun sanitizeDesktop(input: JsonElement?): DesktopPrefs {
    val base = DesktopPrefs()
    val v = input.obj() ?: return base
    val widgets = if (v["widgets"] is JsonArray) {
        v["widgets"].arr().mapNotNull { it.obj() }
            .filter { it["kind"].str() in WIDGET_KINDS && it["id"].str()?.let(ID::matches) == true }
            .map { w ->
                val kind = w["kind"].str()!!
                Widget(
                    id = w["id"].str()!!,
                    kind = kind,
                    x = int(w["x"], -200, 200, 0),
                    y = int(w["y"], 0, 200, 0),
                    w = int(w["w"], 6, 60, 12),
                    h = int(w["h"], 3, 60, 6),
                    hide = if (kind in GPU_WIDGET_KINDS) GPU_VALUES.filter { value -> w["hide"].arr().any { it.str() == value } } else emptyList(),
                    device = if (kind in GPU_WIDGET_KINDS) text(w["device"], 40).takeIf { Regex("^[0-9a-f:.]+$", RegexOption.IGNORE_CASE).matches(it) }.orEmpty() else "",
                    camera = if (kind == "camera") w["camera"].str()?.takeIf { Regex("^[a-z][a-z0-9_]{0,31}$").matches(it) }.orEmpty() else "",
                )
            }.take(40)
    } else base.widgets
    val folders = v["folders"].arr().mapNotNull { it.obj() }
        .filter { it["id"].str()?.let(ID::matches) == true }
        .map { Folder(it["id"].str()!!, text(it["name"]).ifEmpty { "Ordner" }, strings(it["apps"], 40)) }
        .take(40)
    val wp = v["wallpaper"].obj() ?: JsonObject(emptyMap())
    val live = wp["live"].bool() != false
    val wallpaper = if (wp["source"].str() == "custom") {
        Wallpaper(true, "", long(wp["version"], 0, 1_000_000_000_000, 0), live)
    } else {
        Wallpaper(false, wp["id"].str()?.takeIf { it in WALLPAPERS } ?: "wave", 0, live)
    }
    val layouts = v["layouts"].arr().mapNotNull { it.obj() }
        .filter { it["id"].str()?.let(ID::matches) == true }
        .map { l ->
            SavedLayout(
                l["id"].str()!!,
                text(l["name"]).ifEmpty { "Anordnung" },
                l["windows"].arr().mapNotNull { it.obj() }
                    .filter { w ->
                        val href = w["href"].str()
                        w["app"].str()?.let(ID::matches) == true && href != null && href.startsWith("/") && !href.startsWith("//")
                    }
                    .map { w ->
                        LayoutWindow(
                            w["app"].str()!!, w["href"].str()!!.take(500),
                            int(w["x"], -5000, 10000, 40), int(w["y"], 0, 10000, 40),
                            int(w["w"], 320, 10000, 900), int(w["h"], 220, 10000, 640),
                        )
                    }.take(20),
            )
        }.take(10)
    return DesktopPrefs(
        icons = strings(v["icons"]),
        customIcons = v["customIcons"].bool() == true,
        folders = folders,
        widgets = widgets,
        customWidgets = v["customWidgets"].bool() == true,
        sort = v["sort"].str()?.takeIf { it in SORTS } ?: base.sort,
        iconSize = v["iconSize"].str()?.takeIf { it in SIZES } ?: base.iconSize,
        wallpaper = wallpaper,
        folded = strings(v["folded"], 60),
        layouts = layouts,
        recent = strings(v["recent"], 12),
        iconPos = positions(v["iconPos"]),
        widgetsHidden = v["widgetsHidden"].bool() == true,
        dock = sanitizeDock(v["dock"]),
        barSize = v["barSize"].str()?.takeIf { it in SIZES } ?: "m",
        barLoad = v["barLoad"].bool() != false,
        menuOrder = strings(v["menuOrder"], 300),
    )
}

private fun positions(v: JsonElement?): Map<String, Cell> {
    val o = v.obj() ?: return emptyMap()
    val out = LinkedHashMap<String, Cell>()
    for ((key, at) in o.entries.take(300)) {
        val p = at.obj() ?: continue
        if (!ID.matches(key)) continue
        out[key] = Cell(int(p["c"], 0, 200, 0), int(p["r"], 0, 200, 0))
    }
    return out
}

fun sanitizeDock(v: JsonElement?): Dock {
    val base = Dock()
    val d = v.obj() ?: return base
    return Dock(
        show = d["show"].bool() != false,
        pinned = strings(d["pinned"], 40),
        customPinned = d["customPinned"].bool() == true,
        size = int(d["size"], 36, 80, base.size),
        opacity = int(d["opacity"], 30, 100, base.opacity),
        magnify = int(d["magnify"], 0, 2, base.magnify),
        position = d["position"].str()?.takeIf { it in listOf("bottom", "left", "right") } ?: base.position,
        border = d["border"].bool() != false,
    )
}

// ---- Zurück ins JSON des Webs (nur die Felder, die sich geändert haben) ----

private fun jstr(list: List<String>) = JsonArray(list.map(::JsonPrimitive))

fun Widget.toJson(): JsonObject = JsonObject(
    buildMap {
        put("id", JsonPrimitive(id)); put("kind", JsonPrimitive(kind))
        put("x", JsonPrimitive(x)); put("y", JsonPrimitive(y)); put("w", JsonPrimitive(w)); put("h", JsonPrimitive(h))
        if (hide.isNotEmpty()) put("hide", jstr(hide))
        if (device.isNotEmpty()) put("device", JsonPrimitive(device))
        if (camera.isNotEmpty()) put("camera", JsonPrimitive(camera))
    },
)

fun Dock.toJson(): JsonObject = JsonObject(
    mapOf(
        "show" to JsonPrimitive(show), "pinned" to jstr(pinned), "customPinned" to JsonPrimitive(customPinned),
        "size" to JsonPrimitive(size), "opacity" to JsonPrimitive(opacity), "magnify" to JsonPrimitive(magnify),
        "position" to JsonPrimitive(position), "border" to JsonPrimitive(border),
    ),
)

fun Wallpaper.toJson(): JsonObject = if (custom) {
    JsonObject(mapOf("source" to JsonPrimitive("custom"), "version" to JsonPrimitive(version), "live" to JsonPrimitive(live)))
} else {
    JsonObject(mapOf("source" to JsonPrimitive("builtin"), "id" to JsonPrimitive(id), "live" to JsonPrimitive(live)))
}

/** Ein Feld der Einstellung als JSON, wie das Web es schreibt. */
fun DesktopPrefs.field(name: String): JsonElement = when (name) {
    "icons" -> jstr(icons)
    "customIcons" -> JsonPrimitive(customIcons)
    "folders" -> JsonArray(folders.map { JsonObject(mapOf("id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name), "apps" to jstr(it.apps))) })
    "widgets" -> JsonArray(widgets.map { it.toJson() })
    "customWidgets" -> JsonPrimitive(customWidgets)
    "sort" -> JsonPrimitive(sort)
    "iconSize" -> JsonPrimitive(iconSize)
    "wallpaper" -> wallpaper.toJson()
    "folded" -> jstr(folded)
    "layouts" -> JsonArray(
        layouts.map { l ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(l.id), "name" to JsonPrimitive(l.name),
                    "windows" to JsonArray(
                        l.windows.map { w ->
                            JsonObject(
                                mapOf(
                                    "app" to JsonPrimitive(w.app), "href" to JsonPrimitive(w.href),
                                    "x" to JsonPrimitive(w.x), "y" to JsonPrimitive(w.y), "w" to JsonPrimitive(w.w), "h" to JsonPrimitive(w.h),
                                ),
                            )
                        },
                    ),
                ),
            )
        },
    )
    "recent" -> jstr(recent)
    "iconPos" -> JsonObject(iconPos.mapValues { (_, at) -> JsonObject(mapOf("c" to JsonPrimitive(at.c), "r" to JsonPrimitive(at.r))) })
    "widgetsHidden" -> JsonPrimitive(widgetsHidden)
    "dock" -> dock.toJson()
    "barSize" -> JsonPrimitive(barSize)
    "barLoad" -> JsonPrimitive(barLoad)
    "menuOrder" -> jstr(menuOrder)
    else -> JsonNull
}

/** Alle Felder, die die App schreiben kann. */
val DESKTOP_FIELDS = listOf(
    "icons", "customIcons", "folders", "widgets", "customWidgets", "sort", "iconSize", "wallpaper", "folded",
    "layouts", "recent", "iconPos", "widgetsHidden", "dock", "barSize", "barLoad", "menuOrder",
)

/** Was geschickt wird: der Stand am NAS, darin nur [changed] aus [prefs] ersetzt (der Rest bleibt, wie er ist). */
fun mergeDesktop(stored: JsonElement?, prefs: DesktopPrefs, changed: Set<String>): JsonObject {
    val base = (stored as? JsonObject)?.toMutableMap() ?: mutableMapOf<String, JsonElement>("version" to JsonPrimitive(1))
    if ("version" !in base) base["version"] = JsonPrimitive(1)
    for (name in changed) if (name in DESKTOP_FIELDS) base[name] = prefs.field(name)
    return JsonObject(base)
}

/** Alles als ein Objekt (eigene Einrichtung für Android). */
fun DesktopPrefs.toJson(): JsonObject = mergeDesktop(null, this, DESKTOP_FIELDS.toSet())
