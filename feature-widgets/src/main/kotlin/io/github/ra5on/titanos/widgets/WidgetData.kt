package io.github.ra5on.titanos.widgets

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.ra5on.titanos.model.Volume
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.net.certificateChange
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** Was die Widgets zeigen; gespeichert, damit sie auch ohne NAS den letzten Stand zeigen. */
@Serializable
data class NasSnapshot(
    val hostname: String = "",
    /** ok, problem, offline, certificate, unpaired */
    val state: String = "unpaired",
    val problems: Int = 0,
    val volumes: List<VolumeUse> = emptyList(),
    val cpu: Int? = null,
    val ram: Int? = null,
    val atMs: Long = 0,
)

@Serializable
data class VolumeUse(val name: String, val percent: Int)

/** Stand der Foto-Sicherung für das Widget; die App schreibt ihn, wenn er sich ändert. */
@Serializable
data class PhotosSnapshot(
    val enabled: Boolean = false,
    /** Hauptzeile in Worten (z. B. „Aktuell · 9:12“), schon übersetzt. */
    val line: String = "",
    /** Zweite Zeile, z. B. „4 Videos warten aufs Laden“. */
    val detail: String = "",
    val upToDate: Boolean = false,
    val atMs: Long = 0,
)

/** Ablage der Widget-Daten (SharedPreferences, JSON). */
object WidgetStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun nas(context: Context): NasSnapshot =
        prefs(context).getString("nas", null)?.let { runCatching { json.decodeFromString(NasSnapshot.serializer(), it) }.getOrNull() } ?: NasSnapshot()

    fun photos(context: Context): PhotosSnapshot =
        prefs(context).getString("photos", null)?.let { runCatching { json.decodeFromString(PhotosSnapshot.serializer(), it) }.getOrNull() } ?: PhotosSnapshot()

    fun saveNas(context: Context, s: NasSnapshot) = prefs(context).edit().putString("nas", json.encodeToString(NasSnapshot.serializer(), s)).apply()

    /** Schreibt nur, wenn sich etwas geändert hat; gibt true zurück, wenn das Widget neu zeichnen soll. */
    fun savePhotos(context: Context, s: PhotosSnapshot): Boolean {
        val old = photos(context)
        if (old.copy(atMs = 0) == s.copy(atMs = 0)) return false
        prefs(context).edit().putString("photos", json.encodeToString(PhotosSnapshot.serializer(), s)).apply()
        return true
    }
}

/** Die App setzt die Verbindung, damit der Worker ohne Activity laden kann. */
object WidgetRuntime {
    @Volatile var api: () -> TitanApi? = { null }
}

object WidgetRefresh {
    private const val PERIODIC = "widgets-refresh"
    private const val NOW = "widgets-refresh-now"

    /** Lädt Zustand, Volumes und (für Verwalter) CPU/RAM; ohne NAS bleibt der letzte Stand mit „nicht erreichbar“. */
    suspend fun load(context: Context, api: TitanApi?): NasSnapshot {
        val last = WidgetStore.nas(context)
        if (api == null) return last.copy(state = "unpaired")
        return try {
            val system = api.system()
            val volumes = try {
                api.storage().volumes
            } catch (e: ApiException) {
                if (e.status == 403) null else throw e
            }
            val sample = try {
                api.monitoring(1).lastOrNull()
            } catch (e: ApiException) {
                null
            }
            snapshot(system.hostname.ifBlank { last.hostname }, volumes, sample?.cpu, sample?.let { if (it.ramTotal > 0) 100.0 * (it.ramTotal - it.ramAvailable) / it.ramTotal else null })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            last.copy(state = if (e.certificateChange() != null) "certificate" else "offline")
        }
    }

    fun snapshot(hostname: String, volumes: List<Volume>?, cpu: Double?, ram: Double?): NasSnapshot {
        val list = volumes.orEmpty()
        val problems = list.count { it.state != "ok" }
        return NasSnapshot(
            hostname = hostname,
            state = if (problems > 0) "problem" else "ok",
            problems = problems,
            volumes = list.filter { it.mounted && it.sizeBytes > 0 }.sortedByDescending { it.sizeBytes }.take(3)
                .map { VolumeUse(it.name, ((it.usedBytes * 100) / it.sizeBytes).toInt().coerceIn(0, 100)) },
            cpu = cpu?.toInt()?.coerceIn(0, 100),
            ram = ram?.toInt()?.coerceIn(0, 100),
            atMs = System.currentTimeMillis(),
        )
    }

    suspend fun refresh(context: Context) {
        WidgetStore.saveNas(context, load(context, WidgetRuntime.api()))
        NasWidget().updateAll(context)
        PhotosWidget().updateAll(context)
    }

    /** Alle 30 Minuten, solange Widgets da sind (Glance-Empfänger planen es). */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun now(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetWorker>().build())
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetRefresh.refresh(applicationContext)
        return Result.success()
    }
}
