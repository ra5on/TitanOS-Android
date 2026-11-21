package io.github.ra5on.titanos.vms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk

// Kleine Bausteine der Seiten VMs und LXC im Stil der übrigen App-Seiten.
// Texte brechen immer um (kein maxLines, keine Auslassung).

@Composable
@ReadOnlyComposable
fun s(id: Int, vararg args: Any): String = stringResource(id, *args)

/** Zustand einer VM in Worten und mit Zeichen. */
@Composable
fun VmStateMark(vm: Vm, modifier: Modifier = Modifier) {
    when {
        vm.job?.state == "running" -> StateMark(t("vms.state.${vm.state}") + " · " + s(R.string.vms_preparing), StateKind.Busy, modifier)
        vm.job?.state == "failed" -> StateMark(t("vms.state.${vm.state}") + " · " + s(R.string.vms_problem), StateKind.Error, modifier)
        vm.backupHeld == true -> StateMark(t("vms.backupHeld"), StateKind.Warn, modifier)
        vm.ioError == true -> StateMark(s(R.string.vms_paused), StateKind.Warn, modifier)
        !vm.available -> StateMark(t("vms.state.${vm.state}") + " · " + s(R.string.vms_volume_missing), StateKind.Warn, modifier)
        vm.running -> StateMark(t("vms.state.running"), StateKind.Ok, modifier)
        else -> StateMark(t("vms.state.stopped"), StateKind.Off, modifier)
    }
}

/** Abschnitt mit schlichter Überschrift und Knöpfen daneben (umbrechend). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Group(title: String?, modifier: Modifier = Modifier, tools: (@Composable FlowRowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 14.dp)) {
        if (title != null || tools != null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (title != null) {
                    Text(title, style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.weight(1f).padding(start = 2.dp).semantics { heading() })
                }
                tools?.invoke(this)
            }
        }
        val shape = RoundedCornerShape(14.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(Titan.colors.surface).border(1.dp, Titan.colors.border, shape), content = content)
    }
}

/** Ein Hinweis in Farbe, Zeichen und Worten. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoticeBox(level: NoticeLevel, text: String, modifier: Modifier = Modifier, detail: String = "", actions: (@Composable FlowRowScope.() -> Unit)? = null) {
    val c = Titan.colors
    val (ink, icon) = when (level) {
        NoticeLevel.Error -> c.dangerInk to TitanIcons.x
        NoticeLevel.Warn -> c.warnInk to TitanIcons.warn
        NoticeLevel.Info -> c.accent to TitanIcons.info
        NoticeLevel.Ok -> c.okInk to TitanIcons.check
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).background(ink.copy(alpha = .10f).compositeOver(c.surface))
            .border(1.dp, ink.copy(alpha = .45f).compositeOver(c.border), shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
            Text(text, style = TitanType.body, color = c.text, modifier = Modifier.weight(1f))
        }
        if (detail.isNotBlank()) {
            Text(detail, style = TitanType.small.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = c.text)
        }
        if (actions != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = actions)
        }
    }
}

/** Zeile mit Name links und Wert darunter/rechts; der Wert bricht um. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FactRow(label: String, value: String, divider: Boolean = false, mono: Boolean = false, warn: Boolean = false, tools: (@Composable FlowRowScope.() -> Unit)? = null) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth()) {
        if (divider) io.github.ra5on.titanos.ui.components.Divider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(label, style = TitanType.small.copy(fontSize = TitanType.rowSub.fontSize), color = c.muted)
                Text(
                    value.ifEmpty { "–" },
                    style = if (mono) TitanType.body.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono) else TitanType.body,
                    color = if (warn) c.warnInk else c.text,
                )
            }
            if (tools != null) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = tools)
            }
        }
    }
}

/** Auswahlzeile mit Kreis (Vorlage, Volume, Modus …). */
@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, sub: String? = null, enabled: Boolean = true, divider: Boolean = false) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth()) {
        if (divider) io.github.ra5on.titanos.ui.components.Divider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .semantics { stateDescription = if (selected) "✓" else "" }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioMark(selected)
            Column(Modifier.weight(1f)) {
                Text(title, style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = if (enabled) c.text else c.muted)
                if (sub != null) Text(sub, style = TitanType.small, color = c.muted)
            }
        }
    }
}

/** Ankreuzfeld als Zeile (Dialoge). */
@Composable
fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null, enabled: Boolean = true) {
    val c = Titan.colors
    val on = s(R.string.vms_checked)
    val off = s(R.string.vms_unchecked)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }
            .semantics { stateDescription = if (checked) on else off }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val shape = RoundedCornerShape(5.dp)
        androidx.compose.foundation.layout.Box(
            Modifier.size(22.dp).clip(shape).background(if (checked) c.accent else Color.Transparent).border(2.dp, if (checked) c.accent else c.muted, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(TitanIcons.check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = TitanType.body, color = if (enabled) c.text else c.muted)
            if (sub != null) Text(sub, style = TitanType.small, color = c.muted)
        }
    }
}

/** Zahlenfeld; ungültige Eingaben bleiben stehen, bis sie passen. */
@Composable
fun NumberField(label: String, value: Long, onChange: (Long) -> Unit, hint: String? = null, enabled: Boolean = true, unit: String? = null) {
    var text by remember(value) { mutableStateOf(if (value == 0L && unit == null) "" else value.toString()) }
    Field(
        if (unit != null) "$label ($unit)" else label,
        text,
        { v ->
            text = v.filter { it.isDigit() }.take(9)
            text.toLongOrNull()?.let(onChange) ?: if (text.isEmpty()) onChange(0) else Unit
        },
        hint = hint,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
        enabled = enabled,
    )
}

/** Kleiner Knopf als Werkzeug einer Gruppe. */
@Composable
fun Tool(text: String, icon: ImageVector?, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false, primary: Boolean = false) =
    io.github.ra5on.titanos.compute.SmallButton(text, icon, onClick, danger = danger, enabled = enabled, primary = primary)

/** Ein Wert mit Beschriftung, gedämpft (Untertitel, Hinweise unter Zeilen). */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.small.copy(fontSize = TitanType.rowSub.fontSize), color = Titan.colors.muted, modifier = modifier)
}

@Composable
fun WarnText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.small.copy(fontSize = TitanType.rowSub.fontSize), color = Titan.colors.warnInk, modifier = modifier)
}

/** Laufzeit in Worten wie formatDuration im Web. */
@Composable
fun durationText(seconds: Long): String {
    val days = seconds / 86400
    val hours = seconds % 86400 / 3600
    val minutes = seconds % 3600 / 60
    return when {
        days > 0 -> t("duration.daysHours", "days" to days, "hours" to hours)
        hours > 0 -> t("duration.hoursMinutes", "hours" to hours, "minutes" to minutes)
        seconds < 60 -> t("duration.underMinute")
        else -> t("duration.minutes", "minutes" to minutes)
    }
}

/** Wochentage kurz (Mo, Di …) in der Reihenfolge Montag zuerst. */
@Composable
fun daysText(days: List<Int>): String = WEEK.filter { it in days }.map { t("backup.dayShort.$it") }.joinToString(", ")
