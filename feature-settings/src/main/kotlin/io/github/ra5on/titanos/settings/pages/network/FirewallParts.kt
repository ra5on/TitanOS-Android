package io.github.ra5on.titanos.settings.pages.network

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.without
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.DangerZone
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject

/**
 * Eine Richtlinie als kleine Formularkarte (PolicyEditor.svelte): was sie
 * trifft (Dienst, App oder Ports), woher (und zwischen Zonen wohin), erlauben
 * oder sperren, eine Notiz; mit ↑/↓ für die Reihenfolge (die erste passende entscheidet).
 */
@Composable
internal fun PolicyEditor(
    policy: JsonObject,
    to: String,
    services: List<JsonObject>,
    apps: List<Pair<String, String>>,
    index: Int,
    count: Int,
    locked: Boolean,
    heading: String = "",
    onChange: (JsonObject) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    val kind = policyKindOf(policy)
    val problem = policyProblem(policy)
    val zoneCell = to != NAS && to != APPS
    val off = policy.bool("disabled")
    fun set(key: String, v: String) = onChange(policy.with(key to v))
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).background(if (off) c.bg else c.surface).border(1.dp, c.border, shape).padding(12.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
            if (heading.isNotEmpty()) Text(heading, style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
        Choice(t("firewall.ruleOn"), !off, { on -> onChange(if (on) policy.without("disabled") else policy.with("disabled" to true)) }, enabled = !locked)
        Buttons {
            ArrowButton("↑", t("firewall.ruleUp"), !locked && index > 0) { onMove(-1) }
            ArrowButton("↓", t("firewall.ruleDown"), !locked && index < count - 1) { onMove(1) }
            SmallButton(t("firewall.ruleRemove"), onRemove, enabled = !locked)
        }
        LabeledSelect(t("firewall.ruleAction"), policy.str("action"), listOf("allow" to t("firewall.allow"), "deny" to t("firewall.deny")), { set("action", it) }, enabled = !locked)
        if (!zoneCell) {
            val kinds = buildList {
                if (to == NAS) add("service" to t("firewall.kindService"))
                if (to == APPS) add("app" to t("firewall.kindApp"))
                add("ports" to t("firewall.kindPorts"))
            }
            LabeledSelect(t("firewall.policyKind"), kind, kinds, { onChange(policyKind(policy, it, services, apps)) }, enabled = !locked)
        }
        when (kind) {
            "service" -> LabeledSelect(
                t("firewall.kindService"),
                policy.str("service"),
                services.map { it.str("id") to t("firewall.svc.${it.str("id")}") },
                { set("service", it) },
                enabled = !locked,
            )
            "app" -> {
                val app = policy.str("app")
                val options = apps + if (app.isNotEmpty() && apps.none { it.first == app }) listOf(app to app.removePrefix("container:")) else emptyList()
                LabeledSelect(t("firewall.kindApp"), app, options, { set("app", it) }, enabled = !locked)
            }
            else -> {
                LabeledSelect(
                    t("firewall.ruleProtocol"),
                    policy.str("protocol", "tcp"),
                    listOf("tcp" to "TCP", "udp" to "UDP", "any" to t("firewall.anyProtocol")),
                    { set("protocol", it) },
                    enabled = !locked,
                )
                LabeledField(t("firewall.rulePortsLabel"), policy.str("ports"), { set("ports", it) }, placeholder = t("firewall.allPorts"), mono = true, enabled = !locked)
            }
        }
        LabeledField(t("firewall.ruleSourceLabel"), policy.str("source"), { set("source", it) }, placeholder = t("firewall.anySource"), mono = true, enabled = !locked)
        if (zoneCell) {
            LabeledField(t("firewall.policyDestinationLabel"), policy.str("destination"), { set("destination", it) }, placeholder = t("firewall.anyDestination"), mono = true, enabled = !locked)
        }
        LabeledField(t("firewall.ruleCommentLabel"), policy.str("comment"), { set("comment", it.take(80)) }, enabled = !locked)
        if (kind == "service" && policy.str("service").isNotEmpty()) {
            val service = services.firstOrNull { it.str("id") == policy.str("service") }
            Hint(t("firewall.svcText.${policy.str("service")}") + (service?.let { " · " + servicePorts(it) } ?: ""))
        }
        if (problem.isNotEmpty()) Banner(t(problem), tone = Tone.Warn)
    }
}

