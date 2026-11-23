package io.github.ra5on.titanos.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.selected
import io.github.ra5on.titanos.ui.onSecondaryClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.BigTitle
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import io.github.ra5on.titanos.ui.R as UiR
import kotlinx.coroutines.launch

/** Kopf einer Ebene: Zurück links, Textknöpfe rechts, darunter der große Titel. */
@Composable
fun LevelHead(
    title: String,
    sub: String? = null,
    back: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Row(
                    Modifier.weight(1f).heightIn(min = 44.dp)
                        .then(Modifier.combinedClickable(role = Role.Button, onClick = onBack))
                        .semantics { contentDescription = back ?: "" }
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(TitanIcons.back, null, tint = Titan.colors.accent, modifier = Modifier.size(26.dp))
                    if (back != null) Text(back, style = TitanType.button.copy(fontWeight = FontWeight.Normal), color = Titan.colors.accent)
                }
                io.github.ra5on.titanos.agent.panel.AgentPanelButton()
                actions()
            } else {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    io.github.ra5on.titanos.agent.panel.AgentPanelButton()
                    actions()
                }
            }
        }
        BigTitle(title, Modifier.padding(start = 8.dp))
        if (sub != null) Text(sub, style = TitanType.rowSub, color = Titan.colors.muted, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
    }
}

/** Der Zustand eines Postfachs in Worten mit Symbol (StatusBadge), nur wenn nicht „Verbunden“. */
@Composable
fun statusText(s: MailStatus): String = when (s.state) {
    "connecting" -> stringResource(R.string.mail_state_connecting)
    "auth_failed" -> stringResource(R.string.mail_state_auth_failed)
    "unreachable" -> stringResource(R.string.mail_state_unreachable)
    "off" -> stringResource(R.string.mail_state_off)
    else -> stringResource(R.string.mail_state_ok)
}

private sealed interface FolderAsk {
    data class Create(val account: String, val parent: MailFolder?) : FolderAsk
    data class Rename(val account: String, val folder: MailFolder) : FolderAsk
    data class Delete(val account: String, val folder: MailFolder) : FolderAsk
}

/**
 * Ebene „Postfächer“ (Entwurf 41, Web am Handy): „Alle Posteingänge“,
 * „Markiert“, Favoriten, je Postfach seine Ordner (einklappbar), unten
 * „Postfach hinzufügen“ und „Einstellungen“. „Bearbeiten“ sortiert
 * Postfächer und Ordner, blendet Ordner aus und heftet Favoriten an.
 */
