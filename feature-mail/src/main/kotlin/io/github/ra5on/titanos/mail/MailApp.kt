package io.github.ra5on.titanos.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.ui.Width
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.R as UiR
import java.io.File

/** Die Ebenen von Mail (levels.ts) und die Seiten darüber. */
sealed interface MailRoute {
    data object Boxes : MailRoute
    data class Folder(val account: String, val folder: String) : MailRoute
    data class Read(val account: String, val folder: String, val uid: Long, val listAccount: String, val listFolder: String) : MailRoute
    data class Compose(val start: ComposeStart, val closeAfter: Boolean = false) : MailRoute
    data object Settings : MailRoute
    data class Account(val id: String) : MailRoute
    data object Setup : MailRoute
}

/** Die Ebenen, mit denen Mail für einen [MailStart] beginnt. */
fun routesFor(start: MailStart): List<MailRoute> = when (start) {
    MailStart.Boxes -> listOf(MailRoute.Boxes)
    is MailStart.Folder -> listOf(MailRoute.Boxes, MailRoute.Folder(start.account, start.folder))
    is MailStart.Message -> listOf(
        MailRoute.Boxes,
        MailRoute.Folder(start.account, start.folder),
        MailRoute.Read(start.account, start.folder, start.uid, start.account, start.folder),
    )
    is MailStart.Compose -> listOf(
        MailRoute.Boxes,
        MailRoute.Compose(ComposeStart(start.mode, start.account, start.folder, null, start.to, start.cc, start.bcc, start.subject, start.body, start.uid), closeAfter = true),
    )
}

/**
 * Mail wie im Web (web/src/routes/mail, lib/tmail): am Handy Ebenen, ab
 * „Split“ Liste und Lesebereich nebeneinander, ab „Wide“ drei Spalten mit
 * den Postfächern links (ColumnFrame); jede Fenstergröße live. [nas] für
 * Anfragen, [stream] mit langer Lesezeit für `GET /mail/events`. [start]:
 * wo Mail beginnt (`mailto:`, Link einer Mitteilung). [newWindow] öffnet
 * eine Nachricht oder das Verfassen in einem eigenen Fenster.
 */
@Composable
fun MailApp(
    nas: NasConnection,
    stream: NasConnection,
    onClose: () -> Unit,
    start: MailStart = MailStart.Boxes,
    newWindow: ((MailStart) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(nas) { MailModel(MailApi(nas, stream), context.applicationContext, scope) }
    val titan = remember(nas) { TitanApi(nas) }
    val stack = remember(start) { mutableStateListOf<MailRoute>().apply { addAll(routesFor(start)) } }
    LaunchedEffect(model) {
        // Geöffnete oder geteilte Anhänge vom letzten Mal weg (nur im Hauptfenster).
        if (newWindow == null || start == MailStart.Boxes) runCatching { File(context.cacheDir, "mail").listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 3_600_000 }?.forEach { it.delete() } }
        model.start()
    }
    // Live nur, solange Mail zu sehen ist (wie die Seite, die im Hintergrund schließt).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, model.access) {
        if (model.access == true) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.watch() }
    }
    val open = newWindow ?: { s: MailStart -> MailRuntime.openWindow(context, s) }
    MailHost(model, titan, stack, onClose, open)
}

/**
 * Zeigt Mail nach der Breite: [Width.Phone] die oberste Ebene, [Width.Split]
 * Liste (bzw. Postfächer) und Lesebereich, ab [Width.Wide] dazu die
 * Postfächer als Spalte links. Schreiben, Einstellungen, Einrichten und
 * Postfach ändern stehen in jeder Breite über allem.
 */
