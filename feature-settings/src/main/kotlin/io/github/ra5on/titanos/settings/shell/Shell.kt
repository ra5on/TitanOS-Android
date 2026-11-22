package io.github.ra5on.titanos.settings.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import io.github.ra5on.titanos.ui.components.SmallButton
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.R
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.settings.pages.Pages
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.LocalFindTarget
import io.github.ra5on.titanos.ui.components.OverlayHost
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons

/** Eine Einstellungsseite der App selbst (Fotos, Dateien, …), aus der Systemsteuerung verlinkt. */
data class AppLink(val title: String, val desc: String?, val icon: ImageVector, val onClick: () -> Unit)

/** Die Texte der Systemsteuerung (aus dem Web, feature-settings/tools/web.mjs). */
@Composable
fun rememberSettingsTr(): Tr {
    val context = LocalContext.current
    return remember { Tr.load(context, "settings-i18n") }
}

/**
 * Die Systemsteuerung: am Handy (bis 760 dp) Ebenen mit Pfeil zurück wie das
 * Web am Handy, ab 761 dp Seitenleiste und Seite wie das Web am PC.
 * [start] ist eine Adresse wie `/settings/updates` (z. B. aus einer Mitteilung).
 */
@Composable
fun SettingsApp(
    env: SettingsEnv,
    onClose: () -> Unit,
    start: String? = null,
    appLinks: List<AppLink> = emptyList(),
    nav: SettingsNav = remember { SettingsNav(start) },
) {
    val context = LocalContext.current
    val tr = rememberSettingsTr()
    val index = remember { SettingsIndex.load(context) }
    CompositionLocalProvider(LocalTr provides tr, LocalEnv provides env) {
        OverlayHost {
            val width = currentWidth()
            val phone = !width.twoPanes
            BackHandler { if (!nav.back(phone)) onClose() }
            val searchFocus = remember { FocusRequester() }
            val keys = Modifier.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.K -> { runCatching { searchFocus.requestFocus() }; true }
                    e.key == Key.Escape || (e.isAltPressed && e.key == Key.DirectionLeft) -> { if (!nav.back(phone)) onClose(); true }
                    else -> false
                }
            }
            Box(Modifier.fillMaxSize().background(Titan.colors.bg).then(keys).focusable()) {
                if (phone) PhoneLayout(env, index, nav, appLinks, onClose, searchFocus)
                else WideLayout(env, index, nav, appLinks, onClose, searchFocus)
            }
        }
    }
}

private fun titleOf(index: SettingsIndex, tr: Tr, page: String, appLinksTitle: (String) -> String?): String {
    appLinksTitle(page)?.let { return it }
    val top = page.substringBefore('/')
    val area = index.area(top) ?: return tr.t("settingshome.all")
    return tr.t(area.label)
}

@Composable
private fun appPageTitle(page: String): String? = when (page) {
    "app/look" -> stringResource(R.string.set_app_look)
    "app/guard" -> stringResource(io.github.ra5on.titanos.ui.R.string.guard_title)
    else -> null
}

@Composable
private fun PhoneLayout(env: SettingsEnv, index: SettingsIndex, nav: SettingsNav, appLinks: List<AppLink>, onClose: () -> Unit, searchFocus: FocusRequester) {
    val tr = LocalTr.current
    val c = Titan.colors
    val page = nav.page
    val appTitle = page?.let { appPageTitle(it) }
    val title = when {
        page != null -> appTitle ?: titleOf(index, tr, page) { null }
        nav.area != null -> tr.t("settingshome.group.${nav.area}")
        else -> stringResource(R.string.set_title)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(c.header).heightIn(min = 56.dp).padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(TitanIcons.back, stringResource(R.string.set_back), { if (!nav.back(true)) onClose() }, size = 24.dp)
            Text(stringResource(R.string.set_title), style = TitanType.topTitle, color = c.text, modifier = Modifier.weight(1f))
        }
        Divider()
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp).navigationBarsPadding(),
        ) {
            // Der Pfad steht genau einmal: als Überschrift der Seite.
            PathTitle(index, nav, title)
            if (page == null) {
                SearchField(nav, searchFocus)
                if (nav.query.isNotBlank()) {
                    Hits(index, env, nav)
                } else if (nav.area == null) {
                    // Eine Zeile je Bereich; ein Bereich mit nur einer Seite öffnet sie direkt.
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingRow(env.user(), desc = stringResource(R.string.set_account), icon = WebIcons["users"], onClick = { env.open("/account") })
                        for ((group, pages) in index.grouped(env.admin)) {
                            SettingRow(
                                tr.t("settingshome.group.$group"),
                                desc = pages.joinToString(" · ") { tr.t(it.label) },
                                icon = WebIcons[index.groupIcons[group] ?: "gear"],
                                onClick = { if (pages.size == 1) nav.openPage(pages[0].id, group) else nav.area = group },
                            )
                        }
                        AppGroup(appLinks, nav)
                    }
                } else {
                    val pages = index.pages(env.admin).filter { it.group == nav.area }
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (a in pages) {
                            SettingRow(
                                tr.t(a.label),
                                desc = tr.or("settingshome.desc.${a.id}", ""),
                                icon = WebIcons[a.icon],
                                badge = if (isNew(a)) tr.t("settings.new") else null,
                                onClick = { nav.openPage(a.id, a.group) },
                            )
                        }
                    }
                }
            } else {
                PageBody(page, nav)
            }
        }
    }
}

