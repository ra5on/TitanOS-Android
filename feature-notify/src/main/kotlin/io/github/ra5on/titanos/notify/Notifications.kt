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
import io.github.ra5on.titanos.net.NasNames
import io.github.ra5on.titanos.net.NasRegistry

/**
 * Android-Kanal je NAS und Art; vom System einzeln abschaltbar (Einstellungen › Apps › TitanOS).
 * Die alte Kopplung behält `notify-<art>`, jedes weitere NAS hat `notify-<art>-<nas>`.
 */
fun channelFor(c: Category, nas: String = NasRegistry.LEGACY_ID): String = "notify-" + c.key + NasNames.suffix(nas)

/** Gruppe der Kanäle eines NAS, mit seinem Namen. */
fun groupFor(nas: String): String = "notify" + NasNames.suffix(nas)

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
    const val EXTRA_NAS = "io.github.ra5on.titanos.notify.NAS"
    private const val GROUP = "notify"
    private const val AGENT_PACKAGE = "io.github.ra5on.titanos.agent"

    /** Kanäle aller gekoppelten NAS (je NAS eine Gruppe mit dem NAS-Namen) und der ruhige Kanal der Dauerverbindung. */
    fun ensureChannels(context: Context) {
        val list = NotifyRuntime.nasList()
        if (list.isEmpty()) ensureChannels(context, NotifyNas(NasRegistry.LEGACY_ID, ""))
        for (n in list) ensureChannels(context, n)
    }

    fun ensureChannels(context: Context, nas: NotifyNas) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val group = groupFor(nas.id)
        // Der Name der Gruppe ist der NAS-Name; ohne Namen „Mitteilungen“. Gleiche Kennung: Android nimmt den neuen Namen.
        nm.createNotificationChannelGroup(NotificationChannelGroup(group, nas.name.ifBlank { context.getString(R.string.notify_title) }))
        for (c in Category.entries) {
            nm.createNotificationChannel(
                NotificationChannel(channelFor(c, nas.id), context.getString(titleRes(c)), importanceFor(c)).apply {
                    description = context.getString(textRes(c))
                    this.group = group
                },
            )
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, context.getString(R.string.notify_live_channel), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.notify_live_channel_text)
                setShowBadge(false)
                this.group = GROUP
            },
        )
    }

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Kanäle und Gruppe eines entfernten NAS weg (die der alten Kopplung bleiben für eine neue). */
    fun removeChannels(context: Context, nas: String) {
        if (nas == NasRegistry.LEGACY_ID) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.deleteNotificationChannelGroup(groupFor(nas)) }
    }

    private val OWNED = Regex("^(n[0-9a-f]{10})\\|")

    /** Zu welchem NAS ein Tag in der Leiste gehört ([tagOf] umgekehrt). */
    internal fun ownerOf(tag: String): String = OWNED.find(tag)?.groupValues?.get(1) ?: NasRegistry.LEGACY_ID

    /** Tag einer Mitteilung in der Leiste; je NAS eigen, damit gleiche Kennungen zweier NAS sich nicht ersetzen. */
    fun tagOf(nas: String, note: Note): String = if (nas == NasRegistry.LEGACY_ID) note.notificationTag else "$nas|" + note.notificationTag

    /**
     * Öffnet die App an der passenden Stelle: Antworten des KI-Agenten in der
     * Agent-App, wenn sie installiert ist, sonst die Liste der Mitteilungen
     * des NAS [nas] (die App wechselt dorthin).
     */
    fun openIntent(context: Context, note: Note?, nas: String? = null): PendingIntent? {
        val pm = context.packageManager
        val agent = note?.app == "agent" || note?.kind == Category.Agent
        val intent = (if (agent) pm.getLaunchIntentForPackage(AGENT_PACKAGE) else null)
            ?: pm.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra(EXTRA_OPEN, true)
                note?.link?.let { putExtra(EXTRA_LINK, it) }
                nas?.let { putExtra(EXTRA_NAS, it) }
            }
            ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val code = note?.let { tagOf(nas ?: NasRegistry.LEGACY_ID, it) }?.hashCode() ?: 0
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun post(context: Context, nas: NotifyNas, notes: List<Note>, settings: NotifySettings) {
        if (notes.isEmpty() || !permitted(context)) return
        ensureChannels(context, nas)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val several = NotifyRuntime.nasList().size > 1
        for (n in notes) {
            if (!settings.rings(n.kind)) continue
            val tag = tagOf(nas.id, n)
            val read = PendingIntent.getBroadcast(
                context, tag.hashCode(),
                Intent(context, ReadReceiver::class.java).putExtra(ReadReceiver.EXTRA_ID, n.id).putExtra(ReadReceiver.EXTRA_TAG, tag)
                    .putExtra(ReadReceiver.EXTRA_NAS, nas.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = openIntent(context, n, nas.id)
            val builder = NotificationCompat.Builder(context, channelFor(n.kind, nas.id))
                .setSmallIcon(R.drawable.ic_notify_bell)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
                .setAutoCancel(true)
                .setCategory(if (n.kind == Category.Mail) Notification.CATEGORY_EMAIL else Notification.CATEGORY_STATUS)
                .setGroup(groupFor(nas.id))
            // Mit mehreren NAS steht der Name dabei, von welchem sie kommt.
            if (several && nas.name.isNotBlank()) builder.setSubText(nas.name)
            if (n.millis > 0) builder.setWhen(n.millis).setShowWhen(true)
            // Wie Entwurf 71: zuerst „Ansehen“, dann „Gelesen“.
            if (open != null) {
                builder.setContentIntent(open).addAction(0, context.getString(R.string.notify_action_view), open)
            }
            builder.addAction(0, context.getString(R.string.notify_action_read), read)
            try {
                nm.notify(tag, ID, builder.build())
            } catch (_: SecurityException) {
                // Berechtigung inzwischen entzogen: die Liste zeigt es trotzdem.
            }
        }
    }

    fun cancel(context: Context, tag: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tag, ID)
    }

    /** Alle Mitteilungen eines NAS aus der Leiste (Liste geöffnet, NAS abgeschaltet oder entfernt). */
    fun cancelAll(context: Context, nas: String = NotifyRuntime.active()) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.activeNotifications.filter { it.id == ID && ownerOf(it.tag.orEmpty()) == nas }.forEach { nm.cancel(it.tag, ID) }
    }

    /** Alle Mitteilungen einer Kopplung haben dieselbe Nummer; unterschieden wird am Tag. */
    private const val ID = 0x7116_0100
}

/** „Gelesen“ an einer Mitteilung: in der Liste als gelesen merken und aus der Leiste nehmen. */
class ReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        intent.getStringExtra(EXTRA_TAG)?.let { Poster.cancel(context, it) }
        val nas = intent.getStringExtra(EXTRA_NAS) ?: NasRegistry.LEGACY_ID
        NotifyRuntime.inbox(context, nas).update { Inbox.markRead(it, listOf(id)) to Unit }
        NotifyRuntime.changed()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TAG = "tag"
        const val EXTRA_NAS = "nas"
    }
}
