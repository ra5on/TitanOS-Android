package io.github.ra5on.titanos.agent.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.core.LocalCalm
import io.github.ra5on.titanos.agent.core.Motion
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.hexColor
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.o
import io.github.ra5on.titanos.agent.core.str
import io.github.ra5on.titanos.agent.core.t
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.figure.FigureClock
import io.github.ra5on.titanos.agent.figure.LiveLine
import io.github.ra5on.titanos.agent.figure.stepLabel
import io.github.ra5on.titanos.agent.ui.AgentColors
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.AgentWarm
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.Buttons
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.agent.ui.srow
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Der Chat wie ChatView.svelte und seine Teile (chat/*.svelte): Nachrichten
// der Person mit langem Druck, Antworten mit Zeile darunter (Vorlesen,
// Kopieren, Neu erzeugen), Schritte als Zeile mit Blatt, Notizen, Plan,
// Karten (Container, Geräte, Fotos), erzeugte Dateien, die arbeitende Figur,
// Freigaben und die Suche im Chat.

/** Der Text eines Problems in den Worten des Webs. */
fun problemText(tr: Tr, p: AgentProblem): String = when (p) {
    AgentProblem.Offline -> tr.or("agent.offlineSend", tr.t("error.unreachable"))
    AgentProblem.NoVolume -> tr.t("agent.noVolume")
    AgentProblem.NotAllowed -> tr.t("agent.notAllowed")
    is AgentProblem.Limit -> if (p.runs <= 1) tr.t("agent.runs.limitOne") else tr.t("agent.runs.limitMany", "n" to p.runs)
    AgentProblem.Busy -> tr.or("error.agent_busy", tr.t("agent.busyWait"))
    AgentProblem.NothingPending -> tr.or("error.agent_nothing_pending", tr.t("error.internal"))
    AgentProblem.TooLarge -> tr.or("error.too_large", tr.t("error.internal"))
    AgentProblem.Certificate -> tr.or("error.certificate", tr.t("error.internal"))
    AgentProblem.Unavailable -> tr.or("error.agent_unavailable", tr.t("error.internal"))
    is AgentProblem.Code -> tr.or("error.${p.code}", tr.t("error.internal"))
    is AgentProblem.Other -> tr.t("error.internal")
}

/** Datum und Uhrzeit kurz, wie formatDateTime im Web. */
fun dateTime(iso: String?, locale: Locale = Locale.getDefault()): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        .format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso.orEmpty())

fun timeOnly(iso: String?, locale: Locale = Locale.getDefault()): String = runCatching {
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso.orEmpty())

/** „Kopieren“ zeigt kurz einen Haken an dem Text, der kopiert wurde. */
class Copied {
    var id by mutableStateOf<String?>(null)
        private set
    private var turn = 0

    suspend fun copy(id: String) {
        val mine = ++turn
        this.id = id
        delay(1800)
        if (mine == turn) this.id = null
    }
}

/** Was der Chat neben dem Modell braucht: was gedrückt wurde, wohin es geht. */
class ChatActions(
    val onHold: (Entry) -> Unit = {},
    val onSteps: (Block.Steps) -> Unit = {},
    val onPhoto: (List<PhotoHit>, Int) -> Unit = { _, _ -> },
    val onPlanned: () -> Unit = {},
    val onOpenFile: (FileRef) -> Unit = {},
    val speak: (Entry) -> Unit = {},
    val speaking: () -> String = { "" },
    val canSpeak: Boolean = false,
    val picture: suspend (String, String) -> ByteArray? = { _, _ -> null },
)

