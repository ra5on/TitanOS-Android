package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.junit4.createComposeRule
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

/** Antworten eines NAS für die Seiten unter „System“ (Formen wie titand, Platzhalter statt Geheimnissen). */
object SystemFixtures {
    val UPDATE_AVAILABLE = """{"version":"1.14.0","release":true,
      "slots":[{"name":"a","version":"1.14.0","running":true,"complete":true,"next":true,"triesLeft":null,"kernel":"6.12.57+deb13-amd64","variant":"stable"},
               {"name":"b","version":"1.13.0","running":false,"complete":true,"next":false,"triesLeft":null,"kernel":"6.12.48+deb13-amd64","variant":"stable"}],
      "settings":{"channel":"stable","autoInstall":true,"installTime":"03:30","checkEvery":720,"future":"x"},
      "canVerify":true,
      "available":{"version":"1.15.0","channel":"stable","kernel":"6.12.57+deb13-amd64","released":"2026-11-19T10:00:00Z",
        "changelog":"# TitanOS 1.15 — Mehr Einstellungen in der App\n\n## Neu\n- **Systemsteuerung** nativ in der App\n- Updates zeigen ihre Schritte\n## Sicherheit\n- Abhängigkeiten aktualisiert\n## Behoben\n- Lange Namen brechen um",
        "files":[{"size":734003200},{"size":52428800}],"app":{"base":"1.14.0","size":41943040}},
      "lastCheck":"2026-11-21T08:00:00Z","nextCheck":"2026-11-21T20:00:00Z","checkError":"",
      "progress":{"phase":"","file":"","percent":0,"speedBytesPerSecond":0,"error":""},
      "pending":null,"canRollback":true,"unconfirmed":false,"base":"1.14.0","kind":"app",
      "app":{"current":"1.14.2","previous":"1.14.1","trial":false,"canRollback":true},"variant":"stable","bootLoader":"systemd-boot"}"""

    val UPDATE_PENDING = UPDATE_AVAILABLE
        .replace(""""pending":null""", """"pending":{"kind":"update","version":"1.15.0","slot":"b"}""")
        .replace(Regex(""""available":\{.*?"app":\{"base":"1.14.0","size":41943040\}\}""", RegexOption.DOT_MATCHES_ALL), """"available":null""")

