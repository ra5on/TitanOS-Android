package io.github.ra5on.titanos.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.rememberWidestWord
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Die Dialoge des Arbeitsplatzes (DesktopDialogs.svelte). */
@Composable
internal fun BoxScope.DeskDialogs(state: WorkspaceState) {
    when (state.dialog) {
        DeskDialog.Wallpaper -> WallpaperDialog(state)
        DeskDialog.Settings -> SettingsDialog(state)
        DeskDialog.Gallery -> GalleryDialog(state)
        DeskDialog.Layouts -> LayoutsDialog(state)
        DeskDialog.Shortcuts -> ShortcutsDialog(state)
        DeskDialog.NewFolder -> NewFolderDialog(state)
        null -> Unit
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = TitanType.section, color = Titan.colors.muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp).semantics { heading() })
}

@Composable
private fun Hint(text: String) = Text(text, style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun <T> Segments(value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((v, label) in options) Chip(label, on = v == value) { onChange(v) }
    }
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TitanType.body, color = Titan.colors.text)
            if (hint != null) Hint(hint)
        }
        Toggle(checked, onChange, stringResource(R.string.ws_on), stringResource(R.string.ws_off), label = title)
    }
}

@Composable
private fun SliderLine(title: String, value: Int, range: IntRange, step: Int, unit: String, onChange: (Int) -> Unit) {
    var live by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(title, style = TitanType.body, color = Titan.colors.text, modifier = Modifier.weight(1f))
            Text("${live.roundToInt()} $unit", style = TitanType.body, color = Titan.colors.muted)
        }
        Slider(
            live,
            { live = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            onValueChangeFinished = { onChange(live.roundToInt()) },
            colors = SliderDefaults.colors(thumbColor = Titan.colors.accent, activeTrackColor = Titan.colors.accent, inactiveTrackColor = Titan.colors.track),
            modifier = Modifier.semantics { contentDescription = title; stateDescription = "${live.roundToInt()} $unit" },
        )
    }
}

