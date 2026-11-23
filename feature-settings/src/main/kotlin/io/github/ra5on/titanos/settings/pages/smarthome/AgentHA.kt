package io.github.ra5on.titanos.settings.pages.smarthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

// Gerätearten ohne lohnende Dienste (homeassistant.domainLevels).
private val HA_READ_ONLY = setOf("sensor", "binary_sensor", "weather", "person", "device_tracker", "sun", "zone")
private val HA_NAMED = setOf(
    "alarm_control_panel", "automation", "binary_sensor", "button", "climate", "cover", "device_tracker", "fan", "humidifier",
    "input_boolean", "input_button", "input_number", "input_select", "light", "lock", "media_player", "person", "scene", "script",
    "sensor", "sun", "switch", "vacuum", "valve", "water_heater", "weather", "zone",
)
private val HA_ALWAYS = listOf("automation", "scene", "script")
private val HA_ASKS_FIRST = setOf("lock", "alarm_control_panel", "automation")

/**
 * Home Assistant (HomeAssistantTab.svelte): was der Agent dort darf und
 * welche Geräte er sieht (AgentHARelease), Assist (AssistCard), Regeln und
 * an wen er Mitteilungen schickt.
 */
@Composable
internal fun AgentHaTab(st: AgentState) {
    val tr = LocalTr.current
    if (st.settings == null) return
    AgentHaRelease(st)
    AssistCard(st)
    SettingGroup(t("agent.haRules"), tools = {
        SmallButton(t("agent.haRuleAdd"), { st.haRules = st.haRules + HaRule("entity:", "ask") }, icon = WebIcons["plus"])
    }) {
        val modes = modeOptions()
        st.haRules.forEachIndexed { i, r ->
            if (!r.key.startsWith("domain:")) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(r.key, { v -> st.haRules = st.haRules.mapIndexed { n, x -> if (n == i) x.copy(key = v) else x } }, label = t("agent.haRules"), placeholder = "entity:light.flur · area:kueche", mono = true, singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Segments(r.mode, modes, { v -> st.haRules = st.haRules.mapIndexed { n, x -> if (n == i) x.copy(mode = v) else x } })
                        io.github.ra5on.titanos.ui.components.IconButton(WebIcons["trash"], tr.t("common.delete"), { st.haRules = st.haRules.filterIndexed { n, _ -> n != i } }, size = 20.dp)
                    }
                }
                Divider()
            }
        }
        Muted(t("agent.haRulesHint"))
    }
    SettingGroup(t("agent.sectionHA")) {
        SettingRow(t("agent.haNotify"), desc = t("agent.haNotifyHint"), icon = WebIcons["bell"], control = {
            Field(st.haNotify, { st.haNotify = it }, label = t("agent.haNotify"), placeholder = "notify.mobile_app_iphone", mono = true, singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        })
    }
}

