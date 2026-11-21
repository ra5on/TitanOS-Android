package io.github.ra5on.titanos.settings.pages.network

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
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
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// Die Regeln von web/src/lib/app-proxy.ts: was der Editor gleich prüft, prüft das NAS noch einmal.

private val PX_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
private val PX_IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

internal fun validProxyName(name: String) = PX_LABEL.matches(name)
internal fun normalDomain(d: String) = d.trim().lowercase().removeSuffix(".")
internal fun validProxyDomain(domain: String): Boolean {
    val v = normalDomain(domain)
    if (v.isEmpty()) return true
    val labels = v.split('.')
    return v.length <= 200 && labels.size >= 2 && labels.all { PX_LABEL.matches(it) } && labels.last() != "local"
}
internal fun suggestedProxyName(app: String): String = app.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(63).trimEnd('-')
internal fun proxyAddresses(name: String, suffixes: List<String>) = if (validProxyName(name)) suffixes.map { "https://$name.$it" } else emptyList()
internal fun normalLocalName(name: String) = normalDomain(name).removeSuffix(".local").removeSuffix(".")
internal fun validLocalName(name: String): Boolean {
    val v = normalLocalName(name)
    if (v.isEmpty()) return true
    val labels = v.split('.')
    return labels.size <= 3 && labels.all { PX_LABEL.matches(it) && it != "local" }
}
internal fun splitList(text: String) = text.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }
private fun validIPv6(text: String) = text.contains(':') && Regex("^[0-9a-fA-F:.]+$").matches(text) && runCatching { java.net.InetAddress.getByName("[$text]") }.isSuccess
internal fun validNetwork(text: String): Boolean {
    val parts = text.trim().split('/')
    if (parts.size > 2 || parts[0].isEmpty()) return false
    val v4 = PX_IPV4.matches(parts[0])
    if (!v4 && !validIPv6(parts[0])) return false
    if (parts.size == 1) return true
    return Regex("^\\d{1,3}$").matches(parts[1]) && parts[1].toInt() <= if (v4) 32 else 128
}
internal fun validTargetHost(host: String): Boolean {
    val v = normalDomain(host)
    if (v.isEmpty()) return false
    if (PX_IPV4.matches(v) || validIPv6(v)) return true
    return v.length <= 253 && v.split('.').all { PX_LABEL.matches(it) }
}

/** Eine Route, wie das Formular sie bearbeitet. */
internal data class ProxyForm(
    val enabled: Boolean = false,
    val name: String = "",
    val aliases: String = "",
    val domains: String = "",
    val short: Boolean = false,
    val host: String = "",
    val port: String = "",
    val scheme: String = "http",
    val verifyTLS: Boolean = false,
    val access: String = "local",
    val networks: String = "",
    val tailscale: Boolean = false,
    val tailscaleName: String = "",
)

internal fun proxyForm(route: JsonObject?): ProxyForm = ProxyForm(
    enabled = route?.bool("enabled") ?: false,
    name = route?.str("name").orEmpty(),
    aliases = route?.strings("aliases").orEmpty().joinToString(", "),
    domains = route?.strings("domains").orEmpty().joinToString(", "),
    short = route?.bool("short") ?: false,
    host = route?.str("host").orEmpty(),
    port = (route?.long("port") ?: 0L).toString(),
    scheme = route?.str("scheme")?.ifEmpty { null } ?: "http",
    verifyTLS = route?.bool("verifyTLS") ?: false,
    access = when {
        route?.strings("networks").orEmpty().isNotEmpty() -> "networks"
        route == null || route.bool("localOnly") -> "local"
        else -> "all"
    },
    networks = route?.strings("networks").orEmpty().joinToString("\n"),
    tailscale = route?.bool("tailscale") ?: false,
    tailscaleName = route?.str("tailscaleName").orEmpty(),
)

