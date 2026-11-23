package io.github.ra5on.titanos.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.focusable
import androidx.compose.runtime.key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.github.ra5on.titanos.ui.currentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Die Seiten im Reiter „Dateien“; der Stapel liegt im Modell und übersteht den Reiterwechsel. */
sealed interface FilesRoute {
    data object Roots : FilesRoute
    data class Folder(val location: Location) : FilesRoute
    data object Transfers : FilesRoute
    data object Offline : FilesRoute
    data class OfflineFolder(val pin: OfflinePin) : FilesRoute
    data class Viewer(val location: Location, val files: List<FileEntry>, val index: Int) : FilesRoute
    data class Editor(val location: Location, val name: String) : FilesRoute
    data class Office(val location: Location, val name: String?, val app: WebApp) : FilesRoute
    data object TrashRoots : FilesRoute
    data class Trash(val root: FileRoot) : FilesRoute
    data class Search(val root: FileRoot?, val path: String) : FilesRoute
    data object Jobs : FilesRoute
    data class Usage(val location: Location) : FilesRoute
    data class Duplicates(val location: Location) : FilesRoute
    data class BatchRename(val location: Location, val names: List<String>) : FilesRoute
    data class Extract(val location: Location, val entry: FileEntry) : FilesRoute
    data object Settings : FilesRoute
    data object Usb : FilesRoute
    data class Partitions(val disk: Disk) : FilesRoute
    data class Compare(val location: Location) : FilesRoute
}

/** Ein Blatt oder Dialog über der Ordneransicht. */
private sealed interface FolderOverlay {
    data object Add : FolderOverlay
    data object Menu : FolderOverlay
    data object Sort : FolderOverlay
    data class Entry(val entries: List<FileEntry>) : FolderOverlay
    data class Rename(val entry: FileEntry) : FolderOverlay
    data class Delete(val entries: List<FileEntry>) : FolderOverlay
    data object NewFolder : FolderOverlay
    data object NewFile : FolderOverlay
    data object Url : FolderOverlay
    data class Paste(val inspection: Inspection) : FolderOverlay
    data class Props(val name: String, val path: String) : FolderOverlay
    data class Docker(val name: String) : FolderOverlay
}

/**
 * Der Reiter „Dateien“ (wie routes/files im Web): Freigaben, Ordner,
 * Betrachter, Editor, Office, Papierkorb, Suche, Aufträge und Werkzeuge.
 */
