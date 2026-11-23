package io.github.ra5on.titanos.files

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.FileTypeIcon
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Was der Betrachter mit der aktuellen Datei tun kann; null blendet die Aktion aus. */
class ViewerActions(
    val share: (FileEntry) -> Unit,
    val openWith: (FileEntry) -> Unit,
    val download: (FileEntry) -> Unit,
    val info: (FileEntry) -> Unit,
    val edit: ((FileEntry) -> Unit)? = null,
    val delete: ((FileEntry) -> Unit)? = null,
    val rename: ((FileEntry) -> Unit)? = null,
    /** In einem eigenen Fenster öffnen. */
    val window: ((FileEntry) -> Unit)? = null,
    /** Name des Office-Programms für die Datei, wenn TitanOS eines hat („T-Word“). */
    val officeName: (FileEntry) -> String? = { null },
    val office: (FileEntry) -> Unit = {},
)

/** Ob die Seite dunkel hinterlegt wird (Bilder, Video). */
private fun darkKind(k: FileKind) = k == FileKind.Image || k == FileKind.Video

/**
 * Betrachter (wie FilePreview/PhotoView im Web): Wischen durch die Dateien des
 * Ordners, Bilder zoomen, Video und Ton gestreamt mit Spulen, PDF Seite für
 * Seite, Text, Code und Markdown; sonst Öffnen mit oder Teilen. Tippen blendet
 * die Leisten aus (Vollbild).
 */
@Composable
fun ViewerScreen(
    api: FilesApi?,
    location: Location,
    files: List<FileEntry>,
    start: Int,
    actions: ViewerActions,
    onBack: () -> Unit,
    onPage: (Int) -> Unit = {},
    /** Neben der Liste (ab Split): kein Vollbild, Leiste immer sichtbar. */
    embedded: Boolean = false,
    /** Fotoansicht (Entwurf 13, Galerie „Auf dem NAS“): unten die Leiste der Nachbarbilder. */
    strip: Boolean = false,
) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (files.size - 1).coerceAtLeast(0))) { files.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    // Von außen gewählt (Vorschau neben der Liste): dorthin blättern.
    LaunchedEffect(start) { if (start in files.indices && start != pager.currentPage) pager.scrollToPage(start) }
    LaunchedEffect(pager.currentPage) {
        zoomed = false
        onPage(pager.currentPage)
    }
    if (!embedded) FullScreen(!chrome) else LaunchedEffect(Unit) { chrome = true }
    // Bild im Bild (Video): nur noch das Bild, ohne Leisten.
    var pip by remember { mutableStateOf(false) }
    val pipView = LocalView.current
    DisposableEffect(Unit) {
        val act = pipView.context.findActivity() as? androidx.activity.ComponentActivity
        val l = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { pip = it.isInPictureInPictureMode }
        act?.addOnPictureInPictureModeChangedListener(l)
        onDispose { act?.removeOnPictureInPictureModeChangedListener(l) }
    }
    val current = files.getOrNull(pager.currentPage)
    val kind = current?.let { Kinds.of(it.name) } ?: FileKind.Other
    val c = Titan.colors
    // Auf schwarzem Grund helle Symbole in der Statusleiste.
    val view = LocalView.current
    val black = darkKind(kind) && !embedded
    DisposableEffect(black) {
        val ctl = view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        val before = ctl?.isAppearanceLightStatusBars
        if (black) ctl?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) ctl.isAppearanceLightStatusBars = before }
    }
    // Tastatur: Pfeile blättern, Escape schließt (ab Split nur, wenn die Vorschau den Fokus hat).
    val keys = rememberCoroutineScope()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { if (!embedded) runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(if (darkKind(kind)) Color.Black else c.bg)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionRight, Key.PageDown -> { keys.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(files.lastIndex)) }; true }
                    Key.DirectionLeft, Key.PageUp -> { keys.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }; true }
                    Key.Escape -> { onBack(); true }
                    else -> false
                }
            },
    ) {
        // Bild nicht gezoomt: nach unten ziehen schließt (Entwurf 13).
        DismissBox(enabled = !embedded && !zoomed && kind == FileKind.Image, onClose = onBack, modifier = Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, beyondViewportPageCount = 1, key = { files[it].name }) { page ->
            val e = files[page]
            val active = page == pager.currentPage
            val top = if (chrome) 64.dp else 0.dp
            Box(Modifier.fillMaxSize()) {
                when (Kinds.of(e.name)) {
                    FileKind.Image -> ImagePage(api, location, e, active, onZoom = { if (active) zoomed = it }, onTap = { if (!embedded) chrome = !chrome })
                    FileKind.Video -> if (api != null) MediaPage(api, location, e, active, audio = false, files, pip)
                    FileKind.Audio -> if (api != null) MediaPage(api, location, e, active, audio = true, files, pip)
                    FileKind.Pdf -> PdfPage(api, location, e, top, onZoom = { if (active) zoomed = it }, onTap = { if (!embedded) chrome = !chrome }, actions)
                    FileKind.Text -> TextPage(api, location, e, top, actions)
                    else -> OtherPage(api, location, e, top, actions)
                }
            }
        }
        }
        if (strip && chrome && !pip && files.size > 1) {
            ThumbStrip(
                count = files.size,
                current = pager.currentPage,
                onPick = { i -> keys.launch { pager.scrollToPage(i) } },
                label = { i -> files[i].name },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            ) { i, m ->
                val f = files[i]
                NasThumb(api, location.root.id, location.full(f.name), f.modified, m, size = 160) {
                    Box(Modifier.matchParentSize().background(Color.DarkGray))
                }
            }
        }
        if (chrome && !pip && current != null) {
            Box(Modifier.statusBarsPadding()) {
                val counter = stringResource(R.string.view_counter, pager.currentPage + 1, files.size)
                TopBar(
                    current.name,
                    onBack = onBack,
                    backLabel = stringResource(UiR.string.ui_back),
                    actions = {
                        Text(counter, style = TitanType.small, color = c.muted)
                        IconButton(TitanIcons.share, stringResource(R.string.files_share), { actions.share(current) })
                        IconButton(TitanIcons.more, stringResource(UiR.string.ui_more), { menu = true })
                    },
                )
            }
        }
        if (menu && current != null) {
            ViewerMenu(current, actions) { menu = false }
        }
    }
}

