package io.github.ra5on.titanos.plugins

import io.github.ra5on.titanos.admin.breakAfterHyphens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.ListDetail
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.admin.uiLocale
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.launch

@Composable
internal fun lang(): String = uiLocale().language

@Composable
internal fun Mono(text: String, modifier: Modifier = Modifier, color: Color = Titan.colors.text, style: androidx.compose.ui.text.TextStyle = TitanType.body) {
    Text(text, style = style.copy(fontFamily = TitanFonts.mono), color = color, modifier = modifier)
}

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * 0.8f), color = Titan.colors.text, modifier = modifier.semantics { heading() })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipLine(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) { content() }
}

/** Die Kachel eines Plugins: zwei Zeichen auf einem festen Farbton (nur Schmuck). */
@Composable
internal fun Monogram(p: Plugin, size: Dp = 44.dp) {
    val shape = RoundedCornerShape(size / 4)
    Box(
        Modifier.size(size).clip(shape).background(Color.hsl(hue(p).toFloat(), .45f, .36f)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(monogram(p), style = TitanType.rowTitle.copy(fontSize = TitanType.rowTitle.fontSize * (size.value / 44f)), color = Color.White)
    }
}

@Composable
internal fun badgeText(b: Badge): Pair<String, Tone> = when (b) {
    Badge.Incompatible -> t("plugins.incompatible") to Tone.Warn
    Badge.Available -> t("plugins.available") to Tone.Neutral
    Badge.NotLoaded -> t("plugins.notLoaded") to Tone.Warn
    Badge.Active -> t("plugins.active") to Tone.Ok
    Badge.Waiting -> t("plugins.waiting") to Tone.Neutral
    Badge.Off -> t("plugins.off") to Tone.Neutral
}

@Composable
internal fun originText(p: Plugin, state: PluginsState): String =
    t(if (p.local) "plugins.driverLocal" else if (p.official) "plugins.official" else "plugins.community") +
        (p.source?.let { " · " + state.sourceName(it) } ?: "")

@Composable
internal fun ErrorNotice(e: Throwable, modifier: Modifier = Modifier) {
    Notice(LocalContext.current.pluginError(e), modifier, tone = Tone.Error)
}

/** Plugins (routes/plugins): Liste mit Empfehlungen und Bereichen, daneben das gewählte Plugin. */
@Composable
internal fun PluginsPage(env: AdminEnv, state: PluginsState, pollMs: Long) {
    val width = currentWidth()
    LaunchedEffect(state) {
        state.loadVolumes()
        state.load()
        state.refreshCatalog(false)
    }
    // Läuft etwas, folgt die Seite seinen Schritten jede Sekunde.
    val follow = state.busy || state.working
    Polling(follow, if (follow && pollMs > 0) 1000 else 0) { if (state.busy || state.working) state.loadProgress() }
    val status = state.status
    val current = state.plugins.firstOrNull { it.name == state.picked } ?: if (width.twoPanes) state.plugins.firstOrNull() else null
    ListDetail(
        list = { PluginList(state) },
        detail = if (status != null && current != null) ({ PluginDetail(env, state, status, current, pollMs) }) else null,
        listWidth = 420.dp,
    )
    PluginDialogs(state)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginList(state: PluginsState) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val status = state.status
    val language = lang()
    PageBody {
        ButtonRow {
            ActionButton(t(if (state.refreshing) "plugins.catalogLoading" else "plugins.catalogRefresh"), { scope.launch { state.refreshCatalog(true) } }, enabled = !state.busy && !state.refreshing, icon = "refresh")
            ActionButton(t("plugins.sourceList"), { state.error = null; state.sourcesOpen = true }, enabled = !state.locked, icon = "list")
            ActionButton(t("plugins.fromLink"), {
                state.error = null
                state.linkURL = ""
                state.linkPreview = null
                state.activate = true
                state.volume = state.defaultVolume()
                state.linking = true
            }, enabled = !state.locked && state.volumes.isNotEmpty(), icon = "external")
        }
        Text(t("plugins.intro") + " " + t("nav.apps"), style = TitanType.body, color = c.muted)
        Text(t("plugins.catalogIntro"), style = TitanType.body, color = c.muted)
        when (status?.catalog?.state) {
            "development" -> Notice(t("plugins.catalogDevelopment"), tone = Tone.Warn)
            "unavailable" -> Notice(t("plugins.catalogUnavailable", "version" to status.catalog.release), tone = Tone.Warn)
            "error" -> Notice(t("plugins.catalogError"), tone = Tone.Warn) {
                status.catalog.error?.takeIf { it.isNotEmpty() }?.let { Mono(it, style = TitanType.small) }
            }
        }
        val dialogOpen = state.asking || state.removing != null || state.updating != null || state.linking || state.sourcesOpen
        if (!dialogOpen) state.error?.let { ErrorNotice(it) }

        if (status != null && state.recommendations.isNotEmpty()) {
            SettingGroup(t("plugins.recommendedTitle"), desc = t("plugins.recommendedIntro")) {
                state.recommendations.forEachIndexed { i, r ->
                    val offered = state.plugins.firstOrNull { it.name == r.plugin }
                    SettingRow(
                        breakAfterHyphens(offered?.title ?: r.title),
                        icon = "chip",
                        divider = i > 0,
                        below = {
                            StatusChip(t("plugins.category.${categoryOf(r.category)}"), Tone.Neutral, Modifier.padding(vertical = 4.dp))
                            for (d in r.devices) {
                                Text(d.name, style = TitanType.rowSub, color = c.muted)
                                d.address?.let { Mono(it, style = TitanType.small, color = c.muted) }
                            }
                            if (offered == null) Text(t("plugins.recommendedUnavailable"), style = TitanType.rowSub, color = c.muted)
                            ButtonRow(Modifier.padding(top = 8.dp)) {
                                ActionButton(t("plugins.recommendedShow"), {
                                    if (!state.busy && offered != null) {
                                        state.query = ""
                                        state.area = ""
                                        state.picked = r.plugin
                                        if (!offered.installed) scope.launch { openInstall(state, offered) }
                                    }
                                }, primary = true, enabled = !state.busy && offered != null)
                                ActionButton(t("plugins.recommendedDismiss"), { scope.launch { state.run { state.api.dismissRecommendation(r.plugin) } } }, enabled = !state.busy)
                            }
                        },
                    )
                }
            }
        }
        status?.dismissed?.takeIf { it > 0 }?.let { n ->
            ActionButton(t("plugins.recommendedRestore", "count" to n), { scope.launch { state.run { state.api.restoreRecommendations() } } }, enabled = !state.busy)
        }

        when {
            status == null && state.error == null -> Loading()
            status == null -> {}
            state.plugins.isEmpty() -> Notice(
                if (state.refreshing) t("plugins.catalogLoading") else t("plugins.emptyText") + " " + t("plugins.emptyOfficial"),
                title = if (state.refreshing) null else t("plugins.emptyTitle"),
                tone = Tone.Neutral,
            )
            else -> {
                Field(t("plugins.search"), state.query, { state.query = it })
                val used = CATEGORIES.filter { id -> state.plugins.any { categoryOf(it.category) == id } }
                Card {
                    ChoiceRow(
                        t("plugins.categoryAll"),
                        listOf("" to t("plugins.categoryAll")) + used.map { it to t("plugins.category.$it") },
                        state.area,
                        { state.area = it },
                        icon = "filter",
                    )
                }
                val shown = state.plugins.filter { (state.area.isEmpty() || categoryOf(it.category) == state.area) && matches(it, state.query, language) }
                val groups = CATEGORIES.map { id -> id to shown.filter { categoryOf(it.category) == id } }.filter { it.second.isNotEmpty() }
                if (groups.isEmpty()) Text(t("plugins.noMatch"), style = TitanType.body, color = c.muted)
                val selected = state.plugins.firstOrNull { it.name == state.picked }?.name ?: if (currentWidth().twoPanes) state.plugins.firstOrNull()?.name else null
                for ((id, list) in groups) {
                    Text(
                        t("plugins.category.$id").uppercase(uiLocale()),
                        style = TitanType.section,
                        color = c.muted,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
                    )
                    for (p in list) PluginTile(state, p, p.name == selected)
                }
                Divider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t("plugins.statCount", "count" to state.plugins.size), style = TitanType.small, color = c.muted)
                    Text("${t("plugins.statInstalled")} ${state.plugins.count { it.installed }}", style = TitanType.small, color = c.muted)
                    Text("${t("plugins.statUpdates")} ${state.plugins.count { it.update != null }}", style = TitanType.small, color = if (state.plugins.any { it.update != null }) c.warnInk else c.muted)
                    Text(if (status.volume.isNotEmpty()) t("plugins.where", "volume" to status.volume) else t("plugins.whereNone"), style = TitanType.small, color = c.muted)
                }
            }
        }
    }
}

