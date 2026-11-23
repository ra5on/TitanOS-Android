package io.github.ra5on.titanos.shares

import io.github.ra5on.titanos.admin.AdminHttp
import io.github.ra5on.titanos.admin.seg
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Modelle und Anfragen wie web/src/lib/api.ts (Share, Account) und
// routes/shares (ShareDialog, DeleteShareDialog, UnlockDialog).

@Serializable
data class Nfs(val enabled: Boolean = false, val clients: List<String>? = null, val mode: String? = null)

@Serializable
data class Recycle(val enabled: Boolean = false, val days: Int = 30)

@Serializable
data class TimeMachine(val enabled: Boolean = false, val sizeGB: Long = 0)

@Serializable
data class QuotaStatus(val state: String = "", val used: Long = 0, val limit: Long = 0, val error: String? = null)

@Serializable
data class GuardLock(val at: String = "", val user: String = "", val reason: String = "", val files: Int = 0)

@Serializable
data class Share(
    val name: String,
    val volume: String = "",
    val smb: Boolean = false,
    val nfs: Nfs = Nfs(),
    val access: Map<String, String> = emptyMap(),
    val guest: Boolean = false,
    val browseable: Boolean = true,
    val recycle: Recycle = Recycle(),
    val timeMachine: TimeMachine = TimeMachine(),
    val quota: Long? = null,
    val quotaID: Long? = null,
    val quotaStatus: QuotaStatus? = null,
    val previousVersions: Boolean? = null,
    val guard: Boolean? = null,
    val guardLocked: GuardLock? = null,
    val perUser: Boolean? = null,
    val path: String = "",
    val available: Boolean = true,
)

@Serializable
data class Account(val name: String, val role: String = "user", val disabled: Boolean = false)

/** Körper von POST /shares und PUT /shares/{name}, Felder wie ShareDialog im Web. */
@Serializable
data class ShareBody(
    val name: String,
    val volume: String,
    val smb: Boolean,
    val nfs: NfsBody,
    val access: Map<String, String>,
    val guest: Boolean,
    val browseable: Boolean,
    val recycle: Recycle,
    val timeMachine: TimeMachine,
    val previousVersions: Boolean,
    val guard: Boolean,
    val quota: Long,
)

@Serializable
data class NfsBody(val enabled: Boolean, val mode: String, val clients: List<String>)

@Serializable
data class ShareUsage(val kind: String = "", val name: String? = null, val path: String? = null)

/** GET /shares/{name}/delete-data: was das Löschen der Daten löschen würde. */
@Serializable
data class DataPreview(
    val share: String = "",
    val volume: String = "",
    val path: String = "",
    val exists: Boolean = false,
    val files: Long = 0,
    val dirs: Long = 0,
    val bytes: Long = 0,
    val complete: Boolean = true,
    val limit: String? = null,
    val subvolume: Boolean = false,
    val blockers: List<ShareUsage> = emptyList(),
    val warnings: List<ShareUsage> = emptyList(),
)

/** Ein Löschauftrag, wie /operations ihn unter shareDeletions nennt. */
@Serializable
data class ShareDeletion(
    val id: String = "",
    val share: String = "",
    val volume: String = "",
    val path: String = "",
    val phase: String = "",
    val started: String = "",
    val finished: String? = null,
    val files: Long = 0,
    val bytes: Long = 0,
    val totalFiles: Long = 0,
    val totalBytes: Long = 0,
    val shareRemoved: Boolean = false,
    val dataKept: Boolean = false,
    val error: String? = null,
)

@Serializable
data class Operations(val shareDeletions: List<ShareDeletion> = emptyList())

@Serializable
data class PurgeBody(val path: String)

/** Ansicht der Liste (sanitizeShareView), gespeichert je Konto unter /preferences/shares. */
@Serializable
data class ShareView(
    val volume: String = "",
    val protocol: String = "all",
    val sort: String = "name",
    val descending: Boolean = false,
    val compact: Boolean = true,
) {
    fun clean(): ShareView = copy(
        volume = volume.takeIf { it.length <= 128 && it.none { c -> c.code < 32 || c.code == 127 } } ?: "",
        protocol = if (protocol == "smb" || protocol == "nfs") protocol else "all",
        sort = if (sort == "volume") "volume" else "name",
    )
}

@Serializable
data class Preferences(val shares: ShareView? = null)

@Serializable
data class StorageVolume(val name: String, val filesystem: String = "", val shareQuotas: String? = null, val mounted: Boolean = true)

@Serializable
data class StorageLite(val volumes: List<StorageVolume> = emptyList())

@Serializable
data class NetworkLite(val ipv4: String = "")

@Serializable
data class SystemLite(val hostname: String = "", val network: NetworkLite? = null)

class SharesApi(private val http: AdminHttp) {
    suspend fun shares(): List<Share> = http.get("shares")
    suspend fun accounts(): List<Account> = http.get("users")
    suspend fun storage(): StorageLite = http.get("storage")
    suspend fun system(): SystemLite = http.get("system")
    suspend fun preferences(): Preferences = http.get("preferences")
    suspend fun saveView(view: ShareView): JsonElement = http.put("preferences/shares", view.clean())
    suspend fun create(body: ShareBody): JsonElement = http.post("shares", body)
    suspend fun update(name: String, body: ShareBody): JsonElement = http.put("shares/${seg(name)}", body)

    /** Nur die Freigabe; Ordner und Daten bleiben. */
    suspend fun delete(name: String): JsonElement = http.delete("shares/${seg(name)}")
    suspend fun preview(name: String): DataPreview = http.get("shares/${seg(name)}/delete-data")

    /** Freigabe samt Daten: der Pfad aus der Vorschau ist der Bestätigungswert, die App schickt ihn selbst. */
    suspend fun purge(name: String, path: String): ShareDeletion = http.send("POST", "shares/${seg(name)}/delete-data", PurgeBody(path))
    suspend fun unlock(name: String): JsonElement = http.post("shares/${seg(name)}/unlock", emptyMap<String, String>())
    suspend fun operations(): Operations = http.get("operations")
}
