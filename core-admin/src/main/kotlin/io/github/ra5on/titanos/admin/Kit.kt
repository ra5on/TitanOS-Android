package io.github.ra5on.titanos.admin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- Gruppen und Zeilen

/**
 * Ob Bedienelemente einer Zeile unter den Text rücken: bei großer Schrift
 * (ab 150 %), damit Titel und Beschreibung nur zwischen ganzen Wörtern umbrechen.
 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun stacked(): Boolean = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f

/**
 * Gruppe von Einstellungen (SettingGroup im Web, Windows-11-Stil): Überschrift
 * und Karte mit Zeilen. [expandable]: Kopf klappt die Karte auf und zu.
 */
@Composable
fun SettingGroup(
    title: String?,
    modifier: Modifier = Modifier,
    desc: String? = null,
    expandable: Boolean = false,
    initiallyOpen: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Titan.colors
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen || !expandable) }
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            val expandLabel = stringResource(if (open) R.string.admin_collapse else R.string.admin_expand)
            Row(
                Modifier.fillMaxWidth()
                    .then(if (expandable) Modifier.clickable(role = Role.Button, onClickLabel = expandLabel) { open = !open } else Modifier)
                    .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 8.dp)
                    .semantics { heading(); if (expandable) stateDescription = expandLabel },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = TitanType.section, color = c.muted)
                    if (desc != null) Text(desc, style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                }
                if (expandable) WebIcon(if (open) "chevronDown" else "chevronRight", size = 18.dp)
            }
        }
        if (open) Card(content = content)
    }
}

/**
 * Zeile einer Gruppe (SettingRow): Symbol, Titel, Beschreibung, rechts
 * [value] oder [control]; mit [onClick] tippbar (Pfeil mit [chevron]).
 * [onMenu] öffnet das Menü der Zeile (langes Drücken, Rechtsklick).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    icon: String? = null,
    value: String? = null,
    divider: Boolean = false,
    danger: Boolean = false,
    chevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
) {
    val c = Titan.colors
    Column(modifier.fillMaxWidth()) {
        if (divider) Divider()
        val click = when {
            onClick != null || onMenu != null -> Modifier.combinedClickable(enabled = enabled, onClick = { onClick?.invoke() ?: onMenu?.invoke() }, onLongClick = onMenu)
            else -> Modifier
        }
        Row(
            Modifier.fillMaxWidth().then(click).then(if (onMenu != null) Modifier.onSecondaryClick(onMenu) else Modifier)
                .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) WebIcon(icon, size = 22.dp, tint = if (danger) c.dangerInk else c.muted)
            Column(Modifier.weight(1f)) {
                Text(title, style = TitanType.rowTitle, color = if (danger) c.dangerInk else if (enabled) c.text else c.muted)
                if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                if (value != null && control == null) Text(value, style = TitanType.rowSub, color = c.text, modifier = Modifier.padding(top = 2.dp))
                below?.invoke(this)
                // Große Schrift: das Bedienelement unter den Text, damit Wörter ganz bleiben.
                if (control != null && stacked()) Box(Modifier.padding(top = 8.dp)) { control() }
            }
            if (control != null && !stacked()) control()
            if (chevron) Icon(TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** Zeile mit Schalter; TalkBack hört Titel und Zustand („Ein“/„Aus“). Die ganze Zeile schaltet. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    desc: String? = null,
    icon: String? = null,
    enabled: Boolean = true,
    divider: Boolean = false,
    /** Name für TalkBack, wenn der Titel allein nicht reicht (Web: aria-label, z. B. „Live für Einfahrt“). */
    label: String? = null,
) {
    val on = stringResource(R.string.admin_on)
    val off = stringResource(R.string.admin_off)
    Column(modifier.fillMaxWidth()) {
        if (divider) Divider()
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .semantics(mergeDescendants = label != null) {
                    stateDescription = if (checked) on else off
                    if (label != null) contentDescription = label
                }
                .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val c = Titan.colors
            val stack = stacked()
            if (icon != null && !stack) WebIcon(icon, size = 22.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = TitanType.rowTitle, color = if (enabled) c.text else c.muted)
                if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                // Große Schrift: der Schalter unter den Text (ganze Wörter statt Umbruch im Wort).
                if (stack) Box(Modifier.padding(top = 8.dp)) { Toggle(checked, null, on, off, enabled = enabled, label = title) }
            }
            // Der Schalter selbst ist nur Anzeige; die Zeile trägt die Bedienung.
            if (!stack) Toggle(checked, null, on, off, enabled = enabled, label = title)
        }
    }
}