/** Jedes Netz, das das NAS kennt, mit seiner Zone; umhängen ändert den Entwurf (NetworksTab.svelte). */
@Composable
internal fun FwNetworksTab(m: FwModel) {
    val tr = LocalTr.current
    val draft = m.draft ?: return
    val networks = m.status.objs("networks")
    val own = ownZones(draft)
    SettingGroup(t("firewall.tab.networks"), desc = t("firewall.networksText")) {
        if (networks.isEmpty()) Hint(t("firewall.noNetworks"))
        for (n in networks) {
            val id = n.str("id")
            val kind = n.str("kind")
            val zone = zoneOfNetwork(draft, id)
            val fallback = if (kind == "docker") "docker" else ""
            val device = n.str("device").ifEmpty { n.str("parent").takeIf { it.isNotEmpty() }?.let { "↳ $it" } ?: "–" }
            SettingRow(
                n.str("name"),
                icon = WebIcons[if (kind == "docker" || kind == "dockerlan") "box" else if (kind == "vm") "monitor" else "globe"],
                desc = t("firewall.kind.$kind") + " · " + device + " · " + n.strings("subnets").joinToString(", ").ifEmpty { "–" },
                badge = if (n.bool("missing")) t("firewall.netMissing") else null,
                control = {
                    if (kind == "dockerlan") {
                        Hint(t("firewall.netBypass"))
                    } else {
                        Select(
                            if (zone == fallback) "" else zone,
                            listOf("" to if (kind == "docker") t("firewall.dockerDefault") else t("firewall.byAddress")) + own.map { it.str("id") to m.zoneLabel(tr, it.str("id")) },
                            { z -> m.edit { assignNetwork(it, id, z) } },
                            t("firewall.netZone"),
                            enabled = !m.locked,
                        )
                    }
                },
            )
        }
        if (own.isEmpty()) Hint(t("firewall.networksNoZones"))
        Hint(t("firewall.bypassText"))
    }
}

/** Alle Richtlinien aller Zellen als eine Liste, nach Absender und Ziel zu filtern (PoliciesTab.svelte). */
@Composable
internal fun FwPoliciesTab(m: FwModel) {
    val tr = LocalTr.current
    val draft = m.draft ?: return
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    val all = listOf(NAS, APPS) + draft.objs("zones").map { it.str("id") }
    val canAdd = from.isNotEmpty() && to.isNotEmpty() && to in destinations(draft, from)
    SettingGroup(t("firewall.tab.policies"), desc = t("firewall.policiesText")) {
        LabeledSelect(t("firewall.from"), from, listOf("" to t("firewall.filterAll")) + draft.objs("zones").map { it.str("id") to m.zoneLabel(tr, it.str("id")) }, { from = it })
        LabeledSelect(t("firewall.to"), to, listOf("" to t("firewall.filterAll")) + all.map { it to m.destLabel(tr, it) }, { to = it })
        var any = false
        for (cell in draft.objs("cells")) {
            val cf = cell.str("from")
            val ct = cell.str("to")
            if ((from.isNotEmpty() && cf != from) || (to.isNotEmpty() && ct != to) || ct !in destinations(draft, cf)) continue
            val policies = cell.objs("policies")
            fun update(change: (JsonObject) -> JsonObject) = m.edit { updateCell(it, m.services, cf, ct, change) }
            policies.forEachIndexed { i, p ->
                any = true
                PolicyEditor(
                    policy = p,
                    to = ct,
                    services = m.services,
                    apps = m.apps,
                    index = i,
                    count = policies.size,
                    locked = m.locked,
                    heading = tr.t("firewall.cellTitle", "from" to m.zoneLabel(tr, cf), "to" to m.destLabel(tr, ct)),
                    onChange = { next -> update { c -> c.with("policies" to c.objs("policies").mapIndexed { j, o -> if (j == i) next else o }) } },
                    onMove = { by -> update { c -> c.with("policies" to swap(c.objs("policies"), i, i + by)) } },
                    onRemove = { update { c -> c.with("policies" to c.objs("policies").filterIndexed { j, _ -> j != i }) } },
                )
            }
        }
        if (!any) Hint(t("firewall.noMatch"))
        Buttons {
            SmallButton(t("firewall.addPolicy"), {
                m.edit { updateCell(it, m.services, from, to) { c -> c.with("policies" to c.objs("policies") + newPolicy(to, m.services, m.apps, withApps = false)) } }
            }, enabled = !m.locked && canAdd)
            if (!canAdd) Hint(t("firewall.addPolicyHint"))
        }
    }
}

