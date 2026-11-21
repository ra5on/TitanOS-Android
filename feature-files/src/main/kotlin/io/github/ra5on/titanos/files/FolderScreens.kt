package io.github.ra5on.titanos.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.Fab
import io.github.ra5on.titanos.ui.components.FileTypeIcon
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk

/** Was die Ordneransicht zeigt; alles Weitere kommt über [FolderCallbacks]. */
data class FolderUi(
    val state: FolderState,
    val entries: List<FileEntry>,
    val view: FileView = FileView.List,
    val sort: SortBy = SortBy.Name,
    val descending: Boolean = false,
    val selected: Set<String> = emptySet(),
    val uploads: List<Transfer> = emptyList(),
    val clipboard: Clip? = null,
    val jobs: List<Job> = emptyList(),
    val undo: UndoOffer? = null,
    val offlinePaths: Set<String> = emptySet(),
    /** Zeile unter dem Tastaturfokus (Pfeiltasten). */
    val cursor: String? = null,
    /** Datei in der Vorschau daneben (ab Split). */
    val previewing: String? = null,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
    val writable: Boolean get() = state.location.root.writable
}

class FolderCallbacks(
    val back: () -> Unit,
    val crumb: (Int) -> Unit,
    val open: (FileEntry) -> Unit,
    val select: (FileEntry) -> Unit,
    val entryMenu: (FileEntry) -> Unit,
    val add: () -> Unit,
    val menu: () -> Unit,
    val search: () -> Unit,
    val sortMenu: () -> Unit,
    val clearSelection: () -> Unit = {},
    val selectAll: () -> Unit = {},
    val shareSelected: () -> Unit = {},
    val selectionMenu: () -> Unit = {},
    val paste: () -> Unit = {},
    val dropClipboard: () -> Unit = {},
    val jobs: () -> Unit = {},
    val undo: () -> Unit = {},
    val dismissUndo: () -> Unit = {},
    /** Was beim Ziehen eines Eintrags mitgeht; null: Ziehen aus. */
    val drag: ((FileEntry) -> DragAndDropTransferData?)? = null,
    /** Abgelegt auf einem Ordner (Eintrag) oder in den offenen Ordner (null). */
    val drop: ((DragAndDropEvent, FileEntry?) -> Boolean)? = null,
    /** Rechtsklick öffnet das Menü. */
    val secondary: Boolean = true,
)

@Composable
private fun sortLabel(sort: SortBy, descending: Boolean): String {
    val name = stringResource(
        when (sort) {
            SortBy.Name -> R.string.sort_name
            SortBy.Size -> R.string.sort_size
            SortBy.Modified -> R.string.sort_modified
            SortBy.Type -> R.string.sort_type
        },
    )
    return "$name ${if (descending) "↓" else "↑"}"
}

/**
 * Ordner (Entwurf 21, wie routes/files im Web): Pfad als Chips, Sortierung
 * und Ansicht, Liste oder Raster mit Vorschaubildern, Mehrfachauswahl durch
 * langes Tippen, Einfügen aus der Zwischenablage, laufende Aufträge und
 * „Rückgängig“; „+“ für Hochladen und Neues.
 */
