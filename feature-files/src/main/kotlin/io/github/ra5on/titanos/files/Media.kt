package io.github.ra5on.titanos.files

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import io.github.ra5on.titanos.net.ApiException
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * Video und Ton direkt vom NAS: jedes Stück mit `Range` über die Verbindung
 * der App (Pins, Sitzung, Heimnetz zuerst). So geht Spulen, ohne die Datei
 * vorher zu laden, und auch für mkv/mov (ohne `media=1`).
 */
@OptIn(UnstableApi::class)
class NasDataSource(private val api: () -> FilesApi?, private val fixedRoot: String? = null, private val fixedPath: String? = null) : BaseDataSource(true) {
    constructor(api: FilesApi, root: String, path: String) : this({ api }, root, path)

    private var root = ""
    private var path = ""
    private var response: Response? = null
    private var stream: InputStream? = null
    private var left: Long = C.LENGTH_UNSET.toLong()
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val target = if (fixedRoot != null && fixedPath != null) fixedRoot to fixedPath else NasMedia.parse(dataSpec.uri) ?: throw IOException("not a NAS address")
        root = target.first
        path = target.second
        val nas = api() ?: throw IOException("not paired")
        transferInitializing(dataSpec)
        val r = try {
            nas.openRange(root, path, dataSpec.position, dataSpec.length)
        } catch (e: ApiException) {
            if (e.status == 416) {
                // Am Ende der Datei: nichts mehr zu lesen.
                left = 0
                opened = true
                transferStarted(dataSpec)
                return 0
            }
            throw e
        }
        response = r
        val input = r.body.byteStream()
        if (r.code == 200 && dataSpec.position > 0) {
            // Kein Range verstanden: bis zur Stelle überspringen.
            var skip = dataSpec.position
            while (skip > 0) {
                val n = input.skip(skip)
                if (n <= 0) throw IOException("cannot seek")
                skip -= n
            }
        }
        stream = input
        val body = r.body.contentLength()
        left = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            body < 0 -> C.LENGTH_UNSET.toLong()
            r.code == 200 -> body - dataSpec.position
            else -> body
        }
        opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val want = if (left == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), left).toInt()
        val n = stream?.read(buffer, offset, want) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (left != C.LENGTH_UNSET.toLong()) left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null
        response = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** Adressen der Medien am NAS für Media3: `titanos://nas/<Freigabe>?p=<Pfad>`. */
object NasMedia {
    fun uri(root: String, path: String): Uri = Uri.Builder().scheme("titanos").authority("nas").appendPath(root).appendQueryParameter("p", path).build()

    fun parse(uri: Uri): Pair<String, String>? {
        if (uri.scheme != "titanos" || uri.authority != "nas") return null
        val root = uri.pathSegments.firstOrNull() ?: return null
        val path = uri.getQueryParameter("p") ?: return null
        return root to path
    }
}

/** Abspieler mit den Bedienelementen von Media3 (Spulen, Pause, Vollbild-Zeit). */
@OptIn(UnstableApi::class)
@Composable
fun NasPlayer(api: FilesApi, root: String, path: String, active: Boolean, onError: (String) -> Unit, modifier: Modifier = Modifier, controls: Boolean = true) {
    val context = LocalContext.current
    val player = remember(root, path) {
        val factory = DataSource.Factory { NasDataSource(api, root, path) }
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(NasMedia.uri(root, path))))
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error.errorCodeName)
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Nur die sichtbare Seite spielt; beim Wegwischen hält sie an.
    val autoplay = FilesSettings.get(context).autoplay
    LaunchedEffect(active) { if (!active) player.pause() else if (autoplay) player.play() }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        update = { it.useController = controls },
        modifier = modifier,
    )
}

/** Ton über den Hintergrunddienst: dieselbe Bedienung, Wiedergabe läuft weiter, wenn man die Seite verlässt. */
@OptIn(UnstableApi::class)
@Composable
fun NasControllerPlayer(location: Location, playlist: List<io.github.ra5on.titanos.model.FileEntry>, start: io.github.ra5on.titanos.model.FileEntry, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var controller by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.media3.session.MediaController?>(null) }
    LaunchedEffect(location, start.name) {
        controller = runCatching {
            val list = playlist.ifEmpty { listOf(start) }
            Playback.play(context, location, list, list.indexOfFirst { it.name == start.name }.coerceAtLeast(0))
        }.onFailure { onError(it.javaClass.simpleName) }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { controller?.release() } }
    val c = controller ?: return
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = c; controllerShowTimeoutMs = 0; controllerHideOnTouch = false; showController() } },
        modifier = modifier,
    )
}
