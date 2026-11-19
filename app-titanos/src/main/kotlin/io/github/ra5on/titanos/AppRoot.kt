package io.github.ra5on.titanos

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.ra5on.titanos.files.DeleteDialog
import io.github.ra5on.titanos.files.FileSheet
import io.github.ra5on.titanos.files.FolderSheet
import io.github.ra5on.titanos.files.FilesHomeScreen
import io.github.ra5on.titanos.files.FilesModel
import io.github.ra5on.titanos.files.FolderScreen
import io.github.ra5on.titanos.files.Location
import io.github.ra5on.titanos.files.RenameDialog
import io.github.ra5on.titanos.files.TransferState
import io.github.ra5on.titanos.files.TransfersScreen
import io.github.ra5on.titanos.home.HomeModel
import io.github.ra5on.titanos.home.HomeScreen
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.net.PairLink
import io.github.ra5on.titanos.net.ServerAddress
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
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.NavItem
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TopBar
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
    LaunchedEffect(link) {
        if (link != null) {
            pairingModel.startFromLink(link)
            step = SetupStep.Confirm
            onLinkTaken()
        }
    }
    when {
        pairing == null -> SetupFlow(graph, pairingModel, step) { step = it }
        permissionsPending -> PermissionsRoute(
            onNext = { permissionsPending = false; step = SetupStep.Welcome },
            photoPermissions = MediaAccess.PERMISSIONS,
            photosOn = { SettingsStore(context).load().enabled && MediaAccess.access(context) != Access.None },
            photosPartial = { MediaAccess.access(context) == Access.Partial },
            // Eingeschaltet wird hier; das Ziel wählt man danach unter Fotos.
            onPhotos = { on ->
                graph.photos?.update(SettingsStore(context).load().copy(enabled = on)) ?: SettingsStore(context).let { it.save(it.load().copy(enabled = on)) }
            },
        )
        else -> MainShell(graph)
    }
}

@Composable
private fun SetupFlow(graph: AppGraph, model: PairingModel, step: SetupStep, go: (SetupStep) -> Unit) {
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
        SetupStep.Welcome -> WelcomeScreen(
            onScan = {
                scan.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(scanPrompt)
                        .setBeepEnabled(false).setOrientationLocked(false),
                )
            },
            onManual = { go(SetupStep.Manual) },
            problem = when {
                invalidScan -> stringResource(PairR.string.pair_scan_invalid)
                graph.unpairedByNas -> stringResource(R.string.app_unpaired)
                else -> null
            },
        )
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

private sealed interface FilesRoute {
    data object Roots : FilesRoute
    data class Folder(val location: Location) : FilesRoute
    data object Transfers : FilesRoute
    data object Offline : FilesRoute
    data class OfflineFolder(val pin: io.github.ra5on.titanos.files.OfflinePin) : FilesRoute
}

