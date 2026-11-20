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
import androidx.compose.ui.res.pluralStringResource
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
import io.github.ra5on.titanos.ui.theme.warnInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.dangerInk

@Composable
fun errorText(e: FilesError): String = when (e) {
    FilesError.Offline -> stringResource(R.string.files_offline_nas)
    FilesError.Exists -> stringResource(R.string.files_exists)
    FilesError.ReadOnly -> stringResource(R.string.err_read_only)
    FilesError.NoSpace -> stringResource(R.string.err_no_space)
    FilesError.SameFolder -> stringResource(R.string.err_same_folder)
    FilesError.IntoItself -> stringResource(R.string.err_into_itself)
    FilesError.NotText -> stringResource(R.string.err_not_text)
    FilesError.InvalidName -> stringResource(R.string.err_invalid_name)
    FilesError.NoIndex -> stringResource(R.string.err_no_index)
    FilesError.DownloadLimit -> stringResource(R.string.err_download_limit)
    FilesError.DownloadBlocked -> stringResource(R.string.err_download_blocked)
    FilesError.DownloadFailed -> stringResource(R.string.err_download_failed)
    FilesError.NotFound -> stringResource(R.string.err_not_found)
    FilesError.Locked -> stringResource(R.string.err_locked)
    is FilesError.Other -> stringResource(R.string.files_failed, e.detail)
}

/** Überschrift je Art der Ablage (wie Places im Web). */
@Composable
private fun kindTitle(kind: String): String = stringResource(
    when (kind) {
        "volume" -> R.string.files_volumes
        "usb" -> R.string.files_usb
        "stick" -> R.string.files_stick
        else -> R.string.files_shares
    },
)

/** Freigaben, Favoriten, NAS und „Auf diesem Handy“ (Entwurf 20). */
@Composable
fun FilesHomeScreen(
    state: RootsState,
    running: Int,
    finished: Int,
    offlineSummary: String,
    onRoot: (FileRoot) -> Unit,
    onTransfers: () -> Unit,
    onOffline: () -> Unit,
    onSearch: () -> Unit,
    onTrash: () -> Unit,
    onJobs: () -> Unit,
    bottomBar: @Composable () -> Unit,
    favorites: List<Favorite> = emptyList(),
    onFavorite: (Favorite) -> Unit = {},
    jobsRunning: Int = 0,
) {
    val c = Titan.colors
    Page(
        topBar = {
            Box(Modifier.statusBarsPadding()) {
                TopBar(
                    stringResource(R.string.files_title),
                    icon = { AppIcon("files", "orange", 30.dp) },
                    actions = { IconButton(TitanIcons.search, stringResource(UiR.string.ui_search), onSearch) },
                )
            }
        },
        bottomBar = bottomBar,
    ) {
        val roots = state.roots
        if (roots != null && favorites.isNotEmpty()) {
            SectionHeader(stringResource(R.string.files_favorites), top = 14.dp)
            Card {
                favorites.forEachIndexed { i, f ->
                    val root = roots.firstOrNull { it.id == f.root }
                    ListRow(
                        title = f.label ?: f.path.substringAfterLast('/').ifEmpty { root?.name ?: f.root },
                        sub = (listOf(root?.name ?: f.root) + f.path.split('/').filter { it.isNotEmpty() }).joinToString(" › "),
                        icon = { RowIcon(TitanIcons.pin, size = 24.dp) },
                        chevron = true,
                        divider = i > 0,
                        onClick = { onFavorite(f) },
                    )
                }
            }
        }
        when {
            roots == null && state.error != null -> {
                SectionHeader(stringResource(R.string.files_shares), top = 14.dp)
                Problem(errorText(state.error))
            }
            roots == null -> {
                SectionHeader(stringResource(R.string.files_shares), top = 14.dp)
                Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted)
            }
            roots.isEmpty() -> {
                SectionHeader(stringResource(R.string.files_shares), top = 14.dp)
                Text(stringResource(R.string.files_no_shares), style = TitanType.body, color = c.muted)
            }
            else -> {
                val groups = roots.groupBy { it.kind.ifEmpty { "share" } }
                var first = favorites.isEmpty()
                for (kind in listOf("share", "volume", "usb", "stick")) {
                    val list = groups[kind] ?: continue
                    SectionHeader(kindTitle(kind), top = if (first) 14.dp else 18.dp)
                    first = false
                    Card {
                        list.forEachIndexed { i, r ->
                            val free = if (r.totalBytes > 0) stringResource(R.string.files_free, Format.bytes(r.freeBytes), Format.bytes(r.totalBytes)) else null
                            ListRow(
                                title = r.name,
                                sub = listOfNotNull(free, if (r.writable) null else stringResource(R.string.files_read_only)).joinToString(" · ").ifEmpty { null },
                                icon = { if (kind == "share") AppIcon("shares", "yellow", 38.dp) else RowIcon(TitanIcons.hdd, size = 26.dp) },
                                chevron = true,
                                divider = i > 0,
                                onClick = { onRoot(r) },
                            )
                        }
                    }
                }
            }
        }
        SectionHeader(stringResource(R.string.files_on_nas))
        Card {
            val bins = roots.orEmpty().filter { it.recycle }
            ListRow(
                stringResource(R.string.files_trash),
                sub = when {
                    roots == null -> null
                    bins.isEmpty() -> stringResource(R.string.trash_none_text)
                    else -> pluralStringResource(R.plurals.trash_shares, bins.size, bins.size)
                },
                icon = { RowIcon(TitanIcons.trash, size = 24.dp) },
                chevron = true,
                onClick = onTrash,
            )
            ListRow(
                stringResource(R.string.jobs_title),
                sub = if (jobsRunning > 0) pluralStringResource(R.plurals.jobs_running, jobsRunning, jobsRunning) else stringResource(R.string.jobs_text),
                icon = { RowIcon(TitanIcons.sync, size = 24.dp) },
                chevron = true,
                divider = true,
                onClick = onJobs,
            )
        }
        SectionHeader(stringResource(R.string.files_on_phone))
        Card {
            ListRow(
                stringResource(R.string.files_offline),
                sub = offlineSummary,
                icon = { RowIcon(TitanIcons.offline, size = 24.dp) },
                chevron = true,
                onClick = onOffline,
            )
            ListRow(
                stringResource(R.string.files_transfers),
                sub = if (running + finished == 0) stringResource(R.string.files_transfers_none) else stringResource(R.string.files_transfers_summary, running, finished),
                icon = { RowIcon(TitanIcons.upload, size = 24.dp) },
                chevron = true,
                divider = true,
                onClick = onTransfers,
            )
        }
        Box(Modifier.padding(bottom = 12.dp))
    }
}

