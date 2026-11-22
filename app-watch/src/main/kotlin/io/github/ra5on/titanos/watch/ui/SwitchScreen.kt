package io.github.ra5on.titanos.watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.data.Container
import io.github.ra5on.titanos.watch.data.HaEntity
import io.github.ra5on.titanos.watch.data.Vm
import io.github.ra5on.titanos.watch.data.WatchApi
import io.github.ra5on.titanos.watch.data.isHidden
import io.github.ra5on.titanos.watch.graph
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Ein Bereich: geladen, nicht erlaubt bzw. nicht da (fehlt dann), oder Fehler. */
sealed interface Section<out T> {
    data object Loading : Section<Nothing>
    data object Hidden : Section<Nothing>
    data class Ready<T>(val items: List<T>) : Section<T>
    data class Failed(val message: Int) : Section<Nothing>
}

private suspend fun <T> section(load: suspend () -> List<T>): Section<T> = try {
    Section.Ready(load())
} catch (e: Exception) {
    if (e.isHidden()) Section.Hidden else Section.Failed(R.string.w_err_reach)
}

/** Geräte von Home Assistant, die die Uhr schalten kann. */
val switchable = setOf("light", "switch", "fan", "input_boolean")
val runnable = setOf("scene", "script")

data class SwitchData(val ha: Section<HaEntity>, val containers: Section<Container>, val vms: Section<Vm>)

suspend fun loadSwitches(api: WatchApi): SwitchData = coroutineScope {
    val ha = async { section { api.haEntities().filter { it.domain in switchable || it.domain in runnable }.sortedBy { it.name.lowercase() } } }
    val c = async { section { api.containers().sortedBy { it.name.lowercase() } } }
    val v = async { section { api.vms().sortedBy { it.name.lowercase() } } }
    SwitchData(ha.await(), c.await(), v.await())
}

/** Was nach Tippen auf einen laufenden Container/eine laufende VM gefragt wird. */
data class StopAsk(val kind: String, val id: String, val name: String)

@Composable
fun SwitchRoute() {
    val context = LocalContext.current
    val g = context.graph
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(SwitchData(Section.Loading, Section.Loading, Section.Loading)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var ask by remember { mutableStateOf<StopAsk?>(null) }
    suspend fun reload() {
        g.api()?.let { data = loadSwitches(it) }
    }
    LaunchedEffect(Unit) { reload() }
    fun run(key: String, action: suspend (WatchApi) -> Unit) {
        val api = g.api() ?: return
        busy = key
        scope.launch {
            problem = try {
                action(api)
                null
            } catch (e: Exception) {
                if (e.isHidden()) R.string.w_switch_err_rights else R.string.w_switch_err
            }
            reload()
            busy = null
        }
    }
    SwitchScreen(
        data, busy, problem,
        onEntity = { e, on ->
            val service = if (e.domain in runnable) "turn_on" else if (on) "turn_on" else "turn_off"
            run(e.id) { it.haCall(e.domain, service, e.id) }
        },
        onContainer = { c ->
            if (isRunning(c.state)) ask = StopAsk("container", c.id, c.name) else run(c.id) { it.containerAction(c.id, "start") }
        },
        onVm = { v ->
            if (isRunning(v.state)) ask = StopAsk("vm", v.name, v.name) else run(v.name) { it.vmAction(v.name, "start") }
        },
    )
    val a = ask
    ConfirmDanger(
        visible = a != null,
        title = if (a?.kind == "vm") stringResource(R.string.w_vm_stop_title, a.name) else stringResource(R.string.w_container_stop_title, a?.name.orEmpty()),
        text = if (a?.kind == "vm") stringResource(R.string.w_vm_stop_text) else stringResource(R.string.w_container_stop_text),
        action = stringResource(if (a?.kind == "vm") R.string.w_vm_stop else R.string.w_container_stop),
        onConfirm = {
            ask = null
            if (a != null) {
                if (a.kind == "vm") run(a.id) { it.vmAction(a.id, "shutdown") } else run(a.id) { it.containerAction(a.id, "stop") }
            }
        },
        onDismiss = { ask = null },
    )
}

@Composable
fun SwitchScreen(
    data: SwitchData,
    busy: String?,
    problem: Int?,
    onEntity: (HaEntity, Boolean) -> Unit,
    onContainer: (Container) -> Unit,
    onVm: (Vm) -> Unit,
) {
    Page {
        item { Header(stringResource(R.string.w_switch)) }
        if (problem != null) item { Problem(stringResource(problem)) }
        val all = listOf(data.ha, data.containers, data.vms)
        if (all.all { it is Section.Loading }) item { Busy(stringResource(R.string.w_loading)) }
        if (all.all { it is Section.Hidden || (it is Section.Ready<*> && it.items.isEmpty()) }) {
            item { WText(stringResource(R.string.w_switch_none), center = true) }
        }
        section(data.ha, R.string.w_switch_devices) { e ->
            if (e.domain in runnable) {
                item { RowButton(e.name, sub = stringResource(R.string.w_run), onClick = { onEntity(e, true) }, icon = TitanIcons.play, enabled = busy != e.id) }
            } else {
                val on = e.state == "on"
                item {
                    SwitchButton(
                        checked = on,
                        onCheckedChange = { onEntity(e, it) },
                        enabled = busy != e.id && e.state != "unavailable",
                        modifier = Modifier.fillMaxWidth(),
                        label = { WText(e.name, style = MaterialTheme.typography.labelMedium) },
                        secondaryLabel = {
                            WText(
                                listOfNotNull(e.area?.takeIf { it.isNotBlank() }, stringResource(entityState(e.state))).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
        }
        section(data.containers, R.string.w_switch_containers) { c ->
            item {
                RowButton(
                    c.name,
                    sub = stringResource(if (isRunning(c.state)) R.string.w_running_tap_stop else R.string.w_stopped_tap_start),
                    onClick = { onContainer(c) },
                    icon = if (isRunning(c.state)) TitanIcons.stop else TitanIcons.play,
                    enabled = busy != c.id,
                )
            }
        }
        section(data.vms, R.string.w_switch_vms) { v ->
            item {
                RowButton(
                    v.name,
                    sub = stringResource(if (isRunning(v.state)) R.string.w_running_tap_stop else R.string.w_stopped_tap_start),
                    onClick = { onVm(v) },
                    icon = if (isRunning(v.state)) TitanIcons.stop else TitanIcons.play,
                    enabled = busy != v.name,
                )
            }
        }
    }
}

private fun entityState(state: String): Int = when (state) {
    "on" -> R.string.w_on
    "off" -> R.string.w_off
    "unavailable" -> R.string.w_unavailable
    else -> R.string.w_unknown
}

private fun <T> androidx.wear.compose.foundation.lazy.ScalingLazyListScope.section(
    s: Section<T>,
    title: Int,
    row: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.(T) -> Unit,
) {
    when (s) {
        is Section.Ready -> if (s.items.isNotEmpty()) {
            item { Header(stringResource(title)) }
            s.items.forEach { row(it) }
        }
        is Section.Failed -> {
            item { Header(stringResource(title)) }
            item { Problem(stringResource(s.message)) }
        }
        else -> Unit
    }
}
