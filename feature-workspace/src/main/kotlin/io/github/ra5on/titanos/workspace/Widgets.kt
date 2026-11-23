package io.github.ra5on.titanos.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** Zelle des Widget-Rasters (CELL im Web: 20 px). */
const val CELL = 20

/** Breite und Höhe in Zellen je Art und Größe (widgetDims im Web). */
fun widgetDims(kind: String, size: String): Pair<Int, Int> {
    val table = when {
        kind in GPU_WIDGET_KINDS -> mapOf("s" to (15 to 17), "m" to (17 to 17), "l" to (24 to 18))
        kind == "camera" -> mapOf("s" to (14 to 10), "m" to (20 to 13), "l" to (30 to 19))
        else -> mapOf("s" to (12 to 5), "m" to (17 to 8), "l" to (24 to 12))
    }
    return table[size] ?: table.getValue("m")
}

/** Größe ändern (resizeWidget): rechts verankert bleibt rechts verankert. */
internal fun resizeWidget(state: WorkspaceState, id: String, size: String) {
    state.store.change("widgets", "customWidgets") { p ->
        p.copy(
            widgets = p.widgets.map { w ->
                if (w.id != id) return@map w
                val (dw, dh) = widgetDims(w.kind, size)
                w.copy(x = if (w.x < 0) -dw else w.x, w = dw, h = maxOf(w.h, dh))
            },
            customWidgets = true,
        )
    }
}

/** Ein neues Widget an der ersten freien Stelle (addWidget). */
internal fun addWidget(state: WorkspaceState, kind: String, size: String = "m") {
    val columns = maxOf(10, state.wm.area.w / CELL)
    val (w, h) = widgetDims(kind, size)
    val grid = iconGrid(state.store.prefs, state.wm.area, state.wm.inset)
    val (x, y) = placeWidget(state.store.prefs.widgets, w, h, columns, minX = (grid.left + grid.cols * grid.colW) / CELL)
    state.store.change("widgets", "customWidgets") { p ->
        p.copy(widgets = p.widgets + Widget("w" + System.currentTimeMillis().toString(36), kind, x, y, w, h), customWidgets = true)
    }
}

/**
 * Die Widgets auf dem Arbeitsplatz (DesktopWidgets.svelte). Im Bearbeiten:
 * verschieben, an der Ecke vergrößern, mit ✕ entfernen; sonst öffnet ein
 * Tippen das zugehörige Programm.
 */
