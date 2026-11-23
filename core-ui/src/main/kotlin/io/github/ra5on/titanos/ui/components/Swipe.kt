package io.github.ra5on.titanos.ui.components

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.okInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Eine Aktion hinter einer Zeile (Entwürfe 10–12): Farbe, Symbol, Wort. */
data class SwipeAction(val label: String, val icon: ImageVector, val color: Color, val run: () -> Unit)

/** Was nach dem Loslassen passiert. */
enum class SwipeOutcome { Close, RevealStart, RevealEnd, RunStart, RunEnd }

/**
 * Schwellen der Wischgesten, für alle Listen gleich (auch Mail, MailUi.kt):
 * kurz ziehen (über die halbe Knopfbreite) lässt den Knopf stehen, weit
 * durchziehen (über die halbe Zeile, mindestens [RUN_EXTRA_DP] hinter dem
 * Knopf) führt die Aktion aus. Rechnet nur mit Zahlen, damit Tests sie prüfen.
 */
object SwipeDefaults {
    /** Breite des Knopfs hinter der Zeile (Entwurf: 112 dp). */
    const val REVEAL_DP = 112f
    const val RUN_FRACTION = 0.5f
    const val RUN_EXTRA_DP = 48f

    /** Ab hier führt das Loslassen die Aktion aus ([width], [reveal], [extra] in Pixeln). */
    fun runAt(width: Float, reveal: Float, extra: Float): Float = maxOf(width * RUN_FRACTION, reveal + extra)

    /**
     * Ergebnis für den Versatz beim Loslassen ([offset] > 0: nach rechts gezogen,
     * die Aktion am Anfang). Eine Richtung ohne Aktion schließt immer.
     */
    fun outcome(offset: Float, width: Float, reveal: Float, extra: Float, hasStart: Boolean, hasEnd: Boolean): SwipeOutcome {
        val run = runAt(width, reveal, extra)
        return when {
            offset > 0 && hasStart && offset >= run -> SwipeOutcome.RunStart
            offset > 0 && hasStart && offset >= reveal / 2 -> SwipeOutcome.RevealStart
            offset < 0 && hasEnd && -offset >= run -> SwipeOutcome.RunEnd
            offset < 0 && hasEnd && -offset >= reveal / 2 -> SwipeOutcome.RevealEnd
            else -> SwipeOutcome.Close
        }
    }

    /** Wie weit sich die Zeile ziehen lässt: ohne Aktion in einer Richtung gar nicht, sonst bis fast zum Rand. */
    fun clamp(offset: Float, width: Float, hasStart: Boolean, hasEnd: Boolean): Float =
        offset.coerceIn(if (hasEnd) -width * 0.9f else 0f, if (hasStart) width * 0.9f else 0f)
}

/**
 * Eine Zeile zum Wischen (Entwürfe 10–12): nach rechts zieht [start] hervor,
 * nach links [end]. Kurz ziehen zeigt den Knopf, weit durchziehen führt die
 * Aktion aus (mit spürbarem Ruck an der Schwelle). Jede Aktion steht auch als
 * TalkBack-Aktion da und muss außerdem im Menü der Zeile stehen. Die Maus
 * wischt nicht (Ziehen und Ablegen im Desktop-Modus); mit „Animationen
 * entfernen“ springt die Zeile statt zu gleiten.
 */