@Composable
private fun PluginTile(state: PluginsState, p: Plugin, selected: Boolean) {
    val c = Titan.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) c.accent.copy(alpha = .10f) else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.border, shape)
            .clickable(role = Role.Button) {
                if (!state.busy) {
                    state.picked = p.name
                    state.asking = false
                    state.error = null
                }
            }
            .semantics { this.selected = selected }
            .heightIn(min = 64.dp).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Monogram(p)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(breakAfterHyphens(p.title), style = TitanType.rowTitle, color = c.text)
            Text(originText(p, state), style = TitanType.rowSub, color = c.muted)
            Text(breakAfterHyphens(localized(p.description, lang())), style = TitanType.body, color = c.text)
            ChipLine {
                if (state.fits(p)) StatusChip(t("plugins.fits"), Tone.Ok)
                if (p.update != null) StatusChip(t("plugins.updateChip"), Tone.Info)
                val (text, tone) = badgeText(badge(p))
                StatusChip(text, tone)
            }
        }
    }
}

/** Öffnet das Formular „Installieren“ (Hinweis auf volle Systemrechte, Volume, Einschalten). */
internal suspend fun openInstall(state: PluginsState, p: Plugin) {
    state.clearFinished(p.name)
    state.activate = true
    state.volume = state.defaultVolume()
    state.error = null
    state.picked = p.name
    state.asking = true
}
