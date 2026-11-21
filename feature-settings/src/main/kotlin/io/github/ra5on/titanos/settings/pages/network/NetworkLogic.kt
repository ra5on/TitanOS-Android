package io.github.ra5on.titanos.settings.pages.network

import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import kotlinx.serialization.json.JsonObject

// Die Regeln von web/src/lib/network.ts auf den JSON-Objekten des NAS:
// Felder, die die App nicht kennt, bleiben in jedem Netz und Bond erhalten.

internal val BOND_MODE_KEYS = mapOf(
    "active-backup" to "netcfg.bond.active-backup",
    "802.3ad" to "netcfg.bond.802.3ad",
    "balance-alb" to "netcfg.bond.balance-alb",
)

internal val PURPOSES = listOf("management", "storage", "apps", "custom")

/** Nameserver wie eingetippt: getrennt durch Leerzeichen, Komma oder Semikolon, ohne Wiederholungen. */
internal fun parseDns(text: String): List<String> = text.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.distinct()

private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

/** Grobe Prüfung vor dem NAS: eine IPv4- oder IPv6-Adresse, sonst nichts. */
internal fun looksLikeIP(value: String): Boolean {
    if (IPV4.matches(value)) return true
    return value.contains(':') && Regex("^[0-9a-fA-F:.]+$").matches(value)
}

/** Was die Seite schickt: Zahlen als Zahlen, ungenutzte Felder leer, Nameserver bereinigt. */
internal fun networkPayload(cfg: JsonObject): JsonObject {
    if (!cfg.has("networks")) return cfg
    val networks = cfg.objs("networks").map { n ->
        val static4 = n.str("ipv4") == "static"
        val static6 = n.str("ipv6") == "static"
        var out = n.with(
            "vlan" to (n.str("vlan").trim().toLongOrNull() ?: 0L),
            "address" to if (static4) n.str("address").trim() else "",
            "gateway" to if (static4) n.str("gateway").trim() else "",
            "address6" to if (static6) n.str("address6").trim() else "",
            "gateway6" to if (static6) n.str("gateway6").trim() else "",
            "dns" to parseDns(n.strings("dns").joinToString(" ")),
        )
        if (n.has("routes")) out = out.with("routes" to n.objs("routes").map { r -> r.with("metric" to (r.str("metric").trim().toLongOrNull() ?: 0L)) })
        out
    }
    return cfg.with("networks" to networks)
}

// Kennungen werden zu Gerätenamen (tv<id>, tn<id>, tb<id>): kurz und im
// offenen Entwurf nie wiederverwendet.
internal fun freeId(taken: List<String>, base: String): String {
    var i = 1
    while (true) {
        val id = base + i
        if (id !in taken) return id
        i++
    }
}

internal fun blankNetwork(cfg: JsonObject, ports: List<JsonObject>): JsonObject {
    val used = cfg.objs("bonds").flatMap { it.strings("ports") }.toSet()
    val parent = ports.firstOrNull { it.str("name") !in used }?.str("name")
        ?: cfg.objs("bonds").firstOrNull()?.let { "bond:" + it.str("id") } ?: ""
    return jobj(
        "id" to freeId(cfg.objs("networks").map { it.str("id") }, "net"),
        "name" to "",
        "purpose" to "custom",
        "parent" to parent,
        "vlan" to 0,
        "bridge" to false,
        "management" to cfg.objs("networks").isEmpty(),
        "ipv4" to "dhcp",
        "address" to "",
        "gateway" to "",
        "ipv6" to "auto",
        "address6" to "",
        "gateway6" to "",
        "dns" to emptyList<String>(),
        "routes" to emptyList<Any>(),
    )
}

internal fun blankBond(cfg: JsonObject): JsonObject =
    jobj("id" to freeId(cfg.objs("bonds").map { it.str("id") }, "bond"), "ports" to emptyList<String>(), "mode" to "active-backup")

/** Der Anschluss oder Bond eines Netzes, wie man ihn liest. */
internal fun parentLabel(parent: String): String = parent.removePrefix("bond:")

/** Wer einen Anschluss verwendet: sein Bond oder die Netze direkt darauf. ("bond"/"network" to Name) */
internal fun portUsers(cfg: JsonObject, port: String): List<Pair<String, String>> {
    cfg.objs("bonds").firstOrNull { port in it.strings("ports") }?.let { return listOf("bond" to it.str("id")) }
    return cfg.objs("networks").filter { it.str("parent") == port }.map { "network" to it.str("name") }
}

/** Die Netze auf einem Bond; ein verwendeter Bond lässt sich nicht entfernen. */
internal fun bondUsers(cfg: JsonObject, id: String): List<String> =
    cfg.objs("networks").filter { it.str("parent") == "bond:$id" }.map { it.str("name") }

/** Anschlüsse, die ein Bond nehmen darf: freie und seine eigenen. */
internal fun bondCandidates(cfg: JsonObject, ports: List<JsonObject>, bond: JsonObject): List<JsonObject> {
    val elsewhere = cfg.objs("bonds").filter { it.str("id") != bond.str("id") }.flatMap { it.strings("ports") }.toSet()
    return ports.filter { it.str("name") !in elsewhere }
}

/** up, noAddress, down, draft, unknown – der Zustand einer Netzzeile. */
internal fun liveState(status: JsonObject, n: JsonObject, applied: Boolean): String {
    if (!applied) return "draft"
    val live = status.o("live")[n.str("id")] as? JsonObject ?: return "unknown"
    if (!live.bool("up")) return "down"
    return if (live.a("addresses").isNotEmpty()) "up" else "noAddress"
}

/** Die Adresse des Verwaltungsnetzes, unter der man nach einer Änderung bestätigt (fest eingestellte Adresse). */
internal fun managementURL(n: JsonObject): String {
    val address = n.str("address").ifEmpty { n.str("address6") }.substringBefore('/')
    if (address.isEmpty()) return ""
    return "https://" + (if (address.contains(':')) "[$address]" else address) + "/"
}

/** Das bevorzugte Netz einer Topologie. */
internal fun managementOf(topology: JsonObject?): JsonObject? = topology.objs("networks").firstOrNull { it.bool("management") }

/**
 * Ob die Probe den Weg zum NAS ändert (Adresse, Adressvergabe, Anschluss,
 * VLAN oder Bridge des Verwaltungsnetzes): dann erreicht die App es unter
 * Umständen nur über eine neue Adresse, die sie nicht kennt.
 */
internal fun managementChanges(status: JsonObject): Boolean {
    val before = managementOf(status.o("topology")) ?: return false
    val after = managementOf(status["pendingTopology"] as? JsonObject) ?: return false
    if (before.str("id") != after.str("id")) return true
    return listOf("ipv4", "address", "ipv6", "address6", "parent", "bridge").any { before.str(it) != after.str(it) } ||
        before.long("vlan") != after.long("vlan")
}

private val HOSTNAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

internal fun validHostname(name: String): Boolean = HOSTNAME.matches(name)

private val BOND_ID = Regex("^[a-z][a-z0-9-]{0,9}$")

internal fun validBondId(id: String): Boolean = BOND_ID.matches(id)
