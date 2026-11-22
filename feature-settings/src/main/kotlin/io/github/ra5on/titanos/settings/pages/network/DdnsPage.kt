package io.github.ra5on.titanos.settings.pages.network

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val DDNS_INTERVALS = listOf(5L, 10L, 15L, 30L, 60L, 360L, 1440L)
internal const val DDNS_MAX_HOSTS = 8

/**
 * Was `PUT network/ddns` bekommt: die gespeicherten Einstellungen mit den
 * geänderten Feldern (unbekannte bleiben), der Anbieter nur mit seinen
 * Feldern, der Healthcheck unverändert (er hat seinen eigenen Teil).
 */
internal fun ddnsBody(
    settings: JsonObject,
    enabled: Boolean,
    hostnames: List<String>,
    ipv4: Boolean,
    ipv6: Boolean,
    interval: Long,
    lookupURL: String,
    iface: String,
    provider: JsonObject,
    cleared: List<String>,
): JsonObject = settings.with(
    "enabled" to enabled,
    "hostnames" to hostnames,
    "ipv4" to ipv4,
    "ipv6" to ipv6,
    "interval" to interval,
    "lookupURL" to lookupURL.trim(),
    "interface" to iface.trim(),
    "provider" to providerPayload(provider, "address"),
    "heartbeat" to settings.o("heartbeat"),
    "clearSecrets" to cleared,
)

/** Der Text eines IPv64-Fehlers mit Grund (failure() im Web). */
internal fun Tr.ipv64Error(e: Throwable): String {
    val api = e as? io.github.ra5on.titanos.net.ApiException
    if (api != null && api.code.startsWith("ipv64_") && api.code != "ipv64_no_key") return error(api.code) + " " + api.message?.substringAfter(": ", "").orEmpty()
    return errorOf(e)
}

/**
 * Dynamisches DNS (Paket ddns): Namen auf die öffentliche IPv4-Adresse des
 * Anschlusses und die globale IPv6-Adresse des NAS. Mit IPv64 als Anbieter
 * auch Domains und der Healthcheck „Erreichbarkeit überwachen“.
 */
@Composable
fun DdnsPage(a: PageArgs) {
    val env = LocalEnv.current
    val state = rememberLoad { env.api.obj("network/ddns") }
    Loading(state) { loaded -> DdnsContent(env.api, loaded) }
}

