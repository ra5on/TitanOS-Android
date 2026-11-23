package io.github.ra5on.titanos.docker

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.compute.ComputeEnv

/**
 * Docker wie im Web (routes/docker): Container und Apps, Images, Volumes, Netzwerke, Aufräumen.
 * [start] aus der Adresse: ein Abschnitt (`catalog`, `images` …) oder `apps/<app>` für eine App.
 */
@Composable
fun DockerRoot(env: ComputeEnv, onBack: () -> Unit, start: String? = null) {
    DockerProgram(env, onBack, start)
}
