package io.github.ra5on.titanos.photos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.core.app.NotificationCompat

/**
 * Wohin die Sicherung hochgeladen hat (1.01, Inhaber: „sag, wohin“): nach
 * einem Lauf mit Uploads eine ruhige Mitteilung und eine Zeile in der Karte
 * „Foto-Sicherung“, etwa „14 Fotos hochgeladen nach Fotos/Handy Zeus/2026/11“.
 * Gezählt wird nur, was das NAS abgeschlossen hat (finish), nicht, was es
 * schon kannte.
 */
class BatchCounter {
    var photos = 0
        private set
    var videos = 0
        private set
    private val folders = LinkedHashSet<String>()

    val count: Int get() = photos + videos

    fun add(kind: Kind, folder: String) {
        if (kind == Kind.Video) videos++ else photos++
        folders += folder
    }

    fun result(at: Long): LastBatch = LastBatch(photos, videos, folders.toList(), at)
}

/** Der Ordner, wie man ihn in den Dateien sieht: Name der Freigabe und Pfad darin („Fotos/Handy Zeus/2026/11“). */
fun shownFolder(rootName: String, root: String, path: String): String {
    val share = rootName.ifEmpty { root.substringAfter('/') }
    val dir = path.trim('/').substringBeforeLast('/', "")
    return if (dir.isEmpty()) share else "$share/$dir"
}

/**
 * Ein Ordner für die Anzeige und wie viele es waren: einer wie er ist; mehrere
 * (ein Lauf über zwei Monate) mit dem gemeinsamen Anfang, sonst der erste.
 */
fun summaryFolder(folders: List<String>): Pair<String, Int> {
    if (folders.size <= 1) return folders.firstOrNull().orEmpty() to folders.size
    val parts = folders.map { it.split('/') }
    var n = 0
    while (parts.all { it.size > n && it[n] == parts[0][n] }) n++
    val common = parts[0].take(n).joinToString("/")
    return common.ifEmpty { folders.first() } to folders.size
}

/** „14 Fotos und 2 Videos hochgeladen nach Fotos/Handy Zeus/2026/11“ (mehrere Ordner: „… (3 Ordner)“). */
fun batchText(res: Resources, b: LastBatch): String {
    val what = listOfNotNull(
        b.photos.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.photos_uploaded_photos, it, it) },
        b.videos.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.photos_uploaded_videos, it, it) },
    ).let { if (it.size == 2) res.getString(R.string.photos_uploaded_and, it[0], it[1]) else it.firstOrNull().orEmpty() }
    val (folder, n) = summaryFolder(b.folders)
    return if (n > 1) res.getString(R.string.photos_uploaded_more, what, folder, n) else res.getString(R.string.photos_uploaded_to, what, folder)
}

/** Die ruhige Mitteilung „… hochgeladen nach …“; je NAS eine, die nächste ersetzt sie. */
object UploadNotice {
    const val CHANNEL = "photos-done"

    fun post(context: Context, nas: String, b: LastBatch) {
        if (b.count <= 0) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.photos_done_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.photos_done_channel_text)
            },
        )
        val text = batchText(context.resources, b)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_OPEN_PHOTOS, true)
            ?.putExtra(io.github.ra5on.titanos.net.NasRegistry.EXTRA_NAS, nas)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.getString(R.string.photos_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        if (PhotosRuntime.nasIds().size > 1) PhotosRuntime.nasName(nas).takeIf { it.isNotBlank() }?.let(builder::setSubText)
        if (open != null) builder.setContentIntent(PendingIntent.getActivity(context, ID + nas.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        runCatching { nm.notify("photos-done-$nas", ID, builder.build()) }
    }

    /** Wie das Widget „Fotos“: die App öffnet den Reiter Fotos (PhotosWidget.EXTRA_OPEN_PHOTOS). */
    private const val EXTRA_OPEN_PHOTOS = "io.github.ra5on.titanos.OPEN_PHOTOS"
    private const val ID = 0x7116_0300
}
