package io.github.ra5on.titanos.plugins

import io.github.ra5on.titanos.admin.breakAfterHyphens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminSheet
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.JobProgress
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.SheetButtons
import io.github.ra5on.titanos.admin.ToggleRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.formatBytes
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch

@Composable
internal fun PluginDialogs(state: PluginsState) {
    val current = state.plugins.firstOrNull { it.name == state.picked }
    if (state.asking && current != null && !current.installed) InstallSheet(state, current)
    state.removing?.let { RemoveDialog(state, it) }
    state.updating?.let { UpdateDialog(state, it) }
    if (state.linking) LinkSheet(state)
    if (state.sourcesOpen) SourcesSheet(state)
    state.sourceRemoving?.let { s ->
        val scope = rememberCoroutineScope()
        Confirm(
            t("plugins.sourceRemoveTitle", "name" to s.name),
            t("plugins.sourceRemoveText"),
            t("plugins.sourceRemove"),
            {
                scope.launch {
                    state.error = null
                    try {
                        state.api.removeSource(s.id)
                        state.sourceRemoving = null
                        state.refreshCatalog(true)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        state.error = e
                        state.sourceRemoving = null
                    }
                }
            },
            { state.sourceRemoving = null },
        )
    }
}

/**
 * Installieren: der Hinweis auf volle Systemrechte steht immer da, bei
 * Community- und unsignierten Plugins die Warnung dazu. Installiert wird nur
 * mit dem Knopf „Erlauben“; den Namen als Bestätigung schickt die App selbst.
 */
@Composable
private fun InstallSheet(state: PluginsState, p: Plugin) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val uri = LocalUriHandler.current
    val status = state.status ?: return
    val shown = state.stepsFor(p.name)
    val busyText = t(if (p.download) "plugins.downloadingChecking" else "plugins.installing")
    AdminSheet(breakAfterHyphens(p.title), onDismiss = { if (!state.busy) state.asking = false }, footer = {
        SheetButtons {
            ActionButton(t("common.cancel"), { state.asking = false }, enabled = !state.busy)
            ActionButton(
                t(if (state.busy) (if (p.download) "plugins.downloading" else "plugins.installing") else "plugins.allow"),
                {
                    scope.launch {
                        state.run(p.name) {
                            when {
                                p.download && p.source != null -> state.api.installExternal(ExternalBody(source = p.source, name = p.name, volume = state.volume, confirm = p.name, activate = state.activate))
                                p.download -> state.api.install(InstallBody(name = p.name, volume = state.volume, activate = state.activate))
                                else -> state.api.install(InstallBody(path = p.path, volume = state.volume, confirm = p.name, activate = state.activate))
                            }
                        }
                    }
                },
                primary = true,
                enabled = !state.busy && state.volume.isNotEmpty() && p.problem != "kernel",
            )
        }
    }) {
        Notice(t(if (p.official) "plugins.trustOfficial" else "plugins.trustCommunity"), tone = Tone.Warn)
        if (p.download) Text(t("plugins.downloadNote", "size" to formatBytes(p.size)), style = TitanType.body, color = c.muted)
        p.licenseUrl?.takeIf(::isLink)?.let { l -> LinkText(t("plugins.licenses")) { uri.openUri(l) } }
        SettingGroup(null) {
            if (status.volume.isEmpty()) {
                ChoiceRow(t("plugins.volume"), state.volumes.map { it to it }, state.volume, { state.volume = it }, icon = "disk")
            }
            ToggleRow(t("plugins.activateNow"), state.activate, { state.activate = it }, desc = t("plugins.activateLaterHint"), icon = "power", enabled = !state.busy, divider = status.volume.isEmpty())
        }
        state.error?.let { ErrorNotice(it) }
        when {
            shown != null -> PluginSteps(shown, if (state.busy) null else ({ scope.launch { state.dismiss(shown) } }))
            state.busy -> JobProgress(busyText)
        }
    }
}

@Composable
private fun RemoveDialog(state: PluginsState, target: Target) {
    val scope = rememberCoroutineScope()
    val steps = state.stepsFor(target.name)
    Confirm(
        t("plugins.removeTitle", "name" to breakAfterHyphens(target.title)),
        t("plugins.removeText"),
        t("plugins.removeSubmit"),
        { scope.launch { state.run(target.name) { state.api.remove(target.name) } } },
        { if (!state.busy) state.removing = null },
        enabled = !state.busy,
    ) {
        if (steps != null && (state.busy || steps.state != "done")) PluginSteps(steps)
        state.error?.let { ErrorNotice(it) }
    }
}

/** Aktualisieren aus einer Freigabe oder dem Katalog; bei unsignierter Fassung mit Warnung, den Namen schickt die App. */
@Composable
private fun UpdateDialog(state: PluginsState, target: UpdateTarget) {
    val scope = rememberCoroutineScope()
    val steps = state.stepsFor(target.name)
    Confirm(
        t("plugins.updateTitle", "name" to breakAfterHyphens(target.title), "version" to target.version),
        t("plugins.updateText"),
        t(if (state.busy) "plugins.updating" else "plugins.update"),
        { scope.launch { state.run(target.name) { state.api.update(target.name, target.path) } } },
        { if (!state.busy) state.updating = null },
        danger = false,
        enabled = !state.busy,
    ) {
        Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!target.official) Notice(t("plugins.trustCommunity"), tone = Tone.Warn)
            if (target.download) Text(t("plugins.updateDownloadText"), style = TitanType.body, color = Titan.colors.text)
            else Mono(target.path, style = TitanType.small, color = Titan.colors.muted)
            if (steps != null && (state.busy || steps.state != "done")) PluginSteps(steps)
            state.error?.let { ErrorNotice(it) }
        }
    }
}

