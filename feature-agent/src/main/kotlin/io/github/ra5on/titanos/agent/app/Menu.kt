package io.github.ra5on.titanos.agent.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.ChatInfo
import io.github.ra5on.titanos.agent.chat.ChatState
import io.github.ra5on.titanos.agent.chat.PatchChat
import io.github.ra5on.titanos.agent.chat.Project
import io.github.ra5on.titanos.agent.core.LocalCalm
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.bool
import io.github.ra5on.titanos.agent.core.int
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.ui.AgentColors
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.agent.ui.textMix
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ein Eintrag der Funktionen im Menü (featureItems in agent-page.svelte.ts). */
data class FeatureItem(val id: String, val label: String, val icon: String, val count: Int = 0)

fun featureItems(tr: Tr, model: AgentModel): List<FeatureItem> {
    val f = model.features ?: return emptyList()
    val p = model.protectMe
    val admin = model.status?.admin == true
    val out = ArrayList<FeatureItem>()
    if (f.bool("paper")) out += FeatureItem("paper", tr.t("agent.f.menu.paper"), "list")
    if (f.bool("deadlines")) out += FeatureItem("deadlines", tr.t("agent.f.menu.deadlines"), "clock")
    if (p?.bool("guard") == true) out += FeatureItem("guard", tr.t("agent.f.menu.guard"), "shield", p.int("alerts"))
    if (p?.bool("handbook") == true) out += FeatureItem("handbook", tr.t("agent.f.menu.handbook"), "fileText")
    if (f.bool("emergency") && (admin || p?.bool("emergency") == true)) out += FeatureItem("emergency", tr.t("agent.f.menu.emergency"), "key", p?.int("requests") ?: 0)
    if (f.bool("advisor") && admin) out += FeatureItem("advisor", tr.t("agent.f.menu.advisor"), "chip")
    if (f.bool("buddy") && admin) out += FeatureItem("buddy", tr.t("agent.f.menu.buddy"), "users")
    if (f.bool("catalog")) out += FeatureItem("catalog", tr.t("agent.f.menu.catalog"), "zap")
    return out
}

