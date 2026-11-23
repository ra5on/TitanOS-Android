package io.github.ra5on.titanos.workspace

import java.text.Collator
import java.util.Locale

/*
 * Wie die Symbole, das Dock, die Widgets und das Menü angeordnet sind
 * (web/src/lib/desktop/prefs.ts). Dieselben Regeln wie im Web, damit der
 * Arbeitsplatz am Handy und am PC gleich aussieht.
 */

/** Ein Programm, wie der Arbeitsplatz es kennt: Schlüssel („app:docker“, „docker:immich“), Name, Art. */
data class Available(val key: String, val label: String, val kind: String)

/** Die gezeigten Symbole in Reihenfolge: die eigene Liste des Kontos oder alle Programme (arrangeIcons). */
fun arrangeIcons(prefs: DesktopPrefs, available: List<Available>): List<String> {
    val known = available.map { it.key }.toSet()
    val folders = prefs.folders.map { "folder:" + it.id }
    val inFolders = prefs.folders.flatMap { it.apps }.toSet()
    var keys = if (prefs.customIcons) {
        prefs.icons.filter { it in known || it in folders }
    } else {
        available.map { it.key }.filter { it !in inFolders }
    }
    if (!prefs.customIcons) keys = keys + folders.filter { it !in keys }
    val label = available.associate { it.key to it.label }
    val kind = available.associate { it.key to it.kind }
    fun name(k: String) = if (k.startsWith("folder:")) prefs.folders.firstOrNull { "folder:" + it.id == k }?.name.orEmpty() else label[k] ?: k
    val original = keys
    keys = when (prefs.sort) {
        "name" -> {
            val c = Collator.getInstance(Locale.getDefault())
            keys.sortedWith { a, b -> c.compare(name(a), name(b)) }
        }
        "kind" -> {
            fun rank(k: String) = if (k.startsWith("folder:")) 2 else if (kind[k] == "system") 0 else 1
            keys.sortedWith(compareBy<String> { rank(it) }.thenBy { original.indexOf(it) })
        }
        "recent" -> keys.sortedBy { prefs.recent.indexOf(it).let { i -> if (i < 0) 999 else i } }
        else -> keys
    }
    return keys
}

/**
 * Wo jedes Symbol im Raster liegt (placeIcons): sortiert von oben links in
 * Spalten; „frei“ behält die Plätze des Kontos. Ein Platz außerhalb eines
 * kleineren Fensters wird nur vertreten, nie überschrieben.
 */
fun placeIcons(keys: List<String>, sort: String, iconPos: Map<String, Cell>, rows: Int, columns: Int): Map<String, Cell> {
    val out = LinkedHashMap<String, Cell>()
    val rowsN = maxOf(1, rows)
    val colsN = maxOf(1, columns)
    val taken = HashSet<Cell>()
    fun take(key: String, at: Cell) {
        taken += at
        out[key] = at
    }
    if (sort == "free") {
        val outside = mutableListOf<String>()
        for (key in keys) {
            val at = iconPos[key] ?: continue
            if (at.c >= colsN || at.r >= rowsN) outside += key
            else if (at !in taken) take(key, at)
        }
        for (key in outside) {
            val at = iconPos.getValue(key)
            val c0 = minOf(at.c, colsN - 1)
            val r0 = minOf(at.r, rowsN - 1)
            var best: Cell? = null
            var bestD = Double.MAX_VALUE
            for (c in 0 until colsN) for (r in 0 until rowsN) {
                val cell = Cell(c, r)
                if (cell in taken) continue
                val d = ((c - c0) * (c - c0) + (r - r0) * (r - r0)).toDouble() + Math.abs(c - c0) / 100.0
                if (d < bestD) {
                    bestD = d
                    best = cell
                }
            }
            best?.let { take(key, it) }
        }
    }
    var next = 0
    for (key in keys) {
        if (key in out) continue
        while (Cell(next / rowsN, next % rowsN) in taken) next++
        take(key, Cell(next / rowsN, next % rowsN))
    }
    return out
}

/** Die Plätze nach dem Ablegen von [key] auf [to] (dropIcon); nur das Symbol und ein getroffenes ändern sich. */
fun dropIcon(sort: String, iconPos: Map<String, Cell>, shown: Map<String, Cell>, key: String, to: Cell, other: String? = null): Map<String, Cell> {
    val next = LinkedHashMap<String, Cell>(if (sort == "free") iconPos else shown)
    val from = shown[key]
    if (other != null && other != key) {
        if (from != null) next[other] = from else next.remove(other)
    }
    next[key] = to
    return next
}

/** Die Programme im Dock: die eigenen des Kontos oder die Werksauswahl, soweit es sie gibt (dockPinned). */
fun dockPinned(prefs: DesktopPrefs, known: Set<String>): List<String> =
    (if (prefs.dock.customPinned) prefs.dock.pinned else DOCK_DEFAULT_PINNED).filter { it in known }

/** Wo ein neues Widget niemanden verdeckt (placeWidget): erste freie Reihe. */
fun placeWidget(widgets: List<Widget>, w: Int, h: Int, columns: Int, minX: Int = 0): Pair<Int, Int> {
    fun overlaps(x: Int, y: Int) = widgets.any { o ->
        val ox = if (o.x < 0) columns + o.x else o.x
        x < ox + o.w && ox < x + w && y < o.y + o.h && o.y < y + h
    }
    val start = minOf(minX, maxOf(0, columns - w))
    for (y in 0 until 200) {
        var x = start
        while (x + w <= columns) {
            if (!overlaps(x, y)) return x to y
            x++
        }
    }
    return start to 0
}

/** Linke Kante eines Widgets in Zellen (widgetLeft): x < 0 hängt am rechten Rand. */
fun widgetColumn(w: Widget, columns: Int): Int = if (w.x < 0) maxOf(0, columns + w.x) else w.x

/** Verschiebt den Eintrag an [from] nach [to] (moveItem). */
fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
    if (from < 0 || from >= list.size) return list
    val out = list.toMutableList()
    val item = out.removeAt(from)
    out.add(to.coerceIn(0, out.size), item)
    return out
}

/** Die Programme des Menüs in ihrer Reihenfolge (menuSorted): eigene Reihenfolge, Neues A–Z dahinter. */
fun <T> menuSorted(programs: List<T>, order: List<String>, key: (T) -> String, label: (T) -> String): List<T> {
    val c = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val byName = programs.sortedWith { a, b -> c.compare(label(a), label(b)).takeIf { it != 0 } ?: key(a).compareTo(key(b)) }
    if (order.isEmpty()) return byName
    val at = order.withIndex().associate { it.value to it.index }
    val known = byName.filter { key(it) in at }.sortedBy { at.getValue(key(it)) }
    return known + byName.filter { key(it) !in at }
}

/** Die Reihenfolge, nachdem [key] vor [before] gelegt wurde (menuMove). */
fun menuMove(shown: List<String>, key: String, before: String?): List<String> {
    if (key == before || key !in shown) return shown
    val out = shown.filter { it != key }.toMutableList()
    val at = if (before == null) -1 else out.indexOf(before)
    if (at < 0) out += key else out.add(at, key)
    return out
}
