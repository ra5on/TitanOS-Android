package io.github.ra5on.titanos.vpn

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import io.github.ra5on.titanos.ui.Format

/** Mitteilung, solange der Tunnel läuft, und eine, wenn er sollte, aber nicht konnte. */
object VpnNotify {
    const val CHANNEL = "vpn"
    const val CHANNEL_WAIT = "vpn-wait"
    private const val ID_UP = 7301
    private const val ID_WAIT = 7302
    const val EXTRA_OPEN_VPN = "io.github.ra5on.titanos.vpn.OPEN"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.vpn_channel_text)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WAIT, context.getString(R.string.vpn_channel_wait), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.vpn_channel_wait_text)
            },
        )
    }

    private fun permitted(context: Context) =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openApp(context: Context): PendingIntent? {
        val intent = VpnRuntime.host?.openVpnIntent(context)
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)?.putExtra(EXTRA_OPEN_VPN, true)
            ?: return null
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, VpnActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun show(context: Context, live: VpnLive, settings: VpnSettings) {
        if (!permitted(context)) return
        val routing = context.getString(routingText(settings.routing))
        val text = if (settings.trafficInNotification) {
            context.getString(R.string.vpn_note_traffic, routing, Format.bytes(live.rx), Format.bytes(live.tx))
        } else {
            routing
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_shield)
            .setContentTitle(context.getString(R.string.vpn_note_up))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(live.sinceMs != null)
            .setWhen(live.sinceMs ?: System.currentTimeMillis())
            .setUsesChronometer(live.sinceMs != null)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.vpn_disconnect), action(context, VpnActionReceiver.DISCONNECT))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID_UP, n) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_UP)
    }

    /** Der Tunnel sollte laufen, konnte aber nicht von selbst: Tippen öffnet die App bzw. verbindet. */
    fun waiting(context: Context, problem: VpnProblem) {
        if (!permitted(context)) return
        val text = context.getString(problemText(problem))
        val b = NotificationCompat.Builder(context, CHANNEL_WAIT)
            .setSmallIcon(R.drawable.ic_vpn_shield)
            .setContentTitle(context.getString(R.string.vpn_note_wait))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
        // Aus einer Mitteilung darf die App den Dienst starten.
        if (problem == VpnProblem.Background) b.addAction(0, context.getString(R.string.vpn_connect), action(context, VpnActionReceiver.CONNECT))
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID_WAIT, b.build()) }
    }

    fun cancelWaiting(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_WAIT)
    }
}

fun routingText(r: VpnRouting): Int = when (r) {
    VpnRouting.Nas -> R.string.vpn_route_nas
    VpnRouting.Home -> R.string.vpn_route_home
    VpnRouting.All -> R.string.vpn_route_all
}

fun problemText(p: VpnProblem): Int = when (p) {
    VpnProblem.None -> R.string.vpn_problem_none
    VpnProblem.NoAccess -> R.string.vpn_problem_no_access
    VpnProblem.NotAllowed -> R.string.vpn_problem_not_allowed
    VpnProblem.Dns -> R.string.vpn_problem_dns
    VpnProblem.Start -> R.string.vpn_problem_start
    VpnProblem.Background -> R.string.vpn_problem_background
    VpnProblem.Keys -> R.string.vpn_problem_keys
}

/** „Trennen“/„Verbinden“ aus der Mitteilung. */
class VpnActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VpnRuntime.init(context)
        when (intent.action) {
            DISCONNECT -> VpnRuntime.userSet(false)
            CONNECT -> {
                VpnNotify.cancelWaiting(context)
                VpnRuntime.userSet(true)
            }
        }
    }

    companion object {
        const val DISCONNECT = "io.github.ra5on.titanos.vpn.DISCONNECT"
        const val CONNECT = "io.github.ra5on.titanos.vpn.CONNECT"
    }
}

/** Kachel „TitanOS VPN“ in den Schnelleinstellungen. */
class VpnTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        VpnRuntime.init(this)
        draw()
    }

    private fun draw() {
        val tile = qsTile ?: return
        val live = VpnRuntime.state.value
        tile.label = getString(R.string.vpn_tile_label)
        tile.state = when {
            !live.hasAccess -> Tile.STATE_UNAVAILABLE
            live.status == TunnelStatus.On -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        // Zustand auch in Worten, nicht nur über die Farbe der Kachel.
        val sub = getString(
            when {
                !live.hasAccess -> R.string.vpn_tile_setup
                live.status == TunnelStatus.On -> R.string.vpn_state_on
                live.status == TunnelStatus.Connecting -> R.string.vpn_state_connecting
                else -> R.string.vpn_state_off
            },
        )
        tile.subtitle = sub
        tile.stateDescription = sub
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        VpnRuntime.init(this)
        val live = VpnRuntime.state.value
        if (!live.hasAccess || !VpnRuntime.isPrepared(this)) {
            // Einrichten und die Frage von Android gehen nur in der App.
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(VpnNotify.openApp(this) ?: return)
            } else {
                openBefore34()
            }
            return
        }
        VpnRuntime.userSet(live.status != TunnelStatus.On)
    }

    /** Android 13 kennt nur die Fassung mit Intent (ab 14 die mit PendingIntent, oben). */
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openBefore34() {
        startActivityAndCollapse(VpnRuntime.host?.openVpnIntent(this)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return)
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, VpnTileService::class.java)) }
        }
    }
}
