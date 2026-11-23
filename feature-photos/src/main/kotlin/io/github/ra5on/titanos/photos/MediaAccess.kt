package io.github.ra5on.titanos.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant

/** Wie weit die App die Aufnahmen sehen darf. */
enum class Access { Full, Partial, None }

object MediaAccess {
    /** Alles, was die App für die Sicherung fragt (Android 14+: dabei auch die Teilauswahl). */
    val PERMISSIONS: Array<String> = buildList {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    private fun granted(context: Context, p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /**
     * Voll: Fotos und Videos. Teilweise: nur ausgewählte (Android 14+); dann
     * sieht die App keine neuen Aufnahmen und sagt das.
     */
    fun access(context: Context): Access = when {
        granted(context, Manifest.permission.READ_MEDIA_IMAGES) && granted(context, Manifest.permission.READ_MEDIA_VIDEO) -> Access.Full
        granted(context, Manifest.permission.READ_MEDIA_IMAGES) || granted(context, Manifest.permission.READ_MEDIA_VIDEO) -> Access.Partial
        Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Partial
        else -> Access.None
    }

    /** Mit dieser Erlaubnis bleibt der Ort im EXIF (setRequireOriginal). */
    fun location(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)
}

/** Eine Aufnahme aus dem MediaStore. */
data class MediaItem(
    val media: Media,
    val volume: String,
    val bucket: String,
) {
    val id: Long get() = media.id

    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (media.kind == Kind.Video) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume),
            media.id,
        )
}

/** Ein Ordner am Handy (RELATIVE_PATH) mit Namen und Anzahl. */
data class LocalFolder(val path: String, val name: String, val count: Int)

/** Liest den MediaStore: Aufnahmen, Ordner, Generationen. */
class MediaScanner(private val context: Context) {

    fun scan(): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.VOLUME_NAME,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val kind = if (c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) Kind.Video else Kind.Photo
                val taken = if (!c.isNull(6) && c.getLong(6) > 0) Instant.ofEpochMilli(c.getLong(6)) else Instant.ofEpochSecond(c.getLong(7))
                val name = c.getString(2) ?: continue
                out += MediaItem(
                    Media(c.getLong(0), kind, name, c.getString(3).orEmpty(), c.getLong(5), taken, c.getLong(8), c.getString(4).orEmpty()),
                    volume = c.getString(9) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    bucket = c.getString(4).orEmpty(),
                )
            }
        }
        return out
    }

    /** Die Ordner mit Aufnahmen, die größten zuerst. */
    fun folders(items: List<MediaItem>): List<LocalFolder> =
        items.groupBy { it.media.folder }.map { (path, list) ->
            LocalFolder(path, list.first().bucket.ifEmpty { path.trim('/').substringAfterLast('/') }, list.size)
        }.sortedWith(compareByDescending<LocalFolder> { defaultFolder(it.path) }.thenByDescending { it.count })

    /** Generation und Version je Speicher (MediaStore.getGeneration/getVersion). */
    fun generations(): Pair<Map<String, Long>, Map<String, String>> {
        val gens = HashMap<String, Long>()
        val versions = HashMap<String, String>()
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            gens[volume] = MediaStore.getGeneration(context, volume)
            versions[volume] = MediaStore.getVersion(context, volume)
        }
        return gens to versions
    }

    /** Öffnet eine Aufnahme; mit der Erlaubnis für den Ort im Original samt EXIF-Ort. */
    fun open(item: MediaItem): InputStream {
        val uri = if (MediaAccess.location(context)) MediaStore.setRequireOriginal(item.uri) else item.uri
        return context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot read ${item.media.name}")
    }

    /** SHA-256 und Länge der Bytes, die genau so hochgeladen würden. */
    fun hash(item: MediaItem): Pair<String, Long> = open(item).use { hashStream(it) }
}

fun hashStream(input: InputStream): Pair<String, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
        total += n
    }
    return digest.digest().joinToString("") { "%02x".format(it) } to total
}
