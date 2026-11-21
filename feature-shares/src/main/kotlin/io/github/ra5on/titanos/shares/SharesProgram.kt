package io.github.ra5on.titanos.shares

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.AdminMenu
import io.github.ra5on.titanos.admin.AdminShell
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.ErrorBox
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.JobProgress
import io.github.ra5on.titanos.admin.ListDetail
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.MenuItem
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.Segments
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.SideEntry
import io.github.ra5on.titanos.admin.SideGroup
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.ToggleRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.formatDiskBytes
import io.github.ra5on.titanos.admin.formatPercent
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.admin.uiLocale
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.launch

/** Welcher Dialog offen ist (dialog im Web). */
internal sealed interface ShareDialogKind {
    data object Create : ShareDialogKind
    data class Edit(val share: Share) : ShareDialogKind
    data class Delete(val share: Share) : ShareDialogKind
    data class Connect(val share: Share) : ShareDialogKind
    data class Unlock(val share: Share) : ShareDialogKind
}

/** Zustand der Seite: Freigaben, Konten, Volumes, Ansicht, laufende Löschaufträge. */
internal class SharesState(val env: AdminEnv) {
    val api = SharesApi(env.http)
    var shares by mutableStateOf<List<Share>?>(null)
    var accounts by mutableStateOf<List<Account>>(emptyList())
    var accountReady by mutableStateOf(false)
    var volumes by mutableStateOf<List<StorageVolume>>(emptyList())
    var system by mutableStateOf(SystemLite())
    var view by mutableStateOf(ShareView())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var preferenceError by mutableStateOf<Throwable?>(null)
    var savingView by mutableStateOf(false)
    var deletions by mutableStateOf<List<ShareDeletion>>(emptyList())
    private var viewLoaded = false

    val canAdminister: Boolean get() = env.admin
    val volumeNames: List<String> get() = volumes.map { it.name }

    /** Adresse wie im Web: IPv4 ohne Netz, sonst Name; zusätzlich der .local-Name. */
    val host: String get() = system.network?.ipv4?.substringBefore('/')?.takeIf { it.isNotEmpty() } ?: system.hostname.ifEmpty { "nas" }
    val mdnsHost: String get() = system.hostname.takeIf { it.isNotEmpty() && it != host }?.let { "$it.local" } ?: ""

