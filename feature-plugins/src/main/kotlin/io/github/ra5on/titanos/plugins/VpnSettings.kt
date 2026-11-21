package io.github.ra5on.titanos.plugins

import io.github.ra5on.titanos.admin.breakAfterHyphens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.ToggleRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.formatTime
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Tailscale (TailscaleSettings.svelte, TailscaleHttps.svelte)

/** Tailscale im Plugin-Detail: Verbindung, Anmelden, Probezeit, HTTPS, Routen, Exit-Node, Firewall-Hinweis. */
@Composable
internal fun TailscaleGroup(api: PluginsApi, pollMs: Long, onPending: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val uri = LocalUriHandler.current
    var status by remember { mutableStateOf<TailscaleStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("none") }
    var exitNode by remember { mutableStateOf("") }
    var acceptRoutes by remember { mutableStateOf(false) }
    var acceptDNS by remember { mutableStateOf(false) }
    var httpsBusy by remember { mutableStateOf(false) }
    var httpsError by remember { mutableStateOf<Throwable?>(null) }
    // Jede Änderung zählt hoch: eine Abfrage von davor wird verworfen.
    var generation by remember { mutableIntStateOf(0) }
    fun take(v: TailscaleStatus, reset: Boolean = false) {
        status = v
        onPending(v.pendingUntil != null)
        if (reset || !dirty) {
            val s = v.settings
            routes = s.routes.joinToString("\n")
            mode = if (s.advertiseExit) "provide" else if (s.exitNode.isNotEmpty()) "use" else "none"
            exitNode = s.exitNode
            acceptRoutes = s.acceptRoutes
            acceptDNS = s.acceptDNS
            dirty = false
        }
    }
    DisposableEffect(Unit) { onDispose { onPending(false) } }
    Polling(api, pollMs) {
        if (busy) return@Polling
        val asked = generation
        try {
            val v = api.tailscale()
            if (asked == generation && !busy) take(v)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (status == null && asked == generation) error = e
        }
    }
    fun run(reset: Boolean = true, call: suspend () -> TailscaleStatus) = scope.launch {
        generation++
        busy = true
        error = null
        try {
            take(call(), reset)
            loggingOut = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        busy = false
    }
    val s = status
    val locked = busy || s == null || !s.active || s.readOnly || s.pendingUntil != null
    SettingGroup(t("tailscale.connection")) {
        error?.let { Notice(errorText(it, detail = true), Modifier.padding(14.dp), tone = Tone.Error) }
        if (s == null) return@SettingGroup
        SettingRow(s.name.ifEmpty { t("tailscale.connection") }, icon = "tailnet", below = {
            for (ip in s.ips) Mono(ip, style = TitanType.small, color = c.muted)
            ChipLine {
                StatusChip(tKey("tailscale.states.${s.state}", s.state), if (s.state == "Running") Tone.Ok else Tone.Neutral)
                if (s.version.isNotEmpty()) Mono(s.version, style = TitanType.small, color = c.muted)
            }
        })
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.error?.takeIf { it.isNotEmpty() }?.let { Notice(it, tone = Tone.Warn) }
            for (w in s.health) Notice(w, tone = Tone.Warn)
            if (!s.active) Text(t("tailscale.activateHint"), style = TitanType.body, color = c.muted)
            ButtonRow {
                s.authUrl?.takeIf(::isLink)?.let { u -> ActionButton(t("tailscale.login"), { uri.openUri(u) }, primary = true, icon = "external") }
                if (s.state != "Running") ActionButton(t("tailscale.connect"), { run { api.tailscaleControl("connect") } }, enabled = !locked)
                else ActionButton(t("tailscale.disconnect"), { run { api.tailscaleControl("disconnect") } }, enabled = !locked)
                if (s.state != "NeedsLogin" && s.active) ActionButton(t("tailscale.logout"), { loggingOut = true }, enabled = !locked)
            }
            s.pendingUntil?.let { until ->
                Notice(t("tailscale.trial", "time" to formatTime(until)), tone = Tone.Warn) {
                    ButtonRow {
                        ActionButton(t("tailscale.confirm"), { run { api.tailscaleControl("confirm") } }, primary = true, enabled = !busy)
                        ActionButton(t("tailscale.undo"), { run { api.tailscaleControl("undo") } }, enabled = !busy)
                    }
                }
            }
        }
        // HTTPS-Zertifikat des eigenen Namens im Tailnet: ab Werk aus.
        val https = s.https
        val name = https?.name?.ifEmpty { null } ?: t("tailscale.httpsTheName")
        ToggleRow(
            t("tailscale.httpsTitle"),
            https?.enabled == true,
            { on ->
                scope.launch {
                    httpsBusy = true
                    httpsError = null
                    try {
                        take(api.tailscaleHttps(on))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        httpsError = e
                    }
                    httpsBusy = false
                }
            },
            desc = t("tailscale.httpsSwitch", "name" to name) + "\n" + t("tailscale.httpsWhat"),
            icon = "lock",
            enabled = !busy && !httpsBusy && !s.readOnly && s.pendingUntil == null,
            divider = true,
        )
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("tailscale.httpsNeeds"), style = TitanType.body, color = c.text)
            Bullets(listOf(t("tailscale.httpsNeedMagicDNS"), t("tailscale.httpsNeedCertificates")))
            Notice(breakAfterHyphens(t("tailscale.httpsPublic")), tone = Tone.Warn)
            httpsError?.let { Notice(errorText(it), tone = Tone.Error) }
            if (https?.enabled == true) {
                when {
                    https.state == "ok" && !https.name.isNullOrEmpty() -> Notice(
                        t("tailscale.httpsReady") + " https://${https.name}" + (https.notAfter?.let { " · " + t("tailscale.httpsUntil", "date" to formatDateTime(it)) } ?: ""),
                        tone = Tone.Ok,
                    )
                    https.state == "disabled" -> Notice(
                        t(if (https.enableUrl != null) "tailscale.httpsOffLink" else "tailscale.httpsDisabled", "name" to https.name.orEmpty()),
                        tone = Tone.Warn,
                    ) {
                        https.enableUrl?.takeIf(::isLink)?.let { u -> ActionButton(t("tailscale.httpsEnable"), { uri.openUri(u) }, primary = true, icon = "external") }
                    }
                    https.state == "error" -> Notice(t("tailscale.httpsError") + " " + https.error.orEmpty(), tone = Tone.Warn)
                    else -> Text(t("tailscale.httpsWaiting"), style = TitanType.body, color = c.muted)
                }
            }
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(t("tailscale.routes"), routes, { routes = it; dirty = true }, hint = t("tailscale.routesHint"), placeholder = "192.168.10.0/24", mono = true, singleLine = false, minLines = 3, enabled = !locked)
        }
        ChoiceRow(
            t("tailscale.exitMode"),
            listOf("none" to t("tailscale.exitNone"), "provide" to t("tailscale.exitProvide"), "use" to t("tailscale.exitUse")),
            mode,
            { mode = it; dirty = true },
            icon = "globe",
            enabled = !locked,
        )
        if (mode == "use") {
            val options = buildList {
                add("" to t("tailscale.chooseExit"))
                if (exitNode.isNotEmpty() && s.exitNodes.none { it.ip == exitNode }) add(exitNode to "$exitNode · ${t("tailscale.unavailable")}")
                for (peer in s.exitNodes) add(peer.ip to "${peer.name} · ${peer.ip}" + if (peer.online) "" else " · ${t("tailscale.offline")}")
            }
            ChoiceRow(t("tailscale.exitNode"), options, exitNode, { exitNode = it; dirty = true }, desc = t("tailscale.lanAccess"), divider = true, enabled = !locked)
        }
        ToggleRow(t("tailscale.acceptRoutes"), acceptRoutes, { acceptRoutes = it; dirty = true }, enabled = !locked, divider = true)
        ToggleRow(t("tailscale.acceptDNS"), acceptDNS, { acceptDNS = it; dirty = true }, enabled = !locked, divider = true)
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("tailscale.approvalHint"), style = TitanType.small, color = c.muted)
            ActionButton(t("tailscale.apply"), {
                run {
                    api.tailscaleSave(
                        TailscaleBody(
                            routes = routes.split(Regex("[\\s,]+")).filter { it.isNotEmpty() },
                            advertiseExit = mode == "provide",
                            exitNode = if (mode == "use") exitNode else "",
                            acceptRoutes = acceptRoutes,
                            acceptDNS = acceptDNS,
                        ),
                    )
                }
            }, primary = true, enabled = !locked && dirty && !(mode == "use" && exitNode.isEmpty()))
        }
        SettingRow(t("tailscale.firewall"), desc = t("tailscale.firewallHint"), icon = "shield", divider = true)
    }
    if (loggingOut) {
        Confirm(t("tailscale.logout"), t("tailscale.logoutHint"), t("tailscale.logout"), { run { api.tailscaleControl("logout") } }, { loggingOut = false }, enabled = !busy)
    }
}

