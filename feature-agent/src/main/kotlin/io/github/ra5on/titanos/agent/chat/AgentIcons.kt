package io.github.ra5on.titanos.agent.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Strichsymbole der Agent-App, die TitanIcons noch nicht hat (24er Raster, Strich 1,8). */
object AgentIcons {
    private fun line(name: String, d: String, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val menu: ImageVector by lazy { line("menu", "M4 7h16M4 12h16M4 17h16") }
    val newChat: ImageVector by lazy { line("newChat", "M20 12a8 8 0 0 1-11.6 7.1L4 20l1-4A8 8 0 1 1 20 12ZM12 8.5v7M8.5 12h7") }
    val chat: ImageVector by lazy { line("chat", "M5 5h14a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H10l-4 3v-3H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1ZM8 10h8M8 13h5") }
    val clock: ImageVector by lazy { line("clock", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 7v5l3 2") }
    val copy: ImageVector by lazy { line("copy", "M9 9h10v11H9ZM5 15V4h10") }
    val up: ImageVector by lazy { line("up", "M12 19V6M6.5 11.5 12 6l5.5 5.5", 2.2f) }
    val ring: ImageVector by lazy { line("ring", "M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z", 2.2f) }
    val brain: ImageVector by lazy { line("brain", "M9 4a3 3 0 0 0-3 3 3 3 0 0 0-2 5 3 3 0 0 0 2 5 3 3 0 0 0 6 1V5a3 3 0 0 0-3-1ZM15 4a3 3 0 0 1 3 3 3 3 0 0 1 2 5 3 3 0 0 1-2 5 3 3 0 0 1-6 1") }
    val code: ImageVector by lazy { line("code", "m8 8-4 4 4 4M16 8l4 4-4 4M13.5 5l-3 14") }
    val switch: ImageVector by lazy { line("switch", "M7 7h10a5 5 0 0 1 0 10H7A5 5 0 0 1 7 7ZM7 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z") }
    val hand: ImageVector by lazy { line("hand", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 8v5M12 16h.01") }
}
