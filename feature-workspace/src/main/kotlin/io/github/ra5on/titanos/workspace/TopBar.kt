package io.github.ra5on.titanos.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

/** Höhe der oberen Leiste je Größe (barSize im Web: 34/40/48 px). */
fun barHeight(size: String): Dp = when (size) {
    "s" -> 34.dp
    "l" -> 48.dp
    else -> 40.dp
}

/**
 * Die obere Leiste (TopBar.svelte): Menü (Strg + Leertaste), Suche, die
 * Menüleiste des vorderen Programms bzw. des Arbeitsplatzes, rechts
 * Auslastung (abschaltbar), Datum und Uhrzeit. Rechtsklick: Menü der Leiste.
 */
@Composable
internal fun TopBar(state: WorkspaceState, onSearchFocus: () -> Unit) {
    val prefs = state.store.prefs
    val h = barHeight(prefs.barSize)
    val c = Titan.colors
    val density = LocalDensity.current
    val icon = when (prefs.barSize) {
        "s" -> 16.dp
        "l" -> 22.dp
        else -> 18.dp
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = h).glass(c.surface, RoundedCornerShape(0.dp), alpha = .78f, border = null)
            .onSecondaryClickAt { x, _ -> state.menu = DeskMenu(x, 0f, DeskMenu.Kind.Bar) }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val menuTitle = t("desktop.menu.title")
        val shortcut = t("desktop.menu.shortcut")
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(if (state.launcher) c.chip else c.surface.copy(alpha = 0f))
                .clickable(role = Role.Button) { state.launcher = !state.launcher }
                .semantics(mergeDescendants = true) { contentDescription = "$menuTitle ($shortcut)" }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(TitanIcons.grid, null, tint = c.text, modifier = Modifier.size(icon))
            Text(menuTitle, style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
        // Schmal (Handy, geteilter Bildschirm, große Schrift): erst die Suche, dann die Menüleiste weglassen, nie Wörter zerreißen.
        val room = state.wm.area.w / androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
        val search = state.onSearch
        if (search != null) {
            // Eine Suche für alles (Programme, Seiten, Einstellungen): die Befehlspalette der App.
            if (room >= 900) SearchButton(Modifier.widthIn(min = 120.dp, max = 220.dp), search)
            else io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.search, t("desktop.menu.search"), search, size = 18.dp)
        } else if (room >= 900) SearchField(state, Modifier.widthIn(min = 120.dp, max = 220.dp), onSearchFocus)
        if (room >= 640) {
            Box(Modifier.padding(horizontal = 4.dp).width(1.dp).heightIn(min = h * .5f, max = h * .5f).background(c.border))
            MenuBarItems(state)
        }
        Spacer(Modifier.weight(1f))
        // Mehrere NAS: das gewählte mit Namen, ein Klick öffnet die Liste zum Umschalten (wie der Umschalter der Startseite).
        if (state.nasList.size > 1) {
            var nx by remember { mutableStateOf(0f) }
            val name = state.nasList.firstOrNull { it.first == state.activeNas }?.second.orEmpty()
            Row(
                Modifier.onGloballyPositioned { nx = it.boundsInRoot().left / density.density }
                    .clip(RoundedCornerShape(8.dp)).background(c.chip.copy(alpha = .7f))
                    .clickable(role = Role.Button) { state.menu = DeskMenu(nx, 0f, DeskMenu.Kind.Bar, "nas") }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(TitanIcons.nodes, null, tint = c.muted, modifier = Modifier.size(15.dp))
                Text(name, style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            }
        }
                // Der KI-Agent als Leiste rechts (wie die Seitenleiste des Agenten im Web), wenn das Konto ihn hat.
        state.programOf("app:agent")?.let { agent ->
            val label = stringResource(R.string.ws_agent_panel)
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { state.agentPanel() }
                    .semantics { contentDescription = label }.padding(6.dp),
            ) { io.github.ra5on.titanos.ui.components.AppIcon(agent.glyph, agent.gradient, icon + 4.dp) }
        }
        Status(state, compact = room < 900, tiny = room < 640)
    }
}

