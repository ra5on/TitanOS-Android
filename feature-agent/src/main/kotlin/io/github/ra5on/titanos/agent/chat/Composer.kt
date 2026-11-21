package io.github.ra5on.titanos.agent.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.core.LocalCalm
import io.github.ra5on.titanos.agent.core.Motion
import io.github.ra5on.titanos.agent.core.t
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.AgentWarm
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.ui.theme.Titan

/** Ein „/“-Befehl der Eingabe (Composer.svelte). */
data class Command(val id: String, val icon: String, val arg: Boolean, val name: String, val label: String)

/** Die Befehle in der Sprache der App (+page.svelte). */
@Composable
fun commands(): List<Command> {
    val tr = tr()
    return listOf(
        Triple("new", "newChat", false), Triple("project", "folder", false), Triple("model", "chip", false),
        Triple("remember", "bulb", true), Triple("plan", "clock", true), Triple("find", "search", true), Triple("status", "activity", false),
    ).map { (id, icon, arg) -> Command(id, icon, arg, tr.t("agent.cmd.$id.name"), tr.t("agent.cmd.$id")) }
}

/** Was die Eingabe braucht; die Zustände liegen im Modell. */
class ComposerActions(
    val onPlus: () -> Unit = {},
    val onMic: () -> Unit = {},
    val onMicUp: () -> Unit = {},
    val onCommand: (String, String) -> Unit = { _, _ -> },
    val onHead: () -> Unit = {},
)

