package io.github.ra5on.titanos.vms

import kotlinx.serialization.Serializable

// Datenformen der API wie web/src/lib/api.ts (VMDisk … Hardware), lib/lxc.ts,
// routes/vms/snapshots.ts, lib/media.ts und lib/vm-schedule.ts. Jedes Feld
// hat einen Standardwert, damit ältere oder neuere titand nicht stören.

@Serializable
data class VmDisk(
    val file: String = "",
    val format: String = "qcow2",
    val sizeBytes: Long = 0,
    val volume: String = "",
    val path: String? = null,
    val source: String? = null,
)

@Serializable
data class VmFolder(val path: String = "", val tag: String = "", val readOnly: Boolean = false)

@Serializable
data class VmJob(val operation: String? = null, val state: String = "", val text: String = "")

@Serializable
data class PowerRule(val action: String = "start", val days: List<Int> = emptyList(), val time: String = "07:00")

@Serializable
data class PowerSchedule(
    val enabled: Boolean = false,
    val rules: List<PowerRule> = emptyList(),
    val stopTimeout: Int? = null,
    val forceOff: Boolean? = null,
)

@Serializable
data class ScheduleView(
    val nextStart: String? = null,
    val nextStop: String? = null,
    val waiting: String? = null,
    val waitReason: String? = null,
)

@Serializable
data class Vm(
    val directory: String? = null,
    val name: String = "",
    val displayName: String? = null,
    val description: String = "",
    val template: String = "linux",
    val memoryMiB: Long = 0,
    val vcpus: Int = 1,
    val volume: String = "",
    val disks: List<VmDisk> = emptyList(),
    val iso: String = "",
    val virtioISO: String? = null,
    val autostart: Boolean = false,
    val cpuPin: List<Int>? = null,
    val exclusive: Boolean = false,
    val folders: List<VmFolder>? = null,
    val tpm: Boolean = false,
    val secureBoot: Boolean = false,
    val firmware: String? = null,
    val pci: List<String>? = null,
    val autoGPU: Boolean? = null,
    val usb: List<String>? = null,
    val network: String = "user",
    val mac: String = "",
    val job: VmJob? = null,
    val state: String = "stopped",
    val available: Boolean = true,
    val cpuPercent: Double = 0.0,
    val ioError: Boolean? = null,
    val backupHeld: Boolean? = null,
    val memoryBytes: Long = 0,
    val since: String = "",
    val powerSchedule: PowerSchedule? = null,
    val schedule: ScheduleView? = null,
) {
    val label: String get() = displayName?.takeIf { it.isNotEmpty() } ?: name
    val running: Boolean get() = state == "running"
}

@Serializable
data class NasNetwork(
    val id: String = "",
    val name: String = "",
    val purpose: String = "",
    val parent: String = "",
    val vlan: Int = 0,
    val bridge: Boolean = false,
)

@Serializable
data class VmHostInfo(
    val cpus: Int = 1,
    val memoryBytes: Long = 0,
    val usedMemoryBytes: Long = 0,
    val availableMemoryBytes: Long? = null,
    val cpuModel: String = "",
)

@Serializable
data class VmStatus(
    val networks: List<NasNetwork>? = null,
    val kvm: Boolean = true,
    val bridge: Boolean = false,
    val host: VmHostInfo = VmHostInfo(),
    val vms: List<Vm> = emptyList(),
)

@Serializable
data class AdoptCandidate(
    val volume: String = "",
    val directory: String = "",
    val name: String = "",
    val displayName: String? = null,
    val description: String? = null,
    val template: String = "",
    val firmware: String? = null,
    val memoryMiB: Long = 0,
    val vcpus: Int = 0,
    val disks: List<String> = emptyList(),
    val created: String = "",
    val removed: Boolean = false,
    val removedAt: String? = null,
    val nameTaken: Boolean = false,
    val dropped: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
) {
    val key: String get() = "$volume/$directory"
    val label: String get() = displayName?.takeIf { it.isNotEmpty() } ?: name
}

@Serializable
data class AdoptList(val candidates: List<AdoptCandidate> = emptyList())

@Serializable
data class AdoptRequest(val volume: String, val directory: String, val name: String)

@Serializable
data class AdoptResult(val name: String = "", val changes: List<String> = emptyList())

@Serializable
data class HostCpu(val id: Int = 0, val kind: String = "", val siblings: List<Int> = emptyList(), val usedBy: List<String> = emptyList())

@Serializable
data class DiskImage(
    val path: String = "",
    val format: String = "",
    val virtualBytes: Long = 0,
    val actualBytes: Long = 0,
    val usedBy: String = "",
)

@Serializable
data class WholeDisk(val path: String = "", val device: String = "", val usedBy: String = "")

@Serializable
data class Virtualization(
    val vendor: String = "other",
    val cpuFlag: Boolean = true,
    val kvm: Boolean = true,
    val disabledByFirmware: Boolean = false,
    val iommu: Boolean = false,
    val iommuTable: Boolean = false,
)

