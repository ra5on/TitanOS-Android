package io.github.ra5on.titanos.agent.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.agent.chat.AgentApi
import io.github.ra5on.titanos.agent.chat.ApprovalCard
import io.github.ra5on.titanos.agent.chat.Block
import io.github.ra5on.titanos.agent.chat.MarkdownView
import io.github.ra5on.titanos.agent.chat.blocks
import io.github.ra5on.titanos.agent.chat.stepWord
import io.github.ra5on.titanos.agent.core.LocalTr
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** Ein Vorschlag des Programms als Chip (PanelAction im Web). */
data class PanelAction(val id: String, val label: String, val prompt: String, val needsContext: Boolean = false, val fill: Boolean = false)

/** Die Vorschläge je Programm (panel-actions.ts im Web), Texte aus den Sprachdateien des Agenten. */
object PanelActions {
    private fun action(tr: Tr, app: String, id: String, needsContext: Boolean, fill: Boolean = false) =
        PanelAction(id, tr.t("agentpim.act.$app.$id"), tr.t("agentpim.act.$app.$id.prompt"), needsContext, fill)

    fun mail(tr: Tr) = listOf("summarize", "reply", "event", "contact", "folder").map { action(tr, "mail", it, true) }
    fun calendar(tr: Tr) = listOf(action(tr, "calendar", "event", false, true), action(tr, "calendar", "free", false), action(tr, "calendar", "day", false), action(tr, "calendar", "week", false))
    fun contacts(tr: Tr) = listOf(action(tr, "contacts", "duplicates", false), action(tr, "contacts", "fromText", false, true), action(tr, "contacts", "complete", true))

    fun of(tr: Tr, app: String): List<PanelAction> = when (app) {
        "mail" -> mail(tr)
        "calendar" -> calendar(tr)
        else -> contacts(tr)
    }
}

/** Bezug für eine Nachricht (AgentContext im Web). */
object PanelContext {
    fun mail(account: String, folder: String, uid: Long) = JsonObject(
        mapOf(
            "app" to kotlinx.serialization.json.JsonPrimitive("mail"),
            "mail" to JsonObject(mapOf("a" to kotlinx.serialization.json.JsonPrimitive(account), "f" to kotlinx.serialization.json.JsonPrimitive(folder), "uid" to kotlinx.serialization.json.JsonPrimitive(uid))),
        ),
    )

    fun contact(book: String, id: String) = JsonObject(
        mapOf(
            "app" to kotlinx.serialization.json.JsonPrimitive("contacts"),
            "card" to JsonObject(mapOf("book" to kotlinx.serialization.json.JsonPrimitive(book), "id" to kotlinx.serialization.json.JsonPrimitive(id))),
        ),
    )

    fun calendar(date: String?, cal: String? = null, id: String? = null, occurrence: String? = null) = JsonObject(
        buildMap {
            put("app", kotlinx.serialization.json.JsonPrimitive("calendar"))
            if (date != null) put("date", kotlinx.serialization.json.JsonPrimitive(date))
            if (cal != null && id != null) {
                put(
                    "event",
                    JsonObject(
                        buildMap {
                            put("cal", kotlinx.serialization.json.JsonPrimitive(cal))
                            put("id", kotlinx.serialization.json.JsonPrimitive(id))
                            if (occurrence != null) put("occurrence", kotlinx.serialization.json.JsonPrimitive(occurrence))
                        },
                    ),
                )
            }
        },
    )
}

/** Ob das Panel offen ist, je Gerät und Programm gemerkt (localStorage im Web). */
object PanelPrefs {
    private const val FILE = "agent-panel"
    fun open(context: android.content.Context, app: String): Boolean = context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getBoolean(app, false)
    fun setOpen(context: android.content.Context, app: String, on: Boolean) =
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putBoolean(app, on).apply()
}

