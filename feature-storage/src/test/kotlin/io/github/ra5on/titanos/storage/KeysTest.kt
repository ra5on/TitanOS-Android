package io.github.ra5on.titanos.storage

import io.github.ra5on.titanos.admin.hasText
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Jeder feste Web-Schlüssel, den das Modul benutzt, steht in den erzeugten Texten (sonst stünde der Schlüssel selbst da). */
class KeysTest {
    @Test fun allLiteralKeysExist() {
        val key = Regex("""\bt(?:Or)?\("([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)"""")
        val used = File("src/main/kotlin").walk().filter { it.extension == "kt" }
            .flatMap { f -> key.findAll(f.readText()).map { it.groupValues[1] } }.toSortedSet()
        assertTrue("keine Schlüssel gefunden", used.size > 100)
        val missing = used.filterNot(::hasText)
        assertTrue("fehlende Schlüssel: $missing", missing.isEmpty())
    }

    @Test fun templateKeysExist() {
        val keys = buildList {
            for (s in listOf("ok", "degraded", "rebuilding", "syncing", "checking", "failed", "missing")) add("state.$s")
            for (s in listOf("active", "rebuilding", "faulty", "spare")) add("member.$s")
            for (s in listOf("ext4", "xfs", "btrfs")) { add("fs.$s"); add("fs.${s}Desc") }
            for (s in listOf("raid0", "raid1", "raid5", "raid6", "raid10", "single", "raid1c3")) { add("level.$s"); add("level.${s}Desc") }
            for (s in listOf("stick", "volume", "mounted", "testing", "small", "vm")) add("disk.blocked.$s")
            for (s in listOf("hdd", "ssd", "nvme", "usb")) add("disk.$s")
            for (s in listOf("ssd", "nvme", "removable", "stick", "name", "transport")) add("storage.sleepReason.$s")
            for (s in listOf("off", "pending", "active")) add("storage.shareQuotas.$s")
            for (s in listOf("gentle", "normal", "fast")) { add("syncSpeed.$s"); add("syncSpeed.${s}Desc") }
            for (s in listOf("unlock", "lock", "change", "add", "remove", "header")) { add("crypt.${s}Title"); add("crypt.${s}Text") }
            for (s in listOf("unlock", "lock", "change", "add", "remove", "header")) add("crypt.${s}Submit")
            for (s in listOf("enable", "mode", "disable")) { add("cache.${s}Title"); add("cache.${s}Submit") }
            for (s in listOf("ok", "warning", "failed", "unavailable", "unknown")) add("smart.state.$s")
            for (s in listOf("smartFailed", "attributeNow", "attributePast", "reallocated", "pending", "uncorrectable", "crc", "temperature", "nvmeCritical", "wear", "mediaErrors", "spare")) add("smart.warn.$s")
            for (f in HISTORY_FIELDS) { add("smartHistory.field.$f"); add("smartHistory.short.$f") }
            for (s in listOf("pick", "insert", "running", "done")) add("swap.step.$s")
            for (s in listOf("rebuild", "live", "addRemove", "impossible")) add("swap.mode.$s")
            for (s in listOf("running", "done", "failed", "cancelled")) add("snapshotRestore.state.$s")
            for (s in listOf("skip", "rename", "replace")) add("files.conflict.$s")
            add("ops.usage.other")
        }
        val missing = keys.filterNot(::hasText)
        assertTrue("fehlende Schlüssel: $missing", missing.isEmpty())
    }
}
