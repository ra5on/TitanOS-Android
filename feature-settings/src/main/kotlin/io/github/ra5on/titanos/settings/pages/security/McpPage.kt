package io.github.ra5on.titanos.settings.pages.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId

/** Steht im Befehl für das Token, bis eines gerade erstellt wurde (lib/mcp.ts). */
internal const val TOKEN_PLACEHOLDER = "<TOKEN>"

internal fun mcpEndpoint(origin: String, path: String?): String = origin.trimEnd('/') + (path?.ifEmpty { null } ?: "/mcp")

internal fun claudeCodeCommand(endpoint: String, token: String = TOKEN_PLACEHOLDER): String =
    "claude mcp add --transport http titanos $endpoint --header \"Authorization: Bearer $token\""

private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** claude_desktop_config.json mit mcp-remote (wie JSON.stringify(…, null, 2) im Web). */
internal fun desktopRemoteConfig(endpoint: String, token: String = TOKEN_PLACEHOLDER): String = """
{
  "mcpServers": {
    "titanos": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        ${q(endpoint)},
        "--header",
        "Authorization:${'$'}{TITANOS_AUTH}"
      ],
      "env": {
        "TITANOS_AUTH": ${q("Bearer $token")}
      }
    }
  }
}
""".trim()

/** claude_desktop_config.json mit der stdio-Brücke von titanosctl. */
internal fun desktopBridgeConfig(origin: String, tokenFile: String): String = """
{
  "mcpServers": {
    "titanos": {
      "command": "titanosctl",
      "args": [
        "--url",
        ${q(origin.trimEnd('/'))},
        "--token-file",
        ${q(tokenFile)},
        "mcp"
      ]
    }
  }
}
""".trim()

private val AREA_ICONS = mapOf(
    "system" to "gear", "storage" to "disk", "shares" to "folder", "files" to "file", "docker" to "box", "vms" to "monitor",
    "backups" to "archive", "network" to "globe", "accounts" to "users", "plugins" to "grid", "updates" to "download", "home" to "home",
)
private val AREAS = listOf("system", "storage", "shares", "files", "docker", "vms", "backups", "network", "accounts", "plugins", "updates", "home")

/**
 * MCP-Server (routes/settings/mcp): Zustand, Adresse, wie man einen Client
 * verbindet (drei Reiter mit Befehl zum Kopieren), die Werkzeuge je Bereich
 * mit Schalter und Wahl (aus, an, an inkl. Gefahr) samt Schnellwahl und die
 * letzten Aufrufe. Schalter und Werkzeuge nur für Besitzer und Administratoren.
 */
