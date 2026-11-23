package io.github.ra5on.titanos.vms.screen

import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.SocketEvents
import io.github.ra5on.titanos.net.Endpoint
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import kotlin.random.Random

/** Der RFB-Client gegen eine nachgebaute Gegenstelle wie QEMU (scripts/test-ui-rfb-peer.mjs als Vorbild). */
class RfbClientTest {

    /** Baut den Bytestrom des Servers. */
    private class Peer {
        val out = ByteArrayOutputStream()
        private val deflater = Deflater()

        fun bytes(vararg b: Int) = apply { b.forEach { out.write(it) } }
        fun u16(v: Int) = apply { out.write(v shr 8); out.write(v) }
        fun s32(v: Int) = apply { out.write(ByteBuffer.allocate(4).putInt(v).array()) }
        fun text(s: String) = apply { out.write(s.toByteArray()) }

        fun hello(version: String = "RFB 003.008\n", types: List<Int> = listOf(1), w: Int = 8, h: Int = 4) = apply {
            text(version)
            if (version.endsWith("003.003\n")) s32(types.first()) else {
                bytes(types.size); types.forEach { bytes(it) }
                if (version.endsWith("003.008\n")) s32(0)
            }
            u16(w); u16(h)
            bytes(32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0)
            s32(4); text("Gast")
        }

        fun update(rects: Int) = apply { bytes(0, 0); u16(rects) }
        fun rect(x: Int, y: Int, w: Int, h: Int, enc: Int) = apply { u16(x); u16(y); u16(w); u16(h); s32(enc) }
        fun px(rgb: Int) = apply { bytes(rgb and 0xff, (rgb shr 8) and 0xff, (rgb shr 16) and 0xff, 0) }

        fun zrle(plain: ByteArray) = apply {
            deflater.setInput(plain)
            val buf = ByteArray(plain.size + 64)
            val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            s32(n); out.write(buf, 0, n)
        }

        fun build(): ByteArray = out.toByteArray()
    }

    private class Recorder : RfbListener {
        val updates = LinkedBlockingQueue<IntArray>()
        val connected = CountDownLatch(1)
        val errors = LinkedBlockingQueue<String>()
        val resizes = LinkedBlockingQueue<Pair<Int, Int>>()
        val results = LinkedBlockingQueue<Int>()
        @Volatile var cursor: RfbCursor? = null
        val cursors = CountDownLatch(1)
        override fun onConnected(name: String, width: Int, height: Int) = connected.countDown()
        override fun onUpdate(x: Int, y: Int, width: Int, height: Int) { updates.put(intArrayOf(x, y, width, height)) }
        override fun onError(message: String) { errors.put(message) }
        override fun onResize(width: Int, height: Int) { resizes.put(width to height) }
        override fun onResizeResult(status: Int) { results.put(status) }
        override fun onCursor(cursor: RfbCursor?) { this.cursor = cursor; cursors.countDown() }
    }

    private val sent = LinkedBlockingQueue<ByteArray>()
    private val listener = Recorder()
    private val client = RfbClient({ sent.put(it); true }, listener)

