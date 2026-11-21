package io.github.ra5on.titanos.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.JobProgress
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.ToggleRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.WebIcon
import io.github.ra5on.titanos.admin.formatBytes
import io.github.ra5on.titanos.admin.hasText
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.launch

/** Text zu einem Schlüssel aus einem Wert des NAS; einen unbekannten Wert zeigt die App roh. */
@Composable
internal fun tKey(key: String, fallback: String): String = if (hasText(key)) t(key) else fallback

/** Eine Aufzählung als Zeilen mit Punkt. */
@Composable
internal fun Bullets(items: List<String>, mono: Boolean = false) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in items) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = TitanType.body, color = Titan.colors.muted)
                if (mono) Mono(i) else Text(i, style = TitanType.body, color = Titan.colors.text, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Das gewählte Plugin (PluginDetails.svelte): was es enthält und ändert, Hardware, Angaben, Aktionen. */
@Composable
internal fun PluginDetail(env: AdminEnv, state: PluginsState, status: PluginsStatus, p: Plugin, pollMs: Long) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val language = lang()
    val about = aboutOf(p, language)
    val uri = LocalUriHandler.current
    PageBody {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Monogram(p, 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Heading(p.title)
                Text(localized(p.description, language), style = TitanType.body, color = c.text)
                Mono(listOf(p.name, p.version.ifEmpty { "–" }, formatBytes(p.size)).joinToString(" · "), style = TitanType.small, color = c.muted)
                Text(originText(p, state), style = TitanType.small, color = c.muted)
            }
        }
        val contains = containsOf(p, language)
        if (contains.isNotEmpty()) {
            SettingGroup(t("plugins.contains")) {
                Column(Modifier.padding(14.dp)) { ChipLine { for (e in contains) StatusChip(e, Tone.Neutral) } }
            }
        }
        about?.`when`?.takeIf { it.isNotEmpty() }?.let { w ->
            SettingGroup(t("plugins.when")) { Text(w, style = TitanType.body, color = c.text, modifier = Modifier.padding(14.dp)) }
        }
        if (!about?.hardware.isNullOrEmpty()) SettingGroup(t("plugins.hardware")) { Bullets(about!!.hardware!!) }
        if (!p.devices.isNullOrEmpty() || !about?.hardware.isNullOrEmpty()) {
            SettingGroup(t("plugins.detected")) {
                val devices = p.devices.orEmpty()
                if (devices.isEmpty()) Text(t("plugins.detectedNone"), style = TitanType.body, color = c.muted, modifier = Modifier.padding(14.dp))
                devices.forEachIndexed { i, d ->
                    SettingRow(
                        d.name,
                        divider = i > 0,
                        below = {
                            d.address?.let { Mono(it, style = TitanType.small, color = c.muted) }
                            if (d.bus != "dmi") {
                                Text(
                                    d.driver?.let { t("plugins.detectedDriver", "driver" to it) } ?: t("plugins.detectedNoDriver"),
                                    style = TitanType.rowSub,
                                    color = c.muted,
                                )
                            }
                        },
                    )
                }
            }
        }
        SettingGroup(t("plugins.does")) {
            Bullets(listOfNotNull(t("plugins.doesFiles"), if (p.kernel.isNotEmpty()) t("plugins.forKernel", "kernel" to p.kernel) else null))
            if (!p.modules.isNullOrEmpty()) SettingRow(t("plugins.doesModules"), below = { Mono(p.modules.joinToString(", "), style = TitanType.small) }, divider = true)
            if (!p.services.isNullOrEmpty()) SettingRow(t("plugins.doesServices"), below = { Mono(p.services.joinToString(", "), style = TitanType.small) }, divider = true)
            about?.changes?.takeIf { it.isNotEmpty() }?.let { Text(it, style = TitanType.body, color = c.text, modifier = Modifier.padding(14.dp)) }
        }
        if (!about?.limits.isNullOrEmpty()) SettingGroup(t("plugins.limits")) { Bullets(about!!.limits!!) }
        if (p.license != null || p.upstream != null || about?.footprint != null || !p.sources.isNullOrEmpty()) {
            SettingGroup(t("plugins.facts"), expandable = true) {
                p.license?.let { SettingRow(t("plugins.license"), value = it) }
                p.upstream?.let { u ->
                    val link = u.url?.takeIf(::isLink)
                    SettingRow(
                        t("plugins.upstream"),
                        divider = p.license != null,
                        value = listOfNotNull(u.name ?: u.url, u.version).joinToString(" · "),
                        onClick = link?.let { l -> { uri.openUri(l) } },
                        chevron = link != null,
                    )
                }
                SettingRow(
                    t("plugins.footprint"),
                    divider = true,
                    value = t("plugins.diskSize", "size" to formatBytes(p.size)) + (about?.footprint?.let { ". $it" } ?: ""),
                )
                if (!p.sources.isNullOrEmpty()) {
                    SettingRow(t("plugins.sources"), divider = true, below = {
                        for (s in p.sources) {
                            if (isLink(s)) LinkText(s, mono = true) { uri.openUri(s) } else Mono(s, style = TitanType.small)
                        }
                    })
                }
            }
        }
        when (p.problem) {
            "kernel" -> Notice(t("plugins.kernelMismatch", "built" to p.kernel, "running" to status.kernel), tone = Tone.Warn)
            "activation" -> Notice(t("plugins.activationFailed"), tone = Tone.Warn)
            "integrity" -> Notice(t("plugins.integrityFailed"), tone = Tone.Warn)
            "busy" -> Notice(t("plugins.busy"), tone = Tone.Warn)
        }
        val shown = state.stepsFor(p.name)
        if (shown != null && !state.asking && state.removing?.name != p.name && state.updating?.name != p.name) {
            PluginSteps(shown) { scope.launch { state.dismiss(shown) } }
        }

        if (!p.installed) {
            SettingGroup(null) {
                SettingRow(
                    if (p.download) t("plugins.githubSource", "version" to status.catalog.release) else p.path,
                    icon = "download",
                    below = {
                        ButtonRow(Modifier.padding(top = 8.dp)) {
                            ActionButton(
                                t(if (p.download) "plugins.downloadInstall" else "plugins.install"),
                                { scope.launch { openInstall(state, p) } },
                                primary = true,
                                enabled = !state.locked && !state.refreshing && state.volumes.isNotEmpty() && p.problem != "kernel",
                            )
                        }
                    },
                )
            }
            if (state.volumes.isEmpty()) {
                Notice(t("plugins.needVolume") + " " + t("nav.storage"), tone = Tone.Neutral) {
                    LinkText(t("nav.storage") + " →") { env.open("storage", null) }
                }
            }
        } else {
            SettingGroup(null) {
                ToggleRow(
                    t("plugins.enabled"),
                    p.enabled,
                    { on -> scope.launch { state.run { state.api.enable(p.name, on) } } },
                    desc = t("plugins.enabledNote"),
                    icon = "power",
                    enabled = !state.locked,
                )
                p.update?.let { u ->
                    SettingRow(
                        if (u.download == true) t("plugins.updateAvailableDownload", "version" to u.version) else t("plugins.updateAvailable", "version" to u.version),
                        icon = "download",
                        divider = true,
                        below = {
                            if (u.download != true) Mono(u.path, style = TitanType.small, color = c.muted)
                            ButtonRow(Modifier.padding(top = 8.dp)) {
                                ActionButton(t("plugins.update"), {
                                    scope.launch {
                                        state.clearFinished(p.name)
                                        state.error = null
                                        state.updating = UpdateTarget(p.name, p.title, u.path, u.version, u.official, u.download == true)
                                    }
                                }, primary = true, enabled = !state.locked)
                            }
                        },
                    )
                }
            }
            if (p.name == "tailscale") TailscaleGroup(state.api, pollMs) { state.tailnetPending = it }
            if (p.name == "netbird") NetbirdGroup(state.api, pollMs) { state.netbirdPending = it }
            val driver = status.driverUpdate
            if (p.name == "nvidia" && (p.official || p.local) && driver != null) {
                if (!driver.drivers.isNullOrEmpty()) DriverChoice(state, driver) else DriverUpdates(state, driver)
            }
            if (p.enabled && p.problem == "activation") {
                ActionButton(t("plugins.retry"), { scope.launch { state.run { state.api.enable(p.name, true) } } }, enabled = !state.busy)
            }
            // Zerstörendes steht abseits, rot, am Ende.
            SettingGroup(t("ui.dangerZone"), desc = t("plugins.removeHint")) {
                SettingRow(
                    t("plugins.remove"),
                    icon = "trash",
                    danger = true,
                    enabled = !state.locked,
                    onClick = if (state.locked) null else ({
                        scope.launch {
                            state.clearFinished(p.name)
                            state.error = null
                            state.removing = Target(p.name, p.title)
                        }
                    }),
                )
            }
        }
    }
}

