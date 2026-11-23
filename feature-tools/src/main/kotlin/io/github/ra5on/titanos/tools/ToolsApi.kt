package io.github.ra5on.titanos.tools

import io.github.ra5on.titanos.admin.AdminHttp
import io.github.ra5on.titanos.admin.seg
import io.github.ra5on.titanos.net.ApiException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.OutputStream

/** Eine Abfrage `a=1&b=2` in fester Reihenfolge (wie URLSearchParams im Web). */
internal fun query(vararg pairs: Pair<String, String?>): String =
    pairs.filter { it.second != null }.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")}" }

@Serializable
data class SignalBody(val signal: String, val start: Long)

@Serializable
data class VolumeBody(val volume: String)

@Serializable
data class ConfirmBody(val confirm: String)

@Serializable
data class RecoveryBody(val confirm: String, val restart: Boolean)

@Serializable
data class CloneBody(val confirm: String, val action: String)

@Serializable
data class DiskTestStart(val disk: String, val mode: String, val confirm: String, val autoResume: Boolean)

@Serializable
data class UasBody(val device: String, val on: Boolean)

/**
 * Alle Anfragen der Werkzeuge (Pfade, Körper und Reihenfolge der Abfrage wie
 * im Web). Bestätigungswerte, die die API verlangt, setzt die App selbst ein.
 */
class ToolsApi(val http: AdminHttp) {

    // Übersicht, Aufträge
    suspend fun operations(): Operations = http.get("operations")
    suspend fun scripts(): ScriptsStatus = http.get("scripts")
    suspend fun monitoring(): MonitorStatus = http.get("monitoring")
    suspend fun storage(): StorageStatus = http.get("storage")
    suspend fun backups(): BackupList = http.get("backups")
    suspend fun timezone(): String = http.get<NetworkTimezone>("network").timezone

    /** Knopf eines Auftrags (Anhalten, Weiter, Stoppen, Wiederholen): POST mit dem Körper des Webs. */
    suspend fun taskAction(action: TaskAction) {
        http.send<JsonElement, JsonElement>("POST", action.path.removePrefix("/"), jsonOf(action.body))
    }

    suspend fun applicationRecovery(details: Boolean = false): ApplicationRecoveryStatus =
        http.get("backups/application-recovery" + if (details) "?details=1" else "")

    suspend fun clones(volume: String): List<Clone> = http.get("vm-clones/${seg(volume)}")

    /** Unterbrochene Anwendungssicherung prüfen: die Kennung geht als Bestätigung mit. */
    suspend fun reviewApplication(id: String, restart: Boolean) {
        http.post("backups/application-recovery", RecoveryBody(id, restart))
    }

    suspend fun reviewClone(volume: String, id: String, action: String) {
        http.post("vm-clones/${seg(volume)}/$id", CloneBody(id, action))
    }

    // Task-Manager
    suspend fun system(): TaskSystem = http.get("system")
    suspend fun processes(): ProcessList = http.get("system/processes")
    suspend fun docker(): DockerStatus = http.get("docker")
    suspend fun vms(): VmList = http.get("vms")
    suspend fun lxc(): LxcList = http.get("lxc")

    /** SIGTERM/SIGKILL mit der Startzeit (gegen neu vergebene Nummern). */
    suspend fun signal(p: ProcessRow, signal: String) {
        http.post("system/processes/${p.pid}/signal", SignalBody(signal, p.start))
    }

    suspend fun container(id: String, action: String) {
        http.call<JsonElement>("POST", "docker/containers/${seg(id)}/$action")
    }

    suspend fun vm(name: String, action: String) {
        http.call<JsonElement>("POST", "vms/${seg(name)}/$action")
    }

    suspend fun lxcAction(name: String, action: String) {
        http.call<JsonElement>("POST", "lxc/${seg(name)}/$action")
    }

    // Monitoring
    suspend fun history(hours: Int): List<MonitorSample> = http.get("monitoring/history?hours=$hours")
    suspend fun trends(): List<Trend> = http.get("monitoring/trends")
    suspend fun saveMonitoring(settings: MonitorSettings): MonitorStatus = http.send("PUT", "monitoring", settings)
    fun monitoringExport(hours: Int, format: String) = "monitoring/export?" + query("hours" to hours.toString(), "format" to format)

    // Aktivität
    fun activityQuery(days: Int, user: String, search: String, failed: Boolean, before: String? = null, format: String? = null): String =
        "activity?" + query(
            "days" to days.toString(),
            "limit" to "200",
            "user" to user.ifEmpty { null },
            "q" to search.trim().ifEmpty { null },
            "failed" to if (failed) "1" else null,
            "before" to before,
            "format" to format,
        )

