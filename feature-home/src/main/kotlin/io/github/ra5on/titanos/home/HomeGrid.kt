package io.github.ra5on.titanos.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.rememberWidestWord
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Eine Zeile einer Seite: Symbole (bis zur Spaltenzahl) oder ein bzw. zwei Widgets. */
internal sealed interface GridRow {
    data class Icons(val keys: List<String>) : GridRow
    data class Widgets(val keys: List<String>) : GridRow
}

/**
 * Teilt eine Seite in Zeilen, wenn weniger als vier Spalten passen (große
 * Schrift): Programme und Ordner füllen Zeilen zu [columns], ein Widget
 * „ganz“ hat eine Zeile für sich, zwei „halbe“ teilen sich eine (bei weniger
 * als drei Spalten steht jedes Widget allein). Freie Plätze gibt es dann
 * nicht, die Lesereihenfolge bleibt.
 */
internal fun gridRows(page: List<String>, prefs: PhonePrefs, columns: Int): List<GridRow> {
    val out = mutableListOf<GridRow>()
    var icons = mutableListOf<String>()
    fun flush() {
        if (icons.isNotEmpty()) out += GridRow.Icons(icons)
        icons = mutableListOf()
    }
    for (k in page) {
        if (k.startsWith("widget:")) {
            flush()
            val half = columns >= 3 && cellsOf(k, prefs) == 4
            val last = out.lastOrNull()
            if (half && last is GridRow.Widgets && last.keys.size == 1 && cellsOf(last.keys[0], prefs) == 4) {
                out[out.lastIndex] = GridRow.Widgets(last.keys + k)
            } else {
                out += GridRow.Widgets(listOf(k))
            }
        } else {
            icons += k
            if (icons.size >= columns) flush()
        }
    }
    flush()
    return out
}

/** Lage des Rasters einer Seite: Spaltenbreite, Abstand, Oberkante und Höhe jeder Zeile (Pixel in der Seite). */
internal class PageGeometry {
    var coords: LayoutCoordinates? = null
    var colWidth = 0f
    var gap = 0f
    var rowTops = FloatArray(0)
    var rowHeights = FloatArray(0)

    /** Mitte des Symbols unter der Oberkante der Zelle. */
    var iconCenter = 0f

    /** Mitte der Zelle [index] für einen Eintrag der Breite [w] (Koordinaten der Startseite). */
    fun cellCenter(root: LayoutCoordinates, index: Int, w: Int): Offset? {
        val c = coords ?: return null
        if (!c.isAttached || !root.isAttached) return null
        val row = index / PHONE_COLUMNS
        if (row !in rowTops.indices) return null
        val x = index % PHONE_COLUMNS * (colWidth + gap) + (w * colWidth + (w - 1) * gap) / 2f
        val y = rowTops[row] + iconCenter
        return root.localPositionOf(c, Offset(x, y))
    }

    /** Zelle unter [pos] (Koordinaten der Startseite) oder null außerhalb des Rasters. */
    fun cellAt(root: LayoutCoordinates, pos: Offset, w: Int): Int? {
        val c = coords ?: return null
        if (!c.isAttached || !root.isAttached || rowTops.isEmpty() || colWidth <= 0f) return null
        val local = c.localPositionOf(root, pos)
        if (local.x < -colWidth || local.x > c.size.width + colWidth) return null
        val col = ((local.x - (w - 1) * (colWidth + gap) / 2f) / (colWidth + gap)).toInt().coerceIn(0, PHONE_COLUMNS - w)
        var row = rowTops.lastIndex
        for (i in rowTops.indices) {
            if (local.y < rowTops[i] + rowHeights[i] + gap / 2f) {
                row = i
                break
            }
        }
        val snapped = if (w >= PHONE_COLUMNS) 0 else col - col % w
        return row.coerceAtLeast(0) * PHONE_COLUMNS + snapped
    }
}

/**
 * Wo was beim Ziehen liegt (1.02 mit freien Plätzen): die Zelle unter dem
 * Finger ([target]) mit Platzhalter, nach kurzem Halten über einem Symbol der
 * Ordner ([hover]), am Rand die Seite davor bzw. danach, unten das Dock.
 */
