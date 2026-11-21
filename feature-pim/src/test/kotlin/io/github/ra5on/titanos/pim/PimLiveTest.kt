package io.github.ra5on.titanos.pim

import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Mit Robolectric, weil der Strom android.util.Log schreibt. */
@RunWith(RobolectricTestRunner::class)
class PimLiveTest {
    @Test
    fun streamGivesKinds() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(
                    "event: ready\ndata: {\"account\":\"alex\",\"generations\":{}}\n\n: keep-alive\n\n" +
                        "event: pim\ndata: {\"account\":\"alex\",\"kind\":\"calendar\",\"collection\":\"arbeit\",\"generation\":\"1-2\"}\n\n" +
                        "event: resync\ndata: {\"account\":\"alex\"}\n\n" +
                        "event: pim\ndata: {\"account\":\"alex\",\"kind\":\"contacts\",\"collection\":\"kontakte\",\"generation\":\"1-3\"}\n\n",
                ).build(),
            )
            val nas = NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null)
            val got = withTimeout(5000) { pimChanges(nas).take(3).toList() }
            assertEquals(listOf("calendar", "resync", "contacts"), got)
            assertEquals("/api/v1/calendar/stream", server.takeRequest().url.encodedPath)
        }
    }
}
