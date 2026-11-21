package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Antworten eines NAS mit Home Assistant, Sprachdiensten und KI-Agent (Beispieldaten, Platzhalter-Geheimnisse). */
object SmartHomeFixtures {
    val haConnections = """{"connections":[{"id":"main","name":"","url":"http://homeassistant.local:8123","enabled":true,"active":true,"mqtt":true},{"id":"c2","name":"Ferienhaus","url":"","enabled":false,"active":false,"mqtt":false}],"max":8}"""
    val haSettings = """{"agentDomains":["automation","light","lock","sensor","switch","cover"],"commandGroups":["containers","vms","backups","updates","power","agent"],
        "diagnostics":{"connected":true,"evictedTransitions":2,"lastConnected":"2026-11-20T08:15:00Z","lastDisconnected":"2026-11-19T22:01:00Z","watchedEntities":14},
        "domains":["cover","light","lock","sensor","switch"],
        "mqttDevices":[{"group":"system","id":"nas","name":"NAS"},{"group":"volumes","id":"daten","name":"daten"},{"group":"volumes","id":"medien","name":"medien"}],
        "mqttPasswordSet":true,"sensorGroups":["system","volumes","disks","containers","backups"],
        "settings":{"enabled":true,"url":"http://homeassistant.local:8123","fingerprint":"","domains":["light"],"entities":["lock.haustuer"],"account":"alex","future":"bleibt",
          "policies":{"lock.haustuer":{"aliases":["Haustür"],"readOnly":false,"services":null,"description":"Die Tür vorne"}},
          "mqtt":{"enabled":true,"host":"homeassistant.local","port":1883,"username":"titanos","tls":false,"prefix":"homeassistant","groups":["system","volumes"],"commands":["containers"],"interval":30,"id":"a6e9925c2915","devices":{"volumes":["daten"]}}},
        "status":{"mqtt":false,"mqttError":"connection refused","watchError":"","watching":true},"tokenSet":true}"""
    val haStorage = """{"account":"homeassistant","accountMissing":false,"server":"192.168.178.20","homeAssistant":true,
        "entries":[{"share":"homeassistant","usage":"backup","mount":"titanos_backup","created":"2026-11-01T10:00:00Z","maxAgeDays":7,"backup":{"checked":"2026-11-20T06:00:00Z","latest":"2026-11-12T03:00:00Z","name":"Automatisch","overdue":true}},
                   {"share":"medien","folder":"Musik","existing":true,"readOnly":true,"usage":"media","mount":"titanos_musik","created":"2026-11-02T10:00:00Z","maxAgeDays":0}],
        "shares":[{"name":"medien","volume":"daten"}],"volumes":["daten"]}"""
    val haEntities = """{"entities":[{"id":"light.flur","name":"Flur","area":"Flur","state":"on","domain":"light"},{"id":"lock.haustuer","name":"Haustür","area":"Eingang","state":"locked","domain":"lock"},{"id":"sensor.temp","name":"Temperatur","state":"21.5","domain":"sensor"}]}"""
    val users = """[{"name":"alex","role":"owner"},{"name":"sam","role":"admin"},{"name":"kim","role":"user"}]"""