/** Automatisches Sperren: für sich gespeichert und sofort wirksam (ohne Probe), mit der Liste gesperrter Absender. */
@Composable
internal fun FwAutoBlockTab(m: FwModel) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val status = m.status ?: return
    val saved = status.o("config").o("autoBlock")
    var edited by remember { mutableStateOf(false) }
    var d by remember { mutableStateOf(saved) }
    var exemptText by remember { mutableStateOf(saved.strings("exempt").joinToString("\n")) }
    var localNotice by remember { mutableStateOf("") }
    if (!edited && d != saved) {
        d = saved
        exemptText = saved.strings("exempt").joinToString("\n")
    }
    val exempt = parseNetworks(exemptText)
    val exemptOk = exempt.all(::validSource)
    fun change(next: JsonObject) {
        d = next
        edited = true
        localNotice = ""
    }
    val on = d.bool("enabled")
    SettingGroup(t("firewall.autoBlock")) {
        SettingRow(t("firewall.autoBlockEnable"), desc = t("firewall.autoBlockText"), icon = WebIcons["shield"], control = {
            Switch(on, { change(d.with("enabled" to it)) }, t("firewall.autoBlockEnable"))
        })
        SettingRow(t("firewall.attempts"), icon = WebIcons["key"], control = {
            io.github.ra5on.titanos.ui.components.NumberField(d.long("attempts"), { v -> if (v != null) change(d.with("attempts" to v.coerceIn(2, 100))) }, t("firewall.attempts"), enabled = on)
        })
        SettingRow(t("firewall.within"), icon = WebIcons["clock"], control = {
            io.github.ra5on.titanos.ui.components.NumberField(d.long("within"), { v -> if (v != null) change(d.with("within" to v.coerceIn(1, 1440))) }, t("firewall.within"), enabled = on)
        })
        SettingRow(t("firewall.blockMinutes"), icon = WebIcons["lock"], control = {
            io.github.ra5on.titanos.ui.components.NumberField(d.long("minutes"), { v -> if (v != null) change(d.with("minutes" to v.coerceIn(1, 525600))) }, t("firewall.blockMinutes"), enabled = on && !d.bool("permanent"))
        })
        SettingRow(t("firewall.permanent"), icon = WebIcons["lock"], control = {
            Switch(d.bool("permanent"), { change(d.with("permanent" to it)) }, t("firewall.permanent"), enabled = on)
        })
        SettingRow(t("firewall.exemptLocal"), icon = WebIcons["home"], control = {
            Switch(d.bool("exemptLocal"), { change(d.with("exemptLocal" to it)) }, t("firewall.exemptLocal"), enabled = on)
        })
        SettingRow(t("firewall.exempt"), icon = WebIcons["list"]) {
            LabeledField(t("firewall.exempt"), exemptText, { exemptText = it; edited = true; localNotice = "" }, placeholder = "203.0.113.0/24", mono = true, multiLine = true, enabled = on)
        }
        if (!exemptOk) Banner(t("firewall.ruleSource"), tone = Tone.Warn)
        Hint(t("firewall.autoBlockSources"))
        Buttons {
            SmallButton(t("common.save"), {
                val body = d.with("exempt" to exempt)
                m.change(context, call = { env.api.put("firewall/autoblock", body) }, after = {
                    edited = false
                    localNotice = tr.t("ui.saved")
                })
            }, kind = ButtonKind.Primary, enabled = !m.busy && edited && exemptOk)
            if (localNotice.isNotEmpty()) Hint(localNotice)
        }
        Note(m.error)
    }
    SettingGroup(t("firewall.blocked")) {
        val blocked = status.objs("blocked")
        if (blocked.isEmpty()) Hint(t("firewall.noBlocked"))
        for (b in blocked) {
            val address = b.str("address")
            SettingRow(
                address,
                icon = WebIcons["lock"],
                desc = t("firewall.blockSource") + ": " + tr.or("firewall.source.${b.str("source")}", b.str("source")) + " (${b.long("attempts")})\n" +
                    t("firewall.since") + ": " + tr.whenOr(b.str("since")) + "\n" +
                    t("firewall.until") + ": " + (if (b.has("until")) tr.whenOr(b.str("until")) else t("firewall.forever")),
                control = {
                    SmallButton(t("firewall.release"), {
                        ov.confirm(tr.t("firewall.releaseTitle"), tr.t("firewall.releaseText", "address" to address), action = tr.t("firewall.release"), cancel = tr.t("common.cancel"), danger = false) {
                            m.change(context, call = { env.api.post("firewall/unblock", jobj("address" to address)) })
                        }
                    })
                },
            )
        }
    }
}

