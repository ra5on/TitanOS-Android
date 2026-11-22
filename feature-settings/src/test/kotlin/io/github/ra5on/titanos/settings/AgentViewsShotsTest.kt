package io.github.ra5on.titanos.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.pages.smarthome.AdvisorReportSheet
import io.github.ra5on.titanos.settings.pages.smarthome.BuddyViewSheet
import io.github.ra5on.titanos.settings.pages.smarthome.CatalogViewSheet
import io.github.ra5on.titanos.settings.pages.smarthome.DeadlinesViewSheet
import io.github.ra5on.titanos.settings.pages.smarthome.EmergencyPanel
import io.github.ra5on.titanos.settings.pages.smarthome.GuardPanel
import io.github.ra5on.titanos.settings.pages.smarthome.HandbookPanel
import io.github.ra5on.titanos.settings.pages.smarthome.PaperViewSheet
import io.github.ra5on.titanos.settings.pages.smarthome.WhatIfPlaySheet
import io.github.ra5on.titanos.ui.components.OverlayHost
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Antworten für die Ansichten der Funktionen des KI-Agenten (Formen wie web/src/lib/features im Web, Beispieldaten). */
object AgentViewFixtures {
    const val paper = """{"month":"2026-11","months":["2026-11","2026-10","2026-09"],"total":128437,"count":7,"prevTotal":99010,
      "types":[{"type":"energy","cents":8900,"count":1,"senders":["Stadtwerke"]},{"type":"insurance","cents":45210,"count":3,"senders":["Allianz","HUK"]}],
      "open":[{"id":"p1","name":"Rechnung-2026-11.pdf","state":"filed","origin":"mail","root":"share/daten","path":"Rechnungen/r.pdf","at":"2026-11-20T08:00:00Z","sender":"Telekom","type":"telecom","kind":"Rechnung","cents":3999,"due":"2026-11-30"}],
      "recent":[{"id":"p2","name":"Scan_0042.pdf","state":"filed","origin":"inbox","to":"Versicherung › HUK","root":"share/daten","path":"Eingang/Scan_0042.pdf","at":"2026-11-21T07:00:00Z","type":"insurance"},
                {"id":"p3","name":"Brief.pdf","state":"ask","origin":"inbox","root":"share/daten","path":"Eingang/Brief.pdf","at":"2026-11-21T06:00:00Z","type":"other","doubt":["tax","housing"]}]}"""
    const val inbox = """{"items":[{"id":"i1","kind":"deadline","title":"Kündigungsfrist Handyvertrag","body":"Bis 15.12.2026 kündigen, sonst verlängert er sich um 12 Monate.","created":"2026-11-20T08:00:00Z","data":{"deadline":"cancel","root":"share/daten","path":"Verträge/handy.pdf"}},
      {"id":"i2","kind":"deadline","title":"Rechnung Telekom bezahlen","body":"39,99 € bis 30.11.2026","created":"2026-11-20T09:00:00Z","data":{"deadline":"payment","paper":"p1","debit":"0"}},
      {"id":"i3","kind":"note","title":"anderes"}]}"""
    const val guard = """{"enabled":true,"mode":"lock","watching":true,"error":"","overflows":0,"alerts":[
      {"id":"a1","share":"daten","volume":"daten","client":{"user":"kim","ip":"192.168.178.44","machine":"laptop-kim"},"reason":"known-extension","mode":"lock","first":"2026-11-22T09:14:00Z","last":"2026-11-22T09:15:00Z","count":214,
       "files":[{"was":"Fotos/urlaub.jpg","now":"Fotos/urlaub.jpg.locked","kind":1},{"was":"Texte/brief.docx","now":"Texte/brief.docx.locked","kind":1},{"was":"x","now":"x.locked","kind":1}],
       "state":"stopped","locked":true,"btrfs":true,"snapshot":"guard-a1","snapshotAt":"2026-11-22T09:14:00Z","restorable":214},
      {"id":"a0","share":"medien","volume":"daten","client":{"ip":"192.168.178.50"},"reason":"rewrite","mode":"report","first":"2026-11-02T20:00:00Z","last":"2026-11-02T20:01:00Z","count":12,"files":[],"state":"dismissed","locked":false,"btrfs":true}]}"""
    const val handbook = """{"made":"2026-11-22T03:00:00Z","volumes":[{"name":"daten","line":"RAID 1 aus 2 Platten (sda, sdb), Btrfs, 4 TB"}],
      "sections":[{"id":"disk","title":"Eine Platte fällt aus","sub":"Volume daten","steps":["Rote Meldung lesen","Platte gleicher Größe kaufen","Unter Speicher › Ersetzen tauschen"]},
                  {"id":"stick","title":"Der USB-Stick ist kaputt","sub":"Systemstick","steps":["Neuen Stick mit dem Abbild beschreiben","Einstellungen aus der Sicherung zurückholen"]}],
      "facts":{"host":"titan","shares":[{"name":"daten"},{"name":"medien"}],"apps":[{"name":"Immich"}],"vms":[{"name":"Windows 11"}],"network":[{"name":"LAN","detail":"192.168.178.20"}],"remote":[{"name":"Tailscale","detail":"titan.tail1234.ts.net"}],"backups":[{"name":"Täglich","detail":"auf Backup-NAS"}],"contacts":[{"name":"Eva","phone":"0151 0000000"}]}}"""
    const val emergency = """{"enabled":true,"code":true,"now":"2026-11-22T10:00:00Z",
      "persons":[{"account":"kim","waitDays":3,"shares":["daten"],"codeSet":true}],
      "requests":[{"id":"r1","account":"kim","reason":"Alex ist im Krankenhaus.","created":"2026-11-21T10:00:00Z","due":"2026-11-24T10:00:00Z","state":"waiting","shares":["daten"]},
                  {"id":"r0","account":"kim","reason":"Test","created":"2026-10-01T10:00:00Z","due":"2026-10-04T10:00:00Z","state":"canceled","shares":["daten"]}]}"""
    const val advisor = """{"settings":{"enabled":true,"predict":true,"bottlenecks":true,"prices":true,"monthly":false,"at":"03:30"},"running":false,"web":true,
      "saved":{"lastRun":"2026-11-22T03:30:00Z","history":true,"report":{"at":"2026-11-22T03:30:00Z","findings":[
        {"area":"disk","subject":"sdb","level":"fail","code":"realloc","from":8,"to":64,"weeks":3,"hours":38000},
        {"area":"memory","subject":"RAM","level":"tight","code":"swap","percent":82,"suggestBytes":17179869184},
        {"area":"volume","subject":"daten","level":"watch","code":"fullIn","days":120},
        {"area":"disk","subject":"sda","level":"ok","code":"healthy","hours":12000}]},
        "offers":{"sdb":[{"title":"WD Red Plus 4 TB","url":"https://example.org/wd","price":"109,90"}]}}}"""
    const val whatIf = """{"scenario":{"kind":"disk","disk":"sdb","volume":"daten"},"volume":"daten","level":"raid1","filesystem":"btrfs","disks":2,"disk":"sdb","serial":"ZDH0XYZ","outcome":"redundant","dataAtRiskBytes":0,
      "rebuild":{"seconds":30600,"bytes":2400000000000,"bps":160000000,"measured":true},"risk":{"level":"elevated","reasons":[{"code":"sameAge","disk":"sda","value":38000}]},
      "gaps":[{"share":"medien","days":9,"bytes":52000000000}],"safe":[{"share":"daten","job":"j1","at":"2026-11-22T02:00:00Z"}],
      "advice":[{"code":"startBackup","share":"medien","job":"j2"},{"code":"replacement","kind":"hdd","bytes":4000000000000},{"code":"addSpare"}]}"""
    const val buddy = """{"settings":{"enabled":true,"address":"nas-alex.tail1234.ts.net","volume":"daten","protectDays":30},"name":"titan","code":null,"volumes":["daten"],"shares":["daten","fotos"],"listening":["tailscale"],"problem":"",
      "partners":[{"id":"b1","name":"Eva","address":"nas-eva.tail1234.ts.net","quota":500000000000,"used":120000000000,"usedKnown":true,"theirQuota":300000000000,"shares":["fotos"],"paired":"2026-10-01T10:00:00Z",
        "job":{"lastRun":"2026-11-22T02:00:00Z","lastOK":"2026-11-22T02:00:00Z","bytes":180000000000,"running":false,"next":"2026-11-29T02:00:00Z","probe":{"at":"2026-11-01T04:00:00Z","files":20,"bytes":80000000,"error":""}}}]}"""
    const val catalog = """{"skills":[{"key":"titanos/rechnungen","id":"rechnungen","source":"titanos","sourceName":"TitanOS-Apps","name":"Rechnungen sortieren","description":"Legt Rechnungen nach Absender ab.","when":"Wenn eine PDF im Eingang landet","steps":"1. Lesen\n2. Absender finden\n3. Ablegen","tools":["files_list","file_write"],"digest":"d1","unknown":[],"installed":false},
      {"key":"own/wetter","id":"wetter","source":"own","sourceName":"Meine Quelle","name":"Wetter am Morgen","when":"Jeden Morgen","steps":"Wetter holen","tools":["web_search"],"author":"Eva","digest":"d2","unknown":["weather_api"],"installed":true}]}"""
    const val skills = """{"skills":[{"id":"s1","name":"Wochenbericht"},{"id":"s0","name":"Eingebaut","builtIn":true}]}"""

