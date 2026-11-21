package io.github.ra5on.titanos.settings.pages.hardware

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Energie (routes/settings/energy): Zeitplan und Wake-on-LAN
 * (PowerSchedule.svelte), der Ein-/Ausschalter, der Ruhemodus der
 * Festplatten (ein Dialog, derselbe wie unter Speicher), die Profile des
 * Prozessors und die USV. Jede Änderung wird sofort gespeichert.
 */
@Composable
fun EnergyPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val energy = rememberLoad { env.api.obj("system/energy") }
    val sleep = rememberLoad { env.api.obj("storage/disk-sleep") }
    // Die Zeilen zu eigenen Seiten nennen deren Zustand nebenbei; lässt er sich nicht lesen, sagen sie nichts.
    val button = rememberLoad { runCatching { env.api.obj("system/power-button").o("settings").str("action") }.getOrDefault("") }
    val ups = rememberLoad { runCatching { upsSummary(tr, env.api.obj("ups").o("status")) }.getOrDefault("") }
    val run = rememberRunner()
    val savedText = t("energy.saved")

    PageIntro(t("energy.intro"))
    energy.error?.let { Banner(tr.errorOf(it, detail = true), tone = Tone.Error, action = t("ui.retry"), onAction = { energy.reload() }) }
    val status = energy.value
    if (status != null) {
        if (status.str("error").isNotEmpty()) Banner(t("energy.startupError") + " " + status.str("error"), tone = Tone.Warn)
        if (status.bool("readOnly")) Banner(t("energy.readOnly"), tone = Tone.Warn)
    }

    SettingGroup(t("energy.group.power")) {
        PowerSchedule()
        val b = button.value.orEmpty()
        SettingRow(
            t("energy.powerButton"),
            desc = t("energy.powerButtonDesc"),
            icon = WebIcons["power"],
            summary = if (b.isNotEmpty()) t("powerButton.$b") else null,
            onClick = { a.openPage("power-button") },
        )
    }

    SettingGroup(t("energy.group.saving")) {
        val s = sleep.value
        SettingRow(
            t("energy.diskSleep"),
            desc = sleep.error?.let { tr.errorOf(it) } ?: t("energy.diskSleepHint"),
            icon = WebIcons["disk"],
            id = "disk-sleep",
            summary = s?.let { sleepSummary(tr, it) },
            control = {
                SmallButton(t("energy.diskSleepOpen"), {
                    ov.show { close -> DiskSleepDialog(s ?: JsonObject(emptyMap()), onClose = close, onSaved = { sleep.value = it }, onSystemData = { close(); a.openPage("systemdata") }) }
                }, enabled = s != null)
            },
        )
        when {
            status == null && energy.error == null -> LoadingLine(t("common.loading"))
            status == null -> Unit
            status.a("controls").isEmpty() -> SettingRow(t("energy.cpu"), desc = t("energy.unsupported"), icon = WebIcons["chip"])
            else -> {
                val settings = status.o("settings")
                val controls = status.a("controls").objects()
                val drivers = status.strings("drivers")
                SettingRow(
                    t("energy.cpu"),
                    desc = if (drivers.isNotEmpty()) "${t("energy.driver")} ${drivers.joinToString(", ")}" else null,
                    icon = WebIcons["chip"],
                    id = "cpu",
                    summary = controls.joinToString(" · ") { if (it.bool("mixed")) tr.t("energy.mixed") else profileLabel(tr, it.str("current")) },
                ) {
                    for (item in controls) {
                        val key = item.str("key")
                        val current = if (item.bool("mixed")) t("energy.mixed") else profileLabel(tr, item.str("current"))
                        val value = settings.str(key)
                        val choices = item.strings("choices")
                        SettingSub(t("energy.$key"), desc = "${t("energy.${key}Hint")} · ${t("energy.current")}: $current", control = {
                            val options = buildList {
                                add("" to t("energy.unmanaged"))
                                if (value.isNotEmpty() && value !in choices) add(value to "$value — ${t("energy.noLongerAvailable")}")
                                for (c in choices) {
                                    val l = profileLabel(tr, c)
                                    add(c to if (l != c) "$l ($c)" else l)
                                }
                            }
                            Select(value, options, { picked ->
                                if (picked == value || (picked.isNotEmpty() && picked !in choices)) return@Select
                                run.run(guard = GuardAction.Settings, ok = savedText, detail = true) {
                                    val r = env.api.put("system/energy", settings.with(key to picked)) as? JsonObject
                                    if (r != null) energy.value = r
                                }
                            }, label = t("energy.$key"), enabled = !run.running && !status.bool("readOnly"))
                        })
                    }
                    RunnerState(run)
                }
            }
        }
    }

    SettingGroup(t("energy.group.ups")) {
        SettingRow(
            t("ups.title"),
            desc = t("energy.upsDesc"),
            icon = WebIcons["battery"],
            summary = ups.value?.takeIf { it.isNotEmpty() },
            onClick = { a.openPage("ups") },
        )
    }
}

