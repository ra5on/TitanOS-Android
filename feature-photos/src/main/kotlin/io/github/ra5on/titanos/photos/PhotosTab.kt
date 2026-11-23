package io.github.ra5on.titanos.photos

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

private enum class Route { Main, Settings, Target, VideoTarget, Folders, Gallery }

/** Die Seiten der Foto-Sicherung im Reiter „Fotos“ (Entwürfe 30–33). */
@Composable
fun PhotosTab(model: PhotosModel, bottomBar: @Composable () -> Unit, files: io.github.ra5on.titanos.files.FilesModel? = null) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf(Route.Main) }
    var menu by remember { mutableStateOf(false) }
    var whenFor by remember { mutableStateOf<Kind?>(null) }
    // Fotoansicht am Handy (Entwurf 13): welche Aufnahme offen ist.
    var viewing by rememberSaveable { mutableStateOf<Long?>(null) }
    DisposableEffect(Unit) {
        model.start()
        onDispose { model.stop() }
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        model.accessChanged()
    }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        model.trashed(r.resultCode == Activity.RESULT_OK)
    }
    val allowAccess = {
        if (asked && model.view.access == Access.None) {
            // Android fragt nach einem Nein nicht mehr; dann in den Einstellungen der App.
            context.startActivity(appSettingsIntent(context.packageName))
        } else {
            // Bei Teilzugriff zeigt Android die Auswahl erneut, mit „Alle erlauben“.
            ask.launch(MediaAccess.PERMISSIONS)
        }
    }
    model.message?.let { id ->
        val text = stringResource(id)
        LaunchedEffect(id) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            model.message = null
        }
    }
    val open = viewing?.let { id -> model.view.tiles.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    if (route == Route.Main && open != null) {
        LocalPhotoViewer(model, model.view.tiles, open, onClose = { viewing = null }) { t, m -> MediaThumb(model, t, m) }
        return
    }
    when (route) {
        Route.Main -> PhotosScreen(
            model.view,
            onMenu = { menu = true },
            onSetUp = { route = if (model.settings.enabled) Route.Target else Route.Settings },
            onAllowAccess = allowAccess,
            thumb = { t, m -> MediaThumb(model, t, m) },
            bottomBar = bottomBar,
            onGallery = { route = Route.Gallery },
            onResume = model::resume,
            onTile = { viewing = it.id },
            onNow = model::backupNow,
            onSettings = { route = Route.Settings },
            reduceMotion = io.github.ra5on.titanos.ui.components.reduceMotion(context),
        ) {
            if (menu) {
                PhotosMenu(
                    onNow = { menu = false; model.backupNow() },
                    onFree = { menu = false; model.prepareFree() },
                    onSettings = { menu = false; route = Route.Settings },
                    onDismiss = { menu = false },
                    onGallery = if (model.settings.hasTarget) { { menu = false; route = Route.Gallery } } else null,
                )
            }
            model.free?.let { state ->
                FreeDialog(
                    state,
                    onDelete = { model.trashRequest()?.let { trash.launch(IntentSenderRequest.Builder(it).build()) } ?: model.trashed(false) },
                    onDismiss = { model.free = null },
                )
            }
        }
        Route.Settings -> {
            BackHandler { route = Route.Main }
            PhotosSettingsScreen(
                model.settings,
                model.folders,
                onChange = { next ->
                    model.update(next)
                    if (next.enabled && model.view.access == Access.None) ask.launch(MediaAccess.PERMISSIONS)
                },
                onTarget = { k -> route = if (k == Kind.Video) Route.VideoTarget else Route.Target },
                onFolders = { route = Route.Folders },
                onWhen = { whenFor = it },
                onBack = { route = Route.Main },
                device = PhotosRuntime.deviceName(),
            ) {
                whenFor?.let { kind ->
                    WhenSheet(kind, model.settings.whenOf(kind), onPick = { w ->
                        model.update(if (kind == Kind.Photo) model.settings.copy(photos = w) else model.settings.copy(videos = w))
                        whenFor = null
                    }, onDismiss = { whenFor = null })
                }
            }
        }
        Route.Target -> {
            BackHandler { route = Route.Settings }
            TargetRoute(model, Kind.Photo) { route = Route.Settings }
        }
        Route.VideoTarget -> {
            BackHandler { route = Route.Settings }
            TargetRoute(model, Kind.Video) { route = Route.Settings }
        }
        Route.Gallery -> {
            BackHandler { route = Route.Main }
            GalleryRoute(model, files) { route = Route.Main }
        }
        Route.Folders -> {
            BackHandler { route = Route.Settings }
            FoldersScreen(model.settings, model.folders, model::update) { route = Route.Settings }
        }
    }
}

