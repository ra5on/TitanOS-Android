package io.github.ra5on.titanos.docker.compose

import java.security.SecureRandom

// Eine Vorlage aus dem App-Katalog als Felder des Installationsformulars und,
// mit dem Eingetragenen, als Compose-Datei der App – wie web/src/lib/compose/template.ts.

/** Text einer Vorlage, einmal oder je Sprache (String, Map oder null). */
fun localize(value: Any?, locale: String): String = when (value) {
    null -> ""
    is Map<*, *> -> (value[locale] ?: value["en"] ?: value[""] ?: value.values.firstOrNull())?.let { YamlDoc.jsString(it) } ?: ""
    else -> YamlDoc.jsString(value)
}

/** Was titand mit einer Vorlage schickt. */
data class TemplateInfo(
    val compose: String,
    val timezone: String = "",
    val uid: Int = 0,
    val gid: Int = 0,
    val appdata: String = "",
    val listening: List<kotlin.Pair<Int, String>> = emptyList(),
    val missingDevices: List<String> = emptyList(),
    val cpuMissing: List<String> = emptyList(),
)

data class ShareRef(val name: String, val path: String)

data class VarField(
    val service: String,
    val name: String,
    val label: Any?,
    val help: Any?,
    val placeholder: String,
    val value: String,
    /** template, titanos, generated, required */
    val origin: String,
    val secret: Boolean,
    val required: Boolean,
    /** "", timezone, uid, gid */
    val fill: String,
)

data class PathField(
    val service: String,
    val index: Int,
    val container: String,
    val label: Any?,
    val template: String,
    val share: ShareRef?,
    val wanted: Boolean,
    val host: String,
    val touched: Boolean,
)

data class PortField(
    val service: String,
    val index: Int,
    val container: String,
    val protocol: String,
    val label: Any?,
    val webui: Boolean,
    val templateHost: String,
    /** Wer den Port der Vorlage hat: Name eines Containers, "" für das System, null wenn frei. */
    val blockedBy: String?,
    val host: String,
)

data class OwnPath(val host: String, val container: String)

data class OwnPort(val host: String, val container: String, val protocol: String)

data class OwnEntries(
    val vars: List<Pair> = emptyList(),
    val paths: List<OwnPath> = emptyList(),
    val ports: List<OwnPort> = emptyList(),
    val labels: List<Pair> = emptyList(),
    val devices: List<String> = emptyList(),
)

data class ImageField(val service: String, val image: String)

data class InstallForm(
    val images: List<ImageField>,
    val publicUrl: String,
    val name: String,
    val tips: Any?,
    /** privileged, hostNetwork, dockerSocket, rootMount, capabilities */
    val risks: List<String>,
    val missingDevices: List<String>,
    val main: String,
    val vars: List<VarField>,
    val paths: List<PathField>,
    val ports: List<PortField>,
    val cpuset: String,
    val memLimit: String,
    val own: OwnEntries = OwnEntries(),
)

private data class Meta(
    val main: Any?,
    val tips: Any?,
    val variables: Map<String, Any?>,
    val paths: Map<String, Any?>,
    val icon: Any?,
    val ports: Map<String, Any?>,
)

private fun readMeta(compose: String): Meta {
    val meta = record(record(YamlDoc.parse(compose).toPlain())["x-titanos"])
    return Meta(meta["main"], meta["tips"], record(meta["variables"]), record(meta["paths"]), meta["icon"], record(meta["ports"]))
}

private val FILLED = mapOf("TZ" to "timezone", "PUID" to "uid", "PGID" to "gid")
private val MEDIA_SHARES = listOf("media", "medien", "video", "videos", "filme", "movies", "musik", "music", "fotos", "photos")
private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

fun isRelative(path: String) = path.startsWith("./") || path == "."