@Composable
private fun PathTitle(index: SettingsIndex, nav: SettingsNav, title: String) {
    val tr = LocalTr.current
    val c = Titan.colors
    val page = nav.page
    val parts = mutableListOf<Pair<String, (() -> Unit)?>>()
    if (page == null) {
        parts += (if (nav.area == null) tr.t("settingshome.all") else title) to null
    } else if (page.startsWith("app/")) {
        parts += stringResource(R.string.set_this_app) to { nav.page = null }
        parts += title to null
    } else {
        val top = page.substringBefore('/')
        val area = index.area(top)
        if (area != null) parts += tr.t("settingshome.group.${area.group}") to { nav.page = null; nav.area = area.group }
        if (page.contains('/')) {
            parts += title to { nav.page = top }
            parts += subTitle(page, tr) to null
        } else {
            parts += title to null
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp).semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, (text, go) ->
            val last = i == parts.lastIndex
            Text(
                text,
                style = TitanType.title.copy(fontSize = TitanType.title.fontSize * 0.95f),
                color = if (last) c.text else c.muted,
                modifier = if (go != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = go) else Modifier,
            )
            if (!last) Text("›", style = TitanType.title, color = c.muted)
        }
    }
}

/** Titel einer Unterseite (Sicherungen › Daten …). */
fun subTitle(page: String, tr: Tr): String = when (page) {
    "backups/data" -> tr.t("safeguard.data")
    "backups/applications" -> tr.t("applications.title")
    "backups/configuration" -> tr.t("safeguard.config")
    "backups/coverage" -> tr.t("coverage.title")
    "backups/restore" -> tr.t("safeguard.restore")
    "appearance/dock" -> tr.t("look.tab.dock")
    else -> page.substringAfterLast('/')
}

@Composable
private fun isNew(a: Area): Boolean {
    val since = a.newSince ?: return false
    val start = runCatching { java.time.LocalDate.parse(since.date) }.getOrNull() ?: return false
    return java.time.LocalDate.now().isBefore(start.plusDays(30))
}

@Composable
private fun SearchField(nav: SettingsNav, focus: FocusRequester, modifier: Modifier = Modifier) {
    val tr = LocalTr.current
    TextInput(
        nav.query,
        { nav.query = it },
        label = stringResource(R.string.set_search_label),
        modifier = modifier.padding(top = 6.dp).focusRequester(focus),
        leading = TitanIcons.search,
        placeholder = tr.t("settingshome.searchHint"),
    )
}

@Composable
private fun Hits(index: SettingsIndex, env: SettingsEnv, nav: SettingsNav) {
    val tr = LocalTr.current
    val hits = remember(nav.query, env.admin, tr) { SettingsSearch.find(index, nav.query, env.admin, tr) }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Wie im Web: Zahl der Treffer als Statusmeldung (TalkBack liest sie vor) und „Suche leeren“.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (hits.isEmpty()) tr.t("ui.noSettings") else tr.t("ui.results", "count" to hits.size),
                style = TitanType.body,
                color = Titan.colors.muted,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            SmallButton(tr.t("ui.clear"), { nav.query = "" })
        }
        for (h in hits) {
            SettingRow(h.label, desc = h.where, icon = WebIcons[h.icon], onClick = {
                if (h.page.startsWith("/settings")) nav.go(h) { id -> index.area(id)?.group }
                else env.open(h.page + if (h.titles.isNotEmpty()) "#find=" + h.titles.joinToString("|") { java.net.URLEncoder.encode(it, "UTF-8") } else "")
            })
        }
    }
}

@Composable
private fun AppGroup(appLinks: List<AppLink>, nav: SettingsNav) {
    SettingGroup(stringResource(R.string.set_this_app)) {
        SettingRow(stringResource(R.string.set_app_look), desc = stringResource(R.string.set_app_look_desc), icon = WebIcons["palette"], onClick = { nav.openPage("app/look") })
        SettingRow(stringResource(io.github.ra5on.titanos.ui.R.string.guard_title), desc = stringResource(R.string.set_app_guard_desc), icon = WebIcons["lock"], onClick = { nav.openPage("app/guard") })
        for (l in appLinks) SettingRow(l.title, desc = l.desc, icon = l.icon, onClick = l.onClick)
    }
}

