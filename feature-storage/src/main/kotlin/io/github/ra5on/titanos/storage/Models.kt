package io.github.ra5on.titanos.storage

import kotlinx.serialization.Serializable

// Die Antworten des NAS für den Speicher, wie web/src/lib/api.ts sie nennt.
// Unbekannte Felder werden ignoriert, fehlende bekommen die Vorgabe.

@Serializable
internal data class Smart(
    val healthy: Boolean = true,
    val temperatureC: Int = 0,
    val powerOnHours: Long = 0,
    val errors: Long = 0,
    val rotationRate: Int = 0,
    val wearPercent: Int = -1,
)

@Serializable
internal data class Disk(
    val identity: String? = null,
    /** stick, volume, mounted, testing, small, vm: das NAS bietet die Platte nicht an. */
    val unavailableReason: String? = null,
    val name: String = "",
    val path: String = "",
    val model: String = "",
    val serial: String = "",
    val sizeBytes: Long = 0,
    /** hdd, ssd, nvme, usb */
    val kind: String = "hdd",
    /** stick, member, free, used */
    val role: String = "free",
    val volume: String? = null,
    val memberState: String? = null,
    val contents: List<String>? = null,
    val mounted: Boolean = false,
    val testing: Boolean = false,
    val smart: Smart? = null,
) {
    /** Schlüssel wie im Web (`identity || serial || name`). */
    val key: String get() = identity?.takeIf { it.isNotEmpty() } ?: serial.ifEmpty { name }
}

@Serializable
internal data class Member(val disk: String = "", val state: String = "active")

@Serializable
internal data class SyncInfo(val percent: Double = 0.0, val speedBytesPerSecond: Double = 0.0, val remainingSeconds: Long = -1)

@Serializable
internal data class VolumeCache(
    /** writethrough oder writeback */
    val mode: String = "writethrough",
    val disk: String = "",
    val active: Boolean = false,
    val missing: Boolean = false,
    val removing: Boolean = false,
    val usedBlocks: Long = 0,
    val totalBlocks: Long = 0,
    val dirtyBlocks: Long = 0,
    val readHitRate: Double = -1.0,
    val blockBytes: Long = 0,
)

@Serializable
internal data class VolumeEncryption(
    val locked: Boolean = false,
    val members: Int = 0,
    val unlocked: Int = 0,
    val passwords: Int = 0,
    val retryAfterSeconds: Int? = null,
)

@Serializable
internal data class Volume(
    val name: String = "",
    /** raid0, raid1, raid5, raid6, raid10 oder bei Btrfs single, raid1, raid1c3 */
    val level: String = "raid1",
    val filesystem: String = "ext4",
    val mountPoint: String = "",
    val mounted: Boolean = false,
    /** ok, degraded, rebuilding, syncing, checking, failed, missing */
    val state: String = "ok",
    val sizeBytes: Long = 0,
    val usedBytes: Long = 0,
    val raidDisks: Int = 0,
    val members: List<Member> = emptyList(),
    val sync: SyncInfo? = null,
    val mismatches: Long = 0,
    /** unsupported, off, pending, active */
    val shareQuotas: String? = null,
    val shareQuotasError: String? = null,
    val cache: VolumeCache? = null,
    val encryption: VolumeEncryption? = null,
    /** gentle, normal, fast; fehlt bei Btrfs und RAID 0 */
    val syncSpeed: String? = null,
)

@Serializable
internal data class DiskWakeCause(val code: String = "unknown", val detail: String? = null)

@Serializable
internal data class DiskWake(val at: String = "", val causes: List<DiskWakeCause> = emptyList())

@Serializable
internal data class WakeDay(val day: String = "", val count: Int = 0)

@Serializable
internal data class DiskSleepVolume(
    val name: String = "",
    val override: Int? = null,
    val effective: Int = 0,
    /** default, volume, disk */
    val origin: String = "default",
)

@Serializable
internal data class DiskSleepDisk(
    val id: String = "",
    val name: String = "",
    val model: String = "",
    val serial: String = "",
    val sizeBytes: Long = 0,
    val volume: String? = null,
    val override: Int? = null,
    val effective: Int = 0,
    val origin: String = "default",
    /** asleep oder active */
    val state: String = "active",
    val since: String? = null,
    val wakes: List<DiskWake>? = null,
    val wakesToday: Int? = null,
    val wakeDays: List<WakeDay>? = null,
    val error: String? = null,
)

@Serializable
internal data class DiskSleepUnavailable(
    val name: String = "",
    val model: String = "",
    val sizeBytes: Long = 0,
    val volume: String? = null,
    val reason: String = "",
)

@Serializable
internal data class SleepError(val disk: String = "", val message: String = "")