@Composable
fun FolderScreen(
    ui: FolderUi,
    api: FilesApi?,
    on: FolderCallbacks,
    bottomBar: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val c = Titan.colors
    val location = ui.state.location
    val title = location.segments.lastOrNull() ?: location.root.name
    var hotFolder by remember { mutableStateOf(false) }
    val folderDrop = on.drop?.let { drop ->
        remember(drop) {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean { hotFolder = false; return drop(event, null) }
                override fun onEntered(event: DragAndDropEvent) { hotFolder = true }
                override fun onExited(event: DragAndDropEvent) { hotFolder = false }
                override fun onEnded(event: DragAndDropEvent) { hotFolder = false }
            }
        }
    }
    Box(
        (if (folderDrop != null) Modifier.dragAndDropTarget(shouldStartDragAndDrop = { DragDrop.accepts(it) }, target = folderDrop) else Modifier)
            .then(if (hotFolder) Modifier.border(3.dp, c.accent) else Modifier),
    ) {
        Page(
            scroll = false,
            topBar = {
                Box(Modifier.statusBarsPadding()) {
                    if (ui.selecting) {
                        TopBar(
                            pluralStringResource(R.plurals.files_selected, ui.selected.size, ui.selected.size),
                            onBack = on.clearSelection,
                            backLabel = stringResource(R.string.files_selection_clear),
                            actions = {
                                IconButton(TitanIcons.check, stringResource(R.string.files_select_all), on.selectAll)
                                IconButton(TitanIcons.share, stringResource(R.string.files_share), on.shareSelected)
                                IconButton(TitanIcons.more, stringResource(R.string.files_selection_more), on.selectionMenu)
                            },
                        )
                    } else {
                        TopBar(title, onBack = on.back, backLabel = stringResource(UiR.string.ui_back), actions = {
                            IconButton(TitanIcons.search, stringResource(UiR.string.ui_search), on.search)
                            IconButton(TitanIcons.more, stringResource(UiR.string.ui_more), on.menu)
                        })
                    }
                }
            },
            bottomBar = bottomBar,
            overlay = {
                if (ui.writable && !ui.selecting) {
                    Fab(TitanIcons.plus, stringResource(R.string.files_add), on.add, Modifier.align(Alignment.BottomEnd).padding(18.dp))
                }
                overlay()
            },
        ) {
            val header: @Composable () -> Unit = {
                Column {
                    val crumbs = listOf(location.root.name) + location.segments
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 12.dp).semantics { contentDescription = crumbs.joinToString(" › ") },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        crumbs.forEachIndexed { i, name ->
                            if (i > 0) Icon(TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(16.dp))
                            Chip(name, on = i == crumbs.lastIndex, onClick = { on.crumb(i) })
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Chip(sortLabel(ui.sort, ui.descending), onClick = on.sortMenu)
                        Chip(
                            stringResource(
                                when (ui.view) {
                                    FileView.List -> R.string.view_list
                                    FileView.Small -> R.string.view_small
                                    FileView.Large -> R.string.view_large
                                },
                            ),
                            onClick = on.sortMenu,
                        )
                        if (!location.root.writable) Chip(stringResource(R.string.files_read_only))
                    }
                    ui.clipboard?.let { clip -> ClipboardBar(clip, ui.writable, on.paste, on.dropClipboard) }
                    ui.jobs.firstOrNull { it.running }?.let { j -> JobBar(j, ui.jobs.count { it.running }, on.jobs) }
                    ui.state.error?.let { Problem(errorText(it), Modifier.padding(bottom = 12.dp)) }
                }
            }
            val entries = ui.entries
            val extraUploads = ui.uploads.filter { u -> entries.none { it.name == u.name } }
            when {
                ui.state.entries == null && ui.state.error == null -> Column {
                    header()
                    Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted)
                }
                ui.state.entries == null -> header()
                entries.isEmpty() && extraUploads.isEmpty() -> Column {
                    header()
                    EmptyState(stringResource(R.string.files_empty), if (ui.writable) stringResource(R.string.files_empty_text) else "")
                }
                ui.view == FileView.List -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    item(key = "_header") { header() }
                    items(entries, key = { "e:" + it.name }) { e ->
                        val up = ui.uploads.firstOrNull { it.name == e.name }
                        EntryListRow(api, location, e, up, e.name in ui.selected, ui.selecting, location.full(e.name) in ui.offlinePaths, on, first = e == entries.first(), cursor = ui.cursor == e.name || ui.previewing == e.name)
                    }
                    items(extraUploads, key = { "u:" + it.id }) { u -> UploadRow(u) }
                }
                else -> {
                    val min = if (ui.view == FileView.Small) 104.dp else 168.dp
                    LazyVerticalGrid(GridCells.Adaptive(min), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "_header", span = { GridItemSpan(maxLineSpan) }) { header() }
                        items(entries, key = { "e:" + it.name }) { e ->
                            EntryTile(api, location, e, e.name in ui.selected, ui.selecting, ui.view == FileView.Large, on, cursor = ui.cursor == e.name || ui.previewing == e.name)
                        }
                        items(extraUploads, key = { "u:" + it.id }, span = { GridItemSpan(maxLineSpan) }) { u -> UploadRow(u) }
                    }
                }
            }
        }
        ui.undo?.let { u -> UndoBar(u, on.undo, on.dismissUndo, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp)) }
    }
}

