package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

/**
 * Antworten eines NAS mit eingerichteter Sicherung für die Bild- und
 * API-Tests der Sicherungsseiten. Geheimnisse sind nur Platzhalter.
 */
internal object BackupsFixtures {
    const val OVERVIEW = """{"jobs":[
 {"job":{"id":"fotos","enabled":true,"sources":["Fotos","Dokumente"],"folders":{},"applications":[{"kind":"docker","name":"immich","method":"stop"}],"allowStop":true,
   "destination":{"type":"s3","endpoint":"https://s3.eu-central-1.example.com","bucket":"nas-backup","prefix":"titan","accessKey":"AKIA_BEISPIEL"},
   "every":"daily","interval":6,"weekday":1,"weekdays":[1],"monthDay":1,"time":"02:30","times":["02:30","14:30"],
   "keep":{"last":14,"daily":0,"weekly":0,"monthly":0},"bandwidth":{"limit":20,"windowFrom":"22:00","windowTo":"06:00","windowLimit":0},"future":"bleibt"},
  "secretSet":{"secretKey":true},"stateKnown":true,
  "state":{"running":false,"phase":"","percent":0,"bytesDone":0,"bytesTotal":0,"lastRun":"2026-11-21T02:30:00Z","lastOK":"2026-11-21T02:41:00Z","lastError":"","warning":"",
   "problems":[{"code":"read_error","count":5,"paths":["Fotos/2019/kaputt.jpg","Fotos/2020/a.raw","Fotos/2020/b.raw","Fotos/2021/c.raw"]}],"notices":null,
   "bytes":48200000000,"restoredTo":"","lastCheck":"2026-11-15T03:00:00Z","checkError":"",
   "probe":{"at":"2026-11-14T03:00:00Z","snapshot":"a1b2c3d4","files":20,"bytes":64000000,"truncated":false,"error":""}},
  "next":"2026-11-21T14:30:00Z"},
 {"job":{"id":"usb-kopie","enabled":true,"sources":["Dokumente"],"destination":{"type":"usb-copy","deviceId":"1234-ABCD","deviceName":"Samsung T7","folder":"backups/usb-kopie"},
   "every":"weekly","weekday":6,"weekdays":[6,0],"time":"09:00","times":["09:00"],"keep":{"daily":0,"weekly":0,"monthly":0},"copy":{"dated":false,"mirror":true,"mirrorDays":30}},
  "secretSet":{},"state":{"running":true,"phase":"backup","percent":42,"bytesDone":4200000000,"bytesTotal":10000000000,"lastRun":"2026-11-14T09:00:00Z","lastOK":"2026-11-14T09:20:00Z","lastError":"","warning":"","bytes":0,"restoredTo":""},
  "next":null},
 {"job":{"id":"keller","enabled":true,"sources":["Medien"],"destination":{"type":"rest","url":"https://keller.example.ts.net:8750/titan","username":"titan"},
   "every":"hourly","interval":6,"time":"00:15","times":["00:15"],"keep":{"mode":"rolling","daily":0,"weekly":0,"monthly":0}},
  "secretSet":{"password":true},"state":{"running":false,"lastRun":"2026-11-21T06:15:00Z","lastOK":"2026-11-19T06:15:00Z","lastError":"Fatal: unable to open repository: connection refused","warning":"","bytes":0,"restoredTo":""},
  "next":"2026-11-21T12:15:00Z"}],
 "shares":[{"name":"Fotos","volume":"daten"},{"name":"Dokumente","volume":"daten"},{"name":"Medien","volume":"daten"}],
 "smbTargets":[{"id":"nas2","host":"192.168.1.20","username":"backup","share":"Sicherung","folder":"TitanOS"}],
 "volumes":["daten","archiv"]}"""

    const val CONFIG = """{"backup":{"settings":{"enabled":true,"volume":"","keep":14,"schedule":"weekly","time":"03:00","weekday":0,"encrypt":true,"pace":"daily","future":1},
 "target":"daten","lastCheck":"2026-11-21T08:00:00Z","lastError":"",
 "backups":[{"name":"config-20261121T080000Z-a1b2c3d4e5f6.tar.gz","volume":"daten","time":"2026-11-21T08:00:00Z","sizeBytes":412000,"hostname":"titan","version":"1.19.0","encrypted":true},
  {"name":"config-20261120T080000Z-0f1e2d3c4b5a.tar.gz","volume":"daten","time":"2026-11-20T08:00:00Z","sizeBytes":409000,"hostname":"titan","version":"1.19.0","encrypted":false}],
 "next":"2026-11-23T03:00:00Z","unsearched":{"code":"volume_asleep","volumes":["archiv"]}},"passwordSet":true,"volumes":["daten","archiv"]}"""