/** Was der Agent in Home Assistant darf und welche Geräte er sieht, je Verbindung. */
@Composable
private fun AgentHaRelease(st: AgentState) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val s = st.settings ?: return
    val policy = s.o("policy")
    val modes = modeOptions()
    val homeTools = st.tools.filter { it.area == "home" }
    val looking = homeTools.filter { it.kind == "read" }
    val switching = homeTools.filter { it.kind != "read" }
    fun modeOf(list: List<ToolView>): String = list.map { toolMode(policy, it) }.toSet().singleOrNull() ?: ""
    fun setMode(list: List<ToolView>, mode: String) = st.setPolicy { p -> p.with("tools" to JsonObject(p.o("tools") + list.associate { it.name to kotlinx.serialization.json.JsonPrimitive(mode) })) }

    var haConn by remember { mutableStateOf("main") }
    val conns = io.github.ra5on.titanos.settings.common.rememberLoad {
        runCatching { env.api.obj("homeassistant/connections").a("connections").objects().filter { it.bool("active") || it.str("id") == "main" } }.getOrDefault(emptyList())
    }
    var picking by remember(haConn) { mutableStateOf(false) }
    var loadRound by remember { mutableStateOf(0) }
    val ha = s.o("homeassistant")
    val release = if (haConn == "main") ha.o("release") else ha.o("releases").o(haConn)
    val scope = when {
        release.bool("all") -> "all"
        picking || release.texts("domains").isNotEmpty() || release.texts("entities").isNotEmpty() -> "some"
        else -> "none"
    }
    fun setRelease(change: (JsonObject) -> JsonObject) = st.setHa { h ->
        if (haConn == "main") h.with("release" to change(h.o("release")))
        else h.with("releases" to h.o("releases").with(haConn to change(h.o("releases").o(haConn))))
    }
    fun setScope(next: String) {
        picking = next == "some"
        setRelease { r ->
            var x = if (next == "all") r.with("all" to true) else r.without("all")
            if (next == "none") x = x.with("domains" to emptyList<String>(), "entities" to emptyList<String>())
            x
        }
    }
    fun toggleRelease(kind: String, value: String) = setRelease { r -> r.with(kind to toggle(r.texts(kind), value)) }

    // Die Listen kommen von selbst, sobald eine Auswahl gewollt ist.
    var haDomains by remember(haConn) { mutableStateOf(listOf<String>()) }
    var haEntities by remember(haConn) { mutableStateOf<List<JsonObject>?>(null) }
    var haError by remember(haConn) { mutableStateOf("") }
    var haQuery by remember { mutableStateOf("") }
    LaunchedEffect(haConn, scope != "none", loadRound) {
        if (scope == "none" || haEntities != null) return@LaunchedEffect
        haError = ""
        val q = if (haConn == "main") "" else "?connection=" + URLEncoder.encode(haConn, "UTF-8")
        try {
            val set = env.api.obj("homeassistant/settings$q")
            haDomains = set.texts("agentDomains").ifEmpty { set.texts("domains") }
            haEntities = env.api.obj("homeassistant/entities$q").a("entities").objects()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            haError = tr.errorOf(e)
        }
    }

    SettingGroup(t("agent.haRights"), desc = t("agent.haRightsHint")) {
        if (homeTools.isEmpty()) {
            Banner(t("agent.haNoConnection"), tone = Tone.Warn, action = t("agent.haOpenConnection"), onAction = { env.open("/settings/homeassistant") })
        } else {
            SettingRow(t("agent.haLook"), desc = t("agent.haLookHint"), icon = WebIcons["eye"], control = {
                Segments(modeOf(looking), modes, { setMode(looking, it) })
            })
            SettingRow(t("agent.haSwitch"), desc = t("agent.haSwitchHint"), icon = WebIcons["power"], control = {
                Segments(modeOf(switching), modes, { setMode(switching, it) })
            })
        }
    }

    val connList = conns.value.orEmpty()
    SettingGroup(
        t("agent.haRelease"),
        desc = t("agent.haReleaseHint"),
        tools = if (connList.size > 1) ({
            Select(haConn, connList.map { it.str("id") to it.str("name").ifEmpty { tr.t("ha.conn.first") } }, { haConn = it; haQuery = "" }, label = t("notify.haConnection"))
        }) else null,
    ) {
        SettingRow(
            t("agent.haScope"),
            desc = when (scope) {
                "all" -> tr.t("agent.haScopeAllHint")
                "none" -> tr.t("agent.haScopeNoneHint")
                else -> tr.t("agent.haReleased", "domains" to release.texts("domains").size, "entities" to release.texts("entities").size)
            },
            icon = WebIcons["home"],
            control = {
                Segments(scope, listOf("none" to t("agent.haScopeNone"), "some" to t("agent.haScopeSome"), "all" to t("agent.haScopeAll")), { setScope(it) })
            },
        )
        if (scope == "none") Banner(t("agent.haScopeNoneWarn"), tone = Tone.Warn)
        if (scope != "none") {
            val entities = haEntities
            when {
                haError.isNotEmpty() -> Banner(haError, tone = Tone.Warn, action = t("agent.haLoad"), onAction = { loadRound++ })
                entities == null -> LoadingLine(t("common.loading"))
                else -> {
                    val perDomain = entities.groupingBy { it.str("domain") }.eachCount()
                    val domainsShown = haDomains.filter { (perDomain[it] ?: 0) > 0 || it in release.texts("domains") || it in HA_ALWAYS } +
                        (if (scope == "all") perDomain.keys.filter { it !in haDomains }.sorted() else emptyList())
                    fun domainName(d: String) = if (d in HA_NAMED) tr.t("agent.haDomain.$d") else d
                    fun sees(d: String) = release.bool("all") || d in release.texts("domains")
                    fun canSwitch(d: String) = d in haDomains && d !in HA_READ_ONLY
                    fun switchMode(d: String): String {
                        st.haRules.firstOrNull { it.key.trim() == "domain:$d" }?.let { return it.mode }
                        val base = modeOf(switching).ifEmpty { "ask" }
                        return if (base == "allow" && d in HA_ASKS_FIRST) "ask" else base
                    }
                    fun setSwitch(d: String, mode: String) {
                        val i = st.haRules.indexOfFirst { it.key.trim() == "domain:$d" }
                        st.haRules = if (i >= 0) st.haRules.mapIndexed { n, r -> if (n == i) r.copy(mode = mode) else r } else st.haRules + HaRule("domain:$d", mode)
                    }
                    Plain(t("agent.haKinds"), Modifier.padding(top = 10.dp), bold = true)
                    Muted(t("agent.haKindsHint"))
                    if (domainsShown.isEmpty()) Muted(t("agent.haNoEntities"))
                    for (d in domainsShown) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Plain(domainName(d), bold = true)
                            Muted("$d · " + tr.t("agent.haDomainCount", "count" to (perDomain[d] ?: 0)))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Plain(t("agent.haSee"))
                                    OnOff(sees(d), { toggleRelease("domains", d) }, label = "${domainName(d)}: ${t("agent.haSee")}", enabled = scope != "all")
                                }
                                if (!canSwitch(d)) Muted(t("agent.haReadOnly"))
                                else Segments(switchMode(d), listOf("allow" to t("agent.haYes"), "ask" to t("agent.modeAsk"), "deny" to t("agent.haNo")), { setSwitch(d, it) }, enabled = sees(d))
                            }
                        }
                        Divider()
                    }
                    if (scope == "some") {
                        Plain(t("agent.haSingle"), Modifier.padding(top = 10.dp), bold = true)
                        Field(haQuery, { haQuery = it }, label = t("agent.haSearch"), placeholder = t("agent.haSearch"), singleLine = true, modifier = Modifier.fillMaxWidth())
                        val q = haQuery.trim().lowercase()
                        val shown = entities.filter { e -> q.isEmpty() || "${e.str("id")} ${e.str("name")} ${e.str("area")}".lowercase().contains(q) }.take(200)
                        if (shown.isEmpty()) Muted(t("agent.haNoEntities"))
                        for (e in shown) {
                            val viaDomain = e.str("domain") in release.texts("domains")
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Plain(e.str("name").ifEmpty { e.str("id") })
                                    Mono(e.str("id") + (e.str("area").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), muted = true)
                                }
                                OnOff(viaDomain || e.str("id") in release.texts("entities"), { toggleRelease("entities", e.str("id")) }, label = e.str("name").ifEmpty { e.str("id") }, enabled = !viaDomain)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val ASSIST_ICONS = mapOf(
    "home" to "home", "system" to "activity", "storage" to "disk", "backups" to "archive", "docker" to "box", "vms" to "chip",
    "files" to "folder", "shares" to "folder", "accounts" to "users", "network" to "shield", "updates" to "download", "plugins" to "grid",
    "web" to "globe", "memory" to "bulb", "plan" to "clock", "code" to "zap", "calendar" to "calendar", "contacts" to "person",
)
private val ASSIST_ON_OFF = setOf("web", "plan", "code")

/**
 * Assist (AssistCard.svelte, PLAN 91): Schalter, Profil, Adressen für die
 * Ollama-Integration von Home Assistant, Schlüssel, letzte Anfrage und was
 * eine Frage aus Home Assistant je Bereich nutzen darf. Der Schlüssel hat
 * eigene Wege und reist nie mit den Einstellungen.
 */
@Composable
private fun AssistCard(st: AgentState) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = st.settings ?: return
    val ha = s.o("homeassistant")
    val assist = ha.bool("assist")
    var info by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf("") }
    var consentBusy by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(0) }

    LaunchedEffect(assist, round) {
        if (!assist) return@LaunchedEffect
        try {
            info = env.api.obj("agent/assist")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
    }
    fun act(block: suspend () -> Unit) {
        error = ""
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
        }
    }
    fun copy(what: String, text: String) {
        if (copyText(context, text)) {
            copied = what
            scope.launch { delay(1800); if (copied == what) copied = "" }
        }
    }
    fun newKey() = Guard.require(context, GuardAction.Settings) {
        act {
            key = env.api.post("agent/assist/key").o().str("key")
            round++
        }
    }

    SettingGroup(t("agent.haAssist"), desc = t("agent.assist.intro")) {
        if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
        SettingRow(t("agent.assist.on"), desc = t("agent.assist.onDesc"), icon = WebIcons["mic"], control = {
            OnOff(assist, { v -> st.setHa { it.with("assist" to v) } }, label = t("agent.assist.on"))
        })
        if (assist) {
            val i = info
            SettingRow(
                t("agent.assist.profile"),
                desc = t("agent.assist.profileDesc"),
                icon = WebIcons["users"],
                summary = i?.let { tr.t("agent.assist.profileCount", "memories" to it.long("memories"), "chats" to it.long("chats")) },
                onClick = { env.open("/agent?profile=assist") },
            )
            SettingRow(t("agent.assist.addresses"), desc = t("agent.assist.addressesDesc"), icon = WebIcons["external"], id = "assist-addresses", startOpen = true) {
                for (a in i?.a("addresses")?.objects().orEmpty()) {
                    val url = a.str("url")
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Mono(url)
                        Muted(tr.t(if (a.str("kind") in setOf("lan", "tailscale", "netbird", "ddns", "domain")) "agent.assist.addr.${a.str("kind")}" else "agent.assist.addr.domain"))
                        SmallButton(if (copied == url) t("agent.copiedShort") else t("agent.copy"), { copy(url, url) }, icon = WebIcons[if (copied == url) "check" else "copy"])
                    }
                }
                Muted(t("agent.assist.addressesHttps"))
            }
            SettingRow(t("agent.assist.key"), desc = if (i?.bool("keySet") == true) t("agent.assist.keyDesc") else t("agent.assist.keyNone"), icon = WebIcons["key"], control = {
                if (i?.bool("keySet") == true) {
                    Mono(key.ifEmpty { tr.t("agent.assist.keyHidden") })
                    SmallButton(if (key.isNotEmpty()) t("agent.assist.keyHide") else t("agent.assist.keyShow"), {
                        if (key.isNotEmpty()) key = "" else act { key = env.api.obj("agent/assist/key").str("key") }
                    }, icon = WebIcons["eye"])
                    SmallButton(if (copied == "key") t("agent.copiedShort") else t("agent.copy"), {
                        act { copy("key", key.ifEmpty { env.api.obj("agent/assist/key").str("key") }) }
                    }, icon = WebIcons[if (copied == "key") "check" else "copy"])
                    SmallButton(t("agent.assist.keyNew"), {
                        ov.confirm(tr.t("agent.assist.keyNewTitle"), tr.t("agent.assist.keyNewText"), action = tr.t("agent.assist.keyNewDo"), cancel = tr.t("common.cancel")) { newKey() }
                    }, icon = WebIcons["refresh"])
                } else {
                    SmallButton(t("agent.assist.keyMake"), { newKey() }, kind = ButtonKind.Primary, enabled = i != null)
                }
            })
            val last = i?.o("last")?.takeIf { i.has("last") }
            val lastText = when {
                last == null -> tr.t("agent.assist.lastNone")
                !last.bool("ok") -> tr.t("agent.assist.lastRefused", "when" to Fmt.dateTime(tr, last.str("at")), "from" to last.str("from"), "reason" to tr.t(if (last.str("reason") == "key") "agent.assist.reason.key" else "agent.assist.reason.address"))
                else -> {
                    val seconds = String.format(java.util.Locale.ROOT, "%.1f", last.long("millis") / 1000.0).replace('.', ',')
                    if (last.str("question").isEmpty()) tr.t("agent.assist.lastOkNoQuestion", "when" to Fmt.dateTime(tr, last.str("at")), "from" to last.str("from"), "seconds" to seconds)
                    else tr.t("agent.assist.lastOk", "when" to Fmt.dateTime(tr, last.str("at")), "from" to last.str("from"), "question" to last.str("question"), "seconds" to seconds)
                }
            }
            SettingRow(t("agent.assist.last"), desc = lastText, icon = WebIcons["activity"], control = {
                if (last != null) StatusChip(if (last.bool("ok")) t("agent.assist.connected") else t("agent.assist.refused"), if (last.bool("ok")) Tone.Ok else Tone.Warn)
                io.github.ra5on.titanos.ui.components.IconButton(WebIcons["refresh"], tr.t("ops.refresh"), { round++ }, size = 20.dp)
            })
            SettingRow(t("agent.assist.setup"), desc = t("agent.assist.setupDesc"), icon = WebIcons["info"])
        }
    }

    val i = info
    if (assist && i != null) {
        fun level(area: String) = ha.o("assistAreas").str(area).ifEmpty { i.o("levels").str(area).ifEmpty { "deny" } }
        fun dataLevel(area: String) = when (ha.o("assistData").str(area)) {
            "read" -> "read"
            "deny" -> "deny"
            else -> i.o("dataLevels").str(area).ifEmpty { "deny" }
        }
        fun waitText(area: String) = if (dataLevel(area) != "read" || i.o("consent").bool(area)) "" else if (i.bool("isActor")) tr.t("agent.assist.consent.waitingMe") else tr.t("agent.assist.consent.waiting", "account" to i.str("actor"))
        val three = listOf("deny" to t("agent.assist.deny"), "read" to t("agent.assist.read"), "change" to t("agent.assist.change"))
        val two = listOf("deny" to t("agent.assist.deny"), "change" to t("agent.assist.allow"))
        val readOnly = listOf("deny" to t("agent.assist.deny"), "read" to t("agent.assist.read"))
        SettingGroup(t("agent.assist.rights"), desc = t("agent.assist.rightsDesc"), tools = {
            SmallButton(t("agent.assist.denyAll"), {
                st.setHa { h ->
                    var x = h.with("assistAreas" to i.texts("areas").associateWith { "deny" })
                    if (i.texts("dataAreas").isNotEmpty()) x = x.with("assistData" to i.texts("dataAreas").associateWith { "deny" })
                    x
                }
            })
            SmallButton(t("agent.assist.defaults"), { st.setHa { it.without("assistAreas", "assistData") } })
        }) {
            for (area in i.texts("areas")) {
                SettingRow(tr.or("agent.assist.area.$area", area), desc = tr.or("agent.assist.areaDesc.$area", ""), icon = WebIcons[ASSIST_ICONS[area] ?: "box"], control = {
                    val onOff = area in ASSIST_ON_OFF
                    Segments(if (onOff && level(area) == "read") "deny" else level(area), if (onOff) two else three, { v -> st.setHa { it.with("assistAreas" to it.o("assistAreas").with(area to v)) } })
                })
            }
            for (area in i.texts("dataAreas")) {
                val base = tr.or("agent.assist.areaDesc.$area", "")
                val wait = waitText(area)
                SettingRow(tr.or("agent.assist.area.$area", area), desc = if (wait.isNotEmpty()) "$base – $wait" else base, icon = WebIcons[ASSIST_ICONS[area] ?: "box"], control = {
                    Segments(dataLevel(area), readOnly, { v -> st.setHa { it.with("assistData" to it.o("assistData").with(area to if (v == "read") "read" else "deny")) } })
                })
            }
        }
        // Kalender und Kontakte gelten nur mit der Zustimmung des handelnden Kontos selbst (S-23).
        val showConsent = i.bool("isActor") || i.o("myConsent").values.any { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" }
        if (showConsent) {
            SettingGroup(
                t("agent.assist.consent.title"),
                desc = if (i.bool("isActor")) tr.t("agent.assist.consent.desc") else tr.t("agent.assist.consent.notActor", "actor" to i.str("actor")),
            ) {
                for (area in i.texts("dataAreas")) {
                    val given = i.o("myConsent").bool(area)
                    val title = when (area) {
                        "calendar" -> tr.t("agent.assist.consent.calendar")
                        "contacts" -> tr.t("agent.assist.consent.contacts")
                        else -> area
                    }
                    SettingRow(title, desc = t(if (given) "agent.assist.consent.given" else "agent.assist.consent.notGiven"), icon = WebIcons[ASSIST_ICONS[area] ?: "box"], control = {
                        OnOff(given, { v ->
                            Guard.require(context, GuardAction.Settings) {
                                consentBusy = true
                                act {
                                    try {
                                        env.api.put("agent/assist/consent", i.o("myConsent").with(area to v))
                                        round++
                                    } finally {
                                        consentBusy = false
                                    }
                                }
                            }
                        }, label = title, enabled = !consentBusy)
                    })
                }
            }
        }
    }
}