// ---------------------------------------------------------------- NetBird (NetbirdSettings.svelte)

/** NetBird im Plugin-Detail: Verbindung, Setup-Key (nur verdeckt, nie gespeichert in der App), Einstellungen. */
@Composable
internal fun NetbirdGroup(api: PluginsApi, pollMs: Long, onPending: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    var status by remember { mutableStateOf<NetbirdStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var managementUrl by remember { mutableStateOf("") }
    var acceptRoutes by remember { mutableStateOf(false) }
    var serveRoutes by remember { mutableStateOf(false) }
    var acceptDNS by remember { mutableStateOf(false) }
    var setupKey by remember { mutableStateOf("") }
    var generation by remember { mutableIntStateOf(0) }
    fun take(v: NetbirdStatus, reset: Boolean = false) {
        status = v
        onPending(v.pendingUntil != null)
        if (reset || !dirty) {
            managementUrl = v.settings.managementUrl
            acceptRoutes = v.settings.acceptRoutes
            serveRoutes = v.settings.serveRoutes
            acceptDNS = v.settings.acceptDNS
            dirty = false
        }
    }
    DisposableEffect(Unit) { onDispose { onPending(false); setupKey = "" } }
    Polling(api, pollMs) {
        if (busy) return@Polling
        val asked = generation
        try {
            val v = api.netbird()
            if (asked == generation && !busy) take(v)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (status == null && asked == generation) error = e
        }
    }
    fun run(reset: Boolean = true, call: suspend () -> NetbirdStatus) = scope.launch {
        generation++
        busy = true
        error = null
        try {
            take(call(), reset)
            loggingOut = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        busy = false
    }
    val s = status
    val locked = busy || s == null || !s.active || s.readOnly || s.pendingUntil != null
    val online = s?.state == "Connected" || s?.state == "Connecting"
    val needsLogin = s?.state in setOf("NeedsLogin", "LoginFailed", "SessionExpired")
    SettingGroup(t("netbird.connection")) {
        error?.let { Notice(errorText(it, detail = true), Modifier.padding(14.dp), tone = Tone.Error) }
        if (s == null) return@SettingGroup
        SettingRow(s.fqdn.ifEmpty { t("netbird.connection") }, icon = "tailnet", below = {
            for (ip in s.ips) Mono(ip, style = TitanType.small, color = c.muted)
            if (s.active && s.management.isNotEmpty()) {
                Text(
                    t("netbird.managementState", "url" to s.management, "state" to t(if (s.managementConnected) "netbird.reachable" else "netbird.unreachable")),
                    style = TitanType.rowSub,
                    color = c.muted,
                )
            }
            if (s.state == "Connected") Text(t("netbird.peerCount", "connected" to s.peersConnected, "total" to s.peersTotal), style = TitanType.rowSub, color = c.muted)
            ChipLine {
                StatusChip(tKey("netbird.states.${s.state}", s.state), if (s.state == "Connected") Tone.Ok else Tone.Neutral)
                if (s.version.isNotEmpty()) Mono(s.version, style = TitanType.small, color = c.muted)
            }
        })
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.error?.takeIf { it.isNotEmpty() }?.let { Notice(it, tone = Tone.Warn) }
            for (w in s.health) Notice(w, tone = Tone.Warn)
            if (!s.active) Text(t("netbird.activateHint"), style = TitanType.body, color = c.muted)
            if (needsLogin && !s.setupKeySet) Text(t("netbird.keyNeeded"), style = TitanType.body, color = c.muted)
            ButtonRow {
                if (!online) ActionButton(t("netbird.connect"), { run { api.netbirdControl("connect") } }, enabled = !locked && !(needsLogin && !s.setupKeySet))
                else ActionButton(t("netbird.disconnect"), { run { api.netbirdControl("disconnect") } }, enabled = !locked)
                if (!needsLogin && s.active) ActionButton(t("netbird.logout"), { loggingOut = true }, enabled = !locked)
            }
            s.pendingUntil?.let { until ->
                Notice(t("netbird.trial", "time" to formatTime(until)), tone = Tone.Warn) {
                    ButtonRow {
                        ActionButton(t("netbird.confirm"), { run { api.netbirdControl("confirm") } }, primary = true, enabled = !busy)
                        ActionButton(t("netbird.undo"), { run { api.netbirdControl("undo") } }, enabled = !busy)
                    }
                }
            }
        }
        val keyLocked = busy || s.readOnly || s.pendingUntil != null
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(
                t("netbird.setupKey"),
                setupKey,
                { setupKey = it },
                hint = (if (s.setupKeySet) t("netbird.keySet") else t("netbird.keyUnset")) + " " + t("netbird.keyHint"),
                placeholder = t("netbird.keyPlaceholder"),
                password = true,
                enabled = !keyLocked,
            )
            ButtonRow {
                ActionButton(t("netbird.saveKey"), {
                    // Der Key verlässt die App einmal; das Feld wird in jedem Fall geleert.
                    val value = setupKey
                    setupKey = ""
                    run(reset = false) { api.netbirdKey(value) }
                }, primary = true, enabled = !keyLocked && setupKey.isNotBlank())
                if (s.setupKeySet) ActionButton(t("netbird.removeKey"), { run(reset = false) { api.netbirdKey("") } }, enabled = !keyLocked)
            }
        }
        Column(Modifier.padding(14.dp)) {
            Field(t("netbird.management"), managementUrl, { managementUrl = it; dirty = true }, hint = t("netbird.managementHint"), placeholder = "https://api.netbird.io:443", mono = true, enabled = !locked)
        }
        ToggleRow(t("netbird.acceptRoutes"), acceptRoutes, { acceptRoutes = it; dirty = true }, enabled = !locked, divider = true)
        ToggleRow(t("netbird.serveRoutes"), serveRoutes, { serveRoutes = it; dirty = true }, enabled = !locked, divider = true)
        ToggleRow(t("netbird.acceptDNS"), acceptDNS, { acceptDNS = it; dirty = true }, enabled = !locked, divider = true)
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("netbird.policyHint"), style = TitanType.small, color = c.muted)
            ActionButton(t("netbird.apply"), {
                run { api.netbirdSave(NetbirdBody(managementUrl.trim(), acceptRoutes, serveRoutes, acceptDNS)) }
            }, primary = true, enabled = !locked && dirty)
        }
        SettingRow(t("netbird.firewall"), desc = t("netbird.firewallHint"), icon = "shield", divider = true)
    }
    if (loggingOut) {
        Confirm(t("netbird.logout"), t("netbird.logoutHint"), t("netbird.logout"), { run { api.netbirdControl("logout") } }, { loggingOut = false }, enabled = !busy)
    }
}