    val speech = """{"devices":[{"id":"nvidia","title":"NVIDIA GeForce RTX 3060","available":true,"memoryUsedBytes":2000000000,"memoryTotalBytes":12000000000}],
        "hosts":[{"host":"192.168.178.20","kind":"lan"},{"host":"titan.tail1234.ts.net","kind":"tailscale"}],
        "jobs":{"model/small":{"done":250000000,"total":509607936}},
        "models":[{"id":"large-v3-turbo","bytes":1700790272,"vramBytes":2831155200,"recommended":true,"installed":true},{"id":"small","bytes":509607936,"installed":false},{"id":"tiny","bytes":81788928,"installed":false}],
        "settings":{"enabled":true,"volume":"daten","device":"nvidia","whisperModel":"large-v3-turbo","language":"de","voice":"de_DE-thorsten-medium","speed":1.2,"reach":"ha","agent":true,"later":1},
        "status":{"whisper":{"phase":"running","port":10300,"listening":true,"version":"v3.8.1","seen":{"at":"2026-11-20T09:00:00Z","from":"192.168.178.30","ok":true}},"piper":{"phase":"pulling","pullDone":100000000,"pullTotal":300000000,"port":10200,"listening":false,"version":"2.5.3"},"available":true},
        "voices":[{"id":"de_DE-thorsten-medium","name":"thorsten","language":"de_DE","languageName":"Deutsch","country":"Deutschland","quality":"medium","speakers":1,"bytes":63201294,"installed":true},
                  {"id":"de_DE-eva_k-x_low","name":"eva_k","language":"de_DE","languageName":"Deutsch","country":"Deutschland","quality":"x_low","speakers":1,"bytes":20000000,"installed":false},
                  {"id":"en_GB-alba-medium","name":"alba","language":"en_GB","languageName":"English","country":"United Kingdom","quality":"medium","speakers":1,"bytes":63000000,"installed":false}],
        "volumes":["daten","medien"]}"""

    val agentSettings = """{"enabled":true,"volume":"daten","ollama":"http://192.168.178.40:11434","model":"qwen3:8b","modelNames":{"qwen3:8b":"Hauskopf"},"think":"medium","context":32768,
        "maxSteps":30,"maxMinutes":30,"users":["sam"],"instruction":"","weeklyReport":true,"react":true,
        "policy":{"preset":"standard","folders":[{"root":"share:daten","path":"privat","mode":"deny"}],"extraAreas":{"history":"allow"}},
        "locations":[{"name":"Rechnungen","root":"share:daten","path":"Rechnungen","note":"PDFs"}],
        "homeassistant":{"release":{"domains":["light"],"extraDomains":["automation"],"entities":[]},"entities":{"domain:lock":"deny","entity:light.flur":"allow"},"notify":["notify.mobile_app_iphone"],"assist":true},
        "voice":{},"search":{},"vision":{},"code":{"timeout":60,"memory":512},
        "mcp":[{"id":"m1","name":"n8n","url":"https://n8n.example/mcp","enabled":true,"tokenSet":true}],
        "features":{"docs":{},"rev":"x"},"unbekannt":{"a":1}}"""
    val agentStatus = """{"admin":true,"allowed":true,"enabled":true,"reachable":true,"version":"0.12.3","model":"qwen3:8b","think":"medium","context":32768,"volume":true,"web":true,
        "models":[{"name":"qwen3:8b","size":5200000000,"capabilities":["tools","thinking"],"contextLength":40960,"levels":["off","low","medium","high"],"defaultLevel":"medium"},
                  {"name":"llava:7b","size":4700000000,"capabilities":["vision"],"levels":[]}],
        "running":[{"name":"qwen3:8b","contextLength":32768,"vram":6100000000}]}"""
    val agentTools = """{"tools":[{"name":"docs_search","area":"system","kind":"read","dangerous":false},{"name":"files_list","area":"files","kind":"read","dangerous":false},
        {"name":"file_write","area":"files","kind":"create","dangerous":false},{"name":"calendar_list","area":"calendar","kind":"read","dangerous":false},
        {"name":"contacts_list","area":"contacts","kind":"read","dangerous":false},{"name":"docker_list","area":"docker","kind":"read","dangerous":false},
        {"name":"docker_container_stop","area":"docker","kind":"change","dangerous":true},{"name":"ha_states","area":"home","kind":"read","dangerous":false},
        {"name":"ha_call","area":"home","kind":"change","dangerous":false},{"name":"history_read","area":"history","kind":"read","dangerous":false},
        {"name":"nvr_events","area":"nvr","kind":"read","dangerous":false},{"name":"web_search","area":"web","kind":"read","dangerous":false}]}"""
    val agentAssist = """{"actor":"alex","addresses":[{"kind":"lan","url":"http://192.168.178.20/agent-ollama"}],"areas":["home","system","web"],"consent":{},"dataAreas":["calendar","contacts"],
        "dataLevels":{"calendar":"deny","contacts":"deny"},"isActor":true,"keySet":true,"last":{"at":"2026-11-20T07:30:00Z","from":"192.168.178.30","ok":true,"question":"Ist das Licht im Flur an?","millis":1840},
        "levels":{"home":"change","system":"read","web":"deny"},"myConsent":{},"memories":3,"chats":12}"""

