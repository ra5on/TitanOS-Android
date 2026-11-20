package io.github.ra5on.titanos.agent.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class AgentTab { Chat, Projects, Planned, Notes }

/** Leiste unten als schwebende, abgerundete Fläche: Chat, Projekte, Aufträge, Notizen. */
@Composable
fun AgentNav(selected: AgentTab, onSelect: (AgentTab) -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 2.dp)) {
        Row(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(5.dp)) {
            val items = listOf(
                Triple(AgentTab.Chat, R.string.agent_tab_chat, AgentIcons.chat),
                Triple(AgentTab.Projects, R.string.agent_tab_projects, TitanIcons.folder),
                Triple(AgentTab.Planned, R.string.agent_tab_planned, AgentIcons.clock),
                Triple(AgentTab.Notes, R.string.agent_tab_notes, TitanIcons.edit),
            )
            for ((tab, label, icon) in items) {
                val on = tab == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(21.dp)).background(if (on) c.bg else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(tab) }.semantics { this.selected = on }
                        .heightIn(min = 52.dp).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Icon(icon, null, tint = if (on) c.text else c.muted, modifier = Modifier.size(21.dp))
                    Text(
                        stringResource(label),
                        style = if (on) TitanType.nav.copy(fontWeight = FontWeight.SemiBold) else TitanType.nav,
                        color = if (on) c.text else c.muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Kopf einer Seite der Agent-App: runder Zurück-Knopf oder Titel, rechts Aktionen. */
@Composable
fun AgentPageHead(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    val c = Titan.colors
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) RoundButton(TitanIcons.back, stringResource(io.github.ra5on.titanos.ui.R.string.ui_back), onBack)
        Text(title, style = TitanType.topTitle, color = c.text, modifier = Modifier.weight(1f).padding(start = if (onBack == null) 6.dp else 0.dp))
        action?.invoke()
    }
}

/** Seite mit Kopf, scrollendem Inhalt und der Leiste. */
@Composable
fun AgentPage(
    title: String,
    nav: @Composable () -> Unit,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Titan.colors.bg)) {
        AgentPageHead(title, onBack, action)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) { content() }
        nav()
    }
}

private enum class Day { Pinned, Today, Yesterday, Week, Older }

private fun dayOf(info: ChatInfo, now: LocalDate, zone: ZoneId): Day {
    if (info.pinned) return Day.Pinned
    val d = Format.instant(info.updated)?.atZone(zone)?.toLocalDate() ?: return Day.Older
    return when {
        !d.isBefore(now) -> Day.Today
        d == now.minusDays(1) -> Day.Yesterday
        !d.isBefore(now.minusDays(6)) -> Day.Week
        else -> Day.Older
    }
}

/** Zustand eines Chats in Worten mit Zeichen (nie nur Farbe). */
@Composable
private fun chatStateText(info: ChatInfo): Pair<String, Color>? {
    val c = Titan.colors
    return when {
        info.state == ChatState.RUNNING -> stringResource(R.string.agent_chat_running) to AgentEmber
        info.state == ChatState.WAITING -> stringResource(R.string.agent_chat_waiting) to c.warnInk
        info.state == ChatState.ERROR -> stringResource(R.string.agent_chat_error) to c.dangerInk
        info.unread -> stringResource(R.string.agent_chat_unread) to c.accent
        else -> null
    }
}

@Composable
fun ChatRow(info: ChatInfo, divider: Boolean, onClick: () -> Unit) {
    val c = Titan.colors
    val state = chatStateText(info)
    ListRow(
        info.title.ifBlank { "…" },
        sub = listOfNotNull(info.author, info.preview?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · ").ifEmpty { null },
        divider = divider,
        onClick = onClick,
        right = state?.let { (text, color) ->
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                    Text(text, style = TitanType.small, color = if (color == c.accent) c.text else color)
                }
            }
        },
    )
}

