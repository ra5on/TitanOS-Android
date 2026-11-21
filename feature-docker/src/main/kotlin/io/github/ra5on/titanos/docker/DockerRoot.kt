package io.github.ra5on.titanos.docker

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.compute.ComputeEnv

/** Docker wie im Web (routes/docker): Container und Apps, Images, Volumes, Netzwerke, Aufräumen. */
@Composable
fun DockerRoot(env: ComputeEnv, onBack: () -> Unit) {
    DockerProgram(env, onBack)
}
