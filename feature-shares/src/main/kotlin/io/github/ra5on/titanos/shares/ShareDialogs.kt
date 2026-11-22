package io.github.ra5on.titanos.shares

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminDialog
import io.github.ra5on.titanos.admin.AdminSheet
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.FieldLabel
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.Segments
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SheetButtons
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.adminText
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDiskBytes
import io.github.ra5on.titanos.admin.formatNumber
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Freigabe anlegen oder bearbeiten (ShareDialog im Web): Reiter Allgemein,
 * Zugriff, Optionen. Ungespeicherte Änderungen fragen beim Schließen nach
 * (Weiter bearbeiten, Verwerfen, Speichern).
 */
@Composable
internal fun ShareForm(state: SharesState, share: Share?, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val initial = remember(share) { ShareDraft.of(share, state.volumeNames, state.env.user) }
    var draft by remember(share) { mutableStateOf(initial) }
    var tab by remember { mutableStateOf("general") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var errorCode by remember { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
    val dirty = draft != initial
    val volume = state.volumes.firstOrNull { it.name == draft.volume }
    val btrfs = volume?.filesystem == "btrfs"
    val quotaState = volume?.shareQuotas ?: "off"
    val nameError = if (draft.name.isNotEmpty() && !validShareName(draft.name)) context.adminText("error.invalid_share_name") else null

    fun submit() {
        if (!validShareName(draft.name)) {
            error = context.adminText("error.invalid_share_name"); tab = "general"; return
        }
        if (draft.badClients.isNotEmpty() || draft.noClients) {
            error = context.adminText("error.invalid_nfs_client"); tab = "general"; return
        }
        working = true
        error = ""
        scope.launch {
            try {
                if (share != null) state.api.update(share.name, draft.body()) else state.api.create(draft.body())
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = (e as? ApiException)?.code ?: "internal"
                errorCode = code
                error = context.errorText(e)
                errorTab(code, draft)?.let { tab = it }
                working = false
            }
        }
    }

    val requestClose = {
        if (!working) {
            if (dirty) confirmClose = true else onClose()
        }
    }
    AdminSheet(
        title = if (share != null) t("share.editTitle", "name" to share.name) else t("share.createTitle"),
        onDismiss = requestClose,
        footer = {
            SheetButtons {
                ActionButton(t("common.cancel"), requestClose, enabled = !working)
                ActionButton(
                    t(if (share != null) "share.save" else "share.create"),
                    ::submit,
                    primary = true,
                    enabled = !working && draft.volume.isNotEmpty() && !draft.quotaTooSmall,
                )
            }
        },
    ) {
        Segments(listOf("general", "access", "options").map { it to t("shares.tab.$it") }, tab, { tab = it }, label = t("shares.details"))
        when (tab) {
            "general" -> {
                Field(
                    t("share.name"),
                    draft.name,
                    { draft = draft.copy(name = it.take(32)) },
                    hint = t("share.nameHint"),
                    error = nameError,
                    enabled = share == null,
                    mono = true,
                )
                if (share == null) {
                    SettingGroup(null) {
                        ChoiceRow(t("share.volume"), state.volumeNames.map { it to it }, draft.volume, { draft = draft.copy(volume = it) }, icon = "disk")
                    }
                } else {
                    Field(t("share.volume"), draft.volume, {}, enabled = false)
                }
                Text(t("share.location", "path" to "/mnt/${draft.volume}/${draft.name}"), style = TitanType.small, color = Titan.colors.muted)
                if (quotaState != "unsupported" || draft.quotaValue > 0) {
                    Field(
                        t("share.quota"),
                        draft.quotaGB,
                        { draft = draft.copy(quotaGB = it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
                        hint = t("share.quotaHint.$quotaState"),
                        error = if (draft.quotaTooSmall) t("share.quotaTooSmall") else null,
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        suffix = "GB",
                    )
                } else {
                    Text(t("share.quotaHint.unsupported"), style = TitanType.small, color = Titan.colors.muted)
                }
                Text(t("share.protocols"), style = TitanType.section, color = Titan.colors.muted, modifier = Modifier.semantics { heading() })
                SettingGroup(null) {
                    CheckRow(t("share.smb"), draft.smb, { draft = draft.copy(smb = it) })
                    ChoiceRow(
                        t("share.nfs"),
                        listOf("off" to t("share.nfsOff"), "ro" to t("share.ro"), "rw" to t("share.rw")),
                        draft.nfs,
                        { draft = draft.copy(nfs = it) },
                        divider = true,
                    )
                }
                if (draft.nfs != "off") {
                    Field(
                        t("share.nfsClients"),
                        draft.clients,
                        { draft = draft.copy(clients = it) },
                        placeholder = "192.168.1.0/24",
                        singleLine = false,
                        minLines = 3,
                        mono = true,
                        hint = t("share.nfsClientsHint"),
                        error = when {
                            draft.badClients.isNotEmpty() -> t("share.nfsClientsInvalid", "clients" to draft.badClients.joinToString(", "))
                            draft.noClients -> t("share.nfsClientsNone")
                            else -> null
                        },
                    )
                    Notice(t("share.nfsWarning"), tone = Tone.Warn)
                    if (draft.nfs == "ro") Text(t("share.nfsReadOnlyHint"), style = TitanType.small, color = Titan.colors.muted)
                }
            }
            "access" -> if (!draft.smb) {
                Text(t("shares.smbOptions"), style = TitanType.body, color = Titan.colors.muted)
            } else {
                Text(t("share.accessTitle"), style = TitanType.section, color = Titan.colors.muted, modifier = Modifier.semantics { heading() })
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (a in state.accounts) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(a.name, style = TitanType.rowTitle.copy(fontFamily = TitanFonts.mono), color = Titan.colors.text)
                                Text(t("role.${a.role}"), style = TitanType.small, color = Titan.colors.muted)
                                Segments(
                                    listOf("" to t("share.none"), "ro" to t("share.ro"), "rw" to t("share.rw")),
                                    draft.access[a.name] ?: "",
                                    { level -> draft = draft.copy(access = if (level.isEmpty()) draft.access - a.name else draft.access + (a.name to level)) },
                                )
                            }
                        }
                    }
                }
                SettingGroup(null) { CheckRow(t("share.guest"), draft.guest, { draft = draft.copy(guest = it) }) }
                if (draft.guest) Notice(t("share.guestWarning"), tone = Tone.Warn)
                if (draft.nobody) Notice(t("share.nobodyWarning"), tone = Tone.Warn)
            }
            else -> if (!draft.smb) {
                Text(t("shares.smbOptions"), style = TitanType.body, color = Titan.colors.muted)
            } else {
                SettingGroup(null) {
                    CheckRow(t("share.browseable"), draft.browseable, { draft = draft.copy(browseable = it) })
                    CheckRow(t("share.recycle"), draft.recycle, { draft = draft.copy(recycle = it) }, divider = true)
                    if (draft.recycle) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Field(t("share.recycleDays"), draft.days, { draft = draft.copy(days = it.filter(Char::isDigit).take(4)) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), hint = "1–3650")
                        }
                    }
                    CheckRow(t("share.timeMachine"), draft.timeMachine, { draft = draft.copy(timeMachine = it) }, divider = true)
                    if (draft.timeMachine) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Field(t("share.timeMachineSize"), draft.sizeGB, { draft = draft.copy(sizeGB = it.filter(Char::isDigit).take(7)) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), suffix = "GB")
                        }
                    }
                    CheckRow(
                        t("share.previousVersions"),
                        draft.previousVersions,
                        { draft = draft.copy(previousVersions = it) },
                        desc = t(if (btrfs) "share.previousVersionsHint" else "share.previousVersionsBtrfs"),
                        enabled = btrfs,
                        divider = true,
                    )
                    CheckRow(t("share.guard"), draft.guard, { draft = draft.copy(guard = it) }, desc = t("share.guardHint"), divider = true)
                }
            }
        }
        if (error.isNotEmpty()) {
            Notice(if (errorCode == "share_guard_locked") "$error ${t("shares.guardLockedHint")}" else error, tone = Tone.Error)
        }
    }
    if (confirmClose) {
        AdminDialog(
            title = stringResource(R.string.shares_unsaved_title),
            text = t("shares.unsaved"),
            actions = listOf(
                DialogAction(t("shares.discard"), danger = true) { confirmClose = false; onClose() },
                DialogAction(t("shares.keepEditing")) { confirmClose = false },
                DialogAction(t("common.save")) { confirmClose = false; submit() },
            ),
            onDismiss = { confirmClose = false },
        )
    }
}

