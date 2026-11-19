package io.github.ra5on.titanos.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.FileTypeIcon
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import java.time.Instant

/** Zusammenfassung für die Zeile „Offline verfügbar“ (Entwurf 20). */
@Composable
fun offlineSummary(store: OfflineStore): String {
    val dirs = store.pins.count { it.dir }
    val files = store.pins.sumOf { it.files }
    return if (store.pins.isEmpty()) stringResource(R.string.off_none_short)
    else stringResource(R.string.off_summary, dirs, files, Format.bytes(store.usedBytes))
}

@Composable
private fun pinState(p: OfflinePin, syncing: Boolean): Pair<String, Boolean?> = when {
    p.problem == "offline" -> stringResource(R.string.off_state_nas) to false
    p.problem == "gone" -> stringResource(R.string.off_state_gone) to false
    p.problem == "limit" -> stringResource(R.string.off_state_limit, p.missing) to false
    p.problem != null -> stringResource(R.string.off_state_failed, p.problem) to false
    syncing -> stringResource(R.string.off_state_syncing) to null
    else -> stringResource(R.string.off_state_ok) to true
}

/** Offline verfügbar (Entwurf 24). */
@Composable
fun OfflineScreen(
    store: OfflineStore,
    onBack: () -> Unit,
    onPin: (OfflinePin) -> Unit,
    onRemove: (OfflinePin) -> Unit,
    onSyncNow: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val c = Titan.colors
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.files_offline), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } },
        bottomBar = bottomBar,
    ) {
        val last = store.lastSyncMs.takeIf { it > 0 }?.let { Format.shortDate(Instant.ofEpochMilli(it), stringResource(R.string.files_today), stringResource(R.string.files_yesterday)) }
        Note(
            if (last != null) stringResource(R.string.off_note_last, last) else stringResource(R.string.off_note_never),
            Modifier.padding(top = 14.dp, bottom = 4.dp),
            icon = TitanIcons.sync,
        )
        if (store.pins.isEmpty()) {
            EmptyState(stringResource(R.string.files_offline), stringResource(R.string.off_none), Modifier.padding(top = 24.dp))
        }
        val dirs = store.pins.filter { it.dir }
        val single = store.pins.filter { !it.dir }
        if (dirs.isNotEmpty()) {
            SectionHeader(stringResource(R.string.off_folders))
            Card {
                dirs.forEachIndexed { i, p ->
                    val (state, ok) = pinState(p, store.syncing)
                    ListRow(
                        title = p.name,
                        sub = "${p.parent.ifEmpty { p.rootName }} · ${stringResource(R.string.off_folder_files, p.files, Format.bytes(p.bytes))} · $state",
                        icon = { AppIcon("files", "orange", 40.dp) },
                        right = { PinActions(ok, p, onRemove) },
                        divider = i > 0,
                        onClick = { onPin(p) },
                    )
                }
            }
        }
        if (single.isNotEmpty()) {
            SectionHeader(stringResource(R.string.off_files))
            Card {
                single.forEachIndexed { i, p ->
                    val (state, ok) = pinState(p, store.syncing)
                    ListRow(
                        title = p.name,
                        sub = "${p.parent} · ${Format.bytes(p.bytes)} · $state",
                        icon = { FileTypeIcon(TitanIcons.file) },
                        right = { PinActions(ok, p, onRemove) },
                        divider = i > 0,
                        onClick = { onPin(p) },
                    )
                }
            }
        }
        SectionHeader(stringResource(R.string.off_settings))
        Card {
            ListRow(
                stringResource(R.string.off_wifi_only),
                sub = stringResource(R.string.off_wifi_only_text),
                right = { Toggle(store.wifiOnly, onWifiOnly, on, off) },
            )
            ListRow(stringResource(R.string.off_sync_now), icon = { RowIcon(TitanIcons.sync) }, divider = true, onClick = onSyncNow)
        }
        Text(
            stringResource(R.string.off_used, Format.bytes(store.usedBytes), Format.bytes(store.limitBytes)),
            style = TitanType.body,
            color = c.muted,
            modifier = Modifier.padding(top = 16.dp, start = 4.dp),
        )
        Text(stringResource(R.string.off_read_only), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        Box(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
    }
}

@Composable
private fun PinActions(ok: Boolean?, p: OfflinePin, onRemove: (OfflinePin) -> Unit) {
    val c = Titan.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (ok) {
            true -> Icon(TitanIcons.check, null, tint = c.okInk, modifier = Modifier.size(18.dp))
            false -> Icon(TitanIcons.warn, null, tint = c.warnInk, modifier = Modifier.size(18.dp))
            null -> Icon(TitanIcons.sync, null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
        IconButton(TitanIcons.x, stringResource(R.string.off_remove, p.name), { onRemove(p) }, size = 20.dp, tint = c.muted)
    }
}

/** Die Dateien eines offline markierten Ordners, lesend zu öffnen – auch ohne NAS. */
@Composable
fun OfflineFolderScreen(pin: OfflinePin, copies: List<OfflineCopy>, onBack: () -> Unit, onOpen: (OfflineCopy) -> Unit, bottomBar: @Composable () -> Unit) {
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(pin.name, onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } },
        bottomBar = bottomBar,
    ) {
        if (copies.isEmpty()) {
            EmptyState(pin.name, stringResource(R.string.off_folder_empty), Modifier.padding(top = 24.dp))
        } else {
            Card(Modifier.padding(top = 14.dp)) {
                copies.sortedBy { it.path.lowercase() }.forEachIndexed { i, cp ->
                    val rel = cp.path.removePrefix(pin.path).trimStart('/')
                    ListRow(
                        title = rel.substringAfterLast('/'),
                        sub = listOfNotNull(rel.substringBeforeLast('/', "").ifEmpty { null }, Format.bytes(cp.size)).joinToString(" · "),
                        icon = { FileTypeIcon(TitanIcons.file) },
                        divider = i > 0,
                        onClick = { onOpen(cp) },
                    )
                }
            }
        }
        Box(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
    }
}

/** Blatt eines Ordners („⋯“ in der Ordneransicht): den ganzen Ordner offline halten. */
@Composable
fun BoxScope.FolderSheet(name: String, offline: Boolean, onOffline: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = Titan.colors
    Sheet(onDismiss) {
        Text(name, style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(bottom = 14.dp))
        Card(background = c.bg) {
            ListRow(
                stringResource(R.string.off_folder_toggle),
                sub = stringResource(R.string.off_folder_toggle_text),
                icon = { RowIcon(TitanIcons.offline) },
                right = { Toggle(offline, onOffline, stringResource(UiR.string.ui_on), stringResource(UiR.string.ui_off)) },
            )
        }
        TitanButton(stringResource(UiR.string.ui_close), onDismiss, Modifier.padding(top = 12.dp), kind = ButtonKind.Normal)
    }
}
