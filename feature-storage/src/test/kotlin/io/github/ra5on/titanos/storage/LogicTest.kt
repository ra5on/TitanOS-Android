package io.github.ra5on.titanos.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Regeln aus btrfs.ts, disk-swap.ts, checks-hint.ts, disk-sleep.ts und smart.ts. */
class LogicTest {
    @Test fun btrfsEstimate() {
        val tb = 1_000_000_000_000L
        assertEquals(6 * tb, btrfsUsable("single", listOf(2 * tb, 4 * tb)))
        assertEquals(2 * tb, btrfsUsable("raid1", listOf(2 * tb, 4 * tb)))
        assertEquals(4 * tb, btrfsUsable("raid1", listOf(4 * tb, 4 * tb, 1 * tb).let { listOf(4 * tb, 4 * tb) }))
        assertEquals(3 * tb, btrfsUsable("raid1c3", listOf(3 * tb, 3 * tb, 3 * tb)))
        assertEquals(2 * tb, btrfsUsable("raid1c3", listOf(2 * tb, 4 * tb, 8 * tb)))
    }

    @Test fun capacityLikeTheWeb() {
        val tb = 1_000_000_000_000L
        assertEquals(Capacity.Needs(3), capacity("ext4", "raid5", listOf(tb, tb)))
        assertEquals(Capacity.Bytes(2 * tb), capacity("ext4", "raid5", listOf(tb, tb, 2 * tb)))
        assertEquals(Capacity.Even, capacity("xfs", "raid10", listOf(tb, tb, tb, tb, tb)))
        assertEquals(Capacity.Bytes(3 * tb), capacity("ext4", "raid0", listOf(tb, tb, tb)))
    }

    @Test fun swapModes() {
        val disks = listOf(Disk(name = "sda"), Disk(name = "sdb", smart = Smart(healthy = false)))
        val ok = Volume(name = "D", level = "raid1", members = listOf(Member("sda"), Member("sdb")))
        assertEquals(listOf("sdb", "sda"), swapCandidates(ok, disks).map { it.disk })
        assertTrue(swapCandidates(ok, disks).all { it.mode == "live" })
        val degraded = ok.copy(state = "degraded", members = listOf(Member("sda")))
        assertEquals("rebuild", swapCandidates(degraded, disks).first().mode)
        assertEquals("", swapCandidates(degraded, disks).first().disk)
        val raid0 = ok.copy(level = "raid0")
        assertTrue(swapCandidates(raid0, disks).all { it.mode == "impossible" })
    }

    @Test fun replacementSkipsBlocked() {
        val old = Disk(name = "sda", sizeBytes = 100)
        val list = listOf(Disk(name = "sdb", sizeBytes = 200), Disk(name = "sdc", sizeBytes = 50), Disk(name = "sdd", sizeBytes = 200, unavailableReason = "vm"), Disk(name = "sde", sizeBytes = 200, role = "stick"))
        assertEquals(listOf("sdb"), replacementDisks(list, old, 0).map { it.name })
    }

    @Test fun checksHint() {
        val vols = listOf(Volume(name = "A"), Volume(name = "B", level = "raid0", members = listOf(Member("sda"))), Volume(name = "C", state = "missing"))
        val disks = listOf(Disk(name = "sda", serial = "S1"))
        val m = MaintenanceSettings(true, listOf(MaintenanceJob("1", "raid-check", "A", true), MaintenanceJob("2", "smart-short", "S1", true)))
        assertEquals(emptyList<String>(), uncheckedVolumes(vols, disks, m).map { it.name })
        assertEquals(listOf("A", "B"), uncheckedVolumes(vols, disks, m.copy(enabled = false)).map { it.name })
        assertEquals(listOf("B"), checksHintVolumes(listOf(vols[0], vols[1]), listOf("A")))
    }

    @Test fun sleepRules() {
        assertEquals(10 to "disk", effectiveSleep(5, mapOf("D" to 30), "D", 10))
        assertEquals(30 to "volume", effectiveSleep(5, mapOf("D" to 30), "D", null))
        assertEquals(5 to "default", effectiveSleep(5, emptyMap(), "D", null))
        assertEquals(listOf("a", "b"), overridesFrom(mapOf("b" to 1, "a" to 2, "x" to 3), listOf("b", "a")).keys.toList())
        val disk = DiskSleepDisk(wakes = listOf(DiskWake(causes = listOf(DiskWakeCause("journal"))), DiskWake(causes = listOf(DiskWakeCause("unknown"))), DiskWake(causes = listOf(DiskWakeCause("activity_log")))))
        assertEquals(listOf("systemData", "unknown"), wakeHints(disk))
    }

    @Test fun usableDisks() {
        assertTrue(isUsable(Disk(role = "free")))
        assertTrue(isUsable(Disk(role = "used")))
        assertFalse(isUsable(Disk(role = "stick")))
        assertFalse(isUsable(Disk(role = "free", unavailableReason = "vm")))
        assertFalse(isUsable(Disk(role = "free", mounted = true)))
        assertFalse(isUsable(Disk(role = "free", testing = true)))
        assertFalse(isUsable(Disk(role = "member")))
    }

    @Test fun smartOrder() {
        val r = listOf(SmartReport(disk = "sdb", state = "ok"), SmartReport(disk = "sda", state = "failed"), SmartReport(disk = "nvme10n1", state = "ok"), SmartReport(disk = "nvme2n1", state = "ok"))
        assertEquals(listOf("sda", "nvme2n1", "nvme10n1", "sdb"), sortReports(r).map { it.disk })
        assertEquals(3L, sectorProblems(SmartValues(reallocatedSectors = 1, pendingSectors = 2)))
        val rows = historyRows(listOf(SmartSample(day = "1", crcErrors = 0, temperatureC = 30), SmartSample(day = "2", crcErrors = 2, temperatureC = 40)))
        assertTrue(rows.first { it.field == "crcErrors" }.rose)
        assertEquals("2", rows.first { it.field == "crcErrors" }.roseOn)
        assertFalse(rows.first { it.field == "temperatureC" }.rose)
    }

    @Test fun smrOnlyForParity() {
        val hw = listOf(HwDisk("sda", "X", "zoned"), HwDisk("sdb", "Y", null))
        assertEquals(listOf("sda"), smrForLevel("raid5", listOf("sda", "sdb"), hw).map { it.name })
        assertTrue(smrForLevel("raid1", listOf("sda"), hw).isEmpty())
    }
}