@Composable
fun SwipeRow(
    start: SwipeAction?,
    end: SwipeAction?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    background: Color = Titan.colors.surface,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val minReveal = with(density) { SwipeDefaults.REVEAL_DP.dp.toPx() }
    // Der Knopf wächst mit seinem Wort (große Schrift, „Papierkorb“): nie mitten im Wort umbrechen.
    var backWidth by remember { mutableFloatStateOf(0f) }
    val extra = with(density) { SwipeDefaults.RUN_EXTRA_DP.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(1f) }
    val still = reduceMotion()
    val shown by animateFloatAsState(offset, if (still || dragging) snap() else tween(180), label = "swipe")
    val haptic = LocalHapticFeedback.current
    val startNow by rememberUpdatedState(start)
    val endNow by rememberUpdatedState(end)
    val live = enabled && (start != null || end != null)
    fun finish() {
        // Beim Loslassen neu lesen (der Knopf ist inzwischen gemessen); die Geste hält die erste Fassung fest.
        val reveal = maxOf(minReveal, backWidth)
        // Im Rechts-nach-links-Layout ist „Anfang“ rechts: die Richtung kehrt sich um.
        val logical = if (rtl) -offset else offset
        when (SwipeDefaults.outcome(logical, width, reveal, extra, startNow != null, endNow != null)) {
            SwipeOutcome.RunStart -> { offset = 0f; startNow?.run?.invoke() }
            SwipeOutcome.RunEnd -> { offset = 0f; endNow?.run?.invoke() }
            SwipeOutcome.RevealStart -> offset = if (rtl) -reveal else reveal
            SwipeOutcome.RevealEnd -> offset = if (rtl) reveal else -reveal
            SwipeOutcome.Close -> offset = 0f
        }
    }
    Box(
        modifier.fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                if (live) customActions = listOfNotNull(start, end).map { a -> CustomAccessibilityAction(a.label) { a.run(); true } }
            }
            .then(
                if (!live) {
                    Modifier
                } else {
                    Modifier.pointerInput(start != null, end != null, rtl) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.type == PointerType.Mouse) return@awaitEachGesture
                            var crossed = false
                            fun move(dx: Float) {
                                val logicalStart = if (rtl) endNow != null else startNow != null
                                val logicalEnd = if (rtl) startNow != null else endNow != null
                                offset = SwipeDefaults.clamp(offset + dx, width, logicalStart, logicalEnd)
                                val beyond = abs(offset) >= SwipeDefaults.runAt(width, maxOf(minReveal, backWidth), extra)
                                if (beyond != crossed) {
                                    crossed = beyond
                                    if (beyond) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                }
                            }
                            val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                move(over)
                            } ?: return@awaitEachGesture
                            dragging = true
                            val ok = horizontalDrag(first.id) { change ->
                                move(change.positionChange().x)
                                change.consume()
                            }
                            dragging = false
                            if (ok) finish() else offset = 0f
                        }
                    }
                },
            ),
    ) {
        val left = if (rtl) end else start
        val right = if (rtl) start else end
        // Hinter der Zeile: die Aktion auf der Seite, die frei wird; antippen führt sie aus.
        if (shown > 0 && left != null) {
            SwipeBack(left, Modifier.matchParentSize(), Alignment.CenterStart, revealed = !dragging && abs(offset) > 1f, onWidth = { backWidth = maxOf(backWidth, it) }) { offset = 0f; left.run() }
        }
        if (shown < 0 && right != null) {
            SwipeBack(right, Modifier.matchParentSize(), Alignment.CenterEnd, revealed = !dragging && abs(offset) > 1f, onWidth = { backWidth = maxOf(backWidth, it) }) { offset = 0f; right.run() }
        }
        Box(Modifier.offset { IntOffset(shown.roundToInt(), 0) }.background(background).fillMaxWidth()) {
            content()
            // Offen: Tippen auf die Zeile schließt sie wieder, statt sie zu öffnen.
            if (abs(offset) > 1f && !dragging) {
                Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { offset = 0f })
            }
        }
    }
}

@Composable
private fun SwipeBack(a: SwipeAction, modifier: Modifier, align: Alignment, revealed: Boolean, onWidth: (Float) -> Unit, onRun: () -> Unit) {
    Box(modifier.background(a.color), contentAlignment = align) {
        Row(
            Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).widthIn(min = SwipeDefaults.REVEAL_DP.dp).fillMaxHeight()
                .onSizeChanged { onWidth(it.width.toFloat()) }
                .then(if (revealed) Modifier.clickable(role = Role.Button, onClick = onRun) else Modifier)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(a.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(a.label, style = TitanType.button, color = Color.White)
        }
    }
}

/**
 * Herunterziehen lädt neu (Entwürfe 10 und 11). Der Inhalt muss scrollen
 * (Liste oder verticalScroll), auch wenn er kurz ist. Ohne [refresh] nur der Inhalt.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PullRefresh(refresh: (suspend () -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (refresh == null) {
        Box(modifier) { content() }
        return
    }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val run by rememberUpdatedState(refresh)
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = busy,
        onRefresh = {
            busy = true
            scope.launch {
                try {
                    run()
                } finally {
                    busy = false
                }
            }
        },
        modifier = modifier,
    ) { content() }
}

/** Meldet jedes Aufsetzen eines Fingers, teilt es aber mit der Seite darunter. */
private class OutsideTapElement(val onDown: () -> Unit) : androidx.compose.ui.node.ModifierNodeElement<OutsideTapNode>() {
    override fun create() = OutsideTapNode(onDown)
    override fun update(node: OutsideTapNode) {
        node.onDown = onDown
    }
    override fun equals(other: Any?) = other is OutsideTapElement && other.onDown === onDown
    override fun hashCode() = onDown.hashCode()
}

