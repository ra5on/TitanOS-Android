package io.github.ra5on.titanos.settings.pages.smarthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Selten geänderte Bereiche warten hinter „weitere Bereiche“ (PLAN 68). */
private val RARE = setOf("backups", "storage", "shares", "accounts", "plugins", "home", "history", "nvr", "code")
private val AREA_HINT = mapOf("photos" to "agent.area.photosHint", "history" to "agent.area.historyHint", "nvr" to "agent.area.nvrHint")
private val KIND_LABEL = mapOf("read" to "agent.kindRead", "create" to "agent.kindCreate", "change" to "agent.kindChange")

@Composable
internal fun modeOptions() = listOf("allow" to t("agent.modeAllow"), "ask" to t("agent.modeAsk"), "deny" to t("agent.modeDeny"))

/**
 * Rechte (RightsTab.svelte): Schnellwahl, Bereiche mit ihren Werkzeugen,
 * Kalender und Kontakte auf zwei Achsen, Aktionen für Mail, Termine,
 * Kontakte und Erinnerungen, und Regeln für Ordner.
 */
@Composable
internal fun AgentRightsTab(st: AgentState) {
    val tr = LocalTr.current
    val s = st.settings ?: return
    val policy = s.o("policy")
    val tools = st.tools
    val modes = modeOptions()
    var allAreas by rememberSaveable { mutableStateOf(false) }

    fun setArea(area: String, mode: String) = st.setPolicy { p ->
        // Die einzelnen Werkzeuge des Bereichs folgen ihm wieder.
        val names = tools.filter { it.area == area }.map { it.name }.toSet()
        p.with("areas" to p.o("areas").with(area to mode), "tools" to JsonObject(p.o("tools").filterKeys { it !in names }))
    }
    fun setTool(name: String, mode: String) = st.setPolicy { it.with("tools" to it.o("tools").with(name to mode)) }
    fun setData(area: String, axis: String, mode: String) = st.setPolicy { p ->
        // Beide Achsen werden geschrieben: die andere behält, was sie jetzt zeigt.
        val (r, w) = dataRight(p, area)
        val now = jobj("read" to r, "write" to w)
        p.with("dataRights" to p.o("dataRights").with(area to now.with(axis to mode)))
    }

    SettingGroup(t("agent.preset")) {
        SettingRow(t("agent.presetRow"), icon = WebIcons["zap"], control = {
            Segments(presetOf(policy), listOf("read" to t("agent.presetRead"), "standard" to t("agent.presetStandard"), "all" to t("agent.presetAll"), "custom" to t("agent.presetCustom")), { p ->
                if (p != "custom") st.setPolicy { old ->
                    val next = jobj("preset" to p, "folders" to old.a("folders"))
                    old["webFree"]?.let { next.with("webFree" to it) } ?: next
                }
            })
        })
        // 0.94: Webwerkzeuge fragen, sobald ein Chat Dateien, Geräte oder Seiten gelesen hat.
        SettingRow(t("agent.webFree"), desc = t("agent.webFreeHint"), icon = WebIcons["globe"], control = {
            OnOff(policy.bool("webFree"), { v -> st.setPolicy { it.with("webFree" to v) } }, label = t("agent.webFree"))
        })
        SettingRow(t("agent.alwaysDeniedLabel"), desc = t("agent.alwaysDenied"), icon = WebIcons["lock"])
    }

    val withTools = AGENT_AREAS.filter { it in DATA_AREAS || tools.any { x -> x.area == it } }
    fun ownSetting(area: String): Boolean = when {
        area in DATA_AREAS -> policy.o("dataRights").containsKey(area)
        policy.o("areas").str(area).isNotEmpty() -> true
        else -> tools.any { it.area == area && policy.o("tools").str(it.name).isNotEmpty() }
    }
    val hidden = withTools.filter { it in RARE && !ownSetting(it) }
    val shown = if (allAreas) withTools else withTools.filter { it !in hidden }

    SettingGroup(t("agent.areas")) {
        for (area in shown) {
            if (area in DATA_AREAS) {
                val (r, w) = dataRight(policy, area)
                val summary = tr.t(if (r == "deny") "agent.data.sumNoRead" else "agent.data.sumRead") + " · " +
                    tr.t(when (w) { "allow" -> "agent.data.sumWriteAllow"; "ask" -> "agent.data.sumWriteAsk"; else -> "agent.data.sumWriteDeny" })
                SettingRow(t("agent.area.$area"), desc = t("agent.data.${area}Desc"), summary = summary, badge = t("agent.data.new"), id = "ag-area-$area") {
                    SettingSub(t("agent.data.${area}Read"), desc = t("agent.data.${area}ReadDesc"), control = {
                        Segments(r, listOf("allow" to t("agent.data.allowed"), "deny" to t("agent.data.denied")), { setData(area, "read", it) })
                    })
                    SettingSub(t("agent.data.${area}Write"), desc = t("agent.data.${area}WriteDesc"), control = {
                        Segments(w, modes, { setData(area, "write", it) })
                    })
                }
            } else {
                val list = tools.filter { it.area == area }
                val m = areaMode(policy, area, list)
                val desc = (AREA_HINT[area]?.let { tr.t(it) + " " } ?: "") + tr.t("agent.toolCount", "count" to list.size) + if (m == "mixed") " · " + tr.t("agent.mixed") else ""
                SettingRow(
                    tr.or("agent.area.$area", area),
                    desc = desc,
                    id = "ag-area-$area",
                    control = { Segments(if (m == "mixed") "" else m, modes, { setArea(area, it) }) },
                ) {
                    if (area == "nvr") {
                        SettingSub(t("agent.nvrNames"), desc = t("agent.nvrNamesHint"), control = {
                            OnOff(s.bool("nvrNames"), { v -> st.edit { it.with("nvrNames" to v) } }, label = t("agent.nvrNames"))
                        })
                    }
                    for (tool in list) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Mono(tool.name)
                                Badge(tr.t(KIND_LABEL[tool.kind] ?: "agent.kindChange"), if (tool.dangerous) Tone.Warn else Tone.Info)
                            }
                            Segments(toolMode(policy, tool), modes, { setTool(tool.name, it) })
                        }
                    }
                }
            }
        }
        if (hidden.isNotEmpty()) {
            LinkButton(if (allAreas) tr.t("agent.areasFewer") else tr.t("agent.areasMore", "count" to hidden.size), { allAreas = !allAreas })
        }
    }

    // 1.13 (Q27–Q29): Mail, Termine, Kontakte und Erinnerungen je Art der Aktion.
    SettingGroup(t("agentpim.rights.title")) {
        SettingRow(t("agentpim.rights.intro"), desc = t("agentpim.rights.introDesc"), icon = WebIcons["mail"])
        Banner(t("agentpim.cloud.settings"), tone = Tone.Warn)
        for ((group, keys) in ACTION_GROUPS) {
            val shownModes = keys.filter { !isDelete(it) && it != "mail.read" }.map { nasAction(policy, it) }.distinct().joinToString(" / ") { tr.t("agentpim.mode.$it") }
            val summary = when {
                "mail.read" in keys && nasAction(policy, "mail.read") == "deny" -> tr.t("agentpim.mode.mailOff")
                keys.any { isDelete(it) } -> "$shownModes · ${tr.t("agentpim.mode.deleteAsks")}"
                else -> shownModes
            }
            SettingRow(t("agentpim.group.$group"), summary = summary, id = "ag-act-$group") {
                for (key in keys) {
                    SettingSub(t("agentpim.action.$key"), desc = t("agentpim.action.$key.desc"), control = {
                        val nas = nasAction(policy, key)
                        when {
                            key != "mail.read" && nas == "deny" -> Badge(t("agentpim.mode.closed"))
                            isDelete(key) -> Badge(t("agentpim.mode.alwaysAsk"))
                            else -> Segments(
                                nas,
                                if (key == "mail.read") listOf("allow" to t("agentpim.mode.on"), "deny" to t("agentpim.mode.off"))
                                else listOf("suggest" to t("agentpim.mode.suggest"), "ask" to t("agentpim.mode.ask"), "allow" to t("agentpim.mode.allow")),
                                { v -> st.setPolicy { it.with("actionRights" to it.o("actionRights").with(key to v)) } },
                            )
                        }
                    })
                }
            }
        }
    }

    SettingGroup(t("agent.folderRules"), tools = {
        SmallButton(t("agent.folderRule"), {
            st.setPolicy { it.with("folders" to JsonArray(it.a("folders") + jobj("root" to (st.roots.firstOrNull()?.str("id") ?: ""), "path" to "", "mode" to "deny"))) }
        }, icon = WebIcons["plus"])
    }) {
        val folders = policy.a("folders").objects()
        if (folders.isEmpty()) Muted(t("agent.folderNone"))
        folders.forEachIndexed { i, f ->
            fun setF(change: (JsonObject) -> JsonObject) = st.setPolicy { p -> p.with("folders" to JsonArray(p.a("folders").objects().mapIndexed { n, x -> if (n == i) change(x) else x })) }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val rootOptions = st.roots.map { it.str("id") to it.str("name") } + (if (f.str("root").isNotEmpty() && st.roots.none { it.str("id") == f.str("root") }) listOf(f.str("root") to f.str("root")) else emptyList())
                Select(f.str("root"), rootOptions, { v -> setF { it.with("root" to v) } }, label = t("agent.locationRoot"))
                Field(f.str("path"), { v -> setF { it.with("path" to v) } }, label = t("agent.locationPath"), placeholder = t("agent.locationPath"), singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Segments(f.str("mode"), modes, { v -> setF { it.with("mode" to v) } })
                    io.github.ra5on.titanos.ui.components.IconButton(WebIcons["trash"], tr.t("common.delete"), {
                        st.setPolicy { p -> p.with("folders" to JsonArray(p.a("folders").filterIndexed { n, _ -> n != i })) }
                    }, size = 20.dp)
                }
            }
            Divider()
        }
        Muted(t("agent.folderHint"))
    }
}