    val UPDATE_WORKING = UPDATE_AVAILABLE.replace(""""progress":{"phase":"","file":"","percent":0,"speedBytesPerSecond":0,"error":""}""", """"progress":{"phase":"download","file":"root.img","percent":42,"speedBytesPerSecond":11800000,"error":""}""")
        .replace(""""kind":"app"""", """"kind":"system"""")

    const val VERSIONS = """{"versions":[
      {"version":"1.15.0","channel":"stable","released":"2026-11-19T10:00:00Z","kernel":"6.12.57","size":786432000,"changelog":"# TitanOS 1.15 — Mehr Einstellungen","running":false,"newest":true},
      {"version":"1.14.0","channel":"stable","released":"2026-11-12T10:00:00Z","kernel":"6.12.57","size":780000000,"changelog":"# TitanOS 1.14 — Fernansicht\n## Neu\n- Fernansicht schneller\n- Neue Hintergründe","running":true,"newest":false}],"floor":"0.46.2"}"""

    const val SERVICES = """{"docker":{"enabled":true,"running":3,"total":5,"available":true},"vms":{"enabled":true,"running":0,"total":2,"available":true},"lxc":{"enabled":false,"running":0,"total":0,"available":false}}"""
    const val STARTUP = """{"readySince":"2026-11-21T07:00:00Z","settings":{"extra":30,"future":true,"order":[{"kind":"vm","name":"homeassistant","wait":30},{"kind":"app","name":"immich","wait":10,"off":false,"note":"x"}]}}"""
    const val DOCKER = """{"containers":[{"name":"immich-server","app":"immich"},{"name":"jellyfin","app":"jellyfin"}]}"""
    const val VMS = """{"vms":[{"name":"homeassistant","autostart":true},{"name":"win11","autostart":false}]}"""

    const val VIEWER_SETTINGS = """{"gpu":"auto","quality":"auto","fps":30,"transport":"auto","audio":true,"volume":"","later":1}"""
    const val VIEWER_STATUS = """{"image":{"state":"downloading","version":"1.14.0","bytes":402653184,"size":805306368,"progress":0.5,"usable":true},"gpu":"vaapi",
      "gpus":[{"id":"/dev/dri/renderD128","kind":"vaapi","vendor":"8086","title":"Intel UHD 630","available":true}],
      "sessions":[{"user":"alex","app":"jellyfin","state":"open","windows":1,"gpu":"vaapi","memoryBytes":734003200}],
      "memory":{"availableBytes":10887028736,"reserveBytes":734003200,"perViewBytes":1073741824,"more":9},"idleMinutes":15}"""
    const val STORAGE = """{"disks":[],"volumes":[{"name":"daten","state":"ok","mounted":true},{"name":"sicherung","state":"degraded","mounted":true,"encryption":{"kind":"luks"}}]}"""

    const val ABOUT = """{"kernel":"6.12.57+deb13-amd64","license":"Functional Source License, Version 1.1, ALv2 Future License\n\nAbbreviation: FSL-1.1-ALv2","thirdParty":"linux 6.12 GPL-2.0\nsystemd 257 LGPL-2.1\nbusybox GPL-2.0","version":"1.14.0"}"""
    const val SYSTEM = """{"hostname":"titan","version":"1.14.0","uptimeSeconds":356400,"kernel":"6.12.57+deb13-amd64","image":{"slot":"a"}}"""
    const val SESSION = """{"user":{"name":"alex","role":"owner","twoFactor":false},"hostname":"titan","version":"1.14.0"}"""
    const val USERS = """[{"name":"alex","role":"owner"},{"name":"kim","role":"user"},{"name":"gast","role":"user","disabled":true}]"""

    fun certificate() = """{"notAfter":"${Instant.now().plus(5, ChronoUnit.DAYS)}"}"""
    const val NOTIFICATIONS = """{"messages":[{"id":3,"time":"2026-11-21T06:00:00Z","severity":"warn","kind":"backup_overdue","subject":"Fotos","title":"Sicherung überfällig","text":""}]}"""
    const val ACTIVITY = """{"entries":[
      {"at":"2026-11-21T09:12:00Z","user":"alex","via":"web","action":"PUT /api/v1/system/timezone","ok":true},
      {"at":"2026-11-20T18:40:00Z","user":"alex","via":"web","action":"PUT /api/v1/app-icon","ok":true},
      {"at":"2026-11-20T18:00:00Z","user":"alex","via":"web","action":"login","ok":true},
      {"at":"2026-11-19T07:30:00Z","user":"kim","via":"web","action":"POST /api/v1/update/check","ok":true}],"more":false}"""

    const val PREFERENCES = """{"appearance":{"theme":"graphit","accent":"blau","customAccent":"#E5B92E","style":"glass","glass":45,"blur":18,"shine":20,"rTile":14,"rCtl":10,"font":100,
      "density":"normal","bg":"shapes","dim":30,"bgVersion":0,"custom":{"bg":"#101418","surface":"#1A2027","header":"#13181D","text":"#ECEFF3","border":"#2A333D"},
      "glow":0,"glowColor":"","motion":true,"duration":450,"fx":{"enabled":true,"transitions":true,"skeleton":true,"micro":true,"pulse":false,"gradient":false,"illustrations":true,"shortcuts":true},
      "menus":{"own":true,"glass":30,"blur":24,"shine":20},"dialogs":{"own":false,"glass":30,"blur":24,"shine":20,"dim":70},"bars":{"own":false,"glass":30,"blur":24,"shine":20},
      "nav":{"order":["a"],"hidden":[],"layout":"top","magnify":true},"future":42},
      "desktop":{"version":1,"icons":["app:files","app:docker"],"customIcons":true,"wallpaper":{"source":"builtin","id":"aurora","live":true},
      "dock":{"show":true,"pinned":["app:files","app:docker","app:settings"],"customPinned":true,"size":52,"opacity":90,"magnify":1,"position":"bottom","border":true},
      "barLoad":true,"phone":{"dock":{"show":true}}},"agent":{"x":1}}"""
    const val SERVICES_STATE = """{"agent":false,"calendar":true,"contacts":true,"docker":true,"lxc":true,"mail":false,"vms":true}"""
    const val APP_ICON = """{"kind":"color","color":"#4cc38a","version":3}"""
    const val LOGIN_LOOK = """{"configured":true,"theme":"inherit","accent":"blau","customAccent":"#E5B92E","style":"glass","glass":45,"blur":18,
      "background":{"source":"builtin","id":"nordlicht","fit":"cover","x":50,"y":50,"zoom":100,"dim":30},"image":null,"position":"center","width":"normal",
      "texts":{"de":{"title":"Willkommen","notice":"Nur für die Familie"}},"hideHost":false,"hideLogo":false,"clock":false,"accountsStyle":"circles","showAccounts":true,"showAccountsExternal":false,"hiddenAccounts":[],"later":"y"}"""

    /** Alles, was die Seiten unter „System“ fragen. */
    fun install(nas: FakeNas, update: String = UPDATE_AVAILABLE) {
        nas.on("GET about", ABOUT)
        nas.on("GET system", SYSTEM)
        nas.on("GET session", SESSION)
        nas.on("GET update", update)
        nas.on("GET update/versions", VERSIONS)
        nas.on("GET services", SERVICES)
        nas.on("GET system/startup", STARTUP)
        nas.on("GET docker", DOCKER)
        nas.on("GET vms", VMS)
        nas.on("GET viewer/settings", VIEWER_SETTINGS)
        nas.on("GET viewer/status", VIEWER_STATUS)
        nas.on("GET storage", STORAGE)
        nas.on("GET system/certificate", certificate())
        nas.on("GET notifications", NOTIFICATIONS)
        nas.on("GET shares", "[]")
        nas.on("GET notifications/settings", """{"settings":{"email":{"enabled":false},"ntfy":{"enabled":false}}}""")
        nas.on("GET config-backup", """{"backup":{"backups":[],"lastError":""}}""")
        nas.on("GET plugins", """{"recommendations":[]}""")
        nas.on("GET backups", """{"jobs":[{"job":{"enabled":true}}]}""")
        nas.on("GET network/wireguard", """{"enabled":false}""")
        nas.on("GET notifications/push", """{"devices":[]}""")
        nas.on("GET users", USERS)
        nas.on("GET system/ssh", """{"enabled":true,"running":true}""")
        nas.on("GET firewall", """{"config":{"enabled":false},"active":false}""")
        nas.on("GET activity", ACTIVITY)
        nas.on("GET preferences", PREFERENCES)
        nas.on("GET services/state", SERVICES_STATE)
        nas.on("GET mail/access", """{"enabled":false}""")
        nas.on("GET apps/mine", """{"apps":[{"app":"jellyfin","title":"Jellyfin","running":true}]}""")
        nas.on("GET webapps", """{"apps":[]}""")
        nas.on("GET app-icon", APP_ICON)
        nas.on("GET login-appearance", LOGIN_LOOK)
        nas.on("GET health", "{}")
    }
}

/** Die Seiten unter „System“: hell, dunkel, breit (1280 dp) und mit 200 % Schrift. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h1600dp-xhdpi", sdk = [36])
class SystemShotsTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        SystemFixtures.install(nas)
        nas.start()
    }

    @After
    fun stop() {
        nas.stop()
        shots.assertClean()
    }


    /** Zusätzlich: Texte brechen nur zwischen ganzen Wörtern um (Inhaber 21.11.2026). */
    private fun cap(name: String, dark: Boolean) {
        shots.capture(name, dark)
        for (node in compose.onAllNodes(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isRoot()), useUnmergedTree = true).fetchSemanticsNodes()) {
            val get = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { null } ?: continue
            val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            get.action?.invoke(out)
            val r = out.firstOrNull() ?: continue
            val text = r.layoutInput.text.text
            for (i in 0 until r.lineCount - 1) {
                val end = r.getLineEnd(i)
                if (end <= 0 || end >= text.length) continue
                if (text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit())
                    shots.problems += "${shots.prefix}$name: mitten im Wort umbrochen „${text.take(60)}“"
            }
        }
    }

    private fun open(nav: SettingsNav, path: String, waitFor: String) {
        nav.openPath(path)
        shots.until { nas.requests.any { it.first == "GET" && it.second.substringBefore('?') == waitFor } }
        shots.settle(1200)
    }

    private fun pages(dark: Boolean, wide: Boolean = false) {
        val nav = SettingsNav()
        shots.show(dark) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        open(nav, "/settings/about", "about"); cap("20-ueber", dark)
        open(nav, "/settings/services", "system/startup"); cap("21-dienste", dark)
        open(nav, "/settings/viewer", "viewer/status"); cap("22-fernansicht", dark)
        // Die Übersicht steht nur am großen Bildschirm (Alle Einstellungen).
        if (wide) nav.openPath("/settings")
        if (wide) shots.until { nas.requests.any { it.second.startsWith("activity") } }
        if (wide) { shots.settle(1500); cap("23-uebersicht", dark) }
    }

    private fun updates(dark: Boolean) {
        val nav = SettingsNav()
        shots.show(dark) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        open(nav, "/settings/updates", "update/versions"); cap("30-updates", dark)
        nav.openPath("/settings/about"); shots.settle(300)
        nas.on("GET update", SystemFixtures.UPDATE_PENDING)
        nas.requests.clear()
        open(nav, "/settings/updates", "update"); cap("31-updates-wartet", dark)
        nav.openPath("/settings/about"); shots.settle(300)
        nas.on("GET update", SystemFixtures.UPDATE_WORKING)
        nas.requests.clear()
        open(nav, "/settings/updates", "update"); cap("32-updates-laedt", dark)
    }

    private fun appearance(dark: Boolean) {
        val nav = SettingsNav()
        shots.show(dark) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        open(nav, "/settings/appearance", "preferences"); cap("40-darstellung", dark)
        nav.tab = "glass"; cap("41-darstellung-feinheiten", dark)
        nav.tab = "background"; shots.until { nas.requests.any { it.second == "app-icon" } }; cap("42-darstellung-hintergrund", dark)
        nav.tab = "dock"; shots.until { nas.requests.any { it.second == "webapps" } }; cap("43-darstellung-dock", dark)
        nav.tab = "login"; shots.until { nas.requests.any { it.second == "login-appearance" } }; cap("44-darstellung-anmeldung", dark)
    }

    @Test fun hell() = pages(false)
    @Test fun hellUpdates() = updates(false)
    @Test fun hellDarstellung() = appearance(false)

    @Test fun dunkel() = pages(true)
    @Test fun dunkelUpdates() = updates(true)
    @Test fun dunkelDarstellung() = appearance(true)

    @Test
    @Config(qualifiers = "de-w1280dp-h1400dp-mdpi")
    fun breit() { shots.prefix = "breit-"; pages(true, wide = true) }

    @Test
    @Config(qualifiers = "de-w1280dp-h1400dp-mdpi")
    fun breitUpdates() { shots.prefix = "breit-"; updates(true) }

    @Test
    @Config(qualifiers = "de-w1280dp-h1400dp-mdpi")
    fun breitDarstellung() { shots.prefix = "breit-"; appearance(true) }

    @Test
    fun schrift200() { org.robolectric.RuntimeEnvironment.setFontScale(2f); shots.prefix = "schrift200-"; pages(false) }

    @Test
    fun schrift200Updates() { org.robolectric.RuntimeEnvironment.setFontScale(2f); shots.prefix = "schrift200-"; updates(false) }

    @Test
    fun schrift200Darstellung() { org.robolectric.RuntimeEnvironment.setFontScale(2f); shots.prefix = "schrift200-"; appearance(false) }
}