/** Die Liste des offenen Chats mit Lauf, Freigabe und Suche. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatView(model: AgentModel, act: ChatActions, top: Dp, editing: MutableEditing, clock: FigureClock) {
    val chat = model.current ?: return
    val c = Titan.colors
    val tr = tr()
    val look = model.look
    val list = remember(chat.entries) { blocks(chat.entries) }
    val undone = remember(chat.entries) { chat.entries.filter { it.note == "undone" }.map { (it.tool ?: "") + "|" + (it.content ?: "") }.toSet() }
    val lastUser = chat.entries.lastOrNull { it.role == "user" }
    val canChange = model.author == null && !model.busy
    val copied = remember { Copied() }
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val lastAnswer = remember(list) {
        var id = ""
        for (i in list.indices.reversed()) {
            val b = list[i]
            if (b is Block.User) break
            if (b is Block.Answer) { id = b.entry.id; break }
        }
        id
    }
    // Lernnotizen direkt vor einer Antwort stehen in ihrer Zeile.
    val learn = remember(list) { learned(list) }
    val dots = hexColor(look.dotColor, AgentWarm)
    val words = hexColor(look.textColor, c.muted)
    val calm = LocalCalm.current
    val find = model.find
    val hits = remember(find, chat.entries) { if (find.isNullOrBlank()) emptyList() else findHits(list, find) }
    var hit by remember(find) { mutableStateOf(0) }

    // Am Ende bleiben, solange die Person nicht nach oben schob (ChatView: pinned).
    var pinned by remember(chat.id) { mutableStateOf(true) }
    LaunchedEffect(chat.id) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (moving, more) ->
            if (moving && more) pinned = false
            if (!more) pinned = true
        }
    }
    val total = list.size + 8
    LaunchedEffect(chat.id, list.size, model.live.content.length / 60, model.live.step?.id, model.busy, chat.pending?.id) {
        if (pinned && find == null) listState.scrollToItem(total)
    }
    LaunchedEffect(chat.entries.count { it.role == "user" }) { pinned = true }
    LaunchedEffect(hit, hits) { hits.getOrNull(hit)?.let { listState.animateScrollToItem(it + 1) } }

    Column(Modifier.fillMaxSize()) {
        if (find != null) ChatFind(find, { model.find = it }, hits.size, hit, { d -> if (hits.isNotEmpty()) hit = (hit + d + hits.size) % hits.size }, top) { model.find = null }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (find != null) 8.dp else top + 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("head") { if (model.author != null) Text(t("agent.readOnlyChat", "name" to model.author), style = AgentText.sm, color = c.muted) }
            list.forEachIndexed { i, b ->
                val key = when (b) { is Block.Steps -> "s$i"; is Block.User -> b.entry.id; is Block.Answer -> b.entry.id; is Block.Note -> b.entry.id }
                item(key) {
                    val mark = find != null && hits.getOrNull(hit) == i
                    Box(Modifier.animateItem().then(if (mark) Modifier.border(2.dp, c.accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(4.dp) else Modifier)) {
                        when (b) {
                            is Block.User -> UserMessage(b.entry, editing, canChange, onHold = act.onHold, onResend = { e, text -> model.resend(e, text) })
                            is Block.Steps -> if (b.steps.isNotEmpty() || b.thinking.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                StepsLine(b, look.steps, model.busy, undone, onOpen = { act.onSteps(b) }, onUndo = if (model.author == null) model::undo else null)
                                for (s in b.steps.filter { it.draft != null }) DraftCard(s)
                            }
                            is Block.Answer -> {
                                val last = b.entry.id == lastAnswer
                                AnswerBlock(
                                    b.entry,
                                    cards = if (turnEnds(list, i)) turnSteps(list, i) else null,
                                    active = last && !model.busy && model.author == null && chat.pending == null,
                                    learned = learn.by[i].orEmpty(),
                                    copied = copied,
                                    act = act,
                                    onChoice = model::answerChoice,
                                    onCopy = {
                                        clip.setText(AnnotatedString(b.entry.content.orEmpty()))
                                        scope.launch { copied.copy(b.entry.id) }
                                    },
                                    onCopied = { scope.launch { copied.copy(b.entry.id + ":t") } },
                                    onRegenerate = if (canChange && lastUser != null && last && !b.entry.content.isNullOrEmpty()) ({ model.resend(lastUser) }) else null,
                                )
                            }
                            is Block.Note -> if (b.entry.id !in learn.taken) NoteLine(b.entry, isLearn(b), i == list.lastIndex, onRetry = { model.send(tr.t("agent.continueMessage")) }, onPlanned = act.onPlanned)
                        }
                    }
                }
            }
            item("files") { ChatFiles(chat.entries, act.onOpenFile) }
            if (chat.plan.isNotEmpty()) item("plan") { PlanBox(chat.plan) }
            item("live") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val live = model.live
                    if (model.busy && live.content.isNotEmpty() && look.typing) MarkdownView(live.content)
                    LiveLine(
                        busy = model.busy,
                        chatId = chat.id,
                        step = live.step,
                        streaming = live.content.isNotEmpty() && look.typing,
                        endedWithAnswer = {
                            val l = model.current?.entries?.lastOrNull()
                            model.current?.pending == null && l?.role == "assistant" && !l.content.isNullOrEmpty()
                        },
                        still = calm,
                        dotSize = look.dotDp,
                        dotColor = dots,
                        textColor = words,
                        clock = clock,
                    )
                }
            }
            chat.pending?.let { p ->
                if (!model.busy && model.author == null) item("pending-${p.id}") { ApprovalCard(p, model.deciding) { ok -> model.decide(ok) } }
            }
            if (chat.state == ChatState.ERROR && !model.busy && !chat.error.isNullOrBlank()) {
                item("chat-error") { Notice(chat.error, tone = "error") }
            }
        }
    }
}

/** Die Bearbeitung einer eigenen Nachricht („Bearbeiten und erneut senden“). */
class MutableEditing {
    var id by mutableStateOf<String?>(null)
    var text by mutableStateOf("")
    fun start(e: Entry) { id = e.id; text = e.content.orEmpty() }
    fun stop() { id = null }
}

private class Learned(val by: Map<Int, List<Entry>>, val taken: Set<String>)