/** Ankreuzfeld mit Text (Role.Checkbox), die ganze Zeile schaltet. */
@Composable
fun CheckRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    desc: String? = null,
    enabled: Boolean = true,
    divider: Boolean = false,
) {
    val c = Titan.colors
    Column(modifier.fillMaxWidth()) {
        if (divider) Divider()
        Row(
            Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
                .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier.size(22.dp).clip(shape).background(if (checked) c.accent else c.surface).border(1.5.dp, if (checked) c.accent else c.muted, shape),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Icon(TitanIcons.check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = TitanType.body, color = if (enabled) c.text else c.muted)
                if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Auswahlzeile (Role.RadioButton) mit Text und Beschreibung. */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    desc: String? = null,
    enabled: Boolean = true,
    divider: Boolean = false,
) {
    val c = Titan.colors
    Column(modifier.fillMaxWidth()) {
        if (divider) Divider()
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RadioMark(selected)
            Column(Modifier.weight(1f)) {
                Text(title, style = TitanType.body, color = if (enabled) c.text else c.muted)
                if (desc != null) Text(desc, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Eine Wahl unter wenigen (Segments im Web) als Knopfreihe; bricht um, statt zu kürzen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Segments(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    val c = Titan.colors
    Column(modifier.fillMaxWidth()) {
        if (label != null) FieldLabel(label)
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((value, text) in options) {
                val on = value == selected
                val shape = RoundedCornerShape(10.dp)
                Text(
                    text,
                    style = TitanType.body,
                    color = if (on) c.onAccent else if (enabled) c.text else c.muted,
                    modifier = Modifier.clip(shape).background(if (on) c.accent else c.chip).border(1.dp, if (on) c.accent else c.border, shape)
                        .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                        .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 11.dp),
                )
            }
        }
    }
}

/**
 * Auswahlliste (Dropdown im Web): Zeile mit Titel und gewähltem Wert,
 * Tippen öffnet ein Blatt mit allen Möglichkeiten.
 */
@Composable
fun ChoiceRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    desc: String? = null,
    icon: String? = null,
    enabled: Boolean = true,
    divider: Boolean = false,
    optionDesc: (String) -> String? = { null },
) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second ?: selected
    SettingRow(
        title,
        modifier = modifier,
        desc = desc,
        icon = icon,
        divider = divider,
        enabled = enabled,
        onClick = if (enabled) ({ open = true }) else null,
        chevron = enabled,
        below = { Text(shown, style = TitanType.rowSub, color = Titan.colors.accent, modifier = Modifier.padding(top = 2.dp)) },
    )
    if (open) {
        AdminSheet(title, onDismiss = { open = false }) {
            Card {
                Column(Modifier.selectableGroup()) {
                    options.forEachIndexed { i, (value, text) ->
                        RadioRow(text, value == selected, { open = false; onSelect(value) }, desc = optionDesc(value), divider = i > 0)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Eingaben

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Titan.colors.text, modifier = modifier.padding(start = 2.dp, bottom = 6.dp))
}

/**
 * Eingabefeld mit sichtbarer Beschriftung darüber (auch Name für TalkBack),
 * Hinweis und Fehler darunter. [password]: verdeckt, mit Knopf zum Zeigen;
 * der Wert landet nie in einem Protokoll.
 */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    placeholder: String? = null,
    password: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minLines: Int = 1,
    suffix: String? = null,
) {
    val c = Titan.colors
    var show by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val style: TextStyle = (if (mono) TitanType.body.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono) else TitanType.body).copy(fontSize = TitanType.rowTitle.fontSize, color = if (enabled) c.text else c.muted)
    Column(modifier.fillMaxWidth()) {
        FieldLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = style,
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (password) keyboard.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else keyboard,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.surface)
                        .border(if (error != null) 1.5.dp else 1.dp, if (error != null) c.dangerInk else c.border, shape)
                        .padding(start = 14.dp, end = if (password) 2.dp else 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder, style = style.copy(color = c.muted))
                        inner()
                    }
                    if (suffix != null) Text(suffix, style = TitanType.body, color = c.muted, modifier = Modifier.padding(start = 8.dp))
                    if (password) {
                        io.github.ra5on.titanos.ui.components.IconButton(
                            if (show) AdminIcons.of("eye") else TitanIcons.lock,
                            stringResource(if (show) R.string.admin_password_hide else R.string.admin_password_show),
                            { show = !show },
                            size = 20.dp,
                            tint = c.muted,
                        )
                    }
                }
            },
        )
        if (error != null) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Icon(TitanIcons.warn, null, tint = c.dangerInk, modifier = Modifier.size(16.dp))
                Text(error, style = TitanType.small, color = c.dangerInk)
            }
        } else if (hint != null) {
            Text(hint, style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
    }
}

