package io.github.ra5on.titanos.storage

import java.time.Instant

// Die Regeln der Speicher-Seiten des Webs (btrfs.ts, crypt.ts, labels.ts,
// disk-swap.ts, checks-hint.ts, disk-sleep.ts, sleep-view.ts, smart.ts,
// smart-history.ts, snapshot-restore.ts, hwcheck.ts) ohne Oberfläche.
// Das NAS prüft alles selbst noch einmal; hier steht nur, was die Seite anbietet.

// ---------------------------------------------------------------- Btrfs (btrfs.ts)

internal val BTRFS_COPIES = mapOf("single" to 1, "raid1" to 2, "raid1c3" to 3)
internal val BTRFS_MIN = mapOf("single" to 1, "raid1" to 2, "raid1c3" to 3)

/**
 * Platz, den ein Btrfs-Profil auf Platten dieser Größen gibt, dieselbe
 * Schätzung wie btrfsUsable im NAS: jede Kopie eines Blocks liegt auf einer
 * anderen Platte, die größten füllen sich nur so weit, wie die anderen die
 * Kopien aufnehmen.
 */
internal fun btrfsUsable(profile: String, sizes: List<Long>): Long {
    if (sizes.isEmpty()) return 0
    val sorted = sizes.sorted()
    val sum = sorted.sum()
    val copies = BTRFS_COPIES[profile] ?: 1
    if (copies <= 1) return sum
    val rest = sorted.take(maxOf(0, sorted.size - (copies - 1))).sum()
    return minOf(Math.floorDiv(sum, copies.toLong()), rest)
}

/** Profil eines Btrfs-Volumes; Volumes von vor 0.29.0 sind RAID 1. */
internal fun profileOf(volume: Volume): String = if (volume.level in BTRFS_MIN) volume.level else "raid1"

/** Ob ein Volume eine weitere Platte nimmt (dieselbe Regel wie AddDisk im NAS). */
internal fun takesDisk(volume: Volume): Boolean =
    if (volume.filesystem == "btrfs") volume.state == "ok" || volume.state == "degraded"
    else volume.level != "raid0" && volume.state != "failed" && volume.state != "missing"

/** Ob eine Platte aus einem vollständigen Btrfs-Volume genommen werden kann. */
internal fun canShrink(volume: Volume): Boolean =
    volume.filesystem == "btrfs" && volume.state == "ok" && volume.members.size > (BTRFS_MIN[profileOf(volume)] ?: 2)

// ---------------------------------------------------------------- Verschlüsselung (crypt.ts)

internal fun isLocked(volume: Volume): Boolean = volume.encryption?.locked == true

/** Gesperrt ist ein Wartezustand: ein gesundes gesperrtes Volume steht als Warnung da. */
internal fun lockedWarns(volume: Volume): Boolean = isLocked(volume) && volume.state == "ok"

/** Ein gesperrtes mdadm-Volume nimmt noch Platten und Prüfungen (unter der Verschlüsselung), Btrfs nicht. */
internal fun lockedBlocks(volume: Volume): Boolean = isLocked(volume) && volume.filesystem == "btrfs"

// ---------------------------------------------------------------- Zustände (labels.ts)

internal enum class Tone3 { Ok, Warn, Error }

internal fun stateTone(volume: Volume): Tone3 {
    if (lockedWarns(volume)) return Tone3.Warn
    return when (volume.state) {
        "ok" -> Tone3.Ok
        "degraded", "failed", "missing" -> Tone3.Error
        else -> Tone3.Warn
    }
}

/** Platten, die ein Volume nehmen darf: nicht der Stick, in keinem Volume, nichts eingebunden, kein Test. */
internal fun isUsable(disk: Disk): Boolean =
    disk.unavailableReason.isNullOrEmpty() && (disk.role == "free" || disk.role == "used") && !disk.mounted && !disk.testing

/** Was das Volume aufnimmt, gleich für jede Platte wie die kleinste (CreateVolumeDialog). */
internal sealed interface Capacity {
    data class Needs(val count: Int) : Capacity
    data object Even : Capacity
    data class Bytes(val bytes: Long) : Capacity
}

internal val MDADM_LEVELS = listOf("raid1" to 2, "raid5" to 3, "raid6" to 4, "raid10" to 4, "raid0" to 1)
internal val BTRFS_LEVELS = listOf("raid1" to 2, "raid1c3" to 3, "single" to 1)
internal val FILESYSTEMS = listOf("ext4", "xfs", "btrfs")

