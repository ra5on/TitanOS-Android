package io.github.ra5on.titanos.nvr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlin.math.abs

private val AREA_KINDS = listOf("zone", "motion", "objects")
private val ZoneColor = Color(0xFF38BDF8)
private val MotionColor = Color(0xFFF59E0B)
private val ObjectsColor = Color(0xFFF472B6)

private fun colorOf(kind: String) = when (kind) { "zone" -> ZoneColor; "motion" -> MotionColor; else -> ObjectsColor }

private data class Sel(val kind: String, val index: Int)

private fun inside(p: Offset, pts: List<List<Double>>): Boolean {
    var c = false
    var j = pts.size - 1
    for (i in pts.indices) {
        val (xi, yi) = pts[i][0] to pts[i][1]
        val (xj, yj) = pts[j][0] to pts[j][1]
        if ((yi > p.y) != (yj > p.y) && p.x < (xj - xi) * (p.y - yi) / (yj - yi) + xi) c = !c
        j = i
    }
    return c
}

/**
 * Zonen und Masken auf dem Standbild der Kamera (ZoneEditor.svelte): eine
 * neue Fläche Punkt für Punkt antippen, „Fertig“ oder Tippen auf den ersten
 * Punkt schließt sie; an einer gewählten Fläche Punkte ziehen, „+“ in der
 * Mitte einer Kante fügt einen ein. Ohne Zeichnen scrollt die Seite über das
 * Bild hinweg. Für Bildschirmleser und Tastatur: Fläche in der Liste wählen,
 * Punkt wählen und mit den Pfeilknöpfen verschieben.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZoneEditor(
    camera: String,
    zones: List<CameraZone>,
    masks: CameraMasks,
    onZones: (List<CameraZone>) -> Unit,
    onMasks: (CameraMasks) -> Unit,
    onZoneGone: (String, String) -> Unit,
) {
    val c = Titan.colors
    var selected by remember { mutableStateOf<Sel?>(null) }
    var pointAt by remember { mutableStateOf<Int?>(null) }
    var drawing by remember { mutableStateOf<Pair<String, List<List<Double>>>?>(null) }
    var picture by remember { mutableStateOf("loading") }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var stamp by remember { mutableLongStateOf(0L) }
    val motion = masks.motion.orEmpty()
    val objects = masks.objects.orEmpty()
    fun areas(kind: String): List<List<List<Double>>> = when (kind) { "zone" -> zones.map { it.points }; "motion" -> motion.map { it.points }; else -> objects.map { it.points } }
    fun room(kind: String) = areas(kind).size < if (kind == "zone") MAX_ZONES else MAX_MASKS
    fun setPoints(s: Sel, pts: List<List<Double>>) {
        when (s.kind) {
            "zone" -> onZones(zones.mapIndexed { i, z -> if (i == s.index) z.copy(points = pts) else z })
            "motion" -> onMasks(masks.copy(motion = motion.mapIndexed { i, m -> if (i == s.index) CameraMask(pts) else m }))
            else -> onMasks(masks.copy(objects = objects.mapIndexed { i, m -> if (i == s.index) CameraMask(pts) else m }))
        }
    }
    val kindNames = AREA_KINDS.associateWith { t("nvr.areas.kind.$it") }
    val unnamed = t("nvr.areas.unnamed")
    val maskMotion = (1..MAX_MASKS).map { t("nvr.areas.maskName.motion", "n" to it) }
    val maskObjects = (1..MAX_MASKS).map { t("nvr.areas.maskName.objects", "n" to it) }
    fun labelOf(kind: String, i: Int) = when (kind) { "zone" -> zones.getOrNull(i)?.name?.ifBlank { null } ?: unnamed; "motion" -> maskMotion.getOrElse(i) { "$i" }; else -> maskObjects.getOrElse(i) { "$i" } }
    val all = AREA_KINDS.flatMap { k -> areas(k).mapIndexed { i, p -> Triple(Sel(k, i), p, labelOf(k, i)) } }
    val current = selected?.let { s -> all.firstOrNull { it.first == s } }
    val zoneDefaults = (1..MAX_ZONES + 5).map { t("nvr.areas.zoneDefault", "n" to it) }

    fun finish() {
        val d = drawing ?: return
        if (!closable(d.second)) return
        if (d.first == "zone") {
            val name = zoneDefaults.drop(zones.size).firstOrNull { n -> zones.none { it.name.equals(n, true) } } ?: "Zone ${zones.size + 1}"
            onZones(zones + CameraZone(zoneId(name, zones.mapNotNull { it.id }), name, d.second))
            selected = Sel("zone", zones.size)
        } else if (d.first == "motion") {
            onMasks(masks.copy(motion = motion + CameraMask(d.second)))
            selected = Sel("motion", motion.size)
        } else {
            onMasks(masks.copy(objects = objects + CameraMask(d.second)))
            selected = Sel("objects", objects.size)
        }
        drawing = null
        pointAt = null
    }

    fun insertMiddle(s: Sel, after: Int): Boolean {
        val pts = areas(s.kind).getOrNull(s.index) ?: return false
        if (pts.size >= MAX_POINTS) return false
        val a = pts[after]
        val b = pts[(after + 1) % pts.size]
        setPoints(s, pts.take(after + 1) + listOf(point((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)) + pts.drop(after + 1))
        return true
    }

    fun removePoint(s: Sel, i: Int) {
        val pts = areas(s.kind).getOrNull(s.index) ?: return
        if (pts.size <= MIN_POINTS) return
        setPoints(s, pts.filterIndexed { j, _ -> j != i })
        pointAt = null
    }

    fun removeArea(s: Sel) {
        when (s.kind) {
            "zone" -> {
                val gone = zones.getOrNull(s.index)
                onZones(zones.filterIndexed { i, _ -> i != s.index })
                if (gone?.id != null) onZoneGone(gone.id, gone.name)
            }
            "motion" -> onMasks(masks.copy(motion = motion.filterIndexed { i, _ -> i != s.index }))
            else -> onMasks(masks.copy(objects = objects.filterIndexed { i, _ -> i != s.index }))
        }
        selected = null
        pointAt = null
    }

    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(t("nvr.areas.new"), style = TitanType.rowSub, color = c.muted)
            for (k in AREA_KINDS) TitanButton(kindNames.getValue(k), { selected = null; pointAt = null; drawing = k to emptyList() }, enabled = drawing == null && picture == "ok" && room(k), icon = TitanIcons.plus)
            TitanButton(t("nvr.areas.reload"), { picture = "loading"; stamp = System.currentTimeMillis() }, enabled = drawing == null, icon = TitanIcons.restart)
        }
        drawing?.let { (kind, pts) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = .12f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("nvr.areas.drawing", "kind" to kindNames.getValue(kind)) + " " + t("nvr.areas.pointsCount", "count" to pts.size), style = TitanType.body, color = c.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TitanButton(t("common.cancel"), { drawing = null })
                    TitanButton(t("nvr.areas.done"), { finish() }, kind = ButtonKind.Primary, enabled = closable(pts), icon = TitanIcons.check)
                }
            }
        }
        if (picture == "none") Problem(t("nvr.areas.noPicture"))
        val density = LocalDensity.current
        val touch = with(density) { 24.dp.toPx() }
        val latest by rememberUpdatedState(Triple(drawing, selected, all))
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(Color.Black),
        ) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            NvrPicture(
                "/nvr/cameras/${io.github.ra5on.titanos.compute.ComputeAccess.seg(camera)}/snapshot.jpg?height=720" + if (stamp > 0) "&t=$stamp" else "",
                t("nvr.areas.picture"),
                Modifier.fillMaxSize(),
                contain = true,
                refreshKey = stamp,
                onSize = { w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h; picture = "ok" },
                onError = { picture = "none" },
            )
            Canvas(
                Modifier.fillMaxSize().pointerInput(wPx, hPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val (d, s, list) = latest
                        val rel = Offset(down.position.x / wPx, down.position.y / hPx)
                        if (d != null) {
                            down.consume()
                            val pts = d.second
                            val first = pts.firstOrNull()
                            if (first != null && closable(pts) && abs(first[0] * wPx - down.position.x) < touch && abs(first[1] * hPx - down.position.y) < touch) {
                                finish()
                            } else if (pts.size < MAX_POINTS) {
                                drawing = d.first to (pts + listOf(point(rel.x.toDouble(), rel.y.toDouble())))
                            }
                            return@awaitEachGesture
                        }
                        val pts = s?.let { sel -> list.firstOrNull { it.first == sel }?.second }
                        var dragging: Int? = null
                        if (s != null && pts != null) {
                            dragging = pts.indexOfFirst { abs(it[0] * wPx - down.position.x) < touch && abs(it[1] * hPx - down.position.y) < touch }.takeIf { it >= 0 }
                            if (dragging == null && pts.size < MAX_POINTS) {
                                val mid = pts.indices.firstOrNull { i ->
                                    val a = pts[i]
                                    val b = pts[(i + 1) % pts.size]
                                    abs((a[0] + b[0]) / 2 * wPx - down.position.x) < touch && abs((a[1] + b[1]) / 2 * hPx - down.position.y) < touch
                                }
                                if (mid != null && insertMiddle(s, mid)) dragging = mid + 1
                            }
                        }
                        if (dragging != null && s != null) {
                            down.consume()
                            pointAt = dragging
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                val now = latest.third.firstOrNull { it.first == s }?.second ?: break
                                val moved = now.toMutableList()
                                if (dragging !in moved.indices) break
                                moved[dragging] = point((ch.position.x / wPx).toDouble(), (ch.position.y / hPx).toDouble())
                                setPoints(s, moved)
                            }
                            return@awaitEachGesture
                        }
                        // Ein Tippen wählt die Fläche darunter; Ziehen bleibt der Seite.
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (!ch.pressed) break
                        }
                        if (!moved) {
                            val hit = list.lastOrNull { inside(rel, it.second) }
                            selected = hit?.first
                            pointAt = null
                        }
                    }
                },
            ) {
                if (picture != "ok") return@Canvas
                for ((s, pts, _) in all) {
                    if (pts.size < 2) continue
                    val path = Path().apply {
                        moveTo((pts[0][0] * size.width).toFloat(), (pts[0][1] * size.height).toFloat())
                        for (p in pts.drop(1)) lineTo((p[0] * size.width).toFloat(), (p[1] * size.height).toFloat())
                        close()
                    }
                    val col = colorOf(s.kind)
                    drawPath(path, col.copy(alpha = if (s == selected) .35f else .2f))
                    drawPath(path, col, style = Stroke(width = if (s == selected) 5f else 3f))
                }
                drawing?.let { (kind, pts) ->
                    val col = colorOf(kind)
                    for (i in 1 until pts.size) drawLine(col, Offset((pts[i - 1][0] * size.width).toFloat(), (pts[i - 1][1] * size.height).toFloat()), Offset((pts[i][0] * size.width).toFloat(), (pts[i][1] * size.height).toFloat()), 4f)
                    pts.forEachIndexed { i, p ->
                        val at = Offset((p[0] * size.width).toFloat(), (p[1] * size.height).toFloat())
                        drawCircle(Color.White, if (i == 0 && closable(pts)) 16f else 10f, at)
                        drawCircle(col, if (i == 0 && closable(pts)) 12f else 7f, at)
                    }
                }
                val cur = current
                if (cur != null && drawing == null) {
                    val pts = cur.second
                    val col = colorOf(cur.first.kind)
                    if (pts.size < MAX_POINTS) pts.indices.forEach { i ->
                        val a = pts[i]
                        val b = pts[(i + 1) % pts.size]
                        val m = Offset(((a[0] + b[0]) / 2 * size.width).toFloat(), ((a[1] + b[1]) / 2 * size.height).toFloat())
                        drawCircle(Color.White.copy(alpha = .85f), 11f, m)
                        drawLine(col, m - Offset(6f, 0f), m + Offset(6f, 0f), 3f)
                        drawLine(col, m - Offset(0f, 6f), m + Offset(0f, 6f), 3f)
                    }
                    pts.forEachIndexed { i, p ->
                        val at = Offset((p[0] * size.width).toFloat(), (p[1] * size.height).toFloat())
                        drawCircle(Color.White, if (pointAt == i) 18f else 14f, at)
                        drawCircle(col, if (pointAt == i) 13f else 10f, at)
                    }
                }
            }
            if (picture == "ok") {
                for ((s, pts, label) in all) {
                    val (cx, cy) = centre(pts)
                    Text(
                        label,
                        style = TitanType.small,
                        color = Color.White,
                        modifier = Modifier.offset(maxWidth * cx.coerceIn(0.05, 0.85).toFloat(), maxHeight * cy.coerceIn(0.05, 0.9).toFloat())
                            .clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).border(1.dp, colorOf(s.kind), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp).clearAndSetSemantics { },
                    )
                }
            }
        }
        if (picture == "ok") Note(t("nvr.areas.editHint"))
        if (all.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("nvr.areas.list"), style = TitanType.section, color = c.muted)
                for ((s, pts, label) in all) {
                    val on = s == selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.accent.copy(alpha = .18f) else c.chip)
                            .clickable(role = Role.Button) { drawing = null; selected = if (on) null else s; pointAt = null }
                            .semantics { this.selected = on }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(colorOf(s.kind)))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = c.text)
                            Text(kindNames.getValue(s.kind) + " · " + t("nvr.areas.pointsCount", "count" to pts.size), style = TitanType.rowSub, color = c.muted)
                        }
                    }
                }
            }
        }
        val cur = current
        if (cur != null) {
            val s = cur.first
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.kind == "zone") {
                    val z = zones.getOrNull(s.index)
                    val name = z?.name.orEmpty()
                    val taken = name.isNotBlank() && zones.filterIndexed { i, _ -> i != s.index }.any { it.name.trim().equals(name.trim(), true) }
                    Field(t("nvr.areas.name"), name, { v -> onZones(zones.mapIndexed { i, zz -> if (i == s.index) zz.copy(name = v.take(40)) else zz }) }, error = if (taken) t("nvr.areas.nameTaken") else if (name.isBlank()) t("nvr.areas.nameEmpty") else null)
                } else {
                    Sub(t("nvr.areas.kindHint.${s.kind}"))
                }
                // Punkte wählen und verschieben ohne Ziehen (Tastatur, Bildschirmleser).
                val pointWords = cur.second.indices.map { t("nvr.areas.point", "n" to it + 1, "area" to cur.third) }
                Pills(cur.second.indices.map { Pill(it, (it + 1).toString()) }, listOfNotNull(pointAt), t("nvr.areas.list"), { i, on -> pointAt = if (on) i else null })
                val p = pointAt
                if (p != null && p in cur.second.indices) {
                    Text(pointWords[p], style = TitanType.rowSub, color = c.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((key, icon, word) in listOf(
                            Triple("ArrowLeft", NvrIcons.left, t("nvr.ptz.left")),
                            Triple("ArrowUp", NvrIcons.up, t("nvr.ptz.up")),
                            Triple("ArrowDown", NvrIcons.downArrow, t("nvr.ptz.down")),
                            Triple("ArrowRight", NvrIcons.right, t("nvr.ptz.right")),
                        )) {
                            ToolButton(icon, "${pointWords[p]}: $word", {
                                val pts = cur.second.toMutableList()
                                val (x, y) = pts[p][0] to pts[p][1]
                                pts[p] = when (key) {
                                    "ArrowLeft" -> point(x - 0.01, y)
                                    "ArrowRight" -> point(x + 0.01, y)
                                    "ArrowUp" -> point(x, y - 0.01)
                                    else -> point(x, y + 0.01)
                                }
                                setPoints(s, pts)
                            })
                        }
                        val next = p
                        ToolButton(TitanIcons.plus, t("nvr.areas.addPoint", "a" to next + 1, "b" to (next + 1) % cur.second.size + 1), {
                            if (insertMiddle(s, next)) pointAt = next + 1
                        })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TitanButton(t("nvr.areas.remove"), { removeArea(s) }, kind = ButtonKind.Danger, icon = TitanIcons.trash)
                    TitanButton(t("nvr.areas.removePoint"), { pointAt?.let { removePoint(s, it) } }, enabled = pointAt != null && cur.second.size > MIN_POINTS)
                }
            }
        }
    }
}
