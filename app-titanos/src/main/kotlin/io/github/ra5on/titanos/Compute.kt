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
fun ComputeProgram(start: String, graph: AppGraph, onBack: () -> Unit) {
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
    when (program) {
        "docker" -> DockerRoot(env, back)
        "vms" -> VmsRoot(env, back)
        "lxc" -> LxcRoot(env, back)
        "nvr" -> NvrRoot(env, back)
        else -> TerminalRoot(env, back)
    }
}
