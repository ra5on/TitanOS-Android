package io.github.ra5on.titanos.agent.chat

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.agent.app.AgentNav
import io.github.ra5on.titanos.agent.app.AgentShell
import io.github.ra5on.titanos.agent.app.AgentView
import io.github.ra5on.titanos.agent.app.AppHooks
import io.github.ra5on.titanos.agent.app.Layer
import io.github.ra5on.titanos.agent.core.AgentTheme
import io.github.ra5on.titanos.agent.core.Appearance
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.voice.Speech
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone

/**
 * Bildtests der Agent-App, jeder Bildschirm hell und dunkel, in 390 × 844 dp
 * wie die Aufnahmen des Webs (Entwuerfe/116-android/web-agent) und mit
 * denselben Testdaten wie scripts/test-ui-agent90.mjs (alex, Home Assistant,
 * gpt-oss:20b). Die App spricht dabei mit einem MockWebServer, der die
 * Antworten des NAS gibt – Modell, Seiten und Blätter laufen wie echt.
 * Bilder: `./gradlew :feature-agent:recordRoborazziDebug` →
 * feature-agent/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class AgentScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    private val day = Instant.now().truncatedTo(ChronoUnit.DAYS)
    private fun at(h: Int, m: Int = 0) = day.plusSeconds(h * 3600L + m * 60L).toString()

    private val status = """{"enabled":true,"allowed":true,"admin":true,"model":"gpt-oss:20b","think":"medium","context":0,"volume":true,"web":true,"reachable":true,
        "models":[{"name":"gpt-oss:20b","size":13793441566,"capabilities":["completion","tools","thinking"],"contextLength":131072,"levels":["low","medium","high"],"defaultLevel":"medium"},
        {"name":"qwen3:8b","size":5200000000,"capabilities":["completion","tools"],"contextLength":32768}]}"""
    private val projects = """{"projects":[{"id":"p1aaaaaaaaaa0001","name":"Home Assistant","icon":"🏠","instructions":"Vor Updates immer sichern.","created":"${at(1)}"},
        {"id":"p1aaaaaaaaaa0002","name":"NAS-Doku","icon":"📚","created":"${at(1)}"}]}"""
    private val table = "| Installation | Zustand | Version | Sicherung |\\n|---|---|---|---|\\n| Docker-App | läuft | 2026.10.4 | heute |\\n| VM HAOS | läuft | Update auf 2026.10.4 | vor 9 Tagen |\\n\\nDas Update der VM spiele ich nach einer Sicherung ein."
    private fun chatJson(id: String, state: String, entries: String) = """{"id":"$id","title":"Status Home Assistant","project":"p1aaaaaaaaaa0001","created":"${at(10, 40)}","updated":"${at(10, 42)}","state":"$state","origin":"web","model":"gpt-oss:20b","think":"medium","entries":[$entries]}"""
    private val entries = """{"id":"u1","at":"${at(10, 40)}","role":"user","content":"Wie ist der Status von Home Assistant?"},
        {"id":"a1","at":"${at(10, 40)}","role":"assistant","content":"Home Assistant läuft zweimal. Welche meinst du?"},
        {"id":"u2","at":"${at(10, 41)}","role":"user","content":"Beide, mit Update"},
        {"id":"t1","at":"${at(10, 41)}","role":"tool","tool":"docker_list","ok":true,"durationMs":900,"result":"{\"containers\":[{\"name\":\"homeassistant\",\"state\":\"running\",\"memoryBytes\":512000000,\"cpuPercent\":1.5,\"updateAvailable\":true},{\"name\":\"mosquitto\",\"state\":\"running\"}]}"},
        {"id":"a2","at":"${at(10, 42)}","role":"assistant","content":"$table"}"""
    private val chats = """{"chats":[
        {"id":"c9aaaaaaaaaa0001","title":"Status Home Assistant","project":"p1aaaaaaaaaa0001","created":"${at(10, 40)}","updated":"${at(10, 42)}","state":"idle","origin":"web","preview":""},
        {"id":"c9aaaaaaaaaa0002","title":"VM HAOS aktualisieren","created":"${at(11)}","updated":"${at(11, 42)}","state":"waiting","origin":"web","preview":""},
        {"id":"c9aaaaaaaaaa0003","title":"Gesundheitscheck","created":"${at(8)}","updated":"${at(8, 2)}","state":"idle","origin":"schedule","preview":"Alles in Ordnung."}]}"""
    private val memory = """{"memory":[
        {"id":"m1","text":"Home Assistant läuft als VM HAOS.","at":"${at(10, 42)}","chat":"c9aaaaaaaaaa0001","topic":"Home Assistant","pinned":true,"used":"${Instant.now()}"},
        {"id":"m2","text":"Der Zigbee-Stick hängt hinten links.","at":"2026-01-12T10:00:00Z","topic":"Home Assistant"},
        {"id":"m3","text":"Telekom-Rechnungen liegen in Documents › Post.","at":"${at(11, 1)}","untrusted":true}]}"""
    private val schedules = """{"schedules":[
        {"id":"sc1","name":"Gesundheitscheck","task":"Gesundheitscheck","every":"weekly","at":"08:00","weekday":1,"enabled":true,"next":"2026-11-23T07:00:00Z","when":"montags 08:00","created":"${at(1)}"},
        {"id":"sc2","name":"Müll rausstellen","task":"Müll rausstellen","kind":"reminder","every":"once","enabled":true,"next":"2026-11-22T18:00:00Z","when":"einmal","created":"${at(1)}"},
        {"id":"sc3","name":"","task":"","kind":"morning","every":"weekdays","at":"07:00","weekdays":[1,2,3,4,5],"enabled":false,"when":"werktags 07:00","created":"${at(1)}"}]}"""
    private val home = """{"state":{"at":"${at(14, 40)}","volumes":[{"name":"Data","state":"active","mounted":true,"percent":71,"daysLeft":23},{"name":"Fast","state":"degraded","mounted":true,"percent":12}],
        "backups":{"jobs":5,"failing":1,"running":0,"lastOK":"${at(3)}"},"apps":{"running":12,"stopped":1}}}"""
    private val inbox = """{"items":[{"id":"i1","kind":"fade","title":"Der Zigbee-Stick hängt hinten links.","body":"Stimmt es noch, oder soll es weg?","data":{"memory":"m2"},"created":"${at(3, 10)}"}]}"""
    private val skills = """{"skills":[{"id":"builtin-health","name":"Health check","when":"Gesundheitscheck","steps":"1. system_status\n2. storage_status","builtIn":true,"updated":"${at(1)}",
        "de":{"name":"Gesundheitscheck","when":"Gesundheitscheck","steps":"1. system_status prüfen\n2. storage_status prüfen"}},
        {"id":"s1","name":"Rechnungen einsortieren","when":"Rechnungen ablegen","steps":"1. files_search\n2. files_move","updated":"${at(11)}"}]}"""

    @Volatile private var busyChat = false

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath.removePrefix("/api/v1")
            val get = request.method == "GET"
            fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
            return when {
                path == "/agent/status" -> ok(status)
                path == "/agent/chats" && get -> ok(chats)
                path == "/agent/chats/c9aaaaaaaaaa0001" -> ok(if (busyChat) chatJson("c9aaaaaaaaaa0001", "running", entries.substringBefore(",\n        {\"id\":\"t1\"")) else chatJson("c9aaaaaaaaaa0001", "idle", entries))
                path.endsWith("/events") -> if (busyChat) MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                    .body("event: snapshot\ndata: {\"type\":\"snapshot\",\"entries\":[],\"state\":\"running\",\"step\":{\"id\":\"s1\",\"role\":\"tool\",\"tool\":\"docker_list\"}}\n\n" + ": warten\n".repeat(400))
                    .throttleBody(10, 1, java.util.concurrent.TimeUnit.SECONDS).build()
                    else MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body("event: end\ndata: {}\n\n").build()
                path == "/agent/projects" -> ok(projects)
                path == "/agent/home" -> ok(home)
                path == "/agent/inbox" -> ok(inbox)
                path == "/agent/suggestions" -> ok("""{"suggestions":[{"kind":"update_available","title":"TitanOS 0.82 ist da","severity":"info","time":"${at(6)}"}]}""")
                path == "/agent/schedules" -> ok(schedules)
                path == "/agent/memory" -> ok(memory)
                path == "/agent/initiative" -> ok("""{"level":"suggest","fadeMonths":6,"search":[]}""")
                path == "/agent/skills" -> ok(skills)
                path == "/agent/profile" -> ok("""{"instruction":"Antworte kurz."}""")
                path == "/agent/map" -> ok("""{"built":false,"map":{}}""")
                path == "/agent/prefs" -> ok("{}")
                path == "/agent/settings" -> ok("""{"locations":[],"ollama":""}""")
                path == "/agent/features/me" -> ok("""{"mine":{"docs":{},"paper":{}},"nas":{"docs":true,"deadlines":true,"paper":true,"guard":true,"handbook":true,"emergency":false,"whatIf":true,"advisor":true,"buddy":false,"catalog":true}}""")
                path == "/agent/features" -> ok("""{"docs":{"enabled":true,"deadlines":true,"paper":true,"at":"03:00"},"guard":{"enabled":true,"mode":"report"},"handbook":{"enabled":true},"emergency":{},"whatIf":{"enabled":true},"advisor":{"enabled":true},"buddy":{},"catalog":{"enabled":true},"rev":"1"}""")
                path == "/agent/protect/me" -> ok("""{"guard":true,"handbook":true,"emergency":false}""")
                path == "/agent/speech" -> ok("""{"say":false,"hear":false}""")
                path == "/agent/photos" -> ok("""{"immich":true,"keySet":true,"userName":"alex","port":2283}""")
                path == "/agent/rights/me" -> ok("""{"effective":{"mail.read":"allow","mail.draft":"suggest","mail.send":"ask","event.create":"suggest","event.delete":"ask"},"nas":{"mail.read":"allow","mail.draft":"suggest","mail.send":"ask","event.create":"suggest","event.delete":"ask"},"own":{}}""")
                path == "/notifications/push" -> ok("""{"publicKey":"","devices":[]}""")
                path == "/agent/notes" -> ok("""{"folders":["Haus"],"notes":[{"path":"Einkauf.md","title":"Einkauf","folder":"","modified":"${at(9)}","size":40,"pinned":true,"preview":"Milch, Brot"},{"path":"Haus/Zigbee.md","title":"Zigbee","folder":"Haus","modified":"${at(8)}","size":20,"preview":"Stick hinten links"}]}""")
                path == "/files/roots" -> ok("""[{"id":"share/Documents","name":"Documents","kind":"share"},{"id":"share/Photos","name":"Photos","kind":"share"}]""")
                path == "/agent/accounts" -> ok("""{"accounts":["maria"]}""")
                path == "/preferences" -> ok("""{"appearance":{"theme":"graphit","accent":"blau"}}""")
                path == "/agent/runs" -> ok("""{"runs":[]}""")
                path.endsWith("/seen") -> MockResponse.Builder().code(204).build()
                !get -> ok("{}")
                else -> MockResponse.Builder().code(404).addHeader("Content-Type", "application/json").body("""{"error":{"code":"not_found","message":"fixture"}}""").build()
            }
        }
    }

    private lateinit var model: AgentModel
    private val nav = AgentNav()

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        server.dispatcher = dispatcher
        server.start()
        val nas = NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null)
        model = AgentModel(AgentApi(nas), ChatCache(null), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }

    @After
    fun stop() {
        server.close()
    }

    private fun show(dark: Boolean) {
        Appearance.theme = if (dark) "graphit" else "hell"
        compose.setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val tr = remember { Tr.load(context, "de") }
            val speech = remember { Speech(context, scope).also { it.tr = tr; it.model = model } }
            AgentTheme(model.look) { AgentShell(model, nav, AppHooks("1.00", "pc", {}, "alex"), speech, tr) }
        }
        until { model.listsReady }
        settle()
    }

    private fun settle(ms: Long = 1200) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(40)
        }
    }

    /** Wartet, bis [ok] stimmt (das Modell holt seine Daten auf eigenen Fäden). */
    private fun until(ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "Zeit abgelaufen" }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(30)
        }
    }

    private fun capture(name: String, dark: Boolean) {
        settle(600)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png")
    }

    private fun screens(dark: Boolean) {
        show(dark)
        capture("14-start", dark)
        model.newChat(null, asFresh = true); capture("16-neuer-chat", dark)
        model.newChat(null); compose.onRoot(); settle()
        nav.menuOpen = true; capture("32-menu", dark); nav.menuOpen = false
        model.open("c9aaaaaaaaaa0001"); until { model.current?.entries?.size == 5 }
        capture("10-chat", dark)
        nav.layer = Layer.Head; capture("11-kopf-blatt", dark)
        nav.layer = Layer.Message(model.current!!.entries[2]); capture("12-nachricht-blatt", dark)
        nav.layer = Layer.More; capture("34-chat-mehr", dark)
        nav.layer = Layer.Plus; capture("35-plus", dark)
        nav.layer = null; model.find = "Update"; capture("02-suche", dark); model.find = null
        model.draft = "/"; capture("04-befehle", dark); model.draft = ""
        nav.layer = Layer.Steps(blocks(model.current!!.entries).filterIsInstance<Block.Steps>().first()); capture("36-schritte", dark)
        nav.layer = null
        nav.show(AgentView.Memory); capture("13-gedaechtnis", dark)
        nav.show(AgentView.Knowledge); capture("40-wissen", dark)
        nav.show(AgentView.Planned); capture("41-geplant", dark)
        nav.show(AgentView.Settings); capture("15-einstellungen", dark)
        for (part in listOf("models", "speech", "personality", "initiative", "features", "photos", "pim", "push", "look", "gestures", "assistant", "about")) {
            nav.show(AgentView.Settings, part); capture("50-set-$part", dark)
        }
        nav.show(AgentView.Chat); nav.projectsTab = true; capture("61-projekte", dark); nav.projectsTab = false
        nav.layer = Layer.ProjectOpen(model.projects.first()); capture("62-projekt", dark)
        nav.layer = Layer.ProjectEdit(model.projects.first()); capture("63-projekt-bearbeiten", dark)
        nav.layer = Layer.Notes; capture("60-notizen", dark)
        nav.layer = Layer.Archive; capture("64-archiv", dark)
        nav.layer = Layer.Feature("guard"); capture("65-funktion-waechter", dark)
        nav.layer = Layer.Talk; capture("80-sprechmodus", dark)
        nav.layer = null
    }

    @Test fun dunkel() = screens(true)

    @Test fun hell() = screens(false)

    /** Ein laufender Chat: die Figur mit dem Wort zu docker_list, Bildserie für die Bewegung. */
    @Test fun figur() {
        busyChat = true
        show(true)
        model.open("c9aaaaaaaaaa0001")
        until { model.busy && model.live.step != null }
        for (i in 0 until 4) { settle(500); compose.onRoot().captureRoboImage("build/outputs/roborazzi/70-figur-$i-dunkel.png") }
    }

    /** Große Bildschirme wie das Web am PC: Seitenleiste und Inhalt. */
    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-xhdpi")
    fun breit() {
        show(true)
        model.open("c9aaaaaaaaaa0001"); until { model.current?.entries?.size == 5 }
        capture("90-breit-chat", true)
        nav.show(AgentView.Settings); capture("91-breit-einstellungen", true)
        nav.show(AgentView.Memory); capture("92-breit-gedaechtnis", true)
    }

    /** Schrift 200 %: nichts wird abgeschnitten, alles bricht um. */
    @Test
    @Config(qualifiers = "de-w390dp-h844dp-xxhdpi")
    fun schrift200() {
        Appearance.fontScale = 2f
        try {
            show(false)
            capture("95-schrift200-start", false)
            model.open("c9aaaaaaaaaa0001"); until { model.current?.entries?.size == 5 }
            capture("96-schrift200-chat", false)
            nav.show(AgentView.Settings); capture("97-schrift200-einstellungen", false)
        } finally {
            Appearance.fontScale = 1f
        }
    }
}
