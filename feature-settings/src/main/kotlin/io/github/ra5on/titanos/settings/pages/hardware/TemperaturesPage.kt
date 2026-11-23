package io.github.ra5on.titanos.settings.pages.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.numOrNull
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.NumberField
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SliderField
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Temperaturen (routes/settings/temperatures): die Sensoren, wie der Kernel
 * sie meldet (aus `GET /system`, alle 2 s), dazu für Verwalter die
 * Meldungen bei Hitze mit Notabschaltung (TempAlarms.svelte) und die Lüfter
 * mit fester Drehzahl oder Kurve (FanControl.svelte).
 */
@Composable
fun TemperaturesPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    var sensors by remember { mutableStateOf<List<JsonObject>?>(null) }
    suspend fun read() {
        sensors = env.api.obj("system").a("temperatures").objects()
    }
    LaunchedEffect(Unit) { runCatching { read() } }
    Poll(2000) { read() }

    PageIntro(t("temperatures.description"))
    val list = sensors.orEmpty()
    SettingGroup(t("temperatures.sensors")) {
        if (sensors == null) Muted(t("accelerator.loading")) else if (list.isEmpty()) Muted(t("temperatures.none"))
        for (sensor in list) {
            SettingRow(
                sensor.str("label"),
                id = "sensor-${sensor.str("id")}",
                icon = WebIcons["chip"],
                desc = "${sensor.str("chip")} · ${sensor.str("device")}",
                control = { Mono(celsius(tr, sensor.numOrNull("celsius")), muted = false) },
            )
        }
        Muted(t("temperatures.sensorHint"), small = true)
    }
    if (env.admin) {
        TempAlarms(list)
        FanControl(list)
    }
}

/**
 * Meldungen bei Hitze (TempAlarms.svelte): welche Sensoren melden (stumm
 * je Sensor für das ganze NAS) und die Notabschaltung. Gespeichert wird
 * sofort mit `PUT /notifications/temperatures` (immer mit der ganzen Liste
 * der stummen Sensoren).
 */
@Composable
private fun TempAlarms(sensors: List<JsonObject>) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val run = rememberRunner()
    val savedText = t("temperatures.alarmSaved")
    var alarms by remember { mutableStateOf<JsonObject?>(null) }
    var loadError by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            alarms = env.api.obj("notifications/temperatures")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = tr.errorOf(e)
        }
    }
    val al = alarms
    val muted = al.strings("muted")
    fun alarmOf(id: String) = al.a("sensors").objects().firstOrNull { it.str("id") == id }
    val guards = sensors.filter { alarmOf(it.str("id")).bool("shutdown") && it.str("id") !in muted }
    val reporting = sensors.count { alarmOf(it.str("id")).has("limit") && it.str("id") !in muted }

    fun store(next: JsonObject) {
        run.run(guard = GuardAction.Settings, ok = savedText) {
            alarms = env.api.put("notifications/temperatures", next) as? JsonObject ?: next
        }
    }

    SettingGroup(t("temperatures.alarmTitle")) {
        SettingRow(
            t("temperatures.alarmSensors"),
            desc = t("temperatures.alarmHint"),
            icon = WebIcons["bell"],
            id = "alarm-sensors",
            summary = if (al != null) t("temperatures.alarmCount", "on" to reporting, "total" to sensors.size) else null,
        ) {
            for (sensor in sensors) {
                val id = sensor.str("id")
                val alarm = alarmOf(id)
                val limit = alarm.numOrNull("limit")
                val isMuted = id in muted
                val state = when {
                    al == null -> "–"
                    limit == null -> t("temperatures.alarmNever")
                    isMuted -> t("temperatures.alarmMuted")
                    else -> t(if (alarm.bool("own")) "temperatures.alarmAtOwn" else "temperatures.alarmAtDefault", "celsius" to Math.round(limit).toString(), "chip" to sensor.str("chip"))
                }
                SettingSub(sensor.str("label"), desc = "${sensor.str("chip")} · ${sensor.str("device")}\n$state", control = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Mono(celsius(tr, sensor.numOrNull("celsius")), muted = false)
                        CheckRow(
                            limit != null && !isMuted,
                            { report ->
                                val cur = alarms ?: return@CheckRow
                                val now = cur.strings("muted")
                                store(cur.with("muted" to if (report) now - id else now + id))
                            },
                            t("temperatures.alarmReportFor", "sensor" to "${sensor.str("chip")} ${sensor.str("label")}"),
                            enabled = al != null && !run.running && limit != null,
                        )
                    }
                })
            }
            if (sensors.isEmpty()) Block { Muted(t("temperatures.none")) }
        }
        SettingRow(
            t("temperatures.heatTitle"),
            icon = WebIcons["power"],
            desc = if (guards.isNotEmpty()) t("temperatures.heatHint", "sensors" to guards.joinToString(", ") { "${it.str("chip")} ${it.str("label")}" }) else t("temperatures.heatNone"),
            control = {
                Toggle(al.bool("heatShutdown"), { on ->
                    val cur = alarms ?: return@Toggle
                    // Immer mit der Liste der stummen Sensoren: das NAS ersetzt sie.
                    store(cur.with("muted" to cur.strings("muted"), "heatShutdown" to on))
                }, t("appform.on"), t("appform.off"), enabled = al != null && !run.running, label = t("temperatures.heatTitle"))
            },
        )
        if (loadError.isNotEmpty()) Banner(loadError, tone = Tone.Error)
        RunnerState(run)
    }
}