@Composable
fun BoxesScreen(
    model: MailModel,
    onClose: () -> Unit,
    onOpen: (account: String, folder: String) -> Unit,
    onCompose: () -> Unit,
    onSetup: () -> Unit,
    onSettings: () -> Unit,
    startEditing: Boolean = false,
    selected: Pair<String, String>? = null,
    dropTargets: Boolean = false,
) {
    var editing by remember { mutableStateOf(startEditing) }
    var sheetFor by remember { mutableStateOf<Pair<String, MailFolder>?>(null) }
    var ask by remember { mutableStateOf<FolderAsk?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val scope = rememberCoroutineScope()
    val prefs = model.prefs
    val accounts = model.orderedAccounts()
    val unread = accounts.sumOf { it.unread }
    val c = Titan.colors

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
                ask = null
            } catch (e: Exception) {
                error = e
            } finally {
                busy = false
            }
        }
    }

    Page(
        topBar = {
            LevelHead(
                stringResource(R.string.mail_box_title),
                back = stringResource(R.string.mailx_home),
                onBack = onClose,
            ) {
                if (accounts.isNotEmpty()) {
                    TextAction(stringResource(if (editing) R.string.mail_box_done else R.string.mail_box_edit), { editing = !editing }, bold = editing)
                }
            }
        },
        bottomBar = {
            if (sheetFor == null) MailBottomBar(
                stringResource(R.string.mail_box_title),
                if (accounts.isNotEmpty()) stringResource(R.string.mail_unreadCount, unread.toString()) else null,
                onCompose = if (accounts.isNotEmpty()) onCompose else null,
            )
        },
        overlay = {
            sheetFor?.let { (acc, f) ->
                Sheet(onDismiss = { sheetFor = null }) {
                    Text(folderName(f), style = TitanType.title, color = c.text, modifier = Modifier.padding(bottom = 12.dp))
                    Card(background = c.bg) {
                        val ref = folderRef(acc, f.path)
                        val fav = ref in prefs.favourites
                        ListRow(
                            stringResource(if (fav) R.string.mailx_fav_off else R.string.mailx_fav_on),
                            icon = { RowIcon(MailIcons.star) },
                            onClick = {
                                model.updatePrefs(prefs.copy(favourites = if (fav) prefs.favourites - ref else prefs.favourites + ref))
                                sheetFor = null
                            },
                        )
                        ListRow(
                            stringResource(R.string.mailx_folder_new_inside),
                            icon = { RowIcon(TitanIcons.plus) },
                            divider = true,
                            onClick = { name = ""; ask = FolderAsk.Create(acc, f); sheetFor = null },
                        )
                        if (f.role.isEmpty() && f.path != "INBOX") {
                            ListRow(
                                stringResource(R.string.mailx_folder_rename),
                                icon = { RowIcon(TitanIcons.edit) },
                                divider = true,
                                onClick = { name = f.name; ask = FolderAsk.Rename(acc, f); sheetFor = null },
                            )
                            ListRow(
                                stringResource(R.string.mailx_folder_delete),
                                icon = { RowIcon(TitanIcons.trash, tint = c.dangerInk) },
                                titleColor = c.dangerInk,
                                divider = true,
                                onClick = { ask = FolderAsk.Delete(acc, f); sheetFor = null },
                            )
                        }
                    }
                }
            }
            when (val a = ask) {
                is FolderAsk.Create, is FolderAsk.Rename -> ConfirmDialog(
                    title = stringResource(if (a is FolderAsk.Create) R.string.mailx_folder_new else R.string.mailx_folder_rename),
                    text = if (a is FolderAsk.Create && a.parent != null) stringResource(R.string.mailx_folder_new_in, folderName(a.parent)) else null,
                    actions = listOf(
                        DialogAction(stringResource(UiR.string.ui_cancel)) { ask = null },
                        DialogAction(stringResource(R.string.mail_setup_save)) {
                            if (name.isNotBlank() && !busy) {
                                run {
                                    when (a) {
                                        is FolderAsk.Create -> model.api.createFolder(a.account, childPath(a.parent, name))
                                        is FolderAsk.Rename -> model.api.renameFolder(a.account, a.folder.path, renamedPath(a.folder, name))
                                        else -> Unit
                                    }
                                    val acc = if (a is FolderAsk.Create) a.account else (a as FolderAsk.Rename).account
                                    model.refreshFolders(acc, now = true)
                                }
                            }
                        },
                    ),
                    onDismiss = { ask = null },
                ) {
                    TextInput(name, { name = it }, stringResource(R.string.mail_signature_name), placeholder = stringResource(R.string.mail_signature_name))
                    if (error != null) Problem(errorText(error), Modifier.padding(top = 8.dp))
                    Gap(10.dp)
                }
                is FolderAsk.Delete -> ConfirmDialog(
                    title = stringResource(R.string.mailx_folder_delete_title, folderName(a.folder)),
                    text = stringResource(R.string.mailx_folder_delete_text),
                    actions = listOf(
                        DialogAction(stringResource(R.string.mailx_folder_delete), danger = true) {
                            if (!busy) run {
                                model.api.deleteFolder(a.account, a.folder.path)
                                model.refreshFolders(a.account, now = true)
                            }
                        },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { ask = null },
                    ),
                    onDismiss = { ask = null },
                ) { if (error != null) Problem(errorText(error), Modifier.padding(bottom = 8.dp)) }
                null -> Unit
            }
        },
    ) {
        model.accountsError?.let { Problem(errorText(it), Modifier.padding(bottom = 10.dp)) }
        if (ask == null) error?.let { Problem(errorText(it), Modifier.padding(bottom = 10.dp)) }
        model.orderError?.let { Problem(stringResource(R.string.mailx_order_failed, errorText(it)), Modifier.padding(bottom = 10.dp)) }
        if (model.live == Live.Down) Note(stringResource(R.string.mail_live_down), Modifier.padding(bottom = 10.dp), icon = TitanIcons.warn)
        when {
            model.accounts == null -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(8.dp))
            accounts.isEmpty() -> {
                EmptyState(stringResource(R.string.mail_empty_title), stringResource(R.string.mail_empty_text), icon = { RowIcon(TitanIcons.mail, size = 40.dp) })
                TitanButton(stringResource(R.string.mail_empty_add), onSetup, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = TitanIcons.plus)
            }
            editing -> EditBoxes(model)
            else -> {
                Card {
                    ListRow(
                        stringResource(R.string.mail_box_allInboxes),
                        icon = { RowIcon(TitanIcons.mail, tint = c.accent) },
                        right = { if (unread > 0) Count(unread) },
                        chevron = true,
                        modifier = Modifier.selectedRow(selected == ALL to "inbox"),
                        onClick = { onOpen(ALL, "inbox") },
                    )
                    ListRow(
                        stringResource(R.string.mail_box_flagged),
                        icon = { RowIcon(MailIcons.flag, tint = c.accent) },
                        chevron = true,
                        divider = true,
                        modifier = Modifier.selectedRow(selected == ALL to "flagged"),
                        onClick = { onOpen(ALL, "flagged") },
                    )
                }
                val favs = prefs.favourites.mapNotNull { ref ->
                    val acc = ref.substringBefore('\u0000')
                    val path = ref.substringAfter('\u0000')
                    model.findFolder(acc, path)?.let { acc to it }
                }
                if (favs.isNotEmpty()) {
                    io.github.ra5on.titanos.ui.components.SectionHeader(stringResource(R.string.mail_fav_title))
                    Card {
                        favs.forEachIndexed { i, (acc, f) ->
                            FolderRow(f, 0, i > 0, sub = model.account(acc)?.label, selected = selected == acc to f.path, counts = prefs.folderCounts,
                                drop = DropInfo(dropTargets, acc) { d -> moveDropped(model, scope, d, f) { error = it } },
                                onClick = { onOpen(acc, f.path) }, onLong = { sheetFor = acc to f })
                        }
                    }
                }
                for (a in accounts) {
                    AccountSection(model, a, selected = selected?.takeIf { it.first == a.id }?.second, drop = DropInfo(dropTargets, a.id) { _ -> },
                        onDropTo = { d, f -> moveDropped(model, scope, d, f) { error = it } },
                        onOpen = { onOpen(a.id, it) }, onLong = { f -> sheetFor = a.id to f }, onNewFolder = { name = ""; ask = FolderAsk.Create(a.id, null) })
                }
                Gap(14.dp)
                Card {
                    ListRow(stringResource(R.string.mail_side_add), icon = { RowIcon(TitanIcons.plus) }, chevron = true, onClick = onSetup)
                    ListRow(stringResource(R.string.mail_side_settings), icon = { RowIcon(TitanIcons.gear) }, chevron = true, divider = true, onClick = onSettings)
                }
            }
        }
    }
}