/** Zahl (ganze), leer = null; außerhalb von [min]..[max] als Fehler markiert. */
@Composable
fun NumberField(
    label: String,
    value: Long?,
    onChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    min: Long? = null,
    max: Long? = null,
    hint: String? = null,
    suffix: String? = null,
    enabled: Boolean = true,
    error: String? = null,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    val out = text.toLongOrNull()?.let { (min != null && it < min) || (max != null && it > max) } == true
    Field(
        label,
        text,
        { s ->
            val clean = s.filter { it.isDigit() || it == '-' }
            text = clean
            onChange(clean.toLongOrNull())
        },
        modifier = modifier,
        hint = hint ?: if (min != null && max != null) "$min–$max" else null,
        error = error ?: if (out && min != null && max != null) "$min–$max" else null,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
        suffix = suffix,
        enabled = enabled,
    )
}

// ---------------------------------------------------------------- Zustand, Hinweise

enum class Tone { Ok, Warn, Error, Info, Neutral }

/** Zustand als Text mit Symbol, nie nur über Farbe. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val (fg, bg, icon) = when (tone) {
        Tone.Ok -> Triple(c.okInk, c.okBg, "check")
        Tone.Warn -> Triple(c.warnInk, c.warnBg, "")
        Tone.Error -> Triple(c.dangerInk, c.errorBg, "")
        Tone.Info -> Triple(c.accent, c.chip, "info")
        Tone.Neutral -> Triple(c.text, c.chip, "")
    }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.clip(shape).background(bg).border(1.dp, fg.copy(alpha = .35f), shape).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon.isNotEmpty() && AdminIcons.exists(icon)) WebIcon(icon, size = 14.dp, tint = fg)
        else if (tone == Tone.Warn || tone == Tone.Error) Icon(TitanIcons.warn, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, style = TitanType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = fg)
    }
}

/** Hinweis im Stil des Webs (.notice): Info, Warnung oder Fehler, mit Titel und Knöpfen. */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Info,
    title: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val c = Titan.colors
    val (fg, bg, border) = when (tone) {
        Tone.Warn -> Triple(c.warnText, c.warnBg, c.warnBorder)
        Tone.Error -> Triple(c.errorText, c.errorBg, c.errorBorder)
        Tone.Ok -> Triple(c.okText, c.okBg, c.okBorder)
        else -> Triple(c.text, c.surface, c.border)
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, border, shape).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (tone == Tone.Info || tone == Tone.Neutral) TitanIcons.info else if (tone == Tone.Ok) TitanIcons.check else TitanIcons.warn, null, tint = if (tone == Tone.Info) c.accent else fg, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = TitanType.rowTitle, color = fg)
            Text(text, style = TitanType.body, color = fg)
            actions?.invoke()
        }
    }
}

