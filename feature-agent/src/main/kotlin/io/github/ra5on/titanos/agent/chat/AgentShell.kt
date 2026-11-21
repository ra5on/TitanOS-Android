package io.github.ra5on.titanos.agent.chat

import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Gibt es auf dem Handy eine Spracherkennung? Ohne Google-Dienste oft nicht: dann kein Mikrofon. */
fun speechAvailable(context: android.content.Context): Boolean =
    context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()

private sealed interface ChatRoute {
    data object Chat : ChatRoute
    data object List : ChatRoute
}

/**
 * Die Agent-App nach der Kopplung: Chat mit Kopf und Eingabe, Liste der
 * Chats, Projekte, Aufträge, Notizen. [ask] ist ein Wunsch von außen
 * (Teilen, Widget); er füllt nur das Eingabefeld, gesendet wird erst nach Tippen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentRoot(
    model: AgentModel,
    ask: AskRequest?,
    onAskTaken: () -> Unit,
    versionName: String,
    hostname: String,
    onUnpair: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(AgentTab.Chat) }
    var route by remember { mutableStateOf<ChatRoute>(ChatRoute.Chat) }
    var headSheet by remember { mutableStateOf(false) }
    var moreSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val voice = remember { speechAvailable(context) }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.agent_copied)
    val shareFailed = stringResource(R.string.agent_err_share)

    LaunchedEffect(Unit) { model.refresh() }

    val hear = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!said.isNullOrEmpty()) model.draft = if (model.draft.isBlank()) said else model.draft.trimEnd() + " " + said
    }
    val prompt = stringResource(R.string.agent_voice_prompt)
    fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try {
            hear.launch(i)
        } catch (_: Exception) {
            // Keine Erkennung (mehr) da: dann eben tippen.
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val a = withContext(Dispatchers.IO) { copyToCache(context, uri) }
            if (a != null) model.addAttachment(a) else Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
        }
    }

    // Ein Wunsch von außen: neuer Chat, Text ins Feld, Bild als Anhang; nie von selbst senden.
    LaunchedEffect(ask) {
        val a = ask ?: return@LaunchedEffect
        tab = AgentTab.Chat
        route = ChatRoute.Chat
        model.newChat(null)
        if (a.text.isNotEmpty()) model.draft = a.text
        a.attachments.forEach(model::addAttachment)
        if (a.failed) Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
        onAskTaken()
        if (a.voice && voice) listen()
    }

    // Beim Tippen keine Leiste unten (wie im Web): mehr Platz für Verlauf und Eingabe.
    val typing = WindowInsets.isImeVisible
    val nav = @Composable {
        if (!typing) AgentNav(tab) {
            if (it == tab && it == AgentTab.Chat) route = ChatRoute.Chat
            tab = it
        }
    }
    if (tab != AgentTab.Chat) BackHandler { tab = AgentTab.Chat }

    // Randlos (ab Android 15 Pflicht) schiebt adjustResize nichts mehr hoch:
    // der Abstand zur Tastatur hält Eingabefeld und Blätter über ihr.
    Box(Modifier.fillMaxSize().imePadding()) {
        when (tab) {
            AgentTab.Chat -> when (route) {
                ChatRoute.List -> {
                    BackHandler { route = ChatRoute.Chat }
                    ChatListScreen(
                        model.visibleChats(query),
                        query,
                        { query = it },
                        model.listOffline,
                        model.cachedAt,
                        onOpen = { model.open(it); route = ChatRoute.Chat },
                        onNew = { model.newChat(); route = ChatRoute.Chat },
                        onBack = { route = ChatRoute.Chat },
                        nav = nav,
                        footer = {
                            SectionHeader(stringResource(R.string.agent_about))
                            Card {
                                ListRow(
                                    stringResource(R.string.agent_about),
                                    sub = stringResource(R.string.agent_about_text, versionName, hostname.ifEmpty { "NAS" }),
                                    icon = { RowIcon(TitanIcons.info) },
                                )
                                ListRow(
                                    stringResource(R.string.agent_unpair),
                                    sub = stringResource(R.string.agent_unpair_text),
                                    icon = { RowIcon(TitanIcons.x, tint = Titan.colors.dangerInk) },
                                    titleColor = Titan.colors.dangerInk,
                                    divider = true,
                                    onClick = { confirmUnpair = true },
                                )
                            }
                        },
                    )
                }
                ChatRoute.Chat -> {
                    if (model.current != null) BackHandler { model.newChat() }
                    ChatScreen(model.ui(voice), remember(model) {
                        object : ChatActions() {
                            override fun menu() {
                                route = ChatRoute.List
                                model.refresh()
                            }
                            override fun head() { headSheet = true }
                            override fun newChat() = model.newChat()
                            override fun more() { moreSheet = true }
                            override fun draft(text: String) { model.draft = text }
                            override fun send() = model.send()
                            override fun stop() = model.stop()
                            override fun attach() = pick.launch(arrayOf("image/*", "text/*", "application/pdf", "*/*"))
                            override fun removeAttachment(a: Attachment) = model.removeAttachment(a)
                            override fun voice() = listen()
                            override fun decide(approve: Boolean) = model.decide(approve)
                            override fun choice(text: String) = model.answerChoice(text)
                            override fun regenerate(answer: Entry) = model.regenerate(answer)
                            override fun copied() { Toast.makeText(context, copied, Toast.LENGTH_SHORT).show() }
                        }
                    }, nav)
                }
            }
            AgentTab.Projects, AgentTab.Planned, AgentTab.Notes -> if (model.status?.ready == false) {
                AgentPage(
                    stringResource(
                        when (tab) {
                            AgentTab.Projects -> R.string.agent_projects_title
                            AgentTab.Planned -> R.string.agent_planned_title
                            else -> R.string.agent_notes_title
                        },
                    ),
                    nav,
                ) { AgentUnavailable(model.status, Modifier.padding(top = 56.dp)) }
            } else {
                OtherTab(model, tab, nav, onOpenChat = { model.open(it); tab = AgentTab.Chat; route = ChatRoute.Chat }) { p ->
                    model.newChat(p.id)
                    tab = AgentTab.Chat
                    route = ChatRoute.Chat
                }
            }
        }
        if (headSheet) HeadSheet(model) { headSheet = false }
        if (moreSheet) {
            val chat = model.current
            Sheet(onDismiss = { moreSheet = false }) {
                if (chat != null) {
                    Text(chat.title, style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                    SheetRow(TitanIcons.pin, stringResource(if (chat.pinned) R.string.agent_unpin else R.string.agent_pin)) {
                        moreSheet = false
                        model.setPinned(!chat.pinned)
                    }
                    SheetRow(TitanIcons.trash, stringResource(R.string.agent_delete), danger = true) {
                        moreSheet = false
                        confirmDelete = true
                    }
                }
            }
        }
        if (confirmDelete) {
            ConfirmDialog(
                title = stringResource(R.string.agent_delete_title),
                text = stringResource(R.string.agent_delete_text, model.current?.title.orEmpty()),
                actions = listOf(
                    DialogAction(stringResource(R.string.agent_delete_do), danger = true) {
                        confirmDelete = false
                        model.deleteCurrent()
                    },
                    DialogAction(stringResource(io.github.ra5on.titanos.ui.R.string.ui_cancel)) { confirmDelete = false },
                ),
                onDismiss = { confirmDelete = false },
            )
        }
        if (confirmUnpair) {
            ConfirmDialog(
                title = stringResource(R.string.agent_unpair),
                text = stringResource(R.string.agent_unpair_text),
                actions = listOf(
                    DialogAction(stringResource(R.string.agent_unpair_do), danger = true) {
                        confirmUnpair = false
                        onUnpair()
                    },
                    DialogAction(stringResource(io.github.ra5on.titanos.ui.R.string.ui_cancel)) { confirmUnpair = false },
                ),
                onDismiss = { confirmUnpair = false },
            )
        }
    }
}

