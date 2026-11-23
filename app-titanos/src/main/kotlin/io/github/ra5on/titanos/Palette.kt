package io.github.ra5on.titanos

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ra5on.titanos.compute.webText
import io.github.ra5on.titanos.admin.adminText
import io.github.ra5on.titanos.home.programLabelRes
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.shell.SettingsIndex
import io.github.ra5on.titanos.settings.shell.SettingsSearch
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URLEncoder
import java.text.Normalizer

/*
 * „Schnell finden“ wie die Befehlspalette im Web (web/src/lib/command-palette.ts,
 * CommandPalette.svelte): Seiten und Programme nach Rolle, jede Einstellung aus
 * dem Suchindex der Systemsteuerung (feature-settings, dieselben 604 Einträge),
 * Freigaben, Docker-Apps, VMs und LXC. Nur Navigation, nichts ändert sich.
 * Strg/⌘+K und das Suchfeld oben auf der Startseite öffnen sie, am Arbeitsplatz
 * ebenso (dort statt der eigenen Suche im Hauptmenü). Treffer öffnen über den
 * Router (Links.kt), am Handy wie am Arbeitsplatz.
 */

/** Ein Eintrag wie PaletteEntry im Web: [href] ist die Adresse des Webs (oder `app:…` für Seiten nur der App). */
internal data class PaletteEntry(val href: String, val label: String, val section: String, val terms: String = "")

/** Die Seiten wie `pages` in command-palette.ts (Beschriftung als Schlüssel des Webs). */
internal data class PalettePage(val href: String, val key: String, val admin: Boolean = false)

internal val PALETTE_PAGES = listOf(
    PalettePage("/", "desktop.phone.home"),
    PalettePage("/storage", "nav.storage", true),
    PalettePage("/shares", "nav.shares", true),
    PalettePage("/files", "nav.files"),
    PalettePage("/users", "nav.users", true),
    PalettePage("/docker", "nav.docker", true),
    PalettePage("/vms", "nav.vms", true),
    PalettePage("/lxc", "nav.lxc", true),
    PalettePage("/apps", "nav.apps", true),
    PalettePage("/plugins", "nav.plugins", true),
    PalettePage("/nvr", "nvr.search.live"),
    PalettePage("/nvr/recordings", "nvr.search.recordings"),
    PalettePage("/nvr/events", "nvr.search.events"),
    PalettePage("/nvr/cameras", "nvr.search.cameras", true),
    PalettePage("/nvr/cameras?new=1", "nvr.search.add", true),
    PalettePage("/nvr/settings", "nvr.search.settings", true),
    PalettePage("/nvr/diagnosis", "nvr.search.diagnosis", true),
    PalettePage("/settings", "nav.settings"),
    PalettePage("/tools", "nav.tools", true),
    PalettePage("/tools/logs", "tools.logs", true),
    PalettePage("/tools/tasks", "ops.tasks", true),
    PalettePage("/tools/taskmanager", "taskmanager.title"),
    PalettePage("/tools/maintenance", "ops.maintenance", true),
    PalettePage("/tools/scripts", "scripts.title", true),
    PalettePage("/tools/monitoring", "ops.monitoring", true),
    PalettePage("/tools/activity", "ops.activity", true),
    PalettePage("/tools/hardware", "hwcheck.title", true),
    PalettePage("/tools/disks", "disktest.title", true),
    PalettePage("/tools/bundle", "tools.bundle", true),
    PalettePage("/storage/import", "ops.import", true),
    PalettePage("/storage/manage", "ops.usage", true),
    PalettePage("/storage/smart", "smart.title", true),
    PalettePage("/account", "menu.account"),
)

/**
 * Programme der Startseite, die das Web nicht als Seite der Palette führt
 * (Mail, Kalender, Kontakte, KI-Agent, Terminal …): ihre Adresse im Web.
 */
internal fun programHref(id: String): String = when (id) {
    "backup" -> "/settings/backups"
    "security" -> "/settings/firewall"
    // Das Web leitet /logs dorthin um; so steht „Protokolle“ nur einmal da.
    "logs" -> "/tools/logs"
    else -> "/$id"
}

/** Seiten nur der App (Menü der Startseite): VPN, NAS und Konten, Abgleich, Arbeitsplatz, Über die App. */
internal val APP_PAGES = listOf("app:vpn", "app:naslist", "app:pimsync", "app:workspace", "app:about")