/** Pakete, die die Regeln zuletzt verworfen haben (nur im Arbeitsspeicher; RecentTab.svelte). */
@Composable
internal fun FwRecentTab(m: FwModel) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val status = m.status ?: return
    val recent = status.objs("recent")
    SettingGroup(
        t("firewall.recent"),
        desc = t("firewall.recentText"),
        tools = {
            SmallButton(t("firewall.refresh"), { m.reload() })
            SmallButton(t("firewall.clearRecent"), { m.change(context, call = { env.api.delete("firewall/recent") }) }, enabled = !m.busy && recent.isNotEmpty())
        },
    ) {
        if (recent.isEmpty()) Hint(t("firewall.noRecent"))
        for (drop in recent) {
            val zone = drop.str("zone")
            val where = buildString {
                append(if (zone == "blocked") tr.t("firewall.zoneBlocked") else m.zoneLabel(tr, zone))
                if (drop.bool("app")) append(" · ").append(tr.t("firewall.toApp"))
                if (drop.bool("forward")) append(" · ").append(tr.t("firewall.forwardTo", "address" to drop.str("destination").ifEmpty { "–" }))
            }
            val iface = drop.str("interface") + drop.str("out").takeIf { it.isNotEmpty() }?.let { " → $it" }.orEmpty()
            SettingRow(
                drop.str("source"),
                icon = WebIcons["shield"],
                desc = tr.whenOr(drop.str("at")) + "\n" + t("firewall.zoneColumn") + ": " + where + "\n" +
                    t("firewall.ruleProtocol") + ": " + drop.str("protocol").uppercase() + " · " + t("firewall.port") + ": " + (drop.str("port").ifEmpty { "–" }) +
                    (if (iface.isNotEmpty()) "\n" + t("firewall.interface") + ": " + iface else ""),
            )
        }
        Note(m.error)
    }
}

/**
 * Eine eigene Zone anlegen oder bearbeiten (ZoneDialog.svelte): Name, Netze
 * des NAS (gruppiert) und Quellnetze. Ein Netz liegt in höchstens einer
 * Zone; hier gewählt, verlässt es eine andere.
 */