/** Wo ein relativer Pfad der Vorlage auf dem NAS landet. */
fun resolve(template: String, name: String, appdata: String): String = "$appdata/$name${template.substring(1)}".replace(Regex("/+$"), "")

/** Der Pfad, den ein Feld zeigt, solange niemand ihn ändert. */
fun defaultHost(field: PathField, name: String, appdata: String): String =
    field.share?.path ?: if (isRelative(field.template)) resolve(field.template, name, appdata) else field.template

private val random = SecureRandom()

/** Ein zufälliger Wert für ein Geheimnis, das die App braucht, aber niemand kennen muss. */
fun generateSecret(length: Int = 32): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    val limit = 256 - (256 % alphabet.length)
    val sb = StringBuilder()
    val bytes = ByteArray(length)
    while (sb.length < length) {
        random.nextBytes(bytes)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < limit && sb.length < length) sb.append(alphabet[v % alphabet.length])
        }
    }
    return sb.toString()
}

/**
 * Liest eine Vorlage in die Felder des Installationsformulars. [used]:
 * „Port/Protokoll“ → wer ihn hat (Containername oder "" für das System).
 * [editedEnv] gesetzt: die Compose-Datei wurde von Hand geändert und gilt.
 * Wirft, wenn die Vorlage keine Compose-Datei ist.
 */