private fun profileLabel(tr: Tr, value: String): String = tr.or("energy.profile.$value", value)

/** „Keine USV“ oder „Modell · am Netz“ (die Zeile auf der Energieseite). */
internal fun upsSummary(tr: Tr, ups: JsonObject): String {
    if (ups.isEmpty()) return ""
    if (ups.str("mode") == "off") return tr.t("ups.mode.off")
    val state = tr.t(
        when {
            !ups.bool("connected") -> "ups.notConnected"
            ups.bool("onBattery") -> "ups.onBattery"
            else -> "ups.online"
        },
    )
    return listOf(ups.str("model"), state).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** „Standard: Aus · 1 von 3 schlafen“. */
private fun sleepSummary(tr: Tr, s: JsonObject): String {
    val current = tr.t("energy.diskSleepCurrent", "value" to tr.sleepDuration(s.int("idleMinutes")))
    val disks = if (sleepListed(s)) s.a("diskStatus").objects() else emptyList()
    if (disks.isEmpty()) return current
    val asleep = disks.count { it.str("state") == "asleep" }
    return "$current · ${tr.t("storage.sleepCount", "asleep" to asleep, "total" to disks.size)}"
}

/** Ob die Antwort Volumes und Platten auflistet (auch leere Listen). */
internal fun sleepListed(s: JsonObject?): Boolean =
    s.bool("listed") || (s?.get("volumeStatus") is JsonArray && s["diskStatus"] is JsonArray)

/**
 * Zeitplan und Wake-on-LAN (PowerSchedule.svelte): zwei Zeilen; jede
 * Änderung schickt sofort den ganzen Zeitplan (`PUT /system/power-schedule`).
 */
@Composable
private fun PowerSchedule() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val state = rememberLoad { env.api.obj("system/power-schedule") }
    val run = rememberRunner()
    val savedText = t("notify.saved")
    val status = state.value
    // Montag zuerst, wie Kalender hier; 0 ist Sonntag.
    val days = listOf(1, 2, 3, 4, 5, 6, 0)
    fun fill(v: JsonObject, time: String): JsonObject = v.with(
        "enabled" to v.bool("enabled"),
        "days" to v.a("days").let { if (it.isEmpty()) JsonArray((0..6).map { d -> kotlinx.serialization.json.JsonPrimitive(d) }) else it },
        "time" to v.str("time").ifEmpty { time },
    )
    val draft = status?.o("settings")?.let { s -> s.with("off" to fill(s.o("off"), "23:30"), "on" to fill(s.o("on"), "07:00")) }
    fun store(next: JsonObject) {
        run.run(guard = GuardAction.Settings, ok = savedText, detail = true) {
            val r = env.api.put("system/power-schedule", next) as? JsonObject
            if (r != null) state.value = r
        }
    }
    val summary = draft?.let {
        listOfNotNull(
            if (it.o("off").bool("enabled")) t("powerSchedule.sumOff", "time" to it.o("off").str("time")) else null,
            if (it.o("on").bool("enabled")) t("powerSchedule.sumOn", "time" to it.o("on").str("time")) else null,
        ).joinToString(" · ").ifEmpty { t("appform.off") }
    }
    SettingRow(
        t("powerSchedule.schedule"),
        desc = t("powerSchedule.intro"),
        icon = WebIcons["clock"],
        id = "power-schedule",
        summary = summary,
    ) {
        state.error?.let { Block { Banner(tr.errorOf(it, detail = true), tone = Tone.Error, action = t("ui.retry"), onAction = { state.reload() }) } }
        if (status != null && draft != null) {
            if (status.bool("readOnly") || status.str("error").isNotEmpty() || status.str("postponed").isNotEmpty()) {
                Block {
                    if (status.bool("readOnly")) Banner(t("powerSchedule.readOnly"), tone = Tone.Warn)
                    if (status.str("error").isNotEmpty()) Banner(t("powerSchedule.problem") + " " + status.str("error"), tone = Tone.Warn)
                    if (status.str("postponed").isNotEmpty()) Banner(t("powerSchedule.postponed", "why" to status.str("postponed")), tone = Tone.Warn)
                }
            }
            for (which in listOf("off", "on")) {
                val entry = draft.o(which)
                val next = status.str(if (which == "off") "nextOff" else "nextOn")
                val enabled = entry.bool("enabled")
                val title = t("powerSchedule.$which")
                SettingSub(
                    title,
                    desc = if (next.isNotEmpty() && enabled) t(if (which == "off") "powerSchedule.nextOff" else "powerSchedule.nextOn", "at" to Fmt.dateTime(tr, next)) else null,
                    control = {
                        Toggle(enabled, { on -> store(draft.with(which to entry.with("enabled" to on))) }, t("appform.on"), t("appform.off"),
                            enabled = !run.running && (which == "off" || status.bool("rtc")), label = title)
                    },
                )
                SettingSub(t("powerSchedule.time"), control = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DayButtons(entry, days, enabled = !run.running && enabled) { list -> store(draft.with(which to entry.with("days" to list))) }
                        TimeSelect(entry.str("time"), { tm -> store(draft.with(which to entry.with("time" to tm))) }, label = t("powerSchedule.time"), enabled = !run.running && enabled)
                    }
                })
            }
            Block {
                Muted(t(if (status.bool("rtc")) "powerSchedule.rtcHint" else "powerSchedule.noRtc"))
            }
            RunnerState(run)
        } else if (state.error == null) {
            LoadingLine(t("common.loading"))
        }
    }
    val anyWake = status.a("interfaces").objects().any { it.bool("supported") }
    val wol = draft?.bool("wakeOnLan") == true
    SettingRow(
        t("powerSchedule.wolTitle"),
        desc = t("powerSchedule.wolDesc"),
        icon = WebIcons["globe"],
        id = "power-wol",
        summary = draft?.let { t(if (wol) "appform.on" else "appform.off") },
        control = if (draft == null) null else ({
            Toggle(wol, { on -> store(draft.with("wakeOnLan" to on)) }, t("appform.on"), t("appform.off"), enabled = !run.running && anyWake, label = t("powerSchedule.wolTitle"))
        }),
    ) {
        for (iface in status.a("interfaces").objects()) {
            SettingSub(iface.str("name"), desc = iface.str("mac"), control = {
                val supported = iface.bool("supported")
                val on = iface.bool("enabled")
                StatusChip(
                    t(if (!supported) "powerSchedule.wolNone" else if (on) "powerSchedule.wolOn" else "powerSchedule.wolOff"),
                    if (!supported) Tone.Inactive else if (on) Tone.Ok else Tone.Info,
                )
            })
        }
        Block { Muted(t("powerSchedule.wolHint")) }
    }
}

/** Die Wochentage als Knöpfe zum An- und Abwählen (zwei Buchstaben, der ganze Name für Bildschirmleser). */
@Composable
private fun DayButtons(entry: JsonObject, days: List<Int>, enabled: Boolean, onChange: (List<Int>) -> Unit) {
    val tr = LocalTr.current
    val chosen = entry.a("days").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
    val c = Titan.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (day in days) {
            val on = day in chosen
            val name = tr.t("common.day$day")
            val shape = RoundedCornerShape(10.dp)
            Text(
                name.take(2),
                style = TitanType.body,
                textAlign = TextAlign.Center,
                color = if (on) c.onAccent else if (enabled) c.text else c.muted,
                modifier = Modifier.clip(shape).background(if (on) c.accent else c.chip).border(1.dp, if (on) c.accent else c.border, shape)
                    .clickable(enabled = enabled, role = Role.Checkbox) { onChange(if (on) chosen - day else chosen + day) }
                    .semantics { contentDescription = name; stateDescription = if (on) "☑" else "☐" }
                    .heightIn(min = 40.dp).widthIn(min = 40.dp).padding(horizontal = 6.dp, vertical = 9.dp),
            )
        }
    }
}
