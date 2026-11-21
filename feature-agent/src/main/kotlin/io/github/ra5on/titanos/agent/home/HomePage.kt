package io.github.ra5on.titanos.agent.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.ChatInfo
import io.github.ra5on.titanos.agent.chat.ChatState
import io.github.ra5on.titanos.agent.chat.Suggestion
import io.github.ra5on.titanos.agent.chat.dateTime
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.chat.timeOnly
import io.github.ra5on.titanos.agent.core.LocalCalm
import io.github.ra5on.titanos.agent.core.Motion
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.bool
import io.github.ra5on.titanos.agent.core.int
import io.github.ra5on.titanos.agent.core.jsonOf
import io.github.ra5on.titanos.agent.core.o
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.objs
import io.github.ra5on.titanos.agent.core.os
import io.github.ra5on.titanos.agent.core.str
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.ui.AgentColors
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.Buttons
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.SGroup
import io.github.ra5on.titanos.agent.ui.SRow
import io.github.ra5on.titanos.agent.ui.Tag
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/** Die Begrüßung wechselt mit der Tageszeit und von Besuch zu Besuch (HomePage.svelte). */
fun greeting(tr: Tr, user: String, now: LocalDateTime = LocalDateTime.now(), pick: Int = -1): String {
    val hour = now.hour
    val slot = if (hour >= 23 || hour < 5) "night" else if (hour < 11) "morning" else if (hour < 18) "day" else "evening"
    val weekend = now.dayOfWeek.value >= 6 && slot == "day"
    val p = if (pick >= 0) pick else Random.nextInt(if (weekend) 6 else 4)
    val key = if (p >= 4) "agent.greet.weekend.${p - 3}" else "agent.greet.$slot.${p + 1}"
    return tr.t(key, "name" to user)
}

private data class StateRow(val id: String, val icon: String, val title: String, val desc: String, val tone: String, val chip: String)

private fun stateRows(tr: Tr, nas: JsonObject?, hints: List<JsonObject>): List<StateRow> {
    if (nas == null) return emptyList()
    val out = ArrayList<StateRow>()
    fun chipOf(tone: String) = when (tone) { "ok" -> tr.t("agent.home.good"); "error" -> tr.t("agent.home.bad"); else -> tr.t("agent.home.watch") }
    val vols = nas.os("volumes")
    if (vols.isNotEmpty()) {
        val mounted = vols.filter { it.bool("mounted") }
        val full = mounted.maxByOrNull { it.int("percent") }
        val soon = mounted.filter { it.int("daysLeft") > 0 }.minByOrNull { it.int("daysLeft") }
        if (full != null) {
            val pct = full.int("percent")
            val tone = if (pct >= 95) "error" else if (pct >= 85 || soon != null) "warn" else "ok"
            val extra = if (soon != null) " · " + if (soon.str("name") == full.str("name")) tr.t("agent.home.spaceDaysSame", "days" to soon.int("daysLeft")) else tr.t("agent.home.spaceDays", "name" to soon.str("name"), "days" to soon.int("daysLeft")) else ""
            out += StateRow("space", "disk", tr.t("agent.home.space"), tr.t("agent.home.spaceUsed", "name" to full.str("name"), "percent" to pct) + extra, tone, chipOf(tone))
        }
        val failed = vols.filter { it.str("state") in setOf("failed", "missing") || !it.bool("mounted") }
        val weak = vols.filter { it.str("state") == "degraded" }
        val busy = vols.filter { it.str("state") in setOf("rebuilding", "syncing", "checking", "reshaping") }
        val tone = if (failed.isNotEmpty()) "error" else if (weak.isNotEmpty()) "warn" else if (busy.isNotEmpty()) "info" else "ok"
        fun names(l: List<JsonObject>) = l.joinToString(", ") { it.str("name") }
        val desc = when {
            failed.isNotEmpty() -> tr.t("agent.home.raidFailed", "names" to names(failed))
            weak.isNotEmpty() -> tr.t("agent.home.raidDegraded", "names" to names(weak))
            busy.isNotEmpty() -> tr.t("agent.home.raidBusy", "names" to names(busy))
            vols.size == 1 -> tr.t("agent.home.raidOkOne")
            else -> tr.t("agent.home.raidOk", "count" to vols.size)
        }
        out += StateRow("raid", "shield", tr.t("agent.home.raid"), desc, tone, if (tone == "info") tr.t("agent.home.busy") else chipOf(tone))
    }
    nas["backups"]?.obj()?.takeIf { it.isNotEmpty() }?.let { b ->
        val failing = b.int("failing")
        val last = b.str("lastOK")
        val tone = if (failing > 0) "warn" else if (last.isNotEmpty()) "ok" else "info"
        val jobs = b.int("jobs")
        val desc = (if (last.isNotEmpty()) tr.t("agent.home.backupLast", "time" to dateTime(last)) else tr.t("agent.home.backupNever")) + " · " + when {
            failing > 0 -> tr.t("agent.home.backupFailing", "count" to failing, "total" to jobs)
            b.int("running") > 0 -> tr.t("agent.home.backupRunning", "count" to b.int("running"))
            jobs == 1 -> tr.t("agent.home.backupJobsOne")
            else -> tr.t("agent.home.backupJobs", "count" to jobs)
        }
        out += StateRow("backups", "archive", tr.t("agent.home.backups"), desc, tone, if (tone == "info") tr.t("agent.home.none") else chipOf(tone))
    }
    nas["apps"]?.obj()?.takeIf { it.isNotEmpty() }?.let { a ->
        out += StateRow("apps", "box", tr.t("agent.home.apps"), tr.t("agent.home.appsRunning", "count" to a.int("running")) + if (a.int("stopped") > 0) " · " + tr.t("agent.home.appsStopped", "count" to a.int("stopped")) else "", "ok", chipOf("ok"))
    }
    val updates = hints.filter { it.str("kind") == "update_available" }
    if (updates.isNotEmpty()) out += StateRow("updates", "download", tr.t("agent.home.updates"), updates.joinToString(" · ") { it.str("title") }, "info", tr.t("agent.home.updatesOpen", "count" to updates.size))
    return out
}

