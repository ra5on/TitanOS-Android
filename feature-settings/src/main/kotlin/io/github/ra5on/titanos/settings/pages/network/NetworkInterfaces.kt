package io.github.ra5on.titanos.settings.pages.network

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject

/** Anschlüsse mit dem, was sie verwendet, die Bonds des Entwurfs und „VLANs verwenden“ (InterfacesTab/VlanOption). */
@Composable
internal fun NetworkInterfaces(m: NetModel, draft: JsonObject) {
    val ov = LocalOverlays.current
    val tr = LocalTr.current
    val ports = m.ports
    fun speed(mbps: Long) = if (mbps >= 1000) tr.t("network.gbe", "speed" to (if (mbps % 1000 == 0L) (mbps / 1000).toString() else (mbps / 1000.0).toString())) else tr.t("network.mbit", "speed" to mbps)
    fun mode(x: String) = BOND_MODE_KEYS[x]?.let { tr.t(it) } ?: x

    // Am Handy ist die Tabelle eine Zeile je Anschluss.
    SettingGroup(t("netx.ports")) {
        for (port in ports) {
            val users = portUsers(draft, port.str("name"))
            val carrier = port.bool("carrier")
            SettingRow(
                port.str("name"),
                desc = t("network.mac") + " · " + port.str("mac"),
                icon = WebIcons["columns"],
                summary = t("netx.usedBy") + ": " + (
                    users.joinToString(", ") { (kind, name) -> if (kind == "bond") tr.t("netx.bondNamed", "name" to name) else name }
                        .ifEmpty { t("netx.unused") }
                    ),
                control = {
                    StatusChip(if (carrier) t("netx.connected") else t("netx.disconnected"), if (carrier) Tone.Ok else Tone.Inactive)
                    if (carrier && port.long("speedMbps") > 0) Hint(speed(port.long("speedMbps")))
                },
            )
        }
    }

    SettingGroup(
        t("netx.bonds"),
        desc = t("netx.switchHint"),
        tools = {
            SmallButton(t("netx.addBond"), {
                m.editors++
                val fresh = blankBond(draft)
                ov.show { close -> BondEditor(m, fresh, true) { m.editors--; close() } }
            }, enabled = !m.locked && ports.size >= 2)
        },
    ) {
        val bonds = draft.objs("bonds")
        if (bonds.isEmpty()) Hint(t("netx.noBonds"))
        for (bond in bonds) {
            val users = bondUsers(draft, bond.str("id"))
            SettingRow(
                bond.str("id"),
                desc = bond.strings("ports").joinToString(" + "),
                icon = WebIcons["columns"],
                id = "bond-${bond.str("id")}",
                summary = mode(bond.str("mode")),
            ) {
                Fact(t("netx.ports"), bond.strings("ports").joinToString(" + "), mono = true)
                Fact(t("netx.bondMode"), mode(bond.str("mode")))
                Fact(t("netx.usedBy"), users.joinToString(", ").ifEmpty { t("netx.unused") })
                Buttons {
                    SmallButton(t("netx.editShort"), {
                        m.editors++
                        ov.show { close -> BondEditor(m, bond, false) { m.editors--; close() } }
                    }, enabled = !m.locked)
                    SmallButton(t("netx.remove"), {
                        ov.confirm(tr.t("netx.bondRemove"), tr.t("netx.bondRemoveConfirm", "name" to bond.str("id")), action = tr.t("netx.bondRemove"), cancel = tr.t("common.cancel")) {
                            m.draft = m.draft?.let { d -> d.with("bonds" to d.objs("bonds").filter { it.str("id") != bond.str("id") }) }
                        }
                    }, kind = ButtonKind.Danger, enabled = !m.locked && users.isEmpty())
                }
                if (users.isNotEmpty()) Hint(t("netx.bondInUse", "names" to users.joinToString(", ")))
            }
        }
    }

    VlanOption(m)
}

/** „VLANs verwenden“: ab Werk aus; Einschalten fragt einmal, Ausschalten lehnt das NAS ab, solange ein Netz ein VLAN hat. */
@Composable
private fun VlanOption(m: NetModel) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val status = m.status ?: return
    val enabled = status.bool("vlans", true)
    val inUse = status.bool("vlansInUse")
    val run = rememberRunner()
    fun save(on: Boolean) = run.run(guard = GuardAction.Network) {
        val res = env.api.put("network/options", jobj("vlans" to on)) as? JsonObject
        m.status = res.o("network")
    }
    SettingGroup(t("netx.vlans.title")) {
        SettingRow(
            t("netx.vlans.use"),
            desc = t(if (enabled && inUse) "netx.vlans.inUse" else "netx.vlans.hint"),
            icon = WebIcons["columns"],
            control = {
                Switch(enabled, { on ->
                    if (on) {
                        ov.confirm(tr.t("netx.vlans.confirmTitle"), tr.t("netx.vlans.confirmText"), action = tr.t("netx.vlans.confirm"), cancel = tr.t("common.cancel"), danger = false, body = {
                            Hint(tr.t("netx.vlans.confirmLocal"))
                        }) { save(true) }
                    } else {
                        save(false)
                    }
                }, t("netx.vlans.use"), enabled = !m.locked && !run.running && !(enabled && inUse))
            },
        )
        run.error?.let { Banner(it, tone = Tone.Error) }
    }
}

