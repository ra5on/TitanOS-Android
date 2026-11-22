package io.github.ra5on.titanos.files

import android.content.ComponentName
import android.content.Context
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.ra5on.titanos.model.FileEntry
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Musik und Ton vom NAS im Hintergrund (Inhaber 21.11.2026): eine
 * MediaSession mit Steuerung in der Mitteilung und auf dem Sperrbildschirm,
 * Wiedergabeliste aus dem Ordner. Gestreamt wird über die Verbindung der App
 * (Pins, Sitzung, Range) – ohne Google-Dienste.
 */
@OptIn(UnstableApi::class)
class NasPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val factory = DataSource.Factory { NasDataSource({ FilesRuntime.access()?.api }) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player).setCallback(ControllerGate).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Wer die Wiedergabe steuern und sehen darf: die App selbst, das System
     * (Mitteilung, Sperrbildschirm, Bluetooth, Medientasten – vertrauenswürdig
     * nach Android: Systemkennung, MEDIA_CONTENT_CONTROL oder ein erlaubter
     * Mitteilungszugriff) und Android Auto/Automotive. Andere Apps nicht: Titel
     * und Ordner der Wiedergabeliste kommen vom NAS.
     */
    private object ControllerGate : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (PlaybackControllers.allowed(
                    uid = controller.uid,
                    ownUid = Process.myUid(),
                    trusted = controller.isTrusted,
                    notification = session.isMediaNotificationController(controller),
                    auto = session.isAutomotiveController(controller) || session.isAutoCompanionController(controller),
                )
            ) {
                super.onConnect(session, controller)
            } else {
                MediaSession.ConnectionResult.reject()
            }
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** Welche Controller sich mit [NasPlaybackService] verbinden dürfen. */
object PlaybackControllers {
    /** Nach der Kennung (uid), nicht nach dem Paketnamen: den nennen ältere Controller ungeprüft. */
    fun allowed(uid: Int, ownUid: Int, trusted: Boolean, notification: Boolean, auto: Boolean): Boolean =
        uid == ownUid || uid == Process.SYSTEM_UID || trusted || notification || auto
}

object Playback {
    /** Ein Eintrag der Wiedergabeliste. */
    fun item(location: Location, e: FileEntry): MediaItem = MediaItem.Builder()
        .setMediaId(location.root.id + "|" + location.full(e.name))
        .setUri(NasMedia.uri(location.root.id, location.full(e.name)))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(e.name.substringBeforeLast('.')).setArtist(location.place)
                .setDisplayTitle(e.name).setIsPlayable(true).build(),
        )
        .build()

    /** Verbindet mit dem Dienst (startet ihn bei Bedarf). */
    suspend fun controller(context: Context): MediaController {
        val token = SessionToken(context, ComponentName(context, NasPlaybackService::class.java))
        return MediaController.Builder(context, token).buildAsync().await(context.mainExecutor)
    }

    /** Spielt [files] ab [start] als Wiedergabeliste. */
    suspend fun play(context: Context, location: Location, files: List<FileEntry>, start: Int): MediaController {
        val c = controller(context)
        c.setMediaItems(files.map { item(location, it) }, start.coerceIn(0, (files.size - 1).coerceAtLeast(0)), 0)
        c.prepare()
        c.play()
        return c
    }

    private suspend fun <T> ListenableFuture<T>.await(executor: Executor): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e.cause ?: e)
            }
        }, executor)
        cont.invokeOnCancellation { cancel(false) }
    }
}
