package io.github.ra5on.titanos.nvr

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.NasHttpDataSource
import io.github.ra5on.titanos.compute.NasImages
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Was ein Live-Bild gerade zeigt (onmode im Web). */
enum class LiveMode { Live, Still, None }

/** Warum ein Live-Bild nichts zeigt: Fehlercode des NAS oder „forbidden“. */
data class LiveShown(val mode: LiveMode, val reason: String = "", val stillFailed: Boolean = false)

/** Ein Video der Aufnahmen oder eines Ereignisses; [id] zählt jedes neue Laden. */
data class VideoSource(val path: String, val hls: Boolean, val startAt: Double = 0.0, val id: Int = 0, val play: Boolean = true)

/** Steuerung und Rückmeldung eines Videos (Aufnahmen, Ereignis). */
@Stable
class VideoState {
    var source by mutableStateOf<VideoSource?>(null)
    var rate by mutableFloatStateOf(1f)
    var position by mutableDoubleStateOf(0.0)
    var muted by mutableStateOf(false)
    internal var seekTo by mutableStateOf<Pair<Double, Int>?>(null)
    var onReady: () -> Unit = {}
    var onEnded: () -> Unit = {}
    var onError: (unsupported: Boolean) -> Unit = {}
    private var loads = 0

    fun load(path: String, hls: Boolean, startAt: Double = 0.0, play: Boolean = true) {
        source = VideoSource(path, hls, startAt, ++loads, play)
    }

    fun seek(seconds: Double) {
        seekTo = seconds to ((seekTo?.second ?: 0) + 1)
    }

    fun stop() {
        source = null
        position = 0.0
    }
}

/**
 * Bilder und Videos vom NAS hinter einer Schnittstelle: die App lädt sie über
 * die Verbindung, die Bildtests zeichnen Platzhalter ohne Netz und ohne Spieler.
 */
interface NvrMedia {
    @Composable
    fun Picture(path: String, description: String?, modifier: Modifier, contain: Boolean, refreshKey: Any?, onSize: (Int, Int) -> Unit, onError: () -> Unit)

    @Composable
    fun Live(camera: String, name: String, sub: Boolean, sound: Boolean, contain: Boolean, height: Int, modifier: Modifier, onShown: (LiveShown) -> Unit)

    @Composable
    fun Video(state: VideoState, description: String, modifier: Modifier)
}

val LocalNvrMedia = staticCompositionLocalOf<NvrMedia> { error("NvrMedia fehlt") }

@Composable
fun NvrPicture(
    path: String,
    description: String?,
    modifier: Modifier = Modifier,
    contain: Boolean = false,
    refreshKey: Any? = null,
    onSize: (Int, Int) -> Unit = { _, _ -> },
    onError: () -> Unit = {},
) = LocalNvrMedia.current.Picture(path, description, modifier, contain, refreshKey, onSize, onError)

/** Die echte Umsetzung über die Verbindung der App. */
class NasNvrMedia(private val access: ComputeAccess) : NvrMedia {

