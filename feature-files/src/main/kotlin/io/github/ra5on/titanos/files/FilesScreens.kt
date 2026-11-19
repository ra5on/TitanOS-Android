package io.github.ra5on.titanos.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.Fab
import io.github.ra5on.titanos.ui.components.FileTypeIcon
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import java.time.Instant

@Composable
fun errorText(e: FilesError): String = when (e) {
    FilesError.Offline -> stringResource(R.string.files_offline_nas)
    FilesError.Exists -> stringResource(R.string.files_exists)
    is FilesError.Other -> stringResource(R.string.files_failed, e.detail)
}

/** Freigaben und „Auf diesem Handy“ (Entwurf 20). */
@Composable
fun FilesHomeScreen(
    state: RootsState,
    running: Int,
    finished: Int,
    onRoot: (FileRoot) -> Unit,
    onTransfers: () -> Unit,
    onLater: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    Page(
        topBar = {
            Box(Modifier.statusBarsPadding()) {
                TopBar(
                    stringResource(R.string.files_title),
                    icon = { AppIcon("files", "orange", 30.dp) },
                    actions = {
                        IconButton(TitanIcons.search, stringResource(UiR.string.ui_search), onLater)
                        IconButton(TitanIcons.more, stringResource(UiR.string.ui_more), onLater)
                    },
                )
            }
        },
        bottomBar = bottomBar,
    ) {
        SectionHeader(stringResource(R.string.files_shares), top = 14.dp)
        val roots = state.roots
        when {
            roots == null && state.error != null -> Problem(errorText(state.error))
            roots == null -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = Titan.colors.muted)
            roots.isEmpty() -> Text(stringResource(R.string.files_no_shares), style = TitanType.body, color = Titan.colors.muted)
            else -> Card {
                roots.forEachIndexed { i, r ->
                    val free = stringResource(R.string.files_free, Format.bytes(r.freeBytes), Format.bytes(r.totalBytes))
                    ListRow(
                        title = r.name,
                        sub = if (r.writable) free else "$free · ${stringResource(R.string.files_read_only)}",
                        icon = { AppIcon("shares", "yellow", 38.dp) },
                        chevron = true,
                        divider = i > 0,
                        onClick = { onRoot(r) },
                    )
                }
            }
        }
        SectionHeader(stringResource(R.string.files_on_phone))
        Card {
            ListRow(
                stringResource(R.string.files_offline),
                sub = stringResource(UiR.string.ui_later),
                icon = { RowIcon(TitanIcons.offline, size = 24.dp) },
                chevron = true,
                onClick = onLater,
            )
            ListRow(
                stringResource(R.string.files_transfers),
                sub = if (running + finished == 0) stringResource(R.string.files_transfers_none) else stringResource(R.string.files_transfers_summary, running, finished),
                icon = { RowIcon(TitanIcons.upload, size = 24.dp) },
                chevron = true,
                divider = true,
                onClick = onTransfers,
            )
            ListRow(
                stringResource(R.string.files_trash),
                sub = stringResource(UiR.string.ui_later),
                icon = { RowIcon(TitanIcons.trash, size = 24.dp) },
                chevron = true,
                divider = true,
                onClick = onLater,
            )
        }
    }
}

@Composable
fun entryDate(modified: String): String? = Format.instant(modified)?.let {
    Format.shortDate(it, stringResource(R.string.files_today), stringResource(R.string.files_yesterday))
}

private fun iconFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> TitanIcons.pdf
    "jpg", "jpeg", "png", "gif", "webp", "heic", "avif" -> TitanIcons.image
    "mp4", "mov", "mkv", "webm", "avi" -> TitanIcons.video
    else -> TitanIcons.file
}

@Composable
private fun EntryIcon(e: FileEntry) {
    if (e.dir) {
        AppIcon("files", "orange", 40.dp)
    } else {
        val pdf = e.name.lowercase().endsWith(".pdf")
        FileTypeIcon(iconFor(e.name), tint = if (pdf) Titan.colors.statusError else Titan.colors.muted)
    }
}

