package io.github.ra5on.titanos.settings.pages.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.ra5on.titanos.settings.common.breakAtHyphens
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
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
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Fernzugriff an einem Ort: die Einstellungen der Plugins Tailscale und NetBird, wenn installiert, und Wege zum Rest (RemoteTab.svelte). */
@Composable
internal fun RemoteTab(a: PageArgs) {
    val env = LocalEnv.current
    var plugins by remember { mutableStateOf<List<JsonObject>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            plugins = (env.api.get("plugins") as? JsonObject).objs("plugins")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    fun installed(name: String) = plugins?.any { it.str("name") == name && it.bool("installed") } == true
    SettingGroup(t("netx.tab.remote")) {
        for ((name, title) in listOf("tailscale" to "Tailscale", "netbird" to "NetBird")) {
            if (installed(name)) {
                SettingRow(title, icon = WebIcons["tailnet"], id = "remote-$name", startOpen = true) {
                    if (name == "tailscale") TailscaleParts() else NetbirdParts()
                }
            } else {
                SettingRow(
                    title,
                    icon = WebIcons["tailnet"],
                    id = "remote-$name",
                    desc = when {
                        plugins == null && !failed -> t("netx.loading")
                        failed -> t("netx.remoteUnknown")
                        else -> t("netx.remoteMissing")
                    },
                    control = if (plugins != null || failed) ({ SmallButton(t("netx.remoteInstall"), { env.open("/plugins") }) }) else null,
                )
            }
        }
    }
    SettingGroup(t("netx.proxyTitle"), desc = t("netx.proxyHint")) {
        SettingRow(t("proxy.settingsTitle"), icon = WebIcons["lock"], onClick = { a.openPage("proxy") })
        SettingRow(t("netx.appLinks"), icon = WebIcons["external"], onClick = { env.open("/docker") })
    }
    SettingGroup(t("netx.dnsServices"), desc = t("netx.dnsHint")) {
        SettingRow(t("ddns.title"), icon = WebIcons["globe"], onClick = { a.openPage("ddns") })
    }
}

/**
 * Der Stand eines Plugins mit Abfrage alle 5 s (TailscaleSettings/NetbirdSettings):
 * jede Aktion erhöht die Runde, eine ältere Abfrage wird dann verworfen.
 */
@Stable
internal class PluginModel(private val api: SettingsApi, private val scope: CoroutineScope, private val path: String, private val tr: () -> Tr) {
    var status by mutableStateOf<JsonObject?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var dirty by mutableStateOf(false)

    /** Gesetzt, wenn der Stand neu ist und die Felder ihn übernehmen sollen. */
    var round by mutableStateOf(0)
    private var generation = 0

    fun take(value: JsonObject, reset: Boolean = false) {
        status = value
        if (reset || !dirty) {
            dirty = false
            round++
        }
    }

    suspend fun load() {
        if (busy) return
        val asked = generation
        try {
            val value = api.obj(path)
            if (asked == generation && !busy) take(value)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (status == null && asked == generation) error = tr().errorOf(e, detail = true)
        }
    }

    fun run(context: android.content.Context, reset: Boolean = true, call: suspend () -> Any?) = Guard.require(context, GuardAction.Network) {
        scope.launch {
            generation++
            busy = true
            error = ""
            try {
                (call() as? JsonObject)?.let { take(it, reset) }
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

@Composable
private fun rememberPlugin(path: String): PluginModel {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val m = remember(path) { PluginModel(env.api, scope, path) { tr } }
    LaunchedEffect(m) {
        while (true) {
            m.load()
            delay(5000)
        }
    }
    return m
}

private val TS_STATES = setOf("inactive", "Running", "Stopped", "NeedsLogin", "NeedsMachineAuth", "Starting", "NoState", "InUseOtherUser")
private val NB_STATES = setOf("inactive", "Idle", "Connecting", "Connected", "NeedsLogin", "LoginFailed", "SessionExpired")

/** Die Teile der Tailscale-Zeile: Verbindung, Anmelden/Trennen, Probe, HTTPS-Zertifikat, Subnetze, Exit Node. */
@Composable
private fun TailscaleParts() {
    val env = LocalEnv.current
    val context = LocalContext.current
    val ov = LocalOverlays.current
    val tr = LocalTr.current
    val m = rememberPlugin("plugins/tailscale")
    Note(m.error)
    val s = m.status ?: return
    val settings = s.o("settings")
    var routes by remember(m.round) { mutableStateOf(settings.strings("routes").joinToString("\n")) }
    var mode by remember(m.round) { mutableStateOf(if (settings.bool("advertiseExit")) "provide" else if (settings.str("exitNode").isNotEmpty()) "use" else "none") }
    var exitNode by remember(m.round) { mutableStateOf(settings.str("exitNode")) }
    var acceptRoutes by remember(m.round) { mutableStateOf(settings.bool("acceptRoutes")) }
    var acceptDNS by remember(m.round) { mutableStateOf(settings.bool("acceptDNS")) }
    val pending = s.str("pendingUntil").isNotEmpty()
    val locked = m.busy || !s.bool("active") || s.bool("readOnly") || pending
    val state = s.str("state")
    fun action(name: String) = m.run(context) { env.api.post("plugins/tailscale/control/$name") }

    SettingSub(
        s.str("name").ifEmpty { t("tailscale.connection") },
        desc = s.strings("ips").joinToString("\n").ifEmpty { null },
        control = {
            StatusChip(if (state in TS_STATES) t("tailscale.states.$state") else state, if (state == "Running") Tone.Ok else Tone.Inactive)
        },
    )
    if (s.str("version").isNotEmpty()) Mono(s.str("version"), muted = true)
    Note(s.str("error"), Tone.Warn)
    for (w in s.strings("health")) Banner(w, tone = Tone.Warn)
    if (!s.bool("active")) Hint(t("tailscale.activateHint"))
    Buttons {
        val auth = s.str("authUrl")
        if (auth.isNotEmpty()) SmallButton(t("tailscale.login"), { openUrl(context, auth) }, kind = ButtonKind.Primary)
        if (state != "Running") SmallButton(t("tailscale.connect"), { action("connect") }, enabled = !locked)
        else SmallButton(t("tailscale.disconnect"), { action("disconnect") }, enabled = !locked)
        if (state != "NeedsLogin" && s.bool("active")) SmallButton(t("tailscale.logout"), {
            ov.confirm(tr.t("tailscale.logout"), tr.t("tailscale.logoutHint"), action = tr.t("tailscale.logout"), cancel = tr.t("common.cancel")) { action("logout") }
        }, enabled = !locked)
    }
    if (pending) {
        ActionBox(null, t("tailscale.trial", "time" to timeOf(s.str("pendingUntil")))) {
            SmallButton(t("tailscale.undo"), { action("undo") }, enabled = !m.busy)
            SmallButton(t("tailscale.confirm"), { action("confirm") }, kind = ButtonKind.Primary, enabled = !m.busy)
        }
    }
    TailscaleHttps(s.o("https"), disabled = m.busy || s.bool("readOnly") || pending) { m.take(it) }

    SettingSub(t("tailscale.routes"), desc = t("tailscale.routesHint"))
    Field(routes, { routes = it; m.dirty = true }, t("tailscale.routes"), Modifier.fillMaxWidth(), placeholder = "192.168.10.0/24", mono = true, enabled = !locked, minHeight = 84.dp)
    SettingSub(t("tailscale.exitMode"), control = {
        Select(mode, listOf("none" to t("tailscale.exitNone"), "provide" to t("tailscale.exitProvide"), "use" to t("tailscale.exitUse")), { mode = it; m.dirty = true }, t("tailscale.exitMode"), enabled = !locked)
    })
    if (mode == "use") {
        val peers = s.objs("exitNodes")
        val options = buildList {
            add("" to t("tailscale.chooseExit"))
            if (exitNode.isNotEmpty() && peers.none { it.str("ip") == exitNode }) add(exitNode to "$exitNode · ${t("tailscale.unavailable")}")
            for (p in peers) add(p.str("ip") to "${p.str("name")} · ${p.str("ip")}" + if (p.bool("online")) "" else " · ${t("tailscale.offline")}")
        }
        SettingSub(t("tailscale.exitNode"), desc = t("tailscale.lanAccess"), control = {
            Select(exitNode, options, { exitNode = it; m.dirty = true }, t("tailscale.exitNode"), enabled = !locked)
        })
    }
    SettingSub(t("tailscale.acceptRoutes"), control = { Switch(acceptRoutes, { acceptRoutes = it; m.dirty = true }, t("tailscale.acceptRoutes"), enabled = !locked) })
    SettingSub(t("tailscale.acceptDNS"), control = { Switch(acceptDNS, { acceptDNS = it; m.dirty = true }, t("tailscale.acceptDNS"), enabled = !locked) })
    Hint(t("tailscale.approvalHint"))
    Buttons {
        SmallButton(t("tailscale.apply"), {
            val body = settings.with(
                "routes" to routes.split(Regex("[\\s,]+")).filter { it.isNotEmpty() },
                "advertiseExit" to (mode == "provide"),
                "exitNode" to if (mode == "use") exitNode else "",
                "acceptRoutes" to acceptRoutes,
                "acceptDNS" to acceptDNS,
            )
            m.run(context) { env.api.put("plugins/tailscale", body) }
        }, kind = ButtonKind.Primary, enabled = !locked && m.dirty && !(mode == "use" && exitNode.isEmpty()))
    }
    SettingSub(t("tailscale.firewall"), desc = t("tailscale.firewallHint"))
}

/** Das Zertifikat des eigenen Namens im Tailnet: ein Schalter, ab Werk aus, mit dem, was Tailscale dafür braucht (TailscaleHttps.svelte). */
@Composable
private fun TailscaleHttps(https: JsonObject, disabled: Boolean, onUpdate: (JsonObject) -> Unit) {
    val env = LocalEnv.current
    val context = LocalContext.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val enabled = https.bool("enabled")
    val name = https.str("name").ifEmpty { t("tailscale.httpsTheName") }
    SettingSub(
        t("tailscale.httpsTitle"),
        desc = t("tailscale.httpsSwitch", "name" to name) + "\n" + t("tailscale.httpsWhat"),
        control = {
            Switch(enabled, { on ->
                Guard.require(context, GuardAction.Network) {
                    scope.launch {
                        busy = true
                        error = ""
                        try {
                            (env.api.put("plugins/tailscale/https", jobj("enabled" to on)) as? JsonObject)?.let(onUpdate)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = tr.errorOf(e)
                        }
                        busy = false
                    }
                }
            }, t("tailscale.httpsSwitch", "name" to name), enabled = !disabled && !busy)
        },
    )
    Hint(t("tailscale.httpsNeeds") + "\n• " + t("tailscale.httpsNeedMagicDNS") + "\n• " + t("tailscale.httpsNeedCertificates"))
    Banner(breakAtHyphens(t("tailscale.httpsPublic")), tone = Tone.Warn)
    Note(error)
    if (enabled) {
        when (https.str("state")) {
            "ok" -> if (https.str("name").isNotEmpty()) {
                Hint(t("tailscale.httpsReady"))
                LinkText("https://${https.str("name")}") { openUrl(context, "https://${https.str("name")}") }
                if (https.str("notAfter").isNotEmpty()) Hint(t("tailscale.httpsUntil", "date" to tr.whenOr(https.str("notAfter"))))
            } else {
                Hint(t("tailscale.httpsWaiting"))
            }
            "disabled" -> {
                val link = https.str("enableUrl")
                ActionBox(null, t(if (link.isNotEmpty()) "tailscale.httpsOffLink" else "tailscale.httpsDisabled", "name" to https.str("name"))) {
                    if (link.isNotEmpty()) SmallButton(t("tailscale.httpsEnable"), { openUrl(context, link) }, kind = ButtonKind.Primary)
                }
            }
            "error" -> Banner(t("tailscale.httpsError") + " " + https.str("error"), tone = Tone.Warn)
            else -> Hint(t("tailscale.httpsWaiting"))
        }
    }
}

/** Die Teile der NetBird-Zeile: Verbindung, Setup-Key, Management-Server, Routen, Probe. */
@Composable
private fun NetbirdParts() {
    val env = LocalEnv.current
    val context = LocalContext.current
    val ov = LocalOverlays.current
    val tr = LocalTr.current
    val m = rememberPlugin("plugins/netbird")
    Note(m.error)
    val s = m.status ?: return
    val settings = s.o("settings")
    var managementUrl by remember(m.round) { mutableStateOf(settings.str("managementUrl")) }
    var acceptRoutes by remember(m.round) { mutableStateOf(settings.bool("acceptRoutes")) }
    var serveRoutes by remember(m.round) { mutableStateOf(settings.bool("serveRoutes")) }
    var acceptDNS by remember(m.round) { mutableStateOf(settings.bool("acceptDNS")) }
    var setupKey by remember { mutableStateOf("") }
    val pending = s.str("pendingUntil").isNotEmpty()
    val locked = m.busy || !s.bool("active") || s.bool("readOnly") || pending
    val state = s.str("state")
    val online = state == "Connected" || state == "Connecting"
    val needsLogin = state == "NeedsLogin" || state == "LoginFailed" || state == "SessionExpired"
    fun action(name: String) = m.run(context) { env.api.post("plugins/netbird/control/$name") }

    val lines = buildList {
        addAll(s.strings("ips"))
        if (s.bool("active") && s.str("management").isNotEmpty()) add(
            t("netbird.managementState", "url" to s.str("management"), "state" to t(if (s.bool("managementConnected")) "netbird.reachable" else "netbird.unreachable")),
        )
        if (state == "Connected") add(t("netbird.peerCount", "connected" to s.long("peersConnected"), "total" to s.long("peersTotal")))
    }
    SettingSub(
        s.str("fqdn").ifEmpty { t("netbird.connection") },
        desc = lines.joinToString("\n").ifEmpty { null },
        control = { StatusChip(if (state in NB_STATES) t("netbird.states.$state") else state, if (state == "Connected") Tone.Ok else Tone.Inactive) },
    )
    if (s.str("version").isNotEmpty()) Mono(s.str("version"), muted = true)
    Note(s.str("error"), Tone.Warn)
    for (w in s.strings("health")) Banner(w, tone = Tone.Warn)
    if (!s.bool("active")) Hint(t("netbird.activateHint"))
    if (needsLogin && !s.bool("setupKeySet")) Hint(t("netbird.keyNeeded"))
    Buttons {
        if (!online) SmallButton(t("netbird.connect"), { action("connect") }, enabled = !locked && !(needsLogin && !s.bool("setupKeySet")))
        else SmallButton(t("netbird.disconnect"), { action("disconnect") }, enabled = !locked)
        if (!needsLogin && s.bool("active")) SmallButton(t("netbird.logout"), {
            ov.confirm(tr.t("netbird.logout"), tr.t("netbird.logoutHint"), action = tr.t("netbird.logout"), cancel = tr.t("common.cancel")) { action("logout") }
        }, enabled = !locked)
    }
    if (pending) {
        ActionBox(null, t("netbird.trial", "time" to timeOf(s.str("pendingUntil")))) {
            SmallButton(t("netbird.undo"), { action("undo") }, enabled = !m.busy)
            SmallButton(t("netbird.confirm"), { action("confirm") }, kind = ButtonKind.Primary, enabled = !m.busy)
        }
    }
    val keyLocked = m.busy || s.bool("readOnly") || pending
    SettingSub(t("netbird.setupKey"), desc = (if (s.bool("setupKeySet")) t("netbird.keySet") else t("netbird.keyUnset")) + "\n" + t("netbird.keyHint"))
    // Der Key verlässt die App einmal; das Feld wird in jedem Fall geleert.
    Field(setupKey, { setupKey = it }, t("netbird.setupKey"), Modifier.fillMaxWidth(), placeholder = t("netbird.keyPlaceholder"), password = true, mono = true, enabled = !keyLocked)
    Buttons {
        SmallButton(t("netbird.saveKey"), {
            val value = setupKey
            setupKey = ""
            m.run(context, reset = false) { env.api.put("plugins/netbird/setup-key", jobj("setupKey" to value)) }
        }, kind = ButtonKind.Primary, enabled = !keyLocked && setupKey.isNotBlank())
        if (s.bool("setupKeySet")) SmallButton(t("netbird.removeKey"), {
            m.run(context, reset = false) { env.api.put("plugins/netbird/setup-key", jobj("setupKey" to "")) }
        }, enabled = !keyLocked)
    }
    SettingSub(t("netbird.management"), desc = t("netbird.managementHint"))
    Field(managementUrl, { managementUrl = it; m.dirty = true }, t("netbird.management"), Modifier.fillMaxWidth(), placeholder = "https://api.netbird.io:443", mono = true, enabled = !locked, singleLine = true)
    SettingSub(t("netbird.acceptRoutes"), control = { Switch(acceptRoutes, { acceptRoutes = it; m.dirty = true }, t("netbird.acceptRoutes"), enabled = !locked) })
    SettingSub(t("netbird.serveRoutes"), control = { Switch(serveRoutes, { serveRoutes = it; m.dirty = true }, t("netbird.serveRoutes"), enabled = !locked) })
    SettingSub(t("netbird.acceptDNS"), control = { Switch(acceptDNS, { acceptDNS = it; m.dirty = true }, t("netbird.acceptDNS"), enabled = !locked) })
    Hint(t("netbird.policyHint"))
    Buttons {
        SmallButton(t("netbird.apply"), {
            val body = settings.with(
                "managementUrl" to managementUrl.trim(),
                "acceptRoutes" to acceptRoutes,
                "serveRoutes" to serveRoutes,
                "acceptDNS" to acceptDNS,
            )
            m.run(context) { env.api.put("plugins/netbird", body) }
        }, kind = ButtonKind.Primary, enabled = !locked && m.dirty)
    }
    SettingSub(t("netbird.firewall"), desc = t("netbird.firewallHint"))
}

/** Uhrzeit eines Zeitstempels (formatTime im Web). */
@Composable
private fun timeOf(text: String): String = io.github.ra5on.titanos.settings.common.Fmt.time(LocalTr.current, io.github.ra5on.titanos.settings.common.Fmt.instant(text))
