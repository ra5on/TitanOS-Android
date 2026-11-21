package io.github.ra5on.titanos.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.github.ra5on.titanos.ui.onSecondaryClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Divider
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
import io.github.ra5on.titanos.ui.generated.IconSquareColors
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import io.github.ra5on.titanos.ui.R as UiR
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Was eine Zeile der Liste zeigt: die neueste Mail einer Unterhaltung oder eine aufgeklappte. */
private data class Shown(val row: MessageRow, val thread: Thread, val child: Boolean)

/** Was nach einer Aktion an der Liste geschieht (Hinweis „3 archiviert.“). */
private enum class Act { Seen, Unseen, Flag, Unflag, Archive, Delete, Junk, Move }

/**
 * Ebene „Liste“ eines Ordners bzw. „Alle Posteingänge“/„Markiert“
 * (MessageList.svelte am Handy): Suche, Filter-Blatt, Gruppen nach Tag,
 * Unterhaltungen, Wischen, „Bearbeiten“ mit Leiste unten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    model: MailModel,
    list: MailList,
    onBack: () -> Unit,
    onOpen: (MessageRow) -> Unit,
    onCompose: () -> Unit,
    now: Instant = Instant.now(),
    startEditing: Boolean = false,
    startFilter: Boolean = false,
    showBack: Boolean = true,
    onReplyTo: (MessageRow, ComposeMode) -> Unit = { _, _ -> },
    selected: String? = null,
    newWindow: ((MessageRow) -> Unit)? = null,
    dragRows: Boolean = false,
    startHelp: Boolean = false,
) {
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    val locale = currentLocale()
    val zone = ZoneId.systemDefault()
    var editing by remember { mutableStateOf(startEditing) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var search by remember { mutableStateOf(list.filters.q) }
    var filterOpen by remember { mutableStateOf(startFilter) }
    var actionsFor by remember { mutableStateOf<List<MessageRow>?>(null) }
    var moveFor by remember { mutableStateOf<List<MessageRow>?>(null) }
    var confirmFor by remember { mutableStateOf<List<MessageRow>?>(null) }
    var notice by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<Throwable?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(startHelp) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    val prefs = model.prefs
    val ctx = LocalContext.current

    val folder = if (list.unified) null else model.findFolder(list.account, list.folder)
    val account = model.account(list.account)
    val heading = when {
        list.unified && list.folder == "flagged" -> stringResource(R.string.mail_box_flagged)
        list.unified -> stringResource(R.string.mail_box_allInboxes)
        folder != null -> folderName(folder)
        else -> list.folder.split('/', '.').last().ifBlank { list.folder }
    }
    val sub = when {
        list.unified -> stringResource(R.string.mail_all_sub, model.accounts.orEmpty().size.toString())
        account != null -> if (account.name.isNotBlank() && account.name != account.email) "${account.name} · ${account.email}" else account.email
        else -> null
    }

    // Erste Seite beim Öffnen und wenn der Live-Strom eine Änderung meldet (gebündelt).
    LaunchedEffect(list) { if (!list.loaded) list.load() }
    LaunchedEffect(model.listVersion) {
        if (list.seenVersion >= 0 && list.seenVersion != model.listVersion) {
            delay(800)
            list.load(quiet = true)
        }
        list.seenVersion = model.listVersion
    }
    // Ohne Live-Strom (andere Ordner, Postfächer ohne IDLE) alle 2 Minuten, solange die Liste offen ist.
    LaunchedEffect(list) {
        while (model.autoload) {
            delay(120_000)
            list.load(quiet = true)
        }
    }

    val threads = threadRows(list.rows, prefs.threads)
    val groups = groupByDay(threads, { it.rows.first().date }, now, zone, locale)
    fun isOpen(t: Thread) = t.rows.any { rowKey(it) in expanded }
    val shown = buildList {
        for (g in groups) for (t in g.items) {
            add(g.group to Shown(t.rows.first(), t, false))
            if (t.rows.size > 1 && isOpen(t)) for (r in t.rows.drop(1)) add(g.group to Shown(r, t, true))
        }
    }
    fun rowsOf(s: Shown) = if (s.child || isOpen(s.thread)) listOf(s.row) else s.thread.rows
    val pickedRows = list.rows.filter { rowKey(it) in picked }
    val accOf = { r: MessageRow -> r.account.ifEmpty { list.account } }
    val samePlace = pickedRows.map(accOf).distinct().size <= 1

    val doneArchived = stringResource(R.string.mail_done_archived, "%d")
    val doneDeleted = stringResource(R.string.mail_done_deleted, "%d")
    val doneJunk = stringResource(R.string.mail_done_junk, "%d")
    val doneMoved = stringResource(R.string.mail_done_moved, "%d", "%s")

    fun act(kind: Act, items: List<MessageRow>, to: MailFolder? = null, toName: String = "") {
        if (items.isEmpty()) return
        busy = true
        notice = ""
        actionError = null
        scope.launch {
            try {
                when (kind) {
                    Act.Seen -> model.setFlag(items, list, "seen", true)
                    Act.Unseen -> model.setFlag(items, list, "seen", false)
                    Act.Flag -> model.setFlag(items, list, "flagged", true)
                    Act.Unflag -> model.setFlag(items, list, "flagged", false)
                    Act.Archive -> { model.archive(items, list); notice = doneArchived.format(items.size) }
                    Act.Delete -> { model.delete(items, list); notice = doneDeleted.format(items.size) }
                    Act.Junk -> { model.junk(items, list); notice = doneJunk.format(items.size) }
                    Act.Move -> { model.move(items, list, to!!.path); notice = doneMoved.format(items.size, toName) }
                }
                picked = picked - items.map(::rowKey).toSet()
                if (picked.isEmpty() && kind !in setOf(Act.Seen, Act.Unseen, Act.Flag, Act.Unflag)) editing = false
            } catch (e: Exception) {
                actionError = e
            } finally {
                busy = false
            }
        }
    }
    fun askDelete(items: List<MessageRow>) {
        val trash = items.any { model.inTrash(accOf(it), it.folder.ifEmpty { list.folder }) }
        if (trash || prefs.confirmDelete) confirmFor = items else act(Act.Delete, items)
    }
    fun runSwipe(a: SwipeAction, items: List<MessageRow>) {
        val unread = items.any { !it.flags.seen }
        val flagged = items.any { it.flags.flagged }
        when (a) {
            SwipeAction.Seen -> act(if (unread) Act.Seen else Act.Unseen, items)
            SwipeAction.Flag -> act(if (flagged) Act.Unflag else Act.Flag, items)
            SwipeAction.Archive -> act(Act.Archive, items)
            SwipeAction.Delete -> askDelete(items)
            SwipeAction.Junk -> act(Act.Junk, items)
            SwipeAction.Move -> moveFor = items
            SwipeAction.None -> Unit
        }
    }

    val unreadCount = if (list.unified) {
        model.accounts.orEmpty().sumOf { a -> model.folderOf(a.id, "inbox")?.unread ?: 0 }
    } else {
        folder?.takeIf { it.role != "junk" && it.role != "trash" }?.unread ?: 0
    }
    val refreshed = model.refreshedAt
    val stateLine = when {
        !list.loaded -> stringResource(UiR.string.ui_loading)
        refreshed == null || now.toEpochMilli() - refreshed.toEpochMilli() < 60_000 -> stringResource(R.string.mail_bar_justNow)
        else -> stringResource(R.string.mail_bar_updatedAt, listDate(refreshed.toString(), locale, zone, now))
    }
    val filterWords = listOfNotNull(
        stringResource(R.string.mail_filter_unread).takeIf { list.filters.unread },
        stringResource(R.string.mail_filter_flagged).takeIf { list.filters.flagged },
        stringResource(R.string.mail_filter_attachmentWord).takeIf { list.filters.attachment },
        list.filters.from.takeIf { it.isNotBlank() }?.let { stringResource(R.string.mail_filter_fromWord, it) },
        stringResource(R.string.mail_search_period).takeIf { list.filters.since.isNotBlank() || list.filters.until.isNotBlank() },
        stringResource(R.string.mail_filter_allFolders).takeIf { list.scope == Scope.All },
    )

    // Die Zeilen in der gezeigten Reihenfolge (für Pfeile, j/k, Enter).
    val order = shown.map { it.second }
    val current = order.firstOrNull { rowKey(it.row) == (cursor ?: selected) }
    fun move(by: Int) {
        if (order.isEmpty()) return
        val at = order.indexOfFirst { rowKey(it.row) == (cursor ?: selected) }
        val next = order[(if (at < 0) 0 else (at + by)).coerceIn(0, order.size - 1)]
        cursor = rowKey(next.row)
        // Liest man nebenan schon, folgt der Lesebereich (wie j/k im Web).
        if (selected != null) onOpen(next.row)
    }
    val keyboard = hasKeyboard()
    LaunchedEffect(keyboard) { if (keyboard) runCatching { rootFocus.requestFocus() } }
    Page(
        modifier = (if (keyboard) Modifier.focusRequester(rootFocus).focusable() else Modifier).onPreviewKeyEvent { e ->
            if (!prefs.shortcuts || e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return@onPreviewKeyEvent false
            if (searchFocused) {
                if (e.key == Key.Escape) { runCatching { rootFocus.requestFocus() }; return@onPreviewKeyEvent true }
                return@onPreviewKeyEvent false
            }
            val items = current?.let { if (it.child || it.thread.rows.size == 1) listOf(it.row) else it.thread.rows }
            when (e.key) {
                Key.J, Key.DirectionDown -> move(1)
                Key.K, Key.DirectionUp -> move(-1)
                Key.Enter, Key.NumPadEnter -> current?.let { onOpen(it.row) }
                Key.E -> items?.let { act(Act.Archive, it) }
                Key.Delete, Key.Backspace -> items?.let { askDelete(it) }
                Key.S -> items?.let { act(if (it.any { r -> r.flags.flagged }) Act.Unflag else Act.Flag, it) }
                Key.U -> items?.let { act(if (it.any { r -> !r.flags.seen }) Act.Seen else Act.Unseen, it) }
                Key.R -> current?.let { onReplyTo(it.row, ComposeMode.Reply) }
                Key.A -> current?.let { onReplyTo(it.row, ComposeMode.ReplyAll) }
                Key.F -> current?.let { onReplyTo(it.row, ComposeMode.Forward) }
                Key.N, Key.C -> onCompose()
                Key.M -> items?.let { moveFor = it }
                Key.Slash -> if (e.isShiftPressed) helpOpen = true else runCatching { searchFocus.requestFocus() }
                Key.Escape -> when {
                    helpOpen -> helpOpen = false
                    editing -> { editing = false; picked = emptySet() }
                    showBack -> onBack()
                    else -> return@onPreviewKeyEvent false
                }
                else -> return@onPreviewKeyEvent false
            }
            true
        },
        scroll = false,
        topBar = {
            LevelHead(heading, sub, back = stringResource(R.string.mail_back_folders), onBack = if (editing || !showBack) null else onBack) {
                if (editing) {
                    TextAction(stringResource(R.string.mail_pick_all), { picked = list.rows.map(::rowKey).toSet() })
                    Spacer(Modifier.weight(1f))
                    TextAction(stringResource(R.string.mail_box_done), { editing = false; picked = emptySet() }, bold = true)
                } else if (list.rows.isNotEmpty()) {
                    TextAction(stringResource(R.string.mail_box_edit), { editing = true })
                }
            }
        },
        bottomBar = {
            if (filterOpen || actionsFor != null || moveFor != null || helpOpen) {
                // Ein Blatt ist offen: es reicht bis unten.
            } else if (editing) {
                Column(Modifier.fillMaxWidth().background(c.header)) {
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextAction(stringResource(R.string.mail_bar_mark), { actionsFor = pickedRows }, enabled = pickedRows.isNotEmpty() && !busy)
                        TextAction(stringResource(if (samePlace) R.string.mail_bar_move else R.string.mail_bar_moveOne), { moveFor = pickedRows }, enabled = pickedRows.isNotEmpty() && samePlace && !busy)
                        TextAction(stringResource(R.string.mail_action_delete), { askDelete(pickedRows) }, enabled = pickedRows.isNotEmpty() && !busy, danger = true)
                    }
                    Box(Modifier.navigationBarsPadding())
                }
            } else {
                MailBottomBar(
                    stateLine,
                    stringResource(R.string.mail_unreadCount, unreadCount.toString()),
                    left = {
                        IconButton(MailIcons.filter, stringResource(R.string.mail_bar_filter), { filterOpen = true }, tint = if (filterWords.isNotEmpty()) c.accent else c.text)
                    },
                    onCompose = onCompose,
                )
            }
        },
        overlay = {
            if (filterOpen) FilterSheet(list, onDone = { f, s -> filterOpen = false; list.filters = f; list.scope = s; scope.launch { list.load() } }, onDismiss = { filterOpen = false })
            actionsFor?.let { items ->
                MarkSheet(
                    model, items, list,
                    onReply = { mode -> actionsFor = null; onReplyTo(items.first(), mode) },
                    onNewWindow = newWindow?.takeIf { items.size == 1 }?.let { open -> { actionsFor = null; open(items.first()) } },
                    onAct = { k -> actionsFor = null; act(k, items) },
                    onMove = { actionsFor = null; moveFor = items },
                    onDelete = { actionsFor = null; askDelete(items) },
                    onDismiss = { actionsFor = null },
                )
            }
            if (helpOpen) KeysSheet { helpOpen = false }
            moveFor?.let { items ->
                val acc = accOf(items.first())
                val from = items.first().folder.ifEmpty { list.folder }
                MoveSheet(model, acc, from, onPick = { f, name -> moveFor = null; act(Act.Move, items, f, name) }, onDismiss = { moveFor = null })
            }
            confirmFor?.let { items ->
                val trash = items.any { model.inTrash(accOf(it), it.folder.ifEmpty { list.folder }) }
                ConfirmDialog(
                    title = if (items.size == 1) stringResource(if (trash) R.string.mail_delete_title else R.string.mailx_delete_ask) else stringResource(if (trash) R.string.mail_bulk_deleteTitle else R.string.mailx_delete_ask_many, items.size.toString()),
                    text = if (trash) stringResource(if (items.size == 1) R.string.mail_delete_forever else R.string.mail_bulk_deleteText) else stringResource(R.string.mailx_delete_to_trash),
                    actions = listOf(
                        DialogAction(stringResource(if (trash) R.string.mail_action_deleteForever else R.string.mail_action_delete), danger = true) { confirmFor = null; act(Act.Delete, items) },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { confirmFor = null },
                    ),
                    onDismiss = { confirmFor = null },
                )
            }
        },
    ) {
        val state = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 5 } }
        LaunchedEffect(nearEnd, list.rows.size) { if (nearEnd && list.more) list.loadMore() }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    list.load(quiet = true)
                    if (!list.unified) model.refreshFolders(list.account, now = true)
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                item {
                    SearchField(search, { search = it }, list.scope == Scope.All, list.filters.q, Modifier.focusRequester(searchFocus).onFocusChanged { searchFocused = it.isFocused }) {
                        list.filters = list.filters.copy(q = search)
                        scope.launch { list.load() }
                    }
                    if (filterWords.isNotEmpty()) {
                        FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.mail_bar_filteredBy), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 6.dp))
                            for (w in filterWords) Chip(w, on = true)
                            Chip(stringResource(R.string.mail_search_reset), onClick = {
                                list.filters = Filters(q = list.filters.q); list.scope = Scope.Folder; scope.launch { list.load() }
                            })
                        }
                    }
                    if (list.scope == Scope.All && !list.filters.any) Note(stringResource(R.string.mail_search_needCriteria), Modifier.padding(bottom = 8.dp))
                    list.searched?.let { s ->
                        Text(stringResource(R.string.mail_search_foundAll, s.total.toString(), s.folders.toString()) +
                            if (s.total > s.messages.size) " " + stringResource(R.string.mail_search_newest, s.messages.size.toString()) else "",
                            style = TitanType.small, color = c.muted, modifier = Modifier.padding(bottom = 6.dp))
                        if (s.truncated) Note(stringResource(R.string.mail_search_truncated), Modifier.padding(bottom = 8.dp), icon = TitanIcons.warn)
                    }
                    if (list.failed.isNotEmpty()) {
                        val names = list.failed.joinToString(", ") { f -> model.account(f.account)?.label ?: f.account }
                        Problem(stringResource(R.string.mail_all_failed, names), Modifier.padding(bottom = 8.dp))
                    }
                    if (notice.isNotEmpty()) Done(notice, Modifier.padding(bottom = 8.dp))
                    list.error?.let { Problem(errorText(it), Modifier.padding(bottom = 8.dp)) }
                    actionError?.let { Problem(errorText(it), Modifier.padding(bottom = 8.dp)) }
                    if (model.live == Live.Down) Note(stringResource(R.string.mail_live_down), Modifier.padding(bottom = 8.dp), icon = TitanIcons.warn)
                    if (list.loaded && list.rows.isEmpty() && list.error == null) {
                        EmptyState(
                            stringResource(if (list.filters.any) R.string.mail_list_noneFound else R.string.mail_list_empty),
                            "",
                        )
                    }
                    if (!list.loaded && list.loading) Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(8.dp))
                }
                var lastGroup: DayGroup? = null
                for ((g, s) in shown) {
                    if (g != lastGroup) {
                        lastGroup = g
                        item(key = "g-$g") { SectionHeader(groupName(g)) }
                    }
                    item(key = "r-" + rowKey(s.row) + if (s.child) "-c" else "") {
                        val items = rowsOf(s)
                        val unread = items.any { !it.flags.seen }
                        val flagged = items.any { it.flags.flagged }
                        val inTrash = model.inTrash(accOf(s.row), s.row.folder.ifEmpty { list.folder })
                        @Composable
                        fun swipeOf(a: SwipeAction): Swipe? = when (a) {
                            SwipeAction.Seen -> Swipe(stringResource(if (unread) R.string.mail_action_markRead else R.string.mail_action_markUnread), TitanIcons.mail, IconSquareColors.blue) { runSwipe(a, items) }
                            SwipeAction.Flag -> Swipe(stringResource(if (flagged) R.string.mail_action_unflag else R.string.mail_action_flag), MailIcons.flag, IconSquareColors.amber) { runSwipe(a, items) }
                            SwipeAction.Archive -> Swipe(stringResource(R.string.mail_action_archive), TitanIcons.archive, IconSquareColors.violet) { runSwipe(a, items) }
                            SwipeAction.Delete -> Swipe(stringResource(if (inTrash) R.string.mail_action_deleteForever else R.string.mail_action_delete), TitanIcons.trash, IconSquareColors.red) { runSwipe(a, items) }
                            SwipeAction.Junk -> Swipe(stringResource(R.string.mail_action_junk), MailIcons.junk, IconSquareColors.slate) { runSwipe(a, items) }
                            SwipeAction.Move -> Swipe(stringResource(R.string.mail_action_move), TitanIcons.move, IconSquareColors.teal) { runSwipe(a, items) }
                            SwipeAction.None -> null
                        }
                        val trailing = listOfNotNull(
                            swipeOf(prefs.swipeLeft),
                            swipeOf(if (prefs.swipeLeft == SwipeAction.Flag) SwipeAction.Delete else SwipeAction.Flag),
                            Swipe(stringResource(R.string.mail_action_more), TitanIcons.more, IconSquareColors.slate) { actionsFor = items },
                        ).distinctBy { it.label }
                        // Mit Ziehen und Ablegen (große Bildschirme) gehört die Bewegung dem Ziehen; die Wischaktionen bleiben im Menü und für Bildschirmleser.
                        SwipeRow(swipeOf(prefs.swipeRight), trailing, enabled = !editing && !dragRows) {
                            Entry(
                                model, s, list, prefs, now, locale, zone,
                                current = rowKey(s.row) == selected || rowKey(s.row) == cursor,
                                drag = { MailDrag(accOf(s.row), s.row.folder.ifEmpty { list.folder }, (if (rowKey(s.row) in picked) pickedRows else items).filter { accOf(it) == accOf(s.row) }.map { it.uid }) }.takeIf { dragRows },
                                onMore = { actionsFor = items },
                                editing = editing,
                                picked = items.all { rowKey(it) in picked },
                                expanded = isOpen(s.thread),
                                onClick = {
                                    if (editing) {
                                        val keys = items.map(::rowKey).toSet()
                                        picked = if (items.all { rowKey(it) in picked }) picked - keys else picked + keys
                                    } else {
                                        onOpen(s.row)
                                    }
                                },
                                onLong = {
                                    editing = true
                                    picked = picked + items.map(::rowKey)
                                },
                                onToggle = {
                                    val keys = s.thread.rows.map(::rowKey)
                                    expanded = if (isOpen(s.thread)) expanded - keys.toSet() else expanded + keys.first()
                                },
                            )
                        }
                    }
                }
                if (list.more) item {
                    TitanButton(stringResource(R.string.mail_list_more), { scope.launch { list.loadMore() } }, Modifier.fillMaxWidth().padding(vertical = 12.dp), enabled = !list.loading)
                }
            }
        }
    }
}

@Composable
fun groupName(g: DayGroup): String = stringResource(
    when (g) {
        DayGroup.Today -> R.string.mail_group_today
        DayGroup.Yesterday -> R.string.mail_group_yesterday
        DayGroup.Week -> R.string.mail_group_week
        DayGroup.Older -> R.string.mail_group_older
    },
)

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, all: Boolean, current: String, modifier: Modifier, onSubmit: () -> Unit) {
    val label = stringResource(if (all) R.string.mail_search_labelAll else R.string.mail_search_label)
    Box(Modifier.padding(bottom = 10.dp)) {
        TextInput(
            value, onChange, label,
            modifier = modifier,
            leading = TitanIcons.search,
            placeholder = stringResource(if (all) R.string.mail_search_placeholderAll else R.string.mail_search_placeholderShort),
            keyboard = KeyboardOptions(imeAction = ImeAction.Search),
        )
    }
    LaunchedEffect(value) {
        // Wie im Web: suchen, wenn das Tippen kurz ruht.
        if (value.trim() != current.trim()) {
            delay(500)
            onSubmit()
        }
    }
}

@Composable
private fun Entry(
    model: MailModel,
    s: Shown,
    list: MailList,
    prefs: MailPrefs,
    now: Instant,
    locale: Locale,
    zone: ZoneId,
    editing: Boolean,
    picked: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onToggle: () -> Unit,
    current: Boolean = false,
    drag: (() -> MailDrag?)? = null,
    onMore: () -> Unit = {},
) {
    val c = Titan.colors
    val r = s.row
    val all = if (s.child || expanded) listOf(r) else s.thread.rows
    val unread = all.any { !it.flags.seen }
    val flagged = all.any { it.flags.flagged }
    val attachment = all.any { it.attachments }
    val name = senderName(r.from).ifEmpty { stringResource(R.string.mail_noSender) }
    val subject = r.subject.trim().ifEmpty { stringResource(R.string.mail_noSubject) }
    val count = if (s.child) 1 else s.thread.rows.size
    val unreadWord = stringResource(R.string.mail_mark_unread)
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pad = if (prefs.compact) 5.dp else 10.dp
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .selectedRow(current, hovered)
                .hoverable(source)
                .onSecondaryClick(onMore)
                // Mit Ziehen (große Bildschirme) gehört langes Drücken dem Ziehen; Auswahl über „Bearbeiten“ oder Rechtsklick.
                .combinedClickable(onClick = onClick, onLongClick = if (drag != null) null else onLong)
                // Innen, damit das Ziehen das lange Drücken vor dem Klick sieht.
                .mailDragSource(drag != null) { drag?.invoke() }
                .padding(start = if (s.child) 30.dp else 0.dp, top = pad, bottom = pad, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (editing) {
                Box(Modifier.padding(top = 10.dp)) { RadioMark(picked) }
            }
            // Ungelesen: Punkt und fette Schrift, für Bildschirmleser „Ungelesen“.
            Box(Modifier.size(10.dp).padding(top = 0.dp).let { if (unread) it.semantics { contentDescription = unreadWord } else it }, contentAlignment = Alignment.Center) {
                if (unread) Box(Modifier.padding(top = 0.dp).size(10.dp).clip(CircleShape).background(c.accent))
            }
            if (prefs.avatars) SenderAvatar(model, r.from)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(name, style = TitanType.rowTitle.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold, hyphens = androidx.compose.ui.text.style.Hyphens.Auto), color = c.text, modifier = Modifier.weight(1f))
                    Text(listDate(r.date, locale, zone, now), style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(start = 8.dp))
                    if (!editing) Icon(TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text(subject, style = TitanType.body.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal, hyphens = androidx.compose.ui.text.style.Hyphens.Auto), color = c.text, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 6.dp)) {
                        if (attachment) Mark(MailIcons.clip, stringResource(R.string.mail_mark_attachment), c.muted)
                        if (flagged) Mark(MailIcons.flag, stringResource(R.string.mail_mark_flagged), c.warnInk)
                        if (r.flags.answered) Mark(MailIcons.reply, stringResource(R.string.mail_mark_answered), c.muted)
                        if (r.flags.forwarded) Mark(MailIcons.forward, stringResource(R.string.mail_mark_forwarded), c.muted)
                    }
                }
                if (r.tooLarge) Text(stringResource(R.string.mail_list_tooLarge), style = TitanType.rowSub, color = c.muted)
                if (prefs.preview && r.preview.isNotBlank()) Text(r.preview, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                // In der Suche über alle Ordner und über alle Postfächer: wo die Mail liegt.
                val place = when {
                    list.unified -> {
                        val a = model.account(r.account)?.label.orEmpty()
                        val f = model.findFolder(r.account, r.folder)
                        if (r.folder.isNotEmpty() && r.folder != "INBOX" && f != null) "$a · ${folderName(f)}" else a
                    }
                    list.searched != null -> model.findFolder(list.account, r.folder)?.let { stringResource(R.string.mail_hit_folder, folderName(it)) }
                        ?: stringResource(R.string.mail_hit_folder, r.folder)
                    else -> null
                }
                if (!place.isNullOrBlank()) Text(place, style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                if (count > 1 && !s.child) {
                    val label = stringResource(R.string.mail_thread_count, count.toString())
                    Row(
                        Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).combinedClickable(role = Role.Button, onClick = onToggle)
                            .semantics { contentDescription = label }.background(c.chip).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(count.toString(), style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        Icon(if (expanded) MailIcons.up else TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Divider()
    }
}

@Composable
private fun Mark(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color) {
    Icon(icon, label, tint = tint, modifier = Modifier.size(18.dp))
}

/** Filter-Blatt (115-2): Einbeziehen, „Mails, die …“, Von, Zeitraum. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FilterSheet(list: MailList, onDone: (Filters, Scope) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(list.filters) }
    var s by remember { mutableStateOf(list.scope) }
    val c = Titan.colors
    Sheet(onDismiss = onDismiss) {
        Text(stringResource(R.string.mail_bar_filter), style = TitanType.title, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
        if (!list.unified) {
            SectionHeader(stringResource(R.string.mail_filter_include), top = 4.dp)
            Card(background = c.bg) {
                ChoiceRow(stringResource(R.string.mail_filter_thisFolder), s == Scope.Folder, { s = Scope.Folder })
                ChoiceRow(stringResource(R.string.mail_filter_allFolders), s == Scope.All, { s = Scope.All }, divider = true)
            }
        }
        SectionHeader(stringResource(R.string.mail_filter_mailsThat))
        Card(background = c.bg) {
            ToggleRow(stringResource(R.string.mail_filter_areUnread), f.unread, { f = f.copy(unread = it) })
            ToggleRow(stringResource(R.string.mail_filter_areFlagged), f.flagged, { f = f.copy(flagged = it) }, divider = true)
            ToggleRow(stringResource(R.string.mail_filter_haveAttachment), f.attachment, { f = f.copy(attachment = it) }, divider = true)
        }
        Text(stringResource(R.string.mail_search_attachmentHint), style = TitanType.small, color = c.muted, modifier = Modifier.padding(4.dp))
        LabeledField(stringResource(R.string.mail_search_from), f.from, { f = f.copy(from = it) }, placeholder = stringResource(R.string.mail_search_fromHint))
        SectionHeader(stringResource(R.string.mail_search_period))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DayField(stringResource(R.string.mail_search_since), f.since, { f = f.copy(since = it) }, Modifier.weight(1f))
            DayField(stringResource(R.string.mail_search_until), f.until, { f = f.copy(until = it) }, Modifier.weight(1f))
        }
        Gap(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TitanButton(stringResource(R.string.mail_search_reset), { f = Filters(q = f.q); s = Scope.Folder }, Modifier.weight(1f))
            TitanButton(stringResource(R.string.mail_box_done), { onDone(f, s) }, Modifier.weight(1f), kind = ButtonKind.Primary)
        }
    }
}

/** Ein Tag (JJJJ-MM-TT) über die Datumsauswahl von Android; der Tag steht in Worten da. */
@Composable
fun DayField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = Titan.colors
    val day = runCatching { java.time.LocalDate.parse(value) }.getOrNull()
    val shown = day?.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)) ?: stringResource(R.string.mailx_any_day)
    Column(modifier) {
        Text(label, style = TitanType.small, color = c.muted, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp)).background(c.bg)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .combinedClickable(role = Role.Button) {
                    val d = day ?: java.time.LocalDate.now()
                    android.app.DatePickerDialog(ctx, { _, y, m, dd -> onChange(java.time.LocalDate.of(y, m + 1, dd).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }
                .semantics { contentDescription = "$label: $shown" }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shown, style = TitanType.body, color = if (day != null) c.text else c.muted, modifier = Modifier.weight(1f))
            if (day != null) IconButton(TitanIcons.x, stringResource(R.string.mailx_clear_day, label), { onChange("") }, size = 18.dp)
            else Icon(TitanIcons.cal, null, tint = c.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** „Markieren“ für die Auswahl bzw. „Mehr“ einer Mail. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MarkSheet(
    model: MailModel,
    items: List<MessageRow>,
    list: MailList,
    onAct: (Act) -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onReply: ((ComposeMode) -> Unit)? = null,
    onNewWindow: (() -> Unit)? = null,
) {
    val c = Titan.colors
    val unread = items.any { !it.flags.seen }
    val flagged = items.any { it.flags.flagged }
    val acc = items.first().account.ifEmpty { list.account }
    val hasJunk = items.map { it.account.ifEmpty { list.account } }.distinct().all { model.folderOf(it, "junk") != null }
    val oneBox = items.map { it.account.ifEmpty { list.account } }.distinct().size == 1
    Sheet(onDismiss = onDismiss) {
        Text(if (items.size == 1) stringResource(R.string.mail_bar_markOne) else stringResource(R.string.mail_bar_markMany, items.size.toString()), style = TitanType.title, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
        Card(background = c.bg) {
            ListRow(stringResource(if (flagged) R.string.mail_action_unflag else R.string.mail_action_flag), icon = { RowIcon(MailIcons.flag) }, onClick = { onAct(if (flagged) Act.Unflag else Act.Flag) })
            ListRow(stringResource(if (unread) R.string.mail_action_markRead else R.string.mail_action_markUnread), icon = { RowIcon(TitanIcons.mail) }, divider = true, onClick = { onAct(if (unread) Act.Seen else Act.Unseen) })
            ListRow(stringResource(R.string.mail_action_archive), icon = { RowIcon(TitanIcons.archive) }, divider = true, onClick = { onAct(Act.Archive) })
            if (hasJunk) ListRow(stringResource(R.string.mail_action_junk), icon = { RowIcon(MailIcons.junk) }, divider = true, onClick = { onAct(Act.Junk) })
            if (oneBox) ListRow(stringResource(R.string.mail_action_move), icon = { RowIcon(TitanIcons.move) }, divider = true, onClick = onMove)
            ListRow(stringResource(R.string.mail_action_delete), icon = { RowIcon(TitanIcons.trash, tint = c.dangerInk) }, titleColor = c.dangerInk, divider = true, onClick = onDelete)
        }
        if (items.size == 1 && (onReply != null || onNewWindow != null)) {
            Gap(12.dp)
            Card(background = c.bg) {
                if (onReply != null) {
                    ListRow(stringResource(R.string.mail_action_reply), icon = { RowIcon(MailIcons.reply) }, onClick = { onReply(ComposeMode.Reply) })
                    ListRow(stringResource(R.string.mail_action_replyAll), icon = { RowIcon(MailIcons.replyAll) }, divider = true, onClick = { onReply(ComposeMode.ReplyAll) })
                    ListRow(stringResource(R.string.mail_action_forward), icon = { RowIcon(MailIcons.forward) }, divider = true, onClick = { onReply(ComposeMode.Forward) })
                }
                if (onNewWindow != null) ListRow(stringResource(R.string.mailx_new_window), icon = { RowIcon(MailIcons.open) }, divider = onReply != null, onClick = onNewWindow)
            }
        }
    }
}

/** Tastenkürzel (KeysHelp.svelte), erweitert um die Aktionen am Handy mit Tastatur. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.KeysSheet(onDismiss: () -> Unit) {
    val c = Titan.colors
    val keys = listOf(
        "J  ↓" to R.string.mail_keys_next,
        "K  ↑" to R.string.mail_keys_prev,
        "Enter" to R.string.mailx_keys_open,
        "E" to R.string.mail_keys_archive,
        "R" to R.string.mail_keys_reply,
        "A" to R.string.mail_action_replyAll,
        "F" to R.string.mail_action_forward,
        "S" to R.string.mail_action_flag,
        "U" to R.string.mailx_swipe_seen,
        "M" to R.string.mail_action_move,
        "Entf  ⌫" to R.string.mail_keys_delete,
        "N  C" to R.string.mail_action_compose,
        "/" to R.string.mail_keys_search,
        "?" to R.string.mail_keys_help,
        "Esc" to R.string.mailx_keys_back,
    )
    Sheet(onDismiss = onDismiss) {
        Text(stringResource(R.string.mail_keys_title), style = TitanType.title, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
        Card(background = c.bg) {
            keys.forEachIndexed { i, (k, label) ->
                ListRow(stringResource(label), divider = i > 0, right = {
                    Text(k, style = TitanType.code.copy(fontSize = TitanType.small.fontSize), color = c.text,
                        modifier = Modifier.background(c.chip, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                })
            }
        }
        Text(stringResource(R.string.mail_keys_note), style = TitanType.small, color = c.muted, modifier = Modifier.padding(4.dp))
    }
}

/** Verschieben nach … (nur Ordner desselben Postfachs). */
@Composable
fun androidx.compose.foundation.layout.BoxScope.MoveSheet(model: MailModel, account: String, from: String, onPick: (MailFolder, String) -> Unit, onDismiss: () -> Unit) {
    val c = Titan.colors
    val targets = arrangeFolders(model.folders[account].orEmpty(), model.order.mail[account].orEmpty()).filter { it.folder.path != from }
    Sheet(onDismiss = onDismiss) {
        Text(stringResource(R.string.mail_move_title), style = TitanType.title, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
        if (targets.isEmpty()) Text(stringResource(R.string.mail_move_none), style = TitanType.body, color = c.muted)
        Card(background = c.bg) {
            targets.forEachIndexed { i, af ->
                val name = folderName(af.folder)
                ListRow(name, Modifier.padding(start = (af.depth * 18).dp), icon = { RowIcon(folderIcon(af.folder.role), tint = c.accent) }, divider = i > 0, onClick = { onPick(af.folder, name) })
            }
        }
    }
}
