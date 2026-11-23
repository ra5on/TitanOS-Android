package io.github.ra5on.titanos.compute

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.github.ra5on.titanos.net.throwIfError
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/** Bilder vom NAS (Kamerabilder, Ereignisse, App-Symbole) mit kleinem Zwischenspeicher. */
object NasImages {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(path: String): Bitmap? = cache.get(path)

    suspend fun load(access: ComputeAccess, path: String, maxWidth: Int = 1280, keep: Boolean = true): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = access.bytes(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp != null && keep) cache.put(path, bmp)
        bmp
    }
}

/**
 * Ein Bild vom NAS. [refreshKey] lädt neu (etwa alle paar Sekunden für ein
 * Kamerabild); bis dahin bleibt das alte Bild stehen.
 */
@Composable
fun NasImage(
    access: ComputeAccess,
    path: String,
    description: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    refreshKey: Any? = null,
    onError: (Throwable) -> Unit = {},
) {
    var bitmap by remember(path) { mutableStateOf(NasImages.cached(path)) }
    LaunchedEffect(path, refreshKey) {
        try {
            NasImages.load(access, path)?.let { bitmap = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }
    val sem = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), null, modifier.then(sem), contentScale = contentScale)
    } else {
        Box(modifier.then(sem).background(Titan.colors.chip))
    }
}

/**
 * Media3-Datenquelle für Adressen `titannas://nas/api/v1/…` über die
 * Verbindung der App (Sitzung als Cookie, gepinntes Zertifikat). Für
 * Ereignis-Clips (MP4) und Aufnahmen (HLS: Liste und Stücke über dieselbe
 * Quelle, relative Adressen bleiben unter titannas://).
 */
@OptIn(UnstableApi::class)
class NasHttpDataSource(private val access: ComputeAccess) : BaseDataSource(true) {
    private var response: Response? = null
    private var stream: InputStream? = null
    private var left = C.LENGTH_UNSET.toLong()
    private var uri: Uri? = null
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val path = pathOf(dataSpec.uri) ?: throw IOException("not a NAS address")
        transferInitializing(dataSpec)
        val r = access.execute { base ->
            val b = Request.Builder().url(access.url(base, path))
            if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) (dataSpec.position + dataSpec.length - 1).toString() else ""
                b.header("Range", "bytes=${dataSpec.position}-$end")
            }
            b.build()
        }
        r.throwIfError()
        response = r
        val input = r.body.byteStream()
        if (r.code == 200 && dataSpec.position > 0) {
            var skip = dataSpec.position
            while (skip > 0) {
                val n = input.skip(skip)
                if (n <= 0) throw IOException("cannot seek")
                skip -= n
            }
        }
        stream = input
        val body = r.body.contentLength()
        left = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            body < 0 -> C.LENGTH_UNSET.toLong()
            r.code == 200 -> body - dataSpec.position
            else -> body
        }
        started = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val want = if (left == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), left).toInt()
        val n = stream?.read(buffer, offset, want) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (left != C.LENGTH_UNSET.toLong()) left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null
        response = null
        if (started) transferEnded()
        started = false
        uri = null
    }

    class Factory(private val access: ComputeAccess) : DataSource.Factory {
        override fun createDataSource(): DataSource = NasHttpDataSource(access)
    }

    companion object {
        const val SCHEME = "titannas"

        /** Adresse für Media3 zu einem API-Pfad wie `/nvr/events/1/clip.mp4`. */
        fun uriOf(apiPath: String): Uri = Uri.parse("$SCHEME://nas/api/v1" + (if (apiPath.startsWith("/")) apiPath else "/$apiPath"))

        /** API-Pfad (ohne /api/v1) mit Abfrage. */
        fun pathOf(uri: Uri): String? {
            if (uri.scheme != SCHEME) return null
            val p = uri.encodedPath ?: return null
            if (!p.startsWith("/api/v1/")) return null
            val q = uri.encodedQuery
            return p.removePrefix("/api/v1") + (if (q != null) "?$q" else "")
        }
    }
}
