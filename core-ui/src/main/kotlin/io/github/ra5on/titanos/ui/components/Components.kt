package io.github.ra5on.titanos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.ra5on.titanos.ui.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.generated.ProgramGlyphs
import io.github.ra5on.titanos.ui.generated.ProgramGradients
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlin.math.cos
import kotlin.math.sin
import io.github.ra5on.titanos.ui.theme.dangerInk

// Bausteine nach Entwuerfe/116-android/lib.mjs (.a-top, .a-card, .a-row, …).
// Texte brechen immer um; nie eine Zeilenbegrenzung (TitanOS: kein abgeschnittener Text).

/** Programmsymbol wie am Arbeitsplatz: Verlauf 160°, Ecken 23 %, Glyphe 58 %. */
@Composable
fun AppIcon(glyph: String, gradient: String, size: Dp, modifier: Modifier = Modifier) {
    val (a, c) = ProgramGradients.of(gradient)
    val shape = RoundedCornerShape(percent = 23)
    Box(
        modifier
            .size(size)
            .shadow(size * 0.11f, shape, ambientColor = Color.Black.copy(alpha = .28f), spotColor = Color.Black.copy(alpha = .28f))
            .clip(shape)
            .drawBehind {
                // linear-gradient(160deg, a, c): Richtung wie in CSS (0° = nach oben, im Uhrzeigersinn).
                val rad = Math.toRadians(160.0)
                val dx = sin(rad).toFloat()
                val dy = -cos(rad).toFloat()
                val half = (kotlin.math.abs(this.size.width * dx) + kotlin.math.abs(this.size.height * dy)) / 2
                val center = Offset(this.size.width / 2, this.size.height / 2)
                drawRect(Brush.linearGradient(listOf(a, c), center - Offset(dx, dy) * half, center + Offset(dx, dy) * half))
                // inset 0 1px 0 rgb(255 255 255 / .35)
                drawRect(Color.White.copy(alpha = .35f), size = this.size.copy(height = 1.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        ProgramGlyphs.vector(glyph, c)?.let {
            Icon(it, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(size * 0.58f))
        }
    }
}

/** Kopfleiste (.a-top): Zurück, Titel mit Symbol, Aktionen. Der Titel bricht um. */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backLabel: String = "",
    icon: (@Composable () -> Unit)? = null,
    flat: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth().then(if (flat) Modifier else Modifier.background(c.header))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (onBack != null) {
                IconButton(TitanIcons.back, backLabel, onBack, size = 24.dp)
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                icon?.invoke()
                Text(title, style = TitanType.topTitle, color = c.text)
            }
            actions()
        }
        if (!flat) Divider()
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(Titan.colors.border))
}

/** Runder Symbolknopf, 44 dp, mit Beschriftung für Bildschirmleser. */
@Composable
fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit, size: Dp = 23.dp, tint: Color = Titan.colors.text) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick, role = Role.Button).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

/** Große Überschrift einer Seite (.a-big). */
@Composable
fun BigTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.big, color = Titan.colors.text, modifier = modifier.padding(start = 2.dp, end = 2.dp, top = 14.dp, bottom = 12.dp))
}

/** Abschnittsüberschrift (.a-h). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, top: Dp = 18.dp) {
    Text(text, style = TitanType.section, color = Titan.colors.muted, modifier = modifier.padding(start = 4.dp, end = 4.dp, top = top, bottom = 8.dp))
}

/** Karte (.a-card) mit Zeilen und Trennlinien dazwischen. */
@Composable
fun Card(modifier: Modifier = Modifier, background: Color = Titan.colors.surface, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(background).border(1.dp, Titan.colors.border, shape), content = content)
}

/** Eine Zeile (.a-row): Symbol, Titel, Untertitel, rechts Wert oder Schalter, wahlweise Pfeil. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: (@Composable () -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    titleColor: Color = Titan.colors.text,
    divider: Boolean = false,
    onClick: (() -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
) {
    val c = Titan.colors
    Column(modifier.fillMaxWidth()) {
        if (divider) Divider()
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) Box(contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.weight(1f)) {
                Text(title, style = TitanType.rowTitle, color = titleColor)
                if (sub != null) Text(sub, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                bottom?.invoke()
            }
            if (right != null || chevron) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Ein Schalter rechts heißt für TalkBack wie die Zeile (sonst nur „Aus, Schalter“).
                    if (right != null) CompositionLocalProvider(LocalToggleLabel provides title) { right() }
                    if (chevron) Icon(TitanIcons.chev, null, tint = c.muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Zeilensymbol als Strich in der gedämpften Farbe. */
