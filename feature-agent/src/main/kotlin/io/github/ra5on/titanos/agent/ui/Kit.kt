package io.github.ra5on.titanos.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.core.LocalCalm
import io.github.ra5on.titanos.agent.core.Motion
import io.github.ra5on.titanos.agent.core.WebIcons
import io.github.ra5on.titanos.agent.core.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk

// Die Bausteine der Agent-Seiten in der Form des Webs am Handy (app.css .srow,
// .sgroup, Segments, .btn, .field; agent/+page.svelte: runde Zeilen und
// Karten, Knöpfe als Kapseln, ruhige graue Überschriften). 1 CSS-px = 1 dp,
// 1 rem = 16 sp.

/** Das warme Orange des Agenten (--ag-warm) für Senden, Figur und Haken. */
val AgentWarm = Color(0xFFE07A4C)

/** Ein Anteil der Textfarbe über dem Hintergrund (color-mix im Web). */
@Composable
fun textMix(alpha: Float, over: Color = Titan.colors.bg): Color = Titan.colors.text.copy(alpha = alpha).compositeOver(over)

object AgentColors {
    val srowBg: Color @Composable get() = textMix(0.045f)
    val srowLine: Color @Composable get() = Titan.colors.text.copy(alpha = 0.09f).compositeOver(Titan.colors.bg)
    /** color-mix(#5b8fe0 78 %, Text) für Links und „andere“. */
    val link: Color @Composable get() = Color(0xFF5B8FE0).copy(alpha = 0.78f).compositeOver(Titan.colors.text)
    /** Die Knöpfe oben: Text 7 % über der Fläche. */
    val float: Color @Composable get() = Titan.colors.text.copy(alpha = 0.07f).compositeOver(Titan.colors.surface)
    val overlay: Color get() = Color.Black.copy(alpha = 0.5f)
}

object AgentText {
    /** Ohne Silbentrennung: nur ganze Wörter (Inhaber 21.11.2026); lange Wörter über breakWords (core-ui). */
    val base = TitanType.body
    /** --text-md (14 px). */
    val md = base.copy(fontSize = 14.sp, lineHeight = 19.sp)
    val sm = base.copy(fontSize = 12.sp, lineHeight = 16.8.sp)
    val baseSize = base.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val lg = base.copy(fontSize = 16.sp, lineHeight = 22.sp)
    val chat = base.copy(fontSize = 16.sp, lineHeight = 25.sp)
    val pageTitle = base.copy(fontSize = 21.6.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun WIcon(name: String, size: Dp = 18.dp, tint: Color = Titan.colors.text, label: String? = null, modifier: Modifier = Modifier) {
    Icon(WebIcons[name], label, tint = tint, modifier = modifier.size(size))
}

/** Runder, schwebender Knopf (44 dp, Rand, Fläche mit 7 % Text). */
@Composable
fun FloatButton(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, dot: Boolean = false, size: Dp = 44.dp, iconSize: Dp = 22.dp) {
    val c = Titan.colors
    Box(
        modifier.size(size).clip(CircleShape).background(AgentColors.float).border(1.dp, c.border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        WIcon(icon, iconSize, c.text)
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(8.dp).clip(CircleShape).background(Color(0xFF5B9DF0)))
    }
}

/** Kopf einer Seite am Handy: runder Knopf zurück und der Name der Seite. */
@Composable
fun PageHead(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, tools: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FloatButton("chevronLeft", t("agent.set.back"), onBack)
        Text(title, style = AgentText.lg.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.text, modifier = Modifier.weight(1f).semantics { heading() })
        tools()
    }
}

/** Ein Satz unter dem Kopf einer Seite (.lead). */
@Composable
fun Lead(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AgentText.md.copy(lineHeight = 21.sp), color = Titan.colors.muted, modifier = modifier)
}

/** Eine Gruppe: graue Überschrift, rechts ihre Knöpfe, darunter die Zeilen mit 3 dp Abstand. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    desc: String? = null,
    tools: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (title != null || tools != null) {
            Column(Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, style = AgentText.md.copy(fontWeight = FontWeight.Medium), color = Titan.colors.muted, modifier = Modifier.weight(1f).semantics { heading() })
                    else Spacer(Modifier.weight(1f))
                    if (tools != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = tools)
                }
                if (desc != null) Text(desc, style = AgentText.baseSize, color = Titan.colors.muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        content()
    }
}

val SRowShape = RoundedCornerShape(16.dp)

/** Die Fläche einer Zeile oder Karte (.srow). */
fun Modifier.srow(bg: Color, line: Color, shape: androidx.compose.ui.graphics.Shape = SRowShape): Modifier =
    this.fillMaxWidth().clip(shape).background(bg).border(1.dp, line, shape)

