package io.github.ra5on.titanos.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType

/** Ein möglicher Platz im Dock mit Namen und Strichsymbol. */
data class DockChoice(val key: String, val label: String, val icon: ImageVector)

/**
 * App-Einstellungen › Startbildschirm und Dock (Entwurf 07): Dock immer
 * anzeigen, Programme im Dock (wählen und ordnen), Beschriftung, Bearbeiten,
 * Einrichtung des Webs übernehmen, Zurücksetzen. Jede Änderung geht gleich ans
 * NAS ([onChange]).
 */
@Composable
fun HomeDockSettingsPage(
    prefs: PhonePrefs,
    choices: List<DockChoice>,
    onNas: Boolean,
    onChange: (PhonePrefs) -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Titan.colors
    var dockPage by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    val entries = dockEntries(prefs.dock, choices.map { it.key })
    val byKey = choices.associateBy { it.key }
    if (dockPage) {
        DockAppsPage(prefs, entries, choices, onChange, onBack = { dockPage = false })
        return
    }
    BackHandler(onBack = onBack)
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.hs_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } },
        overlay = {
            if (confirmReset) {
                ConfirmDialog(
                    title = stringResource(R.string.hs_reset_title),
                    text = stringResource(R.string.hs_reset_text),
                    actions = listOf(
                        DialogAction(stringResource(R.string.hs_reset_do), danger = true) { confirmReset = false; onChange(factoryPhone()) },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { confirmReset = false },
                    ),
                    onDismiss = { confirmReset = false },
                )
            }
        },
    ) {
        SectionHeader(stringResource(R.string.hs_dock))
        Card {
            val always = stringResource(R.string.hs_always)
            ListRow(always, sub = stringResource(R.string.hs_always_sub), right = { Toggle(prefs.dock.always, { onChange(prefs.copy(dock = prefs.dock.copy(always = it))) }, on, off, label = always) })
            ListRow(
                stringResource(R.string.hs_apps),
                sub = entries.mapNotNull { byKey[it]?.label }.joinToString(", "),
                chevron = true,
                divider = true,
                onClick = { dockPage = true },
            )
            val labels = stringResource(R.string.hs_labels)
            ListRow(labels, sub = stringResource(R.string.hs_labels_sub), divider = true, right = { Toggle(prefs.dock.labels, { onChange(prefs.copy(dock = prefs.dock.copy(labels = it))) }, on, off, label = labels) })
        }
        SectionHeader(stringResource(R.string.hs_home))
        Card {
            ListRow(stringResource(R.string.hs_edit), sub = stringResource(R.string.hs_edit_sub), chevron = true, onClick = onEdit)
            val web = stringResource(R.string.hs_web)
            ListRow(web, sub = stringResource(R.string.hs_web_sub), divider = true, right = { Toggle(prefs.useWeb, { onChange(prefs.copy(useWeb = it)) }, on, off, label = web) })
            ListRow(stringResource(R.string.hs_reset), sub = stringResource(R.string.hs_reset_sub), divider = true, onClick = { confirmReset = true })
        }
        Text(
            stringResource(if (onNas) R.string.hs_note else R.string.hs_note_local),
            style = TitanType.small,
            color = c.muted,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 16.dp),
        )
    }
}

/** „Programme im Dock“: bis zu fünf Plätze wählen und ordnen; Start bleibt immer. */
@Composable
private fun DockAppsPage(prefs: PhonePrefs, entries: List<String>, choices: List<DockChoice>, onChange: (PhonePrefs) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val byKey = choices.associateBy { it.key }
    val full = entries.size >= DOCK_MAX
    Page(topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.hs_apps), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } }) {
        SectionHeader(stringResource(R.string.hs_dock_in))
        Card {
            entries.forEachIndexed { i, key ->
                val choice = byKey[key] ?: return@forEachIndexed
                ListRow(
                    choice.label,
                    icon = { RowIcon(choice.icon) },
                    divider = i > 0,
                    right = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (i > 0) IconButton(TitanIcons.back, stringResource(R.string.hs_dock_earlier, choice.label), { onChange(dockMove(prefs, entries, key, -1)) })
                            if (i < entries.lastIndex) IconButton(TitanIcons.chev, stringResource(R.string.hs_dock_later, choice.label), { onChange(dockMove(prefs, entries, key, +1)) })
                            if (key != DOCK_HOME) IconButton(TitanIcons.x, stringResource(R.string.hs_dock_remove, choice.label), { onChange(dockRemove(prefs, entries, key)) })
                        }
                    },
                )
            }
        }
        Note(stringResource(R.string.hs_dock_start), Modifier.padding(top = 10.dp))
        val more = choices.filter { it.key !in entries }
        if (more.isNotEmpty()) {
            SectionHeader(stringResource(R.string.hs_dock_more))
            if (full) Note(stringResource(R.string.hs_dock_full), Modifier.padding(bottom = 10.dp))
            Card {
                more.forEachIndexed { i, choice ->
                    ListRow(
                        choice.label,
                        icon = { RowIcon(choice.icon) },
                        divider = i > 0,
                        right = {
                            val label = stringResource(R.string.hs_dock_add, choice.label)
                            SmallButton(stringResource(R.string.home_add_do), { onChange(dockAdd(prefs, entries, choice.key)) }, Modifier.semantics { contentDescription = label }, icon = TitanIcons.plus, enabled = !full)
                        },
                    )
                }
            }
        }
        Box(Modifier.padding(bottom = 16.dp))
    }
}