@Composable
internal fun BoxScope.ZoneDialog(m: FwModel, zone: JsonObject, isNew: Boolean, onClose: () -> Unit) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val cfg = m.draft ?: return
    val networks = m.status.objs("networks")
    var name by remember { mutableStateOf(zone.str("name")) }
    var picked by remember { mutableStateOf(zone.strings("networks")) }
    var sourcesText by remember { mutableStateOf(zone.strings("sources").joinToString("\n")) }
    val result = zone.with("name" to name.trim(), "networks" to picked, "sources" to parseNetworks(sourcesText))
    val problem = zoneProblem(result)
    FormDialog(
        t(if (isNew) "firewall.zoneAdd" else "firewall.zoneEdit"),
        onClose,
        listOf(
            Act(t("common.cancel"), onClick = onClose),
            Act(t("firewall.zoneApply"), ButtonKind.Primary, enabled = problem.isEmpty()) {
                m.edit { d -> saveZone(d, result) }
                onClose()
            },
        ),
    ) {
        LabeledField(t("firewall.zoneNameLabel"), name, { name = it.take(40) })
        for (kind in listOf("network", "docker", "vm", "dockerlan")) {
            val list = networks.filter { it.str("kind") == kind }
            SubHead(t("firewall.group.$kind"))
            if (list.isEmpty()) Hint(t(if (kind == "vm") "firewall.groupEmptyVm" else "firewall.groupEmpty"))
            if (kind == "dockerlan" && list.isNotEmpty()) Hint(t("firewall.bypassText"))
            for (n in list) {
                val id = n.str("id")
                val elsewhere = zoneOfNetwork(cfg, id).takeIf { it.isNotEmpty() && it != zone.str("id") && it != "docker" }
                val desc = listOfNotNull(
                    (n.str("device").ifEmpty { n.str("parent") } + " " + n.strings("subnets").joinToString(", ")).trim().ifEmpty { null },
                    if (n.bool("missing")) t("firewall.netMissing") else null,
                    if (elsewhere != null && id !in picked) t("firewall.inZone", "zone" to m.zoneLabel(tr, elsewhere)) else null,
                ).joinToString(" · ").ifEmpty { null }
                Choice(n.str("name"), id in picked, { on -> picked = if (on) picked.filter { it != id } + id else picked.filter { it != id } }, desc = desc, enabled = kind != "dockerlan")
            }
        }
        LabeledField(t("firewall.zoneSources"), sourcesText, { sourcesText = it }, placeholder = "10.20.0.0/16", mono = true, multiLine = true, hint = t("firewall.zoneSourcesHint"))
        if (problem.isNotEmpty()) Banner(t(problem), tone = Tone.Warn)
        if (!isNew) {
            DangerZone(t("firewall.zoneRemove"), t("firewall.zoneRemoveHint")) {
                SmallButton(t("firewall.zoneRemove"), {
                    ov.confirm(tr.t("firewall.zoneRemove"), tr.t("firewall.zoneRemoveText", "name" to zone.str("name")), action = tr.t("firewall.zoneRemove"), cancel = tr.t("common.cancel")) {
                        val id = zone.str("id")
                        m.edit { removeZone(it, id) }
                        if (m.selected?.let { it.first == id || it.second == id } == true) m.selected = null
                        onClose()
                    }
                }, kind = ButtonKind.Danger)
            }
        }
    }
}

/** Übernimmt eine Zone in den Entwurf: Name, Quellnetze, und jedes gewählte Netz verlässt andere Zonen. */
internal fun saveZone(cfg: JsonObject, zone: JsonObject): JsonObject {
    val id = zone.str("id")
    var d = cfg
    if (d.objs("zones").none { it.str("id") == id }) {
        d = d.with("zones" to d.objs("zones") + jobj("id" to id, "name" to zone.str("name"), "networks" to emptyList<String>(), "sources" to emptyList<String>()))
    }
    d = d.with(
        "zones" to d.objs("zones").map {
            if (it.str("id") == id) it.with("name" to zone.str("name"), "sources" to zone.strings("sources"), "networks" to emptyList<String>()) else it
        },
    )
    for (n in zone.strings("networks")) d = assignNetwork(d, n, id)
    return d
}
