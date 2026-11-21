package io.github.ra5on.titanos.pim

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.R as UiR

/** Farbe aus „#rrggbb“ (Kalender, Gruppen); ohne gültige Farbe das Violett des Webs. */
fun hexColor(hex: String?): Color = runCatching {
    val h = hex?.trim()?.removePrefix("#").orEmpty()
    Color((0xFF000000 or h.take(6).toLong(16)).toInt())
}.getOrDefault(Color(0xFF7C3AED))

/** Farbpunkt; die Farbe steht immer auch als Name daneben (nie nur Farbe). */
@Composable
fun ColorDot(color: Color, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color).border(1.dp, Titan.colors.border, CircleShape))
}

/**
 * Aufklappbare Gruppe wie die Systemsteuerung im Web: Titel, Zusammenfassung
 * des aktuellen Werts, darunter die Zeilen. Der Zustand steht für
 * Bildschirmleser in Worten da.
 */
@Composable
fun SettingsGroup(
    title: String,
    summary: String? = null,
    initiallyOpen: Boolean = false,
    key: String = title,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(key) { mutableStateOf(initiallyOpen) }
    val opened = stringResource(R.string.pim_group_open)
    val closed = stringResource(R.string.pim_group_closed)
    Card(Modifier.padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp).semantics { heading(); stateDescription = if (open) opened else closed },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = TitanType.rowTitle, color = Titan.colors.text)
                if (summary != null && !open) Text(summary, style = TitanType.rowSub, color = Titan.colors.muted, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(TitanIcons.down, null, tint = Titan.colors.muted, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
        }
        AnimatedVisibility(open) {
            Column {
                Divider()
                content()
            }
        }
    }
}

/** Zeile mit Schalter; der Zustand steht in Worten im Untertitel und für Bildschirmleser. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null, divider: Boolean = false, enabled: Boolean = true, icon: (@Composable () -> Unit)? = null) {
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    ListRow(
        title,
        sub = sub,
        icon = icon,
        divider = divider,
        onClick = if (enabled) ({ onChange(!checked) }) else null,
        right = { Toggle(checked, if (enabled) onChange else null, on, off, enabled) },
    )
}

/** Auswahl aus mehreren Werten als Liste mit Auswahlkreisen. */
@Composable
fun <T> ChoiceRows(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, subs: Map<T, String> = emptyMap()) {
    options.forEachIndexed { i, (value, label) ->
        ListRow(
            label,
            sub = subs[value],
            divider = i > 0,
            onClick = { onSelect(value) },
            icon = { RadioMark(value == selected) },
        )
    }
}

/** Ein Chip-artiger Umschalter für Ansichten (Tag/Woche/Monat/Jahr). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.clip(shape).background(c.chip).border(1.dp, c.border, shape).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((key, label) in options) {
            val on = key == selected
            Text(
                label,
                style = TitanType.small.copy(fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else null),
                color = c.text,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) c.surface else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(key) }.semantics { if (on) stateDescription = "✓" }
                    .heightIn(min = 36.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
