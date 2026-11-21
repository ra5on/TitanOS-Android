package io.github.ra5on.titanos.agent.app

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.Attachment
import io.github.ra5on.titanos.agent.chat.Block
import io.github.ra5on.titanos.agent.chat.ChatActions
import io.github.ra5on.titanos.agent.chat.ChatInfo
import io.github.ra5on.titanos.agent.chat.ChatView
import io.github.ra5on.titanos.agent.chat.Composer
import io.github.ra5on.titanos.agent.chat.ComposerActions
import io.github.ra5on.titanos.agent.chat.Entry
import io.github.ra5on.titanos.agent.chat.MutableEditing
import io.github.ra5on.titanos.agent.chat.NasPicture
import io.github.ra5on.titanos.agent.chat.PatchChat
import io.github.ra5on.titanos.agent.chat.PhotoHit
import io.github.ra5on.titanos.agent.chat.Project
import io.github.ra5on.titanos.agent.chat.StepList
import io.github.ra5on.titanos.agent.chat.copyToCache
import io.github.ra5on.titanos.agent.chat.dateTime
import io.github.ra5on.titanos.agent.chat.levelText
import io.github.ra5on.titanos.agent.chat.photoLabel
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.chat.argLines
import io.github.ra5on.titanos.agent.chat.preview
import io.github.ra5on.titanos.agent.chat.stepOutput
import io.github.ra5on.titanos.agent.core.LocalCalm
import io.github.ra5on.titanos.agent.core.LocalTr
import io.github.ra5on.titanos.agent.core.Motion
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.systemReducedMotion
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.figure.FigureClock
import io.github.ra5on.titanos.agent.figure.stepLabel
import io.github.ra5on.titanos.agent.home.HomePage
import io.github.ra5on.titanos.agent.features.FeatureView
import io.github.ra5on.titanos.agent.pages.KnowledgePage
import io.github.ra5on.titanos.agent.pages.MemoryPage
import io.github.ra5on.titanos.agent.pages.NasPicker
import io.github.ra5on.titanos.agent.pages.NotesPanel
import io.github.ra5on.titanos.agent.pages.PlannedPage
import io.github.ra5on.titanos.agent.pages.ProjectDialog
import io.github.ra5on.titanos.agent.pages.ProjectList
import io.github.ra5on.titanos.agent.pages.ProjectSheet
import io.github.ra5on.titanos.agent.settings.SettingsPage
import io.github.ra5on.titanos.agent.ui.AgentColors
import io.github.ra5on.titanos.agent.ui.AgentDialog
import io.github.ra5on.titanos.agent.ui.AgentSheet
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.Check
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.FloatButton
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.Scrim
import io.github.ra5on.titanos.agent.ui.Segments
import io.github.ra5on.titanos.agent.ui.SheetGroup
import io.github.ra5on.titanos.agent.ui.SheetItem
import io.github.ra5on.titanos.agent.ui.SheetQuote
import io.github.ra5on.titanos.agent.ui.SheetRule
import io.github.ra5on.titanos.agent.ui.SlidePage
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.agent.ui.rememberLast
import io.github.ra5on.titanos.agent.voice.Speech
import io.github.ra5on.titanos.agent.voice.VoiceMode
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Die Seiten des Agenten neben dem Chat (AGENT_VIEWS). */
enum class AgentView(val key: String) { Chat("chat"), Planned("planned"), Memory("memory"), Knowledge("knowledge"), Settings("settings") }

/** Was über dem Chat liegt: ein Blatt, ein Dialog oder eine Seite (dialog im Web). */
sealed interface Layer {
    data object Head : Layer
    data object Plus : Layer
    data class Message(val entry: Entry) : Layer
    data class Steps(val block: Block.Steps) : Layer
    data class Actions(val chat: ChatInfo) : Layer
    data object Archive : Layer
    data class Move(val chat: ChatInfo) : Layer
    data class Rename(val chat: ChatInfo) : Layer
    data class Delete(val chat: ChatInfo) : Layer
    data object DeleteAll : Layer
    data object More : Layer
    data class Photo(val photos: List<PhotoHit>, val at: Int) : Layer
    data object Notes : Layer
    data object Talk : Layer
    data object Nas : Layer
    data class ProjectOpen(val project: Project) : Layer
    data class ProjectEdit(val project: Project?) : Layer
    data class Feature(val id: String) : Layer
}

/** Was nur die App weiß: Version, Name des NAS, Entkoppeln, die TitanOS-App. */
class AppHooks(
    val versionName: String = "",
    val hostname: String = "",
    val onUnpair: () -> Unit = {},
    val user: String = "",
)

/** Wohin die App gerade zeigt: Seite, Teil, Menü, Ebene (agent-page.svelte.ts). */
class AgentNav {
    var view by mutableStateOf(AgentView.Chat)
    var part by mutableStateOf("")
    var menuOpen by mutableStateOf(false)
    var projectsTab by mutableStateOf(false)
    var layer by mutableStateOf<Layer?>(null)
    var memoryQuery by mutableStateOf("")
    var memoryDraft by mutableStateOf("")
    /** Die Richtung des letzten Wechsels: tiefer (von rechts) oder zurück. */
    var deeper by mutableStateOf(true)