@Composable
fun FilesTab(model: FilesModel, nav: @Composable () -> Unit) {
    val route = model.route
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        NasImages.trim(context, FilesSettings.get(context).cacheMb * 1024L * 1024)
        model.loadPrefs()
        model.loadOffice()
        model.watchJobs()
    }
    model.notice?.let { n ->
        val text = stringResource(n.text)
        LaunchedEffect(n) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            model.notice = null
        }
    }
    val chooser = stringResource(R.string.files_share_chooser)
    val openWith = stringResource(R.string.off_open_with)
    when (route) {
        FilesRoute.Roots -> {
            LaunchedEffect(Unit) { model.loadRoots() }
            FilesHomeScreen(
                model.roots,
                running = model.transfers.running,
                finished = model.transfers.finished,
                offlineSummary = offlineSummary(model.offline),
                onRoot = { r -> model.openFolder(Location(r)) },
                onTransfers = { model.push(FilesRoute.Transfers) },
                onOffline = { model.push(FilesRoute.Offline) },
                onSearch = { model.push(FilesRoute.Search(model.roots.roots?.firstOrNull(), "")) },
                onTrash = { model.push(FilesRoute.TrashRoots) },
                onJobs = { model.push(FilesRoute.Jobs) },
                bottomBar = nav,
                favorites = model.prefs.favorites,
                onFavorite = { f -> model.roots.roots?.firstOrNull { it.id == f.root }?.let { model.openFolder(Location(it, f.path)) } },
                jobsRunning = model.nasJobs.count { it.running },
                onSettings = { model.push(FilesRoute.Settings) },
                onUsb = if (model.isAdmin) { { model.push(FilesRoute.Usb) } } else null,
            )
        }
        FilesRoute.Transfers -> {
            BackHandler { model.pop() }
            TransfersScreen(model.transfers.items, onBack = { model.pop() }, onCancel = model::cancel, onRetry = model::retry, onClear = { model.transfers.clearFinished() })
        }
        FilesRoute.Offline -> {
            BackHandler { model.pop() }
            val notYet = stringResource(R.string.off_open_failed)
            OfflineScreen(
                model.offline,
                onBack = { model.pop() },
                onPin = { p ->
                    if (p.dir) model.push(FilesRoute.OfflineFolder(p))
                    else if (!model.openOffline(p.root, p.path, openWith)) Toast.makeText(context, notYet, Toast.LENGTH_SHORT).show()
                },
                onRemove = { p -> model.offline.unpin(p.root, p.path) },
                onSyncNow = model::syncOfflineNow,
                onWifiOnly = model::setOfflineWifiOnly,
                bottomBar = nav,
            )
        }
        is FilesRoute.OfflineFolder -> {
            BackHandler { model.pop() }
            val notYet = stringResource(R.string.off_open_failed)
            OfflineFolderScreen(
                route.pin,
                model.offline.copiesFor(route.pin),
                onBack = { model.pop() },
                onOpen = { cp -> if (!model.openOffline(cp.root, cp.path, openWith)) Toast.makeText(context, notYet, Toast.LENGTH_SHORT).show() },
                bottomBar = nav,
            )
        }
        is FilesRoute.Folder -> FolderRoute(model, route.location, nav, chooser, openWith)
        is FilesRoute.Viewer -> {
            BackHandler { model.pop() }
            val loc = route.location
            var deleting by remember { mutableStateOf<FileEntry?>(null) }
            Box(Modifier.fillMaxSize()) {
                ViewerScreen(
                    model.files,
                    loc,
                    route.files,
                    route.index,
                    viewerActions(model, loc, chooser, openWith) { deleting = it },
                    onBack = { model.pop() },
                    onPage = { i -> model.replaceTop(route.copy(index = i)) },
                )
                deleting?.let { e ->
                    DeleteDialog(e.name, model.hasTrash(loc, e.name), onDo = {
                        deleting = null
                        scope.launch { if (model.delete(loc, e)) model.pop() }
                    }, onDismiss = { deleting = null })
                }
                model.showProperties?.let { (n, p) -> ViewerProps(model, loc, n, p) { model.showProperties = null } }
            }
        }
        is FilesRoute.Editor -> {
            val api = model.files
            if (api == null) model.pop() else EditorScreen(api, route.location, route.name) { scope.launch { model.reload() }; model.pop() }
        }
        is FilesRoute.Office -> OfficeScreen(model, route.app, route.location, route.name, onClose = { scope.launch { model.reload() }; model.pop() })
        FilesRoute.TrashRoots -> {
            BackHandler { model.pop() }
            LaunchedEffect(Unit) { model.loadRoots() }
            TrashRootsScreen(model.roots.roots.orEmpty(), onRoot = { model.push(FilesRoute.Trash(it)) }, onBack = { model.pop() })
        }
        is FilesRoute.Trash -> TrashRoute(model, route.root)
        is FilesRoute.Search -> SearchRoute(model, route)
        FilesRoute.Jobs -> {
            BackHandler { model.pop() }
            LaunchedEffect(Unit) { model.watchJobs(force = true) }
            JobsScreen(
                model.nasJobs, model.actionError,
                onBack = { model.pop() },
                onCancel = { j -> scope.launch { model.cancelJob(j.id) } },
                onPause = { j, p -> scope.launch { model.pauseJob(j.id, p) } },
                onRetry = { j -> scope.launch { model.retryJob(j.id) } },
            )
        }
        is FilesRoute.Usage -> {
            BackHandler { model.pop() }
            UsageScreen(model, route.location, onOpen = { path, dir ->
                val target = if (dir) path else path.substringBeforeLast('/', "")
                model.openFolder(Location(route.location.root, target))
            }, onBack = { model.pop() })
        }
        is FilesRoute.Duplicates -> {
            BackHandler { model.pop() }
            var confirm by remember { mutableStateOf<List<String>?>(null) }
            Box {
                DuplicatesScreen(model, route.location, onDelete = { confirm = it }, onBack = { model.pop() })
                confirm?.let { paths ->
                    val loc = Location(route.location.root)
                    val trash = paths.all { model.hasTrash(loc, it) }
                    DeleteDialog(paths.first(), trash, onDo = {
                        confirm = null
                        scope.launch { model.deletePaths(route.location.root, paths, trash); model.pop() }
                    }, onDismiss = { confirm = null }, count = paths.size)
                }
            }
        }
        is FilesRoute.BatchRename -> {
            BackHandler { model.pop() }
            BatchRenameScreen(model, route.location, route.names, onDone = { model.pop() }, onBack = { model.pop() })
        }
        FilesRoute.Usb -> {
            BackHandler { model.pop() }
            UsbScreen(model, onOpen = { r -> model.openFolder(Location(r)) }, onPartitions = { model.push(FilesRoute.Partitions(it)) }, onBack = { model.pop() })
        }
        is FilesRoute.Partitions -> {
            BackHandler { model.pop() }
            PartitionsScreen(model, route.disk, onBack = { model.pop() })
        }
        is FilesRoute.Compare -> {
            BackHandler { model.pop() }
            CompareScreen(model, route.location, onOpen = { model.openFolder(it) }, onBack = { model.pop() })
        }
        FilesRoute.Settings -> {
            BackHandler { model.pop() }
            val ctx = LocalContext.current
            var cache by remember { mutableStateOf(FilesSettings.cacheBytes(ctx)) }
            FilesSettingsScreen(
                FilesSettings.get(ctx), cache,
                onChange = { FilesSettings.save(ctx, it); NasImages.trim(ctx, it.cacheMb * 1024L * 1024) ; cache = FilesSettings.cacheBytes(ctx) },
                onClearCache = { NasImages.trim(ctx, 0); cache = FilesSettings.cacheBytes(ctx) },
                onBack = { model.pop() },
            )
        }
        is FilesRoute.Extract -> {
            BackHandler { model.pop() }
            ExtractScreen(model, route.location, route.entry, onStarted = { model.pop() }, onBack = { model.pop() })
        }
    }
}

