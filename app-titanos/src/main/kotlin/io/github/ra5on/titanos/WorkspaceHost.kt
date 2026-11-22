package io.github.ra5on.titanos

import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ra5on.titanos.agent.app.AgentRoot
import io.github.ra5on.titanos.agent.app.SharedDevicePrefs
import io.github.ra5on.titanos.agent.chat.AgentApi
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.ChatCache
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.notify.NotifyApi
import io.github.ra5on.titanos.notify.NotifyRuntime
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.workspace.Program
import io.github.ra5on.titanos.workspace.Win
import io.github.ra5on.titanos.workspace.Workspace
import io.github.ra5on.titanos.workspace.WorkspaceSettings
import io.github.ra5on.titanos.workspace.WorkspaceState
import io.github.ra5on.titanos.workspace.showWorkspace
import java.io.File

/*
 * Arbeitsplatz wie im Web in der TitanOS-App (PLAN 116 F1): Einstellung
 * Automatisch/Immer/Nie (feature-workspace WorkspaceSettings), die Fenster
 * zeigen die Programme der App. AppRoot ruft nur [workspaceFirst].
 */

/** Programme, die es im Web nicht als eigenes Programm gibt, die App aber hat (Menü der Startseite). */
private val EXTRA = listOf(
    Triple("vpn", "shield", "green"),
    Triple("about", "overview", "grey"),
    Triple(PIM_SYNC, "calendar", "teal"),
)

/** Systemsteuerung, Mein Konto und Sicherheit kommen von Helfer E (feat/1.00-settings). */
internal val SETTINGS_SLOT = setOf("settings", "account", "security")

/**
 * Zeigt den Arbeitsplatz, wenn er erscheinen soll, und sagt es zurück; sonst
 * zeichnet die App die Handy-Gestaltung wie bisher. Sind Fenster offen, bleibt
 * er auch in einem schmaleren Fenster (wie im Web).
 */
@Composable
internal fun workspaceFirst(graph: AppGraph): Boolean {
    val pairing = graph.pairing ?: return false
    val context = LocalContext.current
    val settings = remember { WorkspaceSettings.of(context) }
    val width = LocalConfiguration.current.screenWidthDp
    val external = context.display?.displayId?.let { it != Display.DEFAULT_DISPLAY } == true
    val state = remember(pairing) {
        WorkspaceState(
            ComputeAccess.forPairing(graph.shared, pairing, graph.sessions),
            settings,
            pairing.user,
            { graph.sessions.user?.role ?: "user" },
            graph.scope,
        )
    }
    // Nur in dieser Sitzung gezeigt hält er sich (gemerkte Fenster vom letzten Mal halten ihn nicht am Handy fest).
    var shown by remember(pairing) { mutableStateOf(false) }
    if (!showWorkspace(settings.mode, width, external, holding = shown && state.wm.windows.isNotEmpty())) {
        shown = false
        return false
    }
    shown = true
    WorkspaceRoutes(graph, state)
    Workspace(state, program = { win, close -> WorkspaceProgram(graph, win, close) })
    return true
}

/** Was sonst die Handy-Gestaltung öffnet (Mitteilung, Widget, Kachel), öffnet hier ein Fenster. */
@Composable
private fun WorkspaceRoutes(graph: AppGraph, state: WorkspaceState) {
    val context = LocalContext.current
    LaunchedEffect(state.programs) {
        val extra = EXTRA.map { (id, glyph, gradient) ->
            val label = when (id) {
                "vpn" -> context.getString(io.github.ra5on.titanos.vpn.R.string.vpn_title)
                "about" -> context.getString(io.github.ra5on.titanos.home.R.string.home_menu_about)
                else -> context.getString(io.github.ra5on.titanos.pim.R.string.sync_title)
            }
            Program("app:$id", label, glyph, gradient, "android")
        }
        if (state.programs.isNotEmpty() && state.programs.none { it.key == "app:vpn" }) state.programs = state.programs + extra
    }
    LaunchedEffect(graph.openProgram) {
        val id = graph.openProgram ?: return@LaunchedEffect
        state.open("app:$id", graph.programLink.orEmpty())
        graph.openProgram = null
        graph.programLink = null
    }
    LaunchedEffect(graph.openPhotos) { if (graph.openPhotos) { state.open("app:photos"); graph.openPhotos = false } }
    LaunchedEffect(graph.openAbout) { if (graph.openAbout) { state.open("app:about"); graph.openAbout = false } }
    LaunchedEffect(graph.openVpn) { if (graph.openVpn) { state.open("app:vpn"); graph.openVpn = false } }
    val openList by NotifyRuntime.openList.collectAsStateWithLifecycle()
    LaunchedEffect(openList) { if (openList) { state.open("app:notifications"); NotifyRuntime.listOpened() } }
    val openLink by NotifyRuntime.openLink.collectAsStateWithLifecycle()
    LaunchedEffect(openLink) {
        val link = openLink ?: return@LaunchedEffect
        state.open("app:" + link.removePrefix("/").substringBefore('?').substringBefore('/'), link)
        NotifyRuntime.linkOpened()
    }
    PimLive(graph)
}