@Composable
internal fun LinkText(text: String, mono: Boolean = false, onClick: () -> Unit) {
    val style = if (mono) TitanType.small.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono) else TitanType.body
    Text(
        text,
        style = style,
        color = Titan.colors.accent,
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
    )
}

// ---------------------------------------------------------------- Schritte (PluginSteps.svelte)

/** Die Schritte einer Installation, eines Updates oder Entfernens, wie das NAS sie meldet. */
@Composable
internal fun PluginSteps(progress: PluginProgress, onDismiss: (() -> Unit)? = null) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingGroup(null) {
            progress.steps.forEachIndexed { i, step ->
                val (icon, tint) = when (step.state) {
                    "done" -> "check" to c.okInk
                    "failed" -> "close" to c.dangerInk
                    "running" -> "play" to c.accent
                    "skipped" -> "arrowRight" to c.muted
                    else -> "clock" to c.muted
                }
                SettingRow(
                    tKey("plugins.step.${step.id}", step.id),
                    divider = i > 0,
                    desc = tKey("plugins.stepState.${step.state}", step.state),
                    below = {
                        if (step.id == "download" && step.state == "running") {
                            val percent = downloadPercent(progress)
                            val amount = if (progress.total > 0) "${formatBytes(progress.bytes)} / ${formatBytes(progress.total)}" else formatBytes(progress.bytes)
                            val speed = if (progress.speedBytesPerSecond > 0) " · " + t("plugins.speed", "speed" to io.github.ra5on.titanos.admin.formatDecimal(progress.speedBytesPerSecond / 1_000_000, if (progress.speedBytesPerSecond >= 1e8) 0 else 1)) else ""
                            JobProgress(t("plugins.step.download"), percent = percent, detail = amount + speed)
                        }
                        if (step.state == "failed" && !step.error.isNullOrEmpty()) Mono(step.error, style = TitanType.small, color = c.dangerInk)
                    },
                    control = { WebIcon(icon, size = 20.dp, tint = tint) },
                )
            }
        }
        when (progress.state) {
            "failed" -> Notice(t("plugins.stepsFailed") + (if (progress.steps.none { it.state == "failed" }) progress.error?.let { "\n$it" }.orEmpty() else ""), tone = Tone.Error)
            "done" -> Notice(t("plugins.stepsDone"), tone = Tone.Ok)
        }
        if (onDismiss != null && progress.state != "running") ActionButton(t("plugins.stepsDismiss"), onDismiss)
    }
}

