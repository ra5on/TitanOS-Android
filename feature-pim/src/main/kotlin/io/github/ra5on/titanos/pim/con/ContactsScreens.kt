package io.github.ra5on.titanos.pim.con

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.pim.ChoiceRows
import io.github.ra5on.titanos.pim.PimIcons
import io.github.ra5on.titanos.pim.R
import io.github.ra5on.titanos.pim.SettingsGroup
import io.github.ra5on.titanos.pim.ToggleRow
import io.github.ra5on.titanos.pim.cal.CalEvent
import io.github.ra5on.titanos.pim.cal.CalRoute
import io.github.ra5on.titanos.pim.cal.CalendarScreen
import io.github.ra5on.titanos.pim.cal.DatePick
import io.github.ra5on.titanos.pim.pimChanges
import io.github.ra5on.titanos.ui.Width
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
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import io.github.ra5on.titanos.ui.R as UiR

sealed interface ConRoute {
    data object Home : ConRoute
    data object Lists : ConRoute
    data class Detail(val id: String) : ConRoute
    data class Edit(val id: String?) : ConRoute
    data object Duplicates : ConRoute
    data class Merge(val set: DuplicateSet) : ConRoute
    data object Suggestions : ConRoute
    data object Settings : ConRoute
    data class NewEvent(val name: String, val back: String) : ConRoute
}

/** Kontakte wie im Web am Handy (web/src/routes/contacts, lib/pim/con), am großen Bildschirm Liste und Karte nebeneinander. */
@Composable
fun ContactsScreen(nas: NasConnection, stream: NasConnection?, onClose: () -> Unit, onSyncSettings: () -> Unit, startRoute: ConRoute = ConRoute.Home) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(nas) { ContactsModel(ContactsApi(nas), scope, ConPrefsStore(context)) }
    LaunchedEffect(model) { model.load() }
    if (stream != null) LaunchedEffect(stream) {
        pimChanges(stream).collect { kind -> if (kind == "contacts" || kind == "resync") { delay(300); model.reload() } }
    }
    var route by remember { mutableStateOf(startRoute) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    val go: (ConRoute) -> Unit = { route = it }
    // Agent-Panel wie im Web: der offene Kontakt geht als Bezug mit.
    val agent = remember(nas) { io.github.ra5on.titanos.agent.chat.AgentApi(nas, stream ?: nas) }
    val openId = (route as? ConRoute.Detail)?.id
    val agentContext = openId?.let { id -> io.github.ra5on.titanos.agent.panel.PanelContext.contact(model.items.firstOrNull { it.id == id }?.book ?: BOOK, id) }
    io.github.ra5on.titanos.agent.panel.AgentPanelFrame(agent, "contacts", agentContext) { ContactsRoute(model, route, group, go, { group = it }, nas, stream, onClose, onSyncSettings) }
}

@Composable
private fun ContactsRoute(
    model: ContactsModel,
    route: ConRoute,
    group: String?,
    go: (ConRoute) -> Unit,
    setGroup: (String?) -> Unit,
    nas: NasConnection,
    stream: NasConnection?,
    onClose: () -> Unit,
    onSyncSettings: () -> Unit,
) {
    // Die Breite, die die Kontakte haben (neben einem offenen Agent-Panel weniger).
    val width = currentWidth()
    when (val r = route) {
        ConRoute.Home -> ContactsHome(model, width, group, null, go, onClose, setGroup)
        is ConRoute.Detail -> if (width.twoPanes) ContactsHome(model, width, group, r.id, go, onClose, setGroup) else ContactDetail(model, r.id, go, onBack = { go(ConRoute.Home) })
        is ConRoute.Edit -> ContactEditor(model, r.id, onDone = { id -> model.reload(); go(if (id != null) ConRoute.Detail(id) else ConRoute.Home) })
        ConRoute.Lists -> ListsPage(model, group, onPick = { setGroup(it); go(ConRoute.Home) }, go, onBack = { go(ConRoute.Home) })
        ConRoute.Duplicates -> DuplicatesPage(model, go, onBack = { go(ConRoute.Lists) })
        is ConRoute.Merge -> MergePage(model, r.set, onDone = { model.reload(); go(ConRoute.Duplicates) })
        ConRoute.Suggestions -> SuggestionsPage(model, onBack = { go(ConRoute.Lists) }, onOpen = { go(ConRoute.Detail(it)) })
        ConRoute.Settings -> ContactsSettings(model, onBack = { go(ConRoute.Home) }, onSyncSettings = onSyncSettings)
        is ConRoute.NewEvent -> {
            val start = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
            val ev = CalEvent(id = "", title = r.name, start = start.toString(), end = start.plusHours(1).toString())
            CalendarScreen(nas, stream ?: nas, onClose = { go(ConRoute.Detail(r.back)) }, onSyncSettings = onSyncSettings, startRoute = CalRoute.Edit(ev))
        }
    }
}

@Composable
internal fun conProblemText(p: ConProblem): String = stringResource(
    when (p) {
        ConProblem.Unreachable -> R.string.con_problem_unreachable
        ConProblem.Unavailable -> R.string.con_problem_unavailable
        ConProblem.Disabled -> R.string.con_problem_disabled
        ConProblem.Conflict -> R.string.con_problem_conflict
        ConProblem.Invalid -> R.string.con_problem_invalid
        ConProblem.Other -> R.string.con_problem_other
    },
)

@Composable
private fun ConTop(title: String, onBack: (() -> Unit)?, backLabel: String = stringResource(UiR.string.ui_back), actions: @Composable () -> Unit = {}) {
    Box(Modifier.statusBarsPadding()) {
        TopBar(title, onBack = onBack, backLabel = backLabel) {
            io.github.ra5on.titanos.agent.panel.AgentPanelButton()
            actions()
        }
    }
}

