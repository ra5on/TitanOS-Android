package io.github.ra5on.titanos.terminal

import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.content.res.ResourcesCompat
import io.github.ra5on.titanos.ui.theme.Titan
import kotlin.math.floor

/** Die 16 Grundfarben wie das Standardthema von xterm.js. */
private val ANSI = intArrayOf(
    0xFF2E3436.toInt(), 0xFFCD3131.toInt(), 0xFF0DBC79.toInt(), 0xFFE5E510.toInt(),
    0xFF2472C8.toInt(), 0xFFBC3FBC.toInt(), 0xFF11A8CD.toInt(), 0xFFE5E5E5.toInt(),
    0xFF666666.toInt(), 0xFFF14C4C.toInt(), 0xFF23D18B.toInt(), 0xFFF5F543.toInt(),
    0xFF3B8EEA.toInt(), 0xFFD670D6.toInt(), 0xFF29B8DB.toInt(), 0xFFFFFFFF.toInt(),
)

/** Die Grundfarben auf hellem Grund: dunkler, damit sie 4,5:1 erreichen. */
private val ANSI_LIGHT = intArrayOf(
    0xFF000000.toInt(), 0xFFB3261E.toInt(), 0xFF116329.toInt(), 0xFF7A5A00.toInt(),
    0xFF0B4FA8.toInt(), 0xFF8E2A8E.toInt(), 0xFF0B6A78.toInt(), 0xFF5A5A5A.toInt(),
    0xFF404040.toInt(), 0xFFA8191A.toInt(), 0xFF0E5A26.toInt(), 0xFF6B5000.toInt(),
    0xFF0A47A0.toInt(), 0xFF7E237E.toInt(), 0xFF09606D.toInt(), 0xFF1F1F1F.toInt(),
)

internal fun palette(index: Int, light: Boolean): Int {
    if (index < 16) return (if (light) ANSI_LIGHT else ANSI)[index]
    if (index < 232) {
        val i = index - 16
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        return (0xFF shl 24) or (steps[i / 36] shl 16) or (steps[(i / 6) % 6] shl 8) or steps[i % 6]
    }
    val g = 8 + (index - 232) * 10
    return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
}

/** Markierung in Zeilen/Spalten des ganzen Verlaufs. */
data class Selection(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int) {
    val ordered: Selection
        get() = if (startLine < endLine || (startLine == endLine && startCol <= endCol)) this else Selection(endLine, endCol, startLine, startCol)

    fun contains(line: Int, col: Int): Boolean {
        val o = ordered
        if (line < o.startLine || line > o.endLine) return false
        if (line == o.startLine && col < o.startCol) return false
        if (line == o.endLine && col > o.endCol) return false
        return true
    }
}

/** Zustand der Ansicht, den die Leiste darüber braucht (Markierung, Rücklauf). */
class TermViewState {
    var selection by mutableStateOf<Selection?>(null)
    /** Zeilen über dem unteren Ende (0 = aktuelle Ausgabe). */
    var scroll by mutableIntStateOf(0)
    var focus = FocusRequester()
    var showKeyboard: () -> Unit = {}
}

/**
 * Der Bildschirm einer Sitzung: zeichnet den Emulator im Raster der
 * Schrift (IBM Plex Mono wie im Web), meldet seine Größe in Spalten und
 * Zeilen, nimmt Bildschirm- und Hardware-Tastatur an. Wischen blättert im
 * Verlauf, langes Drücken markiert, zwei Finger ändern die Schriftgröße.
 */