private val LEARN = setOf("memory", "note", "skill", "schedule", "homeassistant", "album")

private fun isLearn(b: Block): Boolean =
    b is Block.Note && b.entry.note in LEARN && !(b.entry.note == "schedule" && b.entry.content?.contains("„") == true)

private fun learned(list: List<Block>): Learned {
    val by = HashMap<Int, List<Entry>>()
    val taken = HashSet<String>()
    list.forEachIndexed { i, b ->
        if (b !is Block.Answer) return@forEachIndexed
        val notes = ArrayList<Entry>()
        var j = i - 1
        while (j >= 0) {
            val prev = list[j]
            if (prev is Block.Note && isLearn(prev)) notes.add(0, prev.entry) else break
            j--
        }
        if (notes.isNotEmpty()) { by[i] = notes; notes.forEach { taken += it.id } }
    }
    return Learned(by, taken)
}

private fun turnEnds(list: List<Block>, i: Int): Boolean {
    for (j in i + 1 until list.size) {
        if (list[j] is Block.User) return true
        if (list[j] is Block.Answer) return false
    }
    return true
}

private fun turnSteps(list: List<Block>, i: Int): List<Entry> {
    val steps = ArrayList<Entry>()
    var j = i - 1
    while (j >= 0 && list[j] !is Block.User) {
        (list[j] as? Block.Steps)?.let { steps.addAll(0, it.steps) }
        j--
    }
    return steps
}

/** Die Blöcke, in denen der gesuchte Text vorkommt. */
private fun findHits(list: List<Block>, q: String): List<Int> {
    val n = q.trim().lowercase()
    return list.indices.filter { i ->
        val text = when (val b = list[i]) {
            is Block.User -> b.entry.content
            is Block.Answer -> b.entry.content
            is Block.Note -> b.entry.content
            is Block.Steps -> null
        }
        text?.lowercase()?.contains(n) == true
    }
}

/** Die Suche im Chat (ChatFind.svelte): Feld, „n von m“, hoch, runter, schließen. */
@Composable
private fun ChatFind(find: String, onChange: (String) -> Unit, count: Int, hit: Int, step: (Int) -> Unit, top: Dp, onClose: () -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.padding(start = 12.dp, end = 12.dp, top = top + 4.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Field(find, onChange, Modifier.weight(1f), placeholder = t("agent.findInChat"), leading = "search", onDone = { step(1) })
        if (find.isNotBlank()) Text(if (count > 0) t("agent.findCount", "n" to hit + 1, "count" to count) else t("agent.findNone"), style = AgentText.sm, color = c.muted)
        SmallIcon("arrowUp", t("agent.findPrev"), enabled = count > 0) { step(-1) }
        SmallIcon("arrowDown", t("agent.findNext"), enabled = count > 0) { step(1) }
        SmallIcon("close", t("agent.findClose"), onClick = onClose)
    }
}

/** Ein runder Knopf mit Symbol, mindestens 44 dp (Zeile unter einer Antwort). */
@Composable
fun SmallIcon(icon: String, label: String, enabled: Boolean = true, on: Boolean = false, size: Dp = 17.dp, tint: Color = Titan.colors.muted, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { WIcon(icon, size, if (on) Titan.colors.accent else if (enabled) tint else tint.copy(alpha = 0.4f)) }
}