@Composable
fun entryDate(modified: String): String? = Format.instant(modified)?.let {
    Format.shortDate(it, stringResource(R.string.files_today), stringResource(R.string.files_yesterday))
}

internal fun iconFor(name: String) = when (Kinds.of(name)) {
    FileKind.Pdf -> TitanIcons.pdf
    FileKind.Image -> TitanIcons.image
    FileKind.Video -> TitanIcons.video
    FileKind.Audio -> TitanIcons.mic
    FileKind.Archive -> TitanIcons.archive
    FileKind.Text -> TitanIcons.edit
    else -> TitanIcons.file
}

@Composable
internal fun EntryIcon(e: FileEntry, size: androidx.compose.ui.unit.Dp = 40.dp) {
    if (e.dir) {
        AppIcon("files", "orange", size)
    } else {
        val pdf = Kinds.ext(e.name) == "pdf"
        FileTypeIcon(iconFor(e.name), tint = if (pdf) Titan.colors.dangerInk else Titan.colors.muted, size = size)
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
fun BoxScope.DeleteDialog(name: String, withTrash: Boolean, onDo: () -> Unit, onDismiss: () -> Unit, count: Int = 1) {
    ConfirmDialog(
        title = if (count > 1) {
            pluralStringResource(if (withTrash) R.plurals.files_trash_many else R.plurals.files_delete_many, count, count)
        } else {
            stringResource(if (withTrash) R.string.files_trash_confirm_title else R.string.files_delete_confirm_title, name)
        },
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
fun TransfersScreen(
    items: List<Transfer>,
    onBack: () -> Unit,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit = {},
    onClear: () -> Unit = {},
    now: Instant = Instant.now(),
) {
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
                    val why = when (t.waiting) {
                        WaitReason.Network -> stringResource(R.string.tr_wait_network)
                        WaitReason.Nas -> stringResource(R.string.tr_wait_nas)
                        WaitReason.None -> stringResource(R.string.tr_waiting_start)
                    }
                    ListRow(
                        t.name,
                        sub = "${t.place} · $why",
                        icon = { RowIcon(TitanIcons.cloud) },
                        right = { IconButton(TitanIcons.x, stringResource(R.string.tr_cancel), { onCancel(t.id) }, size = 20.dp, tint = c.muted) },
                        divider = i > 0,
                    )
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
                                tint = if (ok) c.okInk else c.warnInk,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        right = if (t.state == TransferState.Failed && t.direction != Direction.Share) {
                            { IconButton(TitanIcons.restart, stringResource(R.string.tr_retry), { onRetry(t.id) }, size = 20.dp, tint = c.muted) }
                        } else null,
                        divider = i > 0,
                    )
                }
            }
            Box(Modifier.padding(top = 12.dp)) {
                io.github.ra5on.titanos.ui.components.TitanButton(stringResource(R.string.tr_clear), onClear)
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}
