package io.github.ra5on.titanos.tools

import io.github.ra5on.titanos.admin.hasText
import io.github.ra5on.titanos.net.TitanJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

/** Reine Logik, Fälle aus den Tests des Webs (schedule.test.mjs, taskmanager.test.ts) und eigene. */
class LogicTest {

    @Test fun everySimpleScheduleGoesToCronAndBack() {
        val cases = listOf(
            Simple.Minutes(15) to "*/15 * * * *",
            Simple.Minutes(5) to "*/5 * * * *",
            Simple.Hourly(7) to "7 * * * *",
            Simple.Daily("03:00") to "0 3 * * *",
            Simple.Daily("23:59") to "59 23 * * *",
            Simple.Weekdays(listOf(1, 4), "03:00") to "0 3 * * 1,4",
            Simple.Weekdays(listOf(1, 6, 0), "22:30") to "30 22 * * 0,1,6",
            Simple.Monthly(1, "01:15") to "15 1 1 * *",
            Simple.Monthly(28, "04:00") to "0 4 28 * *",
            Simple.Monthly(LAST_DAY, "04:00") to "0 4 L * *",
        )
        for ((simple, cron) in cases) {
            assertEquals(simple.toString(), cron, toCron(simple))
            assertEquals(cron, simple, fromCron(cron))
        }
    }

    @Test fun equivalentSpellingsLandInSimpleChoice() {
        assertEquals(Simple.Weekdays(listOf(1, 2, 3, 4, 5), "03:00"), fromCron(" 0  3 * * 1-5 "))
        assertEquals(Simple.Weekdays(listOf(0), "03:00"), fromCron("0 3 * * 7"))
        assertEquals(Simple.Daily("03:00"), fromCron("0 3 * * 0-6"))
        assertEquals("0 3 * * *", toCron(Simple.Weekdays(listOf(0, 1, 2, 3, 4, 5, 6), "03:00")))
    }

    @Test fun whatSimpleChoiceCannotShowStaysAdvanced() {
        for (cron in listOf("*/7 * * * *", "0 */2 * * *", "0 3 * 1 *", "0 3 29 * *", "0 3 1 * 1", "0,30 3 * * *", "0 3 1,15 * *", "x", "")) {
            assertNull(cron, fromCron(cron))
        }
    }

    @Test fun incompleteChoicesGiveNoExpression() {
        assertNull(toCron(Simple.Weekdays(emptyList(), "03:00")))
        assertNull(toCron(Simple.Daily("")))
        assertNull(toCron(Simple.Minutes(7)))
        assertNull(toCron(Simple.Monthly(30, "03:00")))
        assertEquals(Simple.Weekdays(listOf(1), "05:30"), simpleOf("weekdays", Simple.Daily("05:30")))
    }

    @Test fun parsingFollowsTitand() {
        assertTrue(parseCron("0 3 L * *")!!.lastDay)
        assertNotNull(parseCron("0 3 1,L * *"))
        assertNull(parseCron("60 * * * *"))
        assertNull(parseCron("* * * *"))
        assertNull(parseCron("0 3 * * L"))
    }

    @Test fun nextRunsInTheZoneOfTheNas() {
        val from = Instant.parse("2026-11-04T10:00:00Z")
        assertEquals(
            listOf("2026-11-05T02:00:00Z", "2026-11-09T02:00:00Z", "2026-11-12T02:00:00Z"),
            nextRuns("0 3 * * 1,4", 3, "Europe/Berlin", from).map { it.toString() },
        )
        assertEquals(
            listOf("2026-11-04T10:15:00Z", "2026-11-04T10:30:00Z", "2026-11-04T10:45:00Z"),
            nextRuns("*/15 * * * *", 3, "UTC", from).map { it.toString() },
        )
        assertEquals(listOf("2026-11-30T04:00:00Z", "2026-12-31T04:00:00Z"), nextRuns("0 4 L * *", 2, "UTC", from).map { it.toString() })
        // 2:30 gibt es am 29. März 2026 in Berlin nicht: diese Nacht fällt aus.
        val spring = Instant.parse("2026-03-28T12:00:00Z")
        assertEquals(listOf("2026-03-30T00:30:00Z", "2026-03-31T00:30:00Z"), nextRuns("30 2 * * *", 2, "Europe/Berlin", spring).map { it.toString() })
        assertEquals(emptyList<Instant>(), nextRuns("0 0 30 2 *", 3, "UTC", from))
        assertEquals(emptyList<Instant>(), nextRuns("nonsense", 3, "UTC", from))
    }

