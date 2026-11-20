package io.github.ra5on.titanos.notify

import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Die Routen der Mitteilungen (TitanOS docs/ANDROID-APP.md „Mitteilungen“).
 * Sie brauchen die Sitzung der App. [nas] braucht für den Strom eine
 * Verbindung mit langer Lesezeit ([streamClient]).
 */
class NotifyApi(private val nas: NasConnection) {

    private fun url(base: HttpUrl, path: String, since: String): HttpUrl =
        base.newBuilder().encodedPath("/api/v1/$path").apply { if (since.isNotEmpty()) addQueryParameter("since", since) }.build()

    /** Die Mitteilungen des Kontos nach der Marke [since], älteste zuerst. */
    suspend fun list(since: String): NoteList = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "app/notifications", since)).build() }.decode<NoteList>()
    }

    /**
     * Öffnet den Strom (blockierend) und gibt jedes Ereignis an [onEvent].
     * [onOpen] bekommt die Antwort, damit der Aufrufer sie zum Beenden
     * schließen kann (ein blockierendes Lesen reagiert nicht auf Abbruch).
     * Endet mit einer [IOException], wenn die Verbindung abreißt.
     */
    fun stream(since: String, onOpen: (Response) -> Unit, onEvent: (SseEvent) -> Unit) {
        val response = nas.execute {
            Request.Builder().url(url(it, "app/notifications/stream", since)).header("Accept", "text/event-stream").build()
        }
        response.use { r ->
            r.throwIfError()
            onOpen(r)
            val source = r.body.source()
            val parser = SseParser()
            while (true) {
                val line = source.readUtf8Line() ?: throw IOException("stream ended")
                parser.line(line)?.let(onEvent)
            }
        }
    }

    companion object {
        /** Herzschlag des NAS ist 90 s; die Lesezeit liegt deutlich darüber. */
        const val STREAM_READ_SECONDS = 240L

        fun streamClient(base: OkHttpClient): OkHttpClient =
            base.newBuilder().readTimeout(STREAM_READ_SECONDS, TimeUnit.SECONDS).build()

        fun decodeNote(data: String): Note? = runCatching { TitanJson.decodeFromString(Note.serializer(), data) }.getOrNull()

        fun decodeHead(data: String): NoteList? = runCatching { TitanJson.decodeFromString(NoteList.serializer(), data) }.getOrNull()
    }
}
