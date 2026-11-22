package io.github.ra5on.titanos.settings.pages.smarthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.breakIds
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.without
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.NumberField
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private val SHARE_TEXT = mapOf(
    "allowed" to "agent.shareAllowed", "pending" to "agent.sharePending", "off" to "agent.shareOff",
    "unreachable" to "agent.shareUnreachable", "old" to "agent.shareOld", "nocluster" to "agent.shareNoCluster",
)

/**
 * Verbindung und Modelle (ConnectionTab.svelte): Ollama oder ein Anbieter,
 * Knoten des Clusters, Modell mit Namen, Denkstufe, Kontext und Grenzen,
 * Bilder, Sprache, Websuche, Code und die Verbindungen zu MCP-Servern.
 */
@Composable
internal fun AgentConnectionTab(st: AgentState) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = st.settings ?: return
    val status = st.status
    val cluster = st.clusterInfo
    val adminNode = cluster?.str("adminNode").orEmpty()
    val providerKind = s.o("provider").str("kind")
    val allModels = status?.a("models")?.objects().orEmpty()
    val models = allModels.filter { "tools" in it.texts("capabilities") }
    val others = allModels.filter { "tools" !in it.texts("capabilities") }
    val chosen = models.firstOrNull { it.str("name") == s.str("model") }
    val visionModels = allModels.filter { "vision" in it.texts("capabilities") }
    val vram = status?.a("running")?.objects().orEmpty().sumOf { it.long("vram") }
    val runningContext = status?.a("running")?.objects()?.firstOrNull { it.str("name") == s.str("model") }?.long("contextLength") ?: 0L
    val modelContext = allModels.firstOrNull { it.str("name") == s.str("model") }?.long("contextLength") ?: 0L
    val contexts = listOf(0L, 8192L, 16384L, 32768L, 65536L, 131072L, 196608L, 262144L)
        .filter { it == 0L || modelContext == 0L || it <= modelContext || it == s.long("context") }
    // Die Adresse der SearXNG-App dieses NAS, mit dem Namen, unter dem es erreichbar ist.
    val host = st.addresses.firstNotNullOfOrNull { runCatching { java.net.URI(it.str("url")).host }.getOrNull()?.takeIf { h -> h.isNotEmpty() } } ?: "192.168.1.5"
    val searxExample = "http://$host:8580"

    SettingGroup(t("agent.sectionConnection")) {
        if (cluster?.bool("member") == true || s.str("source") == "cluster") {
            SettingRow(t("agent.source"), desc = t("agent.sourceHint"), icon = WebIcons["tailnet"], control = {
                Segments(s.str("source"), listOf("" to t("agent.sourceOwn"), "cluster" to tr.t("agent.sourceCluster", "node" to adminNode)), { v -> st.edit { it.with("source" to v) } })
            })
        }
        // 0.96: wo das Modell läuft. Ein Anbieter von außen ist ab Werk aus.
        SettingRow(t("agent.provider"), desc = t("agent.providerHint"), icon = WebIcons["sparkle"], control = {
            Select(providerKind, listOf("" to t("agent.provider.ollama"), "openai" to t("agent.provider.openai"), "claude" to t("agent.provider.claude")), { kind ->
                // Ein anderer Anbieter erbt nie einen getippten Schlüssel oder eine Adresse.
                st.edit { if (kind.isEmpty()) it.without("provider") else it.with("provider" to jobj("kind" to kind)) }
            }, label = t("agent.provider"))
        })
        when {
            providerKind.isNotEmpty() -> {
                Banner(t("agent.providerWarn"), tone = Tone.Warn)
                if (providerKind == "openai") {
                    SettingRow(t("agent.providerUrl"), desc = t("agent.providerUrlHint"), icon = WebIcons["globe"], control = {
                        Field(s.o("provider").str("url"), { v -> st.edit { it.with("provider" to it.o("provider").with("url" to v.trim())) } }, label = t("agent.providerUrl"), placeholder = "https://api.openai.com/v1", singleLine = true, modifier = Modifier.fillMaxWidth())
                    })
                }
                SettingRow(
                    t("agent.providerKey"),
                    desc = t(if (providerKind == "claude") "agent.providerKeyClaude" else "agent.providerKeyHint") + if (s.o("provider").bool("keySet")) "\n" + t("agent.providerKeySet") else "",
                    icon = WebIcons["lock"],
                    control = {
                        Field(s.o("provider").str("key"), { v -> st.edit { it.with("provider" to it.o("provider").with("key" to v.trim())) } }, label = t("agent.providerKey"), password = true, modifier = Modifier.fillMaxWidth())
                    },
                )
            }
            s.str("source") == "cluster" -> {
                val state = st.shareState
                Banner(
                    if (state.isNotEmpty()) tr.t(SHARE_TEXT[state] ?: "agent.shareNotAsked", "node" to adminNode) else tr.t("agent.shareNotAsked", "node" to adminNode),
                    tone = if (state == "allowed") Tone.Ok else Tone.Warn,
                )
            }
            else -> SettingRow(t("agent.ollamaServer"), desc = t("agent.ollamaHint"), icon = WebIcons["globe"], control = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(s.str("ollama"), { v -> st.edit { it.with("ollama" to v) } }, label = t("agent.ollamaServer"), placeholder = "http://192.168.1.5:11434", singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    SmallButton(t("agent.check"), { st.test() })
                }
            })
        }
        val check = st.check
        if (check != null) {
            Banner(
                if (check.bool("reachable")) tr.t("agent.checkOk", "version" to check.str("version"), "models" to check.long("models")) else tr.t("agent.checkFail", "error" to check.str("error")),
                tone = if (check.bool("reachable")) Tone.Ok else Tone.Warn,
            )
        } else if (status?.bool("enabled") == true) {
            StatusChip(
                if (status.bool("reachable")) tr.t(if (status.str("provider").isNotEmpty()) "agent.connectedProvider" else "agent.connected", "version" to status.str("version"), "models" to allModels.size)
                else tr.t("agent.notConnected", "error" to status.str("error")),
                if (status.bool("reachable")) Tone.Ok else Tone.Error,
                Modifier.padding(vertical = 6.dp),
            )
        }
        if (status != null && !status.bool("volume")) Banner(t("agent.noVolume"), tone = Tone.Warn)
        if (s.str("source") == "cluster" && st.shareState != "allowed") {
            SmallButton(tr.t("agent.shareAsk", "node" to adminNode), { st.askAdmin() })
        }
    }

    if (cluster?.bool("admin") == true) {
        SettingGroup(t("agent.sectionNodes"), desc = t("agent.nodesIntro")) {
            val shared = s.a("shared").objects()
            if (shared.isEmpty()) Muted(t("agent.nodesNone"))
            for (sh in shared) {
                SettingRow(sh.str("name"), desc = tr.t("agent.nodeAsked", "date" to Fmt.dateTime(tr, sh.str("asked"))), icon = WebIcons["tailnet"], control = {
                    StatusChip(if (sh.bool("allowed")) t("agent.nodeAllowed") else t("agent.nodePending"), if (sh.bool("allowed")) Tone.Ok else Tone.Warn)
                    SmallButton(t(if (sh.bool("allowed")) "agent.nodeRevoke" else "agent.nodeRelease"), {
                        Guard.require(context, GuardAction.Settings) { st.setShare(sh.str("node"), jobj("allowed" to !sh.bool("allowed"))) }
                    }, kind = if (sh.bool("allowed")) ButtonKind.Normal else ButtonKind.Primary)
                    SmallButton(t("agent.nodeRemove"), { Guard.require(context, GuardAction.Settings) { st.setShare(sh.str("node"), jobj("remove" to true)) } })
                })
            }
        }
    }

    SettingGroup(t("agent.sectionModel")) {
        val caps = chosen?.texts("capabilities").orEmpty()
        val desc = buildList {
            if (chosen != null) add(t("agent.can") + " " + listOf("tools", "thinking", "vision").joinToString("  ") { (if (it in caps) "✓ " else "– ") + tr.t("agent.cap.$it") })
            if (others.isNotEmpty()) add(tr.t("agent.modelsWithoutTools", "names" to others.joinToString(", ") { it.str("name") }))
        }.joinToString("\n").ifEmpty { null }
        SettingRow(t("agent.model"), desc = desc, icon = WebIcons["sparkle"], control = {
            val cur = s.str("model")
            val options = listOf("" to t("agent.modelChoose")) + models.map { it.str("name") to "${it.str("name")} (${gb(it.long("size"))})" } +
                (if (cur.isNotEmpty() && models.none { it.str("name") == cur }) listOf(cur to cur) else emptyList())
            Select(cur, options, { v -> st.edit { it.with("model" to v) } }, label = t("agent.model"))
        })
        // Inhaber 16.11.2026: ein eigener Name für das Modell; der Chat zeigt ihn.
        val named = s.str("model")
        if (named.isNotEmpty()) {
            SettingRow(t("agent.modelName"), desc = t("agent.modelNameHint"), icon = WebIcons["edit"], control = {
                Field(s.o("modelNames").str(named), { v -> st.edit { it.with("modelNames" to it.o("modelNames").with(named to v.take(40))) } }, label = t("agent.modelName"), placeholder = named, singleLine = true, modifier = Modifier.fillMaxWidth())
            })
        }
        val levels = chosen?.texts("levels").orEmpty()
        if (chosen != null && levels.isNotEmpty()) {
            SettingRow(t("agent.thinkDefault"), desc = t("agent.thinkHint"), icon = WebIcons["bulb"], control = {
                Segments(s.str("think").ifEmpty { chosen.str("defaultLevel").ifEmpty { levels.first() } }, levels.map { it to levelLabel(it, tr.de) }, { v -> st.edit { it.with("think" to v) } })
            })
        }
        // Nur ein Ollama-Server hat eine Kontextlänge und ein geladenes Modell.
        if (providerKind.isEmpty()) {
            val contextDesc = listOfNotNull(
                if (modelContext > 0) tr.t("agent.contextModel", "tokens" to tokens(modelContext)) else null,
                if (runningContext > 0) tr.t("agent.contextNow", "tokens" to tokens(runningContext)) else tr.t("agent.contextUnknown"),
                breakIds(tr.t("agent.contextHint")),
                if (s.long("context") > 65536) tr.t("agent.contextVram") else null,
            ).joinToString(" ")
            SettingRow(t("agent.context"), desc = contextDesc, icon = WebIcons["memory"], control = {
                Select(s.long("context"), contexts.map { c -> c to if (c == 0L) tr.t("agent.contextOllama") else tokens(c) + " " + tr.t("agent.tokensWord") }, { v -> st.edit { it.with("context" to v) } }, label = t("agent.context"))
            })
        }
        SettingRow(t("agent.maxSteps"), desc = t("agent.maxStepsHint"), icon = WebIcons["list"], control = {
            NumberField(s.long("maxSteps").takeIf { s.has("maxSteps") }, { v -> st.edit { it.with("maxSteps" to (v ?: 0L).coerceIn(0, 100)) } }, label = t("agent.maxSteps"), allowEmpty = true)
        })
        SettingRow(t("agent.maxMinutes"), desc = t("agent.maxMinutesHint"), icon = WebIcons["clock"], control = {
            NumberField(s.long("maxMinutes").takeIf { s.has("maxMinutes") }, { v -> st.edit { it.with("maxMinutes" to (v ?: 0L).coerceIn(0, 600)) } }, label = t("agent.maxMinutes"), allowEmpty = true)
        })
        // 1.01: Chats eines Kontos zugleich; Ollama nur mit OLLAMA_NUM_PARALLEL nebeneinander.
        val maxRuns = s.long("maxRuns")
        val runsDesc = t("agent.runs.settingHint") + if (maxRuns != 1L) "\n" + when {
            providerKind.isNotEmpty() -> tr.t("agent.runs.providerHint")
            maxRuns > 1 -> tr.t("agent.runs.ollamaHint", "n" to maxRuns)
            else -> tr.t("agent.runs.ollamaHintAny")
        } else ""
        SettingRow(t("agent.runs.setting"), desc = runsDesc, icon = WebIcons["chat"], control = {
            Select(maxRuns, (listOf(0L, 1L, 2L, 3L, 4L) + listOf(maxRuns)).distinct().sorted().map { n -> n to when (n) { 0L -> tr.t("agent.runs.none"); 1L -> tr.t("agent.runs.one"); else -> tr.t("agent.runs.many", "n" to n) } }, { v -> st.edit { it.with("maxRuns" to v) } }, label = t("agent.runs.setting"))
        })
        SettingRow(t("agent.lazyTools"), desc = t("agent.lazyToolsHint"), icon = WebIcons["tool"], control = {
            OnOff(!s.bool("allTools"), { v -> st.edit { it.with("allTools" to !v) } }, label = t("agent.lazyTools"))
        })
        SettingRow(t("agent.delegate"), desc = t("agent.delegateHint"), icon = WebIcons["users"], control = {
            OnOff(!s.bool("noDelegate"), { v -> st.edit { it.with("noDelegate" to !v) } }, label = t("agent.delegate"))
        })
        if (providerKind.isEmpty()) {
            SettingRow(t("agent.keepAlive"), desc = t("agent.keepAliveHint"), icon = WebIcons["pause"], control = {
                val cur = s.str("keepAlive")
                val options = listOf("" to t("agent.keepAliveOllama")) + listOf("5m", "30m", "2h", "-1").map { it to tr.t("agent.keepAlive.$it") } +
                    (if (cur.isNotEmpty() && cur !in listOf("5m", "30m", "2h", "-1")) listOf(cur to cur) else emptyList())
                Select(cur, options, { v -> st.edit { it.with("keepAlive" to v) } }, label = t("agent.keepAlive"))
            })
        }
        if (s.str("source") != "cluster" && providerKind.isEmpty()) {
            val pull = st.pull
            val pullLine = pull?.str("model")?.takeIf { it.isNotEmpty() }?.let { m ->
                val total = pull.long("total")
                "$m: " + pull.str("error").ifEmpty { pull.str("status") } + if (total > 0) " · ${Math.round(pull.long("completed") * 100.0 / total)} %" else ""
            }
            SettingRow(t("agent.pull"), desc = t("agent.pullHint") + (pullLine?.let { "\n$it" } ?: ""), icon = WebIcons["download"], control = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(st.pullName, { st.pullName = it }, label = t("agent.pull"), placeholder = "qwen3:8b", singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    SmallButton(t("agent.pullStart"), { st.startPull() }, enabled = pull?.bool("running") != true && st.pullName.isNotBlank())
                }
            })
        }
    }

    SettingGroup(t("agent.sectionMedia")) {
        val picturesDesc = listOfNotNull(
            if (chosen != null && "vision" in chosen.texts("capabilities")) "✓ " + tr.t("agent.picturesMain", "model" to chosen.str("name")) else tr.t("agent.picturesNone"),
            if (vram > 0) tr.t("agent.vramNow", "gb" to String.format(java.util.Locale.ROOT, "%.1f", vram / 1e9).replace('.', if (tr.de) ',' else '.')) else null,
        ).joinToString("\n")
        SettingRow(t("agent.pictures"), desc = picturesDesc, icon = WebIcons["image"])
        SettingRow(t("agent.visionOwn"), icon = WebIcons["eye"], control = {
            OnOff(s.o("vision").bool("enabled"), { v -> st.edit { it.with("vision" to it.o("vision").with("enabled" to v)) } }, label = t("agent.visionOwn"))
        })
        if (s.o("vision").bool("enabled")) {
            SettingRow(t("agent.visionModel"), desc = t("agent.visionHint"), icon = WebIcons["eye"], control = {
                val cur = s.o("vision").str("model")
                val options = listOf("" to t("agent.modelChoose")) + visionModels.map { it.str("name") to "${it.str("name")} (${gb(it.long("size"))})" } +
                    (if (cur.isNotEmpty() && visionModels.none { it.str("name") == cur }) listOf(cur to cur) else emptyList())
                Select(cur, options, { v -> st.edit { it.with("vision" to it.o("vision").with("model" to v)) } }, label = t("agent.visionModel"))
            })
        }
        if (st.nasHear) {
            // Die Sprachdienste dieses NAS hören für den Agenten.
            SettingRow(t("agent.voiceNas"), desc = t("agent.voiceNasHint"), icon = WebIcons["mic"], control = {
                SmallButton(t("agent.voiceNasOpen"), { env.open("/settings/speech") })
            })
        } else {
            SettingRow(t("agent.voiceOn"), desc = t("agent.voiceHint"), icon = WebIcons["mic"], control = {
                OnOff(s.o("voice").bool("enabled"), { v -> st.edit { it.with("voice" to it.o("voice").with("enabled" to v)) } }, label = t("agent.voiceOn"))
            })
            if (s.o("voice").str("url").isEmpty()) {
                val app = st.whisperApp
                SettingRow(t("agent.voice"), desc = if (app == null) t("agent.whisperSetupHint") else null, icon = WebIcons["mic"], control = {
                    if (app != null) SmallButton(tr.t("agent.whisperUse", "name" to app.first, "port" to app.second), { st.useWhisperApp() }, kind = ButtonKind.Primary)
                    else SmallButton(t("agent.whisperSetup"), { env.open("/settings/speech") }, kind = ButtonKind.Primary)
                })
            }
            if (s.o("voice").bool("enabled")) {
                SettingRow(t("agent.voiceUrl"), icon = WebIcons["globe"], control = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field(s.o("voice").str("url"), { v -> st.edit { it.with("voice" to it.o("voice").with("url" to v)) } }, label = t("agent.voiceUrl"), placeholder = "http://192.168.1.5:8000", singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                        SmallButton(t("agent.voiceCheck"), { st.checkVoice() })
                    }
                })
                SettingRow(t("agent.voiceModel"), icon = WebIcons["mic"], control = {
                    Field(s.o("voice").str("model"), { v -> st.edit { it.with("voice" to it.o("voice").with("model" to v)) } }, label = t("agent.voiceModel"), placeholder = "Systran/faster-whisper-large-v3", singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                })
                st.voiceCheck?.let { vc ->
                    Banner(
                        if (vc.bool("reachable")) tr.t("agent.voiceOk", "models" to vc.texts("models").joinToString(", ").ifEmpty { "–" }) else tr.t("agent.voiceDown", "error" to vc.str("error")),
                        tone = if (vc.bool("reachable")) Tone.Ok else Tone.Error,
                    )
                }
            }
        }
        // Die eigene Websuche (0.92): ein SearXNG, meist die App auf diesem NAS.
        SettingRow(t("agent.searx"), desc = tr.t("agent.searxHint", "example" to searxExample), icon = WebIcons["search"], control = {
            Field(s.o("search").str("url"), { v -> st.edit { it.with("search" to jobj("url" to v.trim())) } }, label = t("agent.searx"), placeholder = searxExample, singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        })
        SettingRow(t("agent.snapshots"), desc = t("agent.snapshotsHint"), icon = WebIcons["camera"], control = {
            OnOff(!s.bool("noSnapshots"), { v -> st.edit { it.with("noSnapshots" to !v) } }, label = t("agent.snapshots"))
        })
        SettingRow(t("agent.code"), desc = t("agent.codeHint"), icon = WebIcons["terminal"], control = {
            OnOff(s.o("code").bool("enabled"), { v -> st.edit { it.with("code" to it.o("code").with("enabled" to v)) } }, label = t("agent.code"))
        })
        if (s.o("code").bool("enabled")) {
            SettingRow(t("agent.codeTimeout"), icon = WebIcons["clock"], control = {
                NumberField(s.o("code").long("timeout", 60), { v -> st.edit { it.with("code" to it.o("code").with("timeout" to (v ?: 0L))) } }, label = t("agent.codeTimeout"), allowEmpty = true)
            })
            SettingRow(t("agent.codeMemory"), icon = WebIcons["memory"], control = {
                NumberField(s.o("code").long("memory", 512), { v -> st.edit { it.with("code" to it.o("code").with("memory" to (v ?: 0L))) } }, label = t("agent.codeMemory"), allowEmpty = true)
            })
        }
    }

    McpConnections(st)
}