    const val TARGET = """{"target":{"enabled":true,"volume":"archiv","port":8750,"sources":[{"name":"eltern","created":"2026-10-01T10:00:00Z","bytes":120000000000,"last":"2026-11-21T01:00:00Z"}],"listening":["100.101.102.103:8750"],"problem":""},"volumes":["daten","archiv"]}"""

    const val REPLICATION = """{"jobs":[{"id":"r1","name":"buero","enabled":true,"volume":"daten","url":"https://buero.example.ts.net:8750","user":"titan","everyHours":24,"keep":14,"limitMiB":0,
 "state":{"running":false,"bytes":0,"lastRun":"2026-11-21T00:00:00Z","lastOK":"2026-11-21T00:10:00Z","lastError":"","snapshot":"2026-11-21","next":"2026-11-22T00:00:00Z"}}],
 "received":[{"source":"eltern","snapshot":"2026-11-20T23-00"}],"receiveVolume":"archiv"}"""

    const val STORAGE = """{"disks":[],"volumes":[{"name":"daten","filesystem":"btrfs","mounted":true},{"name":"archiv","filesystem":"ext4","mounted":true,"encryption":{"state":"unlocked"}}]}"""

    const val COVERAGE = """{"items":[
 {"kind":"share","name":"Fotos","available":true,"status":"covered","lastOK":"2026-11-21T02:41:00Z","jobs":[{"id":"fotos","automatic":true,"stateKnown":true,"partial":false,"method":"","destination":"s3","destinationAvailable":null,
   "state":{"lastOK":"2026-11-21T02:41:00Z","lastCheck":"2026-11-15T03:00:00Z","probe":{"at":"2026-11-14T03:00:00Z","error":""},"lastError":"","warning":""}}]},
 {"kind":"share","name":"Medien","available":true,"status":"attention","lastOK":"2026-11-19T06:15:00Z","jobs":[{"id":"keller","automatic":true,"partial":true,"method":"","destination":"rest","destinationAvailable":false,
   "state":{"lastOK":"2026-11-19T06:15:00Z","lastError":"connection refused","warning":""}}]},
 {"kind":"docker","name":"nextcloud","available":false,"status":"unconfigured","lastOK":null,"jobs":[]}],"unavailable":["vms"]}"""

    const val SNAPSHOTS = """{"snapshots":[{"id":"a1b2c3d4","time":"2026-11-21T02:41:00Z","paths":["/mnt/daten/Fotos","/mnt/daten/Dokumente"],"bytes":48200000000},{"id":"9f8e7d6c","time":"2026-11-20T02:40:00Z","paths":["/mnt/daten/Fotos"],"bytes":0}]}"""

    const val FILES = """{"entries":[{"name":"2019","path":"/mnt/daten/Fotos/2019","type":"dir","size":0},{"name":"urlaub-am-meer-mit-sehr-langem-dateinamen.jpg","path":"/mnt/daten/Fotos/urlaub-am-meer-mit-sehr-langem-dateinamen.jpg","type":"file","size":3400000}],"total":2,"cut":false}"""

    fun install(nas: FakeNas) {
        nas.on("GET backups", OVERVIEW)
        nas.on("GET config-backup", CONFIG)
        nas.on("GET backups/target", TARGET)
        nas.on("GET replication", REPLICATION)
        nas.on("GET storage", STORAGE)
        nas.on("GET usb", """[{"id":"1234-ABCD","name":"Samsung T7","supported":true}]""")
        nas.on("GET cluster/nodes", """{"manages":false,"nodes":[],"version":"1.19"}""")
        nas.on("GET docker", """{"containers":[{"name":"immich"},{"name":"nextcloud"}]}""")
        nas.on("GET vms", """{"vms":[{"name":"homeassistant"}]}""")
        nas.on("GET backups/application-recovery", """{"active":false,"error":"","journalMissing":false,"operation":null}""")
        nas.on("GET vm-clones/daten", "[]")
        nas.on("GET vm-clones/archiv", "[]")
        nas.on("GET session", """{"user":{"name":"alex","role":"owner","twoFactor":true},"version":"1.19.0"}""")
        nas.on("GET session/confirmed", """{"confirmed":false}""")
        nas.on("GET backups/coverage", COVERAGE)
        nas.on("GET backups/fotos/snapshots", SNAPSHOTS)
        nas.on("GET backups/fotos/snapshots/a1b2c3d4/files", FILES)
        nas.on("GET backups/fotos/password", """{"password":"pw_beispiel_nur_platzhalter"}""")
        nas.on("GET backups/vm-disks", """{"choice":null,"choiceNeeded":false,"disks":[]}""")
        nas.on("GET backups/folders", """{"folders":["Fotos","Dokumente","Medien"]}""")
    }
}

