package io.github.ra5on.titanos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk

// Bausteine der Systemsteuerung im Zeilenaufbau wie im Web (PLAN 78,
// SettingGroup/SettingRow/SettingSub, Vorbild Windows 11): eine Gruppe ist
// eine Überschrift über ihren Zeilen, eine Zeile mit Teilen klappt auf. Am
// Handy steht das Bedienelement unter dem Text, ab 600 dp rechts daneben.
// Texte brechen immer um; nie gekürzt.

/**
 * Der Ort, zu dem die Suche führt: die Titel der Gruppen und Zeilen von
 * außen nach innen. Eine Zeile mit passendem Titel klappt auf, rückt ins
 * Bild und ist kurz markiert.
 */
class FindTarget(val titles: List<String>) {
    var reached by mutableStateOf(false)
}

val LocalFindTarget = compositionLocalOf<FindTarget?> { null }

private fun FindTarget?.wants(title: String): Boolean = this != null && titles.any { it.equals(title, ignoreCase = true) }

private fun FindTarget?.isLast(title: String): Boolean = this != null && titles.lastOrNull()?.equals(title, ignoreCase = true) == true

/** Ton eines Zustands: immer mit Text und Symbol, nie nur Farbe. */
enum class Tone { Info, Ok, Warn, Error, Inactive }

/** Eine Gruppe (.sgroup): Überschrift, Satz darunter, Knöpfe rechts, dann die Zeilen. */
@Composable
fun SettingGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    desc: String? = null,
    tools: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Titan.colors
    val find = LocalFindTarget.current
    val requester = remember { BringIntoViewRequester() }
    if (title != null && find.isLast(title)) {
        LaunchedEffect(find) { requester.bringIntoView(); find?.reached = true }
    }
    Column(modifier.fillMaxWidth().padding(top = 18.dp).bringIntoViewRequester(requester), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null || tools != null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (title != null) {
                    Text(title, style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
                }
                tools?.invoke(this)
            }
            if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 6.dp))
        }
        content()
    }
}

