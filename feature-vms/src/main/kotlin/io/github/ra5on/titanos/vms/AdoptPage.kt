package io.github.ra5on.titanos.vms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.compute.tOr
import io.github.ra5on.titanos.compute.webText
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * „VM übernehmen“ (AdoptDialog.svelte): Kopien von VM-Einstellungen (vm.json)
 * auf den Volumes, deren VM dieses NAS nicht kennt; eine davon wieder
 * anmelden. Die Volumes werden nur hier und auf „Erneut suchen“ gelesen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdoptPage(api: VmApi, onAdopted: (String) -> Unit, initial: List<AdoptCandidate>? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf(initial) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf(initial?.firstOrNull { it.missing.isEmpty() }?.key.orEmpty()) }
    var newName by remember { mutableStateOf("") }
    var takenNow by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<AdoptResult?>(null) }

    val selected = candidates?.firstOrNull { it.key == chosen }
    val needsName = selected != null && (selected.nameTaken || selected.key in takenNow)
    val nameValid = validAdoptName(newName)

    fun choose(c: AdoptCandidate?) {
        chosen = c?.key.orEmpty()
        error = null
        newName = if (c?.nameTaken == true) "${c.name.take(29)}-2" else ""
    }
    suspend fun load() {
        loading = true
        loadError = null
        try {
            val list = api.adoptable()
            candidates = list
            if (selected == null) choose(list.firstOrNull { it.missing.isEmpty() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = context.errorText(e)
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (initial == null) load() }
    val changeText = @Composable { code: String -> tOr("vms.adopt.change.$code", code) }

    ScrollPage {
        val result = done
        if (result != null) {
            NoticeBox(NoticeLevel.Ok, t("vms.adopt.done", "name" to result.name))
            if (result.changes.isNotEmpty()) {
                Muted(t("vms.adopt.changes"))
                result.changes.forEach { Text("• " + changeText(it), style = TitanType.body, color = Titan.colors.text) }
            }
            TitanButton(t("common.done"), { onAdopted(result.name) }, kind = ButtonKind.Primary, modifier = Modifier.padding(top = 10.dp))
            return@ScrollPage
        }
        Muted(t("vms.adopt.hint"), Modifier.padding(vertical = 8.dp))
        val list = candidates
        when {
            loadError != null -> NoticeBox(NoticeLevel.Error, loadError.orEmpty()) { Tool(t("common.retry"), TitanIcons.restart, { scope.launch { load() } }) }
            list == null -> Muted(t("vms.adopt.loading"))
            list.isEmpty() -> Muted(t("vms.adopt.none"))
            else -> Group(null) {
                list.forEachIndexed { i, c ->
                    val sub = buildList {
                        if (c.displayName != null) add(c.name)
                        if (c.removed) add(c.removedAt?.let { t("vms.adopt.removedAt", "date" to formatDateTime(it)) } ?: t("vms.adopt.removed"))
                        if (c.nameTaken) add(t("vms.adopt.nameTakenChip"))
                        add("${c.volume}/vms/${c.directory}")
                        add(t("vms.adopt.specs", "vcpus" to c.vcpus, "memory" to formatBytes(c.memoryMiB * MIB), "disks" to c.disks.size))
                        if (c.missing.isNotEmpty()) add(t("vms.adopt.missing", "volumes" to c.missing.joinToString(", ")))
                    }
                    RadioRow(c.label, c.key == chosen, { choose(c) }, sub = sub.joinToString("\n"), enabled = !busy && c.missing.isEmpty(), divider = i > 0)
                }
            }
        }
        if (selected != null) {
            if (needsName) {
                NoticeBox(NoticeLevel.Warn, t("vms.adopt.nameTaken", "name" to selected.name))
                Field(t("vms.adopt.newName"), newName, { newName = it.lowercase().take(31) }, enabled = !busy, error = if (newName.isNotEmpty() && !nameValid) t("vms.new.nameInvalid") else null)
            }
            Text(
                t("vms.adopt.confirm", "name" to (if (needsName) newName.ifEmpty { "…" } else selected.name), "path" to "${selected.volume}/vms/${selected.directory}"),
                style = TitanType.body,
                color = Titan.colors.text,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            selected.disks.forEach { Muted("• $it") }
            if (selected.dropped.isNotEmpty()) {
                Muted(t("vms.adopt.dropped"), Modifier.padding(top = 6.dp))
                selected.dropped.forEach { Text("• " + changeText(it), style = TitanType.body, color = Titan.colors.text) }
            }
        }
        error?.let { NoticeBox(NoticeLevel.Error, it) }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (candidates != null) TitanButton(t("vms.adopt.search"), { scope.launch { load() } }, enabled = !busy && !loading, icon = TitanIcons.search)
            TitanButton(t("vms.adopt.submit"), {
                val c = selected ?: return@TitanButton
                busy = true
                error = null
                scope.launch {
                    try {
                        done = api.adopt(c, if (needsName) newName else "")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val code = (e as? ApiException)?.code ?: "internal"
                        error = if (code == "vm_exists") context.webText("vms.adopt.nameTaken", "name" to (if (needsName) newName else c.name)) else context.errorText(e, detail = code == "vm_failed")
                        if (code == "vm_exists") {
                            takenNow = takenNow + c.key
                            if (newName.isEmpty()) newName = "${c.name.take(29)}-2"
                        }
                    } finally {
                        busy = false
                    }
                }
            }, kind = ButtonKind.Primary, enabled = !busy && selected != null && (!needsName || nameValid))
        }
    }
}