/** Einstellungen des Arbeitsplatzes: wann er erscheint, eigene Einrichtung, Dock, Leiste, Symbole. */
@Composable
private fun BoxScope.SettingsDialog(state: WorkspaceState) {
    val prefs = state.store.prefs
    val d = prefs.dock
    val settings = state.settings
    val scope = rememberCoroutineScope()
    DeskDialogFrame(t("desktop.settings.title"), { state.dialog = null; state.editDock = false }, maxWidth = 620.dp, actions = {
        TextAction(t("desktop.widgets.done")) { state.dialog = null; state.editDock = false }
    }) {
        Section(stringResource(R.string.ws_mode))
        Segments(settings.mode, listOf(WorkspaceMode.Auto to stringResource(R.string.ws_mode_auto), WorkspaceMode.Always to stringResource(R.string.ws_mode_always), WorkspaceMode.Never to stringResource(R.string.ws_mode_never))) { settings.setMode(it) }
        Hint(stringResource(R.string.ws_mode_hint))
        Section(stringResource(R.string.ws_title))
        SwitchLine(stringResource(R.string.ws_own), settings.own, { on ->
            scope.launch {
                state.store.flush()
                settings.setOwn(on)
                state.store.load()
            }
        }, stringResource(R.string.ws_own_hint))
        Section(t("desktop.dock.title"))
        SwitchLine(t("desktop.dock.show"), d.show, { v -> state.store.change("dock") { it.copy(dock = it.dock.copy(show = v)) } })
        Text(t("desktop.dock.position"), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        Segments(d.position, listOf("bottom" to t("desktop.dock.bottom"), "left" to t("desktop.dock.left"), "right" to t("desktop.dock.right"))) { v -> state.store.change("dock") { it.copy(dock = it.dock.copy(position = v)) } }
        SliderLine(t("desktop.dock.size"), d.size, 36..80, 2, "px") { v -> state.store.change("dock") { it.copy(dock = it.dock.copy(size = v)) } }
        SliderLine(t("desktop.dock.opacity"), d.opacity, 30..100, 5, "%") { v -> state.store.change("dock") { it.copy(dock = it.dock.copy(opacity = v)) } }
        Text(t("desktop.dock.magnify"), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        Segments(d.magnify, listOf(0 to t("desktop.dock.magnifyOff"), 1 to t("desktop.dock.magnifyLight"), 2 to t("desktop.dock.magnifyStrong"))) { v -> state.store.change("dock") { it.copy(dock = it.dock.copy(magnify = v)) } }
        Text(t("desktop.dock.border"), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        Segments(d.border, listOf(true to t("desktop.dock.borderOn"), false to t("desktop.dock.borderOff"))) { v -> state.store.change("dock") { it.copy(dock = it.dock.copy(border = v)) } }
        TextAction(t("desktop.dock.reset")) { state.store.change("dock") { it.copy(dock = it.dock.copy(pinned = emptyList(), customPinned = false, size = 52, opacity = 90, magnify = 1)) } }
        Section(t("desktop.bar.title"))
        Text(t("desktop.bar.size"), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(bottom = 4.dp))
        Segments(prefs.barSize, listOf("s" to t("desktop.size.s"), "m" to t("desktop.size.m"), "l" to t("desktop.size.l"))) { v -> state.store.change("barSize") { it.copy(barSize = v) } }
        SwitchLine(t("desktop.bar.load"), prefs.barLoad, { v -> state.store.change("barLoad") { it.copy(barLoad = v) } }, t("desktop.bar.loadHint"))
        Section(t("desktop.settings.resetIcons"))
        Hint(t("desktop.settings.resetIconsHint"))
        TextAction(t("desktop.settings.reset"), danger = true) {
            state.store.change("icons", "customIcons", "folders", "iconPos") { it.copy(icons = emptyList(), customIcons = false, folders = emptyList(), iconPos = emptyMap()) }
        }
    }
}

/** Hintergrund wählen (dieselbe Einstellung wie im Web). */
@Composable
private fun BoxScope.WallpaperDialog(state: WorkspaceState) {
    val prefs = state.store.prefs
    val w = prefs.wallpaper
    val light = !Titan.colors.dark
    val style = TitanType.small.copy(fontWeight = FontWeight.SemiBold)
    val widest = rememberWidestWord(WALLPAPERS.map { t("desktop.wallpaper.$it") }, style)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tile = maxOf(110.dp, with(density) { widest.toDp() } + 16.dp)
    DeskDialogFrame(t("desktop.wallpaper.title"), { state.dialog = null }, maxWidth = 720.dp, actions = { TextAction(t("desktop.widgets.done")) { state.dialog = null } }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (id in WALLPAPERS) {
                val on = !w.custom && w.id == id
                val shape = RoundedCornerShape(10.dp)
                WallpaperFill(
                    Wallpaper(false, id, 0, w.live), light, state.access,
                    Modifier.width(tile).size(tile, 68.dp).clip(shape)
                        .border(if (on) 3.dp else 1.dp, if (on) Titan.colors.accent else Titan.colors.border, shape)
                        .clickable(role = Role.RadioButton) { state.store.change("wallpaper") { it.copy(wallpaper = Wallpaper(false, id, 0, it.wallpaper.live)) } }
                        .semantics(mergeDescendants = true) { stateDescription = if (on) "✓" else "" },
                ) {
                    Text(
                        t("desktop.wallpaper.$id"), style = style, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .5f)).padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
        SwitchLine(t("desktop.wallpaper.live"), w.live, { v -> state.store.change("wallpaper") { it.copy(wallpaper = it.wallpaper.copy(live = v)) } }, t("desktop.wallpaper.liveHint"))
        Hint(stringResource(R.string.ws_wallpaper_more))
    }
}

/** Widgets hinzufügen (Galerie). */
@Composable
private fun BoxScope.GalleryDialog(state: WorkspaceState) {
    DeskDialogFrame(t("desktop.widgets.add"), { state.dialog = null }, maxWidth = 760.dp, actions = { TextAction(t("desktop.widgets.done")) { state.dialog = null } }) {
        Hint(t("desktop.gallery.hint"))
        val kinds = WIDGET_KINDS.filter { k -> state.admin || k !in setOf("docker", "vms", "backups", "temperatures", "igpu", "npu", "gpu", "camera", "volumes") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (k in kinds) {
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier.width(220.dp).clip(shape).background(Titan.colors.bg).border(1.dp, Titan.colors.border, shape)
                        .clickable(role = Role.Button) { addWidget(state, k); state.dialog = null }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(t("desktop.widget.$k"), style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.text)
                    if (io.github.ra5on.titanos.compute.hasText("desktop.gallery.$k")) Hint(t("desktop.gallery.$k"))
                    if (state.store.prefs.widgets.any { it.kind == k }) Hint("✓ " + t("desktop.gallery.shown"))
                    Box(Modifier.fillMaxWidth().size(200.dp, 110.dp).clip(RoundedCornerShape(10.dp)).glass(Titan.colors.surface, alpha = 1f).padding(8.dp)) {
                        WidgetBody(state, Widget("preview-$k", k, 0, 0, 10, 6))
                    }
                }
            }
        }
    }
}

/** Fenster-Anordnungen speichern und wiederherstellen. */
@Composable
private fun BoxScope.LayoutsDialog(state: WorkspaceState) {
    val prefs = state.store.prefs
    val defaultName = t("desktop.layout.defaultName")
    var name by remember { mutableStateOf("") }
    DeskDialogFrame(t("desktop.layout.title"), { state.dialog = null }, actions = { TextAction(t("desktop.widgets.done")) { state.dialog = null } }) {
        Hint(t("desktop.layout.text"))
        if (prefs.layouts.isEmpty()) Text(t("desktop.layout.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 8.dp))
        for (l in prefs.layouts) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = TitanType.body, color = Titan.colors.text)
                    Hint(t("desktop.layout.count", "count" to l.windows.size))
                }
                TextAction(t("desktop.layout.restore")) {
                    state.wm.restore(l) { app -> state.programOf(app)?.label ?: app }
                    state.dialog = null
                }
                TextAction(t("desktop.layout.delete", "name" to l.name), danger = true) {
                    state.store.change("layouts") { p -> p.copy(layouts = p.layouts.filter { it.id != l.id }) }
                }
            }
        }
        if (state.wm.windows.isNotEmpty()) {
            TextInput(name, { name = it }, t("desktop.layout.name"), placeholder = t("desktop.layout.namePlaceholder"))
            TextAction(t("desktop.layout.save")) {
                val windows = state.wm.windows.filter { !it.minimized }.map { val r = state.wm.shownRect(it); LayoutWindow(it.app, it.href.ifEmpty { "/" }, r.x, r.y, r.w, r.h) }
                val layout = SavedLayout("l" + System.currentTimeMillis().toString(36), name.trim().take(60).ifEmpty { defaultName }, windows)
                state.store.change("layouts") { p -> p.copy(layouts = (p.layouts + layout).takeLast(10)) }
                name = ""
            }
        }
    }
}

@Composable
private fun BoxScope.ShortcutsDialog(state: WorkspaceState) {
    DeskDialogFrame(t("menubar.help.shortcuts"), { state.dialog = null }, actions = { TextAction(t("desktop.widgets.done")) { state.dialog = null } }) {
        Text(t("desktop.switcher.keys"), style = TitanType.body, color = Titan.colors.text)
        Text(stringResource(R.string.ws_shortcuts_menu), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.ws_shortcuts_esc), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.ws_shortcuts_back), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun BoxScope.NewFolderDialog(state: WorkspaceState) {
    val fallback = t("desktop.folder.newName")
    var name by remember { mutableStateOf(fallback) }
    DeskDialogFrame(t("desktop.ctx.newFolder"), { state.dialog = null }, actions = {
        TextAction(t("common.cancel")) { state.dialog = null }
        TextAction(t("desktop.widgets.done")) {
            newFolder(state, name.trim().take(60).ifEmpty { fallback })
            state.dialog = null
        }
    }) {
        TextInput(name, { name = it }, t("desktop.folder.rename"))
    }
}
