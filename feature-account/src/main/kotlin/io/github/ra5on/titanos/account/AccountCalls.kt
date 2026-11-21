package io.github.ra5on.titanos.account

import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.throwIfError
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Jede Anfrage von „Mein Konto“, genau wie die Weboberfläche sie stellt
 * (routes/account/ und die eingebundenen Bausteine). Pfade ohne `/api/v1`.
 * Geheimnisse (Passwort, Code, neue Passwörter) gehen nur im Inhalt mit und
 * werden nie protokolliert.
 */
internal class AccountCalls(val api: SettingsApi) {

    private fun seg(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------- Sitzung und Einstellungen des Kontos ----------
    suspend fun session(): JsonObject = api.obj("session")
    suspend fun preferences(): JsonObject = api.obj("preferences")
    suspend fun services(): JsonObject = api.obj("services/state")

    // ---------- Bild (Picture.svelte) ----------
    suspend fun picture(): JsonObject = api.obj("account/picture")
    suspend fun pictureBuiltin(id: String): JsonObject = api.put("account/picture", jobj("builtin" to id)).o()

    /** Ein eigenes Foto als roher Inhalt (wie `api.put('/account/picture', blob)` im Web), nicht als Formular. */
    suspend fun picturePhoto(bytes: ByteArray, mime: String): JsonObject = rawPut(api, "account/picture", bytes, mime)
    suspend fun pictureReset() { api.delete("account/picture") }
    suspend fun pictureBytes(photo: String): ByteArray = api.bytes("account/picture/${seg(photo)}")

    /** Die mitgelieferten Bilder liegen neben der Oberfläche (`/backgrounds/<id>.webp`). */
    suspend fun background(id: String): ByteArray = api.bytes("../../backgrounds/${seg(id)}.webp")

    // ---------- Passwort und zweiter Faktor ----------
    suspend fun changePassword(name: String, current: String, password: String) {
        api.post("users/${seg(name)}/password", jobj("password" to password, "current" to current))
    }

    /** `{secret, uri}` für die Authenticator-App. */
    suspend fun twoFactorBegin(password: String): JsonObject = api.post("account/2fa/begin", jobj("password" to password)).o()
    suspend fun twoFactorEnable(code: String): List<String> = api.post("account/2fa/enable", jobj("code" to code)).o().strings("recoveryCodes")
    suspend fun twoFactorDisable(password: String, code: String) { api.post("account/2fa/disable", jobj("password" to password, "code" to code)) }
    suspend fun recoveryRenew(password: String, code: String): List<String> =
        api.post("account/2fa/recovery", jobj("password" to password, "code" to code)).o().strings("recoveryCodes")

    // ---------- Passkeys ----------
    suspend fun passkeys(): JsonObject = api.obj("account/passkeys")
    suspend fun removePasskey(id: String): JsonObject = api.delete("account/passkeys/${seg(id)}").o()

    // ---------- SSH-Schlüssel ----------
    suspend fun sshKeys(): JsonObject = api.obj("account/ssh-keys")
    suspend fun addSshKey(key: String, password: String, code: String): JsonObject =
        api.post("account/ssh-keys", jobj("key" to key, "password" to password, "code" to code.trim())).o()
    suspend fun removeSshKey(id: String): JsonObject = api.delete("account/ssh-keys/${seg(id)}").o()

    // ---------- WebDAV ----------
    suspend fun webdav(): JsonObject = api.obj("account/webdav")

    /** `{user, password}`: nur einmal zeigen. */
    suspend fun webdavCreate(password: String, code: String): JsonObject =
        api.post("account/webdav", jobj("password" to password, "code" to code.trim())).o()
    suspend fun webdavRemove() { api.delete("account/webdav") }

    // ---------- Gerätepasswörter für Kalender und Kontakte (pim/Devices.svelte) ----------
    suspend fun davDevices(): JsonObject = api.obj("account/devices")

    /** `{id, name, user, password}`: nur einmal zeigen. */
    suspend fun davCreate(name: String, password: String, code: String): JsonObject =
        api.post("account/devices", jobj("name" to name.trim(), "password" to password, "code" to code.trim())).o()
    suspend fun davRevoke(id: String) { api.delete("account/devices/${seg(id)}") }

    // ---------- Angemeldete Geräte ----------
    suspend fun sessions(): JsonObject = api.obj("account/sessions")
    suspend fun endSession(id: String): JsonObject = api.delete("account/sessions/${seg(id)}").o()
    suspend fun endOthers(): JsonObject = api.delete("account/sessions").o()

    // ---------- Android-Apps (AndroidApps.svelte) ----------
    suspend fun apps(): JsonObject = api.obj("account/apps")
    suspend fun pairStart(): JsonObject = api.post("account/apps/pair", JsonObject(emptyMap())).o()
    suspend fun pairStatus(id: String): JsonObject = api.obj("account/apps/pair/${seg(id)}")
    suspend fun pairConfirm(id: String, password: String, code: String): JsonObject =
        api.post("account/apps/pair/${seg(id)}/confirm", jobj("password" to password, "code" to code.trim())).o()
    suspend fun pairReject(id: String) { api.post("account/apps/pair/${seg(id)}/reject", JsonObject(emptyMap())) }
    suspend fun pairCancel(id: String) { api.delete("account/apps/pair/${seg(id)}") }
    suspend fun endApp(id: String) { api.delete("account/apps/${seg(id)}") }

    // ---------- Mitteilungen auf Geräten (PushDevices.svelte, persönlich) ----------
    suspend fun push(): JsonObject = api.obj("notifications/push")

    /** Wie `setKinds` im Web: alle Arten neu, `mail` nur, wo T-Mail freigegeben ist. */
    suspend fun pushKinds(id: String, off: Set<String>, mail: Boolean): JsonObject {
        val body = buildMap<String, Any?> {
            put("nas", "nas" !in off)
            put("agent", "agent" !in off)
            put("camera", "camera" !in off)
            if (mail) put("mail", "mail" !in off)
        }
        return api.put("notifications/push/${seg(id)}", jsonOfMap(body)).o()
    }
    suspend fun pushTest(id: String) { api.post("notifications/push/${seg(id)}/test", JsonObject(emptyMap())) }
    suspend fun pushRemove(id: String) { api.delete("notifications/push/${seg(id)}") }

    // ---------- Glocke und Töne ----------
    suspend fun saveSound(value: JsonObject) { api.put("preferences/sound", value) }
    suspend fun saveBell(value: JsonObject) { api.put("preferences/bell", value) }

    // ---------- Meine Benachrichtigungen (PersonalNotifications.svelte) ----------
    suspend fun notifications(): JsonObject = api.obj("account/notifications")
    suspend fun saveNotifications(body: JsonObject): JsonObject = api.put("account/notifications", body).o()
    suspend fun notifyTest(channel: String) { api.post("account/notifications/test", jobj("channel" to channel)) }
    suspend fun haServices(connection: String): JsonObject =
        api.obj("homeassistant/notify-services" + if (connection.isNotEmpty()) "?connection=${seg(connection)}" else "")
    suspend fun cameras(): List<JsonElement> = api.obj("nvr/cameras").a("cameras")

    // ---------- Notfallzugang (ProtectEmergency.svelte) ----------
    suspend fun protectMe(): JsonObject = api.obj("agent/protect/me")
    suspend fun emergency(): JsonObject = api.obj("agent/protect/emergency")
    suspend fun askEmergency(reason: String, code: String): JsonObject =
        api.post("agent/protect/emergency/request", jobj("reason" to reason, "code" to code)).o()
    suspend fun emergencyAction(id: String, action: String): JsonObject = api.post("agent/protect/emergency/${seg(id)}/$action").o()
    suspend fun emergencyCode(account: String): String =
        (api.post("agent/protect/emergency/code/${seg(account)}").o()["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

    private fun jsonOfMap(m: Map<String, Any?>): JsonObject = io.github.ra5on.titanos.settings.common.jsonOf(m) as JsonObject
}

/**
 * PUT mit rohem Inhalt (ein Bild) wie `fetch(…, {body: file})` im Web.
 */
internal suspend fun rawPut(api: SettingsApi, path: String, bytes: ByteArray, mime: String): JsonObject =
    api.putBytes(path, bytes, mime).let { if (it is JsonNull) JsonObject(emptyMap()) else it.o() }