/** Verbindungen zu MCP-Servern (0.93): Werkzeuge anderer Programme; das Token bleibt auf dem NAS. */
@Composable
private fun McpConnections(st: AgentState) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf(mapOf<Int, JsonObject?>()) }
    val s = st.settings ?: return
    val connections = s.a("mcp").objects()
    fun setConnection(i: Int, change: (JsonObject) -> JsonObject) {
        st.edit { it.with("mcp" to JsonArray(connections.mapIndexed { n, c -> if (n == i) change(c) else c })) }
    }
    SettingGroup(t("agent.mcp.title"), desc = t("agent.mcp.hint")) {
        connections.forEachIndexed { i, c ->
            val name = c.str("name").ifEmpty { tr.t("agent.mcp.name") }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(c.str("name"), { v -> setConnection(i) { it.with("name" to v.take(60)) } }, label = t("agent.mcp.name"), placeholder = t("agent.mcp.name"), singleLine = true, modifier = Modifier.weight(1f))
                    OnOff(c.bool("enabled"), { v -> setConnection(i) { it.with("enabled" to v) } }, label = tr.t("agent.mcp.on", "name" to name))
                }
                Field(c.str("url"), { v -> setConnection(i) { it.with("url" to v.trim().take(300)) } }, label = t("agent.mcp.url"), placeholder = "https://n8n.example/mcp", singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                if (c.bool("tokenSet") && !c.bool("clearToken")) Muted(t("agent.mcp.tokenKept"))
                Field(c.str("token"), { v -> setConnection(i) { it.with("token" to v, "clearToken" to false) } }, label = t("agent.mcp.token"), placeholder = t("agent.mcp.token"), password = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val busy = checks.containsKey(i) && checks[i] == null
                    SmallButton(t("agent.mcp.check"), {
                        checks = checks + (i to null)
                        scope.launch {
                            val r = try {
                                env.api.post("agent/mcp/check", jobj("id" to c.str("id"), "name" to c.str("name"), "url" to c.str("url"), "token" to c.str("token"))) as? JsonObject
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                jobj("reachable" to false, "error" to tr.errorOf(e))
                            }
                            checks = checks + (i to (r ?: jobj("reachable" to false)))
                        }
                    }, enabled = c.str("url").isNotEmpty() && !busy)
                    if (c.bool("tokenSet") && !c.bool("clearToken") && c.str("token").isEmpty()) {
                        SmallButton(t("agent.mcp.tokenClear"), { setConnection(i) { it.with("clearToken" to true) } })
                    }
                    SmallButton(tr.t("agent.mcp.remove", "name" to name), {
                        st.edit { it.with("mcp" to JsonArray(connections.filterIndexed { n, _ -> n != i })) }
                        checks = emptyMap()
                    }, icon = WebIcons["trash"])
                }
                checks[i]?.let { r ->
                    Banner(
                        if (r.bool("reachable")) tr.t("agent.mcp.ok", "count" to r.texts("tools").size, "tools" to r.texts("tools").take(12).joinToString(", ").ifEmpty { "–" }) else tr.t("agent.mcp.down", "error" to r.str("error")),
                        tone = if (r.bool("reachable")) Tone.Ok else Tone.Error,
                    )
                }
            }
            io.github.ra5on.titanos.ui.components.Divider()
        }
        if (connections.size < 8) {
            SmallButton(t("agent.mcp.add"), { st.edit { it.with("mcp" to JsonArray(connections + jobj("name" to "", "url" to "", "enabled" to true))) } }, icon = WebIcons["plus"])
        }
    }
}