@Composable
private fun DdnsContent(api: SettingsApi, loaded: JsonObject) {
    val tr = LocalTr.current
    val run = rememberRunner()
    var status by remember(loaded) { mutableStateOf(loaded) }
    var round by remember { mutableStateOf(0) }
    val set = status.o("settings")
    var enabled by remember(loaded, round) { mutableStateOf(set.bool("enabled")) }
    var names by remember(loaded, round) { mutableStateOf(set.strings("hostnames").joinToString("\n")) }
    var ipv4 by remember(loaded, round) { mutableStateOf(set.bool("ipv4", true)) }
    var ipv6 by remember(loaded, round) { mutableStateOf(set.bool("ipv6", true)) }
    var interval by remember(loaded, round) { mutableStateOf(set.long("interval")) }
    var lookup by remember(loaded, round) { mutableStateOf(set.str("lookupURL")) }
    var iface by remember(loaded, round) { mutableStateOf(set.str("interface")) }
    var provider by remember(loaded, round) { mutableStateOf(providerForm(set.o("provider"))) }
    var cleared by remember(loaded, round) { mutableStateOf(listOf<String>()) }
    var saveError by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { delay(5000); saved = false } }
    // Solange ein Abgleich läuft, folgt die Seite ihm.
    LaunchedEffect(status.bool("running")) {
        while (status.bool("running")) {
            delay(3000)
            runCatching { status = api.obj("network/ddns") }
        }
    }
    val hostnames = parseNames(names)
    val bad = hostnames.filter { !validHostName(it) }
    val missing = missingFields(provider, set.o("provider"), status.o("secrets"), cleared, "address")
    val valid = bad.isEmpty() && hostnames.size <= DDNS_MAX_HOSTS &&
        (!enabled || (hostnames.isNotEmpty() && (ipv4 || ipv6) && provider.str("type").isNotEmpty() && missing.isEmpty()))

    DdnsStatusPanel(api, status, { status = it }) {
        runCatching { status = api.obj("network/ddns") }
    }
    SettingGroup(t("ddns.settingsTitle")) {
        SettingRow(t("ddns.enable"), icon = WebIcons["power"], control = { Switch(enabled, { enabled = it }, t("ddns.enable"), enabled = !run.running) })
        DnsProviderFields(provider, { provider = it }, cleared, { cleared = it }, set.o("provider"), status.o("secrets"), "address", missing, run.running, "ddns-provider")
        SettingRow(t("ddns.hostnames"), desc = t("ddns.hostnamesHint"), icon = WebIcons["list"]) {
            Field(names, { names = it }, t("ddns.hostnames"), Modifier.fillMaxWidth(), placeholder = "nas.example.org", mono = true, enabled = !run.running, minHeight = 84.dp)
        }
        if (bad.isNotEmpty()) Banner(t("ddns.hostnamesInvalid", "names" to bad.joinToString(", ")), tone = Tone.Warn)
        if (hostnames.size > DDNS_MAX_HOSTS) Banner(t("ddns.hostnamesTooMany"), tone = Tone.Warn)
        SettingRow(t("ddns.ipv4"), desc = t("ddns.ipv4Hint"), icon = WebIcons["globe"], control = { Switch(ipv4, { ipv4 = it }, t("ddns.ipv4"), enabled = !run.running) })
        SettingRow(t("ddns.ipv6"), desc = t("ddns.ipv6Hint"), icon = WebIcons["globe"], control = { Switch(ipv6, { ipv6 = it }, t("ddns.ipv6"), enabled = !run.running) })
        SettingRow(t("ddns.interval"), icon = WebIcons["clock"], control = {
            val options = listOf(0L to t("ddns.intervalDefault")) + DDNS_INTERVALS.map { it to t("ddns.minutes", "count" to it) } +
                (if (interval != 0L && interval !in DDNS_INTERVALS) listOf(interval to t("ddns.minutes", "count" to interval)) else emptyList())
            Select(interval, options, { interval = it }, t("ddns.interval"), enabled = !run.running)
        })
        val lookupTitle = t("ddns.lookup") + " (" + t("dnsProvider.optional") + ")"
        SettingRow(lookupTitle, desc = t("ddns.lookupHint"), icon = WebIcons["search"], control = {
            Field(lookup, { lookup = it }, lookupTitle, placeholder = "https://api.ipify.org", mono = true, singleLine = true, enabled = !run.running)
        })
        val ifaceTitle = t("ddns.interface") + " (" + t("dnsProvider.optional") + ")"
        SettingRow(ifaceTitle, icon = WebIcons["columns"], control = {
            Field(iface, { iface = it }, ifaceTitle, placeholder = t("ddns.interfaceAuto"), mono = true, singleLine = true, enabled = !run.running)
        })
        Note(saveError)
        run.error?.let { Banner(it, tone = Tone.Error) }
        Buttons {
            SmallButton(t("common.save"), {
                val body = ddnsBody(set, enabled, hostnames, ipv4, ipv6, interval, lookup, iface, provider, cleared)
                saveError = ""
                saved = false
                run.run(guard = GuardAction.Network) {
                    try {
                        status = api.put("network/ddns", body) as? JsonObject ?: status
                        round++
                        saved = true
                    } catch (e: io.github.ra5on.titanos.net.ApiException) {
                        saveError = tr.errorWithReason(e, setOf("invalid_ddns"), Regex("^ddns: invalid settings: "))
                    }
                }
            }, kind = ButtonKind.Primary, enabled = !run.running && valid)
            if (run.running || saved) Hint(t(if (run.running) "ui.saving" else "ui.saved"))
        }
    }
    if (provider.str("type") == "ipv64") {
        Ipv64Panel(api, status, provider.str("token"), hostnames, { name -> if (name !in hostnames) names = (hostnames + name).joinToString("\n") }) { status = it }
    }
}