/** Was `PUT docker/proxy/apps|targets/{id}` nimmt. */
internal fun proxyBody(f: ProxyForm, target: Boolean): JsonObject {
    val pairs = mutableListOf<Pair<String, Any?>>(
        "enabled" to f.enabled,
        "name" to f.name.trim().lowercase(),
        "aliases" to splitList(f.aliases.lowercase()),
        "domains" to splitList(f.domains).map(::normalDomain),
        "short" to f.short,
    )
    if (target) pairs += "host" to normalDomain(f.host)
    pairs += listOf(
        "port" to (f.port.trim().toLongOrNull() ?: 0L),
        "scheme" to f.scheme,
        "verifyTLS" to (f.scheme == "https" && f.verifyTLS),
        "localOnly" to (f.access != "all"),
        "networks" to if (f.access == "networks") splitList(f.networks) else emptyList(),
        "tailscale" to f.tailscale,
        "tailscaleName" to if (f.tailscale) f.tailscaleName.trim().lowercase() else "",
    )
    return jobj(*pairs.toTypedArray())
}

private fun routeNames(r: JsonObject) = listOf(r.str("name")) + r.strings("aliases")
private fun tailnetName(r: JsonObject) = r.str("tailscaleName").ifEmpty { r.str("name") }

/** Was am Formular nicht stimmt, als fertige Texte (proxyProblems). */
internal fun proxyProblems(tr: Tr, f: ProxyForm, target: Boolean, others: List<JsonObject>, localLabel: String): List<String> {
    val out = mutableListOf<String>()
    val body = proxyBody(f, target)
    val name = body.str("name")
    val aliases = body.strings("aliases")
    val domains = body.strings("domains")
    if (!validProxyName(name)) out += tr.t("proxy.nameInvalid")
    aliases.firstOrNull { !validProxyName(it) }?.let { out += tr.t("proxy.aliasInvalid", "alias" to it) }
    if (aliases.size > 8) out += tr.t("proxy.aliasTooMany")
    domains.firstOrNull { !validProxyDomain(it) }?.let { out += tr.t("proxy.domainInvalid", "domain" to it) }
    if (domains.size > 8) out += tr.t("proxy.domainTooMany")
    val mine = listOf(name) + aliases
    others.firstOrNull { o -> routeNames(o).any { it in mine } }?.let { out += tr.t("proxy.nameTaken", "app" to it.str("app")) }
    val port = body.long("port")
    if (port < 1 || port > 65535) out += tr.t("proxy.portInvalid")
    if (target && !validTargetHost(f.host)) out += tr.t("proxy.hostInvalid")
    if (f.access == "networks") {
        val nets = body.strings("networks")
        val wrong = nets.firstOrNull { !validNetwork(it) }
        if (wrong != null) out += tr.t("proxy.networksInvalid", "network" to wrong) else if (nets.isEmpty()) out += tr.t("proxy.networksEmpty")
    }
    if (body.bool("short") && localLabel.isNotEmpty() && localLabel in mine) out += tr.t("proxy.shortIsNas", "name" to localLabel)
    if (body.bool("tailscale")) {
        val tn = body.str("tailscaleName")
        if (tn.isNotEmpty() && !validProxyName(tn)) out += tr.t("proxy.tailnetNameInvalid")
        val device = tn.ifEmpty { name }
        others.firstOrNull { it.bool("tailscale") && tailnetName(it) == device }?.let { out += tr.t("proxy.tailnetNameTaken", "app" to it.str("app")) }
    }
    return out
}

/**
 * Alle HTTPS-Namen an einem Ort (Paket appproxy): die Namen der Docker-Apps
 * und der Ziele außerhalb von Docker, dann was für alle Namen gilt, der
 * Auth-Key für Tailscale und die eigene Zertifizierungsstelle.
 */
