package io.github.ra5on.titanos.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/*
 * Die Fenster des Arbeitsplatzes (web/src/lib/desktop/wm.svelte.ts). Maße
 * in dp der Arbeitsfläche. Offene Fenster bleiben auf dem Gerät: sie ändern
 * sich ständig und gehören nicht auf den Stick.
 */

enum class Snap(val id: String) {
    Left("left"), Right("right"), Max("max"), TopLeft("tl"), TopRight("tr"), BottomLeft("bl"), BottomRight("br");

    companion object {
        fun of(id: String?): Snap? = entries.firstOrNull { it.id == id }
    }
}

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

data class Win(
    val id: String,
    /** „app:<id>“ für ein Programm von TitanOS, „docker:<name>“ für eine Docker-App. */
    val app: String,
    /** Die Stelle im Programm (`/mail?…`), leer: sein Anfang. */
    val href: String,
    val title: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val z: Int,
    val minimized: Boolean = false,
    val snap: Snap? = null,
) {
    val rect get() = Rect(x, y, w, h)
}

class Wm(private val clock: () -> Long = System::currentTimeMillis) {
    var windows by mutableStateOf(listOf<Win>())
        private set
    var focused by mutableStateOf<String?>(null)
        private set

    /** Größe der Arbeitsfläche (unter der oberen Leiste). */
    var area by mutableStateOf(Rect(0, 0, 1280, 800))

    /** Platz des Docks an den Rändern; angedockte Fenster bleiben davon frei. */
    var inset by mutableStateOf(Inset())

    /** Ein Fenster wird an einen Rand gezogen: wohin es andocken würde. */
    var preview by mutableStateOf<Snap?>(null)

    var switcher by mutableStateOf(false)
    var switcherIndex by mutableStateOf(0)

    /** Ein Fenster wird verschoben oder in der Größe geändert. */
    var dragging by mutableStateOf(false)

    /** Wird nach jeder Änderung an den Fenstern gerufen (Speichern auf dem Gerät). */
    var onChange: () -> Unit = {}

    /** Ein Programm wurde geöffnet (für „Zuletzt“). */
    var onOpened: (String) -> Unit = {}

    data class Inset(val l: Int = 0, val r: Int = 0, val b: Int = 0)

    private var topZ = 10
    private var counter = 0

    private fun update(id: String, change: (Win) -> Win) {
        windows = windows.map { if (it.id == id) change(it) else it }
    }

    private fun frontmost(except: String? = null): String? =
        windows.filter { !it.minimized && it.id != except }.maxByOrNull { it.z }?.id

    fun find(id: String): Win? = windows.firstOrNull { it.id == id }

    fun focus(id: String) {
        val win = find(id) ?: return
        val z = if (win.z == topZ) win.z else ++topZ
        update(id) { it.copy(minimized = false, z = z) }
        focused = id
        onChange()
    }

    /** Die Größe eines neuen Fensters: das meiste der Fläche; schmal bekommt es alles (newGeometry). */
    fun newGeometry(index: Int, app: String = ""): Pair<Rect, Snap?> {
        val aw = area.w - inset.l - inset.r
        val ah = area.h - inset.b
        if (area.w < 1280) return Rect(inset.l, 0, aw, ah) to Snap.Max
        var w = minOf(1300, (aw * 0.78).roundToInt())
        var h = minOf(900, (ah * 0.9).roundToInt())
        if (app == "app:settings") {
            w = minOf(maxOf(w, 1180), aw - 16)
            h = minOf(maxOf(h, 830), ah - 16)
        }
        val offset = (index % 6) * 28
        val x = inset.l + maxOf(0, ((aw - w) / 2.0).roundToInt() + offset - 60)
        val y = maxOf(8, minOf(((ah - h) / 2.0).roundToInt() + offset - 20, ah - h - 8))
        return Rect(x, y, w, h) to null
    }

    /**
     * Öffnet ein Programm: holt sein Fenster nach vorn oder öffnet ein neues
     * ([multi]: immer ein neues, „In neuem Fenster öffnen“). Gibt die Kennung zurück.
     */
    fun open(app: String, href: String, title: String, multi: Boolean = false): String {
        val existing = if (!multi) windows.firstOrNull { it.app == app } else null
        if (existing != null) {
            if (href.isNotEmpty() && existing.href != href) update(existing.id) { it.copy(href = href) }
            focus(existing.id)
            return existing.id
        }
        val (g, snap) = newGeometry(windows.size, app)
        val win = Win(
            id = "w" + clock().toString(36) + (counter++).toString(36),
            app = app, href = href, title = title,
            x = g.x, y = g.y, w = g.w, h = g.h, z = ++topZ, snap = snap,
        )
        windows = windows + win
        focused = win.id
        onChange()
        onOpened(app)
        return win.id
    }

