package io.github.ra5on.titanos.vms.screen

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ConsoleRequest
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.MenuEntry
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.vms.NoticeBox
import io.github.ra5on.titanos.vms.NoticeLevel
import io.github.ra5on.titanos.vms.OverlaySheet
import io.github.ra5on.titanos.vms.R
import io.github.ra5on.titanos.vms.Tool
import io.github.ra5on.titanos.vms.Vm
import io.github.ra5on.titanos.vms.VmApi
import io.github.ra5on.titanos.vms.s
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Anzeige wie im Web: einpassen, Auflösung der VM anpassen, 100 % mit Verschieben. */
enum class ScaleMode(val key: String) { Scale("scale"), Remote("remote"), Full("full") }

/** Finger als Trackpad mit Zeiger oder direkt an der Stelle (vnc-input.ts). */
enum class PointerMode(val key: String) { Trackpad("trackpad"), Direct("direct") }

/** Einstellungen des Bildschirms, auf diesem Gerät gespeichert (wie localStorage im Web). */
class ScreenPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("vms_screen", Context.MODE_PRIVATE)
    var mode: ScaleMode
        get() = ScaleMode.entries.firstOrNull { it.key == prefs.getString("mode", null) } ?: ScaleMode.Remote
        set(v) = prefs.edit().putString("mode", v.key).apply()
    var pointer: PointerMode
        get() = if (prefs.getString("pointer", null) == "direct") PointerMode.Direct else PointerMode.Trackpad
        set(v) = prefs.edit().putString("pointer", v.key).apply()
    var factor: Float
        get() = prefs.getFloat("factor", 1f)
        set(v) = prefs.edit().putFloat("factor", v).apply()
    var keyBar: Boolean
        get() = prefs.getBoolean("keybar", true)
        set(v) = prefs.edit().putBoolean("keybar", v).apply()
    var naturalScroll: Boolean
        get() = prefs.getBoolean("natural", true)
        set(v) = prefs.edit().putBoolean("natural", v).apply()
    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) = prefs.edit().putFloat("speed", v).apply()
}

/** Gehaltene Umschalter der Tastenleiste für die nächste Taste. */
data class Mods(val ctrl: Boolean = false, val alt: Boolean = false, val win: Boolean = false) {
    val any: Boolean get() = ctrl || alt || win
}

/** Tastenkombinationen, die Handy oder NAS sonst für sich behielten (KEYS in vm-page.ts). */
private val COMBOS: List<Pair<String, List<Int>>> = listOf(
    "Ctrl+Alt+Delete" to listOf(Keysyms.CONTROL, Keysyms.ALT, Keysyms.DELETE),
    "Ctrl+Alt+⌫" to listOf(Keysyms.CONTROL, Keysyms.ALT, Keysyms.BACKSPACE),
) + listOf(1, 2, 3, 7).map { n -> "Ctrl+Alt+F$n" to listOf(Keysyms.CONTROL, Keysyms.ALT, Keysyms.f(n)) } + listOf(
    "Alt+Tab" to listOf(Keysyms.ALT, Keysyms.TAB),
    "Alt+F4" to listOf(Keysyms.ALT, Keysyms.f(4)),
    "Win" to listOf(Keysyms.SUPER),
    "Print" to listOf(Keysyms.PRINT),
)

/** Trackpad: schnelle Bewegung bewegt den Zeiger weiter (accelerate im Web). */
fun accelerate(delta: Float, dtMs: Long): Float {
    val speed = abs(delta) / max(dtMs, 1L)
    val factor = if (speed < 0.2f) 1f else if (speed < 0.8f) 1.6f else 2.4f
    return delta * factor
}

