package io.github.ra5on.titanos.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat

/** Android-Kanal je Art; vom System einzeln abschaltbar (Einstellungen › Apps › TitanOS). */
fun channelFor(c: Category): String = "notify-" + c.key

/** Ruhiger Kanal der dauerhaften Mitteilung „Mit dem NAS verbunden“. */
const val CHANNEL_LIVE = "notify-live"

/** Wichtigkeit je Art: NAS-Warnungen und Agent laut, NVR laut, Mail und Termine normal. */
fun importanceFor(c: Category): Int = when (c) {
    Category.Nas, Category.Agent, Category.Nvr -> NotificationManager.IMPORTANCE_HIGH
    Category.Mail, Category.Calendar -> NotificationManager.IMPORTANCE_DEFAULT
}

fun titleRes(c: Category): Int = when (c) {
    Category.Nas -> R.string.notify_kind_nas
    Category.Mail -> R.string.notify_kind_mail
    Category.Calendar -> R.string.notify_kind_calendar
    Category.Agent -> R.string.notify_kind_agent
    Category.Nvr -> R.string.notify_kind_nvr
}

fun textRes(c: Category): Int = when (c) {
    Category.Nas -> R.string.notify_kind_nas_text
    Category.Mail -> R.string.notify_kind_mail_text
    Category.Calendar -> R.string.notify_kind_calendar_text
    Category.Agent -> R.string.notify_kind_agent_text
    Category.Nvr -> R.string.notify_kind_nvr_text
}

/** Zeigt Mitteilungen in Android an; Kanäle legt [ensureChannels] an. */
object Poster {
    const val EXTRA_OPEN = "io.github.ra5on.titanos.notify.OPEN"
    const val EXTRA_LINK = "io.github.ra5on.titanos.notify.LINK"
    private const val GROUP = "notify"
    private const val AGENT_PACKAGE = "io.github.ra5on.titanos.agent"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, context.getString(R.string.notify_title)))
        for (c in Category.entries) {
            nm.createNotificationChannel(
                NotificationChannel(channelFor(c), context.getString(titleRes(c)), importanceFor(c)).apply {
                    description = context.getString(textRes(c))
                    group = GROUP
                },
            )
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, context.getString(R.string.notify_live_channel), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.notify_live_channel_text)
                setShowBadge(false)
                group = GROUP
            },
        )
    }

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Öffnet die App an der passenden Stelle: Antworten des KI-Agenten in der
     * Agent-App, wenn sie installiert ist, sonst die Liste der Mitteilungen.
     */
    fun openIntent(context: Context, note: Note?): PendingIntent? {
        val pm = context.packageManager
        val agent = note?.app == "agent" || note?.kind == Category.Agent
        val intent = (if (agent) pm.getLaunchIntentForPackage(AGENT_PACKAGE) else null)
            ?: pm.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra(EXTRA_OPEN, true)
                note?.link?.let { putExtra(EXTRA_LINK, it) }
            }
            ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val code = note?.notificationTag?.hashCode() ?: 0
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun post(context: Context, notes: List<Note>, settings: NotifySettings) {
        if (notes.isEmpty() || !permitted(context)) return
        ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        for (n in notes) {
            if (!settings.rings(n.kind)) continue
            val read = PendingIntent.getBroadcast(
                context, n.notificationTag.hashCode(),
                Intent(context, ReadReceiver::class.java).putExtra(ReadReceiver.EXTRA_ID, n.id).putExtra(ReadReceiver.EXTRA_TAG, n.notificationTag),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = openIntent(context, n)
            val builder = NotificationCompat.Builder(context, channelFor(n.kind))
                .setSmallIcon(R.drawable.ic_notify_bell)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
                .setAutoCancel(true)
                .setCategory(if (n.kind == Category.Mail) Notification.CATEGORY_EMAIL else Notification.CATEGORY_STATUS)
                .setGroup(GROUP)
            if (n.millis > 0) builder.setWhen(n.millis).setShowWhen(true)
            // Wie Entwurf 71: zuerst „Ansehen“, dann „Gelesen“.
            if (open != null) {
                builder.setContentIntent(open).addAction(0, context.getString(R.string.notify_action_view), open)
            }
            builder.addAction(0, context.getString(R.string.notify_action_read), read)
            try {
                nm.notify(n.notificationTag, ID, builder.build())
            } catch (_: SecurityException) {
                // Berechtigung inzwischen entzogen: die Liste zeigt es trotzdem.
            }
        }
    }

    fun cancel(context: Context, tag: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tag, ID)
    }

    /** Alle Mitteilungen dieser Art aus der Leiste (Liste geöffnet). */
    fun cancelAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.activeNotifications.filter { it.id == ID }.forEach { nm.cancel(it.tag, ID) }
    }

    /** Alle Mitteilungen einer Kopplung haben dieselbe Nummer; unterschieden wird am Tag. */
    private const val ID = 0x7116_0100
}

/** „Gelesen“ an einer Mitteilung: in der Liste als gelesen merken und aus der Leiste nehmen. */
class ReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        intent.getStringExtra(EXTRA_TAG)?.let { Poster.cancel(context, it) }
        NotifyRuntime.inbox(context).update { Inbox.markRead(it, listOf(id)) to Unit }
        NotifyRuntime.changed()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TAG = "tag"
    }
}
