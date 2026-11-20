package io.github.ra5on.titanos.files

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
class NasDataSource(private val api: FilesApi, private val root: String, private val path: String) : BaseDataSource(true) {
    private var response: Response? = null
    private var stream: InputStream? = null
    private var left: Long = C.LENGTH_UNSET.toLong()
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val r = try {
            api.openRange(root, path, dataSpec.position, dataSpec.length)
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

/** Abspieler mit den Bedienelementen von Media3 (Spulen, Pause, Vollbild-Zeit). */
@OptIn(UnstableApi::class)
@Composable
fun NasPlayer(api: FilesApi, root: String, path: String, active: Boolean, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(root, path) {
        val factory = DataSource.Factory { NasDataSource(api, root, path) }
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.parse("titanos://nas/${Uri.encode(root)}/${Uri.encode(path, "/")}"))))
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
    LaunchedEffect(active) { if (!active) player.pause() }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        modifier = modifier,
    )
}