    /** Gibt den Strom in zufälligen Stücken hinein: eine Nachricht über mehrere, mehrere in einer. */
    private fun feedSplit(bytes: ByteArray, seed: Int = 7) {
        val r = Random(seed)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(bytes.size - i, 1 + r.nextInt(9))
            client.feed(bytes.copyOfRange(i, i + n))
            i += n
        }
    }

    private fun next(): ByteArray = sent.poll(5, TimeUnit.SECONDS) ?: error("nichts gesendet")

    private fun handshake(peer: Peer = Peer().hello()) {
        client.start()
        feedSplit(peer.build())
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS))
        assertEquals("RFB 003.008\n", String(next()))
        assertArrayEquals(byteArrayOf(1), next()) // Sicherheit None
        assertArrayEquals(byteArrayOf(1), next()) // ClientInit geteilt
        val pf = next()
        assertEquals(20, pf.size); assertEquals(0, pf[0].toInt()); assertEquals(32, pf[4].toInt())
        val enc = next()
        assertEquals(2, enc[0].toInt())
        val encodings = ByteBuffer.wrap(enc, 4, enc.size - 4).let { b -> List(enc.size / 4 - 1) { b.int } }
        assertTrue(encodings.containsAll(listOf(16, 1, 0, -239, -223, -308)))
        val first = next()
        assertEquals(10, first.size)
        assertEquals(3, first[0].toInt()); assertEquals(0, first[1].toInt())
    }

    @Test
    fun handshakePicksNoneAmongOfferedTypes() {
        handshake(Peer().hello(types = listOf(2, 1)))
        assertEquals(listOf(2, 1), client.securityTypes)
        assertEquals("Gast", client.serverName)
        assertEquals(8, client.framebuffer.width)
        client.close()
    }

    @Test
    fun version33WithoutSecurityChoice() {
        client.start()
        feedSplit(Peer().hello("RFB 003.003\n").build())
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS))
        assertEquals("RFB 003.003\n", String(next()))
        assertArrayEquals(byteArrayOf(1), next()) // gleich ClientInit, keine Wahl
        client.close()
    }

    @Test
    fun refusesWithoutNone() {
        client.start()
        feedSplit(Peer().text("RFB 003.008\n").bytes(1, 2).build())
        assertNotNull(listener.errors.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun rawCopyRectAndRequestsStayAlone() {
        handshake()
        val p = Peer().update(2)
            .rect(0, 0, 2, 1, 0).px(0xff0000).px(0x00ff00)
            .rect(4, 2, 2, 1, 1).u16(0).u16(0)
        feedSplit(p.build(), seed = 3)
        assertArrayEquals(intArrayOf(0, 0, 6, 3), listener.updates.poll(5, TimeUnit.SECONDS))
        assertEquals(0xffff0000.toInt(), client.framebuffer.get(0, 0))
        assertEquals(0xff00ff00.toInt(), client.framebuffer.get(5, 2))
        val req = next()
        assertArrayEquals(byteArrayOf(3, 1, 0, 0, 0, 0, 0, 8, 0, 4), req)
        // Eingaben gehen getrennt hinaus, nie mit einer Anfrage zusammen.
        client.click(1, 1, 0)
        client.key(Keysyms.RETURN, true)
        val inputs = List(3) { next() }
        assertEquals(listOf(6, 6, 8), inputs.map { it.size })
        assertTrue(inputs.none { it[0].toInt() == 3 })
        assertArrayEquals(byteArrayOf(5, 1, 0, 1, 0, 1), inputs[0])
        assertArrayEquals(byteArrayOf(5, 0, 0, 1, 0, 1), inputs[1])
        client.close()
    }

    @Test
    fun zrleTilesOfEveryKind() {
        handshake(Peer().hello(w = 70, h = 2))
        fun c(rgb: Int) = byteArrayOf((rgb and 0xff).toByte(), (rgb shr 8 and 0xff).toByte(), (rgb shr 16 and 0xff).toByte())
        val plain = ByteArrayOutputStream()
        // Kachel 1 (64×1): Paletten-RLE: Rot × 60, dann 4 einzelne Blau.
        plain.write(130); plain.write(c(0xff0000)); plain.write(c(0x0000ff))
        plain.write(0x80); plain.write(59) // Lauf von 60
        repeat(4) { plain.write(1) }
        // Kachel 2 (6×1): Packpalette mit 2 Farben, 1 Bit je Pixel: 101010.
        plain.write(2); plain.write(c(0x111111)); plain.write(c(0x222222)); plain.write(0b10101000)
        val first = plain.toByteArray()
        val second = ByteArrayOutputStream()
        // Zweites Rechteck 70×1 in Zeile 1: einfarbig, dann einfaches RLE.
        second.write(1); second.write(c(0x00ff00))
        second.write(128); second.write(c(0x333333)); second.write(2); second.write(c(0x444444)); second.write(2)
        val peer = Peer()
        peer.update(2).rect(0, 0, 70, 1, 16).zrle(first).rect(0, 1, 70, 1, 16).zrle(second.toByteArray())
        feedSplit(peer.build(), seed = 11)
        assertNotNull(listener.updates.poll(5, TimeUnit.SECONDS))
        val fb = client.framebuffer
        assertEquals(0xffff0000.toInt(), fb.get(59, 0))
        assertEquals(0xff0000ff.toInt(), fb.get(60, 0))
        assertEquals(0xff222222.toInt(), fb.get(64, 0))
        assertEquals(0xff111111.toInt(), fb.get(65, 0))
        assertEquals(0xff00ff00.toInt(), fb.get(63, 1))
        assertEquals(0xff333333.toInt(), fb.get(66, 1))
        assertEquals(0xff444444.toInt(), fb.get(69, 1))
        assertTrue(listener.errors.isEmpty())
        client.close()
    }

    @Test
    fun desktopSizeAndCursor() {
        handshake()
        assertTrue(!client.setDesktopSize(1280, 800)) // erst nach ExtendedDesktopSize
        val peer = Peer().update(2)
            .rect(0, 0, 8, 4, -308).bytes(1, 0, 0, 0).s32(7).u16(0).u16(0).u16(8).u16(4).s32(0)
            .rect(1, 1, 2, 1, -239).px(0xffffff).px(0x000000).bytes(0b10000000)
        feedSplit(peer.build())
        assertTrue(listener.cursors.await(5, TimeUnit.SECONDS))
        assertEquals(2, listener.cursor!!.width)
        assertEquals(0xffffffff.toInt(), listener.cursor!!.pixels[0])
        assertEquals(0, listener.cursor!!.pixels[1])
        next() // Anfrage nach dem Update
        assertTrue(client.canResize)
        assertTrue(client.setDesktopSize(1280, 800))
        val msg = next()
        assertEquals(24, msg.size)
        val b = ByteBuffer.wrap(msg)
        assertEquals(251, b.get().toInt() and 0xff); b.get()
        assertEquals(1280, b.short.toInt()); assertEquals(800, b.short.toInt())
        assertEquals(1, b.get().toInt()); b.get()
        assertEquals(7, b.int)
        // Der Gast nimmt die Größe an: neues Bild ganz anfordern.
        feedSplit(Peer().update(1).rect(1, 0, 1280, 800, -308).bytes(1, 0, 0, 0).s32(7).u16(0).u16(0).u16(1280).u16(800).s32(0).build())
        assertEquals(0, listener.results.poll(5, TimeUnit.SECONDS))
        assertEquals(1280 to 800, listener.resizes.poll(5, TimeUnit.SECONDS))
        val full = next()
        assertArrayEquals(byteArrayOf(3, 0, 0, 0, 0, 0, 5, 0, 3, 32), full)
        client.close()
    }

    @Test
    fun clipboardAndTyping() {
        handshake()
        client.clipboard("Grüße")
        val clip = next()
        assertEquals(6, clip[0].toInt()); assertEquals(5, clip[7].toInt())
        assertEquals("Grüße", String(clip, 8, 5, Charsets.ISO_8859_1))
        client.type("a€")
        val keys = List(4) { next() }
        assertEquals('a'.code, ByteBuffer.wrap(keys[0], 4, 4).int)
        assertEquals(0x20ac, ByteBuffer.wrap(keys[2], 4, 4).int)
        client.close()
    }

    /** Ende-zu-Ende über den WebSocket des NAS: die Gegenstelle teilt den Strom beliebig auf. */
    @Test
    fun overTheNasSocket() {
        val server = MockWebServer()
        server.start()
        val fromApp = LinkedBlockingQueue<ByteString>()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val stream = Peer().hello().update(1).rect(0, 0, 1, 1, 0).px(0x123456).build()
                    var i = 0
                    for (n in listOf(5, 1, 30, 2, 100)) {
                        if (i >= stream.size) break
                        val end = minOf(stream.size, i + n)
                        webSocket.send(stream.copyOfRange(i, end).toByteString())
                        i = end
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    fromApp.put(bytes)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }).build(),
        )
        val access = ComputeAccess(listOf(Endpoint(server.url("/"), OkHttpClient())), null)
        val rec = Recorder()
        lateinit var rfb: RfbClient
        val socket = access.socket("/vms/web/screen", object : SocketEvents {
            override fun onBinary(bytes: ByteString) = rfb.feed(bytes.toByteArray())
            override fun onClosed(opened: Boolean, code: Int, reason: String) = rfb.close()
        })
        rfb = RfbClient({ socket.send(it) }, rec)
        rfb.start()
        assertNotNull(rec.updates.poll(5, TimeUnit.SECONDS))
        assertEquals(0xff123456.toInt(), rfb.framebuffer.get(0, 0))
        val request = server.takeRequest()
        assertEquals("/api/v1/vms/web/screen", request.url.encodedPath)
        val msgs = mutableListOf<ByteString>()
        while (true) msgs += fromApp.poll(2, TimeUnit.SECONDS) ?: break
        // Jede Bildanfrage ist eine eigene 10-Byte-Nachricht.
        val requests = msgs.filter { it[0].toInt() == 3 }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.size == 10 })
        socket.close()
        server.close()
    }
}
