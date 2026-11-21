package io.github.ra5on.titanos.nvr

import android.app.Activity as AndroidActivity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Was die Live-Ansicht gerade tut: anordnen, eine Kamera groß, Vollbild. */
@Stable
class LiveUi {
    var arranging by mutableStateOf(false)
    var draft by mutableStateOf<List<Tile>?>(null)
    var order by mutableStateOf<List<String>?>(null)
    var solo by mutableStateOf("")
    var soloPtz by mutableStateOf(false)
    var full by mutableStateOf(false)
    var active by mutableStateOf<Tile?>(null)
}

@Composable
fun LivePage(model: NvrModel, admin: Boolean, nav: NvrNav) {
    val ui = LocalComputeUi.current
    val scope = rememberCoroutineScope()
    val feed = remember(model.api) { EventFeed(model.api, scope) }
    LifecycleResumeEffect(feed) {
        feed.start()
        onPauseOrDispose { feed.stop() }
    }
    var prefs by remember { mutableStateOf(NvrPrefs()) }
    LaunchedEffect(Unit) { prefs = runCatching { sanitizeNvrPrefs(model.api.livePrefs()) }.getOrDefault(NvrPrefs()) }
    var saving by remember { mutableStateOf<Job?>(null) }
    val live = remember { LiveUi() }
    LiveScreen(
        cameras = model.cameras.filter { it.mayLive(admin) },
        totalCameras = model.cameras.size,
        admin = admin,
        anyRecordings = model.cameras.any { it.mayRecordings(admin) },
        running = model.running,
        prefs = prefs,
        activity = feed.activity,
        events = feed.events,
        eventsAvailable = feed.available,
        nameOf = model::cameraName,
        api = model.api,
        live = live,
        onPrefs = { change ->
            prefs = sanitizeNvrPrefs(change(prefs).toJson())
            val next = prefs
            saving?.cancel()
            saving = scope.launch {
                delay(600)
                ui.run { model.api.saveLivePrefs(next.toJson()) }
            }
        },
        onOpenEvent = { nav.openEvent(it) },
        onAllEvents = { nav.go("events") },
        onAddCamera = { nav.addCamera() },
        onRecordings = { nav.go("recordings") },
    )
}

