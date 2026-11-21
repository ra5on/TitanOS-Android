package io.github.ra5on.titanos.pim.cal

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.pim.ChoiceRows
import io.github.ra5on.titanos.pim.ColorDot
import io.github.ra5on.titanos.pim.R
import io.github.ra5on.titanos.pim.SettingsGroup
import io.github.ra5on.titanos.pim.ToggleRow
import io.github.ra5on.titanos.pim.hexColor
import io.github.ra5on.titanos.pim.sync.intervalLabel
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import io.github.ra5on.titanos.ui.R as UiR

/** Die festen Farben der Programmquadrate (SQUARES im Web) mit Namen, damit Farbe nie allein steht. */
internal val SQUARES = listOf(
    "#2563eb" to R.string.color_blue, "#7c3aed" to R.string.color_violet, "#0f766e" to R.string.color_teal,
    "#0e7490" to R.string.color_cyan, "#15803d" to R.string.color_green, "#c2410c" to R.string.color_orange,
    "#b45309" to R.string.color_amber, "#dc2626" to R.string.color_red, "#be185d" to R.string.color_pink, "#475569" to R.string.color_slate,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorChoice(selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((hex, name) in SQUARES) {
            val on = hex.equals(selected, true)
            val label = stringResource(name)
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(hexColor(hex)).border(3.dp, if (on) Titan.colors.text else Color.Transparent, CircleShape)
                    .clickable(role = Role.RadioButton) { onPick(hex) }.semantics { contentDescription = label; stateDescription = if (on) "✓" else "" },
                contentAlignment = Alignment.Center,
            ) { if (on) Icon(TitanIcons.check, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** Kalender verwalten (CalendarList.svelte, CalendarDialog.svelte): sichtbar, Name, Farbe, neu, löschen, Import/Export. */
@Composable
internal fun CalendarsPage(model: CalendarModel, onBack: () -> Unit, onSyncSettings: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Calendar?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Calendar?>(null) }
    var problem by remember { mutableStateOf<CalProblem?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var importInto by remember { mutableStateOf<Calendar?>(null) }
    var exportOf by remember { mutableStateOf<Calendar?>(null) }
    var replace by remember { mutableStateOf(false) }
    val imported = stringResource(R.string.cal_imported)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cal = importInto ?: return@rememberLauncherForActivityResult
        importInto = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                val r = model.api.importIcs(cal.id, "import.ics", bytes, replace)
                message = imported.format(r.added, r.updated, r.skipped, r.failed.size)
                model.reload()
            } catch (e: Exception) { problem = problemOf(e) }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val cal = exportOf ?: return@rememberLauncherForActivityResult
        exportOf = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { out -> kotlinx.coroutines.runBlocking { model.api.exportIcs(cal.id, out) } }
                }
            } catch (e: Exception) { problem = problemOf(e) }
        }
    }
    Page(
        topBar = { CalTop(stringResource(R.string.cal_calendars), onBack) { IconButton(TitanIcons.plus, stringResource(R.string.cal_new_calendar), { creating = true }, tint = Titan.colors.accent) } },
        overlay = {
            deleting?.let { cal ->
                ConfirmDialog(
                    stringResource(R.string.cal_delete_calendar_title, cal.name),
                    pluralStringResource(R.plurals.cal_delete_calendar_text, cal.count, cal.count),
                    listOf(
                        DialogAction(stringResource(R.string.cal_delete), danger = true) {
                            deleting = null
                            scope.launch { runCatching { model.api.deleteCalendar(cal.id) }.onFailure { problem = problemOf(it) }; model.reload() }
                        },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { deleting = null },
                    ),
                    onDismiss = { deleting = null },
                )
            }
            message?.let { m -> ConfirmDialog(stringResource(R.string.cal_import), m, listOf(DialogAction(stringResource(UiR.string.ui_close)) { message = null }), onDismiss = { message = null }) }
        },
    ) {
        problem?.let { Problem(problemText(it), Modifier.padding(top = 10.dp)) }
        SectionHeader(stringResource(R.string.cal_calendars))
        Card {
            model.calendars.forEachIndexed { i, cal ->
                ListRow(
                    cal.name.ifBlank { stringResource(R.string.cal_birthdays) },
                    sub = listOfNotNull(
                        if (cal.birthdays) stringResource(R.string.cal_birthdays_sub) else pluralStringResource(R.plurals.cal_n_events, cal.count, cal.count),
                        if (cal.readOnly) stringResource(R.string.cal_read_only) else null,
                        stringResource(if (cal.hidden) R.string.cal_hidden else R.string.cal_shown),
                    ).joinToString(" · "),
                    divider = i > 0,
                    icon = { ColorDot(hexColor(cal.color), 16.dp) },
                    onClick = { model.setHidden(cal.id, !cal.hidden) },
                    right = {
                        io.github.ra5on.titanos.ui.components.Toggle(!cal.hidden, { model.setHidden(cal.id, !it) }, stringResource(R.string.cal_shown), stringResource(R.string.cal_hidden))
                        if (!cal.birthdays && !cal.readOnly) IconButton(TitanIcons.edit, stringResource(R.string.cal_edit_calendar, cal.name), { editing = cal })
                    },
                )
            }
        }
        for (cal in model.writable) {
            SettingsGroup(cal.name, stringResource(R.string.cal_more_for), key = "more-" + cal.id) {
                ListRow(stringResource(R.string.cal_export), icon = { RowIcon(TitanIcons.download) }, onClick = { exportOf = cal; save.launch("${cal.name.ifBlank { cal.id }}.ics") })
                ListRow(stringResource(R.string.cal_import_skip), divider = true, icon = { RowIcon(TitanIcons.upload) }, onClick = { replace = false; importInto = cal; pick.launch(arrayOf("text/calendar", "text/x-vcalendar", "*/*")) })
                ListRow(stringResource(R.string.cal_import_replace), divider = true, icon = { RowIcon(TitanIcons.upload) }, onClick = { replace = true; importInto = cal; pick.launch(arrayOf("text/calendar", "text/x-vcalendar", "*/*")) })
                ListRow(stringResource(R.string.cal_delete_calendar), divider = true, titleColor = Titan.colors.dangerInk,
                    icon = { RowIcon(TitanIcons.trash) }, onClick = { deleting = cal })
            }
        }
        SectionHeader(stringResource(R.string.cal_hints))
        Card {
            ToggleRow(stringResource(R.string.cal_hints_on), model.hintsOn, { model.setReminderPrefs(it, model.pushOn) }, sub = stringResource(R.string.cal_hints_on_sub))
            ToggleRow(stringResource(R.string.cal_push_on), model.pushOn, { model.setReminderPrefs(model.hintsOn, it) }, sub = stringResource(R.string.cal_push_on_sub), divider = true)
        }
        SectionHeader(stringResource(R.string.cal_phone))
        Card { ListRow(stringResource(io.github.ra5on.titanos.pim.R.string.sync_title), sub = stringResource(R.string.cal_phone_sub), chevron = true, icon = { RowIcon(TitanIcons.sync) }, onClick = onSyncSettings) }
        Box(Modifier.navigationBarsPadding())
    }
    if (creating || editing != null) {
        CalendarDialog(editing, onDone = { name, color ->
            val e = editing
            creating = false; editing = null
            if (name != null) scope.launch {
                runCatching { if (e == null) model.api.createCalendar(name, color) else model.api.updateCalendar(e.id, name, color) }.onFailure { problem = problemOf(it) }
                model.reload()
            }
        })
    }
}

