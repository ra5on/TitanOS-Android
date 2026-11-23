package io.github.ra5on.titanos.docker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.SmallButton
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.delay

// Teile der App-Seite, die sofort gespeichert werden (ohne „Übernehmen“):
// HTTPS-Name (AppProxy.svelte), Zugriff (AppAccess.svelte), Öffnen mit und
// Freigaben (AppOpens.svelte), Veröffentlichen im Tailnet (TailnetDialog.svelte).

/** HTTPS-Name der App: eigene Felder, gespeichert für sich, ohne Neustart des Containers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppProxySection(api: DockerApi, app: String, members: List<DockerContainer>, version: Int) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    var status by remember(app) { mutableStateOf<ProxyStatus?>(null) }
    var loadError by remember(app) { mutableStateOf<Throwable?>(null) }
    var form by remember(app) { mutableStateOf(ProxyForm()) }
    var saved by remember(app) { mutableStateOf<ProxyRoute?>(null) }
    var open by remember(app) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    fun take(next: ProxyStatus) {
        status = next
        saved = next.apps.firstOrNull { it.app == app }
        saved?.let { form = proxyForm(it) }
    }
    LaunchedEffect(app, version, reload) {
        try {
            take(api.proxyStatus())
            if (saved == null) form = proxyForm(null, suggestedProxyName(app)).copy(port = suggestedProxyPort(members))
            loadError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e
        }
    }
    loadError?.let { ErrorRow(errorText(it)) { reload++ }; return }
    val s = status ?: run { Text(t("common.loading"), style = TitanType.rowSub, color = Titan.colors.muted); return }
    val localLabel = s.localName.ifEmpty { s.defaultLocalName }
    val problems = proxyProblems(form, s.apps.filter { it.app != app } + s.targets, localLabel)
    val changed = saved == null || proxyBody(form) != proxyBody(proxyForm(saved))
    if (!open) {
        val sv = saved
        if (sv != null && sv.enabled && !s.off && sv.urls.isNotEmpty()) {
            ClickRow(sv.urls[0]) { openUrl(context, sv.urls[0]) }
            if (sv.urls.size > 1) Text(t("appform.moreAddresses", "count" to sv.urls.size - 1), style = TitanType.rowSub, color = Titan.colors.muted)
        } else {
            Text(t(if (s.off) "proxy.allOffHint" else "appform.httpsNone"), style = TitanType.rowSub, color = Titan.colors.muted)
        }
        SmallButton(t(if (saved != null) "appform.change" else "appform.setUp"), TitanIcons.edit, { open = true })
        return
    }
    ProxyFields(form, { form = it; notice = null }, s, busy)
    if (changed) for ((key, params) in problems) Problem(t(key, *params.map { it.key to it.value }.toTypedArray()))
    val addresses = if (validProxyName(form.name.trim().lowercase())) s.suffixes.map { "https://${form.name.trim().lowercase()}.$it" } else emptyList()
    if (addresses.isNotEmpty() && form.enabled) {
        Text(t("proxy.addresses"), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 6.dp))
        for (a in addresses) ClickRow(a) { openUrl(context, a) }
    }
    error?.let { Problem(it) }
    notice?.let { StateMark(it, StateKind.Ok) }
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton(t("common.save"), TitanIcons.check, {
            busy = true
            error = null
            ui.run(onError = { e -> error = ui.errorOf(e); busy = false }) {
                take(api.saveProxy(app, proxyBody(form)))
                notice = ui.text("proxy.saved")
                busy = false
            }
        }, primary = true, enabled = !busy && changed && problems.isEmpty())
        if (saved != null) SmallButton(t("proxy.remove"), TitanIcons.trash, {
            busy = true
            ui.run(onError = { e -> error = ui.errorOf(e); busy = false }) {
                take(api.removeProxy(app))
                form = proxyForm(null, suggestedProxyName(app)).copy(port = suggestedProxyPort(members))
                notice = ui.text("proxy.removed")
                busy = false
            }
        }, danger = true, enabled = !busy)
    }
    if (s.suffixes.isNotEmpty()) Text(t("proxy.domainCurrent", "suffixes" to s.suffixes.joinToString(", ")), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 6.dp))
}

/** Felder einer Route (ProxyRouteFields.svelte). */
@Composable
private fun ProxyFields(f: ProxyForm, onChange: (ProxyForm) -> Unit, s: ProxyStatus, busy: Boolean) {
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)
    SwitchRow(t("proxy.enabled"), f.enabled, { onChange(f.copy(enabled = it)) }, enabled = !busy)
    Field(t("proxy.name"), f.name, { onChange(f.copy(name = it.lowercase())) }, hint = t("proxy.addressesPreview", "address" to (s.suffixes.firstOrNull()?.let { "https://${f.name}.$it" } ?: f.name)))
    Field(t("proxy.aliases"), f.aliases, { onChange(f.copy(aliases = it)) }, hint = t("proxy.aliasesHint"), placeholder = t("proxy.aliasesPlaceholder"))
    Field(t("proxy.domains"), f.domains, { onChange(f.copy(domains = it)) }, hint = t("proxy.domainsHint"))
    SwitchRow(t("proxy.short"), f.short, { onChange(f.copy(short = it)) }, sub = if (s.mdns) t("proxy.shortHint") else t("proxy.shortNoMdns"), enabled = !busy)
    Field("Port", if (f.port == 0) "" else f.port.toString(), { onChange(f.copy(port = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) }, hint = t("proxy.portHint"), keyboard = num)
    Choice("", listOf("http" to t("proxy.scheme.http"), "https" to t("proxy.scheme.https")), f.scheme, { onChange(f.copy(scheme = it)) })
    if (f.scheme == "https") SwitchRow(t("proxy.verifyTLS"), f.verifyTLS, { onChange(f.copy(verifyTLS = it)) }, sub = t("proxy.verifyTLSHint"))
    Choice(t("proxy.access"), listOf("local", "networks", "all").map { it to t("proxy.access.$it") }, f.access, { onChange(f.copy(access = it)) }, hint = if (f.access == "all") t("proxy.localOnlyOff") else null)
    if (f.access == "networks") Field(t("proxy.networks"), f.networks, { onChange(f.copy(networks = it)) }, hint = t("proxy.networksHint"))
    val tn = s.tailnet
    SwitchRow(
        t("proxy.tailscale"), f.tailscale, { onChange(f.copy(tailscale = it)) },
        sub = when {
            tn == null || !tn.available -> t("proxy.tailscaleNoPlugin")
            !tn.keySet -> t("proxy.tailscaleNoKey")
            else -> t("proxy.tailscaleHint")
        },
        enabled = !busy && (tn?.available == true || f.tailscale),
    )
    if (f.tailscale) Field(t("proxy.tailscaleName"), f.tailscaleName, { onChange(f.copy(tailscaleName = it.lowercase())) }, hint = t("proxy.tailscaleNameHint"))
}

