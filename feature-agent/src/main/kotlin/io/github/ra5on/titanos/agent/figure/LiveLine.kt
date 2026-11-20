package io.github.ra5on.titanos.agent.figure

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.chat.Entry
import io.github.ra5on.titanos.agent.chat.stepDetail
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.random.Random

/** Wie viele Wörter jede Art hat (WORDS in LiveLine.svelte). */
private val WORDS = mapOf("" to 18, "web" to 8, "files" to 8, "write" to 8, "home" to 6, "switch" to 3, "code" to 8, "system" to 4)

/** Das Wort neben den Punkten (busyWord in LiveLine.svelte). */
fun busyWord(tr: Tr, kind: String, turn: Int, elapsed: Int, hour: Int = LocalTime.now().hour): String {
    fun key(k: String) = tr.t("agent.busy.$k")
    if (kind.isNotEmpty()) return key("$kind.${(turn % (WORDS[kind] ?: 1)) + 1}")
    if (elapsed >= 20 && turn % 3 == 0) return key("long.${((turn / 3) % 6) + 1}")
    if ((hour >= 23 || hour < 5) && turn % 4 == 1) return key("night.${((turn / 4) % 2) + 1}")
    return key("${(turn % WORDS.getValue("")) + 1}")
}

/** Ein laufender Schritt: das Wort seiner Art oder sein Name, und worum es geht. */
fun sweepText(tr: Tr, step: Entry, kind: String, word: String): String {
    val detail = stepDetail(step.args)
    return (if (kind.isNotEmpty()) word else stepLabel(tr, step)) + if (detail.isNotEmpty()) " · $detail" else ""
}

/**
 * Was gerade geschieht (LiveLine.svelte): die Punkte in der Figur der
 * Tätigkeit und ihr Wort; über dem Wort läuft ein Lichtschweif. Endet ein
 * Lauf, den diese Ansicht sah, mit einer Antwort, steht kurz ein Haken mit
 * „Fertig“, der erst verblasst und dann seinen Platz schließt.
 * [content] ist der hereinströmende Text (darüber gezeigt von der Seite).
 */
@Composable
fun LiveLine(
    busy: Boolean,
    chatId: String,
    step: Entry?,
    streaming: Boolean,
    endedWithAnswer: () -> Boolean,
    still: Boolean,
    dotSize: Int,
    dotColor: Color,
    textColor: Color,
    clock: FigureClock,
) {
    val tr = tr()
    var turn by remember { mutableIntStateOf(Random.nextInt(12)) }
    var elapsed by remember { mutableIntStateOf(0) }
    var done by remember(chatId) { mutableStateOf(false) }
    val was = remember(chatId) { booleanArrayOf(busy) }
    LaunchedEffect(busy) {
        if (!busy) return@LaunchedEffect
        val start = System.currentTimeMillis()
        elapsed = 0
        while (true) {
            delay(1000)
            elapsed = ((System.currentTimeMillis() - start) / 1000).toInt()
        }
    }
    LaunchedEffect(busy, still) {
        if (!busy || still) return@LaunchedEffect
        while (true) {
            delay(2800)
            turn += 1
        }
    }
    LaunchedEffect(busy, chatId) {
        val ended = was[0] && !busy
        was[0] = busy
        if (busy) { done = false; return@LaunchedEffect }
        if (ended && !still && endedWithAnswer()) {
            done = true
            delay(1700)
            done = false
        }
    }
    val kind = BusyKind.of(step?.tool)
    if (busy) {
        val figureKind = if (streaming && step == null) BusyKind.WRITE else kind
        val word = busyWord(tr, kind, turn, elapsed)
        Row(
            Modifier.heightIn(min = if (streaming && step == null) 22.dp else 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BusyFigure(figureKind, size = dotSize.dp, color = dotColor, still = still, clock = clock)
            val text = when {
                step != null -> sweepText(tr, step, kind, word)
                !streaming -> word
                else -> null
            }
            if (text != null) {
                AnimatedContent(
                    text,
                    modifier = Modifier.weight(1f, fill = false),
                    transitionSpec = {
                        if (still) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else (slideInVertically(tween(360, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))) { it / 3 } + fadeIn(tween(360))) togetherWith fadeOut(tween(120))
                    },
                    label = "busy-word",
                ) { shown -> TrailText(shown, textColor, still) }
            }
            if (elapsed >= 20 && !streaming) {
                Text(tr.t("agent.busy.seconds", "n" to elapsed), style = AgentText.md.copy(fontSize = 13.sp), color = textColor.copy(alpha = 0.8f))
            }
        }
    }
    AnimatedVisibility(
        visible = !busy && done,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(280)) + shrinkVertically(tween(340, delayMillis = 280)),
    ) {
        Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tick(dotColor)
            Text(tr.t("agent.busy.done"), style = AgentText.md.copy(fontSize = 15.sp), color = textColor)
        }
    }
}