/** Ein Community-Plugin von einem Link: erst das Manifest lesen, das Abbild beim Installieren laden. */
@Composable
private fun LinkSheet(state: PluginsState) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val preview = state.linkPreview
    val status = state.status
    AdminSheet(t("plugins.fromLinkTitle"), onDismiss = { if (!state.busy) state.linking = false }, footer = {
        SheetButtons {
            ActionButton(t("common.cancel"), { state.linking = false }, enabled = !state.busy)
            ActionButton(
                t(if (state.busy) "plugins.downloading" else "plugins.downloadInstall"),
                {
                    val pv = state.linkPreview ?: return@ActionButton
                    scope.launch {
                        state.run(pv.name) {
                            state.api.installExternal(ExternalBody(url = state.linkURL.trim(), volume = state.volume, confirm = pv.name, activate = state.activate))
                        }
                        if (state.error == null) state.linking = false
                    }
                },
                primary = true,
                enabled = !state.busy && preview != null && preview.problem != "kernel" && state.volume.isNotEmpty(),
            )
        }
    }) {
        Text(t("plugins.fromLinkText"), style = TitanType.body, color = c.text)
        Field(
            t("plugins.fromLinkURL"),
            state.linkURL,
            { state.linkURL = it; state.linkPreview = null },
            mono = true,
            singleLine = false,
            placeholder = "https://example.org/plugins/name.plugin.json",
            enabled = !state.busy,
            keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
        )
        ActionButton(
            t(if (state.linkChecking) "plugins.fromLinkChecking" else "plugins.fromLinkCheck"),
            {
                if (state.linkURL.isNotBlank() && !state.linkChecking) {
                    scope.launch {
                        state.linkChecking = true
                        state.error = null
                        state.linkPreview = null
                        try {
                            state.linkPreview = state.api.inspect(state.linkURL.trim())
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            state.error = e
                        }
                        state.linkChecking = false
                    }
                }
            },
            enabled = !state.busy && !state.linkChecking && state.linkURL.isNotBlank(),
        )
        if (preview != null) {
            SettingGroup(null) {
                SettingRow(breakAfterHyphens(preview.title), below = {
                    Mono(listOf(preview.name, preview.version.ifEmpty { "–" }, formatBytes(preview.size)).joinToString(" · "), style = TitanType.small, color = c.muted)
                    breakAfterHyphens(localized(preview.description, lang())).takeIf { it.isNotEmpty() }?.let { Text(it, style = TitanType.rowSub, color = c.muted) }
                })
            }
            Notice(t(if (preview.official) "plugins.trustOfficial" else "plugins.trustLink"), tone = Tone.Warn)
            if (preview.problem == "kernel") Notice(t("plugins.incompatible"), tone = Tone.Warn)
            SettingGroup(null) {
                if (status?.volume.isNullOrEmpty()) ChoiceRow(t("plugins.volume"), state.volumes.map { it to it }, state.volume, { state.volume = it }, icon = "disk")
                ToggleRow(t("plugins.activateNow"), state.activate, { state.activate = it }, icon = "power", enabled = !state.busy, divider = status?.volume.isNullOrEmpty())
            }
            state.stepsFor(preview.name)?.let { PluginSteps(it) }
        }
        state.error?.let { ErrorNotice(it) }
    }
}

/** Quellen der Community: Liste, hinzufügen per Adresse, entfernen nach Nachfrage. */
@Composable
private fun SourcesSheet(state: PluginsState) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    AdminSheet(t("plugins.sourceListTitle"), onDismiss = { if (!state.sourceAdding) state.sourcesOpen = false }, footer = {
        SheetButtons { ActionButton(t("common.close"), { state.sourcesOpen = false }, enabled = !state.sourceAdding) }
    }) {
        Text(t("plugins.sourcesText"), style = TitanType.body, color = c.text)
        if (state.sources.isEmpty()) {
            Text(t("plugins.sourcesEmpty"), style = TitanType.body, color = c.muted)
        } else {
            SettingGroup(null) {
                state.sources.forEachIndexed { i, s ->
                    SettingRow(s.name, divider = i > 0, below = {
                        Mono(s.url, style = TitanType.small, color = c.muted)
                        if (s.error != null) Notice(t("plugins.sourceError") + " " + s.error, tone = Tone.Warn)
                        else Text(t("plugins.sourcePlugins", "count" to s.plugins), style = TitanType.rowSub, color = c.muted)
                        ActionButton(t("plugins.sourceRemove"), { state.sourceRemoving = s }, enabled = !state.sourceAdding, modifier = Modifier.padding(top = 8.dp))
                    })
                }
            }
        }
        Field(
            t("plugins.sourceURL"),
            state.sourceURL,
            { state.sourceURL = it },
            mono = true,
            singleLine = false,
            placeholder = "https://example.org/plugins/plugins.json",
            hint = t("plugins.sourceFormat"),
            enabled = !state.sourceAdding,
            keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
        )
        ActionButton(
            t(if (state.sourceAdding) "plugins.sourceAdding" else "plugins.sourceAdd"),
            {
                if (state.sourceURL.isNotBlank() && !state.sourceAdding) {
                    scope.launch {
                        state.sourceAdding = true
                        state.error = null
                        try {
                            state.api.addSource(state.sourceURL.trim())
                            state.sourceURL = ""
                            state.refreshCatalog(true)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            state.error = e
                        }
                        state.sourceAdding = false
                    }
                }
            },
            primary = true,
            enabled = !state.sourceAdding && state.sourceURL.isNotBlank(),
        )
        state.error?.let { ErrorNotice(it) }
    }
}
