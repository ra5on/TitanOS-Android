package io.github.ra5on.titanos

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.ra5on.titanos.files.FilesModel
import io.github.ra5on.titanos.files.Location
import io.github.ra5on.titanos.model.FileRoot

/**
 * Wohin eine Adresse der Weboberfläche in der App führt. Jeder Link, der im
 * Web ein anderes Programm öffnet (`/users`, `/storage/smart?disk=sda`,
 * `/nvr/rights`, `/files?root=…&path=…`), öffnet in der App dasselbe Programm
 * an derselben Stelle; Adressen außerhalb des NAS öffnet der Browser.
 */
internal sealed interface LinkTarget {
    /** Ein Programm der App über [AppGraph.openProgram]; [link] ist die Adresse für die Stelle darin. */
    data class Program(val id: String, val link: String) : LinkTarget

    /** Eine Adresse im Netz (Angebote, Hilfeseiten): der Browser des Handys. */
    data class External(val url: String) : LinkTarget

    /** Der KI-Agent: die Agent-App, solange die TitanOS-App ihn nicht selbst zeigt. */
    data class Agent(val link: String) : LinkTarget

    /** Gibt es nur in der Weboberfläche (Einrichtung `/welcome`, unbekannte Adressen). */
    data object WebOnly : LinkTarget
}

/** Dateien als Programm (ein Ordner aus `/files?root=…&path=…`). */
internal const val FILES_PROGRAM = "files"

/**
 * Adressen des Webs, die in der App anders heißen. `/backups` öffnet das Web
 * selbst ins Leere (Durchspielen, ForesightWhatIf.svelte); gemeint sind die
 * Sicherungen der Systemsteuerung. `/logs` leitet das Web nach `/tools/logs` um.
 * Das App-Center `/apps` ist in der App der Katalog im Programm Docker.
 */
private fun normalize(path: String): String {
    val first = path.removePrefix("/").substringBefore('?').substringBefore('#').substringBefore('/')
    val rest = path.removePrefix("/").removePrefix(first)
    return when (first) {
        "backups" -> "/settings/backups$rest"
        "logs" -> "/tools/logs$rest"
        "apps" -> "/docker/catalog"
        else -> path
    }
}

/** Die Ziele, die die App kennt (Kennungen wie apps.ts im Web). */
internal fun linkTarget(raw: String): LinkTarget {
    val path = raw.trim()
    if (path.startsWith("https://") || path.startsWith("http://") || path.startsWith("mailto:")) return LinkTarget.External(path)
    if (!path.startsWith("/")) return LinkTarget.WebOnly
    val p = normalize(path)
    val first = p.removePrefix("/").substringBefore('?').substringBefore('#').substringBefore('/')
    return when (first) {
        in SETTINGS_PROGRAMS, in PROGRAMS, in ADMIN_PROGRAMS, in COMPUTE_PROGRAMS, FILES_PROGRAM -> LinkTarget.Program(first, p)
        "agent" -> LinkTarget.Agent(p)
        else -> LinkTarget.WebOnly
    }
}

/**
 * Der Teil einer Adresse hinter dem Programm, als Startseite darin:
 * `/tools/activity?days=7` → `activity?days=7`, `/nvr/rights` → `rights`,
 * `/storage` → null.
 */
internal fun startPage(link: String?, program: String): String? {
    val rest = link?.removePrefix("/")?.removePrefix(program)?.substringBefore('#') ?: return null
    return rest.removePrefix("/").takeIf { it.isNotEmpty() && !it.startsWith("?") }
}

/** Die Adresse des Webs für den Dateimanager an [root] und [path] (`/files?root=…&path=…`). */
internal fun filesLink(root: String, path: String): String {
    fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
    return "/$FILES_PROGRAM?root=${enc(root)}" + if (path.isNotEmpty()) "&path=${enc(path)}" else ""
}

/** Ein Wert aus dem Teil hinter `?` (`root`, `path`), dekodiert. */
internal fun queryValue(link: String, key: String): String? =
    link.substringAfter('?', "").substringBefore('#').split('&').firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=', "")?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotEmpty() }

/** Öffnet [path] in der App; [webOnly] sagt, dass es das nur im Web gibt. */
internal fun openLink(context: Context, graph: AppGraph, path: String, webOnly: () -> Unit) {
    when (val target = linkTarget(path)) {
        is LinkTarget.Program -> {
            graph.programLink = target.link
            graph.openProgram = target.id
        }
        is LinkTarget.External -> runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { webOnly() }
        is LinkTarget.Agent -> {
            val launch = context.packageManager.getLaunchIntentForPackage(AGENT_PACKAGE)
            if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else webOnly()
        }
        LinkTarget.WebOnly -> webOnly()
    }
}

private const val AGENT_PACKAGE = "io.github.ra5on.titanos.agent"

/**
 * Den Dateimanager an einer Ablage (`share/Fotos`, `volume/data`) und einem
 * Pfad öffnen; fehlt die Ablage in der Liste, mit ihrer Kennung als Namen.
 */
internal suspend fun FilesModel.openAt(rootId: String, path: String) {
    if (roots.roots == null) loadRoots()
    val root = roots.roots?.firstOrNull { it.id == rootId } ?: FileRoot(rootId, rootId.substringAfter('/'))
    openFolder(Location(root, path.trim('/')))
}

/**
 * Ein Programm, das ein Link geöffnet hat ([AppGraph.openProgram]): Ablage und
 * Werkzeuge, Rechnen oder Dateien. Zurück schließt es wie die Programme der
 * Startseite.
 */
@Composable
internal fun LinkedProgram(graph: AppGraph, id: String, onClose: () -> Unit) {
    val link = graph.programLink
    when (id) {
        in ADMIN_PROGRAMS -> AdminProgramAt(id, graph, link, onClose)
        in COMPUTE_PROGRAMS -> {
            BackHandler(onBack = onClose)
            ComputeProgramAt(id, graph, link, onClose)
        }
        else -> {
            val files = graph.files ?: return
            LaunchedEffect(link) {
                val root = link?.let { queryValue(it, "root") }
                if (root != null) files.openAt(root, queryValue(link, "path").orEmpty())
            }
            BackHandler(onBack = onClose)
            io.github.ra5on.titanos.files.FilesTab(files, nav = {})
        }
    }
}
