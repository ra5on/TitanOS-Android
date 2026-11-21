package io.github.ra5on.titanos.vms

import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeControls
import io.github.ra5on.titanos.compute.ControlItem
import io.github.ra5on.titanos.compute.ControlState

/**
 * VMs für eine spätere Gerätesteuerung (Android Device Controls): auflisten,
 * starten, herunterfahren. Stoppen fährt das Gastsystem sauber herunter wie
 * „Herunterfahren“ im Web; Ausschalten bleibt der Seite vorbehalten.
 */
class VmControls(access: ComputeAccess) : ComputeControls {
    private val api = VmApi(access)

    override val kind: String = "vm"

    override suspend fun items(): List<ControlItem> = api.status().vms.map { vm ->
        ControlItem(
            id = vm.name,
            kind = kind,
            title = vm.label,
            state = when {
                vm.job?.state == "running" -> ControlState.Busy
                vm.job?.state == "failed" || !vm.available || vm.ioError == true || vm.backupHeld == true -> ControlState.Problem
                vm.running -> ControlState.Running
                else -> ControlState.Stopped
            },
            detail = vm.description,
        )
    }

    override suspend fun start(id: String) = api.action(id, "start")

    override suspend fun stop(id: String) = api.action(id, "shutdown")
}