/** Der Stand: gefundene und veröffentlichte Adressen, letzte und nächste Prüfung, letzter Fehler, „Jetzt aktualisieren“ (DdnsStatus.svelte). */
@Composable
private fun DdnsStatusPanel(api: SettingsApi, status: JsonObject, onStatus: (JsonObject) -> Unit, onRefresh: suspend () -> Unit) {
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val run = rememberRunner()
    val set = status.o("settings")
    val badge = when {
        !set.bool("enabled") -> t("ddns.state.off") to Tone.Inactive
        status.str("lastError").isNotEmpty() -> t("ddns.state.error") to Tone.Warn
        status.str("lastUpdate").isNotEmpty() || status.str("publishedIPv4").isNotEmpty() || status.str("publishedIPv6").isNotEmpty() -> t("ddns.state.ok") to Tone.Ok
        else -> t("ddns.state.waiting") to Tone.Inactive
    }
    SettingGroup(t("ddns.statusTitle"), desc = t("ddns.intro")) {
        if (status.bool("disabled")) Banner(t("ddns.devMode"), tone = Tone.Warn)
        SettingRow(t("ddns.addresses"), icon = WebIcons["activity"], id = "ddns-status-now", startOpen = true, control = { StatusChip(badge.first, badge.second) }) {
            Fact(t("ddns.currentIPv4"), status.str("currentIPv4").ifEmpty { "–" }, mono = true)
            Fact(t("ddns.currentIPv6"), status.str("currentIPv6").ifEmpty { "–" }, mono = true)
            Fact(t("ddns.publishedIPv4"), status.str("publishedIPv4").ifEmpty { "–" }, mono = true)
            Fact(t("ddns.publishedIPv6"), status.str("publishedIPv6").ifEmpty { "–" }, mono = true)
            Fact(t("ddns.lastCheck"), tr.whenOr(status.str("lastCheck")))
            Fact(t("ddns.lastUpdate"), tr.whenOr(status.str("lastUpdate")))
            if (status.str("nextCheck").isNotEmpty()) Fact(t("ddns.nextCheck"), tr.whenOr(status.str("nextCheck")))
            if (status.str("lastError").isNotEmpty()) Banner(t("ddns.lastError") + "\n" + status.str("lastError"), tone = Tone.Error)
            Buttons {
                SmallButton(t("ddns.updateNow"), {
                    // Immer nur eine Aktualisierung; ein Blick drei Sekunden später.
                    run.run(guard = GuardAction.Network) {
                        (api.post("network/ddns/update") as? JsonObject)?.let(onStatus)
                        scope.launch {
                            delay(3000)
                            onRefresh()
                        }
                    }
                }, enabled = set.bool("enabled") && !status.bool("running") && !status.bool("disabled") && !run.running)
                SmallButton(t("ddns.refresh"), { scope.launch { onRefresh() } })
            }
        }
        run.error?.let { Banner(it, tone = Tone.Error) }
    }
}

/**
 * Was IPv64.net dem Abgleich bietet: Domains wählen und anlegen und der
 * Healthcheck (Ipv64Panel.svelte). Jeder Abruf zählt gegen die Grenzen des
 * Kontos, darum lädt jeder Teil erst auf Tippen.
 */
@Composable
private fun Ipv64Panel(api: SettingsApi, status: JsonObject, apiKey: String, hostnames: List<String>, onHost: (String) -> Unit, onStatus: (JsonObject) -> Unit) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyKnown = apiKey.isNotBlank() || status.o("secrets").bool("token")
    fun body(vararg extra: Pair<String, Any?>) = if (apiKey.isNotBlank()) jobj("apiKey" to apiKey.trim(), *extra) else jobj(*extra)
    var domains by remember { mutableStateOf<List<JsonObject>?>(null) }
    var zones by remember { mutableStateOf(IPV64_ZONES) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var wish by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf("ipv64.de") }
    fun task(guarded: Boolean = true, block: suspend () -> Unit) {
        val go = {
            scope.launch {
                busy = true
                error = ""
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr.ipv64Error(e)
                } finally {
                    busy = false
                }
            }
        }
        if (guarded) Guard.require(context, GuardAction.Network) { go() } else go()
    }
    suspend fun loadDomains() {
        val answer = api.post("network/ipv64/domains", body()) as? JsonObject
        domains = answer.objs("domains")
        answer?.strings("zones")?.takeIf { it.isNotEmpty() }?.let { zones = it }
    }
    val newName = "${wish.trim().lowercase()}.$zone"
    SettingGroup(t("ipv64.title")) {
        Hint(t("ipv64.intro"))
        if (!keyKnown) Banner(t("ipv64.needsKey"), tone = Tone.Warn)
        SubHead(t("ipv64.domains"))
        Buttons { SmallButton(t("ipv64.domainsLoad"), { task(guarded = false) { loadDomains() } }, enabled = keyKnown && !busy) }
        Note(error)
        if (notice.isNotEmpty()) Banner(notice, tone = Tone.Ok)
        domains?.let { list ->
            if (list.isEmpty()) Hint(t("ipv64.noDomains"))
            for (d in list) {
                val name = d.str("name")
                SettingRow(name, icon = WebIcons["globe"], id = "ipv64-$name", control = {
                    if (d.bool("wildcard")) StatusChip(t("ipv64.wildcardOn"), Tone.Ok)
                    if (name in hostnames) StatusChip(t("ipv64.inUse"), Tone.Info)
                }) {
                    Buttons {
                        if (name !in hostnames) SmallButton(t("ipv64.use"), { onHost(name) })
                        SmallButton(t(if (d.bool("wildcard")) "ipv64.wildcardOff" else "ipv64.wildcardTurnOn"), {
                            task {
                                val changed = api.post("network/ipv64/domains/change", body("domain" to name, "wildcard" to !d.bool("wildcard"))) as? JsonObject
                                if (changed != null) domains = domains?.map { if (it.str("name") == changed.str("name")) it.with("wildcard" to changed.bool("wildcard")) else it }
                            }
                        }, enabled = !busy)
                        SmallButton(t("ipv64.forApps"), {
                            ov.confirm(tr.t("ipv64.forAppsTitle"), tr.t("ipv64.forAppsText", "domain" to name), action = tr.t("ipv64.forApps"), cancel = tr.t("common.cancel"), danger = false) {
                                notice = ""
                                task {
                                    api.put("docker/proxy", jobj("domain" to name))
                                    notice = tr.t("ipv64.proxyDone", "domain" to name)
                                }
                            }
                        }, enabled = !busy)
                    }
                }
            }
            Hint(t("ipv64.wildcardHint"))
            LabeledField(t("ipv64.newName"), wish, { wish = it }, placeholder = t("ipv64.nameExample"), mono = true)
            LabeledSelect(t("ipv64.zone"), zone, zones.map { it to it }, { zone = it })
            Buttons {
                SmallButton(t("ipv64.create"), {
                    val name = newName
                    ov.confirm(tr.t("ipv64.createTitle"), tr.t("ipv64.createText", "name" to name), action = tr.t("ipv64.create"), cancel = tr.t("common.cancel"), danger = false) {
                        task {
                            val created = api.post("network/ipv64/domains/create", body("name" to name)) as? JsonObject
                            wish = ""
                            created?.str("name")?.let(onHost)
                            loadDomains()
                        }
                    }
                }, enabled = validIpv64Wish(wish.trim().lowercase()) && !busy)
            }
        }
        Heartbeat(api, status, apiKey, keyKnown, onStatus)
    }
}

