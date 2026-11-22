package io.github.ra5on.titanos.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatBytes
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Aufräumen alter Plugin-Sätze (plugins/cleanup): geschützte bleiben, Löschen nach einfacher Bestätigung. */
@Composable
internal fun CleanupPage(api: PluginsApi) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    var releases by remember { mutableStateOf<List<Release>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    suspend fun load() {
        try {
            releases = api.releases().releases
            selected = emptySet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        loading = false
    }
    LaunchedEffect(api) { load() }
    val total = cleanupTotal(releases, selected)
    PageBody {
        Text(t("cleanup.intro"), style = TitanType.body, color = c.muted)
        error?.let { Notice(errorText(it), tone = Tone.Error) }
        when {
            loading -> Loading()
            releases.isEmpty() -> Notice(t("cleanup.empty"), tone = Tone.Neutral)
            else -> SettingGroup(t("cleanup.versions")) {
                releases.forEachIndexed { i, r ->
                    if (r.protected) {
                        SettingRow(r.version, icon = "archive", divider = i > 0, desc = "${formatBytes(r.bytes)} · ${t("cleanup.protectedHint")}", below = {
                            StatusChip(t("cleanup.protected"), Tone.Neutral, Modifier.padding(top = 6.dp))
                        })
                    } else {
                        CheckRow(
                            "${r.version} · ${t("cleanup.select")}",
                            r.version in selected,
                            { on -> selected = if (on) selected + r.version else selected - r.version },
                            desc = formatBytes(r.bytes),
                            divider = i > 0,
                        )
                    }
                }
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("cleanup.estimate", "count" to selected.size, "size" to formatBytes(total)), style = TitanType.body, color = c.muted)
                    ActionButton(t("cleanup.preview"), { checked = false; confirm = true }, primary = true, enabled = selected.isNotEmpty() && !busy)
                }
            }
        }
        Text(t("cleanup.safety"), style = TitanType.small, color = c.muted)
    }
    if (confirm) {
        Confirm(
            t("cleanup.confirmTitle"),
            t("cleanup.estimate", "count" to selected.size, "size" to formatBytes(total)),
            t("netx.remove"),
            {
                if (checked && !busy) {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            api.cleanup(CleanupBody(releases.filter { it.version in selected && !it.protected }.map { CleanupItem(it.version, it.fingerprint) }))
                            confirm = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e
                        }
                        load()
                        busy = false
                    }
                }
            },
            { if (!busy) confirm = false },
            enabled = !busy && checked && selected.isNotEmpty(),
        ) {
            for (r in releases.filter { it.version in selected }) Mono("• ${r.version} · ${formatBytes(r.bytes)}", style = TitanType.small)
            Card(Modifier.padding(vertical = 10.dp)) { CheckRow(t("cleanup.checked"), checked, { checked = it }) }
            error?.let { Notice(errorText(it), tone = Tone.Error) }
        }
    }
}