fun readTemplate(
    tpl: TemplateInfo,
    name: String,
    shares: List<ShareRef>,
    used: Map<String, String>,
    editedEnv: Map<String, String>? = null,
    secret: () -> String = { generateSecret() },
): InstallForm {
    val doc = YamlDoc.parse(tpl.compose)
    val services = serviceNames(doc)
    if (services.isEmpty()) throw IllegalArgumentException("no services")
    val meta = readMeta(tpl.compose)
    val filled = mapOf("timezone" to tpl.timezone, "uid" to tpl.uid.toString(), "gid" to tpl.gid.toString())
    var main = (meta.main as? String)?.takeIf { it in services } ?: services[0]
    val images = services.mapNotNull { s -> readService(doc, s).image.takeIf { it.isNotEmpty() }?.let { ImageField(s, it) } }
    val publicUrl = readService(doc, main).publicUrl
    val reserved = HashSet<String>()
    val risks = LinkedHashSet<String>()
    val missing = ArrayList<String>()
    val vars = ArrayList<VarField>()
    val paths = ArrayList<PathField>()
    val ports = ArrayList<PortField>()
    val content = record(record(doc.toPlain())["services"])

    for (service in services) {
        val entries = readService(doc, service)
        val plain = record(content[service])
        if (entries.privileged) risks.add("privileged")
        if (entries.network == "host" || plain["pid"] == "host") risks.add("hostNetwork")
        if ((plain["cap_add"] as? List<*>)?.isNotEmpty() == true) risks.add("capabilities")
        for (row in entries.paths) {
            if (Regex("^/(var/)?run/docker\\.sock$").matches(row.host)) risks.add("dockerSocket")
            if (row.raw == null && row.host == "/") risks.add("rootMount")
        }
        for (device in (plain["devices"] as? List<*>).orEmpty()) {
            val host = if (device is String) device.split(":")[0] else ""
            if (host in tpl.missingDevices && host !in missing) missing.add(host)
        }

        for (pair in entries.env) {
            val info = record(meta.variables[pair.name])
            val from = info["from"]
            if (from is String && ENV_NAME.matches(from) && (editedEnv == null || pair.value == "\${$from}")) continue
            if ((info["secret"] == "generate" || info["secret"] == true) && vars.any { it.secret && it.name == pair.name && it.service != service }) continue
            val fillRaw = info["fill"]?.let { YamlDoc.jsString(it) }
            val fill = if (fillRaw in listOf("timezone", "uid", "gid")) fillRaw!! else FILLED[pair.name] ?: ""
            var field = VarField(
                service = service,
                name = pair.name,
                label = info["label"],
                help = info["help"],
                placeholder = info["placeholder"] as? String ?: "",
                value = pair.value,
                origin = "template",
                secret = info["secret"] == "generate" || info["secret"] == true,
                required = info["required"] == true,
                fill = fill,
            )
            if (editedEnv != null) {
                val keep = field.secret && editedEnv.containsKey(pair.name) && pair.value == "\${${pair.name}}"
                field = field.copy(secret = keep, value = if (keep) editedEnv.getValue(pair.name) else field.value)
            } else if (info["secret"] == "generate") {
                field = field.copy(value = secret(), origin = "generated")
            } else if (fill.isNotEmpty()) {
                field = field.copy(value = filled.getValue(fill), origin = "titanos")
            } else if (field.required && field.value == "") {
                field = field.copy(origin = "required")
            }
            vars.add(field)
        }

        entries.paths.forEachIndexed { index, row ->
            if (row.raw != null) return@forEachIndexed
            val info = record(meta.paths[row.container])
            val suggest = info["suggest"]
            var share: ShareRef? = null
            if (editedEnv == null && suggest == "media") share = shares.firstOrNull { it.name.lowercase() in MEDIA_SHARES }
            if (editedEnv == null && (suggest == "media" || suggest == "share") && share == null && shares.size == 1) share = shares[0]
            val field = PathField(service, index, row.container, info["label"], row.host, share, suggest == "media" || suggest == "share", "", false)
            paths.add(field.copy(host = defaultHost(field, name, tpl.appdata)))
        }

        entries.ports.forEachIndexed { index, row ->
            if (row.raw != null || row.host == "") return@forEachIndexed
            val info = record(meta.ports[if (row.protocol == "udp") "${row.container}/udp" else row.container])
            fun key(port: Int) = "$port/${row.protocol}"
            var port = row.host.toInt()
            val blockedBy = used[key(port)]
            while (editedEnv == null && port < 65535 && (used.containsKey(key(port)) || key(port) in reserved)) port++
            reserved.add(key(port))
            ports.add(
                PortField(
                    service, index, row.container, row.protocol, info["label"], info["webui"] == true, row.host,
                    if (port == row.host.toInt()) null else (blockedBy ?: ""), port.toString(),
                ),
            )
            if (info["webui"] == true && meta.main !is String) main = service
        }
    }
    val mainEntries = readService(doc, main)
    return InstallForm(
        images = images,
        publicUrl = publicUrl,
        name = name,
        tips = meta.tips,
        risks = risks.toList(),
        missingDevices = missing,
        main = main,
        vars = vars,
        paths = paths,
        ports = ports,
        cpuset = mainEntries.cpuset,
        memLimit = mainEntries.memLimit,
    )
}

data class InstallProblems(
    val images: List<Boolean>,
    /** "", invalid, exists */
    val name: String,
    val vars: List<String>,
    val paths: List<String>,
    val ports: List<String>,
    val ownVars: List<String>,
    val ownPaths: List<String>,
    val ownPorts: List<String>,
    val ownDevices: List<String>,
    val cpuset: Boolean,
    val memLimit: Boolean,
    val missing: Int,
    val blocking: Boolean,
)

val APP_NAME = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")
private val SYSTEM_PATHS = Regex("^/(dev|run|sys|proc|var/run|etc/localtime$|etc/timezone$|lib/modules)(/|$)")

private fun validPort(v: String) = Regex("^\\d{1,5}$").matches(v) && v.toInt() in 1..65535

/** CPUs wie Compose sie nimmt: „3“, „8-11“, „0,2,4-5“; leer für alle. */
fun validCpuset(v: String) = v.isBlank() || Regex("^\\d+(-\\d+)?(,\\d+(-\\d+)?)*$").matches(v.trim())

/** Speichergrenze: Bytes oder Zahl mit b, k, m oder g; leer für keine. */
fun validMemLimit(v: String) = v.isBlank() || Regex("^\\d+(\\.\\d+)?[bkmg]?$", RegexOption.IGNORE_CASE).matches(v.trim())