    fun show(v: AgentView, p: String = "") {
        deeper = v != AgentView.Chat && (v != view || p.isNotEmpty())
        menuOpen = false
        if (layer is Layer.Feature) layer = null
        projectsTab = false
        view = v
        part = p
        if (v != AgentView.Memory) memoryDraft = ""
    }

    fun back(): Boolean {
        when {
            part.isNotEmpty() -> { deeper = false; part = "" }
            view != AgentView.Chat -> { deeper = false; view = AgentView.Chat }
            projectsTab -> projectsTab = false
            else -> return false
        }
        return true
    }
}

/**
 * Die Agent-App nach der Kopplung, aufgebaut wie die Handy-Seite des Webs
 * (agent/+page.svelte): schwebender Kopf über Chat und Startseite, Seiten mit
 * Kopf „zurück“, Eingabe und Leiste unten (Chat, Projekte, Aufträge,
 * Notizen), das Menü von links, Blätter von unten.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentShell(model: AgentModel, nav: AgentNav, hooks: AppHooks, speech: Speech, tr: Tr) {
    val context = LocalContext.current
    val reduced = systemReducedMotion()
    val calm = model.calm || reduced
    CompositionLocalProvider(LocalTr provides tr, LocalCalm provides calm) {
        ShellBody(model, nav, hooks, speech)
    }
    LaunchedEffect(Unit) {
        model.vibrate = { p -> vibrate(context, p) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShellBody(model: AgentModel, nav: AgentNav, hooks: AppHooks, speech: Speech) {
    val c = Titan.colors
    val tr = tr()
    val calm = LocalCalm.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val density = LocalDensity.current
    val editing = remember { MutableEditing() }
    val clock = remember { FigureClock() }
    var headHeight by remember { mutableStateOf(0.dp) }
    val keyboard = WindowInsets.isImeVisible

    LaunchedEffect(Unit) { model.refresh() }
    // Andere laufende Chats: alle 4 s /agent/runs, bis acht Antworten ohne Lauf kamen (1.01).
    LaunchedEffect(model.othersRunning, model.listsReady) {
        if (!model.othersRunning || !model.listsReady) return@LaunchedEffect
        var quiet = 0
        while (isActive && quiet < 8) {
            delay(4000)
            quiet = if (model.checkRuns()) 0 else quiet + 1
        }
    }
    // Der offene Chat ist zu sehen: alle 15 s sagt die App es dem NAS.
    val openId = model.current?.id
    LaunchedEffect(openId, model.author) {
        if (openId == null || model.author != null) return@LaunchedEffect
        try {
            while (isActive) { delay(15_000); model.seen(openId, true) }
        } finally { model.seen(openId, false) }
    }
    // Eine neue Antwort vorlesen, wenn gewünscht (Darstellung).
    LaunchedEffect(Unit) {
        model.onAnswer = { e -> if (model.look.read && nav.layer != Layer.Talk) speech.speak(e.id, e.content.orEmpty()) }
    }

    // Anhänge: Foto, Datei vom Gerät.
    val failed = tr.t("agent.attachFailed")
    fun take(uris: List<android.net.Uri>) {
        scope.launch {
            for (u in uris) {
                val a = withContext(Dispatchers.IO) { copyToCache(context, u) }
                if (a == null) model.error = io.github.ra5on.titanos.agent.chat.AgentProblem.Other(failed)
                else if (a.file != null && a.file.length() > 20L * 1024 * 1024) { a.file.delete(); model.say(tr.t("agent.attachTooBig", "names" to a.name)) }
                else model.addAttachment(freeName(a, model.attachments))
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { take(it) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { take(it) }
    var exportFormat by remember { mutableStateOf<Pair<String, String>?>(null) }
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val job = exportFormat ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val q = buildList { add("lang" to tr.lang); if (job.second == "pdf") add("format" to "pdf") }
                val (bytes, _) = model.api.bytes("agent/chats/${job.first}/export", q)
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                model.say(tr.t("agent.exported"))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { model.error = io.github.ra5on.titanos.agent.chat.problemOf(e) }
        }
    }
    fun export(info: ChatInfo, pdf: Boolean) {
        exportFormat = info.id to if (pdf) "pdf" else "md"
        val name = info.title.ifEmpty { "chat" }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        saveExport.launch(if (pdf) "$name.pdf" else "$name.md")
    }

    val chatActs = ChatActions(
        onHold = { nav.layer = Layer.Message(it) },
        onSteps = { nav.layer = Layer.Steps(it) },
        onPhoto = { list, i -> nav.layer = Layer.Photo(list, i) },
        onPlanned = { nav.show(AgentView.Planned) },
        onOpenFile = { f -> openTitanOS(context, "/files?root=${android.net.Uri.encode(f.root)}&path=${android.net.Uri.encode(f.path.substringBeforeLast('/', ""))}") },
        speak = { e -> speech.speak(e.id, e.content.orEmpty()) },
        speaking = { speech.speakingId },
        canSpeak = speech.canSpeak(model),
        picture = { id, size -> runCatching { model.api.bytes("agent/photos/$id/picture", listOf("size" to size)).first }.getOrNull() },
    )

    fun command(id: String, arg: String) {
        when (id) {
            "new" -> model.newChat(asFresh = true)
            "project" -> {
                val wanted = arg.takeIf { it.isNotEmpty() }?.let { a -> model.projects.firstOrNull { it.name.equals(a, ignoreCase = true) } }
                if (wanted != null) model.chooseProject(wanted.id) else nav.layer = Layer.Head
            }
            "model" -> nav.layer = Layer.Head
            "remember" -> if (arg.isNotEmpty()) model.remember(arg, tr.t("agent.cmd.remembered")) else nav.show(AgentView.Memory)
            "plan" -> if (arg.isNotEmpty()) model.send(tr.t("agent.cmd.planAsk", "text" to arg)) else nav.show(AgentView.Planned)
            "find" -> if (model.current != null) model.find = arg else model.say(tr.t("agent.cmd.findNoChat"))
            "status" -> if (model.busy) model.say(tr.t("agent.busyWait")) else model.send(tr.t("agent.suggestHealth"))
        }
    }

    val ready = model.ready
    val onPage = nav.view != AgentView.Chat

    val width = currentWidth()
    val menuActs = MenuActions(
        onShow = { v -> if (v == AgentView.Chat) { model.newChat(null); nav.show(AgentView.Chat) } else nav.show(v) },
        onNew = { nav.menuOpen = false; model.newChat(null, asFresh = true); nav.show(AgentView.Chat) },
        onOpen = { nav.menuOpen = false; model.open(it); nav.show(AgentView.Chat) },
        onNewProject = { nav.layer = Layer.ProjectEdit(null) },
        onEditProject = { nav.layer = Layer.ProjectEdit(it) },
        onNotes = { nav.menuOpen = false; nav.layer = Layer.Notes },
        onArchive = { nav.menuOpen = false; nav.layer = Layer.Archive },
        onFeature = { nav.menuOpen = false; nav.layer = Layer.Feature(it) },
        onSearchMemory = { q -> nav.memoryQuery = q; nav.show(AgentView.Memory) },
        onChatAction = { chat, a -> model.buzz(12); chatAction(model, chat, a) { kind -> nav.layer = if (kind == "delete") Layer.Delete(chat) else Layer.Actions(chat) } },
        onTitanOS = if (titanOSInstalled(context)) ({ openTitanOS(context, null) }) else null,
    )

    Box(Modifier.fillMaxSize().background(c.bg).imePadding().agentKeys(model, nav).dropFiles(context, model, scope)) {
      Row(Modifier.fillMaxSize()) {
        if (ready && width.twoPanes) Sidebar(model, nav, menuActs)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val status = model.status
                if (!ready) {
                    Unavailable(model)
                } else {
                    AnimatedContent(
                        targetState = Triple(nav.view, nav.part, nav.projectsTab),
                        transitionSpec = {
                            if (calm) fadeIn(Motion.spec(true, 0)) togetherWith fadeOut(Motion.spec(true, 0))
                            else if (nav.deeper) (slideInHorizontally(Motion.spec(false, Motion.SLIDE)) { it } togetherWith slideOutHorizontally(Motion.spec(false, Motion.SLIDE)) { -it / 4 } + fadeOut(Motion.spec(false, 300)))
                            else (slideInHorizontally(Motion.spec(false, Motion.SLIDE)) { -it / 4 } + fadeIn(Motion.spec(false, 300)) togetherWith slideOutHorizontally(Motion.spec(false, Motion.SLIDE)) { it })
                        },
                        label = "page",
                    ) { (view, part, projectsTab) ->
                        Box(Modifier.fillMaxSize().background(c.bg)) {
                            when {
                                view == AgentView.Chat && projectsTab -> Column(Modifier.fillMaxSize()) {
                                    Spacer(headHeight)
                                    ProjectList(model, onOpen = { nav.layer = Layer.ProjectOpen(it) }, onNew = { nav.layer = Layer.ProjectEdit(null) })
                                }
                                view == AgentView.Chat -> {
                                    val chat = model.current
                                    if (chat != null || model.opening) {
                                        if (chat != null) ChatView(model, chatActs, headHeight, editing, clock)
                                        else Text(tr.t("common.loading"), style = AgentText.md, color = c.muted, modifier = Modifier.padding(top = headHeight + 16.dp, start = 16.dp))
                                    } else {
                                        HomePage(
                                            model, fresh = model.fresh, user = hooks.user.ifEmpty { "" }, top = headHeight,
                                            onOpen = { model.open(it) }, onFill = { model.draft = it }, onAsk = { model.newChat(null); model.send(it) },
                                            onPlanned = { nav.show(AgentView.Planned) },
                                            onTitanOS = { id -> openTitanOS(context, mapOf("space" to "/storage", "raid" to "/storage", "backups" to "/settings/backups", "apps" to "/docker", "updates" to "/settings/updates")[id]) },
                                        )
                                    }
                                }
                                else -> Column(Modifier.fillMaxSize()) {
                                    io.github.ra5on.titanos.agent.ui.PageHead(
                                        when (view) {
                                            AgentView.Planned -> tr.t("agent.planned")
                                            AgentView.Memory -> tr.t("agent.memory")
                                            AgentView.Knowledge -> tr.t("agent.knowledge")
                                            else -> tr.t("agent.set.title")
                                        },
                                        onBack = { nav.back() },
                                        modifier = Modifier.statusBarsPadding(),
                                    )
                                    Box(Modifier.weight(1f)) {
                                        when (view) {
                                            AgentView.Planned -> PlannedPage(model, onOpenChat = { model.open(it); nav.show(AgentView.Chat) })
                                            AgentView.Memory -> MemoryPage(model, nav.memoryQuery, nav.memoryDraft, onOpenChat = { model.open(it); nav.show(AgentView.Chat) })
                                            AgentView.Knowledge -> KnowledgePage(model, onEditProject = { nav.layer = Layer.ProjectEdit(it) }, onOpenProject = { nav.layer = Layer.ProjectOpen(it) })
                                            else -> SettingsPage(model, part, onPart = { nav.show(AgentView.Settings, it) }, hooks = hooks, speech = speech, clock = clock,
                                                onDeleteAll = { nav.layer = Layer.DeleteAll }, onFeature = { nav.layer = Layer.Feature(it) },
                                                onKnow = { nav.show(AgentView.Chat); model.newChat(null); model.send(tr.t("agent.knowAsk")) })
                                        }
                                    }
                                }
                            }
                            if (status?.reachable == false && view == AgentView.Chat && !projectsTab) Notice(tr.t("agent.ollamaDownLong", "error" to (status.error ?: "")), tone = "warn", modifier = Modifier.padding(top = headHeight + 4.dp, start = 12.dp, end = 12.dp))
                        }
                    }
                    // Der schwebende Kopf über Chat und Startseite.
                    if (!onPage) {
                        ChatHead(model, nav, Modifier.onSizeChanged { headHeight = with(density) { it.height.toDp() } }, onMore = { nav.layer = Layer.More }, wide = width.twoPanes)
                    }
                }
            }
            if (ready && !onPage && !nav.projectsTab) {
                if (model.error != null) Notice(problemText(tr, model.error!!), tone = "error", modifier = Modifier.padding(horizontal = 12.dp).clickable { model.error = null })
                Composer(
                    model,
                    ComposerActions(
                        onPlus = { nav.layer = Layer.Plus },
                        onMic = { speech.micDown(model) },
                        onMicUp = { speech.micUp(model) },
                        onCommand = ::command,
                        onHead = { nav.layer = Layer.Head },
                    ),
                    voice = speech.canHear(model),
                    recording = speech.recording,
                    transcribing = speech.transcribing,
                    micHold = model.look.mic == "hold",
                )
            }
            if (ready && !keyboard && !width.sidebar && (nav.view == AgentView.Chat || nav.view == AgentView.Planned)) {
                Tabs(nav, model)
            } else if (!keyboard) Box(Modifier.navigationBarsPadding())
        }
      }

        // Wischen vom linken Rand öffnet das Menü (Einstellungen › Gesten).
        if (ready && !width.twoPanes && model.look.gestures && model.look.edge && nav.layer == null && !nav.menuOpen) {
            Box(Modifier.fillMaxHeight().width(20.dp).pointerInput(Unit) {
                detectHorizontalDragGestures { _, dx -> if (dx > 12) nav.menuOpen = true }
            })
        }
        // Zurück: Ebene, Menü, Suche, Teil, Seite, Chat → Startseite.
        BackHandler(enabled = nav.layer == null && !nav.menuOpen && (model.find != null || nav.view != AgentView.Chat || nav.projectsTab || nav.part.isNotEmpty() || model.current != null || model.fresh)) {
            when {
                model.find != null -> model.find = null
                nav.back() -> Unit
                else -> model.newChat(null)
            }
        }

        // ---------- das Menü von links ----------
        Scrim(nav.menuOpen && !width.twoPanes) { nav.menuOpen = false }
        AnimatedVisibility(
            nav.menuOpen && !width.twoPanes,
            enter = slideInHorizontally(Motion.spec(calm, Motion.SLIDE)) { -it },
            exit = slideOutHorizontally(Motion.spec(calm, 320)) { -it },
        ) {
            BackHandler { nav.menuOpen = false }
            Box(Modifier.fillMaxHeight().pointerInput(Unit) { detectHorizontalDragGestures { _, dx -> if (dx < -14) nav.menuOpen = false } }) {
                AgentMenu(model, nav.view, start = model.current == null && !model.fresh, menuActs)
            }
        }

        Layers(model, nav, speech, editing, chatActs, ::export, pickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, pickFile = { pickFile.launch(arrayOf("*/*")) }, clip = { clip.setText(AnnotatedString(it)) })
    }
}

