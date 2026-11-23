package io.github.ra5on.titanos.files

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.github.ra5on.titanos.ui.Format

/**
 * Android-Mitteilungen der Übertragungen (Entwurf 22): eine laufende mit dem
 * Fortschritt aller Downloads und Uploads (zugleich die Mitteilung des
 * Vordergrunddienstes der Aufträge), danach je fertig geladener Datei eine mit
 * „Öffnen“, „Teilen“ und „Im Ordner zeigen“. Kanal „Übertragungen“, ruhig.
 * Die Bausteine sind einfache Funktionen, damit Tests sie prüfen können.
 */
object TransferNotices {
    const val CHANNEL = "transfers"

    /** Die laufende Mitteilung; alle Aufträge teilen sie (eine Zeile im Schirm). */
    const val ONGOING_ID = 0x7117_0200
    private const val DONE_ID = 0x7117_0201
    private const val TAG = "transfer-"

    /** Extra im Intent der App: Übertragungen öffnen (Ring, Seitenmenü, Mitteilung). */
    const val EXTRA_OPEN_TRANSFERS = "io.github.ra5on.titanos.OPEN_TRANSFERS"
    const val ACTION_CANCEL_ALL = "io.github.ra5on.titanos.files.CANCEL_TRANSFERS"

    fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.tr_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.tr_channel_text)
                setShowBadge(false)
            },
        )
    }

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Die App mit der Seite „Übertragungen“. */
    fun openTransfers(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_OPEN_TRANSFERS, true)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        return PendingIntent.getActivity(context, ONGOING_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Überschrift der laufenden Mitteilung: „3 Dateien werden geladen“, bei einer Datei ihr Name. */
    fun ongoingTitle(context: Context, s: TransferSummary): String {
        val res = context.resources
        return when {
            s.count == 1 && s.first != null -> s.first
            s.uploads == 0 -> res.getQuantityString(R.plurals.tr_n_downloading, s.count, s.count)
            s.downloads == 0 -> res.getQuantityString(R.plurals.tr_n_uploading, s.count, s.count)
            else -> res.getQuantityString(R.plurals.tr_n_running, s.count, s.count)
        }
    }

    /** Zeile darunter: „Mietvertrag.pdf und 2 weitere · 4,8 von 9,6 MB“, bei einer Datei „Wird geladen · …“. */
    fun ongoingText(context: Context, s: TransferSummary): String {
        val done = Format.bytes(s.done)
        val total = Format.bytes(s.total)
        val first = s.first
        return when {
            s.count == 1 || first == null ->
                context.getString(if (s.uploads == 1 && s.downloads == 0) R.string.tr_row_uploading else R.string.tr_row_downloading, done, total)
            else -> context.resources.getQuantityString(R.plurals.tr_and_more, s.count - 1, first, s.count - 1) + " · " +
                context.getString(R.string.tr_of, done, total)
        }
    }

    fun ongoing(context: Context, s: TransferSummary): Notification {
        val cancel = PendingIntent.getBroadcast(
            context, ONGOING_ID,
            Intent(context, TransferCancelReceiver::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (s.downloads == 0 && s.uploads > 0) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(ongoingTitle(context, s))
            .setContentText(ongoingText(context, s))
            .setProgress(PROGRESS_MAX, (s.fraction * PROGRESS_MAX).toInt(), s.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.tr_cancel), cancel)
        openTransfers(context)?.let { b.setContentIntent(it).addAction(0, context.getString(R.string.files_transfers), it) }
        return b.build()
    }

    /** ACTION_VIEW auf die Datei in Downloads, nur lesend. */
    fun viewIntent(uri: Uri, name: String): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, TransferWorker.mimeOf(name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(uri: Uri, name: String, title: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType(TransferWorker.mimeOf(name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        title,
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Der Ordner „Downloads“ in der Dateien-App des Systems. */
    fun showIntent(): Intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Fertig geladen: „Mietvertrag.pdf heruntergeladen“, „In Downloads · 2,4 MB“. */
    fun finished(context: Context, t: Transfer): Notification? {
        val uri = t.uri?.let(Uri::parse) ?: return null
        fun pi(code: Int, intent: Intent) = PendingIntent.getActivity(context, (t.id * 4 + code).toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = pi(0, viewIntent(uri, t.name))
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.tr_downloaded_title, t.name))
            .setContentText(context.getString(R.string.tr_in_downloads) + " · " + Format.bytes(t.total))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.tr_open), open)
            .addAction(0, context.getString(R.string.files_share), pi(1, shareIntent(uri, t.name, context.getString(R.string.files_share_chooser))))
            .addAction(0, context.getString(R.string.tr_show), pi(2, showIntent()))
            .build()
    }

    /** Gescheitert: Name und der Weg zu Übertragungen („Erneut“ dort). */
    fun failed(context: Context, t: Transfer): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.tr_failed_title, t.name))
            .setContentText(context.getString(R.string.tr_failed_text))
            .setAutoCancel(true)
            .setSilent(true)
        openTransfers(context)?.let(b::setContentIntent)
        return b.build()
    }

    fun tagOf(t: Transfer) = TAG + t.id

    const val DONE_NOTIFICATION_ID = DONE_ID
    private const val PROGRESS_MAX = 1000
}

/**
 * Hält die Mitteilungen beim Stand der Übertragungen: die laufende höchstens
 * einmal je Sekunde neu (beim Wechsel des Zustands sofort), am Ende je Datei
 * eine. Teilen und Läufe der Foto-Sicherung haben eigene Wege.
 */
class TransferNotifier(private val context: Context, private val store: TransferStore) {
    private var lastPost = 0L

    fun attach() {
        TransferNotices.channel(context)
        store.listen(::changed)
    }

    private fun changed(before: Transfer?, after: Transfer) {
        if (after.direction == Direction.Share || after.backup) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val finished = before?.active != false && !after.active
        val stateChanged = before?.state != after.state
        if (after.active || finished) updateOngoing(nm, force = stateChanged)
        if (!finished || !TransferNotices.permitted(context)) return
        val n = when (after.state) {
            TransferState.Done -> if (after.direction == Direction.Download) TransferNotices.finished(context, after) else null
            TransferState.Failed -> TransferNotices.failed(context, after)
            else -> null
        } ?: return
        runCatching { nm.notify(TransferNotices.tagOf(after), TransferNotices.DONE_NOTIFICATION_ID, n) }
    }

    @Synchronized
    private fun updateOngoing(nm: NotificationManager, force: Boolean) {
        val s = store.summary()
        if (s.count == 0) {
            nm.cancel(TransferNotices.ONGOING_ID)
            lastPost = 0
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPost < 1000) return
        lastPost = now
        if (TransferNotices.permitted(context)) runCatching { nm.notify(TransferNotices.ONGOING_ID, TransferNotices.ongoing(context, s)) }
    }
}

/** „Abbrechen“ in der laufenden Mitteilung: alle laufenden und wartenden Übertragungen. */
class TransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TransferNotices.ACTION_CANCEL_ALL) return
        val store = FilesRuntime.store ?: return
        for (t in store.items.toList().filter { it.active && it.direction != Direction.Share && !it.backup }) {
            if (t.runner == TransferSource.App) FilesRuntime.cancelApp?.invoke(t.id)
            else TransferQueue.cancel(context, t.id, FilesRuntime.apiFor(t.nas))
        }
    }
}
