package io.github.ra5on.titanos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import kotlinx.coroutines.launch
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.AdminHttp
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.plugins.PluginsRoot
import io.github.ra5on.titanos.shares.SharesRoot
import io.github.ra5on.titanos.storage.StorageRoot
import io.github.ra5on.titanos.tools.ToolsRoot
import io.github.ra5on.titanos.users.UsersRoot

/** Programme von „Ablage und Werkzeuge“, die die Startseite öffnet (Kennungen wie apps.ts). */
val ADMIN_PROGRAMS = setOf("storage", "shares", "users", "plugins", "monitor", "logs", "planner", "tools")

/**
 * Speicher, Freigaben, Benutzer, Plugins und Werkzeuge. Ein Programm kann ein
 * anderes auf einer Seite öffnen (Speicher › „Prüfungen einrichten“ →
 * Werkzeuge › Wartung); Zurück verlässt dann das geöffnete Programm.
 */
@Composable
fun AdminProgram(start: String, graph: AppGraph, onBack: () -> Unit) = AdminProgramAt(start, graph, null, onBack)

/** Wie [AdminProgram], an der Stelle aus einer Adresse des Webs ([link], Links.kt). */
@Composable
fun AdminProgramAt(start: String, graph: AppGraph, link: String?, onBack: () -> Unit) {
    val pairing = graph.pairing ?: return
    val context = LocalContext.current
    val webOnly = stringResource(R.string.app_later_toast)
    val scope = rememberCoroutineScope()
    var program by rememberSaveable(start) { mutableStateOf(start) }
    // Eine Adresse wie `/tools/activity` oder `/storage/smart?disk=sda` öffnet die Seite darin.
    var page by rememberSaveable(start, link) { mutableStateOf(startPage(link, start)) }
    val env = remember(pairing) {
        AdminEnv(
            AdminHttp(NasConnection.forPairing(graph.shared, pairing, graph.sessions)),
            roleOf = { graph.sessions.user?.role ?: "user" },
            userOf = { graph.pairing?.user.orEmpty() },
            open = { p, pg ->
                if (p in ADMIN_PROGRAMS) {
                    program = p
                    page = pg
                } else {
                    // Ein Programm außerhalb von Ablage und Werkzeuge (Konto, Systemsteuerung): wie ein Link im Web.
                    onBack()
                    openLink(context, graph, "/$p" + (pg?.let { "/$it" } ?: "")) { Toast.makeText(context, webOnly, Toast.LENGTH_SHORT).show() }
                }
            },
            // „Im Dateimanager öffnen“ (Freigaben, Schnappschüsse): Dateien an dieser Stelle.
            openFiles = { root, path ->
                val files = graph.files
                if (files != null) {
                    onBack()
                    scope.launch { files.openAt(root, path) }
                    graph.programLink = null
                    graph.openProgram = FILES_PROGRAM
                }
            },
        )
    }
    val back = {
        if (program != start) {
            program = start
            page = null
        } else {
            onBack()
        }
    }
    when (program) {
        "storage" -> StorageRoot(env, page, back)
        "shares" -> SharesRoot(env, page, back)
        "users" -> UsersRoot(env, page, back)
        "plugins" -> PluginsRoot(env, page, back)
        "monitor" -> ToolsRoot(env, page ?: "taskmanager", back)
        "logs" -> ToolsRoot(env, page ?: "logs", back)
        "planner" -> ToolsRoot(env, page ?: "scripts", back)
        else -> ToolsRoot(env, page, back)
    }
}