/**
 * Die Eingabe (Composer.svelte): ein abgerundetes Feld. Zu am Handy eine
 * Zeile – Plus, Text, Mikrofon, Senden; mit dem Cursor darin oder mit Text
 * steht der Text oben und darunter Plus, Modell, Mikrofon, Senden. Während
 * ein Lauf arbeitet, hält der runde Knopf ihn an. „/“ am Anfang zeigt die
 * Befehle. Das Feld wächst mit seinem Text bis 220 dp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(
    model: AgentModel,
    act: ComposerActions,
    voice: Boolean,
    recording: Boolean,
    transcribing: Boolean,
    micHold: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Titan.colors
    val tr = tr()
    val calm = LocalCalm.current
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val text = model.draft
    val busy = model.busy
    val disabled = model.current?.pending != null || model.author != null
    val open = focused || text.isNotEmpty() || model.attachments.isNotEmpty()
    val cmds = commands()
    val typed = Regex("^/(\\S*)$").find(text)?.groupValues?.get(1)?.lowercase()
    var dismissed by remember { mutableStateOf("") }
    val offered = if (typed == null || dismissed == text || model.author != null) emptyList() else cmds.filter { it.name.startsWith(typed) }
    val requester = remember { FocusRequester() }

    fun send() {
        val trimmed = text.trim()
        val cmd = Regex("^/(\\S+)(?:\\s+([\\s\\S]*))?$").find(trimmed)
        val known = cmd?.let { m -> cmds.firstOrNull { it.name == m.groupValues[1].lowercase() } }
        if (known != null && model.author == null) {
            val arg = cmd.groupValues.getOrNull(2).orEmpty().trim()
            if (busy && arg.isNotEmpty()) return
            model.draft = ""
            act.onCommand(known.id, arg)
            return
        }
        val value = trimmed.ifEmpty { if (model.attachments.isNotEmpty()) tr.t("agent.attachDefault") else "" }
        if (value.isEmpty() || busy || disabled) return
        model.send(value)
        focus.clearFocus()
    }

    Column(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)) {
        AnimatedVisibility(offered.isNotEmpty(), enter = fadeIn(Motion.spec(calm, 160)) + expandVertically(Motion.spec(calm, 200)), exit = fadeOut(Motion.spec(calm, 120)) + shrinkVertically(Motion.spec(calm, 160))) {
            val shape = RoundedCornerShape(20.dp)
            Column(Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(6.dp)) {
                Text(tr.t("agent.cmd.title").uppercase(), style = AgentText.sm.copy(fontSize = 11.sp, letterSpacing = 0.5.sp), color = c.muted, modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 6.dp))
                offered.forEachIndexed { i, cmd ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (i == 0) c.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable(role = Role.Button) {
                                if (cmd.arg) model.draft = "/${cmd.name} " else { model.draft = ""; act.onCommand(cmd.id, "") }
                            }
                            .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        WIcon(cmd.icon, 16.dp, c.muted)
                        Text("/" + cmd.name, style = AgentText.md.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.widthIn(min = 84.dp))
                        Text(cmd.label, style = AgentText.md, color = c.muted, modifier = Modifier.weight(1f))
                    }
                }
                if (offered.isNotEmpty()) Text(tr.t("common.close"), style = AgentText.sm, color = c.muted, modifier = Modifier.align(Alignment.End).clickable { dismissed = text }.padding(8.dp))
            }
        }
        if (model.notice.isNotEmpty()) Text(model.notice, style = AgentText.sm, color = c.muted, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp).semantics { contentDescription = model.notice })
        val shape = RoundedCornerShape(if (open) 24.dp else 28.dp)
        val bg = c.text.copy(alpha = 0.05f).compositeOver(c.surface)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(bg)
                .border(1.dp, if (focused) c.text.copy(alpha = 0.22f).compositeOver(c.border) else c.border, shape)
                .then(if (calm) Modifier else Modifier.animateContentSize(Motion.spec(false, 220)))
                .padding(if (open) 8.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (open && model.attachments.isNotEmpty()) {
                FlowRow(Modifier.padding(start = 2.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in model.attachments) {
                        val label = tr.t("agent.attachRemove", "name" to a.name)
                        Row(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(c.chip).clickable(role = Role.Button) { model.removeAttachment(a) }
                                .semantics { contentDescription = label }.heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            WIcon(if (a.file != null) "clip" else "file", 13.dp, c.muted)
                            Text(a.name, style = AgentText.sm, color = c.text)
                            WIcon("close", 12.dp, c.muted)
                        }
                    }
                }
            }
            // Ein und dasselbe Feld in beiden Formen (movableContentOf), sonst verlöre es beim Öffnen den Cursor.
            val field = @Composable { m: Modifier ->
                run {
                    val c = Titan.colors
                    val tr = tr()
                    val value = model.draft
                    val ph = when {
                        model.author != null -> tr.t("agent.readOnlyChat", "name" to model.author)
                        model.current?.pending != null -> tr.t("agent.inputPending")
                        else -> tr.t("agent.inputShort")
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { model.draft = it },
                        readOnly = model.current?.pending != null || model.author != null,
                        textStyle = AgentText.lg.copy(fontSize = 16.sp, lineHeight = 23.sp, color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = m.focusRequester(requester).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { e ->
                            // Enter sendet, Umschalt+Enter macht eine neue Zeile (wie im Web).
                            if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) { send(); true } else false
                        }.heightIn(min = 24.dp, max = 220.dp)
                            .semantics { contentDescription = tr.t("agent.input") },
                        decorationBox = { inner ->
                            Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                                if (value.isEmpty()) Text(ph, style = AgentText.lg.copy(fontSize = 16.sp, lineHeight = 23.sp), color = c.muted)
                                inner()
                            }
                        },
                    )
                }
            }
            val buttons = @Composable {
                if (voice && (!busy || recording)) MicButton(recording, transcribing, disabled, micHold, act)
                if (busy) RoundSend("stop", tr.t("agent.stop"), true, stop = true) { model.stop() }
                else RoundSend("arrowUp", tr.t("agent.send"), !disabled && (text.isNotBlank() || model.attachments.isNotEmpty()) && !model.sending) { send() }
            }
            // Das Feld steht immer an derselben Stelle im Baum: so behält es den Cursor, wenn sich die Form ändert.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!open) PlusButton(busy || disabled, act.onPlus)
                field(Modifier.weight(1f))
                if (!open) buttons()
            }
            if (open) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlusButton(busy || disabled, act.onPlus)
                    ModelChip(model, act.onHead, Modifier.weight(1f))
                    buttons()
                }
            }
        }
    }
}

@Composable
private fun PlusButton(disabled: Boolean, onClick: () -> Unit) {
    val c = Titan.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(c.text.copy(alpha = 0.08f)).clickable(enabled = !disabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "+" }.alpha(if (disabled) 0.5f else 1f),
        contentAlignment = Alignment.Center,
    ) { WIcon("plus", 20.dp, c.text, label = t("agent.attach")) }
}

/** Senden: das warme Orange; ohne Text gedämpft. Anhalten: ein heller Kreis mit Quadrat. */
@Composable
private fun RoundSend(icon: String, label: String, enabled: Boolean, stop: Boolean = false, onClick: () -> Unit) {
    val c = Titan.colors
    val bg = when {
        stop -> c.text
        enabled -> AgentWarm
        else -> AgentWarm.copy(alpha = 0.45f)
    }
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(bg).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { WIcon(icon, if (stop) 14.dp else 19.dp, if (stop) c.bg else Color.White.copy(alpha = if (enabled) 1f else 0.75f)) }
}