@Composable
private fun Spacer(h: Dp) = Box(Modifier.heightIn(min = h).fillMaxWidth())

/** Gibt einem Anhang einen freien Namen („name (2)“), wie freeName im Web. */
private fun freeName(a: Attachment, taken: List<Attachment>): Attachment {
    val names = taken.filter { it.file != null }.map { it.name }.toSet()
    if (a.name !in names) return a
    val dot = a.name.lastIndexOf('.').takeIf { it > 0 } ?: a.name.length
    var n = 2
    while (true) {
        val name = a.name.substring(0, dot) + " ($n)" + a.name.substring(dot)
        if (name !in names) return a.copy(name = name)
        n++
    }
}

/** Ohne Agent: aus, nicht erlaubt, kein Volume, nicht erreichbar – mit Worten des Webs. */
@Composable
private fun Unavailable(model: AgentModel) {
    val c = Titan.colors
    val tr = tr()
    val s = model.status
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        val (title, text) = when {
            s == null && model.statusProblem != null -> tr.t("agent.loadFailed").let { if (it == "agent.loadFailed") tr.t("error.internal") else it } to problemText(tr, model.statusProblem!!)
            s == null -> tr.t("agent.loading") to null
            !s.enabled -> tr.t("agent.offTitle") to if (s.admin) tr.t("agent.offAdmin") else tr.t("agent.offUser")
            !s.allowed -> tr.t("agent.notAllowedTitle") to tr.t("agent.notAllowed")
            else -> tr.t("agent.noVolumeTitle") to tr.t("agent.noVolume")
        }
        WIcon("sparkle", 32.dp, c.muted)
        Text(title, style = AgentText.lg.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = c.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        if (text != null) Text(text, style = AgentText.md, color = c.muted, textAlign = TextAlign.Center)
        if (model.statusProblem != null || (s != null && !s.ready)) PillButton(tr.t("common.retry"), { model.refresh() })
    }
}