    suspend fun load() {
        if (loading) return
        loading = true
        error = null
        try {
            shares = api.shares()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        if (canAdminister) {
            try {
                accounts = api.accounts()
                accountReady = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                accountReady = false
            }
        } else {
            accountReady = true
        }
        runCatching { volumes = api.storage().volumes }
        runCatching { system = api.system() }
        if (!viewLoaded) {
            runCatching { api.preferences().shares?.let { view = it.clean() } }
            viewLoaded = true
        }
        loading = false
    }

    suspend fun saveView(next: ShareView) {
        view = next
        savingView = true
        preferenceError = null
        try {
            api.saveView(next)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            preferenceError = e
        } finally {
            savingView = false
        }
    }

    suspend fun pollDeletions() {
        deletions = runCatching { api.operations().shareDeletions }.getOrDefault(deletions)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SharesProgram(env: AdminEnv, start: String?, onBack: () -> Unit) {
    val state = remember(env) { SharesState(env) }
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<ShareDialogKind?>(null) }
    var purgeStarted by rememberSaveable { mutableStateOf("") }
    var wantNew by rememberSaveable { mutableStateOf(start == "new") }
    val width = currentWidth()
    Polling(Unit, 0) { state.load() }
    // Löschaufträge von Daten laufen beim NAS; ihren Stand zeigt /operations.
    Polling(purgeStarted, if (purgeStarted.isNotEmpty() || state.deletions.any { it.finished == null }) 2000 else 0) { state.pollDeletions() }
    // Wie „?new=1“ im Web: erst, wenn Konten und Volumes geladen sind.
    if (wantNew && state.canAdminister && state.accountReady && state.volumes.isNotEmpty()) {
        wantNew = false
        dialog = ShareDialogKind.Create
    }
    val detail = state.shares?.firstOrNull { it.name == selected }
    val reload: () -> Unit = { scope.launch { state.load() } }
    AdminShell(
        glyph = "shares",
        gradient = "yellow",
        program = t("desktop.app.shares"),
        groups = listOf(
            SideGroup(
                null,
                listOfNotNull(
                    SideEntry("list", t("desktop.side.shares"), "globe"),
                    if (state.canAdminister) SideEntry("new", t("shares.new"), "plus", action = true) else null,
                ),
            ),
        ),
        current = "list",
        onSelect = { if (it == "new" && state.accountReady && state.volumes.isNotEmpty()) dialog = ShareDialogKind.Create },
        onExit = onBack,
        page = if (!width.twoPanes && detail != null) detail.name else t("shares.title"),
        onUp = if (!width.twoPanes && detail != null) ({ selected = null }) else null,
        actions = {
            IconButton(TitanIcons.restart, t("shares.refresh"), reload)
        },
        onKey = { e ->
            // Wie die Befehle der Menüleiste im Web; Esc schließt das Detail.
            if (e.type != KeyEventType.KeyDown) false
            else when {
                e.key == Key.F5 || (e.isCtrlPressed && e.key == Key.R) -> { reload(); true }
                e.key == Key.Escape && detail != null -> { selected = null; true }
                e.key == Key.Delete && detail != null && state.canAdminister -> { dialog = ShareDialogKind.Delete(detail); true }
                else -> false
            }
        },
    ) {
        ListDetail(
            list = {
                ShareList(
                    state,
                    selected,
                    purgeStarted,
                    onPurgeDone = { purgeStarted = "" },
                    onSelect = { name -> selected = if (selected == name && width.twoPanes) null else name },
                    onDialog = { dialog = it },
                    onTasks = { env.open("tools", "tasks") },
                )
            },
            detail = detail?.let { d ->
                {
                    ShareDetail(state, d, onClose = { selected = null }, onDialog = { dialog = it })
                }
            },
            listWidth = 440.dp,
            empty = {
                if (!state.shares.isNullOrEmpty()) EmptyState(t("shares.details"), stringResource(R.string.shares_choose), Modifier.padding(24.dp))
            },
        )
    }
    when (val d = dialog) {
        null -> Unit
        ShareDialogKind.Create -> ShareForm(state, null, onClose = { dialog = null }) { dialog = null; reload() }
        is ShareDialogKind.Edit -> ShareForm(state, d.share, onClose = { dialog = null }) { dialog = null; reload() }
        is ShareDialogKind.Connect -> ConnectSheet(d.share, state.host, state.mdnsHost) { dialog = null }
        is ShareDialogKind.Unlock -> UnlockDialog(state, d.share, onClose = { dialog = null }) { dialog = null; reload() }
        is ShareDialogKind.Delete -> DeleteShareDialog(state, d.share, onClose = { dialog = null }) { purged ->
            if (purged) purgeStarted = d.share.name
            if (selected == d.share.name) selected = null
            dialog = null
            reload()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareList(
    state: SharesState,
    selected: String?,
    purgeStarted: String,
    onPurgeDone: () -> Unit,
    onSelect: (String) -> Unit,
    onDialog: (ShareDialogKind) -> Unit,
    onTasks: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val locale = uiLocale()
    val shares = state.shares
    val c = Titan.colors
    PageBody {
        if (shares != null) Text(t("shares.summary", "count" to shares.size), style = TitanType.body, color = c.muted)
        if (state.canAdminister) {
            ButtonRow {
                ActionButton(t("shares.new"), { onDialog(ShareDialogKind.Create) }, primary = true, enabled = state.accountReady && state.volumes.isNotEmpty(), icon = "plus")
            }
        }
        if (purgeStarted.isNotEmpty()) {
            Notice(
                t("share.deleteDataStarted", "name" to purgeStarted),
                tone = Tone.Ok,
                actions = {
                    ButtonRow {
                        ActionButton(t("share.deleteDataLink"), onTasks)
                        ActionButton(t("common.done"), onPurgeDone)
                    }
                },
            )
        }
        for (job in state.deletions.filter { it.finished == null || it.share == purgeStarted }) {
            DeletionProgress(job)
        }
        state.error?.let { ErrorBox(it, { scope.launch { state.load() } }) }
        Field(t("shares.search"), query, { query = it }, placeholder = t("shares.search"))
        val volumeOptions = (state.volumeNames + (shares ?: emptyList()).map { it.volume } + listOfNotNull(state.view.volume.ifEmpty { null })).distinct().sorted()
        SettingGroup(null) {
            ChoiceRow(
                t("share.volume"),
                listOf("" to t("shares.allVolumes")) + volumeOptions.map { it to it },
                state.view.volume,
                { v -> scope.launch { state.saveView(state.view.copy(volume = v)) } },
                icon = "disk",
                enabled = !state.savingView,
            )
            ChoiceRow(
                t("share.protocols"),
                listOf("all" to t("shares.allProtocols"), "smb" to "SMB", "nfs" to "NFS"),
                state.view.protocol,
                { v -> scope.launch { state.saveView(state.view.copy(protocol = v)) } },
                icon = "globe",
                divider = true,
                enabled = !state.savingView,
            )
            ChoiceRow(
                stringResource(R.string.shares_sort),
                listOf("name" to t("menubar.shares.byName"), "volume" to t("menubar.shares.byVolume")),
                state.view.sort,
                { v -> scope.launch { state.saveView(state.view.copy(sort = v, descending = false)) } },
                icon = "sort",
                divider = true,
                enabled = !state.savingView,
            )
            ToggleRow(stringResource(R.string.shares_descending), state.view.descending, { v -> scope.launch { state.saveView(state.view.copy(descending = v)) } }, divider = true, enabled = !state.savingView)
            ToggleRow(t("shares.compact"), state.view.compact, { v -> scope.launch { state.saveView(state.view.copy(compact = v)) } }, divider = true, enabled = !state.savingView)
        }
        state.preferenceError?.let { ErrorBox(it, { scope.launch { state.saveView(state.view) } }) }
        when {
            shares == null && state.loading -> Loading()
            shares?.isEmpty() == true -> EmptyState(t("shares.empty"), if (state.volumes.isEmpty()) t("shares.needVolume") else t("shares.emptyHint"))
            shares != null -> {
                val shown = visibleShares(shares, state.view, query, locale)
                SettingGroup(t("shares.title")) {
                    if (shown.isEmpty()) {
                        SettingRow(t("shares.noMatch"))
                    }
                    shown.forEachIndexed { i, share ->
                        var menu by remember { mutableStateOf(false) }
                        Column {
                            SettingRow(
                                share.name,
                                desc = buildList {
                                    add(share.volume)
                                    add(listOfNotNull(if (share.smb) "SMB" else null, if (share.nfs.enabled) "NFS" else null).joinToString(" · ").ifEmpty { t("shares.noProtocol") })
                                    add(accessSummary(share))
                                    if (!state.view.compact) add(share.path)
                                }.joinToString(" · "),
                                icon = "folder",
                                divider = i > 0,
                                onClick = { onSelect(share.name) },
                                onMenu = { menu = true },
                                below = {
                                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ShareStatusChip(share)
                                        if (selected == share.name) StatusChip(t("shares.details"), Tone.Info)
                                        if (share.timeMachine.enabled) StatusChip(t("shares.timeMachineChip"), Tone.Neutral)
                                        if (share.guest) StatusChip(t("shares.guest"), Tone.Warn)
                                        if (share.guardLocked != null) StatusChip(t("shares.guardUnlock"), Tone.Error)
                                    }
                                },
                                control = { io.github.ra5on.titanos.admin.MoreButton(shareMenu(state, share, onSelect, onDialog), t("shares.actionsFor", "name" to share.name)) },
                            )
                            AdminMenu(menu, { menu = false }, shareMenu(state, share, onSelect, onDialog))
                        }
                    }
                }
                Text(t("ui.results", "count" to shown.size), style = TitanType.small, color = c.muted)
                // Zahlen unten in einer Zeile (StatusBar im Web).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${t("shares.title")} ${shares.size}", style = TitanType.small, color = c.text)
                    Text("SMB ${shares.count { it.smb }}", style = TitanType.small, color = c.text)
                    Text("NFS ${shares.count { it.nfs.enabled }}", style = TitanType.small, color = c.text)
                    val locked = shares.count { it.guardLocked != null }
                    if (locked > 0) Text(t("shares.lockedCount", "count" to locked), style = TitanType.small, color = c.dangerInk)
                }
            }
        }
    }
}

@Composable
private fun shareMenu(state: SharesState, share: Share, onSelect: (String) -> Unit, onDialog: (ShareDialogKind) -> Unit): List<MenuItem> = buildList {
    add(MenuItem(t("shares.details"), "info") { onSelect(share.name) })
    add(MenuItem(t("shares.connect"), "globe") { onDialog(ShareDialogKind.Connect(share)) })
    state.env.openFiles?.let { open -> add(MenuItem(t("shares.openFiles"), "folder") { open("share/${share.name}", "") }) }
    if (state.canAdminister) {
        add(MenuItem(t("shares.edit"), "edit", enabled = state.accountReady) { onDialog(ShareDialogKind.Edit(share)) })
        add(MenuItem(t("shares.delete"), "trash", danger = true) { onDialog(ShareDialogKind.Delete(share)) })
    }
}

@Composable
private fun accessSummary(share: Share): String =
    if (share.access.size == 1) t("shares.accountOne") else t("shares.accountCount", "count" to share.access.size)

@Composable
private fun ShareStatusChip(share: Share) {
    when {
        !share.available -> StatusChip(t("shares.unavailable"), Tone.Error)
        nobodyCanUse(share) -> StatusChip(t("shares.nobodyChip"), Tone.Warn)
        else -> StatusChip(t("shares.available"), Tone.Ok)
    }
}

@Composable
internal fun DeletionProgress(job: ShareDeletion) {
    JobProgress(
        title = t("share.deleteTitle", "name" to job.share),
        phase = when (job.phase) {
            "checking" -> stringResource(R.string.shares_phase_checking)
            "removing" -> stringResource(R.string.shares_phase_removing)
            "waiting" -> stringResource(R.string.shares_phase_waiting)
            "deleting" -> stringResource(R.string.shares_phase_deleting)
            "done" -> stringResource(R.string.shares_phase_done)
            "failed" -> stringResource(R.string.shares_phase_failed)
            else -> job.phase
        },
        percent = deletionPercent(job) ?: if (job.phase == "done") 100.0 else null,
        detail = if (job.totalFiles > 0) "${job.files} / ${job.totalFiles} · ${formatDiskBytes(job.bytes)} / ${formatDiskBytes(job.totalBytes)}" else job.path,
        error = job.error?.takeIf { it.isNotEmpty() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareDetail(state: SharesState, detail: Share, onClose: () -> Unit, onDialog: (ShareDialogKind) -> Unit) {
    var tab by rememberSaveable(detail.name) { mutableStateOf("general") }
    val c = Titan.colors
    val width = currentWidth()
    PageBody {
        if (width.twoPanes) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(detail.name, style = TitanType.title, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(TitanIcons.x, t("shares.closeDetails"), onClose)
            }
        }
        if (detail.perUser == true) Notice(t("shares.perUser"), tone = Tone.Ok)
        detail.guardLocked?.let { lock ->
            Notice(
                t("shares.guardLocked", "time" to formatDateTime(lock.at), "user" to lock.user, "files" to lock.files),
                tone = Tone.Error,
                actions = if (state.canAdminister) ({ ActionButton(t("shares.guardUnlock"), { onDialog(ShareDialogKind.Unlock(detail)) }, danger = true) }) else null,
            )
        }
        Segments(listOf("general", "access", "options").map { it to t("shares.tab.$it") }, tab, { tab = it }, label = t("shares.details"))
        SettingGroup(null) {
            when (tab) {
                "general" -> {
                    SettingRow(t("share.volume"), value = detail.volume, icon = "disk")
                    SettingRow(t("shares.path"), value = detail.path, icon = "folder", divider = true)
                    SettingRow(t("shares.status"), divider = true, icon = "info", below = { Row(Modifier.padding(top = 4.dp)) { ShareStatusChip(detail) } })
                    SettingRow(t("shares.quota"), divider = true, icon = "box", below = { QuotaText(detail) })
                }
                "access" -> {
                    detail.access.entries.sortedBy { it.key }.forEachIndexed { i, (name, level) ->
                        SettingRow(name, value = t("share.$level"), icon = "person", divider = i > 0)
                    }
                    SettingRow(t("shares.guest"), value = if (detail.guest) t("share.ro") else t("share.none"), icon = "users", divider = detail.access.isNotEmpty())
                    if (detail.nfs.enabled) SettingRow("NFS", value = (detail.nfs.clients ?: emptyList()).joinToString(", "), icon = "globe", divider = true)
                }
                else -> {
                    SettingRow(t("share.recycle"), value = if (detail.recycle.enabled) t("shares.recycleChip", "days" to detail.recycle.days) else t("shares.off"), icon = "trash")
                    SettingRow(
                        t("share.timeMachine"),
                        value = if (detail.timeMachine.enabled) (if (detail.timeMachine.sizeGB > 0) "${detail.timeMachine.sizeGB} GB" else t("shares.on")) else t("shares.off"),
                        icon = "clock",
                        divider = true,
                    )
                    SettingRow(t("share.previousVersions"), value = t(if (detail.previousVersions == true) "shares.on" else "shares.off"), icon = "undo", divider = true)
                    SettingRow(t("share.guard"), value = t(if (detail.guard == true) "shares.on" else "shares.off"), icon = "shield", divider = true)
                    SettingRow(t("share.browseable"), value = t(if (detail.browseable) "shares.on" else "shares.off"), icon = "eye", divider = true)
                }
            }
        }
        if (tab == "access" && detail.nfs.enabled) Text(t("share.nfsWarning"), style = TitanType.small, color = c.muted)
        ButtonRow {
            ActionButton(t("shares.connect"), { onDialog(ShareDialogKind.Connect(detail)) }, primary = true)
            state.env.openFiles?.let { open -> ActionButton(t("shares.openFiles"), { open("share/${detail.name}", "") }) }
            if (state.canAdminister) {
                ActionButton(t("shares.edit"), { onDialog(ShareDialogKind.Edit(detail)) }, enabled = state.accountReady)
                ActionButton(t("shares.delete"), { onDialog(ShareDialogKind.Delete(detail)) }, danger = true)
            }
        }
    }
}

@Composable
private fun QuotaText(detail: Share) {
    val c = Titan.colors
    val quota = detail.quota ?: 0
    val status = detail.quotaStatus
    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            quota <= 0 -> Text(t("shares.noQuota"), style = TitanType.rowSub, color = c.text)
            status?.state == "active" || status?.state == "applying" -> {
                Text(t("shares.quotaUsed", "used" to formatDiskBytes(status.used), "limit" to formatDiskBytes(quota)), style = TitanType.rowSub, color = c.text)
                val f = (status.used.toDouble() / quota).coerceIn(0.0, 1.0)
                Bar(f.toFloat(), color = if (status.used >= quota * 0.9) c.dangerInk else c.accent)
                Text(formatPercent(f * 100), style = TitanType.small, color = c.muted)
                if (status.state == "applying") Text(t("shares.quotaApplying"), style = TitanType.small, color = c.muted)
            }
            else -> Text(
                "${formatDiskBytes(quota)} · " + if (status?.state == "error") t("shares.quotaError", "error" to (status.error ?: "")) else t("shares.quotaPending"),
                style = TitanType.rowSub,
                color = if (status?.state == "error") c.dangerInk else c.text,
            )
        }
    }
}

@Composable
private fun UnlockDialog(state: SharesState, share: Share, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Throwable?>(null) }
    io.github.ra5on.titanos.admin.Confirm(
        title = t("shares.guardUnlockTitle", "name" to share.name),
        text = t("shares.guardUnlockText"),
        confirm = t("shares.guardUnlock"),
        onConfirm = {
            scope.launch {
                error = null
                try {
                    state.api.unlock(share.name)
                    onDone()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e
                }
            }
        },
        onDismiss = onClose,
        body = error?.let { e -> { Notice(errorText(e), tone = Tone.Error) } },
    )
}