@Composable
private fun UploadRow(u: Transfer) {
    ListRow(
        title = u.name,
        sub = Format.bytes(u.total),
        icon = { FileTypeIcon(iconFor(u.name)) },
        right = { Text(stringResource(R.string.files_uploading, Format.percent(u.fraction.toDouble())), style = TitanType.small, color = Titan.colors.muted) },
        divider = true,
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.entryClicks(e: FileEntry, selecting: Boolean, on: FolderCallbacks): Modifier =
    (if (on.secondary) onSecondaryClick { on.entryMenu(e) } else this)
        .combinedClickable(
            onClick = { if (selecting) on.select(e) else on.open(e) },
            onLongClick = { on.select(e) },
        )

/** Ziehen (langes Drücken und Bewegen) und, für Ordner, Ablegen darauf. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.entryDrag(e: FileEntry, on: FolderCallbacks, selected: Boolean, onHot: (Boolean) -> Unit): Modifier {
    var m = this
    // Ziehen: mit der Maus sofort, am Touchscreen markierter Einträge (langes Drücken markiert, dann ziehen).
    val now by androidx.compose.runtime.rememberUpdatedState(selected)
    on.drag?.let { d ->
        @Suppress("DEPRECATION")
        val block: suspend androidx.compose.foundation.draganddrop.DragAndDropSourceScope.() -> Unit = {
            val scope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (down.type == androidx.compose.ui.input.pointer.PointerType.Mouse || now) {
                    val slop = awaitTouchSlopOrCancellation(down.id) { change: androidx.compose.ui.input.pointer.PointerInputChange, _: androidx.compose.ui.geometry.Offset -> change.consume() }
                    if (slop != null) d(e)?.let { scope.startTransfer(it) }
                }
            }
        }
        m = m.dragAndDropSource(drawDragDecoration = {}, block = block)
    }
    val drop = on.drop
    if (e.dir && drop != null) {
        val target = remember(e, drop) {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean { onHot(false); return drop(event, e) }
                override fun onEntered(event: DragAndDropEvent) = onHot(true)
                override fun onExited(event: DragAndDropEvent) = onHot(false)
                override fun onEnded(event: DragAndDropEvent) = onHot(false)
            }
        }
        m = m.dragAndDropTarget(shouldStartDragAndDrop = { DragDrop.accepts(it) }, target = target)
    }
    return m
}

/** Hervorhebung unter dem Mauszeiger, dem Tastaturfokus oder beim Ablegen. */
@Composable
private fun rowTint(selected: Boolean, cursor: Boolean, hot: Boolean, hovered: Boolean): androidx.compose.ui.graphics.Color {
    val c = Titan.colors
    return when {
        hot -> c.accent.copy(alpha = .30f)
        selected -> c.accent.copy(alpha = .16f)
        hovered || cursor -> c.chip
        else -> c.surface
    }
}

@Composable
private fun SelectMark(selected: Boolean) {
    val label = stringResource(if (selected) R.string.files_is_selected else R.string.files_not_selected)
    Box(Modifier.semantics { stateDescription = label }) { RadioMark(selected) }
}

@Composable
private fun EntryListRow(
    api: FilesApi?,
    location: Location,
    e: FileEntry,
    upload: Transfer?,
    selected: Boolean,
    selecting: Boolean,
    offline: Boolean,
    on: FolderCallbacks,
    first: Boolean,
    cursor: Boolean = false,
) {
    val c = Titan.colors
    val date = entryDate(e.modified)
    val sub = listOfNotNull(if (e.dir) null else Format.bytes(e.size), date, if (offline) stringResource(R.string.files_offline_short) else null).joinToString(" · ")
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var hot by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(rowTint(selected, cursor, hot, hovered))
            .then(if (cursor) Modifier.border(2.dp, c.accent, RoundedCornerShape(10.dp)) else Modifier)
            .hoverable(hover)
            .entryDrag(e, on, selected) { hot = it }
            .semantics { this.selected = selected },
    ) {
        if (!first) Divider()
        Row(
            Modifier.fillMaxWidth().entryClicks(e, selecting, on).heightIn(min = 60.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selecting) SelectMark(selected)
            if (!e.dir && Kinds.hasThumb(e.name)) {
                NasThumb(api, location.root.id, location.full(e.name), e.modified, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), size = 160) { EntryIcon(e) }
            } else {
                EntryIcon(e)
            }
            Column(Modifier.weight(1f)) {
                Text(e.name, style = TitanType.rowTitle, color = c.text)
                if (sub.isNotEmpty()) Text(sub, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                upload?.let { Text(stringResource(R.string.files_uploading, Format.percent(it.fraction.toDouble())), style = TitanType.small, color = c.muted) }
            }
            if (!selecting) IconButton(TitanIcons.more, stringResource(R.string.files_actions, e.name), { on.entryMenu(e) }, size = 20.dp, tint = c.muted)
        }
    }
}

@Composable
private fun EntryTile(api: FilesApi?, location: Location, e: FileEntry, selected: Boolean, selecting: Boolean, large: Boolean, on: FolderCallbacks, cursor: Boolean = false) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var hot by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(rowTint(selected, cursor, hot, hovered))
            .border(if (cursor) 2.dp else 1.dp, if (selected || cursor) c.accent else c.border, shape)
            .hoverable(hover)
            .entryDrag(e, on, selected) { hot = it }
            .entryClicks(e, selecting, on).semantics { this.selected = selected }.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(c.bg), contentAlignment = Alignment.Center) {
            if (!e.dir && Kinds.hasThumb(e.name)) {
                NasThumb(api, location.root.id, location.full(e.name), e.modified, Modifier.fillMaxSize(), size = if (large) 640 else 320) {
                    EntryIcon(e, if (large) 64.dp else 44.dp)
                }
            } else {
                EntryIcon(e, if (large) 64.dp else 44.dp)
            }
            if (selecting) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { SelectMark(selected) }
        }
        Text(
            e.name,
            style = if (large) TitanType.body else TitanType.small,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        if (large) {
            val sub = listOfNotNull(if (e.dir) null else Format.bytes(e.size), entryDate(e.modified)).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = TitanType.small, color = c.muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ClipboardBar(clip: Clip, writable: Boolean, onPaste: () -> Unit, onDrop: () -> Unit) {
    val c = Titan.colors
    Card(Modifier.padding(bottom = 10.dp)) {
        ListRow(
            pluralStringResource(if (clip.move) R.plurals.clip_cut else R.plurals.clip_copied, clip.names.size, clip.names.size),
            sub = stringResource(R.string.clip_from, clip.from.place),
            icon = { RowIcon(if (clip.move) TitanIcons.move else TitanIcons.file) },
            right = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (writable) Chip(stringResource(R.string.clip_paste), on = true, onClick = onPaste)
                    IconButton(TitanIcons.x, stringResource(R.string.clip_drop), onDrop, size = 20.dp, tint = c.muted)
                }
            },
        )
    }
}