/**
 * Seiten, Programme und Bereiche der Systemsteuerung, die das Konto öffnen darf
 * (palettePages im Web); die Liste der Bereiche teilt sie mit der Systemsteuerung.
 */
internal fun palettePages(
    admin: Boolean,
    web: (String) -> String,
    index: SettingsIndex,
    settings: Tr,
    programs: List<Pair<String, String>> = emptyList(),
    appPages: List<Pair<String, String>> = emptyList(),
): List<PaletteEntry> {
    val pages = PALETTE_PAGES.filter { !it.admin || admin }.map { PaletteEntry(it.href, web(it.key), web("palette.pages")) }
    val known = pages.mapTo(HashSet()) { it.href }
    val extra = programs.map { (id, label) -> PaletteEntry(programHref(id), label, web("palette.pages"), id) }
        .filter { known.add(it.href) }
    val areas = index.pages(admin).map { a -> PaletteEntry("/settings/${a.id}", settings.t(a.label), web("nav.settings"), "${a.id} ${a.keywords}") }
        .filter { known.add(it.href) }
    return pages + extra + areas + appPages.map { (href, label) -> PaletteEntry(href, label, "", href.removePrefix("app:")) }
}

/**
 * Einzelne Einstellungen zu einer Suche (paletteSettings im Web): Reiter,
 * Gruppen, Zeilen und Teile von Zeilen aus dem Suchindex, höchstens 40; nur
 * mit Suchwort. Die Adresse trägt Reiter und Fundstelle (`?tab=`, `#find=`)
 * wie die Suche der Systemsteuerung (SettingsNav.openPath).
 */
internal fun paletteSettings(query: String, admin: Boolean, settings: Tr, index: SettingsIndex): List<PaletteEntry> {
    if (query.isBlank()) return emptyList()
    return SettingsSearch.find(index, query, admin, settings, 40)
        .filter { it.kind != "page" }
        .map { hit ->
            val tab = hit.tab?.takeIf { hit.page.startsWith("/settings") }?.let { "?tab=" + enc(it) }.orEmpty()
            val find = if (hit.titles.isNotEmpty()) "#find=" + hit.titles.joinToString("|") { enc(it) } else ""
            PaletteEntry(hit.page + tab + find, hit.label, hit.where)
        }
}

private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

private val MARKS = Regex("\\p{M}+")

private fun normalize(s: String) = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase()

/** Wie findPaletteEntries im Web: jedes Wort kommt in Name, Bereich oder Stichwörtern vor; höchstens 60. */
internal fun findPaletteEntries(entries: List<PaletteEntry>, query: String): List<PaletteEntry> {
    val words = normalize(query).trim().split(Regex("\\s+"))
    return entries.filter { e -> normalize("${e.label} ${e.section} ${e.terms}").let { hay -> words.all { hay.contains(it) } } }.take(60)
}

/** Zuletzt aus der Palette geöffnete Adressen, neueste zuerst; nur im Speicher (wie palette.svelte.ts). */
internal object PaletteRecent {
    val list = mutableStateListOf<String>()

    fun remember(href: String) {
        list.remove(href)
        list.add(0, href)
        while (list.size > 5) list.removeAt(list.size - 1)
    }
}

/** Die Einträge in der Reihenfolge des Webs: zuletzt geöffnet (ohne Suche), Treffer, einzelne Einstellungen; höchstens 80. */
internal fun paletteEntries(all: List<PaletteEntry>, query: String, recent: List<String>, recentLabel: String, settings: List<PaletteEntry>): List<PaletteEntry> {
    val first = if (query.isBlank()) recent.mapNotNull { h -> all.firstOrNull { it.href == h } }.map { it.copy(section = recentLabel) } else emptyList()
    val hits = findPaletteEntries(all, query).filter { e -> first.none { it.href == e.href } }
    return (first + hits + settings).take(80)
}

