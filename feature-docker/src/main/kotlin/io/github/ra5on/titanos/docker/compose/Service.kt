package io.github.ra5on.titanos.docker.compose

import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.ScalarNode

// Ein Dienst einer Compose-Datei als Felder des Formulars und zurück, wie
// web/src/lib/compose/service.ts. Was das Formular nicht versteht, bleibt wie es ist.

data class PathRow(val host: String, val container: String, val readOnly: Boolean, val raw: Any? = null)

data class PortRow(val host: String, val container: String, val protocol: String, val raw: Any? = null)

data class Pair(val name: String, val value: String)

/** Ein Gerät für den Container („/dev/dri:/dev/dri“); andere Formen bleiben wie sie sind. */
data class DeviceRow(val value: String, val raw: Any? = null)

data class ServiceForm(
    val image: String = "",
    val restart: String = "no",
    val network: String = "bridge",
    val privileged: Boolean = false,
    val hardware: List<String> = emptyList(),
    val webui: String = "",
    val publicUrl: String = "",
    val paths: List<PathRow> = emptyList(),
    val ports: List<PortRow> = emptyList(),
    val env: List<Pair> = emptyList(),
    val labels: List<Pair> = emptyList(),
    val devices: List<DeviceRow> = emptyList(),
    /** CPUs, die der Container nutzen darf („8-11“, „0,2“); leer für alle. */
    val cpuset: String = "",
    /** Speichergrenze wie Compose sie schreibt („4g“, „512m“); leer für keine. */
    val memLimit: String = "",
)

const val WEBUI_LABEL = "titanos.webui"
const val PUBLIC_URL_LABEL = "titanos.public-url"
val RESTART_POLICIES = listOf("no", "always", "unless-stopped", "on-failure")
val NETWORKS = listOf("bridge", "host", "none")

private val PORT = Regex("^(?:(\\d+):)?(\\d+)(?:/(tcp|udp))?$")

/** Namen der Dienste einer Compose-Datei. */
fun serviceNames(doc: YamlDoc): List<String> {
    val services = doc.getIn(listOf("services")) as? MappingNode ?: return emptyList()
    return services.value.map { t -> (t.keyNode as? ScalarNode)?.value ?: YamlDoc.jsString(YamlDoc.plain(t.keyNode)) }
}

