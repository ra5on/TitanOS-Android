package io.github.ra5on.titanos.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.agent.core.LocalCalm
import io.github.ra5on.titanos.agent.core.Motion
import io.github.ra5on.titanos.agent.core.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Behält den letzten Wert, damit ein Blatt beim Schließen seinen Inhalt noch zeigt. */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    if (value != null) last.value = value
    return last.value
}

/** Die dunkle Schicht hinter Blättern und dem Menü (dim: 300 ms). */
@Composable
fun BoxScope.Scrim(visible: Boolean, onClick: () -> Unit) {
    val calm = LocalCalm.current
    AnimatedVisibility(visible, enter = fadeIn(Motion.spec(calm, Motion.DIM)), exit = fadeOut(Motion.spec(calm, Motion.DIM)), modifier = Modifier.matchParentSize()) {
        val close = t("common.close")
        Box(
            Modifier.fillMaxSize().background(AgentColors.overlay)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .semantics { contentDescription = close },
        )
    }
}

/**
 * Ein Blatt von unten (BottomSheet.svelte): Griff oben, Zeilen von mindestens
 * 48 dp, Wischen nach unten schließt es, „Zurück“ auch. Gleitet mit der Kurve
 * des Webs herein (sheetUp: 420 ms, quintOut), die Schicht dahinter blendet.
 */
@Composable
fun BoxScope.AgentSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val calm = LocalCalm.current
    val c = Titan.colors
    BackHandler(enabled = visible, onBack = onDismiss)
    Scrim(visible, onDismiss)
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(Motion.spec(calm, Motion.SHEET)) { it },
        exit = slideOutVertically(Motion.spec(calm, 300)) { it },
    ) {
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        // Auf großen Bildschirmen steht das Blatt höchstens 640 dp breit in der Mitte (wie das kleine Fenster im Web am PC).
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            val max = maxHeight * 0.86f
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().offset { IntOffset(0, drag.value.roundToInt()) }
                    .heightIn(max = max)
                    .clip(shape).background(c.surface).border(1.dp, c.border, shape)
                    .semantics { paneTitle = label }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .draggable(
                        rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                        Orientation.Vertical,
                        onDragStopped = { v ->
                            if (drag.value > with(density) { 96.dp.toPx() } || v > 1400f) onDismiss()
                            else drag.animateTo(0f, tween(220))
                        },
                    )
                    .navigationBarsPadding()
                    .padding(top = 8.dp, bottom = 18.dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.muted.copy(alpha = 0.6f)))
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

/** Überschrift in einem Blatt (.sh-group). */
@Composable
fun SheetGroup(text: String) {
    Text(text, style = AgentText.sm.copy(fontSize = 12.sp_), color = Titan.colors.muted, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 2.dp))
}

/** Eine Zeile in einem Blatt (.sh-item): Symbol, Text (mit kleiner Zeile), Haken. */
@Composable
fun SheetItem(
    icon: String?,
    label: String,
    onClick: () -> Unit,
    sub: String? = null,
    selected: Boolean? = null,
    danger: Boolean = false,
    chevron: Boolean = false,
    enabled: Boolean = true,
    emoji: String? = null,
) {
    val c = Titan.colors
    val fg = if (danger) c.dangerInk else c.text
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)
            .heightIn(min = 48.dp).padding(horizontal = 22.dp, vertical = 4.dp).alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            emoji != null -> Box(Modifier.defaultMinSize(20.dp, 20.dp), contentAlignment = Alignment.Center) { Text(emoji, style = AgentText.lg) }
            icon != null -> WIcon(icon, 18.dp, if (danger) c.dangerInk else c.muted)
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = AgentText.lg.copy(lineHeight = 20.sp_), color = fg)
            if (sub != null) Text(sub, style = AgentText.sm, color = c.muted)
        }
        if (selected == true) Text("✓", style = AgentText.lg.copy(fontWeight = FontWeight.Bold), color = c.accent)
        if (chevron) WIcon("chevronRight", 16.dp, c.muted)
    }
}

@Composable
fun SheetRule() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Titan.colors.border))
}

@Composable
fun SheetQuote(text: String) {
    Text(text, style = AgentText.md.copy(fontSize = 15.sp_), color = Titan.colors.muted, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 8.dp))
}

/**
 * Eine Seite, die von rechts hereinschiebt und das Darunterliegende bedeckt
 * (slideIn im Web); „Zurück“ und Wischen vom Rand schließen sie.
 */
@Composable
fun BoxScope.SlidePage(visible: Boolean, onBack: () -> Unit, fromLeft: Boolean = false, content: @Composable () -> Unit) {
    val calm = LocalCalm.current
    BackHandler(enabled = visible, onBack = onBack)
    AnimatedVisibility(
        visible,
        modifier = Modifier.matchParentSize(),
        enter = slideInHorizontally(Motion.spec(calm, Motion.SLIDE)) { if (fromLeft) -it else it },
        exit = slideOutHorizontally(Motion.spec(calm, 320)) { if (fromLeft) -it else it },
    ) {
        Box(Modifier.fillMaxSize().background(Titan.colors.bg)) { content() }
    }
}

/** Ein schlichter Dialog in der Mitte (Dialog.svelte): Titel, Text, Inhalt, Knöpfe; der gefährliche links. */
@Composable
fun BoxScope.AgentDialog(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    text: String? = null,
    actions: @Composable () -> Unit,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val calm = LocalCalm.current
    val c = Titan.colors
    BackHandler(enabled = visible, onBack = onDismiss)
    Scrim(visible, onDismiss)
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(Motion.spec(calm, Motion.PANEL)) + slideInVertically(Motion.spec(calm, Motion.PANEL)) { it / 12 },
        exit = fadeOut(Motion.spec(calm, 180)),
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .semantics { paneTitle = title }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = AgentText.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            if (text != null) Text(text, style = AgentText.md.copy(lineHeight = 20.sp_), color = c.muted)
            content?.invoke(this)
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

private val Int.sp_ get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** Farbe des Gefährlichen für Dialogknöpfe. */
val dangerColor: Color @Composable get() = Titan.colors.dangerInk