/** Gruppen der Chatliste nach Tag (groupChats im Web), angeheftete zuerst. */
fun groupChats(chats: List<ChatInfo>, now: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, List<ChatInfo>>> {
    val order = listOf("pinned", "today", "yesterday", "week", "older")
    val groups = LinkedHashMap<String, MutableList<ChatInfo>>()
    for (c in chats.sortedByDescending { it.updated }) {
        val key = if (c.pinned) "pinned" else runCatching {
            val d = Instant.parse(c.updated).atZone(zone).toLocalDate()
            when {
                !d.isBefore(now) -> "today"
                !d.isBefore(now.minusDays(1)) -> "yesterday"
                !d.isBefore(now.minusDays(6)) -> "week"
                else -> "older"
            }
        }.getOrDefault("older")
        groups.getOrPut(key) { ArrayList() }.add(c)
    }
    return order.filter { it in groups }.map { it to groups.getValue(it) }
}

private val GROUP_LABEL = mapOf("pinned" to "agent.groupPinned", "today" to "agent.groupToday", "yesterday" to "agent.groupYesterday", "week" to "agent.groupWeek", "older" to "agent.groupOlder")

/** Was das Menü auslöst. */
class MenuActions(
    val onShow: (AgentView) -> Unit,
    val onNew: () -> Unit,
    val onOpen: (ChatInfo) -> Unit,
    val onNewProject: () -> Unit,
    val onEditProject: (Project) -> Unit,
    val onNotes: () -> Unit,
    val onArchive: () -> Unit,
    val onFeature: (String) -> Unit,
    val onSearchMemory: (String) -> Unit,
    val onChatAction: (ChatInfo, String) -> Unit,
    val onTitanOS: (() -> Unit)?,
)

/**
 * Das Menü des Agenten (AgentMenu.svelte), am Handy von links: oben Marke und
 * Suche, „Neuer Chat“, Start/Geplant/Gedächtnis/Wissen/Notizen/Archiv und die
 * Funktionen, die Projekte mit ihren Chats, die Chats nach Tagen; unten die
 * Einstellungen und der Weg zurück zu TitanOS. Chats zur Seite wischen oder
 * lange drücken öffnet ihre Aktionen.
 */
@Composable
fun AgentMenu(model: AgentModel, view: AgentView, start: Boolean, act: MenuActions) {
    val c = Titan.colors
    val tr = tr()
    var query by rememberSaveable { mutableStateOf("") }
    val chats = model.chats.filter { !it.archived }
    val archived = model.chats.count { it.archived && it.author == null }
    val q = query.trim().lowercase()
    val found = if (q.isEmpty()) null else chats.filter { (it.title + " " + (it.preview ?: "")).lowercase().contains(q) }
    val projects = model.projects
    val loose = chats.filter { ch -> ch.project == null || projects.none { it.id == ch.project } }
    val look = model.look
    Column(
        Modifier.fillMaxHeight().width(320.dp).background(c.surface).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon("agent", "ember", 28.dp)
            Text(tr.t("nav.agent"), style = AgentText.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
        Field(query, { query = it }, Modifier.padding(horizontal = 12.dp), placeholder = tr.t("agent.searchAll"), leading = "search")
        MenuItem("newChat", tr.t("agent.newChat"), onClick = act.onNew, center = true)
        Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(1.dp).background(c.border))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            if (found == null) {
                item { MenuItem("home", tr.t("agent.home"), on = start && view == AgentView.Chat, count = model.waitingCount) { act.onShow(AgentView.Chat) } }
                item { MenuItem("clock", tr.t("agent.planned"), on = view == AgentView.Planned, count = model.plannedCount) { act.onShow(AgentView.Planned) } }
                item { MenuItem("bulb", tr.t("agent.memory"), on = view == AgentView.Memory) { act.onShow(AgentView.Memory) } }
                item { MenuItem("fileText", tr.t("agent.knowledge"), on = view == AgentView.Knowledge) { act.onShow(AgentView.Knowledge) } }
                item { MenuItem("edit", tr.t("agent.notes.title"), onClick = act.onNotes) }
                if (archived > 0) item { MenuItem("archive", tr.t("agent.archive.title"), count = archived, onClick = act.onArchive) }
                for (f in featureItems(tr, model)) item(f.id) { MenuItem(f.icon, f.label, count = f.count) { act.onFeature(f.id) } }
                item { GroupHead(tr.t("agent.projects")) }
                for (p in projects) {
                    item("p" + p.id) { ProjectRow(model, p, chats, act) }
                    if (p.id == model.project) {
                        val inside = chats.filter { it.project == p.id }
                        if (inside.isEmpty()) item("pe" + p.id) { Text(tr.t("agent.projectEmpty"), style = AgentText.sm, color = c.muted, modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp)) }
                        for (ch in inside) item("pc" + ch.id) { ChatRow(model, ch, sub = true, act = act) }
                    }
                }
                item { MenuItem("plus", tr.t("agent.newProject"), muted = true, onClick = act.onNewProject) }
                for ((key, list) in groupChats(loose)) {
                    item("g$key") { GroupHead(tr.t(GROUP_LABEL.getValue(key))) }
                    for (ch in list) item("c" + ch.id) { ChatRow(model, ch, sub = false, act = act) }
                }
            } else {
                item { GroupHead(tr.t("agent.found", "count" to found.size)) }
                if (found.isEmpty()) item { Text(tr.t("agent.searchNone"), style = AgentText.md, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                for (ch in found) item("f" + ch.id) { ChatRow(model, ch, sub = false, act = act) }
                item { MenuItem("bulb", tr.t("agent.searchMemory", "query" to query.trim())) { act.onSearchMemory(query.trim()) } }
            }
        }
        Column(Modifier.padding(bottom = 8.dp)) {
            MenuItem("gear", tr.t("agent.set.title"), on = view == AgentView.Settings) { act.onShow(AgentView.Settings) }
            if (act.onTitanOS != null) {
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().heightIn(min = 44.dp).clip(shape)
                        .background(textMix(0.08f, c.surface)).clickable(role = Role.Button, onClick = act.onTitanOS),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    WIcon("chevronLeft", 17.dp, c.text)
                    Text(tr.t("agent.toTitanOS"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                }
            }
        }
        if (look.compact) Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun GroupHead(text: String) {
    Text(text.uppercase(), style = AgentText.sm.copy(fontSize = 11.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium), color = Titan.colors.muted, modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun MenuItem(icon: String, label: String, on: Boolean = false, count: Int = 0, muted: Boolean = false, center: Boolean = false, onClick: () -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.padding(horizontal = 8.dp, vertical = 1.dp).fillMaxWidth().heightIn(min = 44.dp).clip(shape)
            .background(if (on) c.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).semantics { selected = on }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (center) Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(12.dp),
    ) {
        WIcon(icon, 17.dp, if (on) c.text else c.muted)
        Text(label, style = AgentText.md.copy(fontSize = 14.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (muted) c.muted else c.text, modifier = if (center) Modifier else Modifier.weight(1f))
        if (count > 0) Text("$count", style = AgentText.sm, color = c.muted)
    }
}

@Composable
private fun ProjectRow(model: AgentModel, p: Project, chats: List<ChatInfo>, act: MenuActions) {
    val c = Titan.colors
    val tr = tr()
    val list = chats.filter { it.project == p.id }
    val waiting = list.count { it.state == ChatState.WAITING }
    val running = list.any { it.state == ChatState.RUNNING }
    val unread = list.any { it.unread }
    val open = p.id == model.project
    Row(Modifier.padding(horizontal = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(if (open) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(role = Role.Button) { model.project = if (open) null else p.id }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(p.icon?.ifEmpty { null } ?: "📁", style = AgentText.md)
            Text(p.name, style = AgentText.md.copy(fontSize = 14.5.sp), color = c.text, modifier = Modifier.weight(1f))
            when {
                p.owner != null -> Text(p.owner, style = AgentText.sm, color = c.muted)
                p.members.isNotEmpty() -> Text("👥 ${p.members.size}", style = AgentText.sm, color = c.muted)
            }
            when {
                waiting > 0 -> Text("▲ $waiting", style = AgentText.sm, color = c.warnInk, modifier = Modifier.semantics { contentDescription = tr.t("agent.sideWaiting", "count" to waiting) })
                else -> {
                    if (running) LiveDot(tr.t("agent.running")) else if (unread && !open) Dot(tr.t("agent.unread"))
                    Text("${list.size}", style = AgentText.sm, color = c.muted)
                }
            }
        }
        if (p.owner == null) Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { act.onEditProject(p) }.semantics { contentDescription = tr.t("agent.editProject", "name" to p.name) }, contentAlignment = Alignment.Center) {
            WIcon("edit", 13.dp, c.muted)
        }
    }
}

/** Ein laufender Chat pulsiert. */
@Composable
private fun LiveDot(label: String) {
    val calm = LocalCalm.current
    val pulse = rememberInfiniteTransition(label = "live")
    val a by pulse.animateFloat(1f, if (calm) 1f else 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "live-a")
    Box(Modifier.size(8.dp).clip(CircleShape).background(Titan.colors.accent.copy(alpha = a)).semantics { contentDescription = label })
}

@Composable
private fun Dot(label: String) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(Titan.colors.accent).semantics { contentDescription = label })
}

/** Ein Chat in der Liste; zur Seite wischen löst die Aktion der Einstellungen aus, langes Drücken das Menü. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(model: AgentModel, chat: ChatInfo, sub: Boolean, act: MenuActions) {
    val c = Titan.colors
    val tr = tr()
    val look = model.look
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val density = LocalDensity.current
    val limit = with(density) { 90.dp.toPx() }
    val own = chat.author == null
    val swipes = look.gestures && own
    fun label(a: String) = when (a) {
        "pin" -> if (chat.pinned) tr.t("agent.unpin") else tr.t("agent.pin")
        "delete" -> tr.t("agent.delete")
        "archive" -> if (chat.archived) tr.t("agent.archive.restore") else tr.t("agent.archive.do")
        "menu" -> tr.t("agent.set.swipe.menu")
        else -> ""
    }
    val on = chat.id == model.current?.id
    Box(Modifier.padding(horizontal = 8.dp, vertical = 1.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))) {
        if (drag.value != 0f) {
            val right = drag.value > 0
            val action = if (right) look.swipeRight else look.swipeLeft
            Row(
                Modifier.matchParentSize().background(if (action == "delete") c.dangerInkCompat() else c.accent).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (right) Arrangement.Start else Arrangement.End,
            ) { Text(label(action), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = Color.White) }
        }
        Row(
            Modifier.fillMaxWidth().offset { IntOffset(drag.value.roundToInt(), 0) }
                .background(if (on) c.accent.copy(alpha = 0.16f).compositeOver(c.surface) else c.surface)
                .then(
                    if (swipes) Modifier.draggable(
                        rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceIn(-limit * 1.3f, limit * 1.3f)) } },
                        Orientation.Horizontal,
                        onDragStopped = {
                            val v = drag.value
                            if (abs(v) >= limit) {
                                val action = if (v > 0) look.swipeRight else look.swipeLeft
                                if (action != "none") { haptic.performHapticFeedback(HapticFeedbackType.LongPress); act.onChatAction(chat, action) }
                            }
                            drag.animateTo(0f, tween(220))
                        },
                    ) else Modifier,
                )
                .combinedClickable(onClick = { act.onOpen(chat) }, onLongClick = if (own) ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); act.onChatAction(chat, "menu") }) else null)
                .heightIn(min = if (look.compact) 40.dp else 44.dp).padding(start = if (sub) 40.dp else 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!sub) WIcon(if (chat.pinned) "pin" else if (chat.origin == "schedule") "clock" else if (chat.origin == "event" || chat.origin == "reminder") "bell" else "chat", 14.dp, c.muted)
            Text(chat.title.ifEmpty { tr.t("agent.newChat") }, style = AgentText.md.copy(fontSize = 14.5.sp, fontWeight = if (chat.unread) FontWeight.SemiBold else FontWeight.Normal), color = c.text, modifier = Modifier.weight(1f))
            chat.author?.let { Text(it, style = AgentText.sm, color = c.muted) }
            when {
                chat.state == ChatState.RUNNING -> LiveDot(tr.t("agent.running"))
                chat.state == ChatState.WAITING -> Text(
                    tr.t("agent.approvalShort"), style = AgentText.sm.copy(fontWeight = FontWeight.Medium), color = c.warnInk,
                    modifier = Modifier.border(1.dp, c.warnInk.copy(alpha = 0.5f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
                )
                chat.unread -> Dot(tr.t("agent.unread"))
            }
        }
    }
}

@Composable
private fun io.github.ra5on.titanos.ui.generated.TitanColors.dangerInkCompat(): Color = if (dark) statusError else errorText

/** Die Aktionen eines Chats nach Wischen oder langem Druck; „menu“ öffnet das Blatt. */
fun chatAction(model: AgentModel, chat: ChatInfo, action: String, sheet: (String) -> Unit) {
    when (action) {
        "pin" -> model.patch(chat, PatchChat(pinned = !chat.pinned))
        "archive" -> model.patch(chat, PatchChat(archived = !chat.archived))
        "delete" -> sheet("delete")
        "menu" -> sheet("actions")
    }
}
