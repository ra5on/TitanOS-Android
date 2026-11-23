package io.github.ra5on.titanos.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.StateBadge
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.wallpaper
import io.github.ra5on.titanos.ui.rememberWidestWord
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.okInk
import kotlinx.coroutines.launch

@Composable
fun programLabel(id: String): String = stringResource(programLabelRes(id))

/** Text-Ressource des Programmnamens (auch ohne Compose, z. B. für Kurzbefehle am App-Symbol). */
fun programLabelRes(id: String): Int = (
    when (id) {
        "files" -> R.string.app_files
        "photos" -> R.string.app_photos
        "shares" -> R.string.app_shares
        "storage" -> R.string.app_storage
        "docker" -> R.string.app_docker
        "vms" -> R.string.app_vms
        "lxc" -> R.string.app_lxc
        "mail" -> R.string.app_mail
        "calendar" -> R.string.app_calendar
        "contacts" -> R.string.app_contacts
        "agent" -> R.string.app_agent
        "backup" -> R.string.app_backup
        "nvr" -> R.string.app_nvr
        "terminal" -> R.string.app_terminal
        "users" -> R.string.app_users
        "plugins" -> R.string.app_plugins
        "monitor" -> R.string.app_monitor
        "logs" -> R.string.app_logs
        "planner" -> R.string.app_planner
        "security" -> R.string.app_security
        "account" -> R.string.app_account
        else -> R.string.app_settings
    }
)

/** Name und Erklärung eines Widgets unter „Hinzufügen“ (Entwurf 04). */
internal fun widgetTitleRes(kind: String): Int = when (kind) {
    "status" -> R.string.home_widget_status
    "volumes" -> R.string.home_widget_volumes
    "photos" -> R.string.home_photos_title
    "docker" -> R.string.home_widget_docker
    else -> R.string.home_widget_vms
}

internal fun widgetSubRes(kind: String): Int = when (kind) {
    "status" -> R.string.home_widget_status_sub
    "volumes" -> R.string.home_widget_volumes_sub
    "photos" -> R.string.home_widget_photos_sub
    "docker" -> R.string.home_widget_docker_sub
    else -> R.string.home_widget_vms_sub
}

