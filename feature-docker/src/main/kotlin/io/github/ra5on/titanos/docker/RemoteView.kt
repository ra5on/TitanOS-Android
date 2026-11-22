package io.github.ra5on.titanos.docker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeAccess.Companion.seg
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.NasSocket
import io.github.ra5on.titanos.compute.SmallButton
import io.github.ra5on.titanos.compute.SocketEvents
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fernansicht einer Docker-App (docs/FERNANSICHT.md, web/src/lib/desktop/remote*.ts):
 * ein WebSocket zu /viewer/{app}/stream. Kacheln (JPEG/PNG/WebP) werden in
 * ein Bild des Bildschirms gezeichnet, im Videomodus dekodiert MediaCodec
 * H.264 (Annex B) auf eine Fläche, Ton ist Opus (48 kHz, Stereo) über
 * MediaCodec und AudioTrack. Tippen = Klick, Wischen = Rad, langes Tippen =
 * Rechtsklick, Handy-Tastatur als Text, Hardware-Tasten als Tasten.
 */
class RemoteSession(private val access: ComputeAccess, val app: String) : SocketEvents {
    var phase by mutableStateOf("connecting")
    var mode by mutableStateOf("tiles")
    var width by mutableIntStateOf(0)
    var height by mutableIntStateOf(0)
    var frame by mutableIntStateOf(0)
    var bye by mutableStateOf("")
    var stats by mutableStateOf("")
    var clipboard by mutableStateOf("")
    var muted by mutableStateOf(false)
    var audio by mutableStateOf(false)
    var bitmap: Bitmap? = null
        private set
    private var socket: NasSocket? = null
    private var codec: MediaCodec? = null
    private var codecName = ""
    @Volatile
    var surface: Surface? = null
    private var opus: MediaCodec? = null
    private var track: AudioTrack? = null
    private var lastSize: Triple<Int, Int, Float>? = null

    fun open() {
        phase = "connecting"
        bye = ""
        socket = access.socket("/viewer/${seg(app)}/stream", this)
    }

    fun close() {
        socket?.close()
        socket = null
        releaseVideo()
        runCatching { opus?.stop(); opus?.release() }
        opus = null
        runCatching { track?.stop(); track?.release() }
        track = null
    }

    private fun send(o: JsonObject) = socket?.sendText(o.toString())

    fun size(w: Int, h: Int, scale: Float) {
        val s = Triple(w, h, scale)
        if (s == lastSize) return
        lastSize = s
        send(buildJsonObject { put("t", "size"); put("width", w.coerceAtLeast(1)); put("height", h.coerceAtLeast(1)); put("scale", Math.round(scale.coerceIn(1f, 4f) * 100) / 100.0); put("webp", true) })
    }

    fun mouse(x: Int, y: Int, buttons: Int) = send(buildJsonObject { put("t", "mouse"); put("x", x); put("y", y); put("buttons", buttons) })
    fun wheel(dx: Int, dy: Int) = send(buildJsonObject { put("t", "wheel"); put("dx", dx); put("dy", dy) })
    fun text(text: String) = send(buildJsonObject { put("t", "text"); put("text", text.take(1 shl 19)) })
    fun sendClipboard(text: String) = send(buildJsonObject { put("t", "clipboard"); put("text", text.take(1 shl 19)) })

    fun key(code: String, key: String, down: Boolean, shift: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean) = send(buildJsonObject {
        put("t", "key"); put("code", code); put("key", key); put("down", down)
        putJsonObject("mods") { put("shift", shift); put("ctrl", ctrl); put("alt", alt); put("meta", meta) }
    })

    override fun onOpen() {
        phase = "live"
        lastSize?.let { lastSize = null; size(it.first, it.second, it.third) }
    }