/** Rundes Bild: Foto oder Initialen auf Farbe (Avatar.svelte). */
@Composable
internal fun Avatar(name: String, org: String, photo: ImageBitmap?, size: Dp) {
    val c = Titan.colors
    // Initialen nur als Schmuck: der Name steht daneben, TalkBack liest ihn nicht doppelt.
    Box(Modifier.size(size).clip(CircleShape).background(c.chip).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (photo != null) Image(photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(initialsOf(name, org), style = if (size > 60.dp) TitanType.big else TitanType.rowTitle, color = c.muted)
    }
}

@Composable
private fun ContactsHome(model: ContactsModel, width: Width, group: String?, selected: String?, go: (ConRoute) -> Unit, onClose: () -> Unit, onGroup: (String?) -> Unit) {
    val c = Titan.colors
    BackHandler { if (selected != null) go(ConRoute.Home) else onClose() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    // Solange im Suchfeld getippt wird, gelten keine Tastenkürzel.
    var typing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxSize().background(c.bg).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || typing) return@onPreviewKeyEvent false
            when {
                e.key == Key.N && !searching -> { go(ConRoute.Edit(null)); true }
                e.key == Key.E && selected != null && !searching -> { go(ConRoute.Edit(selected)); true }
                e.key == Key.Slash || (e.isCtrlPressed && e.key == Key.F) -> { searching = true; true }
                e.key == Key.Escape && selected != null -> { go(ConRoute.Home); true }
                // Pfeile wählen den nächsten bzw. vorigen Kontakt (Liste und Karte nebeneinander).
                (e.key == Key.DirectionDown || e.key == Key.DirectionUp || e.key == Key.J || e.key == Key.K) && width.twoPanes && !searching -> {
                    val ordered = model.overview.groups.firstOrNull { it.id == group }?.let { g -> model.sorted(model.items.filter { it.id in g.members }) } ?: model.sorted()
                    val i = ordered.indexOfFirst { it.id == selected }
                    val next = if (e.key == Key.DirectionDown || e.key == Key.J) i + 1 else i - 1
                    ordered.getOrNull(next.coerceIn(0, (ordered.size - 1).coerceAtLeast(0)))?.let { go(ConRoute.Detail(it.id)) }
                    true
                }
                else -> false
            }
        },
    ) {
        if (width.sidebar) {
            Column(Modifier.width(260.dp).fillMaxHeight().background(c.surface)) {
                Box(Modifier.statusBarsPadding())
                ListsColumn(model, group, onPick = { g -> onGroup(g); go(ConRoute.Home) }, go)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
        }
        Column(Modifier.then(if (width.twoPanes) Modifier.width(if (width.sidebar) 340.dp else 320.dp) else Modifier.weight(1f)).fillMaxHeight()) {
            ContactList(model, width, group, selected, query, { query = it }, searching, { searching = it }, go, onClose, onTyping = { typing = it }, focusSearch = searching)
        }
        if (width.twoPanes) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (selected != null) ContactDetail(model, selected, go, onBack = null)
                else EmptyState(stringResource(R.string.con_pick), pluralStringResource(R.plurals.con_count, model.items.size, model.items.size), Modifier.statusBarsPadding().padding(top = 80.dp))
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactList(
    model: ContactsModel, width: Width, group: String?, selected: String?, query: String, onQuery: (String) -> Unit,
    searching: Boolean, onSearching: (Boolean) -> Unit, go: (ConRoute) -> Unit, onClose: () -> Unit,
    onTyping: (Boolean) -> Unit = {}, focusSearch: Boolean = false,
) {
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(focusSearch) { if (focusSearch) runCatching { searchFocus.requestFocus() } }
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<Pair<List<ContactItem>, List<Suggestion>>?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(250)
        results = runCatching { model.api.search(query.trim()) }.getOrNull()
            ?: (model.items.filter { ContactsModel.fold(it.name + " " + it.org + " " + it.email).contains(ContactsModel.fold(query)) } to emptyList())
    }
    val g = model.overview.groups.firstOrNull { it.id == group }
    val base = if (g != null) model.items.filter { it.id in g.members } else model.items
    val list = model.sorted(results?.first ?: base)
    val state = rememberLazyListState()
    val letters = list.map { model.letter(it) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.statusBarsPadding())
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { if (width.sidebar) onClose() else go(ConRoute.Lists) }.heightIn(min = 44.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(TitanIcons.back, null, tint = c.accent, modifier = Modifier.size(22.dp))
                Text(stringResource(if (width.sidebar) UiR.string.ui_nav_home else R.string.con_lists), style = TitanType.rowTitle, color = c.accent)
            }
            Spacer(Modifier.weight(1f))
            io.github.ra5on.titanos.agent.panel.AgentPanelButton()
            IconButton(TitanIcons.gear, stringResource(R.string.con_settings), { go(ConRoute.Settings) })
            IconButton(TitanIcons.plus, stringResource(R.string.con_new), { go(ConRoute.Edit(null)) }, tint = c.accent)
        }
        Text(g?.name ?: stringResource(R.string.con_title_all), style = TitanType.big, color = c.text, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
        TextInput(query, onQuery, stringResource(UiR.string.ui_search),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(searchFocus).onFocusChanged { f -> onTyping(f.isFocused); if (!f.isFocused) onSearching(false) },
            leading = TitanIcons.search, placeholder = stringResource(UiR.string.ui_search))
        model.problem?.let { Problem(conProblemText(it), Modifier.padding(horizontal = 16.dp)) }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(end = if (model.prefs.index) 22.dp else 0.dp)) {
                if (results == null && g == null) {
                    model.me?.let { meId -> model.item(meId) }?.let { me ->
                        item(key = "me") {
                            Row(Modifier.fillMaxWidth().clickable { go(ConRoute.Detail(me.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(me.name, me.org, model.photo(me), 56.dp)
                                Column {
                                    Text(model.display(me), style = TitanType.rowTitle, color = c.text)
                                    Text(stringResource(R.string.con_my_card), style = TitanType.rowSub, color = c.muted)
                                }
                            }
                            Divider()
                        }
                    }
                    val soon = model.birthdaysSoon()
                    if (soon.isNotEmpty()) item(key = "soon") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.con_birthdays_soon), style = TitanType.section, color = c.muted)
                            for (s in soon) {
                                val d = runCatching { LocalDate.parse(s.birthdaySoon!!.date) }.getOrNull()
                                Text(
                                    listOfNotNull(model.display(s), d?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), s.birthdaySoon?.age?.let { stringResource(R.string.con_turns, it) }).joinToString(" · "),
                                    style = TitanType.body, color = c.text, modifier = Modifier.fillMaxWidth().clickable { go(ConRoute.Detail(s.id)) }.padding(vertical = 6.dp),
                                )
                            }
                        }
                        Divider()
                    }
                }
                var last = ""
                for (item in list) {
                    val l = model.letter(item)
                    if (l != last && results == null) {
                        val letter = l
                        item(key = "h$letter") {
                            Text(letter, style = TitanType.section, color = c.muted, modifier = Modifier.fillMaxWidth().background(c.chip).padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() })
                        }
                        last = l
                    }
                    item(key = item.id) { ContactRow(model, item, item.id == selected, onClick = { go(ConRoute.Detail(item.id)) }) }
                }
                results?.second?.takeIf { it.isNotEmpty() && model.prefs.suggestions }?.let { sug ->
                    item { Text(stringResource(R.string.con_from_mail), style = TitanType.section, color = c.muted, modifier = Modifier.padding(16.dp)) }
                    items(sug, key = { "s" + it.addr }) { s ->
                        ListRow(s.name.ifBlank { s.addr }, sub = s.addr, right = {
                            TitanButton(stringResource(R.string.con_add), {
                                scope.launch { runCatching { model.api.fromSuggestion(s.addr) }.onSuccess { model.reload(); go(ConRoute.Detail(it.id)) }.onFailure { model.problem = conProblemOf(it) } }
                            })
                        })
                    }
                }
                item { Text(pluralStringResource(R.plurals.con_count, list.size, list.size), style = TitanType.body, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
                item { Box(Modifier.navigationBarsPadding()) }
            }
            if (model.prefs.index && results == null && list.size > 12) {
                // Register am Rand (ContactRail.svelte): Tippen springt zum Buchstaben.
                Column(Modifier.align(Alignment.CenterEnd).width(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    for (letter in ('A'..'Z').map { it.toString() } + "#") {
                        val has = letter in letters
                        Text(letter, style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = if (has) c.accent else c.muted.copy(alpha = .5f),
                            modifier = Modifier.clickable(enabled = has) {
                                scope.launch {
                                    val idx = state.layoutInfo.totalItemsCount.let { _ ->
                                        var i = 0; var found = -1
                                        // Index der Überschrift: Kopfzeilen vor der Liste mitzählen.
                                        val offset = (if (model.me != null && model.item(model.me!!) != null && g == null) 1 else 0) + (if (model.birthdaysSoon().isNotEmpty() && g == null) 1 else 0)
                                        var lastL = ""
                                        for (it in list) { val ll = model.letter(it); if (ll != lastL) { if (ll == letter) { found = i; break }; i++; lastL = ll }; i++ }
                                        if (found >= 0) found + offset else -1
                                    }
                                    if (idx >= 0) state.scrollToItem(idx)
                                }
                            }.padding(vertical = 1.dp).semantics { contentDescription = letter })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(model: ContactsModel, item: ContactItem, selected: Boolean, onClick: () -> Unit) {
    val c = Titan.colors
    val (first, last) = model.nameParts(item)
    val bold = if (model.prefs.sortBy == "first") first else last
    Row(
        Modifier.fillMaxWidth().background(if (selected) c.accent.copy(alpha = .16f) else Color.Transparent).clickable(onClick = onClick).onSecondaryClick(onClick)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.prefs.photos) Avatar(item.name, item.org, model.photo(item), 36.dp)
        Column(Modifier.weight(1f)) {
            val name = model.display(item)
            // Der Teil, nach dem sortiert wird, fett wie am iPhone.
            if (bold.isNotEmpty() && name.contains(bold)) {
                val i = name.indexOf(bold)
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    append(name.substring(0, i))
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)); append(bold); pop()
                    append(name.substring(i + bold.length))
                }, style = TitanType.body, color = c.text)
            } else Text(name, style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            if (model.prefs.showOrg && item.org.isNotBlank() && item.name.isNotBlank()) Text(item.org, style = TitanType.rowSub, color = c.muted)
        }
    }
    Divider(Modifier.padding(start = 16.dp))
}

/** Listen (iOS-Form): Alle, Gruppen, Dubletten, Vorschläge; Gruppen anlegen, umbenennen, löschen. */
@Composable
private fun ListsColumn(model: ContactsModel, group: String?, onPick: (String?) -> Unit, go: (ConRoute) -> Unit) {
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf<Group?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Group?>(null) }
    var acting by remember { mutableStateOf<Group?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(stringResource(R.string.con_lists), style = TitanType.big, color = c.text, modifier = Modifier.semantics { heading() })
            Card(Modifier.padding(top = 10.dp)) {
                ListRow(stringResource(R.string.con_title_all), sub = pluralStringResource(R.plurals.con_count, model.items.size, model.items.size), icon = { RowIcon(PimIcons.group) },
                    right = { RadioMark(group == null) }, onClick = { onPick(null) })
                model.overview.groups.forEach { g ->
                    val on = group == g.id
                    ListRow(g.name, sub = pluralStringResource(R.plurals.con_count, g.members.size, g.members.size), divider = true,
                        icon = { RowIcon(PimIcons.list, tint = if (on) c.accent else c.muted) }, titleColor = if (on) c.accent else c.text,
                        modifier = Modifier.semantics { if (on) stateDescription = "✓" }.onSecondaryClick { acting = g },
                        right = { IconButton(TitanIcons.more, stringResource(R.string.con_group_actions, g.name), { acting = g }) }, onClick = { onPick(g.id) })
                }
                ListRow(stringResource(R.string.con_group_new), divider = true, icon = { RowIcon(TitanIcons.plus, tint = c.accent) }, onClick = { creating = true })
            }
            Card(Modifier.padding(top = 10.dp)) {
                ListRow(stringResource(R.string.con_duplicates), sub = pluralStringResource(R.plurals.con_duplicates_n, model.overview.duplicates, model.overview.duplicates),
                    icon = { RowIcon(PimIcons.merge) }, chevron = true, onClick = { go(ConRoute.Duplicates) })
                if (model.prefs.suggestions) ListRow(stringResource(R.string.con_suggestions), sub = stringResource(R.string.con_suggestions_sub), divider = true,
                    icon = { RowIcon(TitanIcons.mail) }, chevron = true, onClick = { go(ConRoute.Suggestions) })
            }
        }
        acting?.let { g ->
            ConfirmDialog(g.name, null, listOf(
                DialogAction(stringResource(R.string.con_group_rename_short)) { acting = null; naming = g },
                DialogAction(stringResource(R.string.con_delete), danger = true) { acting = null; deleting = g },
                DialogAction(stringResource(UiR.string.ui_cancel)) { acting = null },
            ), onDismiss = { acting = null })
        }
        deleting?.let { g ->
            ConfirmDialog(stringResource(R.string.con_group_delete, g.name), stringResource(R.string.con_group_delete_text), listOf(
                DialogAction(stringResource(R.string.con_delete), danger = true) {
                    deleting = null
                    scope.launch { runCatching { model.api.removeGroup(g) }.onFailure { model.problem = conProblemOf(it) }; if (group == g.id) onPick(null); model.reload() }
                },
                DialogAction(stringResource(UiR.string.ui_cancel)) { deleting = null },
            ), onDismiss = { deleting = null })
        }
    }
    if (creating || naming != null) {
        NameDialog(stringResource(if (naming == null) R.string.con_group_new else R.string.con_group_rename, naming?.name.orEmpty()), naming?.name.orEmpty()) { name ->
            val g = naming
            creating = false; naming = null
            if (name != null) scope.launch {
                runCatching { if (g == null) model.api.createGroup(name) else model.api.updateGroup(g, name = name) }.onFailure { model.problem = conProblemOf(it) }
                model.reload()
            }
        }
    }
}