    @Composable
    override fun Picture(path: String, description: String?, modifier: Modifier, contain: Boolean, refreshKey: Any?, onSize: (Int, Int) -> Unit, onError: () -> Unit) {
        var bitmap by remember(path) { mutableStateOf(NasImages.cached(path)) }
        val size by rememberUpdatedState(onSize)
        val failed by rememberUpdatedState(onError)
        LaunchedEffect(path, refreshKey) {
            try {
                NasImages.load(access, path)?.let {
                    bitmap = it
                    size(it.width, it.height)
                } ?: failed()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed()
            }
        }
        val sem = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, modifier.then(sem), contentScale = if (contain) ContentScale.Fit else ContentScale.Crop)
        else Box(modifier.then(sem).background(Color(0xFF15181D)))
    }

    @OptIn(UnstableApi::class)
    @Composable
    override fun Live(camera: String, name: String, sub: Boolean, sound: Boolean, contain: Boolean, height: Int, modifier: Modifier, onShown: (LiveShown) -> Unit) {
        val context = LocalContext.current
        val shown by rememberUpdatedState(onShown)
        var active by remember { mutableStateOf(false) }
        LifecycleResumeEffect(Unit) {
            active = true
            onPauseOrDispose { active = false }
        }
        var live by remember(camera, sub) { mutableStateOf(false) }
        var reason by remember(camera, sub) { mutableStateOf("") }
        var player by remember { mutableStateOf<ExoPlayer?>(null) }
        var still by remember(camera) { mutableStateOf<Bitmap?>(null) }
        var stillFailed by remember(camera) { mutableStateOf(false) }
        var aspect by remember(camera) { mutableFloatStateOf(16f / 9f) }
        val muted = !sound

        // ---- Live-Bild: neu verbinden nur nach „lost“, mit Pause; nicht nach bye ----
        LaunchedEffect(camera, sub, active) {
            if (!active) return@LaunchedEffect
            val codecs = deviceCodecs()
            var failures = 0
            val main = Handler(Looper.getMainLooper())
            while (isActive) {
                val end = suspendCancellableCoroutine { cont ->
                    var exo: ExoPlayer? = null
                    lateinit var session: MseSession
                    val release = {
                        exo?.let { p ->
                            if (player === p) player = null
                            p.release()
                        }
                        exo = null
                    }
                    session = MseSession(
                        access,
                        NvrApi.livePath(camera, sub),
                        codecs,
                        onMime = {
                            main.post {
                                if (!cont.isActive) return@post
                                val load = DefaultLoadControl.Builder().setBufferDurationsMs(500, 4000, 100, 100).build()
                                val p = ExoPlayer.Builder(context).setLoadControl(load).build()
                                val factory = DataSource.Factory { LiveDataSource(session.feed) }
                                val extractors = ExtractorsFactory { arrayOf(FragmentedMp4Extractor()) }
                                p.setMediaSource(ProgressiveMediaSource.Factory(factory, extractors).createMediaSource(MediaItem.fromUri("titanlive://$camera")))
                                p.volume = if (muted) 0f else 1f
                                p.addListener(object : Player.Listener {
                                    override fun onRenderedFirstFrame() {
                                        failures = 0
                                        reason = ""
                                        live = true
                                    }

                                    override fun onPlayerError(error: PlaybackException) {
                                        session.fail()
                                    }

                                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                                        if (videoSize.width > 0 && videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                                    }
                                })
                                p.prepare()
                                p.playWhenReady = true
                                exo = p
                                player = p
                            }
                        },
                        onEnd = { e -> main.post { release(); if (cont.isActive) cont.resume(e) } },
                    )
                    cont.invokeOnCancellation { main.post { session.close(); release() } }
                    session.start()
                }
                live = false
                if (end is LiveEnd.Bye) {
                    reason = if (end.reason == "forbidden") "forbidden" else ""
                    break
                }
                failures++
                if (end is LiveEnd.Error) reason = end.code
                if (end is LiveEnd.Unsupported) break
                delay(MseSession.backoffMs(failures))
            }
        }
        // Am Rand des Live-Bilds bleiben: sanft aufholen, weit zurück neu verbinden.
        LaunchedEffect(player) {
            val p = player ?: return@LaunchedEffect
            while (isActive) {
                val gap = p.bufferedPosition - p.currentPosition
                val speed = if (gap > 2000) 1.5f else if (gap > 600) 1.1f else 1f
                if (p.playbackParameters.speed != speed) p.playbackParameters = PlaybackParameters(speed)
                delay(1000)
            }
        }
        LaunchedEffect(player, muted) { player?.volume = if (muted) 0f else 1f }

        // ---- Standbilder, solange kein Live-Bild läuft ----
        LaunchedEffect(camera, height, active, live) {
            if (!active || live) return@LaunchedEffect
            while (isActive) {
                val ok = try {
                    NasImages.load(access, NvrApi.snapshotPath(camera, height, System.currentTimeMillis()), keep = false)?.also { still = it } != null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                stillFailed = !ok
                delay(if (ok) 2000 else 5000)
            }
        }
        val mode = if (live) LiveMode.Live else if (still != null && !stillFailed) LiveMode.Still else LiveMode.None
        LaunchedEffect(mode, reason, stillFailed) { shown(LiveShown(mode, reason, stillFailed)) }

        Box(modifier, contentAlignment = Alignment.Center) {
            still?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (contain) ContentScale.Fit else ContentScale.Crop) }
            val p = player
            if (p != null) {
                AndroidView(
                    factory = { ctx ->
                        AspectRatioFrameLayout(ctx).apply {
                            addView(TextureView(ctx))
                        }
                    },
                    update = { frame ->
                        frame.resizeMode = if (contain) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        frame.setAspectRatio(aspect)
                        val view = frame.getChildAt(0) as TextureView
                        if (frame.tag !== p) {
                            frame.tag = p
                            p.setVideoTextureView(view)
                        }
                        frame.alpha = if (live) 1f else 0f
                    },
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = name },
                )
            }
        }
    }

    @OptIn(UnstableApi::class)
    @Composable
    override fun Video(state: VideoState, description: String, modifier: Modifier) {
        val context = LocalContext.current
        val player = remember {
            ExoPlayer.Builder(context).build()
        }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) state.onReady()
                    if (playbackState == Player.STATE_ENDED) state.onEnded()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val unsupported = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                    state.onError(unsupported)
                }

                override fun onVolumeChanged(volume: Float) {
                    state.muted = volume == 0f
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }
        val source = state.source
        LaunchedEffect(source) {
            if (source == null) {
                player.stop()
                player.clearMediaItems()
                return@LaunchedEffect
            }
            val factory = NasHttpDataSource.Factory(access)
            val item = MediaItem.fromUri(NasHttpDataSource.uriOf(source.path))
            val media = if (source.hls) HlsMediaSource.Factory(factory).createMediaSource(item) else ProgressiveMediaSource.Factory(factory).createMediaSource(item)
            player.setMediaSource(media, (source.startAt * 1000).toLong())
            player.prepare()
            player.playWhenReady = source.play
        }
        LaunchedEffect(state.seekTo) {
            val s = state.seekTo ?: return@LaunchedEffect
            player.seekTo((s.first * 1000).toLong())
            player.play()
        }
        LaunchedEffect(state.rate) { player.playbackParameters = PlaybackParameters(state.rate) }
        LaunchedEffect(player) {
            while (isActive) {
                if (player.playbackState != Player.STATE_IDLE) state.position = player.currentPosition / 1000.0
                delay(250)
            }
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    contentDescription = description
                }
            },
            modifier = modifier,
        )
    }
}

/** Ein Text mitten im dunklen Bild (Live, Spieler). */
@Composable
fun PictureNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.body, color = Color.White, textAlign = TextAlign.Center, modifier = modifier.padding(16.dp))
}
