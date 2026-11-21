package io.github.ra5on.titanos.settings.pages.network

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tabs
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Der Zustand der Firewall-Seite: Stand des NAS, Entwurf (geht auf Probe), gewählte Zelle. */
@Stable
internal class FwModel(private val api: SettingsApi, private val scope: CoroutineScope, private val tr: () -> Tr) {
    var status by mutableStateOf<JsonObject?>(null)
    var draft by mutableStateOf<JsonObject?>(null)
    var loadError by mutableStateOf<Throwable?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var notice by mutableStateOf("")
    var left by mutableLongStateOf(0)
    var selected by mutableStateOf<Pair<String, String>?>(null)

    val services: List<JsonObject> get() = status.objs("services")
    val base: JsonObject? get() = status?.let { (it["pending"] as? JsonObject) ?: it.o("config") }
    val pending: Boolean get() = status?.get("pending") is JsonObject
    val dirty: Boolean get() = draft != null && base != null && !samePolicies(draft!!, base!!, services)
    val valid: Boolean get() = draft?.let { d -> d.objs("cells").all { c -> c.objs("policies").all { policyProblem(it).isEmpty() } } && ownZones(d).all { zoneProblem(it).isEmpty() } } ?: false
    val locked: Boolean get() = busy || pending
    val apps: List<Pair<String, String>> get() = firewallApps(status, draft)

    /** Übernimmt eine Antwort; ein Entwurf in Arbeit bleibt, wie er ist. */
    fun take(next: JsonObject) {
        val keep = dirty
        status = next
        left = next.long("pendingSeconds")
        if (!keep || draft == null) draft = base
        val sel = selected
        if (sel != null && draft.objs("zones").none { it.str("id") == sel.first }) selected = null
    }

    suspend fun load() {
        try {
            take(api.obj("firewall"))
            loadError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Während einer Probe darf eine Abfrage scheitern; der letzte Stand bleibt.
            if (status == null) loadError = e
        }
    }

    fun reload() = scope.launch { load() }

    /** Eine Änderung mit Fingerabdruck; die Antwort ist der neue Stand. */
    fun change(context: Context, ok: String? = null, call: suspend () -> JsonElement, after: () -> Unit = {}) = Guard.require(context, GuardAction.Network) {
        scope.launch {
            busy = true
            error = ""
            notice = ""
            try {
                val next = call() as? JsonObject
                if (next != null) take(next)
                after()
                if (ok != null) notice = ok
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr().errorOf(e, detail = true)
                load()
            } finally {
                busy = false
            }
        }
    }

    fun apply(context: Context) {
        val d = draft ?: return
        if (busy) return
        Guard.require(context, GuardAction.Network) {
            scope.launch {
                busy = true
                error = ""
                notice = ""
                try {
                    val next = api.put("firewall", d) as? JsonObject
                    draft = null
                    if (next != null) take(next)
                    notice = if (pending) "" else tr().t("ui.saved")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr().errorOf(e, detail = true)
                } finally {
                    busy = false
                }
            }
        }
    }

    fun trial(context: Context, action: String) = change(context, ok = tr().t(if (action == "confirm") "firewall.confirmed" else "firewall.reverted"), call = {
        val next = api.post("firewall/$action")
        draft = null
        next
    })

    fun discard() {
        draft = base
        error = ""
    }

    fun edit(change: (JsonObject) -> JsonObject) {
        draft = draft?.let(change)
    }

    fun zoneLabel(tr: Tr, id: String): String {
        if (id in BUILTIN_ZONES) return tr.t("firewall.zone.$id")
        val own = (draft ?: status.o("config")).objs("zones").firstOrNull { it.str("id") == id }
            ?: status.o("config").objs("zones").firstOrNull { it.str("id") == id }
        return own?.str("name")?.ifEmpty { null } ?: id
    }

    fun destLabel(tr: Tr, id: String): String = when (id) {
        NAS -> tr.t("firewall.toNas")
        APPS -> tr.t("firewall.toApps")
        else -> zoneLabel(tr, id)
    }

    fun zoneText(tr: Tr, id: String): String {
        if (id in BUILTIN_ZONES) return tr.t("firewall.zoneText.$id")
        val own = draft.objs("zones").firstOrNull { it.str("id") == id } ?: return ""
        return tr.t("firewall.zoneMembers", "networks" to own.strings("networks").size, "sources" to own.strings("sources").size)
    }
}