@Composable
fun TermView(
    session: ConsoleSession,
    fontSizeSp: Float,
    state: TermViewState,
    sink: TermInputSink,
    onFontSize: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    mouseReports: Boolean = true,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val colors = Titan.colors
    val light = !colors.dark
    val version = session.version
    val typeface = remember {
        runCatching { ResourcesCompat.getFont(context, io.github.ra5on.titanos.ui.R.font.ibm_plex_mono_400) }.getOrNull() ?: Typeface.MONOSPACE
    }
    val bold = remember {
        runCatching { ResourcesCompat.getFont(context, io.github.ra5on.titanos.ui.R.font.ibm_plex_mono_500) }.getOrNull() ?: Typeface.MONOSPACE
    }
    val px = with(density) { fontSizeSp * fontScale * this.density }
    val paint = remember(px, typeface) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = px
        }
    }
    val cellW = paint.measureText("M")
    val metrics = paint.fontMetrics
    val cellH = (metrics.descent - metrics.ascent) * 1.12f
    val currentFont by rememberUpdatedState(fontSizeSp)
    var pinch by remember { mutableFloatStateOf(1f) }
    val emu = session.emulator

    val reports by rememberUpdatedState(mouseReports)

    /** Das Programm will Mausmeldungen (htop, mc, vim mit mouse=a …) und der Schalter ist an. */
    fun mouseOn() = reports && emu.mouseTracking != 0

    /** Bildschirmzelle (Spalte, Zeile) für Mausmeldungen. */
    fun screenCell(x: Float, y: Float): Pair<Int, Int> =
        floor(x / cellW).toInt().coerceIn(0, emu.cols - 1) to floor(y / cellH).toInt().coerceIn(0, emu.rows - 1)

    fun report(button: Int, x: Float, y: Float, action: TermEmulator.MouseAction, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        val (col, row) = screenCell(x, y)
        val bytes = synchronized(emu) { emu.mouse(button, col, row, action, shift, alt, ctrl) } ?: return
        session.send(bytes)
    }

    fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val rowOnScreen = floor(y / cellH).toInt().coerceIn(0, emu.rows - 1)
        val col = floor(x / cellW).toInt().coerceIn(0, emu.cols - 1)
        val first = emu.historySize - state.scroll
        return (first + rowOnScreen) to col
    }

    state.showKeyboard = {
        state.focus.requestFocus()
        view.post {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(view, 0)
        }
    }

    val screenText = remember(version) {
        synchronized(emu) {
            (0 until emu.rows).map { emu.screenLine(it).text().trimEnd() }.lastOrNull { it.isNotBlank() }.orEmpty()
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size ->
                if (cellW > 0 && cellH > 0) {
                    val cols = (size.width / cellW).toInt().coerceAtLeast(10)
                    val rows = (size.height / cellH).toInt().coerceAtLeast(3)
                    session.resize(cols, rows)
                }
            }
            .semantics {
                contentDescription = label
                stateDescription = screenText
            }
            .termInput(view, sink)
            .focusRequester(state.focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                sink.keyEvent(e.nativeKeyEvent)
            }
            .pointerInput(cellW, cellH) {
                // Maus und Touchpad: Tasten, Bewegung und Rad gehen an das Programm, wenn es sie will.
                awaitPointerEventScope {
                    var pressed = -1
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.type != PointerType.Mouse || !mouseOn()) continue
                        val meta = event.keyboardModifiers
                        val shift = meta.isShiftPressed
                        if (shift) continue // Umschalt wie in xterm.js: markieren statt melden.
                        val alt = meta.isAltPressed
                        val ctrl = meta.isCtrlPressed
                        val pos = change.position
                        when (event.type) {
                            PointerEventType.Press -> {
                                pressed = when {
                                    event.buttons.isSecondaryPressed -> 2
                                    event.buttons.isTertiaryPressed -> 1
                                    else -> 0
                                }
                                report(pressed, pos.x, pos.y, TermEmulator.MouseAction.Press, alt = alt, ctrl = ctrl)
                            }
                            PointerEventType.Release -> {
                                report(pressed.coerceAtLeast(0), pos.x, pos.y, TermEmulator.MouseAction.Release, alt = alt, ctrl = ctrl)
                                pressed = -1
                            }
                            PointerEventType.Move -> report(if (pressed >= 0) pressed else 3, pos.x, pos.y, TermEmulator.MouseAction.Motion, alt = alt, ctrl = ctrl)
                            PointerEventType.Scroll -> {
                                val dy = change.scrollDelta.y
                                if (dy != 0f) report(if (dy < 0) 64 else 65, pos.x, pos.y, TermEmulator.MouseAction.Press, alt = alt, ctrl = ctrl)
                            }
                            else -> continue
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .pointerInput(cellW, cellH) {
                detectTapGestures(onTap = { o ->
                    state.selection = null
                    if (mouseOn()) {
                        // Tippen ist ein Klick der linken Taste an dieser Stelle.
                        report(0, o.x, o.y, TermEmulator.MouseAction.Press)
                        report(0, o.x, o.y, TermEmulator.MouseAction.Release)
                        state.focus.requestFocus()
                    } else {
                        state.showKeyboard()
                    }
                })
            }
            .pointerInput(cellW, cellH) {
                var acc = 0f
                detectDragGestures(onDragStart = { acc = 0f }) { change, drag ->
                    if (state.selection != null) return@detectDragGestures
                    change.consume()
                    acc += drag.y
                    val lines = (acc / cellH).toInt()
                    if (lines != 0) {
                        acc -= lines * cellH
                        if (mouseOn()) {
                            // Wischen ist das Mausrad: nach unten wischen blättert nach oben.
                            repeat(kotlin.math.abs(lines)) {
                                report(if (lines > 0) 64 else 65, change.position.x, change.position.y, TermEmulator.MouseAction.Press)
                            }
                        } else if (emu.altScreen) {
                            // Im zweiten Bildschirm (less, vim …) blättern die Pfeiltasten.
                            val key = if (lines > 0) TermKey.Up else TermKey.Down
                            repeat(kotlin.math.abs(lines)) { sink.key(key) }
                        } else {
                            state.scroll = (state.scroll + lines).coerceIn(0, emu.historySize)
                        }
                    }
                }
            }
            .pointerInput(cellW, cellH) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        val (l, c) = cellAt(o.x, o.y)
                        state.selection = Selection(l, c, l, c)
                    },
                ) { change, _ ->
                    change.consume()
                    val s = state.selection ?: return@detectDragGesturesAfterLongPress
                    val (l, c) = cellAt(change.position.x, change.position.y)
                    state.selection = s.copy(endLine = l, endCol = c)
                }
            }
            .pointerInput(Unit) {
                // Zwei Finger: Schriftgröße; ein Finger bleibt für Blättern und Markieren.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                pinch *= zoom
                                if (pinch > 1.15f || pinch < 0.87f) {
                                    onFontSize((currentFont + if (pinch > 1f) 1f else -1f).coerceIn(MIN_FONT, MAX_FONT))
                                    pinch = 1f
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        version.hashCode()
        val bgDefault = colors.bg.toArgb()
        val fgDefault = colors.text.toArgb()
        val cursorColor = colors.accent.toArgb()
        val selColor = colors.accent.copy(alpha = .35f).toArgb()
        val canvas = drawContext.canvas.nativeCanvas
        val fill = Paint()
        fill.color = bgDefault
        canvas.drawRect(0f, 0f, size.width, size.height, fill)
        synchronized(emu) {
            val reverse = emu.reverseVideo
            val first = emu.historySize - state.scroll.coerceAtMost(emu.historySize)
            val sel = state.selection
            val baseline = -metrics.ascent + (cellH - (metrics.descent - metrics.ascent)) / 2
            for (y in 0 until emu.rows) {
                val lineIndex = first + y
                val line = emu.line(lineIndex)
                val top = y * cellH
                var x = 0
                while (x < emu.cols) {
                    val ch = line.chars[x]
                    val st = line.styles[x]
                    val flags = TermEmulator.flagsOf(st)
                    var fg = colorOf(TermEmulator.fgOf(st), fgDefault, light, bright = flags and TermEmulator.BOLD != 0L)
                    var bg = colorOf(TermEmulator.bgOf(st), bgDefault, light, bright = false)
                    if ((flags and TermEmulator.INVERSE != 0L) != reverse) {
                        val t = fg
                        fg = bg
                        bg = t
                    }
                    val wide = x + 1 < emu.cols && line.chars[x + 1] == TermEmulator.WIDE_TAIL
                    val w = if (wide) 2 else 1
                    if (bg != bgDefault) {
                        fill.color = bg
                        canvas.drawRect(x * cellW, top, (x + w) * cellW, top + cellH, fill)
                    }
                    if (sel != null && sel.contains(lineIndex, x)) {
                        fill.color = selColor
                        canvas.drawRect(x * cellW, top, (x + w) * cellW, top + cellH, fill)
                    }
                    if (ch != ' '.code && ch != TermEmulator.WIDE_TAIL && flags and TermEmulator.INVISIBLE == 0L) {
                        paint.color = if (flags and TermEmulator.DIM != 0L) (fg and 0x00FFFFFF) or (0x99 shl 24) else fg
                        paint.typeface = if (flags and TermEmulator.BOLD != 0L) bold else typeface
                        paint.textSkewX = if (flags and TermEmulator.ITALIC != 0L) -0.2f else 0f
                        paint.isUnderlineText = flags and TermEmulator.UNDERLINE != 0L
                        paint.isStrikeThruText = flags and TermEmulator.STRIKE != 0L
                        val text = String(Character.toChars(ch))
                        val measured = paint.measureText(text)
                        val target = w * cellW
                        if (measured > target * 1.05f) {
                            // Zeichen aus einer anderen Schrift, das breiter ist: ins Raster stauchen.
                            canvas.save()
                            canvas.scale(target / measured, 1f, x * cellW, top)
                            canvas.drawText(text, x * cellW, top + baseline, paint)
                            canvas.restore()
                        } else {
                            canvas.drawText(text, x * cellW + (target - measured) / 2f, top + baseline, paint)
                        }
                    }
                    x += w
                }
            }
            if (emu.cursorVisible && state.scroll == 0) {
                val cx = emu.cursorX * cellW
                val cy = emu.cursorY * cellH
                fill.color = cursorColor
                if (session.phase == Phase.Open) {
                    fill.alpha = 150
                    canvas.drawRect(cx, cy, cx + cellW, cy + cellH, fill)
                } else {
                    fill.style = Paint.Style.STROKE
                    fill.strokeWidth = 2f
                    canvas.drawRect(cx, cy, cx + cellW, cy + cellH, fill)
                }
            }
        }
    }
}