/**
 * Der Bildschirm einer laufenden VM, nativ: eigener RFB-Client, Finger als
 * Maus, Bildschirm- und Hardware-Tastatur, Tastenleiste, Werkzeuge wie
 * ScreenToolbar.svelte und VncKeyBar.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmScreen(env: ComputeEnv, api: VmApi, vm: Vm, full: Boolean, onFull: () -> Unit, preset: ScreenSession? = null) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val ui = LocalComputeUi.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefs = remember { ScreenPrefs(context) }
    val session = remember(vm.name) { preset?.takeIf { it.vm == vm.name } ?: ScreenSession(api, vm.name) }
    var attempt by remember(vm.name) { mutableStateOf(0) }
    LaunchedEffect(session, attempt) { if (session !== preset || attempt > 0) session.connect() }
    DisposableEffect(session) { onDispose { session.close() } }

    var mode by remember { mutableStateOf(prefs.mode) }
    var pointer by remember { mutableStateOf(prefs.pointer) }
    var factor by remember { mutableFloatStateOf(prefs.factor) }
    var keyBar by remember { mutableStateOf(prefs.keyBar) }
    var natural by remember { mutableStateOf(prefs.naturalScroll) }
    var speed by remember { mutableFloatStateOf(prefs.speed) }
    var mods by remember { mutableStateOf(Mods()) }
    var fkeys by remember { mutableStateOf(false) }
    var zoom by remember(vm.name) { mutableFloatStateOf(1f) }
    var pan by remember(vm.name) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var cursorAt by remember(vm.name) { mutableStateOf(Offset(-1f, -1f)) }
    var typing by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val resizable = vm.template == "linux"
    val shownMode = if (mode == ScaleMode.Remote && !resizable) ScaleMode.Scale else mode
    val phase = session.phase
    val keyboardOpen = WindowInsets.isImeVisible

    // Vollbild: Leisten des Systems ausblenden, solange der Bildschirm groß ist.
    if (full) {
        DisposableEffect(Unit) {
            val window = context.findActivity()?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    // Auflösung anpassen: die Größe des Bildes in dp (× Faktor) als Auflösung des Gasts.
    LaunchedEffect(box, factor, shownMode, session.canResize, phase) {
        if (shownMode != ScaleMode.Remote || !session.canResize || phase != ScreenPhase.Connected || box.width <= 0) return@LaunchedEffect
        delay(400)
        val w = (box.width / density.density * factor).toInt()
        val h = (box.height / density.density * factor).toInt()
        session.rfb?.setDesktopSize(w, h)
    }
    LaunchedEffect(phase) { if (phase == ScreenPhase.Connected) runCatching { focus.requestFocus() } }

    fun showKeyboard() {
        runCatching { focus.requestFocus() }
        view.post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, 0) }
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /** Eine Taste mit den gehaltenen Umschaltern; die werden danach losgelassen. */
    fun press(keysym: Int, extra: Mods = Mods()) {
        val rfb = session.rfb ?: return
        val held = buildList {
            if (mods.ctrl || extra.ctrl) add(Keysyms.CONTROL)
            if (mods.alt || extra.alt) add(Keysyms.ALT)
            if (mods.win || extra.win) add(Keysyms.SUPER)
        }
        rfb.keys(held + keysym)
        if (mods.any) mods = Mods()
    }

    val sink = remember(session) {
        object : ScreenInputSink {
            override fun typed(text: String) {
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i)
                    press(Keysyms.forCodePoint(cp))
                    i += Character.charCount(cp)
                }
            }

            override fun key(keysym: Int) = press(keysym)

            override fun keyEvent(event: KeyEvent): Boolean {
                val sym = Keysyms.forKeyCode(event.keyCode) ?: event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv())
                    .takeIf { it > 0 }?.let { Keysyms.forCodePoint(it) } ?: return false
                press(sym, Mods(event.isCtrlPressed, event.isAltPressed, event.isMetaPressed))
                return true
            }
        }
    }
    // Hardware-Tastatur: Drücken und Loslassen einzeln, Umschalter als eigene Tasten.
    val pressed = remember { HashMap<Int, Int>() }
    fun hardwareKey(e: KeyEvent): Boolean {
        val rfb = session.rfb ?: return false
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        val sym = if (down) {
            (Keysyms.forKeyCode(e.keyCode) ?: e.getUnicodeChar(e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv())
                .takeIf { it > 0 }?.let { Keysyms.forCodePoint(it) })?.also { pressed[e.keyCode] = it }
        } else {
            pressed.remove(e.keyCode)
        } ?: return false
        rfb.key(sym, down)
        return true
    }

    // --- Umrechnung Bild ↔ Fläche ---
    val fw = session.width
    val fh = session.height
    fun baseScale(): Float = when {
        fw <= 0 || fh <= 0 || box.width <= 0 -> 1f
        shownMode == ScaleMode.Full -> density.density
        else -> min(box.width.toFloat() / fw, box.height.toFloat() / fh)
    }
    fun scaleNow() = baseScale() * zoom
    fun origin(): Offset {
        val sc = scaleNow()
        val cw = fw * sc
        val ch = fh * sc
        val ox = if (cw <= box.width) (box.width - cw) / 2 else -pan.x.coerceIn(0f, cw - box.width)
        val oy = if (ch <= box.height) (box.height - ch) / 2 else -pan.y.coerceIn(0f, ch - box.height)
        return Offset(ox, oy)
    }
    fun toFb(p: Offset): Pair<Int, Int> {
        val o = origin()
        val sc = scaleNow()
        return ((p.x - o.x) / sc).toInt().coerceIn(0, max(0, fw - 1)) to ((p.y - o.y) / sc).toInt().coerceIn(0, max(0, fh - 1))
    }
    fun fbToBox(x: Float, y: Float): Offset {
        val o = origin()
        val sc = scaleNow()
        return Offset(o.x + x * sc, o.y + y * sc)
    }
    /** Hält den Zeiger im sichtbaren Ausschnitt, wenn das Bild größer als die Fläche ist. */
    fun follow(fx: Float, fy: Float) {
        val sc = scaleNow()
        val cw = fw * sc
        val ch = fh * sc
        var px = pan.x
        var py = pan.y
        val margin = 24 * density.density
        if (cw > box.width) {
            val sx = fx * sc
            if (sx - px < margin) px = sx - margin
            if (sx - px > box.width - margin) px = sx - box.width + margin
            px = px.coerceIn(0f, cw - box.width)
        }
        if (ch > box.height) {
            val sy = fy * sc
            if (sy - py < margin) py = sy - margin
            if (sy - py > box.height - margin) py = sy - box.height + margin
            py = py.coerceIn(0f, ch - box.height)
        }
        pan = Offset(px, py)
    }

    val currentPointer by rememberUpdatedState(pointer)
    val currentNatural by rememberUpdatedState(natural)
    val currentSpeed by rememberUpdatedState(speed)
    var lastTap by remember { mutableStateOf(Triple(0L, 0f, 0f)) }
    val label = s(R.string.vms_screen_label, vm.label)
    val phaseText = when (phase) {
        ScreenPhase.Connected -> s(R.string.vms_screen_connected)
        ScreenPhase.Connecting -> t("vms.screenConnecting")
        ScreenPhase.Disabled -> s(R.string.vms_screen_disabled)
        is ScreenPhase.Ended -> s(R.string.vms_screen_ended)
    }
    val screenBg = Color(0xFF0B0F14)

    Column(Modifier.fillMaxSize()) {
        if (!full) Toolbar(
            vm = vm,
            shownMode = shownMode,
            resizable = resizable,
            connected = phase == ScreenPhase.Connected,
            onMode = { mode = it; prefs.mode = it; zoom = 1f; pan = Offset.Zero },
            onCombo = { session.rfb?.keys(it) },
            onPaste = { pasting = true },
            onType = { typing = true },
            guestClipboard = session.guestClipboard,
            onCopyGuest = { clipboard.setText(AnnotatedString(session.guestClipboard)); ui.toast(ui.string(R.string.vms_copied)) },
            onScreenshot = { saveScreenshot(context, session.bitmap, vm.name)?.let { ui.toast(ui.string(R.string.vms_screenshot_saved, it)) } ?: ui.toast(ui.string(R.string.vms_screenshot_failed)) },
            onSerial = { env.openConsole(ConsoleRequest.vm(vm.name, vm.label)) },
            onFull = onFull,
            onSettings = {
                ui.sheet = {
                    ScreenSettings(
                        factor, { factor = it; prefs.factor = it }, keyBar, { keyBar = it; prefs.keyBar = it },
                        natural, { natural = it; prefs.naturalScroll = it }, speed, { speed = it; prefs.speed = it },
                    )
                }
            },
            onReconnect = { attempt++ },
        )
        if (session.resizeFailed != 0) NoticeBox(NoticeLevel.Warn, t("vms.size.failed"), Modifier.padding(horizontal = 8.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(if (full) 0.dp else 10.dp)).background(screenBg)
                .onSizeChanged { box = it }
                .semantics {
                    contentDescription = label
                    stateDescription = phaseText
                }
                .screenInput(view, sink)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown && e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    hardwareKey(e.nativeKeyEvent)
                }
                // Maus und Stift (Desktop-Modus): direkt, mit Tasten und Rad.
                .pointerInput(session, fw, fh, box, zoom, shownMode) {
                    awaitPointerEventScope {
                        var wheelAcc = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: continue
                            if (change.type != PointerType.Mouse && change.type != PointerType.Stylus) continue
                            val rfb = session.rfb ?: continue
                            val (x, y) = toFb(change.position)
                            cursorAt = Offset(x.toFloat(), y.toFloat())
                            if (ev.type == PointerEventType.Scroll) {
                                wheelAcc += change.scrollDelta.y
                                while (abs(wheelAcc) >= 1f) {
                                    rfb.wheel(x, y, 0, if (wheelAcc > 0) 1 else -1)
                                    wheelAcc -= if (wheelAcc > 0) 1f else -1f
                                }
                                val dx = change.scrollDelta.x
                                if (abs(dx) >= 1f) rfb.wheel(x, y, if (dx > 0) 1 else -1, 0)
                            } else {
                                val b = ev.buttons
                                val mask = (if (b.isPrimaryPressed) 1 else 0) or (if (b.isTertiaryPressed) 2 else 0) or (if (b.isSecondaryPressed) 4 else 0)
                                rfb.pointer(x, y, mask)
                                if (ev.type == PointerEventType.Press) runCatching { focus.requestFocus() }
                            }
                            change.consume()
                        }
                    }
                }
                // Finger: Trackpad oder direkt, zwei Finger Rechtsklick/Scrollen, Spreizen zoomt.
                .pointerInput(session, fw, fh, box, shownMode) {
                    val slop = 8 * density.density
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        if (first.type == PointerType.Mouse || first.type == PointerType.Stylus) return@awaitEachGesture
                        val rfb = session.rfb
                        val trackpad = currentPointer == PointerMode.Trackpad
                        val started = first.uptimeMillis
                        var gesture = if (trackpad && started - lastTap.first < 320 && hypot(first.position.x - lastTap.second, first.position.y - lastTap.third) < 40 * density.density) "drag" else "one"
                        var moved = 0f
                        var dragging = false
                        var last = first.position
                        var lastTime = started
                        var startSpan = 0f
                        var startZoom = zoom
                        var lastCentroid = Offset.Zero
                        var wheelAcc = Offset.Zero
                        var twoMoved = 0f
                        var twoFingers = false
                        var upTime = started
                        if (trackpad && cursorAt.x < 0) cursorAt = Offset(fw / 2f, fh / 2f)
                        if (!trackpad && rfb != null) {
                            val (x, y) = toFb(first.position)
                            cursorAt = Offset(x.toFloat(), y.toFloat())
                            rfb.pointer(x, y, 0)
                        }
                        first.consume()
                        while (true) {
                            val waitLong = !trackpad && !twoFingers && !dragging && moved < slop && gesture == "one"
                            val remaining = started + 520 - upTime
                            val ev = if (waitLong) {
                                if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (ev == null) {
                                // Direkt: lange drücken = Rechtsklick.
                                val (x, y) = toFb(last)
                                rfb?.click(x, y, 2)
                                gesture = "done"
                                continue
                            }
                            upTime = ev.changes.maxOf { it.uptimeMillis }
                            val down = ev.changes.filter { it.pressed }
                            ev.changes.forEach { it.consume() }
                            if (down.isEmpty()) break
                            if (gesture == "done") continue
                            if (down.size >= 2) {
                                if (!twoFingers) {
                                    twoFingers = true
                                    if (dragging) {
                                        dragging = false
                                        val (x, y) = toFb(last)
                                        rfb?.pointer(x, y, 0)
                                    }
                                    startSpan = hypot(down[0].position.x - down[1].position.x, down[0].position.y - down[1].position.y)
                                    startZoom = zoom
                                    lastCentroid = Offset((down[0].position.x + down[1].position.x) / 2, (down[0].position.y + down[1].position.y) / 2)
                                    gesture = "two"
                                }
                                val centroid = Offset((down[0].position.x + down[1].position.x) / 2, (down[0].position.y + down[1].position.y) / 2)
                                val span = hypot(down[0].position.x - down[1].position.x, down[0].position.y - down[1].position.y)
                                val delta = centroid - lastCentroid
                                lastCentroid = centroid
                                twoMoved += delta.getDistance()
                                if (gesture != "zoom" && startSpan > 0 && abs(span / startSpan - 1f) > 0.12f) gesture = "zoom"
                                if (gesture == "zoom" && startSpan > 0) {
                                    val oldScale = scaleNow()
                                    zoom = (startZoom * span / startSpan).coerceIn(1f, 8f)
                                    val newScale = baseScale() * zoom
                                    // Der Punkt unter den Fingern bleibt stehen.
                                    val o = origin()
                                    val fx = (centroid.x - o.x) / oldScale
                                    val fy = (centroid.y - o.y) / oldScale
                                    pan = Offset((fx * newScale - centroid.x).coerceAtLeast(0f), (fy * newScale - centroid.y).coerceAtLeast(0f))
                                } else if (twoMoved > 10 * density.density) {
                                    gesture = "scroll"
                                    val bigger = fw * scaleNow() > box.width + 1 || fh * scaleNow() > box.height + 1
                                    if (bigger) {
                                        pan = Offset(pan.x - delta.x, pan.y - delta.y)
                                    } else if (rfb != null) {
                                        wheelAcc += delta
                                        val step = 30 * density.density
                                        val (x, y) = if (trackpad) cursorAt.x.toInt() to cursorAt.y.toInt() else toFb(centroid)
                                        val dir = if (currentNatural) -1 else 1
                                        while (abs(wheelAcc.y) >= step) {
                                            rfb.wheel(x, y, 0, if (wheelAcc.y > 0) -dir else dir)
                                            wheelAcc = wheelAcc.copy(y = wheelAcc.y - if (wheelAcc.y > 0) step else -step)
                                        }
                                        while (abs(wheelAcc.x) >= step) {
                                            rfb.wheel(x, y, if (wheelAcc.x > 0) -dir else dir, 0)
                                            wheelAcc = wheelAcc.copy(x = wheelAcc.x - if (wheelAcc.x > 0) step else -step)
                                        }
                                    }
                                }
                                continue
                            }
                            if (twoFingers) continue
                            val ch = down[0]
                            val d = ch.position - last
                            val dt = ch.uptimeMillis - lastTime
                            last = ch.position
                            lastTime = ch.uptimeMillis
                            moved += d.getDistance()
                            if (rfb == null) continue
                            if (trackpad) {
                                if (moved < 4 * density.density) continue
                                val sc = scaleNow()
                                cursorAt = Offset(
                                    (cursorAt.x + accelerate(d.x, dt) * currentSpeed / sc).coerceIn(0f, max(0, fw - 1).toFloat()),
                                    (cursorAt.y + accelerate(d.y, dt) * currentSpeed / sc).coerceIn(0f, max(0, fh - 1).toFloat()),
                                )
                                follow(cursorAt.x, cursorAt.y)
                                if (gesture == "drag" && !dragging) {
                                    dragging = true
                                    rfb.pointer(cursorAt.x.toInt(), cursorAt.y.toInt(), 1)
                                }
                                rfb.pointer(cursorAt.x.toInt(), cursorAt.y.toInt(), if (dragging) 1 else 0)
                            } else {
                                if (moved < slop) continue
                                val (x, y) = toFb(ch.position)
                                cursorAt = Offset(x.toFloat(), y.toFloat())
                                if (!dragging) {
                                    dragging = true
                                    val (sx, sy) = toFb(first.position)
                                    rfb.pointer(sx, sy, 1)
                                }
                                rfb.pointer(x, y, 1)
                            }
                        }
                        val quick = upTime - started < 300
                        when {
                            gesture == "done" -> Unit
                            twoFingers -> if (gesture == "two" && twoMoved < 10 * density.density) {
                                val (x, y) = if (trackpad) cursorAt.x.toInt() to cursorAt.y.toInt() else toFb(lastCentroid)
                                rfb?.click(x, y, 2)
                            }
                            dragging -> {
                                val (x, y) = if (trackpad) cursorAt.x.toInt() to cursorAt.y.toInt() else toFb(last)
                                rfb?.pointer(x, y, 0)
                            }
                            moved < slop && quick -> {
                                val (x, y) = if (trackpad) cursorAt.x.toInt() to cursorAt.y.toInt() else toFb(last)
                                rfb?.click(x, y, 0)
                                if (trackpad) lastTap = Triple(lastTime, last.x, last.y)
                            }
                        }
                    }
                },
        ) {
            val frame = session.frame
            Canvas(Modifier.fillMaxSize()) {
                frame.hashCode()
                val bmp = session.bitmap ?: return@Canvas
                val o = origin()
                val sc = scaleNow()
                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas
                    nc.save()
                    nc.translate(o.x, o.y)
                    nc.scale(sc, sc)
                    nc.drawBitmap(bmp, 0f, 0f, Paint(if (sc < 1f) Paint.FILTER_BITMAP_FLAG else 0))
                    nc.restore()
                    // Der Zeiger: im Trackpad immer, sonst an der letzten Stelle.
                    val cur = session.cursor
                    if (cursorAt.x >= 0) {
                        val p = fbToBox(cursorAt.x, cursorAt.y)
                        if (cur != null) {
                            val (hx, hy) = session.cursorHot
                            nc.save()
                            nc.translate(p.x - hx * sc, p.y - hy * sc)
                            nc.scale(sc, sc)
                            nc.drawBitmap(cur, 0f, 0f, null)
                            nc.restore()
                        } else if (currentPointer == PointerMode.Trackpad) {
                            drawCircle(Color.White, 6.dp.toPx(), p)
                            drawCircle(Color.Black, 6.dp.toPx(), p, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
            if (phase != ScreenPhase.Connected) {
                Column(
                    Modifier.align(Alignment.Center).padding(16.dp).widthIn(max = 520.dp).clip(RoundedCornerShape(12.dp)).background(Titan.colors.surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (phase) {
                        ScreenPhase.Connecting -> Text(t("vms.screenConnecting"), style = TitanType.body, color = Titan.colors.text, textAlign = TextAlign.Center)
                        ScreenPhase.Disabled -> {
                            Text(s(R.string.vms_screen_disabled), style = TitanType.body, color = Titan.colors.text, textAlign = TextAlign.Center)
                            TitanButton(s(R.string.vms_reconnect), { attempt++ }, kind = ButtonKind.Primary, icon = TitanIcons.restart)
                        }
                        is ScreenPhase.Ended -> {
                            Text(endedText(phase), style = TitanType.body, color = Titan.colors.text, textAlign = TextAlign.Center)
                            TitanButton(s(R.string.vms_reconnect), { attempt++ }, kind = ButtonKind.Primary, icon = TitanIcons.restart)
                        }
                        else -> Unit
                    }
                }
            }
            if (full) {
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.x, t("vms.touch.fullClose"), onFull, tint = Color.White)
                }
            }
        }
        if (keyBar && phase == ScreenPhase.Connected) {
            KeyBar(
                keyboardOpen = keyboardOpen,
                onKeyboard = { if (keyboardOpen) hideKeyboard() else showKeyboard() },
                mods = mods,
                onMods = { mods = it },
                fkeys = fkeys,
                onFkeys = { fkeys = !fkeys },
                onKey = { press(it) },
                onCad = { session.rfb?.keys(listOf(Keysyms.CONTROL, Keysyms.ALT, Keysyms.DELETE)) },
                full = full,
                onFull = onFull,
                pointer = pointer,
                onPointer = { pointer = it; prefs.pointer = it },
                zoomed = zoom > 1.01f,
                onZoomReset = { zoom = 1f; pan = Offset.Zero },
                compact = full,
            )
        }
        if (!full) {
            Text(
                s(R.string.vms_screen_idle_hint),
                style = TitanType.small,
                color = Titan.colors.muted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }

    if (pasting) {
        TextSheet(t("vms.dialog.paste", "name" to vm.label), t("vms.pasteText"), t("vms.submit.paste"), onClose = { pasting = false }) {
            session.rfb?.clipboard(it)
            pasting = false
        }
    }
    if (typing) {
        TextSheet(s(R.string.vms_type_text_title, vm.label), s(R.string.vms_type_text_hint), s(R.string.vms_type_text_send), onClose = { typing = false }) {
            session.rfb?.type(it)
            typing = false
        }
    }
}

@Composable
private fun endedText(phase: ScreenPhase.Ended): String = when {
    phase.error.isNotEmpty() -> s(R.string.vms_screen_error, phase.error)
    !phase.opened && phase.status == 409 -> t("error.vm_not_running")
    !phase.opened && phase.status == 403 -> s(R.string.vms_screen_disabled)
    !phase.opened -> s(R.string.vms_screen_refused)
    phase.reason.contains("switched off") -> s(R.string.vms_screen_disabled)
    phase.reason.contains("login") -> s(R.string.vms_screen_login_ended)
    else -> s(R.string.vms_screen_ended_text)
}

/** Werkzeuge über dem Bild (ScreenToolbar.svelte). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(
    vm: Vm,
    shownMode: ScaleMode,
    resizable: Boolean,
    connected: Boolean,
    onMode: (ScaleMode) -> Unit,
    onCombo: (List<Int>) -> Unit,
    onPaste: () -> Unit,
    onType: () -> Unit,
    guestClipboard: String,
    onCopyGuest: () -> Unit,
    onScreenshot: () -> Unit,
    onSerial: () -> Unit,
    onFull: () -> Unit,
    onSettings: () -> Unit,
    onReconnect: () -> Unit,
) {
    val ui = LocalComputeUi.current
    val names = mapOf("Ctrl" to t("shortcuts.ctrl"), "Alt" to t("shortcuts.alt"), "Delete" to s(R.string.vms_key_delete), "Print" to s(R.string.vms_key_print), "Win" to s(R.string.vms_key_win))
    val comboLabel = { label: String -> label.split('+').joinToString("+") { names[it] ?: it } }
    val clipText = t("vms.clipboard")
    val typeText = s(R.string.vms_type_text)
    val copyGuest = s(R.string.vms_guest_clipboard)
    val modeTexts = ScaleMode.entries.associateWith { t("vms.mode.${it.key}") }
    val modeHints = ScaleMode.entries.associateWith { if (it == ScaleMode.Remote && !resizable) t("vms.mode.remote.unsupported") else t("vms.mode.${it.key}.hint") }
    val sendTitle = t("vms.send")
    val displayTitle = t("vms.display")
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Tool(t("vms.send"), TitanIcons.key, {
            ui.menu(sendTitle, COMBOS.map { (label, keys) -> MenuEntry(comboLabel(label)) { onCombo(keys) } } +
                listOfNotNull(
                    MenuEntry("$clipText …", TitanIcons.edit, onClick = onPaste),
                    MenuEntry(typeText, TitanIcons.edit, onClick = onType),
                    MenuEntry(copyGuest, TitanIcons.file, onClick = onCopyGuest).takeIf { guestClipboard.isNotEmpty() },
                ))
        }, enabled = connected)
        Tool("${t("vms.display")}: ${modeTexts[shownMode]}", TitanIcons.monitor, {
            ui.menu(displayTitle, ScaleMode.entries.map { m ->
                MenuEntry((if (m == shownMode) "✓ " else "") + modeTexts[m] + " – " + modeHints[m], enabled = m != ScaleMode.Remote || resizable) { onMode(m) }
            })
        })
        Tool(t("vms.screenshot"), TitanIcons.photo, onScreenshot, enabled = connected)
        Tool(t("vms.serial"), TitanIcons.key, onSerial)
        Tool(t("vms.fullscreen"), TitanIcons.grid, onFull, enabled = connected)
        Tool(s(R.string.vms_reconnect), TitanIcons.restart, onReconnect)
        Tool(s(R.string.vms_screen_settings), TitanIcons.gear, onSettings)
    }
}

/** Tastenleiste unter dem Bild (VncKeyBar.svelte), dazu Win halten und Zoom zurück. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyBar(
    keyboardOpen: Boolean,
    onKeyboard: () -> Unit,
    mods: Mods,
    onMods: (Mods) -> Unit,
    fkeys: Boolean,
    onFkeys: () -> Unit,
    onKey: (Int) -> Unit,
    onCad: () -> Unit,
    full: Boolean,
    onFull: () -> Unit,
    pointer: PointerMode,
    onPointer: (PointerMode) -> Unit,
    zoomed: Boolean,
    onZoomReset: () -> Unit,
    compact: Boolean,
) {
    val held = t("vms.touch.held")
    Column(
        Modifier.fillMaxWidth().then(if (compact) Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()) else Modifier)
            .padding(top = 6.dp).semantics { contentDescription = "" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton(t("vms.touch.keyboard"), onKeyboard, on = keyboardOpen, icon = TitanIcons.edit)
            KeyButton(t("vms.touch.esc"), { onKey(Keysyms.ESCAPE) })
            KeyButton(t("vms.touch.tab"), { onKey(Keysyms.TAB) })
            KeyButton(t("shortcuts.ctrl"), { onMods(mods.copy(ctrl = !mods.ctrl)) }, on = mods.ctrl, extra = if (mods.ctrl) held else null)
            KeyButton(t("shortcuts.alt"), { onMods(mods.copy(alt = !mods.alt)) }, on = mods.alt, extra = if (mods.alt) held else null)
            KeyButton(s(R.string.vms_key_win), { onMods(mods.copy(win = !mods.win)) }, on = mods.win, extra = if (mods.win) held else null)
            KeyButton(t("vms.touch.fkeys"), onFkeys, on = fkeys)
        }
        if (fkeys) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in 1..12) KeyButton("F$n", { onKey(Keysyms.f(n)) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton("←", { onKey(Keysyms.LEFT) }, description = t("vms.touch.left"))
            KeyButton("↑", { onKey(Keysyms.UP) }, description = t("vms.touch.up"))
            KeyButton("↓", { onKey(Keysyms.DOWN) }, description = t("vms.touch.down"))
            KeyButton("→", { onKey(Keysyms.RIGHT) }, description = t("vms.touch.right"))
            KeyButton(s(R.string.vms_key_home), { onKey(Keysyms.HOME) })
            KeyButton(s(R.string.vms_key_end), { onKey(Keysyms.END) })
            KeyButton(s(R.string.vms_key_pgup), { onKey(Keysyms.PAGE_UP) })
            KeyButton(s(R.string.vms_key_pgdn), { onKey(Keysyms.PAGE_DOWN) })
            KeyButton(s(R.string.vms_key_delete), { onKey(Keysyms.DELETE) })
            KeyButton(t("vms.ctrlAltDel"), onCad)
            KeyButton(t(if (full) "vms.touch.fullClose" else "vms.touch.full"), onFull, on = full, icon = TitanIcons.grid)
            if (zoomed) KeyButton(s(R.string.vms_zoom_reset), onZoomReset, icon = TitanIcons.search)
        }
        Text(t("vms.touch.mode"), style = TitanType.small, color = Titan.colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in PointerMode.entries) {
                Box(Modifier.weight(1f)) {
                    KeyButton(t("vms.touch.${m.key}"), { onPointer(m) }, on = pointer == m, fill = true, radio = true)
                }
            }
        }
        Text(
            t(if (pointer == PointerMode.Trackpad) "vms.touch.trackpadHint" else "vms.touch.directHint") + " " + s(R.string.vms_touch_zoom_hint),
            style = TitanType.small,
            color = Titan.colors.muted,
        )
    }
}

@Composable
private fun KeyButton(
    text: String,
    onClick: () -> Unit,
    on: Boolean = false,
    extra: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    description: String? = null,
    fill: Boolean = false,
    radio: Boolean = false,
) {
    val c = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    val onText = s(R.string.vms_on_state)
    Row(
        Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).heightIn(min = 46.dp).widthIn(min = 48.dp).clip(shape)
            .background(if (on) c.accent.copy(alpha = .22f) else c.chip).border(1.dp, if (on) c.accent else c.border, shape)
            .clickable(role = if (radio) Role.RadioButton else Role.Button, onClick = onClick)
            .semantics {
                if (description != null) contentDescription = description
                if (radio) selected = on else if (on) stateDescription = onText
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = c.text, modifier = Modifier.size(18.dp))
        Text(text, style = TitanType.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = c.text, textAlign = TextAlign.Center)
        if (extra != null) Text(extra, style = TitanType.small, color = c.muted)
    }
}

/** Text in die VM: als Zwischenablage oder Zeichen für Zeichen getippt. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextSheet(title: String, hint: String, submit: String, onClose: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    OverlaySheet(onClose) {
        Text(title, style = TitanType.rowTitle, color = Titan.colors.text)
        Text(hint, style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 6.dp))
        Field(title, text, { text = it })
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TitanButton(t("common.cancel"), onClose)
            TitanButton(submit, { onSend(text) }, kind = ButtonKind.Primary, enabled = text.isNotEmpty())
        }
    }
}

/** Weitere Einstellungen des Bildschirms (auf diesem Gerät). */
@Composable
private fun ScreenSettings(
    factor: Float,
    onFactor: (Float) -> Unit,
    keyBar: Boolean,
    onKeyBar: (Boolean) -> Unit,
    natural: Boolean,
    onNatural: (Boolean) -> Unit,
    speed: Float,
    onSpeed: (Float) -> Unit,
) {
    var f by remember { mutableFloatStateOf(factor) }
    var k by remember { mutableStateOf(keyBar) }
    var n by remember { mutableStateOf(natural) }
    var sp by remember { mutableFloatStateOf(speed) }
    Text(s(R.string.vms_screen_settings), style = TitanType.rowTitle, color = Titan.colors.text)
    Choice(
        s(R.string.vms_resolution_factor),
        listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).map { it.toString() to "${it}×" },
        f.toString(),
        { f = it.toFloat(); onFactor(f) },
        hint = s(R.string.vms_resolution_factor_hint),
    )
    Choice(
        s(R.string.vms_pointer_speed),
        listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f).map { it.toString() to "${it}×" },
        sp.toString(),
        { sp = it.toFloat(); onSpeed(sp) },
    )
    io.github.ra5on.titanos.compute.SwitchRow(s(R.string.vms_keybar), k, { k = it; onKeyBar(it) })
    io.github.ra5on.titanos.compute.SwitchRow(s(R.string.vms_natural_scroll), n, { n = it; onNatural(it) }, sub = s(R.string.vms_natural_scroll_hint))
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Speichert das Bild unter Bilder/TitanOS; gibt den Dateinamen zurück. */
private fun saveScreenshot(context: Context, bitmap: Bitmap?, vm: String): String? {
    val bmp = bitmap ?: return null
    val name = "$vm-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.png"
    return runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TitanOS")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name
    }.getOrNull()
}
