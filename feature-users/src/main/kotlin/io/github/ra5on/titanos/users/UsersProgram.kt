package io.github.ra5on.titanos.users

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.AdminShell
import io.github.ra5on.titanos.admin.AdminSheet
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.ErrorBox
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.ListDetail
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.SheetButtons
import io.github.ra5on.titanos.admin.SideEntry
import io.github.ra5on.titanos.admin.SideGroup
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.ToggleRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.adminText
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDate
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

internal sealed interface UserDialog {
    data object Create : UserDialog
    data class Password(val name: String) : UserDialog
    data class Delete(val name: String) : UserDialog
    data class TwoFactor(val account: Account) : UserDialog
    data class Owner(val account: Account) : UserDialog
    data class Lock(val account: Account, val disabled: Boolean) : UserDialog
}

internal class UsersState(val env: AdminEnv) {
    val api = UsersApi(env.http)
    var accounts by mutableStateOf<List<Account>?>(null)
    var shares by mutableStateOf<List<JsonObject>?>(null)
    var loading by mutableStateOf(false)
    var loadError by mutableStateOf<Throwable?>(null)
    var error by mutableStateOf<Throwable?>(null)
    var working by mutableStateOf(false)
    val meName: String get() = env.user
    val meRole: String get() = env.role

    suspend fun load() {
        loading = true
        loadError = null
        try {
            accounts = api.accounts()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e
        }
        // Ohne Freigaben geht die Seite trotzdem; die Spalte sagt, dass es unbekannt ist.
        shares = runCatching { api.sharesRaw() }.getOrNull()
        loading = false
    }

    fun shareList(): List<Share>? = shares?.map { o ->
        Share(
            name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
            volume = (o["volume"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
            access = (o["access"] as? JsonObject)?.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" } ?: emptyMap(),
        )
    }
}

@Composable
internal fun UsersProgram(env: AdminEnv, start: String?, onBack: () -> Unit) {
    val state = remember(env) { UsersState(env) }
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("all") }
    var chosen by rememberSaveable { mutableStateOf("") }
    var showDetail by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<UserDialog?>(null) }
    val width = currentWidth()
    Polling(Unit, 0) { state.load() }
    LaunchedEffect(start) { if (start == "new") dialog = UserDialog.Create }
    val shown = state.accounts?.let { filterAccounts(it, filter) } ?: emptyList()
    // Nach dem Löschen eines Kontos bleibt man in der Liste (Auftrag I3): am Handy die Liste, nebeneinander
    // das Konto, das an seiner Stelle steht – nicht das eigene.
    var deletedAt by rememberSaveable { mutableStateOf(-1) }
    val current = shown.firstOrNull { it.name == chosen }
        ?: (if (deletedAt >= 0) shown.getOrNull(minOf(deletedAt, shown.size - 1)) else null)
        ?: shown.firstOrNull { it.name == state.meName } ?: shown.firstOrNull()
    val reload: () -> Unit = { scope.launch { state.load() } }
    val detailOpen = if (width.twoPanes) current != null else showDetail && current != null
    AdminShell(
        glyph = "users",
        gradient = "blue",
        program = t("desktop.app.users"),
        groups = listOf(SideGroup(null, listOf(SideEntry("accounts", t("desktop.side.accounts"), "users")))),
        current = "accounts",
        onSelect = {},
        onExit = onBack,
        // Der Kontoname steht groß im Inhalt (in Monoschrift); die Kopfleiste bleibt bei „Konten“.
        page = t("users.title"),
        onUp = if (!width.twoPanes && showDetail) ({ showDetail = false }) else null,
        actions = { IconButton(TitanIcons.restart, t("ops.refresh"), reload) },
        onKey = { e ->
            if (e.type != KeyEventType.KeyDown) false
            else when {
                e.key == Key.F5 || (e.isCtrlPressed && e.key == Key.R) -> { reload(); true }
                e.isCtrlPressed && e.key == Key.N -> { dialog = UserDialog.Create; true }
                else -> false
            }
        },
    ) {
        ListDetail(
            list = {
                PageBody {
                    state.accounts?.let { a ->
                        Text(if (a.size == 1) t("users.summaryOne") else t("users.summary", "count" to a.size), style = TitanType.body, color = Titan.colors.muted)
                    }
                    ButtonRow { ActionButton(t("users.new"), { dialog = UserDialog.Create }, primary = true, icon = "plus") }
                    state.error?.let { Notice(errorText(it), tone = Tone.Error) }
                    state.loadError?.let { ErrorBox(it, reload) }
                    if (state.accounts == null) {
                        if (state.loadError == null) Loading()
                    } else {
                        SettingGroup(null) {
                            ChoiceRow(
                                t("users.filter"),
                                listOf("all" to t("users.filterAll"), "active" to t("users.filterActive"), "locked" to t("users.filterLocked")),
                                filter,
                                { filter = it },
                                icon = "filter",
                            )
                        }
                        SettingGroup(t("users.list")) {
                            if (shown.isEmpty()) SettingRow(t("users.filterEmpty"))
                            shown.forEachIndexed { i, a ->
                                val on = width.twoPanes && current?.name == a.name
                                Box(Modifier.background(if (on) Titan.colors.accent.copy(alpha = .14f) else Color.Transparent)) {
                                    AccountRow(a, divider = i > 0) { chosen = a.name; deletedAt = -1; showDetail = true }
                                }
                            }
                        }
                    }
                }
            },
            detail = if (detailOpen && current != null) ({
                AccountDetail(state, current, onDialog = { dialog = it }, onShares = reload)
            }) else null,
            listWidth = 380.dp,
        )
    }
    UsersDialogs(state, dialog, { dialog = it }, reload, onDeleted = { name ->
        deletedAt = shown.indexOfFirst { it.name == name }.coerceAtLeast(0)
        chosen = ""
        showDetail = false
    })
}

