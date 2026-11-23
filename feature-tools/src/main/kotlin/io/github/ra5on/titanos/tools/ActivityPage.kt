package io.github.ra5on.titanos.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.NumberField
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.ToggleRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Aktivitätsprotokoll (tools/activity): wer hat wann was getan, mit Filtern,
 * Export und Aufbewahrung.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityPage(ctx: ToolsCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }
    var more by remember { mutableStateOf(false) }
    var stored by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<ActivityStatus?>(null) }
    var settings by remember { mutableStateOf<ActivitySettings?>(null) }
    var initial by remember { mutableStateOf<ActivitySettings?>(null) }
    var storage by remember { mutableStateOf<StorageStatus?>(null) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var user by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    val saver = rememberSaver()

    suspend fun load(older: Boolean = false) {
        busy = true
        error = ""
        try {
            val last = if (older) entries.lastOrNull()?.at else null
            val page = ctx.api.activity(ctx.api.activityQuery(days, user, search, failed, last))
            entries = if (older) entries + page.entries else page.entries
            more = page.more
            stored = page.stored
            status = page.status
            if (settings == null) {
                settings = page.status.settings
                initial = page.status.settings
            }
        } catch (e: Exception) {
            error = t0(context, "ops.failed") + " " + context.errorText(e)
        } finally {
            busy = false
        }
    }
    // Suche wartet einen Moment nach dem Tippen; die anderen Filter laden sofort.
    LaunchedEffect(days, user, failed, tick) { load() }
    var typed by remember { mutableStateOf(false) }
    LaunchedEffect(search) {
        if (!typed) {
            typed = true
            return@LaunchedEffect
        }
        delay(300)
        load()
    }
    LaunchedEffect(Unit) {
        runCatching { storage = ctx.api.storage() }
        runCatching { accounts = ctx.api.users() }
    }
    val dirty = settings != null && settings != initial
    PageDirty(ctx, dirty)

    fun save() {
        val s = settings ?: return
        busy = true
        saved = false
        error = ""
        scope.launch {
            try {
                val next = ctx.api.saveActivity(s)
                status = next
                settings = next.settings
                initial = next.settings
                saved = true
            } catch (e: Exception) {
                error = t0(context, "ops.failed") + " " + context.errorText(e)
            } finally {
                busy = false
            }
            if (error.isEmpty()) load()
        }
    }
    fun download(format: String) {
        val path = ctx.api.activityQuery(days, user, search, failed, format = format)
        saver.save("titanos-activity-${days}d.$format", if (format == "csv") "text/csv" else "application/json") { out, progress -> ctx.api.download(path, out, progress) }
    }
    PageKeys(ctx) { e ->
        when {
            e.refresh() -> { tick++; true }
            e.ctrlKey(Key.F) -> { runCatching { searchFocus.requestFocus() }; true }
            e.ctrlKey(Key.S) -> { if (dirty && !busy) save(); true }
            e.ctrlKey(Key.E) -> { if (!busy) download("csv"); true }
            else -> false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ButtonRow { ActionButton(t("ops.refresh"), { tick++ }, enabled = !busy, icon = "refresh") }
                Muted(t("activity.hint"))
                if (error.isNotEmpty()) Notice(error, tone = Tone.Error)
                status?.error?.takeIf { it.isNotEmpty() }?.let { Notice(it, tone = Tone.Warn) }
                if (status?.settings?.enabled == true && !stored) Notice(t("activity.memoryOnly"), tone = Tone.Warn)
                if (status != null && status?.settings?.enabled == false) Notice(t("activity.off"), tone = Tone.Warn)
                status?.dropped?.takeIf { it > 0 }?.let { Notice(t("activity.dropped", "count" to it), tone = Tone.Warn) }
                SubHeading(t("activity.entries"))
                Card {
                    ChoiceRow(t("ops.range"), listOf(1 to "24 h", 7 to "7 d", 30 to "30 d", 90 to "90 d", 365 to "365 d").map { it.first.toString() to it.second }, days.toString(), { days = it.toInt() }, icon = "clock")
                    ChoiceRow(t("activity.user"), listOf("" to t("activity.everyone")) + accounts.map { it.name to it.name }, user, { user = it }, icon = "users", divider = true)
                    CheckRow(t("activity.failedOnly"), failed, { failed = it }, divider = true)
                }
                Field(t("tools.search"), search, { search = it.take(200) }, modifier = Modifier.focusRequester(searchFocus))
                ButtonRow {
                    ActionButton(t("tasks.exportCSV"), { download("csv") }, enabled = !busy && !saver.state.busy, icon = "download")
                    ActionButton(t("tasks.exportJSON"), { download("json") }, enabled = !busy && !saver.state.busy, icon = "download")
                }
                SaveStatus(saver.state)
                if (entries.isEmpty()) {
                    if (busy) Loading(text = t("tools.loading")) else Muted(t("activity.none"))
                }
            }
        }
        itemsIndexed(entries) { _, e -> EntryRow(e) }
        if (more) item { ActionButton(t("activity.older"), { scope.launch { load(true) } }, enabled = !busy) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                status?.let { st ->
                    StatusLine(
                        listOf(
                            t("activity.statEntries", "count" to entries.size) to false,
                            "${t("activity.statFailed")} ${entries.count { !it.ok }}" to entries.any { !it.ok },
                            (t(if (st.settings.enabled) "activity.statOn" else "activity.statOff") + if (st.settings.volume.isNotEmpty()) " · ${st.settings.volume}" else "") to false,
                        ),
                    )
                }
                settings?.let { s ->
                    SettingGroup(t("activity.settings")) {
                        ToggleRow(t("ops.enabled"), s.enabled, { settings = s.copy(enabled = it) }, icon = "clock")
                        ChoiceRow(
                            t("ops.volume"),
                            listOf("" to t("activity.autoVolume")) + storage?.volumes.orEmpty().map { it.name to it.name },
                            s.volume,
                            { settings = s.copy(volume = it) },
                            icon = "disk",
                            divider = true,
                            desc = status?.volume?.takeIf { it.isNotEmpty() }?.let { t("activity.storedOn", "volume" to it) } ?: t("activity.noVolume"),
                        )
                        ChoiceRow(t("ops.days"), listOf("30", "90", "365").map { it to it }, s.days.toString(), { settings = s.copy(days = it.toInt()) }, icon = "refresh", divider = true)
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            NumberField(t("ops.budget"), mibToMB(s.maxMiB), { mb -> settings = s.copy(maxMiB = mbToMiB(mb ?: 0)) }, min = 8, max = 268, suffix = "MB", modifier = Modifier.widthIn(max = 320.dp))
                            ActionButton(t("ops.save"), ::save, primary = true, enabled = !busy && dirty && mibToMB(s.maxMiB) in 8..268)
                            if (saved && !dirty) Muted(t("notify.saved"))
                        }
                    }
                }
            }
        }
    }
}

/** Ein Eintrag: Zeit, Konto (mit Weg, wenn nicht das Web), was getan wurde, Ziel und Ergebnis. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryRow(e: ActivityEntry) {
    val c = Titan.colors
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(formatDateTime(e.at), style = TitanType.small, color = c.muted)
                Text(e.user.ifEmpty { "—" }, style = TitanType.small, color = c.text)
                if (e.via != "web") StatusChip(tKnown("activity.via.${e.via}", e.via), Tone.Neutral)
            }
            Text(tx(activityText(e.action)), style = TitanType.rowTitle, color = if (e.ok) c.text else c.warnInk)
            MonoLine(e.action + (e.ip?.let { "  ·  $it" } ?: ""))
            if (!e.target.isNullOrEmpty()) MonoLine(e.target, color = c.text)
            val login = e.action.startsWith("login_") || e.action.startsWith("ssh_login")
            if (!e.ok) StatusChip(t("activity.failed") + if (!login && e.status != null) " (${e.status})" else "", Tone.Error)
            else Text(t("activity.ok"), style = TitanType.small, color = c.muted)
        }
    }
}
