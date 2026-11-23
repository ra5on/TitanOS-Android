package io.github.ra5on.titanos.settings.pages.smarthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strOrNull
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.NumberField
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

// Bereiche, deren Aufrufe die angehakte Bestätigung brauchen; die übrigen
// schalten nach normaler Bestätigung, Sensoren sind nur lesbar.
private val DANGER = setOf("cover", "lock", "alarm_control_panel", "vacuum", "button", "valve", "water_heater")
private val READ_ONLY = setOf("sensor", "binary_sensor", "weather", "person", "device_tracker", "sun", "zone")

private fun level(domain: String) = when (domain) {
    in READ_ONLY -> "read"
    in DANGER -> "danger"
    else -> "normal"
}

/** `?connection=` für alle Verbindungen außer der ersten (PLAN §45). */
internal fun haOn(connection: String, path: String): String =
    if (connection == "main") path else "$path?connection=${URLEncoder.encode(connection, "UTF-8")}"

/** Die Felder, die das Web vor dem Bearbeiten ergänzt (Geheimnisse leer). */
private fun draftOf(settings: JsonObject): JsonObject =
    settings.with(
        "token" to "",
        "fingerprint" to settings.str("fingerprint"),
        "account" to settings.str("account"),
        "mqtt" to settings.o("mqtt").with("password" to ""),
    )

private fun stamp(tr: io.github.ra5on.titanos.ui.i18n.Tr, text: String): String =
    if (text.isNotBlank() && !text.startsWith("0001-")) Fmt.dateTime(tr, text).ifEmpty { text } else "—"

/**
 * Home Assistant (routes/settings/homeassistant): Verbindungen, Verbindung
 * mit Adresse, Token und Fingerabdruck, Diagnose, Speicher für Home
 * Assistant, Sensoren und Knöpfe über MQTT und die Geräte für MCP. Das Token
 * kommt nie vom NAS zurück.
 */
