package io.github.ra5on.titanos.compute

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.ra5on.titanos.ui.Width
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.BottomNav
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.NavItem
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Gemeinsame Bausteine der Programme Docker, VMs, LXC, NVR und Terminal im
// Stil der vorhandenen App-Seiten (core-ui). Texte brechen immer um; kein
// Text wird abgeschnitten, Zustände stehen immer auch in Worten da.

/** Ein Abschnitt eines Programms: unten in der Leiste (Handy) oder links in der Seitenleiste (ab 900 dp). */
data class Section(val key: String, val label: String, val icon: ImageVector)

/** Ein Eintrag eines Menüs (⋯, langes Drücken, Rechtsklick). */
data class MenuEntry(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Eine einfache Rückfrage: gefährlicher Knopf links von „Abbrechen“, nie ein Name zum Eintippen. */
data class Ask(
    val title: String,
    val text: String?,
    val confirm: String,
    val danger: Boolean = true,
    val body: (@Composable ColumnScope.() -> Unit)? = null,
    val onConfirm: () -> Unit,
)

/** Was eine Seite zum Rückfragen, Melden und für Menüs braucht; bereitgestellt von [ProgramShell]. */
@Stable
class ComputeUi internal constructor(private val scope: CoroutineScope, private val context: android.content.Context) {
    var ask by mutableStateOf<Ask?>(null)
    var menu by mutableStateOf<Pair<String?, List<MenuEntry>>?>(null)
    var sheet by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)

    fun confirm(ask: Ask) {
        this.ask = ask
    }

    fun menu(title: String?, entries: List<MenuEntry>) {
        menu = title to entries.filter { true }
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    /** Führt eine Aktion am NAS aus; ein Fehler erscheint als Meldung in Worten. */
    fun run(onError: ((Throwable) -> Unit)? = null, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (onError != null) onError(e) else toast(context.errorText(e, detail = true))
            }
        }
    }
}

val LocalComputeUi = staticCompositionLocalOf<ComputeUi> { error("ComputeUi fehlt: ProgramShell drumherum") }

/**
 * Rahmen eines Programms wie das Web: am Handy Kopfleiste mit Zurück,
 * Programmsymbol und Titel, darunter der Inhalt, unten die Leiste der
 * Abschnitte; ab 900 dp links die Seitenleiste wie die PC-Aufteilung.
 */
@Composable
fun ProgramShell(
    title: String,
    glyph: String,
    gradient: String,
    sections: List<Section>,
    selected: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    pageTitle: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ui = remember { ComputeUi(scope, context) }
    val width = currentWidth()
    val back = stringResource(UiR.string.ui_back)
    CompositionLocalProvider(LocalComputeUi provides ui) {
        Box(Modifier.fillMaxSize().background(Titan.colors.bg)) {
            if (width.sidebar && sections.size > 1) {
                Row(Modifier.fillMaxSize()) {
                    SideBar(title, glyph, gradient, sections, selected, onSelect, onBack, back)
                    Box(Modifier.fillMaxHeight().width(1.dp).background(Titan.colors.border))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(pageTitle ?: sections.firstOrNull { it.key == selected }?.label ?: title, actions = actions)
                        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopBar(
                        pageTitle ?: title,
                        onBack = onBack,
                        backLabel = back,
                        icon = { AppIcon(glyph, gradient, 30.dp) },
                        actions = actions,
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                    if (sections.size > 1) {
                        BottomNav(sections.map { NavItem(it.key, it.label, it.icon) }, selected, onSelect)
                        Box(Modifier.navigationBarsPadding())
                    }
                }
            }
            Overlays(ui)
        }
    }
}

@Composable
private fun SideBar(
    title: String,
    glyph: String,
    gradient: String,
    sections: List<Section>,
    selected: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    back: String,
) {
    val c = Titan.colors
    Column(Modifier.width(260.dp).fillMaxHeight().background(c.header).verticalScroll(rememberScrollState()).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.back, back, onBack)
            AppIcon(glyph, gradient, 32.dp)
            Text(title, style = TitanType.topTitle, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Spacer(Modifier.size(10.dp))
        for (s in sections) {
            val on = s.key == selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) c.accent.copy(alpha = .18f) else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(s.key) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(s.icon, null, tint = if (on) c.accent else c.muted, modifier = Modifier.size(22.dp))
                Text(s.label, style = TitanType.body, color = c.text)
            }
        }
    }
}

