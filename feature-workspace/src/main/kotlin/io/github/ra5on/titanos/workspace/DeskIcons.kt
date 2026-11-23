package io.github.ra5on.titanos.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlin.math.roundToInt

/** Größe der Symbole je Einstellung (sizes im Web: 48/60/72 px). */
fun iconPx(size: String): Int = when (size) {
    "s" -> 48
    "l" -> 72
    else -> 60
}

private const val ICON_LEFT = 18
private const val ICON_TOP = 8

/** Das Raster der Symbole (Desktop.svelte): Spaltenbreite, Zeilenhöhe, Anzahl. */
data class IconGrid(val colW: Int, val rowH: Int, val rows: Int, val cols: Int, val left: Int) {
    fun cellAt(x: Float, y: Float) = Cell(
        ((x - left) / colW).toInt().coerceIn(0, cols - 1),
        ((y - ICON_TOP) / rowH).toInt().coerceIn(0, rows - 1),
    )
}

fun iconGrid(prefs: DesktopPrefs, area: Rect, inset: Wm.Inset): IconGrid {
    val px = iconPx(prefs.iconSize)
    val colW = px + 60
    val rowRoom = maxOf(1, area.h - ICON_TOP - 8 - inset.b)
    val rows = maxOf(1, rowRoom / (px + 56))
    val rowH = maxOf(px + 56, rowRoom / rows)
    val cols = maxOf(1, (area.w - ICON_LEFT - inset.l - inset.r) / colW)
    return IconGrid(colW, rowH, rows, cols, ICON_LEFT + inset.l)
}

/**
 * Die Symbole auf dem Arbeitsplatz (DesktopIcons.svelte): ein Klick öffnet,
 * Rechtsklick oder langes Drücken öffnet das Menü, Ziehen legt das Symbol
 * auf einen freien Platz („frei“), in einen Ordner oder ins Dock.
 */
