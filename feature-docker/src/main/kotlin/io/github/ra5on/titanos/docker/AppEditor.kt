package io.github.ra5on.titanos.docker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Chips
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.ComputeUi
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.InfoRow
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.SmallButton
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.compute.tOr
import io.github.ra5on.titanos.docker.compose.DeviceRow
import io.github.ra5on.titanos.docker.compose.NETWORKS
import io.github.ra5on.titanos.docker.compose.Pair
import io.github.ra5on.titanos.docker.compose.PathRow
import io.github.ra5on.titanos.docker.compose.PortRow
import io.github.ra5on.titanos.docker.compose.RESTART_POLICIES
import io.github.ra5on.titanos.docker.compose.ServiceForm
import io.github.ra5on.titanos.docker.compose.ServiceProblems
import io.github.ra5on.titanos.docker.compose.YamlDoc
import io.github.ra5on.titanos.docker.compose.assignContainerLAN
import io.github.ra5on.titanos.docker.compose.checkService
import io.github.ra5on.titanos.docker.compose.lanAssignment
import io.github.ra5on.titanos.docker.compose.publicURL
import io.github.ra5on.titanos.docker.compose.readService
import io.github.ra5on.titanos.docker.compose.serviceNames
import io.github.ra5on.titanos.docker.compose.validCpuset
import io.github.ra5on.titanos.docker.compose.validDevice
import io.github.ra5on.titanos.docker.compose.validImage
import io.github.ra5on.titanos.docker.compose.validMemLimit
import io.github.ra5on.titanos.docker.compose.writeService
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.delay

data class ApplyResult(val ok: Boolean, val text: String, val detail: String = "")

/**
 * Zustand des App-Editors wie app-editor.svelte.ts: die Compose-Datei als
 * Text und als Formular eines Dienstes. Jede Änderung im Formular wird sofort
 * in den Text geschrieben (alles andere der Datei bleibt), „Übernehmen“
 * schickt den Text und wartet auf den Auftrag des NAS.
 */
class AppEditorState(val api: DockerApi, val app: String) {
    var original by mutableStateOf<String?>(null)
    var text by mutableStateOf("")
    var view by mutableStateOf("form")
    var services by mutableStateOf(emptyList<String>())
    var service by mutableStateOf("")
    var form by mutableStateOf<ServiceForm?>(null)
    var yamlError by mutableStateOf("")
    var loadError by mutableStateOf<Throwable?>(null)
    var applying by mutableStateOf(false)
    var result by mutableStateOf<ApplyResult?>(null)
    private var doc: YamlDoc? = null
    /** Vorab gefüllt (Bildtests): nicht vom NAS neu laden. */
    var preloaded = false

    val changed: Boolean get() = original != null && text != original

    /** Liest den Text in das Formular; false, wenn er kein gültiges YAML ist. */
    fun parse(): Boolean = try {
        val d = YamlDoc.parse(text)
        yamlError = ""
        doc = d
        services = serviceNames(d)
        if (service !in services) service = services.firstOrNull().orEmpty()
        form = if (service.isNotEmpty()) readService(d, service) else null
        true
    } catch (e: Exception) {
        yamlError = e.message ?: "YAML"
        false
    }

    /** Schreibt das Formular in den Text; nach jeder Änderung im Formular. */
    fun commit(next: ServiceForm) {
        form = next
        val d = doc ?: return
        if (service.isEmpty()) return
        writeService(d, service, next)
        val o = original
        // Inhaltlich wie gespeichert: den gespeicherten Text behalten (Form und Kommentare).
        text = if (o != null && runCatching { YamlDoc.parse(o).toPlain() == d.toPlain() }.getOrDefault(false)) o else d.toString()
    }

    fun show(next: String) {
        if (next == "form" && !parse()) return
        view = next
    }

    fun pick(next: String) {
        service = next
        doc?.let { form = readService(it, next) }
    }

    /** Das Formular des Dienstes, wie es gespeichert ist (für „geändert“). */
    fun before(): ServiceForm? = original?.let { o ->
        runCatching { YamlDoc.parse(o) }.getOrNull()?.takeIf { service in serviceNames(it) }?.let { readService(it, service) }
    }

    suspend fun load() {
        try {
            val compose = api.appCompose(app)
            original = compose
            text = compose
            loadError = null
            if (!parse()) view = "compose"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e
        }
    }

    fun discard() {
        text = original ?: return
        result = null
        if (!parse()) view = "compose"
    }

