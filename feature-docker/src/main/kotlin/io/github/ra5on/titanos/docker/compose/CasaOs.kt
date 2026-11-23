package io.github.ra5on.titanos.docker.compose

// Vorlagen aus CasaOS-Katalogen, Hardware-Auswahl und eigene LAN-Netze der
// Container – wie web/src/lib/compose/{casaos,hardware,lan}.ts.

data class CasaContext(val timezone: String, val uid: Int, val gid: Int, /** Adresse des NAS im LAN. */ val host: String)

fun isCasaOS(compose: String): Boolean = runCatching { YamlDoc.parse(compose).has("x-casaos") }.getOrDefault(false)

private fun texts(value: Any?): Map<String, String>? {
    if (value is String) return if (value.isNotBlank()) mapOf("en" to value.trim()) else null
    val result = LinkedHashMap<String, String>()
    for ((locale, text) in record(value)) {
        if (text !is String || text.isBlank()) continue
        val key = locale.lowercase()
        if (key == "de_de") result["de"] = text.trim()
        if (key == "en_us" || (key == "en_gb" && !result.containsKey("en"))) result["en"] = text.trim()
    }
    return result.ifEmpty { null }
}

private val PLACEHOLDER_LABEL = Regex("^container (variable|path|port)\\b", RegexOption.IGNORE_CASE)

private fun label(value: Any?, self: String): Map<String, String>? {
    val found = texts(value)?.toMutableMap() ?: return null
    found.entries.removeAll { (_, text) -> PLACEHOLDER_LABEL.containsMatchIn(text) || text == self || text.length > 120 }
    return found.ifEmpty { null }
}

private val MEDIA_FOLDERS = listOf("media", "gallery", "music", "movies", "tv", "photos")
private val HOST_PLACEHOLDER = Regex("\\[(CHANGE_TO_LAN_IP|(YOUR_)?(CASAOS_)?(LAN_)?IP)]")
private val OPEN_PLACEHOLDER = Regex("\\[[A-Z][A-Z0-9_ ]{2,}]")

