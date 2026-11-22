package io.github.ra5on.titanos.docker

import io.github.ra5on.titanos.compute.ComputeAccess.Companion.seg
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Anfragen und Logik der App-Seite wie im Web: Zugriff (AppAccess.svelte),
// Öffnen mit und Freigaben (lib/docker-opens.ts), HTTPS-Name und Tailnet
// (lib/app-proxy.ts), Freigaben des NAS (/shares).

@Serializable
data class AccessApp(val app: String = "", val gate: Boolean = false, val users: List<String> = emptyList())

@Serializable
data class AccessAccount(val name: String = "", val role: String = "", val disabled: Boolean = false)

@Serializable
data class AppAccessState(val newApps: String = "", val apps: List<AccessApp> = emptyList(), val accounts: List<AccessAccount> = emptyList())

@Serializable
data class AccessUpdate(val newApps: String, val apps: List<AccessApp>)

@Serializable
data class OpensMount(val source: String = "", val target: String = "", val readOnly: Boolean = false)

/** Der opens-Block einer App mit ihren Ordnern (GET /docker/apps/{app}/opens). */
@Serializable
data class AppOpens(
    val app: String = "",
    val name: String = "",
    val types: List<String> = emptyList(),
    val command: List<String> = emptyList(),
    val user: String = "",
    val env: List<String> = emptyList(),
    val service: String = "",
    val hidden: Boolean = false,
    val running: Boolean = false,
    val mounts: List<OpensMount> = emptyList(),
    val error: String = "",
)

@Serializable
data class OpensUpdate(val types: List<String>, val command: List<String>, val user: String, val env: List<String>, val service: String, val hidden: Boolean)

@Serializable
data class NasShare(val name: String = "", val volume: String = "", val path: String = "", val available: Boolean = true, val perUser: Boolean = false)

@Serializable
data class ShareMount(val share: String, val readOnly: Boolean)

@Serializable
data class TailnetCheck(val reachable: Boolean = false, val url: String = "", val error: String = "")

suspend fun DockerApi.access(): AppAccessState = access.get("/apps/access")
suspend fun DockerApi.saveAccess(u: AccessUpdate): AppAccessState = access.call("PUT", "/apps/access", u)
suspend fun DockerApi.opens(app: String): AppOpens? = runCatching { access.get<AppOpens>("/docker/apps/${seg(app)}/opens") }.getOrNull()
suspend fun DockerApi.saveOpens(app: String, o: OpensUpdate) = access.send("PUT", "/docker/apps/${seg(app)}/opens", o)
suspend fun DockerApi.shares(): List<NasShare> = runCatching { access.get<List<NasShare>>("/shares") }.getOrDefault(emptyList())
suspend fun DockerApi.mountShare(app: String, share: String, readOnly: Boolean) = access.send("POST", "/docker/apps/${seg(app)}/shares", ShareMount(share, readOnly))
suspend fun DockerApi.unmountShare(app: String, share: String) = access.delete("/docker/apps/${seg(app)}/shares/${seg(share)}")
suspend fun DockerApi.proxyStatus(): ProxyStatus = access.get("/docker/proxy")
suspend fun DockerApi.saveProxy(app: String, body: JsonObject): ProxyStatus = access.call("PUT", "/docker/proxy/apps/${seg(app)}", body)
suspend fun DockerApi.removeProxy(app: String): ProxyStatus = access.request("DELETE", "/docker/proxy/apps/${seg(app)}", null, ProxyStatus.serializer())!!
/** „Im Tailnet veröffentlichen“: Port und, wenn noch keiner gespeichert ist, der Auth-Schlüssel. */
suspend fun DockerApi.publishTailnet(app: String, port: Int, key: String): ProxyStatus = access.call(
    "POST", "/docker/proxy/apps/${seg(app)}/tailnet",
    buildJsonObject { put("port", port); if (key.isNotBlank()) put("tailscaleKey", key.trim()) },
)
suspend fun DockerApi.unpublishTailnet(app: String) = access.delete("/docker/proxy/apps/${seg(app)}/tailnet")
suspend fun DockerApi.checkTailnet(app: String): TailnetCheck = access.call("POST", "/docker/proxy/apps/${seg(app)}/tailnet/check", JsonObject(emptyMap()))

