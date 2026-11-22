package io.github.ra5on.titanos.settings.pages.network

import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.without
import kotlinx.serialization.json.JsonObject

// Die Regeln von web/src/lib/firewall.ts (Einstellungen Version 2) auf den
// JSON-Objekten des NAS: Zonen × Ziele als Zellen mit geordneten Richtlinien
// und einer Vorgabe. Das NAS prüft alles noch einmal und füllt fehlende
// Zellen genauso auf (backend/internal/firewall/config.go: defaultCell).

internal const val NAS = "nas"
internal const val APPS = "apps"
internal val FW_TABS = listOf("zones", "networks", "policies", "autoblock", "recent")
internal val BUILTIN_ZONES = listOf("home", "vpn", "internet", "docker")

internal fun fwTab(param: String?): String = param?.takeIf { it in FW_TABS } ?: "zones"

/** „TCP 80, 443 · UDP 137-138“; Ping ist „ICMP“. */
internal fun servicePorts(service: JsonObject): String = service.objs("ports").joinToString(" · ") { set ->
    if (set.str("protocol") == "icmp") "ICMP" else "${set.str("protocol").uppercase()} ${set.strings("ports").joinToString(", ")}"
}

internal fun isHostedNetwork(id: String): Boolean = id.startsWith("docker:") || id.startsWith("vm:")

/** Zonen mit Netzen hinter dem NAS haben Zellen zu anderen Zonen. */
internal fun isHosted(cfg: JsonObject, zone: String): Boolean {
    if (zone == "docker") return true
    val z = cfg.objs("zones").firstOrNull { it.str("id") == zone } ?: return false
    return !z.bool("builtin") && z.strings("networks").any(::isHostedNetwork)
}

/** Ob Verkehr einer Zelle überhaupt über das NAS läuft (sonst grau). */
internal fun routable(cfg: JsonObject, from: String, to: String): Boolean = to == NAS || to == APPS || isHosted(cfg, from)

/** Die Zelle, die niemand geändert hat, wie das NAS sie anlegt. */
internal fun defaultCell(cfg: JsonObject, services: List<JsonObject>, from: String, to: String): JsonObject {
    val policies = mutableListOf<JsonObject>()
    var default = "deny"
    if (to == NAS) {
        if (from == "home" || from == "vpn" || from == "internet") {
            for (s in services) if (s.o("default").bool(from)) policies += jobj("action" to "allow", "protocol" to "any", "service" to s.str("id"))
        } else if (from == "docker") {
            policies += jobj("action" to "allow", "protocol" to "any", "service" to "apps")
            policies += jobj("action" to "allow", "protocol" to "any", "ports" to "53", "comment" to "DNS")
        }
    } else if (to == APPS) {
        if (from == "home" || from == "vpn" || from == "docker") default = "allow"
    } else if (!isHosted(cfg, to)) {
        default = "allow"
    }
    return jobj("from" to from, "to" to to, "policies" to policies, "default" to default)
}

/** Die Zelle eines Paars: die gespeicherte oder ihre Vorgabe. */
internal fun cellOf(cfg: JsonObject, services: List<JsonObject>, from: String, to: String): JsonObject =
    cfg.objs("cells").firstOrNull { it.str("from") == from && it.str("to") == to } ?: defaultCell(cfg, services, from, to)

/** Die Einstellung mit der Zelle eines Paars (mit ihrer Vorgabe angelegt, falls sie fehlt), um sie zu bearbeiten. */
internal fun editCell(cfg: JsonObject, services: List<JsonObject>, from: String, to: String): JsonObject {
    if (cfg.objs("cells").any { it.str("from") == from && it.str("to") == to }) return cfg
    return cfg.with("cells" to cfg.objs("cells") + defaultCell(cfg, services, from, to))
}

/** Ändert eine Zelle (legt sie vorher an, falls nötig). */
internal fun updateCell(cfg: JsonObject, services: List<JsonObject>, from: String, to: String, change: (JsonObject) -> JsonObject): JsonObject {
    val c = editCell(cfg, services, from, to)
    return c.with("cells" to c.objs("cells").map { if (it.str("from") == from && it.str("to") == to) change(it) else it })
}

/** Ziele einer Zone in der Reihenfolge der Matrix. */
internal fun destinations(cfg: JsonObject, from: String): List<String> =
    if (isHosted(cfg, from)) listOf(NAS, APPS) + cfg.objs("zones").map { it.str("id") } else listOf(NAS, APPS)

