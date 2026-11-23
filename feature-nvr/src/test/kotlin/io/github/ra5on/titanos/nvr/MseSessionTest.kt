package io.github.ra5on.titanos.nvr

import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.net.Endpoint
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Der MSE-Ablauf gegen einen WebSocket wie titand/go2rtc (docs/NVR.md „Live-Bild“). */
class MseSessionTest {
    private lateinit var server: MockWebServer
    private val asked = LinkedBlockingQueue<String>()
    private val upstreams = java.util.Collections.synchronizedList(mutableListOf<WebSocket>())
    private val sessions = mutableListOf<MseSession>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        synchronized(upstreams) { upstreams.forEach { runCatching { it.close(1000, null) } } }
        Thread.sleep(100)
        server.close()
    }

    private fun access() = ComputeAccess(listOf(Endpoint(server.url("/"), OkHttpClient())), null)

    private fun upstream(onAsk: (WebSocket, String) -> Unit) {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    upstreams += webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    asked.put(text)
                    onAsk(webSocket, text)
                }
            }).build(),
        )
    }

    @Test
    fun codecsMimeAndFragments() {
        upstream { ws, _ ->
            ws.send("""{"type":"mse","value":"video/mp4; codecs=\"avc1.640029,mp4a.40.2\""}""")
            ws.send(byteArrayOf(0, 0, 0, 8, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()).toByteString())
            ws.send(byteArrayOf(9, 9, 9).toByteString())
        }
        val mime = LinkedBlockingQueue<String>()
        val ends = LinkedBlockingQueue<LiveEnd>()
        val session = MseSession(access(), NvrApi.livePath("door", sub = true), "avc1.640029,mp4a.40.2", { mime.put(it) }, { ends.put(it) }).start().also { sessions += it }
        assertEquals("""{"type":"mse","value":"avc1.640029,mp4a.40.2"}""", asked.poll(5, TimeUnit.SECONDS))
        val r = server.takeRequest()
        assertEquals("/api/v1/nvr/cameras/door/live", r.url.encodedPath)
        assertEquals("stream=sub", r.url.encodedQuery)
        assertTrue(mime.poll(5, TimeUnit.SECONDS)!!.contains("avc1"))
        assertEquals(8, session.feed.take()!!.size)
        assertArrayEquals(byteArrayOf(9, 9, 9), session.feed.take())
        session.close()
        assertNull("close ends the reader", session.feed.take())
        assertNull("no end after an own close", ends.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun errorAndByeEndOnce() {
        upstream { ws, _ -> ws.send("""{"t":"error","code":"nvr_not_running","text":"the NVR does not run"}"""); ws.close(1008, "nvr_not_running") }
        val ends = LinkedBlockingQueue<LiveEnd>()
        MseSession(access(), "/nvr/cameras/door/live", "avc1.640029", {}, { ends.put(it) }).start().also { sessions += it }
        assertEquals(LiveEnd.Error("nvr_not_running"), ends.poll(5, TimeUnit.SECONDS))
        assertNull(ends.poll(300, TimeUnit.MILLISECONDS))

        upstream { ws, _ -> ws.send("""{"t":"bye","reason":"forbidden"}""") }
        MseSession(access(), "/nvr/cameras/door/live", "avc1.640029", {}, { ends.put(it) }).start().also { sessions += it }
        assertEquals(LiveEnd.Bye("forbidden"), ends.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun stallAndUnsupported() {
        val ready = CountDownLatch(1)
        upstream { _, _ -> ready.countDown() }
        val ends = LinkedBlockingQueue<LiveEnd>()
        MseSession(access(), "/nvr/cameras/door/live", "avc1.640029", {}, { ends.put(it) }, stallMs = 400).start().also { sessions += it }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals(LiveEnd.Lost, ends.poll(5, TimeUnit.SECONDS))

        upstream { ws, _ -> ws.send("""{"type":"mse","value":"video/mp4; codecs=\"hvc1.1.6.L153.B0\""}""") }
        MseSession(access(), "/nvr/cameras/door/live", "avc1.640029", {}, { ends.put(it) }).start().also { sessions += it }
        assertEquals(LiveEnd.Unsupported, ends.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun tooMuchWaitingIsLost() {
        upstream { ws, _ ->
            ws.send("""{"type":"mse","value":"video/mp4; codecs=\"avc1.640029\""}""")
            repeat(4) { ws.send(ByteArray(1000).toByteString()) }
        }
        val ends = LinkedBlockingQueue<LiveEnd>()
        MseSession(access(), "/nvr/cameras/door/live", "avc1.640029", {}, { ends.put(it) }, maxPending = 2500).start().also { sessions += it }
        assertEquals(LiveEnd.Lost, ends.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun backoffLikeTheWeb() {
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (1..6).map { MseSession.backoffMs(it) })
    }
}