@Composable
private fun CalendarDialog(cal: Calendar?, onDone: (String?, String) -> Unit) {
    var name by remember { mutableStateOf(cal?.name.orEmpty()) }
    var color by remember { mutableStateOf(cal?.color ?: SQUARES[1].first) }
    androidx.compose.ui.window.Dialog(onDismissRequest = { onDone(null, color) }) {
        Column(Modifier.clip(RoundedCornerShape(22.dp)).background(Titan.colors.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (cal == null) R.string.cal_new_calendar else R.string.cal_edit_calendar, cal?.name.orEmpty()), style = TitanType.title, color = Titan.colors.text)
            TextInput(name, { name = it }, stringResource(R.string.cal_calendar_name), placeholder = stringResource(R.string.cal_calendar_name))
            ColorChoice(color) { color = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TitanButton(stringResource(UiR.string.ui_cancel), { onDone(null, color) })
                TitanButton(stringResource(R.string.cal_save), { if (name.isNotBlank()) onDone(name.trim(), color) }, kind = ButtonKind.Primary, enabled = name.isNotBlank())
            }
        }
    }
}

// ---- Erinnerungen ----

/** Kleiner Eintrag einer fälligen Erinnerung (TaskChip.svelte). */
@Composable
internal fun TaskChip(model: CalendarModel, t: Task, onClick: () -> Unit) {
    val c = Titan.colors
    val color = hexColor(model.taskLists.firstOrNull { it.id == t.list }?.color)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).border(1.dp, color, RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp, color, CircleShape))
        Text(t.title, style = TitanType.small, color = c.text)
    }
}