/** Die Menüs der Leiste: Arbeitsplatz (ohne Fenster vorn) bzw. der Name des Programms, Fenster, Hilfe. */
@Composable
private fun MenuBarItems(state: WorkspaceState) {
    val front = state.wm.focused?.let { state.wm.find(it) }
    val c = Titan.colors
    val density = LocalDensity.current
    val menus = listOf(
        "main" to (front?.title ?: t("menubar.desktop")),
        "window" to t("menubar.menu.window"),
        "help" to t("menubar.menu.help"),
    )
    for ((id, label) in menus) {
        var x by remember { mutableStateOf(0f) }
        val open = state.menu?.kind == DeskMenu.Kind.Bar && state.menu?.key == id
        Text(
            label,
            style = TitanType.small.copy(fontWeight = if (id == "main") FontWeight.Bold else FontWeight.Medium),
            color = c.text,
            modifier = Modifier.onGloballyPositioned { x = it.boundsInRoot().left / density.density }
                .clip(RoundedCornerShape(6.dp)).background(if (open) c.chip else c.surface.copy(alpha = 0f))
                .clickable(role = Role.Button) { state.menu = if (open) null else DeskMenu(x, 0f, DeskMenu.Kind.Bar, id) }
                .then(if (id == "main") Modifier.semantics { heading() } else Modifier)
                .padding(horizontal = 9.dp, vertical = 6.dp),
        )
    }
}

/** Das Suchfeld als Knopf: öffnet „Schnell finden“ (Strg/⌘+K). */
@Composable
private fun SearchButton(modifier: Modifier, onClick: () -> Unit) {
    val c = Titan.colors
    val hint = t("desktop.menu.searchShort")
    val label = t("palette.shortcut")
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(c.chip.copy(alpha = .7f))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(TitanIcons.search, null, tint = c.muted, modifier = Modifier.size(15.dp))
        Text(hint, style = TitanType.small, color = c.muted)
    }
}

@Composable
private fun SearchField(state: WorkspaceState, modifier: Modifier, onFocus: () -> Unit) {
    val c = Titan.colors
    val hint = t("desktop.menu.searchShort")
    val label = t("desktop.menu.search")
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(c.chip.copy(alpha = .7f)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(TitanIcons.search, null, tint = c.muted, modifier = Modifier.size(15.dp))
        Box(Modifier.weight(1f)) {
            if (state.query.isEmpty()) Text(hint, style = TitanType.small, color = c.muted)
            BasicTextField(
                state.query,
                { state.query = it; state.launcher = true },
                singleLine = true,
                textStyle = TitanType.small.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { openFirstHit(state) }),
                modifier = Modifier.fillMaxWidth().focusRequester(state.searchFocus).onFocusChanged { if (it.isFocused) onFocus() }
                    // Hardware-Tastatur: Enter öffnet den ersten Treffer wie im Web (Launcher.svelte).
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) openFirstHit(state) else false
                    }
                    .semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun Status(state: WorkspaceState, compact: Boolean = false, tiny: Boolean = false) {
    val c = Titan.colors
    val data = state.data
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(15_000)
        }
    }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    if (state.store.prefs.barLoad && data.cpu != null && !compact) {
        val cpu = Format.percent((data.cpu ?: 0.0) / 100.0)
        val ram = Format.percent(data.memFraction)
        Text(t("menubar.load", "cpu" to cpu, "ram" to ram), style = TitanType.small, color = c.muted, modifier = Modifier.padding(horizontal = 6.dp))
    }
    val date = now.format(DateTimeFormatter.ofPattern(if (locale.language == "de") "EEE d. MMM" else "EEE d MMM", locale))
    val time = now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!tiny) Text(date, style = TitanType.small, color = c.muted)
        Text(time, style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
    }
}

/** Öffnet den ersten Treffer der Suche im Menü; falsch, wenn es keinen gibt. */
private fun openFirstHit(state: WorkspaceState): Boolean {
    val first = searchHits(state).firstOrNull() ?: return false
    state.open(first.key)
    state.query = ""
    state.launcher = false
    return true
}