    @Test fun scheduleInWords() {
        val words = mapOf(
            "schedule.say.weekdays" to "Läuft {days} um {time} Uhr.",
            "schedule.say.monthlyLast" to "Läuft am letzten Tag jedes Monats um {time} Uhr.",
            "schedule.say.custom" to "Läuft nach {cron}.",
            "schedule.when.weekdays" to "{days} um {time} Uhr",
            "schedule.when.minutes" to "Alle {n} Minuten",
            "schedule.days.1" to "montags",
            "schedule.days.4" to "donnerstags",
            "schedule.days.workdays" to "werktags",
        )
        val tr: (String, Map<String, Any?>) -> String = { k, p -> io.github.ra5on.titanos.admin.fill(words.getValue(k), p) }
        val join: (List<String>) -> String = { joinList(it, Locale.GERMAN) }
        assertEquals("Läuft montags und donnerstags um 03:00 Uhr.", describeCron("0 3 * * 1,4", tr, join, "say"))
        assertEquals("Montags und donnerstags um 03:00 Uhr", describeCron("0 3 * * 1,4", tr, join))
        assertEquals("Läuft werktags um 06:00 Uhr.", describeCron("0 6 * * 1-5", tr, join, "say"))
        assertEquals("Läuft am letzten Tag jedes Monats um 04:00 Uhr.", describeCron("0 4 L * *", tr, join, "say"))
        assertEquals("Alle 10 Minuten", describeCron("*/10 * * * *", tr, join))
        assertEquals("Läuft nach 0 */2 * * *.", describeCron("0 */2 * * *", tr, join, "say"))
        assertEquals("a, b und c", joinList(listOf("a", "b", "c"), Locale.GERMAN))
        assertEquals("a, b, and c", joinList(listOf("a", "b", "c"), Locale.ENGLISH))
    }

    @Test fun olderPresetsBecomeCron() {
        assertEquals("0 22 * * 5", cronOfPreset(ScriptSchedule("weekly", time = "22:00", weekday = 5)))
        assertEquals("30 1 28 * *", cronOfPreset(ScriptSchedule("monthly", time = "01:30", monthDay = 28)))
        assertEquals("7 * * * *", cronOfPreset(ScriptSchedule("hourly", minute = 7)))
        assertEquals("0 4 * * 0", cronOfPreset(ScriptSchedule("custom", cron = " 0 4 * * 0 ")))
    }

    @Test fun scriptBodySendsCron() {
        val d = ScriptDraft(" Backup ", "x", "schedule", " 0 3 * * * ", "#!/bin/sh\n")
        assertEquals(ScriptBody("Backup", "x", "schedule", ScriptSchedule("custom", cron = "0 3 * * *"), "#!/bin/sh\n"), draftBody(d))
        assertNull(draftBody(d.copy(trigger = "manual")).schedule)
        assertTrue(scriptTooLarge(d.copy(content = "x".repeat(256 * 1024 + 1))))
        assertEquals("950 ms", shortDuration(950))
        assertEquals("4 s", shortDuration(4200))
        assertEquals("2 min 5 s", shortDuration(125_000))
    }

    private fun row(pid: Int, name: String, cpu: Double, mem: Long, user: String = "root", owner: ProcessOwner = ProcessOwner()) =
        ProcessRow(pid, 1, name, "/usr/bin/$name", user, "S", cpu, mem, pid * 10L, owner)

    private val rows = listOf(
        row(10, "smbd", 2.0, 50),
        row(20, "Immich", 30.0, 900, owner = ProcessOwner("docker", "ab", "immich_server")),
        row(30, "bash", 0.0, 10, user = "alex"),
        row(40, "qemu", 30.0, 4000),
    )

    @Test fun sortsAndSearchesProcesses() {
        assertEquals(listOf(40, 20, 10, 30), sortProcesses(rows, "cpu").map { it.pid })
        assertEquals(listOf(40, 20, 10, 30), sortProcesses(rows, "memory").map { it.pid })
        assertEquals(listOf("bash", "Immich", "qemu", "smbd"), sortProcesses(rows, "name").map { it.name })
        assertEquals(listOf(20), sortProcesses(rows, "cpu", "IMMICH_S").map { it.pid })
        assertEquals(listOf(30), sortProcesses(rows, "cpu", "alex").map { it.pid })
        assertEquals(listOf(40), sortProcesses(rows, "cpu", " 40 ").map { it.pid })
        assertEquals(listOf(10), sortProcesses(rows, "cpu", "usr/bin/smb").map { it.pid })
        assertTrue(sortProcesses(rows, "cpu", "nichts").isEmpty())
    }