@Composable
fun Modifier.srowSurface(): Modifier = srow(AgentColors.srowBg, AgentColors.srowLine)

/**
 * Eine Einstellung als flache Zeile (SettingRow.svelte): Symbol, Name, kurze
 * Erklärung, rechts Wert/Schalter/Wahl; mit [onClick] führt sie weiter
 * (Pfeil), mit [children] klappt sie auf. Am Handy steht das Bedienelement
 * unter dem Text, eingerückt unter dem Namen.
 */
@Composable
fun SRow(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    icon: String? = null,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    initiallyOpen: Boolean = false,
    iconTint: Color? = null,
    chevron: Boolean = true,
    badge: (@Composable () -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
    children: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Titan.colors
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val calm = LocalCalm.current
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.spec(calm, 150), label = "chev")
    val line = AgentColors.srowLine
    Column(modifier.srowSurface().then(if (calm) Modifier else Modifier.animateContentSize(Motion.spec(false, 240)))) {
        val headClick: (() -> Unit)? = onClick ?: if (children != null) ({ open = !open }) else null
        Column(
            Modifier.fillMaxWidth()
                .then(if (headClick != null) Modifier.clickable(role = Role.Button, onClick = headClick) else Modifier)
                .then(if (children != null) Modifier.semantics { stateDescription = if (open) "open" else "closed" } else Modifier)
                .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (icon != null) Box(Modifier.width(24.dp).heightIn(min = 22.dp), contentAlignment = Alignment.Center) { WIcon(icon, 19.dp, iconTint ?: c.muted) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(io.github.ra5on.titanos.ui.breakAtHyphens(title), style = AgentText.md.copy(fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f, fill = false))
                        badge?.invoke()
                    }
                    if (!desc.isNullOrEmpty()) Text(desc, style = AgentText.sm, color = c.muted)
                }
                if (onClick != null && chevron) WIcon("chevronRight", 16.dp, c.muted, modifier = Modifier.padding(top = 3.dp))
                else if (children != null) WIcon("chevronDown", 16.dp, c.muted, modifier = Modifier.padding(top = 3.dp).rotate(turn))
            }
            if (summary != null || control != null) {
                Column(
                    Modifier.fillMaxWidth().padding(start = if (icon != null) 40.dp else 0.dp, top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (summary != null) Text(summary, style = AgentText.baseSize, color = c.muted)
                    if (control != null) androidx.compose.runtime.CompositionLocalProvider(LocalControlLabel provides title) { control() }
                }
            }
        }
        if (children != null) {
            AnimatedVisibility(open, enter = if (calm) fadeIn(Motion.spec(true, 0)) else expandVertically(Motion.spec(false, 240)) + fadeIn(), exit = if (calm) fadeOut(Motion.spec(true, 0)) else shrinkVertically(Motion.spec(false, 200)) + fadeOut()) {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(line))
                    Column(Modifier.fillMaxWidth().padding(start = if (icon != null) 56.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        children()
                    }
                }
            }
        }
    }
}

/** Ein Teil einer offenen Einstellung (SettingSub): Name und Erklärung, darunter das Bedienelement. */
@Composable
fun SSub(title: String, desc: String? = null, modifier: Modifier = Modifier, control: (@Composable () -> Unit)? = null) {
    val c = Titan.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = AgentText.md.copy(fontWeight = FontWeight.Medium), color = c.text)
        if (!desc.isNullOrEmpty()) Text(desc, style = AgentText.sm, color = c.muted)
        control?.invoke()
    }
}

