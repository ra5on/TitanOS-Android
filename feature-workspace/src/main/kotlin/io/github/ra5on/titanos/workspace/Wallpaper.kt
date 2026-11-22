package io.github.ra5on.titanos.workspace

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/*
 * Der Hintergrund des Arbeitsplatzes aus derselben Einstellung wie im Web
 * (`desktop.wallpaper`): gezeichnete Verläufe oder Fotos vom NAS
 * (`/backgrounds/<id>.webp`), ein eigenes Bild über `/api/v1/background`.
 * Gewählt wird er in der Systemsteuerung › Darstellung (Helfer E); nach dem
 * Zusammenführen kann diese Fläche durch E's WallpaperFill ersetzt werden.
 */

private val PHOTOS = setOf("nordlicht", "bergsee", "kueste", "nebelwald", "wueste", "weltraum")

// base, deep, mid, glow (dunkel | hell), wie die gezeichneten Hintergründe des Webs.
private val WALL_COLORS: Map<String, Pair<List<Long>, List<Long>>> = mapOf(
    "wave" to (listOf(0xFF060B18, 0xFF0A1530, 0xFF16336B, 0xFF2A5BB0) to listOf(0xFFE9F1FF, 0xFFCFE1FF, 0xFF8FB8F5, 0xFF5C8FE0)),
    "aurora" to (listOf(0xFF04100F, 0xFF06231F, 0xFF0D5A4F, 0xFF2BB39A) to listOf(0xFFEAFAF6, 0xFFC9F1E7, 0xFF86DCC8, 0xFF3FB39C)),
    "dunes" to (listOf(0xFF140B06, 0xFF2A160B, 0xFF6B3A16, 0xFFC97A2F) to listOf(0xFFFFF5EA, 0xFFFFE3C4, 0xFFF6BD83, 0xFFE08A3C)),
    "peaks" to (listOf(0xFF0B0716, 0xFF1A1035, 0xFF3B2A7A, 0xFF7B5CD6) to listOf(0xFFF3EFFF, 0xFFDDD3FF, 0xFFB5A3F5, 0xFF8566E0)),
    "grid" to (listOf(0xFF07090C, 0xFF10151C, 0xFF1C2632, 0xFF2F6FB0) to listOf(0xFFF4F6F9, 0xFFE4E9F0, 0xFFC8D3E0, 0xFF6C97C8)),
    "calm" to (listOf(0xFF0B0D10, 0xFF14181D, 0xFF20262E, 0xFF3A4654) to listOf(0xFFF6F7F9, 0xFFECEFF3, 0xFFD9DEE5, 0xFFAAB4C0)),
)
private val PHOTO_STAND_IN = mapOf("nordlicht" to "aurora", "bergsee" to "peaks", "kueste" to "wave", "nebelwald" to "calm", "wueste" to "dunes", "weltraum" to "peaks")

/** Ein Verlauf in den Farben eines Hintergrunds (auch Ersatz, solange ein Foto lädt). */
fun wallpaperColors(w: Wallpaper, light: Boolean): List<Color> {
    val id = if (w.custom) "wave" else PHOTO_STAND_IN[w.id] ?: w.id
    val (dark, bright) = WALL_COLORS[id] ?: WALL_COLORS.getValue("wave")
    return (if (light) bright else dark).map { Color(it) }
}

/** Ob der Hintergrund hell ist (Schrift der Symbole dunkel). */
fun wallpaperLight(w: Wallpaper, light: Boolean): Boolean = light && !w.custom && w.id !in PHOTOS

private val cache = HashMap<String, ImageBitmap>()

/** Die Adresse des Bildes am NAS, null bei einem gezeichneten Hintergrund. */
fun wallpaperPath(w: Wallpaper): String? = when {
    w.custom -> "/api/v1/background?v=${w.version}"
    w.id in PHOTOS -> "/backgrounds/${w.id}.webp"
    else -> null
}

/** Der Hintergrund als Fläche: Foto oder eigenes Bild vom NAS, sonst sein Verlauf mit Licht von oben. */
@Composable
fun WallpaperFill(w: Wallpaper, light: Boolean, access: ComputeAccess?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val colors = wallpaperColors(w, light)
    val path = wallpaperPath(w)
    var image by remember(path) { mutableStateOf(path?.let { synchronized(cache) { cache[it] } }) }
    LaunchedEffect(path, access) {
        if (path == null || access == null || image != null) return@LaunchedEffect
        image = runCatching {
            withContext(Dispatchers.IO) {
                access.execute { base -> Request.Builder().url(base.newBuilder().encodedPath(path.substringBefore('?')).encodedQuery(path.substringAfter('?', "").ifEmpty { null }).build()).build() }.use { r ->
                    r.throwIfError()
                    val bytes = r.body.bytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
        }.getOrNull()?.also { synchronized(cache) { cache[path] = it } }
    }
    Box(
        modifier
            .background(Brush.linearGradient(colors, Offset.Zero, Offset.Infinite))
            .drawBehind {
                // Ein heller Schein oben rechts wie die gezeichneten Hintergründe des Webs.
                drawRect(Brush.radialGradient(listOf(colors[3].copy(alpha = .55f), Color.Transparent), Offset(size.width * .78f, size.height * .12f), size.maxDimension * .7f))
                drawRect(Brush.radialGradient(listOf(colors[2].copy(alpha = .45f), Color.Transparent), Offset(size.width * .15f, size.height * .95f), size.maxDimension * .6f))
            },
    ) {
        image?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        content()
    }
}