@Composable
internal fun BoxScope.DeskWidgets(state: WorkspaceState) {
    val prefs = state.store.prefs
    if (prefs.widgetsHidden) return
    val wm = state.wm
    // Schmaler als die PC-Aufteilung des Webs (900): kein Platz neben den Symbolen, die Widgets ruhen (Einstellung bleibt).
    if (wm.area.w < 900) return
    val columns = maxOf(10, wm.area.w / CELL)
    val density = LocalDensity.current
    // Große Schrift: Widgets wachsen in der Höhe mit, damit nichts abgeschnitten wird.
    val tall = density.fontScale.coerceIn(1f, 2f)
    for (w in prefs.widgets) {
        var live by remember(w.id) { mutableStateOf<Widget?>(null) }
        val shown = live ?: w
        val left = widgetColumn(shown, columns) * CELL
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier
                .offset { IntOffset((left * density.density).roundToInt(), (shown.y * CELL * tall * density.density).roundToInt()) }
                .size((shown.w * CELL - 10).dp, (shown.h * CELL * tall - 10).dp)
                .glass(Titan.colors.surface, shape, alpha = .82f)
                .onSecondaryClickAt { _, _ -> state.menu = DeskMenu(left + 20f, shown.y * CELL + 20f, DeskMenu.Kind.Widget, w.id) }
                .then(
                    if (state.editWidgets) {
                        Modifier.border(2.dp, Titan.colors.accent, shape).pointerInput(w.id, columns) {
                            var acc = Offset.Zero
                            detectDragGestures(
                                onDragStart = { acc = Offset.Zero },
                                onDrag = { ch, d ->
                                    ch.consume()
                                    acc += d
                                    val dx = (acc.x / density.density / CELL).roundToInt()
                                    val dy = (acc.y / density.density / CELL).roundToInt()
                                    val x0 = widgetColumn(w, columns)
                                    live = w.copy(x = (x0 + dx).coerceIn(0, maxOf(0, columns - w.w)), y = maxOf(0, w.y + dy))
                                },
                                onDragEnd = { live?.let { commitWidget(state, it, columns) }; live = null },
                                onDragCancel = { live = null },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(12.dp),
        ) {
            WidgetBody(state, shown)
            if (state.editWidgets) {
                val remove = t("desktop.widgets.remove")
                Box(
                    Modifier.align(Alignment.TopEnd).size(28.dp).clip(CircleShape).background(Titan.colors.chip)
                        .clickable(role = Role.Button) { state.store.change("widgets", "customWidgets") { p -> p.copy(widgets = p.widgets.filter { it.id != w.id }, customWidgets = true) } }
                        .semantics { contentDescription = remove },
                    contentAlignment = Alignment.Center,
                ) { Icon(TitanIcons.x, null, tint = Titan.colors.text, modifier = Modifier.size(14.dp)) }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(22.dp).clip(RoundedCornerShape(6.dp)).background(Titan.colors.accent.copy(alpha = .6f))
                        .pointerInput(w.id, columns) {
                            var acc = Offset.Zero
                            detectDragGestures(
                                onDragStart = { acc = Offset.Zero },
                                onDrag = { ch, d ->
                                    ch.consume()
                                    acc += d
                                    val dx = (acc.x / density.density / CELL).roundToInt()
                                    val dy = (acc.y / density.density / CELL).roundToInt()
                                    val x0 = widgetColumn(w, columns)
                                    live = w.copy(x = x0, w = (w.w + dx).coerceIn(6, maxOf(6, columns - x0)), h = maxOf(3, w.h + dy))
                                },
                                onDragEnd = { live?.let { commitWidget(state, it, columns) }; live = null },
                                onDragCancel = { live = null },
                            )
                        },
                )
            }
        }
    }
}

/** Nahe am rechten Rand bleibt ein Widget dort verankert, wenn die Fläche wächst (wie im Web). */
private fun commitWidget(state: WorkspaceState, w: Widget, columns: Int) {
    val anchored = if (w.x >= 0 && w.x + w.w >= columns - 1) w.copy(x = -w.w - maxOf(0, columns - w.x - w.w)) else w
    state.store.change("widgets", "customWidgets") { p -> p.copy(widgets = p.widgets.map { if (it.id == w.id) anchored else it }, customWidgets = true) }
}

/** Innere Kachel eines Widgets (`.in` im Web). */
private fun Modifier.tile(bg: Color) = clip(RoundedCornerShape(10.dp)).background(bg.copy(alpha = .45f)).padding(12.dp)

@Composable
private fun Title(text: String) {
    Text(text, style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.muted, modifier = Modifier.padding(bottom = 6.dp).semantics { heading() })
}

@Composable
private fun Muted(text: String) = Text(text, style = TitanType.small, color = Titan.colors.muted)

/** Balken mit Wert für TalkBack (role meter im Web). */
@Composable
private fun Meter(fraction: Double, label: String) {
    val c = Titan.colors
    Box(
        Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.track)
            .semantics { contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(fraction.toFloat().coerceIn(0f, 1f), 0f..1f) },
    ) {
        Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).height(6.dp).background(c.accent))
    }
}

/** Zustand mit Wort, nie nur Farbe: ● läuft / ○ gestoppt. */
@Composable
private fun RunState(running: Boolean) {
    val c = Titan.colors
    Text(
        (if (running) "● " else "○ ") + t(if (running) "desktop.widget.running" else "desktop.widget.stopped"),
        style = TitanType.small,
        color = if (running) c.okInk else c.muted,
    )
}

@Composable
private fun ListRowButton(label: String, running: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = TitanType.body, color = Titan.colors.text, modifier = Modifier.weight(1f))
        RunState(running)
    }
}

