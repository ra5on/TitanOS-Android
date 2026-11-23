package io.github.ra5on.titanos.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Bilder vom NAS: Vorschaubilder über `files/thumb` (höchstens vier
 * gleichzeitig, `thumb_busy` wird wiederholt) und das Original für den
 * Betrachter, verkleinert auf die Bildschirmgröße. Zwischengespeichert im RAM
 * nach Freigabe, Pfad und Änderungszeit; das Original liegt kurz im Cache.
 */
/**
 * Größen der Vorschaubilder (1.02, Inhaber: „Vorschaubilder einen Ticken
 * schärfer“). Angefragt wird die echte Pixelgröße der Kachel, aufgerundet auf
 * feste Stufen, damit die Caches greifen. Weil die Kachel quadratisch
 * beschnitten wird, muss die kurze Seite des Bildes die Kachel füllen: bei 4:3
 * ist die lange Seite ein Drittel größer.
 */
object ThumbSizes {
    /** Kantenlängen, die das NAS rendert (`thumbs.Sizes` im NAS: 160, 320, 640, 1280; mehr gibt es nicht). */
    val NAS = listOf(160, 320, 640, 1280)

    /** Stufen für die Vorschaubilder von Android (MediaStore). */
    val LOCAL = listOf(256, 384, 512, 768, 1024)

    /** Lange Seite, damit ein Bild im Seitenverhältnis 4:3 eine Kachel von [cellPx] beschnitten ganz füllt. */
    fun cover(cellPx: Int): Int = (cellPx * 4 + 2) / 3

    /** Stufe für eine Kachel von [cellPx] Pixeln bei Bildern vom NAS. */
    fun nas(cellPx: Int): Int = NAS.firstOrNull { it >= cover(cellPx) } ?: NAS.last()

    /** Stufe für eine Kachel von [cellPx] Pixeln bei Bildern am Handy. */
    fun local(cellPx: Int): Int = LOCAL.firstOrNull { it >= cover(cellPx) } ?: LOCAL.last()
}