/** Alle Chats mit Suche, nach Tagen gruppiert, angeheftete zuerst (wie die Seitenleiste im Web). */
@Composable
fun ChatListScreen(
    chats: List<ChatInfo>,
    query: String,
    onQuery: (String) -> Unit,
    offline: Boolean,
    cachedAt: String?,
    onOpen: (ChatInfo) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
    nav: @Composable () -> Unit,
    footer: @Composable () -> Unit = {},
    now: LocalDate = LocalDate.now(),
) {
    val zone = ZoneId.systemDefault()
    AgentPage(
        stringResource(R.string.agent_chats),
        nav,
        onBack = onBack,
        action = { RoundButton(AgentIcons.newChat, stringResource(R.string.agent_new_chat), onNew) },
    ) {
        TextInput(query, onQuery, stringResource(R.string.agent_search), leading = TitanIcons.search, placeholder = stringResource(R.string.agent_search))
        if (offline) Box(Modifier.padding(top = 12.dp)) { OfflineBanner(cachedAt) }
        val groups = chats.groupBy { dayOf(it, now, zone) }
        if (chats.isEmpty()) {
            EmptyState("", stringResource(if (query.isBlank()) R.string.agent_no_chats else R.string.agent_search_none), Modifier.padding(top = 40.dp))
        }
        for (day in Day.entries) {
            val list = groups[day] ?: continue
            SectionHeader(
                stringResource(
                    when (day) {
                        Day.Pinned -> R.string.agent_group_pinned
                        Day.Today -> R.string.agent_group_today
                        Day.Yesterday -> R.string.agent_group_yesterday
                        Day.Week -> R.string.agent_group_week
                        Day.Older -> R.string.agent_group_older
                    },
                ),
            )
            Card {
                list.forEachIndexed { i, info -> ChatRow(info, i > 0) { onOpen(info) } }
            }
        }
        footer()
    }
}

@Composable
fun OfflineBanner(cachedAt: String?) {
    val at = cachedAt?.let { Format.instant(it) }?.let { shortDateTime(it) }
    Problem(if (at != null) stringResource(R.string.agent_offline_list, at) else stringResource(R.string.agent_offline_list_plain))
}

internal fun shortDateTime(at: Instant, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(at.atZone(ZoneId.systemDefault()))

/** Projekte mit der Zahl ihrer Chats; „+“ legt eins an. */
@Composable
fun ProjectsScreen(
    projects: List<Project>,
    chats: List<ChatInfo>,
    problem: AgentProblem?,
    creating: Boolean,
    newName: String,
    onNewName: (String) -> Unit,
    onStartCreate: () -> Unit,
    onCreate: () -> Unit,
    onCancelCreate: () -> Unit,
    onOpen: (Project) -> Unit,
    nav: @Composable () -> Unit,
) {
    AgentPage(
        stringResource(R.string.agent_projects_title),
        nav,
        action = { RoundButton(TitanIcons.plus, stringResource(R.string.agent_project_new), onStartCreate) },
    ) {
        problem?.let { Box(Modifier.padding(bottom = 12.dp)) { Problem(problemText(it)) } }
        if (creating) {
            Card(Modifier.padding(bottom = 12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextInput(newName, onNewName, stringResource(R.string.agent_project_name), placeholder = stringResource(R.string.agent_project_name),
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TitanButton(stringResource(io.github.ra5on.titanos.ui.R.string.ui_cancel), onCancelCreate, Modifier.weight(1f))
                        TitanButton(stringResource(R.string.agent_project_create), onCreate, Modifier.weight(1f), ButtonKind.Primary, enabled = newName.isNotBlank())
                    }
                }
            }
        }
        if (projects.isEmpty() && !creating) EmptyState("", stringResource(R.string.agent_projects_empty), Modifier.padding(top = 40.dp))
        if (projects.isNotEmpty()) {
            Card {
                projects.forEachIndexed { i, p ->
                    val n = chats.count { it.project == p.id && !it.archived }
                    val count = pluralStringResource(R.plurals.agent_project_chats, n, n)
                    ListRow(
                        p.name,
                        sub = listOfNotNull(count, p.owner?.let { stringResource(R.string.agent_project_shared, it) }).joinToString(" · "),
                        icon = { ProjectIcon(p) },
                        chevron = true,
                        divider = i > 0,
                        onClick = { onOpen(p) },
                    )
                }
            }
        }
    }
}

@Composable
fun ProjectIcon(p: Project?) {
    val icon = p?.icon?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isLetterOrDigit() || ch == '-' } }
    if (icon != null) Text(icon, style = TitanType.rowTitle) else RowIcon(TitanIcons.folder)
}