/** Freigaben, Docker-Apps, VMs und LXC (wie load() im Web); [Pair.second]: nicht alles kam an. */
internal suspend fun loadPaletteDynamic(graph: AppGraph, admin: Boolean, web: (String) -> String): Pair<List<PaletteEntry>, Boolean> = coroutineScope {
    val api = graph.connection()?.let { SettingsApi(it) }
    val roots = async { runCatching { graph.api!!.roots() } }
    val docker = async { if (admin && api != null) runCatching { api.obj("docker") } else null }
    val vms = async { if (admin && api != null) runCatching { api.obj("vms") } else null }
    val lxc = async { if (admin && api != null) runCatching { api.obj("lxc") } else null }
    val out = ArrayList<PaletteEntry>()
    roots.await().getOrNull()?.filter { it.kind == "share" }?.forEach { out += PaletteEntry(filesLink(it.id, ""), it.name, web("nav.shares")) }
    fun JsonObject.list(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    docker.await()?.getOrNull()?.let { d ->
        val seen = HashSet<String>()
        for (c in d.list("containers")) {
            val app = c.str("app")
            if ((c["managed"] as? JsonPrimitive)?.booleanOrNull == true && app.isNotEmpty() && seen.add(app)) out += PaletteEntry("/docker/apps/" + enc(app), app, web("nav.docker"))
        }
    }
    vms.await()?.getOrNull()?.let { v -> for (vm in v.list("vms")) out += PaletteEntry("/vms?vm=" + enc(vm.str("name")), vm.str("displayName").ifEmpty { vm.str("name") }, web("nav.vms")) }
    lxc.await()?.getOrNull()?.let { l -> for (c in l.list("containers")) out += PaletteEntry("/lxc?ct=" + enc(c.str("name")), c.str("displayName").ifEmpty { c.str("name") }, web("nav.lxc")) }
    val partial = roots.await().isFailure || listOf(docker, vms, lxc).any { it.await()?.isFailure == true }
    out to partial
}

/**
 * Texte des Webs für die Palette: die Tabellen von core-compute, core-admin
 * und der Systemsteuerung enthalten je einen Teil der Schlüssel des Webs.
 */
internal fun paletteText(context: Context, settings: Tr): (String) -> String = { key ->
    context.webText(key).takeIf { it != key }
        ?: context.adminText(key).takeIf { it != key }
        ?: settings.t(key)
}

/** Strg/⌘+K (ohne Alt und Umschalt). */
internal fun isPaletteKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean =
    e.type == KeyEventType.KeyDown && e.key == Key.K && (e.isCtrlPressed || e.isMetaPressed) && !e.isAltPressed && !e.isShiftPressed

/**
 * Öffnet einen Treffer: Seiten der App über die Schalter des AppGraph, alles
 * andere über den Router (Links.kt). [home] führt zur Startseite (am
 * Arbeitsplatz gibt es keine), [later] sagt, dass es das nur im Web gibt.
 */
internal fun openPaletteEntry(context: Context, graph: AppGraph, href: String, home: () -> Unit, later: () -> Unit) {
    when (href) {
        "/" -> home()
        "app:vpn" -> graph.openVpn = true
        "app:naslist" -> graph.openNasList = true
        "app:about" -> graph.openAbout = true
        "app:pimsync" -> { graph.programLink = null; graph.openProgram = PIM_SYNC }
        "app:workspace" -> { graph.programLink = null; graph.openProgram = WORKSPACE_SETTINGS }
        else -> openLink(context, graph, href, later)
    }
}

/**
 * Die Palette als Fenster über allem (Handy und Arbeitsplatz). Gewählt wird
 * mit Tippen oder ↑ ↓ und Enter; die Oberfläche öffnet den Treffer über
 * [AppGraph.paletteLink].
 */
@Composable
internal fun CommandPalette(graph: AppGraph, onClose: () -> Unit) {
    val context = LocalContext.current
    val role = graph.sessions.user?.role ?: "user"
    val admin = role == "owner" || role == "admin"
    val settingsTr = remember { Tr.load(context, "settings-i18n") }
    val index = remember { SettingsIndex.load(context) }
    val web: (String) -> String = remember(context, settingsTr) { paletteText(context, settingsTr) }
    val appPages = APP_PAGES.map { href ->
        href to when (href) {
            "app:vpn" -> stringResource(io.github.ra5on.titanos.vpn.R.string.vpn_title)
            "app:naslist" -> stringResource(R.string.nas_title)
            "app:pimsync" -> stringResource(io.github.ra5on.titanos.pim.R.string.sync_title)
            "app:workspace" -> stringResource(io.github.ra5on.titanos.workspace.R.string.ws_title)
            else -> stringResource(io.github.ra5on.titanos.home.R.string.home_menu_about)
        }
    }
    val appSection = stringResource(R.string.palette_app)
    val programs = graph.paletteProgramIds.map { it to stringResource(programLabelRes(it)) }
    val static = remember(admin, programs, appPages) {
        palettePages(admin, web, index, settingsTr, programs, appPages).map { if (it.href.startsWith("app:")) it.copy(section = appSection) else it }
    }
    var dynamic by remember { mutableStateOf<List<PaletteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var partial by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loading = true
        val (list, failed) = loadPaletteDynamic(graph, admin, web)
        dynamic = list
        partial = failed
        loading = false
    }
    var query by remember { mutableStateOf("") }
    val all = static + dynamic
    val entries = paletteEntries(all, query, PaletteRecent.list, web("palette.recent"), remember(query, admin) { paletteSettings(query, admin, settingsTr, index) })
    fun pick(e: PaletteEntry) {
        PaletteRecent.remember(e.href)
        onClose()
        graph.paletteLink = e.href
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PaletteContent(
            query = query,
            onQuery = { query = it },
            entries = entries,
            loading = loading,
            partial = partial,
            onRetry = { attempt++ },
            onPick = ::pick,
            onClose = onClose,
            text = web,
        )
    }
}

/** Die Oberfläche der Palette (ohne Daten, auch für die Bildtests). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaletteContent(
    query: String,
    onQuery: (String) -> Unit,
    entries: List<PaletteEntry>,
    loading: Boolean,
    partial: Boolean,
    onRetry: () -> Unit,
    onPick: (PaletteEntry) -> Unit,
    onClose: () -> Unit,
    text: (String) -> String,
    focus: Boolean = true,
) {
    val c = Titan.colors
    var current by remember(query) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val field = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    // Neue Suche: wieder oben anfangen (sonst hielte die Liste den alten ersten Treffer fest, der jetzt weiter unten steht).
    LaunchedEffect(query) { runCatching { list.scrollToItem(0) } }
    LaunchedEffect(current) { if (entries.isNotEmpty()) runCatching { list.animateScrollToItem(current.coerceIn(0, entries.size - 1)) } }
    val shape = RoundedCornerShape(16.dp)
    val closeLabel = stringResource(io.github.ra5on.titanos.ui.R.string.ui_close)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.matchParentSize().background(Color.Black.copy(alpha = .5f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                .semantics { contentDescription = closeLabel },
        )
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(12.dp).widthIn(max = 640.dp).fillMaxWidth()
                .clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text("palette.title"), style = TitanType.topTitle, color = c.text, modifier = Modifier.semantics { heading() })
            TextInput(
                query,
                onQuery,
                text("palette.search"),
                Modifier.focusRequester(field).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.DirectionDown -> { current = (current + 1).coerceAtMost((entries.size - 1).coerceAtLeast(0)); true }
                        e.key == Key.DirectionUp -> { current = (current - 1).coerceAtLeast(0); true }
                        e.key == Key.Enter || e.key == Key.NumPadEnter -> { entries.getOrNull(current)?.let(onPick); true }
                        e.key == Key.Escape || isPaletteKey(e) -> { onClose(); true }
                        else -> false
                    }
                },
                leading = TitanIcons.search,
                placeholder = text("palette.search"),
                keyboard = KeyboardOptions(imeAction = ImeAction.Go),
            )
            Text(text("palette.keys").replace("{open}", text("shortcuts.ctrl") + "+K"), style = TitanType.small, color = c.muted)
            if (loading) Text(text("common.loading"), style = TitanType.small, color = c.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (partial) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(text("palette.partial"), style = TitanType.body, color = c.warnText)
                    TitanButton(text("common.retry"), onRetry, enabled = !loading)
                }
            }
            LazyColumn(
                Modifier.weight(1f, fill = false).heightIn(max = 520.dp).semantics { contentDescription = text("palette.results") },
                state = list,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(entries, key = { _, e -> e.section + "\n" + e.href + "\n" + e.label }) { i, e ->
                    val on = i == current
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) c.chip else Color.Transparent)
                            .clickable(role = Role.Button) { onPick(e) }
                            .semantics(mergeDescendants = true) { selected = on }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(io.github.ra5on.titanos.ui.breakWords(e.label), style = TitanType.body, color = c.text)
                        if (e.section.isNotEmpty()) Text(io.github.ra5on.titanos.ui.breakAtHyphens(e.section), style = TitanType.small, color = c.muted)
                    }
                }
            }
            if (entries.isEmpty()) Text(text("palette.empty"), style = TitanType.body, color = c.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TitanButton(text("common.cancel"), onClose, kind = ButtonKind.Normal)
            }
        }
    }
}