/**
 * Eine Zeile (.srow): Symbol, Titel, Erklärung, rechts der Zustand in Worten
 * ([summary]) und/oder ein Bedienelement ([control]). Mit [onClick] führt sie
 * woandershin (Pfeil), mit [content] klappt sie auf (Pfeil nach unten) und
 * zeigt ihre Teile ([SettingSub]). [id] merkt sich das Aufklappen.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    icon: ImageVector? = null,
    summary: String? = null,
    badge: String? = null,
    id: String? = null,
    onClick: (() -> Unit)? = null,
    startOpen: Boolean = false,
    control: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Titan.colors
    val find = LocalFindTarget.current
    val hit = find.wants(title)
    var open by rememberSaveable(id ?: title) { mutableStateOf(startOpen) }
    if (hit && content != null) open = true
    val requester = remember { BringIntoViewRequester() }
    var marked by remember { mutableStateOf(false) }
    if (find.isLast(title)) {
        LaunchedEffect(find) {
            marked = true
            requester.bringIntoView()
            find?.reached = true
            kotlinx.coroutines.delay(2400)
            marked = false
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val expandable = content != null
    val action: (() -> Unit)? = when {
        expandable -> ({ open = !open })
        else -> onClick
    }
    Column(
        modifier.fillMaxWidth().bringIntoViewRequester(requester).clip(shape)
            .background(if (marked) c.accent.copy(alpha = .16f).compositeOver(c.surface) else c.surface)
            .border(1.dp, if (marked) c.accent else c.border, shape),
    ) {
        val titleStyle = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize)
        val widestWord = io.github.ra5on.titanos.ui.rememberWidestWord(listOf(title), titleStyle)
        val density = androidx.compose.ui.platform.LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val full = maxWidth
            val wide = full >= 600.dp
            // Passt das längste Wort des Titels nicht neben Symbol und Pfeil (große
            // Schrift, Bindestrich-Wörter wie „Zwei-Faktor-Anmeldung“), stehen
            // Symbol und Pfeil in einer eigenen Zeile darüber: ganze Wörter.
            val trailing = expandable || onClick != null
            val beside = (if (icon != null) 36.dp else 0.dp) + (if (trailing) 34.dp else 0.dp) +
                (if (wide && (summary != null || control != null)) full * 0.5f else 0.dp)
            val tight = (icon != null || trailing) && with(density) { widestWord.toDp() } > full - 24.dp - beside
            Row(
                Modifier.fillMaxWidth()
                    .then(if (action != null) Modifier.clickable(role = Role.Button, onClick = action) else Modifier)
                    .semantics(mergeDescendants = action != null) {
                        if (expandable) stateDescription = if (open) "▾" else "▸"
                    }
                    .heightIn(min = 56.dp).padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (icon != null && !tight) Icon(icon, null, tint = c.muted, modifier = Modifier.size(22.dp).align(if (wide) Alignment.CenterVertically else Alignment.Top))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (tight) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) Icon(icon, null, tint = c.muted, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.weight(1f))
                            when {
                                expandable -> Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
                                onClick != null -> Icon(TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(18.dp))
                                else -> Unit
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = titleStyle, color = c.text)
                        if (badge != null) Badge(badge)
                    }
                    if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted)
                    if (!wide && (summary != null || control != null)) {
                        FlowRow(
                            Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (summary != null) Text(summary, style = TitanType.rowSub, color = c.text)
                            control?.invoke()
                        }
                    }
                }
                if (wide && (summary != null || control != null)) {
                    Row(Modifier.widthIn(max = full * 0.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (summary != null) Text(summary, style = TitanType.rowSub, color = c.muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
                        control?.invoke()
                    }
                }
                when {
                    tight -> Unit
                    expandable -> Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
                    onClick != null -> Icon(TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(18.dp))
                    else -> Unit
                }
            }
        }
        if (expandable && open) {
            Divider()
            Column(Modifier.fillMaxWidth().padding(start = if (icon != null) 50.dp else 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
                content?.invoke(this)
            }
        }
    }
}

/** Ein Teil einer aufgeklappten Zeile (.srow-sub): Titel und Erklärung, rechts bzw. darunter das Feld. */
@Composable
fun SettingSub(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    control: (@Composable () -> Unit)? = null,
) {
    val c = Titan.colors
    val find = LocalFindTarget.current
    val requester = remember { BringIntoViewRequester() }
    var marked by remember { mutableStateOf(false) }
    if (find.isLast(title)) {
        LaunchedEffect(find) {
            marked = true
            requester.bringIntoView()
            find?.reached = true
            kotlinx.coroutines.delay(2400)
            marked = false
        }
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().bringIntoViewRequester(requester).clip(RoundedCornerShape(8.dp))
            .background(if (marked) c.accent.copy(alpha = .16f) else Color.Transparent),
    ) {
        val full = maxWidth
        val wide = full >= 520.dp
        if (wide) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = TitanType.body, color = c.text)
                    if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted)
                }
                if (control != null) Box(Modifier.widthIn(max = full * 0.55f)) { control() }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = TitanType.body, color = c.text)
                if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted)
                control?.invoke()
            }
        }
    }
}

/** Kleines Abzeichen („neu“, „Beta“). */
@Composable
fun Badge(text: String, tone: Tone = Tone.Info) {
    val c = Titan.colors
    val color = toneInk(tone)
    Text(
        text,
        style = TitanType.small,
        color = if (tone == Tone.Info) c.text else color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .16f).compositeOver(c.surface))
            .border(1.dp, color.copy(alpha = .5f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun toneInk(tone: Tone): Color {
    val c = Titan.colors
    return when (tone) {
        Tone.Info -> c.accent
        Tone.Ok -> c.okInk
        Tone.Warn -> c.warnInk
        Tone.Error -> c.dangerInk
        Tone.Inactive -> c.muted
    }
}

/** Zustand mit Punkt und Wort („● an“), nie nur Farbe. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = Titan.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val dot = when (tone) {
            Tone.Ok -> c.statusOk
            Tone.Warn -> c.statusWarn
            Tone.Error -> c.statusError
            Tone.Inactive -> c.statusInactive
            Tone.Info -> c.accent
        }
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Text(text, style = TitanType.rowSub, color = c.text)
    }
}

/** Hinweis in einem Kasten (.notice): Info, Erfolg, Warnung oder Fehler, mit Symbol und optionalem Knopf. */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Info,
    title: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Titan.colors
    val (bg, border, fg) = when (tone) {
        Tone.Warn -> Triple(c.warnBg, c.warnBorder, c.warnText)
        Tone.Error -> Triple(c.errorBg, c.errorBorder, c.errorText)
        Tone.Ok -> Triple(c.okBg, c.okBorder, c.okText)
        else -> Triple(c.accent.copy(alpha = .12f).compositeOver(c.surface), c.accent.copy(alpha = .35f).compositeOver(c.border), c.text)
    }
    val icon = when (tone) {
        Tone.Warn, Tone.Error -> TitanIcons.warn
        Tone.Ok -> TitanIcons.check
        else -> TitanIcons.info
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).background(bg).border(1.dp, border, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (tone == Tone.Info) c.accent else fg, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = fg)
            Text(text, style = TitanType.body, color = fg)
            if (action != null && onAction != null) SmallButton(action, onAction)
        }
    }
}

