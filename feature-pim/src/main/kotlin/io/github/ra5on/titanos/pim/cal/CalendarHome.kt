package io.github.ra5on.titanos.pim.cal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.pim.ColorDot
import io.github.ra5on.titanos.pim.R
import io.github.ra5on.titanos.pim.Segmented
import io.github.ra5on.titanos.pim.hexColor
import io.github.ra5on.titanos.pim.pimChanges
import io.github.ra5on.titanos.ui.Width
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import io.github.ra5on.titanos.ui.R as UiR

/** Wohin der Kalender gerade zeigt. */
sealed interface CalRoute {
    data object Home : CalRoute
    data class Detail(val event: CalEvent) : CalRoute
    data class Edit(val event: CalEvent?, val day: LocalDate? = null, val time: LocalTime? = null) : CalRoute
    data object Calendars : CalRoute
    data class Tasks(val list: String? = null) : CalRoute
    data class TaskEdit(val task: Task?, val list: String, val due: LocalDate? = null) : CalRoute
    data object Settings : CalRoute
    data object Search : CalRoute
}

/** Kalender wie im Web am Handy (web/src/routes/calendar, lib/pim/cal), am großen Bildschirm wie am PC. */
@Composable
fun CalendarScreen(
    nas: NasConnection,
    stream: NasConnection,
    onClose: () -> Unit,
    onSyncSettings: () -> Unit,
    startRoute: CalRoute = CalRoute.Home,
    startView: CalView? = null,
    startAnchor: LocalDate? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(nas) {
        CalendarModel(CalendarApi(nas), scope, CalPrefsStore(context)).also { m ->
            startView?.let { m.view = it }
            startAnchor?.let { m.anchor = it }
        }
    }
    LaunchedEffect(model) { model.start() }
    // Live: jede Änderung am NAS (Web, iPhone, Abgleich) lädt neu, gebündelt.
    LaunchedEffect(stream) {
        var last = 0L
        pimChanges(stream).collect { kind ->
            if (kind == "calendar" || kind == "tasks" || kind == "contacts" || kind == "resync") {
                val now = System.currentTimeMillis()
                if (now - last > 800) { delay(400); model.reload() }
                last = now
            }
        }
    }
    var route by remember { mutableStateOf(startRoute) }
    val go: (CalRoute) -> Unit = { route = it }
    // Agent-Panel wie im Web: der Tag und ein offener Termin gehen als Bezug mit.
    val agent = remember(nas) { io.github.ra5on.titanos.agent.chat.AgentApi(nas, stream) }
    val ev = (route as? CalRoute.Detail)?.event?.takeIf { it.kind == "event" }
    val agentContext = io.github.ra5on.titanos.agent.panel.PanelContext.calendar(model.anchor.toString(), ev?.calendar, ev?.id, ev?.occurrence?.ifEmpty { null })
    io.github.ra5on.titanos.agent.panel.AgentPanelFrame(agent, "calendar", agentContext) { CalendarRoute(model, route, go, onClose, onSyncSettings) }
}

@Composable
private fun CalendarRoute(model: CalendarModel, route: CalRoute, go: (CalRoute) -> Unit, onClose: () -> Unit, onSyncSettings: () -> Unit) {
    when (val r = route) {
        CalRoute.Home -> CalendarHome(model, go, onClose)
        is CalRoute.Detail -> EventDetail(model, r.event, onBack = { go(CalRoute.Home) }, onEdit = { go(CalRoute.Edit(it)) })
        is CalRoute.Edit -> EventEditor(model, r.event, r.day, r.time, onDone = { saved -> if (saved) model.reload(); go(CalRoute.Home) })
        CalRoute.Calendars -> CalendarsPage(model, onBack = { go(CalRoute.Home) }, onSyncSettings = onSyncSettings)
        is CalRoute.Tasks -> TasksPage(model, r.list, onBack = { go(CalRoute.Home) }, onEdit = { t, list -> go(CalRoute.TaskEdit(t, list)) })
        is CalRoute.TaskEdit -> TaskEditor(model, r.task, r.list, r.due, onDone = { model.reload(); go(CalRoute.Tasks(r.list)) })
        CalRoute.Settings -> CalendarSettingsPage(model, onBack = { go(CalRoute.Home) }, onSyncSettings = onSyncSettings)
        CalRoute.Search -> SearchPage(model, onBack = { go(CalRoute.Home) }, onOpen = { go(CalRoute.Detail(it)) })
    }
}

