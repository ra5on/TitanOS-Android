package io.github.ra5on.titanos.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType

/** Eine Linie im Diagramm; `null` in [values] ist eine Lücke (keine Messung), sie bleibt sichtbar. */
data class ChartSeries(val label: String, val color: Color, val values: List<Double?>, val dashed: Boolean = false)

/**
 * Liniendiagramm (Monitoring, Task-Manager) nativ mit Canvas. Lücken werden
 * nicht überbrückt. Für TalkBack eine Beschreibung mit letztem, höchstem und
 * niedrigstem Wert je Linie ([format] macht den Wert lesbar). Die Legende
 * steht als Text darunter (Farbe nie allein: Name und gestrichelt/voll).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineChart(
    title: String,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    max: Double? = null,
    min: Double = 0.0,
    format: (Double) -> String = { it.toString() },
    legend: Boolean = true,
) {
    val c = Titan.colors
    val res = LocalResources.current
    val desc = series.joinToString("; ") { s ->
        val v = s.values.filterNotNull()
        if (v.isEmpty()) res.getString(R.string.admin_chart_empty, "$title ${s.label}")
        else res.getString(R.string.admin_chart, "$title ${s.label}", format(v.last()), format(v.max()), format(v.min()))
    }
    val top = max ?: (series.flatMap { it.values.filterNotNull() }.maxOrNull()?.let { if (it <= min) min + 1 else it * 1.1 } ?: (min + 1))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = desc }) {
            val w = size.width
            val h = size.height
            val grid = c.border
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            for (s in series) {
                val n = s.values.size
                if (n == 0) continue
                val step = if (n > 1) w / (n - 1) else w
                var path: Path? = null
                s.values.forEachIndexed { i, v ->
                    if (v == null) {
                        path?.let { drawPath(it, s.color, style = stroke(s.dashed)) }
                        path = null
                    } else {
                        val x = i * step
                        val y = (h - ((v - min) / (top - min)).coerceIn(0.0, 1.0) * h).toFloat()
                        val p = path
                        if (p == null) path = Path().apply { moveTo(x, y) } else p.lineTo(x, y)
                        if (n == 1) drawCircle(s.color, 3.dp.toPx(), Offset(x, y))
                    }
                }
                path?.let { drawPath(it, s.color, style = stroke(s.dashed)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(format(min), style = TitanType.small, color = c.muted)
            Text(format(top), style = TitanType.small, color = c.muted)
        }
        if (legend && series.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (s in series) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Canvas(Modifier.size(18.dp, 8.dp)) {
                            drawLine(s.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3.dp.toPx(),
                                pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null)
                        }
                        Text(s.label, style = TitanType.small, color = c.text)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.stroke(dashed: Boolean) = Stroke(
    width = 2.dp.toPx(),
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
)

/** Kleiner Verlauf in einer Zeile (Sparkline im Web), nur Anzeige mit Beschreibung. */
@Composable
fun Sparkline(label: String, values: List<Double?>, modifier: Modifier = Modifier, color: Color = Titan.colors.accent, max: Double? = null, format: (Double) -> String = { it.toString() }) {
    LineChart(label, listOf(ChartSeries(label, color, values)), modifier.padding(vertical = 2.dp), height = 36.dp, max = max, format = format, legend = false)
}
