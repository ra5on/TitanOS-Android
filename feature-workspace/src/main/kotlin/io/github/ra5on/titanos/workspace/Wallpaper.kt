package io.github.ra5on.titanos.workspace

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.ui.components.DeskWallpaper
import io.github.ra5on.titanos.ui.components.DeskWallpapers
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/*
 * Der Hintergrund des Arbeitsplatzes aus derselben Einstellung wie im Web
 * (`desktop.wallpaper`): gezeichnete Verläufe oder Fotos vom NAS
 * (`/backgrounds/<id>.webp`), ein eigenes Bild über `/api/v1/background`.
 * Gewählt wird er in der Systemsteuerung › Darstellung; Farben und Zeichnung
 * teilen sich beide über core-ui DeskWallpaper.
 */

/** Die Farben eines Hintergrunds (core-ui DeskWallpapers, gemeinsam mit der Systemsteuerung). */
fun wallpaperColors(w: Wallpaper, light: Boolean): List<Color> = DeskWallpapers.colors(w.custom, w.id, light)

/** Ob der Hintergrund hell ist (Schrift der Symbole dunkel). */
fun wallpaperLight(w: Wallpaper, light: Boolean): Boolean = DeskWallpapers.light(w.custom, w.id, light)

private val cache = HashMap<String, ImageBitmap>()

/** Die Adresse des Bildes am NAS, null bei einem gezeichneten Hintergrund. */
fun wallpaperPath(w: Wallpaper): String? = DeskWallpapers.path(w.custom, w.id, w.version)

/** Der Hintergrund als Fläche wie in der Systemsteuerung (core-ui DeskWallpaper); das Bild kommt über die Verbindung des Arbeitsplatzes. */
@Composable
fun WallpaperFill(w: Wallpaper, light: Boolean, access: ComputeAccess?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
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
    DeskWallpaper(w.custom, w.id, light, image, modifier, content)
}
