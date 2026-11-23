package io.github.ra5on.titanos.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** Platz, den das Dock am Rand freihält (dockRoom im Web: Größe + 46). */
fun dockRoom(dock: Dock): Int = if (dock.show) dock.size + 46 else 0

/**
 * Das Dock (Dock.svelte): Menüknopf, behaltene Programme, danach offene
 * Programme ohne Platz im Dock. Ein Punkt unter dem Symbol zeigt offene
 * Fenster (mit Namen für TalkBack, nie nur als Farbe). Klick öffnet,
 * holt nach vorn oder minimiert; Rechtsklick öffnet das Menü des Programms
 * oder des Docks. Unter der Maus wachsen die Symbole (Einstellung „Vergrößern“).
 */
@Composable
internal fun BoxScope.DockBar(state: WorkspaceState) {
    val prefs = state.store.prefs
    val dock = prefs.dock
    if (!dock.show) return
    val wm = state.wm
    val pinned = dockPinned(prefs, state.known)
    val running = wm.windows.map { it.app }.distinct().filter { it !in pinned }
    val vertical = dock.position != "bottom"
    val density = LocalDensity.current
    // Passen nicht alle Symbole an den Rand (Handy, schmales Fenster), werden sie kleiner statt abgeschnitten.
    val count = pinned.size + running.size + 1
    val edge = if (vertical) state.wm.area.h else state.wm.area.w
    val iconDp = minOf(dock.size, maxOf(28, (edge - 52) / count - 6 - (if (running.isNotEmpty() && pinned.isNotEmpty()) 2 else 0)))
    var pointer by remember { mutableStateOf<Float?>(null) }
    val centres = remember { HashMap<String, Float>() }
    val plate = Titan.colors.surface.copy(alpha = dock.opacity / 100f)
    val shape = RoundedCornerShape((iconDp * 0.36f + 9).dp)
    val align = when (dock.position) {
        "left" -> Alignment.CenterStart
        "right" -> Alignment.CenterEnd
        else -> Alignment.BottomCenter
    }
    val k = if (dock.magnify == 2) 0.75f else 0.38f
    val reach = iconDp * 2.4f
    fun scaleOf(key: String): Float {
        val p = pointer ?: return 1f
        if (dock.magnify == 0 || state.editDock) return 1f
        val c = centres[key] ?: return 1f
        val f = maxOf(0f, 1f - abs(p - c) / reach)
        return 1f + k * sin(f * PI.toFloat() / 2).pow(2)
    }
    val label = t("desktop.dock.title")
    val menuLabel = t("desktop.dock.menu")
    val content: @Composable () -> Unit = {
        DockButton(state, "launcher", vertical, 1f, { _ -> }) {
            Box(
                Modifier.size(iconDp.dp).clip(RoundedCornerShape(percent = 23)).background(Titan.colors.chip)
                    .clickable(role = Role.Button) { state.launcher = !state.launcher }
                    .semantics { contentDescription = menuLabel },
                contentAlignment = Alignment.Center,
            ) { Icon(TitanIcons.grid, null, tint = Titan.colors.text, modifier = Modifier.size((iconDp * .48f).dp)) }
        }
        for (key in pinned + running) {
            val p = state.programOf(key)
            if (key == running.firstOrNull() && pinned.isNotEmpty()) {
                Box(
                    if (vertical) Modifier.padding(vertical = 4.dp).height(1.dp).width(iconDp.dp * .7f).background(Titan.colors.border)
                    else Modifier.padding(horizontal = 4.dp).width(1.dp).height(iconDp.dp * .7f).background(Titan.colors.border),
                )
            }
            val windows = wm.windows.filter { it.app == key }
            DockButton(state, key, vertical, scaleOf(key), { centre -> centres[key] = centre }) {
                var focused by remember(key) { mutableStateOf(false) }
                Box(
                    Modifier.size(iconDp.dp)
                        // Tastatur: Rahmen in der Akzentfarbe, solange das Symbol den Fokus hat.
                        .border(2.dp, if (focused) Titan.colors.accent else Color.Transparent, RoundedCornerShape(12.dp))
                        .pointerInput(key) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (currentEvent.buttons.isSecondaryPressed) {
                                    down.consume()
                                    state.menu = DeskMenu(0f, 0f, DeskMenu.Kind.Icon, key, dock = true).placed(state, key)
                                }
                            }
                        }
                        .pointerInput(key) {
                            detectTapGestures(
                                onLongPress = { state.menu = DeskMenu(0f, 0f, DeskMenu.Kind.Icon, key, dock = true).placed(state, key) },
                                onTap = { dockClick(state, key) },
                            )
                        }
                        // TalkBack und Tastatur (Tab, Enter) wie ein Knopf.
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = p?.label ?: key
                            if (windows.isNotEmpty()) stateDescription = "${windows.size} ●"
                            onClick { dockClick(state, key); true }
                            onLongClick { state.menu = DeskMenu(0f, 0f, DeskMenu.Kind.Icon, key, dock = true).placed(state, key); true }
                        }
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar)) {
                                dockClick(state, key)
                                true
                            } else {
                                false
                            }
                        }
                        .onFocusChanged { focused = it.isFocused }
                        .focusable(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (p != null && p.glyph.isNotEmpty()) AppIcon(p.glyph, p.gradient, iconDp.dp) else LetterIcon(p?.letter() ?: "?", p?.gradient ?: "blue", iconDp.dp)
                }
                // Offene Fenster: ein Punkt darunter (bzw. daneben).
                Box(Modifier.padding(2.dp).size(5.dp).clip(CircleShape).background(if (windows.isNotEmpty()) Titan.colors.text.copy(alpha = .85f) else Color.Transparent))
            }
        }
    }
    val base = Modifier
        .align(align)
        .zIndex(100_000f)
        .padding(10.dp)
        .glass(plate, shape, alpha = 1f, border = if (dock.border) Color.White.copy(alpha = .12f) else null)
        .pointerInput(vertical) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull() ?: continue
                    pointer = when {
                        e.type == PointerEventType.Exit || ch.type == PointerType.Touch -> null
                        else -> (if (vertical) ch.position.y else ch.position.x) / density.density
                    }
                }
            }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Final)
                if (currentEvent.buttons.isSecondaryPressed) {
                    state.menu = DeskMenu(0f, 0f, DeskMenu.Kind.Dock).let { m ->
                        val area = state.wm.area
                        when (dock.position) {
                            "left" -> m.copy(x = dockRoom(dock).toFloat(), y = down.position.y / density.density)
                            "right" -> m.copy(x = (area.w - dockRoom(dock) - 260).toFloat(), y = down.position.y / density.density)
                            else -> m.copy(x = down.position.x / density.density, y = (area.h - dockRoom(dock) - 240).toFloat())
                        }
                    }
                }
            }
        }
        .semantics { contentDescription = label }
        .padding(8.dp)
    if (vertical) {
        Column(base.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(base.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) { content() }
    }
}

