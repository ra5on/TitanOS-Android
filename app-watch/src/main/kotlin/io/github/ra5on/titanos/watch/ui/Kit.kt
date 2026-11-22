package io.github.ra5on.titanos.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.LocalTextConfiguration
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedIconButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextConfiguration
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.watch.R

/** Farben wie die TitanOS-Oberfläche im dunklen Design (core-ui TitanColors), Kontrast ≥ 4,5:1 auf Schwarz. */
object WatchColors {
    val accent = Color(0xFF6CB6FF)
    val onAccent = Color(0xFF0B1622)
    val ok = Color(0xFF9BE3BE)
    val warn = Color(0xFFF6D79A)
    val danger = Color(0xFFF6B8A4)
    val dangerContainer = Color(0xFF5A2A20)
    val muted = Color(0xFFB4BCC5)
}

@Composable
fun WatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = WatchColors.accent,
            onPrimary = WatchColors.onAccent,
            primaryContainer = Color(0xFF1B3A5C),
            onPrimaryContainer = Color(0xFFDCEBFF),
            error = WatchColors.danger,
            onError = Color(0xFF2A1512),
            errorContainer = WatchColors.dangerContainer,
            onErrorContainer = Color(0xFFFFE4DC),
        ),
        content = content,
    )
}

/**
 * Text ohne Zeilengrenze und ohne „…“: Wear-Bausteine geben ihren Texten
 * sonst höchstens drei Zeilen. Umbrochen wird nur zwischen ganzen Wörtern
 * (keine Silbentrennung).
 */
private val unlimited = TextConfiguration(TextAlign.Start, TextOverflow.Clip, Int.MAX_VALUE)
private val unlimitedCentered = TextConfiguration(TextAlign.Center, TextOverflow.Clip, Int.MAX_VALUE)

@Composable
fun WText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    center: Boolean = false,
) {
    val base = style.copy(hyphens = Hyphens.None, lineBreak = LineBreak.Paragraph)
    // Ganze Wörter: passt das längste Wort bei großer Schrift nicht in die
    // Breite, wird nur dieser Text so weit kleiner, dass es passt – statt es
    // mitten im Wort zu trennen. Die Breite kommt aus dem Layout (Wear-Knöpfe
    // messen ihren Inhalt vorab, BoxWithConstraints geht dort nicht).
    var avail by remember { mutableIntStateOf(0) }
    val measurer = rememberTextMeasurer()
    val fitted = remember(text, base, avail) {
        if (avail <= 0 || !base.fontSize.isSp) {
            base
        } else {
            val words = text.split(wordGaps).filter { it.isNotEmpty() }
            fun scaled(f: Float) = base.copy(
                fontSize = base.fontSize * f,
                lineHeight = if (base.lineHeight.isSp) base.lineHeight * f else base.lineHeight,
                letterSpacing = if (base.letterSpacing.isSp) base.letterSpacing * f else base.letterSpacing,
            )
            fun widest(st: TextStyle) = words.maxOfOrNull { measurer.measure(it, st, softWrap = false).size.width } ?: 0
            // Große Schrift wächst nicht linear (Android 14+): schrittweise kleiner, bis das Wort passt.
            var f = 1f
            var w = widest(base)
            var tries = 0
            while (w > avail && tries < 12) {
                f *= (avail.toFloat() / w) * 0.97f
                w = widest(scaled(f))
                tries++
            }
            if (f == 1f) base else scaled(f)
        }
    }
    CompositionLocalProvider(LocalTextConfiguration provides if (center) unlimitedCentered else unlimited) {
        Text(
            text,
            modifier = modifier
                .layout { measurable, constraints ->
                    // Nur kleiner werden: misst ein Baustein zweimal mit verschiedener Breite,
                    // pendelt der Text sonst endlos hin und her. Es gilt die engste.
                    if (constraints.hasBoundedWidth && (avail == 0 || constraints.maxWidth < avail)) avail = constraints.maxWidth
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .then(if (center) Modifier.fillMaxWidth() else Modifier),
            color = color,
            style = fitted,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** Wo eine Zeile umbrechen darf: Leerzeichen und Zeilenende (Wörter mit Bindestrich bleiben ganz). */
private val wordGaps = Regex("""\s+""")

/** Eine Seite der Uhr: rund oder eckig, scrollt, alles untereinander. */
@Composable
fun Page(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    ScreenScaffold(scrollState = state) { padding ->
        ScalingLazyColumn(
            state = state,
            contentPadding = padding,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
fun Header(text: String) {
    ListHeader(Modifier.semantics { heading() }) { WText(text, style = MaterialTheme.typography.titleMedium, center = true) }
}

/** Knopf über die ganze Breite mit Text, darunter wahlweise eine zweite Zeile. */
@Composable
fun RowButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    sub: String? = null,
    danger: Boolean = false,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        colors = when {
            danger -> ButtonDefaults.filledTonalButtonColors(containerColor = WatchColors.dangerContainer, contentColor = Color(0xFFFFE4DC), secondaryContentColor = Color(0xFFFFE4DC), iconColor = Color(0xFFFFE4DC))
            filled -> ButtonDefaults.buttonColors()
            else -> ButtonDefaults.filledTonalButtonColors()
        },
        icon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(22.dp)) } },
        secondaryLabel = sub?.let { { WText(it, style = MaterialTheme.typography.labelSmall) } },
        label = { WText(label, style = MaterialTheme.typography.labelMedium) },
    )
}

/** Zustand mit Symbol und Wort, nie nur Farbe. */
@Composable
fun StateLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Icon(if (ok) TitanIcons.check else TitanIcons.warn, contentDescription = null, tint = if (ok) WatchColors.ok else WatchColors.warn, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        WText(text, color = if (ok) WatchColors.ok else WatchColors.warn, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Busy(text: String) {
    Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(32.dp))
        Spacer(Modifier.size(6.dp))
        WText(text, center = true, color = WatchColors.muted)
    }
}

@Composable
fun Problem(text: String) {
    WText(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), color = WatchColors.danger, center = true)
}

/**
 * Bestätigung für Gefährliches (VM herunterfahren, Container stoppen):
 * einfacher Dialog, gefährlicher Knopf links von „Abbrechen“, nichts eintippen.
 */
@Composable
fun ConfirmDanger(visible: Boolean, title: String, text: String?, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { WText(title, center = true, style = MaterialTheme.typography.titleMedium) },
        text = text?.let { { WText(it, center = true) } },
        // Wear legt den zweiten Platz links an: dort steht der gefährliche Knopf.
        dismissButton = {
            FilledIconButton(
                onClick = onConfirm,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = WatchColors.dangerContainer, contentColor = Color(0xFFFFE4DC)),
            ) { Icon(TitanIcons.stop, contentDescription = action) }
        },
        confirmButton = {
            OutlinedIconButton(onClick = onDismiss) { Icon(TitanIcons.x, contentDescription = stringResource(R.string.w_cancel)) }
        },
    )
}