/** Kleiner Knopf (40 dp) für Zeilen und Gruppen; der Text bricht um. */
@Composable
fun SmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Normal,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    val bg = if (kind == ButtonKind.Primary) c.accent else c.chip
    val fg = when (kind) {
        ButtonKind.Primary -> c.onAccent
        ButtonKind.Danger -> c.dangerInk
        ButtonKind.Normal -> c.text
    }
    Row(
        modifier.heightIn(min = 40.dp).clip(shape).background(if (enabled) bg else bg.copy(alpha = .5f).compositeOver(c.surface))
            .border(1.dp, if (kind == ButtonKind.Danger) c.dangerInk.copy(alpha = .6f) else if (kind == ButtonKind.Primary) c.accent else c.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = if (enabled) fg else fg.copy(alpha = .6f), modifier = Modifier.size(18.dp))
        Text(text, style = TitanType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = if (enabled) fg else fg.copy(alpha = .7f), textAlign = TextAlign.Center)
    }
}

/** Eine Auswahl (wie `<select>`): zeigt die gewählte Option, Tippen öffnet die Liste. */
@Composable
fun <T> Select(
    value: T,
    options: List<Pair<T, String>>,
    onChange: (T) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Titan.colors
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second ?: value?.toString().orEmpty()
    val shape = RoundedCornerShape(10.dp)
    Box(modifier) {
        Row(
            Modifier.heightIn(min = 40.dp).clip(shape).background(c.chip).border(1.dp, c.border, shape)
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = current }
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(current, style = TitanType.body, color = if (enabled) c.text else c.muted, modifier = Modifier.weight(1f, fill = false))
            Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(c.surface)) {
            for ((v, text) in options) {
                DropdownMenuItem(
                    text = { Text(text, style = TitanType.body, color = c.text) },
                    onClick = { expanded = false; onChange(v) },
                    leadingIcon = { RadioMark(v == value) },
                )
            }
        }
    }
}

/** Mehrere Knöpfe, einer gewählt (Segments.svelte); bricht um statt zu kürzen. */
@Composable
fun <T> Segments(value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Titan.colors
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((v, text) in options) {
            val on = v == value
            val shape = RoundedCornerShape(10.dp)
            Text(
                text,
                style = TitanType.body,
                color = if (on) c.text else c.muted,
                modifier = Modifier.clip(shape).background(if (on) c.accent.copy(alpha = .22f).compositeOver(c.surface) else c.chip)
                    .border(1.dp, if (on) c.accent else c.border, shape)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onChange(v) }
                    .semantics { stateDescription = if (on) "✓" else "" }
                    .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

/** Reiter einer Seite (Tabs.svelte): stehen nebeneinander und brechen um. */
@Composable
fun <T> Tabs(value: T, tabs: List<Pair<T, String>>, onChange: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Titan.colors
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((v, text) in tabs) {
            val on = v == value
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Tab) { onChange(v) }
                    .semantics { stateDescription = if (on) "✓" else "" }
                    .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text, style = TitanType.body.copy(fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal), color = if (on) c.text else c.muted)
                Spacer(Modifier.heightIn(min = 4.dp))
                Box(Modifier.width(24.dp).heightIn(min = 3.dp, max = 3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) c.accent else Color.Transparent))
            }
        }
    }
}

/** Ein Eingabefeld (.input) mit Beschriftung für Bildschirmleser; [password] verdeckt den Inhalt. */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    mono: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minHeight: Dp = 44.dp,
    suffix: String? = null,
) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    val style: TextStyle = if (mono) TitanType.body.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono) else TitanType.body
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = singleLine || password,
        textStyle = style.copy(color = if (enabled) c.text else c.muted),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = if (password) keyboard.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else keyboard,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.semantics { contentDescription = label },
        decorationBox = { inner ->
            Row(
                Modifier.heightIn(min = minHeight).clip(shape).background(c.bg).border(1.dp, c.border, shape).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f, fill = false).widthIn(min = 60.dp)) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = style, color = c.muted)
                    inner()
                }
                if (suffix != null) Text(suffix, style = TitanType.rowSub, color = c.muted)
            }
        },
    )
}

