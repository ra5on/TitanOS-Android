package io.github.ra5on.titanos.vms.screen

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.Inflater

/**
 * Eigener RFB-Client (Protokoll 3.8, abwärts 3.7/3.3) für den Bildschirm
 * einer VM. QEMU lauscht an einem Unix-Socket, den titand als WebSocket
 * hinter der Anmeldung weiterreicht (`/vms/{name}/screen`); jede Nachricht
 * des WebSockets ist ein beliebiges Stück des Bytestroms, darum liest der
 * Client aus einem Puffer und nicht Nachricht für Nachricht.
 *
 * Sicherheit: nur „None“ (der Socket liegt hinter der Anmeldung des NAS).
 * Kodierungen: ZRLE, CopyRect, Raw, dazu Cursor, DesktopSize,
 * ExtendedDesktopSize und LastRect.
 *
 * Regel des NAS (vncInput): eine Nachricht nur aus FramebufferUpdateRequests
 * zählt nicht als Eingabe, sonst endete die Leerlaufzeit nie. Darum geht
 * jede Anfrage nach einem Bild als eigene 10-Byte-Nachricht hinaus und nie
 * zusammen mit Tasten oder Maus; von selbst sendet der Client sonst nichts.
 */
class RfbClient(
    private val out: (ByteArray) -> Boolean,
    private val listener: RfbListener,
) {
    val framebuffer = Framebuffer()

    @Volatile
    var connected: Boolean = false
        private set

    /** Der Server hat ExtendedDesktopSize geschickt: SetDesktopSize ist erlaubt. */
    @Volatile
    var canResize: Boolean = false
        private set

    @Volatile
    var serverName: String = ""
        private set

    @Volatile
    var securityTypes: List<Int> = emptyList()
        private set

    private val input = ChunkStream()
    private var thread: Thread? = null
    private val inflater = Inflater()
    private var screens: List<Screen> = emptyList()
    private var buttons = 0

    @Volatile
    private var closed = false

    /** Startet das Lesen auf einem eigenen Faden; danach Bytes mit [feed] hineingeben. */
    fun start() {
        thread = Thread({ run() }, "rfb").apply {
            isDaemon = true
            start()
        }
    }

    /** Ein Stück des Bytestroms vom NAS (eine WebSocket-Nachricht). */
    fun feed(bytes: ByteArray) {
        if (!closed) input.offer(bytes)
    }

    /** Die Verbindung ist zu: der Lesefaden endet. */
    fun close() {
        if (closed) return
        closed = true
        connected = false
        input.end()
    }

    private fun run() {
        val data = DataInputStream(input)
        try {
            handshake(data)
            while (!closed) message(data)
        } catch (e: RfbException) {
            if (!closed) listener.onError(e.message ?: "rfb")
        } catch (_: EOFException) {
            // Ende des Stroms: der Socket meldet das selbst.
        } catch (e: IOException) {
            if (!closed) listener.onError(e.message ?: "io")
        } catch (e: RuntimeException) {
            if (!closed) listener.onError(e.toString())
        } finally {
            connected = false
            inflater.end()
        }
    }

    // --- Aufbau ---------------------------------------------------------------

    private fun handshake(d: DataInputStream) {
        val version = ByteArray(12).also { d.readFully(it) }.toString(Charsets.US_ASCII)
        val m = Regex("^RFB (\\d{3})\\.(\\d{3})\n$").find(version) ?: throw RfbException("not rfb")
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toInt()
        val use = when {
            major > 3 || minor >= 8 -> 8
            minor >= 7 -> 7
            else -> 3
        }
        send("RFB 003.00$use\n".toByteArray(Charsets.US_ASCII))
        if (use == 3) {
            val type = d.readInt()
            securityTypes = listOf(type)
            if (type == 0) throw RfbException("refused: " + reason(d))
            if (type != 1) throw RfbException("security $type")
        } else {
            val count = d.readUnsignedByte()
            if (count == 0) throw RfbException("refused: " + reason(d))
            val types = (0 until count).map { d.readUnsignedByte() }
            securityTypes = types
            if (1 !in types) throw RfbException("security " + types.joinToString(","))
            send(byteArrayOf(1))
            if (use == 8) {
                val result = d.readInt()
                if (result != 0) throw RfbException("refused: " + reason(d))
            }
        }
        // ClientInit: geteilt, damit ein zweites Fenster das erste nicht trennt.
        send(byteArrayOf(1))
        val w = d.readUnsignedShort()
        val h = d.readUnsignedShort()
        d.skipFully(16)
        val nameLength = d.readInt()
        serverName = ByteArray(nameLength.coerceIn(0, 1 shl 16)).also { d.readFully(it) }.toString(Charsets.UTF_8)
        framebuffer.resize(w, h)
        send(setPixelFormat())
        send(setEncodings())
        connected = true
        listener.onConnected(serverName, w, h)
        request(incremental = false)
    }

    private fun reason(d: DataInputStream): String {
        val n = d.readInt().coerceIn(0, 1 shl 16)
        return ByteArray(n).also { d.readFully(it) }.toString(Charsets.UTF_8)
    }

    // --- Nachrichten des Servers ----------------------------------------------

    private fun message(d: DataInputStream) {
        when (val type = d.readUnsignedByte()) {
            0 -> update(d)
            1 -> {
                d.skipFully(1)
                d.readUnsignedShort()
                val n = d.readUnsignedShort()
                d.skipFully(n * 6)
            }
            2 -> listener.onBell()
            3 -> {
                d.skipFully(3)
                val n = d.readInt()
                if (n < 0) throw RfbException("extended clipboard")
                val text = ByteArray(n.coerceAtMost(4 shl 20)).also { d.readFully(it) }
                if (n > text.size) d.skipFully(n - text.size)
                listener.onClipboard(String(text, Charsets.ISO_8859_1))
            }
            else -> throw RfbException("message $type")
        }
    }

    private fun update(d: DataInputStream) {
        d.skipFully(1)
        val count = d.readUnsignedShort()
        var dirty: IntArray? = null
        fun mark(x: Int, y: Int, w: Int, h: Int) {
            val r = dirty
            dirty = if (r == null) intArrayOf(x, y, x + w, y + h) else intArrayOf(minOf(r[0], x), minOf(r[1], y), maxOf(r[2], x + w), maxOf(r[3], y + h))
        }
        var resized = false
        for (i in 0 until count) {
            val x = d.readUnsignedShort()
            val y = d.readUnsignedShort()
            val w = d.readUnsignedShort()
            val h = d.readUnsignedShort()
            when (val encoding = d.readInt()) {
                ENC_RAW -> {
                    raw(d, x, y, w, h)
                    mark(x, y, w, h)
                }
                ENC_COPYRECT -> {
                    val sx = d.readUnsignedShort()
                    val sy = d.readUnsignedShort()
                    framebuffer.copy(sx, sy, x, y, w, h)
                    mark(x, y, w, h)
                }
                ENC_ZRLE -> {
                    zrle(d, x, y, w, h)
                    mark(x, y, w, h)
                }
                ENC_CURSOR -> cursor(d, x, y, w, h)
                ENC_DESKTOP_SIZE -> {
                    framebuffer.resize(w, h)
                    resized = true
                }
                ENC_EXT_DESKTOP_SIZE -> {
                    val n = d.readUnsignedByte()
                    d.skipFully(3)
                    screens = (0 until n).map {
                        Screen(d.readInt(), d.readUnsignedShort(), d.readUnsignedShort(), d.readUnsignedShort(), d.readUnsignedShort(), d.readInt())
                    }
                    canResize = true
                    // x: Grund (1 = Antwort auf diese Anfrage), y: Ergebnis (0 = gelungen).
                    if (x == 1) listener.onResizeResult(y)
                    if (y == 0 && (w != framebuffer.width || h != framebuffer.height)) {
                        framebuffer.resize(w, h)
                        resized = true
                    }
                }
                ENC_LAST_RECT -> break
                else -> throw RfbException("encoding $encoding")
            }
        }
        if (resized) {
            listener.onResize(framebuffer.width, framebuffer.height)
            request(incremental = false)
            return
        }
        dirty?.let { listener.onUpdate(it[0], it[1], it[2] - it[0], it[3] - it[1]) }
        request(incremental = true)
    }

    private fun raw(d: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val row = ByteArray(w * 4)
        val line = IntArray(w)
        for (r in 0 until h) {
            d.readFully(row)
            for (c in 0 until w) line[c] = pixel32(row, c * 4)
            framebuffer.putRow(x, y + r, line, w)
        }
    }

    private fun cursor(d: DataInputStream, hotX: Int, hotY: Int, w: Int, h: Int) {
        val px = ByteArray(w * h * 4).also { d.readFully(it) }
        val stride = (w + 7) / 8
        val mask = ByteArray(stride * h).also { d.readFully(it) }
        if (w == 0 || h == 0) {
            listener.onCursor(null)
            return
        }
        val argb = IntArray(w * h) { i ->
            val cx = i % w
            val cy = i / w
            val visible = (mask[cy * stride + cx / 8].toInt() shr (7 - cx % 8)) and 1 == 1
            if (visible) pixel32(px, i * 4) else 0
        }
        listener.onCursor(RfbCursor(argb, w, h, hotX, hotY))
    }

    private fun zrle(d: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val length = d.readInt()
        val compressed = ByteArray(length).also { d.readFully(it) }
        inflater.setInput(compressed)
        val plain = ByteArrayOutputStream(maxOf(1024, w * h))
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = inflater.inflate(buf)
            if (n > 0) plain.write(buf, 0, n)
            if (n == 0 && (inflater.needsInput() || inflater.finished() || inflater.needsDictionary())) break
        }
        val z = Reader(plain.toByteArray())
        val tile = IntArray(64 * 64)
        var ty = 0
        while (ty < h) {
            val th = minOf(64, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(64, w - tx)
                zrleTile(z, tile, tw, th)
                framebuffer.putBlock(x + tx, y + ty, tile, tw, th)
                tx += 64
            }
            ty += 64
        }
    }

    private fun zrleTile(z: Reader, tile: IntArray, tw: Int, th: Int) {
        val n = tw * th
        when (val sub = z.u8()) {
            0 -> for (i in 0 until n) tile[i] = z.cpixel()
            1 -> tile.fill(z.cpixel(), 0, n)
            in 2..16 -> {
                val palette = IntArray(sub) { z.cpixel() }
                val bits = if (sub == 2) 1 else if (sub <= 4) 2 else 4
                val mask = (1 shl bits) - 1
                for (r in 0 until th) {
                    var shift = 8
                    var byte = 0
                    for (c in 0 until tw) {
                        if (shift == 8) {
                            byte = z.u8()
                            shift = 0
                        }
                        shift += bits
                        tile[r * tw + c] = palette[(byte shr (8 - shift)) and mask]
                    }
                }
            }
            128 -> {
                var i = 0
                while (i < n) {
                    val p = z.cpixel()
                    val run = z.runLength()
                    tile.fill(p, i, minOf(n, i + run))
                    i += run
                }
            }
            in 130..255 -> {
                val palette = IntArray(sub - 128) { z.cpixel() }
                var i = 0
                while (i < n) {
                    val index = z.u8()
                    val p = palette[index and 127]
                    if (index and 128 != 0) {
                        val run = z.runLength()
                        tile.fill(p, i, minOf(n, i + run))
                        i += run
                    } else {
                        tile[i++] = p
                    }
                }
            }
            else -> throw RfbException("zrle $sub")
        }
    }

    // --- Nachrichten an den Server -------------------------------------------------

    private fun send(bytes: ByteArray) {
        out(bytes)
    }

    /** Eine Anfrage nach dem Bild, immer allein in einer Nachricht (zählt nicht als Eingabe). */
    private fun request(incremental: Boolean) {
        if (closed) return
        val w = framebuffer.width
        val h = framebuffer.height
        send(byteArrayOf(3, if (incremental) 1 else 0, 0, 0, 0, 0, (w shr 8).toByte(), w.toByte(), (h shr 8).toByte(), h.toByte()))
    }

    /** Maus: [mask] Bit 0 links, 1 Mitte, 2 rechts, 3/4 Rad hoch/runter, 5/6 Rad links/rechts. */
    fun pointer(x: Int, y: Int, mask: Int) {
        if (!connected) return
        buttons = mask
        val px = x.coerceIn(0, maxOf(0, framebuffer.width - 1))
        val py = y.coerceIn(0, maxOf(0, framebuffer.height - 1))
        send(byteArrayOf(5, mask.toByte(), (px shr 8).toByte(), px.toByte(), (py shr 8).toByte(), py.toByte()))
    }

    /** Ein Klick an der Stelle: drücken und loslassen. */
    fun click(x: Int, y: Int, button: Int) {
        val bit = 1 shl button
        pointer(x, y, buttons or bit)
        pointer(x, y, buttons and bit.inv())
    }

    /** Ein Schritt des Rads: [dy] < 0 hoch, > 0 runter; [dx] < 0 links, > 0 rechts. */
    fun wheel(x: Int, y: Int, dx: Int, dy: Int) {
        val bit = when {
            dy < 0 -> 8
            dy > 0 -> 16
            dx < 0 -> 32
            dx > 0 -> 64
            else -> return
        }
        pointer(x, y, buttons or bit)
        pointer(x, y, buttons and bit.inv())
    }

    fun key(keysym: Int, down: Boolean) {
        if (!connected) return
        send(byteArrayOf(4, if (down) 1 else 0, 0, 0, (keysym ushr 24).toByte(), (keysym ushr 16).toByte(), (keysym ushr 8).toByte(), keysym.toByte()))
    }

    /** Tasten zusammen drücken und in umgekehrter Reihenfolge loslassen (Strg+Alt+Entf …). */
    fun keys(keysyms: List<Int>) {
        for (k in keysyms) key(k, true)
        for (k in keysyms.asReversed()) key(k, false)
    }

    /** Text als Tastendrücke, Zeichen für Zeichen. */
    fun type(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            keys(listOf(Keysyms.forCodePoint(cp)))
            i += Character.charCount(cp)
        }
    }

    /** Text in die Zwischenablage der VM (Latin-1, wie RFB es vorsieht). */
    fun clipboard(text: String) {
        if (!connected) return
        val bytes = text.replace("\r\n", "\n").map { if (it.code in 0..255) it.code.toByte() else '?'.code.toByte() }.toByteArray()
        val head = byteArrayOf(6, 0, 0, 0, (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte())
        send(head + bytes)
    }

    /** Bittet den Gast um eine neue Auflösung (nur nach ExtendedDesktopSize). */
    fun setDesktopSize(width: Int, height: Int): Boolean {
        if (!connected || !canResize) return false
        val w = width.coerceIn(320, 8192) and 0xfffe
        val h = height.coerceIn(200, 8192)
        if (w == framebuffer.width && h == framebuffer.height) return false
        val screen = screens.firstOrNull() ?: Screen(0, 0, 0, w, h, 0)
        val b = java.nio.ByteBuffer.allocate(8 + 16)
        b.put(251.toByte()).put(0).putShort(w.toShort()).putShort(h.toShort()).put(1).put(0)
        b.putInt(screen.id).putShort(0).putShort(0).putShort(w.toShort()).putShort(h.toShort()).putInt(screen.flags)
        send(b.array())
        return true
    }

    private fun setPixelFormat(): ByteArray = byteArrayOf(
        0, 0, 0, 0,
        32, 24, 0, 1, // 32 bpp, Tiefe 24, little-endian, true colour
        0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), // Maxima R, G, B
        16, 8, 0, // Verschiebung R, G, B
        0, 0, 0,
    )

    private fun setEncodings(): ByteArray {
        val list = intArrayOf(ENC_ZRLE, ENC_COPYRECT, ENC_RAW, ENC_CURSOR, ENC_EXT_DESKTOP_SIZE, ENC_DESKTOP_SIZE, ENC_LAST_RECT)
        val b = java.nio.ByteBuffer.allocate(4 + list.size * 4)
        b.put(2).put(0).putShort(list.size.toShort())
        for (e in list) b.putInt(e)
        return b.array()
    }

    private data class Screen(val id: Int, val x: Int, val y: Int, val w: Int, val h: Int, val flags: Int)

    private class Reader(private val data: ByteArray) {
        private var pos = 0
        fun u8(): Int {
            if (pos >= data.size) throw RfbException("zrle short")
            return data[pos++].toInt() and 0xff
        }

        /** CPIXEL: drei Bytes B, G, R im vereinbarten Format. */
        fun cpixel(): Int {
            if (pos + 3 > data.size) throw RfbException("zrle short")
            val b = data[pos].toInt() and 0xff
            val g = data[pos + 1].toInt() and 0xff
            val r = data[pos + 2].toInt() and 0xff
            pos += 3
            return OPAQUE or (r shl 16) or (g shl 8) or b
        }

        fun runLength(): Int {
            var length = 1
            while (true) {
                val v = u8()
                length += v
                if (v != 255) return length
            }
        }
    }

    companion object {
        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_ZRLE = 16
        const val ENC_CURSOR = -239
        const val ENC_DESKTOP_SIZE = -223
        const val ENC_LAST_RECT = -224
        const val ENC_EXT_DESKTOP_SIZE = -308
        const val OPAQUE = -0x1000000

        /** Ein Pixel im vereinbarten Format (B, G, R, X) als ARGB. */
        fun pixel32(src: ByteArray, at: Int): Int =
            OPAQUE or ((src[at + 2].toInt() and 0xff) shl 16) or ((src[at + 1].toInt() and 0xff) shl 8) or (src[at].toInt() and 0xff)
    }
}

