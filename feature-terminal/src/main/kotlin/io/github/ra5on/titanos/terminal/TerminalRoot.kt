package io.github.ra5on.titanos.terminal

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.compute.ComputeEnv

/** Terminal wie im Web (TerminalPanel): NAS-Shell und Konsolen in Reitern. */
@Composable
fun TerminalRoot(env: ComputeEnv, onBack: () -> Unit) {
}

/** Offene Konsolen; leben außerhalb der Seite weiter wie die Reiter im Web (terminal.svelte.ts). */
object Consoles {
    fun open(request: io.github.ra5on.titanos.compute.ConsoleRequest) {
    }
}
