package io.github.ra5on.titanos.settings.pages.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.content
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Übersicht: Name und Zeitzone des NAS, dann die benannten Netze als Zeilen, die aufklappen. */
@Composable
internal fun NetworkOverview(m: NetModel, a: PageArgs, onDiagnose: (String) -> Unit) {
    val status = m.status ?: return
    val draft = m.draft ?: return
    val ov = LocalOverlays.current
    SystemGroup(m, a)
    SettingGroup(
        t("netx.networks"),
        desc = if (status.bool("migrated") && !m.dirty && !m.pending) t("netx.migrated") else null,
        tools = {
            SmallButton(t("netx.add"), {
                m.editors++
                val fresh = blankNetwork(draft, m.ports)
                ov.show { close -> NetworkEditor(m, fresh, true) { m.editors--; close() } }
            }, kind = ButtonKind.Primary, icon = WebIcons["plus"], enabled = !m.locked)
        },
    ) {
        for (n in draft.objs("networks")) {
            androidx.compose.runtime.key(n.str("id")) {
                NetworkRow(m, n, onDiagnose)
            }
        }
        Hint(t("netx.external"))
    }
}

@Composable
private fun SystemGroup(m: NetModel, a: PageArgs) {
    val env = LocalEnv.current
    val hostname = m.status.o("config").str("hostname")
    var value by remember { mutableStateOf(hostname) }
    var edited by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    if (!edited && value != hostname) value = hostname
    val run = rememberRunner()
    LaunchedEffect(saved) {
        if (saved) {
            delay(5000)
            saved = false
        }
    }
    SettingGroup(t("netx.system")) {
        SettingRow(
            t("netcfg.hostname"),
            desc = t("netcfg.hostnameHint"),
            icon = WebIcons["home"],
            control = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(value, {
                        value = it.take(63)
                        edited = true
                        saved = false
                    }, t("netcfg.hostname"), Modifier.widthIn(min = 200.dp), mono = true, singleLine = true, enabled = !m.locked)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        SmallButton(t("netx.saveHostname"), {
                            val name = value
                            run.run(guard = GuardAction.Network, detail = true) {
                                env.api.put("system/hostname", jobj("hostname" to name))
                                edited = false
                                saved = true
                                m.setHostname(name)
                            }
                        }, kind = ButtonKind.Primary, enabled = !m.locked && !run.running && edited && validHostname(value) && value != hostname)
                        if (saved) StatusChip(t("netx.hostnameSaved"), Tone.Ok)
                    }
                }
            },
        )
        run.error?.let { Banner(it, tone = Tone.Error) }
        SettingRow(
            t("netx.timezone"),
            icon = WebIcons["clock"],
            onClick = { a.openPage("timezone") },
            control = { Mono(m.timezone.ifEmpty { "–" }) },
        )
    }
}

@Composable
private fun NetworkRow(m: NetModel, n: JsonObject, onDiagnose: (String) -> Unit) {
    val status = m.status ?: return
    val ov = LocalOverlays.current
    val tr = LocalTr.current
    val applied = m.applied(n)
    val state = liveState(status, n, applied)
    val live = status.o("live")[n.str("id")] as? JsonObject
    val ipv4 = when (n.str("ipv4")) {
        "static" -> n.str("address")
        "dhcp" -> t("netx.mode.dhcp")
        else -> t("netx.off")
    }
    val ipv6 = when (n.str("ipv6")) {
        "static" -> n.str("address6")
        "auto" -> t("netx.mode.auto")
        "dhcp" -> t("netx.mode.dhcp6")
        else -> t("netx.off")
    }
    val firstLive = live?.strings("addresses")?.firstOrNull()?.takeIf { state != "draft" }
    val summary = firstLive ?: (if (n.str("ipv4") != "off") ipv4 else ipv6)
    val gateways = listOf(n.str("gateway"), n.str("gateway6")).filter { it.isNotBlank() }
    val management = n.bool("management")
    val auto = if (management) t("netx.fromNetwork") else t("netx.none")
    SettingRow(
        n.str("name"),
        desc = t("netx.purpose.${n.str("purpose", "custom")}"),
        icon = WebIcons["globe"],
        id = "net-${n.str("id")}",
        summary = summary.ifEmpty { null },
        control = {
            when (state) {
                "draft" -> StatusChip(t("netx.live.draft"), Tone.Inactive)
                "up" -> StatusChip(t("netx.live.up"), Tone.Ok)
                "noAddress" -> StatusChip(t("netx.live.noAddress"), Tone.Warn)
                "down" -> StatusChip(t("netx.live.down"), Tone.Error)
                else -> Unit
            }
            if (management) Badge(t("netx.management"))
            if (n.long("vlan") != 0L) Badge(t("netx.vlanChip", "id" to n.long("vlan")), Tone.Inactive)
            if (n.bool("bridge")) Badge(t("netx.bridgeChip"), Tone.Inactive)
        },
    ) {
        val device = live?.str("device").orEmpty().takeIf { it.isNotEmpty() && state != "draft" }
        Fact(t("netx.connection"), parentLabel(n.str("parent")).ifEmpty { "–" } + (device?.let { " → $it" } ?: ""), mono = true)
        if (state != "draft" && state != "unknown") Fact(t("netx.current"), live?.strings("addresses").orEmpty(), mono = true)
        Fact(t("network.ipv4"), ipv4, mono = true)
        Fact(t("network.ipv6"), ipv6, mono = true)
        Fact(t("netx.gateway"), gateways, mono = true, empty = auto)
        Fact(t("netcfg.dns"), n.strings("dns"), mono = true, empty = auto)
        Buttons {
            SmallButton(t("netx.editShort"), {
                m.editors++
                ov.show { close -> NetworkEditor(m, n, false) { m.editors--; close() } }
            }, icon = WebIcons["edit"], enabled = !m.locked)
            SmallButton(t("netx.tab.diagnostics"), { onDiagnose(n.str("id")) }, icon = WebIcons["activity"], enabled = applied && !m.pending)
            SmallButton(t("netx.remove"), {
                ov.confirm(tr.t("netx.remove"), tr.t("netx.removeConfirm", "name" to n.str("name")), action = tr.t("netx.remove"), cancel = tr.t("common.cancel")) {
                    m.draft = m.draft?.let { d -> d.with("networks" to d.objs("networks").filter { it.str("id") != n.str("id") }) }
                }
            }, kind = ButtonKind.Danger, icon = WebIcons["trash"], enabled = !m.locked && !management)
        }
        if (management) Hint(t("netx.removeManagement"))
    }
}