internal fun levelsFor(filesystem: String) = if (filesystem == "btrfs") BTRFS_LEVELS else MDADM_LEVELS

internal fun capacity(filesystem: String, level: String, sizes: List<Long>): Capacity {
    val min = levelsFor(filesystem).firstOrNull { it.first == level }?.second ?: 1
    val n = sizes.size
    if (n < min) return Capacity.Needs(min)
    if (filesystem == "btrfs") return Capacity.Bytes(btrfsUsable(level, sizes))
    if (level == "raid10" && n % 2 != 0) return Capacity.Even
    val smallest = sizes.min()
    val data = when (level) {
        "raid0" -> n
        "raid1" -> 1
        "raid5" -> n - 1
        "raid6" -> n - 2
        "raid10" -> n / 2
        else -> 1
    }
    return Capacity.Bytes(smallest * data)
}

/** Name eines Volumes wie im NAS: Buchstabe, dann Buchstaben, Ziffern, Bindestrich, höchstens 12. */
internal val VOLUME_NAME = Regex("^[A-Za-z][A-Za-z0-9-]{0,11}$")

internal val SNAPSHOT_NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9_\\-]{0,63}$")

// ---------------------------------------------------------------- Platte ersetzen (disk-swap.ts)

/** rebuild, live, addRemove, impossible – siehe docs/DISK-SWAP.md. */
internal data class SwapCandidate(val disk: String, val state: String, val present: Boolean, val warn: Boolean, val mode: String)

internal fun swapCandidates(volume: Volume, disks: List<Disk>): List<SwapCandidate> {
    val noCopy = volume.level == "raid0" && volume.filesystem != "btrfs"
    val list = volume.members.map { member ->
        val disk = disks.firstOrNull { it.name == member.disk }
        val failed = member.state == "faulty" || disk == null
        val warn = disk?.smart != null && (!disk.smart.healthy || disk.smart.errors > 0)
        val mode = when {
            noCopy -> "impossible"
            failed -> "rebuild"
            volume.filesystem == "btrfs" -> if (volume.members.size > 1) "addRemove" else "impossible"
            volume.state != "ok" || member.state != "active" -> "impossible"
            else -> "live"
        }
        SwapCandidate(member.disk, member.state, disk != null, warn, mode)
    }.sortedWith(
        compareByDescending<SwapCandidate> { it.mode == "rebuild" }.thenByDescending { it.warn }.thenBy { it.disk },
    ).toMutableList()
    if (!noCopy && volume.state == "degraded" && list.none { it.mode == "rebuild" }) {
        list.add(0, SwapCandidate("", "missing", false, false, "rebuild"))
    }
    return list
}

internal fun needsSwap(volume: Volume): Boolean =
    volume.state == "degraded" || volume.state == "failed" || volume.members.any { it.state == "faulty" }

/** Platten, die den Platz der alten nehmen können: frei oder benutzt, in keinem Volume, mindestens so groß. */
internal fun replacementDisks(disks: List<Disk>, old: Disk?, smallest: Long): List<Disk> {
    val need = maxOf(old?.sizeBytes ?: 0, smallest)
    return disks.filter {
        it.name != old?.name && it.unavailableReason.isNullOrEmpty() && (it.role == "free" || it.role == "used") &&
            !it.mounted && !it.testing && it.sizeBytes >= need
    }
}

internal fun newlyAttached(before: List<String>, now: List<Disk>): List<Disk> = now.filter { (it.identity ?: it.name) !in before }

// ---------------------------------------------------------------- Prüfungen-Hinweis (checks-hint.ts)

internal fun uncheckedVolumes(volumes: List<Volume>, disks: List<Disk>, maintenance: MaintenanceSettings): List<Volume> {
    val jobs = if (maintenance.enabled) maintenance.jobs.filter { it.enabled } else emptyList()
    return volumes.filter { volume ->
        if (volume.state == "missing") return@filter false
        if (volume.level == "raid0" && volume.filesystem != "btrfs") {
            val serials = volume.members.mapNotNull { m -> disks.firstOrNull { it.name == m.disk }?.serial?.takeIf { it.isNotEmpty() } }.toSet()
            jobs.none { (it.kind == "smart-short" || it.kind == "smart-long") && it.target in serials }
        } else {
            jobs.none { it.kind == "raid-check" && it.target == volume.name }
        }
    }
}