    fun close(id: String) {
        windows = windows.filter { it.id != id }
        if (focused == id) focused = frontmost()
        onChange()
    }

    fun closeApp(app: String) {
        windows.filter { it.app == app }.forEach { close(it.id) }
    }

    fun minimize(id: String) {
        update(id) { it.copy(minimized = true) }
        if (focused == id) focused = frontmost(except = id)
        onChange()
    }

    /** Alle Fenster weg, die Fläche ist zu sehen (Alt + K); noch einmal holt sie zurück. */
    private var shownBefore: List<String> = emptyList()
    fun showDesktop() {
        val open = windows.filter { !it.minimized }
        if (open.isEmpty() && shownBefore.isNotEmpty()) {
            val back = shownBefore
            shownBefore = emptyList()
            back.forEach { id -> if (find(id) != null) focus(id) }
            return
        }
        shownBefore = open.sortedBy { it.z }.map { it.id }
        windows = windows.map { it.copy(minimized = true) }
        focused = null
        onChange()
    }

    fun toggleMax(id: String) {
        update(id) { it.copy(snap = if (it.snap == Snap.Max) null else Snap.Max) }
        focus(id)
    }

    fun setSnap(id: String, snap: Snap?) {
        update(id) { it.copy(snap = snap) }
        focus(id)
    }

    /** Neue Lage eines freien Fensters (Ziehen, Größe ändern). */
    fun move(id: String, rect: Rect) {
        update(id) { it.copy(x = rect.x, y = rect.y, w = maxOf(MIN_W, rect.w), h = maxOf(MIN_H, rect.h), snap = null) }
    }

    fun navigate(id: String, href: String) {
        update(id) { it.copy(href = href) }
        onChange()
    }

    fun retitle(id: String, title: String) {
        if (find(id)?.title == title) return
        update(id) { it.copy(title = title) }
    }

    /**
     * Wo ein freies Fenster gezeichnet wird (clearOfDock): nie unter der
     * Oberkante des Docks, immer mit einem Stück Titelleiste in Reichweite.
     */
    fun clearOfDock(r: Rect): Rect {
        val room = area.h - inset.b
        if (room <= 0) return r
        val h = minOf(r.h, room)
        val grip = 140
        val left = inset.l
        val right = area.w - inset.r
        val w = if (right - left > grip) minOf(r.w, maxOf(MIN_W, right - left)) else r.w
        val x = if (right - left > grip) maxOf(left + grip - w, minOf(r.x, right - grip)) else r.x
        return Rect(x, maxOf(0, minOf(r.y, room - h)), w, h)
    }

    /** Die Fläche wurde schmaler: Fenster, die rechts hinausragen, kommen zurück (pullWindowsIn). */
    fun pullIn() {
        val left = inset.l
        val right = area.w - inset.r
        if (right - left < 200) return
        var changed = false
        windows = windows.map { win ->
            if (win.snap != null) return@map win
            val w = minOf(win.w, maxOf(MIN_W, right - left))
            val x = maxOf(left, minOf(win.x, right - w))
            if (x != win.x && win.x + w > right) {
                changed = true
                win.copy(x = x)
            } else {
                win
            }
        }
        if (changed) onChange()
    }

    /** Das Rechteck eines angedockten Fensters (snapRect). */
    fun snapRect(snap: Snap?): Rect? {
        val w = maxOf(200, area.w - inset.l - inset.r)
        val h = maxOf(200, area.h - inset.b)
        val hw = (w / 2.0).roundToInt()
        val hh = (h / 2.0).roundToInt()
        fun at(x: Int, y: Int, ww: Int, wh: Int) = Rect(x + inset.l, y, ww, wh)
        return when (snap) {
            Snap.Max -> at(0, 0, w, h)
            Snap.Left -> at(0, 0, hw, h)
            Snap.Right -> at(hw, 0, w - hw, h)
            Snap.TopLeft -> at(0, 0, hw, hh)
            Snap.TopRight -> at(hw, 0, w - hw, hh)
            Snap.BottomLeft -> at(0, hh, hw, h - hh)
            Snap.BottomRight -> at(hw, hh, w - hw, h - hh)
            null -> null
        }
    }

    /** Wo ein Fenster gezeichnet wird: angedockt oder frei, nie unter dem Dock. */
    fun shownRect(win: Win): Rect = snapRect(win.snap) ?: clearOfDock(win.rect)