/**
 * Einstellungen › Firewall (routes/settings/firewall): Hauptschalter und die
 * Reiter Zonen (Matrix Absender × Ziel mit den Richtlinien der gewählten
 * Zelle), Netze, Richtlinien, automatisches Sperren und „Zuletzt gesperrt“.
 * Zonen, Netze und Richtlinien gehen bei eingeschalteter Firewall zusammen
 * auf Probe; das automatische Sperren wird für sich gespeichert.
 */
@Composable
fun FirewallPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = remember { FwModel(env.api, scope) { tr } }
    val tab = fwTab(a.tab)
    val trialTab = tab == "zones" || tab == "networks" || tab == "policies"

    LaunchedEffect(m) { m.load() }
    // Während einer Probe: der Countdown jede Sekunde, der Stand alle fünf.
    LaunchedEffect(m.pending) {
        var tick = 0
        while (m.pending) {
            delay(1000)
            if (m.left > 0) m.left = m.left - 1
            if (++tick % 5 == 0) m.load()
        }
    }

    val status = m.status
    val draft = m.draft
    if (status == null || draft == null) {
        m.loadError?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { m.reload() }) }
            ?: LoadingLine(t("common.loading"))
        return
    }
    if (m.pending && trialTab) {
        TrialBox(
            title = t("firewall.trial", "time" to countdown(m.left)),
            hint = t("firewall.trialHint"),
            confirmLabel = t("firewall.confirm"),
            revertLabel = t("firewall.revert"),
            busy = m.busy,
            onConfirm = { m.trial(context, "confirm") },
            onRevert = { m.trial(context, "revert") },
        )
    }
    val chip = when {
        status.str("error").isNotEmpty() -> t("firewall.stateError") to Tone.Error
        m.pending -> t("firewall.stateTrial") to Tone.Warn
        status.o("config").bool("enabled") -> t("firewall.stateOn") to Tone.Ok
        status.bool("active") -> t("firewall.stateBlocksOnly") to Tone.Info
        else -> t("firewall.stateOff") to Tone.Inactive
    }
    SettingGroup {
        SettingRow(
            t("firewall.enable"),
            desc = t("firewall.intro"),
            icon = WebIcons["shield"],
            control = {
                StatusChip(chip.first, chip.second)
                Switch(draft.bool("enabled"), { on -> m.edit { it.with("enabled" to on) } }, t("firewall.enable"), enabled = !m.locked)
            },
        )
        if (status.str("clientAddress").isNotEmpty()) {
            Hint(t("firewall.you", "address" to status.str("clientAddress"), "zone" to (status.str("clientZone").takeIf { it.isNotEmpty() }?.let { m.zoneLabel(tr, it) } ?: "–")))
        }
        if (status.str("error").isNotEmpty()) Banner(t("firewall.loadError", "detail" to status.str("error")), tone = Tone.Error)
        if (status.str("dockerError").isNotEmpty() && status.o("config").bool("enabled")) {
            Banner(t("firewall.dockerError", "detail" to status.str("dockerError")), tone = Tone.Warn)
        }
    }
    val blocked = status.objs("blocked").size
    Tabs(tab, FW_TABS.map { id -> id to (t("firewall.tab.$id") + if (id == "autoblock" && blocked > 0) " ($blocked)" else "") }, { a.tab = it })
    when (tab) {
        "zones" -> ZonesTab(m)
        "networks" -> FwNetworksTab(m)
        "policies" -> FwPoliciesTab(m)
        "autoblock" -> FwAutoBlockTab(m)
        "recent" -> FwRecentTab(m)
    }
    if (trialTab && (m.dirty || m.error.isNotEmpty() || m.notice.isNotEmpty())) {
        if (m.dirty) {
            ActionBox(null, t(if (draft.bool("enabled")) "firewall.unsavedTrial" else "firewall.unsaved")) {
                SmallButton(t("common.cancel"), { m.discard() }, enabled = !m.busy)
                SmallButton(t(if (draft.bool("enabled")) "firewall.applyTrial" else "common.save"), { m.apply(context) }, kind = ButtonKind.Primary, enabled = !m.busy && m.valid)
            }
        }
        Note(m.error)
        if (m.notice.isNotEmpty() && !m.dirty) Banner(m.notice, tone = Tone.Ok)
    }
}

