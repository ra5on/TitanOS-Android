package io.github.ra5on.titanos.notify

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Hintergrund der Mitteilungen (TECHNIK §5):
 * - [FetchWorker] alle 15 Minuten mit Netz als Rückfall, auch wenn die
 *   Dauerverbindung aus ist oder Android sie getrennt hat.
 * - [LiveService]: „Direkt zum NAS“, ein Vordergrunddienst `specialUse`
 *   mit ruhiger Mitteilung, der den SSE-Strom offen hält.
 * - [BootReceiver] startet ihn nach Neustart und App-Update, wenn er an ist
 *   (`specialUse` darf aus BOOT_COMPLETED starten, anders als `dataSync`).
 */
object NotifyScheduler {
    private const val FETCH = "notify-fetch"

    /** Beim Start der App, nach dem Koppeln und nach jeder Änderung der Einstellungen. */
    fun arm(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            FETCH, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FetchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        if (wantsLive(context)) LiveService.start(context) else LiveService.stop(context)
    }

    /** Dauerverbindung nur, wenn an und Android Mitteilungen erlaubt; sonst reicht der Auftrag für die Liste. */
    fun wantsLive(context: Context): Boolean {
        val s = NotifySettingsStore(context).load()
        return s.enabled && s.live && Poster.permitted(context)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FETCH)
    }

    /** Ist die App vom Akku-Sparen ausgenommen? */
    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Bittet Android, die App vom Akku-Sparen auszunehmen (außerhalb des
     * Play Stores für Apps ohne FCM erlaubt). Ohne Activity im Rücken mit
     * eigener Aufgabe.
     */
    @SuppressLint("BatteryLife")
    fun askBatteryExemption(context: Context): Boolean {
        if (batteryExempt(context)) return true
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, ("package:" + context.packageName).toUri())
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

/** Holt neue Mitteilungen; ohne Kopplung nichts, ohne NAS beim nächsten Mal. */
class FetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        NotifyRuntime.fetch(applicationContext)
        // Hat Android den Dienst beendet (Speicher, Akku), kommt er hier wieder.
        if (NotifyScheduler.wantsLive(applicationContext) && NotifyRuntime.live.value == LiveState.Off) LiveService.start(applicationContext)
        return Result.success()
    }
}

/** Wartezeit vor dem nächsten Versuch: verdoppelt sich von 2 s bis 5 min, mit Zufall. */
fun backoffMillis(attempt: Int, random: Random = Random.Default): Long {
    val base = 2_000L shl attempt.coerceIn(0, 8)
    val capped = minOf(base, 5 * 60_000L)
    return capped / 2 + random.nextLong(capped / 2 + 1)
}

/**
 * „Direkt zum NAS“: hält den Strom der Mitteilungen offen. Reißt er ab,
 * verbindet sich der Dienst mit wachsender Pause neu; ein neues Netz
 * (WLAN ↔ Mobil) verkürzt die Pause.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    @Volatile
    private var open: Response? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Poster.ensureChannels(this)
        val cm = getSystemService(ConnectivityManager::class.java)
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wake.trySend(Unit)
            }

            override fun onLost(network: Network) {
                // Die alte Verbindung hängt am verlorenen Netz: schließen, neu verbinden.
                open?.close()
            }
        }.also { cb -> runCatching { cm?.registerDefaultNetworkCallback(cb) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notify_bell)
            .setContentTitle(getString(R.string.notify_live_title))
            .setContentText(getString(R.string.notify_live_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Poster.openIntent(this, null))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(LIVE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                // Android 13 kennt den Typ specialUse noch nicht.
                startForeground(LIVE_ID, notification)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        var attempt = 0
        while (true) {
            val api = NotifyRuntime.api()
            if (api == null || !NotifyScheduler.wantsLive(this)) {
                NotifyRuntime.setLive(LiveState.Off)
                stopSelf()
                return
            }
            NotifyRuntime.setLive(if (NotifyRuntime.online(this)) LiveState.Connecting else LiveState.Waiting)
            var quietUpTo = -1L
            var quietEpoch = ""
            try {
                val since = NotifyRuntime.inbox(this).snapshot().marker
                api.stream(since, onOpen = { open = it }) { ev ->
                    when (ev.event) {
                        "ready" -> {
                            attempt = 0
                            NotifyRuntime.setLive(LiveState.Connected)
                            val head = NotifyApi.decodeHead(ev.data)
                            val primed = NotifyRuntime.inbox(this).snapshot().primed
                            // Der Rückstand eines Neubeginns (reset) oder des ersten Abgleichs klingelt nicht.
                            if (head != null && (head.reset || !primed)) {
                                Marker.parse(head.latest)?.let { quietUpTo = it.seq; quietEpoch = it.epoch }
                            }
                            // Erster Abgleich: als abgeglichen merken, damit danach Neues klingelt
                            // (die Marke bleibt, bis der Rückstand da ist).
                            if (!primed) {
                                runBlocking { NotifyRuntime.lock.withLock { NotifyRuntime.take(this@LiveService, emptyList(), "", quiet = true) } }
                            }
                        }
                        "notification" -> NotifyApi.decodeNote(ev.data)?.let { note ->
                            val m = Marker.parse(note.id)
                            val quiet = m != null && m.epoch == quietEpoch && m.seq <= quietUpTo
                            runBlocking { NotifyRuntime.lock.withLock { NotifyRuntime.take(this@LiveService, listOf(note), note.id, quiet) } }
                        }
                        "resync" -> runBlocking { NotifyRuntime.fetch(this@LiveService) }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Gerät am NAS beendet, oder ein NAS ohne Mitteilungen (älter): kein Dauerversuch.
                if (e is io.github.ra5on.titanos.net.DeviceUnknownException || (e is io.github.ra5on.titanos.net.ApiException && e.status in setOf(403, 404))) {
                    NotifyRuntime.setLive(LiveState.Off)
                    stopSelf()
                    return
                }
            } finally {
                open = null
            }
            NotifyRuntime.setLive(LiveState.Waiting)
            // Pause, ein neues Netz weckt früher.
            withTimeoutOrNull(backoffMillis(attempt++)) { wake.receive() }
            delay(500)
        }
    }

    override fun onDestroy() {
        callback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        open?.close()
        scope.cancel()
        NotifyRuntime.setLive(LiveState.Off)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // specialUse kennt keine Zeitgrenze; falls Android doch eine setzt: sauber beenden.
        stopSelf()
    }

    companion object {
        private const val LIVE_ID = 0x7116_0101

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, LiveService::class.java))
            } catch (_: ForegroundServiceStartNotAllowedException) {
                // Aus dem Hintergrund nicht erlaubt: der Auftrag alle 15 Minuten bleibt.
            } catch (_: IllegalStateException) {
            } catch (_: SecurityException) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveService::class.java))
        }
    }
}

/** Nach Neustart oder App-Update: Auftrag und, wenn an, die Dauerverbindung wieder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (NotifyRuntime.api() != null) NotifyScheduler.arm(context)
            }
        }
    }
}
