package io.github.ra5on.titanos.storage

import io.github.ra5on.titanos.admin.AdminHttp
import io.github.ra5on.titanos.admin.seg
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.OutputStream

// Die Anfragen des Speichers, mit denselben Pfaden und Körpern wie das Web.
// Felder, die das Web weglässt (`undefined`), sind hier null und fehlen im
// JSON (TitanJson: explicitNulls = false). Passwörter stehen nur im Körper.

@Serializable
internal data class Checks(val parity: Boolean, val smartShort: Boolean, val smartLong: Boolean, val snapshots: Boolean)

@Serializable
internal data class CreateBody(
    val name: String,
    val level: String,
    val filesystem: String,
    val disks: List<String>,
    val erase: List<String>,
    val identities: Map<String, String>,
    val encrypt: Boolean? = null,
    val checks: Checks,
    val password: String? = null,
)

@Serializable
internal data class AddDiskBody(val disk: String, val erase: Boolean, val identity: String? = null, val profile: String? = null, val password: String? = null)

@Serializable
internal data class ConfirmBody(val confirm: String)

@Serializable
internal data class ActionBody(val action: String)

@Serializable
internal data class EnabledBody(val enabled: Boolean)

@Serializable
internal data class ProfileBody(val profile: String)

@Serializable
internal data class SpeedBody(val speed: String)

@Serializable
internal data class CacheBody(val mode: String, val disk: String? = null, val erase: Boolean? = null, val identity: String? = null)

@Serializable
internal data class PasswordBody(val password: String)

@Serializable
internal data class PasswordsBody(val action: String, val current: String, val password: String? = null)

@Serializable
internal data class SnapshotBody(val action: String, val name: String, val confirm: String)

@Serializable
internal data class DiskSleepBody(val idleMinutes: Int, val volumes: Map<String, Int>? = null, val disks: Map<String, Int>? = null)

@Serializable
internal data class GrowthBody(val disk: String, val identity: String, val recoveryVolume: String, val erase: Boolean, val confirm: String)

@Serializable
internal data class FilesSelection(val paths: List<String>, val targetVolume: String)

@Serializable
internal data class FilesRestoreBody(val uuid: String, val files: Long, val bytes: Long, val paths: List<String>, val targetVolume: String)

@Serializable
internal data class FolderRestoreRequest(val path: String, val mode: String, val conflict: String? = null, val uuid: String? = null)

@Serializable
internal data class ImportPreviewBody(val id: String, val fingerprint: String, val password: String? = null)

@Serializable
internal data class ImportBody(val id: String, val fingerprint: String, val name: String, val confirm: String, val password: String? = null)

@Serializable
internal data class ReplaceBody(val old: String, val new: String, val erase: Boolean, val identity: String? = null)

@Serializable
internal data class IdentifyBody(val seconds: Int)

@Serializable
internal data class MemoryBody(val cachePercent: Int)

@Serializable
internal class Empty

internal class StorageApi(val http: AdminHttp) {
    private fun vol(name: String) = "storage/volumes/${seg(name)}"

    suspend fun status(): StorageStatus = http.get("storage")
    suspend fun rescan(): StorageStatus = http.call("POST", "storage/rescan")
    suspend fun maintenance(): MaintenanceStatus = http.get("maintenance")

    /** Die Hinweise des Kontos (Teil von GET preferences). */
    suspend fun hints(): JsonObject? = (http.json("preferences") as? JsonObject)?.get("hints") as? JsonObject

    suspend fun saveHints(value: JsonObject) {
        http.put("preferences/hints", value)
    }

    suspend fun check(volume: String, start: Boolean) {
        http.post("${vol(volume)}/check", ActionBody(if (start) "start" else "stop"))
    }

    suspend fun create(body: CreateBody) {
        http.post("storage/volumes", body)
    }

    /** Löschen: die API verlangt den Namen als Bestätigung; die App schickt ihn selbst. */
    suspend fun delete(volume: String) {
        http.send<ConfirmBody, JsonElement>("DELETE", vol(volume), ConfirmBody(volume))
    }

    suspend fun addDisk(volume: String, body: AddDiskBody) {
        http.post("${vol(volume)}/disks", body)
    }

    suspend fun removeDisk(volume: String, disk: String) {
        http.delete("${vol(volume)}/disks/${seg(disk)}")
    }

    suspend fun profile(volume: String, profile: String) {
        http.put("${vol(volume)}/profile", ProfileBody(profile))
    }

    suspend fun quotas(volume: String, enabled: Boolean) {
        http.put("${vol(volume)}/quotas", EnabledBody(enabled))
    }

    suspend fun syncSpeed(volume: String, speed: String) {
        http.put("${vol(volume)}/sync-speed", SpeedBody(speed))
    }

    suspend fun enableCache(volume: String, disk: String, mode: String, identity: String?) {
        http.put("${vol(volume)}/cache", CacheBody(mode, disk, true, identity))
    }

    suspend fun cacheMode(volume: String, mode: String) {
        http.put("${vol(volume)}/cache", CacheBody(mode))
    }

    suspend fun disableCache(volume: String) {
        http.delete("${vol(volume)}/cache")
    }

    suspend fun unlock(volume: String, password: String) {
        http.post("${vol(volume)}/unlock", PasswordBody(password))
    }

    suspend fun lock(volume: String) {
        http.post("${vol(volume)}/lock", Empty())
    }