@Composable
fun HomeAssistantPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    var current by rememberSaveable { mutableStateOf("main") }
    val conns = rememberLoad { runCatching { env.api.obj("homeassistant/connections") }.getOrDefault(JsonObject(emptyMap())) }
    val accounts = rememberLoad {
        runCatching { env.api.list("users").objects().filter { it.str("role") == "owner" || it.str("role") == "admin" }.map { it.str("name") } }.getOrDefault(emptyList())
    }
    val settings = rememberLoad(current) { env.api.obj(haOn(current, "homeassistant/settings")) }
    val listRun = rememberRunner()

    PageIntro(t("ha.intro"))

    val connections = conns.value?.a("connections")?.objects().orEmpty()
    val max = conns.value?.int("max", 8)?.takeIf { it > 0 } ?: 8
    fun label(c: JsonObject) = c.str("name").ifEmpty { if (c.str("id") == "main") tr.t("ha.conn.first") else tr.t("ha.conn.unnamed") }

    if (connections.isNotEmpty()) {
        SettingGroup(
            t("ha.conn.title"),
            tools = {
                SmallButton(t("ha.conn.add"), {
                    ov.show { close -> AddConnectionSheet(close) { id -> conns.reload(); current = id } }
                }, enabled = !listRun.running && connections.size < max)
            },
        ) {
            for (c in connections) {
                val id = c.str("id")
                val chosen = id == current
                val stateKey = if (c.bool("active")) "ha.conn.on" else if (c.str("url").isNotEmpty()) "ha.conn.off" else "ha.conn.empty"
                SettingRow(
                    label(c) + if (chosen) " ✓" else "",
                    desc = c.str("url").ifEmpty { tr.t("ha.conn.noUrl") },
                    icon = WebIcons["home"],
                    id = "ha-conn-$id",
                    onClick = { if (!chosen) current = id },
                    control = {
                        StatusChip(t(stateKey) + if (c.bool("mqtt")) " · MQTT" else "", if (c.bool("active")) Tone.Ok else Tone.Inactive)
                        if (id != "main") {
                            SmallButton(t("ha.conn.remove"), {
                                ov.confirm(tr.t("ha.conn.removeTitle"), tr.t("ha.conn.removeText", "name" to label(c)), action = tr.t("ha.conn.remove"), cancel = tr.t("common.cancel")) {
                                    listRun.run(guard = GuardAction.Settings) {
                                        env.api.delete("homeassistant/connections/${URLEncoder.encode(id, "UTF-8")}")
                                        conns.reload()
                                        if (current == id) current = "main"
                                    }
                                }
                            }, enabled = !listRun.running)
                        }
                    },
                )
            }
            if (connections.size > 1) Muted(t("ha.conn.hint"))
            RunnerState(listRun)
        }
    }

    Loading(settings) { loaded ->
        val chosen = connections.firstOrNull { it.str("id") == current }
        key(current) {
            ConnectionForm(
                current = current,
                loaded = loaded,
                title = if (connections.size > 1 && chosen != null) label(chosen) else null,
                accounts = accounts.value.orEmpty(),
                onStored = { conns.reload() },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.AddConnectionSheet(close: () -> Unit, onAdded: (String) -> Unit) {
    val env = LocalEnv.current
    val run = rememberRunner()
    var name by remember { mutableStateOf("") }
    DialogSheet(
        t("ha.conn.add"),
        onDismiss = { if (!run.running) close() },
        buttons = listOf(
            SheetButton(t("common.cancel"), enabled = !run.running) { close() },
            SheetButton(t("ha.conn.addButton"), primary = true, enabled = !run.running) {
                run.run(guard = GuardAction.Settings) {
                    val res = env.api.post("homeassistant/connections", jobj("name" to name.trim())).o()
                    close()
                    onAdded(res.str("id", "main"))
                }
            },
        ),
    ) {
        Field(name, { name = it.take(40) }, label = t("ha.conn.name"), placeholder = t("ha.conn.namePlaceholder"), singleLine = true, modifier = Modifier.fillMaxWidth())
        Muted(t("ha.conn.addHint"))
        RunnerState(run)
    }
}

@Composable
private fun ConnectionForm(current: String, loaded: JsonObject, title: String?, accounts: List<String>, onStored: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val run = rememberRunner()
    var answer by remember(loaded) { mutableStateOf(loaded) }
    val settings = answer.o("settings")
    var draft by remember(answer) { mutableStateOf(draftOf(settings)) }
    var clear by remember(answer) { mutableStateOf(listOf<String>()) }
    var saved by remember(answer) { mutableStateOf(false) }
    var missing by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf<JsonObject?>(null) }
    var checkError by remember { mutableStateOf("") }
    var entities by remember { mutableStateOf<List<JsonObject>?>(null) }
    var search by remember { mutableStateOf("") }
    // Die Zeilen, die vor dem Speichern aufgehen (PLAN 78-1): ein neuer
    // Schlüssel öffnet sie wie bind:open im Web.
    var openConnection by remember { mutableIntStateOf(if (settings.str("url").isEmpty()) 1 else 0) }
    var openMqtt by remember { mutableIntStateOf(0) }

    val tokenSet = answer.bool("tokenSet")
    val mqttPasswordSet = answer.bool("mqttPasswordSet")
    val status = answer.o("status")
    val sentEvery = settings.o("mqtt").long("interval").takeIf { it > 0 } ?: 30
    val domains = answer.texts("domains")
    val sensorGroups = answer.texts("sensorGroups")
    val commandGroups = answer.texts("commandGroups")
    val mqttDevices = answer.a("mqttDevices").objects()
    val mqtt = draft.o("mqtt")
    fun setMqtt(vararg p: Pair<String, Any?>) { draft = draft.with("mqtt" to mqtt.with(*p)) }
    fun toggleClear(name: String) { clear = if (name in clear) clear - name else clear + name }

    suspend fun store(): Boolean {
        saved = false
        val body = draft.with(
            "mqtt" to mqtt.with("port" to mqtt.long("port"), "interval" to mqtt.long("interval")),
            "clearSecrets" to clear,
        )
        val res = env.api.put(haOn(current, "homeassistant/settings"), body).o()
        answer = if (res.has("settings")) res else env.api.obj(haOn(current, "homeassistant/settings"))
        onStored()
        return true
    }

    /** Wie reportValidity im Web: Pflichtfelder der eingeschalteten Zeilen und die Grenzen der Zahlen. */
    fun complete(): Boolean {
        if (draft.bool("enabled")) openConnection++
        if (mqtt.bool("enabled")) openMqtt++
        val problem = when {
            draft.bool("enabled") && draft.str("url").isBlank() -> tr.t("ha.url")
            draft.bool("enabled") && !tokenSet && draft.str("token").isBlank() -> tr.t("ha.token")
            mqtt.bool("enabled") && mqtt.str("host").isBlank() -> tr.t("ha.mqttHost")
            mqtt.long("port") != 0L && mqtt.long("port") !in 1..65535 -> tr.t("notify.port")
            mqtt.long("interval") != 0L && mqtt.long("interval") !in 5..3600 -> tr.t("ha.mqttInterval")
            else -> null
        }
        missing = problem
        return problem == null
    }

    missing?.let { Banner(tr.t("appform.required"), tone = Tone.Warn, title = it) }

    SettingGroup(title) {
        // Verbindung
        key(openConnection) {
            SettingRow(
                t("ha.connection"),
                desc = draft.str("url").ifEmpty { tr.t("ha.conn.noUrl") },
                icon = WebIcons["home"],
                id = "ha-connection-$current-$openConnection",
                startOpen = openConnection > 0,
                control = {
                    OnOff(draft.bool("enabled"), { on -> draft = draft.with("enabled" to on); if (on) openConnection++ }, label = t("ha.connection"))
                },
            ) {
                SettingSub(t("ha.conn.name"), control = {
                    Field(draft.str("name"), { draft = draft.with("name" to it.take(40)) }, label = t("ha.conn.name"), placeholder = if (current == "main") t("ha.conn.first") else null, singleLine = true, modifier = Modifier.fillMaxWidth())
                })
                SettingSub(t("ha.url"), control = {
                    Field(draft.str("url"), { draft = draft.with("url" to it.trim()) }, label = t("ha.url"), placeholder = "http://homeassistant.local:8123", mono = true, singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                })
                SettingSub(t("ha.token"), desc = t("ha.tokenHint"), control = {
                    Column {
                        SecretNote(tokenSet, "token" in clear) { toggleClear("token") }
                        Field(draft.str("token"), { draft = draft.with("token" to it) }, label = t("ha.token"), password = true, mono = true, modifier = Modifier.fillMaxWidth())
                    }
                })
                SettingSub(t("ha.fingerprintLabel"), desc = "${t("ha.fingerprint")}: ${t("ha.fingerprintHint")}", control = {
                    Field(draft.str("fingerprint"), { draft = draft.with("fingerprint" to it.trim()) }, label = t("ha.fingerprintLabel"), placeholder = "AB:CD:", mono = true, singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                })
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton(t(if (run.busy == "check") "ha.checking" else "ha.check"), {
                        checked = null
                        checkError = ""
                        if (complete()) run.run("check", guard = GuardAction.Settings, detail = true) {
                            store()
                            try {
                                checked = env.api.post(haOn(current, "homeassistant/check")).o()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                checkError = apiMessage(e).ifEmpty { tr.errorOf(e) }
                            }
                        }
                    }, enabled = !run.running)
                    checked?.let { Banner(tr.t("ha.checkOk", "version" to it.str("version"), "location" to it.str("location")), tone = Tone.Ok) }
                    if (checkError.isNotEmpty()) Banner(checkError, tone = Tone.Error, title = t("ha.checkFailed"))
                }
            }
        }

        // Diagnose
        if (answer.has("diagnostics")) {
            val diag = answer.o("diagnostics")
            val connectedAt = stamp(tr, diag.str("lastConnected"))
            SettingRow(
                t("ha.diagnostics"),
                icon = WebIcons["activity"],
                id = "ha-diagnostics",
                summary = "${t("ha.lastConnected")}: $connectedAt",
            ) {
                SettingSub(t("ha.lastConnected"), control = { Plain(connectedAt) })
                SettingSub(t("ha.lastDisconnected"), control = { Plain(stamp(tr, diag.str("lastDisconnected"))) })
                SettingSub(t("ha.evicted"), control = { Plain(diag.long("evictedTransitions").toString()) })
                Muted(t("ha.gaps"))
            }
        }

        // Speicher für Home Assistant
        HAStorageRow(current)

        // Sensoren und Knöpfe über MQTT
        key(openMqtt) {
            SettingRow(
                t("ha.mqtt"),
                desc = t("ha.mqttIntro"),
                icon = WebIcons["activity"],
                id = "ha-mqtt-$current-$openMqtt",
                startOpen = openMqtt > 0,
                control = {
                    if (status.bool("mqtt")) StatusChip(t("ha.conn.on"), Tone.Ok)
                    else if (status.str("mqttError").isNotEmpty()) StatusChip(t("ha.mqttNoConnection"), Tone.Error)
                    OnOff(mqtt.bool("enabled"), { on -> setMqtt("enabled" to on); if (on) openMqtt++ }, label = t("ha.mqtt"))
                },
            ) {
                SettingSub(t("ha.mqttHost"), control = {
                    Field(mqtt.str("host"), { setMqtt("host" to it.trim()) }, label = t("ha.mqttHost"), placeholder = "homeassistant.local", mono = true, singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                })
                SettingSub(t("notify.port"), control = {
                    NumberField(mqtt.long("port").takeIf { it != 0L }, { setMqtt("port" to (it ?: 0L)) }, label = t("notify.port"), allowEmpty = true)
                })
                SettingSub("TLS", control = {
                    OnOff(mqtt.bool("tls"), { setMqtt("tls" to it) }, label = "TLS")
                })
                SettingSub(t("notify.username"), control = {
                    Field(mqtt.str("username"), { setMqtt("username" to it) }, label = t("notify.username"), mono = true, singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                })
                SettingSub(t("notify.password"), control = {
                    Column {
                        SecretNote(mqttPasswordSet, "mqtt" in clear) { toggleClear("mqtt") }
                        Field(mqtt.str("password"), { setMqtt("password" to it) }, label = t("notify.password"), password = true, mono = true, modifier = Modifier.fillMaxWidth())
                    }
                })
                SettingSub(t("ha.mqttPrefix"), control = {
                    Field(mqtt.str("prefix"), { setMqtt("prefix" to it) }, label = t("ha.mqttPrefix"), mono = true, singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                })
                SettingSub(t("ha.mqttInterval"), desc = t("ha.mqttIntervalHint"), control = {
                    NumberField(mqtt.long("interval").takeIf { it != 0L }, { setMqtt("interval" to (it ?: 0L)) }, label = t("ha.mqttInterval"), allowEmpty = true)
                })

                Legend(t("ha.sensorGroups"))
                val groups = mqtt.texts("groups")
                for (g in sensorGroups) {
                    CheckRow(g in groups, { setMqtt("groups" to toggle(groups, g)) }, tr.or("ha.group.$g", g))
                }
                for (g in sensorGroups.filter { it in groups && it !in setOf("system", "work") }) {
                    val devices = mqttDevices.filter { it.str("group") == g }
                    val picked = mqtt.o("devices")
                    val own = picked.has(g)
                    Details("${tr.or("ha.group.$g", g)} · ${t("ha.deviceSelection")}") {
                        CheckRow(!own, { all ->
                            val next = if (all) JsonObject(picked - g) else picked.with(g to devices.map { it.str("id") })
                            setMqtt("devices" to next)
                        }, t("ha.allDevices"))
                        if (own) {
                            val chosenIds = picked.texts(g)
                            for (id in (devices.map { it.str("id") } + chosenIds).distinct()) {
                                CheckRow(id in chosenIds, { setMqtt("devices" to picked.with(g to toggle(chosenIds, id))) }, devices.firstOrNull { it.str("id") == id }?.str("name") ?: id)
                            }
                        }
                    }
                }

                SettingSub(t("ha.accountLabel"), desc = t("ha.accountIntro"), control = {
                    val options = listOf("" to t("ha.accountNone")) + (accounts + listOfNotNull(draft.str("account").takeIf { it.isNotEmpty() && it !in accounts })).map { it to it }
                    Select(draft.str("account"), options, { draft = draft.with("account" to it) }, label = t("ha.accountLabel"))
                })

                Legend(t("ha.commandGroups"))
                val commands = mqtt.texts("commands")
                for (g in commandGroups) {
                    CheckRow(g in commands, { setMqtt("commands" to toggle(commands, g)) }, tr.or("ha.command.$g", g))
                }
                if (commands.isNotEmpty()) {
                    Banner(t("ha.commandsWarning"), tone = Tone.Warn)
                    if (draft.str("account").isEmpty()) Banner(t("ha.commandsNoAccount"), tone = Tone.Warn)
                }
                if (status.bool("mqtt")) Banner(tr.t("ha.mqttConnected", "seconds" to sentEvery.toString()), tone = Tone.Ok)
                else if (status.str("mqttError").isNotEmpty()) Banner(status.str("mqttError"), tone = Tone.Error, title = t("ha.mqttFailed"))
            }
        }

        // Geräte für MCP freigeben
        val chosenDomains = draft.texts("domains")
        val chosenEntities = draft.texts("entities")
        SettingRow(
            t("ha.release"),
            desc = t("ha.releaseIntro"),
            icon = WebIcons["shield"],
            id = "ha-release-$current",
            summary = tr.t("ha.releaseSummary", "domains" to chosenDomains.size, "entities" to chosenEntities.size),
        ) {
            Legend(t("ha.domains"))
            for (d in domains) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheckRow(d in chosenDomains, { draft = draft.with("domains" to toggle(chosenDomains, d)) }, d, modifier = Modifier.weight(1f))
                    Badge(t("ha.level.${level(d)}"), if (level(d) == "danger") Tone.Warn else Tone.Info)
                }
            }
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(t(if (entities != null) "ha.reloadEntities" else "ha.loadEntities"), {
                    run.run("entities", detail = true) {
                        entities = env.api.obj(haOn(current, "homeassistant/entities")).a("entities").objects()
                    }
                }, enabled = !run.running && tokenSet)
                if (entities != null) Field(search, { search = it }, label = t("ha.searchEntities"), placeholder = t("ha.searchEntities"), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (chosenEntities.isNotEmpty()) Muted(tr.t("ha.singleCount", "count" to chosenEntities.size))
                entities?.let { list ->
                    val q = search.trim().lowercase()
                    val known = domains.toSet()
                    val shown = list.filter { e ->
                        e.str("domain") in known && (q.isEmpty() || "${e.str("id")} ${e.str("name")} ${e.str("area")}".lowercase().contains(q))
                    }.take(200)
                    if (shown.isEmpty()) Muted(t("ha.noEntities"))
                    for (e in shown) {
                        EntityItem(
                            e,
                            viaDomain = e.str("domain") in chosenDomains,
                            picked = e.str("id") in chosenEntities,
                            policy = draft.o("policies").o(e.str("id")),
                            onPick = { draft = draft.with("entities" to toggle(chosenEntities, e.str("id"))) },
                            onPolicy = { p -> draft = draft.with("policies" to draft.o("policies").with(e.str("id") to p)) },
                        )
                    }
                }
            }
        }

        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            TitanButton(t(if (run.busy == "save") "notify.saving" else "notify.save"), {
                if (complete()) run.run("save", guard = GuardAction.Settings, detail = true) { saved = store() }
            }, kind = ButtonKind.Primary, enabled = !run.running)
            if (saved) StatusChip(t("notify.saved"), Tone.Ok)
        }
        RunnerState(run)
    }
}

/** Ein Gerät aus Home Assistant mit Häkchen, Zustand und seinen Regeln (Richtlinie). */
@Composable
private fun EntityItem(e: JsonObject, viaDomain: Boolean, picked: Boolean, policy: JsonObject, onPick: () -> Unit, onPolicy: (JsonObject) -> Unit) {
    val id = e.str("id")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        CheckRow(
            viaDomain || picked,
            { onPick() },
            e.str("name").ifEmpty { id },
            desc = id + (e.strOrNull("area")?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") + " · " + e.str("state"),
            enabled = !viaDomain,
        )
        Details(t("ha.policy") + " · " + e.str("name").ifEmpty { id }) {
            var aliases by remember(id) { mutableStateOf(policy.texts("aliases").joinToString(", ")) }
            var services by remember(id) { mutableStateOf(policy.texts("services").joinToString(", ")) }
            var description by remember(id) { mutableStateOf(policy.str("description")) }
            fun list(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            Field(aliases, { aliases = it; onPolicy(policy.with("services" to (policy["services"] ?: JsonNull), "aliases" to list(it))) }, label = t("ha.aliases"), placeholder = t("ha.aliases"), modifier = Modifier.fillMaxWidth())
            CheckRow(policy.bool("readOnly"), { onPolicy(policy.with("services" to (policy["services"] ?: JsonNull), "readOnly" to it)) }, t("ha.readOnly"))
            Field(services, { services = it; onPolicy(policy.with("services" to if (it.isBlank()) null else list(it))) }, label = t("ha.services"), placeholder = t("ha.services"), mono = true, modifier = Modifier.fillMaxWidth())
            Field(description, { description = it.take(1000); onPolicy(policy.with("services" to (policy["services"] ?: JsonNull), "description" to it.take(1000))) }, label = t("ha.description"), placeholder = t("ha.description"), modifier = Modifier.fillMaxWidth())
        }
    }
}
