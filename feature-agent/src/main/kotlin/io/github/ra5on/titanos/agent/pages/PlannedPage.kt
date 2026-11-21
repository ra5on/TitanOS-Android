package io.github.ra5on.titanos.agent.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.dateTime
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.core.a
import io.github.ra5on.titanos.agent.core.bool
import io.github.ra5on.titanos.agent.core.int
import io.github.ra5on.titanos.agent.core.json
import io.github.ra5on.titanos.agent.core.jsonOf
import io.github.ra5on.titanos.agent.core.num
import io.github.ra5on.titanos.agent.core.o
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.os
import io.github.ra5on.titanos.agent.core.str
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.ui.AgentDialog
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.Buttons
import io.github.ra5on.titanos.agent.ui.Check
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.IconTile
import io.github.ra5on.titanos.agent.ui.Lead
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PagePadding
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.SGroup
import io.github.ra5on.titanos.agent.ui.SRow
import io.github.ra5on.titanos.agent.ui.Segments
import io.github.ra5on.titanos.agent.ui.Select
import io.github.ra5on.titanos.agent.ui.Switch
import io.github.ra5on.titanos.agent.ui.Tag
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.agent.ui.srowSurface
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
private val EVERY = listOf("once", "minutes", "hours", "daily", "days", "weekdays", "weekly", "monthly", "cron", "trigger")
private val EVERY_LABEL = mapOf(
    "once" to "agent.everyOnce", "minutes" to "agent.everyMinutes", "hours" to "agent.everyHours", "hourly" to "agent.everyHourly",
    "daily" to "agent.everyDaily", "days" to "agent.everyDays", "weekdays" to "agent.everyWeekdays", "weekly" to "agent.everyWeekly",
    "monthly" to "agent.everyMonthly", "cron" to "agent.everyCron", "trigger" to "agent.everyTrigger",
)

private fun ran(s: JsonObject) = s.str("lastRun").take(4).toIntOrNull()?.let { it >= 2000 } == true

