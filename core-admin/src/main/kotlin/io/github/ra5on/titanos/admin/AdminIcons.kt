package io.github.ra5on.titanos.admin

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.theme.Titan

/**
 * Die Strichsymbole der Weboberfläche (`<Icon name="disk">`), gezeichnet aus
 * denselben Pfaden (AdminIconPaths.kt, erzeugt aus Icon.svelte). Unbekannte
 * Namen ergeben ein leeres Symbol, nie einen Absturz.
 */
object AdminIcons {
    private val cache = HashMap<String, ImageVector>()

    fun of(name: String): ImageVector = synchronized(cache) {
        cache.getOrPut(name) {
            val d = ICON_PATHS[name].orEmpty()
            val heavy = name == "grip" || name == "more"
            ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
                if (d.isNotEmpty()) {
                    addPath(
                        pathData = PathParser().parsePathString(d).toNodes(),
                        fill = null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = if (heavy) 2.6f else 1.8f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()
        }
    }

    fun exists(name: String): Boolean = ICON_PATHS.containsKey(name)
}

/** Ein Symbol des Webs in der Farbe [tint]; [label] nur, wenn es allein für etwas steht. */
@Composable
fun WebIcon(name: String, modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = Titan.colors.muted, label: String? = null) {
    Icon(AdminIcons.of(name), contentDescription = label, tint = tint, modifier = modifier.then(Modifier.size(size)))
}