@Composable
private fun PageBody(page: String, nav: SettingsNav) {
    val args = remember(nav) { PageArgs(nav) }
    CompositionLocalProvider(LocalFindTarget provides nav.find) {
        Column(Modifier.fillMaxWidth()) { Pages.Show(page, args) }
    }
}

@Composable
private fun WideLayout(env: SettingsEnv, index: SettingsIndex, nav: SettingsNav, appLinks: List<AppLink>, onClose: () -> Unit, searchFocus: FocusRequester) {
    val tr = LocalTr.current
    val c = Titan.colors
    var collapsed by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (!collapsed) {
            Column(
                Modifier.width(290.dp).fillMaxHeight().background(c.header).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(TitanIcons.back, stringResource(R.string.set_back), onClose, size = 22.dp)
                    Text(stringResource(R.string.set_title), style = TitanType.topTitle, color = c.text, modifier = Modifier.weight(1f))
                    IconButton(WebIcons["sidebar"], tr.t("desktop.window.fold"), { collapsed = true }, size = 20.dp)
                }
                AccountHeader(env)
                SearchField(nav, searchFocus)
                if (nav.query.isNotBlank()) {
                    Hits(index, env, nav)
                } else {
                    SideItem(tr.t("settingshome.overview"), WebIcons["home"], nav.page == null) { nav.page = null; nav.area = null }
                    for ((group, pages) in index.grouped(env.admin)) {
                        val open = nav.area == group || pages.any { it.id == nav.page?.substringBefore('/') }
                        SideItem(tr.t("settingshome.group.$group"), WebIcons[index.groupIcons[group] ?: "gear"], false, expandable = true, open = open) {
                            nav.area = if (nav.area == group) null else group
                        }
                        if (open) for (a in pages) {
                            SideItem(tr.t(a.label), WebIcons[a.icon], nav.page?.substringBefore('/') == a.id, indent = true) { nav.openPage(a.id, a.group) }
                        }
                    }
                    Divider(Modifier.padding(vertical = 6.dp))
                    Text(stringResource(R.string.set_this_app), style = TitanType.section, color = c.muted, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 4.dp))
                    SideItem(stringResource(R.string.set_app_look), WebIcons["palette"], nav.page == "app/look") { nav.openPage("app/look") }
                    SideItem(stringResource(io.github.ra5on.titanos.ui.R.string.guard_title), WebIcons["lock"], nav.page == "app/guard") { nav.openPage("app/guard") }
                    for (l in appLinks) SideItem(l.title, l.icon, false, onClick = l.onClick)
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (collapsed) {
                Row(Modifier.padding(6.dp)) { IconButton(WebIcons["sidebar"], tr.t("desktop.window.unfold"), { collapsed = false }, size = 20.dp) }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            ) {
                Column(Modifier.widthIn(max = 1100.dp)) {
                    val page = nav.page
                    if (page == null) {
                        PathTitle(index, nav.also { }, tr.t("settingshome.all"))
                        Pages.Overview(PageArgs(nav), wide = true)
                    } else {
                        PathTitle(index, nav, appPageTitle(page) ?: titleOf(index, tr, page) { null })
                        PageBody(page, nav)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountHeader(env: SettingsEnv) {
    val c = Titan.colors
    val role = when (env.role()) {
        "owner" -> stringResource(R.string.set_role_owner)
        "admin" -> stringResource(R.string.set_role_admin)
        else -> stringResource(R.string.set_role_user)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { env.open("/account") }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Text(env.user().take(1).uppercase(), style = TitanType.rowTitle, color = c.onAccent)
        }
        Column(Modifier.weight(1f)) {
            Text(env.user(), style = TitanType.rowTitle, color = c.text)
            Text("$role · ${stringResource(R.string.set_account)}", style = TitanType.rowSub, color = c.muted)
        }
    }
}

@Composable
private fun SideItem(text: String, icon: ImageVector, current: Boolean, indent: Boolean = false, expandable: Boolean = false, open: Boolean = false, onClick: () -> Unit) {
    val c = Titan.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (current) c.accent.copy(alpha = .16f).compositeOver(c.header) else c.header)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (current) stateDescription = "✓" }
            .heightIn(min = 44.dp).padding(start = if (indent) 30.dp else 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = if (current) c.accent else c.muted, modifier = Modifier.size(18.dp))
        Text(text, style = TitanType.body.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal), color = c.text, modifier = Modifier.weight(1f))
        if (expandable) Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(16.dp).rotate(if (open) 180f else 0f))
    }
}

/** Inhalt einer Seite mit Spalte, wenn eine Seite selbst keine hat. */
@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxWidth(), content = content)