/** Kopfzeile mit Rand oben für die Statusleiste. */
@Composable
internal fun CalTop(title: String, onBack: (() -> Unit)?, backLabel: String = stringResource(UiR.string.ui_back), actions: @Composable () -> Unit = {}) {
    Box(Modifier.statusBarsPadding()) {
        io.github.ra5on.titanos.ui.components.TopBar(title, onBack = onBack, backLabel = backLabel) { actions() }
    }
}

@Composable
private fun CalendarHome(model: CalendarModel, go: (CalRoute) -> Unit, onClose: () -> Unit) {
    val width = currentWidth()
    val c = Titan.colors
    val zone = model.zone
    val today = CalDates.today(zone)
    BackHandler {
        when (model.view) {
            CalView.Day, CalView.Week -> if (width == Width.Phone) model.view = CalView.Month else onClose()
            else -> onClose()
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun step(sign: Long) {
        model.anchor = when (model.view) {
            CalView.Day -> model.anchor.plusDays(sign)
            CalView.Week -> model.anchor.plusWeeks(sign)
            CalView.Month, CalView.List -> model.anchor.plusMonths(sign)
            CalView.Year -> model.anchor.plusYears(sign)
        }
        model.ensure(model.anchor)
    }
    fun newEvent() = go(CalRoute.Edit(null, if (model.anchor == today) null else model.anchor))
    Column(
        Modifier.fillMaxSize().background(c.bg).focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Tastenkürzel wie im Web: Heute, Ansichten, Neu, Blättern, Suche.
                when {
                    e.key == Key.T -> { model.anchor = today; model.ensure(today); true }
                    e.key == Key.D -> { model.view = CalView.Day; true }
                    e.key == Key.W -> { model.view = CalView.Week; true }
                    e.key == Key.M -> { model.view = CalView.Month; true }
                    e.key == Key.Y -> { model.view = CalView.Year; true }
                    e.key == Key.L -> { model.view = CalView.List; true }
                    e.key == Key.N || e.key == Key.C -> { newEvent(); true }
                    e.key == Key.DirectionLeft || e.key == Key.J -> { step(-1); true }
                    e.key == Key.DirectionRight || e.key == Key.K -> { step(1); true }
                    e.key == Key.Slash || (e.isCtrlPressed && e.key == Key.F) -> { go(CalRoute.Search); true }
                    else -> false
                }
            },
    ) {
        Box(Modifier.statusBarsPadding())
        HomeBar(model, width, go, onClose, onPrev = { step(-1) }, onNext = { step(1) }, onNew = ::newEvent)
        model.problem?.let { Problem(problemText(it), Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (width.sidebar) {
                Sidebar(model, go, Modifier.width(280.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (model.view) {
                    CalView.Month -> MonthView(model, width, onDay = { d ->
                        model.anchor = d
                        if (width == Width.Phone) model.view = CalView.Day
                    }, onEvent = { go(CalRoute.Detail(it)) }, onNewAt = { d -> go(CalRoute.Edit(null, d)) })
                    CalView.Year -> YearView(model, width) { m -> model.anchor = m.atDay(1); model.view = CalView.Month; model.ensure(model.anchor) }
                    CalView.Week -> WeekView(model, width, go)
                    CalView.Day -> DayView(model, go)
                    CalView.List -> AgendaView(model, onEvent = { go(CalRoute.Detail(it)) }, onTask = { go(CalRoute.TaskEdit(it, it.list)) })
                }
            }
        }
        BottomBar(model, width, go, today)
    }
}

@Composable
private fun HomeBar(model: CalendarModel, width: Width, go: (CalRoute) -> Unit, onClose: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onNew: () -> Unit) {
    val c = Titan.colors
    val a = model.anchor
    // Zurück wie am iPhone: Tag → Monat, Monat → Jahr, Jahr → Startseite.
    val upLabel = when (model.view) {
        CalView.Day, CalView.Week -> CalDates.monthName(YearMonth.from(a))
        CalView.Month, CalView.List -> a.year.toString()
        CalView.Year -> stringResource(UiR.string.ui_nav_home)
    }
    val up: () -> Unit = {
        when (model.view) {
            CalView.Day, CalView.Week -> model.view = CalView.Month
            CalView.Month, CalView.List -> model.view = CalView.Year
            CalView.Year -> onClose()
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = up).heightIn(min = 44.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TitanIcons.back, null, tint = c.accent, modifier = Modifier.size(22.dp))
                Text(upLabel, style = TitanType.rowTitle, color = c.accent)
            }
            Spacer(Modifier.weight(1f))
            if (width.twoPanes) {
                IconButton(TitanIcons.back, stringResource(R.string.cal_prev), onPrev)
                IconButton(TitanIcons.chev, stringResource(R.string.cal_next), onNext)
            }
            IconButton(listIcon, stringResource(if (model.view == CalView.List) R.string.cal_view_month else R.string.cal_view_list), {
                model.view = if (model.view == CalView.List) CalView.Month else CalView.List
            })
            IconButton(TitanIcons.search, stringResource(UiR.string.ui_search), { go(CalRoute.Search) })
            IconButton(TitanIcons.plus, stringResource(R.string.cal_new_event), onNew, tint = c.accent)
        }
        if (width.twoPanes || model.view != CalView.List) {
            val views = listOf(
                CalView.Day.name to stringResource(R.string.cal_view_day), CalView.Week.name to stringResource(R.string.cal_view_week),
                CalView.Month.name to stringResource(R.string.cal_view_month), CalView.Year.name to stringResource(R.string.cal_view_year),
            )
            Segmented(views, model.view.name, { model.view = CalView.valueOf(it) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (model.view != CalView.Month && model.view != CalView.Year) Text(
            title(model),
            style = TitanType.big,
            color = c.text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() },
        )
    }
}

internal val listIcon by lazy { io.github.ra5on.titanos.pim.PimIcons.list }

@Composable
private fun title(model: CalendarModel): String {
    val a = model.anchor
    return when (model.view) {
        CalView.Day -> CalDates.longDate(a)
        CalView.Week -> {
            val s = CalDates.weekStart(a, model.firstDay)
            val e = s.plusDays(6)
            stringResource(R.string.cal_week_title, CalDates.weekNumber(s), CalDates.mediumDate(s), CalDates.mediumDate(e))
        }
        CalView.Month -> "${CalDates.monthName(YearMonth.from(a))} ${a.year}"
        CalView.Year -> a.year.toString()
        CalView.List -> stringResource(R.string.cal_upcoming)
    }
}

@Composable
private fun BottomBar(model: CalendarModel, width: Width, go: (CalRoute) -> Unit, today: LocalDate) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth().background(c.header)) {
        Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.cal_today),
                style = TitanType.button,
                color = c.accent,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { model.anchor = today; model.ensure(today) }
                    .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 11.dp),
            )
            Spacer(Modifier.weight(1f))
            if (!width.sidebar) {
                IconButton(TitanIcons.cal, stringResource(R.string.cal_calendars), { go(CalRoute.Calendars) })
                IconButton(TitanIcons.check, stringResource(R.string.cal_tasks), { go(CalRoute.Tasks()) })
            }
            IconButton(TitanIcons.gear, stringResource(R.string.cal_settings), { go(CalRoute.Settings) })
        }
        Box(Modifier.navigationBarsPadding())
    }
}