/** Eine Nachricht der Person: Blase rechts, langer Druck öffnet ihre Aktionen; beim Bearbeiten ein Feld. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(entry: Entry, editing: MutableEditing, canChange: Boolean, onHold: (Entry) -> Unit, onResend: (Entry, String) -> Unit) {
    val c = Titan.colors
    val haptic = LocalHapticFeedback.current
    if (editing.id == entry.id) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(editing.text, { editing.text = it }, multiline = true, minHeight = 88.dp, label = t("agent.editMessage"))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("agent.editHint"), style = AgentText.sm, color = c.muted, modifier = Modifier.weight(1f))
                PillButton(t("common.cancel"), { editing.stop() }, small = true)
                PillButton(t("agent.resend"), { onResend(entry, editing.text.trim()); editing.stop() }, kind = Btn.Primary, enabled = editing.text.isNotBlank() && canChange, small = true)
            }
        }
        return
    }
    // Eine neue Nachricht gleitet sanft herein.
    val calm = LocalCalm.current
    val appear = remember(entry.id) { Animatable(if (calm) 1f else 0f) }
    LaunchedEffect(entry.id) { appear.animateTo(1f, tween(320, easing = Motion.Ease)) }
    Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.End) {
        val shape = RoundedCornerShape(20.dp)
        Text(
            entry.content.orEmpty(),
            style = AgentText.chat.copy(lineHeight = 24.sp),
            color = c.text,
            modifier = Modifier.graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * 18f }
                .clip(shape).background(textMixUser()).combinedClickable(onClick = {}, onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHold(entry)
                }, onLongClickLabel = t("agent.messageActions")).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun textMixUser(): Color = Titan.colors.text.copy(alpha = 0.09f).compositeOver(Titan.colors.bg)

/** Eine Antwort: Text, Karten der Runde, Rückfrage, die Zeile darunter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerBlock(
    entry: Entry,
    cards: List<Entry>?,
    active: Boolean,
    learned: List<Entry>,
    copied: Copied,
    act: ChatActions,
    onChoice: (String) -> Unit,
    onCopy: () -> Unit,
    onCopied: () -> Unit,
    onRegenerate: (() -> Unit)?,
) {
    val c = Titan.colors
    val tr = tr()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!entry.content.isNullOrEmpty()) MarkdownView(entry.content, onCopied = onCopied)
        if (cards != null) ResultCards(cards, active, onChoice, act)
        if (entry.sources.isNotEmpty()) Sources(entry.sources)
        if (entry.choices.isNotEmpty() && active) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.choices.forEachIndexed { n, choice ->
                    PillButton(choice, { onChoice(choice) }, kind = if (n == entry.choices.lastIndex) Btn.Primary else Btn.Normal)
                }
            }
        }
        if (!entry.content.isNullOrEmpty() || learned.isNotEmpty()) {
            FlowRow(Modifier.padding(start = 0.dp), verticalArrangement = Arrangement.Center) {
                if (!entry.content.isNullOrEmpty()) {
                    if (act.canSpeak) {
                        val reading = act.speaking() == entry.id
                        SmallIcon(if (reading) "stop" else "volume", t(if (reading) "agent.readStop" else "agent.readAloud"), on = reading) { act.speak(entry) }
                    }
                    val done = copied.id == entry.id
                    SmallIcon(if (done) "check" else "copy", if (done) t("agent.copiedShort") else t("agent.copy"), on = done, onClick = onCopy)
                    if (onRegenerate != null) SmallIcon("refresh", t("agent.regenerate"), onClick = onRegenerate)
                }
                for (n in learned) {
                    Text("✦ " + tr.t(NOTE_TEXT[n.note ?: "memory"] ?: "agent.noteMemory") + ": " + n.content.orEmpty(), style = AgentText.sm, color = c.muted, modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun Sources(sources: List<Source>) {
    val c = Titan.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(t("agent.sources"), style = AgentText.sm, color = c.muted)
        for (s in sources) Text(inlineText("[${(s.title ?: s.url).replace("]", "")}](${s.url})", c), style = AgentText.sm)
    }
}

val NOTE_TEXT = mapOf(
    "memory" to "agent.noteMemory", "note" to "agent.noteNote", "skill" to "agent.noteSkill", "schedule" to "agent.noteSchedule",
    "homeassistant" to "agent.noteHA", "album" to "agent.noteAlbum", "stopped" to "agent.noteStopped", "interrupted" to "agent.noteInterrupted",
    "error" to "agent.noteError", "limit" to "agent.noteLimit", "timelimit" to "agent.noteTimeLimit", "loop" to "agent.noteLoop",
    "undone" to "agent.noteUndone", "notdone" to "agent.noteNotDone",
)

/** Werkzeuge, die TitanOS zurücknehmen kann (UNDOABLE). */
val UNDOABLE = setOf("file_write", "files_delete", "ha_call")

fun seconds(tr: Tr, steps: List<Entry>): String {
    val ms = steps.sumOf { it.durationMs ?: 0 }
    if (ms < 100) return ""
    return " · " + String.format(if (tr.de) Locale.GERMAN else Locale.ENGLISH, "%.1f", ms / 1000.0) + " s"
}

fun stepsGist(tr: Tr, steps: List<Entry>): String {
    val words = steps.filter { it.role == "tool" }.map { stepLabel(tr, it) }.distinct()
    val rest = words.size - 3
    if (rest <= 0) return words.joinToString(", ")
    return words.take(3).joinToString(", ") + " " + if (rest == 1) tr.t("agent.stepsMoreOne") else tr.t("agent.stepsMore", "count" to rest)
}

/** Die Schritte einer Runde als eine Zeile (StepsLine.svelte); ein Tippen öffnet das Blatt. */
@Composable
private fun StepsLine(block: Block.Steps, showSteps: Boolean, busy: Boolean, undone: Set<String>, onOpen: () -> Unit, onUndo: ((Entry) -> Unit)?) {
    val c = Titan.colors
    val tr = tr()
    val tools = block.steps.count { it.role == "tool" }
    val gist = stepsGist(tr, block.steps)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onOpen).heightIn(min = 36.dp).padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                (if (block.steps.isNotEmpty()) tr.t("agent.steps", "count" to tools) + seconds(tr, block.steps) else tr.t("agent.thoughts")) + if (gist.isNotEmpty()) " · $gist" else "",
                style = AgentText.md, color = c.muted, modifier = Modifier.weight(1f, fill = false),
            )
            WIcon("chevronRight", 14.dp, c.muted)
        }
        if (showSteps) StepList(block, tappable = false, busy = busy, undone = undone, onShow = {}, onUndo = onUndo)
    }
}