/** allow, deny oder partial: nach der Vorgabe und den eingeschalteten Richtlinien dagegen. */
internal fun cellState(cell: JsonObject): String {
    val active = cell.objs("policies").filter { !it.bool("disabled") }
    return if (cell.str("default") == "allow") {
        if (active.any { it.str("action") == "deny" }) "partial" else "allow"
    } else {
        if (active.any { it.str("action") == "allow" }) "partial" else "deny"
    }
}

/** Zahl der eingeschalteten Richtlinien mit der anderen Aktion als der Vorgabe. */
internal fun exceptions(cell: JsonObject): Int = cell.objs("policies").count { !it.bool("disabled") && it.str("action") != cell.str("default") }

/** Die Zone eines Netzes: eine eigene, 'docker' für Docker-Netze, sonst '' (nach Adresse). */
internal fun zoneOfNetwork(cfg: JsonObject, id: String): String {
    cfg.objs("zones").firstOrNull { !it.bool("builtin") && id in it.strings("networks") }?.let { return it.str("id") }
    return if (id.startsWith("docker:")) "docker" else ""
}

/** Legt ein Netz in eine eigene Zone oder zurück zu seiner Vorgabe. */
internal fun assignNetwork(cfg: JsonObject, id: String, zone: String): JsonObject = cfg.with(
    "zones" to cfg.objs("zones").map { z ->
        if (z.bool("builtin")) {
            z
        } else {
            val rest = z.strings("networks").filter { it != id }
            z.with("networks" to if (z.str("id") == zone) rest + id else rest)
        }
    },
)

internal fun newZoneId(cfg: JsonObject, random: () -> Double = Math::random): String {
    while (true) {
        val id = "z" + (random() * 60466176.0).toLong().toString(36).padStart(5, '0')
        if (cfg.objs("zones").none { it.str("id") == id }) return id
    }
}

/** Entfernt eine eigene Zone mit ihren Zellen und den Zellen zu ihr. */
internal fun removeZone(cfg: JsonObject, id: String): JsonObject = cfg.with(
    "zones" to cfg.objs("zones").filter { it.str("id") != id || it.bool("builtin") },
    "cells" to cfg.objs("cells").filter { it.str("from") != id && it.str("to") != id },
)

internal fun ownZones(cfg: JsonObject): List<JsonObject> = cfg.objs("zones").filter { !it.bool("builtin") }

private fun tidy(p: JsonObject): JsonObject {
    val matched = p.str("service").isNotEmpty() || p.str("app").isNotEmpty()
    val out = mutableMapOf<String, Any?>("action" to p.str("action"), "protocol" to if (matched) "any" else p.str("protocol").ifEmpty { "any" })
    for (key in listOf("ports", "service", "app", "source", "destination", "comment")) {
        val v = p.str(key).trim()
        if (v.isNotEmpty() && !(key == "ports" && matched)) out[key] = v
    }
    if (p.bool("disabled")) out["disabled"] = true
    return jobj(*out.toList().toTypedArray())
}

