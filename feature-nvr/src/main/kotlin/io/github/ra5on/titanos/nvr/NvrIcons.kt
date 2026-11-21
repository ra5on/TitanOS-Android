package io.github.ra5on.titanos.nvr

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Strichsymbole des NVR, die TitanIcons nicht hat (24er Raster, Strich 1,8 wie dort). */
object NvrIcons {
    private fun line(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()

    val volume by lazy { line("volume", "M4 9h4l5-4v14l-5-4H4ZM16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12") }
    val volumeOff by lazy { line("volumeOff", "M4 9h4l5-4v14l-5-4H4ZM17 9l5 6M22 9l-5 6") }
    val maximize by lazy { line("maximize", "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5") }
    val up by lazy { line("up", "M12 19V5M6 11l6-6 6 6") }
    val downArrow by lazy { line("downArrow", "M12 5v14M6 13l6 6 6-6") }
    val left by lazy { line("left", "M19 12H5M11 6l-6 6 6 6") }
    val right by lazy { line("right", "M5 12h14M13 6l6 6-6 6") }
    val eye by lazy { line("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12ZM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z") }
    val clock by lazy { line("clock", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 7v5l3 2") }
    val copy by lazy { line("copy", "M9 9h11v11H9ZM5 15H4V4h11v1") }
    val camera by lazy { line("camera", "M4 7h11v10H4ZM15 10l5-3v10l-5-3") }
    val power by lazy { line("power", "M12 3v8M6.3 7a8 8 0 1 0 11.4 0") }
}