/** Ob das Konto den KI-Agenten hat (`/services/state` › agent, wie session.services.agent im Web). */
suspend fun agentAvailable(api: AgentApi): Boolean = runCatching {
    ((api.getJson("services/state") as? JsonObject)?.get("agent") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
}.getOrDefault(false)

private fun errorText(tr: Tr, e: Throwable): String = when (e) {
    is ApiException -> tr.or("error.${e.code}", tr.t("error.internal"))
    is IOException -> tr.or("error.offline", tr.t("error.internal"))
    else -> tr.t("error.internal")
}

/**
 * Das Agent-Panel der Programme (AgentPanel.svelte, TitanOS 1.13 „KI-Agent in
 * den Programmen“): Kopf mit „Neues Gespräch“, „In der KI-Agent-App öffnen“
 * und Schließen; der Hinweis auf einen Anbieter im Internet vor der ersten
 * Nutzung; Vorschläge des Programms als Chips; das Gespräch mit Freigaben;
 * Eingabe mit Senden/Anhalten. Breit steht es als Spalte neben dem Inhalt,
 * am Handy in einem Blatt ([phone]); das entscheidet der Aufrufer.
 */
@Composable
fun AgentPanel(
    api: AgentApi,
    app: String,
    context: JsonObject?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    phone: Boolean = false,
    onOpenInApp: ((String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val tr = remember { Tr.load(ctx) }
    val scope = rememberCoroutineScope()
    val panel = remember(api) { PanelChat(api, scope) }
    LaunchedEffect(panel) { panel.load() }
    DisposableEffect(panel) { onDispose { panel.detach() } }
    CompositionLocalProvider(LocalTr provides tr) {
        PanelBody(panel, tr, app, context, onClose, modifier, phone, onOpenInApp)
    }
}

@Composable
internal fun PanelBody(
    panel: PanelChat,
    tr: Tr,
    app: String,
    context: JsonObject?,
    onClose: () -> Unit,
    modifier: Modifier,
    phone: Boolean,
    onOpenInApp: ((String) -> Unit)?,
) {
    val c = Titan.colors
    var text by rememberSaveable { mutableStateOf("") }
    val state = panel.state
    val needsNote = state != null && state.cloud && !state.cloudNoted
    val ready = state != null && !needsNote
    val actions = if (app == "mail" && state != null && !state.mail) emptyList() else PanelActions.of(tr, app)
    fun send(message: String) {
        if (!ready) return
        panel.send(message, context) { ok -> if (ok) text = "" }
    }
    val title = tr.t("agentpim.panel.title")
    Column(
        modifier.background(c.surface).semantics { paneTitle = title }
            .then(if (phone) Modifier.fillMaxWidth().height(minOf(600, (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.78f).toInt()).dp) else Modifier.fillMaxSize()),
    ) {
        // Kopf: Name und Schließen in einer Zeile, die Knöpfe darunter (bei großer Schrift bricht nichts im Wort).
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WIcon("sparkle", 18.dp, c.accent)
                Text(title, style = TitanType.rowTitle, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
                val close = tr.t("common.close")
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = close },
                    contentAlignment = Alignment.Center,
                ) { WIcon("close", 16.dp, c.text) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton(tr.t("agentpim.panel.new"), { panel.fresh() }, enabled = panel.chat != null && !panel.busy, small = true)
                val chat = panel.chat
                if (chat != null && onOpenInApp != null) PillButton(tr.t("agentpim.panel.inApp"), { onOpenInApp(chat.id) }, small = true)
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(c.border))
        // Hinweis, Vorschläge und Gespräch scrollen zusammen (am Handy und bei großer Schrift bleibt das Gespräch erreichbar).
        Talk(panel, tr, Modifier.weight(1f).fillMaxWidth(), header = {
            if (needsNote) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warnBg).border(1.dp, c.warnBorder, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(tr.t("agentpim.cloud.title"), style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.warnText)
                    Text(tr.t("agentpim.cloud.text"), style = TitanType.small, color = c.warnText)
                    PillButton(tr.t("agentpim.cloud.ok"), { panel.noteCloud() }, kind = Btn.Primary, small = true)
                }
            }
            if (actions.isNotEmpty()) {
                val label = tr.t("agentpim.panel.actions")
                FlowRow(
                    Modifier.fillMaxWidth().semantics { contentDescription = label },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (a in actions) {
                        val enabled = ready && !panel.sending && !panel.busy && !(a.needsContext && context == null)
                        Text(
                            a.label,
                            style = TitanType.small,
                            color = if (enabled) c.text else c.muted,
                            modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp))
                                .background(c.accent.copy(alpha = .08f)).border(1.dp, c.border, RoundedCornerShape(18.dp))
                                .clickable(enabled = enabled, role = Role.Button) { if (a.fill) text = a.prompt + " " else send(a.prompt) }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            when {
                panel.chat != null -> Unit
                state != null -> Text(tr.t("agentpim.panel.empty", "where" to tr.t("agentpim.panel.where.$app")), style = TitanType.body, color = c.muted)
                panel.error == null -> Text(tr.t("common.loading"), style = TitanType.body, color = c.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        })
        panel.error?.let { e ->
            Text(errorText(tr, e), style = TitanType.small, color = c.dangerInk, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Assertive })
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = tr.t("agentpim.panel.input")
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(c.bg).border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (text.isEmpty()) Text(tr.t("agentpim.panel.placeholder"), style = TitanType.body, color = c.muted)
                BasicTextField(
                    text,
                    { text = it },
                    enabled = ready,
                    textStyle = TitanType.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(text) }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
            }
            if (panel.busy) {
                PillButton(tr.t("agentpim.panel.stop"), { panel.stop() })
            } else {
                PillButton(tr.t("agentpim.panel.send"), { send(text) }, kind = Btn.Primary, enabled = ready && text.isNotBlank() && !panel.sending)
            }
        }
    }
}

/** Das Gespräch: Fragen, Schritte als kurze Zeilen, Antworten, Freigaben und was gerade hereinströmt; davor [header]. */
@Composable
private fun Talk(panel: PanelChat, tr: Tr, modifier: Modifier, header: @Composable () -> Unit) {
    val c = Titan.colors
    val chat = panel.chat
    val list = rememberLazyListState()
    val shown = chat?.let { blocks(it.entries) }.orEmpty()
    LaunchedEffect(shown.size, panel.live.content.length) { if (shown.isNotEmpty()) list.animateScrollToItem(shown.size + 2) }
    LazyColumn(modifier, state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { header() } }
        items(shown) { b ->
            when (b) {
                is Block.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        b.entry.content.orEmpty(),
                        style = AgentText.md,
                        color = c.text,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(c.text.copy(alpha = .08f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                is Block.Steps -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (s in b.steps) {
                        val word = stepWord(s.tool)
                        val label = when {
                            word.isNotEmpty() -> tr.t("agent.step.$word")
                            s.tool != null -> tr.or("agentpim.action." + s.tool.replace('_', '.'), s.summary ?: s.tool)
                            else -> (s.summary ?: s.note).orEmpty()
                        }
                        Text((if (s.ok || s.role == "note") "✓ " else "✕ ") + label, style = AgentText.sm, color = c.muted)
                    }
                }
                is Block.Answer -> MarkdownView(b.entry.content.orEmpty())
                is Block.Note -> Text(b.entry.content ?: b.entry.note.orEmpty(), style = AgentText.sm, color = c.warnInk)
            }
        }
        item {
            val live = panel.live
            if (panel.busy && live.content.isNotEmpty()) MarkdownView(live.content)
            else if (panel.busy) Text(tr.t(if (live.thinking.isNotEmpty()) "agent.thinking" else "agent.working"), style = AgentText.sm, color = c.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        item {
            chat?.pending?.let { p -> ApprovalCard(p, panel.deciding) { approve -> panel.decide(p, approve) } }
        }
    }
}