@Composable
fun ProxyPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var loadError by remember { mutableStateOf("") }
    var ca by remember { mutableStateOf<JsonObject?>(null) }
    var busyKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val failure = { e: Throwable ->
        val api = e as? io.github.ra5on.titanos.net.ApiException
        when (api?.code) {
            "proxy_name_taken" -> tr.error(api.code)
            "proxy_invalid" -> tr.error(api.code) + " " + api.message?.substringAfter(": ", "").orEmpty()
            else -> tr.errorOf(e)
        }
    }
    suspend fun load() {
        loadError = ""
        try {
            status = env.api.obj("docker/proxy")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = failure(e)
        }
    }
    LaunchedEffect(Unit) {
        load()
        ca = runCatching { env.api.obj("certs/ca") }.getOrElse { jobj("available" to false) }
    }
    /** Eine Änderung mit Fingerabdruck; die Antwort ist der neue Stand. */
    fun change(key: String, onError: (String) -> Unit = { error = it }, done: () -> Unit = {}, call: suspend () -> Any?) =
        Guard.require(context, GuardAction.Network) {
            scope.launch {
                busyKey = key
                try {
                    (call() as? JsonObject)?.let { status = it }
                    done()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(failure(e))
                } finally {
                    busyKey = ""
                }
            }
        }

    PageIntro(t("proxy.settingsIntro"))
    val s = status
    if (s == null) {
        if (loadError.isNotEmpty()) Banner(loadError, tone = Tone.Error, action = t("common.retry"), onAction = { scope.launch { load() } })
        else LoadingLine(t("common.loading"))
        return
    }
    val extended = s.has("mdns")
    val apps = s.objs("apps")
    val targets = s.objs("targets")
    var offError by remember { mutableStateOf("") }
    if (extended) {
        SettingGroup {
            SettingRow(t("proxy.allOn"), desc = t("proxy.allOnHint"), icon = WebIcons["power"], control = {
                Switch(!s.bool("off"), { on -> offError = ""; change("off", { offError = it }) { env.api.put("docker/proxy", jobj("off" to !on)) } }, t("proxy.allOn"), enabled = busyKey != "off")
            })
        }
    }
    if (s.bool("off")) Banner(t("proxy.allOffHint"), tone = Tone.Warn)
    Note(offError)

    val localName = remember(s) { mutableStateOf(s.str("localName")) }
    val localLabel = normalLocalName(localName.value).ifEmpty { s.str("defaultLocalName") }
    fun edit(kind: String, route: JsonObject?) {
        var form = proxyForm(route)
        if (route == null) form = form.copy(enabled = true, scheme = "http")
        ov.show { close -> ProxyEditor(s, kind, route?.str("app"), form, localLabel, busyKey.isNotEmpty(), close) { key, body, fail, ok ->
            change(key, fail, ok) { env.api.put(routePath(kind, key), body) }
        } }
    }
    fun remove(kind: String, route: JsonObject) {
        val name = if (kind == "app") route.str("app") else route.str("name")
        ov.confirm(
            tr.t("proxy.removeTitle"),
            tr.t(if (kind == "app") "proxy.removeText" else "proxy.removeTargetText", "app" to name, "name" to (route.strings("urls").firstOrNull() ?: route.str("name"))),
            action = tr.t("proxy.remove"),
            cancel = tr.t("common.cancel"),
        ) {
            error = ""
            change(route.str("app")) { env.api.delete(routePath(kind, route.str("app"))) }
        }
    }
    fun menu(kind: String, route: JsonObject) {
        val name = if (kind == "app") route.str("app") else route.str("name")
        ov.show { close ->
            FormDialog(
                tr.t("proxy.actionsFor", "name" to name),
                close,
                listOf(Act(tr.t("common.close"), onClick = close)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (extended) SmallButton(t("proxy.editHere"), { close(); edit(kind, route) }, icon = WebIcons["edit"])
                    if (kind == "app") SmallButton(t("proxy.edit"), { close(); env.open("/docker/apps/${route.str("app")}#h-proxy") }, icon = WebIcons["external"])
                    if (!route.bool("auto")) SmallButton(t("proxy.remove"), { close(); remove(kind, route) }, kind = ButtonKind.Danger, icon = WebIcons["trash"])
                }
            }
        }
    }

    var autoError by remember { mutableStateOf("") }
    SettingGroup(t("proxy.appsTitle")) {
        if (s.has("auto")) {
            SettingRow(t("appaccess.autoNames"), desc = t("appaccess.autoNamesHint"), icon = WebIcons["sparkle"], control = {
                Switch(s.bool("auto"), { on -> autoError = ""; change("auto", { autoError = it }) { env.api.put("docker/proxy", jobj("auto" to on)) } }, t("appaccess.autoNames"), enabled = busyKey != "auto" && !s.bool("off"))
            })
            Note(autoError)
        }
        if (apps.isEmpty()) {
            Hint(t("proxy.appsEmpty"))
            Buttons { SmallButton(t("proxy.openDocker"), { env.open("/docker") }) }
        }
        for (r in apps) RouteRow("app", r, s, busyKey.isNotEmpty(), { on -> change(r.str("app")) { env.api.put(routePath("app", r.str("app")), proxyBody(proxyForm(r), false).withEnabled(on)) } }) { menu("app", r) }
        Note(error)
    }
    if (extended) {
        SettingGroup(t("proxy.targetsTitle"), desc = t("proxy.targetsIntro"), tools = {
            SmallButton(t("proxy.addTarget"), { edit("target", null) }, enabled = busyKey.isEmpty())
        }) {
            for (r in targets) RouteRow("target", r, s, busyKey.isNotEmpty(), { on -> change(r.str("app")) { env.api.put(routePath("target", r.str("app")), proxyBody(proxyForm(r), true).withEnabled(on)) } }) { menu("target", r) }
        }
    }
    GeneralGroup(s, localName, busyKey == "general") { body, fail, ok -> change("general", fail, ok) { env.api.put("docker/proxy", body) } }
    if (s["tailnet"] is JsonObject) TailnetKeyGroup(s, busyKey == "tailnet") { key, fail, ok -> change("tailnet", fail, ok) { env.api.put("docker/proxy", jobj("tailscaleKey" to key)) } }
    ca?.let { CaGroup(it) }
    SettingGroup {
        SettingRow(t("proxy.howTitle"), icon = WebIcons["info"], id = "proxy-how") {
            Hint(t("proxy.howLocal"))
            Hint(t("proxy.howDomain"))
            Hint(t("proxy.howCert"))
            Buttons { SmallButton(t("proxy.openCert"), { a.openPage("access") }) }
        }
    }
}