/**
 * Start (PLAN 117, Entwürfe 02–04): oben das Seitenmenü, der Knopf mit dem
 * Namen des NAS (1.02: öffnet die Auswahl von NAS und Knoten), Suche und Glocke; darunter Seiten mit
 * Widgets, Programmen und Ordnern, Punkte für die Seiten. Langes Drücken auf
 * eine freie Stelle oder ein Symbol: Bearbeiten. Das Dock zeichnet die App.
 *
 * [prefs]: was gezeigt wird (die eigene Einrichtung oder die des Webs).
 * [draft]: die Einrichtung im Bearbeiten (null: nicht im Bearbeiten);
 * [onDraft] ändert sie, [onDone] beendet das Bearbeiten und speichert.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: HomeState,
    prefs: PhonePrefs,
    draft: PhonePrefs?,
    onDraft: (PhonePrefs?) -> Unit,
    onDone: (PhonePrefs) -> Unit,
    onProgram: (Program) -> Unit,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    /** Name oben im Knopf; [node]: gewählter Cluster-Knoten („titan · nas-keller“), sonst null. */
    title: String = state.hostname,
    node: String? = null,
    /** Knopf mit dem Namen: Auswahl von NAS und Cluster-Knoten (ohne: das Seitenmenü). */
    onNas: (() -> Unit)? = null,
    /** Punkt am Seitenmenü (eine neue Version der App ist da, 1.02). */
    menuBadge: Boolean = false,
    /** Hinweis unter der Leiste (neue Version), null: keiner. */
    banner: (@Composable () -> Unit)? = null,
    /** Stand der Foto-Sicherung in Worten für Zustand und Widget; null: kommt später. */
    photos: String? = null,
    onPhotos: (() -> Unit)? = null,
    /** Widgets, die das Konto hinzufügen darf (Container und VMs nur mit Recht und Dienst). */
    widgetsAllowed: List<String> = HOME_WIDGET_KINDS,
    /** Wackeln im Bearbeiten (aus bei „Animationen entfernen“: gestrichelter Rahmen wie im Web). */
    wiggle: Boolean = true,
    /** Zähler: bei jeder Änderung zurück auf die erste Seite (zweites Tippen auf „Start“). */
    firstPage: Int = 0,
    /**
     * Ob sich die Einrichtung ändern lässt: erst, wenn sie vom NAS gelesen ist
     * und die Programme des Kontos bekannt sind (sonst schriebe „Fertig“ eine
     * Anordnung ohne die Programme über die des Kontos).
     */
    canEdit: Boolean = true,
) {
    val c = Titan.colors
    val programs = remember(state.programs) { homePrograms(state.programs) }
    val keys = remember(programs) { programs.map { programKey(it.id) } }
    val byKey = remember(programs) { programs.associateBy { programKey(it.id) } }
    val editing = draft != null
    val scope = rememberCoroutineScope()
    var askTakeOver by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var folderOpen by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val notLoaded = stringResource(R.string.home_not_loaded)

    val grid = rememberGridState()
    Box(modifier.fillMaxSize().wallpaper(c.dark)) {
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { grid.root = it }) {
            val labelHeight = with(density) { TitanType.appLabel.lineHeight.toDp() } * 2
            val rowHeight = 60.dp + 7.dp + labelHeight + 18.dp
            // Zellen einer Seite: vier Spalten wie im Web, so viele Zeilen wie in die Höhe passen (oben und unten abgezogen).
            val rows = maxOf(2, ((maxHeight - 140.dp) / rowHeight).toInt())
            val cells = PHONE_COLUMNS * rows
            val shownLayout = remember(prefs, keys, cells) { phoneLayout(prefs, keys, cells) }
            // Im Bearbeiten die Seiten wie gespeichert (auch leere neue Seiten), ohne Umbruch: nichts springt beim Ziehen.
            val pages: List<List<String>> = if (draft != null) editPages(draft, keys) else shownLayout.pages
            val folders = if (draft != null) phoneLayout(draft, keys, 10_000).folders + draft.folders.filter { f -> f.apps.none { it in keys } } else shownLayout.folders
            val current = draft ?: prefs
            val pageCount = rememberUpdatedState(pages.size)
            val pager = rememberPagerState { pageCount.value }
            SyncGrid(grid, draft, pages, stringResource(R.string.home_folder_default)) { onDraft(it) }
            LaunchedEffect(firstPage) { if (firstPage > 0) pager.animateScrollToPage(0) }
            fun startEdit() {
                if (editing) return
                if (!canEdit) {
                    android.widget.Toast.makeText(context, notLoaded, android.widget.Toast.LENGTH_LONG).show()
                    return
                }
                if (prefs.useWeb) {
                    askTakeOver = true
                } else {
                    onDraft(ownLayout(prefs, shownLayout, keys))
                }
            }
            fun finish() {
                val d = draft ?: return
                onDone(dropEmptyPages(d))
            }
            if (editing) BackHandler { finish() }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.statusBarsPadding()) {
                    if (editing) {
                        EditBar(onAdd = { adding = true }, onDone = ::finish)
                    } else {
                        HomeTopBar(title, node, state.problem != HomeProblem.Offline, onMenu, onNas ?: onMenu, onSearch, onNotifications, menuBadge)
                    }
                }
                if (!editing) banner?.invoke()
                state.problem?.let {
                    Problem(
                        stringResource(
                            when (it) {
                                HomeProblem.Offline -> R.string.home_offline
                                HomeProblem.NotReady -> R.string.home_not_ready
                                HomeProblem.Locked -> R.string.home_locked
                                HomeProblem.Certificate -> R.string.home_certificate
                            },
                        ),
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                HorizontalPager(
                    pager,
                    Modifier.weight(1f).fillMaxWidth(),
                    // Im Bearbeiten wechselt man die Seite über die Punkte oder beim Ziehen am Rand; alle Seiten bleiben dabei bestehen.
                    userScrollEnabled = !editing,
                    beyondViewportPageCount = if (editing) pages.size else 0,
                    key = { it },
                ) { index ->
                    val page = pages.getOrElse(index) { emptyList() }
                    PageContent(
                        page = page,
                        index = index,
                        prefs = current,
                        folders = folders,
                        byKey = byKey,
                        state = state,
                        photos = photos,
                        editing = editing,
                        wiggle = wiggle,
                        grid = grid,
                        onLongPress = ::startEdit,
                        onOpen = { key ->
                            when {
                                key.startsWith("folder:") -> folderOpen = key.removePrefix("folder:")
                                else -> byKey[key]?.let(onProgram)
                            }
                        },
                        onWidget = { kind ->
                            when (kind) {
                                "photos" -> onPhotos?.invoke()
                                "volumes" -> programs.firstOrNull { it.id == "storage" }?.let(onProgram)
                                "docker" -> programs.firstOrNull { it.id == "docker" }?.let(onProgram)
                                "vms" -> programs.firstOrNull { it.id == "vms" }?.let(onProgram)
                            }
                        },
                        onPhotos = onPhotos,
                        onSheet = { sheet = it },
                        onChange = { d -> onDraft(d) },
                        draft = draft,
                        pageCount = pages.size,
                        pager = pager,
                        scope = scope,
                    )
                }
                PageDots(pages.size, pager.currentPage, editing, onPage = { scope.launch { pager.animateScrollToPage(it) } }) {
                    val d = draft ?: return@PageDots
                    onDraft(addPage(d))
                    scope.launch { pager.animateScrollToPage(d.pages.size) }
                }
            }
            DragOverlay(grid, byKey, folders)
            // Bearbeiten: Blätter und Rückfragen.
            sheet?.let { key ->
                val d = draft
                if (d == null) {
                    sheet = null
                } else {
                    ItemSheet(key, d, pages, folders, byKey, onChange = { onDraft(it) }, onDismiss = { sheet = null }, onOpenFolder = { folderOpen = it })
                }
            }
            folderOpen?.let { id ->
                val folder = folders.firstOrNull { it.id == id } ?: current.folders.firstOrNull { it.id == id }
                if (folder == null) {
                    folderOpen = null
                } else {
                    FolderSheet(
                        folder = folder,
                        byKey = byKey,
                        editing = editing,
                        onOpen = { p -> folderOpen = null; onProgram(p) },
                        onChange = { change -> draft?.let { onDraft(change(it)) } },
                        onDismiss = { folderOpen = null },
                    )
                }
            }
            if (adding && draft != null) {
                AddSheet(
                    draft = draft,
                    programs = programs,
                    keys = keys,
                    widgetsAllowed = widgetsAllowed,
                    page = pager.currentPage,
                    onChange = { onDraft(it) },
                    onDismiss = { adding = false },
                )
            }
            if (askTakeOver) {
                ConfirmDialog(
                    title = stringResource(R.string.home_web_title),
                    text = stringResource(R.string.home_web_text),
                    actions = listOf(
                        DialogAction(stringResource(R.string.home_web_do)) {
                            askTakeOver = false
                            onDraft(ownLayout(prefs.copy(useWeb = false), shownLayout, keys).copy(useWeb = false))
                        },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { askTakeOver = false },
                    ),
                    onDismiss = { askTakeOver = false },
                )
            }
        }
    }
}

