package io.github.ra5on.titanos.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Geöffnete Bilder in Originalgröße (1.02, Inhaber: „ein geöffnetes Bild immer
 * in Originalgröße, nicht heruntergerechnet“). Erst das ganze Bild verkleinert
 * auf etwa Bildschirmgröße, beim Zoomen Kacheln des Originals in voller
 * Auflösung (BitmapRegionDecoder, JPEG, PNG, WebP, HEIF). Im Speicher liegen
 * nie mehr als das verkleinerte Bild und die sichtbaren Kacheln, auch bei
 * 50 Megapixeln oder einem Panorama. Die EXIF-Drehung gilt für beides gleich.
 */

/** Rechnungen des Kachel-Betrachters ohne Android (Tests). */
object TileMath {
    /** Kantenlänge einer Kachel in Pixeln des Bildschirms (bei Stufe [sample] also `TILE * sample` Pixel des Originals). */
    const val TILE = 512

    /** Höchstens so viele Pixel hat das verkleinerte ganze Bild (8 MP, rund 32 MB). */
    const val BASE_PIXELS = 8_000_000L

    /** EXIF-Drehungen 5–8 tauschen Breite und Höhe. */
    fun transposed(orientation: Int): Boolean = orientation in 5..8

    /** Breite und Höhe, wie das Bild erscheint. */
    fun shown(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        if (transposed(orientation)) height to width else width to height

    /** Zweierpotenz, mit der das ganze Bild höchstens [maxEdge] Pixel lang und [BASE_PIXELS] groß wird. */
    fun baseSample(width: Int, height: Int, maxEdge: Int): Int {
        var s = 1
        while (max(width, height) / s > maxEdge || (width.toLong() / s) * (height.toLong() / s) > BASE_PIXELS) s *= 2
        return s
    }

    /** Größte Zweierpotenz, deren Pixel noch höchstens einen Bildschirmpunkt groß sind ([pxPerImagePx]: Bildschirmpixel je Bildpixel). */
    fun sampleFor(pxPerImagePx: Float): Int {
        if (pxPerImagePx >= 1f) return 1
        var s = 1
        while (s * 2 * pxPerImagePx <= 1f) s *= 2
        return s
    }

    /** So weit lässt sich zoomen: mindestens 4-fach, sonst bis 2 Bildschirmpixel je Bildpixel (ein Panorama braucht mehr als 8-fach). */
    fun maxScale(fit: Float): Float = max(4f, 2f / fit)

    /** Kacheln (Spalte, Zeile) der Stufe [sample], die den Bereich [visible] (Pixel des Originals) treffen. */
    fun tiles(visible: RectF, width: Int, height: Int, sample: Int): List<Pair<Int, Int>> {
        val edge = (TILE * sample).toFloat()
        val left = max(0f, visible.left)
        val top = max(0f, visible.top)
        val right = min(width.toFloat(), visible.right)
        val bottom = min(height.toFloat(), visible.bottom)
        if (right <= left || bottom <= top) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        for (row in floor(top / edge).toInt()..(ceil(bottom / edge).toInt() - 1)) {
            for (col in floor(left / edge).toInt()..(ceil(right / edge).toInt() - 1)) out += col to row
        }
        return out
    }

    /** Fläche einer Kachel im Original. */
    fun tileRect(col: Int, row: Int, sample: Int, width: Int, height: Int): Rect {
        val edge = TILE * sample
        return Rect(col * edge, row * edge, min(width, (col + 1) * edge), min(height, (row + 1) * edge))
    }

    /** Bildet Pixel des Originals auf das erscheinende Bild ab (EXIF-Drehung 1–8). */
    fun orientationMatrix(orientation: Int, width: Int, height: Int): Matrix {
        val m = Matrix()
        val w = width.toFloat()
        val h = height.toFloat()
        when (orientation) {
            2 -> m.setValues(floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f))
            3 -> m.setValues(floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f))
            4 -> m.setValues(floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f))
            5 -> m.setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
            6 -> m.setValues(floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f))
            7 -> m.setValues(floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f))
            8 -> m.setValues(floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f))
        }
        return m
    }

    /**
     * Was vom Bild gerade zu sehen ist, in Pixeln des erscheinenden Bildes:
     * Fläche [box], darin das Bild eingepasst mit [fit], gezoomt um [scale]
     * um die Mitte und verschoben um [offset] (wie ZoomBox es zeichnet).
     */
    fun visibleShown(box: IntSize, shownW: Int, shownH: Int, fit: Float, scale: Float, offset: Offset): RectF {
        val cx = box.width / 2f
        val cy = box.height / 2f
        fun unzoom(x: Float, y: Float) = Offset(cx + (x - cx - offset.x) / scale, cy + (y - cy - offset.y) / scale)
        val a = unzoom(0f, 0f)
        val b = unzoom(box.width.toFloat(), box.height.toFloat())
        val left = (box.width - shownW * fit) / 2f
        val top = (box.height - shownH * fit) / 2f
        return RectF((a.x - left) / fit, (a.y - top) / fit, (b.x - left) / fit, (b.y - top) / fit)
    }
}

