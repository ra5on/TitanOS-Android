package io.github.ra5on.titanos.docker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.SmallButton
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.docker.compose.CasaContext
import io.github.ra5on.titanos.docker.compose.ImageField
import io.github.ra5on.titanos.docker.compose.InstallForm
import io.github.ra5on.titanos.docker.compose.OwnPath
import io.github.ra5on.titanos.docker.compose.OwnPort
import io.github.ra5on.titanos.docker.compose.Pair
import io.github.ra5on.titanos.docker.compose.PathField
import io.github.ra5on.titanos.docker.compose.PortField
import io.github.ra5on.titanos.docker.compose.ShareRef
import io.github.ra5on.titanos.docker.compose.TemplateInfo
import io.github.ra5on.titanos.docker.compose.VarField
import io.github.ra5on.titanos.docker.compose.buildInstall
import io.github.ra5on.titanos.docker.compose.checkInstall
import io.github.ra5on.titanos.docker.compose.defaultHost
import io.github.ra5on.titanos.docker.compose.fromCasaOS
import io.github.ra5on.titanos.docker.compose.hardwareSelections
import io.github.ra5on.titanos.docker.compose.isCasaOS
import io.github.ra5on.titanos.docker.compose.isRelative
import io.github.ra5on.titanos.docker.compose.localize
import io.github.ra5on.titanos.docker.compose.readTemplate
import io.github.ra5on.titanos.docker.compose.withHardware
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@kotlinx.serialization.Serializable
data class NewApp(val name: String, val compose: String, val env: Map<String, String>)

/** Was installiert wird: eine App des Katalogs oder ein eigener Container (Vorlage /apps/blank). */
data class InstallTarget(val source: String, val id: String, val title: String) {
    val own get() = source.isEmpty() || id.isEmpty()
}