@Composable
internal fun ErrorRow(text: String, onRetry: () -> Unit) {
    Problem(text)
    SmallButton(t("common.retry"), TitanIcons.restart, onRetry)
}

/** Welche Konten die App nutzen dürfen und ob ihre Seiten die TitanOS-Anmeldung verlangen (sofort gespeichert). */
@Composable
internal fun AppAccessSection(api: DockerApi, app: String) {
    val ui = LocalComputeUi.current
    var access by remember(app) { mutableStateOf<AppAccessState?>(null) }
    var error by remember(app) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(app) {
        try {
            access = api.access()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = ui.errorOf(e)
        }
    }
    error?.let { Problem(it) }
    val a = access ?: return
    val mine = a.apps.firstOrNull { it.app == app }
    if (mine == null) {
        Text(t("appaccess.empty"), style = TitanType.rowSub, color = Titan.colors.muted)
        return
    }
    fun store(change: (AccessApp) -> AccessApp) {
        if (busy) return
        busy = true
        error = null
        ui.run(onError = { e -> error = ui.errorOf(e); busy = false }) {
            access = api.saveAccess(AccessUpdate(a.newApps, a.apps.map { if (it.app == app) change(it) else it }))
            busy = false
        }
    }
    val normal = a.accounts.filter { it.role == "user" }
    Card {
        if (normal.isEmpty()) Text(t("appaccess.noUsers"), style = TitanType.rowSub, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
        normal.forEachIndexed { i, acc ->
            val on = acc.name in mine.users
            SwitchRow(acc.name, on, { v -> store { e -> e.copy(users = if (v) e.users + acc.name else e.users - acc.name) } }, enabled = !busy, divider = i > 0)
        }
    }
    Text(t("appaccess.adminAlways"), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 4.dp))
    SwitchRow(t("appaccess.gate"), mine.gate, { v -> store { it.copy(gate = v) } }, sub = t("appaccess.gateHint"), enabled = !busy)
}

