package io.github.ra5on.titanos.agent.chat

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType

/** Was der Chat-Bildschirm zeigt; aus [AgentModel] oder in Bildtests von Hand. */
data class ChatUi(
    val chat: Chat? = null,
    val live: Live = Live(),
    val busy: Boolean = false,
    val sending: Boolean = false,
    val deciding: Boolean = false,
    val opening: Boolean = false,
    val readOnlyBy: String? = null,
    val error: AgentProblem? = null,
    val title: String = "",
    val subtitle: String = "",
    val draft: String = "",
    val attachments: List<Attachment> = emptyList(),
    val status: AgentStatus? = null,
    val statusProblem: AgentProblem? = null,
    val offline: Boolean = false,
    val cachedAt: String? = null,
    val voice: Boolean = false,
)

/** Was man im Chat tun kann; leer in Bildtests. */
open class ChatActions {
    open fun menu() {}
    open fun head() {}
    open fun newChat() {}
    open fun more() {}
    open fun draft(text: String) {}
    open fun send() {}
    open fun stop() {}
    open fun attach() {}
    open fun removeAttachment(a: Attachment) {}
    open fun voice() {}
    open fun decide(approve: Boolean) {}
    open fun choice(text: String) {}
    open fun regenerate(answer: Entry) {}
    open fun copied() {}
}

/** Runder, schwebender Knopf oben (Menü, Neuer Chat, ⋯), 48 dp. */
@Composable
fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, size: Dp = 48.dp, dot: Boolean = false) {
    val c = Titan.colors
    Box(
        Modifier.size(size).clip(CircleShape).background(c.surface).border(1.dp, c.border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(22.dp))
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp).clip(CircleShape).background(c.accent))
    }
}

/** Kopf wie die Handy-Seite des Agenten: runde Knöpfe, Titel mit Projekt · Modell. */
@Composable
fun ChatHead(ui: ChatUi, act: ChatActions) {
    val c = Titan.colors
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundButton(AgentIcons.menu, stringResource(R.string.agent_menu), act::menu)
        val shape = RoundedCornerShape(26.dp)
        val hint = stringResource(R.string.agent_head_hint)
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp).clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .clickable(role = Role.Button, onClick = act::head).semantics { contentDescription = hint }
                .padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.title.ifBlank { stringResource(R.string.agent_title) }, style = TitanType.rowTitle.copy(fontSize = 15.sp, lineHeight = 19.sp), color = c.text, textAlign = TextAlign.Center)
                if (ui.subtitle.isNotEmpty()) Text(ui.subtitle, style = TitanType.small.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = c.muted, textAlign = TextAlign.Center)
            }
            Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
        RoundButton(AgentIcons.newChat, stringResource(R.string.agent_new_chat), act::newChat)
        if (ui.chat != null) RoundButton(TitanIcons.more, stringResource(R.string.agent_more), act::more)
    }
}

/** Der Chat: Kopf, Verlauf mit Schritten, Antworten und Freigaben, Eingabe, Leiste. */
@Composable
fun ChatScreen(ui: ChatUi, act: ChatActions, nav: @Composable () -> Unit) {
    val c = Titan.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        ChatHead(ui, act)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChatBody(ui, act)
        }
        val ready = ui.status?.ready != false
        if (ready && ui.readOnlyBy == null) Composer(ui, act)
        nav()
    }
}

@Composable
private fun ChatBody(ui: ChatUi, act: ChatActions) {
    val c = Titan.colors
    val chat = ui.chat
    val blocks = remember(chat?.entries) { chat?.let { blocks(it.entries) }.orEmpty() }
    val list = rememberLazyListState()
    val lastAnswer = blocks.lastOrNull { it is Block.Answer } as? Block.Answer
    val clip = LocalClipboardManager.current
    val count = blocks.size + 4
    LaunchedEffect(chat?.id, blocks.size, ui.live.content.length / 80, ui.live.step?.id, chat?.pending?.id) {
        if (chat != null) list.scrollToItem(count)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (ui.offline) item("offline") { OfflineBanner(ui.cachedAt) }
        if (chat == null && !ui.opening) {
            item("fresh") { Fresh(ui) }
        }
        if (ui.opening) item("opening") { Text(stringResource(io.github.ra5on.titanos.ui.R.string.ui_loading), style = TitanType.body, color = c.muted) }
        ui.readOnlyBy?.let { by -> item("ro") { Text(stringResource(R.string.agent_read_only, by), style = TitanType.small, color = c.muted) } }
        blocks.forEachIndexed { i, b ->
            item("b$i") {
                when (b) {
                    is Block.User -> UserBubble(b.entry.content.orEmpty())
                    is Block.Steps -> StepsLine(b, "${chat?.id}-$i")
                    is Block.Note -> NoteLine(b.entry)
                    is Block.Answer -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MarkdownView(b.entry.content.orEmpty(), onCopied = act::copied)
                        b.entry.files.forEach { f ->
                            Text(stringResource(R.string.agent_files_made, (f.root.removePrefix("share/") + "/" + f.path).trim('/')), style = TitanType.small, color = c.muted)
                        }
                        if (b.entry.sources.isNotEmpty()) Sources(b.entry.sources)
                        val last = b === lastAnswer
                        if (b.entry.choices.isNotEmpty() && last && chat?.pending == null) {
                            Choices(b.entry.choices, enabled = !ui.busy && !ui.sending && ui.readOnlyBy == null, onChoice = act::choice)
                        }
                        Row {
                            SmallAction(AgentIcons.copy, stringResource(R.string.agent_copy)) {
                                clip.setText(AnnotatedString(b.entry.content.orEmpty()))
                                act.copied()
                            }
                            if (last && !ui.busy && ui.readOnlyBy == null) {
                                SmallAction(TitanIcons.restart, stringResource(R.string.agent_regenerate)) { act.regenerate(b.entry) }
                            }
                        }
                    }
                }
            }
        }
        if (ui.busy) item("live") { LivePart(ui.live) }
        chat?.pending?.let { p ->
            item("pending-${p.id}") { ApprovalCard(p, ui.deciding, ui.readOnlyBy != null, act::decide) }
        }
        if (chat?.state == ChatState.ERROR && !ui.busy && !chat.error.isNullOrBlank()) {
            item("chat-error") { Problem(stringResource(R.string.agent_state_error, chat.error)) }
        }
        ui.error?.let { e -> item("error") { Problem(problemText(e)) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sources(sources: List<Source>) {
    val c = Titan.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.agent_sources), style = TitanType.small, color = c.muted)
        for (s in sources) {
            Text(inlineText("[${(s.title ?: s.url).replace("]", "")}](${s.url})", c), style = TitanType.small)
        }
    }
}

