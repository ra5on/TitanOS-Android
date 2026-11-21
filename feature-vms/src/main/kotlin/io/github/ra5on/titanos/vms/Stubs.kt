package io.github.ra5on.titanos.vms

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.compute.ComputeEnv

@Composable
fun VmScreenTab(env: ComputeEnv, state: VmsState, vm: Vm, full: Boolean, onFull: () -> Unit) =
    io.github.ra5on.titanos.vms.screen.VmScreen(env, state.api, vm, full, onFull)





@Composable
fun LxcRoot(env: ComputeEnv, onBack: () -> Unit) {}
