package io.github.ra5on.titanos.photos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.ra5on.titanos.net.NasNames
import io.github.ra5on.titanos.net.NasRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Hintergrund der Foto-Sicherung (TECHNIK §3):
 * - [ScanWorker] wartet mit einem Content-URI-Auslöser auf neue Aufnahmen
 *   im MediaStore, stößt die Uploads an und plant sich danach neu ein.
 * - [CheckWorker] läuft periodisch als Netz: Hat sich die Generation des
 *   MediaStore geändert oder wartet etwas, stößt er die Uploads an, falls
 *   Android einen Auslöser verschluckt hat.
 * - [UploadWorker] je Art mit den Bedingungen aus den Einstellungen.
 * - „Jetzt sichern“ ist ein vom Nutzer ausgelöster Auftrag von JobScheduler
 *   ([BackupNowService]); WorkManager 2.12 kennt das nicht. Kein
 *   Vordergrunddienst `dataSync`.
 */
object PhotoScheduler {
    private const val SCAN = "photos-scan"
    private const val CHECK = "photos-check"
    private const val NOW_WORK = "photos-now"

    /** Ein Upload-Auftrag je NAS und Art; die alte Kopplung behält `photos-upload-photo`. */
    internal fun uploadName(kind: Kind, nas: String) = "photos-upload-" + kind.name.lowercase() + NasNames.suffix(nas)

    /** Feste Kennung des Auftrags „Jetzt sichern“, weit weg von den Kennungen, die WorkManager vergibt (ab 0). */
    const val NOW_JOB_ID = 0x7116_0001

    /** Extra des Auftrags „Jetzt sichern“: die NAS, an die gesichert wird. */
    internal const val EXTRA_NAS = "nas"

    /** Beim Start der App: alle NAS. */
    fun armAll(context: Context) {
        val ids = PhotosRuntime.nasIds()
        for (id in ids) enqueueUploads(context, id)
        armShared(context)
    }

    /** Nach jeder Änderung der Einstellungen eines NAS. */
    fun arm(context: Context, nas: String, settingsChanged: Boolean = false) {
        enqueueUploads(context, nas, if (settingsChanged) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
        armShared(context)
    }

    /** Auslöser für neue Aufnahmen und der Abgleich alle 6 h gelten für alle NAS; ohne eingeschaltetes NAS keiner. */
    private fun armShared(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (PhotosRuntime.enabledNas(context).isEmpty()) {
            wm.cancelUniqueWork(SCAN)
            wm.cancelUniqueWork(CHECK)
            return
        }
        armScan(context, ExistingWorkPolicy.KEEP)
        wm.enqueueUniquePeriodicWork(
            CHECK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckWorker>(6, TimeUnit.HOURS).build(),
        )
    }

    internal fun armScan(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SCAN, policy, OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(constraints).build(),
        )
    }

    /** Uploads aller NAS anstoßen (neue Aufnahmen). */
    fun enqueueAll(context: Context) {
        for (id in PhotosRuntime.nasIds()) enqueueUploads(context, id)
    }