    override fun onText(text: String) {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun i(k: String) = o[k]?.jsonPrimitive?.intOrNull ?: 0
        when (s("t")) {
            "hello" -> {
                width = i("width")
                height = i("height")
                audio = o["audio"]?.jsonPrimitive?.contentOrNull == "true"
                codecName = s("codec")
                mode = if (codecName == "tiles") "tiles" else "video"
                synchronized(this) {
                    val b = bitmap
                    if (b == null || b.width != width || b.height != height) bitmap = if (width > 0 && height > 0) Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) else null
                }
                releaseVideo()
                if (mode == "video") startVideo()
            }
            "mode" -> mode = s("mode").ifEmpty { mode }
            "stats" -> stats = "${i("fps")} fps · ${i("kbps")} kbit/s · ${i("latencyMs")} ms"
            "clipboard" -> clipboard = s("text")
            "bye" -> { bye = s("reason").ifEmpty { "error" }; phase = "ended" }
        }
    }

    override fun onBinary(bytes: ByteString) {
        val b = bytes.toByteArray()
        if (b.isEmpty()) return
        when (b[0].toInt()) {
            0x04 -> tile(b)
            0x01 -> video(b)
            0x02 -> audioPacket(b)
        }
    }

    override fun onClosed(opened: Boolean, code: Int, reason: String) {
        if (phase != "ended") {
            bye = if (!opened) "refused" else reason.ifEmpty { "lost" }
            phase = "ended"
        }
        releaseVideo()
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    /** 0x04 | Art | x, y, Breite, Höhe (je uint16) | Bild. */
    private fun tile(b: ByteArray) {
        if (b.size < 11) return
        val x = u16(b, 2)
        val y = u16(b, 4)
        val img = BitmapFactory.decodeByteArray(b, 10, b.size - 10) ?: return
        synchronized(this) {
            val target = bitmap ?: return
            Canvas(target).drawBitmap(img, x.toFloat(), y.toFloat(), null)
        }
        img.recycle()
        frame++
    }

    private fun startVideo() {
        val s = surface ?: return
        runCatching {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width.coerceAtLeast(16), height.coerceAtLeast(16)), s, null, 0)
            c.start()
            codec = c
            send(buildJsonObject { put("t", "keyframe") })
        }
    }

    fun surfaceReady(s: Surface?) {
        surface = s
        if (s != null && mode == "video" && codec == null) startVideo()
        if (s == null) releaseVideo()
    }

    private fun releaseVideo() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    /** 0x01 | Flags | µs (uint64) | Zugriffseinheit Annex B. */
    private fun video(b: ByteArray) {
        val c = codec ?: return
        if (b.size < 10) return
        val ts = ByteBuffer.wrap(b, 2, 8).order(ByteOrder.BIG_ENDIAN).long
        try {
            val i = c.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val buf = c.getInputBuffer(i) ?: return
                buf.clear()
                buf.put(b, 10, b.size - 10)
                c.queueInputBuffer(i, 0, b.size - 10, ts, 0)
            } else {
                send(buildJsonObject { put("t", "keyframe") })
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val o = c.dequeueOutputBuffer(info, 0)
                if (o < 0) break
                c.releaseOutputBuffer(o, true)
            }
        } catch (_: Exception) {
            releaseVideo()
            startVideo()
        }
    }

    /** 0x02 | µs (uint64) | Opus-Paket (48 kHz, Stereo, 20 ms). */
    private fun audioPacket(b: ByteArray) {
        if (muted || b.size < 10) return
        val c = opus ?: startAudio() ?: return
        try {
            val i = c.dequeueInputBuffer(5_000)
            if (i >= 0) {
                val buf = c.getInputBuffer(i) ?: return
                buf.clear()
                buf.put(b, 9, b.size - 9)
                c.queueInputBuffer(i, 0, b.size - 9, ByteBuffer.wrap(b, 1, 8).order(ByteOrder.BIG_ENDIAN).long, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val o = c.dequeueOutputBuffer(info, 0)
                if (o < 0) break
                val out = c.getOutputBuffer(o)
                if (out != null && info.size > 0) {
                    val pcm = ByteArray(info.size)
                    out.position(info.offset)
                    out.get(pcm)
                    track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                }
                c.releaseOutputBuffer(o, false)
            }
        } catch (_: Exception) {
            runCatching { opus?.release() }
            opus = null
        }
    }

    private fun startAudio(): MediaCodec? = runCatching {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2)
        // OpusHead (RFC 7845) und Vorlauf/Nachlauf in Nanosekunden, wie Android sie verlangt.
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray()); put(1); put(2); putShort(312); putInt(48000); putShort(0); put(0)
        }.array()
        format.setByteBuffer("csd-0", ByteBuffer.wrap(head))
        format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(6_500_000L).apply { flip() })
        format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).apply { flip() })
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        c.configure(format, null, null, 0)
        c.start()
        val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 48000 * 4 / 4))
            .build().also { it.play() }
        opus = c
        c
    }.getOrNull()
}