    /** Übernehmen: Text speichern, dann auf den Auftrag warten (alle 1,5 s). */
    suspend fun apply(ui: ComputeUi, onStatus: (DockerStatus) -> Unit) {
        if (view == "compose" && !parse()) return
        applying = true
        result = null
        try {
            api.saveCompose(app, text)
            while (true) {
                delay(1500)
                val next = api.status()
                onStatus(next)
                val job = next.jobs[app]
                if (job?.state == "running") continue
                result = when {
                    job == null -> ApplyResult(false, ui.text("editor.jobLost"))
                    job.state == "done" -> { original = text; ApplyResult(true, ui.text("editor.applied")) }
                    else -> ApplyResult(false, ui.text("editor.failed"), dockerJobOutput(ui, job))
                }
                break
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? io.github.ra5on.titanos.net.ApiException)?.code ?: "internal"
            result = ApplyResult(false, ui.errorOf(e), if (code == "invalid_compose" || code == "docker_failed") e.message?.substringAfter(": ", "").orEmpty() else "")
        }
        applying = false
    }
}

/** Ausgabe eines Docker-Auftrags; die letzte Zeile (Grund von titand) in der Sprache der App (lib/job-code.ts). */
internal fun dockerJobOutput(ui: ComputeUi, job: DockerJob): String {
    val key = if (Regex("^[a-z_]+$").matches(job.code)) "docker.jobCode.${job.code}" else ""
    val text = if (key.isNotEmpty() && io.github.ra5on.titanos.compute.hasText(key)) ui.text(key) else ""
    if (text.isEmpty()) return job.output
    val lines = job.output.trimEnd().split("\n").toMutableList()
    lines[lines.size - 1] = text
    return lines.joinToString("\n")
}

