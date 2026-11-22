package io.github.ra5on.titanos.settings.pages.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.i18n.tr
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private val SERVICE_NAMES = listOf("docker", "vms", "lxc")
private val SERVICE_ICONS = mapOf("docker" to "box", "vms" to "monitor", "lxc" to "terminal")
private val WAITS = listOf(0, 5, 10, 20, 30, 45, 60, 90, 120, 180, 300)
private val EXTRAS = listOf(0, 10, 20, 30, 60, 120, 300)

/**
 * Dienste (routes/settings/services): Docker, VMs und LXC an oder aus
 * (`PUT /services/<name>`), dazu „Start nach dem Hochfahren“ als ein Formular
 * mit einem Speichern (`PUT /system/startup`, ganze Einstellung mit Reihenfolge).
 */
@Composable
fun ServicesPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = tr()
    val scope = rememberCoroutineScope()
    val run = rememberRunner()
    var services by remember { mutableStateOf<JsonObject?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf("") }

    // Start nach dem Hochfahren: gespeichert, Entwurf, Apps und VMs zur Auswahl.
    var saved by remember { mutableStateOf<JsonObject?>(null) }
    var startup by remember { mutableStateOf<JsonObject?>(null) }
    var apps by remember { mutableStateOf<List<String>>(emptyList()) }
    var vms by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var adding by remember { mutableStateOf("") }
    val save = rememberRunner()

    fun loadServices() = scope.launch {
        try {
            services = env.api.obj("services"); loadError = null
        } catch (e: Exception) {
            loadError = tr.errorOf(e)
        }
    }
    fun loadStartup() = scope.launch {
        try {
            val s = env.api.obj("system/startup").o("settings")
            saved = s; startup = s
        } catch (_: Exception) {
            saved = null; startup = null
        }
        apps = runCatching { env.api.obj("docker").a("containers").objects().map { it.str("app") }.filter { it.isNotEmpty() }.distinct().sorted() }.getOrDefault(emptyList())
        vms = runCatching { env.api.obj("vms").a("vms").objects().map { it.str("name") to it.bool("autostart") } }.getOrDefault(emptyList())
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { loadServices(); loadStartup() }

    fun set(name: String, enabled: Boolean) {
        asking = ""
        run.run(name, guard = GuardAction.Settings) {
            try {
                services = env.api.put("services/$name", jobj("enabled" to enabled)) as? JsonObject ?: services
            } catch (e: Exception) {
                runCatching { services = env.api.obj("services") }
                throw e
            }
        }
    }

    PageIntro(t("services.intro"))
    loadError?.let { Banner(it, tone = Tone.Error, action = t("ui.retry"), onAction = { loadServices() }) }
    SettingGroup(t("services.title")) {
        val s = services
        if (s == null && loadError == null) LoadingLine(t("common.loading"))
        for (name in SERVICE_NAMES) {
            val service = s?.get(name) as? JsonObject ?: continue
            val enabled = service.bool("enabled")
            val available = service.bool("available")
            val label = t("services.$name")
            val stateText = when {
                !available -> t("services.unavailable")
                enabled -> t("services.counts", "running" to service.int("running"), "total" to service.int("total"))
                else -> t("services.offHint")
            }
            SettingRow(label, desc = t("services.${name}Desc") + "\n" + stateText, icon = WebIcons[SERVICE_ICONS.getValue(name)], control = {
                ToneChip(if (enabled) "● " + t("services.on") else "○ " + t("services.off"), if (enabled) Tone.Ok else Tone.Inactive)
                Toggle(
                    enabled,
                    { on -> if (!on && service.int("running") > 0) asking = name else set(name, on) },
                    t("services.on"), t("services.off"),
                    enabled = !run.running && (available || enabled),
                    label = label,
                )
            })
            if (asking == name) {
                Banner(t("services.confirm", "name" to label, "count" to service.int("running")), tone = Tone.Warn)
                Buttons(Modifier.padding(start = 4.dp)) {
                    SmallButton(t("services.switchOff", "name" to label), { set(name, false) }, kind = ButtonKind.Danger)
                    SmallButton(t("common.cancel"), { asking = "" })
                }
            }
        }
        RunnerState(run)
    }

    val st = startup ?: return
    fun change(next: JsonObject) { startup = next }
    val order = st.a("order").objects()
    SettingGroup(t("startup.title")) {
        val noWait = st.bool("noWait")
        SettingRow(t("startup.waitReady"), desc = t("startup.waitReadyHint"), icon = WebIcons["power"], control = {
            Toggle(!noWait, { change(st.with("noWait" to !it)) }, t("look.on"), t("look.off"), label = t("startup.waitReady"))
        })
        SettingRow(t("startup.extra"), desc = t("startup.extraHint"), icon = WebIcons["clock"], control = {
            val extra = st.int("extra")
            Select(extra, (EXTRAS + extra).distinct().sorted().map { it to t("startup.seconds", "count" to it) }, { change(st.with("extra" to it)) }, label = t("startup.extra"), enabled = !noWait)
        })
    }
    SettingGroup(t("startup.order"), desc = t("startup.orderHint")) {
        if (order.isEmpty()) Muted(t("startup.empty"))
        order.forEachIndexed { i, entry ->
            val name = entry.str("name")
            val vm = entry.str("kind") == "vm"
            val autostartOff = vm && vms.firstOrNull { it.first == name }?.second != true
            fun put(list: List<JsonObject>) = change(st.with("order" to JsonArray(list)))
            SettingRow(
                "${i + 1}. $name",
                desc = t(if (vm) "startup.kindVm" else "startup.kindApp") + if (autostartOff) " · " + t("startup.vmAutostartOff") else "",
                icon = WebIcons[if (vm) "monitor" else "box"],
                control = {
                    IconKey(WebIcons["arrowUp"], t("startup.up", "name" to name), { put(order.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0)
                    IconKey(WebIcons["chevronDown"], t("startup.down", "name" to name), { put(order.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < order.size - 1)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Muted(t("startup.thenWait"))
                        val wait = entry.int("wait")
                        Select(wait, (WAITS + wait).distinct().sorted().map { it to t("startup.seconds", "count" to it) }, { w ->
                            put(order.mapIndexed { k, e -> if (k == i) e.with("wait" to w) else e })
                        }, label = t("startup.thenWait") + " – " + name)
                    }
                    if (!vm) {
                        Toggle(!entry.bool("off"), { on -> put(order.mapIndexed { k, e -> if (k == i) e.with("off" to !on) else e }) }, t("look.on"), t("look.off"), label = t("startup.autostart", "name" to name))
                    }
                    IconKey(WebIcons["close"], t("startup.remove", "name" to name), { put(order.filterIndexed { k, _ -> k != i }) })
                },
            )
        }
        val missing = (vms.map { "vm" to it.first } + apps.map { "app" to it }).filter { (kind, n) -> order.none { it.str("kind") == kind && it.str("name") == n } }
        if (missing.isNotEmpty()) {
            Buttons(Modifier.padding(top = 6.dp)) {
                Select(adding, listOf("" to t("startup.add")) + missing.map { (kind, n) -> "$kind/$n" to "$n – " + t(if (kind == "vm") "startup.kindVm" else "startup.kindApp") }, { adding = it }, label = t("startup.add"))
                SmallButton(t("startup.addButton"), {
                    val (kind, n) = adding.split('/', limit = 2)
                    change(st.with("order" to JsonArray(order + jobj("kind" to kind, "name" to n, "wait" to 0))))
                    adding = ""
                }, enabled = adding.isNotEmpty())
            }
        }
    }
    val dirty = st != saved
    if (dirty || save.running) {
        SettingGroup {
            Muted(t("agent.unsaved"))
            val savedText = t("ui.saved")
            Buttons {
                SmallButton(t("common.cancel"), { loadStartup() }, enabled = !save.running)
                SmallButton(t("common.save"), {
                    save.run(guard = GuardAction.Settings, ok = savedText) {
                        val answer = env.api.put("system/startup", st) as? JsonObject
                        if (answer != null) { saved = answer; startup = answer } else saved = st
                    }
                }, kind = ButtonKind.Primary, enabled = !save.running)
            }
        }
    }
    RunnerState(save)
}