/** Konten und Orte (AccountsTab.svelte): wer den Agenten nutzt, und benannte Orte. */
@Composable
internal fun AgentAccountsTab(st: AgentState) {
    val tr = LocalTr.current
    val s = st.settings ?: return
    val users = s.texts("users")
    val userAccounts = st.accounts.filter { it.str("role") != "owner" }
    SettingGroup(t("agent.sectionAccounts"), desc = t("agent.accountsHint")) {
        if (userAccounts.isEmpty()) Muted(t("agent.accountsNone"))
        for (acc in userAccounts) {
            val name = acc.str("name")
            SettingRow(name, desc = t(if (acc.str("role") == "admin") "agent.roleAdmin" else "agent.roleUser"), icon = WebIcons["users"], control = {
                OnOff(name in users, { on -> st.edit { it.with("users" to if (on) (users + name).distinct() else users - name) } }, label = name)
            })
        }
    }
    SettingGroup(t("agent.sectionLocations"), desc = t("agent.locationsHint"), tools = {
        SmallButton(t("agent.locationAdd"), {
            st.edit { it.with("locations" to JsonArray(it.a("locations") + jobj("name" to "", "root" to (st.roots.firstOrNull()?.str("id") ?: ""), "path" to "", "note" to ""))) }
        }, icon = WebIcons["plus"])
    }) {
        val locations = s.a("locations").objects()
        locations.forEachIndexed { i, l ->
            fun setL(change: (JsonObject) -> JsonObject) = st.edit { x -> x.with("locations" to JsonArray(x.a("locations").objects().mapIndexed { n, y -> if (n == i) change(y) else y })) }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(l.str("name"), { v -> setL { it.with("name" to v) } }, label = t("agent.locationName"), placeholder = t("agent.locationName"), singleLine = true, modifier = Modifier.fillMaxWidth())
                val options = listOf("" to t("agent.locationRoot")) + st.roots.map { r -> r.str("id") to "${r.str("name")} (${tr.t(if (r.str("kind") == "share") "agent.share" else "agent.volume")})" } +
                    (if (l.str("root").isNotEmpty() && st.roots.none { it.str("id") == l.str("root") }) listOf(l.str("root") to l.str("root")) else emptyList())
                Select(l.str("root"), options, { v -> setL { it.with("root" to v) } }, label = t("agent.locationRoot"))
                Field(l.str("path"), { v -> setL { it.with("path" to v) } }, label = t("agent.locationPath"), placeholder = t("agent.locationPath"), singleLine = true, modifier = Modifier.fillMaxWidth())
                Field(l.str("note"), { v -> setL { it.with("note" to v) } }, label = t("agent.locationNote"), placeholder = t("agent.locationNote"), singleLine = true, modifier = Modifier.fillMaxWidth())
                SmallButton(t("common.delete"), { st.edit { x -> x.with("locations" to JsonArray(x.a("locations").filterIndexed { n, _ -> n != i })) } }, icon = WebIcons["trash"])
            }
            Divider()
        }
    }
}