/** Die Sicherungsseiten: Übersicht, Datensicherung, Formular, Konfiguration, Abdeckung, Anwendungen, Wiederherstellung, Dialoge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [36])
class BackupsShotsTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Berlin"))
        BackupsFixtures.install(nas)
        nas.start()
    }

    @After
    fun stop() {
        nas.stop()
        shots.assertClean()
    }

    /**
     * Ganze Wörter (Inhaber 21.11.2026): eine Zeile darf nur nach einem
     * Leerzeichen, Bindestrich oder Schrägstrich enden, nie mitten im Wort.
     */
    private fun words(name: String) {
        for (node in compose.onAllNodes(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isRoot()), useUnmergedTree = true).fetchSemanticsNodes()) {
            val get = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { null } ?: continue
            val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            get.action?.invoke(out)
            val r = out.firstOrNull() ?: continue
            val text = r.layoutInput.text.text
            for (line in 0 until r.lineCount - 1) {
                val end = r.getLineEnd(line)
                if (end <= 0 || end >= text.length) continue
                val before = text[end - 1]
                val after = text[end]
                if (before.isLetterOrDigit() && after.isLetterOrDigit()) shots.problems += "${shots.prefix}$name: mitten im Wort umbrochen „${text.take(60)}“"
            }
        }
    }

    private fun capture(name: String, dark: Boolean) {
        shots.capture(name, dark)
        words(name)
    }

    private fun seen(path: String) = shots.until { nas.requests.any { it.second == path } }

    private fun click(text: String, index: Int = 0) {
        val nodes = compose.onAllNodesWithText(text)
        nodes[index].performScrollTo().performClick()
        shots.settle(400)
    }

    private fun screens(dark: Boolean) {
        val nav = SettingsNav("/settings/backups")
        shots.show(dark) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        seen("config-backup"); seen("backups")
        capture("00-uebersicht", dark)
        compose.onAllNodesWithText("3-2-1-Regel")[0].performScrollTo()
        capture("01-uebersicht-321", dark)

        nav.openPage("backups/data"); seen("backups/target")
        capture("10-datensicherung", dark)
        click("Backups anderer TitanOS annehmen")
        click("Replikation auf ein anderes TitanOS"); seen("replication")
        capture("11-ziele-offen", dark)
        compose.onAllNodesWithText("fotos")[0].performScrollTo()
        capture("12-auftrag", dark)
        click("Bearbeiten", compose.onAllNodesWithText("Bearbeiten").fetchSemanticsNodes().size - 1)
        capture("13-formular", dark)

        nav.openPage("backups/configuration"); shots.until { nas.requests.count { it.second == "config-backup" } >= 2 }
        capture("20-konfiguration", dark)
        compose.onAllNodesWithText("Vorhandene Kopien")[0].performScrollTo()
        capture("21-konfiguration-kopien", dark)

        nav.openPage("backups/coverage"); seen("backups/coverage")
        capture("30-abdeckung", dark)

        nav.openPage("backups/applications"); seen("storage")
        capture("40-anwendungen", dark)

        nav.openPage("backups/restore"); shots.settle()
        capture("50-wiederherstellung", dark)
        click("Wiederherstellen …"); seen("backups/fotos/snapshots")
        click("Einzelne Dateien und Ordner"); seen("backups/fotos/snapshots/a1b2c3d4/files?path=%2Fmnt%2Fdaten%2FFotos&offset=0")
        capture("51-wiederherstellen-dialog", dark)
    }

    @Test fun hell() = screens(false)

    @Test fun dunkel() = screens(true)

    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-xhdpi")
    fun breit() { shots.prefix = "breit-"; screens(true) }

    @Test
    fun schrift200() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shots.prefix = "schrift200-"
        screens(false)
    }

    /** Neuer Auftrag, Passwort des Backups und erneute Passworteingabe beim Herunterladen. */
    @Test
    fun dialoge() {
        val nav = SettingsNav("/settings/backups/data")
        shots.show(false) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        seen("backups"); shots.settle()
        click("Backup einrichten"); seen("usb")
        capture("60-neuer-auftrag", false)
        click("Ordner wählen …"); shots.settle()
        capture("61-ordner-waehlen", false)
        click("Abbrechen")
        click("Abbrechen")
        compose.onAllNodesWithText("Passwort anzeigen")[0].performScrollTo().performClick()
        seen("backups/fotos/password"); shots.settle()
        capture("62-passwort", false)
        click("Ich habe es notiert")
        nav.openPage("backups/configuration"); shots.until { nas.requests.count { it.second == "config-backup" } >= 1 }; shots.settle()
        click("Herunterladen"); seen("session/confirmed"); seen("session")
        capture("63-passwort-erneut", false)
    }
}
