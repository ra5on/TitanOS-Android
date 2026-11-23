package io.github.ra5on.titanos.agent.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.bool
import io.github.ra5on.titanos.agent.core.int
import io.github.ra5on.titanos.agent.core.jsonOf
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.os
import io.github.ra5on.titanos.agent.core.str
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.ui.AgentColors
import io.github.ra5on.titanos.agent.ui.AgentDialog
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.Buttons
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.IconTile
import io.github.ra5on.titanos.agent.ui.Lead
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.Range
import io.github.ra5on.titanos.agent.ui.SGroup
import io.github.ra5on.titanos.agent.ui.SRow
import io.github.ra5on.titanos.agent.ui.Segments
import io.github.ra5on.titanos.agent.ui.Switch
import io.github.ra5on.titanos.agent.ui.SwipeRow
import io.github.ra5on.titanos.agent.ui.Tag
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.agent.ui.rememberDoc
import io.github.ra5on.titanos.agent.ui.srowSurface
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val DAY = 86_400_000L
private const val FADE_NEVER = 25

fun ms(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

fun dateOnly(iso: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso)

/** „vor 3 Tagen“ wie Intl.RelativeTimeFormat im Web. */
fun ago(tr: Tr, iso: String): String {
    val days = ((System.currentTimeMillis() - ms(iso)) / DAY).toInt()
    if (days <= 0) return tr.t("agent.mem.today")
    fun rel(n: Int, unitDe: Pair<String, String>, unitEn: Pair<String, String>): String =
        if (tr.de) (if (n == 1 && unitDe.first == "Tag") "gestern" else "vor $n " + if (n == 1) unitDe.first else unitDe.second)
        else (if (n == 1 && unitEn.first == "day") "yesterday" else "$n " + (if (n == 1) unitEn.first else unitEn.second) + " ago")
    return when {
        days < 14 -> rel(days, "Tag" to "Tagen", "day" to "days")
        days < 60 -> rel(Math.round(days / 7f), "Woche" to "Wochen", "week" to "weeks")
        days < 730 -> rel(Math.round(days / 30f), "Monat" to "Monaten", "month" to "months")
        else -> rel(Math.round(days / 365f), "Jahr" to "Jahren", "year" to "years")
    }
}

/** Die Eigeninitiative ab Werk (defaultInitiative im Web); gelesen wird darüber, was das NAS hat. */
fun initiativeDefaults(): JsonObject = jsonOf(
    "level" to "suggest", "reflect" to true, "reflectAt" to "03:30", "confirm" to true, "morningNote" to true, "watch" to true,
    "forecast" to true, "events" to listOf("usb", "update"), "wake" to "wake", "search" to emptyList<Any>(), "quietFrom" to "22:00",
    "quietTo" to "07:00", "gpuMinutes" to 30, "weekly" to true, "memoryAuto" to true, "conflicts" to true, "fadeMonths" to 6,
    "learnSkills" to true, "length" to "normal", "tone" to "casual", "name" to "", "icon" to "",
)

private data class Editing(val id: String, val text: String, val topic: String)

/**
 * Das Gedächtnis (MemoryPage.svelte): Suche, „+ Merksatz“, Nach Thema /
 * Zuletzt genutzt / Neueste, Anheften, Bearbeiten, Löschen, am Handy wischen
 * (rechts anheften, links löschen, lang drücken bearbeiten), Hinweise
 * („verblasst nie“, „wird bald nachgefragt“) und wie er sich merkt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryPage(model: AgentModel, query: String, draft: String, onOpenChat: (String) -> Unit) {
    val c = Titan.colors
    val tr = tr()
    val scope = rememberCoroutineScope()
    var memory by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by rememberSaveable { mutableStateOf(query) }
    var order by rememberSaveable { mutableStateOf("topic") }
    var editing by remember { mutableStateOf(if (draft.isNotEmpty()) Editing("new", draft.take(500), "") else null) }
    var confirm by remember { mutableStateOf<JsonObject?>(null) }
    var clearAsk by remember { mutableStateOf(false) }
    val ini = rememberDoc(model, "agent/initiative", initiativeDefaults())

    suspend fun load() {
        try { memory = model.api.getJson("agent/memory").obj().os("memory"); loaded = true } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
    }
    fun run(block: suspend () -> Unit) {
        scope.launch {
            error = null
            try { block(); editing = null; load() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
        }
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(draft) { if (draft.isNotEmpty()) editing = Editing("new", draft.take(500), "") }

    fun fresh(m: JsonObject) = maxOf(ms(m.str("at")), ms(m.str("used")))
    val fadeMonths = ini.obj.int("fadeMonths", 6)
    fun fading(m: JsonObject) = !m.bool("pinned") && fadeMonths > 0 && System.currentTimeMillis() - fresh(m) > (fadeMonths - 1) * 30L * DAY
    val none = tr.t("agent.mem.noTopic")
    fun topicOf(m: JsonObject): String {
        m.str("topic").takeIf { it.isNotEmpty() }?.let { return it }
        val p = model.projects.firstOrNull { it.id == m.str("project") }
        return if (p != null) (if (!p.icon.isNullOrEmpty()) p.icon + " " else "") + p.name else none
    }
    val shown = q.trim().lowercase().let { s -> if (s.isEmpty()) memory else memory.filter { (it.str("text") + " " + topicOf(it)).lowercase().contains(s) } }
    val groups: List<Pair<String, List<JsonObject>>> = when (order) {
        "used" -> listOf("" to shown.sortedByDescending { fresh(it) })
        "newest" -> listOf("" to shown.sortedByDescending { ms(it.str("at")) })
        else -> shown.groupBy { topicOf(it) }.entries
            .sortedWith { a, b -> if (a.key == none) 1 else if (b.key == none) -1 else a.key.compareTo(b.key, ignoreCase = true) }
            .map { (k, rows) -> k to rows.sortedWith(compareByDescending<JsonObject> { it.bool("pinned") }.thenByDescending { fresh(it) }) }
    }
    fun pin(m: JsonObject) = run { model.api.sendJson("PUT", "agent/memory/${m.str("id")}", jsonOf("pinned" to !m.bool("pinned"))) }

    @Composable
    fun editor(e: Editing) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(e.text, { editing = e.copy(text = it.take(500)) }, multiline = true, minHeight = 60.dp, placeholder = tr.t("agent.memoryNewHint"), label = tr.t("agent.mem.text"))
            Field(e.topic, { editing = e.copy(topic = it.take(40)) }, placeholder = tr.t("agent.mem.topicHint"), label = tr.t("agent.mem.topic"))
            val topics = memory.map { it.str("topic") }.filter { it.isNotEmpty() }.distinct()
            if (topics.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tp in topics) io.github.ra5on.titanos.ui.components.Chip(tp, on = e.topic == tp) { editing = e.copy(topic = tp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton(tr.t("common.cancel"), { editing = null })
                PillButton(tr.t("common.save"), { run { model.api.sendJson("PUT", "agent/memory/${e.id}", jsonOf("text" to e.text, "topic" to e.topic)) } }, kind = Btn.Primary, enabled = e.text.isNotBlank())
            }
        }
    }

    @Composable
    fun row(m: JsonObject) {
        val id = m.str("id")
        val e = editing
        SwipeRow(
            enabled = model.look.gestures && e?.id != id,
            right = if (m.bool("pinned")) tr.t("agent.unpin") else tr.t("agent.pin"),
            left = tr.t("common.delete"),
            onSwipe = { right -> if (right) pin(m) else confirm = m },
            onMenu = { editing = Editing(id, m.str("text"), m.str("topic")) },
        ) {
            Row(Modifier.srowSurface().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(24.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) { WIcon(if (m.bool("pinned")) "pin" else "bulb", 19.dp, if (m.bool("pinned")) c.accent else c.muted) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (e?.id == id) editor(e) else {
                        Text(m.str("text"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            val chatTitle = model.chats.firstOrNull { it.id == m.str("chat") && it.author == null }?.title.orEmpty()
                            if (chatTitle.isNotEmpty()) {
                                Text(tr.t("agent.mem.fromChat", "title" to chatTitle), style = AgentText.sm, color = AgentColors.link, modifier = Modifier.clickable { onOpenChat(m.str("chat")) })
                            }
                            val parts = listOfNotNull(
                                dateOnly(m.str("at")),
                                topicOf(m).takeIf { order != "topic" && it != none },
                                if (m.str("used").isNotEmpty()) tr.t("agent.mem.used", "when" to ago(tr, m.str("used"))) else tr.t("agent.mem.unused"),
                            )
                            // Der Punkt gehört zum Text danach: allein auf einer Zeile stünde er sonst verloren da.
                            Text((if (chatTitle.isNotEmpty()) "· " else "") + parts.joinToString(" · "), style = AgentText.sm, color = c.muted)
                            if (m.bool("pinned")) Tag(tr.t("agent.mem.pinned"), tone = "info")
                            if (fading(m)) Tag(tr.t("agent.mem.fading"), tone = "warn")
                            if (m.bool("untrusted")) Tag(tr.t("agent.untrusted"), tone = "warn")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            IconTile("pin", if (m.bool("pinned")) tr.t("agent.unpin") else tr.t("agent.pin"), { pin(m) }, on = m.bool("pinned"))
                            IconTile("edit", tr.t("agent.edit"), { editing = Editing(id, m.str("text"), m.str("topic")) })
                            IconTile("trash", tr.t("common.delete"), { confirm = m })
                        }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = io.github.ra5on.titanos.agent.ui.PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Lead(tr.t("agent.mem.lead")) }
            error?.let { item { Notice(it, tone = "error") } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Große Schrift: Suchfeld und Knopf untereinander, sonst bräche der Hinweis im Feld mitten im Wort.
                    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
                        Field(q, { q = it }, Modifier.fillMaxWidth(), placeholder = tr.t("agent.mem.search"), leading = "search")
                        PillButton(tr.t("agent.mem.add"), { editing = Editing("new", "", "") }, icon = "plus")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(q, { q = it }, Modifier.weight(1f), placeholder = tr.t("agent.mem.search"), leading = "search")
                            PillButton(tr.t("agent.mem.add"), { editing = Editing("new", "", "") }, icon = "plus")
                        }
                    }
                    Segments(listOf("topic", "used", "newest").map { it to tr.t("agent.mem.order.$it") }, order, { order = it }, compact = true, fill = true)
                }
            }
            editing?.takeIf { it.id == "new" }?.let { e ->
                item("new") {
                    SGroup(tr.t("agent.mem.new")) {
                        Row(Modifier.srowSurface().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.width(24.dp).padding(top = 10.dp)) { WIcon("bulb", 19.dp, c.muted) }
                            Box(Modifier.weight(1f)) { editor(e) }
                        }
                    }
                }
            }
            for ((title, rows) in groups) item("g$title") {
                SGroup(title.ifEmpty { null }, tools = if (title.isNotEmpty()) ({ Text(tr.t("agent.mem.count", "count" to rows.size), style = AgentText.sm, color = c.muted) }) else null) {
                    for (m in rows) row(m)
                }
            }
            if (loaded && shown.isEmpty()) item { Text(if (q.isNotBlank()) tr.t("agent.mem.noneFound") else tr.t("agent.memoryEmpty"), style = AgentText.md, color = c.muted) }
            item("how") {
                SGroup(tr.t("agent.mem.how")) {
                    SRow(tr.t("agent.init.memoryAuto"), desc = tr.t("agent.init.memoryAutoHint"), icon = "bulb", control = { Switch(ini.obj.bool("memoryAuto", true), { ini.set("memoryAuto" to it) }) })
                    SRow(tr.t("agent.init.conflicts"), desc = tr.t("agent.init.conflictsHint"), icon = "info", control = { Switch(ini.obj.bool("conflicts", true), { ini.set("conflicts" to it) }) })
                    val fadeText = if (fadeMonths == 0) tr.t("agent.init.fadeOff") else if (fadeMonths == 1) tr.t("agent.init.fadeHintOne") else tr.t("agent.init.fadeHint", "months" to fadeMonths)
                    val fadeValue = if (fadeMonths == 0) tr.t("agent.mem.fadeNever") else if (fadeMonths == 1) tr.t("agent.mem.fadeMonth") else tr.t("agent.mem.fadeMonths", "months" to fadeMonths)
                    SRow(tr.t("agent.init.fade"), desc = "$fadeText · " + tr.t("agent.mem.fadePinned"), icon = "clock", control = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Range((if (fadeMonths == 0) FADE_NEVER else fadeMonths).toFloat(), 1f..FADE_NEVER.toFloat(), { v -> val n = v.toInt(); ini.set("fadeMonths" to if (n >= FADE_NEVER) 0 else n) }, fadeValue, steps = FADE_NEVER - 2, modifier = Modifier.weight(1f))
                            Text(fadeValue, style = AgentText.sm, color = c.muted)
                        }
                    })
                    SRow(tr.t("agent.mem.clear"), desc = tr.t("agent.mem.clearDesc"), icon = "trash", control = {
                        PillButton(tr.t("agent.mem.clearAsk"), { clearAsk = true }, kind = Btn.Danger, enabled = memory.any { !it.bool("pinned") }, small = true)
                    })
                }
            }
        }
        AgentDialog(confirm != null, tr.t("agent.mem.deleteTitle"), { confirm = null }, text = "„" + (confirm?.str("text") ?: "") + "“", actions = {
            PillButton(tr.t("common.delete"), { val id = confirm?.str("id"); confirm = null; if (id != null) run { model.api.sendJson("DELETE", "agent/memory/$id") } }, kind = Btn.Danger)
            PillButton(tr.t("common.cancel"), { confirm = null })
        })
        AgentDialog(clearAsk, tr.t("agent.mem.clear"), { clearAsk = false }, text = tr.t("agent.mem.clearSure"), actions = {
            PillButton(tr.t("agent.mem.clearDo"), { clearAsk = false; run { model.api.sendJson("DELETE", "agent/memory") } }, kind = Btn.Danger)
            PillButton(tr.t("common.cancel"), { clearAsk = false })
        })
    }
}
