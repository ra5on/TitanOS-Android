package io.github.ra5on.titanos.storage

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.AdminSheet
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.SheetButtons
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.formatBytes
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Speicher › Importieren (storage/import): vorhandene mdadm-Arrays und
 * Btrfs-Volumes übernehmen. Vorschau nur lesend; registriert wird nur auf
 * Knopfdruck, den neuen Namen als Bestätigung schickt die App selbst.
 */
@Composable
internal fun ImportPage(env: AdminEnv, model: StorageModel, nav: StorageNav) {
    if (!env.admin) return Forbidden()
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val api = model.api
    var list by remember { mutableStateOf<List<ImportCandidate>>(emptyList()) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var registered by remember { mutableStateOf("") }
    // Verschlüsselte Arrays: das Passwort öffnet sie lesend für die Vorschau; nur solange die Fenster offen sind.
    var unlocking by remember { mutableStateOf<ImportCandidate?>(null) }
    var password by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { password = "" } }

    suspend fun load() {
        busy = true
        error = null
        try {
            list = api.importCandidates()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        loaded = true
        busy = false
    }
    LaunchedEffect(Unit) { load() }
    fun closeAll() {
        preview = null
        unlocking = null
        password = ""
    }
    fun inspect(cand: ImportCandidate) {
        if (cand.encrypted == true && password.isEmpty()) {
            unlocking = cand
            error = null
            return
        }
        scope.launch {
            busy = true
            error = null
            try {
                preview = api.importPreview(ImportPreviewBody(cand.id, cand.fingerprint, if (cand.encrypted == true) password else null))
                name = ""
                unlocking = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                if (cand.encrypted != true) password = ""
            }
            busy = false
        }
    }
    val validName = VOLUME_NAME.matches(name)

    PageBody {
        ButtonRow { ActionButton(t("ops.refresh"), { scope.launch { load() } }, enabled = !busy, icon = "refresh") }
        Text(t("ops.importHint"), style = TitanType.body, color = c.muted)
        if (preview == null && unlocking == null) error?.let { Notice(opError(it), tone = Tone.Error) }
        if (registered.isNotEmpty()) Notice(registered, tone = Tone.Ok) { LinkText(t("desktop.side.overview") + " →") { nav.go("overview") } }
        SettingGroup(t("ops.importFound")) {
            if (list.isEmpty()) SettingRow(if (busy || !loaded) t("ops.loading") else t("ops.importNone"), icon = "info")
            list.forEachIndexed { i, cand ->
                ActionRow(
                    cand.level + (if (cand.filesystem.isNotEmpty()) " · ${cand.filesystem.uppercase()}" else "") + (if (cand.encrypted == true) " · ${t("crypt.encrypted")}" else ""),
                    icon = "disk",
                    divider = i > 0,
                    below = {
                        Mono(cand.id, style = TitanType.small, color = c.muted)
                        Text("${cand.disks.joinToString(" · ")} · ${formatBytes(cand.size)}", style = TitanType.small, color = c.text)
                        if (cand.reason == "encrypted-partition") Notice(t("crypt.importRefused"), tone = Tone.Warn)
                        else if (cand.reason.isNotEmpty()) Notice(t("detail.importRefused") + " " + cand.reason, tone = Tone.Warn)
                    },
                ) {
                    ActionButton(t("ops.preview"), { inspect(cand) }, primary = true, enabled = !busy && cand.reason.isEmpty())
                }
            }
        }
    }

    unlocking?.let { cand ->
        AdminSheet(t("crypt.importTitle"), onDismiss = { if (!busy) closeAll() }, footer = {
            SheetButtons {
                ActionButton(t("common.cancel"), ::closeAll, enabled = !busy)
                ActionButton(t("ops.preview"), { if (password.isNotEmpty()) inspect(cand) }, primary = true, enabled = !busy && password.isNotEmpty())
            }
        }) {
            Text(t("crypt.importText"), style = TitanType.body, color = c.text)
            Field(t("crypt.password"), password, { password = it }, password = true)
            error?.let { Notice(opError(it), tone = Tone.Error) }
        }
    }
    preview?.let { p ->
        AdminSheet(t("ops.import"), onDismiss = { if (!busy) closeAll() }, footer = {
            SheetButtons {
                ActionButton(t("common.cancel"), ::closeAll, enabled = !busy)
                ActionButton(t("ops.register"), {
                    if (!validName || busy) return@ActionButton
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            api.importVolume(p.candidate, name, if (p.candidate.encrypted == true) password else null)
                            registered = name
                            closeAll()
                            busy = false
                            load()
                            model.load()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e
                        }
                        busy = false
                    }
                }, danger = true, enabled = !busy && validName)
            }
        }) {
            Text(t("ops.importReady"), style = TitanType.body, color = c.text)
            Mono(p.candidate.filesystemUUID, style = TitanType.small)
            Text(t("ops.folders"), style = TitanType.rowTitle, color = c.text, modifier = Modifier.semantics { heading() })
            for (f in p.folders) Mono("• $f", style = TitanType.small)
            if (p.truncated) Notice(t("ops.truncated"), tone = Tone.Warn)
            Field(t("ops.name"), name, { name = it.take(12) }, mono = true, error = if (name.isNotEmpty() && !validName) t("create.nameHint") else null, hint = t("create.nameHint"))
            error?.let { Notice(opError(it), tone = Tone.Error) }
        }
    }
}