@Composable
fun McpPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val status = rememberLoad { env.api.obj("system/mcp") }
    val tokens = rememberLoad { runCatching { env.api.obj("account/tokens").objs("tokens").count { "mcp" in it.strings("scopes") } }.getOrDefault(0) }
    val calls = rememberLoad { if (env.admin) runCatching { env.api.obj("activity?via=mcp&days=1&limit=20").objs("entries") }.getOrDefault(emptyList()) else emptyList() }
    val run = rememberRunner()
    val modes = remember { mutableStateMapOf<String, String>() }
    var tab by remember { mutableStateOf("code") }
    var copied by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val origin = remember { env.api.origin() }

    PageIntro(t("mcp.pageIntro"))
    status.error?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { status.reload() }) }
    val st = status.value
    if (st == null) {
        if (status.loading) LoadingLine(t("common.loading"))
        return
    }
    val tools = st.objs("tools")
    for (tool in tools) if (tool.str("name") !in modes) modes[tool.str("name")] = tool.str("mode", "on")
    val enabled = st.bool("enabled")
    fun save(on: Boolean = enabled) {
        saved = false
        run.run(guard = GuardAction.Settings) {
            env.api.put("system/mcp", jobj("enabled" to on, "tools" to modes.toMap()))
            val next = env.api.obj("system/mcp")
            modes.clear()
            status.value = next
            tokens.refresh()
            calls.refresh()
            saved = true
        }
    }
    val endpoint = mcpEndpoint(origin, st.str("path"))

    SettingGroup(t("mcp.status")) {
        val title = if (env.admin) t("mcp.enabled") else t("mcp.state")
        SettingRow(title, icon = WebIcons["sparkle"], control = {
            StatusChip(if (enabled) t("mcp.ready") else t("mcp.off"), if (enabled) Tone.Ok else Tone.Inactive)
            if (env.admin) Toggle(enabled, { save(it) }, onText(), offText(), enabled = !run.running, label = title)
        })
        if (env.admin) {
            val today = calls.value.orEmpty().filter { c -> Fmt.instant(c.str("at"))?.atZone(ZoneId.systemDefault())?.toLocalDate() == LocalDate.now() }
            val refused = today.count { !it.bool("ok") }
            SettingRow(t("mcp.callsToday"), icon = WebIcons["activity"], control = {
                Value(today.size.toString() + if (refused > 0) " · " + t("mcp.refused", "count" to refused) else "")
            })
        }
        SettingRow(t("mcp.tokens"), icon = WebIcons["key"], control = { Value((tokens.value ?: 0).toString()) })
        SettingRow(t("mcp.endpoint"), icon = WebIcons["globe"], control = {
            Value(endpoint, mono = true)
            SmallButton(t(if (copied == "endpoint") "mcp.copied" else "mcp.copy"), { copyText(context, endpoint); copied = "endpoint" }, icon = WebIcons["copy"])
        })
    }

    SettingGroup(t("mcp.connect")) {
        Segments(tab, listOf("code" to t("mcp.claudeCode"), "remote" to t("mcp.desktopRemote"), "bridge" to t("mcp.desktopBridge")), { tab = it })
        val snippet = when (tab) {
            "code" -> claudeCodeCommand(endpoint)
            "remote" -> desktopRemoteConfig(endpoint)
            else -> desktopBridgeConfig(origin, "/home/me/.config/titanos-token")
        }
        CodeBlock(snippet)
        Buttons {
            SmallButton(t(if (copied == "snippet") "mcp.copied" else "mcp.copy"), { copyText(context, snippet); copied = "snippet" }, icon = WebIcons["copy"])
            SmallButton(t("mcp.createToken"), { a.openPage("api"); a.tab = "mcp" }, kind = ButtonKind.Primary)
        }
        Muted(t("mcp.tokenHint"))
        Muted(t("mcp.placeholder"))
        if (tab == "bridge") Muted(t("mcp.bridgeHint"))
    }

    if (env.admin) {
        val isStandard = tools.all { modes[it.str("name")] == "on" }
        SettingGroup(t("mcp.tools"), desc = t("mcp.toolsIntro")) {
            Buttons {
                Muted(t("mcp.quick"))
                SmallButton(t("mcp.quickRead"), {
                    for (tool in tools) modes[tool.str("name")] = if (tool.str("kind") == "read") "on" else "off"
                    save()
                }, enabled = !run.running)
                SmallButton((if (isStandard) "✓ " else "") + t("mcp.quickStandard"), {
                    for (tool in tools) modes[tool.str("name")] = "on"
                    save()
                }, kind = if (isStandard) ButtonKind.Primary else ButtonKind.Normal, enabled = !run.running)
                SmallButton(t("mcp.quickAll"), {
                    for (tool in tools) modes[tool.str("name")] = if (tool.bool("dangerous")) "all" else "on"
                    save()
                }, enabled = !run.running)
            }
            if (saved) Banner(t("mcp.saved"), tone = Tone.Ok)
            RunnerState(run)
            for (area in AREAS) {
                val group = tools.filter { it.str("area").ifEmpty { "system" } == area }.sortedBy { it.str("name") }
                if (group.isEmpty()) continue
                val on = group.count { modes[it.str("name")] != "off" }
                val danger = group.count { modes[it.str("name")] == "all" }
                val line = if (on == 0) t("mcp.groupOff") else t("mcp.groupState", "on" to on, "count" to group.size) + if (danger > 0) " · " + t("mcp.groupDanger", "count" to danger) else ""
                val title = tr.or("mcp.area.$area", area)
                SettingRow(title, icon = WebIcons[AREA_ICONS[area] ?: "tool"], summary = line, id = "mcp-area-$area", control = {
                    Toggle(on > 0, { yes ->
                        for (tool in group) { val n = tool.str("name"); modes[n] = if (yes) (if (modes[n] == "all") "all" else "on") else "off" }
                        save()
                    }, onText(), offText(), enabled = !run.running, label = title)
                }) {
                    for (tool in group) ToolSub(tool, modes[tool.str("name")] ?: "on", !run.running) { mode -> modes[tool.str("name")] = mode; save() }
                }
            }
        }
        SettingGroup(t("mcp.recent")) {
            val list = calls.value.orEmpty()
            if (list.isEmpty()) Muted(t("mcp.recentNone"))
            for (c in list.take(10)) {
                SettingRow(Fmt.dateTime(tr, c.str("at")), desc = c.str("target").ifEmpty { null }, control = {
                    StatusChip(if (c.bool("ok")) "ok" else (if (c.has("status")) c.str("status") else "–"), if (c.bool("ok")) Tone.Ok else Tone.Error)
                })
            }
            LinkText(t("mcp.recentAll"), { env.open("/tools/activity") })
        }
    }
}

@Composable
private fun ToolSub(tool: JsonObject, mode: String, enabled: Boolean, onMode: (String) -> Unit) {
    val tr = LocalTr.current
    val name = tool.str("name")
    val kind = if (tool.bool("dangerous")) "danger" else tool.str("kind")
    val kindLabel = when (kind) { "read" -> "◉ " + t("mcp.kindRead"); "danger" -> "⚠ " + t("mcp.kindDanger"); else -> "✎ " + t("mcp.kindChange") }
    val options = buildList {
        add("off" to tr.t("mcp.modeOff"))
        add("on" to tr.t("mcp.modeOn"))
        if (tool.bool("dangerous")) add("all" to tr.t("mcp.modeAll"))
    }
    SettingSub(name, desc = tr.or("mcp.tool.$name", "").ifEmpty { null }, control = {
        Buttons {
            StatusChip(kindLabel, when (kind) { "read" -> Tone.Info; "danger" -> Tone.Warn; else -> Tone.Inactive })
            Select(mode, options, onMode, label = name, enabled = enabled)
        }
    })
}