internal fun checksHintVolumes(unchecked: List<Volume>, dismissed: List<String>): List<String> =
    unchecked.map { it.name }.filter { it !in dismissed }

// ---------------------------------------------------------------- Ruhemodus (disk-sleep.ts, sleep-view.ts)

internal val SLEEP_MINUTES = listOf(0, 5, 10, 15, 30, 60, 120, 180)

/** Die Ruhezeit, die eine Platte bekommt: ihre eigene, sonst die des Volumes, sonst der Standard. */
internal fun effectiveSleep(defaultMinutes: Int, volumes: Map<String, Int>, volume: String?, own: Int?): Pair<Int, String> = when {
    own != null -> own to "disk"
    volume != null && volumes[volume] != null -> volumes.getValue(volume) to "volume"
    else -> defaultMinutes to "default"
}

/** Nur die Ausnahmen der Schlüssel, die es jetzt gibt, sortiert. */
internal fun overridesFrom(values: Map<String, Int>, keys: List<String>): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    for (k in keys.sorted()) values[k]?.let { out[k] = it }
    return out
}

internal fun sleepListed(status: DiskSleepStatus?): Boolean =
    status?.listed == true || (status?.volumeStatus != null && status.diskStatus != null)

internal data class VolumeSleepInfo(val minutes: Int, val origin: String, val asleep: Int, val total: Int, val disks: List<DiskSleepDisk>)

internal fun volumeSleep(status: DiskSleepStatus?, name: String): VolumeSleepInfo? {
    if (!sleepListed(status)) return null
    val v = status!!.volumeStatus?.firstOrNull { it.name == name } ?: return null
    val disks = status.diskStatus.orEmpty().filter { it.volume == name }
    return VolumeSleepInfo(v.effective, v.origin, disks.count { it.state == "asleep" }, disks.size, disks)
}

internal data class DiskSleepInfo(val disk: DiskSleepDisk?, val reason: String?)

internal fun diskSleepOf(status: DiskSleepStatus?, name: String): DiskSleepInfo? {
    if (!sleepListed(status)) return null
    status!!.diskStatus?.firstOrNull { it.name == name }?.let { return DiskSleepInfo(it, null) }
    return DiskSleepInfo(null, status.unavailable?.firstOrNull { it.name == name }?.reason)
}

/** Der Körper von PUT storage/disk-sleep wie das Web: Listen nur, wenn das NAS die Platten kennt. */
internal fun sleepBody(status: DiskSleepStatus, idle: Int, volumes: Map<String, Int>, disks: Map<String, Int>): DiskSleepBody =
    if (sleepListed(status)) {
        DiskSleepBody(
            idle,
            overridesFrom(volumes, status.volumeStatus.orEmpty().map { it.name }),
            overridesFrom(disks, status.diskStatus.orEmpty().map { it.id }.filter { it.isNotEmpty() }),
        )
    } else {
        DiskSleepBody(idle, null, null)
    }

private val SYSTEM_DATA = setOf("journal", "activity_log", "notifications", "smart_history", "monitoring", "config_backup")
private val LIKELY = setOf("smb_session", "docker_running")

/** Welche Hinweise zu den letzten Weckvorgängen passen, der häufigste zuerst (systemData, unknown, likely). */
internal fun wakeHints(disk: DiskSleepDisk): List<String> {
    val count = linkedMapOf("systemData" to 0, "unknown" to 0, "likely" to 0)
    for (wake in disk.wakes.orEmpty()) {
        val codes = wake.causes.map { it.code }
        if (codes.any { it in SYSTEM_DATA }) count["systemData"] = count.getValue("systemData") + 1
        if ("unknown" in codes) count["unknown"] = count.getValue("unknown") + 1
        if (codes.any { it in LIKELY }) count["likely"] = count.getValue("likely") + 1
    }
    return count.filter { it.value > 0 }.entries.sortedByDescending { it.value }.map { it.key }
}

// ---------------------------------------------------------------- SMR (hwcheck.ts)

internal fun smrForLevel(level: String, selected: List<String>, disks: List<HwDisk>): List<HwDisk> {
    if (level != "raid5" && level != "raid6") return emptyList()
    val names = selected.map { it.removePrefix("/dev/") }.toSet()
    return disks.filter { !it.smr.isNullOrEmpty() && it.name in names }
}