/** „Erreichbarkeit überwachen“: der Push-Healthcheck von IPv64, den das NAS anpingt (Heartbeat.svelte). */
@Composable
private fun Heartbeat(api: SettingsApi, status: JsonObject, apiKey: String, keyKnown: Boolean, onStatus: (JsonObject) -> Unit) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val beat = status.o("settings").o("heartbeat")
    val live = status.o("heartbeat")
    fun body(vararg extra: Pair<String, Any?>) = if (apiKey.isNotBlank()) jobj("apiKey" to apiKey.trim(), *extra) else jobj(*extra)
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<JsonObject?>(null) }
    var testWait by remember { mutableStateOf(false) }
    var testNotice by remember { mutableStateOf("") }
    fun task(block: suspend () -> Unit) = Guard.require(context, GuardAction.Network) {
        scope.launch {
            busy = true
            error = ""
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.ipv64Error(e)
            } finally {
                busy = false
            }
        }
    }
    SubHead(t("ipv64.heartbeat"))
    Hint(t("ipv64.heartbeatHint"))
    Choice(t("ipv64.heartbeatEnable"), beat.bool("enabled"), {
        if (beat.bool("enabled")) {
            ov.show { close -> HeartbeatOff(beat.str("check"), keyKnown, busy, onClose = close) { action -> close(); task { (api.post("network/ipv64/heartbeat/off", body("action" to action)) as? JsonObject)?.let(onStatus); remote = null } } }
        } else {
            task {
                val answer = api.post("network/ipv64/healthchecks", body()) as? JsonObject
                ov.show { close -> HeartbeatOn(api, answer.objs("healthchecks"), answer.objs("integrations"), answer.str("suggestedName"), apiKey, close, onStatus) }
            }
        }
    }, enabled = !busy && (beat.bool("enabled") || keyKnown))
    if (beat.bool("enabled")) {
        Fact(t("ipv64.check"), beat.str("check").ifEmpty { "–" })
        Fact(t("ipv64.lastPing"), tr.whenOr(live.str("lastPing")))
        Fact(t("ipv64.nextPing"), tr.whenOr(live.str("nextPing")))
        Fact(t("ipv64.autoPause"), t(if (beat.bool("autoPause")) "ipv64.yes" else "ipv64.no"))
        remote?.let { r ->
            Fact(t("ipv64.remoteStatus"), r.str("status"))
            Fact(t("ipv64.lastSeen"), tr.whenOr(r.str("lastSeenAt")))
        }
        if (live.bool("paused")) Banner(t("ipv64.pausedForShutdown"), tone = Tone.Warn)
        Note(live.str("lastError"))
        Buttons {
            SmallButton(t("ipv64.remoteLoad"), { task { remote = api.post("network/ipv64/heartbeat/status", body()) as? JsonObject } }, enabled = keyKnown && !busy)
            SmallButton(t("ipv64.test"), {
                testWait = true
                testNotice = ""
                scope.launch { delay(30_000); testWait = false }
                task {
                    api.post("network/ipv64/heartbeat/test", body())
                    testNotice = tr.t("ipv64.testSent")
                }
            }, enabled = keyKnown && !testWait)
        }
        if (testNotice.isNotEmpty()) Hint(testNotice)
    }
    Note(error)
}

