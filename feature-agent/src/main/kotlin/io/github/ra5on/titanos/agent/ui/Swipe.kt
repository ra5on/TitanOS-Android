package io.github.ra5on.titanos.agent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Eine Zeile, die sich zur Seite wischen lässt (agent-swipe.ts): nach rechts
 * [right], nach links [left]; darunter steht, was geschieht, als Wort.
 * Langes Drücken ruft [onMenu].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeRow(
    enabled: Boolean,
    right: String?,
    left: String?,
    onSwipe: (Boolean) -> Unit,
    onMenu: (() -> Unit)? = null,
    leftDanger: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = Titan.colors
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val limit = with(LocalDensity.current) { 90.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth().clip(SRowShape)) {
        if (drag.value != 0f) {
            val toRight = drag.value > 0
            Row(
                Modifier.matchParentSize().background(if (!toRight && leftDanger) c.dangerInk else c.accent).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End,
            ) { Text((if (toRight) right else left).orEmpty(), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = Color.White) }
        }
        Box(
            Modifier.fillMaxWidth().offset { IntOffset(drag.value.roundToInt(), 0) }.background(c.bg, SRowShape)
                .then(
                    if (enabled) Modifier.draggable(
                        rememberDraggableState { d ->
                            scope.launch {
                                val next = drag.value + d
                                val allowed = (next > 0 && right != null) || (next < 0 && left != null) || next == 0f
                                if (allowed) drag.snapTo(next.coerceIn(-limit * 1.3f, limit * 1.3f))
                            }
                        },
                        Orientation.Horizontal,
                        onDragStopped = {
                            if (abs(drag.value) >= limit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onSwipe(drag.value > 0) }
                            drag.animateTo(0f, tween(220))
                        },
                    ) else Modifier,
                )
                .then(if (onMenu != null && enabled) Modifier.combinedClickable(onClick = {}, onLongClick = onMenu) else Modifier),
        ) { content() }
    }
}
