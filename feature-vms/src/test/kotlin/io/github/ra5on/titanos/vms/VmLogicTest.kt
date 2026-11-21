package io.github.ra5on.titanos.vms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VmLogicTest {
    @Test
    fun identifiersLikeTheWeb() {
        assertEquals("grosse-vm", newVmIdentifier("Große VM", emptyList()))
        assertEquals("cafe-ubuntu", newVmIdentifier("Café · Ubuntu!", emptyList()))
        assertEquals("vm", newVmIdentifier("✨✨", emptyList()))
        assertEquals("debian-2", newVmIdentifier("Debian", listOf("debian")))
        assertEquals("debian-3", newVmIdentifier("Debian", listOf("debian", "debian-2")))
        val long = "a".repeat(40)
        assertEquals("a".repeat(29) + "-2", newVmIdentifier(long, listOf("a".repeat(31))))
        assertEquals("ct", newContainerIdentifier("✨", emptyList()))
        assertEquals("web", newContainerIdentifier("Web", emptyList()))
    }

    @Test
    fun displayNames() {
        assertTrue(validVmDisplayName("Windows 11 Büro"))
        assertFalse(validVmDisplayName("  "))
        assertFalse(validVmDisplayName("a\u0007b"))
        assertFalse(validVmDisplayName("a​b"))
        assertFalse(validVmDisplayName("x".repeat(81)))
        assertTrue(validVmDisplayName("x".repeat(80)))
    }

    @Test
    fun scheduleChecks() {
        val first = newRule(emptyList())
        assertEquals(PowerRule("start", listOf(1, 2, 3, 4, 5), "07:00"), first)
        val second = newRule(listOf(first))
        assertEquals("stop", second.action)
        assertEquals("22:00", second.time)
        assertEquals(listOf("noRule"), scheduleProblems(ScheduleDraft(enabled = true)).map { it.key })
        val clash = ScheduleDraft(true, listOf(PowerRule("start", listOf(1), "07:00"), PowerRule("stop", listOf(1), "07:00")))
        assertEquals(listOf("clash"), scheduleProblems(clash).map { it.key })
        val bad = ScheduleDraft(true, listOf(PowerRule("start", emptyList(), "25:00")))
        assertEquals(listOf("time", "days"), scheduleProblems(bad).map { it.key })
        assertEquals(listOf(1, 3), schedulePayload(ScheduleDraft(true, listOf(PowerRule("start", listOf(3, 1, 3), "07:00")))).rules[0].days)
        assertEquals(listOf(60, 120, 300, 450, 600, 900, 1800, 3600), timeoutChoices(450))
        assertEquals(300, scheduleDraft(null).stopTimeout)
        val next = nextScheduled(ScheduleView(nextStart = "2026-11-22T07:00:00Z", nextStop = "2026-11-21T22:00:00Z"))
        assertEquals("stop", next?.first)
    }

    @Test
    fun disksAndFolders() {
        val volumes = listOf(Volume("Data", mounted = true, sizeBytes = 100 * GB, usedBytes = 40 * GB))
        val free = { v: String -> freeOn(volumes, v) }
        assertFalse(diskBlocked(DiskDraft("new", "qcow2", 500.0, "Data"), emptyList(), free))
        assertTrue(diskBlocked(DiskDraft("new", "raw", 70.0, "Data"), emptyList(), free))
        assertTrue(diskBlocked(DiskDraft("new", "qcow2", 0.5, "Data"), emptyList(), free))
        val image = DiskImage("/mnt/Data/iso/a.qcow2", "qcow2", virtualBytes = 20 * GB)
        assertTrue(diskBlocked(DiskDraft("copy", "qcow2", 10.0, "Data", image.path), listOf(image), free))
        assertFalse(diskBlocked(DiskDraft("copy", "qcow2", 20.0, "Data", image.path), listOf(image), free))
        assertTrue(diskBlocked(DiskDraft("block", path = ""), emptyList(), free))
        val folders = listOf(VmFolder("/mnt/Data/share", "share"), VmFolder("/srv/x", "x"), VmFolder("/mnt/a", "share"), VmFolder("/mnt/b", "bad tag"))
        assertEquals("vms.form.folderTwice", folderProblem(folders, folders[0], 0))
        assertEquals("vms.form.folderPath", folderProblem(folders, folders[1], 1))
        assertEquals("vms.form.folderTag", folderProblem(folders, folders[3], 3))
        assertEquals("Data" to "win", directFolder(listOf(DiskDraft("direct", path = "/mnt/Data/vms/win/vdisk1.qcow2"))))
        assertEquals(emptyList<String>(), togglePci(listOf("01:00.0", "01:00.1"), "01:00.0", listOf("01:00.1")))
        assertEquals(listOf("01:00.0"), togglePci(emptyList(), "01:00.0", listOf("01:00.1")))
    }

    @Test
    fun pinsWholeCores() {
        val cpus = listOf(HostCpu(0, siblings = listOf(0, 4)), HostCpu(1, siblings = listOf(1, 5)), HostCpu(5, siblings = listOf(1, 5)))
        assertEquals(listOf(1, 5), togglePin(cpus, emptyList(), 1))
        assertEquals(emptyList<Int>(), togglePin(cpus, listOf(1, 5), 5))
    }

    @Test
    fun lowSpace() {
        val vm = Vm(name = "a", disks = listOf(VmDisk(volume = "Data"), VmDisk(path = "/mnt/Big/x.qcow2")))
        val volumes = listOf(
            Volume("Data", mounted = true, sizeBytes = 100, usedBytes = 95),
            Volume("Big", mounted = true, sizeBytes = 100, usedBytes = 91),
            Volume("Free", mounted = true, sizeBytes = 100, usedBytes = 99),
        )
        assertEquals(listOf("Data", "Big"), lowSpaceVolumes(volumes, vm))
    }

    @Test
    fun lxcRules() {
        assertTrue(validMountTarget("/mnt/media"))
        assertFalse(validMountTarget("/etc"))
        assertFalse(validMountTarget("/proc/x"))
        assertFalse(validMountTarget("/mnt/"))
        assertFalse(validMountTarget("/mnt/../etc"))
        assertTrue(validMac(""))
        assertTrue(validMac("00:16:3e:aa:bb:cc"))
        assertFalse(validMac("01:16:3e:aa:bb:cc"))
        assertFalse(validMac("00:00:00:00:00:00"))
        val images = listOf(
            LxcImage("ubuntu", "22.04"), LxcImage("ubuntu", "24.04"), LxcImage("debian", "bookworm"), LxcImage("debian", "trixie"), LxcImage("alpine", "3.9"), LxcImage("alpine", "3.20"),
        )
        val groups = groupImages(images)
        assertEquals(listOf("alpine", "debian", "ubuntu"), groups.map { it.distribution })
        assertEquals(listOf("3.20", "3.9"), groups[0].releases)
        assertEquals(listOf("24.04", "22.04"), groups[2].releases)
        assertEquals("debian" to "trixie", defaultImage(images))
        assertEquals("" to "", defaultImage(emptyList()))
        assertEquals(1000L, mibToMb(954))
        assertEquals(954L, mbToMib(1000))
    }

    @Test
    fun isoPaths() {
        assertEquals("/mnt/Data/iso/sub/a.iso", isoPath("Data", "iso/sub", "a.iso"))
        assertEquals("Data" to "iso/sub", isoLocation("/mnt/Data/iso/sub/a.iso", listOf("Data")))
        assertNull(isoLocation("/mnt/Other/a.iso", listOf("Data")))
        assertNull(isoLocation("/mnt/Data/../a.iso", listOf("Data")))
        assertEquals(1, matchMedia(listOf(MediaEntry("/mnt/Data/iso/Debian-13.iso"), MediaEntry("/mnt/Data/iso/win11.iso")), "debian 13").size)
        assertEquals("debian.iso", mediaOutputName("https://x/y/debian.iso.xz"))
        assertEquals("disk.qcow2", mediaOutputName("disk.qcow2"))
        assertEquals("file.iso", mediaOutputName("file"))
    }

    @Test
    fun numbers() {
        assertEquals("537 MB", formatBytes(536_870_912, Locale.GERMANY))
        assertEquals("1,5 GB", formatBytes(1_500_000_000, Locale.GERMANY))
        assertEquals("12,3 GB", formatBytes(12_340_000_000, Locale.GERMANY))
        assertEquals("1 %", formatPercent(0.2, Locale.GERMANY))
        assertEquals("99 %", formatPercent(99.7, Locale.GERMANY))
        assertTrue(validCloneName("web-2"))
        assertFalse(validCloneName("2web"))
        assertTrue(validAdoptName("2web"))
    }
}
