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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Sicherheit und Konten: Nutzer, Zugang, API, Benachrichtigungen, Cluster, MCP; hell, dunkel, breit, 200 %. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [36])
class SecurityShotsTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        SecurityFixtures.install(nas)
        nas.start()
    }

    @After
    fun stop() {
        nas.stop()
        shots.assertClean()
    }

    private fun waitFor(path: String) = shots.until { nas.requests.any { it.second.substringBefore('?') == path } }

    private fun open(text: String) {
        compose.onAllNodes(hasText(text)).onFirst().performScrollTo().performClick()
        shots.settle(400)
    }

    private fun screens(dark: Boolean, part: Int) {
        val nav = SettingsNav()
        shots.show(dark) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        if (part == 1) {
            nav.openPage("users"); waitFor("users"); shots.capture("20-nutzer", dark)
            nav.openPage("access"); waitFor("terminal"); shots.capture("21-zugang", dark)
            open("SSH-Zugang einschalten"); open("Nur aus diesen Netzen"); shots.capture("21-zugang-ssh", dark)
            open("Zertifikat der Weboberfläche"); open("Sperren bei Leerlauf"); shots.capture("21-zugang-zertifikat", dark)
            nav.openPage("api"); waitFor("account/tokens"); shots.capture("22-api", dark)
            compose.onAllNodes(hasText("Token erstellen")).onFirst().performClick(); shots.capture("22-api-neu", dark)
        } else {
            nav.openPage("notifications"); waitFor("notifications/settings"); shots.capture("23-benachrichtigungen", dark)
            open("Mitteilungen auf diesem Gerät"); open("Bericht über das NAS"); open("E-Mail"); shots.capture("23-benachrichtigungen-offen", dark)
            nav.openPage("mcp"); waitFor("system/mcp"); shots.capture("24-mcp", dark)
            open("Dateien"); shots.capture("24-mcp-dateien", dark)
            nav.openPage("cluster"); waitFor("cluster"); shots.capture("25-cluster-ausserhalb", dark)
            nas.on("GET cluster", SecurityFixtures.clusterInside)
            nav.openPage("mcp"); shots.settle(200); nav.openPage("cluster"); shots.until { compose.onAllNodes(hasText("keller")).fetchSemanticsNodes().isNotEmpty() }
            shots.capture("25-cluster", dark)
            open("keller"); shots.until { nas.requests.any { it.second.startsWith("cluster/nodes/n2/api/notifications") } }; shots.capture("25-cluster-knoten", dark)
        }
    }

    @Test fun hell1() = screens(false, 1)

    @Test fun hell2() = screens(false, 2)

    @Test fun dunkel1() = screens(true, 1)

    @Test fun dunkel2() = screens(true, 2)

    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-xhdpi")
    fun breit1() { shots.prefix = "breit-"; screens(true, 1) }

    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-xhdpi")
    fun breit2() { shots.prefix = "breit-"; screens(true, 2) }

    @Test
    fun schrift200a() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shots.prefix = "schrift200-"
        screens(false, 1)
    }

    @Test
    fun schrift200b() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shots.prefix = "schrift200-"
        screens(false, 2)
    }
}

/** Antworten eines NAS für die Seiten „Sicherheit und Konten“ (Formen wie titand -dev; Geheimnisse sind Platzhalter). */
object SecurityFixtures {
    private val now = Instant.now()
    private fun ago(hours: Long) = now.minus(hours, ChronoUnit.HOURS).toString()

    val clusterOutside = """{"cluster":{"self":"n1","internet":false,"nodes":[],"manages":false,"extra":[],"found":["192.168.1.10:443"],"foundWays":{"192.168.1.10:443":"home"},"ways":{},"routes":{},"tunnel":{"running":false},"version":"1.0.0","protocol":1},"nodes":[]}"""

    val clusterInside = """{"cluster":{"self":"n1","name":"Zuhause","internet":false,"nodes":[{"id":"n1"},{"id":"n2"},{"id":"n3"}],"admin":"n1","manages":true,"extra":["nas.example.org"],
        "found":["192.168.1.10:443","titan.tail1234.ts.net:443"],"foundWays":{"192.168.1.10:443":"home","titan.tail1234.ts.net:443":"tailscale"},
        "ways":{"manage":"tailscale","only":false,"return":5,"alert":0,"future":7},"routes":{"n2":{"manage":"home"}},
        "tunnel":{"enabled":true,"port":51821,"address":"10.77.0.1","running":true},"version":"1.0.0","localAccounts":["gast"],"protocol":1},
        "nodes":[
          {"id":"n1","name":"titan","self":true,"online":true,"addresses":["192.168.1.10:443"],"compatible":true,"status":{"name":"titan","version":"1.0.0","hostname":"titan","uptimeSeconds":200000,"cpuPercent":12.4,"memory":{"totalBytes":16000000000,"availableBytes":9000000000},"volumes":[{"name":"daten","state":"ok","mounted":true,"sizeBytes":1,"usedBytes":1}],"warnings":0,"errors":0}},
          {"id":"n2","name":"keller","self":false,"online":true,"manage":true,"way":"home","address":"192.168.1.11:443","millis":3,"addresses":["192.168.1.11:443"],"compatible":true,"clockSkew":180,"status":{"name":"keller","version":"0.99.0","hostname":"keller","uptimeSeconds":5000,"cpuPercent":null,"memory":{"totalBytes":8000000000,"availableBytes":2000000000},"volumes":[{"name":"backup","state":"degraded","mounted":true,"sizeBytes":1,"usedBytes":1}],"warnings":1,"errors":2}},
          {"id":"n3","name":"buero","self":false,"online":false,"error":"node_unreachable","addresses":["100.64.0.3:443"],"compatible":true}
        ]}"""