object NasImages {
    // Etwa ein Sechstel dessen, was Android der App gibt, höchstens 96 MB: ein Bildschirm voll scharfer Kacheln und mehr.
    private val memory = object : LruCache<String, Bitmap>(minOf(96L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val none = java.util.Collections.synchronizedSet(HashSet<String>())
    private val gate = Semaphore(4)
    private val full = Semaphore(2)

    /** Bis zu dieser Größe lädt der Betrachter das Original (wie das NAS für Vorschaubilder). */
    const val ORIGINAL_LIMIT = 64L * 1024 * 1024

    private fun key(root: String, path: String, modified: String, size: Int) = "$root|$path|$modified|$size"

    fun cached(root: String, path: String, modified: String, size: Int): Bitmap? = memory.get(key(root, path, modified, size))

    /**
     * Vorschaubild; null, wenn das NAS keines hat (415) oder es nicht geht.
     * Mit [context] liegt es auch auf der Platte (`cache/thumbs`), damit
     * Zurückscrollen und der nächste Start nicht erneut laden.
     */
    suspend fun thumb(api: FilesApi, root: String, path: String, modified: String, size: Int, context: Context? = null): Bitmap? {
        val k = key(root, path, modified, size)
        memory.get(k)?.let { return it }
        if (k in none) return null
        val disk = context?.let { thumbFile(it, k) }
        if (disk != null && disk.isFile) {
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(disk.path) }.getOrNull() }?.let {
                memory.put(k, it)
                return it
            }
        }
        return gate.withPermit {
            memory.get(k) ?: try {
                val bytes = api.thumb(root, path, size, modified)
                if (bytes == null) {
                    none += k
                    null
                } else {
                    if (disk != null) withContext(Dispatchers.IO) { storeThumb(disk, bytes) }
                    withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }?.also { memory.put(k, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Das Original, verkleinert auf höchstens [maxPx] an der längeren Seite. */
    suspend fun original(context: Context, api: FilesApi, root: String, path: String, modified: String, sizeBytes: Long, maxPx: Int): Bitmap? {
        val k = key(root, path, modified, -maxPx)
        memory.get(k)?.let { return it }
        if (sizeBytes > ORIGINAL_LIMIT) return null
        if (!FilesSettings.allowed(context, FilesSettings.get(context).originals)) return null
        return full.withPermit {
            memory.get(k) ?: try {
                val file = cacheFile(context, root, path, modified)
                if (!file.exists()) api.downloadTo(root, path, file, ORIGINAL_LIMIT)
                withContext(Dispatchers.Default) { decode(file, maxPx) }?.also { memory.put(k, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    fun decode(file: File, maxPx: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = maxOf(w, h).toFloat() / maxPx
            if (scale > 1f) decoder.setTargetSize((w / scale).toInt().coerceAtLeast(1), (h / scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        null
    }

    /** Eine Kopie im Cache je Datei und Stand; ältere räumt Android bei Platzmangel weg. */
    fun cacheFile(context: Context, root: String, path: String, modified: String): File {
        val dir = File(context.cacheDir, "view").apply { mkdirs() }
        val id = MessageDigest.getInstance("SHA-256").digest("$root|$path|$modified".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val ext = Kinds.ext(path)
        return File(dir, if (ext.isEmpty()) id else "$id.$ext")
    }

    private fun thumbFile(context: Context, key: String): File {
        val id = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "thumbs"), id)
    }

    @Volatile private var written = 0

    private fun storeThumb(file: File, bytes: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
            // Gelegentlich aufräumen: höchstens 160 MB Vorschaubilder auf der Platte.
            if (++written % 64 == 0) trimDir(file.parentFile!!, 160L * 1024 * 1024)
        }
    }

    private fun trimDir(dir: File, maxBytes: Long) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    /** Räumt den Cache der Vorschau auf, wenn er größer als [maxBytes] ist (älteste zuerst). */
    fun trim(context: Context, maxBytes: Long = 512L * 1024 * 1024) {
        val files = File(context.cacheDir, "view").listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}

/**
 * Vorschaubild einer Datei am NAS; bis es da ist oder ohne Vorschau zeigt es
 * [fallback]. [size] 0: passend zur echten Größe der Fläche ([ThumbSizes.nas]).
 */
@Composable
fun NasThumb(
    api: FilesApi?,
    root: String,
    path: String,
    modified: String,
    modifier: Modifier = Modifier,
    size: Int = 0,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable BoxScope.() -> Unit,
) {
    if (size > 0) {
        NasThumbSized(api, root, path, modified, modifier, size, contentScale, fallback)
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val edge = maxOf(constraints.maxWidth.takeIf { it != androidx.compose.ui.unit.Constraints.Infinity } ?: 0, constraints.maxHeight.takeIf { it != androidx.compose.ui.unit.Constraints.Infinity } ?: 0)
        NasThumbSized(api, root, path, modified, Modifier.matchParentSize(), ThumbSizes.nas(if (edge > 0) edge else 320), contentScale, fallback)
    }
}

@Composable
private fun NasThumbSized(
    api: FilesApi?,
    root: String,
    path: String,
    modified: String,
    modifier: Modifier,
    size: Int,
    contentScale: ContentScale,
    fallback: @Composable BoxScope.() -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState(NasImages.cached(root, path, modified, size), api, root, path, modified, size) {
        if (value == null && api != null && Kinds.hasThumb(path) && FilesSettings.allowed(ctx, FilesSettings.get(ctx).thumbs)) {
            value = NasImages.thumb(api, root, path, modified, size, ctx.applicationContext)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = contentScale) else fallback()
    }
}

/**
 * Ein Bild zum Zoomen: zwei Finger oder Doppeltippen (2,5-fach an der
 * Stelle). [onZoom] meldet, ob gezoomt ist; dann wischt das Blättern nicht.
 */
@Composable
fun ZoomImage(image: ImageBitmap, description: String?, onZoom: (Boolean) -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier) {
    ZoomBox(onZoom, onTap, modifier) {
        Image(image, description, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
fun ZoomBox(onZoom: (Boolean) -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (box.width * (s - 1f)) / 2f
        val maxY = (box.height * (s - 1f)) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    LaunchedEffect(scale > 1.01f) { onZoom(scale > 1.01f) }
    Box(
        modifier
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val s = 2.5f
                            val center = Offset(box.width / 2f, box.height / 2f)
                            scale = s
                            offset = clamp((center - at) * (s - 1f), s)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGestureZoom({ scale > 1.01f }) { pan, zoom ->
                    val s = (scale * zoom).coerceIn(1f, 8f)
                    scale = s
                    offset = if (s <= 1.01f) Offset.Zero else clamp(offset + pan, s)
                    s > 1.01f
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        content = content,
    )
}

/**
 * Zwei Finger zoomen immer; ein Finger verschiebt nur, wenn gezoomt ist –
 * sonst bleibt die Geste für das Blättern im Pager.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitEachGestureZoom(isZoomed: () -> Boolean, onGesture: (Offset, Float) -> Boolean) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoomed = isZoomed()
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            val zoom = calculateZoomSafe(event)
            val pan = calculatePanSafe(event)
            if (pressed >= 2 || zoomed) {
                zoomed = onGesture(pan, zoom)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

private fun calculateZoomSafe(event: androidx.compose.ui.input.pointer.PointerEvent): Float =
    event.calculateZoom()

private fun calculatePanSafe(event: androidx.compose.ui.input.pointer.PointerEvent): Offset =
    event.calculatePan()

private fun androidx.compose.ui.input.pointer.PointerInputChange.positionChanged(): Boolean = position != previousPosition