/** Was der Client meldet; alles auf dem Lesefaden. */
interface RfbListener {
    fun onConnected(name: String, width: Int, height: Int) {}
    fun onResize(width: Int, height: Int) {}

    /** Ein vollständiges Bild ist eingetroffen; das Rechteck hat sich geändert. */
    fun onUpdate(x: Int, y: Int, width: Int, height: Int) {}
    fun onCursor(cursor: RfbCursor?) {}
    fun onBell() {}
    fun onClipboard(text: String) {}

    /** Antwort auf SetDesktopSize: 0 gelungen, sonst Grund des Gasts. */
    fun onResizeResult(status: Int) {}
    fun onError(message: String) {}
}

class RfbCursor(val pixels: IntArray, val width: Int, val height: Int, val hotX: Int, val hotY: Int)

class RfbException(message: String) : IOException(message)

/** Das Bild des Gasts als ARGB; Zugriffe nur unter [lock]. */
class Framebuffer {
    val lock = Any()

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    var pixels = IntArray(0)
        private set

    fun resize(w: Int, h: Int) = synchronized(lock) {
        width = w
        height = h
        pixels = IntArray(w * h)
    }

    fun putRow(x: Int, y: Int, row: IntArray, w: Int) = synchronized(lock) {
        if (y < 0 || y >= height || x >= width) return@synchronized
        val n = minOf(w, width - x)
        System.arraycopy(row, 0, pixels, y * width + x, n)
    }