@Composable
private fun Avatar(name: String, big: Boolean = false) {
    // Farbe nur als Schmuck; der Name steht daneben.
    val color = Color.hsl(hue(name).toFloat(), 0.55f, 0.42f)
    Box(Modifier.size(if (big) 56.dp else 38.dp).clip(CircleShape).background(color).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = if (big) TitanType.title else TitanType.rowTitle, color = Color.White)
    }
}

@Composable
private fun AccountRow(a: Account, divider: Boolean, onClick: () -> Unit) {
    val c = Titan.colors
    val sub = buildList {
        add(t("role.${a.role}"))
        if (a.disabled) add(t("users.statusLocked"))
        if (a.twoFactor) add(t("users.twoFactor"))
    }.joinToString(" · ")
    Column {
        if (divider) io.github.ra5on.titanos.ui.components.Divider()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(a.name)
            Column(Modifier.weight(1f)) {
                Text(a.name, style = TitanType.rowTitle.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = c.text)
                Text(sub, style = TitanType.rowSub, color = c.muted)
                if (a.disabled) Row(Modifier.padding(top = 4.dp)) { StatusChip(t("users.statusLocked"), Tone.Warn) }
            }
            androidx.compose.material3.Icon(TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountDetail(state: UsersState, account: Account, onDialog: (UserDialog) -> Unit, onShares: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val self = account.name == state.meName
    val manageable = mayManage(state.meRole, account.role)
    val accounts = state.accounts ?: emptyList()
    val lockable = mayLock(state.meName, state.meRole, account, accounts)
    val isAdmin = account.role == "owner" || account.role == "admin"
    var apps by remember(account.name) { mutableStateOf<UserApps?>(null) }
    var appsError by remember(account.name) { mutableStateOf<Throwable?>(null) }
    var appsBusy by remember(account.name) { mutableStateOf(false) }
    var mailOn by remember(account.name) { mutableStateOf<Boolean?>(null) }
    var mailBusy by remember(account.name) { mutableStateOf(false) }
    var mailError by remember(account.name) { mutableStateOf<Throwable?>(null) }
    var cams by remember(account.name) { mutableStateOf<UserCameras?>(null) }
    var camsMissing by remember(account.name) { mutableStateOf(false) }
    var camsBusy by remember(account.name) { mutableStateOf(false) }
    var camsError by remember(account.name) { mutableStateOf<Throwable?>(null) }
    var shareBusy by remember(account.name) { mutableStateOf("") }
    var shareError by remember(account.name) { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(account.name) {
        try {
            apps = state.api.apps(account.name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appsError = e
        }
    }
    // Ohne Antwort kein Schalter (älteres NAS ohne T-Mail).
    LaunchedEffect(account.name) { mailOn = runCatching { state.api.mail(account.name).enabled }.getOrNull() }
    LaunchedEffect(account.name) {
        val r = runCatching { state.api.cameras(account.name) }.getOrNull()
        camsMissing = r?.cameras == null
        cams = if (camsMissing) null else r
    }
    fun saveApps(body: AppsBody) {
        appsBusy = true
        appsError = null
        scope.launch {
            try {
                apps = state.api.saveApps(account.name, body)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appsError = e
            } finally {
                appsBusy = false
            }
        }
    }
    PageBody {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(account.name, big = true)
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(account.name, style = TitanType.title.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = c.text, modifier = Modifier.semantics { heading() })
                    if (self) StatusChip(t("users.you"), Tone.Info)
                }
                val locked = if (account.disabled) {
                    account.disabledAt?.let { at ->
                        if (account.disabledBy != null) t("users.lockedSince", "date" to formatDateTime(at), "name" to account.disabledBy) else t("users.lockedAt", "date" to formatDateTime(at))
                    } ?: t("users.statusLocked")
                } else {
                    t("users.createdOn", "date" to formatDate(account.created))
                }
                Text("${t("role.${account.role}")} · $locked", style = TitanType.rowSub, color = c.muted)
                if (!account.smbReady) Text(t("users.smbPending"), style = TitanType.small, color = c.muted)
            }
        }
        if (account.disabled) StatusChip(t("users.statusLocked"), Tone.Warn)
        ButtonRow {
            if (manageable || self) ActionButton(t("users.changePassword"), { onDialog(UserDialog.Password(account.name)) }, icon = "key")
            if (lockable) ActionButton(if (account.disabled) t("users.unlock") else t("users.lock"), { onDialog(UserDialog.Lock(account, !account.disabled)) }, icon = if (account.disabled) "unlock" else "lock")
            if (manageable && !self) ActionButton(t("users.delete"), { onDialog(UserDialog.Delete(account.name)) }, danger = true, icon = "trash")
        }
        // Rolle
        SettingGroup(t("users.role"), desc = t("users.roleHint.${account.role}"), expandable = true) {
            if (manageable && !self) {
                val roleHints = offeredRoles(state.meRole).associateWith { t("users.roleHint.$it") }
                ChoiceRow(
                    t("users.role"),
                    offeredRoles(state.meRole).map { it to t("role.$it") },
                    account.role,
                    { role ->
                        if (role == "owner" && account.role != "owner") {
                            onDialog(UserDialog.Owner(account))
                        } else if (role != account.role) {
                            scope.launch {
                                state.working = true
                                state.error = null
                                try {
                                    state.api.setRole(account.name, role)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    state.error = e
                                }
                                state.working = false
                                state.load()
                            }
                        }
                    },
                    icon = "users",
                    enabled = !state.working,
                    optionDesc = { roleHints[it] },
                )
            } else {
                SettingRow(t("users.role"), value = t("role.${account.role}"), icon = "users")
            }
        }
        // Apps
        val allowed = apps?.apps?.count { it.allowed } ?: 0
        val appsSummary = apps?.let { a ->
            (listOfNotNull(mailOn?.let { if (it) t("users.apps.mailOn") else t("users.apps.mailOff") }) +
                if (a.admin) listOf(t("users.apps.adminAll")) else listOf(
                    t("users.apps.count", "allowed" to allowed, "total" to a.apps.size),
                    if (a.newApps) t("users.apps.newAuto") else t("users.apps.newManual"),
                )).joinToString(" · ")
        }
        SettingGroup(t("users.apps.title"), desc = appsSummary, expandable = true) {
            appsError?.let { Notice(errorText(it), Modifier.padding(10.dp), tone = Tone.Error) }
            mailOn?.let { on ->
                ToggleRow(t("users.apps.mail"), on, { v ->
                    mailBusy = true
                    mailError = null
                    scope.launch {
                        try {
                            mailOn = state.api.saveMail(account.name, v).enabled
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            mailError = e
                        } finally {
                            mailBusy = false
                        }
                    }
                }, desc = t("users.apps.mailHint"), icon = "mail", enabled = !mailBusy)
                mailError?.let { Notice(errorText(it), Modifier.padding(10.dp), tone = Tone.Error) }
            }
            val a = apps
            when {
                a == null -> Loading()
                a.admin -> SettingRow(t("users.apps.adminHint"), divider = mailOn != null)
                a.apps.isEmpty() -> SettingRow(t("users.apps.none"), divider = mailOn != null)
                else -> {
                    a.apps.forEachIndexed { i, app ->
                        CheckRow(app.app, app.allowed, { v -> saveApps(AppsBody(apps = mapOf(app.app to v))) }, enabled = !appsBusy, divider = i > 0 || mailOn != null)
                    }
                    ToggleRow(t("users.apps.newLabel"), a.newApps, { v -> saveApps(AppsBody(newApps = v)) }, enabled = !appsBusy, divider = true)
                    ButtonRow(Modifier.padding(14.dp)) {
                        ActionButton(t("users.apps.all"), { saveApps(AppsBody(apps = a.apps.associate { it.app to true })) }, enabled = !appsBusy)
                        ActionButton(t("users.apps.noneButton"), { saveApps(AppsBody(apps = a.apps.associate { it.app to false })) }, enabled = !appsBusy)
                    }
                }
            }
        }
        // Freigaben
        val shares = state.shareList()
        val reach = shares?.sortedBy { it.name.lowercase() }?.map { it to it.access[account.name] }
        val sharesSummary = if (shares == null) t("users.sharesUnknown") else {
            val rw = reach!!.filter { it.second == "rw" }.map { it.first.name }
            val ro = reach.filter { it.second == "ro" }.map { it.first.name }
            listOfNotNull(
                rw.takeIf { it.isNotEmpty() }?.let { t("users.sharesSummaryRw", "names" to it.joinToString(", ")) },
                ro.takeIf { it.isNotEmpty() }?.let { t("users.sharesSummaryRo", "names" to it.joinToString(", ")) },
            ).joinToString(" · ").ifEmpty { t("users.sharesNone") }
        }
        SettingGroup(t("users.shares"), desc = sharesSummary, expandable = true) {
            shareError?.let { Notice(errorText(it), Modifier.padding(10.dp), tone = Tone.Error) }
            when {
                shares == null -> SettingRow(t("users.sharesUnknown"))
                shares.isEmpty() -> SettingRow(t("users.sharesNone"))
                else -> reach!!.forEachIndexed { i, (share, level) ->
                    if (manageable || isAdmin) {
                        ChoiceRow(
                            share.name,
                            listOf("" to t("users.shareNone"), "ro" to t("share.ro"), "rw" to t("share.rw")),
                            level ?: "",
                            { next ->
                                val raw = state.shares?.firstOrNull { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == share.name } ?: return@ChoiceRow
                                shareBusy = share.name
                                shareError = null
                                scope.launch {
                                    try {
                                        state.api.setShareAccess(raw, account.name, next)
                                        onShares()
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        shareError = e
                                    } finally {
                                        shareBusy = ""
                                    }
                                }
                            },
                            desc = share.volume,
                            icon = "folder",
                            divider = i > 0,
                            enabled = shareBusy != share.name,
                        )
                    } else {
                        SettingRow(share.name, desc = share.volume, value = level?.let { t("share.$it") } ?: t("users.shareNone"), icon = "folder", divider = i > 0)
                    }
                }
            }
        }
        // Kameras (nur mit NVR)
        if (!camsMissing) {
            val rows = cams?.cameras?.filter { it.deleted != true } ?: emptyList()
            val camsSummary = cams?.let { cs ->
                when {
                    cs.admin -> t("nvr.rights.adminAll")
                    rows.isEmpty() -> t("nvr.rights.noCameras")
                    else -> t("nvr.rights.count", "live" to rows.count { it.live }, "recordings" to rows.count { it.recordings }, "total" to rows.size)
                }
            }
            SettingGroup(t("nvr.rights.title"), desc = camsSummary, expandable = true, initiallyOpen = false) {
                camsError?.let { Notice(errorText(it), Modifier.padding(10.dp), tone = Tone.Error) }
                val cs = cams
                when {
                    cs == null -> Loading()
                    cs.admin -> SettingRow(t("nvr.rights.adminHint"))
                    rows.isEmpty() -> SettingRow(t("nvr.rights.noCameras"))
                    else -> {
                        SettingRow(t("nvr.rights.hint"), desc = t("nvr.rights.namesHint"))
                        val can = manageable || isAdmin
                        rows.forEach { cam ->
                            fun save(right: String, on: Boolean) {
                                camsBusy = true
                                camsError = null
                                scope.launch {
                                    try {
                                        val body = CameraBody(
                                            live = if (right == "live") on else cam.live,
                                            recordings = if (right == "recordings") on else cam.recordings,
                                            names = if (right == "names") on else cam.names == true,
                                        )
                                        val next = state.api.saveCamera(account.name, cam.id, body)
                                        if (next.cameras != null) cams = next
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        camsError = e
                                    } finally {
                                        camsBusy = false
                                    }
                                }
                            }
                            io.github.ra5on.titanos.ui.components.Divider()
                            Text(cam.name, style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(start = 14.dp, top = 12.dp))
                            ToggleRow(t("nvr.rights.live"), cam.live, { save("live", it) }, enabled = !camsBusy && can, label = t("nvr.rights.liveFor", "camera" to cam.name))
                            ToggleRow(t("nvr.rights.recordings"), cam.recordings, { save("recordings", it) }, enabled = !camsBusy && can, label = t("nvr.rights.recordingsFor", "camera" to cam.name))
                            ToggleRow(t("nvr.rights.names"), cam.recordings && cam.names == true, { save("names", it) }, enabled = !camsBusy && can && cam.recordings, label = t("nvr.rights.namesFor", "camera" to cam.name))
                        }
                    }
                }
            }
        }
        // Anmeldung
        SettingGroup(t("users.login"), desc = if (account.twoFactor) t("users.twoFactorOn") else t("users.twoFactorOff"), expandable = true) {
            SettingRow(
                t("users.twoFactor"),
                desc = if (account.twoFactor) t("users.twoFactorOn") else t("users.twoFactorOff"),
                icon = "shield",
                below = when {
                    account.twoFactor && manageable && !self -> ({
                        Row(Modifier.padding(top = 8.dp)) { ActionButton(t("users.resetTwoFactor"), { onDialog(UserDialog.TwoFactor(account)) }) }
                    })
                    // Wie im Web (AccountDetail.svelte): das eigene Konto ohne 2FA richtet sie in „Mein Konto“ ein.
                    self && !account.twoFactor -> ({
                        Row(Modifier.padding(top = 8.dp)) { ActionButton(t("users.twoFactorSetUp"), { state.env.open("account", null) }) }
                    })
                    else -> null
                },
            )
            SettingRow(
                t("users.password"),
                desc = t("users.passwordNoteShort"),
                icon = "key",
                divider = true,
                below = if (manageable || self) ({
                    Row(Modifier.padding(top = 8.dp)) { ActionButton(t("users.changePassword"), { onDialog(UserDialog.Password(account.name)) }) }
                }) else null,
            )
        }
    }
}

@Composable
private fun UsersDialogs(state: UsersState, dialog: UserDialog?, setDialog: (UserDialog?) -> Unit, reload: () -> Unit, onDeleted: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var dialogError by remember(dialog) { mutableStateOf<String?>(null) }
    val close = { if (!state.working) setDialog(null) }
    fun run(action: suspend () -> Unit) {
        if (state.working) return
        state.working = true
        dialogError = null
        scope.launch {
            try {
                action()
                setDialog(null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dialogError = context.errorText(e)
            } finally {
                state.working = false
            }
            state.load()
        }
    }
    val errorBody: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = dialogError?.let { e -> { Notice(e, tone = Tone.Error) } }
    when (dialog) {
        null -> Unit
        UserDialog.Create -> CreateSheet(state, onClose = close) { setDialog(null); reload() }
        is UserDialog.Password -> PasswordSheet(state, dialog.name, onClose = close)
        is UserDialog.Delete -> {
            var agent by remember(dialog.name) { mutableStateOf<AgentDataLines?>(null) }
            var agentLoading by remember(dialog.name) { mutableStateOf(true) }
            LaunchedEffect(dialog.name) {
                agent = runCatching { agentDataLines(state.api.agentData(dialog.name)) }.getOrNull()
                agentLoading = false
            }
            val reached = state.shareList()?.filter { it.access[dialog.name] != null }?.map { it.name }?.sorted() ?: emptyList()
            Confirm(
                title = t("users.deleteTitle", "name" to dialog.name),
                text = t("users.deleteText"),
                confirm = t("users.delete"),
                onConfirm = { run { state.api.delete(dialog.name); onDeleted(dialog.name) } },
                onDismiss = close,
                body = {
                    if (reached.isNotEmpty()) Text(t("users.deleteShares", "shares" to reached.joinToString(", ")), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(bottom = 10.dp))
                    if (agentLoading) {
                        Loading(text = t("users.agentData.loading"))
                    } else {
                        agent?.let { lines ->
                            if (lines.deleted.isNotEmpty()) {
                                Text(t("users.agentData.title"), style = TitanType.body, color = Titan.colors.text)
                                for ((key, n) in lines.deleted) Text("• " + (if (n == null) t(key) else t(key, "count" to n)), style = TitanType.body, color = Titan.colors.text)
                            }
                            lines.kept?.let { (key, n) -> Text(t(key, "count" to n), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(top = 8.dp)) }
                            lines.note?.let { Text(t(it), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 8.dp)) }
                        }
                    }
                    dialogError?.let { Notice(it, Modifier.padding(top = 8.dp), tone = Tone.Error) }
                },
            )
        }
        is UserDialog.Owner -> Confirm(
            title = t("users.ownerTitle", "name" to dialog.account.name),
            text = t("users.ownerText", "name" to dialog.account.name),
            confirm = t("users.ownerSubmit"),
            onConfirm = { run { state.api.setRole(dialog.account.name, "owner") } },
            onDismiss = close,
            body = errorBody,
        )
        is UserDialog.Lock -> Confirm(
            title = t(if (dialog.disabled) "users.lockTitle" else "users.unlockTitle", "name" to dialog.account.name),
            text = t(if (dialog.disabled) "users.lockText" else "users.unlockText", "name" to dialog.account.name),
            confirm = t(if (dialog.disabled) "users.lock" else "users.unlock"),
            danger = dialog.disabled,
            onConfirm = { run { state.api.lock(dialog.account.name, dialog.disabled) } },
            onDismiss = close,
            body = errorBody,
        )
        is UserDialog.TwoFactor -> Confirm(
            title = t("users.resetTwoFactorTitle", "name" to dialog.account.name),
            text = t("users.resetTwoFactorText", "name" to dialog.account.name),
            confirm = t("users.resetTwoFactor"),
            onConfirm = { run { state.api.resetTwoFactor(dialog.account.name, dialog.account.role) } },
            onDismiss = close,
            body = errorBody,
        )
    }
}

/** Neues Konto: Name, Rolle, Passwort zweimal. Passwörter bleiben nur im Speicher dieses Formulars. */
@Composable
private fun CreateSheet(state: UsersState, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("user") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        error = null
        val n = name.trim().lowercase()
        if (!validAccountName(n)) { error = context.adminText("error.invalid_name"); return }
        if (passwordLength(password) < MIN_PASSWORD_LENGTH) { error = context.adminText("error.password_too_short"); return }
        if (password != repeat) { error = context.adminText("auth.passwordMismatch"); return }
        state.working = true
        scope.launch {
            try {
                state.api.create(n, password, role)
                password = ""
                repeat = ""
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = context.errorText(e)
            } finally {
                state.working = false
            }
        }
    }
    AdminSheet(
        t("users.createTitle"),
        onDismiss = onClose,
        footer = {
            SheetButtons {
                ActionButton(t("common.cancel"), onClose, enabled = !state.working)
                ActionButton(t("users.create"), ::submit, primary = true, enabled = !state.working)
            }
        },
    ) {
        Field(
            t("auth.name"),
            name,
            { name = it.take(32) },
            hint = t("auth.nameHint"),
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        SettingGroup(null) {
            ChoiceRow(t("users.role"), offeredRoles(state.meRole).map { it to t("role.$it") }, role, { role = it }, desc = t("users.roleHint.$role"), icon = "users")
        }
        Field(t("auth.password"), password, { password = it }, password = true, hint = t("auth.passwordHint", "min" to MIN_PASSWORD_LENGTH))
        Field(t("auth.passwordRepeat"), repeat, { repeat = it }, password = true)
        error?.let { Notice(it, tone = Tone.Warn) }
    }
}

/** Passwort ändern: das eigene mit dem bisherigen, als Administrator das eines anderen. */
@Composable
private fun PasswordSheet(state: UsersState, name: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val self = name == state.meName
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        error = null
        if (passwordLength(password) < MIN_PASSWORD_LENGTH) { error = context.adminText("error.password_too_short"); return }
        if (password != repeat) { error = context.adminText("auth.passwordMismatch"); return }
        state.working = true
        scope.launch {
            try {
                state.api.password(name, password, if (self) current else null)
                onClose()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = context.errorText(e)
            } finally {
                state.working = false
            }
        }
    }
    AdminSheet(
        t("users.passwordTitle", "name" to name),
        onDismiss = onClose,
        footer = {
            SheetButtons {
                ActionButton(t("common.cancel"), onClose, enabled = !state.working)
                ActionButton(t("users.savePassword"), ::submit, primary = true, enabled = !state.working)
            }
        },
    ) {
        if (self) Field(t("users.currentPassword"), current, { current = it }, password = true)
        Field(t("users.newPassword"), password, { password = it }, password = true, hint = t("auth.passwordHint", "min" to MIN_PASSWORD_LENGTH))
        Field(t("auth.passwordRepeat"), repeat, { repeat = it }, password = true)
        Text(t("users.passwordNote"), style = TitanType.small, color = Titan.colors.muted)
        error?.let { Notice(it, tone = Tone.Warn) }
    }
}