/** Ein Bild, aus dem sich Stücke in voller Auflösung lesen lassen. */
class TiledSource private constructor(
    private val fd: ParcelFileDescriptor,
    private val decoder: BitmapRegionDecoder,
    /** EXIF-Drehung 1–8 (1: keine). */
    val orientation: Int,
) : Closeable {
    val width: Int = decoder.width
    val height: Int = decoder.height
    val shownWidth: Int get() = TileMath.shown(width, height, orientation).first
    val shownHeight: Int get() = TileMath.shown(width, height, orientation).second

    /** Das ganze Bild verkleinert mit [sample] (Pixel des Originals, ungedreht). */
    fun decode(rect: Rect, sample: Int): Bitmap? = synchronized(this) {
        if (closed) return null
        runCatching {
            decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }.getOrNull()
    }

    @Volatile private var closed = false

    override fun close() {
        synchronized(this) {
            closed = true
            runCatching { decoder.recycle() }
            runCatching { fd.close() }
        }
    }

    companion object {
        /** Öffnet eine Datei (Original vom NAS im Cache); null, wenn das Format keine Stücke kann (GIF, AVIF, …). */
        fun open(file: File): TiledSource? = open(
            { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) },
        )

        /** Öffnet eine Aufnahme am Handy (content://). */
        fun open(context: Context, uri: Uri): TiledSource? = open({ context.contentResolver.openFileDescriptor(uri, "r") })

        private fun open(fds: () -> ParcelFileDescriptor?): TiledSource? {
            val orientation = runCatching {
                fds()?.use { ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }.getOrNull()?.takeIf { it in 1..8 } ?: 1
            val fd = runCatching { fds() }.getOrNull() ?: return null
            val decoder = runCatching { BitmapRegionDecoder.newInstance(fd) }.getOrNull()
            if (decoder == null || decoder.width <= 0 || decoder.height <= 0) {
                decoder?.recycle()
                runCatching { fd.close() }
                return null
            }
            return TiledSource(fd, decoder, orientation)
        }
    }
}

private data class TileKey(val sample: Int, val col: Int, val row: Int)

/**
 * Das geöffnete Bild: [preview] (schon gedreht, etwa das Vorschaubild) sofort,
 * darüber das ganze Original verkleinert, beim Zoomen Kacheln in voller
 * Auflösung. Zwei Finger oder Doppeltippen zoomen wie [ZoomImage].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun TiledImage(source: TiledSource, preview: Bitmap?, onZoom: (Boolean) -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(source) { onDispose { source.close() } }
    val zoom = rememberZoomState()
    val sw = source.shownWidth
    val sh = source.shownHeight
    val toShown = remember(source) { TileMath.orientationMatrix(source.orientation, source.width, source.height) }
    val toRaw = remember(source) { Matrix().also { toShown.invert(it) } }
    val screenEdge = maxOf(
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp,
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp,
    ).let { with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.unit.Dp(it.toFloat()).roundToPx() } }
    val baseSample = remember(source, screenEdge) { TileMath.baseSample(source.width, source.height, max(2048, screenEdge)) }
    val base by produceState<Bitmap?>(null, source) {
        value = withContext(Dispatchers.IO) { source.decode(Rect(0, 0, source.width, source.height), baseSample) }
    }
    val tiles = remember(source) {
        object : LruCache<TileKey, Bitmap>(48 * 1024 * 1024) {
            override fun sizeOf(key: TileKey, value: Bitmap) = value.byteCount
        }
    }
    var loaded by remember(source) { mutableIntStateOf(0) }
    val fit = if (zoom.box.width > 0 && zoom.box.height > 0) min(zoom.box.width.toFloat() / sw, zoom.box.height.toFloat() / sh) else 1f
    zoom.maxScale = TileMath.maxScale(fit)

    // Welche Kacheln gerade gebraucht werden: Stufe nach Zoom, Fläche nach Ausschnitt.
    LaunchedEffect(source) {
        snapshotFlow {
            val box = zoom.box
            if (box.width == 0 || box.height == 0) return@snapshotFlow null
            val f = min(box.width.toFloat() / sw, box.height.toFloat() / sh)
            val sample = TileMath.sampleFor(f * zoom.scale)
            if (sample >= baseSample) return@snapshotFlow null
            val shownRect = TileMath.visibleShown(box, sw, sh, f, zoom.scale, zoom.offset)
            val raw = RectF(shownRect).also { toRaw.mapRect(it) }
            sample to TileMath.tiles(raw, source.width, source.height, sample)
        }.distinctUntilChanged().collectLatest { need ->
            val (sample, list) = need ?: return@collectLatest
            for ((col, row) in list) {
                val key = TileKey(sample, col, row)
                if (tiles.get(key) != null) continue
                val bmp = withContext(Dispatchers.IO) { source.decode(TileMath.tileRect(col, row, sample, source.width, source.height), sample) } ?: continue
                tiles.put(key, bmp)
                loaded++
            }
        }
    }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    ZoomBox(zoom, onZoom, onTap, modifier) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") loaded
            val f = min(size.width / sw, size.height / sh)
            val left = (size.width - sw * f) / 2f
            val top = (size.height - sh * f) / 2f
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.save()
                nc.translate(left, top)
                nc.scale(f, f)
                val b = base
                if (b == null && preview != null) nc.drawBitmap(preview, null, RectF(0f, 0f, sw.toFloat(), sh.toFloat()), paint)
                nc.concat(toShown)
                if (b != null) nc.drawBitmap(b, null, RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), paint)
                val sample = TileMath.sampleFor(f * zoom.scale)
                if (sample < baseSample) {
                    val shownRect = TileMath.visibleShown(zoom.box.takeIf { it.width > 0 } ?: IntSize(size.width.toInt(), size.height.toInt()), sw, sh, f, zoom.scale, zoom.offset)
                    val raw = RectF(shownRect).also { toRaw.mapRect(it) }
                    for ((col, row) in TileMath.tiles(raw, source.width, source.height, sample)) {
                        val t = tiles.get(TileKey(sample, col, row)) ?: continue
                        nc.drawBitmap(t, null, RectF(TileMath.tileRect(col, row, sample, source.width, source.height)), paint)
                    }
                }
                nc.restore()
            }
        }
    }
}