@Serializable
internal data class DiskSleepStatus(
    val idleMinutes: Int = 0,
    val volumes: Map<String, Int>? = null,
    val disks: Map<String, Int>? = null,
    val available: Boolean = false,
    val errors: List<SleepError> = emptyList(),
    val listed: Boolean? = null,
    val unavailable: List<DiskSleepUnavailable>? = null,
    val volumeStatus: List<DiskSleepVolume>? = null,
    val diskStatus: List<DiskSleepDisk>? = null,
    val missingVolumes: List<String>? = null,
    val missingDisks: List<String>? = null,
)

@Serializable
internal data class StorageStatus(
    val disks: List<Disk> = emptyList(),
    val volumes: List<Volume> = emptyList(),
    val diskSleep: DiskSleepStatus = DiskSleepStatus(),
)

// ---------------------------------------------------------------- Wartung (nur was der Hinweis braucht)

@Serializable
internal data class MaintenanceJob(
    val id: String = "",
    val kind: String = "",
    val target: String = "",
    val enabled: Boolean = false,
)

@Serializable
internal data class MaintenanceSettings(val enabled: Boolean = false, val jobs: List<MaintenanceJob> = emptyList())

@Serializable
internal data class MaintenanceStatus(val settings: MaintenanceSettings = MaintenanceSettings())

// ---------------------------------------------------------------- Was auf einem Volume liegt

@Serializable
internal data class ShareRef(val name: String = "", val volume: String = "")

@Serializable
internal data class VmDiskRef(val volume: String? = null)

@Serializable
internal data class VmRef(val name: String = "", val displayName: String? = null, val volume: String? = null, val disks: List<VmDiskRef>? = null)

@Serializable
internal data class VmList(val vms: List<VmRef>? = null)

@Serializable
internal data class DockerSettings(val volume: String? = null)

@Serializable
internal data class DockerRef(val settings: DockerSettings? = null, val apps: List<String>? = null)

internal data class VolumeUsage(val shares: List<String>, val vms: List<String>, val docker: List<String>?, val failed: Boolean)

// ---------------------------------------------------------------- Platten-Hinweise (SMR)

@Serializable
internal data class HwDisk(val name: String = "", val model: String = "", val smr: String? = null)

@Serializable
internal data class HwDisks(val disks: List<HwDisk> = emptyList())

// ---------------------------------------------------------------- Verwalten

@Serializable
internal data class UsageEntry(
    val id: String? = null,
    val kind: String? = null,
    val name: String = "",
    val bytes: Long = 0,
    val allocated: Long = 0,
    val files: Long = 0,
    val incomplete: Boolean = false,
    val error: String = "",
)

@Serializable
internal data class UsageResult(val at: String = "", val entries: List<UsageEntry> = emptyList(), val incomplete: Boolean = false, val scanned: Long = 0)

@Serializable
internal data class Growth(
    val id: String = "",
    val volume: String = "",
    val recoveryVolume: String = "",
    val arrayUUID: String = "",
    val diskSerial: String = "",
    val currentBytes: Long? = null,
    val plannedBytes: Long? = null,
    val originalDisks: Int = 0,
    val targetDisks: Int = 0,
    val phase: String = "",
    val error: String = "",
    val started: String = "",
)

@Serializable
internal data class GrowthStatus(val pending: Boolean = false, val active: Boolean? = null, val operation: Growth = Growth(), val error: String = "")

@Serializable
internal data class SnapshotName(val name: String = "")

@Serializable
internal data class SnapshotEntry(val path: String = "", val directory: Boolean = false, val bytes: Long = 0, val modified: String = "")

@Serializable
internal data class SnapshotFiles(val entries: List<SnapshotEntry> = emptyList(), val next: Int = 0)

@Serializable
internal data class FileRestoreInfo(val uuid: String = "", val files: Long = 0, val bytes: Long = 0)

internal data class FileRestorePreview(val uuid: String, val files: Long, val bytes: Long, val paths: List<String>, val targetVolume: String)

@Serializable
internal data class FileRestoreResult(val folder: String = "", val files: Long = 0, val bytes: Long = 0)

@Serializable
internal data class FolderRestorePreview(
    val uuid: String = "",
    val path: String = "",
    val mode: String = "original",
    val conflict: String = "skip",
    val target: String = "",
    val files: Long = 0,
    val folders: Long = 0,
    val links: Long = 0,
    val special: Long = 0,
    val bytes: Long = 0,
    val conflicts: Long = 0,
    val partial: Boolean = false,
)

@Serializable
internal data class FolderRestoreJob(
    val id: String = "",
    val user: String = "",
    val volume: String = "",
    val snapshot: String = "",
    val path: String = "",
    val mode: String = "original",
    val conflict: String = "skip",
    val target: String = "",
    /** running, done, failed, cancelled */
    val state: String = "running",
    /** counting, copying */
    val phase: String = "counting",
    val totalFiles: Long = 0,
    val totalBytes: Long = 0,
    val files: Long = 0,
    val bytes: Long = 0,
    val copied: Long = 0,
    val cloned: Long = 0,
    val skipped: Long = 0,
    val replaced: Long = 0,
    val renamed: Long = 0,
    val special: Long = 0,
    val error: String? = null,
    val started: String = "",
    val finished: String? = null,
)