/** Ein Netz hinzufügen oder bearbeiten (NetworkEditor.svelte). Hier wird nichts angewendet; die Seite testet danach den ganzen Entwurf. */
@Composable
private fun BoxScope.NetworkEditor(m: NetModel, initial: JsonObject, isNew: Boolean, onClose: () -> Unit) {
    val cfg = m.draft ?: return
    val ports = m.ports
    val vlans = m.status?.bool("vlans", true) ?: true
    var n by remember { mutableStateOf(if (initial["routes"] == null) initial.with("routes" to emptyList<Any>()) else initial) }
    var vlanText by remember { mutableStateOf(initial.long("vlan").takeIf { it != 0L }?.toString() ?: "0") }
    var dnsText by remember { mutableStateOf("") }
    val wasManagement = initial.bool("management") && !isNew
    val bonded = cfg.objs("bonds").flatMap { it.strings("ports") }.toSet()
    val vlan = vlanText.trim().toLongOrNull() ?: 0L
    val clash = cfg.objs("networks").firstOrNull { o -> o.str("id") != n.str("id") && o.str("parent") == n.str("parent") && o.long("vlan") == vlan }
    val noAddress = n.bool("management") && n.str("ipv4") == "off" && n.str("ipv6") == "off"
    val dns = parseDns((n.strings("dns") + dnsText).joinToString(" "))
    val dnsOk = dns.size <= 3 && dns.all(::looksLikeIP)
    val vlanOk = vlan in 0..4094
    val ok = n.str("name").isNotBlank() && n.str("parent").isNotEmpty() && clash == null && !noAddress && dnsOk && vlanOk
    val parents = ports.filter { it.str("name") !in bonded || it.str("name") == n.str("parent") }.map { p ->
        p.str("name") to (p.str("name") + if (p.str("name") in bonded) " · " + t("netx.inBond") else "")
    } + cfg.objs("bonds").map { b -> ("bond:" + b.str("id")) to t("netx.bondNamed", "name" to b.str("id")) }
    val routes = n.objs("routes")
    FormDialog(
        if (isNew) t("netx.add") else t("netx.edit"),
        onClose,
        listOf(
            Act(t("common.cancel"), onClick = onClose),
            Act(t("netx.takeOver"), ButtonKind.Primary, enabled = ok) {
                val saved = n.with(
                    "name" to n.str("name").trim(),
                    "vlan" to vlan,
                    "dns" to dns,
                    "routes" to routes.map { r -> r.with("metric" to (r.str("metric").trim().toLongOrNull() ?: 0L)) },
                )
                m.draft = saveNetwork(m.draft ?: cfg, saved, isNew)
                onClose()
            },
        ),
    ) {
        SubHead(t("netx.general"))
        LabeledField(t("netx.name"), n.str("name"), { n = n.with("name" to it.take(80)) })
        LabeledSelect(t("netx.purpose"), n.str("purpose", "custom"), PURPOSES.map { it to t("netx.purpose.$it") }, { n = n.with("purpose" to it) })
        LabeledSelect(t("netx.connection"), n.str("parent"), parents, { n = n.with("parent" to it) })
        if (vlans || vlan != 0L) {
            LabeledField(t("netx.vlan"), vlanText, { vlanText = it.filter(Char::isDigit).take(4) }, number = true, mono = true, hint = t("netx.optional"))
        }
        if (clash != null) Banner(t("netx.clash", "name" to clash.str("name")), tone = Tone.Warn)
        Choice(t("netx.preferred"), n.bool("management"), { n = n.with("management" to it) }, enabled = !wasManagement)
        if (wasManagement) Hint(t("netx.preferredHint"))
        Choice(t("netx.bridge"), n.bool("bridge"), { n = n.with("bridge" to it) })

        SubHead(t("network.ipv4"))
        Segments(n.str("ipv4"), listOf("dhcp" to t("netx.mode.dhcp"), "static" to t("netx.static"), "off" to t("netx.off")), { n = n.with("ipv4" to it) })
        if (n.str("ipv4") == "static") {
            LabeledField(t("netx.address"), n.str("address"), { n = n.with("address" to it) }, mono = true, placeholder = "192.168.1.20/24")
            LabeledField(t("netx.gateway"), n.str("gateway"), { n = n.with("gateway" to it) }, mono = true, placeholder = "192.168.1.1")
        }
        SubHead(t("network.ipv6"))
        Segments(
            n.str("ipv6"),
            listOf("auto" to t("netx.mode.auto"), "dhcp" to t("netx.mode.dhcp6"), "static" to t("netx.static"), "off" to t("netx.off")),
            { n = n.with("ipv6" to it) },
        )
        if (n.str("ipv6") == "static") {
            LabeledField(t("netx.address"), n.str("address6"), { n = n.with("address6" to it) }, mono = true, placeholder = "fd00::20/64")
            LabeledField(t("netx.gateway"), n.str("gateway6"), { n = n.with("gateway6" to it) }, mono = true, placeholder = "fe80::1")
        }
        if (noAddress) Banner(t("netx.needsAddress"), tone = Tone.Warn)

        SubHead(t("netx.dns"))
        DnsChips(n.strings("dns"), { n = n.with("dns" to it) }, dnsText, { dnsText = it })
        val invalid = dns.filter { !looksLikeIP(it) }
        if (invalid.isNotEmpty()) Banner(t("netx.dnsInvalid", "value" to invalid.joinToString(", ")), tone = Tone.Warn)
        if (dns.size > 3) Banner(t("netx.dnsTooMany", "max" to 3), tone = Tone.Warn)
        Hint(if (n.bool("management")) t("netx.dnsNote.management") else t("netx.dnsNote.other"))

        SubHead(t("netx.routes"))
        if (routes.isEmpty()) Hint(t("netx.noRoutes"))
        routes.forEachIndexed { i, r ->
            fun set(key: String, v: String) {
                n = n.with("routes" to routes.mapIndexed { j, o -> if (j == i) o.with(key to v) else o })
            }
            Column(Modifier.fillMaxWidth()) {
                LabeledField(t("netx.destination"), r.str("destination"), { set("destination", it) }, mono = true, placeholder = "10.20.0.0/16")
                LabeledField(t("netx.gateway"), r.str("gateway"), { set("gateway", it) }, mono = true)
                LabeledField(t("netx.metric"), r.str("metric"), { set("metric", it.filter(Char::isDigit).take(5)) }, number = true)
                SmallButton(t("netx.remove"), { n = n.with("routes" to routes.filterIndexed { j, _ -> j != i }) })
            }
        }
        Buttons {
            SmallButton(t("netx.addRoute"), { n = n.with("routes" to routes + jobj("destination" to "", "gateway" to "", "metric" to 100)) })
        }
        Hint(t("netx.routesHint"))
        Hint(t("netx.addressHint"))
    }
}

