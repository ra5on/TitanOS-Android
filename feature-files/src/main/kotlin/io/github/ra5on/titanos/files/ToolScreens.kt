package io.github.ra5on.titanos.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk

@Composable
private fun SimpleTop(title: String, onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Box(Modifier.statusBarsPadding()) { TopBar(title, onBack = onBack, backLabel = stringResource(UiR.string.ui_back), actions = actions) }
}

// ---------------- Papierkorb ----------------

/** Freigaben mit Papierkorb (vom Start aus). */
@Composable
fun TrashRootsScreen(roots: List<FileRoot>, onRoot: (FileRoot) -> Unit, onBack: () -> Unit) {
    Page(topBar = { SimpleTop(stringResource(R.string.files_trash), onBack) }) {
        val bins = roots.filter { it.recycle }
        if (bins.isEmpty()) {
            EmptyState(stringResource(R.string.files_trash), stringResource(R.string.trash_none_text), Modifier.padding(top = 40.dp))
        } else {
            SectionHeader(stringResource(R.string.files_shares), top = 14.dp)
            Card {
                bins.forEachIndexed { i, r ->
                    ListRow(
                        r.name,
                        sub = r.recycleDays?.let { stringResource(R.string.files_trash_text, it) } ?: stringResource(R.string.files_trash_text_plain),
                        icon = { RowIcon(TitanIcons.trash, size = 24.dp) },
                        chevron = true,
                        divider = i > 0,
                        onClick = { onRoot(r) },
                    )
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

data class TrashUi(
    val root: FileRoot,
    val entries: List<TrashEntry>? = null,
    val error: FilesError? = null,
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
)

/**
 * Papierkorb einer Freigabe (TrashView im Web): eigene gelöschte Dateien mit
 * altem Ort, Wiederherstellen (bei gleichem Namen mit „(2)“), endgültig
 * löschen und leeren.
 */
@Composable
fun TrashScreen(
    ui: TrashUi,
    onBack: () -> Unit,
    onToggle: (TrashEntry) -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onEmpty: () -> Unit,
    onSelectAll: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val c = Titan.colors
    Page(
        topBar = {
            SimpleTop(stringResource(R.string.trash_title, ui.root.name), onBack) {
                if (!ui.entries.isNullOrEmpty()) IconButton(TitanIcons.check, stringResource(R.string.files_select_all), onSelectAll)
            }
        },
        overlay = overlay,
    ) {
        Note(
            ui.root.recycleDays?.let { pluralStringResource(R.plurals.trash_keep, it, it) } ?: stringResource(R.string.trash_keep_plain),
            Modifier.padding(top = 12.dp),
        )
        ui.error?.let { Problem(errorText(it), Modifier.padding(top = 12.dp)) }
        val entries = ui.entries
        when {
            entries == null && ui.error == null -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(top = 16.dp))
            entries == null -> Unit
            entries.isEmpty() -> EmptyState(stringResource(R.string.trash_empty), stringResource(R.string.trash_empty_text), Modifier.padding(top = 32.dp))
            else -> {
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TitanButton(stringResource(R.string.trash_restore), onRestore, enabled = ui.selected.isNotEmpty() && !ui.busy, kind = ButtonKind.Primary, icon = TitanIcons.restart)
                    TitanButton(stringResource(R.string.trash_delete), onDelete, enabled = ui.selected.isNotEmpty() && !ui.busy, kind = ButtonKind.Danger)
                    TitanButton(stringResource(R.string.trash_empty_do), onEmpty, enabled = !ui.busy, kind = ButtonKind.Danger)
                }
                Text(
                    pluralStringResource(R.plurals.trash_count, entries.size, entries.size, Format.bytes(entries.filter { !it.dir }.sumOf { it.size })),
                    style = TitanType.small, color = c.muted, modifier = Modifier.padding(bottom = 8.dp),
                )
                Card {
                    entries.forEachIndexed { i, e ->
                        val selected = e.path in ui.selected
                        val date = entryDate(e.modified)
                        ListRow(
                            e.name,
                            sub = listOfNotNull(
                                stringResource(R.string.trash_from, e.original.substringBeforeLast('/', "").ifEmpty { ui.root.name }),
                                if (e.dir) null else Format.bytes(e.size),
                                date?.let { stringResource(R.string.trash_deleted, it) },
                            ).joinToString(" · "),
                            icon = { RadioMark(selected) },
                            divider = i > 0,
                            onClick = { onToggle(e) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

// ---------------- Suche ----------------

data class SearchUi(
    val roots: List<FileRoot>,
    val root: FileRoot?,
    val path: String = "",
    val query: String = "",
    val content: Boolean = false,
    val showHidden: Boolean = false,
    val extension: String = "",
    val minMb: String = "",
    val maxMb: String = "",
    val days: Int = 0,
    val running: Boolean = false,
    val result: SearchResult? = null,
    val error: FilesError? = null,
)

/**
 * Suche (SearchDialog im Web): nach Namen in einem Ordner mit Unterordnern
 * oder im Inhalt über den Volltext-Index, mit Endung, Größe und Zeitraum.
 */
@Composable
fun SearchScreen(
    ui: SearchUi,
    onChange: (SearchUi) -> Unit,
    onSearch: () -> Unit,
    onResult: (Found) -> Unit,
    onBack: () -> Unit,
) {
    val c = Titan.colors
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    Page(topBar = { SimpleTop(stringResource(R.string.search_title), onBack) }) {
        Spacer(Modifier.height(12.dp))
        TextInput(
            ui.query, { onChange(ui.copy(query = it)) }, stringResource(R.string.search_label),
            leading = TitanIcons.search, placeholder = stringResource(R.string.search_placeholder),
            keyboard = KeyboardOptions(imeAction = ImeAction.Search),
        )
        SectionHeader(stringResource(R.string.search_where))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.roots.forEach { r -> Chip(r.name, on = ui.root?.id == r.id, onClick = { onChange(ui.copy(root = r, path = if (ui.root?.id == r.id) ui.path else "")) }) }
        }
        if (ui.path.isNotEmpty()) Text(stringResource(R.string.search_in_folder, ui.path), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        Card(Modifier.padding(top = 12.dp)) {
            ListRow(
                stringResource(R.string.search_content),
                sub = stringResource(if (ui.root?.indexed == true) R.string.search_content_text else R.string.search_content_none),
                right = { Toggle(ui.content, { onChange(ui.copy(content = it)) }, on, off) },
                onClick = { onChange(ui.copy(content = !ui.content)) },
            )
            ListRow(
                stringResource(R.string.view_hidden),
                right = { Toggle(ui.showHidden, { onChange(ui.copy(showHidden = it)) }, on, off) },
                divider = true,
                onClick = { onChange(ui.copy(showHidden = !ui.showHidden)) },
            )
        }
        SectionHeader(stringResource(R.string.search_filters))
        TextInput(ui.extension, { onChange(ui.copy(extension = it.trim().trimStart('.'))) }, stringResource(R.string.search_extension), placeholder = stringResource(R.string.search_extension_hint))
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.48f)) {
                TextInput(ui.minMb, { onChange(ui.copy(minMb = it.filter { ch -> ch.isDigit() })) }, stringResource(R.string.search_min), placeholder = stringResource(R.string.search_min), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Box(Modifier.fillMaxWidth(0.48f)) {
                TextInput(ui.maxMb, { onChange(ui.copy(maxMb = it.filter { ch -> ch.isDigit() })) }, stringResource(R.string.search_max), placeholder = stringResource(R.string.search_max), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to R.string.search_any_time, 1 to R.string.search_day, 7 to R.string.search_week, 30 to R.string.search_month, 365 to R.string.search_year).forEach { (d, label) ->
                Chip(stringResource(label), on = ui.days == d, onClick = { onChange(ui.copy(days = d)) })
            }
        }
        TitanButton(
            stringResource(if (ui.running) R.string.search_running else R.string.search_do), onSearch,
            Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.Primary, icon = TitanIcons.search,
            enabled = !ui.running && ui.root != null && ui.query.isNotBlank(),
        )
        ui.error?.let { Problem(errorText(it), Modifier.padding(top = 12.dp)) }
        ui.result?.let { r ->
            SectionHeader(
                if (r.truncated) stringResource(R.string.search_hits_more, r.entries.size) else pluralStringResource(R.plurals.search_hits, r.entries.size, r.entries.size),
            )
            if (r.entries.isEmpty()) {
                Text(stringResource(R.string.search_nothing), style = TitanType.body, color = c.muted)
            } else {
                Card {
                    r.entries.forEachIndexed { i, f ->
                        ListRow(
                            f.name,
                            sub = listOfNotNull(f.path.substringBeforeLast('/', "").ifEmpty { ui.root?.name }, if (f.dir) null else Format.bytes(f.size), entryDate(f.modified)).joinToString(" · "),
                            icon = { RowIcon(if (f.dir) TitanIcons.folder else iconFor(f.name)) },
                            divider = i > 0,
                            onClick = { onResult(f) },
                            bottom = f.excerpt?.let { ex -> { Text(ex, style = TitanType.small, color = c.text, modifier = Modifier.padding(top = 4.dp)) } },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

// ---------------- Aufträge ----------------

/** Aufträge am NAS (JobsPanel im Web): Fortschritt, anhalten, fortsetzen, abbrechen, wiederholen. */
@Composable
fun JobsScreen(
    jobs: List<Job>,
    error: FilesError?,
    onBack: () -> Unit,
    onCancel: (Job) -> Unit,
    onPause: (Job, Boolean) -> Unit,
    onRetry: (Job) -> Unit,
) {
    val c = Titan.colors
    Page(topBar = { SimpleTop(stringResource(R.string.jobs_title), onBack) }) {
        error?.let { Problem(errorText(it), Modifier.padding(top = 12.dp)) }
        val running = jobs.filter { it.running }
        val done = jobs.filter { !it.running }
        if (jobs.isEmpty()) EmptyState(stringResource(R.string.jobs_title), stringResource(R.string.jobs_none), Modifier.padding(top = 40.dp))
        if (running.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tr_running), top = 14.dp)
            Card {
                running.forEachIndexed { i, j ->
                    ListRow(
                        jobTitle(j),
                        sub = listOf(jobPlace(j), jobProgress(j)).filter { it.isNotEmpty() }.joinToString(" · ") + if (j.current.isNotEmpty()) "\n" + j.current else "",
                        icon = { RowIcon(TitanIcons.sync) },
                        divider = i > 0,
                        bottom = {
                            Column {
                                Bar(j.fraction, Modifier.padding(top = 8.dp))
                                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (j.operation.isEmpty()) Chip(stringResource(if (j.paused) R.string.jobs_resume else R.string.jobs_pause), onClick = { onPause(j, !j.paused) })
                                    Chip(stringResource(R.string.tr_cancel), onClick = { onCancel(j) })
                                }
                            }
                        },
                    )
                }
            }
        }
        if (done.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tr_done), top = if (running.isEmpty()) 14.dp else 18.dp)
            Card {
                done.forEachIndexed { i, j ->
                    val ok = j.state == "done" && j.failed == 0L
                    ListRow(
                        jobTitle(j),
                        sub = listOf(jobPlace(j), jobProgress(j)).filter { it.isNotEmpty() }.joinToString(" · ") +
                            (j.problems.orEmpty().take(5).joinToString("") { "\n" + it.path + " (" + it.code + ")" }),
                        icon = {
                            androidx.compose.material3.Icon(
                                if (ok) TitanIcons.check else TitanIcons.warn,
                                stringResource(if (ok) R.string.tr_state_done else R.string.tr_state_failed),
                                tint = if (ok) c.okInk else c.warnInk,
                                modifier = Modifier.padding(2.dp),
                            )
                        },
                        right = if (j.retryable) { { IconButton(TitanIcons.restart, stringResource(R.string.tr_retry), { onRetry(j) }, size = 20.dp, tint = c.muted) } } else null,
                        divider = i > 0,
                    )
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

@Composable
private fun jobPlace(j: Job): String = when (j.operation) {
    "download" -> stringResource(R.string.jobs_into, j.to.substringAfter('/') + if (j.dir.isNotEmpty()) "/" + j.dir else "")
    else -> stringResource(R.string.jobs_into, j.to.substringAfter('/') + if (j.dir.isNotEmpty()) "/" + j.dir else "")
}

// ---------------- Eigenschaften ----------------

/** Eigenschaften (FileProperties im Web): Ort, Art, Größe, Zeiten, Rechte, Prüfsumme auf Wunsch. */
@Composable
fun BoxScope.PropertiesSheet(
    name: String,
    place: String,
    props: Properties?,
    error: FilesError?,
    hashing: Boolean,
    onChecksum: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Titan.colors
    Sheet(onDismiss) {
        Text(name, style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(bottom = 12.dp))
        error?.let { Problem(errorText(it), Modifier.padding(bottom = 10.dp)) }
        if (props == null && error == null) Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted)
        props?.let { p ->
            Card(background = c.bg) {
                @Composable
                fun line(label: Int, value: String, divider: Boolean = true) =
                    ListRow(stringResource(label), sub = value, divider = divider)
                line(R.string.prop_place, place, divider = false)
                if (p.kind.isNotEmpty()) line(R.string.prop_kind, p.kind)
                line(R.string.prop_size, Format.bytes(p.size) + " (" + java.text.NumberFormat.getIntegerInstance().format(p.size) + " B)")
                Format.instant(p.modified)?.let { line(R.string.prop_modified, Format.longDate(it)) }
                Format.instant(p.changed)?.let { line(R.string.prop_changed, Format.longDate(it)) }
                Format.instant(p.accessed)?.let { line(R.string.prop_accessed, Format.longDate(it)) }
                line(R.string.prop_permissions, "${p.permissions} · uid ${p.uid} · gid ${p.gid}")
                line(
                    R.string.prop_access,
                    listOfNotNull(
                        if (p.access.read) stringResource(R.string.prop_read) else null,
                        if (p.access.write) stringResource(R.string.prop_write) else null,
                        if (p.access.delete) stringResource(R.string.prop_delete) else null,
                    ).joinToString(", ").ifEmpty { stringResource(R.string.prop_none) } + if (p.access.reason.isNotEmpty()) " (" + p.access.reason + ")" else "",
                )
                p.sha256?.let {
                    ListRow(stringResource(R.string.prop_sha256), divider = true, bottom = {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(it, style = TitanType.small.copy(fontFamily = FontFamily.Monospace), color = c.text, modifier = Modifier.padding(top = 4.dp))
                        }
                    })
                }
            }
            if (p.sha256 == null && !p.path.endsWith("/")) {
                TitanButton(
                    stringResource(if (hashing) R.string.prop_hashing else R.string.prop_hash), onChecksum,
                    Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !hashing,
                )
            }
        }
    }
}

// ---------------- Dialoge ----------------

/** Ein Name (neuer Ordner, neue Datei). */
@Composable
fun BoxScope.NameDialog(
    title: String,
    label: String,
    value: String,
    onValue: (String) -> Unit,
    error: FilesError?,
    doLabel: String,
    onDo: () -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    ConfirmDialog(
        title = title,
        text = note,
        actions = listOf(DialogAction(stringResource(UiR.string.ui_cancel), onClick = onDismiss), DialogAction(doLabel, onClick = onDo)),
        onDismiss = onDismiss,
    ) {
        TextInput(value, onValue, label, placeholder = label, keyboard = KeyboardOptions(imeAction = ImeAction.Done), )
        error?.let { Problem(errorText(it), Modifier.padding(top = 10.dp)) }
        Box(Modifier.padding(bottom = 12.dp))
    }
}

/** Was beim Einfügen mit gleichen Namen geschieht (CopyDialog im Web). */
@Composable
fun BoxScope.PasteDialog(
    inspection: Inspection,
    move: Boolean,
    choice: String,
    onChoice: (String) -> Unit,
    onDo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Titan.colors
    ConfirmDialog(
        title = stringResource(if (move) R.string.paste_title_move else R.string.paste_title_copy),
        text = stringResource(R.string.paste_summary, inspection.files, Format.bytes(inspection.bytes)),
        actions = listOf(DialogAction(stringResource(UiR.string.ui_cancel), onClick = onDismiss), DialogAction(stringResource(R.string.clip_paste), onClick = onDo)),
        onDismiss = onDismiss,
    ) {
        if (inspection.conflicts.isNotEmpty() || inspection.identical + inspection.different > 0) {
            Text(
                stringResource(R.string.paste_conflicts, inspection.identical + inspection.different, inspection.identical),
                style = TitanType.body, color = c.text, modifier = Modifier.padding(bottom = 8.dp),
            )
            listOf(
                "rename" to R.string.paste_rename,
                "skip" to R.string.paste_skip,
                "replace" to R.string.paste_replace,
                "missing" to R.string.paste_missing,
            ).forEach { (mode, label) ->
                ListRow(stringResource(label), icon = { RadioMark(choice == mode) }, onClick = { onChoice(mode) })
            }
            if (choice == "replace") Text(stringResource(R.string.paste_replace_note), style = TitanType.small, color = c.dangerInk, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (inspection.unreadableCount > 0) Problem(stringResource(R.string.paste_unreadable, inspection.unreadableCount), Modifier.padding(bottom = 8.dp))
    }
}

/** Aus dem Netz laden (UrlDownloadDialog im Web): Adresse prüfen, Name, dann als Auftrag. */
@Composable
fun BoxScope.UrlDialog(
    url: String,
    onUrl: (String) -> Unit,
    probe: UrlProbe?,
    name: String,
    onName: (String) -> Unit,
    checking: Boolean,
    error: FilesError?,
    onCheck: () -> Unit,
    onDo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Titan.colors
    ConfirmDialog(
        title = stringResource(R.string.add_url),
        text = stringResource(R.string.url_text),
        actions = listOf(
            DialogAction(stringResource(UiR.string.ui_cancel), onClick = onDismiss),
            if (probe == null) DialogAction(stringResource(if (checking) R.string.url_checking else R.string.url_check), onClick = onCheck)
            else DialogAction(stringResource(R.string.url_do), onClick = onDo),
        ),
        onDismiss = onDismiss,
    ) {
        TextInput(url, onUrl, stringResource(R.string.url_label), placeholder = "https://…", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), )
        probe?.let { p ->
            Spacer(Modifier.height(10.dp))
            TextInput(name, onName, stringResource(R.string.url_name), placeholder = p.name)
            Text(
                listOfNotNull(
                    if (p.size >= 0) Format.bytes(p.size) else stringResource(R.string.url_size_unknown),
                    p.type,
                    stringResource(R.string.url_free, Format.bytes(p.free)),
                ).joinToString(" · "),
                style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 6.dp),
            )
            if (p.exists) Text(stringResource(R.string.url_exists), style = TitanType.small, color = c.warnInk, modifier = Modifier.padding(top = 4.dp))
        }
        error?.let { Problem(errorText(it), Modifier.padding(top = 10.dp)) }
        Box(Modifier.padding(bottom = 12.dp))
    }
}