@Composable
private fun MicButton(recording: Boolean, transcribing: Boolean, disabled: Boolean, hold: Boolean, act: ComposerActions) {
    val c = Titan.colors
    val label = t(if (recording) "agent.voiceStop" else "agent.voiceStart")
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (recording) c.dangerInkCompat().copy(alpha = 0.18f) else Color.Transparent)
            .then(
                if (hold) Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        act.onMic()
                        waitForUpOrCancellation()
                        act.onMicUp()
                    }
                } else Modifier.clickable(enabled = !disabled && !transcribing, role = Role.Button, onClick = act.onMic),
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (transcribing) Text("…", style = AgentText.lg, color = c.muted) else WIcon(if (recording) "stop" else "mic", 18.dp, if (recording) c.dangerInkCompat() else c.text)
    }
}

@Composable
private fun io.github.ra5on.titanos.ui.generated.TitanColors.dangerInkCompat(): Color = if (dark) statusError else errorText

/** Modell und Denkstufe als Knopf (modelChip am Handy): öffnet das Kopf-Blatt. */
@Composable
private fun ModelChip(model: AgentModel, onHead: () -> Unit, modifier: Modifier) {
    val c = Titan.colors
    val tr = tr()
    Row(modifier, horizontalArrangement = Arrangement.Start) {
        if (model.status == null) return@Row
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button, onClick = onHead).semantics { contentDescription = tr.t("agent.modelPick") }
                .heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(model.shownModel.ifEmpty { "–" }, style = AgentText.sm.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = c.text)
            if (model.status?.cloud == true) Text(tr.t("agent.cloud"), style = AgentText.sm, color = c.text, modifier = Modifier.border(1.dp, AgentWarm.copy(alpha = 0.6f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 1.dp))
            if (model.shownThink.isNotEmpty()) Text(levelText(tr, model.shownThink), style = AgentText.sm.copy(fontSize = 13.sp), color = c.muted)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Deutsch/Englisch für bekannte Denkstufen (levelLabel im Web). */
fun levelText(tr: io.github.ra5on.titanos.agent.core.Tr, level: String): String {
    val de = mapOf("off" to "Aus", "on" to "An", "minimal" to "Minimal", "low" to "Niedrig", "medium" to "Mittel", "high" to "Hoch", "xhigh" to "Sehr hoch", "max" to "Maximal")
    val en = mapOf("off" to "Off", "on" to "On", "minimal" to "Minimal", "low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "Extra high", "max" to "Max")
    return (if (tr.de) de else en)[level] ?: level
}
