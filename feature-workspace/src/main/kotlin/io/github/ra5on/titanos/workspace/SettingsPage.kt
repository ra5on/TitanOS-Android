package io.github.ra5on.titanos.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType

/**
 * Die Einstellung des Arbeitsplatzes in der Handy-Gestaltung (Menü der
 * Startseite): Automatisch/Immer/Nie und die eigene Einrichtung für Android.
 * Am Arbeitsplatz selbst steht dasselbe in „Einstellungen des Arbeitsplatzes“.
 */
@Composable
fun WorkspaceSettingsPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings = remember { WorkspaceSettings.of(context) }
    val back = stringResource(io.github.ra5on.titanos.ui.R.string.ui_back)
    Page(topBar = { Column(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.ws_title), onBack = onBack, backLabel = back) } }) {
        SectionHeader(stringResource(R.string.ws_mode))
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((mode, label) in listOf(WorkspaceMode.Auto to R.string.ws_mode_auto, WorkspaceMode.Always to R.string.ws_mode_always, WorkspaceMode.Never to R.string.ws_mode_never)) {
                        Chip(stringResource(label), on = settings.mode == mode) { settings.chooseMode(mode) }
                    }
                }
                Text(stringResource(R.string.ws_mode_hint), style = TitanType.small, color = Titan.colors.muted)
            }
        }
        SectionHeader(stringResource(R.string.ws_title))
        Card {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ws_own), style = TitanType.rowTitle, color = Titan.colors.text)
                    Text(stringResource(R.string.ws_own_hint), style = TitanType.rowSub, color = Titan.colors.muted)
                }
                Toggle(settings.own, { settings.chooseOwn(it) }, stringResource(R.string.ws_on), stringResource(R.string.ws_off), label = stringResource(R.string.ws_own))
            }
        }
    }
}
