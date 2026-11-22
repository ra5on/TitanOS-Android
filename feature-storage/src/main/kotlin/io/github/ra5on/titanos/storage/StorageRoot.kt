package io.github.ra5on.titanos.storage

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.admin.AdminEnv

/**
 * Einstieg des Programms (von der Startseite oder aus einem anderen Programm).
 * [start] ist wahlweise eine Seite des Programms; [onBack] verlässt es.
 */
@Composable
fun StorageRoot(env: AdminEnv, start: String?, onBack: () -> Unit) {
    StorageProgram(env, start, onBack)
}