@Serializable
data class VmHost(
    val cpus: List<HostCpu> = emptyList(),
    val images: List<DiskImage> = emptyList(),
    val disks: List<WholeDisk> = emptyList(),
    val virt: Virtualization? = null,
)

@Serializable
data class PciDevice(
    val address: String = "",
    val vendor: String = "",
    val device: String = "",
    val vendorName: String = "",
    val kind: String = "gpu",
    val driver: String = "",
    val iommuGroup: Int = 0,
    val primary: Boolean = false,
    val companions: List<String> = emptyList(),
    val passable: Boolean = false,
    val reason: String = "",
)

@Serializable
data class UsbDevice(
    val id: String = "",
    val name: String = "",
    val port: String = "",
    val stick: Boolean = false,
    val duplicate: Boolean = false,
)

@Serializable
data class HardwareList(val iommu: Boolean = false, val pci: List<PciDevice> = emptyList(), val usb: List<UsbDevice> = emptyList())

@Serializable
data class Hardware(val hardware: HardwareList = HardwareList(), val usedBy: Map<String, List<String>> = emptyMap())

@Serializable
data class Volume(
    val name: String = "",
    val mountPoint: String = "",
    val mounted: Boolean = false,
    val state: String = "",
    val sizeBytes: Long = 0,
    val usedBytes: Long = 0,
) {
    val free: Long get() = sizeBytes - usedBytes
}

@Serializable
data class StorageStatus(val volumes: List<Volume> = emptyList())

@Serializable
data class Share(val name: String = "", val volume: String = "", val path: String = "", val available: Boolean = true)

@Serializable
data class Addresses(val addresses: List<String> = emptyList(), val source: String = "")

@Serializable
data class XmlText(val xml: String = "")

@Serializable
data class TerminalInfo(val enabled: Boolean = false)

@Serializable
data class SnapshotSchedule(
    val enabled: Boolean = true,
    val time: String = "03:00",
    val days: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6),
    val keep: Int = 7,
    val memory: Boolean = true,
    val allowPause: Boolean = false,
)

@Serializable
data class Snapshot(
    val id: String = "",
    val name: String = "",
    val created: String = "",
    val memory: Boolean = false,
    val automatic: Boolean = false,
    val bytes: Long = 0,
    val incomplete: Boolean? = null,
)

@Serializable
data class SnapshotJob(val state: String = "", val text: String = "")

@Serializable
data class SnapshotStatus(
    val snapshots: List<Snapshot> = emptyList(),
    val schedule: SnapshotSchedule = SnapshotSchedule(enabled = false),
    val pending: Boolean = false,
    val reason: String = "",
    val job: SnapshotJob? = null,
)

@Serializable
data class MediaEntry(val path: String = "", val sizeBytes: Long = 0)

@Serializable
data class MediaList(val media: List<MediaEntry>? = null)

@Serializable
data class MediaJob(
    val id: String = "",
    val volume: String = "",
    val name: String = "",
    val url: String? = null,
    val uploadName: String? = null,
    val uploadSize: Long? = null,
    val phase: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val written: Long = 0,
    val outputBytes: Long = 0,
    val output: String? = null,
    val error: String? = null,
    val sha256: String? = null,
    val sourceReady: Boolean? = null,
) {
    /** mediaActive im Web. */
    val active: Boolean get() = phase in setOf("downloading", "extracting", "verifying", "publishing", "pausing")
}

@Serializable
data class MediaJobs(val jobs: List<MediaJob>? = null, val chunkBytes: Long = 0)

@Serializable
data class MediaImportRequest(
    val volume: String,
    val name: String,
    val sha256: String,
    val compression: String,
    val url: String? = null,
    val uploadName: String? = null,
    val uploadSize: Long? = null,
)

@Serializable
data class ChunkProof(val offset: Long, val size: Long, val sha256: String)

@Serializable
data class ChunkProofs(val chunks: List<ChunkProof>)

@Serializable
data class FileRoot(val id: String = "", val name: String = "", val kind: String = "")

@Serializable
data class FileEntry(val name: String = "", val dir: Boolean = false, val size: Long = 0, val modified: String = "")

@Serializable
data class BridgeInterface(val name: String = "", val carrier: Boolean = false)

@Serializable
data class BridgeConfig(val bridge: Boolean = false)

@Serializable
data class BridgePending(val bridge: Boolean = false)

@Serializable
data class BridgeStatus(
    val config: BridgeConfig = BridgeConfig(),
    val configured: Boolean = false,
    val bridgeReady: Boolean = false,
    val pending: BridgePending? = null,
    val pendingSeconds: Int = 0,
    val interfaces: List<BridgeInterface> = emptyList(),
)

@Serializable
data class BridgeAnswer(val network: BridgeStatus = BridgeStatus())

@Serializable
data class BridgeRequest(val port: String, val onlyUnconfigured: Boolean)

// --- Anfragen der VMs ---------------------------------------------------------

/** Ein Datenträger beim Anlegen: neu/Kopie ({format,sizeBytes,volume,source}) oder vorhanden ({path}). */
@Serializable
data class NewDisk(
    val path: String? = null,
    val format: String? = null,
    val sizeBytes: Long? = null,
    val volume: String? = null,
    val source: String? = null,
)

