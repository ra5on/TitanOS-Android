package io.github.ra5on.titanos.nvr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

// Formen der NVR-API wie web/src/lib/nvr/types.ts (docs/NVR.md). Zeiten der
// Phase 2 (Ereignisse, Aufnahmen, Exporte) sind Unix-Sekunden, der Zustand
// der Kamera und das Ausweichen tragen ISO-Zeiten. Listen, die ein NAS von
// 0.79 als null schickt, werden hier zu leeren Listen (tidyCamera im Web).

@Serializable
data class NvrDevice(
    val kind: String = "",
    val title: String = "",
    val present: Boolean = false,
    val available: Boolean = false,
    val reason: String? = null,
)

@Serializable
data class NvrDecoder(
    val id: String = "",
    val title: String = "",
    val kind: String = "",
    val available: Boolean = false,
    val reason: String? = null,
)

@Serializable
data class NvrDecoderStatus(
    val setting: String = "auto",
    val active: String = "cpu",
    val kind: String = "",
    val title: String = "",
    val reason: String? = null,
)

@Serializable
data class NvrHardware(
    val devices: List<NvrDevice>? = null,
    val auto: String = "cpu",
    val decoders: List<NvrDecoder>? = null,
    val videoDecode: Boolean = false,
    val accelDevice: Boolean = false,
    val avx2: Boolean = false,
    val memoryBytes: Long = 0,
    val enrichmentsPossible: Boolean = true,
    val enrichmentsReason: String? = null,
) {
    val deviceList: List<NvrDevice> get() = devices.orEmpty()
    val decoderList: List<NvrDecoder> get() = decoders.orEmpty()
}

@Serializable
data class NvrFailover(val from: String = "", val to: String = "", val at: String = "", val reason: String = "", val detail: String = "")

@Serializable
data class NvrLastError(val code: String = "", val detail: String = "", val at: String = "")

@Serializable
data class NvrDetector(
    val kind: String = "auto",
    val active: String = "",
    val failover: NvrFailover? = null,
    val inferenceMs: Double? = null,
)

@Serializable
data class NvrStorage(
    val usedBytes: Long? = null,
    val limitGB: Long = 0,
    val recordingsBytes: Long? = null,
    val exportsBytes: Long? = null,
    val spanDays: Double? = null,
    val cap: String? = null,
    val lastCleanup: Double? = null,
)

@Serializable
data class CameraCounts(val total: Int = 0, val enabled: Int = 0, val online: Int = 0)

@Serializable
data class NvrImage(
    val state: String = "",
    val version: String = "",
    val bytes: Long = 0,
    val size: Long = 0,
    val progress: Double = 0.0,
    val error: String? = null,
)

/** GET /nvr/status; Konten ohne Admin bekommen nur die ersten vier Felder. */
@Serializable
data class NvrStatus(
    val enabled: Boolean = false,
    val state: String = "off",
    val cameras: CameraCounts = CameraCounts(),
    val privacyNoticeDismissed: Boolean = true,
    val error: NvrLastError? = null,
    val image: NvrImage? = null,
    val volume: String? = null,
    val volumeMounted: Boolean? = null,
    val detector: NvrDetector? = null,
    val storage: NvrStorage? = null,
    val rtspOut: Boolean? = null,
    val frigateVersion: String? = null,
    val hardware: NvrHardware? = null,
    val decoder: NvrDecoderStatus? = null,
)

@Serializable
data class PlateEntry(val name: String = "", val plates: List<String>? = null) {
    val list: List<String> get() = plates.orEmpty()
}

@Serializable
data class DetectorChoice(val kind: String = "auto")

@Serializable
data class StorageLimit(val limitGB: Long = 0)

@Serializable
data class NvrSettings(
    val enabled: Boolean = false,
    val volume: String = "",
    val detector: DetectorChoice = DetectorChoice(),
    val storage: StorageLimit = StorageLimit(),
    val privacyNoticeDismissed: Boolean = false,
    val rtspOut: Boolean = false,
    val rtspUser: String = "",
    val rtspPassword: String = "",
    val plates: List<PlateEntry>? = null,
    val notifyOff: Boolean? = null,
    val decoder: String? = null,
)

@Serializable
data class CameraState(
    val online: Boolean = false,
    val known: Boolean = false,
    val fps: Double = 0.0,
    val detectionFps: Double = 0.0,
    val offlineSince: String? = null,
)

@Serializable
data class CameraSource(
    val kind: String = "rtsp",
    val host: String = "",
    val port: Int = 0,
    val onvifPort: Int = 0,
    val user: String = "",
    val hasPassword: Boolean = false,
    val mainURL: String = "",
    val subURL: String = "",
    val allowPublic: Boolean = false,
    val mainWidth: Int = 0,
    val mainHeight: Int = 0,
    val subWidth: Int = 0,
    val subHeight: Int = 0,
)

@Serializable
data class CameraStreams(
    val record: String? = null,
    val detect: String? = null,
    val live: String? = null,
    val noPreload: Boolean = false,
)