private val DEFAULT_POINTS = listOf(35 to 30, 50 to 45, 65 to 75, 80 to 100)

/**
 * Lüfter (FanControl.svelte): jeder Lüfter bleibt beim BIOS, bis er hier
 * eine feste Drehzahl oder eine Kurve bekommt. Ein Speichern für alle
 * (`PUT /system/fans` mit allen nicht vom BIOS geregelten Lüftern). Die
 * Antwort kommt alle 5 s neu; ein geänderter Lüfter behält seinen Entwurf.
 */
@Composable
private fun FanControl(sensors: List<JsonObject>) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val run = rememberRunner()
    val savedText = t("ui.saved")
    var fans by remember { mutableStateOf<List<JsonObject>?>(null) }
    var readOnly by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    var base by remember { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    var loadError by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf("") }
    var round by remember { mutableIntStateOf(0) }

    fun take(answer: JsonObject, fresh: Boolean = false) {
        val list = answer.a("fans").objects()
        fans = list
        readOnly = answer.bool("readOnly")
        val nd = LinkedHashMap<String, JsonObject>()
        val nb = LinkedHashMap<String, JsonObject>()
        for (fan in list) {
            val id = fan.str("id")
            val control = fan["control"] as? JsonObject ?: JsonObject(emptyMap())
            val draft = drafts[id]
            val from = base[id]
            if (!fresh && draft != null && from != null && draft != from) {
                nd[id] = draft; nb[id] = from
            } else {
                nd[id] = control; nb[id] = control
            }
        }
        drafts = nd
        base = nb
    }

    suspend fun load() {
        val mine = round
        try {
            val answer = env.api.obj("system/fans")
            if (mine != round) return
            take(answer)
            loadError = ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine == round) loadError = tr.errorOf(e)
        }
    }
    LaunchedEffect(Unit) { load() }
    Poll(5000, skip = { run.running }) { load() }

    fun edit(id: String, change: (JsonObject) -> JsonObject) {
        val d = drafts[id] ?: return
        drafts = drafts + (id to change(d))
    }

    fun setMode(id: String, mode: String) = edit(id) { d0 ->
        var d = d0.with("mode" to mode)
        if (mode == "fixed" && d.int("percent") == 0) d = d.with("percent" to 60)
        if (mode == "curve") {
            if (d["points"] !is JsonArray) d = d.with("points" to DEFAULT_POINTS.map { (c, p) -> jobj("celsius" to c, "percent" to p) })
            if (!d.has("sensor")) d = d.with("sensor" to "disks")
        }
        d
    }

    val readable = sensors.filter { it.numOrNull("celsius") != null }
    SettingGroup(t("fans.title"), desc = t("fans.intro")) {
        if (readOnly) Banner(t("fans.readOnly"), tone = Tone.Warn)
        if (fans?.isEmpty() == true) Muted(t("fans.none"))
        for ((n, fan) in fans.orEmpty().withIndex()) {
            val id = fan.str("id")
            val d = drafts[id] ?: continue
            val mode = d.str("mode", "bios")
            val off = run.running || readOnly
            SettingRow(
                fan.str("label"),
                id = "fan-$n",
                icon = WebIcons["activity"],
                desc = fan.str("chip"),
                summary = listOfNotNull(
                    if (fan.numOrNull("rpm") == null) "–" else t("fans.rpm", "rpm" to fan.int("rpm").toString()),
                    "${fan.int("percent")} %",
                    fan.numOrNull("sensorC")?.let { celsius0(it) },
                    t("fans.mode.$mode"),
                ).joinToString(" · "),
            ) {
                if (fan.str("error").isNotEmpty()) Block { Banner(fan.str("error"), tone = Tone.Warn) }
                SettingSub(t("fans.mode"), control = {
                    Select(mode, listOf("bios", "fixed", "curve").map { it to t("fans.mode.$it") }, { setMode(id, it) }, label = t("fans.mode"), enabled = !off)
                })
                if (mode == "fixed") {
                    val min = d.int("min", 20).coerceIn(0, 100)
                    val percent = (d.int("percent").takeIf { it > 0 } ?: 60)
                    SettingSub(t("fans.fixed", "percent" to percent.toString()), control = {
                        SliderField(
                            percent.toFloat(),
                            { v -> edit(id) { it.with("percent" to (Math.round(v / 5f) * 5).coerceIn(min, 100)) } },
                            min.toFloat()..100f,
                            label = t("fans.fixed", "percent" to percent.toString()),
                            valueText = "$percent %",
                            steps = ((100 - min) / 5 - 1).coerceAtLeast(0),
                            enabled = !off,
                        )
                    })
                } else if (mode == "curve") {
                    SettingSub(t("fans.sensor"), control = {
                        val current = d.str("sensor", "disks")
                        val options = buildList {
                            add("disks" to t("fans.disks"))
                            for (s in readable) add(s.str("id") to "${s.str("chip")} · ${s.str("label")} (${celsius0(s.numOrNull("celsius") ?: 0.0)})")
                            if (none { it.first == current }) add(current to current)
                        }
                        Select(current, options, { v -> edit(id) { it.with("sensor" to v) } }, label = t("fans.sensor"), enabled = !off)
                    })
                    Block {
                        Muted(t("fans.points"), small = true)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val points = d.a("points").objects()
                            for ((i, point) in points.withIndex()) {
                                fun setPoint(key: String, v: Long) = edit(id) { cur ->
                                    val list = cur.a("points").objects().toMutableList()
                                    if (i < list.size) list[i] = list[i].with(key to v)
                                    cur.with("points" to list)
                                }
                                FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    NumberField(point.int("celsius"), { v -> if (v != null) setPoint("celsius", v.coerceIn(0, 110)) }, label = t("fans.celsius"), enabled = !off)
                                    Plain("°C →")
                                    NumberField(point.int("percent"), { v -> if (v != null) setPoint("percent", v.coerceIn(0, 100)) }, label = t("fans.percent"), enabled = !off)
                                    Plain("%")
                                }
                            }
                        }
                    }
                }
                if (mode != "bios") {
                    SettingSub(t("fans.min"), control = {
                        NumberField(d.int("min"), { v -> if (v != null) edit(id) { it.with("min" to v.coerceIn(20, 100)) } }, label = t("fans.min"), suffix = "%", enabled = !off)
                    })
                }
            }
        }
        Muted(t("fans.safety"), small = true)
        val err = saveError.ifEmpty { loadError }
        if (err.isNotEmpty()) Banner(err, tone = Tone.Error)
        if (fans?.isNotEmpty() == true) {
            Actions {
                SmallButton(t("common.save"), {
                    saveError = ""
                    round++
                    val body = jobj("fans" to fans.orEmpty().mapNotNull { drafts[it.str("id")] }.filter { it.str("mode", "bios") != "bios" })
                    run.run(guard = GuardAction.Settings, ok = savedText) {
                        try {
                            val r = env.api.put("system/fans", body) as? JsonObject
                            if (r != null) take(r, fresh = true)
                        } catch (e: ApiException) {
                            if (e.code == "invalid_fans") saveError = "${tr.error(e.code)} ${e.message.orEmpty().substringAfter("fans: invalid settings: ", e.message.orEmpty())}" else throw e
                        }
                    }
                }, kind = ButtonKind.Primary, enabled = !run.running && !readOnly)
            }
        }
        RunnerState(run)
    }
}