    @Test fun historiesAndVolumeRates() {
        var h = emptyList<Double>()
        repeat(50) { h = pushHistory(h, it.toDouble(), 40) }
        assertEquals(40, h.size)
        assertEquals(49.0, h.last(), 0.0)
        assertEquals(listOf(0.0), pushHistory(emptyList(), Double.NaN))
        assertEquals(listOf(0.0, 50.0, 100.0), scaleToPeak(listOf(0.0, 5.0, 10.0)))
        assertEquals(listOf(0.0, 0.0), scaleToPeak(listOf(0.0, 0.0)))
        val io = listOf(DiskIO("sda", 100.0, 10.0, 1.0), DiskIO("sdb", 50.0, 10.0, 1.0), DiskIO("nvme0n1", 7.0, 3.0, 1.0))
        assertEquals("sda", memberDisk("/dev/sda1", listOf("sda", "sdb")))
        assertEquals("nvme0n1", memberDisk("nvme0n1p2", listOf("nvme0n1", "nvme0n10")))
        assertEquals("", memberDisk("sdz", listOf("sda")))
        assertEquals(
            listOf(VolumeRate("Data", 150.0, 20.0, true), VolumeRate("Fast", 7.0, 3.0, true), VolumeRate("Gone", 0.0, 0.0, false)),
            volumeRates(
                listOf(
                    Volume("Data", members = listOf(VolumeMember("sda"), VolumeMember("sdb1"))),
                    Volume("Fast", members = listOf(VolumeMember("nvme0n1p1"))),
                    Volume("Gone", members = listOf(VolumeMember("sdq"))),
                ),
                io,
            ),
        )
    }

    @Test fun taskItemsMapStatesLikeTheWeb() {
        val ops = TitanJson.decodeFromString(Operations.serializer(), Fixtures.operations)
        val tasks = taskItems(ops)
        val byId = tasks.associateBy { it.id }
        assertEquals("running", byId["file:t1"]!!.state)
        assertEquals(42.0, byId["file:t1"]!!.progress!!, 0.01)
        assertEquals(listOf("pause", "stop"), byId["file:t1"]!!.actions.map { it.label })
        assertEquals("files/jobs/t1/control", byId["file:t1"]!!.actions[0].path)
        // „Fertig mit Fehlern“ braucht einen Blick wie ein Fehlschlag.
        assertEquals("failed", byId["file:t2"]!!.state)
        assertEquals("running", byId["maintenance:j1"]!!.state)
        assertEquals("maintenance/j1/stop", byId["maintenance:j1"]!!.actions.single().path)
        assertEquals("blocked", byId["maintenance:j2"]!!.state)
        assertEquals("scheduled", byId["schedule:j3"]!!.state)
        assertEquals("failed", byId["history:j1:2026-11-01T01:00:00Z"]!!.state)
        assertEquals("failed", byId["backup:Daten nach Cloud"]!!.state)
        assertEquals("backups/Daten%20nach%20Cloud/run", byId["backup:Daten nach Cloud"]!!.actions.single().path)
        assertEquals("scheduled", byId["backup-next:Daten nach Cloud"]!!.state)
        assertEquals("timeout", byId["docker:immich"]!!.dockerCode)
        assertEquals("running", byId["disk:sdd"]!!.state)
        assertEquals("plugins.step.download", byId["plugin:p1"]!!.stepKey)
        assertEquals(30.0, byId["plugin:p1"]!!.progress!!, 0.01)
        assertEquals("waiting", byId["upload:u1"]!!.state)
        assertEquals("ops.cloneRecovery", byId["unavailable:vm-clones"]!!.titleKey)
        assertEquals(setOf("file:t1", "maintenance:j1", "disk:sdd", "plugin:p1", "upload:u1"), tasks.filter(::isActive).map { it.id }.toSet())
        assertEquals(listOf("schedule:j3", "backup-next:Daten nach Cloud", "schedule:j1"), upcomingTasks(tasks).map { it.id })
        assertEquals(listOf("upload:u1"), filterTasks(tasks, "all", "URLAUB").map { it.id })
        assertTrue(filterTasks(tasks, "failed", "").all(::needsAttention))
        assertEquals("tools" to "maintenance", appTarget("/tools/maintenance"))
        assertEquals("storage" to null, appTarget("/storage/manage?mode=grow"))
        assertNull(appTarget("/docker"))
    }