@Serializable
data class CameraDetect(
    val enabled: Boolean = false,
    val objects: List<String>? = null,
    val plates: Boolean = false,
    val faces: Boolean = false,
) {
    val objectList: List<String> get() = objects.orEmpty()
}

@Serializable
data class CameraRecord(
    val mode: String = "events",
    val retainDays: Int = 14,
    val recordDays: Int? = null,
    val eventDays: Int? = null,
    val preSeconds: Int = 5,
    val postSeconds: Int = 5,
)

/** Eine benannte Fläche im Bild; Punkte [x, y] je 0 bis 1. */
@Serializable
data class CameraZone(val id: String? = null, val name: String = "", val points: List<List<Double>> = emptyList())

@Serializable
data class CameraMask(val points: List<List<Double>> = emptyList())

@Serializable
data class CameraMasks(val motion: List<CameraMask>? = null, val objects: List<CameraMask>? = null)

@Serializable
data class NotifyRecipient(val user: String = "", val bell: Boolean = false, val push: Boolean = false, val email: Boolean = false)

@Serializable
data class NotifyWindow(val on: Boolean = false, val days: List<Int>? = null, val from: String = "", val to: String = "")

@Serializable
data class HARule(val connection: String = "", val entity: String = "", val states: List<String>? = null)

@Serializable
data class HACondition(val mode: String = "all", val rules: List<HARule>? = null)

@Serializable
data class CameraNotify(
    val titan: Boolean = false,
    val push: Boolean = false,
    val email: Boolean = false,
    val homeAssistant: Boolean = false,
    val agent: Boolean = false,
    val kinds: List<String>? = null,
    /** null: eine Kamera von 0.78 (alte Wege an die Administratoren); leer ist niemand. */
    val recipients: List<NotifyRecipient>? = null,
    val pauseSeconds: Int? = null,
    val window: NotifyWindow? = null,
    val ha: HACondition? = null,
    val zones: List<String>? = null,
    val faceUnknown: Boolean = false,
)

@Serializable
data class CameraAudio(val enabled: Boolean = false, val twoWay: Boolean = false, val codec: String? = null, val subCodec: String? = null)

@Serializable
data class CameraRights(val live: Boolean = false, val recordings: Boolean = false, val names: Boolean? = null)

/** Eine Kamera, wie Administratoren sie bekommen; andere Konten nur den ersten Block. */
@Serializable
data class Camera(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = false,
    val order: Int = 0,
    val audio: CameraAudio = CameraAudio(),
    val ptz: Boolean = false,
    val state: CameraState = CameraState(),
    val rights: CameraRights? = null,
    val deleted: Boolean = false,
    val deletedAt: JsonElement? = null,
    val expiresAt: JsonElement? = null,
    val purging: Boolean = false,
    val source: CameraSource? = null,
    val streams: CameraStreams? = null,
    val detect: CameraDetect? = null,
    val record: CameraRecord? = null,
    val notify: CameraNotify? = null,
    val zones: List<CameraZone>? = null,
    val zonesOnly: Boolean = false,
    val masks: CameraMasks? = null,
)

@Serializable
data class CameraList(val cameras: List<Camera>? = null, val objects: List<String>? = null)

@Serializable
data class Discovered(
    val address: String = "",
    val onvifPort: Int = 0,
    val name: String = "",
    val hardware: String = "",
    val id: String = "",
    val known: Boolean = false,
)

@Serializable
data class DiscoverAnswer(val cameras: List<Discovered>? = null)