/** Der Inhalt eines Fensters: das Programm der App zu [Win.app]. */
@Composable
internal fun WorkspaceProgram(graph: AppGraph, win: Win, close: () -> Unit) {
    val id = win.app.removePrefix("app:")
    when {
        // Docker-Apps öffnen das Programm Docker (die App zeigt keine Seiten fremder Apps in Fenstern).
        win.app.startsWith("docker:") -> ComputeProgram("docker", graph, close)
        win.app.startsWith("webapp:") -> Later()
        id == "files" -> graph.files?.let { io.github.ra5on.titanos.files.FilesTab(it, nav = {}) }
        id == "photos" -> graph.photos?.let { io.github.ra5on.titanos.photos.PhotosTab(it, {}, graph.files) }
        id == "notifications" -> io.github.ra5on.titanos.notify.NotifyTab(nav = {})
        id == "agent" -> AgentWindow(graph, win.href)
        id in PROGRAMS || id == PIM_SYNC -> PimWindow(graph, id, win.href, close)
        id == "appcenter" -> AdminProgram("plugins", graph, close)
        id in ADMIN_PROGRAMS -> AdminProgram(id, graph, close)
        id in COMPUTE_PROGRAMS -> ComputeProgram(id, graph, close)
        id == "vpn" -> io.github.ra5on.titanos.vpn.VpnPage(onBack = close)
        id == "about" -> {
            var nasVersion by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { nasVersion = runCatching { graph.api?.system()?.version }.getOrNull()?.takeIf { it.isNotBlank() } }
            io.github.ra5on.titanos.update.AboutScreen(nasVersion, onBack = close)
        }
        // E (feat/1.00-settings): nach dem Zusammenführen hier SettingsProgram(graph, id, close) anschließen.
        id in SETTINGS_SLOT -> SettingsSlot(graph, id, close)
        id == WORKSPACE_SETTINGS -> io.github.ra5on.titanos.workspace.WorkspaceSettingsPage(close)
        else -> Later()
    }
}

/** Platzhalter für Systemsteuerung, Konto und Sicherheit, bis E zusammengeführt ist. */
@Composable
private fun SettingsSlot(@Suppress("UNUSED_PARAMETER") graph: AppGraph, @Suppress("UNUSED_PARAMETER") id: String, @Suppress("UNUSED_PARAMETER") close: () -> Unit) = Later()

@Composable
private fun Later() {
    Box(Modifier.fillMaxSize().background(Titan.colors.bg).padding(24.dp)) {
        EmptyState(stringResource(io.github.ra5on.titanos.workspace.R.string.ws_title), stringResource(R.string.app_later_toast))
    }
}

/** Mail, Kalender, Kontakte und der Abgleich in einem Fenster; [href] ist die Stelle (`/mail?…`). */
@Composable
private fun PimWindow(graph: AppGraph, id: String, href: String, close: () -> Unit) {
    val pairing = graph.pairing ?: return
    val nas = remember(pairing) { graph.connection() } ?: return
    val stream = remember(pairing) { graph.connection(NotifyApi.streamClient(graph.shared)) } ?: return
    when (id) {
        "mail" -> io.github.ra5on.titanos.mail.MailApp(nas, stream, close, start = href.takeIf { it.isNotEmpty() }?.let { io.github.ra5on.titanos.mail.MailStart.fromLink(it) } ?: io.github.ra5on.titanos.mail.MailStart.Boxes)
        "calendar" -> io.github.ra5on.titanos.pim.CalendarApp(nas, stream, close)
        "contacts" -> io.github.ra5on.titanos.pim.ContactsApp(nas, stream, close)
        else -> io.github.ra5on.titanos.pim.sync.SyncScreen(nas, close)
    }
}

/** Das Modell des Agenten für eine Kopplung (AppGraph.agentModel). */
internal fun agentModelFor(app: android.app.Application, pairing: io.github.ra5on.titanos.net.Pairing, shared: okhttp3.OkHttpClient, sessions: io.github.ra5on.titanos.net.SessionManager, scope: kotlinx.coroutines.CoroutineScope): AgentModel {
    val stream = AgentApi.streamClient(shared)
    return AgentModel(
        AgentApi(NasConnection.forPairing(shared, pairing, sessions), NasConnection.forPairing(stream, pairing, sessions)),
        ChatCache(File(app.filesDir, "agent-titanos.json")),
        scope,
        prefs = SharedDevicePrefs(app),
    )
}

/** Öffnet den Agenten in dieser App (Assistent, Notizen, Mitteilung einer Antwort). */
internal fun openAgentIn(context: android.content.Context, chatId: String?) {
    context.startActivity(
        android.content.Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_AGENT_CHAT, chatId.orEmpty())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
}

/** Extra: einen Chat des Agenten öffnen ("" ein neuer). Die Mitteilung einer Antwort nennt ihn `chat`. */
internal const val EXTRA_AGENT_CHAT = "chat"

/**
 * Der KI-Agent in der TitanOS-App wie das Agent-Fenster im Web: dieselben
 * Bildschirme wie die Agent-App (feature-agent), über die Kopplung dieser App.
 * Die Agent-App bleibt eigenständig. [href] `/agent?chat=<id>` öffnet einen Chat.
 */
@Composable
internal fun AgentWindow(graph: AppGraph, href: String = "") {
    val pairing = graph.pairing ?: return
    val context = LocalContext.current
    val model = remember(pairing) { graph.agentModel() } ?: return
    var open by remember(href) { mutableStateOf(if (href.contains("chat=")) href.substringAfter("chat=").substringBefore('&') else null) }
    AgentRoot(
        model,
        ask = null,
        onAskTaken = {},
        open = open,
        onOpenTaken = { open = null },
        versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault(""),
        hostname = pairing.hostname,
        user = pairing.user,
        onUnpair = { graph.forget() },
    )
}
