package io.github.ra5on.titanos.files

import android.os.Process
import android.util.Base64
import io.github.ra5on.titanos.net.PinnedOrigin
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class PlaybackControllersTest {
    private val me = 10_123

    private fun allowed(uid: Int, trusted: Boolean = false, notification: Boolean = false, auto: Boolean = false) =
        PlaybackControllers.allowed(uid, me, trusted, notification, auto)

    @Test
    fun ownAppSystemAndTrustedControllersOnly() {
        assertTrue(allowed(me))
        assertTrue(allowed(Process.SYSTEM_UID))
        // SystemUI, Bluetooth, Medientasten: von Android als vertrauenswürdig gemeldet.
        assertTrue(allowed(10_050, trusted = true))
        assertTrue(allowed(me, notification = true))
        assertTrue(allowed(10_077, auto = true))
        // Eine beliebige andere App.
        assertFalse(allowed(10_200))
    }
}

class OutboxPathTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun onlyFilesInsideTheOutbox() {
        val outbox = tmp.newFolder("files", "outbox")
        val copy = File(outbox, "123/Bericht.pdf").apply { parentFile!!.mkdirs(); writeText("x") }
        assertNotNull(Outbox.inside(outbox, copy.path))
        assertNull(Outbox.inside(outbox, File(tmp.root, "files/../shared_prefs/keys.xml").path))
        assertNull(Outbox.inside(outbox, File(outbox, "../secret").path))
        assertNull(Outbox.inside(outbox, outbox.path))
        assertNull(Outbox.inside(outbox, File(tmp.root, "files/outbox-other/a").path))
        assertNull(Outbox.inside(outbox, null))
    }
}

/** Speichern aus der Office-Rahmenseite: nur PUT auf files/content des eigenen Ursprungs, über den Client der App. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfficePutTest {
    @Test
    fun putGoesOverTheAppClientAndOnlyToFilesContent() {
        val seen = CopyOnWriteArrayList<RecordedRequest>()
        MockWebServer().use { nas ->
            nas.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    return MockResponse.Builder().code(200).body("{\"mtime\":\"5\"}").build()
                }
            }
            nas.start()
            val client = OkHttpClient.Builder().addInterceptor { it.proceed(it.request().newBuilder().header("Cookie", "titanos_session=abc").build()) }.build()
            val host = OfficeHost(PinnedOrigin(nas.url("/"), OkHttpClient(), "", false), client)
            val b64 = Base64.encodeToString("Inhalt".toByteArray(), Base64.NO_WRAP)

            val ok = officePut(host, "/api/v1/files/content?root=share%2FDocs&path=a.docx&expect=null", b64)
            assertEquals("{\"status\":200,\"body\":\"{\\\"mtime\\\":\\\"5\\\"}\"}", ok)
            val r = seen.single()
            assertEquals("PUT", r.method)
            assertEquals("Inhalt", r.body?.utf8())
            assertEquals("titanos_session=abc", r.headers["Cookie"])
            assertEquals("share/Docs", r.url.queryParameter("root"))

            for (bad in listOf("/api/v1/files/delete?root=x", "https://evil.example/api/v1/files/content", "//evil.example/api/v1/files/content", "/api/v1/session")) {
                assertTrue(bad, officePut(host, bad, b64).startsWith("{\"status\":403"))
            }
            assertEquals(1, seen.size)
        }
    }
}