private fun Context.activity(): AndroidActivity? = when (this) {
    is AndroidActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Die Live-Ansicht (routes/nvr/+page.svelte): jede Kamera liegt, wo das Konto
 * sie hingelegt hat; am Handy (schmaler als 560 dp) stehen die Kacheln
 * untereinander in eigener Reihenfolge. Eine Kamera groß mit Zwei-Finger-
 * Zoom und Wischen zur nächsten; Ereignisse neben den Bildern als Wahl.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveScreen(
    cameras: List<Camera>,
    totalCameras: Int,
    admin: Boolean,
    anyRecordings: Boolean,
    running: Boolean,
    prefs: NvrPrefs,
    activity: Map<String, Activity>,
    events: List<NvrEvent>,
    eventsAvailable: Boolean,
    nameOf: (String) -> String,
    api: NvrApi?,
    live: LiveUi,
    onPrefs: ((NvrPrefs) -> NvrPrefs) -> Unit,
    onOpenEvent: (String) -> Unit,
    onAllEvents: () -> Unit,
    onAddCamera: () -> Unit,
    onRecordings: () -> Unit,
) {
    val ui = LocalComputeUi.current
    val ids = cameras.map { it.id }
    val byId = cameras.associateBy { it.id }
    if (cameras.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                admin && totalCameras == 0 -> {
                    EmptyState(t("nvr.live.noneTitle"), t("nvr.live.noneAdmin"))
                    TitanButton(t("nvr.add.title"), onAddCamera, kind = ButtonKind.Primary, icon = TitanIcons.plus)
                }
                !admin && anyRecordings -> {
                    EmptyState(t("nvr.live.noneTitle"), t("nvr.live.onlyRecordings"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TitanButton(t("nvr.menu.recordings"), onRecordings, kind = ButtonKind.Primary)
                        TitanButton(t("nvr.menu.events"), onAllEvents)
                    }
                }
                else -> EmptyState(t("nvr.live.noneTitle"), if (admin) t("nvr.live.noneAdmin") else t("nvr.live.noneUser"))
            }
        }
        return
    }
    if (live.solo.isNotEmpty() && live.solo !in byId) live.solo = ""
    BackHandler(enabled = live.solo.isNotEmpty() && !live.full) { live.solo = "" }
    BackHandler(enabled = live.full) { live.full = false }
    // Vollbild: die Leisten des Systems verschwinden (und die Leiste über den Bildern, wenn gewählt).
    val view = LocalView.current
    DisposableEffect(live.full) {
        val window = view.context.activity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (live.full) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (live.full) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val showEvents = prefs.events && anyRecordings && !live.arranging
    val prefsNow = androidx.compose.runtime.rememberUpdatedState(prefs)
    BoxWithConstraints(
        Modifier.fillMaxSize().onKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                when {
                    live.full -> live.full = false
                    live.solo.isNotEmpty() -> live.solo = ""
                    else -> return@onKeyEvent false
                }
                true
            } else false
        },
    ) {
        val wide = maxWidth >= 760.dp
        val stacked = (if (showEvents && wide) maxWidth - 336.dp else maxWidth) < 560.dp
        Column(Modifier.fillMaxSize()) {
            if (!(live.full && prefs.hideBar)) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (live.arranging) {
                        Text(if (stacked) t("nvr.arrange.hintPhone") else t("nvr.arrange.hint"), style = TitanType.rowSub, color = Titan.colors.muted, modifier = Modifier.weight(1f))
                        TitanButton(t("nvr.arrange.reset"), {
                            live.draft = gridTiles(ids)
                            live.order = ids
                        })
                        TitanButton(t("nvr.arrange.done"), {
                            val next = live.draft
                            val nextOrder = live.order
                            live.arranging = false
                            live.draft = null
                            live.order = null
                            live.active = null
                            onPrefs { p -> if (stacked) p.copy(phoneOrder = nextOrder ?: p.phoneOrder) else if (next != null) p.copy(tiles = next, custom = true) else p }
                        }, kind = ButtonKind.Primary, icon = TitanIcons.check)
                    } else {
                        if (anyRecordings) {
                            val onWord = if (prefs.events) t("nvr.view.events") else ""
                            TitanButton(t("nvr.menu.events"), { onPrefs { it.copy(events = !it.events) } }, Modifier.semantics { stateDescription = onWord }, kind = if (prefs.events) ButtonKind.Primary else ButtonKind.Normal, icon = NvrIcons.clock)
                        }
                        TitanButton(t("nvr.arrange.start"), {
                            live.solo = ""
                            live.arranging = true
                            live.draft = tilesFor(prefs, ids)
                            live.order = phoneOrderFor(prefs, ids)
                        }, icon = TitanIcons.edit)
                        TitanButton(if (live.full) t("nvr.live.fullOff") else t("nvr.live.full"), { live.full = !live.full }, icon = NvrIcons.maximize)
                        TitanButton(t("nvr.view.title"), {
                            ui.sheet = { ViewSheet(prefsNow, anyRecordings, onPrefs, { ui.sheet = null; reset(live, ids, onPrefs) }) }
                        }, icon = NvrIcons.eye)
                    }
                }
            }
            val wall: @Composable (Modifier) -> Unit = { m ->
                Wall(cameras, byId, ids, running, prefs, activity, api, live, stacked, m)
            }
            val aside: @Composable (Modifier) -> Unit = { m ->
                EventsAside(events, eventsAvailable, nameOf, onOpenEvent, onAllEvents, m)
            }
            if (showEvents && wide) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    wall(Modifier.weight(1f).fillMaxHeight())
                    aside(Modifier.width(320.dp).fillMaxHeight().padding(end = 16.dp))
                }
            } else if (showEvents && live.solo.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    wall(Modifier.fillMaxWidth())
                    aside(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
            } else {
                wall(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

private fun reset(live: LiveUi, ids: List<String>, onPrefs: ((NvrPrefs) -> NvrPrefs) -> Unit) {
    if (live.arranging) {
        live.draft = gridTiles(ids)
        live.order = ids
    } else {
        onPrefs { it.copy(tiles = emptyList(), custom = false, phoneOrder = emptyList()) }
    }
}

@Composable
private fun ViewSheet(prefs: androidx.compose.runtime.State<NvrPrefs>, anyRecordings: Boolean, onPrefs: ((NvrPrefs) -> NvrPrefs) -> Unit, onReset: () -> Unit) {
    // Schalter wirken sofort; der Stand kommt aus der Seite.
    val cur = prefs.value
    Text(t("nvr.view.title"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(4.dp).semantics { heading() })
    fun set(change: (NvrPrefs) -> NvrPrefs) = onPrefs(change)
    if (anyRecordings) SwitchRow(t("nvr.view.events"), cur.events, { v -> set { it.copy(events = v) } })
    SwitchRow(t("nvr.view.names"), cur.names, { v -> set { it.copy(names = v) } }, divider = true)
    SwitchRow(t("nvr.view.marks"), cur.marks, { v -> set { it.copy(marks = v) } }, divider = true)
    SwitchRow(t("nvr.view.recDot"), cur.recDot, { v -> set { it.copy(recDot = v) } }, divider = true)
    SwitchRow(t("nvr.view.hideBar"), cur.hideBar, { v -> set { it.copy(hideBar = v) } }, divider = true)
    TitanButton(t("nvr.view.reset"), onReset, Modifier.padding(top = 8.dp).fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Wall(
    cameras: List<Camera>,
    byId: Map<String, Camera>,
    ids: List<String>,
    running: Boolean,
    prefs: NvrPrefs,
    activity: Map<String, Activity>,
    api: NvrApi?,
    live: LiveUi,
    stacked: Boolean,
    modifier: Modifier,
) {
    val solo = byId[live.solo]
    if (solo != null) {
        SoloView(solo, cameras, ids, prefs, running, activity, api, live, stacked, modifier)
        return
    }
    if (stacked) {
        val stack = live.order ?: phoneOrderFor(prefs, ids)
        Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stack.forEachIndexed { i, id ->
                val camera = byId[id] ?: return@forEachIndexed
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiveTile(
                        camera, running, prefs, activity[id], api, Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        arranging = live.arranging, small = true,
                        onSolo = { live.soloPtz = false; live.solo = id },
                        onPtz = { live.soloPtz = true; live.solo = id },
                    )
                    if (live.arranging) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val move = { by: Int ->
                            val list = (live.order ?: stack).toMutableList()
                            val to = i + by
                            if (to in list.indices) {
                                val x = list[i]; list[i] = list[to]; list[to] = x
                                live.order = list
                            }
                        }
                        TitanButton(t("nvr.arrange.up", "name" to camera.name), { move(-1) }, enabled = i > 0, icon = NvrIcons.up)
                        TitanButton(t("nvr.arrange.down", "name" to camera.name), { move(1) }, enabled = i < stack.size - 1, icon = NvrIcons.downArrow)
                    }
                }
            }
        }
        return
    }
    BoxWithConstraints(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        val cell = maxWidth / LIVE_COLS
        val density = LocalDensity.current
        val cellPx = with(density) { cell.toPx() }
        val tiles = live.draft ?: tilesFor(prefs, ids)
        val gap = 6.dp
        Box(Modifier.fillMaxWidth().height(cell * layoutRows(tiles))) {
            for (tile in tiles) {
                val camera = byId[tile.id] ?: continue
                val tileMod = Modifier.offset(cell * tile.x, cell * tile.y).size(cell * tile.w - gap, cell * tile.h - gap)
                val arrangeMod = if (!live.arranging) Modifier else Modifier
                    .border(2.dp, if (live.active?.id == tile.id) Titan.colors.accent else Titan.colors.border, RoundedCornerShape(12.dp))
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val by = when (e.key) {
                            Key.DirectionLeft -> -1 to 0
                            Key.DirectionRight -> 1 to 0
                            Key.DirectionUp -> 0 to -1
                            Key.DirectionDown -> 0 to 1
                            else -> return@onKeyEvent false
                        }
                        val d = live.draft ?: return@onKeyEvent false
                        val next = dragTile(tile, e.isShiftPressed, by.first, by.second)
                        live.draft = settle(d.map { if (it.id == tile.id) next else it }, tile.id)
                        true
                    }
                    .pointerInput(tile.id, cellPx) { dragArrange(tile.id, false, cellPx, live) }
                Box(tileMod.then(arrangeMod)) {
                    LiveTile(
                        camera, running, prefs, activity[tile.id], api, Modifier.fillMaxSize(),
                        arranging = live.arranging, small = cell * tile.w < 520.dp,
                        onSolo = { live.soloPtz = false; live.solo = tile.id },
                        onPtz = { live.soloPtz = true; live.solo = tile.id },
                    )
                    if (live.arranging) {
                        val a = live.active
                        if (a != null && a.id == tile.id) {
                            Badge(
                                "${(cell * a.w - gap).value.roundToInt()} × ${(cell * a.h - gap).value.roundToInt()}",
                                Modifier.align(Alignment.Center),
                            )
                        }
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(28.dp).clip(RoundedCornerShape(topStart = 10.dp)).background(Titan.colors.accent)
                                .pointerInput(tile.id, cellPx) { dragArrange(tile.id, true, cellPx, live) },
                        )
                    }
                }
            }
        }
    }
}

/** Ziehen beim Anordnen: ganze Zellen; nach dem Loslassen rutscht Verdecktes nach unten. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.dragArrange(id: String, size: Boolean, cellPx: Float, live: LiveUi) {
    var start: Tile? = null
    var total = Offset.Zero
    detectDragGestures(
        onDragStart = {
            start = live.draft?.firstOrNull { it.id == id }
            total = Offset.Zero
            live.active = start
        },
        onDrag = { change, amount ->
            change.consume()
            val s = start ?: return@detectDragGestures
            total += amount
            val next = dragTile(s, size, (total.x / cellPx).roundToInt(), (total.y / cellPx).roundToInt())
            live.draft = live.draft?.map { if (it.id == id) next else it }
            live.active = next
        },
        onDragEnd = {
            live.active = null
            live.draft = live.draft?.let { settle(it, id) }
        },
        onDragCancel = { live.active = null },
    )
}

/** Eine Kamera groß: zwei Finger zoomen das Bild, Wischen zur Seite zeigt die nächste Kamera. */
@Composable
private fun SoloView(
    camera: Camera,
    cameras: List<Camera>,
    ids: List<String>,
    prefs: NvrPrefs,
    running: Boolean,
    activity: Map<String, Activity>,
    api: NvrApi?,
    live: LiveUi,
    stacked: Boolean,
    modifier: Modifier,
) {
    var scale by remember(camera.id) { mutableFloatStateOf(1f) }
    var pan by remember(camera.id) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val swipe = with(density) { 60.dp.toPx() }
    val list = (if (stacked) phoneOrderFor(prefs, ids) else ids).filter { id -> cameras.any { it.id == id } }
    Box(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).pointerInput(camera.id, list) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var moved = Offset.Zero
                var fingers = 1
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    fingers = maxOf(fingers, pressed.size)
                    if (pressed.size >= 2) {
                        val a = pressed[0]
                        val b = pressed[1]
                        val before = (a.previousPosition - b.previousPosition).getDistance()
                        val after = (a.position - b.position).getDistance()
                        if (before > 0f) scale = (scale * after / before).coerceIn(1f, 6f)
                        if (scale == 1f) pan = Offset.Zero
                        event.changes.forEach { it.consume() }
                    } else if (pressed.size == 1) {
                        val d = pressed[0].positionChange()
                        if (scale > 1f) {
                            pan += d
                            pressed[0].consume()
                        } else {
                            moved += d
                        }
                    }
                } while (event.changes.any { it.pressed })
                if (fingers == 1 && scale == 1f && abs(moved.x) >= swipe && abs(moved.x) >= abs(moved.y) * 1.5f && list.size > 1) {
                    val at = list.indexOf(live.solo)
                    if (at >= 0) {
                        live.soloPtz = false
                        live.solo = list[(at + (if (moved.x < 0) 1 else -1) + list.size) % list.size]
                    }
                }
            }
        },
    ) {
        LiveTile(
            camera, running, prefs, activity[camera.id], api,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = pan.x
                translationY = pan.y
            },
            solo = true,
            ptzStart = live.soloPtz,
            onSolo = { live.solo = "" },
        )
    }
}

@Composable
private fun EventsAside(events: List<NvrEvent>, available: Boolean, nameOf: (String) -> String, onOpen: (String) -> Unit, onAll: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t("nvr.menu.events"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(4.dp).semantics { heading() })
        when {
            !available -> Text(t("nvr.events.unavailable"), style = TitanType.body, color = Titan.colors.muted)
            events.isEmpty() -> Text(t("nvr.events.none"), style = TitanType.body, color = Titan.colors.muted)
            else -> {
                for (e in events.take(8)) EventCard(e, nameOf(e.camera), { onOpen(e.id) })
                TitanButton(t("nvr.events.all"), onAll, Modifier.fillMaxWidth())
            }
        }
    }
}
