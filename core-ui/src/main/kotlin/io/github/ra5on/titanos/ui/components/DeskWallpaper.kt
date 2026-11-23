package io.github.ra5on.titanos.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale

/**
 * Die Hintergründe des Arbeitsplatzes wie im Web (web/src/lib/desktop/wallpapers.ts):
 * gezeichnete Verläufe, Fotos vom NAS (`/backgrounds/<id>.webp`) oder ein
 * eigenes Bild (`/api/v1/background`). Gemeinsam für die Systemsteuerung
 * (Darstellung › Hintergrund) und den Arbeitsplatz; das Bild lädt der Aufrufer.
 */
object DeskWallpapers {
    val IDS = listOf("wave", "aurora", "dunes", "peaks", "grid", "calm", "nordlicht", "bergsee", "kueste", "nebelwald", "wueste", "weltraum")
    val PHOTOS = setOf("nordlicht", "bergsee", "kueste", "nebelwald", "wueste", "weltraum")

    // base, deep, mid, glow (dunkel | hell), wie die gezeichneten Hintergründe des Webs.
    private val COLORS: Map<String, Pair<List<Long>, List<Long>>> = mapOf(
        "wave" to (listOf(0xFF060B18, 0xFF0A1530, 0xFF16336B, 0xFF2A5BB0) to listOf(0xFFE9F1FF, 0xFFCFE1FF, 0xFF8FB8F5, 0xFF5C8FE0)),
        "aurora" to (listOf(0xFF04100F, 0xFF06231F, 0xFF0D5A4F, 0xFF2BB39A) to listOf(0xFFEAFAF6, 0xFFC9F1E7, 0xFF86DCC8, 0xFF3FB39C)),
        "dunes" to (listOf(0xFF140B06, 0xFF2A160B, 0xFF6B3A16, 0xFFC97A2F) to listOf(0xFFFFF5EA, 0xFFFFE3C4, 0xFFF6BD83, 0xFFE08A3C)),
        "peaks" to (listOf(0xFF0B0716, 0xFF1A1035, 0xFF3B2A7A, 0xFF7B5CD6) to listOf(0xFFF3EFFF, 0xFFDDD3FF, 0xFFB5A3F5, 0xFF8566E0)),
        "grid" to (listOf(0xFF07090C, 0xFF10151C, 0xFF1C2632, 0xFF2F6FB0) to listOf(0xFFF4F6F9, 0xFFE4E9F0, 0xFFC8D3E0, 0xFF6C97C8)),
        "calm" to (listOf(0xFF0B0D10, 0xFF14181D, 0xFF20262E, 0xFF3A4654) to listOf(0xFFF6F7F9, 0xFFECEFF3, 0xFFD9DEE5, 0xFFAAB4C0)),
    )
    private val PHOTO_STAND_IN = mapOf("nordlicht" to "aurora", "bergsee" to "peaks", "kueste" to "wave", "nebelwald" to "calm", "wueste" to "dunes", "weltraum" to "peaks")

    /** Die vier Farben eines Hintergrunds (auch Ersatz, solange ein Foto lädt). */
    fun colors(custom: Boolean, id: String, light: Boolean): List<Color> {
        val key = if (custom) "wave" else PHOTO_STAND_IN[id] ?: id
        val (dark, bright) = COLORS[key] ?: COLORS.getValue("wave")
        return (if (light) bright else dark).map { Color(it) }
    }

    /** Die Adresse des Bildes am NAS (Pfad mit Abfrage), null bei einem gezeichneten Hintergrund. */
    fun path(custom: Boolean, id: String, version: Long): String? = when {
        custom -> "/api/v1/background?v=$version"
        id in PHOTOS -> "/backgrounds/$id.webp"
        else -> null
    }

    /** Ob die Fläche hell ist (Schrift darauf dunkel). */
    fun light(custom: Boolean, id: String, light: Boolean): Boolean = light && !custom && id !in PHOTOS
}

/** Ein Hintergrund als Fläche: [image] (Foto, eigenes Bild), sonst sein Verlauf mit Licht und bei „Welle“ den Bögen des Webs. */
@Composable
fun DeskWallpaper(custom: Boolean, id: String, light: Boolean, image: ImageBitmap?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val colors = DeskWallpapers.colors(custom, id, light)
    Box(
        modifier
            .background(Brush.linearGradient(colors, Offset.Zero, Offset.Infinite))
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(colors[3].copy(alpha = .55f), Color.Transparent), Offset(size.width * .78f, size.height * .12f), size.maxDimension * .7f))
                drawRect(Brush.radialGradient(listOf(colors[2].copy(alpha = .45f), Color.Transparent), Offset(size.width * .15f, size.height * .95f), size.maxDimension * .6f))
                if (!custom && id == "wave") {
                    val w = size.width
                    val h = size.height
                    for ((k, a) in listOf(0.0f to .55f, 0.18f to .35f)) {
                        val p = Path().apply {
                            moveTo(0f, h * (0.75f + k))
                            cubicTo(w * .3f, h * (0.35f + k), w * .55f, h * (0.15f + k), w, h * (0.3f + k))
                            lineTo(w, h)
                            lineTo(0f, h)
                            close()
                        }
                        drawPath(p, colors[2].copy(alpha = .18f * a))
                        drawPath(p, colors[3].copy(alpha = a * .6f), style = Stroke(1.5f * density))
                    }
                }
            },
    ) {
        if (image != null) Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        content()
    }
}