/**
 * Nameserver als Chips (DnsChips.svelte): Leerzeichen, Komma oder Semikolon
 * machen aus dem Text Chips; was noch im Feld steht, zählt beim Übernehmen mit.
 */
@Composable
private fun DnsChips(value: List<String>, onChange: (List<String>) -> Unit, text: String, onText: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        for (server in value) {
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                Badge(server, if (looksLikeIP(server)) Tone.Inactive else Tone.Warn)
                IconButton(TitanIcons.x, t("netx.dnsRemove", "server" to server), { onChange(value.filter { it != server }) }, size = 18.dp, tint = Titan.colors.muted)
            }
        }
    }
    Field(
        text,
        { typed ->
            if (Regex("[\\s,;]").containsMatchIn(typed)) {
                val parts = typed.split(Regex("[\\s,;]+"))
                val added = parseDns(parts.dropLast(1).joinToString(" "))
                if (added.isNotEmpty()) onChange(parseDns((value + added).joinToString(" ")))
                onText(parts.last())
            } else {
                onText(typed)
            }
        },
        t("netx.dns"),
        Modifier.fillMaxWidth(),
        placeholder = if (value.isEmpty()) "1.1.1.1, 9.9.9.9" else null,
        mono = true,
        singleLine = true,
    )
}