/** Blendet Status- und Navigationsleiste aus, solange [on] gilt. */
@Composable
private fun FullScreen(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        val window = view.context.findActivity()?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        if (on) {
            ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun BoxScope.ViewerMenu(e: FileEntry, a: ViewerActions, onDismiss: () -> Unit) {
    val c = Titan.colors
    Sheet(onDismiss) {
        Text(e.name, style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(bottom = 4.dp))
        Text(Format.bytes(e.size), style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(bottom = 14.dp))
        Card(background = c.bg) {
            a.officeName(e)?.let { name ->
                ListRow(stringResource(R.string.view_open_in, name), icon = { RowIcon(TitanIcons.edit) }, onClick = { onDismiss(); a.office(e) })
            }
            a.edit?.takeIf { Kinds.editable(e.name) }?.let { edit ->
                ListRow(stringResource(R.string.view_edit), icon = { RowIcon(TitanIcons.edit) }, divider = true, onClick = { onDismiss(); edit(e) })
            }
            ListRow(stringResource(R.string.view_open_with), sub = stringResource(R.string.view_open_with_text), icon = { RowIcon(TitanIcons.box) }, divider = true, onClick = { onDismiss(); a.openWith(e) })
            ListRow(stringResource(R.string.files_download), icon = { RowIcon(TitanIcons.download) }, divider = true, onClick = { onDismiss(); a.download(e) })
            a.window?.let { w -> ListRow(stringResource(R.string.window_open), icon = { RowIcon(TitanIcons.monitor) }, divider = true, onClick = { onDismiss(); w(e) }) }
            ListRow(stringResource(R.string.files_properties), icon = { RowIcon(TitanIcons.info) }, divider = true, onClick = { onDismiss(); a.info(e) })
            a.rename?.let { r -> ListRow(stringResource(R.string.files_rename), icon = { RowIcon(TitanIcons.edit) }, divider = true, onClick = { onDismiss(); r(e) }) }
            a.delete?.let { d ->
                ListRow(
                    stringResource(R.string.files_delete),
                    icon = { RowIcon(TitanIcons.trash, tint = c.dangerInk) },
                    titleColor = c.dangerInk,
                    divider = true,
                    onClick = { onDismiss(); d(e) },
                )
            }
        }
    }
}

/** Bildschirmgröße in Pixeln (für das Verkleinern des Originals). */
@Composable
private fun screenPx(): Int {
    val conf = LocalConfiguration.current
    val d = LocalDensity.current
    return with(d) { maxOf(conf.screenWidthDp, conf.screenHeightDp).dp.roundToPx() }
}

/**
 * Ein Bild vom NAS: sofort das Vorschaubild (1280 px), dann das Original aus
 * `files/download` (auf der Platte zwischengespeichert) in voller Auflösung mit
 * Kacheln beim Zoomen (1.02, [TiledImage]). Formate ohne Kacheln (GIF, AVIF …)
 * zeigt es wie bisher auf die doppelte Bildschirmgröße verkleinert.
 */
@Composable
private fun ImagePage(api: FilesApi?, location: Location, e: FileEntry, active: Boolean, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
    val context = LocalContext.current
    val path = location.full(e.name)
    val maxPx = (screenPx() * 2).coerceAtMost(4096)
    val thumb by produceState(NasImages.cached(location.root.id, path, e.modified, 1280), api, path) {
        if (value == null && api != null && Kinds.hasThumb(e.name)) value = NasImages.thumb(api, location.root.id, path, e.modified, 1280, context.applicationContext)
    }
    // Das Original erst für die sichtbare Seite (und ihre Nachbarn, sobald sie sichtbar werden).
    var failed by remember(path) { mutableStateOf(false) }
    val original by produceState<Any?>(null, api, path, active) {
        if (value == null && api != null && (active || thumb == null)) {
            val file = NasImages.originalFile(context, api, location.root.id, path, e.modified, e.size)
            // Ohne Abbruch öffnen und, falls die Seite inzwischen weg ist, gleich wieder schließen (Datei und Decoder).
            val tiled = file?.let { f -> withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { TiledSource.open(f) } }
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                tiled?.close()
                return@produceState
            }
            value = tiled ?: file?.let { f -> withContext(Dispatchers.Default) { NasImages.decode(f, maxPx) } }
            if (value == null && thumb == null) failed = true
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val o = original
        when {
            o is TiledSource -> TiledImage(o, thumb, onZoom, onTap, Modifier.fillMaxSize())
            o is Bitmap -> ZoomImage(o.asImageBitmap(), e.name, onZoom, onTap, Modifier.fillMaxSize())
            thumb != null -> ZoomImage(thumb!!.asImageBitmap(), e.name, onZoom, onTap, Modifier.fillMaxSize())
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(
                    stringResource(if (e.size > NasImages.ORIGINAL_LIMIT) R.string.view_too_large else R.string.view_image_failed),
                    style = TitanType.body,
                    color = Color.White,
                )
            }
            else -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = Color.White)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MediaPage(api: FilesApi, location: Location, e: FileEntry, active: Boolean, audio: Boolean, files: List<FileEntry>, pip: Boolean) {
    var error by remember { mutableStateOf<String?>(null) }
    var cast by remember { mutableStateOf(false) }
    val c = Titan.colors
    val context = LocalContext.current
    // Verlässt man die Seite, endet auch die Freigabe für Fernseher/Lautsprecher.
    DisposableEffect(e.name) { onDispose { MediaRelay.stop() } }
    if (pip) {
        // Bild im Bild: nur das Video.
        if (!audio) NasPlayer(api, location.root.id, location.full(e.name), active = true, onError = { error = it }, modifier = Modifier.fillMaxSize(), controls = false)
        return
    }
    if (!audio) {
        // Faltbares in Tischhaltung: oben das Video, unten die Bedienung (core-ui FoldPanes); die Wiedergabe läuft weiter.
        val player: @Composable () -> Unit = { NasPlayer(api, location.root.id, location.full(e.name), active, onError = { error = it }, modifier = Modifier.fillMaxSize()) }
        val controls: @Composable () -> Unit = {
            error?.let { Problem(stringResource(R.string.view_play_failed, it), Modifier.padding(16.dp)) }
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.media_pip), onClick = {
                    (context.findActivity())?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build())
                })
                Chip(stringResource(R.string.media_cast_tv), onClick = { cast = true })
            }
        }
        Box(Modifier.fillMaxSize()) {
            io.github.ra5on.titanos.ui.FoldPanes(
                first = player,
                second = { Column(Modifier.fillMaxSize().navigationBarsPadding()) { controls() } },
                modifier = Modifier.fillMaxSize(),
                book = false,
            ) { a, _ ->
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    controls()
                    Box(Modifier.fillMaxWidth().weight(1f)) { a() }
                }
            }
            if (cast) CastSheet(location, e) { cast = false }
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (audio) {
                Spacer(Modifier.height(24.dp))
                NasThumb(api, location.root.id, location.full(e.name), e.modified, Modifier.size(180.dp), size = 640) {
                    FileTypeIcon(TitanIcons.mic, size = 96.dp)
                }
                Text(e.name, style = TitanType.title, color = c.text, modifier = Modifier.padding(16.dp))
            }
            error?.let { Problem(stringResource(R.string.view_play_failed, it), Modifier.padding(16.dp)) }
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!audio) {
                    Chip(stringResource(R.string.media_pip), onClick = {
                        (context.findActivity())?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build())
                    })
                }
                Chip(stringResource(if (audio) R.string.media_cast_speaker else R.string.media_cast_tv), onClick = { cast = true })
            }
            val fs = FilesSettings.get(context)
            if (audio && fs.backgroundAudio) {
                // Ton läuft im Hintergrunddienst mit Wiedergabeliste aus dem Ordner weiter.
                val list = if (fs.folderPlaylist) files.filter { Kinds.of(it.name) == FileKind.Audio } else listOf(e)
                if (active) NasControllerPlayer(location, list, e, onError = { error = it }, modifier = Modifier.fillMaxWidth().weight(1f))
            } else {
                NasPlayer(api, location.root.id, location.full(e.name), active, onError = { error = it }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
        if (cast) CastSheet(location, e) { cast = false }
    }
}

