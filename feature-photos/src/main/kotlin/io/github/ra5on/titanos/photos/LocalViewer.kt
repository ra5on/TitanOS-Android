package io.github.ra5on.titanos.photos

import android.content.Intent
import android.graphics.Bitmap
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.files.DismissBox
import io.github.ra5on.titanos.files.ThumbStrip
import io.github.ra5on.titanos.files.ZoomImage
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Fotoansicht der Aufnahmen am Handy (Entwurf 13): wischen für das nächste
 * bzw. vorige Foto, zwei Finger oder doppelt tippen zoomen, nach unten ziehen
 * schließt, unten die Leiste der Nachbarbilder. Oben Zeit, Name, Größe und ob
 * es schon auf dem NAS liegt; Teilen. Videos spielt eine andere App ab.
 */
@Composable
fun LocalPhotoViewer(model: PhotosModel, tiles: List<Tile>, start: Int, onClose: () -> Unit, thumb: @Composable (Tile, Modifier) -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (tiles.size - 1).coerceAtLeast(0))) { tiles.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    val current = tiles.getOrNull(pager.currentPage)
    val item = current?.let { model.item(it.id) }
    val noApp = stringResource(R.string.photos_view_none)
    // Auf Schwarz helle Symbole in der Statusleiste (wie die Fotoansicht der Dateien).
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)
            ?: (view.context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
        val ctl = window?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val before = ctl?.isAppearanceLightStatusBars
        ctl?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) ctl.isAppearanceLightStatusBars = before }
    }
    // Auf Schwarz immer die dunkle Darstellung (helle Schrift), wie die Fotoansicht der Dateien.
    io.github.ra5on.titanos.ui.theme.TitanTheme(dark = true) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        DismissBox(enabled = !zoomed && current?.kind != Kind.Video, onClose = onClose, modifier = Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, beyondViewportPageCount = 1, key = { tiles[it].id }) { page ->
                val t = tiles[page]
                LocalPage(model, t, active = page == pager.currentPage, onZoom = { if (page == pager.currentPage) zoomed = it }, onTap = { chrome = !chrome }) {
                    val uri = model.item(t.id)?.uri ?: return@LocalPage
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (e: android.content.ActivityNotFoundException) {
                        Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        if (chrome && current != null) {
            Column(Modifier.background(Color.Black.copy(alpha = .45f)).statusBarsPadding()) {
                val zone = ZoneId.systemDefault()
                val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(current.taken.atZone(zone))
                val title = Format.shortDate(current.taken, stringResource(R.string.photos_today), stringResource(R.string.photos_yesterday)) + ", " + time
                val sub = listOfNotNull(
                    item?.media?.name,
                    item?.media?.size?.takeIf { it > 0 }?.let { Format.bytes(it) },
                    stringResource(if (current.saved) R.string.photos_view_saved else R.string.photos_view_waiting),
                ).joinToString(" · ")
                TopBar(
                    title,
                    onBack = onClose,
                    backLabel = stringResource(UiR.string.ui_back),
                    flat = true,
                    actions = {
                        item?.let { m ->
                            IconButton(TitanIcons.share, stringResource(R.string.photos_view_share), {
                                val send = Intent(Intent.ACTION_SEND).setType(if (m.media.kind == Kind.Video) "video/*" else "image/*")
                                    .putExtra(Intent.EXTRA_STREAM, m.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }, tint = Color.White)
                        }
                    },
                )
                Text(sub, style = TitanType.small, color = Color.White.copy(alpha = .8f), modifier = Modifier.padding(start = 60.dp, end = 16.dp, bottom = 8.dp))
            }
        }
        if (chrome && tiles.size > 1) {
            ThumbStrip(
                count = tiles.size,
                current = pager.currentPage,
                onPick = { i -> scope.launch { pager.scrollToPage(i) } },
                label = { i -> Format.longDate(tiles[i].taken) },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            ) { i, m -> thumb(tiles[i], m) }
        }
    }
    }
}

@Composable
private fun LocalPage(model: PhotosModel, t: Tile, active: Boolean, onZoom: (Boolean) -> Unit, onTap: () -> Unit, onPlay: () -> Unit) {
    val context = LocalContext.current
    var failed by remember(t.id) { mutableStateOf(false) }
    // Groß genug zum Zoomen, aber nicht das ganze Original (Speicher): 2048 Pixel an der langen Seite.
    val bitmap by produceState<Bitmap?>(null, t.id, active) {
        if (value == null && (active || !failed)) {
            value = withContext(Dispatchers.IO) {
                runCatching { model.item(t.id)?.let { context.contentResolver.loadThumbnail(it.uri, Size(2048, 2048), null) } }.getOrNull()
            }
            if (value == null) failed = true
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val b = bitmap
        when {
            b != null && t.kind == Kind.Video -> {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Box(Modifier.background(Color.Black.copy(alpha = .55f), androidx.compose.foundation.shape.CircleShape)) {
                    IconButton(TitanIcons.play, stringResource(R.string.photos_view_play), onPlay, size = 30.dp, tint = Color.White)
                }
            }
            b != null -> ZoomImage(b.asImageBitmap(), null, onZoom, onTap, Modifier.fillMaxSize())
            failed -> Column(Modifier.padding(24.dp)) { Text(stringResource(R.string.photos_view_failed), style = TitanType.body, color = Color.White) }
            else -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = Color.White)
        }
    }
}
