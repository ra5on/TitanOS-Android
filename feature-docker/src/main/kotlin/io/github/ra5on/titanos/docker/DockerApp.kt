package io.github.ra5on.titanos.docker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ConsoleRequest
import io.github.ra5on.titanos.compute.InfoRow
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.SmallButton
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.Width
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType

/**
 * Seite einer App bzw. eines Containers (routes/docker/apps/[name]):
 * Kopf mit Zustand, CPU/RAM und Schnellaktionen (WebUI, Protokoll,
 * Konsole, Neustart), die Container mit ihren Aktionen und die
 * Compose-Datei zum Bearbeiten und Übernehmen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupDetail(env: ComputeEnv, state: DockerState, g: DockerGroup, onClose: () -> Unit) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val c = Titan.colors
    val phone = currentWidth() == Width.Phone
    ScrollPage {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (phone) IconButton(TitanIcons.back, stringResource(io.github.ra5on.titanos.ui.R.string.ui_back), onClose)
            Avatar(g.name)
            Column(Modifier.weight(1f)) {
                Text(g.name, style = TitanType.title, color = c.text, modifier = Modifier.semantics { heading() })
                stateMark(g)
            }
        }
        if (g.on) {
            Text(t("docker.totals", "cpu" to pct(g.cpu), "ram" to Format.bytes(g.ram)), style = TitanType.body, color = c.muted, modifier = Modifier.padding(vertical = 6.dp))
        }
        val primary = g.members.firstOrNull { it.webui.isNotEmpty() }
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (primary != null && primary.running) {
                val url = appUrl(primary.webui, nasHost(env))
                if (url.isNotEmpty()) SmallButton(t("docker.webui"), TitanIcons.globe, { openUrl(context, url) }, primary = true)
            }
            if (g.on) {
                SmallButton(t("docker.restart"), TitanIcons.restart, { groupAction(state, ui, g, "restart") })
                SmallButton(t("docker.stop"), TitanIcons.stop, { groupAction(state, ui, g, "stop") })
            } else {
                SmallButton(t("docker.start"), TitanIcons.play, { groupAction(state, ui, g, "start") }, primary = true)
            }
            if (g.update && g.app.isNotEmpty()) SmallButton(t("docker.updateNow"), TitanIcons.update, { state.act(ui, "app:${g.app}", g.app) { state.api.updateApp(g.app) } })
            SmallButton(t("docker.remove"), TitanIcons.trash, {
                if (g.app.isNotEmpty()) ui.sheet = { RemoveAppSheet(state, g.app) } else askRemoveContainer(state, ui, g.members.first())
            }, danger = true)
        }
        for (link in g.members.flatMap { listOf(it.proxyUrl, it.tailnetUrl, it.publicUrl) }.filter { it.startsWith("http") }.distinct()) {
            ClickRow(link) { openUrl(context, link) }
        }
        Section(if (g.members.size > 1) t("docker.servicesOf", "name" to g.name) else t("docker.detail.containers"))
        Card {
            g.members.forEachIndexed { i, ct -> ContainerRow(env, state, ct, i > 0) }
        }
        if (g.app.isNotEmpty()) ComposeEditor(state, g.app)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerRow(env: ComputeEnv, state: DockerState, ct: DockerContainer, divider: Boolean) {
    val ui = LocalComputeUi.current
    val c = Titan.colors
    val title = ct.label("service")
    val entries = listOf(
        io.github.ra5on.titanos.compute.MenuEntry(t("docker.logs"), TitanIcons.file) { ui.sheet = { LogsSheet(state.api, ct) } },
        io.github.ra5on.titanos.compute.MenuEntry(t("docker.console"), TitanIcons.chip, enabled = ct.running) { env.openConsole(ConsoleRequest.container(ct.id, ct.label("distinct"))) },
        io.github.ra5on.titanos.compute.MenuEntry(t("docker.restart"), TitanIcons.restart) { state.act(ui, "container:${ct.id}", title) { state.api.containerAction(ct.id, "restart") } },
        io.github.ra5on.titanos.compute.MenuEntry(t("docker.remove"), TitanIcons.trash, danger = true) { askRemoveContainer(state, ui, ct) },
    )
    val actions = t("docker.actionsFor", "name" to title)
    Column(Modifier.fillMaxWidth().onSecondaryClick { ui.menu(actions, entries) }) {
        if (divider) Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = TitanType.rowTitle, color = c.text)
                Text(ct.image, style = TitanType.rowSub, color = c.muted)
                val since = Format.instant(ct.since)?.let { dateTime(it) }
                if (since != null) Text(t(if (ct.running) "docker.runningSince" else "docker.stoppedSince", "time" to since), style = TitanType.rowSub, color = c.muted)
                if (ct.portLabels.isNotEmpty()) Text("${t("docker.column.ports")}: ${ct.portLabels.joinToString(", ")}", style = TitanType.rowSub, color = c.muted)
                if (ct.running) Text("${t("docker.cpu")} ${pct(ct.cpuPercent)} · ${t("docker.ram")} ${Format.bytes(ct.memoryBytes)}", style = TitanType.rowSub, color = c.muted)
                when {
                    "container:${ct.id}" in state.busy -> StateMark(stringResource(R.string.docker_busy), StateKind.Busy)
                    else -> StateMark(t("docker.state.${ct.state}"), when (ct.state) {
                        "running" -> StateKind.Ok
                        "restarting", "paused" -> StateKind.Warn
                        "dead" -> StateKind.Error
                        else -> StateKind.Off
                    })
                }
                if (ct.updateError.isNotEmpty()) Problem(ct.updateError)
            }
            if (ct.running) IconButton(TitanIcons.stop, t("docker.table.stopNamed", "name" to title), { state.act(ui, "container:${ct.id}", title) { state.api.containerAction(ct.id, "stop") } })
            else IconButton(TitanIcons.play, t("docker.table.startNamed", "name" to title), { state.act(ui, "container:${ct.id}", title) { state.api.containerAction(ct.id, "start") } })
            IconButton(TitanIcons.more, actions, { ui.menu(actions, entries) })
        }
    }
}

/** Compose-Datei der App bearbeiten und übernehmen (app-editor.svelte.ts, Ansicht „Compose“). */
@Composable
private fun ComposeEditor(state: DockerState, app: String) {
    val ui = LocalComputeUi.current
    var original by remember(app) { mutableStateOf<String?>(null) }
    var text by remember(app) { mutableStateOf("") }
    var error by remember(app) { mutableStateOf<Throwable?>(null) }
    var result by remember(app) { mutableStateOf<String?>(null) }
    var applying by remember(app) { mutableStateOf(false) }
    LaunchedEffect(app) {
        try {
            val compose = state.api.appCompose(app)
            original = compose
            text = compose
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    Section(t("apps.asCompose"))
    error?.let { Problem(errorText(it)) }
    val o = original ?: return
    Note(stringResource(R.string.docker_compose_note))
    CodeEditor(text, { text = it; result = null }, t("apps.asCompose"), minHeight = 280)
    result?.let { Text(it, style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(top = 6.dp)) }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton(t("docker.save"), TitanIcons.check, {
            applying = true
            ui.run(onError = { e -> result = ui.errorOf(e); applying = false }) {
                state.api.saveCompose(app, text)
                original = text
                result = ui.string(R.string.docker_compose_applied)
                applying = false
                state.load()
            }
        }, primary = true, enabled = !applying && text != o && text.isNotBlank())
        SmallButton(stringResource(R.string.docker_compose_reset), TitanIcons.restart, {
            ui.confirm(Ask(ui.string(R.string.docker_compose_reset), ui.string(R.string.docker_compose_reset_text), ui.string(R.string.docker_compose_reset)) { text = o; result = null })
        }, enabled = text != o)
    }
    if (applying) StateMark(t("docker.jobRunning", "app" to app), StateKind.Busy)
    InfoRow(t("docker.detail.image"), state.status?.containers?.filter { it.app == app }?.joinToString(", ") { it.image }.orEmpty())
}
