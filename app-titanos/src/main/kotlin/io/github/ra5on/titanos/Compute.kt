package io.github.ra5on.titanos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.docker.DockerRoot
import io.github.ra5on.titanos.nvr.NvrRoot
import io.github.ra5on.titanos.terminal.Consoles
import io.github.ra5on.titanos.terminal.TerminalRoot
import io.github.ra5on.titanos.vms.LxcRoot
import io.github.ra5on.titanos.vms.VmsRoot

/** Die Programme des Bereichs Rechnen, die die Startseite öffnet. */
val COMPUTE_PROGRAMS = setOf("docker", "vms", "lxc", "nvr", "terminal")

/**
 * Docker, VMs, LXC, NVR und Terminal. Eine Konsole aus Docker, VMs oder LXC
 * öffnet das Terminal mit ihrem Reiter; Zurück führt dorthin zurück, woher
 * sie kam.
 */
@Composable
fun ComputeProgram(start: String, graph: AppGraph, onBack: () -> Unit) = ComputeProgramAt(start, graph, null, onBack)

/** Wie [ComputeProgram], an der Stelle aus einer Adresse des Webs ([link], Links.kt). */
@Composable
fun ComputeProgramAt(start: String, graph: AppGraph, link: String?, onBack: () -> Unit) {
    val pairing = graph.pairing ?: return
    val access = remember(pairing) { ComputeAccess.forPairing(graph.shared, pairing, graph.sessions) }
    var program by rememberSaveable(start) { mutableStateOf(start) }
    var from by rememberSaveable(start) { mutableStateOf<String?>(null) }
    val env = remember(access) {
        ComputeEnv(
            access,
            roleOf = { graph.sessions.user?.role ?: "user" },
            userOf = { graph.pairing?.user.orEmpty() },
            openConsole = { request ->
                Consoles.open(request)
                if (program != "terminal") from = program
                program = "terminal"
            },
            openProgram = { id ->
                from = program
                program = id
            },
        )
    }
    val back = {
        val previous = from
        if (previous != null && previous != program) {
            from = null
            program = previous
        } else {
            onBack()
        }
    }
    // Eine Adresse wie `/nvr/rights` oder `/docker/apps/<app>` öffnet die Stelle im Programm, mit dem es begann.
    val page = if (program == start) startPage(link, start) else null
    when (program) {
        "docker" -> DockerRoot(env, back, start = page)
        "vms" -> VmsRoot(env, onBack = back, select = if (program == start) link?.let { queryValue(it, "vm") } else null)
        "lxc" -> LxcRoot(env, onBack = back, select = if (program == start) link?.let { queryValue(it, "ct") } else null)
        "nvr" -> NvrRoot(env, back, start = page)
        else -> TerminalRoot(env, back)
    }
}
