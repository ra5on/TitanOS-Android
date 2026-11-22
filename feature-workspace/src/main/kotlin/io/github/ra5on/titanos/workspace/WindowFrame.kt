package io.github.ra5on.titanos.workspace

import android.content.res.Configuration
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlin.math.roundToInt

/** Höhe der Titelleiste eines Fensters. */
val TITLE_H = 38.dp

/**
 * Ein Fenster des Arbeitsplatzes (Window.svelte): Titelleiste mit Symbol,
 * Name und Knöpfen (Minimieren, Maximieren, Schließen), Ziehen am Titel
 * (an einen Rand: andocken mit Vorschau), Größe an allen Kanten, Doppelklick
 * maximiert, Rechtsklick öffnet das Fenstermenü. Ein Programm im Fenster
 * sieht die Größe des Fensters als seine Bildschirmgröße (Breitenstufen wie
 * im Web), bekommt eigene Zurück-Handler und eigene ViewModels.
 * Minimierte Fenster bleiben aufgebaut (Konsolen, Bildschirme, Uploads laufen weiter).
 */
@Composable
internal fun BoxScope.WindowFrame(state: WorkspaceState, win: Win, program: Program?, content: @Composable () -> Unit) {
    val wm = state.wm
    val density = LocalDensity.current
    val focused = wm.focused == win.id
    var live by remember(win.id) { mutableStateOf<Rect?>(null) }
    val current by rememberUpdatedState(win)
    val shown = live ?: wm.shownRect(win)
    val c = Titan.colors
    val shape = RoundedCornerShape(if (win.snap == Snap.Max) 0.dp else 12.dp)

    Box(
        Modifier
            .zIndex(win.z.toFloat())
            .offset { if (win.minimized) IntOffset(-40_000, 0) else IntOffset((shown.x * density.density).roundToInt(), (shown.y * density.density).roundToInt()) }
            .size(shown.w.dp, shown.h.dp)
            .then(if (win.minimized) Modifier.alpha(0f) else Modifier)
            .shadow(if (focused) 22.dp else 10.dp, shape, ambientColor = Color.Black.copy(alpha = .45f), spotColor = Color.Black.copy(alpha = .45f))
            .clip(shape)
            .background(c.bg)
            .border(1.dp, if (focused) c.accent.copy(alpha = .55f) else c.border, shape)
            // Ein Druck irgendwo ins Fenster holt es nach vorn (der Druck selbst geht weiter ans Programm).
            .pointerInput(win.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (wm.focused != current.id) wm.focus(current.id)
                }
            }
            .semantics { paneTitle = win.title },
    ) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(state, win, program, focused, onDragStart = { start ->
                wm.focus(win.id)
                wm.dragging = true
                val s = wm.shownRect(current)
                live = if (current.snap != null) {
                    // Aus dem Andocken heraus: die freie Größe, der Griff bleibt unter dem Zeiger.
                    val free = current.rect
                    val fx = (start.x / density.density) / s.w
                    Rect((s.x + start.x / density.density - fx * free.w).roundToInt(), s.y, free.w, free.h)
                } else {
                    s
                }
            }, onDrag = { change, delta ->
                val r = live ?: return@TitleBar
                val next = r.copy(x = r.x + (delta.x / density.density).roundToInt(), y = maxOf(0, r.y + (delta.y / density.density).roundToInt()))
                live = next
                wm.preview = wm.snapAt(next.x + (change.x / density.density).roundToInt(), next.y + (change.y / density.density).roundToInt())
            }, onDragEnd = {
                val target = wm.preview
                val r = live
                wm.preview = null
                wm.dragging = false
                live = null
                if (target != null) wm.setSnap(win.id, target) else if (r != null) { wm.move(win.id, r); wm.onChange() }
            })
            val cfg = LocalConfiguration.current
            val bodyH = maxOf(0, shown.h - TITLE_H.value.roundToInt())
            val winCfg = remember(cfg, shown.w, bodyH) {
                Configuration(cfg).apply {
                    screenWidthDp = shown.w
                    screenHeightDp = bodyH
                    smallestScreenWidthDp = minOf(shown.w, bodyH)
                    orientation = if (shown.w >= bodyH) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            val backOwner = remember(win.id, lifecycleOwner) { WindowBack(lifecycleOwner, state.back(win.id)) }
            val vmOwner = remember(win.id) { WindowViewModels(state.viewModels(win.id)) }
            CompositionLocalProvider(
                LocalConfiguration provides winCfg,
                LocalOnBackPressedDispatcherOwner provides backOwner,
                LocalViewModelStoreOwner provides vmOwner,
            ) {
                Box(Modifier.fillMaxWidth().weight(1f).consumeWindowInsets(WindowInsets.safeDrawing)) { content() }
            }
        }
        if (win.snap != Snap.Max && !win.minimized) ResizeHandles(state, win, onLive = { live = it })
    }
}