@Composable
internal fun Count(n: Int) {
    Text(n.toString(), style = TitanType.rowSub, color = Titan.colors.muted)
}

/** Ob ein Ordner gezogene Mails annimmt, und aus welchem Postfach. */
class DropInfo(val enabled: Boolean, val account: String, val onDrop: (MailDrag) -> Unit)

/** Hervorhebung des offenen Ordners bzw. der offenen Mail (auch mit Zeiger darüber). */
@Composable
fun Modifier.selectedRow(selected: Boolean, hovered: Boolean = false): Modifier {
    val c = Titan.colors
    return when {
        selected -> this.background(c.accent.copy(alpha = .16f))
        hovered -> this.background(c.text.copy(alpha = .06f))
        else -> this
    }.semantics { if (selected) this.selected = true }
}

@Composable
private fun FolderRow(
    f: MailFolder,
    depth: Int,
    divider: Boolean,
    sub: String? = null,
    toggle: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    counts: Boolean = true,
    drop: DropInfo? = null,
    onDropHere: ((MailDrag) -> Unit)? = null,
    onClick: () -> Unit,
    onLong: () -> Unit,
) {
    val c = Titan.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dropHover by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (divider) Divider()
        Row(
            Modifier.fillMaxWidth()
                .mailDropTarget(drop?.enabled == true, drop?.account.orEmpty(), f.path, { dropHover = it }) { d -> (onDropHere ?: drop?.onDrop)?.invoke(d) }
                .then(if (dropHover) Modifier.border(2.dp, c.accent, RoundedCornerShape(10.dp)) else Modifier)
                .selectedRow(selected || dropHover, hovered)
                .hoverable(source)
                .onSecondaryClick(onLong)
                .combinedClickable(onClick = onClick, onLongClick = onLong).heightIn(min = 52.dp)
                .padding(start = (14 + depth * 22).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RowIcon(folderIcon(f.role), tint = c.accent)
            Column(Modifier.weight(1f)) {
                Text(folderName(f), style = TitanType.body.copy(fontSize = TitanType.rowTitle.fontSize), color = c.text)
                if (sub != null) Text(sub, style = TitanType.rowSub, color = c.muted)
            }
            if (counts && f.unread > 0) Count(f.unread)
            Icon(TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(18.dp))
            toggle?.invoke()
        }
    }
}