@Composable
private fun BoxScope.Overlays(ui: ComputeUi) {
    val cancel = stringResource(UiR.string.ui_cancel)
    ui.menu?.let { (title, entries) ->
        Sheet(onDismiss = { ui.menu = null }) {
            if (title != null) {
                Text(title, style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() })
            }
            for (e in entries) MenuRow(e) { ui.menu = null; e.onClick() }
        }
    }
    ui.sheet?.let { body ->
        Sheet(onDismiss = { ui.sheet = null }) { body() }
    }
    ui.ask?.let { a ->
        ConfirmDialog(
            title = a.title,
            text = a.text,
            actions = listOf(
                DialogAction(a.confirm, danger = a.danger) { ui.ask = null; a.onConfirm() },
                DialogAction(cancel) { ui.ask = null },
            ),
            onDismiss = { ui.ask = null },
            body = a.body,
        )
    }
}

@Composable
fun MenuRow(e: MenuEntry, onClick: () -> Unit) {
    val c = Titan.colors
    val tint = when {
        !e.enabled -> c.muted
        e.danger -> c.dangerInk
        else -> c.text
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = e.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (e.icon != null) Icon(e.icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(e.label, style = TitanType.body.copy(fontSize = TitanType.rowTitle.fontSize), color = tint)
    }
}

/**
 * Liste und Inhalt: am Handy als Ebenen (der Inhalt legt sich über die
 * Liste, Zurück schließt ihn), ab 761 dp nebeneinander.
 */
@Composable
fun ListDetail(
    detailOpen: Boolean,
    onCloseDetail: () -> Unit,
    list: @Composable BoxScope.() -> Unit,
    detail: @Composable BoxScope.() -> Unit,
    listWidth: Dp = 360.dp,
) {
    val width = currentWidth()
    if (width.twoPanes) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.widthIn(max = listWidth).weight(0.42f).fillMaxHeight()) { list() }
            Box(Modifier.fillMaxHeight().width(1.dp).background(Titan.colors.border))
            Box(Modifier.weight(0.58f).fillMaxHeight()) { detail() }
        }
    } else if (detailOpen) {
        BackHandler(onBack = onCloseDetail)
        Box(Modifier.fillMaxSize()) { detail() }
    } else {
        Box(Modifier.fillMaxSize()) { list() }
    }
}

enum class StateKind { Ok, Off, Warn, Error, Busy, Info }

/** Zustand mit Zeichen und Wort (nie nur Farbe): ● läuft, ○ gestoppt, ▲ Warnung, ✕ Fehler. */
@Composable
fun StateMark(label: String, kind: StateKind, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val color = when (kind) {
        StateKind.Ok -> c.okInk
        StateKind.Warn -> c.warnInk
        StateKind.Error -> c.dangerInk
        StateKind.Busy, StateKind.Info -> c.accent
        StateKind.Off -> c.muted
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (kind) {
            StateKind.Ok, StateKind.Info -> Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            StateKind.Off -> Box(Modifier.size(9.dp).border(1.5.dp, color, CircleShape))
            StateKind.Warn -> Icon(TitanIcons.warn, null, tint = color, modifier = Modifier.size(15.dp))
            StateKind.Error -> Icon(TitanIcons.x, null, tint = color, modifier = Modifier.size(15.dp))
            StateKind.Busy -> CircularProgressIndicator(Modifier.size(13.dp), color = color, strokeWidth = 2.dp)
        }
        Text(label, style = TitanType.small.copy(fontSize = TitanType.rowSub.fontSize), color = if (kind == StateKind.Off) c.muted else color)
    }
}