/** Zonen: die Matrix (am Handy als Liste je Absender), die gewählte Zelle und die Zonen mit ihren Netzen. */
@Composable
private fun ZonesTab(m: FwModel) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val status = m.status ?: return
    val draft = m.draft ?: return
    SettingGroup(t("firewall.zones"), desc = t("firewall.matrixText")) {
        val columns = listOf(NAS, APPS) + draft.objs("zones").map { it.str("id") }
        for (zone in draft.objs("zones")) {
            val from = zone.str("id")
            SettingRow(m.zoneLabel(tr, from), desc = m.zoneText(tr, from).ifEmpty { null }, id = "fw-from-$from", startOpen = true) {
                val grey = mutableListOf<String>()
                for (to in columns) {
                    if (!routable(draft, from, to)) {
                        grey += m.destLabel(tr, to)
                        continue
                    }
                    val cell = cellOf(draft, m.services, from, to)
                    MatrixCell(
                        to = m.destLabel(tr, to),
                        title = tr.t("firewall.cellTitle", "from" to m.zoneLabel(tr, from), "to" to m.destLabel(tr, to)),
                        state = cellState(cell),
                        count = exceptions(cell),
                        allowDefault = cell.str("default") == "allow",
                        selected = m.selected == (from to to),
                    ) {
                        if (m.selected == (from to to)) {
                            m.selected = null
                        } else {
                            m.edit { editCell(it, m.services, from, to) }
                            m.selected = from to to
                        }
                    }
                }
                if (grey.isNotEmpty()) Hint("— " + t("firewall.notThroughNas") + ": " + grey.joinToString(", "))
            }
        }
        Hint(t("firewall.notThroughNasText"))
        Hint(t("firewall.always"))
    }
    m.selected?.let { (from, to) -> CellPanel(m, from, to) }

    SettingGroup(
        t("firewall.zoneList"),
        tools = {
            SmallButton(t("firewall.zoneAdd"), {
                val fresh = io.github.ra5on.titanos.settings.common.jobj("id" to newZoneId(draft), "name" to "", "networks" to emptyList<String>(), "sources" to emptyList<String>())
                ov.show { close -> ZoneDialog(m, fresh, true, close) }
            }, enabled = !m.locked && ownZones(draft).size < 16)
        },
    ) {
        SettingRow(m.zoneLabel(tr, "home"), icon = WebIcons["home"], control = { Mono(status.strings("homeNetworks").joinToString(", ").ifEmpty { "–" }) })
        SettingRow(m.zoneLabel(tr, "vpn"), icon = WebIcons["tailnet"], control = { Mono(status.strings("vpnNetworks").joinToString(", ") + " · tailscale0, wt0") })
        SettingRow(m.zoneLabel(tr, "internet"), icon = WebIcons["globe"], desc = t("firewall.internetText"))
        SettingRow(m.zoneLabel(tr, "docker"), icon = WebIcons["box"], desc = t("firewall.dockerText"))
        val own = ownZones(draft)
        own.forEachIndexed { i, zone ->
            SettingRow(
                zone.str("name").ifEmpty { zone.str("id") },
                icon = WebIcons["shield"],
                desc = (zone.strings("networks").map { it.replace(Regex("^[a-z]+:"), "") } + zone.strings("sources")).joinToString(", ").ifEmpty { "–" },
                control = {
                    ArrowButton("↑", t("firewall.ruleUp"), !m.locked && i > 0) { m.edit { moveZone(it, zone.str("id"), -1) } }
                    ArrowButton("↓", t("firewall.ruleDown"), !m.locked && i < own.size - 1) { m.edit { moveZone(it, zone.str("id"), 1) } }
                    SmallButton(t("firewall.zoneEdit"), { ov.show { close -> ZoneDialog(m, zone, false, close) } }, enabled = !m.locked)
                },
            )
        }
        Hint(t("firewall.zonesHint"))
        Hint(t("firewall.zoneOrder"))
        Hint(t("firewall.bypassText"))
    }
}