/** Die Seiten im Bearbeiten: wie gespeichert, nur was die App zeigen kann. */
internal fun editPages(draft: PhonePrefs, keys: List<String>): List<List<String>> {
    val known = keys.toSet()
    val folders = draft.folders.filter { f -> f.apps.any { it in known } }.map { "folder:" + it.id }.toSet()
    val widgets = draft.widgets.filter { it.kind in HOME_WIDGET_KINDS }.map { "widget:" + it.id }.toSet()
    return draft.pages.map { page -> page.filter { it in known || it in folders || it in widgets } }.ifEmpty { listOf(emptyList()) }
}

@Composable
private fun HomeTopBar(title: String, node: String?, online: Boolean, onMenu: () -> Unit, onNas: () -> Unit, onSearch: () -> Unit, onNotifications: () -> Unit, menuBadge: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            IconButton(TitanIcons.menu, stringResource(if (menuBadge) R.string.home_side_menu_update else R.string.home_side_menu), onMenu, size = 24.dp)
            // Neue Version: ein Punkt am Menü (der Name sagt es TalkBack).
            if (menuBadge) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(10.dp).clip(CircleShape).background(Titan.colors.accent).border(1.5.dp, Titan.colors.bg, CircleShape))
        }
        Box(Modifier.weight(1f)) { NasPill(nasPillText(title, node), online, onNas) }
        IconButton(TitanIcons.search, stringResource(UiR.string.ui_search), onSearch)
        IconButton(TitanIcons.bell, stringResource(R.string.home_notifications), onNotifications)
    }
}

