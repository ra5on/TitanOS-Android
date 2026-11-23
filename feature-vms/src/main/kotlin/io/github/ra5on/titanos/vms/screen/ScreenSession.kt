package io.github.ra5on.titanos.vms.screen

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.NasSocket
import io.github.ra5on.titanos.compute.SocketEvents
import io.github.ra5on.titanos.vms.VmApi
import kotlinx.coroutines.CancellationException
import okio.ByteString

/** Wo die Verbindung zum Bildschirm steht. */
sealed interface ScreenPhase {
    data object Connecting : ScreenPhase
    data object Connected : ScreenPhase

    /** Konsolen sind am NAS ausgeschaltet. */
    data object Disabled : ScreenPhase

    /** Beendet: [opened] war offen (Leerlaufzeit, Abmelden, VM aus) oder abgelehnt mit [status]. */
    data class Ended(val opened: Boolean, val code: Int, val reason: String, val status: Int = 0, val error: String = "") : ScreenPhase
}

/**
 * Eine Verbindung zum Bildschirm einer VM: WebSocket des NAS, RFB-Client,
 * Bild als Bitmap für die Oberfläche. Nie von selbst neu verbunden: nach dem
 * Ende zeigt die Seite einen Hinweis mit „Neu verbinden“.
 */
@Stable
class ScreenSession(private val api: VmApi, val vm: String) : RfbListener {
    var phase by mutableStateOf<ScreenPhase>(ScreenPhase.Connecting)
        private set

    /** Zählt jedes neue Bild hoch, damit die Oberfläche neu zeichnet. */
    var frame by mutableIntStateOf(0)
        private set

    var width by mutableIntStateOf(0)
        private set
    var height by mutableIntStateOf(0)
        private set
    var cursor by mutableStateOf<Bitmap?>(null)
        private set
    var cursorHot by mutableStateOf(0 to 0)
        private set

    /** Letzter Text aus der Zwischenablage der VM. */
    var guestClipboard by mutableStateOf("")
        private set
    var resizeFailed by mutableStateOf(0)
        private set
    var canResize by mutableStateOf(false)
        private set

    var bitmap: Bitmap? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var socket: NasSocket? = null
    private var client: RfbClient? = null

    @Volatile
    private var dirty: IntArray? = null
    private var copyPosted = false
    private val lock = Any()

    val rfb: RfbClient? get() = client?.takeIf { it.connected }

    /** Fragt, ob Konsolen an sind, und öffnet dann den Bildschirm. */
    suspend fun connect() {
        close()
        phase = ScreenPhase.Connecting
        val enabled = try {
            api.terminal().enabled
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nicht erreichbar: der Socket sagt dann selbst, was los ist.
            true
        }
        if (!enabled) {
            phase = ScreenPhase.Disabled
            return
        }
        val rfbClient = RfbClient({ socket?.send(it) == true }, this)
        client = rfbClient
        rfbClient.start()
        socket = api.access.socket("/vms/${ComputeAccess.seg(vm)}/screen", object : SocketEvents {
            override fun onBinary(bytes: ByteString) = rfbClient.feed(bytes.toByteArray())

            override fun onClosed(opened: Boolean, code: Int, reason: String) {
                rfbClient.close()
                val status = socket?.refusedStatus ?: 0
                main.post {
                    if (client === rfbClient && phase !is ScreenPhase.Ended) phase = ScreenPhase.Ended(opened, code, reason, status)
                }
            }
        })
    }

    /** Zeigt ein Bild ohne Verbindung (Bildtests). */
    internal fun preview(image: Bitmap) {
        bitmap = image
        width = image.width
        height = image.height
        phase = ScreenPhase.Connected
        frame++
    }

    fun close() {
        client?.close()
        socket?.close()
        client = null
        socket = null
    }

    // --- RfbListener (Lesefaden) -----------------------------------------------

    override fun onConnected(name: String, width: Int, height: Int) {
        val c = client
        main.post {
            if (client !== c) return@post
            this.width = width
            this.height = height
            canResize = c?.canResize == true
            phase = ScreenPhase.Connected
        }
    }

    override fun onResize(width: Int, height: Int) {
        main.post {
            this.width = width
            this.height = height
            canResize = client?.canResize == true
        }
    }

    override fun onUpdate(x: Int, y: Int, width: Int, height: Int) {
        synchronized(lock) {
            val r = dirty
            dirty = if (r == null) intArrayOf(x, y, x + width, y + height) else intArrayOf(minOf(r[0], x), minOf(r[1], y), maxOf(r[2], x + width), maxOf(r[3], y + height))
            if (copyPosted) return
            copyPosted = true
        }
        main.post { copyFrame() }
    }

    /** Übernimmt die geänderten Pixel ins Bitmap, auf dem Hauptfaden. */
    private fun copyFrame() {
        val region = synchronized(lock) {
            copyPosted = false
            dirty.also { dirty = null }
        } ?: return
        val fb = client?.framebuffer ?: return
        synchronized(fb.lock) {
            val w = fb.width
            val h = fb.height
            if (w <= 0 || h <= 0) return
            var bmp = bitmap
            var x0 = region[0].coerceIn(0, w)
            var y0 = region[1].coerceIn(0, h)
            var x1 = region[2].coerceIn(0, w)
            var y1 = region[3].coerceIn(0, h)
            if (bmp == null || bmp.width != w || bmp.height != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap = bmp
                x0 = 0; y0 = 0; x1 = w; y1 = h
            }
            if (x1 > x0 && y1 > y0) bmp.setPixels(fb.pixels, y0 * w + x0, w, x0, y0, x1 - x0, y1 - y0)
        }
        canResize = client?.canResize == true
        frame++
    }

    override fun onCursor(cursor: RfbCursor?) {
        val bmp = cursor?.let { Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888) }
        main.post {
            this.cursor = bmp
            cursorHot = (cursor?.hotX ?: 0) to (cursor?.hotY ?: 0)
        }
    }

    override fun onClipboard(text: String) {
        main.post { guestClipboard = text }
    }

    override fun onResizeResult(status: Int) {
        if (status != 0) main.post { resizeFailed = status }
    }

    override fun onError(message: String) {
        val c = client
        main.post {
            if (client === c) phase = ScreenPhase.Ended(true, 1002, "", error = message)
        }
        socket?.close()
    }
}