/** Farbe des Symbols einer Zeile je Ton (.srow-ico.warn …); der Ton steht immer auch als Wort daneben. */
@Composable
private fun toneColor(tone: String): Color = when (tone) {
    "warn" -> Titan.colors.warnInk
    "error" -> Titan.colors.dangerInk
    "ok" -> Titan.colors.okInk
    "live" -> Titan.colors.accent
    else -> Titan.colors.muted
}

/**
 * Die Startseite des Agenten (HomePage.svelte): Begrüßung, was wartet und
 * läuft, auf Wunsch die Übersicht (NAS-Zustand, als Nächstes geplant,
 * zuletzt erledigt) und bis zu drei Fragen, die gerade passen. [fresh]:
 * „Neuer Chat“ – nur die Frage und die Vorschläge.
 */
@Composable
fun HomePage(
    model: AgentModel,
    fresh: Boolean,
    user: String,
    top: Dp,
    onOpen: (String) -> Unit,
    onFill: (String) -> Unit,
    onAsk: (String) -> Unit,
    onPlanned: () -> Unit,
    onTitanOS: (String) -> Unit,
) {
    val c = Titan.colors
    val tr = tr()
    val calm = LocalCalm.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var nas by remember { mutableStateOf<JsonObject?>(null) }
    var planned by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var more by rememberSaveable { mutableStateOf(false) }
    val hello = remember(user, tr) { greeting(tr, user) }
    LaunchedEffect(model.listsReady) {
        try { items = model.api.getJson("agent/inbox").obj().os("items").reversed() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
        runCatching { nas = model.api.getJson("agent/home").obj().o("state").takeIf { it.isNotEmpty() } }
        runCatching { planned = model.api.getJson("agent/schedules").obj().os("schedules") }
    }
    LaunchedEffect(items.size) { model.inboxCount = items.size }

    val today = remember { LocalDate.now().format(DateTimeFormatter.ofPattern(if (tr.de) "EEEE, d. MMMM" else "EEEE, d MMMM", if (tr.de) Locale.GERMAN else Locale.ENGLISH)) }
    val brief = fresh || !more
    val chats = model.listedChats
    val waiting = chats.filter { it.state == ChatState.WAITING && it.author == null }
    val running = chats.filter { it.state == ChatState.RUNNING && it.author == null }
    val open = waiting.size + items.size
    val lead = if (open == 0) today else "$today · " + if (open == 1) tr.t("agent.home.openOne") else tr.t("agent.home.open", "count" to open)
    val next = planned.filter { it.str("next").isNotEmpty() && it.bool("enabled") }.sortedBy { it.str("next") }.take(3)
    val done = chats.filter { it.author == null && it.state != ChatState.RUNNING && it.state != ChatState.WAITING && it.origin in setOf("schedule", "reminder", "event") }.sortedByDescending { it.updated }.take(3)
    val suggestions = model.suggestions({ k, p -> tr.t(k, *p) }, LocalDateTime.now().hour)
    val rows = stateRows(tr, nas, model.hints)

    suspend fun decide(item: JsonObject, accept: Boolean, text: String = "") {
        error = null
        try {
            model.api.sendJson("POST", "agent/inbox/${item.str("id")}", jsonOf("accept" to accept, "text" to text))
            items = items.filter { it.str("id") != item.str("id") }
            editing = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
    }
    fun asks(kind: String) = kind == "alert" || kind == "forecast"

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val minH = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = minH)
                .padding(start = 12.dp, end = 12.dp, top = top + 16.dp, bottom = 16.dp),
            verticalArrangement = if (brief) Arrangement.spacedBy(12.dp, Alignment.CenterVertically) else Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (fresh) tr.t("agent.freshTitle") else hello,
                style = AgentText.base.copy(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.27).sp),
                color = c.text,
                textAlign = if (brief) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(bottom = if (brief) 8.dp else 0.dp).semantics { heading() },
            )
            if (!brief) Text(lead, style = AgentText.md, color = c.muted)
            if (error != null && !brief) Notice(error!!, tone = "error")

            if (brief && !fresh && (open > 0 || running.isNotEmpty())) {
                SRow(
                    if (open == 0) tr.t("agent.home.runningCount", "count" to running.size) else if (open == 1) tr.t("agent.home.openOne") else tr.t("agent.home.open", "count" to open),
                    desc = if (open > 0 && running.isNotEmpty()) tr.t("agent.home.runningCount", "count" to running.size) else null,
                    icon = if (open > 0) "shield" else "activity",
                    iconTint = toneColor(if (open > 0) "warn" else "live"),
                    onClick = { more = true },
                )
            }
            AnimatedVisibility(!brief, enter = fadeIn(Motion.spec(calm, 260)) + expandVertically(Motion.spec(calm, 320)), exit = fadeOut(Motion.spec(calm, 160)) + shrinkVertically(Motion.spec(calm, 240))) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (open > 0) SGroup(tr.t("agent.home.waiting")) {
                        for (chat in waiting) SRow(
                            tr.t("agent.home.approval", "title" to chat.title),
                            desc = tr.t("agent.home.approvalDesc", "time" to dateTime(chat.updated)),
                            icon = "shield", iconTint = toneColor("warn"),
                            control = { PillButton(tr.t("agent.home.look"), { onOpen(chat.id) }, kind = Btn.Primary, small = true) },
                        )
                        for (item in items) {
                            val kind = item.str("kind")
                            val icon = mapOf("memory" to "bulb", "conflict" to "activity", "fade" to "clock", "skill" to "zap", "forecast" to "disk", "alert" to "bell", "deadline" to "clock")[kind] ?: "bell"
                            val kindText = if (kind == "deadline") tr.t("agent.f.docs.inboxKind") else tr.t("agent.inbox.kind.$kind")
                            val edit = editing?.takeIf { it.first == item.str("id") }
                            SRow(
                                if (edit != null) "" else item.str("title"),
                                desc = kindText + " · " + dateTime(item.str("created")) + item.str("body").let { if (it.isNotEmpty()) " · $it" else "" },
                                icon = icon,
                                control = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (edit != null) Field(edit.second, { editing = edit.first to it }, multiline = true, label = tr.t("agent.edit"))
                                        Buttons {
                                            if (kind == "memory" && edit == null) PillButton(tr.t("agent.edit"), { editing = item.str("id") to item.str("title") }, small = true)
                                            PillButton(if (kind == "fade" || kind == "conflict") tr.t("agent.inbox.keep") else tr.t("agent.inbox.no"), { scope.launch { decide(item, false) } }, small = true)
                                            PillButton(
                                                if (kind == "fade" || kind == "conflict") tr.t("agent.inbox.remove") else if (asks(kind)) tr.t("agent.inbox.ask") else tr.t("agent.inbox.take"),
                                                {
                                                    scope.launch {
                                                        if (asks(kind)) { decide(item, false); onAsk(tr.t("agent.inbox.askText", "title" to item.str("title"))) }
                                                        else decide(item, true, edit?.second ?: "")
                                                    }
                                                },
                                                small = true,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (running.isNotEmpty()) SGroup(tr.t("agent.home.running")) {
                        for (chat in running) SRow(
                            chat.title, desc = tr.t("agent.working"), icon = "activity", iconTint = toneColor("live"),
                            control = {
                                Buttons {
                                    PillButton(tr.t("agent.home.look"), { onOpen(chat.id) }, small = true)
                                    PillButton(tr.t("agent.stop"), { model.stop(chat.id) }, small = true)
                                }
                            },
                        )
                    }
                    if (rows.isNotEmpty()) SGroup(tr.t("agent.home.state"), tools = { Text(tr.t("agent.home.asOf", "time" to timeOnly(nas?.str("at"))), style = AgentText.sm, color = c.muted) }) {
                        for (r in rows) SRow(
                            r.title, desc = r.desc, icon = r.icon, iconTint = if (r.tone == "warn" || r.tone == "error") toneColor(r.tone) else null,
                            onClick = { onTitanOS(r.id) },
                            control = { Tag(r.chip, tone = r.tone) },
                        )
                    }
                    if (next.isNotEmpty()) SGroup(tr.t("agent.home.next"), tools = { MoreLink(tr.t("agent.home.all", "count" to planned.count { AgentModel.scheduleActive(it) }), onPlanned) }) {
                        for (s in next) {
                            val kind = s.str("kind")
                            SRow(
                                if (kind == "morning") tr.t("agent.morning") else s.str("name"),
                                desc = (if (kind == "reminder") tr.t("agent.home.reminder") else if (kind == "morning") tr.t("agent.home.report") else tr.t("agent.home.task")) + " · " + dateTime(s.str("next")),
                                icon = if (kind == "reminder") "bell" else "clock",
                                onClick = onPlanned,
                            )
                        }
                    }
                    if (done.isNotEmpty()) SGroup(tr.t("agent.home.done")) {
                        for (chat in done) SRow(chat.title, desc = dateTime(chat.updated) + (chat.preview?.let { " · $it" } ?: ""), icon = "check", iconTint = toneColor("ok"), onClick = { onOpen(chat.id) })
                    }
                }
            }
            if (model.look.suggestions && suggestions.isNotEmpty()) {
                SGroup(tr.t("agent.fitsNow"), tools = { MoreLink(tr.t("agent.otherSuggestions"), { model.shuffle += 1 }, icon = "refresh") }) {
                    for (s in suggestions) SuggestionRow(s) { onFill(s.text) }
                }
            }
            if (!fresh) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MoreLink(if (more) tr.t("agent.home.less") else tr.t("agent.home.overview"), { more = !more })
                }
            }
            if (brief) Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
}

@Composable
private fun SuggestionRow(s: Suggestion, onClick: () -> Unit) {
    SRow(
        s.title,
        desc = s.sub,
        icon = s.icon,
        iconTint = if (s.tone == "warn") Titan.colors.warnInk else null,
        badge = s.tag?.let { { Tag(it, tone = s.tone ?: "info") } },
        onClick = onClick,
        chevron = false,
    )
}

/** Ein Link-Knopf in einer Gruppe („andere“, „Alle 2“, „Übersicht anzeigen“). */
@Composable
fun MoreLink(text: String, onClick: () -> Unit, icon: String? = null) {
    Row(
        Modifier.clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) WIcon(icon, 13.dp, AgentColors.link)
        Text(text, style = AgentText.sm.copy(fontSize = 13.sp), color = AgentColors.link)
    }
}
