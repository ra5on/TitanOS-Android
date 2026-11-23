package io.github.ra5on.titanos.tools

import io.github.ra5on.titanos.admin.testing.FakeNas
import io.github.ra5on.titanos.admin.testing.Seen
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.tools.Fixtures.rich
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Anfragen der Werkzeuge gegen das nachgebaute NAS: Methode, Pfad und Körper wie das Web. */
class ApiTest {
    private val nas = FakeNas().start().rich()
    private val api = ToolsApi(nas.env().http)

    @After fun end() = nas.close()

    private fun last(): Seen = nas.seen.last()
    private fun body(s: Seen = last()): JsonObject = TitanJson.parseToJsonElement(s.body).jsonObject

    @Test fun readsRecordedAndRichAnswers() = runBlocking {
        // Die aufgenommenen Antworten des Webs passen auf die Modelle.
        val plain = FakeNas().start()
        try {
            val p = ToolsApi(plain.env().http)
            p.operations()
            p.scripts()
            p.monitoring()
            p.maintenance()
            p.history(24)
            p.activity(p.activityQuery(7, "", "", false))
            // Die aufgenommene Antwort des Journals ist leer ({}): das ist ein Fehler, keine leere Liste.
            try {
                p.logs(p.logsQuery("", 6, "", ""))
                fail("{} sind keine Zeilen")
            } catch (e: ApiException) {
                assertEquals("invalid_response", e.code)
            }
            p.diskTests()
            p.storage()
            p.applicationRecovery()
            p.backups()
            assertEquals("UTC", p.timezone().ifEmpty { "UTC" })
        } finally {
            plain.close()
        }
        assertEquals(7, api.processes().processes.size)
        assertEquals(3, api.diskTests().tests.size)
        assertEquals(4, api.hardwareReport().unbound.size)
        assertEquals(9, api.logs(api.logsQuery("", 6, "", "")).entries.size)
    }

    @Test fun queriesKeepTheOrderOfTheWeb() {
        assertEquals("activity?days=7&limit=200", api.activityQuery(7, "", " ", false))
        assertEquals("activity?days=30&limit=200&user=sam&q=login%20fehl&failed=1&before=2026-01-01T00%3A00%3A00Z", api.activityQuery(30, "sam", "login fehl", true, "2026-01-01T00:00:00Z"))
        assertEquals("activity?days=7&limit=200&format=csv", api.activityQuery(7, "", "", false, format = "csv"))
        assertEquals("logs?source=&priority=6&lines=500", api.logsQuery("", 6, "", ""))
        assertEquals("logs?source=kernel&priority=3&lines=500&search=ata1&since=996400&after=c%3D1", api.logsQuery("kernel", 3, " ata1 ", "1h", "c=1", now = 1_000_000))
        assertEquals("logs/export?source=docker&priority=4&since=999100", api.logsExport("docker", 4, "15m", now = 1_000_000))
        assertEquals("monitoring/export?hours=168&format=json", api.monitoringExport(168, "json"))
    }

    @Test fun badJournalAnswerIsAnError() = runBlocking {
        nas.on("GET", "logs?*", """<html>Dashboard</html>""")
        try {
            api.logs(api.logsQuery("", 6, "", ""))
            fail("keine Zeilen aus HTML")
        } catch (e: ApiException) {
            assertEquals("invalid_response", e.code)
        }
        nas.on("GET", "logs?*", """{"entries":[{"time":"x","priority":6,"source":"a","message":"b"}],"cursor":""}""")
        try {
            api.logs(api.logsQuery("", 6, "", ""))
            fail("ungültige Zeit")
        } catch (e: ApiException) {
            assertEquals("invalid_response", e.code)
        }
    }