/**
 * Der Editor auf der Seite einer App (routes/docker/apps/[name]): Formular
 * des Dienstes oder Compose-Datei, Leiste mit Übernehmen/Verwerfen, dazu
 * HTTPS-Name, Zugriff, Tailnet, Öffnen mit und Freigaben.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppEditorSection(env: io.github.ra5on.titanos.compute.ComputeEnv, state: DockerState, g: DockerGroup) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val app = g.app
    val ed = remember(app) { state.editors.getOrPut(app) { AppEditorState(state.api, app) } }
    var advanced by remember { mutableStateOf(AppFormPrefs.advanced(context)) }
    var proxy by remember(app) { mutableStateOf<ProxyStatus?>(null) }
    var proxyVersion by remember { mutableIntStateOf(0) }
    var volumes by remember { mutableStateOf<List<StorageVolume>>(emptyList()) }
    LaunchedEffect(app) {
        if (!ed.preloaded && !ed.changed && !ed.applying) ed.load()
        volumes = state.api.storage()
    }
    LaunchedEffect(app, proxyVersion) { proxy = state.api.proxy() }
    val published = inTailnet(proxy?.apps?.firstOrNull { it.app == app })

    Section(t("editor.title", "name" to app))
    ViewSwitch(advanced) { advanced = it; AppFormPrefs.setAdvanced(context, it) }
    ed.loadError?.let { ErrorRow(errorText(it)) { ui.run { ed.load() } } }
    if (ed.original == null && ed.loadError == null) Text(t("common.loading"), style = TitanType.rowSub, color = Titan.colors.muted)
    if (ed.yamlError.isNotEmpty()) {
        Problem(t("editor.yamlError"))
        Text(ed.yamlError, style = TitanType.small.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = Titan.colors.dangerInk)
    }
    if (ed.original == null) return
    val form = ed.form
    if (form != null && ed.view == "form" && ed.services.size > 1) {
        Chips(ed.services.map { it to it }, ed.service, { ed.pick(it) }, Modifier.padding(vertical = 6.dp))
    }
    InfoRow(t("apps.name"), app + if (ed.services.size > 1) " · ${ed.service}" else "")
    Text(t("editor.location", "path" to "/config/apps/$app/compose.yaml"), style = TitanType.small, color = Titan.colors.muted)
    if (ed.view == "compose") {
        CodeEditor(ed.text, { ed.text = it; ed.result = null }, t("editor.compose"), minHeight = 320)
    } else if (form != null) {
        val members = state.status?.containers.orEmpty().filter { it.app == app }
        val taken = HashMap<String, String>()
        for (c in state.status?.containers.orEmpty()) if (c.app != app) for (p in c.ports) taken["${p.host}/${p.protocol}"] = c.label("distinct")
        val problems = checkService(form, taken) { key, params -> ui.text(key, *params.map { it.key to it.value }.toTypedArray()) }
        ServiceFields(ed, form, problems, advanced, members, volumes)
        Section(t("appform.sectionAccess"))
        Text(t("appform.httpsName"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(top = 6.dp))
        AppProxySection(state.api, app, members, proxyVersion)
        Text(t("appaccess.whoMay"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(top = 10.dp))
        AppAccessSection(state.api, app)
        if (proxy != null) {
            InfoRow(t("appform.tailscale"), t(if (published) "appform.on" else "appform.off"))
            Text(t(if (published) "appform.tailnetOnHint" else "appform.tailnetOffHint"), style = TitanType.small, color = Titan.colors.muted)
            SmallButton(t(if (published) "docker.tailnetUnpublish" else "docker.tailnetPublish"), TitanIcons.globe, {
                ui.sheet = { TailnetSheet(state.api, app, members, published) { proxyVersion++ } }
            })
        }
        AppOpensSection(state.api, app, ed.services, locked = ed.changed) { ui.run { ed.load() } }
    } else {
        Note(t("editor.noServices"))
    }

    ed.result?.let { r ->
        if (r.ok) StateMark(r.text, StateKind.Ok) else Problem(r.text)
        if (r.detail.isNotEmpty()) CodeEditor(r.detail, null, t("editor.failed"), minHeight = 80)
    }
    val problemsAny = form?.let { f ->
        val bad = checkService(f, emptyMap()) { k, _ -> k }.any || !validCpuset(f.cpuset) || !validMemLimit(f.memLimit) || f.devices.any { it.raw == null && !validDevice(it.value) }
        bad
    } ?: false
    val blocked = ed.applying || !ed.changed || (ed.view == "form" && problemsAny)
    Card(Modifier.padding(top = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(t("editor.apply"), TitanIcons.check, { ui.run { ed.apply(ui) { state.status = it } } }, primary = true, enabled = !blocked)
                SmallButton(t("editor.discard"), TitanIcons.restart, {
                    ui.confirm(Ask(ui.text("editor.discardTitle"), ui.text("editor.discardText"), ui.text("editor.discard")) { ed.discard() })
                }, enabled = ed.changed && !ed.applying)
                SmallButton(t(if (ed.view == "form") "appform.editCompose" else "apps.asForm"), TitanIcons.edit, { ed.show(if (ed.view == "form") "compose" else "form") })
            }
            Text(
                t(if (ed.applying) "editor.applying" else if (ed.changed) "editor.applyNote" else "editor.unchanged"),
                style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Die Felder eines Dienstes (ServiceFields.svelte). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceFields(ed: AppEditorState, form: ServiceForm, problems: ServiceProblems, advanced: Boolean, members: List<DockerContainer>, volumes: List<StorageVolume>) {
    val ui = LocalComputeUi.current
    val c = Titan.colors
    val before = remember(ed.original, ed.service) { ed.before() }
    val changedWord = androidx.compose.ui.res.stringResource(R.string.docker_changed)
    fun mark(changed: Boolean) = if (changed && before != null) " · $changedWord" else ""
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)
    val update = members.any { it.updateAvailable }
    Field(
        t("appform.repository") + mark(before?.image != form.image), form.image, { ed.commit(form.copy(image = it)) },
        error = if (!validImage(form.image)) t("editor.imageInvalid") else null,
        hint = if (update) t("appform.updateAvailable") else null,
    )
    Field(t("editor.webui") + mark(before?.webui != form.webui), form.webui, { ed.commit(form.copy(webui = it)) }, hint = t("editor.webuiHint"), placeholder = "8096")
    val policies = RESTART_POLICIES.map { it to t("editor.restart.$it") } + if (form.restart !in RESTART_POLICIES) listOf(form.restart to form.restart) else emptyList()
    Choice(t("editor.restart") + mark(before?.restart != form.restart), policies, form.restart, { ed.commit(form.copy(restart = it)) })
    if (advanced) {
        val bad = form.publicUrl.isNotBlank() && publicURL(form.publicUrl).isEmpty()
        Field(t("editor.publicUrl") + mark(before?.publicUrl != form.publicUrl), form.publicUrl, { ed.commit(form.copy(publicUrl = it)) },
            error = if (bad) t("editor.publicUrlInvalid") else null, hint = t("editor.publicUrlHint"), placeholder = "https://app.example.org")
        Field(t("appform.cpus") + mark(before?.cpuset != form.cpuset), form.cpuset, { ed.commit(form.copy(cpuset = it)) },
            error = if (!validCpuset(form.cpuset)) t("appform.cpusInvalid") else null, hint = t("appform.cpusHint"), placeholder = "0-3")
        Field(t("appform.memory") + mark(before?.memLimit != form.memLimit), form.memLimit, { ed.commit(form.copy(memLimit = it)) },
            error = if (!validMemLimit(form.memLimit)) t("appform.memoryInvalid") else null, hint = t("appform.memoryHint"), placeholder = t("appform.noLimit"))
    }

    Section(t("editor.network"))
    val nets = NETWORKS.map { it to t("editor.network.$it") } + if (form.network !in NETWORKS) listOf(form.network to form.network) else emptyList()
    Choice(t("editor.network") + mark(before?.network != form.network), nets, form.network, { ed.commit(form.copy(network = it)) }, hint = t("appform.networkHint"))
    if (form.network != "host" && form.network != "none") LanAssignment(ed)
    SwitchRow(t("editor.privileged") + mark(before?.privileged != form.privileged), form.privileged, { ed.commit(form.copy(privileged = it)) }, sub = if (form.privileged) t("editor.privilegedWarning") else null)

    Section(t("appform.sectionDevices"))
    Text(t("appform.gpu") + mark(before?.hardware != form.hardware), style = TitanType.rowTitle, color = c.text)
    Text(t("hardware.containerHint"), style = TitanType.small, color = c.muted)
    HardwarePicker(ed.api, form.hardware) { ed.commit(form.copy(hardware = it)) }
    form.devices.forEachIndexed { i, row ->
        if (row.raw != null) {
            InfoRow(t("appform.device"), YamlDoc.jsString(row.raw) + " · " + t("editor.rawEntry"), mono = true)
        } else {
            Field(t("appform.device"), row.value, { v -> ed.commit(form.copy(devices = form.devices.toMutableList().also { it[i] = DeviceRow(v) })) },
                error = if (!validDevice(row.value)) t("appform.addDeviceHint") else null)
            if (advanced) SmallButton(t("editor.remove"), TitanIcons.trash, { ed.commit(form.copy(devices = form.devices.filterIndexed { j, _ -> j != i })) })
        }
    }

    Section(t("appform.sectionEntries"))
    form.paths.forEachIndexed { i, row ->
        if (row.raw != null) {
            InfoRow(t("editor.paths"), (row.raw as? String ?: YamlDoc.jsString(row.raw)) + " · " + t("editor.rawEntry"), mono = true)
            return@forEachIndexed
        }
        fun set(r: PathRow) = ed.commit(form.copy(paths = form.paths.toMutableList().also { it[i] = r }))
        val hint = problems.paths.getOrNull(i)?.ifEmpty { null }
            ?: listOf(ui.text("appform.containerPathHint", "path" to row.container), whereText(row.host, volumes), if (row.readOnly) ui.text("editor.readOnly") else "").filter { it.isNotEmpty() }.joinToString(" · ")
        Field(row.container.ifEmpty { t("apps.ownPath") } + mark(before?.paths?.getOrNull(i) != row), row.host, { set(row.copy(host = it)) },
            error = problems.paths.getOrNull(i)?.ifEmpty { null }, hint = hint)
        if (advanced) {
            Field(t("editor.containerPath"), row.container, { set(row.copy(container = it)) })
            Choice(t("editor.readOnly"), listOf("rw" to t("editor.readWrite"), "ro" to t("editor.readOnly")), if (row.readOnly) "ro" else "rw", { set(row.copy(readOnly = it == "ro")) })
            SmallButton(t("editor.remove"), TitanIcons.trash, { ed.commit(form.copy(paths = form.paths.filterIndexed { j, _ -> j != i })) })
        }
    }
    if (form.network != "host" && form.network != "none") {
        form.ports.forEachIndexed { i, row ->
            if (row.raw != null) {
                InfoRow(t("editor.ports"), (row.raw as? String ?: YamlDoc.jsString(row.raw)) + " · " + t("editor.rawEntry"), mono = true)
                return@forEachIndexed
            }
            fun set(r: PortRow) = ed.commit(form.copy(ports = form.ports.toMutableList().also { it[i] = r }))
            Field(t("apps.portDefault", "port" to row.container.ifEmpty { "?" }) + mark(before?.ports?.getOrNull(i) != row), row.host, { set(row.copy(host = it.filter(Char::isDigit))) },
                error = problems.ports.getOrNull(i)?.ifEmpty { null }, hint = t("appform.containerPortHint", "port" to "${row.container}/${row.protocol}"), keyboard = num)
            if (advanced) {
                Field(t("editor.containerPort"), row.container, { set(row.copy(container = it.filter(Char::isDigit))) }, keyboard = num)
                Choice(t("apps.protocol"), listOf("tcp" to "TCP", "udp" to "UDP"), row.protocol, { set(row.copy(protocol = it)) })
                SmallButton(t("editor.remove"), TitanIcons.trash, { ed.commit(form.copy(ports = form.ports.filterIndexed { j, _ -> j != i })) })
            }
        }
    }
    form.env.forEachIndexed { i, pair ->
        fun set(p: Pair) = ed.commit(form.copy(env = form.env.toMutableList().also { it[i] = p }))
        val changed = before != null && before.env.firstOrNull { it.name == pair.name }?.value != pair.value
        if (advanced) Field(t("editor.envName"), pair.name, { set(pair.copy(name = it)) }, error = problems.env.getOrNull(i)?.ifEmpty { null })
        Field(pair.name.ifEmpty { t("apps.ownVar") } + mark(changed), pair.value, { set(pair.copy(value = it)) },
            error = if (!advanced) problems.env.getOrNull(i)?.ifEmpty { null } else null)
        if (advanced) SmallButton(t("editor.remove"), TitanIcons.trash, { ed.commit(form.copy(env = form.env.filterIndexed { j, _ -> j != i })) })
    }
    if (advanced) {
        form.labels.forEachIndexed { i, pair ->
            fun set(p: Pair) = ed.commit(form.copy(labels = form.labels.toMutableList().also { it[i] = p }))
            Field(t("editor.envName"), pair.name, { set(pair.copy(name = it)) })
            Field(pair.name.ifEmpty { t("appform.label") }, pair.value, { set(pair.copy(value = it)) })
            SmallButton(t("editor.remove"), TitanIcons.trash, { ed.commit(form.copy(labels = form.labels.filterIndexed { j, _ -> j != i })) })
        }
    } else if (form.labels.isNotEmpty()) {
        Text(t("appform.more", "count" to 4 + form.labels.size), style = TitanType.small, color = c.muted)
    }
    SmallButton(t("appform.addEntry"), TitanIcons.plus, {
        ui.sheet = {
            AddEntrySheet { e ->
                val f = ed.form ?: return@AddEntrySheet
                ed.commit(
                    when (e) {
                        is NewEntry.Path -> f.copy(paths = f.paths + PathRow(e.host, e.container, e.readOnly))
                        is NewEntry.Port -> f.copy(ports = f.ports + PortRow(e.host, e.container, e.protocol))
                        is NewEntry.Variable -> f.copy(env = f.env + Pair(e.name, e.value))
                        is NewEntry.Label -> f.copy(labels = f.labels + Pair(e.name, e.value))
                        is NewEntry.Device -> f.copy(devices = f.devices + DeviceRow(e.device))
                    },
                )
            }
        }
    })
}

/** Eigenes LAN-Netz und feste Adresse des Dienstes (LANAssignment.svelte); ändert den Text der Compose-Datei. */
@Composable
private fun LanAssignment(ed: AppEditorState) {
    val ui = LocalComputeUi.current
    var lans by remember { mutableStateOf<List<ContainerLan>?>(null) }
    LaunchedEffect(Unit) { lans = runCatching { ed.api.lans().networks }.getOrDefault(emptyList()) }
    val list = lans ?: return
    val (network, address) = lanAssignment(ed.text, ed.service)
    var addr by remember(network, address) { mutableStateOf(address) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember(network) { mutableStateOf(network) }
    Choice(t("lan.title"), listOf("" to t("lan.noLAN")) + list.map { it.name to "${it.name} (${it.subnet})" }, selected, { selected = it }, hint = t("lan.service"))
    if (selected.isNotEmpty()) Field(t("lan.staticAddress"), addr, { addr = it }, hint = t("lan.autoHint"))
    if (selected != network || addr != address) SmallButton(t("lan.assignDraft"), TitanIcons.check, {
        try {
            ed.text = assignContainerLAN(ed.text, ed.service, selected, if (selected.isEmpty()) "" else addr)
            ed.parse()
            error = null
        } catch (_: Exception) {
            error = ui.text("lan.assignError")
        }
    })
    Text(t("lan.privateHint"), style = TitanType.small, color = Titan.colors.muted)
    error?.let { Problem(it) }
}