    fun putBlock(x: Int, y: Int, block: IntArray, w: Int, h: Int) = synchronized(lock) {
        for (r in 0 until h) {
            val yy = y + r
            if (yy >= height) break
            val n = minOf(w, width - x)
            if (n > 0) System.arraycopy(block, r * w, pixels, yy * width + x, n)
        }
    }

    fun copy(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) = synchronized(lock) {
        val rows = if (sy < y) (h - 1 downTo 0) else (0 until h)
        for (r in rows) {
            val from = (sy + r) * width + sx
            val to = (y + r) * width + x
            if (sy + r >= height || y + r >= height) continue
            System.arraycopy(pixels, from, pixels, to, minOf(w, width - maxOf(sx, x)))
        }
    }

    fun get(x: Int, y: Int): Int = synchronized(lock) { pixels[y * width + x] }
}

/** Blockierender Strom aus den Stücken des WebSockets. */
internal class ChunkStream : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray = ByteArray(0)
    private var pos = 0
    private var ended = false

    fun offer(bytes: ByteArray) {
        if (bytes.isNotEmpty()) queue.put(bytes)
    }

    fun end() {
        queue.put(END)
    }

    private fun fill(): Boolean {
        while (pos >= current.size) {
            if (ended) return false
            val next = queue.take()
            if (next === END) {
                ended = true
                return false
            }
            current = next
            pos = 0
        }
        return true
    }

    override fun read(): Int = if (!fill()) -1 else current[pos++].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, current.size - pos)
        System.arraycopy(current, pos, b, off, n)
        pos += n
        return n
    }

    private companion object {
        val END = ByteArray(0)
    }
}

private fun DataInputStream.skipFully(n: Int) {
    var left = n
    while (left > 0) {
        val s = skipBytes(left)
        if (s <= 0) {
            readByte()
            left--
        } else {
            left -= s
        }
    }
}