private class OutsideTapNode(var onDown: () -> Unit) : Modifier.Node(), androidx.compose.ui.node.PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: androidx.compose.ui.input.pointer.PointerEvent, pass: androidx.compose.ui.input.pointer.PointerEventPass, bounds: androidx.compose.ui.unit.IntSize) {
        if (pass == androidx.compose.ui.input.pointer.PointerEventPass.Initial && pointerEvent.type == androidx.compose.ui.input.pointer.PointerEventType.Press) onDown()
    }
    override fun onCancelPointerInput() {}
    override fun sharePointerInputWithSiblings(): Boolean = true
}

/** Ein Knopf in einer kurzen Meldung unten („Rückgängig“, „Öffnen“, „Zeigen“). */
data class SnackAction(val label: String, val run: () -> Unit)

/** Wie lange eine kurze Meldung steht: 4 s ohne, 6 s mit Knopf („Rückgängig“). */
const val SNACK_INFO_MS = 4_000L
const val SNACK_ACTION_MS = 6_000L

/**
 * Standzeit einer Meldung (1.02): [base]; nur mit TalkBack (Tastendruck-Erkunden)
 * gilt die von Android empfohlene Zeit als Untergrenze – vorher galt sie immer
 * und konnte mit „Zeit zum Reagieren“ Minuten betragen.
 */
fun snackTimeout(base: Long, talkBack: Boolean, recommended: Long): Long = if (talkBack) maxOf(base, recommended) else base

/**
 * Kurze Meldung unten (Entwurf 21, 1.02 überarbeitet): Text, darunter wahlweise
 * ein zweiter, rechts die Knöpfe. Steht 4 s, mit Knopf 6 s ([snackTimeout]);
 * eine neue Meldung ([key]) ersetzt sie sofort und beginnt neu. Wegwischen
 * oder Tippen daneben schließt sie. [startedAt] (uptimeMillis): seit wann sie
 * steht, damit ein Neuaufbau der Seite die Zeit nicht neu beginnt. Sie wird
 * vorgelesen.
 */
@Composable
fun BoxScope.Snackbar(
    text: String,
    actions: List<SnackAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    done: Boolean = false,
    timeoutMs: Long = if (actions.isEmpty()) SNACK_INFO_MS else SNACK_ACTION_MS,
    key: Any? = null,
    startedAt: Long? = null,
) {
    val c = Titan.colors
    val context = LocalContext.current
    val wait = remember(timeoutMs) {
        val am = context.getSystemService(AccessibilityManager::class.java)
        val recommended = am?.getRecommendedTimeoutMillis(timeoutMs.toInt(), AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS)?.toLong() ?: timeoutMs
        snackTimeout(timeoutMs, am?.isTouchExplorationEnabled == true, recommended)
    }
    val dismiss by rememberUpdatedState(onDismiss)
    val since = remember(key, text, sub) { startedAt ?: android.os.SystemClock.uptimeMillis() }
    LaunchedEffect(key, text, sub) {
        delay((since + wait - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
        dismiss()
    }
    // Tippen daneben schließt, ohne das Tippen zu schlucken (es geht weiter an die Seite darunter).
    Box(Modifier.matchParentSize().then(OutsideTapElement { dismiss() }))
    var drag by remember(key, text) { mutableFloatStateOf(0f) }
    val width = with(LocalDensity.current) { 120.dp.toPx() }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .offset { IntOffset(drag.toInt(), 0) }
            .pointerInput(key, text) {
                // Zur Seite wischen schließt.
                detectHorizontalDragGestures(
                    onDragEnd = { if (kotlin.math.abs(drag) > width) dismiss() else drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            }
            .clip(shape).background(c.header)
            .heightIn(min = 56.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (done) Icon(TitanIcons.check, null, tint = c.okInk, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = TitanType.body, color = c.text)
            if (sub != null) Text(sub, style = TitanType.small, color = c.muted)
        }
        for (a in actions) {
            Text(
                a.label,
                style = TitanType.button,
                color = c.accent,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { a.run(); dismiss() }.padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    }
}