/** Zustand des Chat-Bildschirms aus dem Modell. */
fun AgentModel.ui(voice: Boolean): ChatUi {
    val chat = current
    val p = currentProject
    val sub = listOfNotNull(p?.let { listOfNotNull(p.icon?.takeIf { i -> i.any { !it.isLetterOrDigit() } }, p.name).joinToString(" ") }, shownModel.ifEmpty { null })
    return ChatUi(
        chat = chat,
        live = live,
        busy = busy,
        sending = sending,
        deciding = deciding,
        opening = opening,
        readOnlyBy = author,
        error = error,
        title = chat?.title.orEmpty(),
        subtitle = sub.joinToString(" · "),
        draft = draft,
        attachments = attachments,
        status = status,
        statusProblem = statusProblem,
        offline = listOffline,
        cachedAt = cachedAt,
        voice = voice,
    )
}


@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, selected: Boolean? = null, sub: String? = null, onClick: () -> Unit) {
    val c = Titan.colors
    val said = stringResource(R.string.agent_selected)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)
            .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (danger) c.dangerInk else c.muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = TitanType.body.copy(fontSize = TitanType.rowTitle.fontSize), color = if (danger) c.dangerInk else c.text)
            if (sub != null) Text(sub, style = TitanType.small, color = c.muted)
        }
        if (selected == true) Icon(TitanIcons.check, said, tint = c.accent, modifier = Modifier.size(20.dp))
    }
}