/** Der Kopf am Handy: Menü (mit Punkt), Titel in der Kapsel (Projekt · Modell), Neuer Chat, ⋯. */
@Composable
private fun ChatHead(model: AgentModel, nav: AgentNav, modifier: Modifier, onMore: () -> Unit, wide: Boolean = false) {
    val c = Titan.colors
    val tr = tr()
    val chat = model.current
    Box(modifier.fillMaxWidth().background(Brush.verticalGradient(0f to c.bg, 0.62f to c.bg.copy(alpha = 0.82f), 1f to Color.Transparent))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!wide) FloatButton("menu", tr.t("agent.menuOpen"), { nav.menuOpen = true }, dot = model.chats.any { it.unread || it.state == "waiting" })
            if (nav.projectsTab) Box(Modifier.weight(1f)) else {
                val shape = RoundedCornerShape(999.dp)
                val title = chat?.title?.ifEmpty { null } ?: tr.t("nav.agent")
                Row(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(shape).background(AgentColors.float).border(1.dp, c.border, shape)
                        .clickable(role = Role.Button) { nav.layer = Layer.Head }
                        .semantics { contentDescription = tr.t("agent.head.open", "title" to title) }
                        .padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, style = AgentText.md.copy(fontWeight = FontWeight.SemiBold, lineHeight = 17.sp), color = c.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                        val p = model.currentProject
                        Text(
                            (if (p != null) "${p.icon ?: ""} ${p.name} · " else "") + model.shownModel.ifEmpty { "–" },
                            style = AgentText.sm.copy(fontSize = 11.5.sp, lineHeight = 14.sp), color = c.muted, textAlign = TextAlign.Center,
                        )
                    }
                    WIcon("chevronDown", 16.dp, c.muted)
                }
            }
            FloatButton("newChat", tr.t("agent.newChat"), { model.newChat(null, asFresh = true); nav.show(AgentView.Chat) })
            if (wide && model.current != null) FloatButton("search", tr.t("agent.findInChat"), { model.find = if (model.find == null) "" else null })
            if (wide) model.currentInfo?.let { info -> FloatButton("pin", if (info.pinned) tr.t("agent.unpin") else tr.t("agent.pin"), { model.patch(info, PatchChat(pinned = !info.pinned)) }) }
            if (model.currentInfo != null) FloatButton("more", tr.t("ui.more"), onMore)
        }
    }
}