/** „titan“ bzw. mit gewähltem Cluster-Knoten „titan · nas-keller“ (der Knoten nur, wenn er nicht das NAS selbst ist). */
fun nasPillText(title: String, node: String?): String {
    val n = node?.trim().orEmpty()
    return if (n.isEmpty() || n == title || title.endsWith(" · $n")) title else "$title · $n"
}

/**
 * Knopf mit dem Namen des verbundenen NAS (1.02, wie der Umschalter im Web:
 * Punkt, Name, Pfeil, Rahmen in der Akzentfarbe). Antippen öffnet die Auswahl
 * (NAS, Cluster-Knoten, NAS hinzufügen). Der Name bricht nie mitten im Wort
 * und wird nie gekürzt: erst eine Stufe kleinere Schrift, dann Umbruch an
 * Wortgrenzen (bei Namen ohne Leerzeichen nach „-“ oder „.“).
 */
@Composable
internal fun NasPill(text: String, online: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val label = stringResource(R.string.home_nas_switch, text)
    val state = stringResource(if (online) R.string.home_nas_online else R.string.home_nas_offline)
    val big = TitanType.rowTitle
    val small = TitanType.body.copy(fontWeight = TitanType.rowTitle.fontWeight)
    val shape = RoundedCornerShape(22.dp)
    BoxWithConstraints(modifier) {
        // Platz für den Namen: Knopf ohne Punkt, Abstände und Pfeil.
        val room = with(LocalDensity.current) { (maxWidth - 64.dp).toPx() }
        val widest = rememberWidestWord(listOf(text), big)
        val style = if (widest <= room) big else small
        Row(
            Modifier.clip(shape)
                .background(c.accent.copy(alpha = .14f))
                .border(1.dp, c.accent.copy(alpha = .6f), shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = state
                }
                .heightIn(min = 44.dp)
                .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Erreichbar: voller Punkt; nicht erreichbar: Ring (nie nur Farbe).
            Box(
                Modifier.size(9.dp).clip(CircleShape)
                    .then(if (online) Modifier.background(c.okInk) else Modifier.border(1.5.dp, c.muted, CircleShape)),
            )
            Text(io.github.ra5on.titanos.ui.breakWords(text), style = style, color = c.text, modifier = Modifier.weight(1f, fill = false))
            Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EditBar(onAdd: () -> Unit, onDone: () -> Unit) {
    val hint = stringResource(R.string.home_editing)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp).semantics { contentDescription = hint },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitanButton(stringResource(R.string.home_edit_add), onAdd, icon = TitanIcons.plus)
        Spacer(Modifier.weight(1f))
        TitanButton(stringResource(R.string.home_edit_done), onDone, kind = ButtonKind.Primary)
    }
}

/**
 * Punkte der Seiten: der gewählte ist größer (nie nur Farbe, Regel 7 im Web).
 * Im Bearbeiten ein „+“ für eine neue Seite. Jeder Punkt ist ein Knopf.
 */
@Composable
private fun PageDots(count: Int, current: Int, editing: Boolean, onPage: (Int) -> Unit, onNew: () -> Unit) {
    val c = Titan.colors
    if (count <= 1 && !editing) {
        Spacer(Modifier.size(12.dp))
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            val on = i == current
            val label = stringResource(R.string.home_page_of, i + 1, count)
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Tab) { onPage(i) }
                    .semantics { contentDescription = label; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (on) 10.dp else 6.dp).clip(CircleShape).background(if (on) c.text else c.muted.copy(alpha = .7f)))
            }
        }
        if (editing) {
            val label = stringResource(R.string.home_page_new)
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onNew).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, c.muted, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(TitanIcons.plus, null, tint = c.text, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** Zustandskarte (Widget „Zustand“, Entwurf 02): Befund, Laufzeit, Speicher und Foto-Sicherung. */
@Composable
internal fun StatusCard(state: HomeState, photos: String?, onPhotos: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(16.dp)) {
        val volumes = state.volumes
        val problems = state.volumesWithProblems
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!state.loading) StateBadge(ok = problems == 0 && state.problem == null)
            Column(Modifier.weight(1f)) {
                val title = when {
                    state.loading -> stringResource(R.string.home_loading)
                    problems > 0 || state.problem != null -> stringResource(R.string.home_attention)
                    else -> stringResource(R.string.home_all_good)
                }
                Text(title, style = TitanType.state, color = c.text)
                val parts = buildList {
                    when {
                        volumes == null -> Unit
                        volumes.isEmpty() -> add(stringResource(R.string.home_no_volumes))
                        problems > 0 -> add(pluralStringResource(R.plurals.home_volumes_attention, problems, problems))
                        else -> add(stringResource(R.string.home_all_good_text))
                    }
                    state.uptimeSeconds?.let { add(uptime(it)) }
                }
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = TitanType.body, color = c.muted)
            }
        }
        val main = state.mainVolume
        val storage = main?.let {
            val (used, total) = Format.bytesOf(it.usedBytes, it.sizeBytes)
            Triple(
                stringResource(R.string.home_storage_of, used, total),
                stringResource(R.string.home_storage_sub, it.name, it.level.uppercase().replace("RAID", "RAID ")),
                it.usedBytes.toFloat() / it.sizeBytes,
            )
        }
        val photosTitle = stringResource(R.string.home_photos_title)
        val photosSub = photos ?: stringResource(R.string.home_photos_later)
        val titleWord = rememberWidestWord(listOfNotNull(storage?.first, photosTitle), TitanType.body.copy(fontWeight = TitanType.rowTitle.fontWeight))
        val subWord = rememberWidestWord(listOfNotNull(storage?.second, photosSub), TitanType.small)
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            // Zwei Felder nebeneinander nur, wenn jedes Wort in eine Hälfte passt; sonst untereinander.
            val half = with(density) { ((maxWidth - 10.dp) / 2 - 24.dp).toPx() }
            val side = storage == null || maxOf(titleWord, subWord) <= half
            val photosClick = if (onPhotos != null) Modifier.clickable(role = Role.Button, onClick = onPhotos) else Modifier
            if (side) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    storage?.let { (t, s, f) -> Tile(t, s, f, Modifier.weight(1f)) }
                    // Stand der Foto-Sicherung in Worten („12 418 gesichert“, „4 Aufnahmen warten“).
                    Tile(photosTitle, photosSub, null, Modifier.weight(1f).then(photosClick))
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    storage?.let { (t, s, f) -> Tile(t, s, f, Modifier.fillMaxWidth()) }
                    Tile(photosTitle, photosSub, null, Modifier.fillMaxWidth().then(photosClick))
                }
            }
        }
    }
}