@Composable
private fun JobBar(job: Job, running: Int, onClick: () -> Unit) {
    Card(Modifier.padding(bottom = 10.dp)) {
        ListRow(
            jobTitle(job),
            sub = jobProgress(job) + if (running > 1) " · " + pluralStringResource(R.plurals.jobs_more, running - 1, running - 1) else "",
            icon = { RowIcon(TitanIcons.sync) },
            chevron = true,
            onClick = onClick,
            bottom = { Bar(job.fraction, Modifier.padding(top = 8.dp)) },
        )
    }
}

@Composable
private fun UndoBar(u: UndoOffer, onUndo: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val c = Titan.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.text).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (u.kind) {
                UndoKind.Trash -> pluralStringResource(R.plurals.undo_trash, u.count, u.count)
                UndoKind.Move -> pluralStringResource(R.plurals.undo_move, u.count, u.count)
                UndoKind.Rename -> stringResource(R.string.undo_rename)
            },
            style = TitanType.body,
            color = c.bg,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.undo_do),
            style = TitanType.button,
            color = c.bg,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onUndo).padding(horizontal = 12.dp, vertical = 10.dp),
        )
        IconButton(TitanIcons.x, stringResource(UiR.string.ui_close), onDismiss, size = 18.dp, tint = c.bg)
    }
}

/** Titel eines Auftrags in Worten. */
@Composable
fun jobTitle(j: Job): String {
    val what = j.names?.let { if (it.size == 1) it[0] else pluralStringResource(R.plurals.jobs_items, it.size, it.size) } ?: j.dir
    return when (j.operation) {
        "download" -> stringResource(R.string.jobs_download, what)
        "extract" -> stringResource(R.string.jobs_extract, what)
        else -> stringResource(if (j.move) R.string.jobs_move else R.string.jobs_copy, what)
    }
}

