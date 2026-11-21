package io.github.ra5on.titanos.agent.system

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.MarkdownView
import io.github.ra5on.titanos.agent.core.LocalCalm
import io.github.ra5on.titanos.agent.core.LocalTr
import io.github.ra5on.titanos.agent.core.Look
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.hexColor
import io.github.ra5on.titanos.agent.figure.FigureClock
import io.github.ra5on.titanos.agent.figure.LiveLine
import io.github.ra5on.titanos.agent.pages.NotesPanel
import io.github.ra5on.titanos.agent.ui.AgentColors
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.AgentWarm
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.Buttons
import io.github.ra5on.titanos.agent.ui.Check
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanTheme
import java.util.Locale

/**
 * Das Assistenten-Blatt: unten über der laufenden App, die Spracheingabe
 * beginnt sofort. Den Bildschirminhalt gibt es nur nach der Einstellung
 * (nie / jedes Mal fragen / immer) und nur für das eigene NAS. Die Antwort
 * kommt mit der arbeitenden Figur gleich im Blatt.
 */
@Composable
fun AssistSheet(context: Context, model: AgentModel?, onClose: () -> Unit, onOpen: (String?) -> Unit) {
    val tr = remember { Tr.load(context) }
    TitanTheme {
        CompositionLocalProvider(LocalTr provides tr, LocalCalm provides (model?.calm ?: false)) {
            val c = Titan.colors
            var text by remember { mutableStateOf("") }
            var sent by remember { mutableStateOf(false) }
            val policy = remember { AssistScreen.get(context) }
            val hasScreen = AssistContent.text.isNotEmpty() || AssistContent.screenshot != null
            var withScreen by remember { mutableStateOf(policy == AssistScreen.ALWAYS && hasScreen) }
            val clock = remember { FigureClock() }
            val hear = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
                if (said.isNotEmpty()) text = if (text.isBlank()) said else "$text $said"
            }
            fun listen() {
                runCatching {
                    hear.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (tr.de) "de-DE" else Locale.getDefault().toLanguageTag())
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, tr.t("agent.voiceStart")),
                    )
                }
            }
            LaunchedEffect(Unit) { if (model != null) { if (model.status == null) model.refresh(); listen() } }
            DisposableEffect(Unit) { onDispose { AssistContent.clear() } }
            fun send() {
                val m = model ?: return
                var body = text.trim()
                if (body.isEmpty()) return
                if (withScreen) {
                    if (AssistContent.text.isNotEmpty()) body += "\n\n" + (if (tr.de) "Auf dem Bildschirm" else "On the screen") + (if (AssistContent.app.isNotEmpty()) " (${AssistContent.app})" else "") + ":\n" + AssistContent.text.take(6000)
                    AssistContent.screenshot?.let { b -> screenshotAttachment(context, b)?.let(m::addAttachment) }
                }
                m.newChat(null, asFresh = true)
                m.send(body)
                sent = true
            }
            Box(Modifier.fillMaxSize().background(AgentColors.overlay).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
                val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().clip(shape).background(c.surface).border(1.dp, c.border, shape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .semantics { paneTitle = tr.t("nav.agent") }.navigationBarsPadding().padding(16.dp).heightIn(max = 620.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        io.github.ra5on.titanos.ui.components.AppIcon("agent", "ember", 26.dp)
                        Text(tr.t("nav.agent"), style = AgentText.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                        io.github.ra5on.titanos.agent.chat.SmallIcon("close", tr.t("common.close"), onClick = onClose)
                    }
                    if (model == null) {
                        Notice(if (tr.de) "Die Agent-App ist noch mit keinem NAS gekoppelt." else "The agent app is not paired with a NAS yet.", tone = "warn")
                        PillButton(if (tr.de) "App öffnen" else "Open the app", { onOpen(null) }, kind = Btn.Primary)
                        return@Column
                    }
                    if (!sent) {
                        Field(text, { text = it }, multiline = true, minHeight = 56.dp, placeholder = tr.t("agent.inputShort"), label = tr.t("agent.input"))
                        if (hasScreen && policy != AssistScreen.NEVER) {
                            if (policy == AssistScreen.ASK) Check(if (tr.de) "Bildschirminhalt mitsenden (nur an dein NAS)" else "Send what is on the screen (only to your NAS)", withScreen, { withScreen = it })
                            else Text(if (tr.de) "Der Bildschirminhalt geht mit (Einstellung „Immer“)." else "What is on the screen goes along (setting “Always”).", style = AgentText.sm, color = c.muted)
                        }
                        Buttons {
                            PillButton(tr.t("agent.voiceStart"), ::listen, icon = "mic")
                            PillButton(tr.t("agent.send"), ::send, kind = Btn.Primary, enabled = text.isNotBlank())
                        }
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text, style = AgentText.md, color = c.muted)
                            val answer = model.current?.entries?.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }
                            if (model.busy && model.live.content.isNotEmpty()) MarkdownView(model.live.content)
                            else if (!model.busy && answer != null) MarkdownView(answer.content.orEmpty())
                            val look = model.look
                            LiveLine(model.busy || model.sending, model.current?.id ?: "", model.live.step, model.live.content.isNotEmpty(), { true }, model.calm, look.dotDp, hexColor(look.dotColor, AgentWarm), hexColor(look.textColor, c.muted), clock)
                            model.error?.let { Notice(io.github.ra5on.titanos.agent.chat.problemText(tr, it), tone = "error") }
                        }
                        Buttons {
                            PillButton(if (tr.de) "In der App weiter" else "Continue in the app", { onOpen(model.current?.id) }, kind = Btn.Primary)
                            PillButton(tr.t("common.close"), onClose)
                        }
                    }
                }
            }
        }
    }
}

/** Notizen als Notizen-App des Systems: gleich eine neue Notiz, am Sperrbildschirm nur das. */
@Composable
fun NotesRole(context: Context, model: AgentModel?, locked: Boolean, onClose: () -> Unit) {
    val tr = remember { Tr.load(context) }
    TitanTheme {
        CompositionLocalProvider(LocalTr provides tr) {
            Box(Modifier.fillMaxSize().background(Titan.colors.bg)) {
                if (model == null) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Notice(if (tr.de) "Die Agent-App ist noch mit keinem NAS gekoppelt." else "The agent app is not paired with a NAS yet.", tone = "warn")
                    }
                } else NotesPanel(model, onClose = onClose, startNew = true)
            }
        }
    }
}