    /** Ein Upload-Auftrag je Art mit ihren Bedingungen; „Nur von Hand“ und „Pausiert“ bekommen keinen. */
    fun enqueueUploads(context: Context, nas: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val wm = WorkManager.getInstance(context)
        val s = SettingsStore(context, nas).load()
        val paused = PhotosRuntime.paused(context)
        for (kind in Kind.entries) {
            val need = needFor(s.whenOf(kind), s.mobileData)
            if (need == null || !s.enabled || paused) {
                wm.cancelUniqueWork(uploadName(kind, nas))
                continue
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (need.network == NetNeed.Unmetered) NetworkType.UNMETERED else NetworkType.NOT_ROAMING)
                .setRequiresCharging(need.charging)
                .build()
            wm.enqueueUniqueWork(
                uploadName(kind, nas), policy,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(UploadWorker.KIND to kind.name, UploadWorker.NAS to nas))
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }

    /**
     * „Jetzt sichern“ an die NAS [nas] (nacheinander): nur, solange die App
     * sichtbar ist oder aus einer Schnelleinstellung (Android lässt vom Nutzer
     * ausgelöste Aufträge sonst nicht zu). Falsch, wenn Android ablehnt.
     * Hebt eine Pause auf.
     */
    fun backupNow(context: Context, nas: List<String>, estimatedBytes: Long): Boolean {
        if (nas.isEmpty()) return false
        if (PhotosRuntime.paused(context)) {
            PhotosRuntime.setPaused(context, false)
            enqueueAll(context)
        }
        val mobile = nas.any { SettingsStore(context, it).load().mobileData }
        val need = needForNow(mobile)
        if (Build.VERSION.SDK_INT < 34) {
            // Android 13 kennt keine vom Nutzer ausgelösten Aufträge: ein eiliger Auftrag von WorkManager.
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if (need.network == NetNeed.Unmetered) NetworkType.UNMETERED else NetworkType.NOT_ROAMING).build())
                    .setInputData(workDataOf(UploadWorker.KIND to UploadWorker.ALL, UploadWorker.NAS to nas.joinToString(",")))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
            return true
        }
        val js = context.getSystemService(JobScheduler::class.java) ?: return false
        val info = JobInfo.Builder(NOW_JOB_ID, ComponentName(context, BackupNowService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(if (need.network == NetNeed.Unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_NOT_ROAMING)
            .setEstimatedNetworkBytes(0, maxOf(estimatedBytes, 0))
            .setExtras(android.os.PersistableBundle().apply { putStringArray(EXTRA_NAS, nas.toTypedArray()) })
            .build()
        return try {
            js.schedule(info) == JobScheduler.RESULT_SUCCESS
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** „Pausieren“: laufende und wartende Uploads aller NAS anhalten, bis man fortsetzt. */
    fun pause(context: Context) {
        PhotosRuntime.setPaused(context, true)
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(NOW_WORK)
        for (id in PhotosRuntime.nasIds()) Kind.entries.forEach { wm.cancelUniqueWork(uploadName(it, id)) }
        context.getSystemService(JobScheduler::class.java)?.cancel(NOW_JOB_ID)
    }

    /** Fortsetzen nach „Pausieren“. */
    fun resume(context: Context) {
        PhotosRuntime.setPaused(context, false)
        armAll(context)
    }

    /** Alles eines NAS abbestellen (seine Kopplung aufgehoben). */
    fun cancelAll(context: Context, nas: String) {
        val wm = WorkManager.getInstance(context)
        Kind.entries.forEach { wm.cancelUniqueWork(uploadName(it, nas)) }
        val others = PhotosRuntime.nasIds().filter { it != nas }
        if (others.none { SettingsStore(context, it).load().enabled }) {
            listOf(SCAN, CHECK, NOW_WORK).forEach(wm::cancelUniqueWork)
            context.getSystemService(JobScheduler::class.java)?.cancel(NOW_JOB_ID)
        }
    }
}

/** Neue Aufnahmen: Uploads anstoßen und sich selbst für die nächste Änderung neu einplanen. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PhotoScheduler.enqueueAll(applicationContext)
        // Nach dem Lauf, nicht während: APPEND_OR_REPLACE hängt den neuen Auftrag hinten an.
        PhotoScheduler.armScan(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

/** Netz unter dem Auslöser: Generation des MediaStore vergleichen, ggf. Uploads anstoßen. */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val enabled = PhotosRuntime.enabledNas(ctx)
        if (enabled.isEmpty()) return Result.success()
        val (gens, versions) = MediaScanner(ctx).generations()
        for (nas in enabled) {
            val state = BackupStore.get(ctx, nas).snapshot()
            // Auch, wenn eine Nachprüfung des Gesicherten fällig ist (1.03, Recheck.kt).
            if (mediaChanged(state.generations, state.versions, gens, versions) || state.uploads.isNotEmpty() || state.problem != null ||
                recheckDue(state, System.currentTimeMillis())
            ) {
                PhotoScheduler.enqueueUploads(ctx, nas)
            }
        }
        PhotoScheduler.armScan(ctx, ExistingWorkPolicy.KEEP)
        return Result.success()
    }
}

/** Sichert eine Art; ohne Kopplung oder ohne NAS später noch einmal. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString(KIND)
        val kinds = if (name == ALL) Kind.entries.toSet() else Kind.entries.filter { it.name == name }.toSet()
        if (kinds.isEmpty()) return Result.failure()
        // Aufträge von vor mehreren NAS haben keine Angabe: die alte Kopplung.
        val list = (inputData.getString(NAS) ?: NasRegistry.LEGACY_ID).split(',').filter { it.isNotBlank() }
        var retry = false
        for (nas in list) {
            if (isStopped) return Result.retry()
            val titan = PhotosRuntime.titan(nas)
            val photos = PhotosRuntime.photos(nas)
            if (titan == null || photos == null) {
                // Ohne diese Kopplung (entfernt) nichts zu tun; sonst später noch einmal.
                if (nas in PhotosRuntime.nasIds()) retry = true
                continue
            }
            if (PhotoBackup(applicationContext, titan, photos, nas).run(kinds) { isStopped } == Outcome.RetryLater) retry = true
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        const val KIND = "kind"
        const val ALL = "all"
        const val NAS = "nas"
    }
}

/**
 * „Jetzt sichern“ als vom Nutzer ausgelöster Auftrag (JobScheduler,
 * `setUserInitiated`): ohne Tageslimit, mit Mitteilung, beide Arten.
 */
class BackupNowService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.photos_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.photos_now_running))
            .setOngoing(true)
            .build()
        // Nur ab Android 14 eingeplant (backupNow); die Mitteilung ist dort Pflicht.
        if (Build.VERSION.SDK_INT >= 34) setNotification(params, PhotoScheduler.NOW_JOB_ID, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
        val list = params.extras.getStringArray(PhotoScheduler.EXTRA_NAS)?.toList() ?: listOf(NasRegistry.LEGACY_ID)
        val targets = list.mapNotNull { nas -> PhotosRuntime.titan(nas)?.let { t -> PhotosRuntime.photos(nas)?.let { p -> Triple(nas, t, p) } } }
        if (targets.isEmpty()) return false
        job = scope.launch {
            var retry = false
            for ((nas, titan, photos) in targets) {
                if (PhotoBackup(applicationContext, titan, photos, nas).run(Kind.entries.toSet()) == Outcome.RetryLater) retry = true
            }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    companion object {
        const val CHANNEL = "photos"
    }
}
