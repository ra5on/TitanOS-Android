package io.github.ra5on.titanos.settings.pages.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.boolOrNull
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.settings.shell.SettingsIndex
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.i18n.tr
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

// ---- lib/setup-checklist.ts ----

/** Ein Schritt der Einrichtungsliste; [done] ist null, solange unbekannt. */
internal data class SetupTodo(val href: String, val key: String, val done: Boolean?)

/**
 * Fragt das NAS, was erledigt ist (checkSetup im Web). Ohne [admin] nur der
 * eigene Schritt (Anmeldung in zwei Schritten). Zweites Ergebnis: ob eine der
 * Hauptantworten fehlte.
 */
internal suspend fun checkSetup(api: SettingsApi, admin: Boolean): Pair<List<SetupTodo>, Boolean> = coroutineScope {
    val session = async { runCatching { api.obj("session") } }
    if (!admin) {
        val tf = session.await().getOrNull()?.o("user")?.boolOrNull("twoFactor")
        return@coroutineScope listOf(SetupTodo("/account", "welcome.todoTwoFactor", tf)) to false
    }
    fun get(path: String) = async { runCatching { api.get(path) } }
    val storage = get("storage"); val shares = get("shares"); val notify = get("notifications/settings")
    val stick = get("config-backup"); val plugins = get("plugins"); val backups = get("backups")
    val tailnet = get("plugins/tailscale"); val wireguard = get("network/wireguard"); val push = get("notifications/push")
    fun value(r: Result<JsonElement>, test: (JsonElement) -> Boolean): Boolean? = r.getOrNull()?.let { runCatching { test(it) }.getOrNull() }
    val s = storage.await(); val sh = shares.await(); val n = notify.await(); val st = stick.await()
    val pl = plugins.await(); val b = backups.await(); val tn = tailnet.await(); val wg = wireguard.await(); val p = push.await()
    val tf = session.await().getOrNull()?.o("user")?.boolOrNull("twoFactor")
    fun JsonElement.obj() = this as JsonObject
    val todos = mutableListOf(
        SetupTodo("/storage", "welcome.todoVolume", value(s) { (it.obj()["volumes"] as JsonArray).isNotEmpty() }),
        SetupTodo("/shares", "welcome.todoShare", value(sh) { (it as JsonArray).isNotEmpty() }),
        SetupTodo("/account", "welcome.todoTwoFactor", tf),
        SetupTodo("/settings/notifications", "welcome.todoNotify", value(n) { e ->
            val settings = e.obj()["settings"] as JsonObject
            listOf("email", "ntfy", "telegram", "discord", "homeAssistant").any { (settings[it] as? JsonObject).bool("enabled") }
        }),
        SetupTodo("/settings/backups/configuration", "welcome.todoStick", value(st) { e ->
            val backup = e.obj()["backup"] as JsonObject
            (backup["backups"] as JsonArray).isNotEmpty() && backup.str("lastError").isEmpty()
        }),
        SetupTodo("/settings/backups/data", "welcome.todoBackup", value(b) { e -> (e.obj()["jobs"] as JsonArray).objects().any { it.o("job").bool("enabled") } }),
        SetupTodo(
            "/settings/network?tab=remote", "welcome.todoRemote",
            when {
                tn.getOrNull()?.let { (it as? JsonObject).str("state") == "Running" } == true || wg.getOrNull()?.let { (it as? JsonObject).bool("enabled") } == true -> true
                tn.isFailure && wg.isFailure -> null
                else -> false
            },
        ),
        SetupTodo("/settings/notifications#push", "welcome.todoPush", value(p) { e -> (e.obj()["devices"] as JsonArray).isNotEmpty() }),
        SetupTodo("/docker", "welcome.todoApps", null),
        SetupTodo("/tools/hardware", "welcome.todoHardware", null),
    )
    // Erweiterungen für erkannte Hardware: nur, wenn es welche gibt.
    if ((pl.getOrNull() as? JsonObject).a("recommendations").isNotEmpty()) todos.add(1, SetupTodo("/plugins", "welcome.todoDrivers", false))
    val failed = listOf(s, sh, n, st).any { it.isFailure }
    todos to failed
}