@Composable
internal fun TaskRow(model: CalendarModel, t: Task, onClick: () -> Unit, onToggle: (() -> Unit)? = null) {
    val c = Titan.colors
    val list = model.taskLists.firstOrNull { it.id == t.list }
    val color = hexColor(list?.color)
    val said = stringResource(if (t.done) R.string.cal_task_done else R.string.cal_task_open)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (t.done) color else Color.Transparent).border(2.dp, color, CircleShape)
                .then(if (onToggle != null) Modifier.clickable(role = Role.Checkbox, onClick = onToggle) else Modifier).semantics { stateDescription = said },
            contentAlignment = Alignment.Center,
        ) { if (t.done) Icon(TitanIcons.check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(t.title.ifBlank { stringResource(R.string.cal_no_title) }, style = TitanType.rowTitle.copy(textDecoration = if (t.done) TextDecoration.LineThrough else null), color = if (t.done) c.muted else c.text)
            val due = model.dueDay(t)?.let { d ->
                if (t.dueAllDay || t.due.length == 10) CalDates.mediumDate(d) else "${CalDates.mediumDate(d)}, ${CalDates.time(CalDates.parse(t.due, false, model.zone))}"
            }
            val sub = listOfNotNull(due, priorityText(t.priority), if (t.recurring) stringResource(R.string.cal_recurring) else null, list?.name).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = TitanType.rowSub, color = c.muted)
        }
        if (t.priority in 1..4) Text("!!!", style = TitanType.rowTitle, color = c.dangerInk)
    }
}

@Composable
private fun priorityText(p: Int): String? = when (p) {
    in 1..4 -> stringResource(R.string.cal_prio_high)
    5 -> stringResource(R.string.cal_prio_medium)
    in 6..9 -> stringResource(R.string.cal_prio_low)
    else -> null
}