    fun install(nas: FakeNas) {
        nas.on("GET homeassistant/connections", haConnections)
        nas.on("GET homeassistant/settings", haSettings)
        nas.on("GET homeassistant/storage", haStorage)
        nas.on("GET homeassistant/entities", haEntities)
        nas.on("GET users", users)
        nas.on("GET speech", speech)
        nas.on("GET agent/settings", agentSettings)
        nas.on("GET agent/status", agentStatus)
        nas.on("GET agent/tools", agentTools)
        nas.on("GET agent/assist", agentAssist)
        nas.on("GET agent/cluster", """{"admin":false,"member":false}""")
        nas.on("GET agent/addresses", """{"addresses":[{"kind":"lan","url":"http://192.168.178.20/agent","https":false},{"kind":"tailscale","url":"https://titan.tail1234.ts.net/agent","https":true}]}""")
        nas.on("GET agent/volumes", """{"current":"daten","move":null,"volumes":[{"name":"daten","sizeBytes":4000000000000,"freeBytes":2500000000000,"kind":"hdd","current":true},{"name":"ssd","sizeBytes":1000000000000,"freeBytes":800000000000,"kind":"ssd","current":false,"oldCopy":true}]}""")
        nas.on("GET agent/speech", """{"hear":false,"say":false}""")
        nas.on("GET files/roots", """[{"id":"share:daten","name":"daten","kind":"share","writable":true},{"id":"vol:daten","name":"daten","kind":"volume","writable":true}]""")
        nas.on("GET docker", """{"containers":[]}""")
        nas.on("GET agent/features", """{"docs":{},"guard":{"mode":"lock","known":5,"renames":30,"rewrites":30,"notes":3,"windowSec":60,"quietSec":120},"handbook":{},"emergency":{},"whatIf":{},"advisor":{"predict":true,"bottlenecks":true,"at":"03:30"},"buddy":{"protectDays":30,"every":"weekly","time":"02:00","probe":"monthly","probeFiles":50},"catalog":{},"rev":"d9009c339cec8ab9"}""")
        nas.on("GET agent/features/me", """{"mine":{"docs":{},"paper":{}},"nas":{"docs":false,"deadlines":false,"paper":false,"guard":false,"catalog":false}}""")
        nas.on("GET agent/protect/me", """{"alerts":0,"emergency":false,"guard":false,"handbook":false}""")
        nas.on("GET agent/buddy", """{"volumes":["daten"]}""")
        nas.on("GET shares", """[{"name":"daten"}]""")
    }

    /** Ein Text aus den Web-Texten (für Klicks in Tests). */
    fun de(key: String): String {
        val raw = java.io.File("src/main/assets/settings-i18n/de.json").takeIf { it.exists() }?.readText()
            ?: java.io.File("../feature-settings/src/main/assets/settings-i18n/de.json").readText()
        return org.json.JSONObject(raw).getString(key)
    }
}