private fun JsonObject.withEnabled(on: Boolean): JsonObject = JsonObject(this + ("enabled" to kotlinx.serialization.json.JsonPrimitive(on)))

internal fun routePath(kind: String, key: String) = "docker/proxy/${if (kind == "app") "apps" else "targets"}/${java.net.URLEncoder.encode(key, "UTF-8").replace("+", "%20")}"

/** Ein Name als Zeile: Schalter, Name mit Hinweisen, erste Adresse und „+n weitere“, Menü (auch langes Drücken/Rechtsklick). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RouteRow(kind: String, route: JsonObject, status: JsonObject, busy: Boolean, onToggle: (Boolean) -> Unit, onMenu: () -> Unit) {
    val context = LocalContext.current
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    val name = if (kind == "app") route.str("app") else route.str("name")
    val live = route.bool("enabled") && !status.bool("off")
    val tailnet = route["tailnet"] as? JsonObject
    val tailnetUrl = tailnet?.takeIf { it.str("state") == "running" }?.str("url")?.ifEmpty { null }
    val urls = route.strings("urls")
    val more = urls.size - 1 + (if (tailnetUrl != null) 1 else 0)
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape).background(c.surface).border(1.dp, c.border, shape)
            .combinedClickable(onClick = {}, onLongClick = onMenu, onLongClickLabel = t("proxy.actionsFor", "name" to name))
            .onSecondaryClick(onMenu)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(route.bool("enabled"), onToggle, t("proxy.toggle", "app" to name), enabled = !busy)
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    if (!route.bool("enabled")) StatusChip(t("proxy.off"), Tone.Inactive)
                    if (route.bool("auto")) StatusChip(t("appaccess.autoChip"), Tone.Info)
                    val nets = route.strings("networks")
                    if (!route.bool("localOnly") || nets.isNotEmpty()) {
                        val text = if (nets.isNotEmpty()) t("proxy.networksChip", "count" to nets.size) else t(if (route.bool("localOnly")) "proxy.localChip" else "proxy.publicChip")
                        StatusChip(text, if (!route.bool("localOnly") && nets.isEmpty()) Tone.Warn else Tone.Info)
                    }
                    if (tailnet != null) {
                        val st = tailnet.str("state")
                        StatusChip("Tailscale · " + t("proxy.tailnetState.$st"), when (st) { "running" -> Tone.Ok; "login", "https-off", "error" -> Tone.Warn; else -> Tone.Inactive })
                    }
                    if (route.str("scheme") == "https" && !route.bool("verifyTLS")) StatusChip(t("proxy.noVerifyChip"), Tone.Inactive)
                }
                urls.firstOrNull()?.let { UrlLine(it, live) }
                if (more > 0) LinkText(if (expanded) t("proxy.fewerUrls") else t("proxy.moreUrls", "count" to more)) { expanded = !expanded }
                if (expanded) {
                    for (u in urls.drop(1)) UrlLine(u, live)
                    tailnetUrl?.let { UrlLine(it, live) }
                    tailnet?.str("error")?.takeIf { it.isNotEmpty() && it != "plugin" }?.let { Hint(it) }
                    if (route.bool("short")) Hint(t("proxy.shortChip"))
                    Hint(t("proxy.target") + ": " + "${route.str("scheme")}://${route.str("host").ifEmpty { "127.0.0.1" }}:${route.long("port")}")
                }
            }
            IconButton(TitanIcons.more, t("proxy.actionsFor", "name" to name), { if (!busy) onMenu() })
        }
    }
}

@Composable
private fun UrlLine(url: String, live: Boolean) {
    val context = LocalContext.current
    if (live) LinkText(url) { openUrl(context, url) } else Mono(url, muted = true)
}

/** Name bearbeiten oder Ziel anlegen (ProxyRouteFields im Dialog). */
@Composable
private fun BoxScope.ProxyEditor(
    status: JsonObject,
    kind: String,
    key: String?,
    initial: ProxyForm,
    localLabel: String,
    busy: Boolean,
    onClose: () -> Unit,
    onSave: (key: String, body: JsonObject, fail: (String) -> Unit, ok: () -> Unit) -> Unit,
) {
    val tr = LocalTr.current
    var f by remember { mutableStateOf(initial) }
    var editError by remember { mutableStateOf("") }
    val target = kind == "target"
    val apps = status.objs("apps")
    val targets = status.objs("targets")
    val others = apps.filter { !(kind == "app" && it.str("app") == key) } + targets.filter { !(kind == "target" && it.str("app") == key) }
    val problems = proxyProblems(tr, f, target, others, localLabel)
    val mdns = status.bool("mdns", true)
    val tailnet = status["tailnet"] as? JsonObject
    val name = f.name.trim().lowercase()
    val preview = proxyAddresses(name, status.strings("suffixes")) + if (f.short && mdns) proxyAddresses(name, listOf("local")) else emptyList()
    fun newTargetId(n: String): String {
        val base = suggestedProxyName(n).take(32).trimEnd('-').ifEmpty { "target" }
        var id = base
        var i = 2
        while (targets.any { it.str("app") == id }) { id = "${base.take(29)}-$i"; i++ }
        return id
    }
    FormDialog(
        if (key == null) t("proxy.addTarget") else t("proxy.editTitle", "name" to if (kind == "app") key else f.name),
        onClose,
        listOf(
            Act(t("common.cancel"), enabled = !busy, onClick = onClose),
            Act(t("common.save"), ButtonKind.Primary, enabled = !busy && problems.isEmpty()) {
                editError = ""
                onSave(key ?: newTargetId(f.name), proxyBody(f, target), { editError = it }, onClose)
            },
        ),
    ) {
        Choice(t("proxy.enabled"), f.enabled, { f = f.copy(enabled = it) })
        LabeledField(t("proxy.name"), f.name, { f = f.copy(name = it.take(63)) }, mono = true)
        LabeledField(t("proxy.aliases"), f.aliases, { f = f.copy(aliases = it) }, placeholder = t("proxy.aliasesPlaceholder"), mono = true, hint = t("proxy.aliasesHint", "count" to splitList(f.aliases).size))
        LabeledField(t("proxy.domains"), f.domains, { f = f.copy(domains = it) }, placeholder = "fotos.example.org", mono = true, hint = t("proxy.domainsHint", "count" to splitList(f.domains).size))
        if (target) LabeledField(t("proxy.host"), f.host, { f = f.copy(host = it) }, placeholder = "192.168.1.30", mono = true, hint = t("proxy.hostHint"))
        LabeledField(t(if (target) "proxy.targetPort" else "proxy.port"), f.port, { f = f.copy(port = it.filter(Char::isDigit).take(5)) }, mono = true, number = true, hint = if (target) null else t("proxy.portHint"))
        LabeledSelect(t(if (target) "proxy.targetScheme" else "proxy.scheme"), f.scheme, listOf("http" to t("proxy.scheme.http"), "https" to t("proxy.scheme.https")), { f = f.copy(scheme = it) })
        if (f.scheme == "https") Choice(t("proxy.verifyTLS"), f.verifyTLS, { f = f.copy(verifyTLS = it) }, desc = t("proxy.verifyTLSHint"))
        Choice(t("proxy.short", "name" to name.ifEmpty { "app" }), f.short, { f = f.copy(short = it) }, desc = if (mdns) t("proxy.shortHint") else t("proxy.shortNoMdns"), enabled = mdns)
        if (tailnet != null) {
            Choice(t("proxy.tailscale"), f.tailscale, { f = f.copy(tailscale = it) }, desc = t("proxy.tailscaleHint"))
            if (f.tailscale) {
                LabeledField(t("proxy.tailscaleName"), f.tailscaleName, { f = f.copy(tailscaleName = it.take(63)) }, placeholder = name.ifEmpty { "app" }, mono = true,
                    hint = t("proxy.tailscaleNameHint", "name" to f.tailscaleName.trim().lowercase().ifEmpty { name.ifEmpty { "app" } }))
                if (!tailnet.bool("available")) Banner(t("proxy.tailscaleNoPlugin"), tone = Tone.Warn)
                else if (!tailnet.bool("keySet")) Banner(t("proxy.tailscaleNoKey"), tone = Tone.Warn)
            }
        }
        LabeledSelect(t("proxy.access"), f.access, listOf("local" to t("proxy.access.local"), "networks" to t("proxy.access.networks"), "all" to t("proxy.access.all")), { f = f.copy(access = it) })
        if (f.access == "networks") LabeledField(t("proxy.networks"), f.networks, { f = f.copy(networks = it) }, placeholder = "192.168.1.0/24\n100.64.0.0/10", mono = true, multiLine = true, hint = t("proxy.networksHint"))
        else if (f.access == "all") Banner(t("proxy.localOnlyOff"), tone = Tone.Warn)
        if (preview.isNotEmpty()) Fact(t("proxy.addressesPreview"), preview, mono = true)
        if (problems.isNotEmpty()) Banner(problems.joinToString("\n") { "• $it" }, tone = Tone.Warn)
        Note(editError)
    }
}