/** Ordner (Entwurf 21): Pfad als Chips, Einträge, laufende Uploads in der Zeile, „+“ unten rechts. */
@Composable
fun FolderScreen(
    state: FolderState,
    uploads: List<Transfer>,
    onBack: () -> Unit,
    onCrumb: (Int) -> Unit,
    onEntry: (FileEntry) -> Unit,
    onUpload: () -> Unit,
    onLater: () -> Unit,
    bottomBar: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val c = Titan.colors
    val title = state.location.segments.lastOrNull() ?: state.location.root.name
    Box {
        Page(
            topBar = {
                Box(Modifier.statusBarsPadding()) {
                    TopBar(title, onBack = onBack, backLabel = stringResource(UiR.string.ui_back), actions = {
                        IconButton(TitanIcons.search, stringResource(UiR.string.ui_search), onLater)
                        IconButton(TitanIcons.more, stringResource(UiR.string.ui_more), onLater)
                    })
                }
            },
            bottomBar = bottomBar,
            overlay = {
                if (state.location.root.writable) {
                    Fab(TitanIcons.plus, stringResource(R.string.files_upload), onUpload, Modifier.align(Alignment.BottomEnd).padding(18.dp))
                }
                overlay()
            },
        ) {
            val crumbs = listOf(state.location.root.name) + state.location.segments
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 12.dp).semantics { contentDescription = crumbs.joinToString(" › ") },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                crumbs.forEachIndexed { i, name ->
                    if (i > 0) Icon(TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(16.dp))
                    Chip(name, on = i == crumbs.lastIndex, onClick = { onCrumb(i) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.files_sort_name), style = TitanType.body.copy(fontSize = TitanType.small.fontSize * (14f / 13f)), color = c.muted, modifier = Modifier.weight(1f))
            }
            state.error?.let { Problem(errorText(it), Modifier.padding(bottom = 12.dp)) }
            val entries = state.entries
            when {
                entries == null && state.error == null -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted)
                entries == null -> Unit
                entries.isEmpty() && uploads.isEmpty() -> EmptyState(stringResource(R.string.files_empty), "")
                else -> Card {
                    var first = true
                    for (e in entries) {
                        val up = uploads.firstOrNull { it.name == e.name }
                        EntryRow(e, up, divider = !first, onClick = { onEntry(e) })
                        first = false
                    }
                    for (u in uploads.filter { u -> entries.none { it.name == u.name } }) {
                        ListRow(
                            title = u.name,
                            sub = Format.bytes(u.total),
                            icon = { FileTypeIcon(iconFor(u.name)) },
                            right = { Text(stringResource(R.string.files_uploading, Format.percent(u.fraction.toDouble())), style = TitanType.small, color = c.muted) },
                            divider = !first,
                        )
                        first = false
                    }
                }
            }
            Box(Modifier.padding(bottom = 72.dp))
        }
    }
}

@Composable
private fun EntryRow(e: FileEntry, upload: Transfer?, divider: Boolean, onClick: () -> Unit) {
    val c = Titan.colors
    val date = entryDate(e.modified)
    val sub = if (e.dir) date else listOfNotNull(Format.bytes(e.size), date).joinToString(" · ")
    ListRow(
        title = e.name,
        sub = sub,
        icon = { EntryIcon(e) },
        right = upload?.let { u -> { Text(stringResource(R.string.files_uploading, Format.percent(u.fraction.toDouble())), style = TitanType.small, color = c.muted) } },
        chevron = e.dir,
        divider = divider,
        onClick = onClick,
    )
}

/** Blatt einer Datei (Entwurf 22). */
@Composable
fun BoxScope.FileSheet(
    entry: FileEntry,
    withTrash: Boolean,
    writable: Boolean,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Titan.colors
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    Sheet(onDismiss) {
        Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryIcon(entry)
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = TitanType.rowTitle, color = c.text)
                val date = Format.instant(entry.modified)?.let { Format.longDate(it) }
                Text(
                    if (date != null) stringResource(R.string.files_changed, Format.bytes(entry.size), date) else Format.bytes(entry.size),
                    style = TitanType.rowSub,
                    color = c.muted,
                )
            }
        }
        Card(background = c.bg) {
            ListRow(stringResource(R.string.files_share), sub = stringResource(R.string.files_share_text), icon = { RowIcon(TitanIcons.share) }, onClick = onShare)
            ListRow(
                stringResource(R.string.files_offline),
                sub = stringResource(R.string.files_offline_text),
                icon = { RowIcon(TitanIcons.offline) },
                right = { Toggle(false, null, on, off, enabled = false) },
                divider = true,
            )
            ListRow(stringResource(R.string.files_download), icon = { RowIcon(TitanIcons.download) }, divider = true, onClick = onDownload)
            if (writable) {
                ListRow(stringResource(R.string.files_rename), icon = { RowIcon(TitanIcons.edit) }, divider = true, onClick = onRename)
                ListRow(stringResource(R.string.files_move), sub = stringResource(R.string.files_move_text), icon = { RowIcon(TitanIcons.move) }, divider = true)
                ListRow(
                    stringResource(if (withTrash) R.string.files_to_trash else R.string.files_delete_forever),
                    icon = { RowIcon(TitanIcons.trash, tint = c.statusError) },
                    titleColor = c.statusError,
                    divider = true,
                    onClick = onDelete,
                )
            }
        }
    }
}