/** Die Liste der Schritte (StepList.svelte): Haken, Fehler oder Freigabe, Wort, Ort, Dauer, Rückgängig. */
@Composable
fun StepList(block: Block.Steps, tappable: Boolean, busy: Boolean, undone: Set<String>, onShow: (Entry?) -> Unit, onUndo: ((Entry) -> Unit)?) {
    val c = Titan.colors
    val tr = tr()
    Column(Modifier.fillMaxWidth()) {
        @Composable
        fun line(step: Entry?) {
            val failed = step != null && stepFailed(step)
            Row(
                Modifier.fillMaxWidth().then(if (tappable) Modifier.clickable(role = Role.Button) { onShow(step) } else Modifier)
                    .heightIn(min = 44.dp).padding(horizontal = if (tappable) 22.dp else 0.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WIcon(if (step == null) "bulb" else if (failed) "close" else if (step.role == "note") "shield" else "check", 16.dp, if (failed) c.dangerInk else c.muted)
                Column(Modifier.weight(1f)) {
                    Text(if (step == null) tr.t("agent.thoughts") else stepLabel(tr, step), style = AgentText.md, color = c.text)
                    val detail = step?.let { stepDetail(it.args) }.orEmpty()
                    if (detail.isNotEmpty()) Text(detail, style = AgentText.sm, color = c.muted)
                    if (failed) Text(tr.t("agent.stepFailed"), style = AgentText.sm, color = c.dangerInk)
                }
                if (step != null && (step.durationMs ?: 0) >= 100) Text(String.format(Locale.GERMAN, "%.1f s", (step.durationMs ?: 0) / 1000.0), style = AgentText.sm, color = c.muted)
                if (tappable) WIcon("chevronRight", 15.dp, c.muted)
            }
            if (step != null && onUndo != null && step.ok && step.tool in UNDOABLE && (step.tool + "|" + (step.summary ?: "")) !in undone && !busy) {
                PillButton(tr.t("agent.undo"), { onUndo(step) }, small = true, modifier = Modifier.padding(start = if (tappable) 50.dp else 28.dp, bottom = 4.dp))
            }
        }
        if (block.thinking.isNotEmpty()) line(null)
        for (s in block.steps) line(s)
    }
}

/** Eine Notiz des Laufs (NoteLine.svelte). */
@Composable
private fun NoteLine(entry: Entry, learn: Boolean, last: Boolean, onRetry: () -> Unit, onPlanned: () -> Unit) {
    val c = Titan.colors
    val tr = tr()
    val content = entry.content.orEmpty()
    when {
        entry.note == "schedule" && content.contains("„") -> Row(
            Modifier.srow(AgentColors.srowBg, AgentColors.srowLine).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (content.startsWith("Erinnerung")) "⏰" else "🗓️", style = AgentText.lg)
            Text(content, style = AgentText.md, color = c.text, modifier = Modifier.weight(1f))
            PillButton(tr.t("agent.planned"), onPlanned, small = true)
        }
        entry.note == "summary" || entry.note == "edited" -> Details(tr.t(if (entry.note == "summary") "agent.noteSummary" else "agent.noteEdited"), content)
        entry.note == "snapshot" -> Text(content, style = AgentText.sm, color = c.muted)
        learn -> Text("✦ " + tr.t(NOTE_TEXT[entry.note ?: "memory"] ?: "agent.noteMemory") + ": " + content, style = AgentText.sm, color = c.muted)
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val bad = entry.note == "error"
            Text(
                tr.t(NOTE_TEXT[entry.note ?: ""] ?: "agent.noteError", "count" to content) + if (bad && content.isNotEmpty()) " · $content" else "",
                style = AgentText.md, color = if (bad) c.dangerInk else c.muted,
            )
            if (entry.note in setOf("error", "interrupted", "limit", "timelimit", "loop") && last) PillButton(tr.t("agent.continue"), onRetry, small = true)
        }
    }
}

/** Auf- und zuklappbarer Text (details/summary). */
@Composable
fun Details(title: String, body: String, initiallyOpen: Boolean = false) {
    val c = Titan.colors
    var open by rememberSaveable(title, body.length) { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { open = !open }.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WIcon(if (open) "chevronDown" else "chevronRight", 14.dp, c.muted)
            Text(title, style = AgentText.md, color = c.muted)
        }
        if (open) Text(body, style = AgentText.sm.copy(fontFamily = FontFamily.Monospace), color = c.text, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.chip).padding(10.dp))
    }
}