/** Erinnerungen (TaskSide.svelte): Listen, offene/erledigte, abhaken, neu, Listen verwalten. */
@Composable
internal fun TasksPage(model: CalendarModel, listId: String?, onBack: () -> Unit, onEdit: (Task?, String) -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(listId ?: model.taskLists.firstOrNull()?.id) }
    var tasks by remember { mutableStateOf<List<Task>?>(null) }
    var problem by remember { mutableStateOf<CalProblem?>(null) }
    var showDone by remember { mutableStateOf(model.prefs.showDone) }
    var quick by remember { mutableStateOf("") }
    var editList by remember { mutableStateOf<TaskList?>(null) }
    var newList by remember { mutableStateOf(false) }
    var deleteList by remember { mutableStateOf<TaskList?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(model.taskLists) { if (current == null) current = model.taskLists.firstOrNull()?.id }
    LaunchedEffect(current, showDone, reloadKey) {
        val id = current ?: return@LaunchedEffect
        runCatching { model.api.tasks(id, if (showDone) "all" else "open") }.onSuccess { tasks = it; problem = null }.onFailure { problem = problemOf(it) }
    }
    val list = model.taskLists.firstOrNull { it.id == current }
    Page(
        topBar = { CalTop(list?.name ?: stringResource(R.string.cal_tasks), onBack) { current?.let { c -> IconButton(TitanIcons.plus, stringResource(R.string.cal_new_task), { onEdit(null, c) }, tint = Titan.colors.accent) } } },
        overlay = {
            deleteList?.let { l ->
                ConfirmDialog(stringResource(R.string.cal_delete_list_title, l.name), pluralStringResource(R.plurals.cal_delete_list_text, l.count, l.count),
                    listOf(DialogAction(stringResource(R.string.cal_delete), danger = true) {
                        deleteList = null
                        scope.launch { runCatching { model.api.deleteTaskList(l.id) }.onFailure { problem = problemOf(it) }; current = null; model.reload() }
                    }, DialogAction(stringResource(UiR.string.ui_cancel)) { deleteList = null }), onDismiss = { deleteList = null })
            }
        },
    ) {
        problem?.let { Problem(problemText(it), Modifier.padding(top = 10.dp)) }
        SectionHeader(stringResource(R.string.cal_task_lists))
        Card {
            model.taskLists.forEachIndexed { i, l ->
                ListRow(l.name, sub = stringResource(R.string.cal_list_counts, l.open, l.count), divider = i > 0, icon = { ColorDot(hexColor(l.color), 14.dp) },
                    onClick = { current = l.id }, right = {
                        RadioMark(l.id == current)
                        if (!l.virtual) IconButton(TitanIcons.edit, stringResource(R.string.cal_edit_list, l.name), { editList = l })
                    })
            }
            ListRow(stringResource(R.string.cal_new_list), divider = model.taskLists.isNotEmpty(), icon = { RowIcon(TitanIcons.plus, tint = Titan.colors.accent) }, onClick = { newList = true })
            list?.takeIf { !it.virtual }?.let { l ->
                ListRow(stringResource(R.string.cal_delete_list, l.name), divider = true, titleColor = Titan.colors.dangerInk, icon = { RowIcon(TitanIcons.trash, tint = Titan.colors.dangerInk) }, onClick = { deleteList = l })
            }
        }
        if (list != null) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextInput(quick, { quick = it }, stringResource(R.string.cal_quick_task), Modifier.weight(1f), placeholder = stringResource(R.string.cal_quick_task))
                TitanButton(stringResource(R.string.cal_add), {
                    val title = quick.trim()
                    if (title.isNotEmpty()) scope.launch {
                        runCatching { model.api.createTask(TaskInput(list = list.id, title = title, lang = model.lang2())) }.onFailure { problem = problemOf(it) }
                        quick = ""; reloadKey++; model.reload()
                    }
                }, kind = ButtonKind.Primary, enabled = quick.isNotBlank())
            }
            ToggleRow(stringResource(R.string.cal_show_done), showDone, { showDone = it })
            val items = tasks
            when {
                items == null -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(16.dp))
                items.isEmpty() -> EmptyState(stringResource(R.string.cal_tasks_empty), stringResource(R.string.cal_tasks_empty_text))
                else -> Card {
                    for (t in items) TaskRow(model, t, onClick = { onEdit(t, t.list) }, onToggle = {
                        scope.launch {
                            runCatching { model.api.setDone(t.list, t.id, t.etag, !t.done, model.lang2()) }.onFailure { problem = problemOf(it) }
                            reloadKey++; model.reload()
                        }
                    })
                }
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
    if (newList || editList != null) {
        val e = editList
        CalendarDialog(e?.let { Calendar(it.id, it.name, it.color) }, onDone = { name, color ->
            newList = false; editList = null
            if (name != null) scope.launch {
                runCatching { if (e == null) model.api.createTaskList(name, color) else model.api.updateTaskList(e.id, name, color) }.onFailure { problem = problemOf(it) }
                model.reload()
            }
        })
    }
}