/** Die Programmzeile in Argumente: Wörter durch Leerzeichen, "…" hält Leerzeichen zusammen; keine Shell. */
fun splitCommand(line: String): List<String> = Regex("\"([^\"]*)\"|(\\S+)").findAll(line).map { m -> if (m.groups[1] != null) m.groupValues[1] else m.groupValues[2] }.toList()

/** Die Programmzeile für den Editor (Argumente mit Leerzeichen in Anführungszeichen). */
fun joinCommand(args: List<String>): String = args.joinToString(" ") { if (Regex("\\s").containsMatchIn(it) || it.isEmpty()) "\"$it\"" else it }

const val SHARES_TARGET = "/shares/"

/** Die von „Öffnen mit“ eingehängten Freigaben (Ziel /shares/<Name>). */
fun mountedShares(o: AppOpens): List<ShareMount> = o.mounts
    .filter { it.target.startsWith(SHARES_TARGET) && !it.target.substring(8).contains('/') }
    .map { ShareMount(it.target.substring(SHARES_TARGET.length), it.readOnly) }

// ---- HTTPS-Namen (lib/app-proxy.ts) ----

private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

fun validProxyName(name: String) = LABEL.matches(name)

fun normalDomain(domain: String) = domain.trim().lowercase().removeSuffix(".")

fun validProxyDomain(domain: String): Boolean {
    val v = normalDomain(domain)
    if (v.isEmpty()) return true
    val labels = v.split(".")
    return v.length <= 200 && labels.size >= 2 && labels.all { LABEL.matches(it) } && labels.last() != "local"
}

fun suggestedProxyName(app: String) = app.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(63).trimEnd('-')

/** Port für den Vorschlag: der des WebUI-Labels, sonst der erste veröffentlichte TCP-Port. */
fun suggestedProxyPort(containers: List<DockerContainer>): Int {
    for (c in containers) {
        val w = c.webui.trim()
        val port = Regex("^(\\d{1,5})(?:[/?#].*)?$").find(w)?.groupValues?.get(1)
            ?: Regex("^https?://\\[host]:(\\d{1,5})", RegexOption.IGNORE_CASE).find(w)?.groupValues?.get(1)
        val n = port?.toIntOrNull()
        if (n != null && n in 1..65535) return n
    }
    for (c in containers) c.ports.firstOrNull { it.protocol != "udp" && it.host > 0 }?.let { return it.host }
    return 0
}

fun splitList(text: String) = text.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }

private fun validIPv6(text: String): Boolean {
    if (!Regex("^[0-9a-fA-F:.]+$").matches(text) || !text.contains(':')) return false
    return runCatching { java.net.InetAddress.getByName(text) is java.net.Inet6Address }.getOrDefault(false)
}

fun validNetwork(text: String): Boolean {
    val parts = text.trim().split("/")
    if (parts.size > 2 || parts[0].isEmpty()) return false
    val v4 = IPV4.matches(parts[0])
    if (!v4 && !validIPv6(parts[0])) return false
    if (parts.size == 1) return true
    return Regex("^\\d{1,3}$").matches(parts[1]) && parts[1].toInt() <= (if (v4) 32 else 128)
}

data class ProxyForm(
    val enabled: Boolean = false,
    val name: String = "",
    val aliases: String = "",
    val domains: String = "",
    val short: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val scheme: String = "http",
    val verifyTLS: Boolean = false,
    /** local, networks, all */
    val access: String = "local",
    val networks: String = "",
    val tailscale: Boolean = false,
    val tailscaleName: String = "",
)

fun proxyForm(route: ProxyRoute?, name: String = ""): ProxyForm = ProxyForm(
    enabled = route?.enabled ?: false,
    name = route?.name ?: name,
    aliases = route?.aliases.orEmpty().joinToString(", "),
    domains = route?.domains.orEmpty().joinToString(", "),
    short = route?.short == true,
    host = route?.host.orEmpty(),
    port = route?.port ?: 0,
    scheme = route?.scheme ?: "http",
    verifyTLS = route?.verifyTLS == true,
    access = when {
        route?.networks?.isNotEmpty() == true -> "networks"
        route == null || route.localOnly -> "local"
        else -> "all"
    },
    networks = route?.networks.orEmpty().joinToString("\n"),
    tailscale = route?.tailscale == true,
    tailscaleName = route?.tailscaleName.orEmpty(),
)

