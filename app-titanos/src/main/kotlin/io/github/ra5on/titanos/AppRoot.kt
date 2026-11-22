package io.github.ra5on.titanos

import android.Manifest
import android.provider.CalendarContract
import android.provider.ContactsContract
import io.github.ra5on.titanos.pim.sync.PimSync
import io.github.ra5on.titanos.pim.sync.SyncStore
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import io.github.ra5on.titanos.widgets.redraw
import io.github.ra5on.titanos.photos.upToDate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.ra5on.titanos.files.FilesModel
import io.github.ra5on.titanos.home.HomeModel
import io.github.ra5on.titanos.home.HomeScreen
import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.net.PairLink
import io.github.ra5on.titanos.net.ServerAddress
import io.github.ra5on.titanos.notify.NotifyRuntime
import io.github.ra5on.titanos.notify.NotifyScheduler
import io.github.ra5on.titanos.notify.NotifyTab
import io.github.ra5on.titanos.notify.notificationsOn
import io.github.ra5on.titanos.notify.setNotificationsOn
import io.github.ra5on.titanos.pairing.ConfirmScreen
import io.github.ra5on.titanos.pairing.LOCAL_NETWORK
import io.github.ra5on.titanos.pairing.localNetworkGranted
import io.github.ra5on.titanos.pairing.ManualScreen
import io.github.ra5on.titanos.pairing.PairingModel
import io.github.ra5on.titanos.pairing.PermissionsRoute
import io.github.ra5on.titanos.pairing.WelcomeScreen
import io.github.ra5on.titanos.photos.Access
import io.github.ra5on.titanos.photos.MediaAccess
import io.github.ra5on.titanos.photos.PhotosTab
import io.github.ra5on.titanos.photos.SettingsStore
import io.github.ra5on.titanos.photos.homeTileText
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.BottomNav
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.NavItem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import kotlinx.coroutines.launch
import io.github.ra5on.titanos.home.R as HomeR
import io.github.ra5on.titanos.pairing.R as PairR
import io.github.ra5on.titanos.ui.theme.dangerInk

private enum class SetupStep { Welcome, Manual, Confirm }