@Stable
internal class GridState {
    var root: LayoutCoordinates? = null
    val items = HashMap<String, Pair<Int, LayoutCoordinates>>()
    val geometry = HashMap<Int, PageGeometry>()
    var dragKey by mutableStateOf<String?>(null)
    var pos by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)

    /** Ziel: Seite und Zelle. */
    var target by mutableStateOf<Pair<Int, Int>?>(null)

    /** Über diesem Symbol gehalten: Ablegen ergibt einen Ordner (Ring). */
    var hover by mutableStateOf<String?>(null)

    /** Über dem Dock: Platz im Dock, an den abgelegt wird. */
    var dockSlot by mutableStateOf<Int?>(null)

    /** Gerade abgelegt: das Symbol gleitet vom Finger ([from]) in seine Zelle ([to]), beides Koordinaten der Startseite. */
    var landing by mutableStateOf<Landing?>(null)
    var edgeJob: Job? = null
    var hoverJob: Job? = null

    // Der neueste Stand (die Zeiger-Routinen laufen länger als eine Komposition).
    var draft: PhonePrefs? = null
    var change: (PhonePrefs) -> Unit = {}
    var pages: List<List<String?>> = emptyList()
    var folderName: String = "Ordner"
    var dock: DockDrop? = null

    /** Zelle unter dem Finger auf Seite [page] für einen Eintrag der Breite [w]. */
    fun cellAt(page: Int, w: Int): Int? {
        val r = root ?: return null
        return geometry[page]?.cellAt(r, pos, w)
    }

    /** Programm oder Ordner an Zelle [index] von Seite [page] (für den Ordner beim Halten). */
    fun keyAt(page: Int, index: Int): String? = pages.getOrNull(page)?.getOrNull(index)
}

data class Landing(val key: String, val from: Offset, val to: Offset)

/** Das Dock als Ziel beim Ziehen (die App zeichnet es; Koordinaten im Fenster). */
interface DockDrop {
    /** Platz im Dock unter [window] (Fensterkoordinaten), sonst null. */
    fun slotAt(window: Offset): Int?

    /** Beim Ziehen darüber: Platzhalter an [slot] (null: keiner). */
    fun hover(slot: Int?)

    /** Abgelegt: [key] an Platz [slot]. */
    fun drop(key: String, slot: Int)
}

@Composable
internal fun rememberGridState(): GridState = remember { GridState() }

/** Kleines Wackeln im Bearbeiten (nur ohne „Animationen entfernen“). */
@Composable
private fun wiggleAngle(on: Boolean, seed: Int): Float {
    if (!on) return 0f
    val t = rememberInfiniteTransition(label = "wiggle")
    val a by t.animateFloat(-1.4f, 1.4f, infiniteRepeatable(tween(120 + abs(seed % 5) * 14, easing = LinearEasing), RepeatMode.Reverse), label = "angle")
    return a
}

/** Ohne Wackeln: gestrichelter Rahmen wie im Web (Bewegung reduzieren). */
private fun Modifier.dashed(on: Boolean, color: androidx.compose.ui.graphics.Color): Modifier =
    if (!on) this else drawBehind {
        drawRoundRect(color, cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))))
    }

/**
 * Gleitet zur neuen Stelle, wenn ein Eintrag den Platz wechselt (Nachbarn
 * rücken beim Ziehen, Ablegen). Ohne Bewegung ([animate] aus) springt es.
 */
private fun Modifier.glide(animate: Boolean, grid: GridState): Modifier = composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IntOffset?>(null) }
    val anim = remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    this
        .onPlaced { c ->
            val root = grid.root
            val now = (if (root != null && root.isAttached) root.localPositionOf(c, Offset.Zero) else c.positionInRoot()).round()
            val a = anim.value
            if (!animate) {
                anim.value = null
            } else if (a == null) {
                anim.value = Animatable(now, IntOffset.VectorConverter)
            } else if (a.targetValue != now) {
                scope.launch { a.animateTo(now, spring(stiffness = Spring.StiffnessMediumLow)) }
            }
            target = now
        }
        .offset {
            val a = anim.value
            val t = target
            if (a == null || t == null) IntOffset.Zero else a.value - t
        }
}