// ---- lib/settings-states.svelte.ts ----

internal data class AreaState(val text: String, val tone: Tone)

/** Kurze Zustände einiger Bereiche (Update, Akku, Zertifikat, überfällige Sicherung). */
internal suspend fun settingsStates(api: SettingsApi, admin: Boolean): Map<String, AreaState> = coroutineScope {
    if (!admin) return@coroutineScope emptyMap()
    val update = async { runCatching { api.obj("update") } }
    val ups = async { runCatching { api.obj("ups") } }
    val cert = async { runCatching { api.obj("system/certificate") } }
    val notices = async { runCatching { api.obj("notifications").a("messages").objects() } }
    val next = LinkedHashMap<String, AreaState>()
    if (update.await().getOrNull()?.get("available") is JsonObject) next["updates"] = AreaState("settings.state.update", Tone.Info)
    ups.await().getOrNull()?.o("status")?.let { power ->
        if (power.bool("onBattery")) {
            val low = power.bool("lowBattery")
            next["ups"] = AreaState(if (low) "settings.state.lowBattery" else "settings.state.onBattery", if (low) Tone.Error else Tone.Warn)
        }
    }
    cert.await().getOrNull()?.str("notAfter")?.let { Fmt.instant(it) }?.let { notAfter ->
        val days = (notAfter.toEpochMilli() - System.currentTimeMillis()) / 86_400_000.0
        if (days < 0) next["access"] = AreaState("settings.state.certExpired", Tone.Error)
        else if (days < 14) next["access"] = AreaState("settings.state.certExpiring", Tone.Warn)
    }
    // Je Sicherungsauftrag sagt seine neueste Meldung, ob er überfällig ist.
    val newest = LinkedHashMap<String, String>()
    for (m in notices.await().getOrDefault(emptyList()).sortedByDescending { Fmt.instant(it.str("time")) ?: Instant.EPOCH }) {
        val kind = m.str("kind")
        if (kind.startsWith("backup_") && m.str("subject") !in newest) newest[m.str("subject")] = kind
    }
    if ("backup_overdue" in newest.values) next["backups"] = AreaState("settings.state.backupOverdue", Tone.Warn)
    next
}

// ---- lib/settings-recent.ts und lib/activity.ts (activityLabel) ----

private val NOT_A_CHANGE = Regex("""/(\{action\}|run|check|probe|preview|restore|scan|discover|call|join-info|test|confirm|revert|applications/\{step\}|application-recovery)(/|$)""")

private val PREFIXES: List<Pair<String, String>> = listOf(
    "system/timezone" to "timezone", "system/energy" to "energy", "system/power-schedule" to "energy",
    "system/power-button" to "power-button", "system/memory" to "memory", "system/mcp" to "mcp",
    "system/letsencrypt" to "letsencrypt", "system/ssh" to "access", "system/certificate" to "access",
    "system/login" to "access", "system/webdav" to "access", "terminal" to "access", "system/hostname" to "network",
    "system/fans" to "temperatures", "network/ddns" to "ddns", "network/ipv64" to "ddns", "ipv64" to "ddns",
    "network/wireguard" to "wireguard", "network" to "network", "firewall" to "firewall", "update/settings" to "updates",
    "ups" to "ups", "notifications/settings" to "notifications", "notifications/push" to "notifications",
    "notifications/temperatures" to "temperatures", "homeassistant/settings" to "homeassistant",
    "homeassistant/connections" to "homeassistant", "homeassistant/storage" to "homeassistant",
    "homeassistant/undo" to "homeassistant", "agent/settings" to "agent", "docker/proxy" to "proxy",
    "services" to "services", "hardware" to "hardware", "backups" to "backups", "config-backup" to "backups",
    "replication" to "backups", "account/tokens" to "api", "login-appearance" to "appearance",
    "app-icon" to "appearance", "files/index" to "files", "cluster" to "cluster", "viewer/settings" to "viewer",
).sortedByDescending { it.first.length }