    @Test fun problemsAreTranslatedWithDetail() {
        assertEquals(Problem(Msg("ops.err.unknownOutcome"), ""), problemText("previous execution outcome is unknown"))
        assertEquals(Problem(Msg("ops.err.waiting"), "waiting for backup"), problemText(" waiting for backup "))
        assertEquals(Problem(Msg("ops.err.budget"), ""), problemText("budget exhausted after 3600 s"))
        assertEquals(Problem(Msg("ops.err.failed"), "disk on fire"), problemText("disk on fire"))
    }

    @Test fun activityNames() {
        assertEquals(Msg("activity.do.fileDelete"), activityText("POST /api/v1/files/delete"))
        assertEquals(Msg("lock.activity.autolocked"), activityText("session_autolock"))
        assertEquals(
            Msg("activity.generic", mapOf("area" to Msg("activity.area.system"), "verb" to Msg("activity.verb.change"))),
            activityText("PUT /api/v1/system/timezone"),
        )
        assertEquals(Msg("activity.generic", mapOf("area" to Msg("activity.area.other"), "verb" to Msg("activity.verb.run"))), activityText("POST /api/v1/zzz"))
    }

    @Test fun logsAndHarmlessMessages() {
        val logs = TitanJson.decodeFromString(Logs.serializer(), Fixtures.logs)
        assertTrue(validLogs(logs))
        assertTrue(!validLogs(logs.copy(entries = listOf(LogEntry("gestern", 6, "x", "y")))))
        assertTrue(!validLogs(logs.copy(entries = listOf(LogEntry("2026-11-21T09:50:01Z", 9, "x", "y")))))
        assertEquals("tools.harmlessFirmware", harmlessNote(logs.entries[4].message))
        assertEquals("tools.harmless", harmlessNote(logs.entries[5].message))
        assertNull(harmlessNote(logs.entries[3].message))
        assertEquals("t source ERROR m\nt source WARNING w\nt source i", logText(listOf(LogEntry("t", 3, "source", "m"), LogEntry("t", 4, "source", "w"), LogEntry("t", 6, "source", "i"))))
    }