/** Was auf Probe geht, unabhängig von der Reihenfolge der Schlüssel und fehlenden Vorgabe-Zellen. */
internal fun canonical(cfg: JsonObject, services: List<JsonObject>): String {
    val zones = cfg.objs("zones").map { z ->
        val b = z.bool("builtin")
        jobj(
            "id" to z.str("id"),
            "name" to if (b) "" else z.str("name").trim(),
            "networks" to if (b) emptyList() else z.strings("networks").sorted(),
            "sources" to if (b) emptyList() else z.strings("sources").map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
    val cells = cfg.objs("zones").flatMap { z ->
        destinations(cfg, z.str("id")).map { to ->
            val c = cellOf(cfg, services, z.str("id"), to)
            jobj("from" to z.str("id"), "to" to to, "default" to c.str("default"), "policies" to c.objs("policies").map(::tidy))
        }
    }
    return jobj("enabled" to cfg.bool("enabled"), "zones" to zones, "cells" to cells).toString()
}

/** Ob sich die Teile unterscheiden, die auf Probe gehen (das automatische Sperren wird für sich gespeichert). */
internal fun samePolicies(a: JsonObject, b: JsonObject, services: List<JsonObject>): Boolean = canonical(a, services) == canonical(b, services)

private val FW_IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

/** Grobe Prüfung einer Adresse oder eines Netzes; das NAS hat das letzte Wort. */
internal fun validSource(text: String): Boolean {
    val parts = text.trim().split('/')
    if (parts.size > 2 || parts[0].isEmpty()) return false
    val addr = parts[0]
    val v4 = FW_IPV4.matches(addr)
    val v6 = !v4 && addr.contains(':') && Regex("^[0-9a-fA-F:.]+$").matches(addr) && !addr.contains(":::")
    if (!v4 && !v6) return false
    if (parts.size == 1) return true
    val bits = parts[1]
    if (!Regex("^\\d{1,3}$").matches(bits)) return false
    return bits.toInt() <= if (v4) 32 else 128
}

/** „22“ oder „8000-8100“. */
internal fun validPorts(text: String): Boolean {
    val m = Regex("^(\\d{1,5})(?:-(\\d{1,5}))?$").matchEntire(text.trim()) ?: return false
    val low = m.groupValues[1].toInt()
    val high = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
    return low >= 1 && high <= 65535 && low <= high
}

/** Der erste Fehler einer Richtlinie als Textschlüssel oder "". */
internal fun policyProblem(p: JsonObject): String {
    val ports = p.str("ports")
    if (!p.has("service") && !p.has("app") && ports.isNotEmpty() && !validPorts(ports)) return "firewall.rulePorts"
    if (p.str("source").isNotEmpty() && !validSource(p.str("source"))) return "firewall.ruleSource"
    if (p.str("destination").isNotEmpty() && !validSource(p.str("destination"))) return "firewall.policyDestination"
    val comment = p.str("comment")
    if (comment.isNotEmpty() && (comment.length > 80 || Regex("[\"\\\\\n\r\t]").containsMatchIn(comment))) return "firewall.ruleComment"
    return ""
}

internal fun zoneProblem(zone: JsonObject): String {
    val name = zone.str("name").trim()
    if (name.isEmpty() || name.length > 40 || Regex("[\"\\\\\n\r\t]").containsMatchIn(name)) return "firewall.zoneName"
    if (zone.strings("sources").any { !validSource(it) }) return "firewall.ruleSource"
    return ""
}

/** Netze aus einem Textfeld (Leerzeichen, Komma, Semikolon, Zeilen), ohne Wiederholungen. */
internal fun parseNetworks(text: String): List<String> = text.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.distinct()

/** Eine Richtlinie mit anderer Art (Dienst, App oder Ports) wie setKind im PolicyEditor. */
internal fun policyKind(p: JsonObject, kind: String, services: List<JsonObject>, apps: List<Pair<String, String>>): JsonObject {
    var out = p.without("service", "app")
    if (kind == "service") out = out.with("service" to (services.firstOrNull()?.str("id") ?: "webui"))
    if (kind == "app") out = out.with("app" to (apps.firstOrNull()?.first ?: ""))
    out = if (kind != "ports") out.with("protocol" to "any", "ports" to "")
    else if (out.str("protocol") == "any") out.with("protocol" to "tcp") else out
    return out
}

internal fun policyKindOf(p: JsonObject): String = when {
    p.containsKey("service") -> "service"
    p.containsKey("app") -> "app"
    else -> "ports"
}

/** Eine neue Richtlinie für eine Zelle (CellPanel/PoliciesTab). */
internal fun newPolicy(to: String, services: List<JsonObject>, apps: List<Pair<String, String>>, withApps: Boolean = true): JsonObject = when {
    to == NAS -> jobj("action" to "allow", "protocol" to "any", "service" to (services.firstOrNull()?.str("id") ?: "webui"))
    to == APPS && withApps && apps.isNotEmpty() -> jobj("action" to "allow", "protocol" to "any", "app" to apps.first().first)
    else -> jobj("action" to "allow", "protocol" to "tcp", "ports" to "")
}

/** Apps für App-Richtlinien: laufende und solche, die Richtlinien nennen. (key to name) */
internal fun firewallApps(status: JsonObject?, draft: JsonObject?): List<Pair<String, String>> {
    val list = status.objs("apps").map { it.str("key") to it.str("name") }.toMutableList()
    for (cell in draft.objs("cells")) for (p in cell.objs("policies")) {
        val app = p.str("app")
        if (app.isNotEmpty() && list.none { it.first == app }) list += app to app.removePrefix("container:")
    }
    return list.sortedBy { it.second.lowercase() }
}