/** Ein Projekt: seine Chats und „Neuer Chat in diesem Projekt“. */
@Composable
fun ProjectChatsScreen(project: Project, chats: List<ChatInfo>, onOpen: (ChatInfo) -> Unit, onNew: () -> Unit, onBack: () -> Unit, nav: @Composable () -> Unit) {
    AgentPage(project.name, nav, onBack = onBack) {
        project.instructions?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(bottom = 12.dp))
        }
        TitanButton(stringResource(R.string.agent_project_new_chat), onNew, Modifier.fillMaxWidth(), ButtonKind.Primary, icon = AgentIcons.newChat)
        if (chats.isEmpty()) {
            EmptyState("", stringResource(R.string.agent_project_no_chats), Modifier.padding(top = 24.dp))
        } else {
            SectionHeader(stringResource(R.string.agent_chats))
            Card { chats.forEachIndexed { i, info -> ChatRow(info, i > 0) { onOpen(info) } } }
        }
    }
}

@Composable
private fun everyText(s: Schedule): String {
    val base = when (s.every) {
        "once" -> s.date?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.agent_every_once_at, it) } ?: stringResource(R.string.agent_every_once)
        "daily", "days" -> if ((s.interval ?: 1) > 1) pluralStringResource(R.plurals.agent_every_days, s.interval!!, s.interval!!) else stringResource(R.string.agent_every_daily)
        "weekdays" -> stringResource(R.string.agent_every_weekdays)
        "weekly" -> stringResource(R.string.agent_every_weekly)
        "monthly" -> stringResource(R.string.agent_every_monthly)
        "hourly" -> stringResource(R.string.agent_every_hourly)
        "hours" -> pluralStringResource(R.plurals.agent_every_hours, s.interval ?: 1, s.interval ?: 1)
        "minutes" -> pluralStringResource(R.plurals.agent_every_minutes, s.interval ?: 1, s.interval ?: 1)
        "cron" -> stringResource(R.string.agent_every_cron, s.cron.orEmpty())
        "trigger" -> stringResource(R.string.agent_every_trigger)
        else -> s.every
    }
    val at = s.at?.takeIf { it.isNotBlank() && s.every !in setOf("hourly", "hours", "minutes", "cron", "trigger") }
    return if (at != null) stringResource(R.string.agent_every_at, base, at) else base
}

