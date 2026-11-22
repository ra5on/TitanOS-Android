package io.github.ra5on.titanos.watch.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.data.Chat
import io.github.ra5on.titanos.watch.data.Speaker
import io.github.ra5on.titanos.watch.data.Voice
import io.github.ra5on.titanos.watch.data.WatchApi
import io.github.ra5on.titanos.watch.data.isHidden
import io.github.ra5on.titanos.watch.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface AgentView {
    data object Idle : AgentView
    data object Typing : AgentView
    data object Recording : AgentView
    data class Working(val question: String) : AgentView
    data class Answer(val question: String, val chat: Chat) : AgentView
    data class Failed(val message: Int) : AgentView
}

/** Die Antwort auf die letzte Frage: der Text des Agenten nach der letzten Zeile der Person. */
fun answerOf(chat: Chat): String {
    val lastUser = chat.entries.indexOfLast { it.role == "user" }
    return chat.entries.drop(lastUser + 1).filter { it.role == "assistant" && !it.content.isNullOrBlank() }
        .joinToString("\n\n") { it.content!!.trim() }
}

/**
 * Fertig heißt: Der Agent wartet auf eine (neue) Freigabe, ist gescheitert
 * oder hat nach der letzten Zeile der Person geantwortet. Gleich nach dem
 * Senden meldet das NAS den Chat oft noch als „idle“, nach einer Freigabe
 * noch mit der alten Frage.
 */
fun agentDone(c: Chat, decided: String? = null): Boolean = when (c.state) {
    "running" -> false
    "waiting" -> c.pending != null && c.pending.id != decided
    "error", "interrupted" -> true
    else -> answerOf(c).isNotBlank()
}

/** Fragt den Chat ab, bis der Agent fertig ist oder auf eine Freigabe wartet (höchstens 3 Minuten). */
suspend fun awaitAgent(api: WatchApi, first: Chat, decided: String? = null): Chat {
    var chat = first
    val end = System.currentTimeMillis() + 180_000
    while (!agentDone(chat, decided) && System.currentTimeMillis() < end) {
        delay(1500)
        chat = api.chat(chat.id)
    }
    return chat
}

