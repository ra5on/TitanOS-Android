package io.github.ra5on.titanos.settings.pages.hardware

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.delay

// Kleine Bausteine der Seiten unter „Hardware und Energie“ und „Daten und
// Sicherung“, die es (noch) nicht in common/ gibt.

/** Fragt in Abständen neu (poll() im Web); [skip] lässt eine Runde aus, z. B. während gespeichert wird. */
@Composable
internal fun Poll(intervalMs: Long, key: Any? = Unit, skip: () -> Boolean = { false }, block: suspend () -> Unit) {
    val current by rememberUpdatedState(block)
    val skipNow by rememberUpdatedState(skip)
    LaunchedEffect(key) {
        while (true) {
            delay(intervalMs)
            if (!skipNow()) runCatching { current() }
        }
    }
}

/** Ein Wert in Mono (Adressen, IDs, Messwerte). */
@Composable
internal fun Mono(text: String, modifier: Modifier = Modifier, muted: Boolean = true) {
    Text(text, style = TitanType.rowSub.copy(fontFamily = TitanFonts.mono), color = if (muted) Titan.colors.muted else Titan.colors.text, modifier = modifier)
}

/** Ein gedämpfter Satz (p.muted). */
@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(text, style = if (small) TitanType.small else TitanType.rowSub, color = Titan.colors.muted, modifier = modifier.padding(vertical = 2.dp))
}

/** Ein Satz in Textfarbe. */
@Composable
internal fun Plain(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.body, color = Titan.colors.text, modifier = modifier)
}

/** Unterüberschrift in einem aufgeklappten Bereich oder Dialog. */
@Composable
internal fun SubHead(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = Titan.colors.text, modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() })
}

/** Block in einer aufgeklappten Zeile (.srow-block). */
@Composable
internal fun Block(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** Knöpfe und Zustände nebeneinander, die umbrechen (.row-actions). */
@Composable
internal fun Actions(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Eine Zeile Beschriftung → Wert (dl/dt/dd im Web), am Handy untereinander. */
@Composable
internal fun Fact(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TitanType.rowSub, color = Titan.colors.muted, modifier = Modifier.widthIn(min = 90.dp))
        value()
    }
}

/** Ein umrandeter Kasten (Freigabe-Daten der USV, gefundene Geräte). */
@Composable
internal fun Framed(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).border(1.dp, Titan.colors.border, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** „41,5 °C“ oder „–“ (formatDecimal im Web). */
internal fun celsius(tr: Tr, value: Double?, digits: Int = 1): String = value?.let { "${Fmt.number(tr, it, digits)} °C" } ?: "–"

/** Ganze Grad: „65 °C“ (toFixed(0) im Web). */
internal fun celsius0(value: Double): String = "${Math.round(value)} °C"

/** „nach 30 Minuten“, „Aus“ … (sleepDuration im Web). */
internal fun Tr.sleepDuration(minutes: Int): String = when {
    minutes == 0 -> t("storage.sleepOff")
    minutes < 60 -> t("storage.sleepMinutes", "count" to minutes)
    minutes == 60 -> t("storage.sleepHour")
    else -> t("storage.sleepHours", "count" to minutes / 60)
}

/** "07:30" gültig? */
internal val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

/**
 * Eine Uhrzeit (input type=time im Web) als Stunde und Minute zum Wählen –
 * jede Wahl ist ein ganzer Wert, nie ein halb getippter. Mit [emptyText]
 * steht vorn die Wahl „keine Uhrzeit“ (leerer Wert).
 */
@Composable
internal fun TimeSelect(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true, emptyText: String? = null) {
    val valid = TIME.matches(value)
    val hour = if (valid) value.substring(0, 2) else ""
    val minute = if (valid) value.substring(3, 5) else "00"
    val hours = buildList {
        if (emptyText != null) add("" to emptyText)
        for (h in 0..23) add("%02d".format(h) to "%02d".format(h))
    }
    val minutes = (0..59).map { "%02d".format(it) to "%02d".format(it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Select(if (emptyText == null && !valid) "00" else hour, hours, { h -> onChange(if (h.isEmpty()) "" else "$h:$minute") }, label = label, enabled = enabled)
        if (hour.isNotEmpty() || emptyText == null) {
            Text(":", style = TitanType.body, color = Titan.colors.text)
            Select(minute, minutes, { m -> onChange("${hour.ifEmpty { "00" }}:$m") }, label = label, enabled = enabled)
        }
    }
}

/**
 * Ein Dialog über der Seite, dessen Inhalt scrollt (Dialog.svelte): Titel,
 * Inhalt, Knöpfe unten rechts (der gefährliche links von „Abbrechen“).
 * [locked]: weder Zurück noch Tippen daneben schließen ihn.
 */
@Composable
internal fun BoxScope.HwDialog(
    title: String,
    onDismiss: () -> Unit,
    locked: Boolean = false,
    buttons: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = { if (!locked) onDismiss() })
    val c = Titan.colors
    val closeLabel = io.github.ra5on.titanos.ui.i18n.t("common.close")
    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = .5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!locked) onDismiss() }
            .semantics { contentDescription = closeLabel },
    )
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 24.dp).widthIn(max = 640.dp).fillMaxWidth().clip(shape).background(c.surface)
            .border(1.dp, c.border, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
    ) {
        Text(title, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * (20f / 22f)), color = c.text, modifier = Modifier.padding(bottom = 10.dp).semantics { heading() })
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        if (buttons != null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) { buttons() }
        }
    }
}

/** Ein Knopf eines Dialogs als Text (wie in ConfirmDialog). */
@Composable
internal fun DialogButton(text: String, onClick: () -> Unit, danger: Boolean = false, enabled: Boolean = true) {
    val c = Titan.colors
    Text(
        text,
        style = TitanType.button.copy(fontSize = TitanType.body.fontSize),
        color = when {
            !enabled -> c.muted
            danger -> c.dangerInk
            else -> c.accent
        },
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Ein Punkt einer Liste (li mit .dot im Web). */
@Composable
internal fun Bullet(text: String, muted: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 8.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(Titan.colors.muted))
        Text(text, style = TitanType.body, color = if (muted) Titan.colors.muted else Titan.colors.text, modifier = Modifier.weight(1f))
    }
}

/**
 * Rückfrage wie `Overlays.confirm`, aber mit benannter Abdunkelung (die
 * Prüfung der Bildtests verlangt für jedes Bedienelement einen Namen): der
 * gefährliche Knopf links von „Abbrechen“, nichts per Enter oder Autofokus.
 */
internal fun io.github.ra5on.titanos.ui.components.Overlays.ask(title: String, text: String, action: String, cancel: String, danger: Boolean = true, onConfirm: () -> Unit) =
    show { close ->
        HwDialog(title, onDismiss = close, buttons = {
            DialogButton(action, { close(); onConfirm() }, danger = danger)
            DialogButton(cancel, close)
        }) { Text(text, style = TitanType.body, color = Titan.colors.muted) }
    }
