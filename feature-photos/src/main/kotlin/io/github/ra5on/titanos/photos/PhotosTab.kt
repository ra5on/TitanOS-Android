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

private enum class Route { Main, Settings, Target, Folders }

/** Die Seiten der Foto-Sicherung im Reiter „Fotos“ (Entwürfe 30–33). */
@Composable
fun PhotosTab(model: PhotosModel, bottomBar: @Composable () -> Unit) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf(Route.Main) }
    var menu by remember { mutableStateOf(false) }
    var whenFor by remember { mutableStateOf<Kind?>(null) }
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
    when (route) {
        Route.Main -> PhotosScreen(
            model.view,
            onMenu = { menu = true },
            onSetUp = { route = if (model.settings.enabled) Route.Target else Route.Settings },
            onAllowAccess = allowAccess,
            thumb = { t, m -> MediaThumb(model, t, m) },
            bottomBar = bottomBar,
        ) {
            if (menu) {
                PhotosMenu(
                    onNow = { menu = false; model.backupNow() },
                    onFree = { menu = false; model.prepareFree() },
                    onSettings = { menu = false; route = Route.Settings },
                    onDismiss = { menu = false },
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
                onTarget = { route = Route.Target },
                onFolders = { route = Route.Folders },
                onWhen = { whenFor = it },
                onBack = { route = Route.Main },
                sample = targetDir("", true, Instant.now()),
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
            TargetRoute(model) { route = Route.Settings }
        }
        Route.Folders -> {
            BackHandler { route = Route.Settings }
            FoldersScreen(model.settings, model.folders, model::update) { route = Route.Settings }
        }
    }
}

@Composable
private fun TargetRoute(model: PhotosModel, done: () -> Unit) {
    val s = model.settings
    var root by rememberSaveable { mutableStateOf(s.root) }
    var rootName by rememberSaveable { mutableStateOf(s.rootName) }
    var dir by rememberSaveable { mutableStateOf(s.dir.ifEmpty { PhotosRuntime.deviceName().replace('/', ' ').trim() }) }
    var yearMonth by rememberSaveable { mutableStateOf(s.yearMonth) }
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.loadRoots() }
    TargetScreen(
        model.roots, model.rootsFailed, root, dir, yearMonth, invalid,
        onRoot = { root = it.id; rootName = it.name },
        onDir = { dir = it; invalid = false },
        onYearMonth = { yearMonth = it },
        onSave = {
            if (!validDir(dir)) {
                invalid = true
            } else {
                model.update(model.settings.copy(enabled = true, root = root, rootName = rootName, dir = dir.trim().trim('/'), yearMonth = yearMonth))
                done()
            }
        },
        onBack = done,
    )
}

private val thumbs = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
}

/** Vorschaubild aus dem MediaStore, bis dahin eine Fläche. */
@Composable
private fun MediaThumb(model: PhotosModel, t: Tile, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(thumbs.get(t.id), t.id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    model.item(t.id)?.let { context.contentResolver.loadThumbnail(it.uri, Size(240, 240), null) }
                }.getOrNull()?.also { thumbs.put(t.id, it) }
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