/** Geplante Aufträge: wann, zuletzt gelaufen (mit Zeichen und Wort), ein/aus, „Jetzt ausführen“. */
@Composable
fun PlannedScreen(
    schedules: List<Schedule>?,
    problem: AgentProblem?,
    started: Boolean,
    onToggle: (Schedule, Boolean) -> Unit,
    onRun: (Schedule) -> Unit,
    nav: @Composable () -> Unit,
) {
    val c = Titan.colors
    AgentPage(stringResource(R.string.agent_planned_title), nav) {
        problem?.let { Box(Modifier.padding(bottom = 12.dp)) { Problem(problemText(it)) } }
        if (started) io.github.ra5on.titanos.ui.components.Note(stringResource(R.string.agent_planned_started), Modifier.padding(bottom = 12.dp))
        when {
            schedules == null -> Text(stringResource(io.github.ra5on.titanos.ui.R.string.ui_loading), style = TitanType.body, color = c.muted)
            schedules.isEmpty() -> EmptyState("", stringResource(R.string.agent_planned_empty), Modifier.padding(top = 40.dp))
            else -> Card {
                schedules.forEachIndexed { i, s ->
                    val kind = stringResource(
                        when (s.kind) {
                            "reminder" -> R.string.agent_planned_kind_reminder
                            "morning" -> R.string.agent_planned_kind_morning
                            else -> R.string.agent_planned_kind_task
                        },
                    )
                    val ran = s.lastRun?.let { Format.instant(it) }?.takeIf { it.atZone(ZoneId.systemDefault()).year >= 2000 }
                    ListRow(
                        s.name.ifBlank { s.task.take(80) },
                        sub = "$kind · ${everyText(s)}",
                        divider = i > 0,
                        right = {
                            Toggle(
                                s.enabled,
                                { onToggle(s, it) },
                                stringResource(R.string.agent_enabled_on),
                                stringResource(R.string.agent_enabled_off),
                            )
                        },
                        bottom = {
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val (icon, color, text) = when {
                                    ran == null -> Triple(AgentIcons.clock, c.muted, stringResource(R.string.agent_planned_never))
                                    s.lastOk == false -> Triple(TitanIcons.warn, c.dangerInk, stringResource(R.string.agent_planned_last_failed, shortDateTime(ran)))
                                    else -> Triple(TitanIcons.check, c.okInk, stringResource(R.string.agent_planned_last_ok, shortDateTime(ran)))
                                }
                                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                                Text(text, style = TitanType.small, color = if (color == c.muted) c.muted else color)
                            }
                            Text(
                                stringResource(R.string.agent_planned_run),
                                style = TitanType.body.copy(fontWeight = FontWeight.SemiBold),
                                color = c.accent,
                                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onRun(s) }
                                    .heightIn(min = 40.dp).padding(vertical = 10.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Notizen: Liste mit Suche; „+“ legt eine neue an. */
@Composable
fun NotesScreen(
    notes: NoteList?,
    query: String,
    onQuery: (String) -> Unit,
    problem: AgentProblem?,
    onOpen: (NoteInfo) -> Unit,
    onNew: () -> Unit,
    nav: @Composable () -> Unit,
) {
    val c = Titan.colors
    AgentPage(
        stringResource(R.string.agent_notes_title),
        nav,
        action = { RoundButton(TitanIcons.plus, stringResource(R.string.agent_notes_new), onNew) },
    ) {
        problem?.let { Box(Modifier.padding(bottom = 12.dp)) { Problem(problemText(it)) } }
        TextInput(query, onQuery, stringResource(R.string.agent_notes_search), leading = TitanIcons.search, placeholder = stringResource(R.string.agent_notes_search))
        val q = query.trim().lowercase()
        val list = notes?.notes.orEmpty()
            .filter { q.isEmpty() || (it.title + " " + it.preview.orEmpty()).lowercase().contains(q) }
            .sortedWith(compareByDescending<NoteInfo> { it.pinned }.thenByDescending { it.modified })
        when {
            notes == null && problem == null -> Text(stringResource(io.github.ra5on.titanos.ui.R.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(top = 16.dp))
            notes != null && notes.notes.isEmpty() -> EmptyState("", stringResource(R.string.agent_notes_empty), Modifier.padding(top = 40.dp))
            list.isNotEmpty() -> Card(Modifier.padding(top = 12.dp)) {
                list.forEachIndexed { i, n ->
                    val where = listOfNotNull(
                        n.folder.takeIf { it.isNotEmpty() },
                        Format.instant(n.modified)?.let { shortDateTime(it) },
                        if (n.pinned) stringResource(R.string.agent_notes_pinned) else null,
                    ).joinToString(" · ")
                    ListRow(
                        n.title.ifBlank { n.path },
                        sub = listOfNotNull(n.preview?.trim()?.takeIf { it.isNotEmpty() }, where.ifEmpty { null }).joinToString("\n"),
                        icon = { RowIcon(if (n.pinned) TitanIcons.pin else TitanIcons.file) },
                        divider = i > 0,
                        onClick = { onOpen(n) },
                    )
                }
            }
        }
    }
}

/** Eine Notiz bearbeiten: Text als Markdown, „Speichern“. */
@Composable
fun NoteEditor(note: NoteFile, saved: Boolean, problem: AgentProblem?, onChange: (String) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    val c = Titan.colors
    val title = note.path.substringAfterLast('/').removeSuffix(".md")
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AgentPageHead(title, onBack) {
            TitanButton(stringResource(R.string.agent_notes_save), onSave, kind = ButtonKind.Primary, enabled = !saved)
        }
        Text(
            stringResource(if (saved) R.string.agent_notes_saved else R.string.agent_notes_unsaved),
            style = TitanType.small,
            color = c.muted,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        problem?.let { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Problem(problemText(it)) } }
        val label = stringResource(R.string.agent_notes_text)
        BasicTextField(
            value = note.content,
            onValueChange = onChange,
            textStyle = AnswerStyle.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(16.dp).semantics { contentDescription = label },
        )
    }
}
