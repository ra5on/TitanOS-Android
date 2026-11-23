package io.github.ra5on.titanos

import io.github.ra5on.titanos.ui.components.Snackbar
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ra5on.titanos.home.DOCK_FILES
import io.github.ra5on.titanos.home.DOCK_HOME
import io.github.ra5on.titanos.home.DOCK_NOTIFICATIONS
import io.github.ra5on.titanos.home.DOCK_PHOTOS
import io.github.ra5on.titanos.home.HOME_WIDGET_KINDS
import io.github.ra5on.titanos.home.HomeModel
import io.github.ra5on.titanos.home.HomeScreen
import io.github.ra5on.titanos.home.PhonePrefs
import io.github.ra5on.titanos.home.Program
import io.github.ra5on.titanos.home.WidgetItem
import io.github.ra5on.titanos.home.dockEntries
import io.github.ra5on.titanos.home.homePrograms
import io.github.ra5on.titanos.home.programIdOf
import io.github.ra5on.titanos.home.programKey
import io.github.ra5on.titanos.notify.Inbox
import io.github.ra5on.titanos.notify.NotifyRuntime
import io.github.ra5on.titanos.notify.NotifyScheduler
import io.github.ra5on.titanos.notify.NotifyTab
import io.github.ra5on.titanos.photos.PhotosTab
import io.github.ra5on.titanos.photos.homeTileText
import io.github.ra5on.titanos.photos.upToDate
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.DockBar
import io.github.ra5on.titanos.ui.components.DockItem
import io.github.ra5on.titanos.ui.components.wallpaper
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.widgets.redraw
import kotlinx.coroutines.launch
import io.github.ra5on.titanos.home.R as HomeR

internal enum class Tab { Home, Files, Photos, Notifications }

/**
 * Was in der Handy-Gestaltung offen ist. Alles an einer Stelle, damit „Start“
 * im Dock alles auf einmal schließt (Entwurf 06: „statt viermal Zurück“).
 */
internal class Shell(tab: Tab = Tab.Home, admin: String? = null, compute: String? = null) {
    var tab by mutableStateOf(tab)
    var admin by mutableStateOf(admin)
    var compute by mutableStateOf(compute)
    var watchPair by mutableStateOf(false)
    var drawer by mutableStateOf(false)

    /** Blatt des NAS-Knopfs auf der Startseite (1.02). */
    var nasSheet by mutableStateOf(false)
    var confirmUnpair by mutableStateOf(false)

    /** Startbildschirm im Bearbeiten (null: nicht). */
    var draft by mutableStateOf<PhonePrefs?>(null)

    /** Je Platz im Dock: ein zweites Tippen erhöht ihn, das Programm fängt an seinem Anfang neu an. */
    val epochs = mutableStateMapOf<String, Int>()
    var homeFirst by mutableIntStateOf(0)

    fun epoch(key: String): Int = epochs[key] ?: 0
    fun bump(key: String) {
        epochs[key] = epoch(key) + 1
    }