/** Der Text mit dem Lichtschweif: ein Band der Textfarbe gleitet von links nach rechts (1,7 s). */
@Composable
private fun TrailText(text: String, color: Color, still: Boolean) {
    val light = lerpColor(color, Titan.colors.text, 0.65f)
    if (still) {
        Text(text, style = AgentText.md.copy(fontSize = 15.sp, lineHeight = 21.sp), color = color)
        return
    }
    val sweep = rememberInfiniteTransition(label = "trail")
    val at by sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Restart), label = "trail-at")
    Text(
        text,
        style = AgentText.md.copy(fontSize = 15.sp, lineHeight = 21.sp),
        color = color,
        modifier = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            // background-size 220 %: das helle Band ist etwa ein Drittel so breit wie der Text.
            val w = size.width * 2.2f
            val x = -w + (size.width + w) * at
            drawRect(
                Brush.horizontalGradient(
                    0f to color, 0.36f to color, 0.5f to light, 0.64f to color, 1f to color,
                    startX = x, endX = x + w,
                ),
                blendMode = BlendMode.SrcIn,
            )
        },
    )
}

private fun lerpColor(a: Color, b: Color, f: Float) = Color(
    a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, a.alpha + (b.alpha - a.alpha) * f,
)

/** Das Ende eines Laufs: ein Ring schließt sich, der Haken wird gezeichnet, mit kleinem Federn. */
@Composable
fun Tick(color: Color) {
    val ring = remember { Animatable(0f) }
    val hook = remember { Animatable(0f) }
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { pop.animateTo(1f, tween(500, easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f))) }
            launch { ring.animateTo(1f, tween(450)) }
            launch { delay(300); hook.animateTo(1f, tween(300)) }
        }
    }
    Canvas(Modifier.size(18.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - 0.4f) / 0.6f).coerceIn(0f, 1f) }) {
        val s = size.width / 18f
        val stroke = Stroke(width = 1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArc(color.copy(alpha = color.alpha * 0.55f), -90f, 360f * ring.value, false, topLeft = Offset(1.5f * s, 1.5f * s), size = androidx.compose.ui.geometry.Size(15f * s, 15f * s), style = stroke)
        val path = Path().apply { moveTo(5.4f * s, 9.4f * s); lineTo(7.9f * s, 11.9f * s); lineTo(12.6f * s, 6.9f * s) }
        val measure = PathMeasure().apply { setPath(path, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * hook.value, part, true)
        drawPath(part, color, style = stroke)
    }
}

/** Wie stepLabel in chat-text.svelte.ts. */
fun stepLabel(tr: Tr, e: Entry): String {
    if (e.role == "note") return tr.t(
        when (e.note) {
            "verify" -> "agent.noteVerify"
            "approved" -> "agent.noteApproved"
            else -> "agent.noteDeclined"
        },
    )
    val word = io.github.ra5on.titanos.agent.chat.stepWord(e.tool)
    return if (word.isNotEmpty()) tr.t("agent.step.$word") else (e.tool ?: "").replace('_', ' ')
}
