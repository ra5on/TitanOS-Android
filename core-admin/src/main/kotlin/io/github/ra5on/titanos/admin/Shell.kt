package io.github.ra5on.titanos.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.Width
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType

/** Ein Eintrag der Seitenleiste eines Programms (wie `side` in apps.ts). */
data class SideEntry(val id: String, val label: String, val icon: String, val action: Boolean = false)

/** Eine Gruppe der Seitenleiste; [title] null für die erste ohne Überschrift. */
data class SideGroup(val title: String?, val entries: List<SideEntry>)

/**
 * Rahmen eines Programms (Speicher, Freigaben, …) wie das Web:
 * - bis 760 dp (Phone) und Split: Kopfleiste mit Zurück, Programmsymbol und
 *   Titel; die übrigen Seiten des Programms im Menü „⋯“ (Ebenen am Handy),
 * - ab Wide: Seitenleiste links wie das Fenster am PC, rechts der Pfad
 *   „Programm › Seite“ mit den Aktionen.
 *
 * [onUp] führt eine Ebene zurück (Detail → Liste). Sonst führt Zurück zur
 * zuvor gewählten Seite des Programms (Seiten als Ebenen wie am Handy im
 * Web: Speicher › Importieren → Zurück → Übersicht) und erst auf der ersten
 * Seite aus dem Programm hinaus ([onExit]); mit [ownHistory] führt das
 * Programm den Verlauf selbst. [onKey] bekommt Tastendrücke der ganzen Seite
 * (Tastenkürzel wie im Web), gibt true zurück, wenn sie sie verbraucht.
 */
@Composable
fun AdminShell(
    glyph: String,
    gradient: String,
    program: String,
    groups: List<SideGroup>,
    current: String,
    onSelect: (String) -> Unit,
    onExit: () -> Unit,
    page: String = groups.flatMap { it.entries }.firstOrNull { it.id == current }?.label ?: program,
    onUp: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onKey: ((KeyEvent) -> Boolean)? = null,
    /** Das Programm führt seinen Seitenverlauf selbst (Werkzeuge) und meldet ihn über [onUp]. */
    ownHistory: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val width = currentWidth()
    val c = Titan.colors
    // Verlauf der im Menü/in der Seitenleiste gewählten Seiten (Einträge mit action öffnen anderes, kein Verlauf).
    // Die Lambdas entstehen immer (nicht in einem Zweig): der Compose-Compiler merkt sie sich in Gruppen,
    // bedingt angelegt verrutschten sonst die Plätze dahinter und die Seite verlor ihren Zustand.
    var history by rememberSaveable { mutableStateOf("") }
    val stack = history.split('\n').filter { it.isNotEmpty() }
    val select: (String) -> Unit = { id ->
        val action = groups.flatMap { it.entries }.firstOrNull { it.id == id }?.action == true
        if (id != current && !action && !ownHistory) history = (history.split('\n').filter { it.isNotEmpty() } + current).joinToString("\n")
        onSelect(id)
    }
    val pop: () -> Unit = {
        val s = history.split('\n').filter { it.isNotEmpty() }
        if (s.isNotEmpty()) {
            history = s.dropLast(1).joinToString("\n")
            onSelect(s.last())
        }
    }
    val back: () -> Unit = when {
        onUp != null -> onUp
        stack.isNotEmpty() -> pop
        else -> onExit
    }
    BackHandler { back() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val keys = Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e -> onKey?.invoke(e) == true }
    if (width.sidebar) {
        Row(Modifier.fillMaxSize().background(c.bg).then(keys)) {
            Sidebar(glyph, gradient, program, groups, current, select, onExit)
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
            Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().background(c.header).heightIn(min = 56.dp).padding(start = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onUp != null) IconButton(TitanIcons.back, stringResource(R.string.admin_back), onUp, size = 22.dp)
                    // Pfad „Programm › Seite“; bricht um, wenn er nicht passt.
                    Text(
                        if (page == program) program else "$program › $page",
                        style = TitanType.topTitle,
                        color = c.text,
                        modifier = Modifier.weight(1f).padding(start = if (onUp == null) 8.dp else 0.dp, top = 8.dp, bottom = 8.dp).semantics { heading() },
                    )
                    actions()
                }
                Divider()
                Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), content = content)
            }
        }
    } else {
        var menu by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(c.bg).then(keys).imePadding()) {
            Box(Modifier.background(c.header).statusBarsPadding()) {
                TopBar(
                    page,
                    onBack = back,
                    backLabel = stringResource(R.string.admin_back),
                    icon = if (onUp == null && stack.isEmpty()) ({ AppIcon(glyph, gradient, 30.dp) }) else null,
                ) {
                    actions()
                    if (groups.sumOf { it.entries.size } > 1) {
                        IconButton(TitanIcons.more, t("ui.more"), { menu = true })
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), content = content)
        }
        if (menu) {
            AdminSheet(program, onDismiss = { menu = false }) {
                SideList(groups, current) { menu = false; select(it) }
            }
        }
    }
}

@Composable
private fun Sidebar(
    glyph: String,
    gradient: String,
    program: String,
    groups: List<SideGroup>,
    current: String,
    onSelect: (String) -> Unit,
    onExit: () -> Unit,
) {
    val c = Titan.colors
    Column(
        Modifier.width(280.dp).fillMaxHeight().background(c.header).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(TitanIcons.back, stringResource(R.string.admin_back), onExit, size = 22.dp)
            AppIcon(glyph, gradient, 32.dp)
            Text(program, style = TitanType.topTitle, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
        }
        SideList(groups, current, onSelect)
    }
}

/** Die Einträge der Seitenleiste; dieselbe Liste steht am Handy im Menü „⋯“. */
@Composable
fun SideList(groups: List<SideGroup>, current: String, onSelect: (String) -> Unit) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth()) {
        for (g in groups) {
            if (g.title != null) {
                Text(
                    g.title.uppercase(uiLocale()),
                    style = TitanType.section,
                    color = c.muted,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp).semantics { heading() },
                )
            }
            for (e in g.entries) {
                val on = e.id == current
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) c.accent.copy(alpha = .16f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(e.id) }
                        .semantics { selected = on }
                        .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    WebIcon(e.icon, tint = if (on) c.accent else c.muted)
                    Text(e.label, style = TitanType.body, color = if (on) c.accent else c.text, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Liste und Inhalt: am Handy eine Ebene nach der anderen (erst die Liste,
 * mit Auswahl der Inhalt), ab Split nebeneinander. [detail] null: nichts
 * gewählt (ab Split steht dann [empty] rechts).
 */
@Composable
fun ListDetail(
    list: @Composable BoxScope.() -> Unit,
    detail: (@Composable BoxScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    listWidth: Dp = 360.dp,
    empty: @Composable BoxScope.() -> Unit = {},
) {
    val width = currentWidth()
    if (!width.twoPanes) {
        Box(modifier.fillMaxSize()) { if (detail != null) detail() else list() }
        return
    }
    Row(modifier.fillMaxSize()) {
        Box(Modifier.width(if (width == Width.Split) 320.dp else listWidth).fillMaxHeight(), content = list)
        Box(Modifier.width(1.dp).fillMaxHeight().background(Titan.colors.border))
        Box(Modifier.weight(1f).fillMaxHeight()) { if (detail != null) detail() else empty() }
    }
}

/** Scrollender Inhalt einer Seite mit Rand; auf breiten Bildschirmen höchstens 980 dp breit. */
@Composable
fun PageBody(modifier: Modifier = Modifier, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 980.dp).fillMaxWidth()
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