/** Ein Gerät des NAS, wahlweise mit Pfad im Container und Rechten. */
fun validDevice(v: String) = Regex("^/dev/[^\\s:]+(:/[^\\s:]+(:[rwm]+)?)?$").matches(v.trim())

private fun pathProblem(host: String, container: String): String {
    if (host.isBlank() || !container.trim().startsWith("/")) return "invalid"
    if (isRelative(host) || host.startsWith("/mnt/") || SYSTEM_PATHS.containsMatchIn(host)) return ""
    return "volatile"
}

/** Prüft das Formular beim Tippen. */
fun checkInstall(form: InstallForm, installed: List<String>, used: Map<String, String>): InstallProblems {
    val all = (form.ports.map { it.host to it.protocol } + form.own.ports.map { it.host to it.protocol })
        .map { (h, p) -> if (validPort(h)) "${h.toInt()}/$p" else "" }
    fun portProblem(host: String, container: String, at: Int): String {
        if (!validPort(host) || !validPort(container)) return "invalid"
        if (all.withIndex().any { (other, key) -> other != at && key == all[at] }) return "duplicate"
        return if (used.containsKey(all[at])) "inUse" else ""
    }
    val names = form.vars.map { it.name }
    val vars = form.vars.map { if (it.required && it.value.isBlank()) "required" else "" }
    val paths = form.paths.map { pathProblem(it.host, it.container) }
    val ports = form.ports.mapIndexed { at, f -> portProblem(f.host, f.container, at) }
    val ownVars = form.own.vars.mapIndexed { at, p ->
        when {
            p.name == "" -> "empty"
            !ENV_NAME.matches(p.name) -> "invalid"
            form.own.vars.withIndex().any { (i, o) -> i != at && o.name == p.name } -> "duplicate"
            p.name in names -> "replaces"
            else -> ""
        }
    }
    val ownPaths = form.own.paths.map { pathProblem(it.host, it.container) }
    val ownPorts = form.own.ports.mapIndexed { at, r -> if (r.host == "" && r.container == "") "empty" else portProblem(r.host, r.container, form.ports.size + at) }
    val ownDevices = form.own.devices.map { if (validDevice(it)) "" else "invalid" }
    val images = form.images.map { !validImage(it.image) }
    val name = if (!APP_NAME.matches(form.name)) "invalid" else if (form.name in installed) "exists" else ""
    val missing = vars.count { it.isNotEmpty() }
    val cpuset = !validCpuset(form.cpuset)
    val mem = !validMemLimit(form.memLimit)
    val blocking = images.any { it } || (form.publicUrl.isNotBlank() && publicURL(form.publicUrl).isEmpty()) || name != "" || missing > 0 ||
        "invalid" in (paths + ownPaths) || (ports + ownPorts).any { it.isNotEmpty() } || ownVars.any { it != "" && it != "replaces" } ||
        ownDevices.any { it.isNotEmpty() } || cpuset || mem
    return InstallProblems(images, name, vars, paths, ports, ownVars, ownPaths, ownPorts, ownDevices, cpuset, mem, missing, blocking)
}