/** Die Leiste unten (15 Nov 2026): Chat, Projekte, Aufträge, Notizen. */
@Composable
private fun Tabs(nav: AgentNav, model: AgentModel) {
    val c = Titan.colors
    val tr = tr()
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 6.dp, top = 2.dp)
            .clip(shape).background(AgentColors.float).border(1.dp, c.border, shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        @Composable
        fun tab(icon: String, label: String, on: Boolean, count: Int = 0, onClick: () -> Unit) {
            Column(
                Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(22.dp)).background(if (on) c.bg else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = onClick).semantics { selected = on; if (count > 0) contentDescription = "$label $count" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                WIcon(icon, 21.dp, if (on) c.text else c.muted)
                Text(label, style = AgentText.sm.copy(fontSize = 11.sp), color = if (on) c.text else c.muted, textAlign = TextAlign.Center)
            }
        }
        tab("chat", tr.t("agent.tabs.chat"), nav.view == AgentView.Chat && !nav.projectsTab) { nav.projectsTab = false; if (nav.view != AgentView.Chat) nav.show(AgentView.Chat) }
        tab("folder", tr.t("agent.projects"), nav.projectsTab) { if (nav.view != AgentView.Chat) nav.show(AgentView.Chat); nav.projectsTab = true }
        tab("clock", tr.t("agent.tabs.planned"), nav.view == AgentView.Planned, model.plannedCount) { nav.show(AgentView.Planned) }
        tab("edit", tr.t("agent.notes.title"), false) { nav.layer = Layer.Notes }
    }
}