    suspend fun activity(path: String): ActivityPage = http.get(path)
    suspend fun users(): List<Account> = http.get("users")
    suspend fun saveActivity(settings: ActivitySettings): ActivityStatus = http.send("PUT", "activity", settings)

    // Protokoll
    fun logsQuery(source: String, priority: Int, search: String, range: String, after: String = "", now: Long = System.currentTimeMillis() / 1000): String {
        val since = rangeSeconds(range).takeIf { it > 0 }?.let { (now - it).toString() }
        return "logs?" + query(
            "source" to source,
            "priority" to priority.toString(),
            "lines" to "500",
            "search" to search.trim().ifEmpty { null },
            "since" to since,
            "after" to after.ifEmpty { null },
        )
    }

    /** Liest Zeilen des Journals; eine Antwort, die nicht passt, ist ein Fehler (nie leere Zeilen). */
    suspend fun logs(path: String): Logs {
        val logs = try {
            http.get<Logs>(path)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ApiException(200, "invalid_response", "Invalid journal response")
        } catch (e: IllegalArgumentException) {
            throw ApiException(200, "invalid_response", "Invalid journal response")
        }
        if (!validLogs(logs)) throw ApiException(200, "invalid_response", "Invalid journal response")
        return logs
    }

    fun logsExport(source: String, priority: Int, range: String, now: Long = System.currentTimeMillis() / 1000): String {
        val since = rangeSeconds(range).takeIf { it > 0 }?.let { (now - it).toString() }
        return "logs/export?" + query("source" to source, "priority" to priority.toString(), "since" to since)
    }

    // Wartung
    suspend fun maintenance(): MaintenanceStatus = http.get("maintenance")
    suspend fun saveMaintenance(settings: MaintenanceSettings): MaintenanceStatus = http.send("PUT", "maintenance", settings)

    /** Ausführen, Stoppen oder Prüfen; Prüfen schickt die Kennung des Auftrags als Bestätigung. */
    suspend fun maintenanceAction(id: String, kind: String): MaintenanceStatus =
        if (kind == "review") http.send("POST", "maintenance/${seg(id)}/$kind", ConfirmBody(id))
        else http.send("POST", "maintenance/${seg(id)}/$kind", buildJsonObject { })

    // Skripte
    suspend fun saveScriptVolume(volume: String): ScriptsStatus = http.send("PUT", "scripts/volume", VolumeBody(volume))
    suspend fun script(id: String): ScriptDetail = http.get("scripts/$id")
    suspend fun createScript(d: ScriptDraft) {
        http.post("scripts", draftBody(d))
    }
    suspend fun updateScript(id: String, d: ScriptDraft) {
        http.put("scripts/$id", draftBody(d))
    }
    suspend fun deleteScript(id: String) {
        http.delete("scripts/$id")
    }
    suspend fun runScript(id: String) {
        http.call<JsonElement>("POST", "scripts/$id/run")
    }
    suspend fun cancelScript(id: String) {
        http.call<JsonElement>("POST", "scripts/$id/cancel")
    }
    suspend fun scriptOutput(id: String, from: Long): ScriptOutput = http.get("scripts/$id/output?from=$from")
    suspend fun scriptRuns(id: String): ScriptRuns = http.get("scripts/$id/runs")

    // Hardware
    suspend fun hardwareReport(): HardwareReport = http.get("hardware/report")
    suspend fun uasQuirks(): UasStatus = http.get("hardware/uas-quirks")
    suspend fun setUas(device: String, on: Boolean): UasStatus = http.send("PUT", "hardware/uas-quirks", UasBody(device, on))

    // Plattentests
    suspend fun diskTests(): TestList = http.get("storage/tests")

    /** Start; ein überschreibender Test schickt den Plattennamen als Bestätigung, er läuft nach einem Neustart nie von selbst weiter. */
    suspend fun startDiskTest(disk: String, mode: String, autoResume: Boolean): TestList =
        http.send("POST", "storage/tests", DiskTestStart(disk, mode, if (mode == "write") disk else "", mode == "read" && autoResume))

    suspend fun stopDiskTest(disk: String): TestList = http.call("DELETE", "storage/tests/${seg(disk)}")
    suspend fun resumeDiskTest(test: DiskTest): TestList = http.send("POST", "storage/tests/${seg(test.disk)}/resume", ConfirmBody(resumeConfirm(test)))
    suspend fun dismissDiskNotices(): TestList = http.call("DELETE", "storage/test-notices")

    /** Lädt [path] nach [out] (Export, Diagnosepaket); gibt den Namen des NAS zurück, wenn es einen schickt. */
    suspend fun download(path: String, out: OutputStream, progress: (Long, Long) -> Unit = { _, _ -> }): String? =
        http.download(path, out, progress = progress)
}

private fun jsonOf(map: Map<String, Any?>): JsonElement = buildJsonObject {
    for ((k, v) in map) put(k, when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    })
}