@Composable
private fun AccountSection(
    model: MailModel,
    a: MailAccount,
    selected: String?,
    drop: DropInfo,
    onDropTo: (MailDrag, MailFolder) -> Unit,
    onOpen: (String) -> Unit,
    onLong: (MailFolder) -> Unit,
    onNewFolder: () -> Unit,
) {
    val c = Titan.colors
    val prefs = model.prefs
    val collapsed = a.id in prefs.collapsed
    val shown = stringResource(R.string.mailx_shown)
    val hiddenWord = stringResource(R.string.mailx_collapsed)
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)
            .combinedClickable(role = Role.Button) {
                model.updatePrefs(prefs.copy(collapsed = if (collapsed) prefs.collapsed - a.id else prefs.collapsed + a.id))
            }
            .semantics { stateDescription = if (collapsed) hiddenWord else shown }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.label, style = TitanType.title, color = c.text)
            if (a.name.isNotBlank() && a.name != a.email) Text(a.email, style = TitanType.rowSub, color = c.muted)
            if (a.status.state != "ok") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    Icon(if (a.status.state == "connecting") TitanIcons.sync else TitanIcons.warn, null, tint = if (a.status.state == "connecting") c.muted else c.warnInk, modifier = Modifier.size(16.dp))
                    Text(statusText(a.status), style = TitanType.rowSub, color = if (a.status.state == "connecting") c.muted else c.warnInk)
                }
            }
        }
        Icon(if (collapsed) TitanIcons.chev else TitanIcons.down, null, tint = c.accent, modifier = Modifier.size(22.dp))
    }
    if (collapsed) return
    model.folderErrors[a.id]?.let { Problem(errorText(it), Modifier.padding(bottom = 8.dp)) }
    val list = model.folders[a.id]
    if (list == null) {
        Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(8.dp))
        return
    }
    val hidden = model.hidden(a.id)
    val arranged = arrangeFolders(list, model.order.mail[a.id].orEmpty()).filter { it.folder.path !in hidden }
    // Ein eingeklappter Ordner verbirgt alles darunter.
    val folded = prefs.collapsedFolders.filter { it.startsWith(a.id + "\u0000") }.map { it.substringAfter('\u0000') }
    val visible = arranged.filter { af -> folded.none { p -> list.firstOrNull { it.path == p }?.let { isBelow(af.folder, it) } == true } }
    Card {
        visible.forEachIndexed { i, af ->
            val hasChildren = list.any { isBelow(it, af.folder) } && af.folder.role.isEmpty()
            val ref = folderRef(a.id, af.folder.path)
            val isFolded = af.folder.path in folded
            FolderRow(
                af.folder, af.depth, i > 0,
                selected = selected == af.folder.path,
                counts = prefs.folderCounts,
                drop = drop,
                onDropHere = { d -> onDropTo(d, af.folder) },
                toggle = if (!hasChildren) null else ({
                    val label = stringResource(if (isFolded) R.string.mail_tree_expand else R.string.mail_tree_collapse, folderName(af.folder))
                    IconButton(if (isFolded) TitanIcons.chev else TitanIcons.down, label, {
                        model.updatePrefs(prefs.copy(collapsedFolders = if (isFolded) prefs.collapsedFolders - ref else prefs.collapsedFolders + ref))
                    }, tint = c.accent)
                }),
                onClick = { onOpen(af.folder.path) },
                onLong = { onLong(af.folder) },
            )
        }
        ListRow(stringResource(R.string.mailx_folder_new), icon = { RowIcon(TitanIcons.plus) }, divider = visible.isNotEmpty(), onClick = onNewFolder)
    }
}

/**
 * „Bearbeiten“ (115-4): Postfächer und Ordner nach oben/unten (das oberste
 * Postfach ist das Hauptpostfach), Haken zeigt/verbirgt einen Ordner, der
 * Stern heftet ihn als Favorit an. Gespeichert am NAS (Reihenfolge) bzw.
 * am Handy (Favoriten).
 */