/** Alle Blätter, Dialoge und Seiten über dem Chat. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.Layers(
    model: AgentModel,
    nav: AgentNav,
    speech: Speech,
    editing: MutableEditing,
    chatActs: ChatActions,
    export: (ChatInfo, Boolean) -> Unit,
    pickPhoto: () -> Unit,
    pickFile: () -> Unit,
    clip: (String) -> Unit,
) {
    val c = Titan.colors
    val tr = tr()
    val layer = nav.layer
    val close = { nav.layer = null }

    // Projekt, Modell und Denkstufe (HeadSheet).
    AgentSheet(layer == Layer.Head, close, tr.t("agent.head.title")) {
        val activeProject = model.current?.project ?: model.project
        if (model.author == null) {
            SheetGroup(tr.t("agent.head.project"))
            for (p in model.projects) SheetItem(null, p.name, { model.chooseProject(p.id); close() }, selected = p.id == activeProject, emoji = p.icon?.ifEmpty { null } ?: "📁")
            SheetItem("close", tr.t("agent.noProject"), { model.chooseProject(null); close() }, selected = activeProject == null)
            SheetRule()
        }
        SheetGroup(tr.t("agent.model"))
        val usable = model.usable
        if (usable.isEmpty()) SheetQuote(tr.t("agent.noToolModel"))
        val chosen = model.shownModelName
        for (m in usable) {
            val caps = listOfNotNull(Format.bytes(m.size), if ("vision" in m.capabilities) tr.t("agent.capVision") else null, if ("thinking" in m.capabilities) tr.t("agent.capThinking") else null).joinToString(" · ")
            SheetItem("chip", m.shown, { model.chooseModel(m.name) }, sub = if (m.label.isNullOrEmpty()) caps else "${m.name} · $caps", selected = m.name == chosen)
        }
        val levels = usable.firstOrNull { it.name == chosen }?.levels.orEmpty()
        if (levels.isNotEmpty()) {
            SheetGroup(tr.t("agent.think"))
            Segments(levels.map { it to levelText(tr, it) }, model.shownThink, { model.chooseThink(it) }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), compact = true, fill = true)
        }
    }

    // Das Plus der Eingabe (Composer: Foto, Datei, vom NAS, Sprechmodus, Kopf, Arbeitsweise).
    AgentSheet(layer == Layer.Plus, close, tr.t("agent.plus.title")) {
        SheetGroup(tr.t("agent.plus.title"))
        SheetItem("camera", tr.t("agent.attachPhoto"), { close(); pickPhoto() })
        SheetItem("upload", tr.t("agent.attachDevice"), { close(); pickFile() })
        SheetItem("folder", tr.t("agent.attachNas"), { nav.layer = Layer.Nas })
        if (speech.canHear(model) || speech.deviceHear) {
            SheetRule()
            SheetItem("mic", tr.t("agent.talk.open"), { nav.layer = Layer.Talk })
        }
        SheetRule()
        SheetItem("chip", tr.t("agent.head.title"), { nav.layer = Layer.Head }, sub = model.currentProject?.name ?: tr.t("agent.noProject"), chevron = true)
        if (model.author == null) {
            SheetRule()
            SheetGroup(tr.t("agent.autonomy.title"))
            for (way in listOf("direct", "plan", "ask")) {
                SheetItem(if (way == "direct") "zap" else if (way == "plan") "list" else "shield", tr.t("agent.autonomy.$way"), { close(); model.chooseAutonomy(way) }, sub = tr.t("agent.autonomy.${way}Hint"), selected = model.autonomy == way)
            }
        }
    }

    // Die Aktionen einer eigenen Nachricht (MessageSheet).
    val msg = rememberLast((layer as? Layer.Message)?.entry)
    AgentSheet(layer is Layer.Message, close, tr.t("agent.messageActions")) {
        val e = msg ?: return@AgentSheet
        val canChange = model.author == null && !model.busy
        val last = model.current?.entries?.lastOrNull { it.role == "user" }?.id == e.id
        SheetQuote("„" + e.content.orEmpty() + "“")
        if (canChange) SheetItem("edit", tr.t("agent.editResend"), { close(); editing.start(e) })
        SheetItem("copy", tr.t("agent.copy"), { close(); clip(e.content.orEmpty()); model.say(tr.t("agent.copiedShort")) })
        if (canChange && last) SheetItem("refresh", tr.t("agent.regenerateAnswer"), { close(); model.resend(e) })
        SheetItem("search", tr.t("agent.findInChat"), { close(); model.find = "" })
        SheetItem("bulb", tr.t("agent.rememberThis"), { close(); nav.memoryDraft = e.content.orEmpty(); nav.show(AgentView.Memory); nav.memoryDraft = e.content.orEmpty() })
    }

    // Zusammenfassung der Schritte (StepsSheet) mit zweiter Ebene je Schritt.
    val steps = rememberLast((layer as? Layer.Steps)?.block)
    AgentSheet(layer is Layer.Steps, close, tr.t("agent.stepsSummary")) {
        val b = steps ?: return@AgentSheet
        StepsSheetBody(model, b, close)
    }

    // ⋯ des offenen Chats (chatMenu).
    AgentSheet(layer == Layer.More, close, tr.t("ui.more")) {
        val info = model.currentInfo ?: return@AgentSheet
        Text(info.title.ifEmpty { tr.t("agent.newChat") }, style = AgentText.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        ChatActionItems(model, nav, info, export, close, find = true)
    }
    // Aktionen eines Chats aus der Liste (ChatSheets: actions).
    val acts = rememberLast((layer as? Layer.Actions)?.chat)
    AgentSheet(layer is Layer.Actions, close, acts?.title ?: "") {
        val info = acts ?: return@AgentSheet
        Text(info.title.ifEmpty { tr.t("agent.newChat") }, style = AgentText.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        ChatActionItems(model, nav, info, export, close, find = false)
    }
    // Archiv.
    AgentSheet(layer == Layer.Archive, close, tr.t("agent.archive.title")) {
        SheetGroup(tr.t("agent.archive.title"))
        val list = model.chats.filter { it.archived && it.author == null }.sortedByDescending { it.updated }
        if (list.isEmpty()) SheetQuote(tr.t("agent.archive.empty"))
        for (ch in list) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                Text(ch.title.ifEmpty { tr.t("agent.newChat") }, style = AgentText.lg, color = c.text)
                Text(dateTime(ch.updated), style = AgentText.sm, color = c.muted)
                io.github.ra5on.titanos.agent.ui.Buttons(Modifier.padding(top = 6.dp)) {
                    PillButton(tr.t("agent.archive.open"), { close(); model.open(ch); nav.show(AgentView.Chat) }, small = true)
                    PillButton(tr.t("agent.archive.restore"), { model.patch(ch, PatchChat(archived = false)) }, small = true)
                }
            }
        }
    }
    // In Projekt verschieben.
    val moving = rememberLast((layer as? Layer.Move)?.chat)
    AgentSheet(layer is Layer.Move, close, tr.t("agent.moveToProject")) {
        val info = moving ?: return@AgentSheet
        SheetGroup(tr.t("agent.moveToProject"))
        SheetItem("close", tr.t("agent.noProject"), { close(); model.patch(info, PatchChat(project = "")) }, selected = info.project == null)
        for (p in model.projects) SheetItem(null, p.name, { close(); model.patch(info, PatchChat(project = p.id)) }, selected = info.project == p.id, emoji = p.icon?.ifEmpty { null } ?: "📁")
    }
    // Umbenennen.
    val renaming = rememberLast((layer as? Layer.Rename)?.chat)
    var title by remember(renaming?.id) { mutableStateOf(renaming?.title.orEmpty()) }
    AgentDialog(layer is Layer.Rename, tr.t("agent.rename"), close, actions = {
        PillButton(tr.t("common.cancel"), close)
        PillButton(tr.t("common.save"), { renaming?.let { model.patch(it, PatchChat(title = title.trim().take(200))) }; close() }, kind = Btn.Primary, enabled = title.isNotBlank())
    }) { Field(title, { title = it.take(200) }, label = tr.t("agent.rename")) }
    // Löschen.
    val deleting = rememberLast((layer as? Layer.Delete)?.chat)
    AgentDialog(layer is Layer.Delete, tr.t("agent.deleteTitle"), close, text = tr.t("agent.deleteText", "title" to (deleting?.title ?: "")), actions = {
        PillButton(tr.t("common.delete"), { deleting?.let(model::remove); close() }, kind = Btn.Danger)
        PillButton(tr.t("common.cancel"), close)
    })
    // Alle Chats löschen.
    var keepPinned by remember { mutableStateOf(true) }
    val own = model.chats.filter { it.author == null }
    AgentDialog(layer == Layer.DeleteAll, tr.t("agent.deleteAllTitle"), close, text = tr.t("agent.deleteAllText", "count" to own.size), actions = {
        PillButton(tr.t("agent.deleteAllButton"), { model.removeAll(keepPinned); close() }, kind = Btn.Danger)
        PillButton(tr.t("common.cancel"), close)
    }) {
        val pinned = own.count { it.pinned }
        if (pinned > 0) Check(tr.t("agent.deleteAllKeepPinned", "count" to pinned), keepPinned, { keepPinned = it })
    }
    // Ein Foto groß.
    val photo = rememberLast(layer as? Layer.Photo)
    var at by remember(photo) { mutableStateOf(photo?.at ?: 0) }
    AgentDialog(layer is Layer.Photo, photo?.let { photoLabel(tr, it.photos[at.coerceIn(0, it.photos.lastIndex)]) } ?: "", close, actions = {
        PillButton(tr.t("agent.photos.prev"), { at = (at - 1).coerceAtLeast(0) }, enabled = at > 0, small = true)
        PillButton(tr.t("agent.photos.next"), { at = (at + 1).coerceAtMost((photo?.photos?.size ?: 1) - 1) }, enabled = at < (photo?.photos?.size ?: 1) - 1, small = true)
        PillButton(tr.t("common.close"), close, small = true)
    }) {
        photo?.photos?.getOrNull(at)?.let { p -> NasPicture(p.id, "preview", chatActs, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp), fit = true) }
    }

    // Seiten über allem: Notizen, Sprechmodus, NAS-Dateien, Projekt, Funktionen.
    SlidePage(layer == Layer.Notes, close) { NotesPanel(model, onClose = close) }
    val proj = rememberLast((layer as? Layer.ProjectOpen)?.project)
    SlidePage(layer is Layer.ProjectOpen, close) {
        proj?.let { p -> ProjectSheet(model, p, onClose = close, onOpen = { id -> close(); model.open(id); nav.show(AgentView.Chat) }, onEdit = { nav.layer = Layer.ProjectEdit(p) }, onNew = { close(); model.newChat(p.id, asFresh = true); nav.show(AgentView.Chat) }) }
    }
    val editProject = remember { mutableStateOf<Project?>(null) }
    if (layer is Layer.ProjectEdit) editProject.value = layer.project
    SlidePage(layer is Layer.ProjectEdit, close) { ProjectDialog(model, editProject.value, onClose = close) }
    val feature = rememberLast((layer as? Layer.Feature)?.id)
    SlidePage(layer is Layer.Feature, close) { feature?.let { FeatureView(model, it, onClose = close) } }
    SlidePage(layer == Layer.Nas, close) { NasPicker(model, onClose = close, onPick = { refs -> close(); refs.forEach { model.addAttachment(Attachment(it.substringAfter(':').substringAfterLast('/'), null, null, ref = it)) } }) }
    if (layer == Layer.Talk) VoiceMode(model, speech, onClose = close)
}

@Composable
private fun ChatActionItems(model: AgentModel, nav: AgentNav, info: ChatInfo, export: (ChatInfo, Boolean) -> Unit, close: () -> Unit, find: Boolean) {
    val tr = tr()
    SheetItem("pin", if (info.pinned) tr.t("agent.unpin") else tr.t("agent.pin"), { close(); model.patch(info, PatchChat(pinned = !info.pinned)) })
    if (find) SheetItem("search", tr.t("agent.findInChat"), { close(); model.find = "" })
    SheetItem("edit", tr.t("agent.rename"), { nav.layer = Layer.Rename(info) })
    SheetItem("folder", tr.t("agent.moveToProject"), { nav.layer = Layer.Move(info) })
    SheetItem("archive", if (info.archived) tr.t("agent.archive.restore") else tr.t("agent.archive.do"), { close(); model.patch(info, PatchChat(archived = !info.archived)) })
    SheetRule()
    SheetItem("download", tr.t("agent.exportMd"), { close(); export(info, false) })
    SheetItem("fileText", tr.t("agent.exportPdf"), { close(); export(info, true) })
    SheetRule()
    SheetItem("trash", tr.t("agent.delete"), { nav.layer = Layer.Delete(info) }, danger = true, enabled = !(model.busy && model.current?.id == info.id))
}

/** Die Schritte im Blatt; ein Tippen zeigt Eingabe und Ergebnis des Schritts. */
@Composable
private fun StepsSheetBody(model: AgentModel, block: Block.Steps, close: () -> Unit) {
    val c = Titan.colors
    val tr = tr()
    var shown by remember(block) { mutableStateOf<Any?>(null) }
    val undone = model.current?.entries.orEmpty().filter { it.note == "undone" }.map { (it.tool ?: "") + "|" + (it.content ?: "") }.toSet()
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        io.github.ra5on.titanos.agent.chat.SmallIcon(if (shown != null) "chevronLeft" else "close", if (shown != null) tr.t("agent.set.back") else tr.t("common.close")) { if (shown != null) shown = null else close() }
        Text(
            when (val s = shown) { "thinking" -> tr.t("agent.thoughts"); is Entry -> stepLabel(tr, s); else -> tr.t("agent.stepsSummary") },
            style = AgentText.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text,
        )
    }
    AnimatedContent(shown, transitionSpec = {
        val calm = false
        (slideInHorizontally(Motion.spec(calm, 300)) { if (targetState != null) it / 10 else -it / 10 } + fadeIn(Motion.spec(calm, 300))) togetherWith fadeOut(Motion.spec(calm, 120))
    }, label = "steps") { s ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s) {
                "thinking" -> Box(Modifier.padding(horizontal = 16.dp)) { BoxText(block.thinking) }
                is Entry -> {
                    val lines = argLines(s.args)
                    val pre = preview(s.args)
                    if (lines.isNotEmpty() || pre.isNotEmpty()) {
                        SheetGroup(tr.t("agent.stepInput"))
                        Box(Modifier.padding(horizontal = 16.dp)) { BoxText((lines.map { (k, v) -> "$k: $v" } + listOfNotNull(pre.ifEmpty { null })).joinToString("\n")) }
                    }
                    val out = stepOutput(s)
                    if (out.isNotEmpty()) {
                        SheetGroup(tr.t("agent.stepOutput"))
                        Box(Modifier.padding(horizontal = 16.dp)) { BoxText(out) }
                    }
                }
                else -> StepList(block, tappable = true, busy = model.busy, undone = undone, onShow = { shown = it ?: "thinking" }, onUndo = if (model.author == null) ({ st -> close(); model.undo(st) }) else null)
            }
        }
    }
}

@Composable
private fun BoxText(text: String) {
    val c = Titan.colors
    Text(text, style = AgentText.sm.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp), color = c.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.chip).padding(12.dp))
}

/** Ist die TitanOS-App installiert? Dann führt „Zurück zu TitanOS“ dorthin. */
fun titanOSInstalled(context: android.content.Context): Boolean =
    context.packageManager.getLaunchIntentForPackage(TITANOS_APP) != null

const val TITANOS_APP = "io.github.ra5on.titanos"

/** Öffnet die TitanOS-App (mit [path] als Ziel, wenn sie es kennt). */
fun openTitanOS(context: android.content.Context, path: String?) {
    val launch = context.packageManager.getLaunchIntentForPackage(TITANOS_APP) ?: return
    if (path != null) launch.putExtra("path", path)
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(launch) }
}

/** Kurz vibrieren (Darstellung „Vibration“). */
fun vibrate(context: android.content.Context, pattern: LongArray) {
    val v = context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
    runCatching {
        val timings = if (pattern.size == 1) longArrayOf(0, pattern[0]) else longArrayOf(0) + pattern
        v.vibrate(android.os.VibrationEffect.createWaveform(timings, -1))
    }
}