@Composable
private fun DockButton(state: WorkspaceState, key: String, vertical: Boolean, scale: Float, centre: (Float) -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val m = Modifier
        .onGloballyPositioned { c ->
            val b = c.boundsInParent()
            centre((if (vertical) b.center.y else b.center.x) / density.density)
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            transformOrigin = if (vertical) androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f) else androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        }
    if (vertical) Row(m, verticalAlignment = Alignment.CenterVertically) { content() }
    else Column(m, horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

/** Klick im Dock wie im Web: öffnen, nach vorn holen oder (schon vorn) minimieren. */
internal fun dockClick(state: WorkspaceState, key: String) {
    if (state.editDock) return
    val wm = state.wm
    val list = wm.windows.filter { it.app == key }
    if (list.isEmpty()) return state.open(key)
    val front = list.firstOrNull { it.id == wm.focused && !it.minimized }
    if (front != null) list.forEach { wm.minimize(it.id) } else list.sortedBy { it.z }.forEach { wm.focus(it.id) }
}

/** Ein Kontextmenü am Dock setzt sich neben das Dock (die Lage des Symbols kennt das Dock nicht genau). */
private fun DeskMenu.placed(state: WorkspaceState, key: String): DeskMenu {
    val dock = state.store.prefs.dock
    val area = state.wm.area
    return when (dock.position) {
        "left" -> copy(x = dockRoom(dock).toFloat(), y = area.h / 3f)
        "right" -> copy(x = (area.w - dockRoom(dock) - 280).toFloat(), y = area.h / 3f)
        else -> copy(x = area.w / 2f - 140, y = (area.h - dockRoom(dock) - 260).toFloat())
    }.copy(key = key)
}
