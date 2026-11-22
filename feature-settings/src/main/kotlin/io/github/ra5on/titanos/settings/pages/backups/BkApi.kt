package io.github.ra5on.titanos.settings.pages.backups

import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import kotlinx.serialization.json.JsonObject

/**
 * Alle Anfragen der Sicherungsseiten an titand, wie die Web-Seiten sie
 * stellen (Pfade ohne `/api/v1`). Nie einen Wert protokollieren: Ziele und
 * Antworten tragen Passwörter und Schlüssel.
 */
internal object BkApi {
    /** Ein Wert in einer Abfrage oder einem Pfadteil (Leerzeichen als %20). */
    fun q(v: String): String = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    // --- Datensicherung ---
    suspend fun overview(api: SettingsApi): JsonObject = api.obj("backups")
    suspend fun createJob(api: SettingsApi, job: JsonObject): JsonObject = api.post("backups", job).o()
    suspend fun updateJob(api: SettingsApi, id: String, job: JsonObject): JsonObject = api.put("backups/${q(id)}", job).o()
    suspend fun deleteJob(api: SettingsApi, id: String): JsonObject = api.delete("backups/${q(id)}").o()

    /** `run` oder `stop`. */
    suspend fun act(api: SettingsApi, id: String, action: String): JsonObject = api.post("backups/${q(id)}/$action").o()
    suspend fun sshKey(api: SettingsApi): String = api.post("backups/ssh-key").o().str("publicKey")
    suspend fun password(api: SettingsApi, id: String): String = api.obj("backups/${q(id)}/password").str("password")
    suspend fun check(api: SettingsApi, id: String, readData: Int) { api.post("backups/${q(id)}/check", jobj("readData" to readData)) }

    /** Wiederherstellungsprobe: höchstens [mb] MB (dezimal wie `MB` im Web) und [files] Dateien. */
    suspend fun probe(api: SettingsApi, id: String, mb: Long, files: Long) {
        api.post("backups/${q(id)}/probe", jobj("maxBytes" to mb * 1_000_000L, "maxFiles" to files))
    }
    suspend fun snapshots(api: SettingsApi, id: String): List<JsonObject> = api.obj("backups/${q(id)}/snapshots").a("snapshots").objects()
    suspend fun files(api: SettingsApi, id: String, snapshot: String, path: String, offset: Int): JsonObject =
        api.obj("backups/${q(id)}/snapshots/${q(snapshot)}/files?path=${q(path)}&offset=$offset")

    /** Stellt einen Stand wieder her (alles, wenn [paths] null ist); Antwort: der neue Ordner. */
    suspend fun restore(api: SettingsApi, id: String, snapshot: String, volume: String, paths: List<String>?): String {
        val body = if (paths != null) jobj("snapshot" to snapshot, "volume" to volume, "paths" to paths) else jobj("snapshot" to snapshot, "volume" to volume)
        return api.post("backups/${q(id)}/restore", body).o().str("target")
    }
    suspend fun folders(api: SettingsApi, volume: String, dir: String): List<String> = api.obj("backups/folders?volume=${q(volume)}&dir=${q(dir)}").strings("folders")
    suspend fun vmDisks(api: SettingsApi, query: String): JsonObject = api.obj("backups/vm-disks?$query")
    suspend fun usb(api: SettingsApi): List<JsonObject> = api.list("usb").objects()

    // --- Erneut bestätigen (ConfirmDialog) ---
    suspend fun confirmSession(api: SettingsApi, password: String, code: String) { api.post("session/confirm", jobj("password" to password, "code" to code)) }
    suspend fun confirmed(api: SettingsApi): Boolean = api.obj("session/confirmed")["confirmed"]?.toString() == "true"
    suspend fun session(api: SettingsApi): JsonObject = api.obj("session")

    // --- SMB-Ziele ---
    suspend fun smbDiscover(api: SettingsApi, draft: JsonObject): List<String> = api.post("backups/smb/discover", draft).o().strings("shares")
    suspend fun smbSave(api: SettingsApi, draft: JsonObject) { api.put("backups/smb", draft) }
    suspend fun smbDelete(api: SettingsApi, id: String) { api.delete("backups/smb/${q(id)}") }

