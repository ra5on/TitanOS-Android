package io.github.ra5on.titanos.nvr

import android.media.MediaCodecList
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.NasSocket
import io.github.ra5on.titanos.compute.SocketEvents
import io.github.ra5on.titanos.net.TitanJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okio.ByteString
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Live-Bild einer Kamera wie web/src/lib/nvr/mse.ts: titand reicht das
// MSE-Protokoll von go2rtc unverändert durch. Die App sagt, welche Codecs sie
// spielen kann ({"type":"mse","value":"<codecs>"}), go2rtc antwortet mit dem
// Typ des Stroms und schickt dann Stücke eines fragmentierten MP4 als
// Binärnachrichten. Statt einer SourceBuffer gehen die Stücke hier in eine
// Warteschlange, aus der eine Media3-Datenquelle (unbekannte Länge) liest;
// ExoPlayer spielt sie mit dem FragmentedMp4Extractor. titand ergänzt
// {"t":"error","code"} und {"t":"bye","reason"}.

/** Wie ein Live-Strom endete (MseEnd im Web). */
sealed interface LiveEnd {
    /** Verbindung weg oder ins Stocken geraten: ein neuer Versuch lohnt sich. */
    data object Lost : LiveEnd

    /** titand sagt, warum es kein Bild gibt (nvr_no_picture, nvr_not_running …). */
    data class Error(val code: String) : LiveEnd

    /** Sitzung beendet oder Recht entzogen: nicht neu versuchen. */
    data class Bye(val reason: String) : LiveEnd

    /** Das Gerät kann nicht spielen, was die Kamera schickt. */
    data object Unsupported : LiveEnd
}

/** Die Codecs, nach denen gefragt wird, häufigste zuerst (wie der Spieler von go2rtc). */
val MSE_CODECS = listOf(
    "avc1.640029" to "video/avc",
    "avc1.64002A" to "video/avc",
    "avc1.640033" to "video/avc",
    "hvc1.1.6.L153.B0" to "video/hevc",
    "mp4a.40.2" to "audio/mp4a-latm",
    "mp4a.40.5" to "audio/mp4a-latm",
    "flac" to "audio/flac",
    "opus" to "audio/opus",
)

/** Die Codecs, die dieses Gerät dekodieren kann, als Liste für go2rtc. */
fun deviceCodecs(): String {
    val types = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }
            .map { it.lowercase() }.toSet()
    }.getOrDefault(emptySet())
    return MSE_CODECS.filter { it.second in types }.joinToString(",") { it.first }
}

/** Ob das Gerät den Typ dekodieren kann, den go2rtc nannte (video/mp4; codecs="…"). */
fun canPlay(mime: String, codecs: String): Boolean {
    val asked = Regex("codecs=\"?([^\"]*)\"?").find(mime)?.groupValues?.get(1)?.split(',')?.map { it.trim() }.orEmpty()
    if (asked.isEmpty()) return mime.startsWith("video/mp4")
    val have = codecs.split(',').map { it.trim().lowercase() }
    return asked.all { a -> have.any { h -> a.lowercase() == h || a.lowercase().substringBefore('.') == h.substringBefore('.') } }
}

/**
 * Die Stücke zwischen WebSocket und Datenquelle. [close] legt ein Ende in
 * die Schlange, damit ein wartendes [read] zurückkehrt.
 */
class LiveFeed {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)
    private val waiting = AtomicLong(0)

    /** Bytes, die noch niemand gelesen hat. */
    val pending: Long get() = waiting.get()

    fun push(bytes: ByteArray) {
        if (closed.get()) return
        waiting.addAndGet(bytes.size.toLong())
        queue.put(bytes)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) queue.put(END)
    }

    val isClosed: Boolean get() = closed.get()

    /** Das nächste Stück; null am Ende. Wartet höchstens [timeoutMs] je Runde und prüft dazwischen das Ende. */
    fun take(timeoutMs: Long = 250): ByteArray? {
        while (true) {
            val next = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (next == null) {
                if (closed.get() && queue.isEmpty()) return null
                continue
            }
            if (next === END) {
                queue.put(END)
                return null
            }
            waiting.addAndGet(-next.size.toLong())
            return next
        }
    }

    private companion object {
        val END = ByteArray(0)
    }
}