// ---------------------------------------------------------------- NVIDIA (DriverChoice.svelte, DriverUpdates.svelte)

/** Jede Version aus NVIDIAs Archiv: das NAS baut sie und nutzt sie nach dem nächsten Neustart. */
@Composable
private fun DriverChoice(state: PluginsState, status: DriverStatus) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    var picked by remember { mutableStateOf(status.chosen.orEmpty()) }
    val working = status.state in DRIVER_WORKING
    val failed = status.state == "error" && !status.target.isNullOrEmpty()
    val ready = picked.isNotEmpty() && picked == status.running && status.runningLocal == true
    fun act(body: DriverBody) = scope.launch { state.run { state.api.driver(body) } }
    SettingGroup(t("plugins.choice.title")) {
        if (failed) {
            Notice(t("plugins.choice.failed", "version" to status.target.orEmpty(), "bundled" to status.bundled.orEmpty()), Modifier.padding(14.dp), tone = Tone.Warn) {
                status.error?.let { Mono(it, style = TitanType.small) }
            }
        }
        ChoiceRow(
            t("plugins.choice.version"),
            listOf("" to t("plugins.choice.bundled", "version" to status.bundled.orEmpty())) + status.drivers.orEmpty().map { v ->
                v to v + (if (v == status.running && status.runningLocal == true) " · " + t("plugins.choice.built") else "") +
                    (if (oldDriver(v, status.bundled)) " · " + t("plugins.choice.old") else "")
            },
            picked,
            { picked = it },
            desc = status.running?.let { t(if (status.runningLocal == true) "plugins.choice.runningLocal" else "plugins.choice.runningBundled", "version" to it) },
            icon = "gpu",
            enabled = !state.busy && !working && status.pending == null,
        )
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonRow {
                if (status.pending != null) {
                    ActionButton(t("plugins.driverCancel"), { act(DriverBody("select", sha256 = "cancel")) }, enabled = !state.busy)
                } else {
                    ActionButton(
                        t(if (picked.isNotEmpty()) "plugins.choice.buildUse" else "plugins.choice.use"),
                        { act(DriverBody("choose", version = picked)) },
                        primary = true,
                        enabled = !state.busy && !working && (picked != status.chosen.orEmpty() || failed),
                    )
                }
            }
            when {
                working -> JobProgress(
                    t(if (status.state == "building") "plugins.choice.building" else "plugins.choice.downloading", "version" to (status.target?.ifEmpty { null } ?: picked)),
                    phase = tKey("plugins.driverState.${status.state}", status.state),
                )
                status.pending != null -> Notice(t("plugins.driverPending", "version" to status.pending.version), tone = Tone.Warn)
                picked.isNotEmpty() && !ready -> {
                    Text(t("plugins.choice.buildHint"), style = TitanType.small, color = c.muted)
                    if (oldDriver(picked, status.bundled)) Notice(t("plugins.choice.oldHint"), tone = Tone.Warn)
                }
            }
            Text(t("plugins.choice.kernelHint"), style = TitanType.small, color = c.muted)
        }
    }
}

