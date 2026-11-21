package io.github.ra5on.titanos.vms

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.compute.ComputeEnv

@Composable
fun VmScreenTab(env: ComputeEnv, state: VmsState, vm: Vm, full: Boolean, onFull: () -> Unit) =
    io.github.ra5on.titanos.vms.screen.VmScreen(env, state.api, vm, full, onFull)

@Composable
fun VmForm(env: ComputeEnv, api: VmApi, existing: Vm?, onDone: (String) -> Unit, onCancel: () -> Unit, onStartFailed: (String, Throwable) -> Unit = { _, _ -> }) {}

@Composable
fun MediaPage(api: VmApi) {}

@Composable
fun AdoptPage(api: VmApi, onAdopted: (String) -> Unit) {}

@Composable
fun VmSnapshots(api: VmApi, vm: Vm) {}

@Composable
fun LxcRoot(env: ComputeEnv, onBack: () -> Unit) {}
