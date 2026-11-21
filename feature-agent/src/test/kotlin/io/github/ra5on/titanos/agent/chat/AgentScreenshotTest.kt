package io.github.ra5on.titanos.agent.chat

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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

    /** Funktionen aus den Web-Tests scripts/test-ui-agent54-*.mjs (tools/fixtures54.mjs). */
    private val fx: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("agent54-fixtures.json")!!.readText(),
    ) as kotlinx.serialization.json.JsonObject
    private fun fx(key: String) = fx[key].toString()

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath.removePrefix("/api/v1")
            val get = request.method == "GET"
            fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
            return when {
                path == "/agent/status" -> ok(status)
                path == "/storage" -> ok(fx("storage"))
                path == "/agent/whatif" && get -> ok("""{"enabled":true,"levels":["raid1","raid5","raid6","raid10","raid1c3"]}""")
                path == "/agent/whatif" -> ok(if (request.body?.utf8()?.contains("\"full\"") == true) fx("full") else fx("disk"))
                path == "/agent/advisor" -> ok(fx("advisor"))
                path == "/agent/paper" -> ok(fx("paper"))
                path == "/agent/buddy" -> ok(fx("buddy"))
                path == "/agent/protect/handbook" -> ok(fx("handbook"))
                path == "/agent/protect/emergency" -> ok(fx("emergency"))
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
                path == "/agent/features/me" -> ok("""{"mine":{"docs":{},"paper":{}},"nas":{"docs":true,"deadlines":true,"paper":true,"guard":true,"handbook":true,"emergency":true,"whatIf":true,"advisor":true,"buddy":true,"catalog":true}}""")
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

    private var prefix = ""

    /** Texte, deren Zeilen nicht in ihren Platz passen (abgeschnitten, ausgeblendet), je Bildschirm. */
    private val cut = mutableListOf<String>()

    private fun capture(name: String, dark: Boolean) {
        settle(600)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$prefix$name-${if (dark) "dunkel" else "hell"}.png")
        overflow(name)
    }

    /** Mit Dialogen (Wähler): alle Fenster. */
    private fun captureScreen(name: String, dark: Boolean) {
        settle(600)
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/outputs/roborazzi/$prefix$name-${if (dark) "dunkel" else "hell"}.png")
    }

    private fun overflow(name: String) {
        // Was TalkBack ansagt: jedes Bedienelement hat einen Namen (Text oder Beschreibung).
        for (node in compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()) {
            val cfg = node.config
            val named = cfg.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.any { it.text.isNotBlank() } == true ||
                cfg.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }?.any { it.isNotBlank() } == true ||
                cfg.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.EditableText) { null } != null
            if (!named && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f) cut += "$prefix$name: Bedienelement ohne Namen bei ${node.boundsInRoot}"
        }
        for (node in compose.onAllNodes(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isRoot()), useUnmergedTree = true).fetchSemanticsNodes()) {
            val get = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { null } ?: continue
            val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            get.action?.invoke(out)
            val r = out.firstOrNull() ?: continue
            // Nicht gefüllte Platzhalter oder rohe Schlüssel.
            val text = r.layoutInput.text.text
            if (Regex("\\{[a-zA-Z]+\\}").containsMatchIn(text) || Regex("^(agent|agentpim|common)\\.[a-zA-Z.]+$").matches(text.trim()) || Regex("\\bagent\\.f\\.[a-z]").containsMatchIn(text))
                cut += "$prefix$name: Platzhalter „${text.take(80)}“"
            // Breiter oder höher als der Platz, oder mit „…“ gekürzt: abgeschnitten.
            val widest = (0 until r.lineCount).maxOfOrNull { r.getLineRight(it) - r.getLineLeft(it) } ?: 0f
            val bottom = if (r.lineCount > 0) r.getLineBottom(r.lineCount - 1) else 0f
            val dots = (0 until r.lineCount).any { r.isLineEllipsized(it) }
            if (widest > node.size.width + 2 || bottom > node.size.height + 2 || dots)
                cut += "$prefix$name: „${r.layoutInput.text.text.take(60)}“ (${widest.toInt()}×${bottom.toInt()} in ${node.size})"
        }
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
        for ((id, n) in listOf("guard" to "waechter", "whatif" to "was-waere-wenn", "paper" to "papierkram", "handbook" to "handbuch", "emergency" to "notfall", "advisor" to "berater", "buddy" to "partner")) {
            nav.layer = Layer.Feature(id); capture("65-funktion-$n", dark)
        }
        nav.layer = null
        nav.show(AgentView.Planned); settle()
        // Die Liste von Geplant (nicht die Seitenleiste in Split und Breit).
        fun list() = compose.onNodeWithTag("planned-list")
        list().performScrollToNode(androidx.compose.ui.test.hasText("Auftrag oder Erinnerung", substring = true)); settle(300)
        compose.onAllNodesWithText("Auftrag oder Erinnerung", substring = true).onFirst().performClick(); settle(300)
        list().performScrollToNode(androidx.compose.ui.test.hasContentDescription("Uhrzeit"))
        capture("42-geplant-formular", dark)
        compose.onAllNodesWithContentDescription("Uhrzeit").onFirst().performClick()
        captureScreen("43-zeitwaehler", dark)
        val toggle = if (compose.onAllNodesWithText("Eintippen").fetchSemanticsNodes().isNotEmpty()) "Eintippen" else "Uhr"
        compose.onAllNodesWithText(toggle).onFirst().performClick()
        captureScreen("44-zeit-umschalten", dark)
        compose.onAllNodesWithText("Abbrechen").onFirst().performClick(); settle(300)
        nav.show(AgentView.Chat)
        nav.layer = Layer.Talk; capture("80-sprechmodus", dark)
        nav.layer = null
    }

    @After
    fun noCut() {
        check(cut.isEmpty()) { "Abgeschnittene Texte:\n" + cut.distinct().joinToString("\n") }
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

    /** Split (761–899 dp): Seitenleiste und Inhalt, Leiste unten bleibt – jeder Bildschirm. */
    @Test
    @Config(qualifiers = "de-w840dp-h1000dp-xhdpi")
    fun split() { prefix = "split-"; screens(true) }

    /** Wide wie das Web am PC: Seitenleiste und Inhalt – jeder Bildschirm. */
    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-xhdpi")
    fun breit() { prefix = "breit-"; screens(true) }

    /** Schrift 200 % (Android): nichts wird abgeschnitten, alles bricht um – jeder Bildschirm. */
    @Test
    @Config(qualifiers = "de-w390dp-h844dp-xxhdpi")
    fun schrift200() {
        // Wie „Schriftgröße“ 200 % in den Bedienungshilfen von Android: jeder Bildschirm.
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        prefix = "schrift200-"
        screens(false)
    }
}