private class WindowBack(owner: androidx.lifecycle.LifecycleOwner, override val onBackPressedDispatcher: OnBackPressedDispatcher) :
    OnBackPressedDispatcherOwner, androidx.lifecycle.LifecycleOwner by owner

private class WindowViewModels(override val viewModelStore: ViewModelStore) : ViewModelStoreOwner

@Composable
private fun TitleBar(
    state: WorkspaceState,
    win: Win,
    program: Program?,
    focused: Boolean,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset, Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val wm = state.wm
    val c = Titan.colors
    val current by rememberUpdatedState(win)
    val density = LocalDensity.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = TITLE_H)
            .background(if (focused) c.header else c.surface)
            .pointerInput(win.id) {
                detectDragGestures(
                    onDragStart = onDragStart,
                    onDrag = { change, delta ->
                        change.consume()
                        onDrag(change.position, delta)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            }
            .pointerInput(win.id) { detectTapGestures(onDoubleTap = { wm.toggleMax(current.id) }) }
            .pointerInput(win.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (currentEvent.buttons.isSecondaryPressed) {
                        down.consume()
                        state.menu = DeskMenu(
                            (wm.shownRect(current).x + down.position.x / density.density),
                            (wm.shownRect(current).y + down.position.y / density.density),
                            DeskMenu.Kind.Window, current.id,
                        )
                    }
                }
            }
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (program != null && program.glyph.isNotEmpty()) AppIcon(program.glyph, program.gradient, 18.dp) else LetterIcon(program?.letter() ?: "?", program?.gradient ?: "grey", 18.dp)
        Text(
            win.title,
            style = TitanType.small.copy(fontWeight = FontWeight.SemiBold),
            color = if (focused) c.text else c.muted,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp).semantics { heading() },
        )
        Ctl(TitanIcons.down, t("desktop.window.minimize")) { wm.minimize(win.id) }
        Ctl(if (win.snap == Snap.Max) TitanIcons.grid else TitanIcons.box, t(if (win.snap == Snap.Max) "desktop.window.restore" else "desktop.window.maximize")) { wm.toggleMax(win.id) }
        Ctl(TitanIcons.x, t("desktop.window.close"), close = true) { state.requestClose(win.id) }
    }
}

@Composable
private fun Ctl(icon: ImageVector, label: String, close: Boolean = false, onClick: () -> Unit) {
    val c = Titan.colors
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (close) c.muted else c.muted, modifier = Modifier.size(16.dp))
    }
}

private enum class Edge(val l: Boolean = false, val t: Boolean = false, val r: Boolean = false, val b: Boolean = false) {
    L(l = true), R(r = true), T(t = true), B(b = true), TL(l = true, t = true), TR(t = true, r = true), BL(l = true, b = true), BR(r = true, b = true)
}