@Composable
fun jobProgress(j: Job): String = when {
    j.paused -> stringResource(R.string.jobs_paused)
    j.planning -> stringResource(R.string.jobs_planning)
    j.state == "done" -> if (j.failed > 0) stringResource(R.string.jobs_done_problems, j.failed) else stringResource(R.string.jobs_state_done)
    j.state == "failed" -> stringResource(R.string.jobs_state_failed, j.error.ifEmpty { j.errorCode })
    j.state == "cancelled" -> stringResource(R.string.jobs_state_cancelled)
    j.bytes > 0 -> stringResource(R.string.jobs_bytes, Format.bytes(j.copied), Format.bytes(j.bytes), Format.percent(j.fraction.toDouble()))
    else -> stringResource(R.string.jobs_files, j.done, j.files)
}

/** Sortierung und Ansicht (Blatt). */
@Composable
fun BoxScope.SortSheet(
    view: FileView,
    sort: SortBy,
    descending: Boolean,
    showHidden: Boolean,
    remembered: Boolean,
    onChange: (FileView, SortBy, Boolean) -> Unit,
    onHidden: (Boolean) -> Unit,
    onRemember: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Titan.colors
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    Sheet(onDismiss) {
        Text(stringResource(R.string.sort_title), style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
        Card(background = c.bg) {
            SortBy.entries.forEachIndexed { i, s ->
                ListRow(
                    stringResource(
                        when (s) {
                            SortBy.Name -> R.string.sort_name
                            SortBy.Size -> R.string.sort_size
                            SortBy.Modified -> R.string.sort_modified
                            SortBy.Type -> R.string.sort_type
                        },
                    ),
                    right = { RadioMark(s == sort) },
                    divider = i > 0,
                    onClick = { onChange(view, s, descending) },
                )
            }
        }
        Card(background = c.bg, modifier = Modifier.padding(top = 10.dp)) {
            ListRow(stringResource(R.string.sort_ascending), right = { RadioMark(!descending) }, onClick = { onChange(view, sort, false) })
            ListRow(stringResource(R.string.sort_descending), right = { RadioMark(descending) }, divider = true, onClick = { onChange(view, sort, true) })
        }
        Text(stringResource(R.string.view_title), style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
        Card(background = c.bg) {
            FileView.entries.forEachIndexed { i, v ->
                ListRow(
                    stringResource(
                        when (v) {
                            FileView.List -> R.string.view_list
                            FileView.Small -> R.string.view_small
                            FileView.Large -> R.string.view_large
                        },
                    ),
                    icon = { RowIcon(if (v == FileView.List) TitanIcons.file else TitanIcons.grid) },
                    right = { RadioMark(v == view) },
                    divider = i > 0,
                    onClick = { onChange(v, sort, descending) },
                )
            }
        }
        Card(background = c.bg, modifier = Modifier.padding(top = 10.dp)) {
            ListRow(
                stringResource(R.string.view_hidden), sub = stringResource(R.string.view_hidden_text),
                right = { Toggle(showHidden, onHidden, on, off) }, onClick = { onHidden(!showHidden) },
            )
            ListRow(
                stringResource(R.string.view_remember), sub = stringResource(R.string.view_remember_text),
                right = { Toggle(remembered, onRemember, on, off) }, divider = true, onClick = { onRemember(!remembered) },
            )
        }
    }
}

/** „+“: Hochladen und Neues (wie MobileActions „Neu“ im Web). */
@Composable
fun BoxScope.AddSheet(
    canPaste: Boolean,
    onUpload: () -> Unit,
    onCamera: (() -> Unit)?,
    onFolder: () -> Unit,
    onFile: () -> Unit,
    onUrl: () -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    onFolderUpload: (() -> Unit)? = null,
) {
    val c = Titan.colors
    Sheet(onDismiss) {
        Card(background = c.bg) {
            onFolderUpload?.let { f -> ListRow(stringResource(R.string.add_folder_upload), sub = stringResource(R.string.add_folder_upload_text), icon = { RowIcon(TitanIcons.folder) }, onClick = { onDismiss(); f() }) }
            ListRow(stringResource(R.string.add_upload), sub = stringResource(R.string.add_upload_text), icon = { RowIcon(TitanIcons.upload) }, onClick = { onDismiss(); onUpload() })
            onCamera?.let { cam -> ListRow(stringResource(R.string.add_camera), icon = { RowIcon(TitanIcons.photo) }, divider = true, onClick = { onDismiss(); cam() }) }
            ListRow(stringResource(R.string.add_folder), icon = { RowIcon(TitanIcons.folder) }, divider = true, onClick = { onDismiss(); onFolder() })
            ListRow(stringResource(R.string.add_file), sub = stringResource(R.string.add_file_text), icon = { RowIcon(TitanIcons.edit) }, divider = true, onClick = { onDismiss(); onFile() })
            ListRow(stringResource(R.string.add_url), sub = stringResource(R.string.add_url_text), icon = { RowIcon(TitanIcons.globe) }, divider = true, onClick = { onDismiss(); onUrl() })
            if (canPaste) ListRow(stringResource(R.string.clip_paste), icon = { RowIcon(TitanIcons.file) }, divider = true, onClick = { onDismiss(); onPaste() })
        }
    }
}

/** Menü des Ordners (⋮). */
@Composable
fun BoxScope.FolderMenu(
    name: String,
    favorite: Boolean,
    offline: Boolean,
    hasTrash: Boolean,
    onFavorite: (Boolean) -> Unit,
    onOffline: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onProperties: () -> Unit,
    onTrash: () -> Unit,
    onJobs: () -> Unit,
    onUsage: () -> Unit,
    onDuplicates: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    onWindow: (() -> Unit)? = null,
    onCompare: (() -> Unit)? = null,
) {
    val c = Titan.colors
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    Sheet(onDismiss) {
        Text(name, style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(bottom = 12.dp))
        Card(background = c.bg) {
            ListRow(stringResource(R.string.menu_favorite), sub = stringResource(R.string.menu_favorite_text), icon = { RowIcon(TitanIcons.pin) },
                right = { Toggle(favorite, onFavorite, on, off) }, onClick = { onFavorite(!favorite) })
            ListRow(stringResource(R.string.off_folder_toggle), sub = stringResource(R.string.off_folder_toggle_text), icon = { RowIcon(TitanIcons.offline) },
                right = { Toggle(offline, onOffline, on, off) }, divider = true, onClick = { onOffline(!offline) })
        }
        Card(background = c.bg, modifier = Modifier.padding(top = 10.dp)) {
            ListRow(stringResource(R.string.files_select_all), icon = { RowIcon(TitanIcons.check) }, onClick = { onDismiss(); onSelectAll() })
            ListRow(stringResource(R.string.files_properties), icon = { RowIcon(TitanIcons.info) }, divider = true, onClick = { onDismiss(); onProperties() })
            ListRow(stringResource(R.string.menu_reload), icon = { RowIcon(TitanIcons.restart) }, divider = true, onClick = { onDismiss(); onReload() })
            onWindow?.let { w -> ListRow(stringResource(R.string.window_open), icon = { RowIcon(TitanIcons.monitor) }, divider = true, onClick = { onDismiss(); w() }) }
            if (hasTrash) ListRow(stringResource(R.string.files_trash), icon = { RowIcon(TitanIcons.trash) }, chevron = true, divider = true, onClick = { onDismiss(); onTrash() })
            ListRow(stringResource(R.string.jobs_title), icon = { RowIcon(TitanIcons.sync) }, chevron = true, divider = true, onClick = { onDismiss(); onJobs() })
        }
        Card(background = c.bg, modifier = Modifier.padding(top = 10.dp)) {
            ListRow(stringResource(R.string.usage_title), sub = stringResource(R.string.usage_text), icon = { RowIcon(TitanIcons.hdd) }, chevron = true, onClick = { onDismiss(); onUsage() })
            ListRow(stringResource(R.string.dupes_title), sub = stringResource(R.string.dupes_text), icon = { RowIcon(TitanIcons.file) }, chevron = true, divider = true, onClick = { onDismiss(); onDuplicates() })
            onCompare?.let { cmp -> ListRow(stringResource(R.string.compare_title), sub = stringResource(R.string.compare_text), icon = { RowIcon(TitanIcons.nodes) }, chevron = true, divider = true, onClick = { onDismiss(); cmp() }) }
        }
    }
}

/** Aktionen einer Datei oder eines Ordners (Entwurf 22) – auch für die Auswahl. */
@Composable
fun BoxScope.EntrySheet(
    entries: List<FileEntry>,
    withTrash: Boolean,
    writable: Boolean,
    offline: Boolean?,
    officeName: String?,
    onOffline: (Boolean) -> Unit,
    onOpen: (() -> Unit)?,
    onOffice: () -> Unit,
    onOpenWith: (() -> Unit)?,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: (() -> Unit)?,
    onBatchRename: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onExtract: (() -> Unit)?,
    onProperties: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onWindow: (() -> Unit)? = null,
    onDocker: (() -> Unit)? = null,
) {
    val c = Titan.colors
    val single = entries.singleOrNull()
    val files = entries.filter { !it.dir }
    Sheet(onDismiss) {
        Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (single != null) EntryIcon(single) else RowIcon(TitanIcons.check, size = 32.dp)
            Column(Modifier.weight(1f)) {
                Text(single?.name ?: pluralStringResource(R.plurals.files_selected, entries.size, entries.size), style = TitanType.rowTitle, color = c.text)
                val size = Format.bytes(files.sumOf { it.size })
                val date = single?.let { s -> Format.instant(s.modified)?.let { Format.longDate(it) } }
                Text(
                    when {
                        single?.dir == true -> date ?: ""
                        date != null -> stringResource(R.string.files_changed, size, date)
                        else -> size
                    },
                    style = TitanType.rowSub,
                    color = c.muted,
                )
            }
        }
        fun go(f: () -> Unit) { onDismiss(); f() }
        Card(background = c.bg) {
            var divider = false
            @Composable
            fun row(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, sub: String? = null, danger: Boolean = false, action: () -> Unit) {
                ListRow(
                    title, sub = sub, icon = { RowIcon(icon, tint = if (danger) c.dangerInk else c.muted) },
                    titleColor = if (danger) c.dangerInk else c.text, divider = divider, onClick = { go(action) },
                )
                divider = true
            }
            onOpen?.let { row(stringResource(R.string.entry_open), TitanIcons.file, action = it) }
            onWindow?.let { row(stringResource(R.string.window_open), TitanIcons.monitor, action = it) }
            officeName?.let { row(stringResource(R.string.view_open_in, it), TitanIcons.edit, action = onOffice) }
            onEdit?.let { row(stringResource(R.string.view_edit), TitanIcons.edit, action = it) }
            onOpenWith?.let { row(stringResource(R.string.view_open_with), TitanIcons.box, stringResource(R.string.view_open_with_text), action = it) }
            onDocker?.let { row(stringResource(R.string.docker_title), TitanIcons.docker, action = it) }
            if (files.isNotEmpty()) row(stringResource(R.string.files_share), TitanIcons.share, stringResource(R.string.files_share_text), action = onShare)
            row(
                stringResource(R.string.files_download), TitanIcons.download,
                if (entries.size > 1 || single?.dir == true) stringResource(R.string.download_zip_text) else null, action = onDownload,
            )
            if (offline != null) {
                ListRow(
                    stringResource(R.string.files_offline),
                    sub = stringResource(R.string.files_offline_text),
                    icon = { RowIcon(TitanIcons.offline) },
                    right = { Toggle(offline, onOffline, stringResource(UiR.string.ui_on), stringResource(UiR.string.ui_off)) },
                    divider = divider,
                )
                divider = true
            }
            row(stringResource(R.string.entry_copy), TitanIcons.file, stringResource(R.string.entry_copy_text), action = onCopy)
            if (writable) {
                row(stringResource(R.string.entry_cut), TitanIcons.move, stringResource(R.string.entry_cut_text), action = onCut)
                onRename?.let { row(stringResource(R.string.files_rename), TitanIcons.edit, action = it) }
                onBatchRename?.let { row(stringResource(R.string.batch_title), TitanIcons.edit, stringResource(R.string.batch_text), action = it) }
                onExtract?.let { row(stringResource(R.string.extract_title), TitanIcons.archive, action = it) }
            }
            onProperties?.let { row(stringResource(R.string.files_properties), TitanIcons.info, action = it) }
            if (writable) {
                row(stringResource(if (withTrash) R.string.files_to_trash else R.string.files_delete_forever), TitanIcons.trash, danger = true, action = onDelete)
            }
        }
    }
}