/** Seiten eines PDFs; gerendert mit PdfRenderer aus einer Kopie im Cache. */
@Composable
private fun PdfPage(api: FilesApi?, location: Location, e: FileEntry, top: androidx.compose.ui.unit.Dp, onZoom: (Boolean) -> Unit, onTap: () -> Unit, actions: ViewerActions) {
    val context = LocalContext.current
    val path = location.full(e.name)
    var progress by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val doc by produceState<PdfDoc?>(null, api, path) {
        if (api == null) return@produceState
        value = try {
            val file = NasImages.cacheFile(context, location.root.id, path, e.modified)
            if (!file.exists()) api.downloadTo(location.root.id, path, file, PDF_LIMIT) { done, total -> if (total > 0) progress = done.toFloat() / total }
            withContext(Dispatchers.IO) { PdfDoc.open(file) }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: TooLargeException) {
            PdfDoc.Failed(true)
        } catch (ex: Exception) {
            PdfDoc.Failed(false)
        }
    }
    // Den Wert festhalten: beim Wechsel von „lädt“ auf „offen“ darf nicht das neue Dokument schließen.
    val opened = doc
    DisposableEffect(opened) { onDispose { (opened as? PdfDoc.Open)?.close() } }
    val width = LocalConfiguration.current.screenWidthDp
    val px = with(LocalDensity.current) { width.dp.roundToPx() } * 2
    when (val d = doc) {
        null -> CenterNote(stringResource(R.string.view_loading_percent, Format.percent(progress.toDouble())), top)
        is PdfDoc.Failed -> OtherPage(api, location, e, top, actions, problem = stringResource(if (d.tooLarge) R.string.view_too_large else R.string.view_pdf_failed))
        is PdfDoc.Open -> ZoomBox(onZoom, onTap, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().background(Titan.colors.track).statusBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = top + 8.dp, bottom = 24.dp)) {
                itemsIndexed(List(d.pages) { it }) { _, i ->
                    val ratio = d.ratio(i)
                    val page by produceState<Bitmap?>(null, d, i) { value = d.render(i, px) }
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().aspectRatio(ratio).background(Color.White)) {
                        page?.let { Image(it.asImageBitmap(), stringResource(R.string.view_pdf_page, i + 1, d.pages), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    }
                }
            }
        }
    }
}