/** DOM-Code einer Android-Taste für {"t":"key"} (US-Belegung wie der Viewer). */
internal fun domCode(keyCode: Int): String? = when (keyCode) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> "Key" + ('A' + (keyCode - KeyEvent.KEYCODE_A))
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> "Digit" + (keyCode - KeyEvent.KEYCODE_0)
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F" + (keyCode - KeyEvent.KEYCODE_F1 + 1)
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
    KeyEvent.KEYCODE_DEL -> "Backspace"
    KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_ESCAPE -> "Escape"
    KeyEvent.KEYCODE_SPACE -> "Space"
    KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
    KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
    KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
    KeyEvent.KEYCODE_MOVE_HOME -> "Home"
    KeyEvent.KEYCODE_MOVE_END -> "End"
    KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
    KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
    KeyEvent.KEYCODE_INSERT -> "Insert"
    KeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft"
    KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight"
    KeyEvent.KEYCODE_CTRL_LEFT -> "ControlLeft"
    KeyEvent.KEYCODE_CTRL_RIGHT -> "ControlRight"
    KeyEvent.KEYCODE_ALT_LEFT -> "AltLeft"
    KeyEvent.KEYCODE_ALT_RIGHT -> "AltRight"
    KeyEvent.KEYCODE_META_LEFT -> "MetaLeft"
    KeyEvent.KEYCODE_META_RIGHT -> "MetaRight"
    KeyEvent.KEYCODE_MINUS -> "Minus"
    KeyEvent.KEYCODE_EQUALS -> "Equal"
    KeyEvent.KEYCODE_LEFT_BRACKET -> "BracketLeft"
    KeyEvent.KEYCODE_RIGHT_BRACKET -> "BracketRight"
    KeyEvent.KEYCODE_BACKSLASH -> "Backslash"
    KeyEvent.KEYCODE_SEMICOLON -> "Semicolon"
    KeyEvent.KEYCODE_APOSTROPHE -> "Quote"
    KeyEvent.KEYCODE_GRAVE -> "Backquote"
    KeyEvent.KEYCODE_COMMA -> "Comma"
    KeyEvent.KEYCODE_PERIOD -> "Period"
    KeyEvent.KEYCODE_SLASH -> "Slash"
    else -> null
}