/** Seitenleiste ab „Wide“: Monatsübersicht, Kalender und Erinnerungslisten wie im Web am PC. */
@Composable
private fun Sidebar(model: CalendarModel, go: (CalRoute) -> Unit, modifier: Modifier) {
    val c = Titan.colors
    Column(modifier.background(c.surface).verticalScroll(rememberScrollState()).padding(12.dp)) {
        MiniMonth(model, YearMonth.from(model.anchor), selected = model.anchor) { model.anchor = it; model.ensure(it) }
        Text(stringResource(R.string.cal_calendars), style = TitanType.section, color = c.muted, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() })
        for (cal in model.calendars) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox) { model.setHidden(cal.id, !cal.hidden) }
                    .semantics { stateDescription = if (cal.hidden) "✗" else "✓" }.heightIn(min = 40.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (cal.hidden) Color.Transparent else hexColor(cal.color)).border(2.dp, hexColor(cal.color), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                    if (!cal.hidden) Icon(TitanIcons.check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
                Text(cal.name.ifBlank { stringResource(R.string.cal_birthdays) }, style = TitanType.body, color = c.text, modifier = Modifier.weight(1f))
            }
        }
        Text(stringResource(R.string.cal_manage), style = TitanType.body, color = c.accent, modifier = Modifier.clickable(role = Role.Button) { go(CalRoute.Calendars) }.padding(6.dp))
        Text(stringResource(R.string.cal_tasks), style = TitanType.section, color = c.muted, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() })
        for (l in model.taskLists) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { go(CalRoute.Tasks(l.id)) }.heightIn(min = 40.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ColorDot(hexColor(l.color), 12.dp)
                Text(l.name, style = TitanType.body, color = c.text, modifier = Modifier.weight(1f))
                Text(l.open.toString(), style = TitanType.small, color = c.muted)
            }
        }
    }
}