/** Umbenennen als einfacher Dialog mit Feld. */
@Composable
fun BoxScope.RenameDialog(name: String, value: String, onValue: (String) -> Unit, error: FilesError?, onDo: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.files_rename_title),
        text = null,
        actions = listOf(
            DialogAction(stringResource(UiR.string.ui_cancel), onClick = onDismiss),
            DialogAction(stringResource(R.string.files_rename_do), onClick = onDo),
        ),
        onDismiss = onDismiss,
    ) {
        TextInput(value, onValue, stringResource(R.string.files_rename_label), placeholder = name)
        error?.let { Problem(errorText(it), Modifier.padding(top = 10.dp)) }
        Box(Modifier.padding(bottom = 12.dp))
    }
}

/** In den Papierkorb bzw. endgültig löschen: gefährlicher Knopf links von „Abbrechen“. */
@Composable
fun BoxScope.DeleteDialog(name: String, withTrash: Boolean, onDo: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(if (withTrash) R.string.files_trash_confirm_title else R.string.files_delete_confirm_title, name),
        text = stringResource(if (withTrash) R.string.files_trash_confirm_text else R.string.files_delete_confirm_text),
        actions = listOf(
            DialogAction(stringResource(if (withTrash) R.string.files_to_trash else R.string.files_delete_forever), danger = true, onClick = onDo),
            DialogAction(stringResource(UiR.string.ui_cancel), onClick = onDismiss),
        ),
        onDismiss = onDismiss,
    )
}

/** Übertragungen (Entwurf 23), einfache Fassung. */
@Composable
fun TransfersScreen(items: List<Transfer>, onBack: () -> Unit, onCancel: (Long) -> Unit, now: Instant = Instant.now()) {
    val c = Titan.colors
    Page(topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.tr_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } }) {
        val running = items.filter { it.state == TransferState.Running }
        val waiting = items.filter { it.state == TransferState.Waiting }
        val finished = items.filter { it.state == TransferState.Done || it.state == TransferState.Failed || it.state == TransferState.Cancelled }
        if (items.isEmpty()) {
            EmptyState(stringResource(R.string.tr_title), stringResource(R.string.tr_none), Modifier.padding(top = 40.dp))
        }
        if (running.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tr_running), top = 14.dp)
            Card {
                running.forEachIndexed { i, t ->
                    val (done, total) = Format.bytes(t.done) to Format.bytes(t.total)
                    ListRow(
                        title = t.name,
                        sub = stringResource(if (t.direction == Direction.Upload) R.string.tr_to else R.string.tr_from, t.place, done, total),
                        icon = { RowIcon(if (t.direction == Direction.Upload) TitanIcons.upload else TitanIcons.download) },
                        right = { IconButton(TitanIcons.x, stringResource(R.string.tr_cancel), { onCancel(t.id) }, size = 20.dp, tint = c.muted) },
                        divider = i > 0,
                        bottom = { Bar(t.fraction, Modifier.padding(top = 8.dp)) },
                    )
                }
            }
        }
        if (waiting.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tr_waiting), top = if (running.isEmpty()) 14.dp else 18.dp)
            Card {
                waiting.forEachIndexed { i, t ->
                    ListRow(t.name, sub = "${t.place} · ${stringResource(R.string.tr_waiting_start)}", icon = { RowIcon(TitanIcons.cloud) }, divider = i > 0)
                }
            }
        }
        if (finished.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tr_done), top = if (running.isEmpty() && waiting.isEmpty()) 14.dp else 18.dp)
            Card {
                finished.forEachIndexed { i, t ->
                    val ok = t.state == TransferState.Done
                    val whenText = t.finishedAt?.let { Format.shortDate(it, stringResource(R.string.files_today), stringResource(R.string.files_yesterday)) }.orEmpty()
                    val sub = when {
                        t.state == TransferState.Cancelled -> stringResource(R.string.tr_cancelled)
                        !ok && t.offline -> stringResource(R.string.tr_failed_offline)
                        !ok -> stringResource(R.string.tr_failed, t.reason.orEmpty())
                        t.direction == Direction.Upload -> stringResource(R.string.tr_uploaded, t.place, Format.bytes(t.total), whenText)
                        t.direction == Direction.Download -> stringResource(R.string.tr_downloaded, whenText)
                        else -> stringResource(R.string.tr_shared, whenText)
                    }
                    ListRow(
                        title = t.name,
                        sub = sub,
                        icon = {
                            Icon(
                                if (ok) TitanIcons.check else TitanIcons.warn,
                                stringResource(if (ok) R.string.tr_state_done else R.string.tr_state_failed),
                                tint = if (ok) c.statusOk else c.statusWarn,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        divider = i > 0,
                    )
                }
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}