@Composable
fun AgentRoute() {
    val context = LocalContext.current
    val g = context.graph
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<AgentView>(AgentView.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    var recording by remember { mutableStateOf(false) }
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }

    fun speak(text: String) {
        if (text.isBlank()) return
        scope.launch {
            val api = g.api() ?: return@launch
            if (g.settings.nasVoice || !speaker.speakLocal(text)) runCatching { speaker.speakNas(api, text) }
        }
    }

    fun ask(question: String) {
        val api = g.api() ?: return
        view = AgentView.Working(question)
        job?.cancel()
        job = scope.launch {
            view = try {
                val first = api.ask(g.settings.lastChat, question)
                g.settings.lastChat = first.id
                val chat = awaitAgent(api, first)
                if (g.settings.readAloud) speak(answerOf(chat))
                AgentView.Answer(question, chat)
            } catch (e: Exception) {
                if (e is io.github.ra5on.titanos.net.ApiException && e.status == 404) g.settings.lastChat = null
                AgentView.Failed(if (e.isHidden()) R.string.w_agent_err_rights else R.string.w_err_reach)
            }
        }
    }

    fun decide(chat: Chat, approve: Boolean) {
        val api = g.api() ?: return
        val pending = chat.pending ?: return
        val question = (view as? AgentView.Answer)?.question.orEmpty()
        view = AgentView.Working(question)
        job = scope.launch {
            view = try {
                api.decide(chat.id, pending.id, approve)
                val next = awaitAgent(api, api.chat(chat.id), decided = pending.id)
                if (g.settings.readAloud) speak(answerOf(next))
                AgentView.Answer(question, next)
            } catch (_: Exception) {
                AgentView.Failed(R.string.w_err_reach)
            }
        }
    }

    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::ask)
        }
    }
    // Ohne Spracheingabe des Systems (Uhr ohne Google): selbst aufnehmen, Whisper am NAS schreibt mit.
    fun recordNow() {
        val api = g.api() ?: return
        recording = true
        view = AgentView.Recording
        job = scope.launch {
            try {
                val wav = Voice.record({ recording })
                view = AgentView.Working("…")
                val text = api.transcribe(wav, Locale.getDefault().language)
                if (text.isBlank()) view = AgentView.Failed(R.string.w_agent_heard_nothing) else ask(text)
            } catch (e: Exception) {
                view = AgentView.Failed(if (e.isHidden()) R.string.w_agent_err_whisper else R.string.w_err_reach)
            }
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recordNow() else view = AgentView.Failed(R.string.w_agent_no_mic)
    }
    val prompt = stringResource(R.string.w_agent_prompt)
    fun speakInput() {
        if (Voice.hasRecognizer(context)) {
            dictate.launch(Voice.recognizerIntent(prompt))
        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recordNow()
        } else {
            mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (view == AgentView.Typing) {
        TextEntry(prompt, onDone = { ask(it) }, onCancel = { view = AgentView.Idle })
        return
    }
    AgentScreen(
        view,
        onSpeak = ::speakInput,
        onType = { view = AgentView.Typing },
        onStopRecording = { recording = false },
        onRead = { speak(answerOf(it)) },
        onDecide = ::decide,
        onAgain = { view = AgentView.Idle },
    )
}

@Composable
fun AgentScreen(
    view: AgentView,
    onSpeak: () -> Unit,
    onType: () -> Unit,
    onStopRecording: () -> Unit,
    onRead: (Chat) -> Unit,
    onDecide: (Chat, Boolean) -> Unit,
    onAgain: () -> Unit,
) {
    Page {
        item { Header(stringResource(R.string.w_agent)) }
        when (view) {
            AgentView.Idle -> {
                item { RowButton(stringResource(R.string.w_agent_speak), onClick = onSpeak, icon = TitanIcons.mic, filled = true) }
                item { RowButton(stringResource(R.string.w_agent_type), onClick = onType, icon = TitanIcons.edit) }
            }
            AgentView.Typing -> Unit
            AgentView.Recording -> {
                item { WText(stringResource(R.string.w_agent_listening), center = true) }
                item { RowButton(stringResource(R.string.w_agent_done), onClick = onStopRecording, icon = TitanIcons.check, filled = true) }
            }
            is AgentView.Working -> {
                if (view.question != "…") item { Quote(view.question) }
                item { Busy(stringResource(R.string.w_agent_working)) }
            }
            is AgentView.Answer -> {
                item { Quote(view.question) }
                val answer = answerOf(view.chat)
                val pending = view.chat.pending
                if (answer.isNotBlank()) item { WText(answer, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) }
                if (view.chat.state == "error") item { Problem(view.chat.error ?: stringResource(R.string.w_agent_error)) }
                if (view.chat.state == "waiting" && pending != null) {
                    item { ApprovalCard(pending.summary.ifBlank { pending.tool }, pending.level) }
                    item { RowButton(stringResource(R.string.w_allow), onClick = { onDecide(view.chat, true) }, icon = TitanIcons.check, filled = true) }
                    item { RowButton(stringResource(R.string.w_deny), onClick = { onDecide(view.chat, false) }, icon = TitanIcons.x) }
                }
                if (answer.isNotBlank()) item { RowButton(stringResource(R.string.w_read_aloud), onClick = { onRead(view.chat) }, icon = TitanIcons.play) }
                item { RowButton(stringResource(R.string.w_agent_more), onClick = onAgain, icon = TitanIcons.mic) }
            }
            is AgentView.Failed -> {
                item { Problem(stringResource(view.message)) }
                item { RowButton(stringResource(R.string.w_agent_more), onClick = onAgain, icon = TitanIcons.restart) }
            }
        }
    }
}

@Composable
private fun Quote(text: String) {
    WText("„$text“", color = WatchColors.muted, center = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
}

@Composable
fun ApprovalCard(summary: String, level: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        StateLine(false, stringResource(if (level == "high" || level == "danger") R.string.w_approval_high else R.string.w_approval))
        WText(summary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
