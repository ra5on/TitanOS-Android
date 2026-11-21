package io.github.ra5on.titanos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
fun AdminProgram(start: String, graph: AppGraph, onBack: () -> Unit) {
    val pairing = graph.pairing ?: return
    var program by rememberSaveable(start) { mutableStateOf(start) }
    var page by rememberSaveable(start) { mutableStateOf<String?>(null) }
    val env = remember(pairing) {
        AdminEnv(
            AdminHttp(NasConnection.forPairing(graph.shared, pairing, graph.sessions)),
            roleOf = { graph.sessions.user?.role ?: "user" },
            userOf = { graph.pairing?.user.orEmpty() },
            open = { p, pg ->
                program = p
                page = pg
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
