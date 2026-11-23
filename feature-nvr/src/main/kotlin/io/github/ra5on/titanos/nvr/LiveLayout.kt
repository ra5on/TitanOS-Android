package io.github.ra5on.titanos.nvr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Die eigene Anordnung der Live-Kacheln wie web/src/lib/nvr/layout.ts: ein
// Raster mit fester Spaltenzahl, das sich die Breite teilt; reine Funktionen.

const val LIVE_COLS = 24
const val TILE_MIN_W = 4
const val TILE_MIN_H = 3
private const val MAX_ROWS = 400
private const val MAX_TILES = 64
private val CAMERA_ID = Regex("^[a-z][a-z0-9_]{0,31}$")

data class Tile(val id: String, val x: Int, val y: Int, val w: Int, val h: Int)

data class NvrPrefs(
    val tiles: List<Tile> = emptyList(),
    val custom: Boolean = false,
    val phoneOrder: List<String> = emptyList(),
    val events: Boolean = false,
    val names: Boolean = true,
    val marks: Boolean = true,
    val recDot: Boolean = true,
    val hideBar: Boolean = true,
)

private fun int(value: JsonElement?, lo: Int, hi: Int, fallback: Int): Int {
    val d = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return fallback
    if (!d.isFinite()) return fallback
    return min(hi, max(lo, d.roundToInt()))
}

fun clampTile(t: Tile): Tile {
    val w = min(LIVE_COLS, max(TILE_MIN_W, t.w))
    val h = min(60, max(TILE_MIN_H, t.h))
    return Tile(t.id, min(LIVE_COLS - w, max(0, t.x)), min(MAX_ROWS, max(0, t.y)), w, h)
}

/** Liest, was das NAS gespeichert hat; Unbekanntes fällt auf den Standard zurück. */
fun sanitizeNvrPrefs(input: JsonElement?): NvrPrefs {
    val v = input as? JsonObject ?: return NvrPrefs()
    val seen = HashSet<String>()
    val tiles = (v["tiles"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
        if (!CAMERA_ID.matches(id) || !seen.add(id)) return@mapNotNull null
        val w = int(o["w"], TILE_MIN_W, LIVE_COLS, 12)
        val h = int(o["h"], TILE_MIN_H, 60, 7)
        Tile(id, int(o["x"], 0, LIVE_COLS - w, 0), int(o["y"], 0, MAX_ROWS, 0), w, h)
    }.take(MAX_TILES)
    val order = (v["phoneOrder"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        .filter { CAMERA_ID.matches(it) }.distinct().take(MAX_TILES)
    fun flag(key: String) = (v[key] as? JsonPrimitive)?.booleanOrNull
    return NvrPrefs(
        tiles = tiles,
        custom = flag("custom") == true && tiles.isNotEmpty(),
        phoneOrder = order,
        events = flag("events") == true,
        names = flag("names") != false,
        marks = flag("marks") != false,
        recDot = flag("recDot") != false,
        hideBar = flag("hideBar") != false,
    )
}

fun NvrPrefs.toJson(): JsonObject = buildJsonObject {
    putJsonArray("tiles") {
        for (t in tiles) addJsonObject { put("id", t.id); put("x", t.x); put("y", t.y); put("w", t.w); put("h", t.h) }
    }
    put("custom", custom)
    putJsonArray("phoneOrder") { phoneOrder.forEach { add(it) } }
    put("events", events)
    put("names", names)
    put("marks", marks)
    put("recDot", recDot)
    put("hideBar", hideBar)
}

/** Das Standardraster: gleichmäßig, eine Kamera allein füllt die Breite. */
fun gridTiles(ids: List<String>): List<Tile> {
    val cols = if (ids.size <= 1) 1 else if (ids.size <= 4) 2 else 3
    val w = LIVE_COLS / cols
    val h = (w * 9.0 / 16).roundToInt()
    return ids.mapIndexed { i, id -> Tile(id, (i % cols) * w, (i / cols) * h, w, h) }
}

private fun overlaps(a: Tile, b: Tile) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

fun freePlace(tiles: List<Tile>, w: Int, h: Int): Pair<Int, Int> {
    for (y in 0 until MAX_ROWS) for (x in 0..LIVE_COLS - w) if (tiles.none { overlaps(it, Tile("", x, y, w, h)) }) return x to y
    return 0 to MAX_ROWS
}

/** Die Kacheln für diese Kameras: eigene Anordnung ohne Verschwundene, Neue an freie Plätze. */
fun tilesFor(prefs: NvrPrefs, ids: List<String>): List<Tile> {
    if (!prefs.custom) return gridTiles(ids)
    val known = ids.toSet()
    val tiles = prefs.tiles.filter { it.id in known }.map(::clampTile).toMutableList()
    for (id in ids) {
        if (tiles.any { it.id == id }) continue
        val (x, y) = freePlace(tiles, 8, 5)
        tiles += Tile(id, x, y, 8, 5)
    }
    return tiles
}

/** Die Kameras in der Reihenfolge des Handy-Stapels; neue folgen am Ende. */
fun phoneOrderFor(prefs: NvrPrefs, ids: List<String>): List<String> {
    val known = ids.toSet()
    val first = prefs.phoneOrder.filter { it in known }
    return first + ids.filter { it !in first }
}

/** Verschiebt oder vergrößert eine Kachel um ganze Zellen. */
fun dragTile(start: Tile, size: Boolean, dx: Int, dy: Int): Tile =
    if (!size) clampTile(start.copy(x = start.x + dx, y = max(0, start.y + dy)))
    else clampTile(start.copy(w = max(TILE_MIN_W, min(LIVE_COLS - start.x, start.w + dx)), h = max(TILE_MIN_H, start.h + dy)))

/** Kacheln liegen nie übereinander: die bewegte bleibt, was sie verdeckt, rutscht nach unten. */
fun settle(tiles: List<Tile>, fixedId: String): List<Tile> {
    val fixed = tiles.firstOrNull { it.id == fixedId } ?: return tiles
    val placed = mutableListOf(fixed)
    for (tile in tiles.filter { it.id != fixedId }.sortedWith(compareBy({ it.y }, { it.x }))) {
        var next = tile
        while (true) {
            val hit = placed.firstOrNull { overlaps(it, next) } ?: break
            next = next.copy(y = hit.y + hit.h)
        }
        placed += next
    }
    return tiles.map { t -> placed.first { it.id == t.id } }
}

fun layoutRows(tiles: List<Tile>): Int = tiles.maxOfOrNull { it.y + it.h } ?: 0