@Composable
private fun BoxScope.ViewerProps(model: FilesModel, location: Location, name: String, path: String, onDismiss: () -> Unit) {
    var props by remember { mutableStateOf<Properties?>(null) }
    var error by remember { mutableStateOf<FilesError?>(null) }
    var hashing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path) {
        try {
            props = model.files?.properties(location.root.id, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.errorOf(e)
        }
    }
    PropertiesSheet(
        name, (listOf(location.root.name) + path.split('/').filter { it.isNotEmpty() }).joinToString(" › "), props, error, hashing,
        onChecksum = {
            hashing = true
            scope.launch {
                try {
                    props = model.files?.properties(location.root.id, path, checksum = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = model.errorOf(e)
                } finally {
                    hashing = false
                }
            }
        },
        onDismiss = onDismiss,
    )
}

/** Aktionen des Betrachters für eine Datei in [loc]. */
private fun viewerActions(model: FilesModel, loc: Location, chooser: String, openWith: String, onDelete: (FileEntry) -> Unit) = ViewerActions(
    share = { model.share(loc, it, chooser) },
    openWith = { model.openWith(loc, it, openWith) },
    download = { model.saveToDownloads(loc, it) },
    info = { model.showProperties = it.name to loc.full(it.name) },
    edit = if (loc.root.writable) { e -> model.push(FilesRoute.Editor(loc, e.name)) } else null,
    delete = if (loc.root.writable) onDelete else null,
    officeName = { e -> model.officeFor(e.name)?.name },
    office = { e -> model.officeFor(e.name)?.let { model.push(FilesRoute.Office(loc, e.name, it)) } },
    window = { e -> FilesWindow.open(model.appContext, loc, e.name) },
)

@Composable
private fun FolderRoute(model: FilesModel, location: Location, nav: @Composable () -> Unit, chooser: String, openWith: String) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(location) { if (model.folder?.location != location) model.open(location) }
    val state = model.folder?.takeIf { it.location == location } ?: FolderState(location)
    val up = {
        model.selection = emptySet()
        if (location.segments.isEmpty()) model.pop() else model.replaceTop(FilesRoute.Folder(location.upTo(location.segments.size - 1)))
    }
    BackHandler { if (model.selection.isNotEmpty()) model.selection = emptySet() else up() }
    var overlay by remember { mutableStateOf<FolderOverlay?>(null) }
    var text by remember { mutableStateOf("") }
    var pasteMode by remember { mutableStateOf("rename") }
    var url by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf<UrlProbe?>(null) }
    var probing by remember { mutableStateOf(false) }
    var props by remember { mutableStateOf<Properties?>(null) }
    var propsError by remember { mutableStateOf<FilesError?>(null) }
    var hashing by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { model.upload(location, it) } }
    val defaultFile = stringResource(R.string.add_file_default)
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { model.uploadTree(location, it) } }
    val entries = model.sorted(location, state.entries.orEmpty())
    val width = currentWidth()
    val fsNow = FilesSettings.get(context)
    val twoPanes = width.twoPanes && fsNow.previewPane
    val sidebar = width.sidebar && fsNow.sidebar
    var cursor by remember(location) { mutableStateOf<String?>(null) }
    LaunchedEffect(location) { model.previewing = null }
    val (view, sort, desc) = model.prefs.forFolder(location.root.id, location.path)
    val uploads = model.transfers.items.filter {
        it.root == location.root.id && it.dir == location.path && it.direction == Direction.Upload &&
            (it.state == TransferState.Running || it.state == TransferState.Waiting)
    }
    val selectedEntries = entries.filter { it.name in model.selection }
    fun openEntry(e: FileEntry) {
        if (e.dir) {
            model.selection = emptySet()
            model.replaceTop(FilesRoute.Folder(location.child(e.name)))
            return
        }
        val fs = FilesSettings.get(context)
        val kind = Kinds.of(e.name)
        val office = when {
            kind == FileKind.Office && fs.officeDefault -> model.officeFor(e.name)
            kind == FileKind.Pdf && fs.pdfInOffice -> model.officeFor(e.name)
            else -> null
        }
        if (office != null) {
            model.push(FilesRoute.Office(location, e.name, office))
        } else if (fs.textOpensEditor && Kinds.editable(e.name) && location.root.writable) {
            model.push(FilesRoute.Editor(location, e.name))
        } else if (twoPanes) {
            model.previewing = e.name
        } else {
            val files = entries.filter { !it.dir }
            model.push(FilesRoute.Viewer(location, files, files.indexOfFirst { it.name == e.name }.coerceAtLeast(0)))
        }
    }
    fun viewFull(e: FileEntry) {
        val files = entries.filter { !it.dir }
        model.push(FilesRoute.Viewer(location, files, files.indexOfFirst { it.name == e.name }.coerceAtLeast(0)))
    }
    fun toggle(e: FileEntry) { model.selection = if (e.name in model.selection) model.selection - e.name else model.selection + e.name }
    model.showProperties?.let { (n, p) ->
        LaunchedEffect(p) {
            overlay = FolderOverlay.Props(n, p)
            model.showProperties = null
        }
    }
    LaunchedEffect((overlay as? FolderOverlay.Props)?.path) {
        val o = overlay as? FolderOverlay.Props ?: return@LaunchedEffect
        props = null
        propsError = null
        try {
            props = model.files?.properties(location.root.id, o.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            propsError = model.errorOf(e)
        }
    }
    val ui = FolderUi(
        state = state,
        entries = entries,
        view = view,
        sort = model.localSort ?: sort,
        descending = desc,
        selected = model.selection,
        uploads = uploads,
        clipboard = model.clipboard,
        jobs = model.nasJobs,
        undo = if (fsNow.showUndo) model.undo else null,
        offlinePaths = model.offline.pins.filter { it.root == location.root.id }.map { it.path }.toSet(),
        cursor = cursor,
        previewing = model.previewing,
    )
    val callbacks = FolderCallbacks(
        back = up,
        crumb = { i -> model.selection = emptySet(); model.replaceTop(FilesRoute.Folder(location.upTo(i))) },
        open = ::openEntry,
        select = ::toggle,
        entryMenu = { e -> overlay = FolderOverlay.Entry(listOf(e)) },
        add = { overlay = FolderOverlay.Add },
        menu = { overlay = FolderOverlay.Menu },
        search = { model.push(FilesRoute.Search(location.root, location.path)) },
        sortMenu = { overlay = FolderOverlay.Sort },
        clearSelection = { model.selection = emptySet() },
        selectAll = { model.selection = entries.map { it.name }.toSet() },
        shareSelected = { model.shareMany(location, selectedEntries, chooser); model.selection = emptySet() },
        selectionMenu = { overlay = FolderOverlay.Entry(selectedEntries) },
        paste = {
            scope.launch {
                val i = model.inspectPaste(location)
                if (i != null) {
                    pasteMode = "rename"
                    if (i.conflicts.isEmpty() && i.identical + i.different == 0L) model.paste(location, "rename") else overlay = FolderOverlay.Paste(i)
                }
            }
        },
        dropClipboard = { model.clipboard = null },
        jobs = { model.push(FilesRoute.Jobs) },
        undo = { scope.launch { model.undoLast() } },
        dismissUndo = { model.undo = null },
        secondary = fsNow.rightClickMenu,
        drag = if (!fsNow.dragAndDrop) null else { e -> DragDrop.transfer(context, location, if (e.name in model.selection) selectedEntries else listOf(e)) },
        drop = { event, target ->
            val into = target?.let { location.child(it.name) } ?: location
            val p = DragDrop.payload(event)
            if (p != null) {
                scope.launch { model.dropNas(into, p) }
                true
            } else if (into.root.writable) {
                val uris = DragDrop.uris(context, event)
                uris.forEach { model.upload(into, it) }
                uris.isNotEmpty()
            } else {
                false
            }
        },
    )
    val dismiss = { overlay = null }
    // Tastatur wie im Web (keyboard.ts): Pfeile, Enter, Leertaste, Kopieren/Einfügen, Entf, F2, Strg+F, Strg+Umschalt+N.
    val focus = remember { FocusRequester() }
    LaunchedEffect(location) { runCatching { focus.requestFocus() } }
    fun onKey(k: FolderKey?): Boolean {
        if (k == null || overlay != null) return false
        val idx = entries.indexOfFirst { it.name == cursor }
        val current = entries.getOrNull(idx)
        val targets = selectedEntries.ifEmpty { listOfNotNull(current) }
        when (k) {
            FolderKey.Down -> cursor = entries.getOrNull((idx + 1).coerceAtMost(entries.lastIndex))?.name
            FolderKey.Up -> cursor = entries.getOrNull((idx - 1).coerceAtLeast(0))?.name
            FolderKey.Home -> cursor = entries.firstOrNull()?.name
            FolderKey.End -> cursor = entries.lastOrNull()?.name
            FolderKey.Open -> current?.let(::openEntry) ?: return false
            FolderKey.Preview -> current?.let { e ->
                if (e.dir) openEntry(e)
                else if (twoPanes) model.previewing = if (model.previewing == e.name) null else e.name
                else viewFull(e)
            } ?: return false
            FolderKey.Copy -> if (targets.isNotEmpty()) model.copyToClipboard(location, targets, move = false) else return false
            FolderKey.Cut -> if (targets.isNotEmpty() && location.root.writable) model.copyToClipboard(location, targets, move = true) else return false
            FolderKey.Paste -> if (model.clipboard != null) callbacks.paste() else return false
            FolderKey.Delete -> if (targets.isNotEmpty() && location.root.writable) {
                if (!fsNow.confirmTrash && targets.all { model.hasTrash(location, it.name) }) scope.launch { model.deleteMany(location, targets) }
                else overlay = FolderOverlay.Delete(targets)
            } else return false
            FolderKey.Rename -> current?.takeIf { location.root.writable }?.let { e -> text = e.name; model.actionError = null; overlay = FolderOverlay.Rename(e) } ?: return false
            FolderKey.Search -> callbacks.search()
            FolderKey.NewFolder -> if (location.root.writable) { text = ""; model.actionError = null; overlay = FolderOverlay.NewFolder } else return false
            FolderKey.SelectAll -> callbacks.selectAll()
            FolderKey.Back -> up()
            FolderKey.Escape -> when {
                model.selection.isNotEmpty() -> model.selection = emptySet()
                model.previewing != null -> model.previewing = null
                else -> return false
            }
            FolderKey.Menu -> overlay = if (targets.isNotEmpty()) FolderOverlay.Entry(targets) else FolderOverlay.Menu
            FolderKey.Refresh -> scope.launch { model.reload() }
        }
        return true
    }
    val previewFiles = entries.filter { !it.dir }
    val previewIndex = previewFiles.indexOfFirst { it.name == model.previewing }
    Column(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { onKey(folderKey(it)) }) {
    Row(Modifier.weight(1f)) {
    if (sidebar) {
        FilesSidebar(
            model.roots.roots.orEmpty(), model.prefs.favorites, location.root.id,
            onRoot = { r -> model.openFolder(Location(r)) },
            onFavorite = { f -> model.roots.roots?.firstOrNull { it.id == f.root }?.let { model.openFolder(Location(it, f.path)) } },
            onTrash = { model.push(FilesRoute.Trash(location.root)) },
            onJobs = { model.push(FilesRoute.Jobs) },
            onTransfers = { model.push(FilesRoute.Transfers) },
            onOffline = { model.push(FilesRoute.Offline) },
            onSearch = callbacks.search,
        )
    }
    Box(Modifier.weight(1f)) {
    FolderScreen(ui, model.files, callbacks, bottomBar = { if (!twoPanes) nav() }) {
        when (val o = overlay) {
            null -> Unit
            FolderOverlay.Add -> AddSheet(
                canPaste = model.clipboard != null,
                onUpload = { pick.launch(arrayOf("*/*")) },
                onCamera = null,
                onFolder = { text = ""; model.actionError = null; overlay = FolderOverlay.NewFolder },
                onFile = { text = defaultFile; model.actionError = null; overlay = FolderOverlay.NewFile },
                onUrl = { url = ""; probe = null; model.actionError = null; overlay = FolderOverlay.Url },
                onFolderUpload = { pickTree.launch(null) },
                onPaste = callbacks.paste,
                onDismiss = dismiss,
            )
            FolderOverlay.Menu -> FolderMenu(
                location.segments.lastOrNull() ?: location.root.name,
                favorite = model.prefs.isFavorite(location.root.id, location.path),
                offline = model.offline.isPinned(location.root.id, location.path),
                hasTrash = location.root.recycle,
                onFavorite = { model.updatePrefs(model.prefs.toggleFavorite(location.root.id, location.path)) },
                onOffline = { on ->
                    if (on) model.offline.pin(location.root.id, location.root.name, location.path, dir = true) else model.offline.unpin(location.root.id, location.path)
                    if (on) model.syncOfflineNow()
                },
                onSelectAll = callbacks.selectAll,
                onProperties = { overlay = FolderOverlay.Props(location.segments.lastOrNull() ?: location.root.name, location.path) },
                onTrash = { model.push(FilesRoute.Trash(location.root)) },
                onJobs = { model.push(FilesRoute.Jobs) },
                onUsage = { model.push(FilesRoute.Usage(location)) },
                onDuplicates = { model.push(FilesRoute.Duplicates(location)) },
                onReload = { scope.launch { model.reload() } },
                onDismiss = dismiss,
                onWindow = { FilesWindow.open(context, location) },
                onCompare = { model.push(FilesRoute.Compare(location)) },
            )
            FolderOverlay.Sort -> SortSheet(
                view, model.localSort ?: sort, desc, model.prefs.showHidden,
                remembered = model.prefs.folders.any { it.root == location.root.id && it.path == location.path },
                onChange = { v, s, d ->
                    model.localSort = if (s == SortBy.Type) SortBy.Type else null
                    model.updatePrefs(model.prefs.withView(location.root.id, location.path, v, s, d))
                },
                onHidden = { model.updatePrefs(model.prefs.copy(showHidden = it)) },
                onRemember = { model.updatePrefs(model.prefs.rememberFolder(location.root.id, location.path, it)) },
                onDismiss = dismiss,
            )
            is FolderOverlay.Entry -> {
                val list = o.entries
                val single = list.singleOrNull()
                val office = single?.takeIf { !it.dir }?.let { model.officeFor(it.name) }
                EntrySheet(
                    list,
                    withTrash = list.all { model.hasTrash(location, it.name) },
                    writable = location.root.writable,
                    offline = single?.let { model.isOffline(location, it) },
                    officeName = office?.name,
                    onOffline = { on -> single?.let { model.setOffline(location, it, on) } },
                    onOpen = single?.let { s -> { openEntry(s) } },
                    onOffice = { office?.let { model.push(FilesRoute.Office(location, single.name, it)) } },
                    onOpenWith = single?.takeIf { !it.dir }?.let { s -> { model.openWith(location, s, openWith) } },
                    onShare = { model.shareMany(location, list, chooser); model.selection = emptySet() },
                    onDownload = { model.saveManyToDownloads(location, list); model.selection = emptySet() },
                    onCopy = { model.copyToClipboard(location, list, move = false); model.selection = emptySet() },
                    onCut = { model.copyToClipboard(location, list, move = true); model.selection = emptySet() },
                    onRename = single?.let { s -> { text = s.name; model.actionError = null; overlay = FolderOverlay.Rename(s) } },
                    onBatchRename = if (list.size > 1) { { model.selection = emptySet(); model.push(FilesRoute.BatchRename(location, list.map { it.name })) } } else null,
                    onEdit = single?.takeIf { Kinds.editable(it.name) && location.root.writable }?.let { s -> { model.push(FilesRoute.Editor(location, s.name)) } },
                    onExtract = single?.takeIf { Kinds.of(it.name) == FileKind.Archive }?.let { s -> { model.push(FilesRoute.Extract(location, s)) } },
                    onProperties = single?.let { s -> { overlay = FolderOverlay.Props(s.name, location.full(s.name)) } },
                    onDelete = {
                        if (!fsNow.confirmTrash && list.all { model.hasTrash(location, it.name) }) {
                            overlay = null; model.selection = emptySet(); scope.launch { model.deleteMany(location, list) }
                        } else overlay = FolderOverlay.Delete(list)
                    },
                    onDismiss = { if (overlay == o) overlay = null },
                    onWindow = single?.let { s -> { if (s.dir) FilesWindow.open(context, location.child(s.name)) else FilesWindow.open(context, location, s.name) } },
                    onDocker = single?.takeIf { !it.dir && model.dockerFor(it.name) }?.let { s -> { overlay = FolderOverlay.Docker(s.name) } },
                )
            }
            is FolderOverlay.Rename -> RenameDialog(o.entry.name, text, { text = it }, model.actionError, onDo = {
                scope.launch { if (text.isNotBlank() && model.renameTo(location, o.entry, text)) overlay = null }
            }, onDismiss = dismiss)
            is FolderOverlay.Delete -> DeleteDialog(o.entries.first().name, o.entries.all { model.hasTrash(location, it.name) }, onDo = {
                overlay = null
                model.selection = emptySet()
                scope.launch { model.deleteMany(location, o.entries) }
            }, onDismiss = dismiss, count = o.entries.size)
            FolderOverlay.NewFolder -> NameDialog(
                stringResource(R.string.add_folder), stringResource(R.string.files_rename_label), text, { text = it }, model.actionError,
                stringResource(R.string.add_create), onDo = { scope.launch { if (text.isNotBlank() && model.mkdir(location, text)) overlay = null } }, onDismiss = dismiss,
            )
            FolderOverlay.NewFile -> NameDialog(
                stringResource(R.string.add_file), stringResource(R.string.files_rename_label), text, { text = it }, model.actionError,
                stringResource(R.string.add_create), note = stringResource(R.string.add_file_note),
                onDo = {
                    scope.launch {
                        if (text.isNotBlank() && model.createFile(location, text)) {
                            overlay = null
                            model.push(FilesRoute.Editor(location, text.trim()))
                        }
                    }
                },
                onDismiss = dismiss,
            )
            FolderOverlay.Url -> UrlDialog(
                url, { url = it; probe = null }, probe, text, { text = it }, probing, model.actionError,
                onCheck = {
                    if (url.isNotBlank() && !probing) {
                        probing = true
                        scope.launch {
                            probe = model.probeUrl(location, url)?.also { text = it.name }
                            probing = false
                        }
                    }
                },
                onDo = { scope.launch { if (text.isNotBlank() && model.downloadUrl(location, url, text, rename = probe?.exists == true)) overlay = null } },
                onDismiss = dismiss,
            )
            is FolderOverlay.Paste -> PasteDialog(
                o.inspection, model.clipboard?.move == true, pasteMode, { pasteMode = it },
                onDo = { overlay = null; scope.launch { model.paste(location, pasteMode) } },
                onDismiss = dismiss,
            )
            is FolderOverlay.Docker -> DockerOpenSheet(model, location, o.name, onDismiss = dismiss)
            is FolderOverlay.Props -> PropertiesSheet(
                o.name, (listOf(location.root.name) + o.path.split('/').filter { it.isNotEmpty() }).joinToString(" › "),
                props, propsError, hashing,
                onChecksum = {
                    hashing = true
                    scope.launch {
                        try {
                            props = model.files?.properties(location.root.id, o.path, checksum = true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            propsError = model.errorOf(e)
                        } finally {
                            hashing = false
                        }
                    }
                },
                onDismiss = dismiss,
            )
        }
    }
    }
    if (twoPanes && previewIndex >= 0) {
        Box(Modifier.weight(if (sidebar) 1.2f else 1f)) {
            key(location) {
                ViewerScreen(
                    model.files, location, previewFiles, previewIndex,
                    viewerActions(model, location, chooser, openWith) { e -> overlay = FolderOverlay.Delete(listOf(e)) },
                    onBack = { model.previewing = null },
                    onPage = { i -> previewFiles.getOrNull(i)?.let { model.previewing = it.name } },
                    embedded = true,
                )
            }
        }
    }
    }
    if (twoPanes) nav()
    }
}

@Composable
private fun TrashRoute(model: FilesModel, root: FileRoot) {
    val scope = rememberCoroutineScope()
    var ui by remember(root.id) { mutableStateOf(TrashUi(root)) }
    var confirm by remember { mutableStateOf<List<String>?>(null) }
    suspend fun load() {
        ui = try {
            ui.copy(entries = model.files!!.trash(root.id, model.userName.orEmpty()).sortedByDescending { it.modified }, error = null, selected = emptySet())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = model.errorOf(e)
            // Noch nie etwas gelöscht: der eigene Ordner im Papierkorb fehlt.
            if (err == FilesError.NotFound) ui.copy(entries = emptyList(), error = null) else ui.copy(error = err)
        }
    }
    LaunchedEffect(root.id) { load() }
    BackHandler { model.pop() }
    fun run(block: suspend () -> Unit) {
        ui = ui.copy(busy = true)
        scope.launch {
            try {
                block()
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui = ui.copy(error = model.errorOf(e))
            } finally {
                ui = ui.copy(busy = false)
            }
        }
    }
    TrashScreen(
        ui,
        onBack = { model.pop() },
        onToggle = { e -> ui = ui.copy(selected = if (e.path in ui.selected) ui.selected - e.path else ui.selected + e.path) },
        onRestore = { run { model.files!!.restore(root.id, ui.selected.toList()); model.notice = Notice(R.string.trash_restored) } },
        onDelete = { confirm = ui.selected.toList() },
        onEmpty = { confirm = ui.entries.orEmpty().map { it.path } },
        onSelectAll = { ui = ui.copy(selected = ui.entries.orEmpty().map { it.path }.toSet()) },
    ) {
        confirm?.let { paths ->
            androidx.compose.runtime.key(paths) {
                ConfirmDialog(
                    title = androidx.compose.ui.res.pluralStringResource(R.plurals.trash_delete_confirm, paths.size, paths.size),
                    text = stringResource(R.string.trash_delete_text),
                    actions = listOf(
                        DialogAction(stringResource(R.string.files_delete_forever), danger = true) {
                            confirm = null
                            run { paths.chunked(1000).forEach { model.files!!.deleteFromTrash(root.id, it) } }
                        },
                        DialogAction(stringResource(io.github.ra5on.titanos.ui.R.string.ui_cancel)) { confirm = null },
                    ),
                    onDismiss = { confirm = null },
                )
            }
        }
    }
}

@Composable
private fun SearchRoute(model: FilesModel, route: FilesRoute.Search) {
    val scope = rememberCoroutineScope()
    BackHandler { model.pop() }
    LaunchedEffect(Unit) { if (model.roots.roots == null) model.loadRoots() }
    var ui by remember { mutableStateOf(SearchUi(model.roots.roots.orEmpty(), route.root, route.path, showHidden = model.prefs.showHidden)) }
    LaunchedEffect(model.roots.roots) {
        val roots = model.roots.roots.orEmpty()
        ui = ui.copy(roots = roots, root = ui.root ?: roots.firstOrNull())
    }
    SearchScreen(
        ui,
        onChange = { ui = it },
        onSearch = {
            val root = ui.root ?: return@SearchScreen
            ui = ui.copy(running = true, error = null)
            scope.launch {
                ui = try {
                    val after = if (ui.days > 0) java.time.Instant.now().minus(java.time.Duration.ofDays(ui.days.toLong())).toString() else null
                    val r = model.files!!.search(
                        SearchRequest(
                            root.id, ui.path, ui.query.trim(), ui.content, ui.showHidden,
                            extension = ui.extension.ifBlank { null },
                            minSize = ui.minMb.toLongOrNull()?.times(1024 * 1024),
                            maxSize = ui.maxMb.toLongOrNull()?.times(1024 * 1024),
                            after = after,
                        ),
                    )
                    ui.copy(running = false, result = r)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ui.copy(running = false, error = model.errorOf(e))
                }
            }
        },
        onResult = { f ->
            val root = ui.root ?: return@SearchScreen
            if (f.dir) {
                model.openFolder(Location(root, f.path))
            } else {
                val dir = Location(root, f.path.substringBeforeLast('/', ""))
                val entry = FileEntry(f.name, false, f.size, f.modified)
                val office = if (Kinds.of(f.name) == FileKind.Office) model.officeFor(f.name) else null
                if (office != null) model.push(FilesRoute.Office(dir, f.name, office)) else model.push(FilesRoute.Viewer(dir, listOf(entry), 0))
            }
        },
        onBack = { model.pop() },
    )
}
