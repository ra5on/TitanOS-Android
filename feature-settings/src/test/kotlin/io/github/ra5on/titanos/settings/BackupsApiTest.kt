package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.withPath
import io.github.ra5on.titanos.settings.pages.backups.Bk
import io.github.ra5on.titanos.settings.pages.backups.BkApi
import io.github.ra5on.titanos.settings.pages.backups.Chosen
import io.github.ra5on.titanos.settings.pages.backups.Share
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.Tr
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Die Anfragen der Sicherungsseiten: Methode, Pfad und Inhalt jeder
 * speichernden Aktion, unbekannte Felder bleiben erhalten, Fingerabdruck
 * „Sicherungen“ vor Löschen und Wiederherstellen.
 */
@RunWith(RobolectricTestRunner::class)
// Wie die Bildtests: alle Klassen im selben Robolectric-Sandkasten (gemischt hingen sie im vollen Lauf).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [36])
class BackupsApiTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val guarded = mutableListOf<GuardAction>()

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        BackupsFixtures.install(nas)
        nas.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Guard.reset(context)
        Guard.setGraceSeconds(context, 0)
        Guard.set(context, GuardAction.Settings, true)
        Guard.prompt = { _, action, done -> guarded += action; done(true) }
    }

    @After
    fun stop() {
        Guard.prompt = null
        nas.stop()
    }

    private fun sent(method: String, path: String): JsonObject {
        val r = nas.requests.last { it.first == method && it.second == path }
        return if (r.third.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(r.third) as JsonObject
    }

    private fun views() = (Json.parseToJsonElement(BackupsFixtures.OVERVIEW) as JsonObject)["jobs"]!!.let { it as kotlinx.serialization.json.JsonArray }.objects()

    private fun job(id: String): JsonObject = views().first { it.o("job").str("id") == id }

    @Test
    fun editKeepsUnknownFieldsAndSendsOnlyTheDestination() = runTest {
        val view = job("fotos")
        val shares = listOf(Share("Fotos", "daten"), Share("Dokumente", "daten"))
        val draft = Bk.editableJob(view.o("job")).withPath("destination", "bucket", value = "neu").with("enabled" to true)
        val selection = Bk.selectionOfIn(shares, Bk.chosenOf(draft, shares))
        val payload = Bk.jobPayload(draft, selection, "*.tmp\n\n", Bk.bandwidthForm(null), emptyList(), "")
        BkApi.updateJob(nas.api(), "fotos", payload)
        val body = sent("PUT", "backups/fotos")
        assertEquals("bleibt", body.str("future"))
        assertEquals("""{"type":"s3","endpoint":"https://s3.eu-central-1.example.com","bucket":"neu","prefix":"titan","accessKey":"AKIA_BEISPIEL","secretKey":""}""", body["destination"].toString())
        assertFalse("bandwidth fehlt, wenn keine Grenze", body.containsKey("bandwidth"))
        assertFalse(body.containsKey("copy"))
        assertFalse(body.containsKey("vmDisks"))
        assertEquals("""["*.tmp"]""", body["patterns"].toString())
        assertEquals("""["Fotos","Dokumente"]""", body["sources"].toString())
        assertEquals("""{"last":14,"daily":0,"weekly":0,"monthly":0}""", body["keep"].toString())
        assertEquals("""["02:30","14:30"]""", body["times"].toString())
        assertEquals("02:30", body.str("time"))
    }

    @Test
    fun newReadableCopyKeepsCopyAndVmChoice() = runTest {
        val shares = listOf(Share("Fotos", "daten"))
        val draft = Bk.newJob().with("id" to " usb2 ", "vmDisks" to io.github.ra5on.titanos.settings.common.jobj("mode" to "skip"))
            .withPath("destination", "type", value = "usb-copy").withPath("destination", "deviceId", value = "1234-ABCD").withPath("destination", "folder", value = "b")
            .with("copy" to io.github.ra5on.titanos.settings.common.jobj("mirror" to true))
        val selection = Bk.selectionOfIn(shares, listOf(Chosen("daten", "Fotos/2020"), Chosen("archiv", ""), Chosen("daten", "privat")))
        val bw = Bk.bandwidthForm(null).copy(on = true, limit = "15", window = true, windowFrom = "22:00", windowTo = "06:00", windowLimit = "0")
        val payload = Bk.jobPayload(draft, selection, "", bw, listOf(io.github.ra5on.titanos.settings.common.jobj("id" to "1234-ABCD", "name" to "Samsung T7")), "")
        BkApi.createJob(nas.api(), payload)
        val body = sent("POST", "backups")
        assertEquals("usb2", body.str("id"))
        assertEquals("""{"type":"usb-copy","deviceId":"1234-ABCD","deviceName":"Samsung T7","folder":"b"}""", body["destination"].toString())
        assertEquals("""{"mirror":true,"dated":false,"mirrorDays":30}""", body["copy"].toString())
        assertEquals("""{"daily":0,"weekly":0,"monthly":0}""", body["keep"].toString())
        assertEquals("""{"mode":"skip"}""", body["vmDisks"].toString())
        assertEquals("""{"limit":15,"windowFrom":"22:00","windowTo":"06:00","windowLimit":0}""", body["bandwidth"].toString())
        assertEquals("""["Fotos"]""", body["sources"].toString())
        assertEquals("""{"Fotos":["2020"]}""", body["folders"].toString())
        assertEquals("""["archiv"]""", body["volumes"].toString())
        assertEquals("""["daten/privat"]""", body["places"].toString())
    }

    @Test
    fun pairCodeBecomesARestTarget() {
        val raw = """{"v":1,"url":"https://keller.example.ts.net:8750/titan","user":"titan","password":"pw_beispiel"}"""
        val code = "titanos-backup:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
        val d = Bk.destinationPayload(io.github.ra5on.titanos.settings.common.jobj("type" to "titanos"), emptyList(), code)
        assertEquals("""{"type":"rest","url":"https://keller.example.ts.net:8750/titan","username":"titan","password":"pw_beispiel"}""", d.toString())
        assertNull(Bk.decodeCode("titanos-backup:kaputt"))
    }

    @Test
    fun jobActions() = runTest {
        val api = nas.api()
        nas.on("GET backups/fotos/snapshots/a1b2/files", "{}")
        BkApi.act(api, "fotos", "run"); assertEquals("POST", nas.requests.last().first); assertEquals("backups/fotos/run", nas.requests.last().second)
        BkApi.act(api, "fotos", "stop"); assertEquals("backups/fotos/stop", nas.requests.last().second)
        BkApi.deleteJob(api, "fotos"); assertEquals("DELETE" to "backups/fotos", nas.requests.last().let { it.first to it.second })
        BkApi.check(api, "fotos", 25); assertEquals("""{"readData":25}""", sent("POST", "backups/fotos/check").toString())
        BkApi.probe(api, "fotos", 64, 20); assertEquals("""{"maxBytes":64000000,"maxFiles":20}""", sent("POST", "backups/fotos/probe").toString())
        BkApi.restore(api, "fotos", "a1b2", "daten", null); assertEquals("""{"snapshot":"a1b2","volume":"daten"}""", sent("POST", "backups/fotos/restore").toString())
        BkApi.restore(api, "fotos", "a1b2", "daten", listOf("/mnt/daten/Fotos/a b.jpg"))
        assertEquals("""{"snapshot":"a1b2","volume":"daten","paths":["/mnt/daten/Fotos/a b.jpg"]}""", sent("POST", "backups/fotos/restore").toString())
        BkApi.files(api, "fotos", "a1b2", "/mnt/daten/Fotos/mit Leerzeichen", 500)
        assertEquals("backups/fotos/snapshots/a1b2/files?path=%2Fmnt%2Fdaten%2FFotos%2Fmit%20Leerzeichen&offset=500", nas.requests.last().second)
        assertEquals("pw_beispiel_nur_platzhalter", BkApi.password(api, "fotos"))
        BkApi.sshKey(api); assertEquals("POST" to "backups/ssh-key", nas.requests.last().let { it.first to it.second })
        BkApi.confirmSession(api, "pw_beispiel", "123456"); assertEquals("""{"password":"pw_beispiel","code":"123456"}""", sent("POST", "session/confirm").toString())
    }

    @Test
    fun targetsAndReplication() = runTest {
        val api = nas.api()
        val smb = io.github.ra5on.titanos.settings.common.jobj("id" to "nas2", "host" to "h", "username" to "u", "password" to "", "share" to "s", "folder" to "f", "extra" to 1)
        BkApi.smbDiscover(api, smb); assertEquals(smb, sent("POST", "backups/smb/discover"))
        BkApi.smbSave(api, smb); assertEquals(smb, sent("PUT", "backups/smb"))
        BkApi.smbDelete(api, "nas 2"); assertEquals("DELETE" to "backups/smb/nas%202", nas.requests.last().let { it.first to it.second })
        BkApi.setTarget(api, true, "archiv"); assertEquals("""{"enabled":true,"volume":"archiv"}""", sent("PUT", "backups/target").toString())
        BkApi.pairSource(api, "eltern"); assertEquals("""{"name":"eltern"}""", sent("POST", "backups/target/sources").toString())
        BkApi.removeSource(api, "eltern", true); assertEquals("backups/target/sources/eltern?deleteData=true", nas.requests.last().second)
        BkApi.removeSource(api, "eltern", false); assertEquals("backups/target/sources/eltern", nas.requests.last().second)

        val base = io.github.ra5on.titanos.settings.common.jobj("name" to "buero", "enabled" to true, "volume" to "daten", "code" to "", "everyHours" to 24, "limitMiB" to 0)
        with(Bk) {
            BkApi.saveReplication(api, "", base.withKeepChoice(14, null, 2, 365))
            assertEquals("""{"name":"buero","enabled":true,"volume":"daten","code":"","everyHours":24,"limitMiB":0,"keep":14}""", sent("POST", "replication").toString())
            BkApi.saveReplication(api, "r1", base.withKeepChoice(14, io.github.ra5on.titanos.settings.common.jobj("mode" to "rolling"), 2, 365))
            assertEquals("""{"name":"buero","enabled":true,"volume":"daten","code":"","everyHours":24,"limitMiB":0,"keep":365,"versions":{"daily":0,"weekly":0,"monthly":0,"mode":"rolling"}}""", sent("PUT", "replication/r1").toString())
        }
        BkApi.runReplication(api, "r1"); assertEquals("POST" to "replication/r1/run", nas.requests.last().let { it.first to it.second })
        BkApi.removeReplication(api, "r1"); assertEquals("DELETE" to "replication/r1", nas.requests.last().let { it.first to it.second })
        BkApi.restoreReplica(api, "eltern", "2026-11-20T23-00"); assertEquals("""{"source":"eltern","snapshot":"2026-11-20T23-00"}""", sent("POST", "replication/received/restore").toString())
        BkApi.recover(api, "op1", true); assertEquals("""{"confirm":"op1","restart":true}""", sent("POST", "backups/application-recovery").toString())
        BkApi.cloneAct(api, "daten", "c1", "c1", "discard"); assertEquals("""{"confirm":"c1","action":"discard"}""", sent("POST", "vm-clones/daten/c1").toString())
        BkApi.backupPair(api, "node2"); assertEquals("POST" to "cluster/nodes/node2/backup-pair", nas.requests.last().let { it.first to it.second })
        BkApi.previewApps(api, "fotos", "a1b2", "daten"); assertEquals("""{"snapshot":"a1b2","volume":"daten"}""", sent("POST", "backups/fotos/applications/preview").toString())
        BkApi.restoreApps(api, "fotos", "a1b2", "daten"); assertEquals("""{"snapshot":"a1b2","volume":"daten"}""", sent("POST", "backups/fotos/applications/restore").toString())
        BkApi.runConfig(api); assertEquals("POST" to "config-backup/run", nas.requests.last().let { it.first to it.second })
        nas.on("GET config-backup/download", "{}")
        BkApi.downloadConfig(api, "daten", "config a.tar.gz"); assertEquals("config-backup/download?volume=daten&name=config%20a.tar.gz", nas.requests.last().second)
    }

    @Test
    fun pureRules() {
        val tr = Tr("de", mapOf("backup.whenDaily" to "täglich um {time}", "backup.manual" to "nur von Hand", "backup.whenWindow" to "nur {from}–{to}"))
        val job = io.github.ra5on.titanos.settings.common.jobj("enabled" to true, "every" to "daily", "time" to "02:30", "times" to listOf("02:30", "14:30"), "windowFrom" to "01:00", "windowTo" to "06:00")
        assertEquals("täglich um 02:30, 14:30 · nur 01:00–06:00", Bk.whenText(tr, job))
        val views = views()
        val rule = Bk.rule321(views)
        assertEquals(4, rule.copies)
        assertEquals(listOf("nas", "cloud", "usb", "server"), rule.media)
        assertEquals(2, rule.offsite)
        assertTrue(rule.three && rule.two && rule.one)
        val notes = Bk.noteGroups(views[0].o("state"))
        assertEquals(1, notes.size); assertEquals(5, notes[0].count); assertEquals(1, notes[0].more)
        assertNull(Bk.bandwidthPayload(Bk.bandwidthForm(null).copy(on = true, limit = "0")))
        assertEquals(5L to null, Bk.keepChoice(5, null, 1, 100))
        assertEquals(1L, Bk.keepChoice(0, null, 1, 100).first)
    }

    /** Speichern der Konfigurationssicherung über die Seite: ganzes Objekt mit unbekannten Feldern, ohne leeres Passwort. */
    @Test
    fun configSaveFromThePage() {
        val nav = SettingsNav("/settings/backups/configuration")
        compose.setContent { io.github.ra5on.titanos.ui.theme.TitanTheme(dark = false) { SettingsApp(nas.env(), onClose = {}, nav = nav) } }
        waitFor { nas.requests.any { it.second == "config-backup" } }
        waitFor { compose.onAllNodesWithText("Speichern").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasContentDescription("Konfiguration automatisch sichern") and hasClickAction())[0].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Speichern")[0].performScrollTo().performClick()
        waitFor { nas.requests.any { it.first == "PUT" && it.second == "config-backup" } }
        val body = sent("PUT", "config-backup")
        assertEquals("1", body["future"].toString())
        assertEquals("false", body["enabled"].toString())
        assertFalse(body.containsKey("password"))
        assertFalse(body.containsKey("versions"))
        assertTrue(GuardAction.Settings in guarded)
    }

    /** Entfernen eines Auftrags: Dialog, Fingerabdruck „Sicherungen“, DELETE. */
    @Test
    fun removeJobAsksForTheFingerprint() {
        val nav = SettingsNav("/settings/backups/data")
        compose.setContent { io.github.ra5on.titanos.ui.theme.TitanTheme(dark = false) { SettingsApp(nas.env(), onClose = {}, nav = nav) } }
        waitFor { compose.onAllNodesWithText("Gefahrenzone").fetchSemanticsNodes().isNotEmpty() }
        val onPage = compose.onAllNodesWithText("Entfernen")
        onPage[onPage.fetchSemanticsNodes().size - 1].performScrollTo().performClick()
        compose.waitForIdle()
        val buttons = compose.onAllNodesWithText("Entfernen")
        buttons[buttons.fetchSemanticsNodes().size - 1].performClick()
        waitFor { nas.requests.any { it.first == "DELETE" } }
        assertEquals("backups/fotos", nas.requests.last { it.first == "DELETE" }.second)
        assertEquals(listOf(GuardAction.Backups), guarded)
    }

    private fun waitFor(ok: () -> Boolean) {
        // Wie Shots.until: echt synchronisieren; abgelaufen erst, wenn Wandzeit und Test-Uhr (je Runde 50 ms) 60 s erreicht haben.
        val end = System.currentTimeMillis() + 60_000
        var steps = 0L
        while (!ok()) {
            check(System.currentTimeMillis() < end || steps * 50 < 60_000) { "Zeit abgelaufen" }
            steps++
            runCatching { compose.waitForIdle() }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(30)
        }
    }
}