/** Filter oder Reiter als Chips, die umbrechen statt abgeschnitten zu werden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Titan.colors
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, label) in options) {
            val on = key == selected
            val shape = RoundedCornerShape(16.dp)
            Text(
                label,
                style = TitanType.body,
                color = c.text,
                modifier = Modifier.clip(shape).background(if (on) c.accent.copy(alpha = .22f) else c.chip)
                    .border(1.dp, if (on) c.accent else c.border, shape)
                    .clickable(role = Role.Tab) { onSelect(key) }
                    .semantics { this.selected = on }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** Beschriftetes Eingabefeld mit Hinweis darunter. */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    placeholder: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = TitanType.small.copy(fontSize = TitanType.rowSub.fontSize), color = Titan.colors.muted, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        TextInput(value, { if (enabled) onChange(it) }, label, keyboard = keyboard, placeholder = placeholder)
        if (error != null) Text(error, style = TitanType.small, color = Titan.colors.dangerInk, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
        else if (hint != null) Text(hint, style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

/** Auswahl aus festen Werten (wie ein <select> im Web). */
@Composable
fun Choice(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    val c = Titan.colors
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = TitanType.small.copy(fontSize = TitanType.rowSub.fontSize), color = c.muted, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        Box {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape).background(c.surface).border(1.dp, c.border, shape)
                    .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                    .semantics { contentDescription = label; stateDescription = current }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(current, style = TitanType.body, color = if (enabled) c.text else c.muted, modifier = Modifier.weight(1f))
                Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                for ((key, text) in options) {
                    DropdownMenuItem(
                        text = { Text(text, style = TitanType.body, color = c.text) },
                        onClick = { open = false; onSelect(key) },
                        leadingIcon = if (key == selected) ({ Icon(TitanIcons.check, null, tint = c.accent) }) else null,
                    )
                }
            }
        }
        if (hint != null) Text(hint, style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

/** Zeile mit Schalter; der Zustand steht für Bildschirmleser in Worten da. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null, enabled: Boolean = true, divider: Boolean = false) {
    val on = stringResource(R.string.compute_on)
    val off = stringResource(R.string.compute_off)
    io.github.ra5on.titanos.ui.components.ListRow(
        title,
        sub = sub,
        divider = divider,
        onClick = if (enabled) ({ onChange(!checked) }) else null,
        right = { Toggle(checked, if (enabled) onChange else null, on, off, enabled) },
    )
}

/** Wert mit Beschriftung (Übersichten). Der Wert bricht um. */
@Composable
fun InfoRow(label: String, value: String, divider: Boolean = false, mono: Boolean = false) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth()) {
        if (divider) Divider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) {}) {
            Text(label, style = TitanType.small.copy(fontSize = TitanType.rowSub.fontSize), color = c.muted)
            Text(value.ifEmpty { "–" }, style = if (mono) TitanType.body.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono) else TitanType.body, color = c.text)
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.compute_loading)
    Box(modifier.fillMaxWidth().padding(32.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Titan.colors.accent)
    }
}

/** Fehler in Worten mit „Erneut versuchen“. */
@Composable
fun ErrorBox(text: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Problem(text)
        if (onRetry != null) TitanButton(t("common.retry"), onRetry, icon = TitanIcons.restart)
    }
}

/** Scrollende Seite mit Rand wie die übrigen Seiten der App. */
@Composable
fun ScrollPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        content = content,
    )
}

/**
 * Fragt regelmäßig nach, solange die Seite sichtbar ist (wie poll.ts im Web);
 * im Hintergrund ruht es. Der erste Lauf kommt sofort.
 */
@Composable
fun Poll(intervalMs: Long, key: Any? = Unit, block: suspend () -> Unit) {
    val current by rememberUpdatedState(block)
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(key, intervalMs) {
        val job = scope.launch {
            while (isActive) {
                runCatching { current() }
                delay(intervalMs)
            }
        }
        onPauseOrDispose { job.cancel() }
    }
}

/** Kleiner Knopf mit Text und Symbol für Zeilen (Starten, Stoppen …). */
@Composable
fun SmallButton(text: String, icon: ImageVector?, onClick: () -> Unit, danger: Boolean = false, enabled: Boolean = true, primary: Boolean = false) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    val fg = when {
        primary -> c.onAccent
        danger -> c.dangerInk
        else -> c.text
    }
    Row(
        Modifier.heightIn(min = 44.dp).clip(shape).background(if (primary) c.accent else c.chip).border(1.dp, if (primary) c.accent else c.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (enabled) fg else c.muted, modifier = Modifier.size(18.dp))
        Text(text, style = TitanType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (enabled) fg else c.muted)
    }
}