/** Zahl mit Einheit; leer oder ungültig bleibt der Text stehen, [onValue] bekommt nur gültige Zahlen. */
@Composable
fun NumberField(value: Number?, onValue: (Long?) -> Unit, label: String, modifier: Modifier = Modifier, suffix: String? = null, enabled: Boolean = true, allowEmpty: Boolean = false) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    Field(
        text,
        { t -> text = t.filter { it.isDigit() || it == '-' }; val n = text.toLongOrNull(); if (n != null || (allowEmpty && text.isEmpty())) onValue(n) },
        label,
        modifier.widthIn(min = 90.dp, max = 220.dp),
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
        enabled = enabled,
        singleLine = true,
        suffix = suffix,
    )
}

/** Regler mit Wert in Worten daneben (Slider.svelte). */
@Composable
fun SliderField(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, label: String, valueText: String, modifier: Modifier = Modifier, steps: Int = 0, onDone: (() -> Unit)? = null, enabled: Boolean = true) {
    val c = Titan.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.track),
            modifier = Modifier.weight(1f).widthIn(min = 120.dp).semantics { contentDescription = label; stateDescription = valueText },
        )
        Text(valueText, style = TitanType.rowSub, color = c.text, modifier = Modifier.widthIn(min = 44.dp))
    }
}

/** Ankreuzfeld mit Text (Häkchen, die eine Folge benennen, sind erlaubt). */
@Composable
fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String, modifier: Modifier = Modifier, desc: String? = null, enabled: Boolean = true) {
    val c = Titan.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "☑" else "☐" }
            .heightIn(min = 44.dp).padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(5.dp)
        Box(
            Modifier.size(22.dp).clip(shape).background(if (checked) c.accent else Color.Transparent).border(2.dp, if (checked) c.accent else c.muted, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(TitanIcons.check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text, style = TitanType.body, color = if (enabled) c.text else c.muted)
            if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted)
        }
    }
}

/** Auswahl als Liste von Kreisen mit Text (Radiogruppe). */
@Composable
fun <T> RadioList(value: T, options: List<Triple<T, String, String?>>, onChange: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Titan.colors
    Column(modifier.fillMaxWidth()) {
        for ((v, text, desc) in options) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.RadioButton) { onChange(v) }
                    .semantics(mergeDescendants = true) { }
                    .heightIn(min = 44.dp).padding(vertical = 8.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioMark(v == value)
                Column(Modifier.weight(1f)) {
                    Text(text, style = TitanType.body, color = if (enabled) c.text else c.muted)
                    if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted)
                }
            }
        }
    }
}

/** Beschriftung und Wert (z. B. „Version · 1.19“); Werte wie Pfade in Mono. */
@Composable
fun ValueLine(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    val c = Titan.colors
    FlowRow(modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = TitanType.rowSub, color = c.muted)
        Text(value, style = if (mono) TitanType.rowSub.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono) else TitanType.rowSub, color = c.text)
    }
}

/** Gefahrenzone (DangerZone.svelte): am Ende der Ansicht, Satz und rote Knöpfe. */
@Composable
fun DangerZone(title: String, text: String, modifier: Modifier = Modifier, buttons: @Composable RowScope.() -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().padding(top = 18.dp).clip(shape).background(c.surface).border(1.dp, c.dangerInk.copy(alpha = .45f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = c.dangerInk, modifier = Modifier.semantics { heading() })
        Text(text, style = TitanType.rowSub, color = c.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
        }
    }
}

/** Seitentext oben (Einleitung) in der gedämpften Farbe. */
@Composable
fun PageIntro(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.body, color = Titan.colors.muted, modifier = modifier.padding(top = 4.dp, bottom = 6.dp))
}

/** Lädt noch: eine Zeile mit Text (nie nur ein Kreisel). */
@Composable
fun LoadingLine(text: String, modifier: Modifier = Modifier) {
    val c = Titan.colors
    Row(modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
        Text(text, style = TitanType.body, color = c.muted)
    }
}