// ---------------------------------------------------------------- SMART (smart.ts, smart-history.ts)

private val SMART_RANK = mapOf("failed" to 0, "warning" to 1, "ok" to 2, "unknown" to 3, "unavailable" to 4)

/** Natürliche Reihenfolge (sda, sdb, sdaa; nvme0n1 vor nvme10n1). */
internal val natural: Comparator<String> = Comparator { a, b ->
    val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
    val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y, ignoreCase = true)
        if (c != 0) return@Comparator c
    }
    ra.size - rb.size
}

internal fun sortReports(reports: List<SmartReport>): List<SmartReport> =
    reports.sortedWith(compareBy<SmartReport> { SMART_RANK[it.state] ?: 9 }.thenComparator { a, b -> natural.compare(a.disk, b.disk) })

internal data class SmartCounts(val ok: Int, val warning: Int, val failed: Int, val sleeping: Int, val other: Int)

internal fun smartCounts(reports: List<SmartReport>): SmartCounts {
    var ok = 0
    var warning = 0
    var failed = 0
    var sleeping = 0
    var other = 0
    for (r in reports) {
        if (r.sleeping) sleeping++
        when (r.state) {
            "ok" -> ok++
            "warning" -> warning++
            "failed" -> failed++
            else -> other++
        }
    }
    return SmartCounts(ok, warning, failed, sleeping, other)
}

internal fun sectorProblems(values: SmartValues?): Long? {
    if (values == null) return null
    val known = listOfNotNull(values.reallocatedSectors, values.pendingSectors, values.uncorrectableSectors)
    return if (known.isEmpty()) null else known.sum()
}

internal val VALID_DISK = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$")

internal val HISTORY_FIELDS = listOf(
    "reallocatedSectors", "pendingSectors", "uncorrectableSectors", "crcErrors", "mediaErrors", "percentageUsed", "temperatureC",
)

internal fun SmartSample.value(field: String): Long? = when (field) {
    "reallocatedSectors" -> reallocatedSectors
    "pendingSectors" -> pendingSectors
    "uncorrectableSectors" -> uncorrectableSectors
    "crcErrors" -> crcErrors
    "mediaErrors" -> mediaErrors
    "percentageUsed" -> percentageUsed
    "temperatureC" -> temperatureC
    else -> null
}

internal data class HistoryRow(
    val field: String,
    val first: Long,
    val firstDay: String,
    val last: Long,
    val lastDay: String,
    val roseOn: String?,
    val min: Long,
    val max: Long,
) {
    /** Ob ein Zähler im Verlauf gestiegen ist (die Temperatur geht auf und ab). */
    val rose: Boolean get() = this.field != "temperatureC" && last > first
}

internal fun historyRows(samples: List<SmartSample>): List<HistoryRow> {
    val rows = mutableListOf<HistoryRow>()
    for (field in HISTORY_FIELDS) {
        var row: HistoryRow? = null
        for (s in samples) {
            val v = s.value(field) ?: continue
            val r = row
            row = if (r == null) {
                HistoryRow(field, v, s.day, v, s.day, null, v, v)
            } else {
                val rose = if (field != "temperatureC" && v > r.last && r.roseOn == null) s.day else r.roseOn
                r.copy(last = v, lastDay = s.day, roseOn = rose, min = minOf(r.min, v), max = maxOf(r.max, v))
            }
        }
        row?.let { rows += it }
    }
    return rows
}

// ---------------------------------------------------------------- Wiederherstellen (snapshot-restore.ts)

internal fun folderRestoreProgress(job: FolderRestoreJob): Double? {
    if (job.state == "done") return 100.0
    if (job.phase != "copying") return null
    val (done, total) = if (job.totalBytes > 0) job.bytes to job.totalBytes else job.files to job.totalFiles
    if (total <= 0) return null
    return (done.toDouble() / total * 100).coerceIn(0.0, 100.0)
}

internal fun needsReplaceConsent(preview: FolderRestorePreview?): Boolean =
    preview != null && preview.mode == "original" && preview.conflict == "replace" && preview.conflicts > 0

// ---------------------------------------------------------------- Zeit

internal fun instant(value: String?): Instant? = try {
    if (value.isNullOrBlank()) null else Instant.parse(value)
} catch (_: Exception) {
    try {
        java.time.OffsetDateTime.parse(value).toInstant()
    } catch (_: Exception) {
        null
    }
}