@Serializable
data class VmCreate(
    val name: String,
    val directory: String? = null,
    val volume: String? = null,
    val displayName: String,
    val description: String,
    val template: String,
    val firmware: String,
    val autostart: Boolean,
    val powerSchedule: PowerSchedule,
    val tpm: Boolean,
    val secureBoot: Boolean,
    val vcpus: Int,
    val memoryMiB: Long,
    val cpuPin: List<Int>,
    val exclusive: Boolean,
    val iso: String,
    val virtioISO: String,
    val folders: List<VmFolder>,
    val pci: List<String>,
    val autoGPU: Boolean,
    val usb: List<String>,
    val network: String,
    val disks: List<NewDisk>,
    val start: Boolean,
)

@Serializable
data class StartError(val code: String = "", val message: String = "")

@Serializable
data class VmCreated(val started: Boolean? = null, val startError: StartError? = null)

@Serializable
data class VmUpdate(
    val displayName: String,
    val description: String,
    val autostart: Boolean,
    val powerSchedule: PowerSchedule,
    val vcpus: Int,
    val memoryMiB: Long,
    val cpuPin: List<Int>,
    val exclusive: Boolean,
    val iso: String,
    val virtioISO: String,
    val folders: List<VmFolder>,
    val pci: List<String>,
    val autoGPU: Boolean,
    val usb: List<String>,
    val network: String,
)

@Serializable
data class GrowRequest(val disk: Int, val sizeBytes: Long)

@Serializable
data class MediumRequest(val path: String)

@Serializable
data class DeleteVm(val deleteDisks: Boolean, val confirm: String)

@Serializable
data class CloneRequest(val name: String, val volume: String)

@Serializable
data class SnapshotCreate(val name: String, val memory: Boolean, val allowPause: Boolean)

@Serializable
data class SnapshotRestore(val confirm: String, val memory: Boolean)

@Serializable
data class SnapshotDelete(val confirm: String)

@Serializable
data class SnapshotRecover(val confirm: String, val discardMemory: Boolean)

@Serializable
data class VirtioRequest(val volume: String)

// --- LXC ----------------------------------------------------------------------

@Serializable
data class LxcMount(val path: String = "", val target: String = "", val readOnly: Boolean = false)

@Serializable
data class LxcJob(
    val state: String = "",
    val phase: String = "prepare",
    val text: String? = null,
    val code: String? = null,
    val started: String = "",
)

@Serializable
data class LxcContainer(
    val name: String = "",
    val displayName: String? = null,
    val description: String? = null,
    val volume: String = "",
    val distribution: String = "",
    val release: String = "",
    val arch: String = "",
    val cpus: Int? = null,
    val memoryMiB: Long? = null,
    val network: String = "none",
    val mac: String = "",
    val mounts: List<LxcMount>? = null,
    val autostart: Boolean = false,
    val privileged: Boolean? = null,
    val btrfs: Boolean? = null,
    val created: String = "",
    val state: String = "stopped",
    val available: Boolean = true,
    val cpuPercent: Double = 0.0,
    val memoryBytes: Long = 0,
    val job: LxcJob? = null,
) {
    val label: String get() = displayName?.takeIf { it.isNotEmpty() } ?: name
    val running: Boolean get() = state == "running"
}

@Serializable
data class LxcNetwork(val id: String = "", val name: String = "", val bridge: Boolean = false, val vlan: Int? = null)

@Serializable
data class LxcHost(val cpus: Int = 1, val memoryBytes: Long = 0)

@Serializable
data class LxcList(
    val installed: Boolean = false,
    val dev: Boolean = false,
    val host: LxcHost = LxcHost(),
    val bridge: Boolean = false,
    val networks: List<LxcNetwork>? = null,
    val containers: List<LxcContainer> = emptyList(),
)

@Serializable
data class LxcImage(val distribution: String = "", val release: String = "", val arch: String = "", val build: String = "")

@Serializable
data class LxcImages(val images: List<LxcImage>? = null, val error: String = "")

@Serializable
data class LxcSnapshot(val id: String = "", val created: String = "")

@Serializable
data class LxcSnapshots(val snapshots: List<LxcSnapshot>? = null)

@Serializable
data class LxcConfig(val config: String = "")

@Serializable
data class LxcChange(
    val displayName: String,
    val description: String,
    val cpus: Int,
    val memoryMiB: Long,
    val network: String,
    val mac: String,
    val mounts: List<LxcMount>,
    val autostart: Boolean,
)

@Serializable
data class LxcCreate(
    val displayName: String,
    val description: String,
    val cpus: Int,
    val memoryMiB: Long,
    val network: String,
    val mac: String,
    val mounts: List<LxcMount>,
    val autostart: Boolean,
    val name: String,
    val volume: String,
    val distribution: String,
    val release: String,
    val privileged: Boolean,
)

@Serializable
data class LxcDelete(val deleteData: Boolean, val confirm: String)
