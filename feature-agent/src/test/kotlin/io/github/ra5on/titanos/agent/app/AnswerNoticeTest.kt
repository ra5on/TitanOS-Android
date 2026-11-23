package io.github.ra5on.titanos.agent.app

import android.app.Application
import android.app.NotificationManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.agent.chat.AgentApi
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.ChatCache
import io.github.ra5on.titanos.agent.chat.Entry
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Mitteilung „Antwort des KI-Agenten“, obwohl der Chat in der TitanOS-App offen
 * ist (Meldung des Inhabers nach 1.00). Ursache: In der TitanOS-App lebt das
 * Modell des Agenten so lange wie der Prozess (AppGraph), die Oberfläche
 * (AgentRoot) aber nur so lange wie ihre Activity. Jede Oberfläche hängte sich
 * an `onAnswer` und blieb dort; die einer beendeten Activity (aus den letzten
 * Apps gewischt, der Mitteilungsdienst hält den Prozess) sah ihren
 * Lebenszyklus „DESTROYED“, hielt die App für „nicht vorne“ und schickte die
 * Antwort als Mitteilung, während die neue Oberfläche den Chat zeigte.
 * Dazu: „nicht mehr zu sehen“ und „zu sehen“ gehen in Reihenfolge ans NAS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnswerNoticeTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()
    private val seen = Collections.synchronizedList(mutableListOf<String>())

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath.removePrefix("/api/v1")
            fun ok(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            return when {
                path == "/agent/chats/c1" -> ok("""{"id":"c1","title":"Platte","state":"idle","entries":[{"id":"q","role":"user","content":"Wie warm ist die Platte?"},{"id":"a","role":"assistant","content":"38 Grad."}]}""")
                path.endsWith("/seen") -> {
                    seen += path.removePrefix("/agent/chats/").removeSuffix("/seen") + ":" + request.body?.utf8()
                    // Das erste „zu sehen“ kommt spät an: ohne Reihenfolge überholte das folgende „nicht mehr“ es.
                    if (seen.size == 1) Thread.sleep(300)
                    MockResponse.Builder().code(204).build()
                }
                path == "/agent/chats" -> ok("""{"chats":[]}""")
                request.method == "GET" -> ok("{}")
                else -> ok("{}")
            }
        }
    }

    private lateinit var model: AgentModel
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** Ein Lebenszyklus wie der einer Activity, von Hand gesteuert. */
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun start() {
        server.dispatcher = dispatcher
        server.start()
        val nas = NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null)
        model = AgentModel(AgentApi(nas), ChatCache(null), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AgentVisible.reset()
    }

    @After
    fun stop() {
        server.close()
        AgentVisible.reset()
    }

    private fun until(ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "Zeit abgelaufen" }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(30)
        }
    }

    private fun answers(): Int = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        .count { it.channelId == "agent-answers" }

    @Test
    fun aFinishedActivityDoesNotPostTheAnswerOfTheOpenChat() {
        val first = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val second = Owner()
        var host by mutableIntStateOf(1)
        compose.setContent {
            key(host) {
                CompositionLocalProvider(LocalLifecycleOwner provides (if (host == 1) first else second)) {
                    AgentRoot(model, ask = null, onAskTaken = {}, open = "c1", versionName = "1.01", hostname = "pc", user = "alex", onUnpair = {})
                }
            }
        }
        until { model.current?.id == "c1" }
        // Die Activity endet (aus den letzten Apps gewischt), der Prozess bleibt; später eine neue Activity mit dem Chat.
        second.registry.currentState = Lifecycle.State.RESUMED
        host = 2
        compose.waitForIdle()
        first.registry.currentState = Lifecycle.State.DESTROYED
        until { model.current?.id == "c1" && AgentVisible.shows("/agent?chat=c1") }
        // Nur die neue Oberfläche hört zu (Mitteilung im Hintergrund und Vorlesen), die alte nicht mehr.
        assertEquals(2, model.answerListenerCount)

        model.answered(Entry("a", role = "assistant", content = "38 Grad."))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("keine Mitteilung für die Antwort im offenen, sichtbaren Chat", 0, answers())

        // Gegenprobe: die App geht in den Hintergrund, dann kommt die Antwort als Mitteilung.
        second.registry.currentState = Lifecycle.State.CREATED
        model.answered(Entry("a", role = "assistant", content = "38 Grad."))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, answers())
    }

    @Test
    fun visibleAndHiddenReachTheNasInOrder() {
        // Eine Oberfläche verschwindet, gleich darauf zeigt eine neue denselben Chat (wieder geöffnet):
        // das NAS muss zuletzt „zu sehen“ hören. Wer zuerst meldet, kommt zuerst an.
        model.showing("c1")
        model.hidden("c1")
        model.showing("c1")
        until { seen.size >= 3 }
        assertEquals(listOf("c1:{\"visible\":true}", "c1:{\"visible\":false}", "c1:{\"visible\":true}"), seen.toList())
    }

    @Test
    fun twoViewersOfOneChatKeepItVisible() {
        // Fenster und Leiste am Arbeitsplatz zeigen denselben Chat; eine schließt.
        model.showing("c1")
        model.showing("c1")
        model.hidden("c1")
        until { seen.size >= 2 }
        Thread.sleep(200)
        assertEquals(listOf("c1:{\"visible\":true}", "c1:{\"visible\":true}"), seen.toList())
        AgentVisible.show("c1")
        AgentVisible.show("c1")
        AgentVisible.hide("c1")
        assertEquals(true, AgentVisible.shows("/agent?chat=c1"))
        assertEquals(true, AgentVisible.shows("/agent?profile=assist&chat=c1"))
        AgentVisible.hide("c1")
        assertEquals(false, AgentVisible.shows("/agent?chat=c1"))
    }
}
