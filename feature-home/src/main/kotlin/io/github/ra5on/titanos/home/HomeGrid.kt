package io.github.ra5on.titanos.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
 * Teilt eine Seite in Zeilen: Programme und Ordner füllen Zeilen zu [columns],
 * ein Widget „ganz“ hat eine Zeile für sich, zwei „halbe“ teilen sich eine
 * (bei weniger als drei Spalten – große Schrift – steht jedes Widget allein).
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

/**
 * Wo was beim Ziehen liegt. Die Lage jedes Eintrags merkt sich die Startseite
 * selbst (in Koordinaten ihrer Fläche); daraus folgt beim Loslassen, ob das
 * Symbol auf ein anderes fällt (Ordner) oder dazwischen (Verschieben).
 */
@Stable
internal class GridState {
    var root: LayoutCoordinates? = null
    val items = HashMap<String, Pair<Int, LayoutCoordinates>>()
    var dragKey by mutableStateOf<String?>(null)
    var pos by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)
    var hover by mutableStateOf<String?>(null)
    var edgeJob: Job? = null

    // Der neueste Stand (die Zeiger-Routinen laufen länger als eine Komposition).
    var draft: PhonePrefs? = null
    var change: (PhonePrefs) -> Unit = {}
    var pages: List<List<String>> = emptyList()
    var folderName: String = "Ordner"

    fun rectOf(key: String): Rect? {
        val r = root ?: return null
        val c = items[key]?.second ?: return null
        if (!c.isAttached || !r.isAttached) return null
        return r.localBoundingBoxOf(c)
    }

    /** Wohin [dragKey] beim Loslassen an [pos] auf Seite [page] fällt: auf einen Eintrag (Ordner) oder vor/hinter ihn. */
    fun targetAt(page: Int): Pair<String, Boolean>? {
        val key = dragKey ?: return null
        val list = pages.getOrNull(page).orEmpty().filter { it != key && items[it]?.first == page }
        for (k in list) {
            val r = rectOf(k) ?: continue
            val inner = Rect(r.left + r.width * .22f, r.top + r.height * .1f, r.right - r.width * .22f, r.top + r.height * .62f)
            val folderable = !key.startsWith("folder:") && !key.startsWith("widget:") && !k.startsWith("widget:")
            if (folderable && inner.contains(pos)) return k to true
        }
        return null
    }

    /** Der Eintrag, vor dem eingefügt wird (in Lesereihenfolge), sonst null (ans Ende). */
    fun insertBefore(page: Int): String? {
        val key = dragKey ?: return null
        val list = pages.getOrNull(page).orEmpty().filter { it != key }
        for (k in list) {
            val r = rectOf(k) ?: continue
            if (pos.y < r.top || (pos.y <= r.bottom && pos.x < r.center.x)) return k
        }
        return null
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PageContent(
    page: List<String>,
    index: Int,
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
    // Ein weiches Trennzeichen („System\u00ADsteuerung“) ist eine erlaubte Trennstelle: dort zählt jedes Teilstück als Wort.
    val labels = page.filter { !it.startsWith("widget:") }.flatMap { io.github.ra5on.titanos.ui.hyphenPieces(labelOf(it)) }
    val widest = rememberWidestWord(labels, TitanType.appLabel)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().longPressFree(!editing, onLongPress)) {
        val gap = 6.dp
        val inner = maxWidth - 32.dp
        val columns = programColumns(with(density) { inner.toPx() }, with(density) { gap.toPx() }, widest.toFloat(), if (maxWidth > 600.dp) 6 else 4)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (columns == 1) 6.dp else 18.dp),
        ) {
            for (row in gridRows(page, prefs, columns)) {
                when (row) {
                    is GridRow.Widgets -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (key in row.keys) {
                            val w = prefs.widgets.firstOrNull { "widget:" + it.id == key } ?: continue
                            HomeEntry(key, index, labelOf(key), editing, wiggle, grid, onSheet, onChange, draft, pager, scope, pageCount, Modifier.weight(1f), onOpen = { onWidget(w.kind) }, onLongPress = onLongPress) {
                                WidgetView(w, state, photos, onPhotos, editing, onResize = { size -> draft?.let { onChange(resizeWidget(it, w.id, size)) } })
                            }
                        }
                        if (row.keys.size == 1 && columns >= 3 && cellsOf(row.keys[0], prefs) == 4) Spacer(Modifier.weight(1f))
                    }
                    is GridRow.Icons -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (key in row.keys) {
                            val label = labelOf(key)
                            HomeEntry(key, index, label, editing, wiggle, grid, onSheet, onChange, draft, pager, scope, pageCount, Modifier.weight(1f), onOpen = { onOpen(key) }, onLongPress = onLongPress) {
                                IconView(key, label, byKey, folderById, wide = columns == 1)
                            }
                        }
                        repeat(columns - row.keys.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            // Freie Fläche unten: langes Drücken beginnt auch dort das Bearbeiten, auf einer leeren Seite ebenso.
            Spacer(Modifier.fillMaxWidth().height(if (page.isEmpty()) 240.dp else 48.dp))
        }
    }
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

/**
 * Ein Eintrag der Startseite: öffnet beim Tippen, beginnt nach langem Drücken
 * das Bearbeiten. Im Bearbeiten: Wackeln (oder gestrichelt), × oben links,
 * Tippen öffnet „Bewegen …“, Ziehen verschiebt (auch an den Rand: Seite davor
 * bzw. danach), auf ein anderes Symbol ergibt einen Ordner. TalkBack bekommt
 * dieselben Aktionen ohne Ziehen.
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
    modifier: Modifier,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = Titan.colors
    val dragging = grid.dragKey == key
    val hover = grid.hover == key
    val angle = wiggleAngle(editing && wiggle && !dragging, key.hashCode())
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
    Box(
        modifier
            .onGloballyPositioned { grid.items[key] = page to it }
            .graphicsLayer {
                alpha = if (dragging) .3f else 1f
                rotationZ = angle
                val s = if (hover) 1.12f else 1f
                scaleX = s
                scaleY = s
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
                        CustomAccessibilityAction(earlier) { grid.draft?.let { onChange(moveBy(it, key, -1)) }; true },
                        CustomAccessibilityAction(later) { grid.draft?.let { onChange(moveBy(it, key, +1)) }; true },
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
                            }
                        },
                        onDrag = { change, amount ->
                            if (grid.dragKey != key) return@detectDragGestures
                            change.consume()
                            grid.pos += amount
                            grid.hover = grid.targetAt(pager.targetPage)?.first
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
                            grid.edgeJob?.cancel()
                            val d = grid.draft
                            val dragged = grid.dragKey
                            if (d != null && dragged == key) {
                                val at = pager.targetPage
                                val target = grid.targetAt(at)
                                val next = when {
                                    target != null && target.second -> combine(d, key, target.first, grid.folderName, "f" + System.currentTimeMillis().toString(36))
                                    else -> grid.insertBefore(at)?.let { moveNextTo(d, key, it) }
                                        ?: grid.pages.getOrNull(at).orEmpty().lastOrNull { it != key }?.let { moveNextTo(d, key, it, after = true) }
                                        ?: moveTo(d, key, at, 0)
                                }
                                grid.change(next)
                            }
                            grid.dragKey = null
                            grid.hover = null
                            // Die Seite ganz zeigen, falls gerade geblättert wurde.
                            if (!pager.isScrollInProgress && pager.currentPageOffsetFraction != 0f) scope.launch { pager.animateScrollToPage(pager.targetPage) }
                        },
                        onDragCancel = {
                            grid.edgeJob?.cancel()
                            grid.dragKey = null
                            grid.hover = null
                        },
                    )
                }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).dashed(editing && !wiggle, c.muted)
                .then(if (hover) Modifier.border(2.dp, c.accent, RoundedCornerShape(14.dp)) else Modifier)
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

/** Das gezogene Symbol unter dem Finger. */
@Composable
internal fun BoxScope.DragOverlay(grid: GridState, byKey: Map<String, Program>, folders: List<PhoneFolder>) {
    val key = grid.dragKey ?: return
    val density = LocalDensity.current
    val size = 64
    val half = with(density) { (size / 2).dp.toPx() }
    Box(
        Modifier.offset { IntOffset((grid.pos.x - half).roundToInt(), (grid.pos.y - half).roundToInt()) }
            .graphicsLayer {
                scaleX = 1.1f
                scaleY = 1.1f
                alpha = .92f
            },
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
internal fun SyncGrid(grid: GridState, draft: PhonePrefs?, pages: List<List<String>>, folderName: String, onChange: (PhonePrefs) -> Unit) {
    SideEffect {
        grid.draft = draft
        grid.pages = pages
        grid.folderName = folderName
        grid.change = onChange
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
