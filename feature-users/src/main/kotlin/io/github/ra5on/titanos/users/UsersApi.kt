package io.github.ra5on.titanos.users

import io.github.ra5on.titanos.admin.AdminHttp
import io.github.ra5on.titanos.admin.seg
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Modelle, Regeln und Anfragen wie routes/users (+page.svelte, AccountDetail.svelte),
// lib/users-view.ts, lib/account-agent-data.ts und PasswordDialog.svelte.

@Serializable
data class Account(
    val name: String,
    val role: String = "user",
    val created: String = "",
    val smbReady: Boolean = true,
    val twoFactor: Boolean = false,
    val disabled: Boolean = false,
    val disabledAt: String? = null,
    val disabledBy: String? = null,
)

@Serializable
data class Share(val name: String, val volume: String = "", val access: Map<String, String> = emptyMap())

@Serializable
data class UserApp(val app: String, val icon: String = "", val allowed: Boolean = false)

@Serializable
data class UserApps(val admin: Boolean = false, val newApps: Boolean = false, val apps: List<UserApp> = emptyList())

@Serializable
data class MailRelease(val enabled: Boolean = false)

@Serializable
data class CameraRight(
    val id: String,
    val name: String = "",
    val live: Boolean = false,
    val recordings: Boolean = false,
    val names: Boolean? = null,
    val deleted: Boolean? = null,
)

@Serializable
data class UserCameras(val admin: Boolean = false, val cameras: List<CameraRight>? = null)

@Serializable
data class CameraBody(val live: Boolean, val recordings: Boolean, val names: Boolean)

@Serializable
data class CamerasBody(val cameras: Map<String, CameraBody>)

/** Antwort von /users/{name}/agent-data (sanitizeAgentData: Unbekanntes wird zu 0). */
@Serializable
data class AgentData(
    val available: Boolean? = null,
    val chats: Double = 0.0,
    val projects: Double = 0.0,
    val handedOver: Double = 0.0,
    val memories: Double = 0.0,
    val skills: Double = 0.0,
    val schedules: Double = 0.0,
    val notes: Double = 0.0,
    val folder: Boolean = false,
    val mailAccounts: Double = 0.0,
    val calendarEvents: Double? = null,
    val contacts: Double? = null,
    val calendarUnknown: Boolean = false,
)

@Serializable
data class CreateBody(val name: String, val password: String, val role: String)

@Serializable
data class RoleBody(val role: String, val resetTwoFactor: Boolean? = null)

@Serializable
data class PasswordBody(val password: String, val current: String? = null)

@Serializable
data class AppsBody(val newApps: Boolean? = null, val apps: Map<String, Boolean>? = null)

@Serializable
data class EnabledBody(val enabled: Boolean)

const val MIN_PASSWORD_LENGTH = 10
private val NAME = Regex("^[a-z][a-z0-9_-]{0,31}$")
val ROLES = listOf("owner", "admin", "user")

/** Kontoname wie das Web: klein, beginnt mit Buchstabe, bis 32 Zeichen. */
fun validAccountName(name: String): Boolean = NAME.matches(name)

/** Länge in Zeichen wie `[...password].length` (Unicode-Codepunkte). */
fun passwordLength(password: String): Int = password.codePointCount(0, password.length)

/** Besitzer verwalten nur Besitzer; Administratoren alle außer Besitzern. */
fun mayManage(myRole: String, role: String): Boolean = myRole == "owner" || (myRole == "admin" && role != "owner")

fun offeredRoles(myRole: String): List<String> = ROLES.filter { mayManage(myRole, it) }

/** Status-Filter der Liste (nur für diesen Besuch). */
fun filterAccounts(accounts: List<Account>, filter: String): List<Account> =
    if (filter == "all") accounts else accounts.filter { it.disabled == (filter == "locked") }

/**
 * Ob [me] [account] sperren oder entsperren darf: nie sich selbst, Besitzer
 * nur durch Besitzer, der letzte nicht gesperrte Besitzer bleibt entsperrt.
 */
fun mayLock(meName: String, meRole: String, account: Account, accounts: List<Account>): Boolean {
    if (meName.isEmpty() || account.name == meName) return false
    if (meRole != "owner" && (meRole != "admin" || account.role == "owner")) return false
    if (account.disabled || account.role != "owner") return true
    return accounts.count { it.role == "owner" && !it.disabled } > 1
}

/** Farbton des Anfangsbuchstabens (hue() im Web). */
fun hue(name: String): Int {
    var h = 0
    for (ch in name) h = (h * 31 + ch.code) % 360
    return h
}

/** Zeilen des Löschdialogs für die Daten des KI-Agenten: (Schlüssel, Zahl) – Zahl null ohne Platzhalter. */
data class AgentDataLines(val deleted: List<Pair<String, Int?>>, val kept: Pair<String, Int>?, val note: String?)