/** Wahl unter wenigen Möglichkeiten (Segments). [compact]: die graue Form der Kopfzeilen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Segments(
    options: List<Pair<T, String>>,
    value: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fill: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    val outer = if (compact) Modifier.clip(shape).background(c.chip) else Modifier.clip(shape).border(1.dp, c.border, shape)
    val item: @Composable (Modifier, T, String) -> Unit = { m, key, label ->
        val on = key == value
        val inner = RoundedCornerShape(if (compact) 10.dp else 12.dp)
        Box(
            m.heightIn(min = 44.dp).clip(inner)
                .background(if (on) (if (compact) c.surface else c.accent) else Color.Transparent)
                .clickable(enabled = enabled, role = Role.RadioButton) { onChange(key) }
                .semantics { selected = on }
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 6.dp)
                .alpha(if (enabled) 1f else 0.6f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = (if (compact) AgentText.baseSize else AgentText.baseSize).copy(fontWeight = if (on && compact) FontWeight.SemiBold else FontWeight.Normal),
                color = if (on && !compact) c.onAccent else c.text,
                textAlign = TextAlign.Center,
            )
        }
    }
    // Gleich breite Felder nur, wenn das längste Wort jeder Beschriftung in ein Feld passt (ganze Wörter);
    // sonst umbrechen statt teilen (große Schrift, schmales Fenster, lange Wörter wie „Anweisungen“).
    val widest = io.github.ra5on.titanos.ui.rememberWidestWord(options.map { it.second }, AgentText.baseSize.copy(fontWeight = FontWeight.SemiBold))
    val density = androidx.compose.ui.platform.LocalDensity.current
    var width by remember { mutableStateOf(0) }
    val gap = if (compact) 2.dp else 4.dp
    val pad = if (compact) 10.dp else 14.dp
    val cell = with(density) { (width - (6.dp + gap * (options.size - 1)).toPx()) / options.size.coerceAtLeast(1) - (pad * 2).toPx() }
    if (fill && (width == 0 || widest <= cell)) {
        Row(modifier.fillMaxWidth().onSizeChanged { width = it.width }.then(outer).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEach { (k, l) -> item(Modifier.weight(1f), k, l) }
        }
    } else if (fill) {
        Box(modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
            FlowRow(Modifier.then(outer).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (k, l) -> item(Modifier, k, l) }
            }
        }
    } else {
        FlowRow(modifier.then(outer).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (k, l) -> item(Modifier, k, l) }
        }
    }
}

enum class Btn { Normal, Primary, Danger, Warn, Ghost }

/** Ein Knopf als Kapsel (.btn in der Form des Agenten). */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: Btn = Btn.Normal,
    icon: String? = null,
    enabled: Boolean = true,
    small: Boolean = false,
) {
    val c = Titan.colors
    val shape = RoundedCornerShape(999.dp)
    val bg = when (kind) {
        Btn.Primary -> c.accent
        Btn.Danger -> c.dangerInk.copy(alpha = 0.14f)
        Btn.Warn -> c.statusWarn
        else -> Color.Transparent
    }
    val fg = when (kind) {
        Btn.Primary -> c.onAccent
        Btn.Danger -> c.dangerInk
        Btn.Warn -> Color(0xFF1A1300)
        else -> c.text
    }
    Row(
        modifier.heightIn(min = if (small) 36.dp else 44.dp).clip(shape).background(bg)
            .then(if (kind == Btn.Normal || kind == Btn.Danger) Modifier.border(1.dp, if (kind == Btn.Danger) c.dangerInk.copy(alpha = 0.4f) else c.border, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) WIcon(icon, 16.dp, fg)
        Text(io.github.ra5on.titanos.ui.breakAtHyphens(label), style = AgentText.md.copy(fontWeight = if (kind == Btn.Primary || kind == Btn.Warn) FontWeight.SemiBold else FontWeight.Normal), color = fg, textAlign = TextAlign.Center)
    }
}

/** Ein quadratischer Knopf mit Symbol (Anheften, Bearbeiten, Löschen am Merksatz). */
@Composable
fun IconTile(icon: String, label: String, onClick: () -> Unit, on: Boolean = false, enabled: Boolean = true, danger: Boolean = false) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(44.dp).clip(shape)
            .background(if (on) c.accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (on) c.accent.copy(alpha = 0.55f) else c.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; if (on) selected = true }
            .alpha(if (enabled) 1f else 0.55f),
        contentAlignment = Alignment.Center,
    ) { WIcon(icon, 16.dp, if (danger) c.dangerInk else if (on) c.accent else c.text) }
}

/** Ein Eingabefeld (.field in der Form des Agenten). */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String = placeholder,
    multiline: Boolean = false,
    minHeight: Dp = 44.dp,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    enabled: Boolean = true,
    leading: String? = null,
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
    style: TextStyle = AgentText.md.copy(fontSize = 15.sp, lineHeight = 21.sp),
) {
    val c = Titan.colors
    val shape = RoundedCornerShape(if (multiline) 14.dp else 12.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        textStyle = style.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        singleLine = !multiline,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboard == KeyboardType.Text && !password) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            keyboardType = keyboard,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onSearch = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight).semantics { contentDescription = label },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(c.bg).border(1.dp, c.border, shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp).alpha(if (enabled) 1f else 0.6f),
                verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (leading != null) WIcon(leading, 18.dp, c.muted)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style, color = c.muted)
                    inner()
                }
            }
        },
    )
}