/** Der Plan der Aufgabe (PlanBox.svelte). */
@Composable
private fun PlanBox(plan: List<PlanStep>) {
    val c = Titan.colors
    val done = plan.count { it.done }
    Column(Modifier.srow(AgentColors.srowBg, AgentColors.srowLine).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text(t("agent.plan"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
            Text(t("agent.planProgress", "done" to done, "total" to plan.size), style = AgentText.sm, color = c.muted)
        }
        plan.forEach { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).border(1.5.dp, if (s.done) c.okInk else c.muted, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                    if (s.done) Text("✓", style = AgentText.sm, color = c.okInk)
                }
                Text(s.text + if (s.done) " (" + t("agent.planDone") + ")" else "", style = AgentText.md, color = if (s.done) c.muted else c.text)
            }
        }
    }
}

/** Ein Entwurf aus „Nur Vorschläge“ (DraftCard): was das Werkzeug vorschlug. */
@Composable
private fun DraftCard(step: Entry) {
    val c = Titan.colors
    val d = step.draft.obj()
    val fields = d.o("fields")
    Column(Modifier.srow(AgentColors.srowBg, AgentColors.srowLine).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("agentpim.draft.title"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Text(d.str("app") + " · " + d.str("kind"), style = AgentText.sm, color = c.muted)
        for ((k, v) in fields) {
            val text = (v as? JsonPrimitive)?.content ?: v.toString()
            if (text.isNotBlank()) Text("$k: $text", style = AgentText.sm, color = c.text)
        }
        Text(t("agentpim.draft.web"), style = AgentText.sm, color = c.muted)
    }
}

/** Dateien, die der Agent im Chat schrieb (ChatFiles.svelte). */
@Composable
private fun ChatFiles(entries: List<Entry>, onOpen: (FileRef) -> Unit) {
    val c = Titan.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (step in entries.filter { it.role == "tool" && it.files.isNotEmpty() }) for (f in step.files) {
            val name = f.path.substringAfterLast('/')
            Row(Modifier.srow(AgentColors.srowBg, AgentColors.srowLine).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(c.chip), contentAlignment = Alignment.Center) {
                    Text(f.path.substringAfterLast('.', "").uppercase(), style = AgentText.sm.copy(fontWeight = FontWeight.Bold), color = c.text)
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Text(f.root.removePrefix("share/") + " › " + f.path, style = AgentText.sm, color = c.muted)
                }
                PillButton(t("agent.openInFiles"), { onOpen(f) }, small = true)
            }
        }
    }
}

/** Eine Freigabe (ApprovalCard.svelte): Ring „Wartet auf deine Freigabe“, Karte mit Titel, Stufe, Zeit, Knöpfen und Details. */
@Composable
private fun ApprovalCard(p: Pending, deciding: Boolean, onDecide: (Boolean) -> Unit) {
    val c = Titan.colors
    val tr = tr()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(min = 32.dp)) {
            Box(Modifier.size(16.dp).border(2.dp, c.warnInk, CircleShape))
            Text(tr.t("agent.waitsForYou"), style = AgentText.md.copy(fontSize = 15.sp), color = c.muted)
        }
        val danger = p.level != "normal"
        val line = if (danger) c.dangerInk.copy(alpha = 0.5f) else AgentColors.srowLine
        Column(Modifier.srow(AgentColors.srowBg, line).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (p.level == "normal") "✎" else "▲", style = AgentText.lg, color = if (danger) c.dangerInk else c.text)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(askTitle(tr, p), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp), color = c.text)
                    val level = tr.t(if (p.kind == "read" && p.level == "normal") "agent.levelRead" else LEVEL_TEXT[p.level] ?: "agent.levelChange")
                    Text(tr.t("agent.ask.level", "level" to level) + " · " + tr.t("agent.ask.at", "time" to dateTime(p.at)), style = AgentText.sm, color = c.muted)
                    if (p.why == "pim" || p.why == "outside") Text(tr.t(if (p.why == "pim") "agent.ask.whyPim" else "agent.ask.whyOutside"), style = AgentText.sm, color = c.warnInk)
                }
            }
            Buttons {
                PillButton(tr.t("agent.decline"), { onDecide(false) }, enabled = !deciding)
                PillButton(tr.t(if (p.tool == "mail_delete") "agent.ask.delete" else "agent.approve"), { onDecide(true) }, kind = if (p.level == "destroy") Btn.Danger else Btn.Primary, enabled = !deciding)
            }
            val lines = argLines(p.args)
            val shown = preview(p.args)
            Details(
                tr.t("agent.ask.details"),
                (listOf(tr.t("agent.ask.tool") + ": " + p.tool + if (p.summary.isNotEmpty() && p.tool != "code_run") " — " + p.summary else "") +
                    lines.map { (k, v) -> "$k: $v" } + listOfNotNull(shown.ifEmpty { null })).joinToString("\n"),
                initiallyOpen = shown.isNotEmpty(),
            )
        }
    }
}

val LEVEL_TEXT = mapOf("normal" to "agent.levelChange", "danger" to "agent.levelDanger", "destroy" to "agent.levelDestroy")