    suspend fun passwords(volume: String, action: String, current: String, password: String?) {
        http.post("${vol(volume)}/passwords", PasswordsBody(action, current, password))
    }

    suspend fun luksHeader(volume: String, out: OutputStream): String? = http.download("${vol(volume)}/luks-header", out)

    suspend fun snapshots(volume: String): List<SnapshotName> = http.get("${vol(volume)}/snapshots")

    suspend fun snapshotAction(volume: String, action: String, name: String) {
        // Löschen: die API will den Namen des Snapshots als Bestätigung; die App schickt ihn selbst.
        http.post("${vol(volume)}/snapshots", SnapshotBody(action, name, if (action == "delete") name else ""))
    }

    suspend fun saveSleep(body: DiskSleepBody): DiskSleepStatus = http.send("PUT", "storage/disk-sleep", body)

    suspend fun hardwareDisks(): HwDisks = http.get("hardware/disks")

    suspend fun shares(): List<ShareRef> = http.get("shares")
    suspend fun vms(): VmList = http.get("vms")
    suspend fun docker(): DockerRef = http.get("docker")

    // Verwalten
    suspend fun usage(volume: String): UsageResult = http.get("${vol(volume)}/usage")
    suspend fun growth(): GrowthStatus = http.get("storage/growth")
    suspend fun growthPreview(volume: String, body: GrowthBody): Growth = http.send("POST", "${vol(volume)}/growth/preview", body)
    suspend fun grow(volume: String, body: GrowthBody) {
        http.post("${vol(volume)}/growth", body)
    }

    suspend fun cancelGrowth(volume: String) {
        http.post("storage/growth/cancel", ConfirmBody(volume))
    }

    suspend fun recoverGrowth(volume: String) {
        http.post("storage/growth/recover", ConfirmBody(volume))
    }

    suspend fun snapshotFiles(volume: String, snapshot: String, path: String, offset: Int): SnapshotFiles =
        http.get("${vol(volume)}/snapshots/${seg(snapshot)}/files", "path" to path, "offset" to offset.toString())

    suspend fun filesPreview(volume: String, snapshot: String, selection: FilesSelection): FileRestoreInfo =
        http.send("POST", "${vol(volume)}/snapshots/${seg(snapshot)}/files/preview", selection)

    suspend fun filesRestore(volume: String, snapshot: String, body: FilesRestoreBody): FileRestoreResult =
        http.send("POST", "${vol(volume)}/snapshots/${seg(snapshot)}/files/restore", body)

    suspend fun folderPreview(volume: String, snapshot: String, body: FolderRestoreRequest): FolderRestorePreview =
        http.send("POST", "${vol(volume)}/snapshots/${seg(snapshot)}/folders/preview", body)

    suspend fun folderRestore(volume: String, snapshot: String, body: FolderRestoreRequest): FolderRestoreJob =
        http.send("POST", "${vol(volume)}/snapshots/${seg(snapshot)}/folders/restore", body)

    suspend fun restoreJobs(): FolderRestoreJobs = http.get("storage/snapshot-restores")
    suspend fun cancelRestore(id: String) {
        http.post("storage/snapshot-restores/${seg(id)}/cancel", Empty())
    }

    suspend fun dismissRestore(id: String) {
        http.delete("storage/snapshot-restores/${seg(id)}")
    }

    // Importieren
    suspend fun importCandidates(): List<ImportCandidate> = http.get("storage/import")
    suspend fun importPreview(body: ImportPreviewBody): ImportPreview = http.send("POST", "storage/import/preview", body)

    /** Registrieren: die API will den neuen Namen wiederholt; die App schickt ihn selbst. */
    suspend fun importVolume(c: ImportCandidate, name: String, password: String?) {
        http.post("storage/import", ImportBody(c.id, c.fingerprint, name, name, password))
    }

    // Platte ersetzen
    suspend fun identify(disk: String) {
        http.post("storage/disks/${seg(disk)}/identify", IdentifyBody(30))
    }

    suspend fun replace(volume: String, old: String, new: String, identity: String?) {
        http.post("${vol(volume)}/replace", ReplaceBody(old, new, true, identity))
    }

    // SMART
    suspend fun smart(refresh: Boolean): SmartList = if (refresh) http.call("POST", "storage/smart/refresh") else http.get("storage/smart")
    suspend fun smartDetail(disk: String): SmartReport = http.get("storage/smart/${seg(disk)}")
    suspend fun smartHistory(disk: String): SmartHistory = http.get("storage/smart/${seg(disk)}/history")

    // Arbeitsspeicher
    suspend fun memory(): MemoryStatus = http.get("system/memory")
    suspend fun saveMemory(percent: Int): MemoryStatus = http.send("PUT", "system/memory", MemoryBody(percent))
}

/** Die Hinweise mit `checksDismissed` als Liste von Namen, andere Schlüssel bleiben (sanitizeHints). */
internal fun dismissedChecks(hints: JsonObject?): List<String> =
    (hints?.get("checksDismissed") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

internal fun hintsWithDismissed(hints: JsonObject?, more: List<String>): JsonObject {
    val all = (dismissedChecks(hints) + more).distinct()
    val map = LinkedHashMap<String, JsonElement>(hints?.jsonObject ?: emptyMap())
    map["checksDismissed"] = kotlinx.serialization.json.JsonArray(all.map { JsonPrimitive(it) })
    return JsonObject(map)
}