/** Ein Schalter mit Wort (Status nie nur über Farbe). */
@Composable
fun Switch(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true, label: String? = null) {
    io.github.ra5on.titanos.ui.components.Toggle(checked, onChange, stateOn = t("agent.on"), stateOff = t("agent.off"), enabled = enabled, label = label ?: LocalControlLabel.current)
}

/** Name der Zeile für ihr Bedienelement (Schalter): TalkBack sagt „Vibration, Ein, Schalter“. */
val LocalControlLabel = androidx.compose.runtime.compositionLocalOf<String?> { null }

/** Ein kleines Schild (.chip/.tag) mit Wort; [tone]: ok, warn, info, error. */
@Composable
fun Tag(text: String, tone: String = "", modifier: Modifier = Modifier) {
    val c = Titan.colors
    val color = when (tone) {
        "ok" -> c.okInk
        "warn" -> c.warnInk
        "error" -> c.dangerInk
        "info" -> AgentColors.link
        else -> c.muted
    }
    val shape = RoundedCornerShape(999.dp)
    Box(modifier.clip(shape).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.45f), shape).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = AgentText.sm.copy(fontWeight = FontWeight.SemiBold), color = if (tone.isEmpty()) c.text else color)
    }
}

/** Ein Hinweis in einer Box (Note/Problem in der Form des Agenten). */
@Composable
fun Notice(text: String, tone: String = "", modifier: Modifier = Modifier, icon: String = if (tone == "error") "alert" else "info", action: (@Composable () -> Unit)? = null) {
    val c = Titan.colors
    val (bg, line, fg) = when (tone) {
        "error" -> Triple(c.errorBg, c.errorBorder, c.errorText)
        "warn" -> Triple(c.warnBg, c.warnBorder, c.warnText)
        "ok" -> Triple(c.okBg, c.okBorder, c.okText)
        else -> Triple(AgentColors.srowBg, AgentColors.srowLine, c.text)
    }
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, line, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WIcon(if (WebIcons.has(icon)) icon else "info", 18.dp, fg)
            Text(text, style = AgentText.md, color = fg, modifier = Modifier.weight(1f))
        }
        action?.invoke()
    }
}

/** Gleichmäßiger Rand einer Seite am Handy (.phone .page). */
val PagePadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 24.dp)

/** Eine Zeile Knöpfe, die umbricht. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Buttons(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** Ein Auswahlpunkt mit Haken (Radio) in Listen von Möglichkeiten. */
@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit, desc: String? = null, icon: String? = null, enabled: Boolean = true) {
    val c = Titan.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }.heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 6.dp).alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) c.accent else c.muted, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(c.accent))
        }
        if (icon != null) WIcon(icon, 18.dp, c.muted)
        Column(Modifier.weight(1f)) {
            Text(label, style = AgentText.md, color = c.text)
            if (desc != null) Text(desc, style = AgentText.sm, color = c.muted)
        }
    }
}

/** Ein Kästchen zum Ankreuzen mit Wort. */
@Composable
fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit, desc: String? = null, enabled: Boolean = true) {
    val c = Titan.colors
    val shape = RoundedCornerShape(5.dp)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }
            .semantics { role = Role.Checkbox; stateDescription = if (checked) "checked" else "unchecked" }
            .heightIn(min = 44.dp).padding(vertical = 6.dp).alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).clip(shape).background(if (checked) c.accent else Color.Transparent).border(2.dp, if (checked) c.accent else c.muted, shape), contentAlignment = Alignment.Center) {
            if (checked) WIcon("check", 14.dp, c.onAccent)
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = AgentText.md, color = c.text)
            if (desc != null) Text(desc, style = AgentText.sm, color = c.muted)
        }
    }
}

/** Eine einfache Auswahl als aufklappende Liste (select.field). */
@Composable
fun Select(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Titan.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape).background(c.bg).border(1.dp, c.border, shape)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = !open }
                .semantics { contentDescription = label }
                .padding(horizontal = 12.dp, vertical = 8.dp).alpha(if (enabled) 1f else 0.6f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == value }?.second ?: value, style = AgentText.md, color = c.text, modifier = Modifier.weight(1f))
            WIcon("chevronDown", 16.dp, c.muted)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(4.dp)) {
                for ((k, l) in options) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { open = false; onChange(k) }
                            .semantics { selected = k == value }.heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(l, style = AgentText.md, color = c.text, modifier = Modifier.weight(1f))
                        if (k == value) WIcon("check", 16.dp, c.accent)
                    }
                }
            }
        }
    }
}

/** Abstand zwischen Gruppen einer Seite. */
@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))

/** Fester Rahmen in der Breite der Seite (max. 760 dp, mittig auf Tablets). */
fun Modifier.pageWidth(): Modifier = this.widthIn(max = 760.dp)