    @Test fun chartGapsStayOpen() {
        val samples = TitanJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MonitorSample.serializer()), Fixtures.history())
        val values = chartValues(samples) { it.cpu }
        assertEquals(samples.size + 1, values.size)
        assertNull(values[30])
        val latency = chartValues(samples) { s -> s.disks.first().latencyMs }
        assertNull(latency[20])
        assertEquals(61L, mibToMB(58))
        assertEquals(58L, mbToMiB(61))
        assertEquals(8L, mibToMB(mbToMiB(8)))
    }

    @Test fun hardwareAndDiskHelpers() {
        assertEquals("SSE4.2, AVX2, SSE3, x86-64-v3", needList(listOf("sse4_2", "avx2", "pni", "x86-64-v3")))
        assertEquals(listOf("hwcheck.virt.ok", "hwcheck.iommu.table.intel"), virtAdvice(Virtualization("intel", true, true, false, false, true)).map { it.key })
        assertEquals(listOf("hwcheck.virt.off.amd", "hwcheck.virt.byFirmware"), virtAdvice(Virtualization("amd", false, false, true)).map { it.key })
        val tests = TitanJson.decodeFromString(TestList.serializer(), Fixtures.tests).tests
        assertNull(resumeKind(tests[0]))
        assertEquals("confirm", resumeKind(tests[1]))
        assertEquals("sdg", resumeConfirm(tests[1]))
        assertEquals("", resumeConfirm(tests[0].copy(result = "interrupted")))
        assertEquals("kept", restartHint(tests[0]))
        val storage = TitanJson.decodeFromString(StorageStatus.serializer(), Fixtures.storage)
        assertEquals(listOf("sde"), testCandidates(storage.disks, tests).map { it.name })
        assertEquals(74L, testHours(storage.disks.first { it.name == "sdd" }, "read"))
        assertEquals(111L, testHours(storage.disks.first { it.name == "sdd" }, "write"))
    }

    @Test fun maintenancePayloadKeepsStoredJobs() {
        val status = TitanJson.decodeFromString(MaintenanceStatus.serializer(), Fixtures.maintenance)
        val job = jobForSave(newMaintenanceJob().copy(kind = "snapshot", keep = 500, name = "Neu", target = "Schnell"))
        assertEquals(100, job.keep)
        val body = jobsPayload(status.settings, "Schnell") { jobs -> jobs + job }
        assertEquals("Schnell", body.volume)
        assertEquals(4, body.jobs.size)
        assertEquals("Europe/Berlin", body.timezone)
    }

    /** Jeder Schlüssel, den die Seiten aus Teilen zusammensetzen, steht in den Texten des Webs. */
    @Test fun dynamicKeysExist() {
        val keys = mutableListOf<String>()
        TOOLS_GROUPS.forEach { (g, entries) ->
            keys += "tools.group.$g"
            entries.forEach { keys += it.label; keys += it.text }
        }
        listOf("files", "backup", "maintenance", "docker", "vm", "disk", "upload", "recovery", "plugin", "restore", "system").forEach { keys += "tasks.kind.$it" }
        listOf("running", "paused", "waiting", "scheduled", "done", "failed", "blocked", "cancelled", "unknown").forEach { keys += "tasks.state.$it" }
        listOf("pause", "resume", "stop", "retry").forEach { keys += "tasks.action.$it" }
        listOf("all", "active", "scheduled", "done", "failed").forEach { keys += "tasks.filter.$it" }
        listOf("overview", "processes", "containers", "guests", "network", "disks").forEach { keys += "taskmanager.tab.$it" }
        listOf("docker", "vm", "lxc", "service", "system").forEach { keys += "taskmanager.owner.$it" }
        listOf("raid-check", "smart-short", "smart-long", "backup-check", "restore-probe", "snapshot").forEach { keys += "ops.kind.$it" }
        listOf("hours", "daily", "weekly", "monthly").forEach { keys += "ops.$it" }
        listOf("minutes", "hourly", "daily", "weekdays", "monthly", "advanced").forEach { keys += "schedule.kind.$it" }
        listOf("minutes", "hourly", "daily", "weekdays", "monthly", "monthlyLast", "custom").forEach { keys += "schedule.say.$it"; keys += "schedule.when.$it" }
        (0..6).forEach { keys += "schedule.days.$it"; keys += "schedule.dayShort.$it"; keys += "schedule.dayName.$it" }
        TRIGGERS.forEach { keys += "scripts.trigger.$it" }
        listOf("running", "ok", "failed", "timeout", "canceled", "error", "interrupted").forEach { keys += "scripts.outcome.$it" }
        LOG_SOURCES.forEach { keys += "tools.source.${it.ifEmpty { "all" }}" }
        LOG_LEVELS.forEach { keys += it.second }
        LOG_RANGES.forEach { keys += it.second }
        listOf("tools.harmless", "tools.harmlessFirmware").forEach { keys += it }
        keys += activityNamedKeys
        activityAreas.forEach { keys += "activity.area.$it" }
        listOf("run", "change", "remove", "open").forEach { keys += "activity.verb.$it" }
        listOf("token", "agent", "system", "ssh", "mcp", "cluster", "homeassistant").forEach { keys += "activity.via.$it" }
        listOf("read", "write").forEach { keys += "disktest.mode.$it" }
        listOf("write", "verify", "read", "smart", "done").forEach { keys += "disktest.phase.$it" }
        listOf("passed", "failed", "cancelled", "interrupted").forEach { keys += "disktest.result.$it" }
        listOf("kept", "no_serial", "no_volume", "write_failed").forEach { keys += "disktest.restart.$it" }
        listOf("plugin", "kernel", "driver_failed", "not_needed", "unclassified", "unsupported").forEach { keys += "hwcheck.hintChip.$it" }
        listOf("not_needed", "unclassified", "unsupported").forEach { keys += "hwcheck.hint.$it" }
        listOf("warn", "info").forEach { keys += "hwcheck.level.$it" }
        listOf("uefi", "bios").forEach { keys += "hwcheck.boot.$it" }
        listOf("zoned", "known_model").forEach { keys += "hwcheck.smr.$it" }
        listOf("intel", "amd", "other").forEach { keys += "hwcheck.virt.off.$it"; keys += "hwcheck.iommu.off.$it" }
        listOf("intel", "amd").forEach { keys += "hwcheck.iommu.table.$it" }
        listOf(
            "unknownOutcome", "persist", "devMode", "unavailable", "volumeChanged", "running", "waiting", "cycle", "dependencyMissing",
            "reviewFirst", "weekdays", "pruneConsent", "cancelled", "budget", "gaps", "invalid", "failed",
        ).forEach { keys += "ops.err.$it" }
        listOf("review", "window", "disabled", "volume").forEach { keys += "tasks.wait.$it" }
        listOf("notify.severity.error", "notify.severity.warning", "notify.saved", "nav.tools", "desktop.side.overview", "nav.docker", "ops.appRecover").forEach { keys += it }
        val missing = keys.distinct().filter { !hasText(it) }
        assertTrue("Fehlende Texte: $missing", missing.isEmpty())
    }
}