/** Die Fernansicht als Seite der Docker-App: Bild, Leiste mit Tastatur, Zwischenablage, Ton, Neu verbinden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteViewPage(access: ComputeAccess, app: String, onClose: () -> Unit) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val session = remember(app) { RemoteSession(access, app) }
    DisposableEffect(session) {
        session.open()
        onDispose { session.close() }
    }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var typing by remember { mutableStateOf("") }
    var showClip by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val screenLabel = t("viewer.screen")
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            IconButton(TitanIcons.back, androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.ui.R.string.ui_back), onClose)
            Text(app, style = TitanType.rowTitle, color = Titan.colors.text)
            StateMark(t("viewer.state.${session.phase}"), when (session.phase) { "live" -> StateKind.Ok; "ended" -> StateKind.Off; else -> StateKind.Busy })
            if (session.phase == "live") Text(t("viewer.mode.${session.mode}"), style = TitanType.small, color = Titan.colors.muted)
            if (session.stats.isNotEmpty()) Text(session.stats, style = TitanType.small, color = Titan.colors.muted)
            if (session.audio) SmallButton(t(if (session.muted) "viewer.unmute" else "viewer.mute"), TitanIcons.bell, { session.muted = !session.muted })
            SmallButton(t("viewer.clipboard"), TitanIcons.file, { showClip = !showClip })
            if (session.phase == "ended") SmallButton(t("viewer.reconnect"), TitanIcons.restart, { session.open() }, primary = true)
        }
        if (session.phase == "ended") Problem(t("viewer.bye.${session.bye}").let { if (it.startsWith("viewer.bye.")) t("viewer.bye.error") else it })
        Field(t("viewer.keyboardField"), typing, { v ->
            // Getipptes geht sofort als Text hinüber; das Feld bleibt leer.
            if (v.isNotEmpty()) session.text(v)
            typing = ""
        })
        if (showClip) {
            var clip by remember { mutableStateOf("") }
            if (session.clipboard.isNotEmpty()) {
                Text(t("viewer.clipboardRemote") + ": " + session.clipboard, style = TitanType.small, color = Titan.colors.text)
                SmallButton(t("viewer.copy"), TitanIcons.file, {
                    context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(app, session.clipboard))
                    ui.toast(ui.text("viewer.copied"))
                })
            }
            Field(t("viewer.clipboardField"), clip, { clip = it })
            SmallButton(t("viewer.clipboardSend"), TitanIcons.upload, { session.sendClipboard(clip); ui.toast(ui.text("viewer.clipboardSent")) }, enabled = clip.isNotEmpty())
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color.Black)
                .onSizeChanged {
                    box = it
                    with(density) { session.size((it.width / this.density).toInt(), (it.height / this.density).toInt(), this.density) }
                }
                .semantics { contentDescription = screenLabel; stateDescription = session.phase },
        ) {
            val w = session.width
            val h = session.height
            fun map(x: Float, y: Float): Pair<Int, Int> {
                if (w <= 0 || h <= 0 || box.width <= 0) return 0 to 0
                val scale = minOf(box.width.toFloat() / w, box.height.toFloat() / h)
                val ox = (box.width - w * scale) / 2
                val oy = (box.height - h * scale) / 2
                return ((x - ox) / scale).toInt().coerceIn(0, w - 1) to ((y - oy) / scale).toInt().coerceIn(0, h - 1)
            }
            if (session.mode == "video") {
                AndroidView(factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = session.surfaceReady(Surface(st))
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { session.surfaceReady(null); return true }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                }, modifier = Modifier.fillMaxSize())
            } else {
                ComposeCanvas(Modifier.fillMaxSize()) {
                    session.frame.hashCode()
                    val bmp = synchronized(session) { session.bitmap?.copy(Bitmap.Config.ARGB_8888, false) } ?: return@ComposeCanvas
                    val scale = minOf(size.width / bmp.width, size.height / bmp.height)
                    val dw = (bmp.width * scale).toInt()
                    val dh = (bmp.height * scale).toInt()
                    drawImage(
                        bmp.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()),
                        dstSize = IntSize(dw, dh),
                    )
                }
            }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(w, h, box) {
                        detectTapGestures(
                            onTap = { o -> val (x, y) = map(o.x, o.y); session.mouse(x, y, 1); session.mouse(x, y, 0); focus.requestFocus() },
                            onLongPress = { o -> val (x, y) = map(o.x, o.y); session.mouse(x, y, 2); session.mouse(x, y, 0) },
                        )
                    }
                    .pointerInput(w, h, box) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val (x, y) = map(change.position.x, change.position.y)
                            session.mouse(x, y, 0)
                            session.wheel(0, (-drag.y * 2).toInt())
                        }
                    }
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        val n = e.nativeKeyEvent
                        val code = domCode(n.keyCode) ?: return@onPreviewKeyEvent false
                        val down = e.type == KeyEventType.KeyDown
                        if (!down && e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                        val ch = n.getUnicodeChar(n.metaState).takeIf { it > 0 }?.let { String(Character.toChars(it)) } ?: code
                        session.key(code, ch, down, n.isShiftPressed, n.isCtrlPressed, n.isAltPressed, n.isMetaPressed)
                        true
                    },
            )
            if (session.phase == "connecting") Text(t("viewer.starting"), style = TitanType.body, color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
    }
}
