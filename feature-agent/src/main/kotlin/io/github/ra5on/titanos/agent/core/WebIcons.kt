package io.github.ra5on.titanos.agent.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Die Strichsymbole der Weboberfläche (Icon.svelte) unter ihren Namen:
 * `WebIcons["newChat"]`. 24er Raster, Strich 1,8 (bei grip und more 2,6).
 */
object WebIcons {
    private val cache = HashMap<String, ImageVector>()

    operator fun get(name: String): ImageVector = synchronized(cache) {
        cache.getOrPut(name) {
            val d = WEB_ICON_PATHS[name] ?: WEB_ICON_PATHS.getValue("info")
            val width = if (name == "grip" || name == "more") 2.6f else 1.8f
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
        }
    }

    fun has(name: String) = name in WEB_ICON_PATHS
}