fun agentDataLines(raw: AgentData): AgentDataLines? {
    val available = raw.available ?: return null
    fun count(v: Double?) = if (v != null && v.isFinite() && v > 0) kotlin.math.floor(v).toInt() else 0
    val projects = count(raw.projects)
    val handedOver = minOf(count(raw.handedOver), projects)
    val mail = mutableListOf<Pair<String, Int?>>()
    if (count(raw.mailAccounts) > 0) mail += "users.agentData.mailAccounts" to count(raw.mailAccounts)
    if (raw.calendarUnknown) {
        mail += "users.agentData.part.calendar" to null
    } else {
        if (count(raw.calendarEvents) > 0) mail += "users.agentData.calendarEvents" to count(raw.calendarEvents)
        if (count(raw.contacts) > 0) mail += "users.agentData.contacts" to count(raw.contacts)
    }
    if (!available) {
        val parts = listOf("chats", "projects", "memories", "skills", "schedules", "notes", "folder").map { "users.agentData.part.$it" to null }
        return AgentDataLines(parts + mail, "users.agentData.part.shared" to 0, "users.agentData.unavailable")
    }
    val rows = listOf(
        "users.agentData.chats" to count(raw.chats),
        "users.agentData.projects" to projects - handedOver,
        "users.agentData.memories" to count(raw.memories),
        "users.agentData.skills" to count(raw.skills),
        "users.agentData.schedules" to count(raw.schedules),
        "users.agentData.notes" to count(raw.notes),
    )
    val deleted = rows.filter { it.second > 0 }.map { it.first to it.second as Int? }.toMutableList()
    if (raw.folder) deleted += "users.agentData.part.folder" to null
    deleted += mail
    return AgentDataLines(
        deleted,
        if (handedOver > 0) "users.agentData.handedOver" to handedOver else null,
        if (deleted.isNotEmpty() || handedOver > 0) null else "users.agentData.nothing",
    )
}

class UsersApi(private val http: AdminHttp) {
    suspend fun accounts(): List<Account> = http.get("users")
    suspend fun shares(): List<Share> = http.get("shares")

    /** Die Freigabe so, wie das NAS sie liefert (alle Felder), zum Ändern nur des Zugriffs. */
    suspend fun sharesRaw(): List<JsonObject> = http.get("shares")
    suspend fun create(name: String, password: String, role: String): JsonElement = http.post("users", CreateBody(name, password, role))
    suspend fun setRole(name: String, role: String): JsonElement = http.put("users/${seg(name)}", RoleBody(role))
    suspend fun resetTwoFactor(name: String, role: String): JsonElement = http.put("users/${seg(name)}", RoleBody(role, resetTwoFactor = true))
    suspend fun lock(name: String, disabled: Boolean): JsonElement = http.call("POST", "users/${seg(name)}/${if (disabled) "lock" else "unlock"}")
    suspend fun delete(name: String): JsonElement = http.delete("users/${seg(name)}")
    suspend fun agentData(name: String): AgentData = http.get("users/${seg(name)}/agent-data")
    suspend fun password(name: String, password: String, current: String?): JsonElement =
        http.post("users/${seg(name)}/password", PasswordBody(password, current))
    suspend fun apps(name: String): UserApps = http.get("users/${seg(name)}/apps")
    suspend fun saveApps(name: String, body: AppsBody): UserApps = http.send("PUT", "users/${seg(name)}/apps", body)
    suspend fun mail(name: String): MailRelease = http.get("users/${seg(name)}/mail")
    suspend fun saveMail(name: String, enabled: Boolean): MailRelease = http.send("PUT", "users/${seg(name)}/mail", EnabledBody(enabled))
    suspend fun cameras(name: String): UserCameras = http.get("users/${seg(name)}/cameras")
    suspend fun saveCamera(name: String, id: String, right: CameraBody): UserCameras =
        http.send("PUT", "users/${seg(name)}/cameras", CamerasBody(mapOf(id to right)))

    /**
     * Zugriff eines Kontos auf eine Freigabe ändern: die ganze Freigabe zurück
     * (ohne quotaStatus), nur `access` anders – wie setShare() im Web.
     */
    suspend fun setShareAccess(share: JsonObject, account: String, level: String): JsonElement {
        val access = (share["access"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (level.isEmpty()) access.remove(account) else access[account] = JsonPrimitive(level)
        val body = buildJsonObject {
            for ((k, v) in share) if (k != "quotaStatus" && k != "access") put(k, v)
            put("access", JsonObject(access))
        }
        val name = (share["name"] as JsonPrimitive).content
        return http.put("shares/${seg(name)}", body)
    }
}
