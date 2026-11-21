package io.github.ra5on.titanos.pim

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Strichsymbole für Kalender und Kontakte im Stil von TitanIcons (24er Raster, Strich 1,8). */
object PimIcons {
    private fun line(name: String, d: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()

    val list: ImageVector by lazy { line("list", "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01") }
    val call: ImageVector by lazy { line("call", "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a1 1 0 0 1-1 1A16 16 0 0 1 4 5a1 1 0 0 1 1-1Z") }
    val message: ImageVector by lazy { line("message", "M4 5h16v11H9l-5 4Z") }
    val group: ImageVector by lazy { line("group", "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM2.5 20a6.5 6.5 0 0 1 13 0M16 4.5a3.5 3.5 0 0 1 0 6.5M18 14a6.5 6.5 0 0 1 3.5 6") }
    val merge: ImageVector by lazy { line("merge", "M6 4v4a4 4 0 0 0 4 4h4a4 4 0 0 1 4 4v4M18 4v4a4 4 0 0 1-4 4M15 17l3 3 3-3") }
    val card: ImageVector by lazy { line("card", "M3 6h18v12H3ZM8 10.5a2 2 0 1 0 0-.01ZM5.5 16a3 3 0 0 1 5 0M13 10h5M13 13h4") }
    val star: ImageVector by lazy { line("star", "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8-4.3-4.1 5.9-.9Z") }
    val window: ImageVector by lazy { line("window", "M4 5h16v14H4ZM4 9h16M13 13h4v3h-4") }
    val cake: ImageVector by lazy { line("cake", "M4 20h16v-7H4ZM4 16c2 1.5 4-1.5 6 0s4 1.5 6 0 3-1 4 0M12 13V9M12 6.5v-.01") }
}