/** Einrichten (ohne Kopplung) oder die App mit der Leiste unten. */
@Composable
fun AppRoot(graph: AppGraph, link: PairLink?, onLinkTaken: () -> Unit) {
    val pairing = graph.pairing
    var permissionsPending by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val pairingModel: PairingModel = viewModel {
        PairingModel(graph.key, graph.store, graph.shared, defaultDeviceName(context)) { p ->
            graph.paired(p)
            permissionsPending = true
        }
    }
    var step by rememberSaveable { mutableStateOf(SetupStep.Welcome) }
    // Ein Kopplungslink, während schon ein NAS gekoppelt ist: erst fragen, dann ein weiteres NAS hinzufügen.
    var addLink by remember { mutableStateOf<PairLink?>(null) }
    var askLink by remember { mutableStateOf<PairLink?>(null) }
    LaunchedEffect(link) {
        if (link != null) {
            if (graph.pairing == null) {
                pairingModel.startFromLink(link)
                step = SetupStep.Confirm
            } else {
                // Ein fremder Link koppelt nie still: deutlicher Hinweis vorher, das neue NAS wird nicht gewählt.
                askLink = link
            }
            onLinkTaken()
        }
    }
    val adding = graph.adding
    when {
        pairing == null -> SetupFlow(graph, pairingModel, step, { step = it })
        adding != null -> AddFlow(graph, adding, addLink, onLinkUsed = { addLink = null }) { if (adding.activate) permissionsPending = true }
        permissionsPending -> PermissionsRoute(
            onNext = { permissionsPending = false; step = SetupStep.Welcome },
            photoPermissions = MediaAccess.PERMISSIONS,
            // Je NAS eigene Einstellungen: die des gerade gekoppelten (gewählten) NAS.
            photosOn = { SettingsStore(context, graph.activeId).load().enabled && MediaAccess.access(context) != Access.None },
            photosPartial = { MediaAccess.access(context) == Access.Partial },
            // Eingeschaltet wird hier; das Ziel wählt man danach unter Fotos.
            onPhotos = { on ->
                graph.photos?.update(SettingsStore(context, graph.activeId).load().copy(enabled = on)) ?: SettingsStore(context, graph.activeId).let { it.save(it.load().copy(enabled = on)) }
            },
            // Mitteilungen ohne Google; danach die Bitte um die Akku-Ausnahme (TECHNIK §5).
            notifyOn = { notificationsOn(context) },
            onNotify = { on ->
                setNotificationsOn(context, on)
                if (on) NotifyScheduler.askBatteryExemption(context)
            },
            // Kontakte und Kalender über das Android-Konto der Kopplung (feature-pim).
            contactsPermissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
            calendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            pimOn = { kind -> SyncStore(context, graph.activeProfile?.let(graph::pimName)).load().let { if (kind == "contacts") it.contacts else it.calendars } },
            onPim = { kind, on ->
                val store = SyncStore(context, graph.activeProfile?.let(graph::pimName))
                store.save(store.load().let { if (kind == "contacts") it.copy(contacts = on) else it.copy(calendars = on) })
                PimSync.configure(context)
                if (on) PimSync.requestNow(context, manual = false, only = if (kind == "contacts") ContactsContract.AUTHORITY else CalendarContract.AUTHORITY)
            },
        )
        // Je NAS fängt die Oberfläche neu an (keine Seite behält die Verbindung des vorigen NAS);
        // die Sperre beim Öffnen liegt außen, damit ein Wechsel des NAS nicht neu sperrt.
        else -> AppLock {
            Box(Modifier.fillMaxSize()) {
                androidx.compose.runtime.key(graph.nasEpoch) { MainShell(graph) }
                askLink?.let { l ->
                    ConfirmDialog(
                        title = stringResource(R.string.nas_link_title),
                        text = stringResource(R.string.nas_link_text, l.addresses.joinToString(", ") { it.address.server }),
                        actions = listOf(
                            DialogAction(stringResource(R.string.nas_link_go)) {
                                askLink = null
                                if (graph.adding == null) graph.beginAdd(fromLink = true)
                                addLink = l
                            },
                            DialogAction(stringResource(UiR.string.ui_cancel)) { askLink = null },
                        ),
                        onDismiss = { askLink = null },
                    )
                }
            }
        }
    }
}

/** „NAS hinzufügen“: derselbe Ablauf wie beim ersten Koppeln, mit eigenem Schlüssel; Zurück bricht ab. */
@Composable
private fun AddFlow(graph: AppGraph, adding: io.github.ra5on.titanos.net.AddingPairingStore, link: PairLink?, onLinkUsed: () -> Unit, onPaired: () -> Unit) {
    val context = LocalContext.current
    val model: PairingModel = viewModel(key = "add-" + adding.id) {
        PairingModel(graph.addingKey(adding), adding, graph.shared, defaultDeviceName(context)) { _ ->
            graph.added(adding)
            onPaired()
        }
    }
    var step by rememberSaveable(adding.id) { mutableStateOf(SetupStep.Welcome) }
    LaunchedEffect(link) {
        if (link != null) {
            model.startFromLink(link)
            step = SetupStep.Confirm
            onLinkUsed()
        }
    }
    SetupFlow(graph, model, step, { step = it }, onCancelAdd = { graph.cancelAdd() })
}