/** Was PUT /docker/proxy/apps/{app} nimmt. */
fun proxyBody(f: ProxyForm): JsonObject = buildJsonObject {
    put("enabled", f.enabled)
    put("name", f.name.trim().lowercase())
    put("aliases", buildJsonArray { splitList(f.aliases.lowercase()).forEach { add(JsonPrimitive(it)) } })
    put("domains", buildJsonArray { splitList(f.domains).map(::normalDomain).forEach { add(JsonPrimitive(it)) } })
    put("short", f.short)
    put("port", f.port)
    put("scheme", f.scheme)
    put("verifyTLS", f.scheme == "https" && f.verifyTLS)
    put("localOnly", f.access != "all")
    put("networks", buildJsonArray { if (f.access == "networks") splitList(f.networks).forEach { add(JsonPrimitive(it)) } })
    put("tailscale", f.tailscale)
    put("tailscaleName", if (f.tailscale) f.tailscaleName.trim().lowercase() else "")
}

private fun tailnetName(r: ProxyRoute) = r.tailscaleName.ifEmpty { r.name }

/** Was vor dem Speichern nicht stimmt (Schlüssel des Webs mit Werten); das NAS prüft dasselbe. */
fun proxyProblems(f: ProxyForm, others: List<ProxyRoute>, localLabel: String): List<kotlin.Pair<String, Map<String, String>>> {
    val out = ArrayList<kotlin.Pair<String, Map<String, String>>>()
    val name = f.name.trim().lowercase()
    val aliases = splitList(f.aliases.lowercase())
    val domains = splitList(f.domains).map(::normalDomain)
    if (!validProxyName(name)) out.add("proxy.nameInvalid" to emptyMap())
    aliases.firstOrNull { !validProxyName(it) }?.let { out.add("proxy.aliasInvalid" to mapOf("alias" to it)) }
    if (aliases.size > 8) out.add("proxy.aliasTooMany" to emptyMap())
    domains.firstOrNull { !validProxyDomain(it) }?.let { out.add("proxy.domainInvalid" to mapOf("domain" to it)) }
    if (domains.size > 8) out.add("proxy.domainTooMany" to emptyMap())
    val mine = listOf(name) + aliases
    others.firstOrNull { o -> (listOf(o.name) + o.aliases).any { it in mine } }?.let { out.add("proxy.nameTaken" to mapOf("app" to it.app)) }
    if (f.port !in 1..65535) out.add("proxy.portInvalid" to emptyMap())
    if (f.access == "networks") {
        val nets = splitList(f.networks)
        val wrong = nets.firstOrNull { !validNetwork(it) }
        if (wrong != null) out.add("proxy.networksInvalid" to mapOf("network" to wrong))
        else if (nets.isEmpty()) out.add("proxy.networksEmpty" to emptyMap())
    }
    if (f.short && localLabel.isNotEmpty() && localLabel in mine) out.add("proxy.shortIsNas" to mapOf("name" to localLabel))
    if (f.tailscale) {
        val tn = f.tailscaleName.trim().lowercase()
        if (tn.isNotEmpty() && !validProxyName(tn)) out.add("proxy.tailnetNameInvalid" to emptyMap())
        val device = tn.ifEmpty { name }
        others.firstOrNull { it.tailscale && tailnetName(it) == device }?.let { out.add("proxy.tailnetNameTaken" to mapOf("app" to it.app)) }
    }
    return out
}

/** Ob eine App im Tailnet ist (oder auf dem Weg dorthin). */
fun inTailnet(r: ProxyRoute?) = r?.enabled == true && r.tailscale

/** Schritt einer Veröffentlichung im Tailnet, wie tailnetStep() im Web. */
fun tailnetStep(r: ProxyRoute?, check: TailnetCheck?, failed: Int = 0, limit: Int = 12): String {
    if (!inTailnet(r)) return "off"
    return when (r?.tailnet?.state) {
        null, "", "starting" -> "start"
        "login" -> "login"
        "https-off" -> "https-off"
        "error" -> "error"
        "running" -> if (check?.reachable == true) "ready" else if (failed >= limit) "unreachable" else "check"
        else -> "off"
    }
}

const val TAILSCALE_KEYS_URL = "https://login.tailscale.com/admin/settings/keys"
const val TAILSCALE_DNS_URL = "https://login.tailscale.com/admin/dns"