/** Belegung mit Balken und Text („400 GB / 1 TB · 40 %“). */
@Composable
fun Usage(used: Long, total: Long, modifier: Modifier = Modifier) {
    val fraction = if (total > 0) (used.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    val c = Titan.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${formatBytesOf(used, total)} · ${formatPercent(fraction * 100)}", style = TitanType.small, color = c.text)
        Bar(fraction.toFloat(), color = if (fraction >= .9) c.dangerInk else if (fraction >= .8) c.warnInk else c.accent)
    }
}

/** Fortschritt eines Auftrags beim NAS: Phase, Balken (wenn bekannt) und Prozent. */
@Composable
fun JobProgress(
    title: String,
    modifier: Modifier = Modifier,
    phase: String? = null,
    percent: Double? = null,
    error: String? = null,
    detail: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val c = Titan.colors
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (error == null && (percent == null || percent < 100)) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
                Text(title, style = TitanType.rowTitle, color = c.text, modifier = Modifier.weight(1f))
                if (percent != null) Text(formatPercent(percent), style = TitanType.body, color = c.text)
            }
            if (phase != null) Text(phase, style = TitanType.rowSub, color = c.muted)
            if (percent != null) Bar((percent / 100).toFloat().coerceIn(0f, 1f))
            if (detail != null) Text(detail, style = TitanType.small, color = c.muted)
            if (error != null) StatusChip(error, Tone.Error)
            actions?.invoke()
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, text: String = t("common.loading")) {
    val c = Titan.colors
    Row(modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
        Text(text, style = TitanType.body, color = c.muted)
    }
}

/** Fehler einer Anfrage mit „Erneut versuchen“. */
@Composable
fun ErrorBox(error: Throwable, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Notice(
        errorText(error, detail = true),
        modifier,
        tone = Tone.Error,
        actions = onRetry?.let { { TitanButton(t("common.retry"), it) } },
    )
}

/** Knopf mit Text des Webs; [danger] rot, [primary] gefüllt. */
@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, icon: String? = null) {
    TitanButton(
        text,
        onClick,
        modifier,
        kind = if (primary) ButtonKind.Primary else if (danger) ButtonKind.Danger else ButtonKind.Normal,
        icon = icon?.let { AdminIcons.of(it) },
        enabled = enabled,
    )
}

/** Knöpfe nebeneinander, die umbrechen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Wert mit Beschriftung darüber (schmale Kacheln: Beschriftung über dem Wert). */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    val c = Titan.colors
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = TitanType.small, color = c.muted)
        Text(value, style = if (mono) TitanType.body.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono) else TitanType.body, color = c.text)
    }
}

/** Kleiner runder Punkt mit Text (Zustand in Listen), Farbe nur zusätzlich. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

// ---------------------------------------------------------------- Menüs

data class MenuItem(val label: String, val icon: String? = null, val danger: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/** Menü einer Zeile oder eines „⋯“-Knopfs; dieselben Einträge bei langem Drücken und Rechtsklick. */
@Composable
fun AdminMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItem>) {
    val c = Titan.colors
    DropdownMenu(expanded, onDismiss, containerColor = c.surface) {
        for (it in items) {
            DropdownMenuItem(
                text = { Text(it.label, style = TitanType.body, color = if (it.danger) c.dangerInk else if (it.enabled) c.text else c.muted) },
                leadingIcon = it.icon?.let { name -> { WebIcon(name, tint = if (it.danger) c.dangerInk else c.muted) } },
                enabled = it.enabled,
                onClick = { onDismiss(); it.onClick() },
            )
        }
    }
}

/** „⋯“-Knopf mit Menü. */
@Composable
fun MoreButton(items: List<MenuItem>, label: String = t("ui.more")) {
    var open by remember { mutableStateOf(false) }
    Box {
        io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.more, label, { open = true })
        AdminMenu(open, { open = false }, items)
    }
}

// ---------------------------------------------------------------- Abfragen

/**
 * Fragt [load] alle [intervalMs] ab, solange die Seite sichtbar ist
 * (Android pausiert sie im Hintergrund). Erstes Laden sofort.
 */
@Composable
fun Polling(key: Any?, intervalMs: Long, load: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(load)
    LaunchedEffect(key, intervalMs) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                runCatching { current() }
                if (intervalMs <= 0) break
                delay(intervalMs)
            }
        }
    }
}