/** Was ein Widget zeigt (WidgetView.svelte). */
@Composable
internal fun WidgetBody(state: WorkspaceState, w: Widget) {
    val data = state.data
    val c = Titan.colors
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val scroll = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    when (w.kind) {
        "status" -> Column(scroll) {
            val problems = data.problems
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(if (problems > 0) c.warnBg else c.okBg), contentAlignment = Alignment.Center) {
                    Text(if (problems > 0) "!" else "✓", style = TitanType.rowTitle, color = if (problems > 0) c.warnInk else c.okInk)
                }
                Column(Modifier.weight(1f)) {
                    Text(t(if (problems > 0) "desktop.widget.attention" else "desktop.widget.allGood"), style = TitanType.rowTitle, color = c.text)
                    Muted(if (problems > 0) t("desktop.widget.volumesAttention", "count" to problems) else t("desktop.widget.allGoodText"))
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f).tile(c.bg)) {
                    Text(data.uptime?.let { duration(it) } ?: "–", style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Muted(t("desktop.widget.uptime"))
                }
                Column(Modifier.weight(1f).tile(c.bg)) {
                    val n = data.nets.firstOrNull()
                    Text(n?.ipv4?.ifEmpty { "–" } ?: "–", style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    if (n != null) Muted(n.iface + if (n.speedMbps > 0) " · " + (if (n.speedMbps >= 1000) "${n.speedMbps / 1000} Gbit/s" else "${n.speedMbps} Mbit/s") else "")
                }
            }
        }
        "cpuram" -> Row(scroll, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).tile(c.bg)) {
                Muted(t("desktop.widget.cpu"))
                Text(data.cpu?.let { Format.percent(it / 100) } ?: "–", style = TitanType.state, color = c.text)
                Meter((data.cpu ?: 0.0) / 100, t("desktop.widget.cpu"))
            }
            Column(Modifier.weight(1f).tile(c.bg)) {
                Muted(t("desktop.widget.ram"))
                Text(Format.percent(data.memFraction), style = TitanType.state, color = c.text)
                if (data.memTotal > 0) Muted(Format.bytes(data.memUsed))
                Meter(data.memFraction, t("desktop.widget.ram"))
            }
        }
        "network" -> Column(scroll) {
            Title(t("desktop.widget.network"))
            val hist = data.history
            val max = maxOf(1.0, hist.maxOfOrNull { maxOf(it.first, it.second) } ?: 1.0)
            val rxColor = c.accent
            val txColor = Color(0xFFA78BFA)
            Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "↓ " + Format.bytes(data.rx.toLong()) + "/s · ↑ " + Format.bytes(data.tx.toLong()) + "/s" }) {
                fun line(pick: (Pair<Double, Double>) -> Double): Path = Path().apply {
                    hist.forEachIndexed { i, h ->
                        val x = size.width * i / 59f
                        val y = size.height - (pick(h) / max).toFloat() * (size.height - 4)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(line { it.first }, rxColor, style = Stroke(2.dp.toPx()))
                drawPath(line { it.second }, txColor, style = Stroke(2.dp.toPx()))
            }
            Muted("↓ " + Format.bytes(data.rx.toLong()) + "/s · ↑ " + Format.bytes(data.tx.toLong()) + "/s")
        }
        "volumes" -> Column(scroll) {
            Title(t("desktop.widget.volumes"))
            val vols = data.volumes.orEmpty()
            if (vols.isEmpty()) Muted(t("desktop.widget.noVolumes"))
            for (v in vols) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { state.open("app:storage") }.padding(vertical = 4.dp)) {
                    val (used, total) = Format.bytesOf(v.used, v.size)
                    Row {
                        Text(v.name + " · " + v.level.uppercase().replace("RAID", "RAID "), style = TitanType.body, color = c.text, modifier = Modifier.weight(1f))
                        Muted("$used / $total")
                    }
                    Meter(v.used.toDouble() / maxOf(1L, v.size), v.name)
                    if (v.state != "ok") Text(t("desktop.widget.volumeState", "state" to io.github.ra5on.titanos.compute.tOr("state.${v.state}", v.state)), style = TitanType.small, color = c.warnInk)
                }
            }
        }
        "docker" -> Column(scroll) {
            Title(t("desktop.widget.docker"))
            val list = data.docker.orEmpty()
            if (list.isEmpty()) Muted(t("desktop.widget.noApps"))
            for (p in list.take(8)) ListRowButton(p.label, p.running) { state.open(p.key) }
        }
        "vms" -> Column(scroll) {
            Title(t("desktop.widget.vms"))
            val list = data.vms.orEmpty()
            if (list.isEmpty()) Muted(t("desktop.widget.noVms"))
            for (vm in list.take(8)) ListRowButton(vm.label, vm.running) { state.open("app:vms", "/vms?vm=" + vm.key) }
        }
        "temperatures" -> Column(scroll) {
            Title(t("desktop.widget.temperatures"))
            if (data.temps.isEmpty()) Muted(t("desktop.widget.noTemps"))
            for (x in data.temps) Row(Modifier.fillMaxWidth()) {
                Text(x.label, style = TitanType.small, color = c.text, modifier = Modifier.weight(1f))
                val hot = x.critical != null && x.celsius >= x.critical - 10
                Text("${x.celsius.roundToInt()} °C" + if (hot) " ▲" else "", style = TitanType.small, color = if (hot) c.warnInk else c.text)
            }
        }
        "clock" -> Column(scroll, verticalArrangement = Arrangement.Center) {
            var now by remember { mutableStateOf(LocalDateTime.now()) }
            LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(15_000) } }
            Text(now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)), style = TitanType.big, color = c.text)
            Muted(now.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", locale)))
        }
        "backups" -> Column(scroll) {
            Title(t("desktop.widget.backups"))
            val list = data.backups
            when {
                list == null -> Muted(t("common.loading"))
                list.isEmpty() -> Muted(t("desktop.widget.noBackups"))
                else -> for (b in list.take(4)) Row(Modifier.fillMaxWidth()) {
                    Text(b.name, style = TitanType.small, color = c.text, modifier = Modifier.weight(1f))
                    Muted(if (b.running) t("desktop.widget.backupRunning") else Format.instant(b.lastRun)?.let { Format.longDate(it) } ?: "–")
                }
            }
            LinkText(t("desktop.widget.openBackups")) { state.open("app:backup") }
        }
        "homeassistant" -> Column(scroll) {
            Title(t("desktop.widget.homeassistant"))
            val list = data.entities
            when {
                list == null -> Muted(t("common.loading"))
                list.isEmpty() -> Muted(t("desktop.widget.noHa"))
                else -> for (e in list) Row(Modifier.fillMaxWidth()) {
                    Text(e.name, style = TitanType.small, color = c.text, modifier = Modifier.weight(1f))
                    Muted(e.state)
                }
            }
        }
        "tasks" -> Column(scroll) {
            Title(t("desktop.widget.tasks"))
            LinkText(t("desktop.widget.openTasks")) { state.open("app:monitor") }
        }
        "agent" -> Column(scroll) {
            Title(t("desktop.widget.agent"))
            when (data.agentOn) {
                null -> Muted(t("common.loading"))
                false -> Muted(t("desktop.widget.agentOff"))
                true -> {
                    val waits = data.agentChats.filter { it.state == "waiting" }
                    val runs = data.agentChats.filter { it.state == "running" }
                    for (ch in waits.take(3)) Row(Modifier.fillMaxWidth().clickable { state.open("app:agent", "/agent?chat=" + ch.id) }.padding(vertical = 3.dp)) {
                        Text(ch.title, style = TitanType.small, color = c.text, modifier = Modifier.weight(1f)); Text(t("desktop.widget.agentWaits"), style = TitanType.small, color = c.warnInk)
                    }
                    for (ch in runs.take(3)) Row(Modifier.fillMaxWidth().clickable { state.open("app:agent", "/agent?chat=" + ch.id) }.padding(vertical = 3.dp)) {
                        Text(ch.title, style = TitanType.small, color = c.text, modifier = Modifier.weight(1f)); Text(t("desktop.widget.agentRuns"), style = TitanType.small, color = c.okInk)
                    }
                    if (waits.isEmpty() && runs.isEmpty()) Muted(t("desktop.widget.agentIdle"))
                }
            }
            LinkText(t("desktop.widget.agentOpen")) { state.open("app:agent") }
        }
        "igpu", "npu", "gpu" -> Column(scroll) {
            val title = t("desktop.widget." + w.kind)
            Title(title)
            val list = data.gpus.filter { it.kind == w.kind }
            val g = list.firstOrNull { it.address == w.device } ?: list.firstOrNull()
            if (g == null) {
                Muted(t("desktop.widget.gpuNone"))
            } else {
                fun shows(v: String) = v !in w.hide
                Text(g.name, style = TitanType.small, color = c.text)
                if (shows("usage") && g.usage != null) {
                    Row { Muted(t("desktop.widget.gpuUsage")); Text("  " + Format.percent(g.usage / 100), style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text) }
                    Meter(g.usage / 100, t("desktop.widget.gpuUsage"))
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shows("temperature") && g.temperature != null) Column { Muted(t("desktop.widget.gpuTemperature")); Text("${g.temperature.roundToInt()} °C", style = TitanType.small, color = c.text) }
                    if (shows("memory") && g.memUsed != null && g.memTotal != null && g.memTotal > 0) Column {
                        Muted(t("desktop.widget.gpuMemory")); Text(Format.bytesOf(g.memUsed, g.memTotal).let { "${it.first} / ${it.second}" }, style = TitanType.small, color = c.text)
                    }
                    if (shows("power") && g.power != null) Column { Muted(t("desktop.widget.gpuPower")); Text("${g.power.roundToInt()} W", style = TitanType.small, color = c.text) }
                    if (shows("clock") && g.clock != null) Column { Muted(t("desktop.widget.gpuClock")); Text("${g.clock.roundToInt()} MHz", style = TitanType.small, color = c.text) }
                }
            }
        }
        "camera" -> Column(scroll) {
            Title(t("desktop.widget.camera"))
            LinkText(t("desktop.app.nvr")) { state.open("app:nvr") }
        }
        else -> Muted(w.kind)
    }
}

@Composable
private fun ColumnScope.LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = TitanType.small.copy(fontWeight = FontWeight.SemiBold),
        color = Titan.colors.accent,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
    )
}

/** Dauer wie formatDuration im Web (Tage, Stunden, Minuten). */
fun duration(seconds: Long): String {
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "$d d $h h"
        h > 0 -> "$h h $m min"
        else -> "$m min"
    }
}
