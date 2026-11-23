package io.github.ra5on.titanos.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlin.math.roundToInt

/** Glasfläche wie `.glass-panel` im Web: Fläche mit Deckkraft, feiner Rand, Schatten. */
fun Modifier.glass(surface: Color, shape: Shape = RoundedCornerShape(14.dp), alpha: Float = .92f, border: Color? = Color.White.copy(alpha = .10f)): Modifier = this
    .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .35f))
    .clip(shape)
    .background(surface.copy(alpha = alpha))
    .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)

/**
 * Rechtsklick (Maus) mit der Stelle in dp relativ zum Element; am Handy ohne
 * Maus passiert nichts (dort öffnet langes Drücken dasselbe Menü).
 */
fun Modifier.onSecondaryClickAt(action: (x: Float, y: Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (currentEvent.buttons.isSecondaryPressed) {
            down.consume()
            action(down.position.x / density, down.position.y / density)
        }
    }
}

/** Ein Eintrag eines Menüs: Text, Kürzel, Haken/Punkt, gefährlich; ohne [onClick] eine Trennlinie. */
data class MenuItem(
    val label: String = "",
    val keys: String = "",
    val mark: Mark = Mark.None,
    val danger: Boolean = false,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val sub: List<MenuItem> = emptyList(),
    val onClick: (() -> Unit)? = null,
) {
    enum class Mark { None, On, Off, Radio, RadioOn }
    val separator: Boolean get() = onClick == null && sub.isEmpty() && label.isEmpty()

    companion object {
        val Separator = MenuItem()
    }
}

/**
 * Ein Menü an einer Stelle (Kontextmenü, Menüleiste). Bleibt im sichtbaren
 * Bereich; ein Tippen daneben oder Escape schließt es. Untermenüs klappen
 * innen auf (keine fliegenden Ebenen: bei 200 % Schrift bleibt alles lesbar).
 */
@Composable
fun BoxScope.MenuPopup(x: Float, y: Float, items: List<MenuItem>, onDismiss: () -> Unit, title: String? = null, minWidth: Dp = 220.dp) {
    Box(
        Modifier.matchParentSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
    BoxWithConstraints(Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val maxW = maxWidth
        val maxH = maxHeight
        val width = minOf(maxOf(minWidth, 280.dp), maxW - 16.dp)
        val left = minOf(x.dp, maxW - width - 8.dp).coerceAtLeast(8.dp)
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .offset { IntOffset(with(density) { left.toPx() }.roundToInt(), with(density) { minOf(y.dp, maxH * 0.55f).toPx() }.roundToInt()) }
                .widthIn(min = minOf(minWidth, width), max = width)
                .heightIn(max = maxH - minOf(y.dp, maxH * 0.55f) - 8.dp)
                .glass(Titan.colors.surface, shape, alpha = .97f, border = Titan.colors.border)
                .verticalScroll(rememberScrollState())
                .onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false }
                .padding(vertical = 6.dp),
        ) {
            if (title != null) Text(title, style = TitanType.section, color = Titan.colors.muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            MenuItems(items, onDismiss)
        }
    }
}

@Composable
fun ColumnScope.MenuItems(items: List<MenuItem>, onDismiss: () -> Unit, indent: Dp = 0.dp) {
    val c = Titan.colors
    for (item in items) {
        if (item.separator) {
            Box(Modifier.fillMaxWidth().padding(vertical = 5.dp).heightIn(min = 1.dp, max = 1.dp).background(c.border))
            continue
        }
        if (item.sub.isNotEmpty()) {
            Text(item.label, style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.muted, modifier = Modifier.padding(start = 14.dp + indent, end = 14.dp, top = 6.dp, bottom = 2.dp))
            MenuItems(item.sub, onDismiss, indent + 10.dp)
            continue
        }
        val on = item.mark == MenuItem.Mark.On || item.mark == MenuItem.Mark.RadioOn
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp)
                .clickable(enabled = item.enabled, role = if (item.mark == MenuItem.Mark.None) Role.Button else if (item.mark.name.startsWith("Radio")) Role.RadioButton else Role.Checkbox) {
                    onDismiss()
                    item.onClick?.invoke()
                }
                .semantics { if (item.mark != MenuItem.Mark.None) { selected = on; stateDescription = if (on) "✓" else "" } }
                .padding(start = 10.dp + indent, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                when {
                    item.icon != null -> Icon(item.icon, null, tint = if (item.danger) c.dangerInk else c.muted, modifier = Modifier.size(16.dp))
                    on -> Text(if (item.mark == MenuItem.Mark.RadioOn) "●" else "✓", style = TitanType.small, color = c.accent)
                }
            }
            Text(
                item.label,
                style = TitanType.body,
                color = when {
                    !item.enabled -> c.muted
                    item.danger -> c.dangerInk
                    else -> c.text
                },
                modifier = Modifier.weight(1f),
            )
            if (item.keys.isNotEmpty()) Text(item.keys, style = TitanType.small, color = c.muted)
        }
    }
}

/** Ein Dialog des Arbeitsplatzes: in der Mitte, höchstens [maxWidth] breit, der Inhalt scrollt. */
@Composable
fun BoxScope.DeskDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    maxWidth: Dp = 560.dp,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Titan.colors
    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = .45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.align(Alignment.Center).padding(16.dp).widthIn(max = maxWidth).fillMaxWidth()
            .clip(shape).background(c.surface).border(1.dp, c.border, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false }
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
    ) {
        Text(title, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * (20f / 22f)), color = c.text, modifier = Modifier.padding(bottom = 10.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
        Spacer(Modifier.size(10.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    }
}

/** Knopf im Text eines Dialogs (wie die Aktionen von ConfirmDialog). */
@Composable
fun TextAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Titan.colors
    Text(
        label,
        style = TitanType.button.copy(fontSize = TitanType.body.fontSize),
        color = if (danger) c.dangerInk else c.accent,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