/** Treiber-Updates mit älterer Bauumgebung: automatisch, prüfen, vorbereiten, Version wählen. */
@Composable
private fun DriverUpdates(state: PluginsState, status: DriverStatus) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    var selected by remember { mutableStateOf("bundled") }
    val versions = status.versions.orEmpty()
    val choice = versions.firstOrNull { it.sha256 == selected }
    val loading = status.state in DRIVER_WORKING
    fun act(body: DriverBody) = scope.launch { state.run { state.api.driver(body) } }
    SettingGroup(t("plugins.driverTitle"), desc = t("plugins.driverHint")) {
        ToggleRow(t("plugins.driverAutomatic"), status.automatic, { act(DriverBody("automatic", automatic = it)) }, icon = "refresh", enabled = !state.busy)
        SettingRow(t("plugins.driverCheck"), icon = "search", divider = true, desc = tKey("plugins.driverState.${status.state}", status.state), below = {
            ButtonRow(Modifier.padding(top = 8.dp)) {
                ActionButton(t(if (loading) "plugins.driverWorking" else "plugins.driverCheck"), { act(DriverBody("check")) }, enabled = !state.busy && !loading)
            }
        })
        val src = status.source
        if (src != null) {
            SettingRow(t("plugins.driverSource", "driver" to src.driver, "toolkit" to src.toolkit), icon = "tool", desc = t("plugins.driverBuildHint"), divider = true, below = {
                ButtonRow(Modifier.padding(top = 8.dp)) {
                    ActionButton(t("plugins.driverPrepare"), { act(DriverBody("prepare")) }, enabled = !state.busy && !loading && status.pending == null && src.downloaded)
                }
            })
        } else {
            Text(t("plugins.driverBuildHint"), style = TitanType.small, color = c.muted, modifier = Modifier.padding(14.dp))
        }
        ChoiceRow(
            t("plugins.driverVersion"),
            listOf("bundled" to t("plugins.driverBundled")) + versions.map { v ->
                v.sha256 to listOfNotNull(
                    v.version,
                    if (v.local == true) t("plugins.driverLocal") else null,
                    if (v.recommended == true) t("plugins.driverRecommended") else null,
                    formatBytes(v.size),
                    if (v.downloaded) t("plugins.driverDownloaded") else null,
                ).joinToString(" · ")
            },
            selected,
            { selected = it },
            icon = "gpu",
            divider = true,
            enabled = !state.busy && status.pending == null,
        )
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonRow {
                when {
                    status.pending != null -> ActionButton(t("plugins.driverCancel"), { act(DriverBody("select", sha256 = "cancel")) }, enabled = !state.busy)
                    choice != null && !choice.downloaded -> ActionButton(t("plugins.driverDownload"), { act(DriverBody("check", sha256 = selected)) }, enabled = !state.busy && !loading)
                    else -> ActionButton(
                        t("plugins.driverSelect"),
                        { act(DriverBody("select", sha256 = selected)) },
                        primary = true,
                        enabled = !state.busy && (if (selected == "bundled") status.canRestore else choice?.downloaded == true),
                    )
                }
            }
            status.pending?.let { Notice(t("plugins.driverPending", "version" to it.version), tone = Tone.Warn) }
            status.error?.takeIf { it.isNotEmpty() }?.let { Notice(t("plugins.errorDetails") + "\n" + it, tone = Tone.Warn) }
        }
    }
}