/** Projekt, Modell und Denkstufe (wie das Blatt am Titel im Web). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.HeadSheet(model: AgentModel, onDismiss: () -> Unit) {
    val c = Titan.colors
    val status = model.status
    val activeProject = model.current?.project ?: model.project
    Sheet(onDismiss = onDismiss) {
        Text(stringResource(R.string.agent_project), style = TitanType.section, color = c.muted, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        for (p in model.projects) {
            SheetRow(TitanIcons.folder, listOfNotNull(p.icon?.takeIf { i -> i.any { !it.isLetterOrDigit() } }, p.name).joinToString(" "), selected = p.id == activeProject) {
                model.chooseProject(p.id)
                onDismiss()
            }
        }
        SheetRow(TitanIcons.x, stringResource(R.string.agent_no_project), selected = activeProject == null) {
            model.chooseProject(null)
            onDismiss()
        }
        Text(stringResource(R.string.agent_model), style = TitanType.section, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp))
        val usable = status?.models.orEmpty().filter { it.usable }
        val chosen = model.model.ifEmpty { status?.model.orEmpty() }
        if (usable.isEmpty()) Text(stringResource(R.string.agent_models_none), style = TitanType.body, color = c.muted, modifier = Modifier.padding(6.dp))
        for (m in usable) {
            val caps = listOfNotNull(
                if (m.size > 0) io.github.ra5on.titanos.ui.Format.bytes(m.size) else null,
                if ("thinking" in m.capabilities) stringResource(R.string.agent_cap_thinking) else null,
                if ("vision" in m.capabilities) stringResource(R.string.agent_cap_vision) else null,
            ).joinToString(" · ")
            SheetRow(TitanIcons.chip, if (m.name == status?.model) stringResource(R.string.agent_model_default, m.shown) else m.shown, selected = m.name == chosen, sub = caps.ifEmpty { null }) {
                model.chooseModel(m.name)
            }
        }
        val levels = usable.firstOrNull { it.name == chosen }?.levels.orEmpty()
        if (levels.isNotEmpty()) {
            Text(stringResource(R.string.agent_think), style = TitanType.section, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                io.github.ra5on.titanos.ui.components.Chip(stringResource(R.string.agent_think_default), on = model.think.isEmpty()) { model.chooseThink("") }
                for (l in levels) io.github.ra5on.titanos.ui.components.Chip(levelLabel(l), on = model.think == l) { model.chooseThink(l) }
            }
        }
    }
}

@Composable
private fun ProjectsTab(model: AgentModel, nav: @Composable () -> Unit, onOpenChat: (ChatInfo) -> Unit, onNewChat: (Project) -> Unit) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val open = openId?.let { id -> model.projects.firstOrNull { it.id == id } }
    if (open != null) {
        BackHandler { openId = null }
        ProjectChatsScreen(open, model.visibleChats(inProject = open.id), onOpenChat, { onNewChat(open) }, { openId = null }, nav)
    } else {
        ProjectsScreen(
            model.projects,
            model.chats,
            model.tabProblem,
            creating,
            name,
            { name = it },
            onStartCreate = { creating = true },
            onCreate = {
                model.createProject(name) {
                    creating = false
                    name = ""
                }
            },
            onCancelCreate = { creating = false; name = "" },
            onOpen = { openId = it.id },
            nav = nav,
        )
    }
}

/** Projekte, Aufträge und Notizen. */
@Composable
private fun OtherTab(model: AgentModel, tab: AgentTab, nav: @Composable () -> Unit, onOpenChat: (ChatInfo) -> Unit, onNewChat: (Project) -> Unit) {
    when (tab) {
        AgentTab.Projects -> ProjectsTab(model, nav, onOpenChat, onNewChat)
        AgentTab.Planned -> {
            var started by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { model.loadSchedules() }
            PlannedScreen(
                model.schedules?.map { it.schedule() },
                model.tabProblem,
                started,
                onToggle = { s, on -> model.schedules?.firstOrNull { it.schedule().id == s.id }?.let { model.setScheduleEnabled(it, on) } },
                onRun = { s -> model.runSchedule(s.id) { started = true } },
                nav = nav,
            )
        }
        AgentTab.Notes -> {
            val open = model.note
            if (open != null) {
                BackHandler { model.closeNote() }
                NoteEditor(open, model.noteSaved, model.tabProblem, model::editNote, model::saveNote, model::closeNote)
            } else {
                var noteQuery by rememberSaveable { mutableStateOf("") }
                LaunchedEffect(Unit) { model.loadNotes() }
                val untitled = stringResource(R.string.agent_notes_untitled)
                NotesScreen(model.notes, noteQuery, { noteQuery = it }, model.tabProblem, { model.openNote(it.path) }, { model.newNote(untitled) }, nav)
            }
        }
        AgentTab.Chat -> Unit
    }
}