@Serializable
data class ProbeStream(
    val role: String = "main",
    val url: String = "",
    val profile: String? = null,
    val outcome: String = "",
    val codec: String = "",
    val audio: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class ProbeDevice(val manufacturer: String = "", val model: String = "", val firmware: String = "")

@Serializable
data class ProbeResult(
    val outcome: String = "",
    val device: ProbeDevice? = null,
    val mainURL: String = "",
    val subURL: String = "",
    val streams: List<ProbeStream>? = null,
    val ptz: Boolean = false,
    val onvifPort: Int = 0,
    val clockOffsetSeconds: Double = 0.0,
) {
    val streamList: List<ProbeStream> get() = streams.orEmpty()
}

@Serializable
data class DiagContainer(
    val state: String = "",
    val exitCode: Int = 0,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val recentEnds: Int = 0,
)

@Serializable
data class DiagCamera(
    val id: String = "",
    val name: String = "",
    val online: Boolean = false,
    val known: Boolean = false,
    val fps: Double = 0.0,
    val detectionFps: Double = 0.0,
    val offlineSince: String? = null,
    val processFps: Double = 0.0,
    val skippedFps: Double = 0.0,
)

@Serializable
data class DiagShm(val totalMB: Double? = null, val usedMB: Double? = null, val freeMB: Double? = null)

@Serializable
data class Diagnosis(
    val state: String = "off",
    val container: DiagContainer = DiagContainer(),
    val imageReference: String = "",
    val imageVersion: String = "",
    val imagePresent: Boolean = false,
    val runningVersion: String = "",
    val detector: NvrDetector = NvrDetector(),
    val detectorPid: Int? = null,
    val lastError: NvrLastError? = null,
    val log: List<String>? = null,
    val logSource: String = "",
    val accelDevice: Boolean = false,
    val npuFirmware: List<String>? = null,
    val gpuUsage: JsonObject? = null,
    val npuUsage: JsonObject? = null,
    val cameras: List<DiagCamera>? = null,
    val shm: DiagShm = DiagShm(),
    val hardware: NvrHardware = NvrHardware(),
    val decoder: NvrDecoderStatus? = null,
)

// ---- Phase 2 ----

@Serializable
data class NvrEvent(
    val id: String = "",
    val camera: String = "",
    val label: String = "",
    val subLabel: String = "",
    val plate: String = "",
    val score: Double = 0.0,
    val start: Double = 0.0,
    val end: Double? = null,
    val hasClip: Boolean = false,
    val hasSnapshot: Boolean = false,
    val zones: List<String>? = null,
    val reviewed: Boolean = false,
    val kept: Boolean = false,
    val keptExport: String? = null,
)

@Serializable
data class EventList(val events: List<NvrEvent>? = null, val next: Double? = null)

@Serializable
data class Segment(val start: Double = 0.0, val end: Double = 0.0, val motion: Double = 0.0, val objects: Double = 0.0)

@Serializable
data class RecordingList(val from: Double = 0.0, val to: Double = 0.0, val segments: List<Segment>? = null)

@Serializable
data class SummaryHour(
    val hour: Int = 0,
    val start: Double = 0.0,
    val seconds: Double = 0.0,
    val motion: Double = 0.0,
    val objects: Double = 0.0,
    val events: Int = 0,
)

@Serializable
data class SummaryDay(val day: String = "", val hours: List<SummaryHour>? = null)

@Serializable
data class RecordingSummary(val days: List<SummaryDay>? = null)

@Serializable
data class NvrExport(
    val id: String = "",
    val camera: String = "",
    val name: String = "",
    val from: Double = 0.0,
    val to: Double = 0.0,
    val created: Double = 0.0,
    val state: String = "running",
    val sizeBytes: Long? = null,
    val owner: String = "",
    val event: String? = null,
)

@Serializable
data class ExportList(val exports: List<NvrExport>? = null)

@Serializable
data class PtzInfo(val supported: Boolean = false, val features: List<String>? = null, val presets: List<String>? = null)

@Serializable
data class FaceAttempt(val file: String = "", val at: Double = 0.0, val guess: String = "", val score: Double = 0.0)

@Serializable
data class Face(val name: String = "", val images: List<String>? = null)

@Serializable
data class Faces(val enabled: Boolean = false, val faces: List<Face>? = null, val attempts: List<FaceAttempt>? = null)

@Serializable
data class RecognizedPlate(val plate: String = "", val count: Int = 0, val lastSeen: Double? = null, val camera: String = "")

@Serializable
data class PlatesAnswer(val plates: List<RecognizedPlate>? = null)

@Serializable
data class RecipientAccount(
    val name: String = "",
    val admin: Boolean = false,
    val email: Boolean = false,
    val pushDevices: Int = 0,
    val pushDevicesOff: Int = 0,
    val cameras: List<String>? = null,
    val names: List<String>? = null,
)

@Serializable
data class RecipientsAnswer(val accounts: List<RecipientAccount>? = null, val mailServer: Boolean = true)

@Serializable
data class HAEntity(val id: String = "", val name: String = "", val area: String? = null, val state: String = "", val domain: String = "")

@Serializable
data class HAEntities(val entities: List<HAEntity>? = null)

@Serializable
data class HAConnection(val id: String = "", val name: String = "")

@Serializable
data class HAConnections(val connections: List<HAConnection>? = null)

/** Ein Volume aus GET /storage (nur was die Einrichtung braucht). */
@Serializable
data class StorageVolume(val name: String = "", val mounted: Boolean = false, val sizeBytes: Long = 0, val usedBytes: Long = 0)

@Serializable
data class StorageStatus(val volumes: List<StorageVolume>? = null)

/** Eine Zeit der API als Unix-Sekunden: Zahlen sind Sekunden, Text ist ISO. */
fun unixOf(value: JsonElement?): Double? {
    val p = value as? JsonPrimitive ?: return null
    p.doubleOrNull?.let { if (!p.isString) return it }
    val text = p.contentOrNull ?: return null
    if (text.isBlank()) return null
    return runCatching { Instant.parse(text).toEpochMilli() / 1000.0 }.getOrNull()
}

/** ISO-Text als Unix-Sekunden. */
fun isoSeconds(iso: String?): Double? =
    if (iso.isNullOrBlank()) null else runCatching { Instant.parse(iso).toEpochMilli() / 1000.0 }.getOrNull()

/** Was das Konto darf: Live sehen / Aufnahmen sehen (Administratoren alles). */
fun Camera.mayLive(admin: Boolean) = admin || rights?.live == true
fun Camera.mayRecordings(admin: Boolean) = admin || rights?.recordings == true