/** Der Bereich der Systemsteuerung, den eine Aktion im Protokoll änderte, sonst null. */
internal fun settingsAreaOfAction(action: String): String? {
    val parts = action.split(' ', limit = 2)
    val method = parts.getOrElse(0) { "" }
    val path = parts.getOrElse(1) { "" }
    if (method !in listOf("PUT", "POST", "DELETE", "PATCH")) return null
    if (!path.startsWith("/api/v1/")) return null
    val rest = path.removePrefix("/api/v1/")
    if (NOT_A_CHANGE.containsMatchIn("/$rest")) return null
    return PREFIXES.firstOrNull { (p, _) -> rest == p || rest.startsWith("$p/") }?.second
}

/** Die letzten erfolgreichen Änderungen an Einstellungen, neueste zuerst. */
internal fun recentSettingsChanges(entries: List<JsonObject>, count: Int = 5): List<Pair<JsonObject, String>> =
    entries.filter { it.bool("ok") }
        .mapNotNull { e -> settingsAreaOfAction(e.str("action"))?.let { e to it } }
        .sortedByDescending { it.first.str("at") }
        .take(count)

private val NAMED: Map<String, String> = mapOf(
    "login" to "login",
    "logout" to "logout",
    "login_failed" to "loginFailed",
    "login_blocked" to "loginBlocked",
    "ssh_login" to "sshLogin",
    "ssh_login_blocked" to "sshLoginBlocked",
    "firewall_block" to "firewallBlock",
    "firewall_reverted" to "firewallReverted",
    "PUT /api/v1/firewall" to "firewallChange",
    "PUT /api/v1/app-icon" to "appIcon",
    "PUT /api/v1/system/fans" to "fans",
    "PUT /api/v1/docker/auto-update" to "autoUpdate",
    "POST /api/v1/storage/volumes/{name}/replace" to "diskReplace",
    "PUT /api/v1/health-report" to "healthReport",
    "POST /api/v1/health-report/send" to "healthReportSend",
    "PUT /api/v1/network/wireguard" to "wireguardChange",
    "POST /api/v1/network/wireguard/peers" to "wireguardAdd",
    "POST /api/v1/network/wireguard/peers/remove" to "wireguardRemove",
    "POST /api/v1/notifications/push" to "pushAdd",
    "DELETE /api/v1/notifications/push/{id}" to "pushRemove",
    "POST /api/v1/firewall/confirm" to "firewallConfirm",
    "POST /api/v1/firewall/revert" to "firewallRevert",
    "PUT /api/v1/firewall/autoblock" to "firewallAutoBlock",
    "POST /api/v1/firewall/unblock" to "firewallUnblock",
    "login_disabled" to "loginDisabled",
    "iphone_profile" to "iphoneProfile",
    "setup" to "setup",
    "POST /api/v1/files/delete" to "fileDelete",
    "POST /api/v1/files/rename" to "fileRename",
    "POST /api/v1/files/rename-batch" to "fileRename",
    "POST /api/v1/files/copy" to "fileCopy",
    "POST /api/v1/files/mkdir" to "fileMkdir",
    "POST /api/v1/files/create" to "fileCreate",
    "PUT /api/v1/files/text" to "fileEdit",
    "PUT /api/v1/files/content" to "fileContent",
    "POST /api/v1/webapps/{id}/install" to "webappInstall",
    "DELETE /api/v1/webapps/{id}" to "webappRemove",
    "POST /api/v1/webapps/defaults/install" to "webappDefaults",
    "POST /api/v1/webapps/migrate" to "webappMigrate",
    "POST /api/v1/files/uploads" to "fileUpload",
    "POST /api/v1/files/url-download" to "fileDownloadLink",
    "POST /api/v1/files/extract" to "fileExtract",
    "POST /api/v1/files/trash/{action}" to "fileTrash",
    "POST /api/v1/files/undo/{id}" to "fileUndo",
    "POST /api/v1/users" to "userCreate",
    "PUT /api/v1/users/{name}" to "userChange",
    "DELETE /api/v1/users/{name}" to "userDelete",
    "POST /api/v1/users/{name}/lock" to "userLock",
    "POST /api/v1/users/{name}/unlock" to "userUnlock",
    "POST /api/v1/users/{name}/password" to "userPassword",
    "POST /api/v1/shares" to "shareCreate",
    "PUT /api/v1/shares/{name}" to "shareChange",
    "DELETE /api/v1/shares/{name}" to "shareDelete",
    "POST /api/v1/shares/{name}/delete-data" to "shareDeleteData",
    "share_data_deleted" to "shareDataDeleted",
    "share_data_delete_failed" to "shareDataDeleteFailed",
    "POST /api/v1/storage/volumes" to "volumeCreate",
    "DELETE /api/v1/storage/volumes/{name}" to "volumeDelete",
    "POST /api/v1/storage/volumes/{name}/unlock" to "volumeUnlock",
    "POST /api/v1/storage/volumes/{name}/lock" to "volumeLock",
    "POST /api/v1/storage/volumes/{name}/passwords" to "volumePasswords",
    "GET /api/v1/storage/volumes/{name}/luks-header" to "volumeHeaderBackup",
    "GET /api/v1/terminal/shell" to "consoleShell",
    "GET /api/v1/docker/containers/{id}/console" to "consoleContainer",
    "GET /api/v1/vms/{name}/serial" to "consoleSerial",
    "GET /api/v1/vms/{name}/screen" to "consoleScreen",
    "GET /api/v1/lxc/{name}/console" to "consoleLxc",
    "GET /api/v1/config-backup/download" to "configDownload",
    "GET /api/v1/diagnostics/bundle" to "diagnostics",
    "POST /api/v1/system/power" to "power",
    "PUT /api/v1/hardware/intel-gpu" to "intelGuc",
    "PUT /api/v1/login-appearance" to "loginAppearance",
    "DELETE /api/v1/login-appearance" to "loginAppearanceReset",
    "DELETE /api/v1/docker/images/{id}" to "dockerImageDelete",
    "POST /api/v1/docker/images/prune" to "dockerImagesPrune",
    "DELETE /api/v1/docker/volumes/{name}" to "dockerVolumeDelete",
    "POST /api/v1/docker/volumes/prune" to "dockerVolumesPrune",
    "POST /api/v1/docker/builder/prune" to "dockerBuilderPrune",
    "POST /api/v1/update/{action}" to "update",
    "POST /api/v1/vms/{name}/{action}" to "vmAction",
    "POST /api/v1/docker/containers/{id}/{action}" to "containerAction",
    "POST /api/v1/account/2fa/enable" to "twoFactorOn",
    "POST /api/v1/account/2fa/disable" to "twoFactorOff",
    "POST /api/v1/account/tokens" to "tokenCreate",
    "DELETE /api/v1/account/tokens/{id}" to "tokenDelete",
    "PUT /api/v1/system/mcp" to "mcpSettings",
    "mcp_tool" to "mcpTool",
    "PUT /api/v1/activity" to "activitySettings",
    "vm_schedule_start" to "vmScheduleStart",
    "vm_schedule_stop" to "vmScheduleStop",
    "vm_schedule_poweroff" to "vmSchedulePoweroff",
    "letsencrypt_issued" to "letsencryptIssued",
    "letsencrypt_failed" to "letsencryptFailed",
    "ddns_updated" to "ddnsUpdated",
    "ddns_failed" to "ddnsFailed",
    "session_lock" to "lock.activity.locked",
    "session_autolock" to "lock.activity.autolocked",
    "session_unlock" to "lock.activity.unlocked",
    "session_unlock_failed" to "lock.activity.unlockFailed",
    "session_unlock_blocked" to "lock.activity.unlockBlocked"
)