    @Test fun savesMaintenanceAndActsOnJobs() = runBlocking {
        val status = api.maintenance()
        api.saveMaintenance(status.settings.copy(enabled = false, windowFrom = "02:00"))
        assertEquals("PUT", last().method)
        assertEquals("maintenance", last().path)
        val b = body()
        assertEquals(false, b["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("02:00", b["windowFrom"]!!.jsonPrimitive.content)
        assertEquals(3, b["jobs"]!!.jsonArray.size)
        assertEquals("Europe/Berlin", b["timezone"]!!.jsonPrimitive.content)

        api.maintenanceAction("j1", "run")
        assertEquals(Seen("POST", "maintenance/j1/run", "{}"), last())
        api.maintenanceAction("j1", "stop")
        assertEquals("maintenance/j1/stop", last().path)
        // Prüfen nach einem Absturz: die App schickt die Kennung selbst als Bestätigung.
        api.maintenanceAction("j2", "review")
        assertEquals(Seen("POST", "maintenance/j2/review", """{"confirm":"j2"}"""), last())
    }

    /** Auftrag entfernen wie im Dialog: auf den gespeicherten Einstellungen, ungespeicherte Änderungen am Formular bleiben draußen. */
    @Test fun removesMaintenanceJob() = runBlocking {
        val stored = api.maintenance().settings
        val edited = stored.copy(windowFrom = "23:00")
        check(edited != stored)
        api.saveMaintenance(jobsPayload(stored, null) { jobs -> jobs.filter { it.id != "j2" } })
        val b = body()
        assertEquals(listOf("j1", "j3"), b["jobs"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("01:00", b["windowFrom"]!!.jsonPrimitive.content)
        assertEquals("Daten", b["volume"]!!.jsonPrimitive.content)
    }

    @Test fun scriptsAreCreatedRunAndDeleted() = runBlocking {
        api.createScript(ScriptDraft("Aufräumen", "", "schedule", "0 4 * * 0", "#!/bin/bash\ndocker system prune -f\n"))
        assertEquals("POST", last().method)
        assertEquals("scripts", last().path)
        val b = body()
        assertEquals("Aufräumen", b["name"]!!.jsonPrimitive.content)
        assertEquals("custom", b["schedule"]!!.jsonObject["every"]!!.jsonPrimitive.content)
        assertEquals("0 4 * * 0", b["schedule"]!!.jsonObject["cron"]!!.jsonPrimitive.content)

        api.updateScript("a1a1a1a1a1a1a1a1", draftOf(api.script("a1a1a1a1a1a1a1a1")).copy(trigger = "manual"))
        assertEquals("PUT", last().method)
        assertEquals("scripts/a1a1a1a1a1a1a1a1", last().path)
        assertTrue(!body().containsKey("schedule"))

        api.runScript("a1a1a1a1a1a1a1a1")
        assertEquals(Seen("POST", "scripts/a1a1a1a1a1a1a1a1/run", ""), last())
        api.cancelScript("a1a1a1a1a1a1a1a1")
        assertEquals("scripts/a1a1a1a1a1a1a1a1/cancel", last().path)
        api.deleteScript("a1a1a1a1a1a1a1a1")
        assertEquals(Seen("DELETE", "scripts/a1a1a1a1a1a1a1a1", ""), last())
        api.saveScriptVolume("Archiv")
        assertEquals(Seen("PUT", "scripts/volume", """{"volume":"Archiv"}"""), last())
        assertEquals("ok", api.scriptOutput("a1a1a1a1a1a1a1a1", 0).run!!.outcome)
        assertEquals(2, api.scriptRuns("a1a1a1a1a1a1a1a1").runs.size)
    }

    @Test fun monitoringAndActivitySettings() = runBlocking {
        api.saveMonitoring(MonitorSettings(true, "Daten", 30, mbToMiB(100)))
        assertEquals("PUT", last().method)
        assertEquals("monitoring", last().path)
        assertEquals(95, body()["maxMiB"]!!.jsonPrimitive.content.toInt())
        api.saveActivity(ActivitySettings(true, "", 365, 64))
        assertEquals(Seen("PUT", "activity", """{"enabled":true,"volume":"","days":365,"maxMiB":64}"""), last())
    }

    @Test fun downloadsWriteTheFile() = runBlocking {
        nas.on("GET", "diagnostics/bundle", "PK-zip-inhalt")
        val out = ByteArrayOutputStream()
        var seen = 0L
        api.download("diagnostics/bundle", out) { done, _ -> seen = done }
        assertEquals("PK-zip-inhalt", out.toString())
        assertEquals(13L, seen)
        nas.on("GET", "monitoring/export*", "at,gap\n2026-10-23T16:00:00Z,false\n")
        val csv = ByteArrayOutputStream()
        api.download(api.monitoringExport(24, "csv"), csv)
        assertTrue(csv.toString().startsWith("at,gap"))
        nas.on("GET", "logs/export*", """{"error":{"code":"not_found","message":"no previous boot"}}""", status = 404)
        try {
            api.download("logs/export?boot=previous", ByteArrayOutputStream())
            fail("kein voriger Start")
        } catch (_: ApiException) {
        }
    }

    @Test fun diskTestsStartCancelAndResume() = runBlocking {
        api.startDiskTest("sdd", "read", autoResume = true)
        assertEquals(Seen("POST", "storage/tests", """{"disk":"sdd","mode":"read","confirm":"","autoResume":true}"""), last())
        // Überschreiben: der Plattenname als Bestätigung, nie von selbst fortgesetzt.
        api.startDiskTest("sde", "write", autoResume = true)
        assertEquals(Seen("POST", "storage/tests", """{"disk":"sde","mode":"write","confirm":"sde","autoResume":false}"""), last())
        api.stopDiskTest("sdd")
        assertEquals(Seen("DELETE", "storage/tests/sdd", ""), last())
        val tests = api.diskTests().tests
        api.resumeDiskTest(tests[1])
        assertEquals(Seen("POST", "storage/tests/sdg/resume", """{"confirm":"sdg"}"""), last())
        api.resumeDiskTest(tests[0].copy(result = "interrupted"))
        assertEquals(Seen("POST", "storage/tests/sdd/resume", """{"confirm":""}"""), last())
        api.dismissDiskNotices()
        assertEquals(Seen("DELETE", "storage/test-notices", ""), last())
    }

    @Test fun taskManagerActions() = runBlocking {
        val p = api.processes().processes.first { it.name == "immich" }
        api.signal(p, "term")
        assertEquals(Seen("POST", "system/processes/2301/signal", """{"signal":"term","start":230100}"""), last())
        api.signal(p, "kill")
        assertEquals("""{"signal":"kill","start":230100}""", last().body)
        api.container("aaaa", "restart")
        assertEquals(Seen("POST", "docker/containers/aaaa/restart", ""), last())
        api.vm("win-11", "shutdown")
        assertEquals("vms/win-11/shutdown", last().path)
        api.lxcAction("pihole", "stop")
        assertEquals("lxc/pihole/stop", last().path)
    }

    @Test fun uasQuirk() = runBlocking {
        api.setUas("152d:0578", true)
        assertEquals(Seen("PUT", "hardware/uas-quirks", """{"device":"152d:0578","on":true}"""), last())
        api.setUas("152d:0578", false)
        assertEquals("""{"device":"152d:0578","on":false}""", last().body)
    }

    @Test fun tasksAndRecovery() = runBlocking {
        val tasks = taskItems(api.operations()).associateBy { it.id }
        api.taskAction(tasks.getValue("file:t1").actions.first())
        assertEquals(Seen("POST", "files/jobs/t1/control", """{"paused":true,"priority":0,"rate":0}"""), last())
        api.taskAction(tasks.getValue("backup:Daten nach Cloud").actions.single())
        assertEquals(Seen("POST", "backups/Daten%20nach%20Cloud/run", "{}"), last())
        api.taskAction(tasks.getValue("maintenance:j1").actions.single())
        assertEquals("maintenance/j1/stop", last().path)
        // Prüfen und Verwerfen schicken die Kennung als Bestätigung.
        api.reviewApplication("op-20261120-0001", restart = true)
        assertEquals(Seen("POST", "backups/application-recovery", """{"confirm":"op-20261120-0001","restart":true}"""), last())
        api.reviewClone("Daten", "c-77", "discard")
        assertEquals(Seen("POST", "vm-clones/Daten/c-77", """{"confirm":"c-77","action":"discard"}"""), last())
    }
}