@Composable
internal fun BoxScope.DeskIcons(state: WorkspaceState) {
    val prefs = state.store.prefs
    val wm = state.wm
    val grid = iconGrid(prefs, wm.area, wm.inset)
    val keys = state.iconsNow()
    val places = placeIcons(keys, prefs.sort, prefs.iconPos, grid.rows, grid.cols)
    val px = iconPx(prefs.iconSize)
    val density = LocalDensity.current
    val light = wallpaperLight(prefs.wallpaper, !Titan.colors.dark)
    val ink = if (light) Color(0xFF14181D) else Color.White
    for (key in keys) {
        val at = places[key] ?: continue
        val folder = if (key.startsWith("folder:")) prefs.folders.firstOrNull { "folder:" + it.id == key } else null
        val p = state.programOf(key)
        if (folder == null && p == null) continue
        val label = folder?.name ?: p!!.label
        var drag by remember(key) { mutableStateOf<Pair<Float, Float>?>(null) }
        // Tastatur: wo der Fokus steht, zeigt ein Rahmen in der Akzentfarbe (wie :focus-visible im Web).
        var focused by remember(key) { mutableStateOf(false) }
        val x = grid.left + at.c * grid.colW + (drag?.first ?: 0f)
        val y = ICON_TOP + at.r * grid.rowH + (drag?.second ?: 0f)
        Column(
            Modifier
                .offset { IntOffset((x * density.density).roundToInt(), (y * density.density).roundToInt()) }
                .width((grid.colW - 4).dp)
                .clip(RoundedCornerShape(10.dp))
                .border(2.dp, if (focused) Titan.colors.accent else Color.Transparent, RoundedCornerShape(10.dp))
                .onSecondaryClickAt { _, _ -> state.menu = DeskMenu(x + grid.colW / 2f, y + px / 2f, DeskMenu.Kind.Icon, key) }
                .pointerInput(key) {
                    detectTapGestures(
                        onTap = { state.open(key) },
                        onLongPress = { state.menu = DeskMenu(x + grid.colW / 2f, y + px / 2f, DeskMenu.Kind.Icon, key) },
                    )
                }
                .pointerInput(key, grid) {
                    var sum = androidx.compose.ui.geometry.Offset.Zero
                    detectDragGestures(
                        onDragStart = { sum = androidx.compose.ui.geometry.Offset.Zero; state.dragKey = key },
                        onDrag = { change, delta ->
                            change.consume()
                            sum += delta
                            drag = sum.x / density.density to sum.y / density.density
                            state.dragAt = (grid.left + at.c * grid.colW + sum.x / density.density + grid.colW / 2f) to (ICON_TOP + at.r * grid.rowH + sum.y / density.density + px / 2f)
                        },
                        onDragEnd = {
                            val end = state.dragAt
                            drag = null
                            state.dragKey = null
                            state.dragAt = null
                            if (end != null) dropIconAt(state, key, end.first, end.second, grid, places)
                        },
                        onDragCancel = { drag = null; state.dragKey = null; state.dragAt = null },
                    )
                }
                // TalkBack und Tastatur (Tab, Enter) wie ein Knopf; die Gesten oben bleiben für Finger und Maus.
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = label
                    onClick { state.open(key); true }
                    onLongClick { state.menu = DeskMenu(x + grid.colW / 2f, y + px / 2f, DeskMenu.Kind.Icon, key); true }
                }
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar)) {
                        state.open(key)
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .padding(vertical = 6.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (folder != null) FolderIcon(state, folder, px) else if (p!!.glyph.isNotEmpty()) AppIcon(p.glyph, p.gradient, px.dp) else LetterIcon(p.letter(), p.gradient, px.dp)
            Text(
                label,
                style = TitanType.appLabel.copy(color = ink, shadow = if (light) null else Shadow(Color.Black.copy(alpha = .6f), blurRadius = 4f)),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FolderIcon(state: WorkspaceState, folder: Folder, px: Int) {
    val shape = RoundedCornerShape(percent = 23)
    Box(
        Modifier.size(px.dp).shadow(4.dp, shape).clip(shape).background(Color.White.copy(alpha = .22f)).border(1.dp, Color.White.copy(alpha = .25f), shape).padding((px * .1f).dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy((px * .06f).dp), verticalArrangement = Arrangement.spacedBy((px * .06f).dp), maxItemsInEachRow = 2) {
            for (app in folder.apps.take(4)) {
                val p = state.programOf(app) ?: continue
                if (p.glyph.isNotEmpty()) AppIcon(p.glyph, p.gradient, (px * .36f).dp) else LetterIcon(p.letter(), p.gradient, (px * .36f).dp)
            }
        }
    }
}

/** Ein gezogenes Symbol losgelassen: ins Dock, in einen Ordner oder auf einen Platz (frei). */
private fun dropIconAt(state: WorkspaceState, key: String, x: Float, y: Float, grid: IconGrid, shown: Map<String, Cell>) {
    val wm = state.wm
    val dock = state.store.prefs.dock
    val room = dockRoom(dock)
    val onDock = dock.show && when (dock.position) {
        "left" -> x < room
        "right" -> x > wm.area.w - room
        else -> y > wm.area.h - room
    }
    if (onDock) {
        if (!key.startsWith("folder:")) {
            val pinned = dockPinned(state.store.prefs, state.known)
            if (key !in pinned) state.setPinned(pinned + key)
        }
        return
    }
    val to = grid.cellAt(x, y)
    val other = shown.entries.firstOrNull { it.value == to && it.key != key }?.key
    if (other != null && other.startsWith("folder:") && !key.startsWith("folder:")) {
        val id = other.removePrefix("folder:")
        state.store.change("folders", "icons", "customIcons") { p ->
            p.copy(
                folders = p.folders.map { f -> if (f.id == id) f.copy(apps = (f.apps - key) + key) else f.copy(apps = f.apps - key) },
                icons = state.iconsNow().filter { it != key },
                customIcons = true,
            )
        }
        return
    }
    val prefs = state.store.prefs
    val next = dropIcon(prefs.sort, prefs.iconPos, shown, key, to, other)
    state.store.change("iconPos", "sort") { it.copy(iconPos = next, sort = "free") }
}

/** Ein offener Ordner (FolderPop.svelte): seine Programme, Umbenennen, Auflösen. */
@Composable
internal fun BoxScope.FolderPop(state: WorkspaceState) {
    val key = state.openFolder ?: return
    val folder = state.store.prefs.folders.firstOrNull { "folder:" + it.id == key } ?: run { state.openFolder = null; return }
    var renaming by remember(key) { mutableStateOf(false) }
    var name by remember(key) { mutableStateOf(folder.name) }
    val programs = folder.apps.mapNotNull { state.programOf(it) }
    DeskDialogFrame(
        title = folder.name,
        onDismiss = { state.openFolder = null },
        actions = {
            TextAction(t("desktop.folder.rename")) { renaming = !renaming }
            TextAction(t("desktop.folder.remove"), danger = true) {
                removeIcon(state, key)
                state.openFolder = null
            }
            TextAction(t("desktop.widgets.done")) { state.openFolder = null }
        },
    ) {
        if (renaming) {
            io.github.ra5on.titanos.ui.components.TextInput(name, { name = it }, t("desktop.folder.rename"))
            TextAction(t("desktop.widgets.done")) {
                val clean = name.trim().take(60).ifEmpty { folder.name }
                state.store.change("folders") { p -> p.copy(folders = p.folders.map { if (it.id == folder.id) it.copy(name = clean) else it }) }
                renaming = false
            }
        }
        if (programs.isEmpty()) Text(t("desktop.folder.empty"), style = TitanType.body, color = Titan.colors.muted)
        else ProgramGrid(state, programs)
        Text(t("desktop.folder.takeOutHint"), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Vom Arbeitsplatz entfernen; ein Ordner gibt seine Programme zurück (removeIcon). */
internal fun removeIcon(state: WorkspaceState, key: String) {
    val now = state.iconsNow()
    state.store.change("icons", "customIcons", "folders") { p ->
        var icons = now.filter { it != key }
        var folders = p.folders
        if (key.startsWith("folder:")) {
            val f = p.folders.firstOrNull { "folder:" + it.id == key }
            folders = p.folders.filter { "folder:" + it.id != key }
            if (f != null) icons = icons + f.apps
        }
        p.copy(icons = icons, customIcons = true, folders = folders)
    }
}

/** Ein neuer Ordner auf dem Arbeitsplatz (newFolder). */
internal fun newFolder(state: WorkspaceState, name: String) {
    val id = "f" + System.currentTimeMillis().toString(36)
    val now = state.iconsNow()
    state.store.change("folders", "icons", "customIcons") { p ->
        p.copy(folders = p.folders + Folder(id, name, emptyList()), icons = now + ("folder:$id"), customIcons = true)
    }
}

/** Ein Programm aus einem Ordner zurück auf den Arbeitsplatz. */
internal fun takeOut(state: WorkspaceState, key: String) {
    val now = state.iconsNow()
    state.store.change("folders", "icons", "customIcons") { p ->
        p.copy(folders = p.folders.map { it.copy(apps = it.apps - key) }, icons = if (key in now) now else now + key, customIcons = true)
    }
}