@Composable
private fun SetupFlow(graph: AppGraph, model: PairingModel, step: SetupStep, go: (SetupStep) -> Unit, onCancelAdd: (() -> Unit)? = null) {
    val context = LocalContext.current
    var invalidScan by remember { mutableStateOf(false) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val link = PairLink.parse(text)
        if (link == null) {
            invalidScan = true
        } else {
            invalidScan = false
            model.startFromLink(link)
            go(SetupStep.Confirm)
        }
    }
    val scanPrompt = stringResource(PairR.string.pair_scan_prompt)
    // Ab Android 17 laufen Verbindungen ins Heimnetz ohne diese Berechtigung still ins Leere.
    var afterLocal by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askLocal = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterLocal?.invoke()
        afterLocal = null
    }
    fun withLocalNetwork(needed: Boolean, then: () -> Unit) {
        if (needed && !localNetworkGranted(context)) {
            afterLocal = then
            askLocal.launch(LOCAL_NETWORK)
        } else {
            then()
        }
    }
    when (step) {
        SetupStep.Welcome -> Box(Modifier.fillMaxSize()) {
            if (onCancelAdd != null) BackHandler(onBack = onCancelAdd)
            WelcomeScreen(
                onScan = {
                    scan.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(scanPrompt)
                            .setBeepEnabled(false).setOrientationLocked(false),
                    )
                },
                onManual = { go(SetupStep.Manual) },
                problem = when {
                    invalidScan -> stringResource(PairR.string.pair_scan_invalid)
                    graph.unpairedByNas && onCancelAdd == null -> stringResource(R.string.app_unpaired)
                    else -> null
                },
                title = if (onCancelAdd != null) stringResource(R.string.nas_add) else null,
                text = if (onCancelAdd != null) stringResource(R.string.nas_add_text) else null,
            )
            // Weiteres NAS: sichtbarer Weg zurück (nicht nur die Zurück-Geste).
            if (onCancelAdd != null) {
                Box(Modifier.statusBarsPadding().padding(start = 6.dp, top = 4.dp)) {
                    io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.back, stringResource(UiR.string.ui_back), onCancelAdd)
                }
            }
        }
        SetupStep.Manual -> {
            BackHandler { go(SetupStep.Welcome) }
            ManualScreen(
                state = model.manual,
                onAddress = model::setManualAddress,
                onProbe = {
                    val lan = ServerAddress.parse(model.manual.address)?.kind == AddressKind.LAN
                    withLocalNetwork(lan) { model.probe() }
                },
                onCode = model::setManualCode,
                onMismatch = model::rejectManual,
                onAccept = { if (model.acceptManual()) go(SetupStep.Confirm) },
                onBack = { go(SetupStep.Welcome) },
            )
        }
        SetupStep.Confirm -> {
            val back = { model.cancel(); go(SetupStep.Welcome) }
            BackHandler(onBack = back)
            LaunchedEffect(Unit) {
                val lan = model.confirm.addresses.any { it.kind == AddressKind.LAN }
                withLocalNetwork(lan) { model.recheck() }
            }
            ConfirmScreen(model.confirm, model::setDeviceName, model::pair, onCancel = back)
        }
    }
}

private enum class Tab { Home, Files, Photos, Notifications }