    /** Wohin ein an (x, y) gezogenes Fenster andocken würde (snapAt). */
    fun snapAt(x: Int, y: Int): Snap? {
        val w = area.w
        val h = area.h
        val edge = 14
        val corner = 90
        if (y <= edge) return if (x <= corner) Snap.TopLeft else if (x >= w - corner) Snap.TopRight else Snap.Max
        if (x <= edge) return if (y >= h - corner) Snap.BottomLeft else if (y <= corner) Snap.TopLeft else Snap.Left
        if (x >= w - edge) return if (y >= h - corner) Snap.BottomRight else if (y <= corner) Snap.TopRight else Snap.Right
        return null
    }

    /** Die Fenster in der Reihenfolge des Umschalters: zuletzt benutzt zuerst (byRecency). */
    fun byRecency(): List<Win> = windows.sortedByDescending { it.z }

    /** Alt + Q: zum nächsten Fenster im Umschalter (mit Umschalt zurück). */
    fun switchNext(back: Boolean) {
        val list = byRecency()
        if (list.isEmpty()) return
        switcherIndex = if (switcher) (switcherIndex + if (back) list.size - 1 else 1) % list.size else minOf(1, list.size - 1)
        switcher = true
    }

    /** Alt losgelassen: das gewählte Fenster nach vorn. */
    fun switchDone() {
        if (!switcher) return
        val target = byRecency().getOrNull(switcherIndex)
        switcher = false
        target?.let { focus(it.id) }
    }

    /** Eine gespeicherte Anordnung: die Fenster daraus öffnen, mit ihren Maßen. */
    fun restore(layout: SavedLayout, title: (String) -> String) {
        val opened = HashSet<String>()
        for (lw in layout.windows) {
            // Dasselbe Programm zweimal in der Anordnung: das zweite in einem eigenen Fenster.
            val id = open(lw.app, lw.href, title(lw.app), multi = lw.app in opened)
            opened += lw.app
            update(id) { it.copy(x = lw.x, y = lw.y, w = lw.w, h = lw.h, snap = null) }
        }
        onChange()
    }

    // ---- Auf dem Gerät merken (wie localStorage im Web) ----

    fun toJson(): JsonArray = JsonArray(
        windows.map { w ->
            JsonObject(
                buildMap {
                    put("id", JsonPrimitive(w.id)); put("app", JsonPrimitive(w.app)); put("href", JsonPrimitive(w.href))
                    put("title", JsonPrimitive(w.title)); put("x", JsonPrimitive(w.x)); put("y", JsonPrimitive(w.y))
                    put("w", JsonPrimitive(w.w)); put("h", JsonPrimitive(w.h)); put("z", JsonPrimitive(w.z))
                    put("minimized", JsonPrimitive(w.minimized))
                    w.snap?.let { put("snap", JsonPrimitive(it.id)) }
                },
            )
        },
    )

    /** Liest gemerkte Fenster; kaputte Werte fallen auf vernünftige Maße zurück (storedRect). */
    fun load(raw: JsonArray?) {
        fun num(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val list = raw.orEmpty().mapNotNull { it as? JsonObject }
            .filter { str(it, "id") != null && str(it, "app") != null && str(it, "href") != null }
            .take(30)
            .withIndex()
            .sortedWith(compareBy<IndexedValue<JsonObject>> { num(it.value, "z") ?: it.index.toDouble() }.thenBy { it.index })
            .mapIndexed { i, (_, o) ->
                Win(
                    id = str(o, "id")!!, app = str(o, "app")!!, href = str(o, "href")!!, title = str(o, "title").orEmpty(),
                    x = (num(o, "x") ?: (80.0 + i * 28)).coerceIn(-20000.0, 20000.0).roundToInt(),
                    y = (num(o, "y") ?: (40.0 + i * 28)).coerceIn(0.0, 20000.0).roundToInt(),
                    w = (num(o, "w") ?: 900.0).coerceIn(MIN_W.toDouble(), 20000.0).roundToInt(),
                    h = (num(o, "h") ?: 600.0).coerceIn(MIN_H.toDouble(), 20000.0).roundToInt(),
                    z = 10 + i,
                    minimized = (o["minimized"] as? JsonPrimitive)?.booleanOrNull == true,
                    snap = Snap.of(str(o, "snap")),
                )
            }
        windows = list
        topZ = 10 + list.size
        focused = list.filter { !it.minimized }.maxByOrNull { it.z }?.id
    }

    companion object {
        const val MIN_W = 360
        const val MIN_H = 240
    }
}