fun askTitle(tr: Tr, p: Pending): String {
    val plain = approvalTitle(p)
    val what = if (plain != null) tr.t("agent.ask.${plain.card}", "name" to plain.name) else p.summary.ifEmpty { p.tool.replace('_', ' ') }
    return tr.t("agent.ask.title", "what" to what)
}

fun argLines(args: JsonElement?): List<Pair<String, String>> {
    val o = args as? JsonObject ?: return emptyList()
    return o.filterKeys { it != "content" && it != "sheets" && it != "code" }.map { (k, v) ->
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: v.toString()
        k to if (s.length > 160) s.take(159) + "…" else s
    }
}

fun preview(args: JsonElement?): String {
    val o = args as? JsonObject ?: return ""
    (o["code"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
    val c = (o["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ""
    return if (c.length > 600) c.take(599) + "…" else c
}

fun stepOutput(step: Entry): String {
    val text = step.result?.ifEmpty { null } ?: step.summary.orEmpty()
    return if (text.length > 6000) text.take(5999) + "…" else text
}

// ---------- Karten unter einer Antwort (ResultCards.svelte, PhotoGrid.svelte) ----------

data class AppRow(val name: String, val app: String, val state: String, val update: Boolean, val cpu: Double, val memory: Long)
data class DeviceCard(val id: String, val name: String, val state: String, val domain: String)
data class PhotoHit(val id: String, val date: String, val place: String, val video: Boolean)

private fun parseResult(result: String?): JsonObject? {
    if (result.isNullOrEmpty()) return null
    val at = result.indexOf('{')
    if (at < 0) return null
    return runCatching { io.github.ra5on.titanos.net.TitanJson.parseToJsonElement(result.substring(at)) as? JsonObject }.getOrNull()
}

fun appRows(steps: List<Entry>): List<AppRow> {
    val step = steps.lastOrNull { it.role == "tool" && it.tool == "docker_list" && it.ok }
    val list = parseResult(step?.result)?.get("containers") as? JsonArray ?: return emptyList()
    return list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
        val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        val state = (o["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        AppRow(
            name, (o["app"] as? JsonPrimitive)?.content.orEmpty(), state,
            (o["updateAvailable"] as? JsonPrimitive)?.booleanOrNull == true,
            (o["cpuPercent"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            (o["memoryBytes"] as? JsonPrimitive)?.longOrNull ?: 0,
        )
    }.sortedWith(compareBy<AppRow>({ if (it.state == "running") 1 else 0 }, { it.name }))
}

private val SWITCHED = setOf("light", "switch", "fan", "input_boolean", "media_player")

fun deviceCards(steps: List<Entry>): List<DeviceCard> {
    val seen = LinkedHashMap<String, DeviceCard>()
    for (s in steps) {
        if (s.role != "tool" || s.tool != "ha_state" || !s.ok) continue
        val d = parseResult(s.result) ?: continue
        val id = listOf("entity_id", "entityId", "id").firstNotNullOfOrNull { (d[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: continue
        val state = (d["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        val attrs = d["attributes"] as? JsonObject
        val name = (d["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: (attrs?.get("friendly_name") as? JsonPrimitive)?.content ?: id
        seen[id] = DeviceCard(id, name, state, id.substringBefore('.'))
    }
    return seen.values.filter { it.domain in SWITCHED }.take(6)
}

private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

fun photoHits(steps: List<Entry>): List<PhotoHit> {
    val step = steps.lastOrNull { it.role == "tool" && it.tool == "photos_search" && it.ok }
    val list = parseResult(step?.result)?.get("photos") as? JsonArray ?: return emptyList()
    return list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
        val id = (o["id"] as? JsonPrimitive)?.content?.takeIf { UUID.matches(it) } ?: return@mapNotNull null
        PhotoHit(id, (o["date"] as? JsonPrimitive)?.content.orEmpty(), (o["place"] as? JsonPrimitive)?.content.orEmpty(), (o["video"] as? JsonPrimitive)?.booleanOrNull == true)
    }
}

private val STATE_WORD = mapOf("running" to "agent.card.running", "exited" to "agent.card.stopped", "created" to "agent.card.stopped", "paused" to "agent.card.paused", "restarting" to "agent.card.restarting", "dead" to "agent.card.failed")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCards(steps: List<Entry>, active: Boolean, onAsk: (String) -> Unit, act: ChatActions) {
    val c = Titan.colors
    val tr = tr()
    val apps = remember(steps) { appRows(steps) }
    val devices = remember(steps) { deviceCards(steps) }
    val photos = remember(steps) { photoHits(steps) }
    var all by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(16.dp)
    if (apps.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().clip(shape).background(AgentColors.srowBg).border(1.dp, AgentColors.srowLine, shape).padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text(tr.t("agent.card.apps"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                Text(tr.t("agent.card.appsCount", "running" to apps.count { it.state == "running" }, "total" to apps.size), style = AgentText.sm, color = c.muted)
            }
            for (a in if (all) apps else apps.take(8)) {
                val on = a.state == "running"
                Column(Modifier.fillMaxWidth().animateContentSize()) {
                    Row(
                        Modifier.fillMaxWidth().then(if (active) Modifier.clickable(role = Role.Button) { opened = if (opened == a.name) "" else a.name } else Modifier)
                            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) c.okInk else c.muted))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = AgentText.md.copy(fontWeight = FontWeight.Medium), color = c.text)
                            val parts = listOfNotNull(
                                STATE_WORD[a.state]?.let { tr.t(it) } ?: a.state,
                                if (on && a.memory > 0) io.github.ra5on.titanos.ui.Format.bytes(a.memory) else null,
                                if (on && a.cpu >= 0.1) tr.t("agent.card.cpu", "percent" to String.format(Locale.getDefault(), "%.1f", a.cpu)) else null,
                                if (a.update) tr.t("agent.card.update") else null,
                            )
                            Text(parts.joinToString(" · "), style = AgentText.sm, color = c.muted)
                        }
                        if (active) WIcon("chevronDown", 16.dp, c.muted)
                    }
                    AnimatedVisibility(active && opened == a.name, enter = expandVertically() + fadeIn(), exit = shrinkVertically()) {
                        val keys = (if (a.update) listOf("updateNow") else emptyList()) + if (on) listOf("restart", "stop", "logs", "details", "errors") else listOf("start", "logs", "details", "why")
                        Buttons(Modifier.padding(start = 32.dp, end = 14.dp, bottom = 10.dp)) {
                            for (k in keys) PillButton(tr.t("agent.card.$k"), { onAsk(tr.t("agent.card.ask.$k", "name" to a.name)) }, kind = if (k == "updateNow") Btn.Primary else Btn.Normal, small = true)
                        }
                    }
                }
            }
            if (apps.size > 8 && !all) Text(tr.t("agent.card.all", "count" to apps.size), style = AgentText.sm, color = AgentColors.link, modifier = Modifier.clickable { all = true }.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
    if (photos.isNotEmpty()) PhotoGrid(photos, act)
    for (d in devices) {
        val on = d.state !in setOf("off", "unavailable", "unknown", "standby")
        Row(Modifier.fillMaxWidth().clip(shape).background(AgentColors.srowBg).border(1.dp, AgentColors.srowLine, shape).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) AgentWarm else c.muted))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = AgentText.md.copy(fontWeight = FontWeight.Medium), color = c.text)
                Text(tr.t(if (on) "agent.card.on" else if (d.state == "unavailable") "agent.card.gone" else "agent.card.off"), style = AgentText.sm, color = c.muted)
            }
            if (active && d.state != "unavailable") PillButton(tr.t(if (on) "agent.card.switchOff" else "agent.card.switchOn"), { onAsk(tr.t(if (on) "agent.card.askOff" else "agent.card.askOn", "name" to d.name)) }, small = true)
        }
    }
}

fun photoLabel(tr: Tr, p: PhotoHit): String {
    val day = runCatching {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(java.time.LocalDateTime.parse(p.date.removeSuffix("Z").take(19)))
    }.getOrDefault("")
    return listOf(if (p.video) tr.t("agent.photos.video") else tr.t("agent.photos.photo"), day, p.place).filter { it.isNotEmpty() }.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoGrid(photos: List<PhotoHit>, act: ChatActions) {
    val c = Titan.colors
    val tr = tr()
    var all by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(AgentColors.srowBg).border(1.dp, AgentColors.srowLine, shape).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 4.dp)) {
            Text(tr.t("agent.photos.card"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
            Text(tr.t("agent.photos.count", "count" to photos.size), style = AgentText.sm, color = c.muted)
        }
        val shown = if (all) photos else photos.take(12)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val size = (maxWidth - 12.dp) / 4
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shown.forEachIndexed { i, p ->
                    val label = photoLabel(tr, p)
                    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(c.chip).clickable(role = Role.Image) { act.onPhoto(photos, i) }.semantics { contentDescription = label }) {
                        NasPicture(p.id, "thumbnail", act, Modifier.fillMaxSize())
                        if (p.video) Text(tr.t("agent.photos.video"), style = AgentText.sm, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp))
                    }
                }
            }
        }
        if (photos.size > 12 && !all) Text(tr.t("agent.photos.all", "count" to photos.size), style = AgentText.sm, color = AgentColors.link, modifier = Modifier.clickable { all = true }.padding(6.dp))
    }
}

/** Ein Bild über die geprüfte Route des NAS (/agent/photos/{id}/picture). */
@Composable
fun NasPicture(id: String, size: String, act: ChatActions, modifier: Modifier = Modifier, fit: Boolean = false) {
    var bitmap by remember(id, size) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(id, size) {
        val bytes = act.picture(id, size) ?: return@LaunchedEffect
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    bitmap?.let { androidx.compose.foundation.Image(it, null, modifier, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop) }
}
