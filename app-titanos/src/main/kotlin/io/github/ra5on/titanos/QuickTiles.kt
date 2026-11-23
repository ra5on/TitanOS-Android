package io.github.ra5on.titanos

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import io.github.ra5on.titanos.net.NasRegistry
import io.github.ra5on.titanos.photos.PhotoScheduler
import io.github.ra5on.titanos.photos.PhotosRuntime
import io.github.ra5on.titanos.widgets.PhotosWidget
import io.github.ra5on.titanos.widgets.WidgetRefresh
import io.github.ra5on.titanos.widgets.WidgetRuntime
import io.github.ra5on.titanos.widgets.WidgetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import io.github.ra5on.titanos.widgets.R as WidgetsR

/**
 * Schnelleinstellungen (Kacheln oben in Android): Foto-Sicherung jetzt
 * starten oder pausieren, NAS-Zustand, Agent fragen. Die Kachel „TitanOS VPN“
 * kommt aus feature-vpn.
 */
object QuickTiles {
    /** Kacheln neu zeichnen lassen (Stand geändert, NAS gewechselt). */
    fun refresh(context: Context) {
        for (c in listOf(PhotoTile::class.java, NasTile::class.java)) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, c)) }
        }
    }

    /**
     * Öffnet eine Activity aus einer Kachel und schließt die Schnelleinstellungen (ab Android 14 nur mit PendingIntent).
     * Die Fassung mit Intent läuft nur auf Android 13 (minSdk 33), wo es die mit PendingIntent noch nicht gibt.
     */
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    internal fun TileService.open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, intent.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    internal fun appIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

/**
 * „Foto-Sicherung“: Tippen sichert jetzt an alle NAS mit eingeschalteter
 * Sicherung; läuft sie, pausiert Tippen sie; pausiert, setzt Tippen sie fort.
 * Der Zustand steht in Worten in der Kachel (nicht nur an/aus).
 */
class PhotoTile : TileService() {
    private var scope: CoroutineScope? = null
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        draw()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        watch = s.launch { merge(PhotosRuntime.progress, PhotosRuntime.changes).collect { draw() } }
    }

    override fun onStopListening() {
        watch?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private enum class Mode { Unpaired, Off, Idle, Running, Paused }

    private fun mode(): Mode = when {
        PhotosRuntime.nasIds().isEmpty() -> Mode.Unpaired
        PhotosRuntime.enabledNas(this).isEmpty() -> Mode.Off
        PhotosRuntime.paused(this) -> Mode.Paused
        PhotosRuntime.progress.value.running -> Mode.Running
        else -> Mode.Idle
    }

    private fun draw() {
        val tile = qsTile ?: return
        val m = mode()
        tile.label = getString(R.string.tile_photos)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_sc_photo)
        tile.state = when (m) {
            Mode.Unpaired -> Tile.STATE_UNAVAILABLE
            Mode.Running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        val sub = when (m) {
            Mode.Unpaired -> getString(R.string.sc_not_paired)
            Mode.Off -> getString(R.string.tile_photos_off)
            Mode.Idle -> getString(R.string.tile_photos_idle)
            Mode.Running -> getString(R.string.tile_photos_running)
            Mode.Paused -> getString(R.string.tile_photos_paused)
        }
        tile.subtitle = sub
        tile.stateDescription = sub
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (mode()) {
            Mode.Unpaired -> return
            Mode.Off -> with(QuickTiles) { open(appIntent(this@PhotoTile).putExtra(PhotosWidget.EXTRA_OPEN_PHOTOS, true)) }
            Mode.Running -> {
                PhotoScheduler.pause(this)
                Toast.makeText(this, getString(R.string.tile_photos_paused_toast), Toast.LENGTH_SHORT).show()
            }
            Mode.Paused, Mode.Idle -> {
                val ok = Shortcuts.backupNow(this)
                Toast.makeText(this, getString(if (ok) R.string.tile_photos_started else R.string.tile_photos_refused), Toast.LENGTH_SHORT).show()
            }
        }
        draw()
    }
}

/** „NAS-Zustand“ des gewählten NAS in Worten (wie das Widget); Tippen öffnet die App. */
class NasTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val nas = WidgetRuntime.active()
        val s = WidgetStore.nas(this, nas)
        // Älter als 15 Minuten: im Hintergrund neu laden (die Kachel zeichnet sich danach neu).
        if (WidgetRuntime.nasList().isNotEmpty() && System.currentTimeMillis() - s.atMs > 15 * 60_000) WidgetRefresh.now(this)
        draw()
    }

    private fun draw() {
        val tile = qsTile ?: return
        val list = WidgetRuntime.nasList()
        val nas = WidgetRuntime.active()
        val s = WidgetStore.nas(this, nas)
        val name = list.firstOrNull { it.id == nas }?.name ?: s.hostname
        tile.label = name.ifBlank { getString(R.string.tile_nas) }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_sc_nas)
        val text = when {
            list.isEmpty() -> getString(WidgetsR.string.w_unpaired)
            s.state == "ok" -> getString(WidgetsR.string.w_ok)
            s.state == "problem" -> resources.getQuantityString(WidgetsR.plurals.w_problem, s.problems, s.problems)
            s.state == "certificate" -> getString(WidgetsR.string.w_certificate)
            s.state == "unpaired" -> getString(WidgetsR.string.w_unpaired)
            else -> getString(WidgetsR.string.w_offline)
        }
        tile.state = when {
            list.isEmpty() -> Tile.STATE_UNAVAILABLE
            s.state == "ok" -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = text
        tile.stateDescription = text
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val nas = WidgetRuntime.active()
        with(QuickTiles) { open(appIntent(this@NasTile).putExtra(NasRegistry.EXTRA_NAS, nas)) }
    }
}

/** „Agent fragen“: öffnet die Agent-App zum Schreiben (ihre Schnittstelle ASK). */
class AgentTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_agent)
        tile.subtitle = getString(R.string.tile_agent_sub)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_sc_agent)
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val ask = Shortcuts.askAgent(this)
        with(QuickTiles) { open(ask) }
    }
}