/** Eine Zelle der Matrix als Zeile: Ziel, Zustand in Worten mit Symbol, Ausnahmen; Tippen zeigt ihre Richtlinien. */
@Composable
private fun MatrixCell(to: String, title: String, state: String, count: Int, allowDefault: Boolean, selected: Boolean, onClick: () -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    val tone = when (state) {
        "allow" -> Tone.Ok
        "deny" -> Tone.Error
        else -> Tone.Warn
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape)
            .background(if (selected) c.accent.copy(alpha = .14f) else c.bg)
            .border(1.dp, if (selected) c.accent else c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                this.selected = selected
            }
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(to, style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        StatusChip(t("firewall.cell.$state"), tone)
        if (count > 0) Text(t(if (allowDefault) "firewall.cellBlocked" else "firewall.cellAllowed", "count" to count), style = TitanType.rowSub, color = c.muted)
    }
}

/** Kleiner Knopf mit Pfeil und Namen für Bildschirmleser (Reihenfolge ändern). */
@Composable
internal fun ArrowButton(arrow: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    Text(
        arrow,
        style = TitanType.button,
        color = if (enabled) c.text else c.muted,
        modifier = Modifier.clip(shape).background(c.chip).border(1.dp, c.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

internal fun moveZone(cfg: JsonObject, id: String, by: Int): JsonObject {
    val zones = cfg.objs("zones").toMutableList()
    val i = zones.indexOfFirst { it.str("id") == id }
    val j = i + by
    if (i < 0 || j < 0 || j >= zones.size || zones[j].bool("builtin")) return cfg
    val tmp = zones[i]
    zones[i] = zones[j]
    zones[j] = tmp
    return cfg.with("zones" to zones)
}

/** Die Richtlinien der gewählten Zelle in ihrer Reihenfolge und ihre Vorgabe (CellPanel.svelte). */
@Composable
private fun CellPanel(m: FwModel, from: String, to: String) {
    val tr = LocalTr.current
    val draft = m.draft ?: return
    val cell = draft.objs("cells").firstOrNull { it.str("from") == from && it.str("to") == to } ?: return
    val policies = cell.objs("policies")
    fun update(change: (JsonObject) -> JsonObject) = m.edit { updateCell(it, m.services, from, to, change) }
    SettingGroup(
        t("firewall.cellTitle", "from" to m.zoneLabel(tr, from), "to" to m.destLabel(tr, to)),
        desc = t("firewall.cellText"),
        tools = { SmallButton(t("common.close"), { m.selected = null }) },
    ) {
        if (to == APPS) Hint(t("firewall.appsText"))
        val hostApps = m.status.objs("apps").filter { it.bool("hostNetwork") }.map { it.str("name") }
        if (to == APPS && hostApps.isNotEmpty()) Banner(t("firewall.hostNetworkHint", "apps" to hostApps.joinToString(", ")), tone = Tone.Warn)
        if (policies.isEmpty()) Hint(t("firewall.noPolicies"))
        policies.forEachIndexed { i, p ->
            PolicyEditor(
                policy = p,
                to = to,
                services = m.services,
                apps = m.apps,
                index = i,
                count = policies.size,
                locked = m.locked,
                onChange = { next -> update { c -> c.with("policies" to c.objs("policies").mapIndexed { j, o -> if (j == i) next else o }) } },
                onMove = { by -> update { c -> c.with("policies" to swap(c.objs("policies"), i, i + by)) } },
                onRemove = { update { c -> c.with("policies" to c.objs("policies").filterIndexed { j, _ -> j != i }) } },
            )
        }
        Buttons {
            SmallButton(t("firewall.addPolicy"), {
                update { c -> c.with("policies" to c.objs("policies") + newPolicy(to, m.services, m.apps)) }
            }, enabled = !m.locked && policies.size < 640)
        }
        SettingRow(t("firewall.default"), control = {
            Segments(cell.str("default"), listOf("allow" to t("firewall.allow"), "deny" to t("firewall.deny")), { d -> update { it.with("default" to d) } }, enabled = !m.locked)
        })
    }
}

internal fun swap(list: List<JsonObject>, i: Int, j: Int): List<JsonObject> {
    if (j < 0 || j >= list.size || i < 0 || i >= list.size) return list
    val out = list.toMutableList()
    val tmp = out[i]
    out[i] = out[j]
    out[j] = tmp
    return out
}