/**
 * Das geführte Installationsformular wie routes/apps/InstallPage.svelte und
 * install-form.svelte.ts: Name, Images, Variablen, Pfade, Ports, Hardware,
 * eigene Einträge, HTTPS-Name, wahlweise die Compose-Datei; danach der
 * Auftrag bis die App läuft. CasaOS-Vorlagen werden vorher übersetzt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InstallPage(env: ComputeEnv, state: DockerState, target: InstallTarget, onClose: () -> Unit, onDone: () -> Unit) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val c = Titan.colors
    val locale = LocalConfiguration.current.locales[0].language
    var tpl by remember(target) { mutableStateOf<TemplateInfo?>(null) }
    var form by remember(target) { mutableStateOf<InstallForm?>(null) }
    var loadError by remember(target) { mutableStateOf<String?>(null) }
    var shares by remember { mutableStateOf<List<NasShare>>(emptyList()) }
    var volumes by remember { mutableStateOf<List<StorageVolume>>(emptyList()) }
    var proxy by remember { mutableStateOf<ProxyStatus?>(null) }
    var hardware by remember(target) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var advanced by remember { mutableStateOf(AppFormPrefs.advanced(context)) }
    var view by remember(target) { mutableStateOf("form") }
    var text by remember(target) { mutableStateOf("") }
    var draftEnv by remember(target) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember(target) { mutableStateOf<kotlin.Pair<String, String>?>(null) }
    var httpsOn by remember(target) { mutableStateOf(true) }
    var httpsName by remember(target) { mutableStateOf("") }
    var httpsTouched by remember(target) { mutableStateOf(false) }
    var phase by remember(target) { mutableStateOf("form") }
    var job by remember(target) { mutableStateOf<DockerJob?>(null) }
    var elapsed by remember(target) { mutableIntStateOf(0) }
    var httpsResult by remember(target) { mutableStateOf<String?>(null) }
    var baseline by remember(target) { mutableStateOf<InstallForm?>(null) }

    val status = state.status
    val installed = status?.apps.orEmpty()
    val used = remember(tpl, status) {
        val m = HashMap<String, String>()
        tpl?.listening?.forEach { (port, proto) -> m["$port/$proto"] = "" }
        for (ct in status?.containers.orEmpty()) for (p in ct.ports) m["${p.host}/${p.protocol}"] = ct.label("distinct")
        m
    }
    fun by(name: String?) = name?.ifEmpty { null } ?: ui.text("apps.system")

    LaunchedEffect(target) {
        try {
            if (state.status == null) state.load()
            shares = state.api.shares()
            volumes = state.api.storage()
            proxy = state.api.proxy()
            val res = if (target.own) state.api.blankTemplate() else state.api.template(target.source, target.id)
            val base = if (target.own) "container" else target.id.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trimStart('-', '_').take(32).ifEmpty { "app" }
            var name = base
            var n = 2
            while (name in state.status?.apps.orEmpty()) { name = "${base.take(29)}-$n"; n++ }
            var compose = res.compose
            if (isCasaOS(compose)) compose = fromCasaOS(compose, base, CasaContext(res.timezone, res.uid, res.gid, nasHost(env)))
            val info = TemplateInfo(compose, res.timezone, res.uid, res.gid, res.appdata, res.listening.map { it.port to it.protocol }, res.missingDevices, res.cpuMissing)
            tpl = info
            hardware = hardwareSelections(compose)
            var f = readTemplate(info, name, shares.filter { it.available }.map { ShareRef(it.name, it.path) }, used)
            if (target.own) f = f.copy(images = listOf(ImageField(f.main, "")))
            form = f
            baseline = f
            httpsName = suggestedProxyName(name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            loadError = ui.text("apps.templateBroken") + " " + (e.message ?: "")
        } catch (e: Exception) {
            loadError = ui.errorOf(e)
        }
    }

    ScrollPage {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(TitanIcons.back, stringResource(io.github.ra5on.titanos.ui.R.string.ui_back), {
                if (phase == "form" && form != null && form != baseline) {
                    ui.confirm(Ask(ui.text("apps.discardTitle"), ui.text("apps.discardText"), ui.text("shares.discard")) { onClose() })
                } else onClose()
            })
            Text(
                if (target.own) t("appform.ownTitle") else t("appform.installTitle", "name" to target.title),
                style = TitanType.title, color = c.text, modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        loadError?.let { Problem(it); return@ScrollPage }
        val tp = tpl
        val f = form
        if (tp == null || f == null) {
            Text(t("apps.loading"), style = TitanType.rowSub, color = c.muted)
            return@ScrollPage
        }
        if (phase != "form") {
            InstallProgress(env, state, f.name, phase, job, elapsed, httpsResult, onDone)
            return@ScrollPage
        }
        if (status?.settings?.enabled == false || status?.running == false) Note(t("apps.dockerOff"))
        ViewSwitch(advanced) { advanced = it; AppFormPrefs.setAdvanced(context, it) }
        val tips = localize(f.tips, locale)
        if (tips.isNotEmpty()) Note(t("apps.tips") + "\n" + tips)
        if (f.risks.isNotEmpty()) Problem(t("apps.risks") + " " + f.risks.joinToString(", ") { ui.text("apps.risk.$it") })
        if (f.missingDevices.isNotEmpty()) Note(t("apps.missingDevices", "devices" to f.missingDevices.joinToString(", ")))
        if (tp.cpuMissing.isNotEmpty()) Problem(t("apps.cpuMissing", "list" to tp.cpuMissing.joinToString(", ")))
        val problems = checkInstall(f, installed, used)
        val webPort = f.ports.firstOrNull { it.webui && it.service == f.main } ?: f.ports.firstOrNull { it.service == f.main }
        val offerHttps = proxy != null && proxy?.off == false && webPort != null
        val httpsProblem = when {
            offerHttps && httpsOn && !validProxyName(httpsName.trim().lowercase()) -> ui.text("appform.httpsNameInvalid")
            offerHttps && httpsOn && proxy!!.apps.any { it.name == httpsName.trim().lowercase() } -> ui.text("appform.httpsNameTaken")
            else -> ""
        }

        if (view == "compose") {
            Text(t("apps.composeNote"), style = TitanType.small, color = c.muted)
            CodeEditor(text, { text = it }, t("appform.composeFile"), minHeight = 320)
        } else {
            InstallFields(state.api, tp, f, problems, advanced, used, volumes, hardware, { hardware = it }, ::by) { next ->
                // Ein neuer Name: unveränderte Pfade und der HTTPS-Name folgen ihm.
                val renamed = if (next.name != f.name) next.copy(paths = next.paths.map { if (!it.touched) it.copy(host = defaultHost(it, next.name, tp.appdata)) else it }) else next
                if (next.name != f.name && !httpsTouched) httpsName = suggestedProxyName(next.name)
                form = renamed
            }
            if (offerHttps) {
                Section(t("appform.httpsName"))
                SwitchRow(t("appform.httpsName"), httpsOn, { httpsOn = it }, sub = if (httpsOn) t("appform.httpsHint") else t("appform.httpsOffHint"))
                if (httpsOn) Field(t("proxy.name"), httpsName, { httpsName = it.lowercase(); httpsTouched = true }, error = httpsProblem.ifEmpty { null },
                    hint = proxy?.suffixes?.firstOrNull()?.let { "https://${httpsName.trim().lowercase()}.$it" })
            }
        }
        error?.let { (msg, detail) ->
            Problem(msg)
            if (detail.isNotEmpty()) CodeEditor(detail, null, msg, minHeight = 60)
        }
        if (problems.missing > 0) Text(t("appform.required") + ": ${problems.missing}", style = TitanType.small, color = c.warnInk)
        Card(Modifier.padding(top = 12.dp)) {
            FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(t("apps.install"), TitanIcons.download, {
                    var current = f
                    var currentTpl = tp
                    if (view == "compose") {
                        try {
                            val next = tp.copy(compose = text)
                            current = readTemplate(next, f.name, emptyList(), used, draftEnv)
                            hardware = hardwareSelections(text)
                            currentTpl = next
                            tpl = next
                            form = current
                        } catch (e: Exception) {
                            error = ui.text("editor.yamlError") to (e.message ?: "")
                            return@SmallButton
                        }
                    }
                    if (checkInstall(current, installed, used).blocking || httpsProblem.isNotEmpty()) {
                        error = ui.text("apps.correctFields") to ""
                        return@SmallButton
                    }
                    error = null
                    val (compose, secrets) = buildInstall(currentTpl, current)
                    phase = "installing"
                    job = DockerJob("running")
                    val started = System.currentTimeMillis()
                    val name = current.name
                    val port = (current.ports.firstOrNull { it.webui && it.service == current.main } ?: current.ports.firstOrNull { it.service == current.main })?.host?.toIntOrNull()
                    ui.run(onError = { e ->
                        val code = (e as? io.github.ra5on.titanos.net.ApiException)?.code ?: "internal"
                        phase = "form"
                        job = null
                        error = ui.errorOf(e) to if (code == "invalid_compose" || code == "docker_failed") e.message?.substringAfter(": ", "").orEmpty() else ""
                    }) {
                        state.api.access.send("POST", "/docker/apps", NewApp(name, withHardware(compose, hardware), secrets))
                        while (true) {
                            delay(1200)
                            elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
                            val next = runCatching { state.api.status() }.getOrNull() ?: continue
                            state.status = next
                            val current2 = next.jobs[name]
                            if (current2 == null) { job = DockerJob("failed", job?.output.orEmpty()); phase = "failed"; return@run }
                            job = current2
                            if (current2.state == "running") continue
                            if (current2.state == "failed") { phase = "failed"; return@run }
                            break
                        }
                        if (offerHttps && httpsOn && port != null) {
                            val route = proxyForm(null, httpsName.trim().lowercase()).copy(enabled = true, port = port)
                            httpsResult = try {
                                state.api.saveProxy(name, proxyBody(route)); ""
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                ui.errorOf(e)
                            }
                            runCatching { state.status = state.api.status() }
                        }
                        phase = "done"
                    }
                }, primary = true, enabled = !problems.blocking && httpsProblem.isEmpty())
                SmallButton(t(if (view == "form") "apps.asCompose" else "apps.asForm"), TitanIcons.edit, {
                    if (view == "form") {
                        val (compose, secrets) = buildInstall(tp, f)
                        text = withHardware(compose, hardware)
                        draftEnv = secrets
                        view = "compose"
                    } else {
                        try {
                            val next = tp.copy(compose = text)
                            form = readTemplate(next, f.name, emptyList(), used, draftEnv)
                            hardware = hardwareSelections(text)
                            tpl = next
                            error = null
                            view = "form"
                        } catch (e: Exception) {
                            error = ui.text("editor.yamlError") to (e.message ?: "")
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun origin(kind: String) = t("apps.origin.$kind")

/** Die Felder des Formulars (InstallFields.svelte). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InstallFields(
    api: DockerApi,
    tpl: TemplateInfo,
    f: InstallForm,
    problems: io.github.ra5on.titanos.docker.compose.InstallProblems,
    advanced: Boolean,
    used: Map<String, String>,
    volumes: List<StorageVolume>,
    hardware: Map<String, List<String>>,
    onHardware: (Map<String, List<String>>) -> Unit,
    by: (String?) -> String,
    onChange: (InstallForm) -> Unit,
) {
    val ui = LocalComputeUi.current
    val c = Titan.colors
    val locale = LocalConfiguration.current.locales[0].language
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)
    Field(t("apps.name"), f.name, { onChange(f.copy(name = it.lowercase())) },
        error = when (problems.name) { "invalid" -> ui.text("apps.nameInvalid"); "exists" -> ui.text("apps.nameExists"); else -> null },
        hint = t("apps.nameHint", "path" to "${tpl.appdata.trimEnd('/')}/${f.name}"))
    val mainImage = f.images.indexOfFirst { it.service == f.main }.coerceAtLeast(0)
    f.images.forEachIndexed { i, img ->
        if (!advanced && i != mainImage) return@forEachIndexed
        Field(if (f.images.size > 1) t("appform.repositoryOf", "service" to img.service) else t("appform.repository"), img.image,
            { v -> onChange(f.copy(images = f.images.toMutableList().also { it[i] = img.copy(image = v) })) },
            error = if (problems.images.getOrNull(i) == true) t("editor.imageInvalid") else null)
    }
    if (!advanced && f.images.size > 1) Text(t("appform.moreImages", "count" to f.images.size - 1), style = TitanType.small, color = c.muted)
    if (advanced) {
        Field(t("editor.publicUrl"), f.publicUrl, { onChange(f.copy(publicUrl = it)) }, hint = t("editor.publicUrlHint"), placeholder = "https://app.example.org")
        Field(t("appform.cpus"), f.cpuset, { onChange(f.copy(cpuset = it)) }, error = if (problems.cpuset) t("appform.cpusInvalid") else null, hint = t("appform.cpusHint"))
        Field(t("appform.memory"), f.memLimit, { onChange(f.copy(memLimit = it)) }, error = if (problems.memLimit) t("appform.memoryInvalid") else null, hint = t("appform.memoryHint"))
    }

    if (f.vars.isNotEmpty() || f.own.vars.isNotEmpty()) Section(t("appform.sectionAccess"))
    f.vars.forEachIndexed { i, v ->
        fun set(next: VarField) = onChange(f.copy(vars = f.vars.toMutableList().also { it[i] = next }))
        val label = localize(v.label, locale).ifEmpty { v.name }
        val help = localize(v.help, locale)
        val note = when {
            v.secret && v.origin == "generated" -> ui.text("apps.note.generated")
            v.fill.isNotEmpty() && v.origin == "titanos" -> ui.text("apps.note.${v.fill}")
            else -> ""
        }
        val orig = if (v.required && v.value.isBlank()) "required" else if (v.origin == "required") "own" else v.origin
        val hint = listOf(ui.text("appform.variable", "name" to v.name), help.ifEmpty { note }, ui.text("apps.origin.$orig")).filter { it.isNotEmpty() }.joinToString(" · ")
        Field(label + if (v.required) " *" else "", v.value, { set(v.copy(value = it, origin = if (v.origin == "generated" || v.origin == "titanos") "template" else v.origin)) },
            error = if (problems.vars.getOrNull(i) == "required") t("appform.required") else null, hint = hint, placeholder = v.placeholder.ifEmpty { null })
    }
    f.own.vars.forEachIndexed { i, p ->
        fun set(next: Pair) = onChange(f.copy(own = f.own.copy(vars = f.own.vars.toMutableList().also { it[i] = next })))
        Field(t("apps.ownVar"), p.name, { set(p.copy(name = it)) }, error = when (problems.ownVars.getOrNull(i)) {
            "invalid", "empty" -> t("apps.ownVarInvalid"); "duplicate" -> t("apps.ownVarDuplicate"); else -> null
        }, hint = if (problems.ownVars.getOrNull(i) == "replaces") t("apps.ownVarReplaces") else null)
        Field(t("apps.ownVarValue"), p.value, { set(p.copy(value = it)) })
        SmallButton(t("apps.remove"), TitanIcons.trash, { onChange(f.copy(own = f.own.copy(vars = f.own.vars.filterIndexed { j, _ -> j != i }))) })
    }

    Section(t("appform.sectionEntries"))
    f.paths.forEachIndexed { i, p ->
        fun set(next: PathField) = onChange(f.copy(paths = f.paths.toMutableList().also { it[i] = next }))
        val problem = problems.paths.getOrNull(i).orEmpty()
        val note = when {
            p.share != null -> ui.text("apps.note.share", "name" to p.share.name)
            !p.touched && isRelative(p.template) && p.wanted -> ui.text("apps.note.noShare")
            else -> ""
        }
        val orig = if (p.touched) "own" else if (p.share != null || isRelative(p.template)) "titanos" else "template"
        val hint = when (problem) {
            "invalid" -> ui.text("apps.pathInvalid")
            "volatile" -> ui.text("apps.pathVolatile")
            else -> listOf(ui.text("appform.containerPathHint", "path" to p.container), whereText(p.host, volumes).ifEmpty { note }, ui.text("apps.origin.$orig")).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        Field(localize(p.label, locale).ifEmpty { p.container }, p.host, { v -> set(p.copy(host = v, touched = v != defaultHost(p, f.name, tpl.appdata))) },
            error = if (problem == "invalid") hint else null, hint = if (problem != "invalid") hint else null)
    }
    f.own.paths.forEachIndexed { i, p ->
        fun set(next: OwnPath) = onChange(f.copy(own = f.own.copy(paths = f.own.paths.toMutableList().also { it[i] = next })))
        Field(t("apps.ownPath"), p.host, { set(p.copy(host = it)) }, error = if (problems.ownPaths.getOrNull(i) == "invalid") t("apps.pathInvalid") else null,
            hint = if (problems.ownPaths.getOrNull(i) == "volatile") t("apps.pathVolatile") else t("appform.containerPathHint", "path" to p.container))
        if (advanced) Field(t("appform.containerPath"), p.container, { set(p.copy(container = it)) })
        SmallButton(t("apps.remove"), TitanIcons.trash, { onChange(f.copy(own = f.own.copy(paths = f.own.paths.filterIndexed { j, _ -> j != i }))) })
    }
    f.ports.forEachIndexed { i, p ->
        fun set(next: PortField) = onChange(f.copy(ports = f.ports.toMutableList().also { it[i] = next }))
        val problem = problems.ports.getOrNull(i).orEmpty()
        val target = ui.text("appform.containerPortHint", "port" to "${p.container}/${p.protocol}")
        val hint = when {
            problem == "invalid" -> ui.text("apps.portInvalid")
            problem == "duplicate" -> ui.text("apps.portDuplicate")
            problem == "inUse" -> ui.text("apps.portInUse", "by" to by(used["${p.host.toIntOrNull()}/${p.protocol}"])) + " · " + target
            p.blockedBy != null -> ui.text("apps.portMoved", "port" to p.templateHost, "by" to by(p.blockedBy), "free" to p.host) + " · " + target
            else -> target + " · " + ui.text("apps.portFree", "port" to p.host)
        }
        val label = localize(p.label, locale).ifEmpty { ui.text("apps.portDefault", "port" to p.container) }
        Field(label, p.host, { set(p.copy(host = it.filter(Char::isDigit))) }, error = if (problem.isNotEmpty()) hint else null, hint = if (problem.isEmpty()) hint else null, keyboard = num)
        if (problem == "inUse") {
            val taken = (f.ports.map { "${it.host.toIntOrNull()}/${it.protocol}" } + f.own.ports.map { "${it.host.toIntOrNull()}/${it.protocol}" }).toSet()
            var free = p.host.toIntOrNull() ?: 1
            while (free < 65535 && (used.containsKey("$free/${p.protocol}") || "$free/${p.protocol}" in taken)) free++
            SmallButton(t("appform.takePort", "port" to free), TitanIcons.check, { set(p.copy(host = free.toString())) })
        }
    }
    f.own.ports.forEachIndexed { i, p ->
        fun set(next: OwnPort) = onChange(f.copy(own = f.own.copy(ports = f.own.ports.toMutableList().also { it[i] = next })))
        val problem = problems.ownPorts.getOrNull(i).orEmpty()
        Field(t("apps.ownPortHost"), p.host, { set(p.copy(host = it.filter(Char::isDigit))) }, keyboard = num, error = when (problem) {
            "invalid", "empty" -> t("apps.portInvalid"); "duplicate" -> t("apps.portDuplicate"); "inUse" -> t("apps.portInUse", "by" to by(used["${p.host.toIntOrNull()}/${p.protocol}"])); else -> null
        }, hint = t("appform.containerPortHint", "port" to "${p.container}/${p.protocol}"))
        SmallButton(t("apps.remove"), TitanIcons.trash, { onChange(f.copy(own = f.own.copy(ports = f.own.ports.filterIndexed { j, _ -> j != i }))) })
    }
    if (advanced) f.own.labels.forEachIndexed { i, p ->
        fun set(next: Pair) = onChange(f.copy(own = f.own.copy(labels = f.own.labels.toMutableList().also { it[i] = next })))
        Field(t("appform.label"), p.name, { set(p.copy(name = it)) })
        Field(t("editor.envValue"), p.value, { set(p.copy(value = it)) })
        SmallButton(t("apps.remove"), TitanIcons.trash, { onChange(f.copy(own = f.own.copy(labels = f.own.labels.filterIndexed { j, _ -> j != i }))) })
    }

    Section(t("appform.sectionDevices"))
    val services = hardware.keys.sortedByDescending { it == f.main }.filterIndexed { i, _ -> advanced || i == 0 }
    for (s in services) {
        Text(if (hardware.size > 1) t("appform.gpuFor", "service" to s) else t("appform.gpu"), style = TitanType.rowTitle, color = c.text)
        Text(t("hardware.containerHint"), style = TitanType.small, color = c.muted)
        HardwarePicker(api, hardware[s].orEmpty()) { onHardware(hardware + (s to it)) }
    }
    f.own.devices.forEachIndexed { i, d ->
        Field(t("appform.device"), d, { v -> onChange(f.copy(own = f.own.copy(devices = f.own.devices.toMutableList().also { it[i] = v }))) },
            error = if (problems.ownDevices.getOrNull(i) == "invalid") t("appform.addDeviceHint") else null)
        SmallButton(t("apps.remove"), TitanIcons.trash, { onChange(f.copy(own = f.own.copy(devices = f.own.devices.filterIndexed { j, _ -> j != i }))) })
    }
    SmallButton(t("appform.addEntry"), TitanIcons.plus, {
        ui.sheet = {
            AddEntrySheet { e ->
                onChange(
                    when (e) {
                        is NewEntry.Path -> f.copy(own = f.own.copy(paths = f.own.paths + OwnPath(e.host, e.container)))
                        is NewEntry.Port -> f.copy(own = f.own.copy(ports = f.own.ports + OwnPort(e.host, e.container, e.protocol)))
                        is NewEntry.Variable -> f.copy(own = f.own.copy(vars = f.own.vars + Pair(e.name, e.value)))
                        is NewEntry.Label -> f.copy(own = f.own.copy(labels = f.own.labels + Pair(e.name, e.value)))
                        is NewEntry.Device -> f.copy(own = f.own.copy(devices = f.own.devices + e.device))
                    },
                )
            }
        }
    })
    Text(t("appform.ownHint"), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 4.dp))
}

/** Nach „Installieren“: der Auftrag bis die App läuft (InstallProgress.svelte). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstallProgress(env: ComputeEnv, state: DockerState, name: String, phase: String, job: DockerJob?, elapsed: Int, httpsResult: String?, onDone: () -> Unit) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    when (phase) {
        "installing" -> {
            StateMark(t("appform.background"), StateKind.Busy)
            Text(t("appform.backgroundHint"), style = TitanType.small, color = Titan.colors.muted)
            Text(String.format(java.util.Locale.ROOT, "%d:%02d", elapsed / 60, elapsed % 60), style = TitanType.rowSub, color = Titan.colors.muted)
        }
        "done" -> {
            StateMark(t("appform.installed", "name" to name), StateKind.Ok)
            if (!httpsResult.isNullOrEmpty()) Problem(httpsResult)
            val links = state.status?.containers.orEmpty().filter { it.app == name }.flatMap { webLinks(it, nasHost(env)) }.distinctBy { it.url }
            for (l in links) ClickRow(t("appform.open") + " " + l.url) { openUrl(context, l.url) }
            SmallButton(t("common.done"), TitanIcons.check, onDone, primary = true)
        }
        else -> {
            Problem(t("appform.installFailed"))
            if (job != null) Text(dockerJobOutput(ui, job).takeLast(400), style = TitanType.small, color = Titan.colors.muted)
            SmallButton(t("common.done"), TitanIcons.check, onDone)
        }
    }
    job?.output?.takeIf { it.isNotBlank() }?.let { CodeEditor(it.lines().takeLast(60).joinToString("\n"), null, t("appform.logCommand"), minHeight = 120) }
}