@Composable
private fun ListsPage(model: ContactsModel, group: String?, onPick: (String?) -> Unit, go: (ConRoute) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Titan.colors.bg)) {
        ConTop(stringResource(R.string.con_lists), onBack)
        ListsColumn(model, group, onPick, go)
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.ui.window.Dialog(onDismissRequest = { onDone(null) }) {
        Column(Modifier.clip(RoundedCornerShape(22.dp)).background(Titan.colors.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = TitanType.title, color = Titan.colors.text)
            TextInput(name, { name = it }, stringResource(R.string.con_name), placeholder = stringResource(R.string.con_name))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TitanButton(stringResource(UiR.string.ui_cancel), { onDone(null) })
                TitanButton(stringResource(R.string.con_save), { if (name.isNotBlank()) onDone(name.trim()) }, kind = ButtonKind.Primary, enabled = name.isNotBlank())
            }
        }
    }
}

private fun open(context: Context, uri: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Die vCard als Datei für Teilen und Ziehen (FileProvider des Moduls). */
internal fun vcardUri(context: Context, c: Contact): Uri {
    val dir = File(context.cacheDir, "pim-share").apply { mkdirs() }
    val name = (c.name.ifBlank { c.org.ifBlank { "Kontakt" } }).replace(Regex("[^\\p{L}\\p{N} ._-]"), "_") + ".vcf"
    val f = File(dir, name)
    f.writeText(vcardOf(c))
    return FileProvider.getUriForFile(context, context.packageName + ".pim.share", f)
}

/** Karte eines Kontakts (ContactDetail.svelte): Aktionen, alle Felder, Gruppen, Teilen, Meine Karte, Löschen. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ContactDetail(model: ContactsModel, id: String, go: (ConRoute) -> Unit, onBack: (() -> Unit)?) {
    if (onBack != null) BackHandler(onBack = onBack)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var contact by remember(id) { mutableStateOf<Contact?>(null) }
    var problem by remember { mutableStateOf<ConProblem?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var groupsOpen by remember { mutableStateOf(false) }
    var photo by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id, model.items) {
        runCatching { model.api.contact(id) }.onSuccess { contact = it; problem = null }.onFailure { problem = conProblemOf(it) }
    }
    LaunchedEffect(contact?.etag) {
        val c = contact ?: return@LaunchedEffect
        photo = if (c.hasPhoto) runCatching { model.api.photo(c.id, c.etag) }.getOrNull()?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmapSafe() } else null
    }
    val copied = stringResource(R.string.con_copied)
    Page(
        topBar = {
            ConTop(if (onBack != null) stringResource(R.string.con_title_all) else "", onBack) {
                contact?.let { c -> Text(stringResource(R.string.con_edit), style = TitanType.button, color = Titan.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { go(ConRoute.Edit(c.id)) }.padding(horizontal = 12.dp, vertical = 10.dp)) }
            }
        },
        overlay = {
            if (deleting) contact?.let { c ->
                ConfirmDialog(stringResource(R.string.con_delete_title, c.name.ifBlank { c.org }), stringResource(R.string.con_delete_text), listOf(
                    DialogAction(stringResource(R.string.con_delete), danger = true) {
                        deleting = false
                        scope.launch { runCatching { model.api.remove(c.id, c.etag) }.onSuccess { model.reload(); go(ConRoute.Home) }.onFailure { problem = conProblemOf(it) } }
                    },
                    DialogAction(stringResource(UiR.string.ui_cancel)) { deleting = false },
                ), onDismiss = { deleting = false })
            }
        },
    ) {
        problem?.let { Problem(conProblemText(it), Modifier.padding(top = 10.dp)) }
        val c = contact ?: run { Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(16.dp)); return@Page }
        val display = c.name.ifBlank { listOf(c.first, c.last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { c.org } }
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Das Bild lässt sich als vCard in andere Apps ziehen.
            Box(Modifier.dragAndDropSource { _ ->
                DragAndDropTransferData(ClipData.newUri(context.contentResolver, display, vcardUri(context, c)), flags = android.view.View.DRAG_FLAG_GLOBAL or android.view.View.DRAG_FLAG_GLOBAL_URI_READ)
            }) { Avatar(display, c.org, photo, 96.dp) }
            Text(display, style = TitanType.big, color = Titan.colors.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            if (c.org.isNotBlank() && c.org != display) Text(c.org, style = TitanType.body, color = Titan.colors.muted)
            if (model.me == c.id) Text(stringResource(R.string.con_my_card), style = TitanType.small, color = Titan.colors.muted)
        }
        // Schnellaktionen wie am iPhone.
        val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = if (big) 2 else 4) {
            val phone = c.phones.firstOrNull()?.value
            val mail = c.emails.firstOrNull()?.value
            QuickAction(PimIcons.message, stringResource(R.string.con_message), phone != null, Modifier.weight(1f)) { open(context, "smsto:" + Uri.encode(phone)) }
            QuickAction(PimIcons.call, stringResource(R.string.con_call), phone != null, Modifier.weight(1f)) { open(context, "tel:" + Uri.encode(phone)) }
            QuickAction(TitanIcons.mail, stringResource(R.string.con_mail), mail != null, Modifier.weight(1f)) { open(context, "mailto:$mail") }
            QuickAction(TitanIcons.cal, stringResource(R.string.con_event), true, Modifier.weight(1f)) { go(ConRoute.NewEvent(display, c.id)) }
        }
        if (c.phones.isNotEmpty()) Card(Modifier.padding(top = 14.dp)) {
            c.phones.forEachIndexed { i, p ->
                FieldRow(typeLabel(p.type, p.label), p.value, i > 0, onClick = { open(context, "tel:" + Uri.encode(p.value)) },
                    onCopy = { clipboard.setText(AnnotatedString(p.value)); android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show() })
            }
        }
        if (c.emails.isNotEmpty()) Card(Modifier.padding(top = 10.dp)) {
            c.emails.forEachIndexed { i, m ->
                FieldRow(typeLabel(m.type, m.label), m.value, i > 0, onClick = { open(context, "mailto:${m.value}") },
                    onCopy = { clipboard.setText(AnnotatedString(m.value)); android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show() })
            }
        }
        if (c.addresses.isNotEmpty()) Card(Modifier.padding(top = 10.dp)) {
            c.addresses.forEachIndexed { i, a ->
                val text = listOf(a.street, listOf(a.postcode, a.city).filter { it.isNotBlank() }.joinToString(" "), a.region, a.country).filter { it.isNotBlank() }.joinToString("\n")
                FieldRow(typeLabel(a.type, a.label), text, i > 0, onClick = { open(context, "geo:0,0?q=" + Uri.encode(text.replace("\n", ", "))) },
                    onCopy = { clipboard.setText(AnnotatedString(text)); android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show() })
            }
        }
        val extra = listOfNotNull(
            c.birthday.takeIf { it.isNotBlank() }?.let { stringResource(R.string.con_birthday) to birthdayText(it) },
            c.url.takeIf { it.isNotBlank() }?.let { stringResource(R.string.con_url) to it },
        )
        if (extra.isNotEmpty()) Card(Modifier.padding(top = 10.dp)) {
            extra.forEachIndexed { i, (k, v) -> FieldRow(k, v, i > 0, onClick = { if (v.startsWith("http")) open(context, v) }, onCopy = { clipboard.setText(AnnotatedString(v)) }) }
        }
        if (c.notes.isNotBlank()) {
            SectionHeader(stringResource(R.string.con_notes))
            Card { Text(c.notes, style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(14.dp)) }
        }
        SectionHeader(stringResource(R.string.con_groups))
        val mine = model.groupsOf(c.id)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (g in mine) Chip(g.name, on = true) { groupsOpen = true }
            Chip(stringResource(R.string.con_groups_edit)) { groupsOpen = true }
        }
        if (groupsOpen) Card(Modifier.padding(top = 8.dp)) {
            model.overview.groups.forEachIndexed { i, g ->
                val inIt = c.id in g.members
                ToggleRow(g.name, inIt, { on ->
                    scope.launch {
                        runCatching { model.api.updateGroup(g, add = if (on) listOf(c.id) else null, remove = if (!on) listOf(c.id) else null) }.onFailure { problem = conProblemOf(it) }
                        model.refreshOverview()
                    }
                }, divider = i > 0)
            }
            if (model.overview.groups.isEmpty()) ListRow(stringResource(R.string.con_no_groups))
        }
        SectionHeader(stringResource(R.string.con_more))
        Card {
            ListRow(stringResource(R.string.con_share), sub = stringResource(R.string.con_share_sub), icon = { RowIcon(TitanIcons.share) }, onClick = {
                val uri = vcardUri(context, c)
                val send = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, display).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            ListRow(stringResource(if (model.me == c.id) R.string.con_unset_me else R.string.con_set_me_set), divider = true, icon = { RowIcon(PimIcons.card) },
                onClick = { model.chooseMe(if (model.me == c.id) null else c.id) })
            ListRow(stringResource(R.string.con_delete), divider = true, titleColor = Titan.colors.dangerInk, icon = { RowIcon(TitanIcons.trash, tint = Titan.colors.dangerInk) },
                onClick = { if (model.prefs.confirmDelete) deleting = true else scope.launch { runCatching { model.api.remove(c.id, c.etag) }.onSuccess { model.reload(); go(ConRoute.Home) } } })
        }
        Box(Modifier.navigationBarsPadding())
    }
}

private fun android.graphics.Bitmap.asImageBitmapSafe(): ImageBitmap = asImageBitmap()

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Titan.colors
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(c.surface).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (enabled) c.accent else c.muted, modifier = Modifier.size(22.dp))
        Text(label, style = TitanType.small, color = if (enabled) c.accent else c.muted, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FieldRow(label: String, value: String, divider: Boolean, onClick: () -> Unit, onCopy: () -> Unit) {
    val copy = stringResource(R.string.con_copy)
    Column {
        if (divider) Divider()
        Column(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onCopy).onSecondaryClick(onCopy)
                .semantics { customActions = listOf(CustomAccessibilityAction(copy) { onCopy(); true }) }.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(label, style = TitanType.small, color = Titan.colors.muted)
            Text(value, style = TitanType.body, color = Titan.colors.accent)
        }
    }
}

@Composable
private fun typeLabel(type: String, label: String?): String = label?.takeIf { it.isNotBlank() } ?: stringResource(
    when (type) {
        "mobile" -> R.string.con_type_mobile
        "home" -> R.string.con_type_home
        "work" -> R.string.con_type_work
        else -> R.string.con_type_other
    },
)

@Composable
private fun birthdayText(b: String): String {
    val pattern = stringResource(R.string.con_day_month_pattern)
    return birthdayWords(b, pattern)
}

private fun birthdayWords(b: String, pattern: String): String = when {
    b.startsWith("--") -> runCatching { java.time.MonthDay.parse(b).let { md -> LocalDate.of(2000, md.month, md.dayOfMonth).format(DateTimeFormatter.ofPattern(pattern)) } }.getOrDefault(b)
    else -> runCatching { LocalDate.parse(b).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) }.getOrDefault(b)
}

/** Kontakt anlegen oder bearbeiten mit allen Feldern des Webs (ContactEditor.svelte) und Bild. */
@Composable
private fun ContactEditor(model: ContactsModel, id: String?, onDone: (String?) -> Unit) {
    BackHandler { onDone(id) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var before by remember { mutableStateOf<Contact?>(null) }
    LaunchedEffect(id) { if (id != null) runCatching { model.api.contact(id) }.onSuccess { before = it } }
    val c = before
    var first by remember(c) { mutableStateOf(c?.first.orEmpty()) }
    var last by remember(c) { mutableStateOf(c?.last.orEmpty()) }
    var org by remember(c) { mutableStateOf(c?.org.orEmpty()) }
    var phones by remember(c) { mutableStateOf(c?.phones?.map { it.type to it.value } ?: emptyList()) }
    var emails by remember(c) { mutableStateOf(c?.emails?.map { it.type to it.value } ?: emptyList()) }
    var addresses by remember(c) { mutableStateOf(c?.addresses ?: emptyList()) }
    var birthday by remember(c) { mutableStateOf(c?.birthday.orEmpty()) }
    var noYear by remember(c) { mutableStateOf(c?.birthday?.startsWith("--") == true) }
    var notes by remember(c) { mutableStateOf(c?.notes.orEmpty()) }
    var newPhoto by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    var dropPhoto by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<ConProblem?>(null) }
    var nameMissing by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            if (bytes != null) { newPhoto = bytes to (context.contentResolver.getType(uri) ?: "image/jpeg"); dropPhoto = false }
        }
    }
    fun input() = ContactInput(
        first = first.trim(), last = last.trim(), org = org.trim(),
        phones = phones.filter { it.second.isNotBlank() }.map { TypedValue(it.first, it.second.trim()) },
        emails = emails.filter { it.second.isNotBlank() }.map { TypedValue(it.first, it.second.trim()) },
        addresses = addresses.filter { listOf(it.street, it.city, it.postcode, it.region, it.country).any { s -> s.isNotBlank() } }
            .map { AddressInput(it.type, it.street.trim(), it.city.trim(), it.postcode.trim(), it.region.trim(), it.country.trim()) },
        birthday = birthday, notes = notes,
    )
    fun save() {
        val i = input()
        if (i.first.isEmpty() && i.last.isEmpty() && i.org.isEmpty() && i.emails.isEmpty() && i.phones.isEmpty()) { nameMissing = true; return }
        scope.launch {
            try {
                var saved = if (c == null) model.api.create(i) else model.api.update(c.id, c.etag, i)
                newPhoto?.let { (bytes, type) -> saved = model.api.putPhoto(saved.id, saved.etag, "bild." + (type.substringAfter('/').ifBlank { "jpg" }), bytes, type); model.forgetPhoto(saved.id) }
                if (dropPhoto && c?.hasPhoto == true) { saved = model.api.removePhoto(saved.id, saved.etag); model.forgetPhoto(saved.id) }
                onDone(saved.id)
            } catch (e: Exception) { problem = conProblemOf(e) }
        }
    }
    val types = listOf("mobile" to stringResource(R.string.con_type_mobile), "home" to stringResource(R.string.con_type_home), "work" to stringResource(R.string.con_type_work), "other" to stringResource(R.string.con_type_other))
    val mailTypes = types.drop(1)
    Page(topBar = {
        ConTop(stringResource(if (id == null) R.string.con_new else R.string.con_edit_title), { onDone(id) }, backLabel = stringResource(UiR.string.ui_cancel)) {
            Text(stringResource(R.string.con_done), style = TitanType.button, color = Titan.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { save() }.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }) {
        problem?.let { Problem(conProblemText(it), Modifier.padding(top = 10.dp)) }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val bmp = newPhoto?.first?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmapSafe() }
            Avatar(listOf(first, last).joinToString(" "), org, bmp, 96.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Chip(stringResource(R.string.con_photo_pick)) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                if ((c?.hasPhoto == true && !dropPhoto) || newPhoto != null) Chip(stringResource(R.string.con_photo_remove)) { newPhoto = null; dropPhoto = true }
            }
            if (dropPhoto) Text(stringResource(R.string.con_photo_removed), style = TitanType.small, color = Titan.colors.muted)
        }
        SectionHeader(stringResource(R.string.con_name))
        TextInput(first, { first = it; nameMissing = false }, stringResource(R.string.con_first), placeholder = stringResource(R.string.con_first))
        TextInput(last, { last = it; nameMissing = false }, stringResource(R.string.con_last), Modifier.padding(top = 8.dp), placeholder = stringResource(R.string.con_last))
        TextInput(org, { org = it; nameMissing = false }, stringResource(R.string.con_org), Modifier.padding(top = 8.dp), placeholder = stringResource(R.string.con_org))
        if (nameMissing) Problem(stringResource(R.string.con_name_missing), Modifier.padding(top = 6.dp))

        SectionHeader(stringResource(R.string.con_phones))
        phones.forEachIndexed { i, (t, v) ->
            ValueEditor(t, v, types, KeyboardType.Phone, onType = { nt -> phones = phones.toMutableList().also { it[i] = nt to v } },
                onValue = { nv -> phones = phones.toMutableList().also { it[i] = t to nv } }, onRemove = { phones = phones.filterIndexed { j, _ -> j != i } })
        }
        Chip("+ " + stringResource(R.string.con_add_phone)) { phones = phones + (model.prefs.phoneType to "") }

        SectionHeader(stringResource(R.string.con_emails))
        emails.forEachIndexed { i, (t, v) ->
            ValueEditor(t, v, mailTypes, KeyboardType.Email, onType = { nt -> emails = emails.toMutableList().also { it[i] = nt to v } },
                onValue = { nv -> emails = emails.toMutableList().also { it[i] = t to nv } }, onRemove = { emails = emails.filterIndexed { j, _ -> j != i } })
        }
        Chip("+ " + stringResource(R.string.con_add_email)) { emails = emails + ("home" to "") }

        SectionHeader(stringResource(R.string.con_addresses))
        addresses.forEachIndexed { i, a ->
            Card(Modifier.padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        for ((k, l) in mailTypes) Chip(l, a.type == k) { addresses = addresses.toMutableList().also { it[i] = a.copy(type = k) } }
                        Spacer(Modifier.weight(1f))
                        IconButton(TitanIcons.x, stringResource(R.string.con_remove), { addresses = addresses.filterIndexed { j, _ -> j != i } })
                    }
                    TextInput(a.street, { v -> addresses = addresses.toMutableList().also { it[i] = a.copy(street = v) } }, stringResource(R.string.con_street), placeholder = stringResource(R.string.con_street))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextInput(a.postcode, { v -> addresses = addresses.toMutableList().also { it[i] = a.copy(postcode = v) } }, stringResource(R.string.con_postcode), Modifier.weight(0.4f), placeholder = stringResource(R.string.con_postcode))
                        TextInput(a.city, { v -> addresses = addresses.toMutableList().also { it[i] = a.copy(city = v) } }, stringResource(R.string.con_city), Modifier.weight(0.6f), placeholder = stringResource(R.string.con_city))
                    }
                    TextInput(a.region, { v -> addresses = addresses.toMutableList().also { it[i] = a.copy(region = v) } }, stringResource(R.string.con_region), placeholder = stringResource(R.string.con_region))
                    TextInput(a.country, { v -> addresses = addresses.toMutableList().also { it[i] = a.copy(country = v) } }, stringResource(R.string.con_country), placeholder = stringResource(R.string.con_country))
                }
            }
        }
        Chip("+ " + stringResource(R.string.con_add_address)) { addresses = addresses + PostalAddress() }

        SectionHeader(stringResource(R.string.con_birthday))
        Card {
            ListRow(stringResource(R.string.con_birthday), right = {
                Chip(if (birthday.isBlank()) stringResource(R.string.con_birthday_none) else birthdayText(birthday)) { pickDate = true }
                if (birthday.isNotBlank()) IconButton(TitanIcons.x, stringResource(R.string.con_remove), { birthday = ""; noYear = false })
            })
            ToggleRow(stringResource(R.string.con_no_year), noYear, { on ->
                noYear = on
                birthday = when {
                    birthday.isBlank() -> ""
                    on && !birthday.startsWith("--") -> "--" + birthday.substring(5)
                    !on && birthday.startsWith("--") -> "${LocalDate.now().year - 30}-" + birthday.substring(2)
                    else -> birthday
                }
            }, divider = true)
        }
        SectionHeader(stringResource(R.string.con_notes))
        TextInput(notes, { notes = it }, stringResource(R.string.con_notes), Modifier.heightIn(min = 120.dp), placeholder = stringResource(R.string.con_notes))
        TitanButton(stringResource(R.string.con_save), { save() }, Modifier.fillMaxWidth().padding(top = 16.dp), kind = ButtonKind.Primary)
        Box(Modifier.navigationBarsPadding())
    }
    if (pickDate) {
        val init = runCatching { if (birthday.startsWith("--")) LocalDate.parse("2000-" + birthday.substring(2)) else LocalDate.parse(birthday) }.getOrDefault(LocalDate.now().minusYears(30))
        DatePick(init, { d -> birthday = if (noYear) "--%02d-%02d".format(d.monthValue, d.dayOfMonth) else d.toString() }) { pickDate = false }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValueEditor(type: String, value: String, types: List<Pair<String, String>>, kb: KeyboardType, onType: (String) -> Unit, onValue: (String) -> Unit, onRemove: () -> Unit) {
    Card(Modifier.padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, l) in types) Chip(l, type == k) { onType(k) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextInput(value, onValue, types.firstOrNull { it.first == type }?.second ?: type, Modifier.weight(1f), keyboard = KeyboardOptions(keyboardType = kb))
                IconButton(TitanIcons.x, stringResource(R.string.con_remove), onRemove)
            }
        }
    }
}