const val MIN_FONT = 8f
const val MAX_FONT = 32f

private fun colorOf(c: Int, default: Int, light: Boolean, bright: Boolean): Int = when {
    c == TermEmulator.DEFAULT_COLOR -> default
    c and TermEmulator.RGB_FLAG != 0 -> (0xFF shl 24) or (c and 0xFFFFFF)
    bright && c < 8 -> palette(c + 8, light)
    else -> palette(c, light)
}

/** Ungenutzt, aber für Tests der Farbumrechnung erreichbar. */
internal fun colorForTest(c: Int, light: Boolean): Int = colorOf(c, Color.Black.toArgb(), light, false)

/** Eine Taste der Hardware-Tastatur in Bytes für die Shell (null: nicht behandelt). */
fun hardwareKeyBytes(event: KeyEvent, appCursor: Boolean, stickyCtrl: Boolean, stickyAlt: Boolean): ByteArray? {
    val ctrl = event.isCtrlPressed || stickyCtrl
    val alt = event.isAltPressed || stickyAlt
    val shift = event.isShiftPressed
    termKeyOf(event.keyCode)?.let { return TermKeys.bytes(it, appCursor, shift, alt, ctrl) }
    val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
    val unicode = event.getUnicodeChar(meta)
    if (unicode == 0 || unicode and android.view.KeyCharacterMap.COMBINING_ACCENT != 0) return null
    return TermKeys.text(String(Character.toChars(unicode)), ctrl, alt)
}