    // --- Backups anderer TitanOS annehmen ---
    suspend fun target(api: SettingsApi): JsonObject = api.obj("backups/target")
    suspend fun setTarget(api: SettingsApi, enabled: Boolean, volume: String): JsonObject = api.put("backups/target", jobj("enabled" to enabled, "volume" to volume)).o()
    suspend fun pairSource(api: SettingsApi, name: String): String = api.post("backups/target/sources", jobj("name" to name)).o().str("code")
    suspend fun removeSource(api: SettingsApi, name: String, deleteData: Boolean): JsonObject =
        api.delete("backups/target/sources/${q(name)}${if (deleteData) "?deleteData=true" else ""}").o()

    // --- Replikation ---
    suspend fun replication(api: SettingsApi): JsonObject = api.obj("replication")
    suspend fun saveReplication(api: SettingsApi, id: String, body: JsonObject) {
        if (id.isNotEmpty()) api.put("replication/${q(id)}", body) else api.post("replication", body)
    }
    suspend fun runReplication(api: SettingsApi, id: String) { api.post("replication/${q(id)}/run", jobj()) }
    suspend fun removeReplication(api: SettingsApi, id: String) { api.delete("replication/${q(id)}") }
    suspend fun restoreReplica(api: SettingsApi, source: String, snapshot: String): String =
        api.post("replication/received/restore", jobj("source" to source, "snapshot" to snapshot)).o().str("path")

    // --- Unterbrochene Vorgänge (RecoveryPanel) ---
    suspend fun recovery(api: SettingsApi, details: Boolean): JsonObject = api.obj("backups/application-recovery" + if (details) "?details=1" else "")
    suspend fun recover(api: SettingsApi, confirm: String, restart: Boolean) { api.post("backups/application-recovery", jobj("confirm" to confirm, "restart" to restart)) }
    suspend fun clones(api: SettingsApi, volume: String): List<JsonObject> = api.list("vm-clones/${q(volume)}").objects()
    suspend fun cloneAct(api: SettingsApi, volume: String, id: String, confirm: String, action: String) {
        api.post("vm-clones/${q(volume)}/${q(id)}", jobj("confirm" to confirm, "action" to action))
    }

    // --- Cluster (ClusterPair) ---
    suspend fun clusterOthers(api: SettingsApi): List<JsonObject> {
        val r = api.obj("cluster/nodes")
        if (r["manages"]?.toString() != "true") return emptyList()
        return r.a("nodes").objects().filter { it["self"]?.toString() != "true" }
    }
    suspend fun backupPair(api: SettingsApi, node: String): String = api.post("cluster/nodes/${q(node)}/backup-pair").o().str("code")

    // --- Systemkonfiguration ---
    suspend fun config(api: SettingsApi, asleep: Boolean = false): JsonObject = api.obj("config-backup" + if (asleep) "?search=asleep" else "")
    suspend fun saveConfig(api: SettingsApi, body: JsonObject): JsonObject = api.put("config-backup", body).o()
    suspend fun runConfig(api: SettingsApi): JsonObject = api.post("config-backup/run").o()
    suspend fun downloadConfig(api: SettingsApi, volume: String, name: String): ByteArray = api.bytes("config-backup/download?volume=${q(volume)}&name=${q(name)}")

    // --- Anwendungen und Abdeckung ---
    suspend fun previewApps(api: SettingsApi, job: String, snapshot: String, volume: String): JsonObject =
        api.post("backups/${q(job)}/applications/preview", jobj("snapshot" to snapshot, "volume" to volume)).o()
    suspend fun restoreApps(api: SettingsApi, job: String, snapshot: String, volume: String): String =
        api.post("backups/${q(job)}/applications/restore", jobj("snapshot" to snapshot, "volume" to volume)).o().str("target")
    suspend fun coverage(api: SettingsApi): JsonObject = api.obj("backups/coverage")

    // --- Weiteres ---
    suspend fun storage(api: SettingsApi): JsonObject = api.obj("storage")

    /** Container und VMs zur Auswahl (ApplicationSelection); Fehler je Quelle getrennt. */
    suspend fun applicationOptions(api: SettingsApi): Pair<List<Pair<String, String>>, Throwable?> {
        val out = mutableListOf<Pair<String, String>>()
        var error: Throwable? = null
        runCatching { api.obj("docker").a("containers").objects().forEach { out += "docker" to it.str("name") } }.onFailure { error = it }
        runCatching { api.obj("vms").a("vms").objects().forEach { out += "vm" to it.str("name") } }.onFailure { error = it }
        return out to error
    }
}