/** „Für alle Namen“: mDNS, lokaler Name, eigene Domain – ein Speichern für alle (eine Anfrage, getippter Text). */
@Composable
private fun GeneralGroup(s: JsonObject, localName: androidx.compose.runtime.MutableState<String>, busy: Boolean, onSave: (JsonObject, (String) -> Unit, () -> Unit) -> Unit) {
    val tr = LocalTr.current
    val extended = s.has("mdns")
    var domain by remember(s) { mutableStateOf(s.str("domain")) }
    var mdns by remember(s) { mutableStateOf(s.bool("mdns", true)) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    val label = normalLocalName(localName.value).ifEmpty { s.str("defaultLocalName") }
    val changed = normalDomain(domain) != s.str("domain") || normalLocalName(localName.value) != s.str("localName") || mdns != s.bool("mdns", true)
    val valid = validProxyDomain(domain) && validLocalName(localName.value)
    SettingGroup(t("proxy.generalTitle")) {
        if (extended) {
            SettingRow(t("proxy.mdns"), desc = t("proxy.mdnsHint"), icon = WebIcons["home"], control = { Switch(mdns, { mdns = it; notice = "" }, t("proxy.mdns")) })
            SettingRow(t("proxy.localName"), desc = t("proxy.localNameHint", "example" to "immich.${label.ifEmpty { "nas" }}.local"), icon = WebIcons["edit"], control = {
                Field(localName.value, { localName.value = it; notice = "" }, t("proxy.localName"), placeholder = s.str("defaultLocalName"), mono = true, singleLine = true, enabled = mdns)
            })
            if (!validLocalName(localName.value)) Banner(t("proxy.localNameInvalid"), tone = Tone.Warn)
        }
        SettingRow(t("proxy.domain"), desc = t("proxy.domainHint"), icon = WebIcons["globe"], control = {
            Field(domain, { domain = it; notice = "" }, t("proxy.domain"), placeholder = "home.example.org", mono = true, singleLine = true)
        })
        if (!validProxyDomain(domain)) Banner(t("proxy.domainInvalid", "domain" to normalDomain(domain)), tone = Tone.Warn)
        if (extended && !mdns && normalDomain(domain).isEmpty()) Banner(t("proxy.unreachable"), tone = Tone.Warn)
        if (s.strings("suffixes").isNotEmpty()) Hint(t("proxy.domainCurrent", "suffixes" to s.strings("suffixes").joinToString(", ")))
        Note(error)
        Buttons {
            SmallButton(t("common.save"), {
                error = ""
                notice = ""
                val body = if (extended) jobj("domain" to normalDomain(domain), "localName" to normalLocalName(localName.value), "mdns" to mdns) else jobj("domain" to normalDomain(domain))
                onSave(body, { error = it }) { notice = tr.t("proxy.saved") }
            }, kind = ButtonKind.Primary, enabled = !busy && valid && changed)
            if (notice.isNotEmpty()) Hint(notice)
        }
    }
}

/** Der Auth-Key für Namen im Tailnet: einmal geschickt, nie wieder gezeigt; leer entfernt ihn. */
@Composable
private fun TailnetKeyGroup(s: JsonObject, busy: Boolean, onSave: (String, (String) -> Unit, () -> Unit) -> Unit) {
    val tr = LocalTr.current
    val tailnet = s.o("tailnet")
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    SettingGroup(t("proxy.tailnetTitle")) {
        if (!tailnet.bool("available")) Banner(t("proxy.tailscaleNoPlugin"), tone = Tone.Warn)
        SettingRow(t("proxy.tailscaleKey"), icon = WebIcons["key"], control = {
            Field(key, { key = it }, t("proxy.tailscaleKey"), placeholder = if (tailnet.bool("keySet")) t("proxy.tailscaleKeySet") else "tskey-auth-…", password = true, mono = true, enabled = !busy)
        })
        Buttons {
            SmallButton(t("proxy.tailscaleKeySave"), {
                val value = key.trim()
                error = ""
                notice = ""
                onSave(value, { error = it }) { key = ""; notice = tr.t("proxy.tailscaleKeySaved") }
            }, kind = ButtonKind.Primary, enabled = !busy && key.trim().startsWith("tskey-"))
            if (tailnet.bool("keySet")) SmallButton(t("proxy.tailscaleKeyRemove"), {
                error = ""
                notice = ""
                onSave("", { error = it }) { notice = tr.t("proxy.tailscaleKeyRemoved") }
            }, enabled = !busy)
        }
        SettingRow(t("proxy.tailnetHelp"), icon = WebIcons["info"], id = "proxy-tailnet-help") {
            Hint(t("proxy.tailnetIntro"))
            Hint(t("proxy.tailscaleKeyHint"))
        }
        Note(error)
        if (notice.isNotEmpty()) Hint(notice)
    }
}

/** Die eigene Zertifizierungsstelle: Fingerabdruck, Download als .crt/.cer und wie man sie einrichtet. */
@Composable
private fun CaGroup(ca: JsonObject) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")) { uri ->
        val path = pending
        if (uri != null && path.isNotEmpty()) scope.launch {
            try {
                val bytes = env.api.bytes(path)
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, tr.errorOf(e), Toast.LENGTH_LONG).show()
            }
        }
    }
    SettingGroup(t("appaccess.caTitle"), desc = t("appaccess.caIntro")) {
        Hint(t("appaccess.caNotNeeded"))
        if (ca.bool("available")) {
            SettingRow(ca.str("name").ifEmpty { t("appaccess.caName") }, icon = WebIcons["lock"], id = "proxy-ca-cert", startOpen = true) {
                if (ca.str("notAfter").isNotEmpty()) Fact(t("appaccess.caUntil"), tr.whenOr(ca.str("notAfter")))
                Fact(t("appaccess.caFingerprint"), ca.str("fingerprint"), mono = true)
                Buttons {
                    SmallButton(t("appaccess.caDownloadCrt"), { pending = "certs/ca.crt"; save.launch("titanos-ca.crt") }, kind = ButtonKind.Primary)
                    SmallButton(t("appaccess.caDownloadCer"), { pending = "certs/ca.cer"; save.launch("titanos-ca.cer") })
                }
            }
            SettingRow(t("appaccess.caHowTitle"), icon = WebIcons["info"], id = "proxy-ca-how") {
                for (os in listOf("Windows", "Mac", "Ios", "Android", "Linux")) {
                    SubHead(t("appaccess.ca${os}Title"))
                    Hint(t("appaccess.ca$os"))
                }
            }
            Hint(t("appaccess.caKey"))
        } else {
            Banner(t("appaccess.caUnavailable"), tone = Tone.Warn)
        }
    }
}