@Composable
private fun BoxScope.HeartbeatOn(
    api: SettingsApi,
    checks: List<JsonObject>,
    integrations: List<JsonObject>,
    suggested: String,
    apiKey: String,
    onClose: () -> Unit,
    onStatus: (JsonObject) -> Unit,
) {
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf("new") }
    var name by remember { mutableStateOf(suggested) }
    var period by remember { mutableStateOf("30") }
    var grace by remember { mutableStateOf("10") }
    var chosen by remember { mutableStateOf(listOf(0L)) }
    var autoPause by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val p = period.toLongOrNull() ?: 0
    val g = grace.toLongOrNull() ?: 0
    val valid = check != "new" || (name.isNotBlank() && p in 20..1440 && g in 1..1440)
    FormDialog(
        t("ipv64.onTitle"),
        onClose,
        listOf(
            Act(t("common.cancel"), enabled = !busy, onClick = onClose),
            Act(t("ipv64.onSubmit"), ButtonKind.Primary, enabled = !busy && valid) {
                val extra = if (check == "new") {
                    arrayOf<Pair<String, Any?>>("name" to name.trim(), "periodMinutes" to p, "graceMinutes" to g, "integrations" to chosen, "autoPause" to autoPause)
                } else {
                    arrayOf<Pair<String, Any?>>("id" to check, "autoPause" to autoPause)
                }
                val body = if (apiKey.isNotBlank()) jobj("apiKey" to apiKey.trim(), *extra) else jobj(*extra)
                Guard.require(context, GuardAction.Network) {
                    scope.launch {
                        busy = true
                        error = ""
                        try {
                            (api.post("network/ipv64/heartbeat/on", body) as? JsonObject)?.let(onStatus)
                            onClose()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = tr.ipv64Error(e)
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        ),
    ) {
        Hint(t("ipv64.onText"))
        LabeledSelect(t("ipv64.check"), check, listOf("new" to t("ipv64.newCheck")) + checks.map { it.str("id") to "${it.str("name")} (${it.str("status")})" }, { check = it })
        if (check == "new") {
            LabeledField(t("ipv64.checkName"), name, { name = it.take(100) })
            LabeledField(t("ipv64.period"), period, { period = it.filter(Char::isDigit).take(4) }, number = true)
            LabeledField(t("ipv64.grace"), grace, { grace = it.filter(Char::isDigit).take(4) }, number = true)
            Hint(t("ipv64.periodHint"))
            SubHead(t("ipv64.integrations"))
            for (i in integrations) {
                val id = i.long("id")
                Choice("${i.str("name")} (${i.str("type")})", id in chosen, { on -> chosen = if (on) (chosen + id).distinct() else chosen.filter { it != id } })
            }
        }
        Choice(t("ipv64.autoPauseLabel"), autoPause, { autoPause = it })
        Hint(t("ipv64.autoPauseHint"))
        Note(error)
    }
}

@Composable
private fun BoxScope.HeartbeatOff(check: String, keyKnown: Boolean, busy: Boolean, onClose: () -> Unit, onOff: (String) -> Unit) {
    FormDialog(
        t("ipv64.offTitle"),
        onClose,
        listOf(
            Act(t("ipv64.offDelete"), ButtonKind.Danger, enabled = !busy && keyKnown) { onOff("delete") },
            Act(t("ipv64.offPause"), enabled = !busy && keyKnown) { onOff("pause") },
            Act(t("ipv64.offOnly"), enabled = !busy) { onOff("") },
            Act(t("common.cancel"), enabled = !busy, onClick = onClose),
        ),
    ) {
        Hint(t("ipv64.offText", "check" to check))
    }
}