/** „Öffnen mit“: Dateitypen, Programmzeile, Benutzer, Dienst; dazu die eingehängten Freigaben. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppOpensSection(api: DockerApi, app: String, services: List<String>, locked: Boolean, onChanged: () -> Unit) {
    val ui = LocalComputeUi.current
    var opens by remember(app) { mutableStateOf<AppOpens?>(null) }
    var loaded by remember(app) { mutableStateOf(false) }
    var editing by remember(app) { mutableStateOf(false) }
    var shares by remember { mutableStateOf<List<NasShare>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var types by remember(app) { mutableStateOf(listOf<String>()) }
    var typeInput by remember { mutableStateOf("") }
    var command by remember(app) { mutableStateOf("") }
    var user by remember(app) { mutableStateOf("") }
    var service by remember(app) { mutableStateOf("") }
    var shown by remember(app) { mutableStateOf(true) }
    var mountShare by remember { mutableStateOf("") }
    var mountRO by remember { mutableStateOf(false) }
    LaunchedEffect(app, reload) {
        val o = api.opens(app)
        opens = o
        loaded = true
        if (o != null) {
            types = o.types
            command = joinCommand(o.command)
            user = o.user
            service = o.service.ifEmpty { services.firstOrNull().orEmpty() }
            shown = !o.hidden
        }
        shares = api.shares()
    }
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        ui.run(onError = { e -> error = ui.errorOf(e); busy = false }) {
            block()
            busy = false
            editing = false
            reload++
            onChanged()
        }
    }
    Section(t("appopens.title"))
    if (!loaded) {
        Text(t("common.loading"), style = TitanType.rowSub, color = Titan.colors.muted)
        return
    }
    val o = opens
    if (o == null && !editing) {
        Text(t("appopens.noneHint"), style = TitanType.rowSub, color = Titan.colors.muted)
        SmallButton(t("appopens.setUp"), TitanIcons.plus, { editing = true; service = services.firstOrNull().orEmpty() }, enabled = !locked)
    } else {
        if (o?.error?.isNotEmpty() == true) Problem(o.error)
        SwitchRow(t("appopens.shown"), shown, { shown = it; editing = true }, sub = t("appopens.shownHint"), enabled = !locked && !busy)
        Text(t("appopens.types"), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (type in types) SmallButton(".$type", TitanIcons.x, { types = types - type; editing = true }, enabled = !locked)
        }
        Field(t("appopens.addType"), typeInput, { v ->
            if (v.endsWith(" ") || v.endsWith(",")) {
                val ext = v.trim(' ', ',', '.').lowercase()
                if (Regex("^[a-z0-9]{1,10}$").matches(ext) && ext !in types) types = types + ext
                typeInput = ""
                editing = true
            } else typeInput = v
        })
        val args = splitCommand(command)
        val problem = when {
            types.isEmpty() -> t("appopens.needTypes")
            args.size < 2 || args.count { it.contains("{file}") } != 1 || args[0] == "{file}" -> t("appopens.needFile")
            else -> ""
        }
        Field(t("appopens.command"), command, { command = it; editing = true }, hint = if (editing && problem.isNotEmpty()) problem else t("appopens.commandHint"))
        Field(t("appopens.user"), user, { user = it; editing = true }, hint = t("appopens.userHint"))
        if (services.size > 1) Choice(t("appopens.service"), services.map { it to it }, service, { service = it; editing = true })
        if (editing) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(t("appopens.save"), TitanIcons.check, {
                    run { api.saveOpens(app, OpensUpdate(types, args, user.trim(), o?.env.orEmpty(), service, !shown)) }
                }, primary = true, enabled = !locked && !busy && problem.isEmpty())
                SmallButton(t("common.cancel"), TitanIcons.x, { editing = false; reload++ })
            }
        }
        if (locked) Note(t("appopens.locked"))
    }
    error?.let { Problem(it) }

    Section(t("appopens.shares"))
    val mounted = o?.let { mountedShares(it) }.orEmpty()
    if (mounted.isEmpty()) Text(t("appopens.sharesNone"), style = TitanType.rowSub, color = Titan.colors.muted)
    Card {
        mounted.forEachIndexed { i, m ->
            if (i > 0) io.github.ra5on.titanos.ui.components.Divider()
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(m.share, style = TitanType.rowTitle, color = Titan.colors.text)
                Text("/shares/${m.share} · ${t(if (m.readOnly) "appopens.readOnly" else "appopens.readWrite")}", style = TitanType.rowSub, color = Titan.colors.muted)
                SmallButton(t("appopens.unmount"), TitanIcons.trash, {
                    ui.confirm(Ask(ui.text("appopens.unmountTitle", "share" to m.share), ui.text("appopens.unmountText", "share" to m.share), ui.text("appopens.unmount")) {
                        run { api.unmountShare(app, m.share) }
                    })
                }, danger = true, enabled = !locked && !busy)
            }
        }
    }
    val free = shares.filter { s -> !s.perUser && mounted.none { it.share == s.name } }
    if (free.isNotEmpty()) {
        Choice(t("appopens.mount"), listOf("" to t("appopens.pickShare")) + free.map { it.name to it.name }, mountShare, { mountShare = it }, hint = t("appopens.mountHint"))
        SwitchRow(t("appopens.readOnly"), mountRO, { mountRO = it })
        SmallButton(t("appopens.mountButton"), TitanIcons.plus, { val n = mountShare; mountShare = ""; run { api.mountShare(app, n, mountRO) } }, enabled = mountShare.isNotEmpty() && !locked && !busy)
    }
}

/** Im Tailnet veröffentlichen oder herausnehmen; folgt dem Gerät bis die Adresse antwortet. */
@Composable
internal fun ColumnScope.TailnetSheet(api: DockerApi, app: String, members: List<DockerContainer>, remove: Boolean, onChanged: () -> Unit) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    var status by remember { mutableStateOf<ProxyStatus?>(null) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var following by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<TailnetCheck?>(null) }
    var failed by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        try {
            status = api.proxyStatus()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e
        }
    }
    val route = status?.apps?.firstOrNull { it.app == app }
    val step = tailnetStep(route, check, failed)
    LaunchedEffect(following) {
        if (!following) return@LaunchedEffect
        var asked = 0L
        while (true) {
            runCatching { status = api.proxyStatus() }
            val now = System.currentTimeMillis()
            if (tailnetStep(status?.apps?.firstOrNull { it.app == app }, check, failed) == "check" && now - asked >= 5000) {
                asked = now
                val next = runCatching { api.checkTailnet(app) }.getOrNull()
                check = next
                if (next?.reachable != true) failed++
            }
            if (tailnetStep(status?.apps?.firstOrNull { it.app == app }, check, failed) == "ready") { onChanged(); break }
            delay(2000)
        }
    }
    if (remove) {
        SheetTitle(t("tailnetPublish.removeTitle", "name" to app))
        Text(t("tailnetPublish.removeText", "name" to app, "device" to (route?.let { it.tailscaleName.ifEmpty { it.name } } ?: app)), style = TitanType.body, color = Titan.colors.text)
        if (route?.tailnetOnly == true) Text(t("tailnetPublish.removeRoute"), style = TitanType.rowSub, color = Titan.colors.muted)
        Text(t("tailnetPublish.removeAdmin"), style = TitanType.rowSub, color = Titan.colors.muted)
        error?.let { Problem(it) }
        SheetButtons(t("docker.tailnetUnpublish"), enabled = !busy, danger = true) {
            busy = true
            ui.run(onError = { e -> error = ui.errorOf(e); busy = false }) {
                api.unpublishTailnet(app)
                onChanged()
                ui.sheet = null
            }
        }
        return
    }
    SheetTitle(t("tailnetPublish.title", "name" to app))
    loadError?.let { ErrorRow(errorText(it)) { loadError = null; reload++ }; return }
    val s = status ?: run { Text(t("common.loading"), style = TitanType.rowSub, color = Titan.colors.muted); return }
    val device = route?.takeIf { it.tailscale }?.let { it.tailscaleName.ifEmpty { it.name } } ?: suggestedProxyName(app).ifEmpty { "app" }
    if (!following && step == "off") {
        Text(t("tailnetPublish.intro", "name" to device), style = TitanType.body, color = Titan.colors.text)
        Text(t("tailnetPublish.introMore"), style = TitanType.rowSub, color = Titan.colors.muted)
        val available = s.tailnet?.available == true
        if (!available) Note(t("tailnetPublish.noPlugin"))
        if (s.off) Note(t("tailnetPublish.proxyOff"))
        if (available && s.tailnet?.keySet != true) {
            Field(t("proxy.tailscaleKey"), key, { key = it }, hint = t("tailnetPublish.keyHint"))
            ClickRow(t("tailnetPublish.keysLink")) { openUrl(context, TAILSCALE_KEYS_URL) }
        }
        Text(t("tailnetPublish.httpsOffBefore"), style = TitanType.small, color = Titan.colors.muted)
        error?.let { Problem(it) }
        SheetButtons(t("docker.tailnetPublish"), enabled = !busy && available && !s.off && (s.tailnet?.keySet == true || key.isNotBlank())) {
            busy = true
            error = null
            ui.run(onError = { e -> error = ui.errorOf(e); busy = false; reload++ }) {
                status = api.publishTailnet(app, suggestedProxyPort(members.sortedByDescending { it.webui.isNotBlank() }), key)
                key = ""
                check = null
                failed = 0
                following = true
                busy = false
                onChanged()
            }
        }
        return
    }
    val steps = listOf("start" to "tailnetPublish.stepStart", "login" to "tailnetPublish.stepJoin", "https-off" to "tailnetPublish.stepCert", "check" to "tailnetPublish.stepCheck")
    val at = steps.indexOfFirst { it.first == step }.let { if (step == "ready") steps.size else it }
    steps.forEachIndexed { i, (_, k) ->
        StateMark(t(k), if (i < at) StateKind.Ok else if (i == at) StateKind.Busy else StateKind.Off)
    }
    when (step) {
        "login" -> Note(t("tailnetPublish.login"))
        "https-off" -> {
            Note(t("tailnetPublish.httpsOff"))
            ClickRow(t("tailnetPublish.dnsLink")) { openUrl(context, TAILSCALE_DNS_URL) }
            Text(t("tailnetPublish.httpsOffWait"), style = TitanType.small, color = Titan.colors.muted)
        }
        "error" -> Problem(t("tailnetPublish.failed", "error" to route?.tailnet?.error.orEmpty()))
        "unreachable" -> Note(t("tailnetPublish.unreachable", "error" to check?.error.orEmpty()))
        "ready" -> StateMark(t("tailnetPublish.ready"), StateKind.Ok)
        else -> {}
    }
    val url = route?.tailnet?.url?.ifEmpty { null } ?: check?.url.orEmpty()
    if (url.isNotEmpty()) ClickRow(url) { openUrl(context, url) }
    Text(t("tailnetPublish.later"), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 8.dp))
}