@Composable
fun MailHost(
    model: MailModel,
    titan: TitanApi?,
    stack: SnapshotStateList<MailRoute>,
    onClose: () -> Unit,
    newWindow: ((MailStart) -> Unit)? = null,
    width: Width = currentWidth(),
) {
    fun push(r: MailRoute) {
        stack.add(r)
    }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose()
    }
    val top = stack.last()
    val folderRoute = stack.lastOrNull { it is MailRoute.Folder } as MailRoute.Folder?
    val readRoute = stack.lastOrNull { it is MailRoute.Read } as MailRoute.Read?
    val page = top.takeIf { it is MailRoute.Compose || it is MailRoute.Settings || it is MailRoute.Account || it is MailRoute.Setup }

    /** Einen Ordner öffnen: am Handy eine Ebene mehr, nebeneinander ersetzt er Liste und Nachricht. */
    fun openFolder(a: String, f: String) {
        if (width == Width.Phone) {
            push(MailRoute.Folder(a, f))
        } else {
            stack.clear()
            stack.add(MailRoute.Boxes)
            stack.add(MailRoute.Folder(a, f))
        }
    }
    fun openMessage(r: MessageRow, list: MailRoute.Folder) {
        val route = MailRoute.Read(r.account.ifEmpty { list.account }, r.folder.ifEmpty { list.folder }, r.uid, list.account, list.folder)
        if (width == Width.Phone) {
            push(route)
        } else {
            while (stack.lastOrNull() is MailRoute.Read) stack.removeAt(stack.lastIndex)
            stack.add(route)
        }
    }
    fun compose(start: ComposeStart) = push(MailRoute.Compose(start))

    // Seiten mit eigenem Zurück (Schreiben fragt, Einrichten geht Schritte zurück) behandeln es selbst.
    if (page == null) BackHandler { pop() }

    // Wo Mail beginnt (Einstellung) und, nebeneinander, nie ohne Liste.
    LaunchedEffect(width, model.accounts, model.prefs.startAt) {
        val main = model.mainAccount() ?: return@LaunchedEffect
        if (folderRoute != null || page != null) return@LaunchedEffect
        when {
            model.prefs.startAt == StartAt.AllInboxes -> openFolder(ALL, "inbox")
            model.prefs.startAt == StartAt.MainInbox || width.sidebar -> openFolder(main.id, model.inboxOf(main.id))
        }
    }
    // Ein Postfach, das es nicht mehr gibt (anderswo entfernt), verlässt die Ansicht.
    LaunchedEffect(model.accounts) {
        val acc = model.accounts ?: return@LaunchedEffect
        if (folderRoute != null && folderRoute.account != ALL && acc.none { it.id == folderRoute.account }) {
            stack.clear()
            stack.add(MailRoute.Boxes)
        }
    }

    when {
        model.accessError != null -> Page(topBar = { LevelHead(stringResource(R.string.desktop_app_mail), back = stringResource(R.string.mailx_home), onBack = onClose) }) {
            Problem(errorText(model.accessError), Modifier.fillMaxWidth())
            TitanButton(stringResource(UiR.string.ui_retry), { model.accessError = null; model.start() }, Modifier.fillMaxWidth())
        }
        model.access == false -> Page(topBar = { LevelHead(stringResource(R.string.desktop_app_mail), back = stringResource(R.string.mailx_home), onBack = onClose) }) {
            EmptyState(stringResource(R.string.mail_disabled_title), stringResource(R.string.mail_disabled_text), icon = { RowIcon(TitanIcons.lock, size = 40.dp) })
        }
        page != null -> PageRoute(model, titan, page, ::pop, onClose, stack)
        width == Width.Phone -> when (top) {
            is MailRoute.Folder -> FolderPane(model, top, readRoute, width, ::pop, ::openMessage, ::compose, newWindow)
            is MailRoute.Read -> ReadPane(model, titan, top, stack, width, ::pop, ::compose, newWindow, ::openMessage)
            else -> BoxesPane(model, null, width, onClose, ::openFolder, ::compose, { push(MailRoute.Setup) }, { push(MailRoute.Settings) })
        }
        else -> Row(Modifier.fillMaxSize().background(Titan.colors.bg)) {
            val prefs = model.prefs
            if (width.sidebar) {
                Box(Modifier.width(prefs.sidebarDp.dp).fillMaxHeight()) {
                    BoxesPane(model, folderRoute, width, onClose, ::openFolder, ::compose, { push(MailRoute.Setup) }, { push(MailRoute.Settings) })
                }
                ColumnDivider(stringResource(R.string.mail_resize_folders), prefs.sidebarDp, 200, 520) { model.updatePrefs(model.prefs.copy(sidebarDp = it)) }
            }
            Box(Modifier.width(prefs.listDp.dp).fillMaxHeight()) {
                if (folderRoute != null) {
                    FolderPane(model, folderRoute, readRoute, width, if (width.sidebar) null else ({ stack.clear(); stack.add(MailRoute.Boxes) }), ::openMessage, ::compose, newWindow)
                } else {
                    BoxesPane(model, null, width, onClose, ::openFolder, ::compose, { push(MailRoute.Setup) }, { push(MailRoute.Settings) })
                }
            }
            ColumnDivider(stringResource(R.string.mail_resize_list), prefs.listDp, 260, 720) { model.updatePrefs(model.prefs.copy(listDp = it)) }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (readRoute != null) {
                    ReadPane(model, titan, readRoute, stack, width, null, ::compose, newWindow, ::openMessage)
                } else {
                    MailOverview(
                        model, folderRoute,
                        onShow = { filter ->
                            val f = folderRoute ?: return@MailOverview
                            val list = model.list(f.account, f.folder)
                            list.filters = if (filter == "unread") list.filters.copy(unread = true) else list.filters.copy(flagged = true)
                            model.listsChanged()
                        },
                        onFolder = { a, f -> openFolder(a, f) },
                        onCompose = { compose(ComposeStart(account = model.mainAccount(folderRoute?.account.orEmpty())?.id.orEmpty())) },
                        onSettings = { push(MailRoute.Settings) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxesPane(
    model: MailModel,
    selected: MailRoute.Folder?,
    width: Width,
    onClose: () -> Unit,
    openFolder: (String, String) -> Unit,
    compose: (ComposeStart) -> Unit,
    onSetup: () -> Unit,
    onSettings: () -> Unit,
) {
    BoxesScreen(
        model,
        onClose = onClose,
        onOpen = openFolder,
        onCompose = { compose(ComposeStart(account = model.mainAccount(selected?.account.orEmpty())?.id.orEmpty())) },
        onSetup = onSetup,
        onSettings = onSettings,
        selected = selected?.let { it.account to it.folder },
        dropTargets = width.twoPanes && model.prefs.dragDrop,
    )
}

@Composable
private fun FolderPane(
    model: MailModel,
    route: MailRoute.Folder,
    read: MailRoute.Read?,
    width: Width,
    onBack: (() -> Unit)?,
    openMessage: (MessageRow, MailRoute.Folder) -> Unit,
    compose: (ComposeStart) -> Unit,
    newWindow: ((MailStart) -> Unit)?,
) {
    val list = model.list(route.account, route.folder)
    ListScreen(
        model, list,
        onBack = onBack ?: {},
        showBack = onBack != null,
        onOpen = { r -> openMessage(r, route) },
        onCompose = { compose(ComposeStart(account = model.mainAccount(route.account)?.id.orEmpty())) },
        onReplyTo = { r, mode -> compose(ComposeStart(mode, r.account.ifEmpty { route.account }, r.folder.ifEmpty { route.folder }, uid = r.uid)) },
        selected = read?.let { "${it.account}\u0001${it.folder}\u0000${it.uid}" },
        newWindow = newWindow?.let { open -> { r: MessageRow -> open(MailStart.Message(r.account.ifEmpty { route.account }, r.folder.ifEmpty { route.folder }, r.uid)) } },
        dragRows = width.twoPanes && model.prefs.dragDrop,
    )
}

@Composable
private fun ReadPane(
    model: MailModel,
    titan: TitanApi?,
    top: MailRoute.Read,
    stack: SnapshotStateList<MailRoute>,
    width: Width,
    onBack: (() -> Unit)?,
    compose: (ComposeStart) -> Unit,
    newWindow: ((MailStart) -> Unit)?,
    openMessage: (MessageRow, MailRoute.Folder) -> Unit,
) {
    val listName = when {
        top.listAccount == ALL && top.listFolder == "flagged" -> stringResource(R.string.mail_box_flagged)
        top.listAccount == ALL -> stringResource(R.string.mail_box_allInboxes)
        else -> model.findFolder(top.listAccount, top.listFolder)?.let { folderName(it) } ?: top.listFolder
    }
    // Die Nachricht danach, gemerkt solange die offene noch in der Liste steht.
    val nextHolder = remember(top) { arrayOfNulls<MessageRow>(1) }
    nextAfter(model.list(top.listAccount, top.listFolder).rows, top)?.let { nextHolder[0] = it }
    ReadScreen(
        model, titan, top.account, top.folder, top.uid,
        backLabel = listName,
        onBack = onBack ?: {},
        showBack = onBack != null,
        onCompose = { mode, msg -> compose(ComposeStart(mode, top.account, top.folder, msg)) },
        onMailto = { addr -> compose(ComposeStart(account = top.account, to = listOf(addr))) },
        onRemoved = {
            // Nach dem Entfernen: die nächste Nachricht der Liste oder zurück (Einstellung).
            stack.remove(top)
            val r = nextHolder[0]
            if (model.prefs.afterRemove == AfterRemove.Next && r != null) {
                openMessage(r, MailRoute.Folder(top.listAccount, top.listFolder))
            }
        },
        newWindow = newWindow?.let { open -> { open(MailStart.Message(top.account, top.folder, top.uid)) } },
        newWindowCompose = newWindow?.let { open -> { mode: ComposeMode -> open(MailStart.Compose(account = top.account, mode = mode, folder = top.folder, uid = top.uid)) } },
    )
}

@Composable
private fun PageRoute(model: MailModel, titan: TitanApi?, page: MailRoute, pop: () -> Unit, onClose: () -> Unit, stack: SnapshotStateList<MailRoute>) {
    when (page) {
        is MailRoute.Compose -> ComposeScreen(model, titan, page.start, onDone = { if (page.closeAfter) onClose() else pop() })
        MailRoute.Settings -> SettingsScreen(model, onBack = pop, onAccount = { stack.add(MailRoute.Account(it)) }, onSetup = { stack.add(MailRoute.Setup) })
        is MailRoute.Account -> AccountScreen(model, page.id, onBack = pop, onRemoved = pop)
        MailRoute.Setup -> SetupScreen(model, onBack = pop, onSaved = { a ->
            pop()
            model.refreshFolders(a.id, now = true)
            model.accounts = (model.accounts.orEmpty().filter { it.id != a.id } + a)
        })
        else -> Unit
    }
}

/**
 * Trenner zwischen zwei Spalten: ziehen ändert die Breite (gemerkt je
 * Gerät); für Bildschirmleser „breiter“/„schmaler“ als Aktionen.
 */
@Composable
private fun ColumnDivider(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val wider = stringResource(R.string.mailx_wider)
    val narrower = stringResource(R.string.mailx_narrower)
    var current = value
    Box(
        Modifier.width(9.dp).fillMaxHeight()
            .hoverable(source)
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .pointerInput(min, max) {
                detectHorizontalDragGestures { _, drag ->
                    current = (current + with(density) { drag.toDp().value }.toInt()).coerceIn(min, max)
                    onChange(current)
                }
            }
            .semantics {
                contentDescription = label
                customActions = listOf(
                    CustomAccessibilityAction(wider) { onChange((value + 40).coerceIn(min, max)); true },
                    CustomAccessibilityAction(narrower) { onChange((value - 40).coerceIn(min, max)); true },
                )
            },
    ) {
        Box(
            Modifier.width(if (hovered) 3.dp else 1.dp).fillMaxHeight().background(if (hovered) Titan.colors.accent else Titan.colors.border)
                .then(Modifier.align(androidx.compose.ui.Alignment.Center)),
        )
    }
}

/** Die Zeile nach der entfernten (sonst die davor), wie after() der Liste. */
private fun nextAfter(rows: List<MessageRow>, gone: MailRoute.Read): MessageRow? {
    val at = rows.indexOfFirst { it.uid == gone.uid && it.folder.ifEmpty { gone.listFolder } == gone.folder }
    if (at < 0) return null
    return rows.getOrNull(at + 1) ?: rows.getOrNull(at - 1)
}