/**
 * Geplant (PlannedPage.svelte, Planned.svelte): der Morgenbericht mit Zeit,
 * Tagen und Inhalt, Aufträge und Erinnerungen mit An/Aus, Jetzt ausführen,
 * letztem Ergebnis, Bearbeiten (einmal, alle n Minuten/Stunden/Tage,
 * täglich, an Wochentagen, wöchentlich, monatlich, Cron, bei einem Zustand
 * in Home Assistant), Löschen, und was zuletzt lief.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlannedPage(model: AgentModel, onOpenChat: (String) -> Unit) {
    val c = Titan.colors
    val tr = tr()
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<JsonObject?>(null) }
    var draft by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var days by remember { mutableStateOf(listOf(1, 2, 3, 4, 5)) }
    var haConns by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    val weekdays = tr.t("agent.weekdays").split(",")
    fun short(i: Int) = weekdays.getOrNull(i)?.take(2).orEmpty()

    suspend fun load() {
        error = null
        try {
            list = model.api.getJson("agent/schedules").obj().os("schedules")
            model.plannedCount = list.count { AgentModel.scheduleActive(it) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
    }
    fun run(close: Boolean = true, block: suspend () -> Unit) {
        scope.launch {
            error = null; busy = true
            try { block(); if (close) editing = null; load() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        load()
        runCatching { haConns = model.api.getJson("homeassistant/connection-names").obj().os("connections") }
    }
    suspend fun put(s: JsonObject) { model.api.sendJson("PUT", "agent/schedules/${s.str("id").ifEmpty { "new" }}", s) }

    val morning = list.firstOrNull { it.str("kind") == "morning" }
    val others = list.filter { it.str("kind") != "morning" }.sortedBy { it.str("next").ifEmpty { "9999" } }

    // Der Morgenbericht: das Formular folgt dem Gespeicherten nur, wenn sich dieses ändert.
    var report by remember { mutableStateOf(jsonOf("nas" to true, "backups" to true, "today" to true)) }
    var mAt by remember { mutableStateOf("07:00") }
    var mDays by remember { mutableStateOf(listOf(1, 2, 3, 4, 5)) }
    var mOn by remember { mutableStateOf(true) }
    var mOpen by remember { mutableStateOf(false) }
    val seedKey = morning?.let { listOf(it.o("report"), it.str("at"), it.str("every"), it.a("weekdays"), it.bool("enabled")).toString() }
    LaunchedEffect(seedKey) {
        val m = morning ?: return@LaunchedEffect
        report = m.o("report")
        mAt = m.str("at", "07:00").ifEmpty { "07:00" }
        mDays = when (m.str("every")) {
            "weekdays" -> m.a("weekdays").mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            "daily" -> listOf(0, 1, 2, 3, 4, 5, 6)
            else -> listOf(1, 2, 3, 4, 5)
        }
        mOn = m.bool("enabled")
    }
    fun mWhen(): String {
        val ds = ORDER.filter { it in mDays }
        val text = if (ds.size == 7) tr.t("agent.morningDaily") else if (ds == listOf(1, 2, 3, 4, 5)) "${short(1)}–${short(5)}" else ds.joinToString(", ") { short(it) }
        return tr.t("agent.morningAt", "days" to text, "time" to mAt)
    }
    fun saveMorning(on: Boolean = mOn) {
        val all = mDays.size == 7
        val body = JsonObject((morning ?: JsonObject(emptyMap())) + mapOf(
            "id" to json(morning?.str("id") ?: ""), "kind" to json("morning"), "name" to json(tr.t("agent.morning")), "task" to json(tr.t("agent.morning")),
            "every" to json(if (all) "daily" else "weekdays"), "weekdays" to json(if (all) emptyList() else mDays), "at" to json(mAt), "enabled" to json(on), "report" to report,
        ))
        run(false) { put(body) }
    }
    fun dayToggle(arr: List<Int>, d: Int) = if (d in arr) arr - d else arr + d

    fun edit(s: JsonObject?) {
        val soon = LocalDateTime.now().plusMinutes(60)
        editing = s?.str("id") ?: "new"
        val trig = s?.get("trigger")?.takeIf { it !is JsonNull }?.obj()
        draft = mapOf(
            "kind" to s?.str("kind").orEmpty(), "name" to s?.str("name").orEmpty(), "task" to s?.str("task").orEmpty(),
            "every" to if (trig != null) "trigger" else s?.str("every")?.ifEmpty { null } ?: "once",
            "at" to (s?.str("at")?.ifEmpty { null } ?: soon.format(DateTimeFormatter.ofPattern("HH:00"))),
            "date" to (s?.str("date")?.ifEmpty { null } ?: soon.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))),
            "interval" to (s?.get("interval")?.let { (it as? JsonPrimitive)?.content } ?: if (s?.str("every") == "minutes") "15" else "2"),
            "weekday" to (s?.int("weekday", 1) ?: 1).toString(), "day" to (s?.int("day", 1) ?: 1).toString(),
            "cron" to (s?.str("cron")?.ifEmpty { null } ?: "0 7 * * 1-5"),
            "entity" to trig?.str("entity").orEmpty(), "conn" to trig?.str("connection").orEmpty(), "state" to trig?.str("state").orEmpty(),
            "cond" to if (trig?.containsKey("above") == true) "above" else if (trig?.containsKey("below") == true) "below" else "state",
            "limit" to (trig?.get("above") ?: trig?.get("below"))?.let { (it as? JsonPrimitive)?.content }.orEmpty(),
            "enabled" to (s?.bool("enabled") ?: true).toString(), "button" to (s?.bool("button") ?: false).toString(),
            "wake" to s?.str("wake").orEmpty(), "project" to s?.str("project").orEmpty(),
        )
        days = s?.a("weekdays")?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }?.takeIf { it.isNotEmpty() } ?: listOf(1, 2, 3, 4, 5)
    }
    fun d(k: String) = draft[k].orEmpty()
    fun setD(k: String, v: String) { draft = draft + (k to v) }
    fun body(): JsonObject {
        val base = list.firstOrNull { it.str("id") == editing } ?: JsonObject(emptyMap())
        val trigger = if (d("every") == "trigger") jsonOf(
            "entity" to d("entity"), "state" to if (d("cond") == "state") d("state") else "",
            "above" to if (d("cond") == "above") d("limit").replace(',', '.').toDoubleOrNull() else null,
            "below" to if (d("cond") == "below") d("limit").replace(',', '.').toDoubleOrNull() else null,
            "connection" to d("conn").ifEmpty { null },
        ).filterValues { it !is JsonNull }.let { JsonObject(it) } else JsonNull
        val reminder = d("kind") == "reminder"
        val m = mapOf(
            "id" to json(if (editing == "new") "" else editing), "kind" to json(if (reminder) "reminder" else ""), "name" to json(d("name")), "task" to json(d("task")),
            "project" to json(d("project").ifEmpty { null }), "every" to json(if (d("every") == "trigger") "daily" else d("every")), "at" to json(d("at")), "date" to json(d("date")),
            "interval" to json(d("interval").toIntOrNull() ?: 0), "weekdays" to json(days), "weekday" to json(d("weekday").toIntOrNull() ?: 1), "day" to json(d("day").toIntOrNull() ?: 1),
            "cron" to json(d("cron")), "enabled" to json(d("enabled") != "false"), "button" to json(d("button") == "true"),
            "wake" to json(if (reminder) null else d("wake").ifEmpty { null }), "trigger" to trigger,
        )
        return JsonObject((base + m).filterValues { it !is JsonNull || it == trigger })
    }

    @Composable
    fun dayChips(sel: List<Int>, onToggle: (Int) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (dd in ORDER) {
                val on = dd in sel
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (on) c.accent else Color.Transparent).border(1.dp, if (on) c.accent else c.border, CircleShape)
                        .clickable(role = Role.Checkbox) { onToggle(dd) }.semantics { selected = on },
                    contentAlignment = Alignment.Center,
                ) { Text(short(dd), style = AgentText.sm.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.onAccent else c.text) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Lead(tr.t("agent.schedulesIntro")) }
            error?.let { item { Notice(it, tone = "error") } }
            item("morning") {
                SGroup(tr.t("agent.plan.daily")) {
                    Column(Modifier.srowSurface().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            WIcon("bell", 19.dp, c.muted)
                            Column(Modifier.weight(1f)) {
                                Text(tr.t("agent.morning"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                                val sub = when {
                                    morning == null -> tr.t("agent.morningIntro")
                                    mOn -> mWhen() + (morning.str("next").takeIf { it.isNotEmpty() }?.let { " · " + tr.t("agent.nextRun", "when" to dateTime(it)) } ?: "")
                                    else -> tr.t("agent.morningOff")
                                }
                                Text(sub, style = AgentText.sm, color = c.muted)
                            }
                            if (morning != null) {
                                io.github.ra5on.titanos.agent.chat.SmallIcon(if (mOpen) "chevronUp".takeIf { io.github.ra5on.titanos.agent.core.WebIcons.has(it) } ?: "chevronDown" else "chevronDown", tr.t("agent.morningEdit")) { mOpen = !mOpen }
                                Switch(mOn, { mOn = it; saveMorning(it) })
                            }
                        }
                        if (morning != null && mOpen) {
                            io.github.ra5on.titanos.agent.ui.TimeField(mAt, { mAt = it }, tr.t("agent.time"))
                            Text(tr.t("agent.days"), style = AgentText.sm, color = c.muted)
                            dayChips(mDays) { mDays = dayToggle(mDays, it) }
                            Text(tr.t("agent.morningWhat"), style = AgentText.sm, color = c.muted)
                            for ((k, label) in listOf("nas" to "agent.morningNAS", "backups" to "agent.morningBackups", "today" to "agent.morningToday", "home" to "agent.morningHome")) {
                                Check(tr.t(label), report.bool(k), { report = JsonObject(report + (k to json(it))) })
                            }
                            Field(report.str("extra"), { report = JsonObject(report + ("extra" to json(it.take(1000)))) }, multiline = true, label = tr.t("agent.morningExtra"), placeholder = tr.t("agent.morningExtraHint"))
                            Buttons {
                                PillButton(tr.t("common.save"), { saveMorning() }, kind = Btn.Primary, enabled = !busy && mDays.isNotEmpty())
                                PillButton(tr.t("agent.morningNow"), { run(false) { model.api.sendJson("POST", "agent/schedules/${morning.str("id")}/run") } }, enabled = !busy)
                            }
                        } else if (morning == null) {
                            PillButton(tr.t("agent.morningSetUp"), { saveMorning() }, kind = Btn.Primary, enabled = !busy)
                        }
                    }
                }
            }
            item("tasks") {
                SGroup(tr.t("agent.plan.tasks")) {
                    if (editing == null) PillButton("+ " + tr.t("agent.scheduleAdd").removePrefix("+ ").removePrefix("+"), { edit(null) }, small = true, modifier = Modifier.padding(bottom = 4.dp))
                    if (others.isEmpty()) Text(tr.t("agent.schedulesEmpty"), style = AgentText.md, color = c.muted)
                    for (s in others) {
                        val parts = buildString {
                            append(if (s.str("kind") == "reminder") tr.t("agent.reminder") else tr.t("agent.task"))
                            append(" · ").append(s.str("when").ifEmpty { s.str("every") })
                            if (s.str("next").isNotEmpty() && s.bool("enabled")) append(" · ").append(tr.t("agent.nextRun", "when" to dateTime(s.str("next"))))
                            append(" · ").append(if (ran(s)) tr.t("agent.lastRun", "when" to dateTime(s.str("lastRun"))) else tr.t("agent.neverRun"))
                            if (ran(s) && s.str("kind") != "reminder") append(if (s.bool("lastOk")) " ✓" else " !")
                        }
                        Column(Modifier.srowSurface().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                WIcon(if (s.str("kind") == "reminder") "bell" else if (s["trigger"].let { it != null && it !is JsonNull }) "home" else "play", 19.dp, c.muted)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                        Text(s.str("name"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = if (s.bool("enabled")) c.text else c.muted)
                                        if (!s.bool("enabled")) Tag(tr.t("agent.off"))
                                        if (s.str("every") == "once" && ran(s)) Tag(tr.t("agent.done"))
                                    }
                                    Text(parts, style = AgentText.sm, color = c.muted)
                                }
                            }
                            Row(Modifier.padding(start = 33.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Switch(s.bool("enabled"), { on -> run(false) { put(JsonObject(s + ("enabled" to json(on)))) } }, enabled = !busy)
                                if (s.str("chat").isNotEmpty()) IconTile("chat", tr.t("agent.lastResult"), { onOpenChat(s.str("chat")) })
                                IconTile("play", tr.t("agent.runNow"), { run(false) { model.api.sendJson("POST", "agent/schedules/${s.str("id")}/run") } }, enabled = !busy)
                                IconTile("edit", tr.t("agent.edit"), { edit(s) })
                                IconTile("trash", tr.t("common.delete"), { removing = s }, danger = true)
                            }
                        }
                    }
                }
            }
            if (editing != null) item("form") {
                Column(Modifier.srowSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val reminder = d("kind") == "reminder"
                    Text(tr.t("agent.planKind"), style = AgentText.sm, color = c.muted)
                    Segments(listOf("" to tr.t("agent.task"), "reminder" to tr.t("agent.reminder")), if (reminder) "reminder" else "", { setD("kind", it) }, fill = true)
                    Field(d("name"), { setD("name", it.take(80)) }, label = tr.t("agent.scheduleName"), placeholder = tr.t("agent.scheduleName"))
                    Field(d("task"), { setD("task", it) }, multiline = true, minHeight = 80.dp, label = if (reminder) tr.t("agent.reminderText") else tr.t("agent.scheduleTask"), placeholder = if (reminder) tr.t("agent.reminderText") else tr.t("agent.scheduleTask"))
                    Text(tr.t("agent.scheduleEvery"), style = AgentText.sm, color = c.muted)
                    Select(EVERY.map { it to tr.t(EVERY_LABEL.getValue(it)) }, d("every"), { setD("every", it) }, tr.t("agent.scheduleEvery"))
                    val every = d("every")
                    if (every in setOf("minutes", "hours", "days")) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(d("interval"), { setD("interval", it.filter(Char::isDigit).take(4)) }, Modifier.width(110.dp), label = tr.t("agent.interval"), keyboard = KeyboardType.Number)
                        Text(tr.t(if (every == "minutes") "agent.unitMinutes" else if (every == "hours") "agent.unitHours" else "agent.unitDays"), style = AgentText.md, color = c.muted)
                    }
                    if (every == "once") io.github.ra5on.titanos.agent.ui.DateField(d("date"), { setD("date", it) }, tr.t("agent.date"))
                    if (every == "weekly") Select(ORDER.map { it.toString() to (weekdays.getOrNull(it) ?: "$it") }, d("weekday"), { setD("weekday", it) }, tr.t("agent.weekday"))
                    if (every == "monthly") Field(d("day"), { setD("day", it.filter(Char::isDigit).take(2)) }, label = tr.t("agent.day"), keyboard = KeyboardType.Number)
                    if (every in setOf("once", "daily", "days", "weekdays", "weekly", "monthly")) io.github.ra5on.titanos.agent.ui.TimeField(d("at"), { setD("at", it) }, tr.t("agent.time"))
                    if (every == "weekdays") { Text(tr.t("agent.days"), style = AgentText.sm, color = c.muted); dayChips(days) { days = dayToggle(days, it) } }
                    if (every == "cron") {
                        Field(d("cron"), { setD("cron", it) }, label = tr.t("agent.cron"), placeholder = "0 7 * * 1-5")
                        Text(tr.t("agent.cronHint"), style = AgentText.sm, color = c.muted)
                    }
                    if (every == "trigger") {
                        if (haConns.size > 1) Select(haConns.map { (if (it.str("id") == "main") "" else it.str("id")) to it.str("name").ifEmpty { tr.t("ha.conn.first") } }, d("conn"), { setD("conn", it) }, tr.t("notify.haConnection"))
                        Field(d("entity"), { setD("entity", it) }, label = tr.t("agent.triggerEntity"), placeholder = "binary_sensor.haustuer")
                        Select(listOf("state" to tr.t("agent.triggerCondState"), "above" to tr.t("agent.triggerCondAbove"), "below" to tr.t("agent.triggerCondBelow")), d("cond"), { setD("cond", it) }, tr.t("agent.triggerCond"))
                        if (d("cond") == "state") Field(d("state"), { setD("state", it) }, label = tr.t("agent.triggerState"), placeholder = "on")
                        else Field(d("limit"), { setD("limit", it) }, label = tr.t("agent.triggerLimit"), placeholder = "28", keyboard = KeyboardType.Decimal)
                    }
                    if (!reminder) {
                        Text(tr.t("agent.scheduleWake"), style = AgentText.sm, color = c.muted)
                        Select(listOf("" to tr.t("agent.scheduleWakeAccount"), "wake" to tr.t("agent.scheduleWakeYes"), "ask" to tr.t("agent.scheduleWakeAsk"), "never" to tr.t("agent.scheduleWakeNever")), d("wake"), { setD("wake", it) }, tr.t("agent.scheduleWake"))
                        Text(tr.t("agent.scheduleWakeHint"), style = AgentText.sm, color = c.muted)
                        if (model.projects.isNotEmpty()) {
                            Text(tr.t("agent.project"), style = AgentText.sm, color = c.muted)
                            Select(listOf("" to tr.t("agent.noProject")) + model.projects.map { it.id to ((it.icon ?: "") + " " + it.name).trim() }, d("project"), { setD("project", it) }, tr.t("agent.project"))
                        }
                    }
                    Check(tr.t("agent.on"), d("enabled") != "false", { setD("enabled", it.toString()) })
                    if (!reminder) Check(tr.t("agent.scheduleButton"), d("button") == "true", { setD("button", it.toString()) })
                    Buttons {
                        PillButton(tr.t("common.save"), { val b = body(); run { put(b) } }, kind = Btn.Primary, enabled = !busy && !(every == "weekdays" && days.isEmpty()))
                        PillButton(tr.t("common.cancel"), { editing = null })
                    }
                }
            }
            val ranList = model.chats.filter { it.author == null && it.origin in setOf("schedule", "reminder") }.sortedByDescending { it.updated }.take(5)
            if (ranList.isNotEmpty()) item("ran") {
                SGroup(tr.t("agent.plan.ran")) {
                    for (ch in ranList) SRow(ch.title, desc = dateTime(ch.updated) + (ch.preview?.let { " · $it" } ?: ""), icon = if (ch.state == "waiting") "shield" else "check", onClick = { onOpenChat(ch.id) })
                }
            }
        }
        AgentDialog(removing != null, tr.t("agent.plan.delete"), { removing = null }, text = "„" + (removing?.str("name") ?: "") + "“", actions = {
            PillButton(tr.t("common.delete"), { val id = removing?.str("id"); removing = null; if (id != null) run(false) { model.api.sendJson("DELETE", "agent/schedules/$id") } }, kind = Btn.Danger)
            PillButton(tr.t("common.cancel"), { removing = null })
        })
    }
}