@Serializable
internal data class FolderRestoreJobs(val restores: List<FolderRestoreJob> = emptyList())

// ---------------------------------------------------------------- Importieren

@Serializable
internal data class ImportCandidate(
    val id: String = "",
    val filesystem: String = "",
    val filesystemUUID: String = "",
    val arrayUUID: String = "",
    val level: String = "",
    val disks: List<String> = emptyList(),
    val size: Long = 0,
    val fingerprint: String = "",
    val reason: String = "",
    val encrypted: Boolean? = null,
    val encryptionUUID: String? = null,
)

@Serializable
internal data class ImportPreview(
    val candidate: ImportCandidate = ImportCandidate(),
    val folders: List<String> = emptyList(),
    val truncated: Boolean = false,
    val readOnly: Boolean = false,
)

// ---------------------------------------------------------------- SMART

@Serializable
internal data class SmartAttribute(
    val id: Int = 0,
    val name: String = "",
    val value: Int = 0,
    val worst: Int = 0,
    val threshold: Int = 0,
    val raw: String = "",
    val flags: String? = null,
    val prefailure: Boolean = false,
    val whenFailed: String? = null,
    val warn: Boolean? = null,
)

@Serializable
internal data class NvmeValues(
    val criticalWarning: Int = 0,
    val percentageUsed: Int = 0,
    val availableSpare: Int = 0,
    val availableSpareThreshold: Int = 0,
    val mediaErrors: Long = 0,
    val errorLogEntries: Long = 0,
    val unsafeShutdowns: Long = 0,
    val dataUnitsRead: Long = 0,
    val dataUnitsWritten: Long = 0,
)

@Serializable
internal data class SmartValues(
    val protocol: String = "",
    val modelFamily: String? = null,
    val firmware: String? = null,
    val passed: Boolean = true,
    val temperatureC: Int? = null,
    val powerOnHours: Long? = null,
    val powerCycles: Long? = null,
    val rotationRate: Int = 0,
    val reallocatedSectors: Long? = null,
    val pendingSectors: Long? = null,
    val uncorrectableSectors: Long? = null,
    val crcErrors: Long? = null,
    val nvme: NvmeValues? = null,
    val attributes: List<SmartAttribute>? = null,
)

@Serializable
internal data class SmartReport(
    val disk: String = "",
    val path: String = "",
    val model: String = "",
    val serial: String = "",
    val kind: String = "hdd",
    val role: String = "",
    val volume: String? = null,
    /** ok, warning, failed, unavailable, unknown */
    val state: String = "unknown",
    val warnings: List<String> = emptyList(),
    val sleeping: Boolean = false,
    val checkedAt: String? = null,
    val readAt: String? = null,
    val values: SmartValues? = null,
)

@Serializable
internal data class SmartList(val disks: List<SmartReport>? = null)

@Serializable
internal data class SmartSample(
    val day: String = "",
    val readAt: String = "",
    val temperatureC: Long? = null,
    val powerOnHours: Long? = null,
    val reallocatedSectors: Long? = null,
    val pendingSectors: Long? = null,
    val uncorrectableSectors: Long? = null,
    val crcErrors: Long? = null,
    val mediaErrors: Long? = null,
    val percentageUsed: Long? = null,
    val availableSpare: Long? = null,
)

@Serializable
internal data class SmartHistory(val disk: String = "", val volume: String = "", val samples: List<SmartSample> = emptyList())

// ---------------------------------------------------------------- Arbeitsspeicher

@Serializable
internal data class MemoryPressure(val someAvg10: Double = 0.0, val fullAvg10: Double = 0.0)

@Serializable
internal data class SystemMemory(
    val totalBytes: Long = 0,
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
    val availableBytes: Long = 0,
    val cacheBytes: Long? = null,
    val dirtyBytes: Long? = null,
    val writebackBytes: Long? = null,
    val reserveBytes: Long? = null,
    val lowAvailable: Boolean? = null,
    val pressure: MemoryPressure? = null,
)

@Serializable
internal data class MemorySettings(val cachePercent: Int = 20)

@Serializable
internal data class MemoryStatus(
    val settings: MemorySettings = MemorySettings(),
    val readOnly: Boolean = false,
    val budgetBytes: Long = 0,
    val reserveBytes: Long = 0,
    val inFlightBytes: Long = 0,
    val memory: SystemMemory = SystemMemory(),
    val paused: Boolean = false,
    val error: String? = null,
)