/**
 * Media3-Datenquelle über einen [LiveFeed]: Länge unbekannt, kein Spulen.
 * Ein zweites Öffnen (ExoPlayer versucht nach einem Fehler neu) scheitert:
 * der Strom lässt sich nicht zurückspulen, die Seite verbindet neu.
 */
@OptIn(UnstableApi::class)
class LiveDataSource(private val feed: LiveFeed) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var buffer: ByteArray? = null
    private var offset = 0
    private var used = false
    private var open = false

    override fun open(dataSpec: DataSpec): Long {
        if (used || dataSpec.position > 0) throw IOException("live stream cannot be reopened")
        used = true
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        open = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
        if (length == 0) return 0
        var b = buffer
        if (b == null || offset >= b.size) {
            b = feed.take() ?: return C.RESULT_END_OF_INPUT
            buffer = b
            offset = 0
        }
        val n = minOf(length, b.size - offset)
        System.arraycopy(b, offset, target, targetOffset, n)
        offset += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        feed.close()
        if (open) transferEnded()
        open = false
        uri = null
    }
}

/**
 * Eine Live-Sitzung wie startMse im Web, ohne den Spieler: fragt nach den
 * Codecs, nimmt den MIME-Typ, legt die Stücke in [feed] und meldet genau
 * einmal das Ende. Ohne Stück für [stallMs] gilt die Verbindung als stockend.
 */
class MseSession(
    private val access: ComputeAccess,
    private val path: String,
    private val codecs: String,
    private val onMime: (String) -> Unit,
    private val onEnd: (LiveEnd) -> Unit,
    private val stallMs: Long = STALL_MS,
    private val maxPending: Long = MAX_PENDING,
) : SocketEvents {
    val feed = LiveFeed()
    private val done = AtomicBoolean(false)
    private var socket: NasSocket? = null
    private var stall: ScheduledFuture<*>? = null

    @Volatile
    var mime: String? = null
        private set

    fun start(): MseSession {
        alive()
        socket = access.socket(path, this)
        return this
    }

    private fun alive() {
        stall?.cancel(false)
        if (!done.get()) stall = TIMER.schedule({ finish(LiveEnd.Lost) }, stallMs, TimeUnit.MILLISECONDS)
    }

    override fun onOpen() {
        alive()
        socket?.sendText(buildJsonObject { put("type", "mse"); put("value", codecs) }.toString())
    }

    override fun onText(text: String) {
        val msg = runCatching { TitanJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        fun field(name: String) = (msg[name] as? JsonPrimitive)?.contentOrNull
        when {
            field("t") == "error" -> finish(LiveEnd.Error(field("code") ?: "nvr_no_picture"))
            field("t") == "bye" -> finish(LiveEnd.Bye(field("reason").orEmpty()))
            field("type") == "error" -> finish(LiveEnd.Error("nvr_no_picture"))
            field("type") == "mse" && !field("value").isNullOrEmpty() && mime == null -> {
                val value = field("value")!!
                if (!canPlay(value, codecs)) {
                    finish(LiveEnd.Unsupported)
                    return
                }
                mime = value
                onMime(value)
            }
        }
    }

    override fun onBinary(bytes: ByteString) {
        if (done.get()) return
        alive()
        feed.push(bytes.toByteArray())
        if (feed.pending > maxPending) finish(LiveEnd.Lost)
    }

    override fun onClosed(opened: Boolean, code: Int, reason: String) {
        finish(LiveEnd.Lost)
    }

    private fun finish(end: LiveEnd?) {
        if (!done.compareAndSet(false, true)) return
        stall?.cancel(false)
        socket?.close()
        feed.close()
        if (end != null) onEnd(end)
    }

    /** Beendet die Sitzung von hier aus; danach kommt keine Meldung mehr. */
    fun close() = finish(null)

    /** Für den Spieler: das Bild fiel zu weit zurück oder der Dekoder scheiterte. */
    fun fail() = finish(LiveEnd.Lost)

    companion object {
        const val STALL_MS = 8000L
        const val MAX_PENDING = 8L * 1024 * 1024
        private val TIMER = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nvr-live-timer").apply { isDaemon = true } }

        /** Wartezeit bis zum nächsten Versuch nach [failures] Fehlschlägen: 2 s … 30 s. */
        fun backoffMs(failures: Int): Long = minOf(30000L, 2000L * (1L shl minOf(maxOf(failures - 1, 0), 4)))
    }
}