/** Einen Bond des Entwurfs anlegen oder bearbeiten: seine Anschlüsse und wie sie zusammenarbeiten. */
@Composable
private fun BoxScope.BondEditor(m: NetModel, initial: JsonObject, isNew: Boolean, onClose: () -> Unit) {
    val tr = LocalTr.current
    val cfg = m.draft ?: return
    val ports = m.ports
    var b by remember { mutableStateOf(initial) }
    val candidates = bondCandidates(cfg, ports, b)
    fun direct(port: String) = portUsers(cfg, port).filter { it.first == "network" }.map { it.second }
    val idOk = validBondId(b.str("id")) && !(isNew && cfg.objs("bonds").any { it.str("id") == b.str("id") })
    val chosen = b.strings("ports")
    val label = { x: String -> BOND_MODE_KEYS[x]?.let { tr.t(it) } ?: x }
    FormDialog(
        if (isNew) t("netx.addBond") else t("netx.bondEdit"),
        onClose,
        listOf(
            Act(t("common.cancel"), onClick = onClose),
            Act(t("netx.takeOver"), ButtonKind.Primary, enabled = idOk && chosen.size >= 2) {
                val ordered = ports.map { it.str("name") }.filter { it in chosen }
                m.draft = saveBond(m.draft ?: cfg, b.with("ports" to ordered), isNew)
                onClose()
            },
        ),
    ) {
        LabeledField(t("netx.bondName"), b.str("id"), { b = b.with("id" to it.take(10)) }, mono = true, enabled = isNew, hint = t("netx.bondNameHint"))
        LabeledSelect(t("netx.bondMode"), b.str("mode"), m.modes.map { it to label(it) }, { b = b.with("mode" to it) })
        SubHead(t("netx.ports"))
        for (p in candidates) {
            val name = p.str("name")
            val users = direct(name)
            Choice(
                name,
                name in chosen,
                { on -> b = b.with("ports" to if (on) (chosen + name).distinct() else chosen.filter { it != name }) },
                desc = if (users.isNotEmpty()) t("netx.portUsedBy", "names" to users.joinToString(", ")) else null,
                enabled = users.isEmpty() || name in chosen,
            )
        }
        if (chosen.size < 2) Hint(t("netx.bondTwoPorts"))
        Hint(t("netx.switchHint"))
    }
}

/** Prüft Link, Adressen, Gateways und Namensauflösung eines Netzes, das das NAS betreibt (DiagnosticsTab.svelte). */
@Composable
internal fun NetworkDiagnostics(networks: List<JsonObject>, pending: Boolean, selected: String, onSelect: (String) -> Unit) {
    val env = LocalEnv.current
    var checks by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var checked by remember { mutableStateOf("") }
    val run = rememberRunner()
    fun check(id: String) {
        checked = id
        run.run(detail = true) {
            checks = emptyList()
            checks = (env.api.post("network/diagnostics", jobj("network" to id)) as? JsonObject).objs("checks")
        }
    }
    // Aus der Zeile eines Netzes geöffnet: dieses gleich prüfen.
    LaunchedEffect(selected, networks.size) {
        if (selected.isNotEmpty() && selected != checked && !run.running && networks.any { it.str("id") == selected }) check(selected)
    }
    SettingGroup(t("netx.tab.diagnostics")) {
        if (pending) Banner(t("netx.diagnosticsPending"), tone = Tone.Warn)
        SettingRow(
            t("netx.network"),
            desc = t("netx.diagnosticsDraft"),
            icon = WebIcons["activity"],
            control = {
                Select(selected, listOf("" to t("netx.choose")) + networks.map { it.str("id") to it.str("name") }, onSelect, t("netx.network"), enabled = !pending)
                SmallButton(t("netx.check"), { check(selected) }, enabled = !run.running && !pending && selected.isNotEmpty())
            },
        )
        run.error?.let { Banner(it, tone = Tone.Error) }
        for (c in checks) {
            val ok = c.bool("ok")
            SettingRow(
                c.str("name"),
                desc = c.str("detail").ifBlank { null },
                icon = WebIcons[if (ok) "check" else "info"],
                control = { StatusChip(if (ok) t("netx.ok") else t("netx.warning"), if (ok) Tone.Ok else Tone.Warn) },
            )
        }
    }
}