private fun LayoutCoordinates.positionInRoot(): Offset {
    var c: LayoutCoordinates = this
    while (true) c = c.parentLayoutCoordinates ?: break
    return c.localPositionOf(this, Offset.Zero)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PageContent(
    page: List<String?>,
    index: Int,
    rows: Int,
    prefs: PhonePrefs,
    folders: List<PhoneFolder>,
    byKey: Map<String, Program>,
    state: HomeState,
    photos: String?,
    editing: Boolean,
    wiggle: Boolean,
    grid: GridState,
    onLongPress: () -> Unit,
    onOpen: (String) -> Unit,
    onWidget: (String) -> Unit,
    onPhotos: (() -> Unit)?,
    onSheet: (String) -> Unit,
    onChange: (PhonePrefs) -> Unit,
    draft: PhonePrefs?,
    pageCount: Int,
    pager: PagerState,
    scope: CoroutineScope,
) {
    val folderById = folders.associateBy { it.id }
    val labelOf: @Composable (String) -> String = { key ->
        when {
            key.startsWith("folder:") -> folderById[key.removePrefix("folder:")]?.name.orEmpty()
            key.startsWith("widget:") -> prefs.widgets.firstOrNull { "widget:" + it.id == key }?.let { stringResource(widgetTitleRes(it.kind)) }.orEmpty()
            else -> byKey[key]?.let { programLabel(it.id) }.orEmpty()
        }
    }
    val keys = page.filterNotNull()
    // Ein weiches Trennzeichen („System\u00ADsteuerung“) ist eine erlaubte Trennstelle: dort zählt jedes Teilstück als Wort.
    val labels = keys.filter { !it.startsWith("widget:") }.flatMap { io.github.ra5on.titanos.ui.hyphenPieces(labelOf(it)) }
    val widest = rememberWidestWord(labels, TitanType.appLabel)
    val density = LocalDensity.current
    val entry: @Composable (String, Modifier) -> Unit = { key, m ->
        val w = prefs.widgets.firstOrNull { "widget:" + it.id == key }
        if (w != null) {
            HomeEntry(key, index, labelOf(key), editing, wiggle, grid, onSheet, onChange, draft, pager, scope, pageCount, rows, m, onOpen = { onWidget(w.kind) }, onLongPress = onLongPress) {
                WidgetView(w, state, photos, onPhotos, editing, onResize = { size -> draft?.let { onChange(resizeWidget(it, w.id, size)) } })
            }
        } else {
            val label = labelOf(key)
            HomeEntry(key, index, label, editing, wiggle, grid, onSheet, onChange, draft, pager, scope, pageCount, rows, m, onOpen = { onOpen(key) }, onLongPress = onLongPress) {
                IconView(key, label, byKey, folderById, wide = false)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().longPressFree(!editing, onLongPress)) {
        val gap = 6.dp
        val inner = maxWidth - 32.dp
        val columns = programColumns(with(density) { inner.toPx() }, with(density) { gap.toPx() }, widest.toFloat(), if (maxWidth > 600.dp) 6 else 4)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (columns == 1) 6.dp else 18.dp),
        ) {
            if (columns == PHONE_COLUMNS) {
                FreeGrid(page, index, rows, prefs, editing, wiggle, grid, gap, entry)
            } else {
                // Große Schrift oder breites Fenster: der Reihe nach, ohne Lücken (die Plätze bleiben gespeichert).
                for (row in gridRows(keys, prefs, columns)) {
                    when (row) {
                        is GridRow.Widgets -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (key in row.keys) entry(key, Modifier.weight(1f))
                            if (row.keys.size == 1 && columns >= 3 && cellsOf(row.keys[0], prefs) == 4) Spacer(Modifier.weight(1f))
                        }
                        is GridRow.Icons -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (key in row.keys) {
                                if (columns == 1 && !key.startsWith("widget:")) {
                                    val label = labelOf(key)
                                    HomeEntry(key, index, label, editing, wiggle, grid, onSheet, onChange, draft, pager, scope, pageCount, rows, Modifier.weight(1f), onOpen = { onOpen(key) }, onLongPress = onLongPress) {
                                        IconView(key, label, byKey, folderById, wide = true)
                                    }
                                } else {
                                    entry(key, Modifier.weight(1f))
                                }
                            }
                            repeat(columns - row.keys.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            // Freie Fläche unten: langes Drücken beginnt auch dort das Bearbeiten, auf einer leeren Seite ebenso.
            Spacer(Modifier.fillMaxWidth().height(if (keys.isEmpty() && !editing) 240.dp else 48.dp))
        }
    }
}

/**
 * Raster mit freien Plätzen (1.02): vier Spalten, [rows] Zeilen (im
 * Bearbeiten so viele, wie belegt sind, und eine leere dazu). Leere Zellen
 * bleiben leer. Beim Ziehen zeigt ein Platzhalter die Zielzelle; ist sie
 * belegt, rücken die Nachbarn sichtbar weiter, so wie sie beim Loslassen
 * stünden. Zeilen wachsen mit ihrem Inhalt (200 % Schrift, Widgets).
 */
@Composable
private fun FreeGrid(
    page: List<String?>,
    index: Int,
    rows: Int,
    prefs: PhonePrefs,
    editing: Boolean,
    wiggle: Boolean,
    grid: GridState,
    gap: Dp,
    entry: @Composable (String, Modifier) -> Unit,
) {
    val c = Titan.colors
    val dragKey = grid.dragKey
    val target = grid.target?.takeIf { it.first == index && dragKey != null && grid.hover == null && grid.dockSlot == null }
    // Was beim Loslassen gälte: die Nachbarn stehen schon dort (der gezogene Eintrag als Platzhalter).
    val preview = remember(page, prefs, target, dragKey) {
        val d = grid.draft
        if (target != null && d != null && dragKey != null) placeAt(d, dragKey, index, target.second).pages.getOrNull(index) else null
    }
    val shownRows = if (editing) maxOf(rows, rowsUsed(preview ?: page, prefs) + 1) else rows
    val (placed, _) = remember(preview ?: page, prefs, shownRows) { arrange(preview ?: page, prefs, if (editing) shownRows else maxOf(shownRows, rowsNeeded(page, prefs))) }
    val geo = remember(index) { PageGeometry() }.also { grid.geometry[index] = it }
    val density = LocalDensity.current
    val minRow = with(density) { (60.dp + 7.dp + TitanType.appLabel.lineHeight.toDp() * 2).roundToPx() }
    val gapPx = with(density) { gap.roundToPx() }
    val rowGap = with(density) { 18.dp.roundToPx() }
    Layout(
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { geo.coords = it },
        content = {
            for (p in placed) {
                // Je Schlüssel: der gezogene Eintrag wandert mit, ohne dass sein Ziehen abbricht.
                androidx.compose.runtime.key(p.key) {
                    Box(Modifier.glide(wiggle && editing, grid)) {
                        entry(p.key, Modifier.fillMaxWidth())
                        if (p.key == dragKey) {
                            // Platzhalter: wo der gezogene Eintrag landet (Rahmen, darin sein blasses Bild).
                            val shape = RoundedCornerShape(14.dp)
                            Box(Modifier.matchParentSize().clip(shape).background(c.accent.copy(alpha = .14f)).border(2.dp, c.accent, shape))
                        }
                    }
                }
            }
            // Leere Zellen im Bearbeiten: ein feiner Rahmen zeigt, wohin man setzen kann.
            if (editing) {
                for (cell in emptyCells(placed, shownRows)) {
                    Box(Modifier.fillMaxWidth().height(with(density) { minRow.toDp() }).dashed(true, c.border))
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val colW = ((width - gapPx * (PHONE_COLUMNS - 1)) / PHONE_COLUMNS).coerceAtLeast(1)
        val empties = if (editing) emptyCells(placed, shownRows) else emptyList()
        val items = measurables.take(placed.size).mapIndexed { i, m ->
            val p = placed[i]
            m.measure(Constraints.fixedWidth(colW * p.w + gapPx * (p.w - 1)))
        }
        val emptyItems = measurables.drop(placed.size).map { it.measure(Constraints.fixedWidth(colW)) }
        val count = maxOf(shownRows, placed.maxOfOrNull { it.row + it.h } ?: 0)
        // Zeilen, die ganz unter Widgets liegen, sind so hoch wie die Widgets (keine Lücke darunter); sonst mindestens eine Symbolzeile.
        val covered = IntArray(count)
        for (p in placed) if (p.h > 1) for (r in p.row until p.row + p.h) covered[r] += p.w
        val heights = IntArray(count) { r -> if (!editing && covered[r] >= PHONE_COLUMNS) 0 else minRow }
        placed.forEachIndexed { i, p -> if (p.h == 1) heights[p.row] = maxOf(heights[p.row], items[i].height) }
        // Ein Widget über zwei Zeilen: wächst es darüber hinaus, wird die zweite Zeile höher.
        placed.forEachIndexed { i, p ->
            if (p.h > 1) {
                val span = (p.row until p.row + p.h).sumOf { heights[it] } + rowGap * (p.h - 1)
                if (items[i].height > span) heights[p.row + p.h - 1] += items[i].height - span
            }
        }
        val tops = IntArray(count)
        for (r in 1 until count) tops[r] = tops[r - 1] + heights[r - 1] + rowGap
        geo.colWidth = colW.toFloat()
        geo.gap = gapPx.toFloat()
        geo.iconCenter = 30.dp.toPx()
        geo.rowTops = FloatArray(count) { tops[it].toFloat() }
        geo.rowHeights = FloatArray(count) { heights[it].toFloat() }
        val total = if (count == 0) 0 else tops[count - 1] + heights[count - 1]
        layout(width, total) {
            empties.forEachIndexed { i, cell -> emptyItems.getOrNull(i)?.place(cell % PHONE_COLUMNS * (colW + gapPx), tops[cell / PHONE_COLUMNS]) }
            placed.forEachIndexed { i, p -> items[i].place(p.col * (colW + gapPx), tops[p.row]) }
        }
    }
}

/** Zeilen, die belegt sind. */
private fun rowsUsed(page: List<String?>, prefs: PhonePrefs): Int {
    var rows = 0
    page.forEachIndexed { i, k -> if (k != null) rows = maxOf(rows, i / PHONE_COLUMNS + spanOf(k, prefs).second) }
    return rows
}

/** Leere Zellen der ersten [rows] Zeilen. */
private fun emptyCells(placed: List<Placed>, rows: Int): List<Int> {
    val taken = HashSet<Int>()
    for (p in placed) for (r in p.row until p.row + p.h) for (c in p.col until p.col + p.w) taken += r * PHONE_COLUMNS + c
    return (0 until rows * PHONE_COLUMNS).filter { it !in taken }
}

/** Symbol eines Programms oder Ordners mit Namen darunter (bei sehr großer Schrift daneben). */
@Composable
private fun IconView(key: String, label: String, byKey: Map<String, Program>, folders: Map<String, PhoneFolder>, wide: Boolean) {
    val c = Titan.colors
    val icon: @Composable (Int) -> Unit = { size ->
        if (key.startsWith("folder:")) {
            FolderIcon(folders[key.removePrefix("folder:")], byKey, size)
        } else {
            byKey[key]?.let { AppIcon(it.glyph, it.gradient, size.dp) }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            icon(48)
            io.github.ra5on.titanos.ui.HyphenText(label, TitanType.appLabel, c.text, Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            icon(60)
            io.github.ra5on.titanos.ui.HyphenText(label, TitanType.appLabel, c.text, textAlign = TextAlign.Center)
        }
    }
}

/** Ordner: bis zu vier kleine Symbole auf einer Glasfläche. */
@Composable
internal fun FolderIcon(folder: PhoneFolder?, byKey: Map<String, Program>, size: Int) {
    val c = Titan.colors
    val shape = RoundedCornerShape(percent = 23)
    Box(Modifier.size(size.dp).clip(shape).background(c.surface.copy(alpha = .78f)).border(1.dp, c.border, shape).padding((size * .12f).dp), contentAlignment = Alignment.Center) {
        val apps = folder?.apps.orEmpty().mapNotNull { byKey[it] }.take(4)
        val mini = (size * .34f).toInt()
        Column(verticalArrangement = Arrangement.spacedBy((size * .06f).dp)) {
            for (row in apps.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy((size * .06f).dp)) {
                    for (p in row) AppIcon(p.glyph, p.gradient, mini.dp)
                    if (row.size == 1) Spacer(Modifier.width(mini.dp))
                }
            }
        }
    }
}

/** Kurzer Ruck beim Wechsel der Zielzelle und beim Ablegen. */
private fun HapticFeedback.tick() = performHapticFeedback(HapticFeedbackType.SegmentTick)

/**
 * Ein Eintrag der Startseite: öffnet beim Tippen, beginnt nach langem Drücken
 * das Bearbeiten. Im Bearbeiten: Wackeln (oder gestrichelt), × oben links,
 * Tippen öffnet „Bewegen …“, Ziehen verschiebt an jede Zelle (auch an den
 * Rand: Seite davor bzw. danach; nach unten: ins Dock), kurz über einem
 * anderen Symbol halten ergibt einen Ordner. TalkBack bekommt dieselben
 * Aktionen ohne Ziehen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeEntry(
    key: String,
    page: Int,
    label: String,
    editing: Boolean,
    wiggle: Boolean,
    grid: GridState,
    onSheet: (String) -> Unit,
    onChange: (PhonePrefs) -> Unit,
    draft: PhonePrefs?,
    pager: PagerState,
    scope: CoroutineScope,
    pageCount: Int,
    rows: Int,
    modifier: Modifier,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = Titan.colors
    val dragging = grid.dragKey == key
    val hover = grid.hover == key
    val angle = wiggleAngle(editing && wiggle && !dragging, key.hashCode())
    val grow by animateFloatAsState(if (hover) 1.14f else 1f, if (wiggle) spring() else androidx.compose.animation.core.snap(), label = "grow")
    val haptic = LocalHapticFeedback.current
    val editLabel = stringResource(R.string.home_edit_start)
    val moveLabel = stringResource(R.string.home_move)
    val earlier = stringResource(R.string.home_move_earlier)
    val later = stringResource(R.string.home_move_later)
    val removeLabel = when {
        key.startsWith("folder:") -> stringResource(R.string.home_dissolve)
        key.startsWith("widget:") -> stringResource(R.string.home_remove_widget)
        else -> stringResource(R.string.home_remove_named, label)
    }
    val edgePx = with(LocalDensity.current) { 28.dp.toPx() }
    val folderable = !key.startsWith("folder:") && !key.startsWith("widget:")
    fun remove() {
        val d = grid.draft ?: return
        onChange(
            when {
                key.startsWith("folder:") -> dissolveFolder(d, key.removePrefix("folder:"))
                key.startsWith("widget:") -> removeWidget(d, key.removePrefix("widget:"))
                else -> removeProgram(d, key)
            },
        )
    }
    fun clearDrag() {
        grid.edgeJob?.cancel()
        grid.hoverJob?.cancel()
        grid.dragKey = null
        grid.hover = null
        grid.target = null
        grid.dockSlot = null
        grid.dock?.hover(null)
    }
    Box(
        modifier
            .onGloballyPositioned { grid.items[key] = page to it }
            .graphicsLayer {
                // Der gezogene Eintrag bleibt blass an seiner alten Stelle, bis er abgelegt ist.
                alpha = if (dragging || grid.landing?.key == key) .3f else 1f
                rotationZ = angle
                scaleX = grow
                scaleY = grow
            },
    ) {
        val interaction = if (!editing) {
            Modifier.combinedClickable(onClickLabel = null, onLongClickLabel = editLabel, onLongClick = onLongPress, onClick = onOpen)
        } else {
            Modifier
                .combinedClickable(onClickLabel = moveLabel, onClick = { onSheet(key) })
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(moveLabel) { onSheet(key); true },
                        CustomAccessibilityAction(earlier) { grid.draft?.let { onChange(moveBy(it, key, -1, rows * PHONE_COLUMNS)) }; true },
                        CustomAccessibilityAction(later) { grid.draft?.let { onChange(moveBy(it, key, +1, rows * PHONE_COLUMNS)) }; true },
                        CustomAccessibilityAction(removeLabel) { remove(); true },
                    )
                }
                .pointerInput(key, page) {
                    detectDragGestures(
                        onDragStart = { local ->
                            val r = grid.root
                            val me = grid.items[key]?.second
                            if (r != null && me != null && me.isAttached) {
                                grid.dragKey = key
                                grid.pos = r.localPositionOf(me, local)
                                grid.grab = local
                                grid.target = placeOf(grid.draft ?: return@detectDragGestures, key)
                                haptic.tick()
                            }
                        },
                        onDrag = { change, amount ->
                            if (grid.dragKey != key) return@detectDragGestures
                            change.consume()
                            grid.pos += amount
                            val d = grid.draft ?: return@detectDragGestures
                            val root = grid.root
                            // Unten über dem Dock: Platzhalter im Dock statt im Raster.
                            val dock = grid.dock
                            val slot = if (dock != null && root != null && root.isAttached && !key.startsWith("widget:") && !key.startsWith("folder:")) dock.slotAt(root.localToWindow(grid.pos)) else null
                            if (slot != grid.dockSlot) {
                                grid.dockSlot = slot
                                dock?.hover(slot)
                                if (slot != null) haptic.tick()
                            }
                            if (slot == null) {
                                val at = pager.targetPage
                                val (w, _) = spanOf(key, d)
                                val cell = grid.cellAt(at, w)
                                val next = cell?.let { at to it }
                                if (next != null && next != grid.target) {
                                    grid.target = next
                                    haptic.tick()
                                    grid.hover = null
                                    grid.hoverJob?.cancel()
                                    // Über einem Symbol kurz halten (400 ms): Ordner statt Weiterrücken.
                                    val other = grid.keyAt(at, next.second)
                                    if (folderable && other != null && other != key && !other.startsWith("widget:")) {
                                        grid.hoverJob = scope.launch {
                                            delay(400)
                                            if (grid.target == next && grid.dragKey == key) {
                                                grid.hover = other
                                                haptic.tick()
                                            }
                                        }
                                    }
                                }
                            }
                            // Am Rand gehalten: nach kurzer Zeit die Seite davor bzw. danach (am Ende eine neue).
                            val width = grid.root?.size?.width ?: 0
                            val dir = when {
                                grid.pos.x < edgePx -> -1
                                width > 0 && grid.pos.x > width - edgePx -> 1
                                else -> 0
                            }
                            if (dir == 0) {
                                grid.edgeJob?.cancel()
                                grid.edgeJob = null
                            } else if (grid.edgeJob?.isActive != true) {
                                grid.edgeJob = scope.launch {
                                    delay(650)
                                    val target = pager.targetPage + dir
                                    if (target < 0) return@launch
                                    if (target >= pageCount) grid.draft?.let { grid.change(addPage(it)) }
                                    // Das Blättern selbst läuft für sich: Loslassen bricht es nicht mitten auf der Seite ab.
                                    scope.launch {
                                        delay(30)
                                        pager.animateScrollToPage(target)
                                    }
                                    delay(450)
                                    grid.edgeJob = null
                                }
                            }
                        },
                        onDragEnd = {
                            val d = grid.draft
                            val dragged = grid.dragKey
                            if (d != null && dragged == key) {
                                val slot = grid.dockSlot
                                val hoverKey = grid.hover
                                val target = grid.target
                                when {
                                    slot != null -> grid.dock?.drop(key, slot)
                                    hoverKey != null -> grid.change(combine(d, key, hoverKey, grid.folderName, "f" + System.currentTimeMillis().toString(36)))
                                    target != null -> {
                                        val (w, _) = spanOf(key, d)
                                        val to = grid.root?.let { r -> grid.geometry[target.first]?.cellCenter(r, target.second, w) }
                                        if (wiggle && to != null) grid.landing = Landing(key, grid.pos, to)
                                        grid.change(placeAt(d, key, target.first, target.second))
                                    }
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                            clearDrag()
                            // Die Seite ganz zeigen, falls gerade geblättert wurde.
                            if (!pager.isScrollInProgress && pager.currentPageOffsetFraction != 0f) scope.launch { pager.animateScrollToPage(pager.targetPage) }
                        },
                        onDragCancel = { clearDrag() },
                    )
                }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).dashed(editing && !wiggle, c.muted)
                // Ordner beim Ablegen: Ring um das Ziel (auch ohne Bewegung zu sehen).
                .then(if (hover) Modifier.border(3.dp, c.accent, RoundedCornerShape(14.dp)) else Modifier)
                .then(interaction),
        ) { content() }
        if (editing) RemoveBadge(removeLabel, Modifier.align(Alignment.TopStart)) { remove() }
    }
}

/** × oben links (Entwurf 03); 32 dp Fläche zum Treffen, beschriftet für TalkBack. */
@Composable
private fun RemoveBadge(label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Titan.colors
    Box(
        modifier.offset(x = (-6).dp, y = (-8).dp).size(32.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(c.header).border(1.dp, c.border, CircleShape), contentAlignment = Alignment.Center) {
            Icon(TitanIcons.x, null, tint = c.text, modifier = Modifier.size(14.dp))
        }
    }
}

/** Das gezogene Symbol unter dem Finger: vergrößert, mit Schatten; nach dem Loslassen gleitet es in seine Zelle. */
@Composable
internal fun BoxScope.DragOverlay(grid: GridState, byKey: Map<String, Program>, folders: List<PhoneFolder>) {
    val landing = grid.landing
    val glide = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    androidx.compose.runtime.LaunchedEffect(landing) {
        if (landing == null) return@LaunchedEffect
        glide.snapTo(landing.from)
        glide.animateTo(landing.to, tween(180))
        grid.landing = null
    }
    val key = grid.dragKey ?: landing?.key ?: return
    val at = if (grid.dragKey != null) grid.pos else glide.value
    val density = LocalDensity.current
    val size = 64
    val half = with(density) { (size / 2).dp.toPx() }
    val shape = RoundedCornerShape(percent = 23)
    Box(
        Modifier.offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
            .graphicsLayer {
                scaleX = 1.2f
                scaleY = 1.2f
                alpha = .95f
            }
            .shadow(14.dp, shape),
    ) {
        when {
            key.startsWith("folder:") -> FolderIcon(folders.firstOrNull { "folder:" + it.id == key }, byKey, size)
            key.startsWith("widget:") -> Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(Titan.colors.surface).border(1.dp, Titan.colors.accent, RoundedCornerShape(14.dp)))
            else -> byKey[key]?.let { AppIcon(it.glyph, it.gradient, size.dp) }
        }
    }
}

/** Hält den Stand für die Zeiger-Routinen aktuell. */
@Composable
internal fun SyncGrid(grid: GridState, draft: PhonePrefs?, pages: List<List<String?>>, folderName: String, dock: DockDrop?, onChange: (PhonePrefs) -> Unit) {
    SideEffect {
        grid.draft = draft
        grid.pages = pages
        grid.folderName = folderName
        grid.change = onChange
        grid.dock = dock
    }
}

/** Widget im Raster: im Bearbeiten mit „halb“ und „ganz“ (Entwurf 03). */
@Composable
private fun WidgetView(w: PhoneWidget, state: HomeState, photos: String?, onPhotos: (() -> Unit)?, editing: Boolean, onResize: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        WidgetBody(w.kind, state, photos, if (editing) null else onPhotos)
        if (editing) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Chip(stringResource(R.string.home_half), on = w.size == "s") { onResize("s") }
                Chip(stringResource(R.string.home_full), on = w.size != "s") { onResize("l") }
            }
        }
    }
}