/** Kleiner Monat (Seitenleiste, Jahr). */
@Composable
internal fun MiniMonth(model: CalendarModel, month: YearMonth, selected: LocalDate?, compactTitle: Boolean = false, onDay: ((LocalDate) -> Unit)?) {
    val c = Titan.colors
    val today = CalDates.today(model.zone)
    Column {
        Text("${CalDates.monthName(month)}${if (compactTitle) "" else " " + month.year}", style = TitanType.rowTitle, color = if (YearMonth.from(today) == month) c.accent else c.text)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (d in CalDates.weekdays(model.firstDay)) Text(CalDates.shortDay(d), style = TitanType.small, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
        for (week in CalDates.monthWeeks(month, model.firstDay)) {
            Row(Modifier.fillMaxWidth()) {
                for (d in week) {
                    val inMonth = YearMonth.from(d) == month
                    val isToday = d == today
                    val sel = d == selected
                    Box(
                        Modifier.weight(1f).heightIn(min = 28.dp).then(if (onDay != null && inMonth) Modifier.clickable { onDay(d) } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (inMonth) {
                            Box(
                                Modifier.defaultMinSize(24.dp, 24.dp).clip(CircleShape).background(when { isToday -> c.statusError; sel -> c.chip; else -> Color.Transparent }).padding(horizontal = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(d.dayOfMonth.toString(), style = TitanType.small.copy(fontWeight = if (isToday || sel) FontWeight.SemiBold else null), color = if (isToday) Color.White else c.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Monate untereinander wie am iPhone; Balken je Termin in der Farbe des Kalenders, der Titel auf Wunsch. */
@Composable
private fun MonthView(model: CalendarModel, width: Width, onDay: (LocalDate) -> Unit, onEvent: (CalEvent) -> Unit, onNewAt: (LocalDate) -> Unit) {
    val c = Titan.colors
    val base = remember { YearMonth.from(model.anchor) }
    val months = remember(base) { (-24L..36L).map { base.plusMonths(it) } }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = 24)
    val target = YearMonth.from(model.anchor)
    LaunchedEffect(target) {
        val i = months.indexOf(target)
        if (i >= 0 && state.firstVisibleItemIndex != i) state.scrollToItem(i)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { i -> months.getOrNull(i)?.let { model.ensure(it.atDay(1), 0, 1) } }
    }
    val today = CalDates.today(model.zone)
    val titles = model.prefs.monthTitles || width.twoPanes
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            if (model.prefs.weekNumbers) Spacer(Modifier.width(28.dp))
            for (d in CalDates.weekdays(model.firstDay)) {
                Text(CalDates.shortDay(d), style = TitanType.small, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Divider()
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(months, key = { it.toString() }) { m ->
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(CalDates.monthName(m) + if (m.year != today.year) " ${m.year}" else "", style = TitanType.title, color = if (m == YearMonth.from(today)) c.accent else c.text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).semantics { heading() })
                    for (week in CalDates.monthWeeks(m, model.firstDay)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = if (titles) 92.dp else 64.dp)) {
                            if (model.prefs.weekNumbers) {
                                Text(CalDates.weekNumber(week.first()).toString(), style = TitanType.small, color = c.muted, textAlign = TextAlign.Center,
                                    modifier = Modifier.width(28.dp).padding(top = 8.dp))
                            }
                            for (d in week) {
                                val inMonth = YearMonth.from(d) == m
                                Box(Modifier.weight(1f)) {
                                    if (inMonth) DayCell(model, d, d == today, titles, onDay, onEvent, onNewAt)
                                }
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(model: CalendarModel, d: LocalDate, isToday: Boolean, titles: Boolean, onDay: (LocalDate) -> Unit, onEvent: (CalEvent) -> Unit, onNewAt: (LocalDate) -> Unit) {
    val c = Titan.colors
    val events = model.eventsOn(d)
    val tasks = model.tasksOn(d)
    val weekend = d.dayOfWeek.value >= 6
    val said = stringResource(R.string.cal_day_a11y, CalDates.longDate(d), events.size + tasks.size)
    Column(
        Modifier.fillMaxWidth().clickable(onClickLabel = said) { onDay(d) }
            .onSecondaryClick { onNewAt(d) }
            .semantics(mergeDescendants = true) { contentDescription = said }.padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.defaultMinSize(30.dp, 30.dp).clip(CircleShape).background(if (isToday) c.statusError else Color.Transparent).padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
            Text(d.dayOfMonth.toString(), style = TitanType.rowTitle.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
                color = when { isToday -> Color.White; weekend -> c.muted; else -> c.text })
        }
        val shown = events.take(if (titles) 3 else 4)
        for (e in shown) {
            val color = hexColor(model.calendar(e.calendar)?.color)
            if (titles) {
                Text(
                    e.title.ifBlank { stringResource(R.string.cal_no_title) },
                    style = TitanType.small.copy(fontSize = TitanType.small.fontSize * 0.85f),
                    color = if (e.allDay) Color.White else c.text,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (e.allDay) color else color.copy(alpha = .18f)).clickable { onEvent(e) }.padding(horizontal = 3.dp, vertical = 1.dp),
                )
            } else {
                Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).heightIn(min = 4.dp, max = 4.dp).clip(RoundedCornerShape(2.dp)).background(color))
            }
        }
        val more = events.size - shown.size + tasks.size
        if (more > 0) Text("+$more", style = TitanType.small, color = c.muted)
    }
}

@Composable
private fun YearView(model: CalendarModel, width: Width, onMonth: (YearMonth) -> Unit) {
    val year = model.anchor.year
    val cols = when (width) { Width.Phone -> 2; Width.Split -> 3; else -> 4 }
    LazyVerticalGrid(GridCells.Fixed(cols), Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items((1..12).map { YearMonth.of(year, it) }) { m ->
            Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onMonth(m) }.padding(4.dp)) {
                MiniMonth(model, m, null, compactTitle = true, onDay = null)
            }
        }
    }
}

/** Liste der nächsten Tage mit Terminen und fälligen Erinnerungen. */
@Composable
private fun AgendaView(model: CalendarModel, onEvent: (CalEvent) -> Unit, onTask: (Task) -> Unit) {
    val c = Titan.colors
    val start = model.anchor
    LaunchedEffect(start, model.prefs.listDays) { model.ensure(start, 0, (model.prefs.listDays / 28 + 1).toLong()) }
    val days = (0 until model.prefs.listDays).map { start.plusDays(it.toLong()) }.filter { model.eventsOn(it).isNotEmpty() || model.tasksOn(it).isNotEmpty() }
    if (days.isEmpty()) {
        EmptyState(stringResource(R.string.cal_list_empty), stringResource(R.string.cal_list_empty_text, model.prefs.listDays))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        for (d in days) {
            item(key = d.toString()) {
                Text(CalDates.longDate(d), style = TitanType.section, color = c.muted, modifier = Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })
            }
            items(model.eventsOn(d), key = { d.toString() + it.key }) { e -> EventRow(model, e, d) { onEvent(e) } }
            items(model.tasksOn(d), key = { d.toString() + "t" + it.id }) { t -> TaskRow(model, t, onClick = { onTask(t) }) }
        }
    }
}

/** Zeile eines Termins (Liste, Suche, Tag). */
@Composable
internal fun EventRow(model: CalendarModel, e: CalEvent, day: LocalDate?, onClick: () -> Unit) {
    val c = Titan.colors
    val z = model.zone
    val cal = model.calendar(e.calendar)
    val time = when {
        e.allDay -> stringResource(R.string.cal_all_day)
        else -> "${CalDates.time(CalDates.start(e, z))} – ${CalDates.time(CalDates.end(e, z))}"
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(2.dp)).background(hexColor(cal?.color)))
        Column(Modifier.weight(1f)) {
            Text(eventTitle(e), style = TitanType.rowTitle, color = c.text)
            val sub = listOfNotNull(if (day == null) CalDates.mediumDate(CalDates.start(e, z).toLocalDate()) else null, time, e.location.ifBlank { null }, cal?.name).joinToString(" · ")
            Text(sub, style = TitanType.rowSub, color = c.muted)
        }
        if (e.recurring) Icon(TitanIcons.restart, stringResource(R.string.cal_recurring), tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun eventTitle(e: CalEvent): String = when {
    e.isBirthday && e.birthday != null -> if (e.birthday.age != null) stringResource(R.string.cal_birthday_age, e.birthday.name, e.birthday.age) else stringResource(R.string.cal_birthday_of, e.birthday.name)
    e.title.isBlank() -> stringResource(R.string.cal_no_title)
    else -> e.title
}

@Composable
internal fun problemText(p: CalProblem): String = stringResource(
    when (p) {
        CalProblem.Unreachable -> R.string.cal_problem_unreachable
        CalProblem.Unavailable -> R.string.cal_problem_unavailable
        CalProblem.Disabled -> R.string.cal_problem_disabled
        CalProblem.Conflict -> R.string.cal_problem_conflict
        CalProblem.Invalid -> R.string.cal_problem_invalid
        CalProblem.Other -> R.string.cal_problem_other
    },
)

/** Suche über Titel, Ort und Notizen (wie SearchPanel im Web). */
@Composable
private fun SearchPage(model: CalendarModel, onBack: () -> Unit, onOpen: (CalEvent) -> Unit) {
    BackHandler(onBack = onBack)
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CalEvent>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.isBlank()) { results = null; return@LaunchedEffect }
        delay(350)
        busy = true
        results = model.search(q)
        busy = false
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Titan.colors.bg)) {
        CalTop(stringResource(R.string.cal_search), onBack)
        TextInput(q, { q = it }, stringResource(R.string.cal_search), Modifier.padding(16.dp).focusRequester(focus), leading = TitanIcons.search, placeholder = stringResource(R.string.cal_search_hint))
        when {
            busy -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(16.dp))
            results?.isEmpty() == true -> EmptyState(stringResource(R.string.cal_search_none), stringResource(R.string.cal_search_none_text))
            results != null -> LazyColumn(Modifier.weight(1f)) { items(results!!, key = { it.key }) { e -> EventRow(model, e, null) { onOpen(e) } } }
        }
        Box(Modifier.navigationBarsPadding())
    }
}