/** Griffe an allen Kanten und Ecken: Größe ändern wie im Web (mindestens 360 × 240). */
@Composable
private fun BoxScope.ResizeHandles(state: WorkspaceState, win: Win, onLive: (Rect?) -> Unit) {
    val wm = state.wm
    val density = LocalDensity.current
    val current by rememberUpdatedState(win)
    val grip = 6.dp
    val corner = 14.dp
    for (edge in Edge.entries) {
        var start by remember { mutableStateOf<Rect?>(null) }
        var acc by remember { mutableStateOf(Offset.Zero) }
        val icon = when (edge) {
            Edge.L, Edge.R -> PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
            Edge.T, Edge.B -> PointerIcon(android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW)
            Edge.TL, Edge.BR -> PointerIcon(android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW)
            Edge.TR, Edge.BL -> PointerIcon(android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW)
        }
        val m = when (edge) {
            Edge.L -> Modifier.align(Alignment.CenterStart).width(grip).fillMaxHeight().padding(vertical = corner)
            Edge.R -> Modifier.align(Alignment.CenterEnd).width(grip).fillMaxHeight().padding(vertical = corner)
            Edge.T -> Modifier.align(Alignment.TopCenter).heightIn(min = grip, max = grip).fillMaxWidth().padding(horizontal = corner)
            Edge.B -> Modifier.align(Alignment.BottomCenter).heightIn(min = grip, max = grip).fillMaxWidth().padding(horizontal = corner)
            Edge.TL -> Modifier.align(Alignment.TopStart).size(corner)
            Edge.TR -> Modifier.align(Alignment.TopEnd).size(corner)
            Edge.BL -> Modifier.align(Alignment.BottomStart).size(corner)
            Edge.BR -> Modifier.align(Alignment.BottomEnd).size(corner)
        }
        Box(
            m.pointerHoverIcon(icon).pointerInput(win.id, edge) {
                detectDragGestures(
                    onDragStart = {
                        wm.focus(current.id)
                        wm.dragging = true
                        start = wm.shownRect(current)
                        acc = Offset.Zero
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        acc += delta
                        val s = start ?: return@detectDragGestures
                        val dx = (acc.x / density.density).roundToInt()
                        val dy = (acc.y / density.density).roundToInt()
                        var x = s.x
                        var y = s.y
                        var w = s.w
                        var h = s.h
                        if (edge.r) w = maxOf(Wm.MIN_W, s.w + dx)
                        if (edge.b) h = maxOf(Wm.MIN_H, s.h + dy)
                        if (edge.l) { w = maxOf(Wm.MIN_W, s.w - dx); x = s.x + s.w - w }
                        if (edge.t) { h = maxOf(Wm.MIN_H, s.h - dy); y = maxOf(0, s.y + s.h - h) }
                        onLive(Rect(x, y, w, h))
                    },
                    onDragEnd = {
                        val s = start
                        wm.dragging = false
                        if (s != null) {
                            val dx = (acc.x / density.density).roundToInt()
                            val dy = (acc.y / density.density).roundToInt()
                            var x = s.x
                            var y = s.y
                            var w = s.w
                            var h = s.h
                            if (edge.r) w = maxOf(Wm.MIN_W, s.w + dx)
                            if (edge.b) h = maxOf(Wm.MIN_H, s.h + dy)
                            if (edge.l) { w = maxOf(Wm.MIN_W, s.w - dx); x = s.x + s.w - w }
                            if (edge.t) { h = maxOf(Wm.MIN_H, s.h - dy); y = maxOf(0, s.y + s.h - h) }
                            wm.move(current.id, Rect(x, y, w, h))
                            wm.onChange()
                        }
                        start = null
                        onLive(null)
                    },
                )
            },
        )
    }
}

/** Buchstabe statt Glyphe (Docker- und Web-Apps ohne eigenes Symbol). */
@Composable
fun LetterIcon(letter: String, gradient: String, size: androidx.compose.ui.unit.Dp) {
    Box(contentAlignment = Alignment.Center) {
        AppIcon("", gradient, size)
        Text(letter, style = TitanType.small.copy(fontWeight = FontWeight.Bold, fontSize = TitanType.small.fontSize * (size.value / 24f).coerceIn(.6f, 2.2f)), color = Color.White)
    }
}