@Composable
fun RowIcon(icon: ImageVector, tint: Color = Titan.colors.muted, size: Dp = 22.dp) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/** Name eines Schalters ohne eigenes [Toggle]-label: der Titel der umgebenden [ListRow]. */
val LocalToggleLabel = staticCompositionLocalOf<String?> { null }

/**
 * Schalter (.a-tg). Der Zustand steht auch für Bildschirmleser in Worten da;
 * [label] sagt TalkBack, was geschaltet wird (sonst nur „Ein, Schalter“). Ohne
 * [label] gilt in einer [ListRow] deren Titel, sobald der Schalter selbst bedienbar ist.
 */
@Composable
fun Toggle(checked: Boolean, onChange: ((Boolean) -> Unit)?, stateOn: String, stateOff: String, enabled: Boolean = true, label: String? = null) {
    val c = Titan.colors
    val shape = RoundedCornerShape(14.dp)
    val clickable = onChange != null && enabled
    val name = label ?: if (clickable) LocalToggleLabel.current else null
    Box(
        Modifier.size(46.dp, 28.dp).clip(shape)
            .background(if (checked) c.accent else c.track)
            .border(1.dp, if (checked) c.accent else c.border, shape)
            .then(if (clickable) Modifier.clickable(role = Role.Switch) { onChange(!checked) } else Modifier)
            .semantics {
                stateDescription = if (checked) stateOn else stateOff
                if (name != null) contentDescription = name
            },
    ) {
        Box(
            Modifier.offset(x = if (checked) 21.dp else 3.dp, y = 3.dp).size(20.dp).clip(CircleShape)
                .background(if (checked) c.onAccent else c.muted),
        )
    }
}

enum class ButtonKind { Primary, Normal, Danger }

/** Knopf (.a-btn), mindestens 48 dp hoch; der Text bricht um. */
@Composable
fun TitanButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Normal,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    val bg = when (kind) {
        ButtonKind.Primary -> c.accent
        else -> c.chip
    }
    val fg = when (kind) {
        ButtonKind.Primary -> c.onAccent
        ButtonKind.Danger -> c.dangerInk
        ButtonKind.Normal -> c.text
    }
    Row(
        modifier.heightIn(min = 48.dp).clip(shape).background(if (enabled) bg else bg.copy(alpha = .6f).compositeOver(c.bg))
            .border(1.dp, if (kind == ButtonKind.Primary) c.accent else c.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        Text(text, style = TitanType.button, color = fg, textAlign = TextAlign.Center)
    }
}

/** Hinweis (.a-note) mit Symbol in der Akzentfarbe. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, icon: ImageVector = TitanIcons.info) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.accent.copy(alpha = .12f).compositeOver(c.surface))
            .border(1.dp, c.accent.copy(alpha = .35f).compositeOver(c.border), shape).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
        Text(text, style = TitanType.body.copy(fontSize = TitanType.small.fontSize.times(14f / 13f)), color = c.text)
    }
}

/** Fortschrittsbalken (.a-bar). */
@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, color: Color = Titan.colors.accent, track: Color = Titan.colors.track) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.fillMaxWidth().heightIn(min = 8.dp, max = 8.dp).clip(shape).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).heightIn(min = 8.dp, max = 8.dp).clip(shape).background(color))
    }
}

/** Chip (.a-chip). */
@Composable
fun Chip(text: String, on: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Titan.colors
    val shape = RoundedCornerShape(16.dp)
    Text(
        text,
        style = TitanType.body.copy(fontSize = TitanType.small.fontSize.times(14f / 13f)),
        color = c.text,
        modifier = Modifier.clip(shape).background(if (on) c.accent.copy(alpha = .22f) else c.chip)
            .border(1.dp, if (on) c.accent else c.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Eingabefeld im Stil von .a-search; Text bricht um statt seitlich zu laufen. */
@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TitanType.body.copy(fontSize = TitanType.rowTitle.fontSize),
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    leading: ImageVector? = null,
    placeholder: String? = null,
) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = style.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = keyboard,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape).background(c.surface).border(1.dp, c.border, shape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (leading != null) Icon(leading, null, tint = c.muted, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = style, color = c.muted)
                    inner()
                }
            }
        },
    )
}

