package io.github.ra5on.titanos.agent.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.ui.theme.Titan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Ein Feld, das wie ein Eingabefeld aussieht und einen Wähler öffnet. */
@Composable
private fun PickerBox(text: String, label: String, icon: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.heightIn(min = 44.dp).clip(shape).background(c.bg).border(1.dp, c.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; stateDescription = text }
            .padding(horizontal = 12.dp, vertical = 8.dp).alpha(if (enabled) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WIcon(icon, 18.dp, c.muted)
        Text(text.ifEmpty { "–" }, style = AgentText.md.copy(fontSize = AgentText.lg.fontSize), color = c.text)
    }
}

/**
 * Uhrzeit „HH:MM“ über den Wähler von Material 3 (24 Stunden nach der
 * Einstellung des Handys); im Wähler lässt sich auf Eintippen umschalten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, width: Dp? = null) {
    val context = LocalContext.current
    val tr = tr()
    var open by remember { mutableStateOf(false) }
    val h = value.substringBefore(':').toIntOrNull()?.coerceIn(0, 23) ?: 7
    val m = value.substringAfter(':', "").toIntOrNull()?.coerceIn(0, 59) ?: 0
    val is24 = DateFormat.is24HourFormat(context)
    val shown = if (value.isEmpty()) "" else if (is24) "%02d:%02d".format(h, m) else java.time.LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    PickerBox(shown, label, "clock", enabled, if (width != null) modifier.width(width) else modifier.fillMaxWidth()) { open = true }
    if (open) {
        val state = rememberTimePickerState(h, m, is24)
        var typing by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton({ open = false; onChange("%02d:%02d".format(state.hour, state.minute)) }) { Text(tr.t("common.save")) } },
            dismissButton = {
                Row {
                    TextButton({ typing = !typing }) { Text(if (typing) (if (tr.de) "Uhr" else "Dial") else (if (tr.de) "Eintippen" else "Type")) }
                    TextButton({ open = false }) { Text(tr.t("common.cancel")) }
                }
            },
            title = { Text(label) },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { if (typing) TimeInput(state) else TimePicker(state) } },
        )
    }
}

/** Datum „JJJJ-MM-TT“ über den Datumswähler von Material 3, mit Umschalten auf Eintippen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tr = tr()
    var open by remember { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    val shown = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(if (tr.de) Locale.GERMAN else Locale.ENGLISH)).orEmpty()
    PickerBox(shown, label, "calendar", enabled, modifier.fillMaxWidth()) { open = true }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialDisplayMode = DisplayMode.Picker,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({
                    open = false
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                }) { Text(tr.t("common.save")) }
            },
            dismissButton = { TextButton({ open = false }) { Text(tr.t("common.cancel")) } },
        ) { DatePicker(state, showModeToggle = true, title = { Text(label, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }, colors = DatePickerDefaults.colors()) }
    }
}