@Composable
private fun MainShell(graph: AppGraph) {
    val context = LocalContext.current
    val later = stringResource(R.string.app_later_toast)
    val toastLater = { Toast.makeText(context, later, Toast.LENGTH_SHORT).show() }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var filesRoute by remember { mutableStateOf<FilesRoute>(FilesRoute.Roots) }
    val api = graph.api ?: return
    val files = graph.files ?: return
    val photos = graph.photos ?: return
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
    // Je Kopplung ein eigenes Modell, sonst behielte es nach neuem Koppeln die alte Verbindung.
    val home: HomeModel = viewModel(key = "home-" + graph.pairing?.device) { HomeModel(api, { graph.sessions.user?.role ?: "user" }, graph.pairing?.hostname.orEmpty()) }
    DisposableEffect(tab) {
        if (tab == Tab.Home) {
            home.start()
            photos.refresh()
        } else {
            home.stop()
        }
        onDispose { home.stop() }
    }
    // Innere Seiten (Ordner, Übertragungen) haben eigene Zurück-Handler, die zuerst greifen.
    if (tab != Tab.Home) BackHandler { tab = Tab.Home }
    var menu by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    when (tab) {
        Tab.Home -> Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                HomeScreen(
                    home.state,
                    onProgram = { p -> if (p.id == "shares") tab = Tab.Files else toastLater() },
                    onSearch = toastLater,
                    onNotifications = { tab = Tab.Notifications },
                    onMenu = { menu = true },
                    modifier = Modifier.weight(1f),
                    photos = homeTileText(photos.view),
                    onPhotos = { tab = Tab.Photos },
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
        Tab.Files -> FilesTab(files, filesRoute, { filesRoute = it }, nav, toastLater)
        Tab.Photos -> PhotosTab(photos, nav)
        Tab.Notifications -> Placeholder(stringResource(R.string.app_notifications), stringResource(R.string.app_notifications_text), nav)
    }
}

@Composable
private fun Placeholder(title: String, text: String, nav: @Composable () -> Unit) {
    Page(topBar = { Box(Modifier.statusBarsPadding()) { TopBar(title) } }, bottomBar = nav) {
        EmptyState(stringResource(UiR.string.ui_later_title), text, Modifier.padding(top = 80.dp))
    }
}

@Composable
private fun FilesTab(files: FilesModel, route: FilesRoute, go: (FilesRoute) -> Unit, nav: @Composable () -> Unit, later: () -> Unit) {
    val scope = rememberCoroutineScope()
    when (route) {
        FilesRoute.Roots -> {
            LaunchedEffect(Unit) { files.loadRoots() }
            FilesHomeScreen(
                files.roots,
                running = files.transfers.running,
                finished = files.transfers.finished,
                offlineSummary = io.github.ra5on.titanos.files.offlineSummary(files.offline),
                onRoot = { r -> Location(r).let { files.open(it); go(FilesRoute.Folder(it)) } },
                onTransfers = { go(FilesRoute.Transfers) },
                onOffline = { go(FilesRoute.Offline) },
                onLater = later,
                bottomBar = nav,
            )
        }
        FilesRoute.Transfers -> {
            BackHandler { go(FilesRoute.Roots) }
            TransfersScreen(
                files.transfers.items,
                onBack = { go(FilesRoute.Roots) },
                onCancel = files::cancel,
                onRetry = files::retry,
                onClear = { files.transfers.clearFinished() },
            )
        }
        FilesRoute.Offline -> {
            BackHandler { go(FilesRoute.Roots) }
            val context = LocalContext.current
            val notYet = stringResource(io.github.ra5on.titanos.files.R.string.off_open_failed)
            val openWith = stringResource(io.github.ra5on.titanos.files.R.string.off_open_with)
            io.github.ra5on.titanos.files.OfflineScreen(
                files.offline,
                onBack = { go(FilesRoute.Roots) },
                onPin = { p ->
                    if (p.dir) go(FilesRoute.OfflineFolder(p))
                    else if (!files.openOffline(p.root, p.path, openWith)) Toast.makeText(context, notYet, Toast.LENGTH_SHORT).show()
                },
                onRemove = { p -> files.offline.unpin(p.root, p.path) },
                onSyncNow = files::syncOfflineNow,
                onWifiOnly = files::setOfflineWifiOnly,
                bottomBar = nav,
            )
        }
        is FilesRoute.OfflineFolder -> {
            BackHandler { go(FilesRoute.Offline) }
            val context = LocalContext.current
            val notYet = stringResource(io.github.ra5on.titanos.files.R.string.off_open_failed)
            val openWith = stringResource(io.github.ra5on.titanos.files.R.string.off_open_with)
            io.github.ra5on.titanos.files.OfflineFolderScreen(
                route.pin,
                files.offline.copiesFor(route.pin),
                onBack = { go(FilesRoute.Offline) },
                onOpen = { cp -> if (!files.openOffline(cp.root, cp.path, openWith)) Toast.makeText(context, notYet, Toast.LENGTH_SHORT).show() },
                bottomBar = nav,
            )
        }
        is FilesRoute.Folder -> {
            val state = files.folder ?: return
            val location = state.location
            val up = {
                if (location.segments.isEmpty()) {
                    files.close(); go(FilesRoute.Roots)
                } else {
                    location.upTo(location.segments.size - 1).let { files.open(it); go(FilesRoute.Folder(it)) }
                }
            }
            BackHandler(onBack = up)
            var selected by remember { mutableStateOf<FileEntry?>(null) }
            var renaming by remember { mutableStateOf<FileEntry?>(null) }
            var renameTo by remember { mutableStateOf("") }
            var deleting by remember { mutableStateOf<FileEntry?>(null) }
            var folderMenu by remember { mutableStateOf(false) }
            val chooser = stringResource(io.github.ra5on.titanos.files.R.string.files_share_chooser)
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                uris.forEach { files.upload(location, it) }
            }
            val uploads = files.transfers.items.filter {
                it.root == location.root.id && it.dir == location.path && it.direction == io.github.ra5on.titanos.files.Direction.Upload &&
                    (it.state == TransferState.Running || it.state == TransferState.Waiting)
            }
            FolderScreen(
                state,
                uploads,
                onBack = up,
                onCrumb = { i -> location.upTo(i).let { files.open(it); go(FilesRoute.Folder(it)) } },
                onEntry = { e -> if (e.dir) location.child(e.name).let { files.open(it); go(FilesRoute.Folder(it)) } else selected = e },
                onUpload = { pick.launch(arrayOf("*/*")) },
                onLater = later,
                bottomBar = nav,
                onMore = { folderMenu = true },
            ) {
                if (folderMenu) {
                    val here = location.path
                    FolderSheet(
                        location.segments.lastOrNull() ?: location.root.name,
                        offline = files.offline.isPinned(location.root.id, here),
                        onOffline = { on ->
                            if (on) files.offline.pin(location.root.id, location.root.name, here, dir = true) else files.offline.unpin(location.root.id, here)
                            if (on) files.syncOfflineNow()
                        },
                        onDismiss = { folderMenu = false },
                    )
                }
                selected?.let { e ->
                    FileSheet(
                        e,
                        withTrash = files.hasTrash(location, e.name),
                        writable = location.root.writable,
                        offline = files.isOffline(location, e),
                        onOffline = { on -> files.setOffline(location, e, on) },
                        onShare = { selected = null; files.share(location, e, chooser) },
                        onDownload = { selected = null; files.saveToDownloads(location, e) },
                        onRename = { selected = null; files.actionError = null; renameTo = e.name; renaming = e },
                        onDelete = { selected = null; deleting = e },
                        onDismiss = { selected = null },
                    )
                }
                renaming?.let { e ->
                    RenameDialog(e.name, renameTo, { renameTo = it }, files.actionError, onDo = {
                        scope.launch { if (renameTo.isNotBlank() && files.rename(location, e, renameTo)) renaming = null }
                    }, onDismiss = { renaming = null })
                }
                deleting?.let { e ->
                    DeleteDialog(e.name, files.hasTrash(location, e.name), onDo = {
                        deleting = null
                        scope.launch { files.delete(location, e) }
                    }, onDismiss = { deleting = null })
                }
            }
        }
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
