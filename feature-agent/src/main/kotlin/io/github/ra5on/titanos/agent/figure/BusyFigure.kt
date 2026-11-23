package io.github.ra5on.titanos.agent.figure

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.agent.core.Motion
import kotlinx.coroutines.isActive
import kotlin.random.Random

/** Die Uhr einer Figur: läuft über jeden Wechsel der Art weiter (ein neues Wort startet die Bewegung nie neu). */
class FigureClock(start: Double = BLOCK * Random.nextInt(50)) {
    internal val clock = mutableDoubleStateOf(start)
}

/**
 * Die arbeitende Figur (BusyFigure.svelte): drei flache Punkte in einer
 * Farbe, die [dance] folgen. Gezeichnet wird nur im Zeichnen-Schritt (die
 * Uhr wird dort gelesen): kein Neuaufbau und kein Layout je Bild. [still]
 * („Animationen: Aus“, Bewegung reduzieren) zeigt ein Standbild zur Zeit 0.
 * Erscheint wie im Web: von halber Größe und durchsichtig in 0,3 s.
 */
@Composable
fun BusyFigure(
    kind: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color,
    still: Boolean = false,
    clock: FigureClock = remember { FigureClock() },
) {
    val width by animateDpAsState(size, if (still) tween(0) else tween(300), label = "figure-size")
    val appear = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) appear.animateTo(1f, tween(300, easing = Motion.Ease)) }
    if (!still) {
        LaunchedEffect(Unit) {
            var last = -1L
            while (isActive) {
                withInfiniteAnimationFrameNanos { now ->
                    if (last >= 0) {
                        // Hinter einer Pause (App im Hintergrund) geht es dort weiter, wo die Punkte standen.
                        val dt = ((now - last) / 1e9).coerceAtMost(0.1)
                        clock.clock.doubleValue += dt
                    }
                    last = now
                }
            }
        }
    }
    Canvas(
        modifier.size(width).semantics { invisibleToUser() }.graphicsLayer {
            val a = appear.value
            alpha = a
            scaleX = 0.5f + 0.5f * a
            scaleY = 0.5f + 0.5f * a
        },
    ) {
        val t = if (still) 0.0 else clock.clock.doubleValue
        val spots = figureAt(kind, t)
        val half = this.size.width / 2f
        val r = (SPHERE * this.size.width / 2f).toFloat()
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        // Was hinten steht, zuerst: die Tiefe zeigt sich nur darin, was was verdeckt.
        for (p in spots.sortedBy { it.z }) {
            drawCircle(color, r, center + Offset((p.x * half).toFloat(), (p.y * half).toFloat()))
        }
    }
}