@Composable
private fun TargetRoute(model: PhotosModel, kind: Kind, done: () -> Unit) {
    val s = model.settings
    val video = kind == Kind.Video
    var root by rememberSaveable { mutableStateOf(if (video) s.videoRoot.ifEmpty { s.root } else s.root) }
    var rootName by rememberSaveable { mutableStateOf(if (video) s.videoRootName.ifEmpty { s.rootName } else s.rootName) }
    val base = PhotosRuntime.deviceName().replace('/', ' ').trim()
    var dir by rememberSaveable { mutableStateOf(if (video) s.videoDir.ifEmpty { s.dir.ifEmpty { base } + " Videos" } else s.dir.ifEmpty { base }) }
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.loadRoots() }
    TargetScreen(
        model.roots, model.rootsFailed, root, dir, invalid,
        onRoot = { root = it.id; rootName = it.name },
        onDir = { dir = it; invalid = false },
        onSave = {
            if (!validDir(dir)) {
                invalid = true
            } else {
                val clean = dir.trim().trim('/')
                model.update(
                    if (video) model.settings.copy(videoOwnTarget = true, videoRoot = root, videoRootName = rootName, videoDir = clean)
                    else model.settings.copy(enabled = true, root = root, rootName = rootName, dir = clean),
                )
                done()
            }
        },
        onBack = done,
        title = stringResource(if (video) R.string.ps_target_videos else R.string.photos_target_title),
        note = stringResource(R.string.ps_target_note),
    )
}

// Schlüssel: Aufnahme und Stufe. Etwa ein Achtel dessen, was Android der App gibt, höchstens 64 MB (48 Kacheln à 384 px passen).
private val thumbs = object : LruCache<String, Bitmap>(minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/**
 * Vorschaubild aus dem MediaStore in der Größe der Kachel (1.02: bis dahin
 * immer 240 px, auf einem Handy mit 2,6-facher Dichte unscharf), bis dahin
 * eine Fläche. Den Plattencache führt Android selbst.
 */
@Composable
private fun MediaThumb(model: PhotosModel, t: Tile, modifier: Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val edge = maxOf(constraints.maxWidth.takeIf { it != androidx.compose.ui.unit.Constraints.Infinity } ?: 0, constraints.maxHeight.takeIf { it != androidx.compose.ui.unit.Constraints.Infinity } ?: 0)
        val size = io.github.ra5on.titanos.files.ThumbSizes.local(if (edge > 0) edge else 240)
        MediaThumbSized(model, t, size, Modifier.matchParentSize())
    }
}

@Composable
private fun MediaThumbSized(model: PhotosModel, t: Tile, size: Int, modifier: Modifier) {
    val context = LocalContext.current
    val key = "${t.id}@$size"
    val bitmap by produceState(thumbs.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    model.item(t.id)?.let { context.contentResolver.loadThumbnail(it.uri, Size(size, size), null) }
                }.getOrNull()?.also { thumbs.put(key, it) }
            }
        }
    }
    val b = bitmap
    if (b == null) {
        TilePlaceholder(t, modifier)
    } else {
        Box(modifier) { Image(b.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

/** Öffnet die Android-Einstellungen der App (falls Android nicht mehr fragt). */
fun appSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
