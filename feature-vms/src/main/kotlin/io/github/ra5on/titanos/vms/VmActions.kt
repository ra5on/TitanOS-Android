package io.github.ra5on.titanos.vms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ComputeUi
import io.github.ra5on.titanos.compute.ConsoleRequest
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.MenuEntry
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Aktionen an einer VM mit ihren Rückfragen (VmButtons, VmRowMenu, VmActionDialog, CloneDialog, VmXmlDialog). */
class VmActions(
    private val env: ComputeEnv,
    private val state: VmsState,
    private val ui: ComputeUi,
    private val scope: CoroutineScope,
    private val onEdit: (Vm) -> Unit,
) {
    fun act(vm: Vm, action: String) {
        scope.launch { state.act(vm, action) { name -> ui.text("ui.vmAccepted", "name" to name) } }
    }

    fun edit(vm: Vm) = onEdit(vm)

    fun serial(vm: Vm) = env.openConsole(ConsoleRequest.vm(vm.name, vm.label))

    /** Das Menü einer VM (⋯, langes Drücken, Rechtsklick). */
    fun menu(vm: Vm) {
        val busy = state.isBusy(vm)
        val entries = buildList<MenuEntry> {
            if (vm.running) {
                add(MenuEntry(ui.text("vms.shutdown"), TitanIcons.stop, enabled = !busy) { act(vm, "shutdown") })
                add(MenuEntry(ui.text("vms.reboot"), TitanIcons.restart, enabled = !busy) { act(vm, "reboot") })
                add(MenuEntry(ui.text("vms.tab.screen"), TitanIcons.monitor) { state.choose(vm, VmTab.Screen) })
                add(MenuEntry(ui.text("vms.serial"), TitanIcons.key) { serial(vm) })
            } else {
                add(MenuEntry(ui.text("vms.start"), TitanIcons.play, enabled = !busy && state.canStart(vm)) { act(vm, "start") })
            }
            add(MenuEntry(ui.text("vms.edit"), TitanIcons.edit) { onEdit(vm) })
            if (!vm.running) add(MenuEntry(ui.text("ops.clone"), TitanIcons.archive, enabled = !busy && vm.job == null) { clone(vm) })
            add(MenuEntry(ui.text("snapshots.title"), TitanIcons.photo) { state.choose(vm, VmTab.Snapshots) })
            add(MenuEntry(ui.text("vms.showXml"), TitanIcons.file) { xml(vm) })
            if (vm.backupHeld == true) add(MenuEntry(ui.text("vms.backupRecovery.button"), TitanIcons.shield) { repair(vm) })
            if (vm.running) add(MenuEntry(ui.text("vms.poweroff"), TitanIcons.bolt, danger = true, enabled = !busy) { poweroff(vm) })
            else add(MenuEntry(ui.text("vms.remove"), TitanIcons.trash, danger = true, enabled = !busy) { this@VmActions.remove(vm) })
        }
        ui.menu(ui.text("vms.actionsFor", "name" to vm.label), entries)
    }

    fun poweroff(vm: Vm) = ui.confirm(
        Ask(ui.text("vms.dialog.poweroff", "name" to vm.label), ui.text("vms.poweroffText"), ui.text("vms.submit.poweroff")) {
            scope.launch { state.work(vm) { state.api.action(vm.name, "poweroff") } }
        },
    )

    fun remove(vm: Vm) {
        var deleteDisks by mutableStateOf(false)
        ui.confirm(
            Ask(
                ui.text("vms.dialog.remove", "name" to vm.label),
                ui.text("vms.removeText"),
                ui.text("vms.submit.remove"),
                body = {
                    CheckRow(ui.text("vms.deleteDisks"), deleteDisks, { deleteDisks = it })
                    if (deleteDisks) {
                        val files = vm.disks.filter { it.path == null }.joinToString(", ") { it.file }.ifEmpty { "–" }
                        Text(ui.text("vms.deleteDisksWarning", "disks" to files), style = TitanType.body, color = Titan.colors.dangerInk, modifier = Modifier.padding(vertical = 8.dp))
                    }
                },
            ) {
                val disks = deleteDisks
                scope.launch {
                    if (state.work(vm) { state.api.remove(vm.name, disks) }) {
                        state.failures.remove(vm.name)
                        if (state.selected == vm.name) state.selected = null
                        state.detailOpen = false
                    }
                }
            },
        )
    }

    /** Platten nach einer unterbrochenen Sicherung zurückführen (POST backup-recovery). */
    fun repair(vm: Vm) = ui.confirm(
        Ask(
            ui.text("vms.backupRecovery.title"),
            ui.text("vms.backupRecovery.text") + "\n\n" + ui.text("vms.backupRecovery.confirm"),
            ui.text("vms.backupRecovery.run"),
            danger = false,
        ) {
            scope.launch {
                state.busy += vm.name
                try {
                    state.api.action(vm.name, "backup-recovery")
                    state.failures.remove(vm.name)
                    state.feedback = ui.text("vms.backupRecovery.done", "name" to vm.label)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ui.toast(ui.text("vms.backupRecovery.failed"))
                } finally {
                    state.busy.remove(vm.name)
                    state.load()
                }
            }
        },
    )

    fun xml(vm: Vm) {
        scope.launch {
            try {
                val text = state.api.xml(vm.name)
                ui.sheet = { XmlSheet(text) { ui.sheet = null } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.failures[vm.name] = VmFailure(e, "")
            }
        }
    }

    fun clone(vm: Vm) {
        ui.sheet = { CloneSheet(state, vm) { ui.sheet = null } }
    }
}

@Composable
fun rememberVmActions(env: ComputeEnv, state: VmsState, onEdit: (Vm) -> Unit): VmActions {
    val ui = LocalComputeUi.current
    val scope = rememberCoroutineScope()
    return remember(env, state, ui) { VmActions(env, state, ui, scope, onEdit) }
}

@Composable
private fun XmlSheet(text: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Text(t("vms.showXml"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
    SelectionContainer {
        Text(text, style = TitanType.small.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = Titan.colors.text)
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TitanButton(s(R.string.vms_copy), { clipboard.setText(AnnotatedString(text)) }, icon = TitanIcons.file)
        TitanButton(t("common.done"), onClose, kind = ButtonKind.Primary)
    }
}

/** Klonen (CloneDialog): neuer Name und Volume, die Kopie läuft im Hintergrund. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloneSheet(state: VmsState, source: Vm, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf("") }
    var volumes by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(Unit) {
        try {
            volumes = state.api.storage().volumes.filter { it.mounted }.map { it.name }
            volume = if (source.volume in volumes) source.volume else volumes.firstOrNull().orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t("ops.clone"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.semantics { heading() })
        Text(t("ops.cloneHint"), style = TitanType.body, color = Titan.colors.muted)
        Text(source.label, style = TitanType.body, color = Titan.colors.text)
        Field(t("ops.name"), name, { name = it.lowercase().take(32) }, error = if (name.isNotEmpty() && !validCloneName(name)) t("vms.new.nameInvalid") else null)
        Choice(t("ops.volume"), listOf("" to t("ops.choose")) + volumes.map { it to it }, volume, { volume = it })
        error?.let { NoticeBox(NoticeLevel.Error, errorText(it, detail = true)) }
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
            TitanButton(t("common.cancel"), onClose, enabled = !busy)
            TitanButton(t("ops.clone"), {
                busy = true
                error = null
                scope.launch {
                    try {
                        state.api.clone(source.name, name, volume)
                        onClose()
                        state.load()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e
                    } finally {
                        busy = false
                    }
                }
            }, kind = ButtonKind.Primary, enabled = !busy && validCloneName(name) && volume.isNotEmpty() && !source.running)
        }
    }
}