    fun install(nas: FakeNas) {
        nas.on("GET agent/paper", paper)
        nas.on("GET agent/inbox", inbox)
        nas.on("GET agent/protect/guard", guard)
        nas.on("GET agent/protect/handbook", handbook)
        nas.on("GET agent/protect/me", """{"guard":true,"handbook":true,"emergency":true,"alerts":1,"requests":1}""")
        nas.on("GET agent/protect/emergency", emergency)
        nas.on("GET agent/advisor", advisor)
        nas.on("GET agent/whatif", """{"enabled":true,"levels":["raid1","raid5"]}""")
        nas.on("POST agent/whatif", whatIf)
        nas.on("GET agent/buddy", buddy)
        nas.on("GET agent/catalog", catalog)
        nas.on("GET agent/skills", skills)
        nas.on("GET storage", """{"volumes":[{"name":"daten","level":"raid1","filesystem":"btrfs","members":[{"disk":"sda"},{"disk":"sdb"}]}]}""")
    }
}

/** Die Ansichten der Funktionen des KI-Agenten in der Systemsteuerung; hell, dunkel, 200 %, breit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h1400dp-xhdpi", sdk = [36])
class AgentViewsShotsTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        AgentViewFixtures.install(nas)
        nas.start()
    }

    @After
    fun stop() {
        nas.stop()
        shots.assertClean()
    }

    private var view by mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null)
    private var round by mutableIntStateOf(0)
    private var started = false

    private fun host(dark: Boolean, wait: String, content: @Composable BoxScope.() -> Unit) {
        val before = nas.requests.size
        view = content
        round++
        if (!started) {
            started = true
            shots.show(dark) {
                val context = LocalContext.current
                val tr = remember { Tr.load(context, "settings-i18n", "de") }
                val env = remember { nas.env() }
                CompositionLocalProvider(LocalTr provides tr, LocalEnv provides env) {
                    key(round) { OverlayHost { Box(Modifier.fillMaxSize()) { view?.invoke(this) } } }
                }
            }
        }
        shots.until { nas.requests.drop(before).any { it.second.startsWith(wait) } }
        shots.settle(900)
    }

    private fun panel(dark: Boolean, wait: String, content: @Composable () -> Unit) =
        host(dark, wait) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() } }

    private fun all(dark: Boolean) {
        host(dark, "agent/paper") { PaperViewSheet {} }
        shots.capture("views-10-papierkram", dark)
        host(dark, "agent/inbox") { DeadlinesViewSheet {} }
        shots.capture("views-11-fristen", dark)
        panel(dark, "agent/protect/guard") { GuardPanel() }
        shots.capture("views-12-waechter", dark)
        panel(dark, "agent/protect/handbook") { HandbookPanel() }
        compose.onAllNodes(hasText("Eine Platte fällt aus")).onFirst().performScrollTo().performClick()
        shots.settle(400)
        shots.capture("views-13-handbuch", dark)
        panel(dark, "agent/protect/emergency") { EmergencyPanel("alex", admin = true) }
        shots.capture("views-14-notfallzugang", dark)
        host(dark, "agent/advisor") { AdvisorReportSheet {} }
        shots.capture("views-15-berater", dark)
        val start = jobj("kind" to "disk", "disk" to "sdb", "volume" to "daten")
        host(dark, "agent/whatif") { WhatIfPlaySheet(listOf(start, jobj("kind" to "full", "volume" to "daten")), start) {} }
        shots.capture("views-16-durchspielen", dark)
        host(dark, "agent/buddy") { BuddyViewSheet {} }
        shots.capture("views-17-partner", dark)
        host(dark, "agent/skills") { CatalogViewSheet {} }
        compose.onAllNodes(hasText("Rechnungen sortieren")).onFirst().performScrollTo().performClick()
        shots.settle(400)
        shots.capture("views-18-katalog", dark)
    }

    @Test fun hell() = all(false)
    @Test fun dunkel() = all(true)
    @Test @Config(qualifiers = "de-w1280dp-h1400dp-xhdpi") fun breit() { shots.prefix = "breit-"; all(true) }
    @Test fun schrift200() { org.robolectric.RuntimeEnvironment.setFontScale(2f); shots.prefix = "schrift200-"; all(false) }

    @Test
    fun whatIfSendsOnlyTheScenario() {
        val start = jobj("kind" to "disk", "disk" to "sdb", "volume" to "daten", "level" to null)
        host(false, "agent/whatif") { WhatIfPlaySheet(listOf(start), start) {} }
        val body = Json.parseToJsonElement(nas.requests.last { it.first == "POST" && it.second == "agent/whatif" }.third) as JsonObject
        assertEquals(setOf("kind", "disk", "volume"), body.keys)
    }
}