private const val PDF_LIMIT = 300L * 1024 * 1024

/** Ein geöffnetes PDF; PdfRenderer verträgt nur eine Seite zugleich. */
sealed interface PdfDoc {
    data class Failed(val tooLarge: Boolean) : PdfDoc

    class Open(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : PdfDoc {
        private val lock = Mutex()
        val pages: Int = renderer.pageCount
        private val ratios = FloatArray(pages) { i -> renderer.openPage(i).use { it.width.toFloat() / it.height.coerceAtLeast(1) } }

        fun ratio(i: Int): Float = ratios[i].takeIf { it > 0f } ?: 0.707f

        suspend fun render(i: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    renderer.openPage(i).use { p ->
                        val w = widthPx.coerceAtMost(3000)
                        val h = (w * p.height.toFloat() / p.width).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(android.graphics.Color.WHITE)
                            p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.onFailure { android.util.Log.w("TitanOS", "PDF-Seite $i", it) }.getOrNull()
            }
        }

        fun close() {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    companion object {
        fun open(file: File): PdfDoc {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                Open(fd, PdfRenderer(fd))
            } catch (e: Exception) {
                fd.close()
                Failed(false)
            }
        }
    }
}

/** Text höchstens so groß wie im Web (512 KiB). */
private const val TEXT_LIMIT = 512L * 1024

sealed interface TextState {
    data object Loading : TextState
    data class Shown(val text: String) : TextState
    data class Failed(val tooLarge: Boolean, val binary: Boolean) : TextState
}

/** Strenges UTF-8 wie das Web (TextDecoder fatal); sonst null. */
fun strictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().takeIf { '\u0000' !in it }
} catch (_: CharacterCodingException) {
    null
}

@Composable
private fun TextPage(api: FilesApi?, location: Location, e: FileEntry, top: androidx.compose.ui.unit.Dp, actions: ViewerActions) {
    val path = location.full(e.name)
    val state by produceState<TextState>(TextState.Loading, api, path, e.modified) {
        if (api == null) return@produceState
        value = try {
            if (e.size > TEXT_LIMIT) {
                TextState.Failed(tooLarge = true, binary = false)
            } else if (Kinds.editable(e.name)) {
                TextState.Shown(api.text(location.root.id, path).content)
            } else {
                strictUtf8(api.bytes(location.root.id, path, TEXT_LIMIT))?.let { TextState.Shown(it) } ?: TextState.Failed(false, binary = true)
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: TooLargeException) {
            TextState.Failed(true, false)
        } catch (ex: io.github.ra5on.titanos.net.ApiException) {
            TextState.Failed(false, binary = ex.code == "not_text" || ex.code == "invalid_path")
        } catch (ex: Exception) {
            TextState.Failed(false, false)
        }
    }
    var formatted by remember { mutableStateOf(true) }
    val c = Titan.colors
    when (val s = state) {
        TextState.Loading -> CenterNote(stringResource(UiR.string.ui_loading), top)
        is TextState.Failed -> OtherPage(
            api, location, e, top, actions,
            problem = stringResource(
                when {
                    s.tooLarge -> R.string.view_text_too_large
                    s.binary -> R.string.view_not_text
                    else -> R.string.view_text_failed
                },
            ),
        )
        is TextState.Shown -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = top).verticalScroll(rememberScrollState()).padding(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                if (Kinds.markdown(e.name)) {
                    Chip(stringResource(R.string.view_formatted), on = formatted, onClick = { formatted = true })
                    Chip(stringResource(R.string.view_source), on = !formatted, onClick = { formatted = false })
                }
                actions.edit?.takeIf { Kinds.editable(e.name) }?.let { edit -> Chip(stringResource(R.string.view_edit), onClick = { edit(e) }) }
            }
            SelectionContainer {
                if (Kinds.markdown(e.name) && formatted) {
                    MarkdownView(s.text)
                } else {
                    Text(s.text, style = TitanType.small.copy(fontFamily = FontFamily.Monospace), color = c.text)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

@Composable
private fun CenterNote(text: String, top: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxSize().padding(top = top), contentAlignment = Alignment.Center) {
        Text(text, style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(24.dp))
    }
}

/** Datei ohne eigene Vorschau: Vorschaubild, falls das NAS eines hat, und Knöpfe. */
@Composable
private fun OtherPage(api: FilesApi?, location: Location, e: FileEntry, top: androidx.compose.ui.unit.Dp, actions: ViewerActions, problem: String? = null) {
    val c = Titan.colors
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(top = top).verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        NasThumb(api, location.root.id, location.full(e.name), e.modified, Modifier.fillMaxWidth().height(260.dp), size = 640, contentScale = ContentScale.Fit) {
            FileTypeIcon(iconFor(e.name), size = 96.dp)
        }
        Text(e.name, style = TitanType.title, color = c.text)
        val date = Format.instant(e.modified)?.let { Format.longDate(it) }
        Text(listOfNotNull(Format.bytes(e.size), date).joinToString(" · "), style = TitanType.rowSub, color = c.muted)
        problem?.let { Problem(it) }
        actions.officeName(e)?.let { name ->
            TitanButton(stringResource(R.string.view_open_in, name), { actions.office(e) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = TitanIcons.edit)
        }
        TitanButton(stringResource(R.string.view_open_with), { actions.openWith(e) }, Modifier.fillMaxWidth(), kind = if (actions.officeName(e) == null) ButtonKind.Primary else ButtonKind.Normal, icon = TitanIcons.box)
        TitanButton(stringResource(R.string.files_share), { actions.share(e) }, Modifier.fillMaxWidth(), icon = TitanIcons.share)
        TitanButton(stringResource(R.string.files_download), { actions.download(e) }, Modifier.fillMaxWidth(), icon = TitanIcons.download)
        Spacer(Modifier.navigationBarsPadding())
    }
}