/** Adressen der Freigabe je System zum Kopieren (ConnectDialog). */
@Composable
internal fun ConnectSheet(share: Share, host: String, mdnsHost: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf("") }
    LaunchedEffect(copied) {
        if (copied.isNotEmpty()) {
            delay(1500)
            copied = ""
        }
    }
    AdminSheet(
        t("shares.connectTitle", "name" to share.name),
        onDismiss = onClose,
        footer = { SheetButtons { ActionButton(t("common.done"), onClose, primary = true) } },
    ) {
        val vias = listOf(host, mdnsHost).filter { it.isNotEmpty() }
        vias.forEachIndexed { index, via ->
            if (mdnsHost.isNotEmpty()) {
                Text(t(if (index == 0) "shares.viaAddress" else "shares.viaName"), style = TitanType.section, color = Titan.colors.muted, modifier = Modifier.semantics { heading() })
            }
            SettingGroup(null) {
                shareAddresses(share, via).forEachIndexed { i, (label, value) ->
                    Column(Modifier.fillMaxWidth()) {
                        if (i > 0) io.github.ra5on.titanos.ui.components.Divider()
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel(t(label))
                            // Lange Adressen scrollen in der Zeile, statt gekürzt zu werden.
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                Text(value, style = TitanType.body.copy(fontFamily = TitanFonts.mono), color = Titan.colors.text)
                            }
                            ActionButton(
                                t(if (copied == value) "shares.copied" else "shares.copy"),
                                {
                                    clipboard.setText(AnnotatedString(value))
                                    copied = value
                                },
                                icon = "copy",
                            )
                        }
                    }
                }
            }
        }
        if (!share.smb && !share.nfs.enabled) Text(t("shares.noProtocol"), style = TitanType.body, color = Titan.colors.text)
    }
}