/** Suchfeld-Optik ohne Eingabe (Start, Entwurf 10). */
@Composable
fun SearchBox(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape).background(c.surface).border(1.dp, c.border, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(TitanIcons.search, null, tint = c.muted, modifier = Modifier.size(20.dp))
        Text(label, style = TitanType.body, color = c.muted)
    }
}

/** Runder Knopf unten rechts (.a-fab). */
@Composable
fun Fab(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.size(58.dp).shadow(10.dp, shape).clip(shape).background(c.accent)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(28.dp))
    }
}

data class NavItem(val key: String, val label: String, val icon: ImageVector)

/** Leiste unten (.a-nav): Start, Dateien, Fotos, Mitteilungen. */
@Composable
fun BottomNav(items: List<NavItem>, selected: String, onSelect: (String) -> Unit) {
    val c = Titan.colors
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Column(Modifier.fillMaxWidth().background(c.header)) {
        Divider()
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            for (item in items) {
                val on = item.key == selected
                val style = if (on) TitanType.nav.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else TitanType.nav
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
                    // Nie mitten im Wort trennen: passt das längste Wort nicht in die Breite
                    // (große Schrift, schmales Fenster), steht nur das Symbol da; der Name
                    // bleibt für Bildschirmleser. Sonst bricht der Name an Wortgrenzen um.
                    val width = constraints.maxWidth
                    val fits = item.label.split(' ').all { w -> measurer.measure(w, style).size.width <= width }
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Tab) { onSelect(item.key) }
                            .semantics(mergeDescendants = true) {
                                if (on) stateDescription = "✓"
                                if (!fits) contentDescription = item.label
                            }.padding(bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier.size(60.dp, 32.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (on) c.accent.copy(alpha = .22f) else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(item.icon, null, tint = if (on) c.accent else c.muted, modifier = Modifier.size(22.dp))
                        }
                        if (fits) {
                            Text(
                                item.label,
                                style = style.copy(hyphens = androidx.compose.ui.text.style.Hyphens.None, lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
                                color = if (on) c.text else c.muted,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Kreis mit Haken bzw. Warnzeichen (Zustandskarte, Entwurf 10). Der Zustand steht daneben in Worten. */
@Composable
fun StateBadge(ok: Boolean, size: Dp = 46.dp) {
    val c = Titan.colors
    Box(Modifier.size(size).clip(CircleShape).background(if (ok) c.statusOk else c.statusWarn), contentAlignment = Alignment.Center) {
        Icon(if (ok) TitanIcons.check else TitanIcons.warn, null, tint = if (ok) Color.White else io.github.ra5on.titanos.ui.generated.StatusColors.onWarn, modifier = Modifier.size(size * 0.56f))
    }
}

/** Kleines Quadrat mit Strichsymbol (Dateizeile, Entwurf 21). */
@Composable
fun FileTypeIcon(icon: ImageVector, tint: Color = Titan.colors.muted, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Titan.colors.chip), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** Ein Fehler oder Hinweis in Worten mit Symbol (nie nur Farbe). */
@Composable
fun Problem(text: String, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.warnBg).border(1.dp, c.warnBorder, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(TitanIcons.warn, null, tint = c.warnText, modifier = Modifier.size(20.dp))
        Text(text, style = TitanType.body, color = c.warnText)
    }
}

/** Mittige leere Seite mit Text, z. B. „kommt in einer späteren Testfassung“. */
@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.invoke()
        Text(title, style = TitanType.title, color = Titan.colors.text, textAlign = TextAlign.Center)
        Text(text, style = TitanType.body, color = Titan.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
    }
}

/** Auswahlkreis (.a-rad): gewählt in der Akzentfarbe mit innerem Punkt; für Bildschirmleser „ausgewählt“. */
@Composable
fun RadioMark(selected: Boolean, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val said = stringResource(if (selected) R.string.ui_selected else R.string.ui_not_selected)
    Box(
        modifier.size(20.dp).border(2.dp, if (selected) c.accent else c.muted, CircleShape).semantics { stateDescription = said },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).background(c.accent, CircleShape))
    }
}