@Composable
private fun EditBoxes(model: MailModel) {
    val c = Titan.colors
    val accounts = model.orderedAccounts()
    Note(stringResource(R.string.mailx_box_hint), Modifier.padding(bottom = 12.dp))
    io.github.ra5on.titanos.ui.components.SectionHeader(stringResource(R.string.mail_settings_accounts), top = 4.dp)
    Card {
        accounts.forEachIndexed { i, a ->
            val ids = accounts.map { it.id }
            ListRow(
                a.label,
                sub = if (i == 0) stringResource(R.string.mailx_main_account) else a.email,
                divider = i > 0,
                right = {
                    Row {
                        IconButton(MailIcons.up, stringResource(R.string.mailx_move_up, a.label), { if (i > 0) model.setAccountOrder(moveKey(ids, a.id, i - 1)) }, tint = if (i > 0) c.accent else c.muted)
                        IconButton(TitanIcons.down, stringResource(R.string.mailx_move_down, a.label), { if (i < ids.size - 1) model.setAccountOrder(moveKey(ids, a.id, i + 1)) }, tint = if (i < ids.size - 1) c.accent else c.muted)
                    }
                },
            )
        }
    }
    val prefs = model.prefs
    for (a in accounts) {
        io.github.ra5on.titanos.ui.components.SectionHeader(a.label)
        val list = model.folders[a.id].orEmpty()
        val arranged = arrangeFolders(list, model.order.mail[a.id].orEmpty())
        val hidden = model.hidden(a.id)
        Card {
            arranged.forEachIndexed { i, af ->
                val f = af.folder
                val group = arranged.filter { it.group == af.group }.map { it.folder.path }
                val at = group.indexOf(f.path)
                val ref = folderRef(a.id, f.path)
                val fav = ref in prefs.favourites
                val on = f.path !in hidden
                val name = folderName(f)
                Column {
                    if (i > 0) Divider()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = (6 + af.depth * 18).dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val showLabel = stringResource(R.string.mail_box_show, name)
                        val onWord = stringResource(UiR.string.ui_selected)
                        val offWord = stringResource(UiR.string.ui_not_selected)
                        IconButton(if (on) TitanIcons.check else TitanIcons.x, showLabel, {
                            model.setHidden(a.id, if (on) hidden + f.path else hidden - f.path)
                        }, tint = if (on) c.accent else c.muted)
                        Box(Modifier.semantics { stateDescription = if (on) onWord else offWord })
                        Text(name, style = TitanType.body, color = if (on) c.text else c.muted, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        IconButton(MailIcons.star, stringResource(if (fav) R.string.mail_fav_remove else R.string.mail_fav_add, name), {
                            model.updatePrefs(prefs.copy(favourites = if (fav) prefs.favourites - ref else prefs.favourites + ref))
                        }, tint = if (fav) c.warnInk else c.muted)
                        IconButton(MailIcons.up, stringResource(R.string.mailx_move_up, name), {
                            if (at > 0) model.setFolderOrder(a.id, mergeGroup(model.order.mail[a.id].orEmpty(), moveKey(group, f.path, at - 1)))
                        }, tint = if (at > 0) c.accent else c.muted)
                        IconButton(TitanIcons.down, stringResource(R.string.mailx_move_down, name), {
                            if (at < group.size - 1) model.setFolderOrder(a.id, mergeGroup(model.order.mail[a.id].orEmpty(), moveKey(group, f.path, at + 1)))
                        }, tint = if (at < group.size - 1) c.accent else c.muted)
                    }
                    if (fav) Text(stringResource(R.string.mailx_is_fav), style = TitanType.small, color = c.muted, modifier = Modifier.padding(start = (54 + af.depth * 18).dp, bottom = 8.dp))
                }
            }
        }
    }
    Spacer(Modifier.width(1.dp))
}

/** Gezogene Mails in einen Ordner verschieben (nur innerhalb des Postfachs). */
private fun moveDropped(model: MailModel, scope: kotlinx.coroutines.CoroutineScope, d: MailDrag, to: MailFolder, onError: (Throwable) -> Unit) {
    scope.launch {
        try {
            model.move(d.uids.map { MessageRow(uid = it, account = d.account, folder = d.folder) }, null, to.path)
        } catch (e: Exception) {
            onError(e)
        }
    }
}

/** Die gespeicherte Reihenfolge einer Gruppe nach dem Verschieben (mergeGroup). */
fun mergeGroup(saved: List<String>, shown: List<String>): List<String> = shown + saved.filter { it !in shown }