@Suppress("UNCHECKED_CAST")
internal fun record(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()

private fun pairs(value: Any?): List<Pair> = when (value) {
    is List<*> -> value.map { entry ->
        val parts = YamlDoc.jsString(entry).split("=")
        Pair(parts[0], parts.drop(1).joinToString("="))
    }
    is Map<*, *> -> value.map { (k, v) -> Pair(k.toString(), if (v == null) "" else YamlDoc.jsString(v)) }
    else -> emptyList()
}

private fun list(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

private val RELATIVE_OR_ABS = Regex("^[./~]")
private val MODE = Regex(":(ro|rw)$")

fun readService(doc: YamlDoc, service: String): ServiceForm {
    val root = record(doc.toPlain())
    val plain = record(record(root["services"])[service])
    fun at(key: String) = plain[key]
    fun text(key: String, fallback: String = ""): String {
        val v = at(key)
        return if (v == null || v is Map<*, *> || v is List<*>) fallback else YamlDoc.jsString(v)
    }
    val labels = pairs(at("labels"))
    val hwSource = if (plain.containsKey("x-titanos-hardware") && at("x-titanos-hardware") != null) at("x-titanos-hardware")
    else if (serviceNames(doc).sorted().firstOrNull() == service) record(root["x-titanos"])["hardware"] else emptyList<Any>()
    return ServiceForm(
        image = text("image"),
        restart = text("restart", "no"),
        network = text("network_mode", "bridge"),
        privileged = at("privileged") == true,
        hardware = list(hwSource).filterIsInstance<String>(),
        webui = labels.firstOrNull { it.name == WEBUI_LABEL }?.value ?: "",
        publicUrl = labels.firstOrNull { it.name == PUBLIC_URL_LABEL }?.value ?: "",
        labels = labels.filter { it.name != WEBUI_LABEL && it.name != PUBLIC_URL_LABEL },
        env = pairs(at("environment")),
        devices = list(at("devices")).map { if (it is String) DeviceRow(it) else DeviceRow("", it) },
        cpuset = text("cpuset"),
        memLimit = text("mem_limit"),
        paths = list(at("volumes")).map { entry -> pathRow(entry) },
        ports = list(at("ports")).map { entry -> portRow(entry) },
    )
}

private fun pathRow(entry: Any?): PathRow {
    if (entry is String) {
        val parts = entry.split(":")
        if ((parts.size == 2 || (parts.size == 3 && parts[2] in listOf("ro", "rw"))) && RELATIVE_OR_ABS.containsMatchIn(parts[0])) {
            return PathRow(parts[0], parts[1], parts.getOrNull(2) == "ro")
        }
    } else if (entry is Map<*, *>) {
        val rest = entry.keys.map { it.toString() }.filter { it !in setOf("type", "source", "target", "read_only", "bind") }
        val bind = entry["bind"]
        val plainBind = !entry.containsKey("bind") || bind == mapOf("create_host_path" to true)
        val source = entry["source"]
        val target = entry["target"]
        if (entry["type"] == "bind" && source is String && target is String && RELATIVE_OR_ABS.containsMatchIn(source) && plainBind && rest.isEmpty()) {
            return PathRow(
                source.replace(MODE, ""),
                target.replace(MODE, ""),
                entry["read_only"] == true || source.endsWith(":ro") || target.endsWith(":ro"),
            )
        }
    }
    return PathRow("", "", false, entry)
}

private fun numberLike(v: Any?) = (v is Number || v is String) && Regex("^\\d+$").matches(YamlDoc.jsString(v))

private fun portRow(entry: Any?): PortRow {
    val match = if (entry is String || entry is Number) PORT.matchEntire(YamlDoc.jsString(entry)) else null
    if (match != null) {
        return PortRow(match.groupValues[1], match.groupValues[2], match.groupValues[3].ifEmpty { "tcp" })
    }
    if (entry is Map<*, *>) {
        val target = entry["target"]
        val published = entry["published"]
        val protocol = if (entry.containsKey("protocol") && entry["protocol"] != null) entry["protocol"] else "tcp"
        val mode = if (entry.containsKey("mode") && entry["mode"] != null) entry["mode"] else "ingress"
        val rest = entry.keys.map { it.toString() }.filter { it !in setOf("target", "published", "protocol", "mode") }
        if (numberLike(target) && (published == null || numberLike(published)) && (protocol == "tcp" || protocol == "udp") && mode == "ingress" && rest.isEmpty()) {
            return PortRow(if (published == null) "" else YamlDoc.jsString(published), YamlDoc.jsString(target), protocol as String)
        }
    }
    return PortRow("", "", "tcp", entry)
}

/**
 * Schreibt das Formular zurück in das Dokument. Nur was sich vom Dokument
 * unterscheidet, wird geschrieben: alles andere behält Form, Kommentare und Anker.
 */
fun writeService(doc: YamlDoc, service: String, form: ServiceForm) {
    val before = readService(doc, service)
    if (before == form) return
    val path = { key: String -> listOf("services", service, key) }
    fun set(key: String, value: Any?) {
        val empty = value == null || (value is List<*> && value.isEmpty()) || (value is Map<*, *> && value.isEmpty())
        if (empty) doc.deleteIn(path(key)) else doc.setIn(path(key), value)
    }
    if (before.image != form.image) set("image", form.image.trim())
    if (before.restart != form.restart) set("restart", if (form.restart == "no") null else form.restart)
    val publishes = form.network != "host" && form.network != "none"
    if (before.network != form.network) {
        if (form.network == "bridge") doc.deleteIn(path("network_mode")) else doc.setIn(path("network_mode"), form.network)
        if (!publishes) doc.deleteIn(path("ports"))
    }
    if (before.hardware != form.hardware) doc.setIn(path("x-titanos-hardware"), form.hardware)
    if (before.privileged != form.privileged) set("privileged", if (form.privileged) true else null)
    if (before.paths != form.paths) {
        set("volumes", form.paths.map { row -> if (row.raw != null) row.raw else YamlDoc.quoted("${row.host.trim()}:${row.container.trim()}${if (row.readOnly) ":ro" else ""}") })
    }
    if (publishes && before.ports != form.ports) {
        set("ports", form.ports.map { row ->
            if (row.raw != null) row.raw
            else YamlDoc.quoted("${if (row.host.trim().isNotEmpty()) row.host.trim() + ":" else ""}${row.container.trim()}${if (row.protocol == "udp") "/udp" else ""}")
        })
    }
    if (before.devices != form.devices) {
        set("devices", form.devices.filter { it.raw != null || it.value.isNotBlank() }.map { it.raw ?: it.value.trim() })
    }
    if (before.cpuset != form.cpuset) set("cpuset", form.cpuset.trim().ifEmpty { null })
    if (before.memLimit != form.memLimit) set("mem_limit", form.memLimit.trim().ifEmpty { null })
    if (before.env != form.env) {
        set("environment", LinkedHashMap<String, Any?>().apply { form.env.filter { it.name.isNotBlank() }.forEach { put(it.name.trim(), it.value) } })
    }
    if (before.labels != form.labels || before.webui != form.webui || before.publicUrl != form.publicUrl) {
        val labels = LinkedHashMap<String, Any?>()
        form.labels.filter { it.name.isNotBlank() }.forEach { labels[it.name.trim()] = it.value }
        if (form.webui.isNotBlank()) labels[WEBUI_LABEL] = form.webui.trim()
        if (form.publicUrl.isNotBlank()) labels[PUBLIC_URL_LABEL] = form.publicUrl.trim()
        set("labels", labels)
    }
}

data class ServiceProblems(val ports: List<String>, val env: List<String>, val paths: List<String>, val any: Boolean)

/** Welche Fehlermeldung (Schlüssel der Web-Texte) zu welchem Feld gehört; `taken`: „Port/Protokoll“ → anderer Container. */
fun checkService(form: ServiceForm, taken: Map<String, String>, t: (String, Map<String, String>) -> String): ServiceProblems {
    fun validPort(v: String) = Regex("^\\d+$").matches(v) && v.toBigInteger() in 1.toBigInteger()..65535.toBigInteger()
    val ports = form.ports.mapIndexed { index, row ->
        if (row.raw != null) return@mapIndexed ""
        if (!validPort(row.container) || (row.host != "" && !validPort(row.host))) return@mapIndexed t("editor.portInvalid", emptyMap())
        if (row.host == "") return@mapIndexed ""
        val key = "${row.host.toInt()}/${row.protocol}"
        if (form.ports.withIndex().any { (at, o) -> at != index && o.raw == null && o.host != "" && "${o.host.toIntOrNull()}/${o.protocol}" == key }) return@mapIndexed t("editor.portDuplicate", emptyMap())
        taken[key]?.let { t("editor.portInUse", mapOf("name" to it)) } ?: ""
    }
    val env = form.env.mapIndexed { index, p ->
        if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(p.name)) t("editor.envInvalid", emptyMap())
        else if (form.env.withIndex().any { (at, o) -> at != index && o.name == p.name }) t("editor.envDuplicate", emptyMap()) else ""
    }
    val paths = form.paths.map { row -> if (row.raw == null && (row.host.isBlank() || !row.container.trim().startsWith("/"))) t("editor.pathInvalid", emptyMap()) else "" }
    val any = (ports + env + paths).any { it.isNotEmpty() } || !validImage(form.image) || (form.publicUrl.isNotBlank() && publicURL(form.publicUrl).isEmpty())
    return ServiceProblems(ports, env, paths, any)
}
