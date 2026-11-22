package io.github.ra5on.titanos.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(if (state.launcher) c.chip else c.surface.copy(alpha = 0f))
                .clickable(role = Role.Button) { state.launcher = !state.launcher }
                .semantics(mergeDescendants = true) { contentDescription = menuTitle + " (" + "Strg + Leertaste" + ")" }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(TitanIcons.grid, null, tint = c.text, modifier = Modifier.size(icon))
            Text(menuTitle, style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
        SearchField(state, Modifier.widthIn(min = 120.dp, max = 220.dp), onSearchFocus)
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).heightIn(min = h * .5f, max = h * .5f).background(c.border))
        MenuBarItems(state)
        Spacer(Modifier.weight(1f))
        Status(state)
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
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
                    .semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun Status(state: WorkspaceState) {
    val c = Titan.colors
    val data = state.data
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(15_000)
        }
    }
    val locale = Locale.getDefault()
    if (state.store.prefs.barLoad && data.cpu != null) {
        val cpu = Format.percent((data.cpu ?: 0.0) / 100.0)
        val ram = Format.percent(data.memFraction)
        Text(t("menubar.load", "cpu" to cpu, "ram" to ram), style = TitanType.small, color = c.muted, modifier = Modifier.padding(horizontal = 6.dp))
    }
    val date = now.format(DateTimeFormatter.ofPattern(if (locale.language == "de") "EEE d. MMM" else "EEE d MMM", locale))
    val time = now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    Row(Modifier.fillMaxHeight().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(date, style = TitanType.small, color = c.muted)
        Text(time, style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
    }
}