@Composable
private fun MainShell(graph: AppGraph) {
    // Systemsteuerung: Darstellung des Kontos (auch am Arbeitsplatz).
    AppearanceSync(graph)
    // Arbeitsplatz wie im Web (feature-workspace, WorkspaceHost.kt): am Monitor und in breiten Fenstern.
    if (workspaceFirst(graph)) return
    val context = LocalContext.current
    val later = stringResource(R.string.app_later_toast)
    val toastLater = { Toast.makeText(context, later, Toast.LENGTH_SHORT).show() }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val api = graph.api ?: return
    val files = graph.files ?: return
    val photos = graph.photos ?: return
    // Systemsteuerung: Reiter, die sie öffnet.
    LaunchedEffect(graph.openTab) {
        when (graph.openTab) {
            "files" -> { tab = Tab.Files; files.push(io.github.ra5on.titanos.files.FilesRoute.Settings) }
            "notifications" -> tab = Tab.Notifications
        }
        graph.openTab = null
    }
    // Widget „Fotos“: den Stand, den die Startseite zeigt, auch dort.
    val photoLine = io.github.ra5on.titanos.photos.homeTileText(photos.view)
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
            tab = Tab.Photos
            graph.openPhotos = false
        }
    }
    val nav = @Composable {
        BottomNav(
            listOf(
                NavItem(Tab.Home.name, stringResource(UiR.string.ui_nav_home), TitanIcons.home),
                NavItem(Tab.Files.name, stringResource(UiR.string.ui_nav_files), TitanIcons.folder),
                NavItem(Tab.Photos.name, stringResource(UiR.string.ui_nav_photos), TitanIcons.photo),
                NavItem(Tab.Notifications.name, stringResource(UiR.string.ui_nav_notifications), TitanIcons.bell),
            ),
            tab.name,
        ) { tab = Tab.valueOf(it) }
        Box(Modifier.navigationBarsPadding())
    }
    // Je Kopplung (und Cluster-Knoten) ein eigenes Modell, sonst behielte es nach neuem Koppeln die alte Verbindung.
    val home: HomeModel = viewModel(key = "home-" + graph.activeId + "-" + graph.pairing?.device + "-" + graph.pairing?.node) {
        HomeModel(api, { graph.sessions.user?.role ?: "user" }, graph.pairing?.let { it.nodeName.ifBlank { it.hostname } }.orEmpty())
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

    DisposableEffect(tab) {
        if (tab == Tab.Home) {
            home.start()
            photos.refresh()
        } else {
            home.stop()
        }
        onDispose { home.stop() }
    }
    // Beim Öffnen der App: neue Mitteilungen holen und die Dauerverbindung prüfen
    // (Android kann sie beendet haben; eine Berechtigung kam vielleicht später).
    val resumeScope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        NotifyScheduler.arm(context)
        val job = resumeScope.launch { NotifyRuntime.fetch(context) }
        onPauseOrDispose { job.cancel() }
    }
    // Programme über der Leiste: Ablage und Werkzeuge (Admin.kt) und Rechnen (Compute.kt).
    var admin by rememberSaveable { mutableStateOf<String?>(null) }
    var compute by rememberSaveable { mutableStateOf<String?>(null) }
    // Eine getippte Mitteilung schließt, was offen ist; sonst läge ihr Ziel dahinter.
    fun closePrograms() {
        admin = null
        compute = null
        graph.openProgram = null
        graph.programLink = null
    }
    // Getippte Mitteilung: zur Liste.
    val openList by NotifyRuntime.openList.collectAsStateWithLifecycle()
    LaunchedEffect(openList) {
        if (openList) {
            closePrograms()
            tab = Tab.Notifications
            NotifyRuntime.listOpened()
        }
    }
    // Mitteilung mit Link: das Programm an dieser Stelle öffnen, über den Router wie jeder Link (Links.kt).
    val openLink by NotifyRuntime.openLink.collectAsStateWithLifecycle()
    LaunchedEffect(openLink) {
        val link = openLink ?: return@LaunchedEffect
        closePrograms()
        openLink(context, graph, link) { tab = Tab.Notifications }
        NotifyRuntime.linkOpened()
    }
    // Innere Seiten (Ordner, Übertragungen) haben eigene Zurück-Handler, die zuerst greifen.
    if (tab != Tab.Home) BackHandler { tab = Tab.Home }
    // Ablage und Werkzeuge (Speicher, Freigaben, Benutzer, Plugins, Werkzeuge): eigene Seiten über der Leiste (Admin.kt).
    admin?.let { id ->
        AdminProgram(id, graph) { admin = null }
        return
    }
    var menu by remember { mutableStateOf(false) }
    var switcher by remember { mutableStateOf(false) }
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
        LaunchedEffect(Unit) { nasVersion = runCatching { api.system().version }.getOrNull()?.takeIf { it.isNotBlank() } }
        io.github.ra5on.titanos.update.AboutScreen(nasVersion, onBack = { graph.openAbout = false })
        return
    }
    // Eigenes VPN (feature-vpn): Seite über der Leiste.
    if (graph.openVpn) {
        // Android erlaubt nur ein VPN gleichzeitig: es gehört einem NAS; mit mehreren NAS steht dabei, welchem.
        val several = graph.profiles.size > 1
        val owner = graph.vpnOwner()
        val access = remember { runCatching { io.github.ra5on.titanos.vpn.VpnRuntime.store.profile() != null }.getOrDefault(false) }
        io.github.ra5on.titanos.vpn.VpnPage(
            onBack = { graph.openVpn = false },
            ownerNas = if (several && access && owner != null && owner.id != graph.activeId) owner.name else "",
            forNas = if (several) (owner ?: graph.activeProfile)?.name.orEmpty() else "",
        )
        return
    }
    PimLive(graph)
    graph.openProgram?.let { id ->
        ProgramHost(graph, id) { graph.openProgram = null; graph.programLink = null }
        return
    }
    var confirmUnpair by remember { mutableStateOf(false) }
    // „Uhr koppeln“ (Paket G): die Uhr zeigt sechs Ziffern, diese App bestätigt sie.
    var watchPair by remember { mutableStateOf(false) }
    if (watchPair) {
        BackHandler { watchPair = false }
        io.github.ra5on.titanos.pairing.WatchPairScreen(
            addresses = graph.pairing?.addresses?.map { it.server }.orEmpty(),
            nas = remember(graph.pairing) { graph.connection() },
            onBack = { watchPair = false },
        )
        return
    }
    // Rechnen (Docker, VMs, LXC, NVR, Terminal): eigene Seiten über der Leiste (Compute.kt).
    // Kurzbefehl „zuletzt geöffnet“: das Programm wie von der Startseite.
    LaunchedEffect(graph.openShortcut) {
        val id = graph.openShortcut ?: return@LaunchedEffect
        graph.openShortcut = null
        when (id) {
            in ADMIN_PROGRAMS -> admin = id
            in PROGRAMS -> graph.openProgram = id
            in COMPUTE_PROGRAMS -> compute = id
        }
    }
    compute?.let { id ->
        BackHandler { compute = null }
        ComputeProgram(id, graph) { compute = null }
        return
    }
    when (tab) {
        Tab.Home -> Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                HomeScreen(
                    home.state,
                    onProgram = { p ->
                        // Zuletzt geöffnete Programme als Kurzbefehle am App-Symbol.
                        if (p.id in ADMIN_PROGRAMS || p.id in PROGRAMS || p.id in COMPUTE_PROGRAMS) Shortcuts.opened(context, p.id)
                        when (p.id) {
                            // Freigaben, Speicher, Benutzer, Plugins, Werkzeuge (Ablage und Werkzeuge) wie im Web.
                            in ADMIN_PROGRAMS -> admin = p.id
                            // Mail, Kalender, Kontakte (feature-mail, feature-pim) in voller Größe.
                            in PROGRAMS -> graph.openProgram = p.id
                            in COMPUTE_PROGRAMS -> compute = p.id
                            in SETTINGS_PROGRAMS -> graph.openProgram = p.id
                            // Sicherung wie apps.ts: die Sicherungen der Systemsteuerung (wie am Arbeitsplatz).
                            "backup" -> openLink(context, graph, "/settings/backups", toastLater)
                            "security" -> openLink(context, graph, "/settings/firewall", toastLater)
                            else -> toastLater()
                        }
                    },
                    onSearch = toastLater,
                    onNotifications = { tab = Tab.Notifications },
                    onMenu = { menu = true },
                    modifier = Modifier.weight(1f),
                    photos = homeTileText(photos.view),
                    onPhotos = { tab = Tab.Photos },
                    onSwitchNas = { switcher = true },
                )
                nav()
            }
            if (menu) {
                Sheet(onDismiss = { menu = false }) {
                    Card(background = Titan.colors.bg) {
                        ListRow(
                            stringResource(HomeR.string.home_menu_about),
                            sub = stringResource(HomeR.string.home_about_text, BuildConfigInfo.versionName(context)),
                            icon = { RowIcon(TitanIcons.info) },
                            chevron = true,
                            onClick = { menu = false; graph.openAbout = true },
                        )
                        ListRow(
                            stringResource(R.string.nas_title),
                            sub = stringResource(R.string.nas_manage_sub),
                            icon = { RowIcon(TitanIcons.nodes) },
                            chevron = true,
                            divider = true,
                            onClick = { menu = false; graph.openNasList = true },
                        )
                        ListRow(
                            stringResource(io.github.ra5on.titanos.pim.R.string.sync_title),
                            sub = stringResource(io.github.ra5on.titanos.pim.R.string.sync_menu_sub),
                            icon = { RowIcon(TitanIcons.sync) },
                            chevron = true,
                            divider = true,
                            onClick = { menu = false; graph.openProgram = PIM_SYNC },
                        )
                        ListRow(
                            stringResource(io.github.ra5on.titanos.vpn.R.string.vpn_title),
                            sub = stringResource(io.github.ra5on.titanos.vpn.R.string.vpn_menu_sub),
                            icon = { RowIcon(TitanIcons.shield) },
                            chevron = true,
                            divider = true,
                            onClick = { menu = false; graph.openVpn = true },
                        )
                        ListRow(
                            stringResource(io.github.ra5on.titanos.workspace.R.string.ws_title),
                            sub = stringResource(io.github.ra5on.titanos.workspace.R.string.ws_mode_hint),
                            icon = { RowIcon(TitanIcons.grid) },
                            chevron = true,
                            divider = true,
                            onClick = { menu = false; graph.openProgram = WORKSPACE_SETTINGS },
                        )
                        ListRow(
                            stringResource(io.github.ra5on.titanos.pairing.R.string.pair_watch_title),
                            icon = { RowIcon(TitanIcons.monitor) },
                            chevron = true,
                            divider = true,
                            onClick = { menu = false; watchPair = true },
                        )
                        ListRow(
                            stringResource(HomeR.string.home_menu_unpair),
                            sub = stringResource(HomeR.string.home_menu_unpair_text),
                            icon = { RowIcon(TitanIcons.x, tint = Titan.colors.dangerInk) },
                            titleColor = Titan.colors.dangerInk,
                            divider = true,
                            onClick = { menu = false; confirmUnpair = true },
                        )
                    }
                }
            }
            if (switcher) {
                NasSwitcherSheet(
                    graph,
                    onDismiss = { switcher = false },
                    onAdd = { graph.beginAdd() },
                    onManage = { graph.openNasList = true },
                )
            }
            if (confirmUnpair) {
                ConfirmDialog(
                    title = stringResource(HomeR.string.home_menu_unpair),
                    text = stringResource(HomeR.string.home_menu_unpair_text),
                    actions = listOf(
                        DialogAction(stringResource(R.string.app_unpair_do), danger = true) { confirmUnpair = false; graph.forget() },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { confirmUnpair = false },
                    ),
                    onDismiss = { confirmUnpair = false },
                )
            }
        }
        Tab.Files -> io.github.ra5on.titanos.files.FilesTab(files, nav)
        Tab.Photos -> PhotosTab(photos, nav, files)
        Tab.Notifications -> NotifyTab(nav)
    }
}

/** Vorschlag für den Gerätenamen: der Name aus den Android-Einstellungen, sonst das Modell. */
private fun defaultDeviceName(context: android.content.Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

private object BuildConfigInfo {
    fun versionName(context: android.content.Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}