/** Das Eingetragene in der Vorlage. Geheimnisse kommen getrennt zurück: die Compose-Datei nennt sie nur als ${NAME}. */
fun buildInstall(tpl: TemplateInfo, form: InstallForm): kotlin.Pair<String, Map<String, String>> {
    val doc = YamlDoc.parse(tpl.compose)
    val env = LinkedHashMap<String, String>()
    val meta = readMeta(tpl.compose)
    val own = form.own.vars.associate { it.name to it.value }

    for (service in serviceNames(doc)) {
        var entries = readService(doc, service)
        form.images.firstOrNull { it.service == service }?.let { entries = entries.copy(image = it.image) }
        if (service == form.main) entries = entries.copy(publicUrl = form.publicUrl)
        if (service == form.main && meta.icon is String) {
            entries = entries.copy(labels = entries.labels.filter { it.name != "titanos.icon" } + Pair("titanos.icon", meta.icon))
        }
        val templatePorts = entries.ports.map { it.host }

        val newEnv = entries.env.map { pair ->
            val field = form.vars.firstOrNull { it.service == service && it.name == pair.name }
            if (field == null) {
                val from = record(meta.variables[pair.name])["from"]
                return@map when {
                    from is String && ENV_NAME.matches(from) -> pair.copy(value = "\${$from}")
                    form.vars.any { it.secret && it.name == pair.name && it.value != "" } -> pair.copy(value = "\${${pair.name}}")
                    else -> pair
                }
            }
            var value = if (field.secret && field.value != "") {
                env[field.name] = field.value
                "\${${field.name}}"
            } else {
                field.value
            }
            if (service == form.main && own.containsKey(pair.name)) value = own.getValue(pair.name)
            pair.copy(value = value)
        }.filter { it.value != "" }
        entries = entries.copy(env = newEnv)

        val newPaths = entries.paths.toMutableList()
        for (field in form.paths) {
            if (field.service != service || field.index !in newPaths.indices) continue
            val unchanged = !field.touched && field.share == null
            newPaths[field.index] = newPaths[field.index].copy(host = if (unchanged) field.template else field.host.trim())
        }
        val newPorts = entries.ports.toMutableList()
        for (field in form.ports) {
            if (field.service == service && field.index in newPorts.indices) newPorts[field.index] = newPorts[field.index].copy(host = numberText(field.host))
        }
        entries = entries.copy(paths = newPaths, ports = newPorts)

        val webui = form.ports.firstOrNull { it.service == service && it.webui }
        val address = Regex("^(https?://\\[host]:)?(\\d+)(.*)$").find(entries.webui)
        if (webui != null && entries.webui == "") {
            entries = entries.copy(webui = numberText(webui.host))
        } else if (address != null) {
            val moved = form.ports.firstOrNull { it.service == service && it.index == templatePorts.indexOf(address.groupValues[2]) }
            if (moved != null) entries = entries.copy(webui = address.groupValues[1] + numberText(moved.host) + address.groupValues[3])
        }

        entries = entries.copy(devices = entries.devices.filter { it.raw != null || it.value.split(":")[0] !in form.missingDevices })

        if (service == form.main) {
            val e = entries.env.toMutableList()
            for (p in form.own.vars) if (p.name != "" && e.none { it.name == p.name }) e.add(Pair(p.name, p.value))
            val ps = entries.paths.toMutableList()
            for (r in form.own.paths) ps.add(PathRow(r.host.trim(), r.container.trim(), false))
            val po = entries.ports.toMutableList()
            for (r in form.own.ports) if (r.host != "" || r.container != "") po.add(PortRow(numberText(r.host), numberText(r.container), r.protocol))
            val ls = entries.labels.toMutableList()
            for (p in form.own.labels) if (p.name.isNotBlank()) ls.add(Pair(p.name.trim(), p.value))
            val ds = entries.devices.toMutableList()
            for (d in form.own.devices) if (d.isNotBlank()) ds.add(DeviceRow(d.trim()))
            entries = entries.copy(env = e, paths = ps, ports = po, labels = ls, devices = ds, cpuset = form.cpuset.trim(), memLimit = form.memLimit.trim())
        }
        writeService(doc, service, entries)
    }
    return doc.toString() to env
}

/** `String(Number(text))` für Ports: leer wird 0, ungültig NaN. */
private fun numberText(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return "0"
    return t.toBigIntegerOrNull()?.toString() ?: t.toDoubleOrNull()?.let { YamlDoc.jsNumber(it) } ?: "NaN"
}