/** Was eine Aktion im Protokoll tat, in Worten (activityLabel + describe der Übersicht). */
internal fun describeActivity(tr: Tr, entry: JsonObject): String {
    val action = entry.str("action")
    NAMED[action]?.let { return tr.t("activity.do.$it") }
    val method = action.substringBefore(' ')
    val verb = when (method) {
        "DELETE" -> "remove"
        "PUT", "PATCH" -> "change"
        "GET" -> "open"
        else -> "run"
    }
    val v = tr.t("activity.verb.$verb")
    val target = entry.str("target")
    return if (target.isNotEmpty()) "$v: $target" else v
}

// ---- routes/settings/SettingsOverview.svelte ----

/**
 * Übersicht der Systemsteuerung (SettingsOverview.svelte, PLAN 71): wie es dem
 * NAS geht, was noch offen ist, welche Dienste laufen und was zuletzt
 * geändert wurde. Nur echte Antworten des NAS; was ein Konto nicht lesen
 * darf, wird nicht gefragt.
 */
@Composable
fun OverviewPage(a: PageArgs, wide: Boolean) {
    val env = LocalEnv.current
    val tr = tr()
    val context = LocalContext.current
    val admin = env.admin
    val index = remember { runCatching { SettingsIndex.load(context) }.getOrNull() }
    fun areaLabel(id: String): String = index?.area(id)?.label?.let { tr.t(it) } ?: id

    var info by remember { mutableStateOf<JsonObject?>(null) }
    var volumes by remember { mutableStateOf<List<JsonObject>?>(null) }
    var states by remember { mutableStateOf<Map<String, AreaState>>(emptyMap()) }
    var setup by remember { mutableStateOf<List<SetupTodo>?>(null) }
    var setupFailed by remember { mutableStateOf(false) }
    var services by remember { mutableStateOf<JsonObject?>(null) }
    var accounts by remember { mutableStateOf<List<JsonObject>?>(null) }
    var ssh by remember { mutableStateOf<JsonObject?>(null) }
    var firewall by remember { mutableStateOf<JsonObject?>(null) }
    var recent by remember { mutableStateOf<List<Pair<JsonObject, String>>>(emptyList()) }

    LaunchedEffect(admin) {
        coroutineScope {
            async { info = runCatching { env.api.obj("system") }.getOrNull() }
            async { states = runCatching { settingsStates(env.api, admin) }.getOrDefault(emptyMap()) }
            async { checkSetup(env.api, admin).let { (todos, failed) -> setup = todos; setupFailed = failed } }
            if (admin) {
                async { volumes = runCatching { env.api.obj("storage").a("volumes").objects() }.getOrNull() }
                async { services = runCatching { env.api.obj("services") }.getOrDefault(JsonObject(emptyMap())) }
                async { accounts = runCatching { (env.api.get("users") as? JsonArray)?.objects() }.getOrNull() }
                async { ssh = runCatching { env.api.obj("system/ssh") }.getOrNull()?.takeIf { it["enabled"] is kotlinx.serialization.json.JsonPrimitive } }
                async { firewall = runCatching { env.api.obj("firewall") }.getOrNull()?.takeIf { it.o("config")["enabled"] is kotlinx.serialization.json.JsonPrimitive } }
                async { recent = runCatching { recentSettingsChanges(env.api.obj("activity?days=30&limit=500").a("entries").objects()) }.getOrDefault(emptyList()) }
            }
        }
    }
    val on = t("settingshome.services.on")
    val off = t("settingshome.services.off")

    SettingGroup(t("settingshome.health")) {
        val i = info
        if (i != null) {
            SettingRow(t("settingshome.health.host"), icon = WebIcons["home"], control = { Value(i.str("hostname"), bold = true) })
            SettingRow(t("settingshome.health.version"), icon = WebIcons["info"], control = { Value(Fmt.osVersion(i.str("version")), bold = true) })
            SettingRow(t("settingshome.health.uptime"), icon = WebIcons["clock"], control = { Value(Fmt.duration(tr, i.long("uptimeSeconds")), bold = true) })
        } else {
            Muted(t("settingshome.health.loading"))
        }
        val vols = volumes
        if (admin && vols != null) {
            SettingRow(
                t("settingshome.health.volumes"),
                desc = if (vols.isEmpty()) null else if (vols.all { it.str("state") == "ok" }) t("settingshome.health.allOk") else t("settingshome.health.attention"),
                icon = WebIcons["disk"],
                control = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (vols.isEmpty()) ToneChip(t("settingshome.health.noVolume"), Tone.Warn, onClick = { env.open("/storage") })
                        for (v in vols) {
                            val state = v.str("state")
                            val tone = when (state) { "ok" -> Tone.Ok; "failed", "missing" -> Tone.Error; else -> Tone.Warn }
                            ToneChip("${v.str("name")}: ${tr.or("state.$state", state)}", tone, onClick = { env.open("/storage") })
                        }
                    }
                },
            )
        }
    }

    SettingGroup(t("settingshome.todo")) {
        for ((area, state) in states) {
            SettingRow(areaLabel(area), icon = WebIcons["bell"], onClick = { a.openPage(area) }, control = { ToneChip(t(state.text), state.tone) })
        }
        val open = setup.orEmpty().filter { it.done == false }
        for (todo in open) {
            SettingRow(t(todo.key), desc = t("${todo.key}Text"), icon = WebIcons["list"], onClick = { a.go(env, todo.href) })
        }
        if (setup == null) Muted(t("settingshome.todo.checking"))
        else if (states.isEmpty() && open.isEmpty()) SettingRow(t("settingshome.todo.none"), icon = WebIcons["check"])
        if (setupFailed) Muted(t("settingshome.todo.unknown"))
    }

    if (admin) {
        SettingGroup(t("settingshome.services"), tools = { SmallButton(t("settingshome.services.manage"), { a.openPage("services") }) }) {
            val s = services
            for (name in listOf("docker", "vms", "lxc")) {
                val sv = s?.get(name) as? JsonObject ?: continue
                if (sv["enabled"] !is kotlinx.serialization.json.JsonPrimitive) continue
                SettingRow(t("services.$name"), icon = WebIcons[if (name == "vms") "monitor" else "box"], control = {
                    if (!sv.bool("available")) Value(t("settingshome.services.notPossible"), bold = true)
                    else {
                        OnOff(sv.bool("enabled"), on, off)
                        if (sv.bool("enabled") && sv.int("total") > 0) Muted(t("services.counts", "running" to sv.int("running"), "total" to sv.int("total")))
                    }
                })
            }
            accounts?.let { list ->
                val locked = list.count { it.bool("disabled") }
                SettingRow(
                    t("desktop.app.users"),
                    icon = WebIcons["users"],
                    summary = t("settingshome.users.count", "n" to list.size) + if (locked > 0) " · " + t("settingshome.users.locked", "n" to locked) else "",
                    onClick = { a.openPage("users") },
                )
            }
            ssh?.let { v -> SettingRow(t("access.ssh"), icon = WebIcons["terminal"], onClick = { a.openPage("access") }, control = { OnOff(v.bool("enabled"), on, off) }) }
            firewall?.let { v -> SettingRow(t("firewall.title"), icon = WebIcons["shield"], onClick = { a.openPage("firewall") }, control = { OnOff(v.o("config").bool("enabled"), on, off) }) }
            if (s == null) Muted(t("settingshome.health.loading"))
        }
    }

    if (admin && recent.isNotEmpty()) {
        SettingGroup(t("settingshome.recent"), tools = { SmallButton(t("settingshome.recentAll"), { env.open("/tools/activity") }) }) {
            for ((entry, area) in recent) {
                SettingRow(areaLabel(area), desc = describeActivity(tr, entry), icon = WebIcons["edit"], onClick = { a.openPage(area) }, control = {
                    val user = entry.str("user")
                    if (user.isNotEmpty()) Muted(t("settingshome.recentBy", "user" to user))
                    Value(Fmt.dateTime(tr, entry.str("at")))
                })
            }
        }
    }
}