/** Erinnerung anlegen/bearbeiten (TaskDialog.svelte): Titel, Notizen, fällig am (mit Uhrzeit), Priorität, Hinweise, Liste. */
@Composable
internal fun TaskEditor(model: CalendarModel, task: Task?, listId: String, due: LocalDate?, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val scope = rememberCoroutineScope()
    var full by remember { mutableStateOf(task) }
    LaunchedEffect(task?.id) { if (task != null) runCatching { model.api.task(task.list, task.id) }.onSuccess { full = it } }
    val z = model.zone
    val init = remember(full?.etag) { full }
    var title by remember(init) { mutableStateOf(init?.title.orEmpty()) }
    var notes by remember(init) { mutableStateOf(init?.notes.orEmpty()) }
    var list by remember(init) { mutableStateOf(init?.list ?: listId) }
    var priority by remember(init) { mutableStateOf(init?.priority ?: 0) }
    val initDue = init?.let { t -> model.dueDay(t) } ?: due
    var hasDue by remember(init) { mutableStateOf(initDue != null) }
    var dueDate by remember(init) { mutableStateOf(initDue ?: CalDates.today(z)) }
    var hasTime by remember(init) { mutableStateOf(init?.let { it.due.length > 10 && !it.dueAllDay } ?: false) }
    var dueTime by remember(init) { mutableStateOf(init?.takeIf { it.due.length > 10 }?.let { CalDates.parse(it.due, false, z).toLocalTime() } ?: LocalTime.of(9, 0)) }
    var alarm by remember(init) { mutableStateOf(init?.alarm ?: false) }
    var problem by remember { mutableStateOf<CalProblem?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun save() {
        if (title.isBlank()) return
        val dueText = when {
            !hasDue -> ""
            hasTime -> CalDates.wall(LocalDateTime.of(dueDate, dueTime))
            else -> dueDate.toString()
        }
        val alarms = if (alarm && hasDue) listOf(if (hasTime) CalDates.wall(LocalDateTime.of(dueDate, dueTime)) else CalDates.wall(LocalDateTime.of(dueDate, LocalTime.of(9, 0)))) else emptyList()
        val input = TaskInput(list = list, title = title, notes = notes, due = dueText.ifEmpty { null }, timezone = if (hasTime) z.id else null, priority = priority, alarms = alarms, lang = model.lang2())
        scope.launch {
            try {
                val t = full
                if (t == null) model.api.createTask(input) else model.api.updateTask(t.list, t.id, t.etag, input)
                onDone()
            } catch (e: Exception) { problem = problemOf(e) }
        }
    }
    Page(
        topBar = {
            CalTop(stringResource(if (task == null) R.string.cal_new_task else R.string.cal_edit_task), onDone, backLabel = stringResource(UiR.string.ui_cancel)) {
                Text(stringResource(R.string.cal_done), style = TitanType.button, color = Titan.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { save() }.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        },
        overlay = {
            if (confirmDelete) full?.let { t ->
                ConfirmDialog(stringResource(R.string.cal_delete_task_title, t.title), null, listOf(
                    DialogAction(stringResource(R.string.cal_delete), danger = true) {
                        confirmDelete = false
                        scope.launch { runCatching { model.api.deleteTask(t.list, t.id, t.etag) }.onSuccess { onDone() }.onFailure { problem = problemOf(it) } }
                    },
                    DialogAction(stringResource(UiR.string.ui_cancel)) { confirmDelete = false },
                ), onDismiss = { confirmDelete = false })
            }
        },
    ) {
        problem?.let { Problem(problemText(it), Modifier.padding(top = 10.dp)) }
        SectionHeader(stringResource(R.string.cal_what))
        TextInput(title, { title = it }, stringResource(R.string.cal_title_field), placeholder = stringResource(R.string.cal_title_field))
        TextInput(notes, { notes = it }, stringResource(R.string.cal_notes), Modifier.padding(top = 8.dp).heightIn(min = 96.dp), placeholder = stringResource(R.string.cal_notes))
        SectionHeader(stringResource(R.string.cal_when))
        Card {
            ToggleRow(stringResource(R.string.cal_due), hasDue, { hasDue = it })
            if (hasDue) {
                ListRow(stringResource(R.string.cal_date), divider = true, right = { io.github.ra5on.titanos.ui.components.Chip(CalDates.mediumDate(dueDate), onClick = { picker = "date" }) })
                ToggleRow(stringResource(R.string.cal_with_time), hasTime, { hasTime = it }, divider = true)
                if (hasTime) ListRow(stringResource(R.string.cal_time), divider = true, right = { io.github.ra5on.titanos.ui.components.Chip(CalDates.time(dueTime), onClick = { picker = "time" }) })
                ToggleRow(stringResource(R.string.cal_task_alarm), alarm, { alarm = it }, sub = stringResource(R.string.cal_task_alarm_sub), divider = true)
            }
        }
        SectionHeader(stringResource(R.string.cal_priority))
        Card {
            ChoiceRows(listOf(0 to stringResource(R.string.cal_prio_none), 9 to stringResource(R.string.cal_prio_low), 5 to stringResource(R.string.cal_prio_medium), 1 to stringResource(R.string.cal_prio_high)),
                if (priority in 1..4) 1 else if (priority in 6..9) 9 else priority, { priority = it })
        }
        SectionHeader(stringResource(R.string.cal_task_list))
        Card {
            ChoiceRows(model.taskLists.filter { !it.virtual || it.id == list }.map { it.id to it.name }, list, { list = it })
        }
        if (full?.recurring == true) Note(stringResource(R.string.cal_task_recurring), Modifier.padding(top = 10.dp))
        TitanButton(stringResource(R.string.cal_save), { save() }, Modifier.fillMaxWidth().padding(top = 16.dp), kind = ButtonKind.Primary, enabled = title.isNotBlank())
        if (full != null) TitanButton(stringResource(R.string.cal_delete), { confirmDelete = true }, Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Danger, icon = TitanIcons.trash)
        Box(Modifier.navigationBarsPadding())
    }
    when (picker) {
        "date" -> DatePick(dueDate, { dueDate = it }) { picker = null }
        "time" -> TimePick(dueTime, { dueTime = it }) { picker = null }
    }
}

// ---- Einstellungen ----

/** Alle Einstellungen des Kalenders in aufklappbaren Gruppen; nichts ist fest. */
@Composable
internal fun CalendarSettingsPage(model: CalendarModel, onBack: () -> Unit, onSyncSettings: () -> Unit) {
    BackHandler(onBack = onBack)
    val p = model.prefs
    fun set(v: CalPrefs) = model.updatePrefs(v)
    Page(topBar = { CalTop(stringResource(R.string.cal_settings), onBack) }) {
        val views = listOf(CalView.Day to stringResource(R.string.cal_view_day), CalView.Week to stringResource(R.string.cal_view_week),
            CalView.Month to stringResource(R.string.cal_view_month), CalView.Year to stringResource(R.string.cal_view_year), CalView.List to stringResource(R.string.cal_view_list))
        SettingsGroup(stringResource(R.string.cal_set_view), views.first { it.first == p.view }.second, initiallyOpen = true) {
            ChoiceRows(views, p.view, { set(p.copy(view = it)); model.view = it })
        }
        val starts = listOf("system" to stringResource(R.string.cal_week_system), "monday" to stringResource(R.string.cal_week_monday),
            "sunday" to stringResource(R.string.cal_week_sunday), "saturday" to stringResource(R.string.cal_week_saturday))
        SettingsGroup(stringResource(R.string.cal_set_week), starts.first { it.first == p.weekStart }.second) {
            ChoiceRows(starts, p.weekStart, { set(p.copy(weekStart = it)) })
            ToggleRow(stringResource(R.string.cal_week_numbers), p.weekNumbers, { set(p.copy(weekNumbers = it)) }, divider = true)
        }
        SettingsGroup(stringResource(R.string.cal_set_zone), if (p.zone == "nas") stringResource(R.string.cal_zone_nas, model.nasZone.ifBlank { "–" }) else stringResource(R.string.cal_zone_device, java.time.ZoneId.systemDefault().id)) {
            ChoiceRows(listOf("nas" to stringResource(R.string.cal_zone_nas, model.nasZone.ifBlank { "–" }), "device" to stringResource(R.string.cal_zone_device, java.time.ZoneId.systemDefault().id)),
                p.zone, { set(p.copy(zone = it)); model.reload() })
        }
        SettingsGroup(stringResource(R.string.cal_set_display), null) {
            ToggleRow(stringResource(R.string.cal_month_titles), p.monthTitles, { set(p.copy(monthTitles = it)) }, sub = stringResource(R.string.cal_month_titles_sub))
            ToggleRow(stringResource(R.string.cal_show_tasks), p.showTasks, { set(p.copy(showTasks = it)); model.reload() }, divider = true)
            ToggleRow(stringResource(R.string.cal_show_done_default), p.showDone, { set(p.copy(showDone = it)) }, divider = true)
            ToggleRow(stringResource(R.string.cal_motion), p.motion, { set(p.copy(motion = it)) }, sub = stringResource(R.string.cal_motion_sub), divider = true)
        }
        SettingsGroup(stringResource(R.string.cal_set_day_start), CalDates.time(LocalTime.of(p.dayStartHour, 0))) {
            ChoiceRows((0..12).map { it to CalDates.time(LocalTime.of(it, 0)) }, p.dayStartHour, { set(p.copy(dayStartHour = it)) })
        }
        val heights = listOf(40 to stringResource(R.string.cal_hour_compact), 56 to stringResource(R.string.cal_hour_normal), 80 to stringResource(R.string.cal_hour_large), 110 to stringResource(R.string.cal_hour_xl))
        SettingsGroup(stringResource(R.string.cal_set_hour), heights.firstOrNull { it.first == p.hourHeight }?.second) {
            ChoiceRows(heights, p.hourHeight, { set(p.copy(hourHeight = it)) })
        }
        val durations = listOf(15, 30, 45, 60, 90, 120, 180)
        SettingsGroup(stringResource(R.string.cal_set_duration), pluralStringResource(R.plurals.cal_minutes, p.defaultDuration, p.defaultDuration)) {
            ChoiceRows(durations.map { it to pluralStringResource(R.plurals.cal_minutes, it, it) }, p.defaultDuration, { set(p.copy(defaultDuration = it)) })
        }
        val alarms = listOf(-1, 0, 5, 10, 15, 30, 60, 120, 1440)
        SettingsGroup(stringResource(R.string.cal_set_alarm), if (p.defaultAlarm < 0) stringResource(R.string.cal_alarm_none) else alarmText(p.defaultAlarm, false)) {
            ChoiceRows(alarms.map { it to if (it < 0) stringResource(R.string.cal_alarm_none) else alarmText(it, false) }, p.defaultAlarm, { set(p.copy(defaultAlarm = it)) })
        }
        val allDayAlarms = listOf(-1, -540, 900, 2340, 9540)
        SettingsGroup(stringResource(R.string.cal_set_alarm_allday), if (p.defaultAlarmAllDay == -1) stringResource(R.string.cal_alarm_none) else alarmText(p.defaultAlarmAllDay, true)) {
            ChoiceRows(allDayAlarms.map { it to if (it == -1) stringResource(R.string.cal_alarm_none) else alarmText(it, true) }, p.defaultAlarmAllDay, { set(p.copy(defaultAlarmAllDay = it)) })
        }
        SettingsGroup(stringResource(R.string.cal_set_default_calendar), model.defaultCalendar?.name) {
            ChoiceRows(model.writable.map { it.id to it.name }, model.defaultCalendar?.id.orEmpty(), { set(p.copy(defaultCalendar = it)) })
        }
        val listDays = listOf(7, 14, 30, 60, 90, 180, 365)
        SettingsGroup(stringResource(R.string.cal_set_list_days), pluralStringResource(R.plurals.cal_days, p.listDays, p.listDays)) {
            ChoiceRows(listDays.map { it to pluralStringResource(R.plurals.cal_days, it, it) }, p.listDays, { set(p.copy(listDays = it)) })
        }
        SettingsGroup(stringResource(R.string.cal_hints), stringResource(if (model.hintsOn) UiR.string.ui_on else UiR.string.ui_off)) {
            ToggleRow(stringResource(R.string.cal_hints_on), model.hintsOn, { model.setReminderPrefs(it, model.pushOn) }, sub = stringResource(R.string.cal_hints_on_sub))
            ToggleRow(stringResource(R.string.cal_push_on), model.pushOn, { model.setReminderPrefs(model.hintsOn, it) }, sub = stringResource(R.string.cal_push_on_sub), divider = true)
            ToggleRow(stringResource(R.string.cal_confirm_delete), p.confirmDelete, { set(p.copy(confirmDelete = it)) }, divider = true)
        }
        SectionHeader(stringResource(R.string.cal_phone))
        Card { ListRow(stringResource(io.github.ra5on.titanos.pim.R.string.sync_title), sub = stringResource(R.string.cal_phone_sub), chevron = true, icon = { RowIcon(TitanIcons.sync) }, onClick = onSyncSettings) }
        Box(Modifier.navigationBarsPadding())
    }
}
