package io.github.ra5on.titanos.tools

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.FieldLabel
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.NumberField
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.admin.uiLocale
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType

/** Uhrzeit „HH:MM“ als Zeile; Tippen öffnet die Uhr von Android (24 Stunden). */
@Composable
fun TimeRow(title: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, icon: String? = "clock", divider: Boolean = false, enabled: Boolean = true) {
    val context = LocalContext.current
    SettingRow(
        title,
        modifier = modifier,
        icon = icon,
        divider = divider,
        enabled = enabled,
        chevron = enabled,
        onClick = if (enabled) ({
            val (h, m) = value.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            TimePickerDialog(context, { _, hour, minute -> onChange("%02d:%02d".format(hour, minute)) }, h, m, true).show()
        }) else null,
        below = { Text(value.ifEmpty { "–" }, style = TitanType.rowSub, color = Titan.colors.accent, modifier = Modifier.padding(top = 2.dp)) },
    )
}

/**
 * Zeitplan ohne Cron (ScheduleField im Web): alle N Minuten, stündlich,
 * täglich, an Wochentagen oder monatlich; darunter der Satz und die nächsten
 * drei Läufe in der Zone des NAS. Der Cron-Ausdruck steht unter „Erweitert“.
 * Der Wert ist immer ein Cron-Ausdruck ("" bei unvollständiger Wahl).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleField(cron: String, onChange: (String) -> Unit, timezone: String?) {
    val c = Titan.colors
    // Ein neuer Zeitplan beginnt täglich um 03:00; einer, der nicht in die einfache Wahl passt, bleibt unter „Erweitert“.
    var simple by remember { mutableStateOf(fromCron(cron) ?: if (cron.isBlank()) simpleOf("daily", null) else null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (cron.isBlank()) simple?.let { toCron(it) }?.let(onChange)
    }
    fun set(next: Simple) {
        simple = next
        onChange(toCron(next) ?: "")
    }
    val kinds = listOf("minutes", "hourly", "daily", "weekdays", "monthly")
    val locale = uiLocale()
    val context = LocalContext.current
    val tr: (String, Map<String, Any?>) -> String = { key, params -> context.msg(Msg(key, params)) }
    val join: (List<String>) -> String = { items -> joinList(items, locale) }
    val s = simple
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card {
            ChoiceRow(
                t("schedule.kind"),
                (if (s == null) listOf("" to t("schedule.kind.advanced")) else emptyList()) + kinds.map { it to t("schedule.kind.$it") },
                s?.kind ?: "",
                { if (it.isNotEmpty()) set(simpleOf(it, s)) },
                icon = "calendar",
            )
            when (s) {
                is Simple.Minutes -> ChoiceRow(t("schedule.every"), MINUTE_STEPS.map { it.toString() to t("schedule.minutes", "n" to it) }, s.every.toString(), { set(Simple.Minutes(it.toInt())) }, divider = true)
                is Simple.Monthly -> ChoiceRow(
                    t("schedule.monthDay"),
                    (1..28).map { it.toString() to "$it." } + (LAST_DAY.toString() to t("schedule.lastDay")),
                    s.day.toString(),
                    { set(s.copy(day = it.toInt())) },
                    divider = true,
                )
                else -> {}
            }
            s?.timeOrNull?.let { time ->
                TimeRow(t("schedule.time"), time, { v ->
                    when (s) {
                        is Simple.Daily -> set(s.copy(time = v))
                        is Simple.Weekdays -> set(s.copy(time = v))
                        is Simple.Monthly -> set(s.copy(time = v))
                        else -> {}
                    }
                }, divider = true)
            }
        }
        if (s is Simple.Hourly) {
            NumberField(t("schedule.minute"), s.minute.toLong(), { v -> if (v != null && v in 0..59) set(Simple.Hourly(v.toInt())) }, min = 0, max = 59, modifier = Modifier.widthIn(max = 240.dp))
        }
        if (s is Simple.Weekdays) {
            Column {
                FieldLabel(t("schedule.weekdays"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (day in WEEK) {
                        val on = day in s.days
                        val shape = RoundedCornerShape(10.dp)
                        val name = t("schedule.dayName.$day")
                        Text(
                            t("schedule.dayShort.$day"),
                            style = TitanType.body,
                            color = if (on) c.onAccent else c.text,
                            modifier = Modifier.clip(shape).background(if (on) c.accent else c.chip).border(1.dp, if (on) c.accent else c.border, shape)
                                .toggleable(on, role = Role.Checkbox) {
                                    val days = if (on) s.days - day else WEEK.filter { d -> d == day || d in s.days }
                                    set(s.copy(days = days))
                                }
                                .semantics { contentDescription = name }
                                .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 11.dp),
                        )
                    }
                }
            }
        }
        val valid = parseCron(cron) != null
        val noDays = s is Simple.Weekdays && s.days.isEmpty()
        when {
            noDays -> Notice(t("schedule.noDays"), tone = Tone.Warn)
            !valid -> Notice(t("schedule.invalid"), tone = Tone.Error)
            else -> {
                val zone = timezone?.ifEmpty { null } ?: java.util.TimeZone.getDefault().id
                val next = nextRuns(cron, 3, zone)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(describeCron(cron, tr, join, "say"), style = TitanType.body, color = c.text)
                    if (next.isNotEmpty()) {
                        Muted(t("schedule.next", "zone" to zone))
                        for (at in next) Text("• " + formatDateTime(at.toString(), zone), style = TitanType.body, color = c.text)
                    } else {
                        Muted(t("schedule.never"))
                    }
                }
            }
        }
        Disclosure(t("schedule.advanced"), initiallyOpen = s == null) {
            Field(t("schedule.cron"), cron, { v ->
                onChange(v)
                simple = fromCron(v)
            }, mono = true, placeholder = t("schedule.cronExample"))
            Muted(t("schedule.advancedHint"))
        }
    }
}

/** „a, b und c“ in der Sprache der App (Intl.ListFormat im Web). */
fun joinList(items: List<String>, locale: java.util.Locale): String {
    val and = if (locale.language == "de") "und" else "and"
    return when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} $and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + (if (locale.language == "de") " $and " else ", $and ") + items.last()
    }
}