    fun install(nas: FakeNas) {
        nas.on("GET session", """{"user":{"name":"alex","role":"owner","twoFactor":true},"hostname":"titan","version":"1.0.0"}""")
        nas.on("GET users", """[{"name":"alex","role":"owner","twoFactor":true,"disabled":false},{"name":"kim","role":"user","disabled":true},{"name":"sam","role":"admin"}]""")
        nas.on("GET system/ssh", """{"enabled":true,"running":true,"fingerprint":"SHA256:beispielFingerabdruck0000","accounts":["alex"],"allowedNetworks":["192.168.1.0/24"],"homeNetworks":["192.168.1.0/24"],"tailscaleNetworks":["100.64.0.0/10","fd7a:115c:a1e0::/48"],"netbirdNetworks":["100.64.0.0/10"],"clientAddress":"192.168.1.20"}""")
        nas.on("GET system", """{"hostname":"titan","network":{"ipv4":"192.168.1.10/24"}}""")
        nas.on("GET system/webdav", """{"enabled":true,"future":1}""")
        nas.on("GET system/certificate", """{"kind":"custom","names":["nas.example.org","192.168.1.10"],"issuer":"Beispiel CA","notBefore":"2026-01-01T00:00:00Z","notAfter":"${now.plus(9, ChronoUnit.DAYS)}","fingerprint":"ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12"}""")
        nas.on("GET system/login", """{"stayDays":30,"choices":[0,30,90,365,-1]}""")
        nas.on("GET preferences", """{"appearance":{"theme":"graphit"},"desktop":{"lockMinutes":15,"lockMinutesPhone":0,"icons":["files"],"recent":[]}}""")
        nas.on("GET terminal", """{"confirm":true,"enabled":true,"idleMinutes":15,"nasShell":true}""")
        nas.on("GET account/tokens", """{"scopes":["system:read","system:write","files:read","files:write","docker:read","docker:write","vms:read","vms:write","mcp"],"tokens":[{"id":"t1","name":"Heimautomation","scopes":["system:read","files:read"],"created":"2026-10-01T10:00:00Z","lastUsed":"${ago(3)}"},{"id":"t2","name":"Claude","scopes":["mcp","system:read"],"created":"2026-11-02T10:00:00Z"}]}""")
        nas.on("GET system/mcp", """{"enabled":true,"path":"/mcp","protocolVersions":["2025-06-18"],"scope":"mcp","tools":[
            {"name":"docs_search","area":"system","scope":"system:read","kind":"read","dangerous":false,"mode":"on"},
            {"name":"system_status","area":"system","scope":"system:read","kind":"read","dangerous":false,"mode":"on"},
            {"name":"files_list","area":"files","scope":"files:read","kind":"read","dangerous":false,"mode":"on"},
            {"name":"files_delete","area":"files","scope":"files:write","kind":"change","dangerous":true,"mode":"all"},
            {"name":"docker_restart","area":"docker","scope":"docker:write","kind":"change","dangerous":false,"mode":"off"}]}""")
        nas.on("GET activity", """{"entries":[{"at":"${ago(0)}","target":"files_list","ok":true},{"at":"${ago(0)}","target":"docker_restart","ok":false,"status":403}],"more":false}""")
        nas.on("GET notifications/settings", """{"secretSet":{"discord":false,"email":true,"ntfy":false,"telegram":false},"settings":{"language":"de","minSeverity":"warning","email":{"enabled":true,"host":"smtp.example.org","port":587,"security":"starttls","username":"nas@example.org","from":"nas@example.org","to":"me@example.org"},"ntfy":{"enabled":false,"url":""},"telegram":{"enabled":false,"chatID":""},"discord":{"enabled":false},"homeAssistant":{"enabled":false,"service":""},"digest":{"enabled":true,"time":"19:00","errorsNow":true},"later":{"x":1}}}""")
        nas.on("GET health-report", """{"preview":{"status":"warning","title":"Bericht – bitte ansehen","sections":[{"title":"Volumes","status":"ok","lines":["1 Volume, alles in Ordnung"]},{"title":"Sicherungen","status":"warning","lines":["Kein Sicherungsauftrag eingeschaltet."]}]},"settings":{"enabled":true,"every":"weekly","weekday":1,"day":1,"time":"08:00"},"next":"${now.plus(2, ChronoUnit.DAYS)}"}""")
        nas.on("GET notifications/push", """{"devices":[{"id":"d1","name":"Pixel 9","service":"fcm","app":"titanos","created":"2026-11-01T10:00:00Z","off":["camera"]},{"id":"d2","name":"","service":"Firefox","app":"agent","created":"2026-11-10T10:00:00Z"}],"publicKey":"pk_beispiel"}""")
        nas.on("GET mail/access", """{"enabled":true}""")
        nas.on("GET cluster", clusterOutside)
        nas.on("GET notifications", """{"messages":[{"id":5,"time":"${ago(2)}","severity":"error","kind":"x_unbekannt","subject":"backup","title":"Volume beschädigt","text":"Das Volume backup ist beschädigt."},{"id":6,"time":"${ago(1)}","severity":"warning","kind":"x_unbekannt2","subject":"","title":"Platte warm","text":"Platte sdb hat 55 °C."}]}""")
        nas.on("GET cluster/nodes/n2/api/notifications", """{"messages":[{"id":5,"time":"${ago(2)}","severity":"error","kind":"x_unbekannt","subject":"backup","title":"Volume beschädigt","text":"Das Volume backup ist beschädigt."},{"id":6,"time":"${ago(1)}","severity":"warning","kind":"x_unbekannt2","subject":"","title":"Platte warm","text":"Platte sdb hat 55 °C."}]}""")
    }
}