/** Dubletten (MergeView-Liste): Sätze möglicher Doppelter mit dem Grund. */
@Composable
private fun DuplicatesPage(model: ContactsModel, go: (ConRoute) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var sets by remember { mutableStateOf<List<DuplicateSet>?>(null) }
    var problem by remember { mutableStateOf<ConProblem?>(null) }
    LaunchedEffect(Unit) { runCatching { model.api.duplicates() }.onSuccess { sets = it }.onFailure { problem = conProblemOf(it) } }
    Page(topBar = { ConTop(stringResource(R.string.con_duplicates), onBack) }) {
        problem?.let { Problem(conProblemText(it), Modifier.padding(top = 10.dp)) }
        val list = sets
        when {
            list == null -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(16.dp))
            list.isEmpty() -> EmptyState(stringResource(R.string.con_no_duplicates), stringResource(R.string.con_no_duplicates_text))
            else -> {
                Note(stringResource(R.string.con_duplicates_note), Modifier.padding(top = 10.dp))
                for (s in list) {
                    Card(Modifier.padding(top = 10.dp)) {
                        val names = s.ids.mapNotNull { model.item(it) }.joinToString(" · ") { model.display(it) }
                        ListRow(names, sub = s.matches.joinToString("\n") { m ->
                            when (m.kind) { "email" -> "E-Mail"; "phone" -> "☎"; else -> "Name" }.let { k -> "$k: ${m.value}" }
                        }, chevron = true, onClick = { go(ConRoute.Merge(s)) })
                    }
                }
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}

/** Zusammenführen (MergeView.svelte): welcher bleibt, Felder vereint, Bild wählen. */
@Composable
private fun MergePage(model: ContactsModel, set: DuplicateSet, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var keep by remember { mutableStateOf(0) }
    var photo by remember { mutableStateOf("keep") }
    var problem by remember { mutableStateOf<ConProblem?>(null) }
    LaunchedEffect(set) { contacts = set.ids.take(2).mapNotNull { runCatching { model.api.contact(it) }.getOrNull() } }
    Page(topBar = { ConTop(stringResource(R.string.con_merge), onDone) }) {
        problem?.let { Problem(conProblemText(it), Modifier.padding(top = 10.dp)) }
        if (contacts.size < 2) { Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(16.dp)); return@Page }
        val a = contacts[keep]
        val b = contacts[1 - keep]
        SectionHeader(stringResource(R.string.con_merge_keep))
        Card {
            ChoiceRows(contacts.mapIndexed { i, c -> i to c.name.ifBlank { c.org } }, keep, { keep = it },
                subs = contacts.mapIndexed { i, c -> i to listOf(c.phones.size, c.emails.size).let { (p, m) -> "☎ $p · @ $m" } }.toMap())
        }
        fun <T> union(x: List<T>, y: List<T>, key: (T) -> String) = (x + y).distinctBy(key)
        val merged = ContactInput(
            first = a.first.ifBlank { b.first }, last = a.last.ifBlank { b.last }, org = a.org.ifBlank { b.org },
            phones = union(a.phones, b.phones) { it.value.filter(Char::isDigit) }.map { TypedValue(it.type, it.value) },
            emails = union(a.emails, b.emails) { it.value.lowercase() }.map { TypedValue(it.type, it.value) },
            addresses = union(a.addresses, b.addresses) { (it.street + it.city).lowercase() }.map { AddressInput(it.type, it.street, it.city, it.postcode, it.region, it.country) },
            birthday = a.birthday.ifBlank { b.birthday }, notes = listOf(a.notes, b.notes).filter { it.isNotBlank() }.distinct().joinToString("\n\n"),
        )
        SectionHeader(stringResource(R.string.con_merge_result))
        Card {
            ListRow(listOf(merged.first, merged.last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { merged.org }, sub = merged.org.takeIf { it.isNotBlank() })
            if (merged.phones.isNotEmpty()) ListRow(stringResource(R.string.con_phones), sub = merged.phones.joinToString("\n") { it.value }, divider = true)
            if (merged.emails.isNotEmpty()) ListRow(stringResource(R.string.con_emails), sub = merged.emails.joinToString("\n") { it.value }, divider = true)
            if (merged.addresses.isNotEmpty()) ListRow(stringResource(R.string.con_addresses), sub = merged.addresses.joinToString("\n") { "${it.street}, ${it.postcode} ${it.city}" }, divider = true)
            if (merged.birthday.isNotBlank()) ListRow(stringResource(R.string.con_birthday), sub = birthdayText(merged.birthday), divider = true)
        }
        if (a.hasPhoto || b.hasPhoto) {
            SectionHeader(stringResource(R.string.con_merge_photo))
            Card {
                ChoiceRows(listOf("keep" to stringResource(R.string.con_merge_photo_keep), "drop" to stringResource(R.string.con_merge_photo_drop), "none" to stringResource(R.string.con_merge_photo_none)), photo, { photo = it })
            }
        }
        Note(stringResource(R.string.con_merge_note), Modifier.padding(top = 10.dp))
        TitanButton(stringResource(R.string.con_merge_do), {
            scope.launch {
                runCatching { model.api.merge(EtagRef(a.id, a.etag), EtagRef(b.id, b.etag), merged, photo) }.onSuccess { onDone() }.onFailure { problem = conProblemOf(it) }
            }
        }, Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.Primary, icon = PimIcons.merge)
        Box(Modifier.navigationBarsPadding())
    }
}

/** Vorschläge aus Mail (SuggestionRow.svelte): gelernte Adressen als Kontakt übernehmen. */
@Composable
private fun SuggestionsPage(model: ContactsModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Suggestion>?>(null) }
    var problem by remember { mutableStateOf<ConProblem?>(null) }
    LaunchedEffect(Unit) { runCatching { model.api.suggestions() }.onSuccess { list = it }.onFailure { problem = conProblemOf(it) } }
    Page(topBar = { ConTop(stringResource(R.string.con_suggestions), onBack) }) {
        problem?.let { Problem(conProblemText(it), Modifier.padding(top = 10.dp)) }
        val l = list
        when {
            l == null -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(16.dp))
            l.isEmpty() -> EmptyState(stringResource(R.string.con_no_suggestions), stringResource(R.string.con_no_suggestions_text))
            else -> Card(Modifier.padding(top = 10.dp)) {
                l.forEachIndexed { i, s ->
                    ListRow(s.name.ifBlank { s.addr }, sub = s.addr + " · " + pluralStringResource(R.plurals.con_mails, s.count, s.count), divider = i > 0, right = {
                        TitanButton(stringResource(R.string.con_add), {
                            scope.launch { runCatching { model.api.fromSuggestion(s.addr) }.onSuccess { model.reload(); onOpen(it.id) }.onFailure { problem = conProblemOf(it) } }
                        })
                    })
                }
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}

/** Einstellungen von Kontakte, aufklappbar; Import/Export und Abgleich mit dem Handy. */
@Composable
private fun ContactsSettings(model: ContactsModel, onBack: () -> Unit, onSyncSettings: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = model.prefs
    var message by remember { mutableStateOf<String?>(null) }
    var replace by remember { mutableStateOf(false) }
    val doneText = stringResource(R.string.con_imported)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                model.api.importVcf(bytes, replace)
            }.onSuccess { r -> message = doneText.format(r.added, r.updated, r.skipped, r.failed.size); model.reload() }.onFailure { model.problem = conProblemOf(it) }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vcard")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { out -> kotlinx.coroutines.runBlocking { model.api.exportVcf(out) } } } }
                .onFailure { model.problem = conProblemOf(it) }
        }
    }
    Page(topBar = { ConTop(stringResource(R.string.con_settings), onBack) }, overlay = {
        message?.let { m -> ConfirmDialog(stringResource(R.string.con_import), m, listOf(DialogAction(stringResource(UiR.string.ui_close)) { message = null }), onDismiss = { message = null }) }
    }) {
        model.problem?.let { Problem(conProblemText(it), Modifier.padding(top = 10.dp)) }
        val sorts = listOf("last" to stringResource(R.string.con_sort_last), "first" to stringResource(R.string.con_sort_first))
        SettingsGroup(stringResource(R.string.con_set_sort), sorts.first { it.first == p.sortBy }.second, initiallyOpen = true) {
            ChoiceRows(sorts, p.sortBy, { model.updatePrefs(p.copy(sortBy = it)) })
        }
        val shows = listOf("first" to stringResource(R.string.con_show_first), "last" to stringResource(R.string.con_show_last))
        SettingsGroup(stringResource(R.string.con_set_show), shows.first { it.first == p.showAs }.second) {
            ChoiceRows(shows, p.showAs, { model.updatePrefs(p.copy(showAs = it)) })
        }
        SettingsGroup(stringResource(R.string.con_set_display), null) {
            ToggleRow(stringResource(R.string.con_set_photos), p.photos, { model.updatePrefs(p.copy(photos = it)) })
            ToggleRow(stringResource(R.string.con_set_org), p.showOrg, { model.updatePrefs(p.copy(showOrg = it)) }, divider = true)
            ToggleRow(stringResource(R.string.con_set_index), p.index, { model.updatePrefs(p.copy(index = it)) }, divider = true)
            ToggleRow(stringResource(R.string.con_set_birthdays), p.birthdaysSoon, { model.updatePrefs(p.copy(birthdaysSoon = it)) }, divider = true)
            ToggleRow(stringResource(R.string.con_set_suggestions), p.suggestions, { model.updatePrefs(p.copy(suggestions = it)) }, sub = stringResource(R.string.con_set_suggestions_sub), divider = true)
            ToggleRow(stringResource(R.string.con_set_confirm), p.confirmDelete, { model.updatePrefs(p.copy(confirmDelete = it)) }, divider = true)
        }
        val phoneTypes = listOf("mobile" to stringResource(R.string.con_type_mobile), "home" to stringResource(R.string.con_type_home), "work" to stringResource(R.string.con_type_work), "other" to stringResource(R.string.con_type_other))
        SettingsGroup(stringResource(R.string.con_set_phone_type), phoneTypes.first { it.first == p.phoneType }.second) {
            ChoiceRows(phoneTypes, p.phoneType, { model.updatePrefs(p.copy(phoneType = it)) })
        }
        SettingsGroup(stringResource(R.string.con_set_me), model.me?.let { model.item(it) }?.let { model.display(it) } ?: stringResource(R.string.con_me_none)) {
            ListRow(stringResource(R.string.con_me_hint))
            if (model.me != null) ListRow(stringResource(R.string.con_unset_me), divider = true, onClick = { model.chooseMe(null) })
        }
        SettingsGroup(stringResource(R.string.con_set_files), null) {
            ListRow(stringResource(R.string.con_export), icon = { RowIcon(TitanIcons.download) }, onClick = { save.launch("Kontakte.vcf") })
            ListRow(stringResource(R.string.con_import_skip), divider = true, icon = { RowIcon(TitanIcons.upload) }, onClick = { replace = false; pick.launch(arrayOf("text/vcard", "text/x-vcard", "*/*")) })
            ListRow(stringResource(R.string.con_import_replace), divider = true, icon = { RowIcon(TitanIcons.upload) }, onClick = { replace = true; pick.launch(arrayOf("text/vcard", "text/x-vcard", "*/*")) })
        }
        SectionHeader(stringResource(R.string.con_phone))
        Card { ListRow(stringResource(R.string.sync_title), sub = stringResource(R.string.con_phone_sub), chevron = true, icon = { RowIcon(TitanIcons.sync) }, onClick = onSyncSettings) }
        Box(Modifier.navigationBarsPadding())
    }
}
