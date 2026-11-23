package io.github.ra5on.titanos.files

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.reduceMotion

/** Ab hier schließt Ziehen nach unten die Fotoansicht (Entwurf 13). */
const val DISMISS_DP = 120f

/**
 * Fotoansicht (Entwurf 13): mit einem Finger nach unten ziehen schließt sie.
 * Nur wenn [enabled] (nicht gezoomt, ein Bild); zwei Finger, nach oben oder
 * zur Seite bleiben bei Zoom und Blättern.
 */
@Composable
fun DismissBox(enabled: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var dy by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val still = reduceMotion()
    val shown by animateFloatAsState(dy, if (dragging || still) snap() else tween(160), label = "dismiss")
    val limit = with(LocalDensity.current) { DISMISS_DP.dp.toPx() }
    val close by rememberUpdatedState(onClose)
    Box(
        modifier
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var multi = false
                            val first = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                                // Nur nach unten und mit einem Finger; sonst weiter warten (Zoom, nach oben).
                                if (over > 0 && currentEvent.changes.count { it.pressed } == 1) {
                                    change.consume()
                                    dy = over
                                }
                            } ?: return@awaitEachGesture
                            dragging = true
                            verticalDrag(first.id) { ch ->
                                if (currentEvent.changes.count { it.pressed } > 1) multi = true
                                dy = (dy + ch.positionChange().y).coerceAtLeast(0f)
                                ch.consume()
                            }
                            dragging = false
                            if (!multi && dy >= limit) close() else dy = 0f
                        }
                    }
                },
            )
            .graphicsLayer {
                translationY = shown
                alpha = 1f - (shown / (limit * 4)).coerceIn(0f, 0.4f)
            },
        content = content,
    )
}

/**
 * Leiste der Nachbarbilder unten in der Fotoansicht (Entwurf 13): das
 * aktuelle mit Rahmen, Tippen springt hin. [thumb] zeichnet ein Vorschaubild.
 */
@Composable
fun ThumbStrip(
    count: Int,
    current: Int,
    onPick: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    thumb: @Composable (Int, Modifier) -> Unit,
) {
    val list = rememberLazyListState()
    val still = reduceMotion()
    LaunchedEffect(current) {
        val target = (current - 2).coerceAtLeast(0)
        if (still) list.scrollToItem(target) else list.animateScrollToItem(target)
    }
    val shape = RoundedCornerShape(6.dp)
    LazyRow(
        modifier.fillMaxWidth().background(Color.Black.copy(alpha = .55f)).padding(vertical = 8.dp),
        state = list,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(count) { i ->
            val on = i == current
            val name = label(i)
            Box(
                Modifier.size(if (on) 60.dp else 52.dp).clip(shape)
                    .then(if (on) Modifier.border(2.dp, Color.White, shape) else Modifier)
                    .clickable(role = Role.Button) { onPick(i) }
                    .semantics {
                        contentDescription = name
                        selected = on
                    },
            ) { thumb(i, Modifier.matchParentSize()) }
        }
    }
}