/** Was gerade hereinströmt: der laufende Schritt, das Denken, der Text. */
@Composable
private fun LivePart(live: Live) {
    val c = Titan.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val step = live.step
        when {
            step != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(stepIcon(step), null, tint = AgentEmber, modifier = Modifier.size(18.dp))
                Text(stepText(step) + " …", style = TitanType.body, color = c.muted)
            }
            live.content.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(AgentIcons.brain, null, tint = AgentEmber, modifier = Modifier.size(18.dp))
                Text(stringResource(if (live.thinking.isNotEmpty()) R.string.agent_thinking else R.string.agent_working), style = TitanType.body, color = c.muted)
            }
        }
        if (live.content.isNotEmpty()) MarkdownView(live.content)
    }
}

/** Ohne offenen Chat: Begrüßung wie die Startseite des Agenten, oder warum er nicht geht. */
@Composable
private fun Fresh(ui: ChatUi) {
    val s = ui.status
    Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            s != null && !s.enabled -> EmptyState(stringResource(R.string.agent_off_title), stringResource(R.string.agent_off_text))
            s != null && !s.allowed -> EmptyState(stringResource(R.string.agent_not_allowed_title), stringResource(R.string.agent_err_not_allowed))
            s != null && !s.volume -> EmptyState(stringResource(R.string.agent_no_volume_title), stringResource(R.string.agent_err_no_volume))
            else -> {
                Text(stringResource(R.string.agent_fresh), style = TitanType.big, color = Titan.colors.text, textAlign = TextAlign.Center)
                if (ui.statusProblem != null && !ui.offline) {
                    Problem(problemText(ui.statusProblem), Modifier.padding(top = 24.dp))
                }
            }
        }
    }
}

/** Eingabe: abgerundetes Feld mit „+“, Text, Mikrofon und Senden bzw. Anhalten. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(ui: ChatUi, act: ChatActions) {
    val c = Titan.colors
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)) {
        if (ui.attachments.isNotEmpty()) {
            FlowRow(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in ui.attachments) {
                    val label = stringResource(R.string.agent_attach_remove, a.name)
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(c.chip).border(1.dp, c.border, RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button) { act.removeAttachment(a) }.semantics { contentDescription = label }
                            .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(if (a.mime?.startsWith("image/") == true) TitanIcons.image else TitanIcons.file, null, tint = c.muted, modifier = Modifier.size(16.dp))
                        Text(a.name, style = TitanType.small, color = c.text)
                        Icon(TitanIcons.x, null, tint = c.muted, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.chip).clickable(role = Role.Button, onClick = act::attach),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TitanIcons.plus, stringResource(R.string.agent_attach), tint = c.text, modifier = Modifier.size(22.dp))
            }
            val label = stringResource(R.string.agent_input)
            BasicTextField(
                value = ui.draft,
                onValueChange = act::draft,
                textStyle = AnswerStyle.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 168.dp).semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                        if (ui.draft.isEmpty()) Text(label, style = AnswerStyle, color = c.muted)
                        inner()
                    }
                },
            )
            if (ui.voice && ui.draft.isEmpty() && !ui.busy) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = act::voice),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TitanIcons.mic, stringResource(R.string.agent_voice), tint = c.text, modifier = Modifier.size(22.dp))
                }
            }
            val canSend = (ui.draft.isNotBlank() || ui.attachments.isNotEmpty()) && !ui.sending
            if (ui.busy) {
                SendButton(TitanIcons.stop, stringResource(R.string.agent_stop), enabled = true, onClick = act::stop)
            } else {
                SendButton(AgentIcons.up, stringResource(if (ui.sending) R.string.agent_sending else R.string.agent_send), enabled = canSend, onClick = act::send)
            }
        }
    }
}

@Composable
private fun SendButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (enabled) AgentEmber else AgentEmber.copy(alpha = .45f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) Color.White else Color.White.copy(alpha = .75f), modifier = Modifier.size(22.dp))
    }
}