/** Home Assistant, Sprachdienste und KI-Agent mit allen Reitern; hell, dunkel, 200 %, breit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [36])
class SmartHomeShotsTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        SmartHomeFixtures.install(nas)
        nas.start()
    }

    @After
    fun stop() {
        nas.stop()
        shots.assertClean()
    }

    private fun open(dark: Boolean, path: String, wait: String): SettingsNav {
        val nav = SettingsNav()
        shots.show(dark) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        nav.openPath(path)
        shots.until { nas.requests.any { it.second.startsWith(wait) } }
        shots.settle(1200)
        return nav
    }

    private fun click(text: String) {
        compose.onAllNodes(hasText(text)).onFirst().performScrollTo().performClick()
        shots.settle(600)
    }

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasText(text)).onFirst().performScrollTo()
        shots.settle(400)
    }

    private fun ha(dark: Boolean) {
        open(dark, "/settings/homeassistant", "homeassistant/storage")
        shots.capture("ha-10-oben", dark)
        click(SmartHomeFixtures.de("ha.connection"))
        shots.capture("ha-11-verbindung", dark)
        click(SmartHomeFixtures.de("hastore.title"))
        shots.capture("ha-12-speicher", dark)
        click(SmartHomeFixtures.de("ha.mqtt"))
        shots.capture("ha-13-mqtt", dark)
        click(SmartHomeFixtures.de("ha.release"))
        click(SmartHomeFixtures.de("ha.loadEntities"))
        shots.until { nas.requests.any { it.second.startsWith("homeassistant/entities") } }
        shots.settle(800)
        scrollTo("Haustür")
        shots.capture("ha-14-freigabe", dark)
    }

    private fun speech(dark: Boolean) {
        open(dark, "/settings/speech", "speech")
        shots.capture("speech-10-oben", dark)
        scrollTo(SmartHomeFixtures.de("speech.sttTitle"))
        shots.capture("speech-11-modelle", dark)
        scrollTo(SmartHomeFixtures.de("speech.ttsTitle"))
        shots.capture("speech-12-stimmen", dark)
        scrollTo(SmartHomeFixtures.de("speech.reach"))
        shots.capture("speech-13-verwenden", dark)
    }

    private fun agentA(dark: Boolean) {
        val nav = open(dark, "/settings/agent", "agent/volumes")
        shots.capture("agent-10-uebersicht", dark)
        nav.tab = "connection"; shots.settle(900); shots.capture("agent-11-verbindung", dark)
        scrollTo(SmartHomeFixtures.de("agent.mcp.title")); shots.capture("agent-12-mcp", dark)
        nav.tab = "rights"; shots.settle(900); shots.capture("agent-13-rechte", dark)
        scrollTo(SmartHomeFixtures.de("agentpim.rights.title")); shots.capture("agent-14-aktionen", dark)
        nav.tab = "accounts"; shots.settle(900); shots.capture("agent-15-konten", dark)
    }

    private fun agentB(dark: Boolean) {
        val nav = open(dark, "/settings/agent?tab=phone", "agent/addresses")
        shots.capture("agent-20-handy", dark)
        nav.tab = "ha"; shots.until { nas.requests.any { it.second.startsWith("agent/assist") } }; shots.settle(1200); shots.capture("agent-21-ha", dark)
        scrollTo(SmartHomeFixtures.de("agent.haAssist")); shots.capture("agent-22-assist", dark)
        nav.tab = "initiative"; shots.settle(900); shots.capture("agent-23-initiative", dark)
        nav.tab = "instruction"; shots.settle(900); shots.capture("agent-24-anweisung", dark)
        nav.tab = "features"; shots.settle(1500); shots.capture("agent-25-funktionen", dark)
    }

    private fun wide() { shots.prefix = "breit-" }
    private fun big() { org.robolectric.RuntimeEnvironment.setFontScale(2f); shots.prefix = "schrift200-" }

    @Test fun haHell() = ha(false)
    @Test fun haDunkel() = ha(true)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-xhdpi") fun haBreit() { wide(); ha(true) }
    @Test fun haSchrift200() { big(); ha(false) }

    @Test fun speechHell() = speech(false)
    @Test fun speechDunkel() = speech(true)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-xhdpi") fun speechBreit() { wide(); speech(true) }
    @Test fun speechSchrift200() { big(); speech(false) }

    @Test fun agentAHell() = agentA(false)
    @Test fun agentADunkel() = agentA(true)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-xhdpi") fun agentABreit() { wide(); agentA(true) }
    @Test fun agentASchrift200() { big(); agentA(false) }

    @Test fun agentBHell() = agentB(false)
    @Test fun agentBDunkel() = agentB(true)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-xhdpi") fun agentBBreit() { wide(); agentB(true) }
    @Test fun agentBSchrift200() { big(); agentB(false) }
}
