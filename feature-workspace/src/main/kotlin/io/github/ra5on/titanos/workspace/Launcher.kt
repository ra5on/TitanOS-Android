package io.github.ra5on.titanos.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.rememberWidestWord
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.TitanIcons
import androidx.compose.foundation.layout.size

/**
 * Das Hauptmenü (Launcher.svelte): alle Programme in der eigenen Reihenfolge
 * des Kontos (sonst A–Z), „Zuletzt“, Docker- und Web-Apps; die Suche der
 * oberen Leiste filtert. Rechtsklick auf ein Programm: sein Menü (in neuem
 * Fenster, ins Dock, auf den Arbeitsplatz). Breite der Kacheln nach dem
 * längsten Wort (ganze Wörter auch bei 200 % Schrift).
 */
@Composable
internal fun BoxScope.Launcher(state: WorkspaceState) {
    val c = Titan.colors
    var filter by remember { mutableStateOf("all") }
    Box(
        Modifier.matchParentSize().zIndex(200_000f)
            .pointerInput(Unit) { detectTapGestures { state.launcher = false } },
    )
    val dock = state.store.prefs.dock
    val align = when {
        dock.show && dock.position == "left" -> Alignment.CenterStart
        dock.show && dock.position == "right" -> Alignment.CenterEnd
        else -> Alignment.TopStart
    }
    BoxWithConstraints(Modifier.matchParentSize().zIndex(200_001f)) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.align(align).padding(start = if (dock.show && dock.position == "left") (dockRoom(dock) + 4).dp else 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .widthIn(max = minOf(760.dp, maxWidth - 16.dp)).heightIn(max = maxHeight - 16.dp)
                .glass(c.surface, shape, alpha = .97f, border = c.border)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val q = state.query.trim().lowercase()
            val all = menuSorted(state.programs, state.store.prefs.menuOrder, { it.key }, { it.label })
            val shown = when {
                q.isNotEmpty() -> searchHits(state)
                filter == "recent" -> state.store.prefs.recent.mapNotNull { state.programOf(it) }
                filter == "docker" -> all.filter { it.kind == "docker" }
                filter == "system" -> all.filter { it.kind == "system" || it.kind == "android" }
                else -> all
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((id, label) in listOf("all" to t("desktop.menu.all"), "system" to t("desktop.menu.system"), "docker" to t("desktop.menu.docker"), "recent" to t("desktop.menu.recent"))) {
                    if (id == "docker" && state.programs.none { it.kind == "docker" }) continue
                    val on = filter == id && q.isEmpty()
                    Text(
                        label,
                        style = TitanType.small.copy(fontWeight = FontWeight.SemiBold),
                        color = if (on) c.onAccent else c.text,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) c.accent else c.chip)
                            .clickable(role = Role.Tab) { filter = id; state.query = "" }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            if (shown.isEmpty()) {
                Text(t("desktop.menu.nothing"), style = TitanType.body, color = c.muted)
            } else {
                ProgramGrid(state, shown, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
            }
            Text(t(if (state.store.prefs.menuOrder.isEmpty()) "desktop.menu.orderAz" else "desktop.menu.orderOwn") + " · " + t("desktop.menu.shortcut"), style = TitanType.small, color = c.muted)
            // Unten wie im Web: Konto, Systemsteuerung, App-Center (soweit es sie für dieses Konto gibt).
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in listOf("app:account", "app:settings", "app:appcenter")) {
                    val p = state.programOf(key) ?: continue
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(c.chip).clickable(role = Role.Button) { state.open(key) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppIcon(p.glyph, p.gradient, 20.dp)
                        Text(if (key == "app:account") state.user.substringAfterLast('/') else p.label, style = TitanType.small, color = c.text)
                    }
                }
                // Wie im Web: Sperren, Neustart, Herunterfahren (beides nur für Besitzer und Admins, mit Rückfrage und Fingerabdruck).
                val power = listOfNotNull(
                    state.onLock?.let { Triple(t("desktop.menu.lock"), TitanIcons.lock) { state.launcher = false; it() } },
                    state.onPower?.takeIf { state.admin }?.let { Triple(t("desktop.menu.restartShort"), TitanIcons.restart) { state.launcher = false; it(true) } },
                    state.onPower?.takeIf { state.admin }?.let { Triple(t("desktop.menu.shutdownShort"), TitanIcons.bolt) { state.launcher = false; it(false) } },
                )
                for ((label, icon, run) in power) {
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(c.chip).clickable(role = Role.Button, onClick = run).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.Icon(icon, null, tint = c.muted, modifier = Modifier.size(18.dp))
                        Text(label, style = TitanType.small, color = c.text)
                    }
                }
            }
        }
    }
}

/** Kacheln: Symbol und Name darunter, ganze Wörter, so breit wie das längste Wort es braucht. */
@Composable
internal fun ProgramGrid(state: WorkspaceState, programs: List<Program>, modifier: Modifier = Modifier, iconSize: Dp = 48.dp) {
    val c = Titan.colors
    val style = TitanType.appLabel
    val widest = rememberWidestWord(programs.map { it.label }, style)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tile = maxOf(96.dp, with(density) { widest.toDp() } + 16.dp)
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in programs) {
            Column(
                Modifier.width(tile).clip(RoundedCornerShape(10.dp))
                    .onSecondaryClickAt { _, _ -> state.menu = DeskMenu(state.wm.area.w / 3f, 80f, DeskMenu.Kind.Icon, p.key) }
                    .clickable(role = Role.Button) { state.open(p.key) }
                    .semantics(mergeDescendants = true) { contentDescription = p.label }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (p.glyph.isNotEmpty()) AppIcon(p.glyph, p.gradient, iconSize) else LetterIcon(p.letter(), p.gradient, iconSize)
                Text(p.label, style = style, color = c.text, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Programme, die zur Suche im Menü passen, in der Reihenfolge des Menüs (Enter öffnet den ersten wie im Web). */
internal fun searchHits(state: WorkspaceState): List<Program> {
    val q = state.query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return menuSorted(state.programs, state.store.prefs.menuOrder, { it.key }, { it.label })
        .filter { it.label.lowercase().contains(q) || it.key.lowercase().contains(q) }
}