/**
 * Freigabe löschen, wahlweise samt Ordner und Daten nach einer Vorschau
 * (DeleteShareDialog). Den Pfad der Vorschau schickt die App selbst als
 * Bestätigung; niemand tippt etwas ein. Der gefährliche Knopf steht links.
 */
@Composable
internal fun DeleteShareDialog(state: SharesState, share: Share, onClose: () -> Unit, onDeleted: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var deleteData by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<DataPreview?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<Throwable?>(null) }
    var deleteError by remember { mutableStateOf<Throwable?>(null) }
    var ticket by remember { mutableStateOf(0) }

    fun loadPreview() {
        val mine = ++ticket
        previewing = true
        previewError = null
        preview = null
        scope.launch {
            try {
                val p = state.api.preview(share.name)
                if (mine == ticket) preview = p
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == ticket) previewError = e
            } finally {
                if (mine == ticket) previewing = false
            }
        }
    }

    fun remove() {
        if (deleting) return
        if (deleteData && (previewing || !canDeleteData(preview))) return
        deleting = true
        deleteError = null
        scope.launch {
            try {
                if (deleteData) {
                    val p = preview ?: return@launch
                    state.api.purge(share.name, p.path)
                } else {
                    state.api.delete(share.name)
                }
                onDeleted(deleteData)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                deleteError = e
                // Was im Weg steht, kann sich geändert haben: den Stand neu zeigen.
                if (deleteData && (e as? ApiException)?.status == 409) loadPreview()
            } finally {
                deleting = false
            }
        }
    }

    val canSubmit = !deleting && !(deleteData && (previewing || !canDeleteData(preview)))
    AdminDialog(
        title = t("share.deleteTitle", "name" to share.name),
        text = if (!deleteData) t("share.deleteText", "path" to share.path) else null,
        actions = listOf(
            DialogAction(t(if (deleteData) "share.deleteDataSubmit" else "share.deleteSubmit"), danger = true) { if (canSubmit) remove() },
            DialogAction(stringResource(UiR.string.ui_cancel)) { if (!deleting) onClose() },
        ),
        onDismiss = { if (!deleting) onClose() },
    ) {
        Card {
            CheckRow(t("share.deleteData"), deleteData, { on ->
                deleteData = on
                if (on) loadPreview() else {
                    ticket++
                    previewing = false
                    preview = null
                    previewError = null
                }
            }, enabled = !deleting)
        }
        if (deleteData) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Notice(t("share.deleteDataHint", "path" to share.path), tone = Tone.Warn)
                val p = preview
                when {
                    previewing -> Loading(text = t("share.deleteDataCounting"))
                    previewError != null -> Notice(errorText(previewError!!), tone = Tone.Error)
                    p != null -> {
                        Text(p.path, style = TitanType.body.copy(fontFamily = TitanFonts.mono), color = Titan.colors.text)
                        if (!p.exists) {
                            Text(t("share.deleteDataMissing", "path" to p.path), style = TitanType.body, color = Titan.colors.text)
                        } else {
                            Text(
                                t("share.deleteDataSummary", "files" to formatNumber(p.files), "dirs" to formatNumber(p.dirs), "size" to formatDiskBytes(p.bytes)),
                                style = TitanType.body,
                                color = Titan.colors.text,
                            )
                            if (!p.complete) Text(t("share.deleteDataPartial", "limit" to (p.limit ?: "")), style = TitanType.small, color = Titan.colors.muted)
                            if (p.subvolume) Text(t("share.deleteDataSubvolume"), style = TitanType.body, color = Titan.colors.text)
                        }
                        if (p.blockers.isNotEmpty()) {
                            Notice(
                                p.blockers.map { "• " + usageText(it) }.joinToString("\n"),
                                tone = Tone.Error,
                                title = t("share.deleteDataBlocked"),
                            )
                        }
                        if (p.warnings.isNotEmpty()) {
                            Notice(p.warnings.map { "• " + usageText(it) }.joinToString("\n"), tone = Tone.Warn, title = t("share.deleteDataWarnings"))
                        }
                    }
                }
                if (!previewing && (previewError != null || preview?.blockers?.isNotEmpty() == true)) {
                    ActionButton(t("share.deleteDataRetry"), ::loadPreview, enabled = !deleting)
                }
            }
        }
        deleteError?.let { Notice(errorText(it), Modifier.padding(top = 8.dp), tone = Tone.Error) }
    }
}

@Composable
private fun usageText(use: ShareUsage): String = t(usageKey(use.kind), "name" to (use.name ?: ""), "path" to (use.path ?: ""))