@Composable
private fun Tile(title: String, sub: String, fraction: Float?, modifier: Modifier) {
    val c = Titan.colors
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, style = TitanType.body.copy(fontWeight = TitanType.rowTitle.fontWeight), color = c.text)
        Text(sub, style = TitanType.small, color = c.muted)
        if (fraction != null) Bar(fraction, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun uptime(seconds: Long): String {
    val days = (seconds / 86_400).toInt()
    val hours = (seconds / 3_600).toInt()
    return when {
        days >= 1 -> pluralStringResource(R.plurals.home_up_days, days, days)
        hours >= 1 -> pluralStringResource(R.plurals.home_up_hours, hours, hours)
        else -> stringResource(R.string.home_up_minutes)
    }
}

/**
 * Höchstens vier Spalten wie im Web (breitere Fenster: bis [max]); weniger,
 * bis das breiteste Wort hineinpasst (mindestens eine). Namen brechen nur
 * zwischen ganzen Wörtern um (Inhaber 21.11.2026: „Ganze Wörter“).
 */
internal fun programColumns(width: Float, gap: Float, widestWord: Float, max: Int = 4): Int {
    for (n in max downTo 2) {
        val column = (width - gap * (n - 1)) / n
        if (widestWord <= column) return n
    }
    return 1
}

/** Langes Drücken auf eine freie Stelle der Seite: Bearbeiten (im Bearbeiten nichts). */
internal fun Modifier.longPressFree(enabled: Boolean, onLongPress: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }

/** Fläche eines Widgets: wie die Zustandskarte. */
@Composable
internal fun WidgetCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.fillMaxWidth().widthIn(min = 0.dp).clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(16.dp)) { content() }
}