/** Die Vorlage als Compose-Datei mit einem x-titanos-Block. */
fun fromCasaOS(compose: String, appId: String, ctx: CasaContext): String {
    val values = mapOf("TZ" to ctx.timezone, "PUID" to ctx.uid.toString(), "PGID" to ctx.gid.toString())
    var text = compose.replace(Regex("\\$\\{AppID}|\\\$AppID\\b"), Regex.escapeReplacement(appId))
    text = Regex("(?<!\\$)\\$\\{(TZ|PUID|PGID)(?::?-[^}]*)?}|(?<!\\$)\\$(TZ|PUID|PGID)\\b").replace(text) { m ->
        values.getValue(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }
    text = HOST_PLACEHOLDER.replace(text, Regex.escapeReplacement(ctx.host))

    val doc = YamlDoc.parse(text)
    val top = record(record(doc.toPlain())["x-casaos"])
    val services = serviceNames(doc)
    val main = (top["main"] as? String)?.takeIf { it in services } ?: services.firstOrNull() ?: ""
    val portMap = top["port_map"]?.let { YamlDoc.jsString(it) }?.takeIf { Regex("^\\d+$").matches(it) } ?: ""

    val variables = LinkedHashMap<String, MutableMap<String, Any?>>()
    val paths = LinkedHashMap<String, MutableMap<String, Any?>>()
    val ports = LinkedHashMap<String, MutableMap<String, Any?>>()
    fun note(into: MutableMap<String, MutableMap<String, Any?>>, key: String, entry: Map<String, Any?>) {
        val kept = entry.filterValues { it != null }
        if (kept.isNotEmpty()) into.getOrPut(key) { LinkedHashMap() }.putAll(kept)
    }

    for (service in services) {
        val described = record(record(record(record(doc.toPlain())["services"])[service])["x-casaos"])
        fun describedAs(list: Any?, container: String): Map<String, Any?> =
            record((list as? List<*>).orEmpty().firstOrNull { YamlDoc.jsString(record(it)["container"]) == container })
        doc.deleteIn(listOf("services", service, "x-casaos"))

        val entries = readService(doc, service)
        val env = entries.env.map { pair ->
            var value = pair.value
            note(variables, pair.name, mapOf("label" to label(describedAs(described["envs"], pair.name)["description"], pair.name)))
            if (OPEN_PLACEHOLDER.containsMatchIn(value)) {
                note(variables, pair.name, mapOf("required" to true, "placeholder" to value))
                value = ""
            }
            val demanded = Regex("^\\$\\{[A-Za-z_][A-Za-z0-9_]*:?\\?([^}]*)}$").find(value)
            if (demanded != null) {
                note(variables, pair.name, mapOf("required" to true, "placeholder" to demanded.groupValues[1].trim().take(120)))
                value = ""
            }
            pair.copy(value = value)
        }
        val newPaths = entries.paths.map { row ->
            if (row.raw != null) return@map row
            var suggest: String? = null
            var host = row.host
            val own = Regex("^/DATA/AppData/[^/]+(/.*)?$").find(row.host)
            if (own != null) {
                host = "." + own.groupValues[1]
            } else if (row.host == "/DATA" || row.host.startsWith("/DATA/")) {
                val parts = row.host.split("/").drop(2)
                val folder = parts.firstOrNull() ?: "data"
                suggest = if (folder.lowercase() in MEDIA_FOLDERS) "media" else "share"
                host = (listOf(".", folder.lowercase()) + parts.drop(1)).joinToString("/")
            }
            note(paths, row.container, mapOf("label" to label(describedAs(described["volumes"], row.container)["description"], row.container), "suggest" to suggest))
            row.copy(host = host)
        }
        for (row in entries.ports) {
            if (row.raw != null) continue
            val webui = if (service == main && row.host != "" && row.host == portMap && row.protocol == "tcp") true else null
            note(
                ports, if (row.protocol == "udp") "${row.container}/udp" else row.container,
                mapOf("label" to label(describedAs(described["ports"], row.container)["description"], row.container), "webui" to webui),
            )
        }
        var webuiValue = entries.webui
        if (service == main && portMap != "" && entries.webui == "") {
            val index = (top["index"] as? String)?.takeIf { it.startsWith("/") } ?: "/"
            webuiValue = if (top["scheme"] == "https") "https://[host]:$portMap$index" else portMap + (if (index == "/") "" else index)
        }
        writeService(doc, service, entries.copy(env = env, paths = newPaths, webui = webuiValue))
    }

    doc.delete("x-casaos")
    val meta = LinkedHashMap<String, Any?>()
    top["icon"]?.let { meta["icon"] = it }
    meta["main"] = main
    texts(record(top["tips"])["before_install"])?.let { meta["tips"] = it }
    meta["variables"] = variables
    meta["paths"] = paths
    meta["ports"] = ports
    doc.set("x-titanos", meta)
    return doc.toString()
}

/** Gewählte Hardware je Dienst (sortiert nach Namen). */
fun hardwareSelections(text: String): Map<String, List<String>> {
    val doc = YamlDoc.parse(text)
    return serviceNames(doc).sorted().associateWith { readService(doc, it).hardware }
}

fun withHardware(text: String, selections: Map<String, List<String>>): String {
    val doc = YamlDoc.parse(text)
    for ((service, devices) in selections) {
        if (doc.hasIn(listOf("services", service))) {
            writeService(doc, service, readService(doc, service).copy(hardware = devices))
        }
    }
    return doc.toString()
}

/** Das eigene LAN-Netz (Name ohne „titanos-lan-“) und die feste Adresse eines Dienstes. */
fun lanAssignment(text: String, service: String): kotlin.Pair<String, String> {
    val data = runCatching { record(YamlDoc.parse(text).toPlain()) }.getOrNull() ?: return "" to ""
    val attached = record(record(record(data["services"])[service])["networks"])
    for ((key, value) in attached) {
        val name = record(record(data["networks"])[key])["name"]
        if (name is String && name.startsWith("titanos-lan-")) return name.substring(12) to (record(value)["ipv4_address"] as? String ?: "")
    }
    return "" to ""
}

/** Hängt den Dienst an das eigene LAN-Netz [network] (leer: keins) mit fester Adresse [address]. */
fun assignContainerLAN(text: String, service: String, network: String, address: String): String {
    val doc = YamlDoc.parse(text)
    if (!doc.hasIn(listOf("services", service))) throw IllegalArgumentException("invalid compose")
    val data = record(doc.toPlain())
    val svc = record(record(data["services"])[service])
    if (svc["network_mode"] != null) throw IllegalArgumentException("network mode must be private")
    val current = svc["networks"]
    val attached = LinkedHashMap<String, Any?>()
    when (current) {
        is List<*> -> current.forEach { attached[YamlDoc.jsString(it)] = null }
        is Map<*, *> -> current.forEach { (k, v) -> attached[k.toString()] = v }
        else -> attached["default"] = null
    }
    val nets = record(data["networks"])
    val previous = ArrayList<String>()
    for (key in attached.keys.toList()) {
        val name = record(nets[key])["name"]
        if (name is String && name.startsWith("titanos-lan-")) {
            previous.add(key)
            attached.remove(key)
        }
    }
    if (network.isNotEmpty()) {
        val key = "titanos_lan_" + network.replace("-", "_")
        val old = nets[key]
        if (old != null && record(old)["name"] != "titanos-lan-$network") throw IllegalArgumentException("network key conflict")
        doc.setIn(listOf("networks", key), linkedMapOf("external" to true, "name" to "titanos-lan-$network"))
        attached[key] = if (address.isNotBlank()) linkedMapOf("ipv4_address" to address.trim()) else LinkedHashMap<String, Any?>()
    }
    if (attached.containsKey("default") && !doc.hasIn(listOf("networks", "default"))) doc.setIn(listOf("networks", "default"), LinkedHashMap<String, Any?>())
    doc.setIn(listOf("services", service, "networks"), attached)
    val updated = record(record(doc.toPlain())["services"])
    for (key in previous) {
        val usedElsewhere = updated.values.any { v ->
            when (val n = record(v)["networks"]) {
                is List<*> -> n.contains(key)
                is Map<*, *> -> n.containsKey(key)
                else -> false
            }
        }
        if (!usedElsewhere) doc.deleteIn(listOf("networks", key))
    }
    return doc.toString()
}
