package io.github.ra5on.titanos.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Antworten des NAS für Werkzeuge (wie die Typen in web/src/lib/operations.ts,
// taskmanager.ts, activity.ts, logs.ts, hwcheck.ts, routes/tools/scripts/scripts.ts).
// Felder, die das NAS als `null` schicken kann, sind nullable (TitanJson wandelt
// null nicht in Vorgabewerte).

// ---------------------------------------------------------------- Aufträge (GET /operations)

@Serializable
data class MaintenanceJob(
    val id: String = "",
    val dependsOn: String? = "",
    val name: String = "",
    val kind: String = "raid-check",
    val target: String = "",
    val enabled: Boolean = true,
    val every: String = "monthly",
    val everyHours: Int? = null,
    val weekdays: List<Int>? = listOf(0),
    val monthDay: Int = 1,
    val time: String = "03:00",
    val missed: String = "window",
    val readData: Int = 5,
    val maxBytes: Long = 64L * 1000 * 1000,
    val maxFiles: Int = 20,
    val keep: Int = 7,
    val allowPrune: Boolean = false,
    val lockDays: Int? = null,
    /** Regeln nach Tag/Woche/Monat (ab 0.41.0); die App schickt sie unverändert zurück. */
    val versions: JsonElement? = null,
)

@Serializable
data class MaintenanceSettings(
    val backupsFirst: Boolean? = false,
    val enabled: Boolean = false,
    val volume: String = "",
    val timezone: String = "",
    val windowFrom: String = "00:00",
    val windowTo: String = "00:00",
    val jobs: List<MaintenanceJob> = emptyList(),
)

@Serializable
data class MaintenanceState(
    val running: Boolean = false,
    val blocked: Boolean = false,
    val lastAttempt: String? = null,
    val lastOK: String? = null,
    val error: String = "",
    val phase: String = "",
)

@Serializable
data class MaintenanceResult(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val target: String = "",
    val started: String = "",
    val finished: String? = null,
    val error: String = "",
)

@Serializable
data class MaintenanceJobView(val job: MaintenanceJob = MaintenanceJob(), val state: MaintenanceState = MaintenanceState(), val wait: String? = null, val next: String? = null)

@Serializable
data class MaintenanceStatus(
    val settings: MaintenanceSettings = MaintenanceSettings(),
    val jobs: List<MaintenanceJobView> = emptyList(),
    val history: List<MaintenanceResult> = emptyList(),
    val available: Boolean = true,
    val error: String = "",
)

@Serializable
data class CopyJob(
    val id: String = "",
    val blocked: Boolean? = null,
    val waiting: Boolean? = null,
    val paused: Boolean? = null,
    val priority: Int? = null,
    val rate: Long? = null,
    val operation: String? = null,
    val retryable: Boolean? = null,
    val state: String = "",
    val from: String = "",
    val to: String = "",
    val bytes: Long = 0,
    val copied: Long = 0,
    val current: String? = "",
    val error: String? = "",
    val failed: Int? = null,
)

@Serializable
data class MediaJob(val id: String = "", val name: String = "", val phase: String = "", val bytes: Long = 0, val total: Long = 0, val output: String? = null, val error: String? = null)

@Serializable
data class ShareDeletion(
    val id: String = "",
    val path: String = "",
    val phase: String = "",
    val started: String? = null,
    val finished: String? = null,
    val files: Long = 0,
    val totalFiles: Long = 0,
    val error: String? = null,
)

@Serializable
data class BackupProbe(val error: String = "")

@Serializable
data class ApplicationRestore(val at: String = "", val snapshot: String = "", val target: String = "", val verified: Boolean = false, val error: String = "")

@Serializable
data class BackupState(
    val running: Boolean = false,
    val phase: String = "",
    val lastOK: String? = null,
    val lastError: String = "",
    val checkError: String = "",
    val statusError: String? = null,
    val applicationRestore: ApplicationRestore? = null,
    val lastRun: String? = null,
    val warning: String? = null,
    val probe: BackupProbe? = null,
)

@Serializable
data class BackupDestination(val type: String = "")

@Serializable
data class BackupJobRef(val id: String = "", val destination: BackupDestination = BackupDestination())

@Serializable
data class BackupView(val stateKnown: Boolean? = null, val job: BackupJobRef = BackupJobRef(), val state: BackupState = BackupState(), val next: String? = null)

@Serializable
data class BackupList(val jobs: List<BackupView> = emptyList())

@Serializable
data class DockerJob(val state: String = "", val output: String = "", val code: String? = null)

@Serializable
data class VmJob(val state: String = "", val text: String? = null, val operation: String? = null)

@Serializable
data class Vm(
    val name: String = "",
    val displayName: String? = null,
    val state: String = "",
    val available: Boolean = true,
    val cpuPercent: Double = 0.0,
    val memoryBytes: Long = 0,
    val job: VmJob? = null,
)

@Serializable
data class VmList(val vms: List<Vm> = emptyList())

@Serializable
data class DiskTestBrief(val disk: String = "", val phase: String = "", val result: String = "", val percent: Double = 0.0)

@Serializable
data class PluginStep(val id: String = "", val state: String = "", val error: String? = null)

@Serializable
data class PluginProgress(
    val id: String = "",
    val plugin: String = "",
    val title: String? = null,
    val state: String = "",
    val steps: List<PluginStep> = emptyList(),
    val bytes: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val started: String? = null,
    val finished: String? = null,
)

@Serializable
data class FolderRestoreJob(
    val id: String = "",
    val volume: String = "",
    val path: String = "",
    val target: String = "",
    val state: String = "",
    val phase: String = "",
    val totalFiles: Long = 0,
    val totalBytes: Long = 0,
    val files: Long = 0,
    val bytes: Long = 0,
    val error: String? = null,
    val started: String? = null,
    val finished: String? = null,
)

@Serializable
data class GrowthOperation(val volume: String = "", val error: String = "")

@Serializable
data class GrowthStatus(val pending: Boolean = false, val active: Boolean? = null, val operation: GrowthOperation = GrowthOperation(), val error: String = "")

@Serializable
data class OperationRef(val id: String = "", val job: String = "")

@Serializable
data class ApplicationRecoveryBrief(val operation: OperationRef? = null, val active: Boolean = false)

@Serializable
data class Upload(val id: String = "", val name: String = "", val size: Long = 0, val offset: Long = 0, val busy: Boolean? = null)

@Serializable
data class CloneVm(val name: String = "", val volume: String = "")

@Serializable
data class CloneOperation(val id: String = "", val phase: String = "", val source: String = "", val error: String? = null, val vm: CloneVm = CloneVm())

@Serializable
data class Operations(
    val media: List<MediaJob>? = null,
    val shareDeletions: List<ShareDeletion>? = null,
    val storageActive: Boolean? = null,
    val clones: List<CloneOperation>? = null,
    val unavailable: List<String>? = null,
    val growthError: String? = null,
    val uploads: List<Upload>? = null,
    val applicationRecovery: ApplicationRecoveryBrief? = null,
    val maintenance: MaintenanceStatus? = null,
    val transfers: List<CopyJob>? = null,
    val backups: List<BackupView>? = null,
    val docker: Map<String, DockerJob>? = null,
    val vms: List<Vm>? = null,
    val growth: GrowthStatus? = null,
    val diskTests: List<DiskTestBrief>? = null,
    val plugins: List<PluginProgress>? = null,
    val snapshotRestores: List<FolderRestoreJob>? = null,
)

// ---------------------------------------------------------------- Wiederherstellung prüfen

@Serializable
data class RecoveryMember(val name: String = "", val kind: String = "", val running: Boolean = false, val phase: String = "", val error: String = "")

@Serializable
data class ApplicationRecovery(val id: String = "", val volume: String = "", val job: String = "", val members: List<RecoveryMember>? = null)

@Serializable
data class ApplicationRecoveryStatus(val operation: ApplicationRecovery? = null, val active: Boolean = false, val error: String? = null, val journalMissing: Boolean? = null)

@Serializable
data class Clone(val id: String = "", val source: String = "", val vm: CloneVm = CloneVm(), val phase: String = "", val error: String = "")

// ---------------------------------------------------------------- Speicher (Auszug)

@Serializable
data class SmartBrief(val temperatureC: Int = 0)

@Serializable
data class Disk(
    val name: String = "",
    val model: String = "",
    val serial: String = "",
    val sizeBytes: Long = 0,
    val kind: String = "",
    val role: String = "",
    val volume: String? = null,
    val mounted: Boolean? = null,
    val testing: Boolean? = null,
    val contents: List<String>? = null,
    val smart: SmartBrief? = null,
)

@Serializable
data class VolumeMember(val disk: String = "", val state: String = "")

@Serializable
data class Volume(val name: String = "", val filesystem: String = "", val mounted: Boolean = false, val members: List<VolumeMember>? = null)

@Serializable
data class DiskSleepState(val name: String = "", val state: String = "")

@Serializable
data class DiskSleep(val diskStatus: List<DiskSleepState>? = null)

@Serializable
data class StorageStatus(val volumes: List<Volume> = emptyList(), val disks: List<Disk> = emptyList(), val diskSleep: DiskSleep? = null)

@Serializable
data class NetworkTimezone(val timezone: String = "")

// ---------------------------------------------------------------- Monitoring

@Serializable
data class MonitorSettings(val enabled: Boolean = false, val volume: String = "", val days: Int = 7, val maxMiB: Long = 8)

@Serializable
data class MonitorStatus(val settings: MonitorSettings = MonitorSettings(), val last: String? = null, val error: String = "")

@Serializable
data class SampleDisk(val name: String = "", val readBps: Double = 0.0, val writeBps: Double = 0.0, val latencyMs: Double? = null)

@Serializable
data class SampleVolume(val name: String = "", val used: Double = 0.0, val total: Double = 0.0)

@Serializable
data class SampleNetwork(val name: String = "", val receive: Double = 0.0, val send: Double = 0.0)

@Serializable
data class MonitorSample(
    val at: String = "",
    val gap: Boolean? = null,
    val cpu: Double = 0.0,
    val ramTotal: Double = 0.0,
    val ramAvailable: Double = 0.0,
    val disks: List<SampleDisk> = emptyList(),
    val volumes: List<SampleVolume> = emptyList(),
    val networks: List<SampleNetwork> = emptyList(),
)

@Serializable
data class Trend(
    val volume: String = "",
    val known: Boolean = false,
    val used: Double = 0.0,
    val total: Double = 0.0,
    val dailyBytes: Double = 0.0,
    val daysTo90: Double? = null,
    val daysToFull: Double? = null,
    val warning: Boolean = false,
    val hours: Double = 0.0,
)

// ---------------------------------------------------------------- Aktivität

@Serializable
data class ActivitySettings(val enabled: Boolean = false, val volume: String = "", val days: Int = 90, val maxMiB: Long = 8)

@Serializable
data class ActivityStatus(val settings: ActivitySettings = ActivitySettings(), val volume: String = "", val pending: Int = 0, val dropped: Int = 0, val error: String = "")

@Serializable
data class ActivityEntry(
    val at: String = "",
    val user: String = "",
    val via: String = "web",
    val ip: String? = null,
    val action: String = "",
    val target: String? = null,
    val status: Int? = null,
    val ok: Boolean = true,
)

@Serializable
data class ActivityPage(val entries: List<ActivityEntry> = emptyList(), val more: Boolean = false, val stored: Boolean = true, val status: ActivityStatus = ActivityStatus())

@Serializable
data class Account(val name: String = "")

// ---------------------------------------------------------------- Protokoll

@Serializable
data class LogEntry(val time: String, val priority: Int, val source: String, val message: String)

@Serializable
data class Logs(val entries: List<LogEntry>, val cursor: String)

// ---------------------------------------------------------------- Skripte

@Serializable
data class ScriptSchedule(val every: String = "custom", val minute: Int? = null, val time: String? = null, val weekday: Int? = null, val monthDay: Int? = null, val cron: String? = null)

@Serializable
data class ScriptRun(
    val trigger: String = "manual",
    val started: String = "",
    val durationMs: Long = 0,
    val exit: Int? = null,
    val outcome: String = "ok",
    val error: String? = null,
    val output: String? = null,
    val truncated: Boolean? = null,
)

@Serializable
data class ScriptView(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val trigger: String = "manual",
    val schedule: ScriptSchedule? = null,
    val changed: String = "",
    val running: Boolean = false,
    val last: ScriptRun? = null,
    val next: String? = null,
)

@Serializable
data class ScriptDetail(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val trigger: String = "manual",
    val schedule: ScriptSchedule? = null,
    val content: String = "",
)

@Serializable
data class ScriptsStatus(
    val volume: String = "",
    val chosen: Boolean = false,
    val available: Boolean = false,
    val disabled: Boolean = false,
    val error: String? = null,
    val timezone: String? = null,
    val volumes: List<String> = emptyList(),
    val scripts: List<ScriptView> = emptyList(),
)

@Serializable
data class ScriptOutput(val running: Boolean = false, val text: String = "", val next: Long = 0, val skipped: Boolean = false, val run: ScriptRun? = null)

@Serializable
data class ScriptRuns(val runs: List<ScriptRun> = emptyList())

@Serializable
data class ScriptBody(val name: String, val description: String, val trigger: String, val schedule: ScriptSchedule? = null, val content: String)

// ---------------------------------------------------------------- Task-Manager

@Serializable
data class ProcessOwner(val kind: String = "", val id: String? = null, val name: String? = null)

@Serializable
data class ProcessRow(
    val pid: Int = 0,
    val ppid: Int = 0,
    val name: String = "",
    val command: String = "",
    val user: String = "",
    val state: String = "",
    val cpuPercent: Double = 0.0,
    val memoryBytes: Long = 0,
    val start: Long = 0,
    val owner: ProcessOwner = ProcessOwner(),
    val protected: Boolean? = null,
)

@Serializable
data class ProcessList(val processes: List<ProcessRow> = emptyList(), val cpus: Int = 0, val memoryTotalBytes: Long = 0, val sampledSeconds: Double = 0.0)

@Serializable
data class ThreadUsage(val id: String = "", val usagePercent: Double? = null)

@Serializable
data class CpuInfo(val model: String = "", val threads: Int = 0, val usagePercent: Double = 0.0, val threadUsage: List<ThreadUsage>? = null)

@Serializable
data class MemoryInfo(val totalBytes: Long = 0, val availableBytes: Long = 0)

@Serializable
data class NetInfo(val `interface`: String = "", val state: String = "", val speedMbps: Long = 0, val rxBytesPerSecond: Double = 0.0, val txBytesPerSecond: Double = 0.0)

@Serializable
data class Accelerator(val address: String = "", val name: String = "", val usagePercent: Double? = null)

@Serializable
data class DiskIO(val name: String = "", val readBytesPerSecond: Double = 0.0, val writeBytesPerSecond: Double = 0.0, val busyPercent: Double = 0.0)

@Serializable
data class TaskSystem(
    val cpu: CpuInfo = CpuInfo(),
    val memory: MemoryInfo = MemoryInfo(),
    val network: NetInfo? = null,
    val networks: List<NetInfo>? = null,
    val accelerators: List<Accelerator>? = null,
    val disks: List<DiskIO>? = null,
)

@Serializable
data class DockerContainer(val id: String = "", val name: String = "", val image: String = "", val state: String = "", val cpuPercent: Double = 0.0, val memoryBytes: Long = 0)

@Serializable
data class DockerStatus(val running: Boolean = false, val containers: List<DockerContainer> = emptyList())

@Serializable
data class LxcContainer(val name: String = "", val displayName: String? = null, val state: String = "", val available: Boolean = true, val cpuPercent: Double = 0.0, val memoryBytes: Long = 0)

@Serializable
data class LxcList(val dev: Boolean = false, val containers: List<LxcContainer> = emptyList())

// ---------------------------------------------------------------- Hardware prüfen

@Serializable
data class PluginHint(val plugin: String = "", val title: String = "", val installed: Boolean = false)

@Serializable
data class UnboundDevice(
    val bus: String = "",
    val address: String = "",
    val id: String = "",
    val `class`: String = "",
    val name: String = "",
    val hint: String = "",
    val plugins: List<PluginHint>? = null,
    val kernel: String? = null,
    val newKernel: String? = null,
    val newKernelFixes: Boolean? = null,
    val driver: String? = null,
    val messages: List<String>? = null,
)

@Serializable
data class FirmwareMissing(val file: String = "", val device: String? = null)

@Serializable
data class Hint(val code: String = "", val setting: String? = null)

@Serializable
data class HwDisk(
    val name: String = "",
    val model: String = "",
    val sizeBytes: Long = 0,
    val transport: String = "other",
    val rotational: Boolean = false,
    val removable: Boolean = false,
    val smr: String? = null,
    val usb: String? = null,
    val usbDriver: String? = null,
    val hints: List<Hint> = emptyList(),
)

@Serializable
data class Virtualization(
    val vendor: String = "other",
    val cpuFlag: Boolean = false,
    val kvm: Boolean = false,
    val disabledByFirmware: Boolean = false,
    val iommu: Boolean = false,
    val iommuTable: Boolean = false,
)

@Serializable
data class HwCpu(val vendor: String = "", val model: String = "", val threads: Int = 0, val cores: Int = 0, val level: Int = 0, val features: List<String> = emptyList())

@Serializable
data class Finding(val level: String = "info", val code: String = "", val subject: String? = null)

@Serializable
data class HwMemory(val totalBytes: Long = 0, val ecc: Boolean = false, val corrected: Long = 0, val uncorrected: Long = 0)

@Serializable
data class HwController(val address: String = "", val id: String = "", val name: String = "", val kind: String = "", val driver: String = "", val hints: List<Hint> = emptyList())

@Serializable
data class HwNic(val name: String = "", val driver: String = "", val address: String = "", val bus: String = "", val speedMbs: Long = 0, val link: Boolean = false)

@Serializable
data class HwGraphics(val address: String = "", val id: String = "", val name: String = "", val driver: String = "")

@Serializable
data class HwSensor(val chip: String = "", val temps: Int = 0, val fans: Int = 0)

@Serializable
data class HardwareReport(
    val generated: String = "",
    val kernel: String = "",
    val board: String = "",
    val boot: String = "uefi",
    val cpu: HwCpu = HwCpu(),
    val virtualization: Virtualization = Virtualization(),
    val memory: HwMemory = HwMemory(),
    val controllers: List<HwController> = emptyList(),
    val disks: List<HwDisk> = emptyList(),
    val network: List<HwNic> = emptyList(),
    val graphics: List<HwGraphics> = emptyList(),
    val sensors: List<HwSensor> = emptyList(),
    val unbound: List<UnboundDevice> = emptyList(),
    val firmwareMissing: List<FirmwareMissing> = emptyList(),
    val summary: List<Finding> = emptyList(),
)

@Serializable
data class UasStatus(val devices: List<String> = emptyList(), val active: List<String> = emptyList(), val pending: List<String> = emptyList(), val writable: Boolean = false)

// ---------------------------------------------------------------- Plattentests

@Serializable
data class DiskTestNote(val kind: String = "", val detail: String = "")

@Serializable
data class DiskTest(
    val disk: String = "",
    val model: String = "",
    val serial: String = "",
    val sizeBytes: Long = 0,
    val mode: String = "read",
    val phase: String = "",
    val percent: Double = 0.0,
    val speedBytesPerSecond: Double = 0.0,
    val remainingSeconds: Long = 0,
    val errors: Int = 0,
    val started: String = "",
    val finished: String? = null,
    val result: String = "",
    val notes: List<DiskTestNote> = emptyList(),
    val autoResume: Boolean = false,
    val persistent: Boolean = false,
    val notPersistent: String? = null,
    val resumed: String? = null,
    val resumes: Int? = null,
)

@Serializable
data class DiskTestNotice(val kind: String = "", val serial: String? = null, val model: String? = null, val mode: String? = null, val started: String = "", val time: String = "")

@Serializable
data class TestList(val tests: List<DiskTest> = emptyList(), val notices: List<DiskTestNotice>? = null)