    companion object {
        val Saver = listSaver<Shell, String?>(
            save = { listOf(it.tab.name, it.admin, it.compute) },
            restore = { Shell(Tab.valueOf(it[0] ?: Tab.Home.name), it[1], it[2]) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MainShell(graph: AppGraph) {
    // Systemsteuerung: Darstellung des Kontos (auch am Arbeitsplatz).
    AppearanceSync(graph)
    // Arbeitsplatz wie im Web (feature-workspace, WorkspaceHost.kt): am Monitor und in breiten Fenstern.
    if (workspaceFirst(graph)) return
    val context = LocalContext.current
    val shell = rememberSaveable(saver = Shell.Saver) { Shell() }
    val api = graph.api ?: return
    val files = graph.files ?: return
    val photos = graph.photos ?: return
    val pairing = graph.pairing ?: return
    // Je Kopplung (und Cluster-Knoten) ein eigenes Modell, sonst behielte es nach neuem Koppeln die alte Verbindung.
    val home: HomeModel = viewModel(key = "home-" + graph.activeId + "-" + pairing.device + "-" + pairing.node) {
        val compute = io.github.ra5on.titanos.compute.ComputeAccess.forPairing(graph.shared, pairing, graph.sessions)
        HomeModel(api, { graph.sessions.user?.role ?: "user" }, pairing.nodeName.ifBlank { pairing.hostname }) { kind ->
            // Widgets „Container“ und „VMs“ mit denselben Listen wie am Arbeitsplatz.
            val data = io.github.ra5on.titanos.workspace.DeskData(compute)
            if (kind == "docker") {
                data.dockerList()
                data.docker?.map { WidgetItem(it.key, it.label, it.running) }
            } else {
                data.vmList()
                data.vms?.map { WidgetItem(it.key, it.label, it.running) }
            }
        }
    }
    val store = rememberHomeStore(graph)
    val shown = store.shown
    val programs = homePrograms(home.state.programs)
    val programKeys = programs.map { programKey(it.id) }
    val entries = dockEntries(shown.dock, listOf(DOCK_NOTIFICATIONS) + programKeys)

    // Widgets, deren Daten die Startseite holt (Container und VMs nur, wenn sie dort stehen).
    val kinds = (shell.draft ?: shown).widgets.map { it.kind }.toSet()
    LaunchedEffect(kinds) {
        if (home.widgetKinds != kinds) {
            home.widgetKinds = kinds
            home.refresh()
        }
    }

    fun saveDraft() {
        val d = shell.draft ?: return
        shell.draft = null
        saveHome(context, graph, store, io.github.ra5on.titanos.home.dropEmptyPages(d))
    }

    /** Schließt alles über der Startseite (Programme, Seiten der App, Seitenmenü). */
    fun closeAll() {
        saveDraft()
        shell.admin = null
        shell.compute = null
        shell.watchPair = false
        shell.drawer = false
        shell.nasSheet = false
        graph.openProgram = null
        graph.programLink = null
        graph.openNasList = false
        graph.openAbout = false
        graph.openVpn = false
        graph.openAppSettings = false
        graph.openHomeSettings = false
    }

    /** Dateien › Übertragungen (Seitenmenü, Mitteilung). */
    fun showTransfers() {
        closeAll()
        shell.tab = Tab.Files
        files.toRoot()
        files.push(io.github.ra5on.titanos.files.FilesRoute.Transfers)
    }
    LaunchedEffect(graph.openTransfers) {
        if (graph.openTransfers) {
            showTransfers()
            graph.openTransfers = false
        }
    }
    // Übertragungen › Lauf der Foto-Sicherung: der Reiter Fotos.
    LaunchedEffect(files) { files.openPhotos = { closeAll(); shell.tab = Tab.Photos } }

    val later = stringResource(R.string.app_later_toast)
    val toastLater = { Toast.makeText(context, later, Toast.LENGTH_SHORT).show() }

    /** Ein Programm wie von der Startseite öffnen. */
    fun openProgram(id: String) {
        // Zuletzt geöffnete Programme als Kurzbefehle am App-Symbol.
        if (id in ADMIN_PROGRAMS || id in PROGRAMS || id in COMPUTE_PROGRAMS) Shortcuts.opened(context, id)
        when (id) {
            "files" -> shell.tab = Tab.Files
            "photos" -> shell.tab = Tab.Photos
            // Freigaben, Speicher, Benutzer, Plugins, Werkzeuge (Ablage und Werkzeuge) wie im Web.
            in ADMIN_PROGRAMS -> shell.admin = id
            // Mail, Kalender, Kontakte (feature-mail, feature-pim) in voller Größe.
            in PROGRAMS -> graph.openProgram = id
            in COMPUTE_PROGRAMS -> shell.compute = id
            in SETTINGS_PROGRAMS -> graph.openProgram = id
            // Sicherung wie apps.ts: die Sicherungen der Systemsteuerung (wie am Arbeitsplatz).
            "backup" -> openLink(context, graph, "/settings/backups", toastLater)
            "security" -> openLink(context, graph, "/settings/firewall", toastLater)
            else -> toastLater()
        }
    }

    // Welcher Platz im Dock gerade gilt (für den Punkt und das zweite Tippen). Beim Tippen neu gelesen:
    // die Rückrufe des Docks können aus einer früheren Komposition stammen.
    fun activeKey(): String? {
        val pageOpen = graph.openNasList || graph.openAbout || graph.openVpn || graph.openAppSettings || graph.openHomeSettings || shell.watchPair
        return when {
            pageOpen -> null
            shell.admin != null -> shell.admin?.let(::programKey)
            shell.compute != null -> shell.compute?.let(::programKey)
            graph.openProgram != null -> graph.openProgram?.let { if (it in PROGRAMS || it in SETTINGS_PROGRAMS) programKey(it) else null }
            else -> when (shell.tab) {
                Tab.Home -> DOCK_HOME
                Tab.Files -> DOCK_FILES
                Tab.Photos -> DOCK_PHOTOS
                Tab.Notifications -> DOCK_NOTIFICATIONS
            }
        }
    }
    val active = activeKey()
    val onHome = active == DOCK_HOME

    fun onDock(key: String) {
        if (key == activeKey()) {
            // Zweites Tippen auf den gewählten Platz: an den Anfang dieses Programms.
            when (key) {
                DOCK_HOME -> shell.homeFirst++
                DOCK_FILES -> files.toRoot()
                else -> {
                    graph.programLink = null
                    shell.bump(key)
                }
            }
            return
        }
        closeAll()
        when (key) {
            DOCK_HOME -> {
                shell.tab = Tab.Home
                shell.homeFirst++
            }
            DOCK_FILES -> shell.tab = Tab.Files
            DOCK_PHOTOS -> shell.tab = Tab.Photos
            DOCK_NOTIFICATIONS -> shell.tab = Tab.Notifications
            else -> programIdOf(key)?.let { openProgram(it) }
        }
    }

    // Ungelesene Mitteilungen als Zahl am Platz „Mitteilungen“.
    val changes by NotifyRuntime.changes.collectAsStateWithLifecycle()
    val unread = remember(changes, graph.activeId) { runCatching { Inbox.unread(NotifyRuntime.inbox(context).snapshot()) }.getOrDefault(0) }
    val choices = dockChoices(programs).associateBy { it.key }
    val dockItems = entries.mapNotNull { k -> choices[k]?.let { DockItem(k, it.label, it.icon, if (k == DOCK_NOTIFICATIONS) unread else 0) } }
    val dock = @Composable { DockBar(dockItems, active, shown.dock.labels, ::onDock) }

    // Dock immer (ab Werk) auch in Programmen; aus: nur auf der Startseite, und wie in 1.00 auf den
    // ersten Seiten von Dateien, Fotos und Mitteilungen (die zeichnen es dann selbst).
    val ime = WindowInsets.isImeVisible
    val shellDock = !ime && (shown.dock.always || onHome)
    val tabNav: @Composable () -> Unit = if (shown.dock.always) ({}) else dock

    Box(Modifier.fillMaxSize().then(if (onHome) Modifier.wallpaper(Titan.colors.dark) else Modifier.background(Titan.colors.bg))) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().then(if (shellDock) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
                ShellContent(graph, shell, home, store, files, photos, tabNav, ::openProgram, ::closeAll, toastLater)
            }
            if (shellDock) dock()
        }
        if (shell.nasSheet) NasSheet(graph) { shell.nasSheet = false }
        if (shell.drawer) {
            SideMenu(
                graph,
                onDismiss = { shell.drawer = false },
                onSettings = { closeAll(); graph.openAppSettings = true },
                onTransfers = ::showTransfers,
                onAbout = { closeAll(); graph.openAbout = true },
            )
        }
        // Fertig geladen, während die App vorn ist (Entwurf 21): „In Downloads gespeichert“ mit Öffnen und Zeigen.
        files.saved?.let { t ->
            val noApp = stringResource(io.github.ra5on.titanos.files.R.string.tr_open_failed)
            Snackbar(
                text = t.name,
                sub = stringResource(io.github.ra5on.titanos.files.R.string.tr_saved),
                done = true,
                actions = listOf(
                    io.github.ra5on.titanos.ui.components.SnackAction(stringResource(io.github.ra5on.titanos.files.R.string.tr_open)) {
                        if (!files.openDownloaded(t)) Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
                    },
                    io.github.ra5on.titanos.ui.components.SnackAction(stringResource(io.github.ra5on.titanos.files.R.string.tr_show_short)) { files.showDownloads() },
                ),
                onDismiss = { if (files.saved == t) files.saved = null },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (shellDock) 88.dp else 16.dp),
            )
        }
        if (shell.confirmUnpair) {
            ConfirmDialog(
                title = stringResource(HomeR.string.home_menu_unpair),
                text = stringResource(HomeR.string.home_menu_unpair_text),
                actions = listOf(
                    DialogAction(stringResource(R.string.app_unpair_do), danger = true) { shell.confirmUnpair = false; graph.forget() },
                    DialogAction(stringResource(UiR.string.ui_cancel)) { shell.confirmUnpair = false },
                ),
                onDismiss = { shell.confirmUnpair = false },
            )
        }
    }
}

/** Der Inhalt über dem Dock: Startseite, Reiter, Programme und Seiten der App. */
@Composable
private fun ShellContent(
    graph: AppGraph,
    shell: Shell,
    home: HomeModel,
    store: io.github.ra5on.titanos.home.HomeLayoutStore,
    files: io.github.ra5on.titanos.files.FilesModel,
    photos: io.github.ra5on.titanos.photos.PhotosModel,
    nav: @Composable () -> Unit,
    openProgram: (String) -> Unit,
    closeAll: () -> Unit,
    toastLater: () -> Unit,
) {
    val context = LocalContext.current
    // Systemsteuerung: Reiter, die sie öffnet.
    LaunchedEffect(graph.openTab) {
        when (graph.openTab) {
            "files" -> { shell.tab = Tab.Files; files.push(io.github.ra5on.titanos.files.FilesRoute.Settings) }
            "notifications" -> shell.tab = Tab.Notifications
        }
        graph.openTab = null
    }
    // Widget „Fotos“: den Stand, den die Startseite zeigt, auch dort.
    val photoLine = homeTileText(photos.view)
    LaunchedEffect(photoLine, photos.view.upToDate, photos.view.settings.enabled) {
        val snap = io.github.ra5on.titanos.widgets.PhotosSnapshot(
            enabled = photos.view.settings.enabled,
            line = photoLine,
            upToDate = photos.view.upToDate,
            atMs = System.currentTimeMillis(),
        )
        if (io.github.ra5on.titanos.widgets.WidgetStore.savePhotos(context, snap)) {
            runCatching { io.github.ra5on.titanos.widgets.PhotosWidget().redraw(context) }
        }
    }
    // Tippen auf das Widget „Fotos“ öffnet den Reiter.
    LaunchedEffect(graph.openPhotos) {
        if (graph.openPhotos) {
            shell.tab = Tab.Photos
            graph.openPhotos = false
        }
    }
    // Ein NAS aus einem Link ist dazugekommen, aber nicht gewählt: sagen, wo man es findet.
    val quiet = graph.addedQuietly
    if (quiet != null) {
        val text = stringResource(R.string.nas_link_added, quiet)
        LaunchedEffect(quiet) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            graph.addedQuietly = null
        }
    }
    // Ein NAS hat die Kopplung beendet, andere sind noch da: sagen, welches.
    val ended = graph.endedByNas
    if (ended != null) {
        val text = stringResource(R.string.nas_ended, ended)
        LaunchedEffect(ended) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            graph.endedByNas = null
        }
    }
    val onHomeTab = shell.tab == Tab.Home
    DisposableEffect(onHomeTab) {
        if (onHomeTab) {
            home.start()
            photos.refresh()
        } else {
            home.stop()
        }
        onDispose { home.stop() }
    }
    // Beim Öffnen der App: neue Mitteilungen holen und die Dauerverbindung prüfen
    // (Android kann sie beendet haben; eine Berechtigung kam vielleicht später).
    // Dazu den Startbildschirm neu lesen (im Web oder auf einem zweiten Gerät geändert).
    val resumeScope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        NotifyScheduler.arm(context)
        val job = resumeScope.launch { NotifyRuntime.fetch(context) }
        val layout = resumeScope.launch { if (shell.draft == null) store.load() }
        onPauseOrDispose {
            job.cancel()
            layout.cancel()
        }
    }
    // Getippte Mitteilung: zur Liste. Eine getippte Mitteilung schließt, was offen ist; sonst läge ihr Ziel dahinter.
    val openList by NotifyRuntime.openList.collectAsStateWithLifecycle()
    LaunchedEffect(openList) {
        if (openList) {
            closeAll()
            shell.tab = Tab.Notifications
            NotifyRuntime.listOpened()
        }
    }
    // Mitteilung mit Link: das Programm an dieser Stelle öffnen, über den Router wie jeder Link (Links.kt).
    val openLink by NotifyRuntime.openLink.collectAsStateWithLifecycle()
    LaunchedEffect(openLink) {
        val link = openLink ?: return@LaunchedEffect
        closeAll()
        openLink(context, graph, link) { shell.tab = Tab.Notifications }
        NotifyRuntime.linkOpened()
    }
    // Ein Treffer aus „Schnell finden“: was offen ist, schließt; dann über den Router (Palette.kt, Links.kt).
    LaunchedEffect(graph.paletteLink) {
        val link = graph.paletteLink ?: return@LaunchedEffect
        graph.paletteLink = null
        closeAll()
        openPaletteEntry(context, graph, link, home = { shell.tab = Tab.Home }, later = toastLater)
    }
    // Die Programme der Startseite (nach Rolle und Diensten) kennt auch die Palette.
    LaunchedEffect(home.state.programs) { graph.paletteProgramIds = home.state.programs.map { it.id } }
    // Kurzbefehl „zuletzt geöffnet“: das Programm wie von der Startseite.
    LaunchedEffect(graph.openShortcut) {
        val id = graph.openShortcut ?: return@LaunchedEffect
        graph.openShortcut = null
        closeAll()
        openProgram(id)
    }
    // Innere Seiten (Ordner, Übertragungen) haben eigene Zurück-Handler, die zuerst greifen.
    if (shell.tab != Tab.Home) BackHandler { shell.tab = Tab.Home }
    // Ablage und Werkzeuge (Speicher, Freigaben, Benutzer, Plugins, Werkzeuge): eigene Seiten über der Leiste (Admin.kt).
    shell.admin?.let { id ->
        key(shell.epoch(programKey(id))) { AdminProgram(id, graph) { shell.admin = null } }
        return
    }
    // „Startbildschirm und Dock“ (Entwurf 07), aus den App-Einstellungen oder „Schnell finden“.
    if (graph.openHomeSettings) {
        HomeDockSettingsHost(
            graph,
            store,
            homePrograms(home.state.programs),
            onEdit = {
                graph.openHomeSettings = false
                graph.openAppSettings = false
                shell.tab = Tab.Home
                startEditing(shell, store, home)
            },
            onBack = { graph.openHomeSettings = false },
        )
        return
    }
    // „NAS und Konten“: eigene Seite über der Leiste.
    if (graph.openNasList) {
        BackHandler { graph.openNasList = false }
        NasListPage(
            graph,
            onBack = { graph.openNasList = false },
            onAdd = { graph.openNasList = false; graph.beginAdd() },
            onPhotos = { graph.openNasList = false; graph.openPhotos = true },
        )
        return
    }
    // „Über die App“ mit Selbst-Update; die Mitteilung „Version … ist da“ öffnet es direkt.
    if (graph.openAbout) {
        BackHandler { graph.openAbout = false }
        var nasVersion by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { nasVersion = runCatching { graph.api?.system()?.version }.getOrNull()?.takeIf { it.isNotBlank() } }
        io.github.ra5on.titanos.update.AboutScreen(nasVersion, onBack = { graph.openAbout = false })
        return
    }
    // Eigenes VPN (feature-vpn): Seite über der Leiste.
    if (graph.openVpn) {
        // Android erlaubt nur ein VPN gleichzeitig: es gehört einem NAS; mit mehreren NAS steht dabei, welchem.
        val several = graph.profiles.size > 1
        val owner = graph.vpnOwner()
        val access = remember { runCatching { io.github.ra5on.titanos.vpn.VpnRuntime.store.profile() != null }.getOrDefault(false) }
        BackHandler { graph.openVpn = false }
        io.github.ra5on.titanos.vpn.VpnPage(
            onBack = { graph.openVpn = false },
            ownerNas = if (several && access && owner != null && owner.id != graph.activeId) owner.name else "",
            forNas = if (several) (owner ?: graph.activeProfile)?.name.orEmpty() else "",
        )
        return
    }
    PimLive(graph)
    graph.openProgram?.let { id ->
        key(shell.epoch(programKey(id))) { ProgramHost(graph, id) { graph.openProgram = null; graph.programLink = null } }
        return
    }
    // „Uhr koppeln“ (Paket G): die Uhr zeigt sechs Ziffern, diese App bestätigt sie.
    if (shell.watchPair) {
        BackHandler { shell.watchPair = false }
        io.github.ra5on.titanos.pairing.WatchPairScreen(
            addresses = graph.pairing?.addresses?.map { it.server }.orEmpty(),
            nas = remember(graph.pairing) { graph.connection() },
            onBack = { shell.watchPair = false },
        )
        return
    }
    // App-Einstellungen: unter den Seiten, die sie öffnet (Zurück dort führt hierher zurück).
    if (graph.openAppSettings) {
        AppSettingsPage(
            onBack = { graph.openAppSettings = false },
            onHome = { graph.openHomeSettings = true },
            onNasList = { graph.openNasList = true },
            onPim = { graph.programLink = null; graph.openProgram = PIM_SYNC },
            onVpn = { graph.openVpn = true },
            onWorkspace = { graph.programLink = null; graph.openProgram = WORKSPACE_SETTINGS },
            onWatch = { shell.watchPair = true },
            onUnpair = { shell.confirmUnpair = true },
        )
        return
    }
    // Rechnen (Docker, VMs, LXC, NVR, Terminal): eigene Seiten über der Leiste (Compute.kt).
    shell.compute?.let { id ->
        BackHandler { shell.compute = null }
        key(shell.epoch(programKey(id))) { ComputeProgram(id, graph) { shell.compute = null } }
        return
    }
    when (shell.tab) {
        Tab.Home -> {
            val programs = homePrograms(home.state.programs)
            val allowed = HOME_WIDGET_KINDS.filter { kind ->
                when (kind) {
                    "volumes" -> programs.any { it.id == "storage" }
                    "docker" -> programs.any { it.id == "docker" }
                    "vms" -> programs.any { it.id == "vms" }
                    else -> true
                }
            }
            HomeScreen(
                home.state,
                store.shown,
                draft = shell.draft,
                onDraft = { shell.draft = it },
                onDone = { d ->
                    shell.draft = null
                    saveHome(context, graph, store, d)
                },
                onProgram = { p: Program -> openProgram(p.id) },
                onSearch = { graph.openPalette = true },
                onNotifications = { closeAll(); shell.tab = Tab.Notifications },
                onMenu = { shell.drawer = true },
                onNas = { shell.nasSheet = true },
                menuBadge = io.github.ra5on.titanos.update.UpdateHint.version != null,
                banner = io.github.ra5on.titanos.update.UpdateHint.version?.takeIf { io.github.ra5on.titanos.update.UpdateHint.banner(context) }?.let { v ->
                    {
                        UpdateBanner(
                            v,
                            onUpdate = {
                                io.github.ra5on.titanos.update.UpdateHint.install(context, graph.scope)
                                // Fortschritt und Rückfragen zeigt „Über die App“.
                                closeAll()
                                graph.openAbout = true
                            },
                            onLater = { io.github.ra5on.titanos.update.UpdateHint.dismiss(context) },
                        )
                    }
                },
                title = homeNasTitle(graph, home.state.hostname),
                node = graph.pairing?.nodeName?.takeIf { it.isNotBlank() && graph.pairing?.node?.isNotEmpty() == true },
                photos = homeTileText(photos.view),
                onPhotos = { shell.tab = Tab.Photos },
                widgetsAllowed = allowed,
                wiggle = !reduceMotion(context),
                firstPage = shell.homeFirst,
                canEdit = store.loaded && home.state.programs.isNotEmpty(),
            )
        }
        Tab.Files -> io.github.ra5on.titanos.files.FilesTab(files, nav)
        Tab.Photos -> key(shell.epoch(DOCK_PHOTOS)) { PhotosTab(photos, nav, files) }
        Tab.Notifications -> key(shell.epoch(DOCK_NOTIFICATIONS)) { NotifyTab(nav) }
    }
}

/** „Bearbeiten“ aus den App-Einstellungen: die Startseite im Bearbeiten (die Einrichtung des Webs fragt dort erst). */
private fun startEditing(shell: Shell, store: io.github.ra5on.titanos.home.HomeLayoutStore, home: HomeModel) {
    if (store.shown.useWeb) return
    val keys = homePrograms(home.state.programs).map { programKey(it.id) }
    val shown = io.github.ra5on.titanos.home.phoneLayout(store.own, keys, 10_000)
    shell.draft = io.github.ra5on.titanos.home.ownLayout(store.own, shown, keys)
}
