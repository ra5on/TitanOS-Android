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
    private fun uploadName(kind: Kind) = "photos-upload-" + kind.name.lowercase()

    /** Feste Kennung des Auftrags „Jetzt sichern“, weit weg von den Kennungen, die WorkManager vergibt (ab 0). */
    const val NOW_JOB_ID = 0x7116_0001

    /** Beim Start der App und nach jeder Änderung der Einstellungen. */
    fun arm(context: Context, settingsChanged: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val s = SettingsStore(context).load()
        if (!s.enabled) {
            wm.cancelUniqueWork(SCAN)
            wm.cancelUniqueWork(CHECK)
            Kind.entries.forEach { wm.cancelUniqueWork(uploadName(it)) }
            return
        }
        armScan(context, ExistingWorkPolicy.KEEP)
        wm.enqueueUniquePeriodicWork(
            CHECK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckWorker>(6, TimeUnit.HOURS).build(),
        )
        enqueueUploads(context, if (settingsChanged) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
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

    /** Ein Upload-Auftrag je Art mit ihren Bedingungen; „Nur von Hand“ bekommt keinen. */
    fun enqueueUploads(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val wm = WorkManager.getInstance(context)
        val s = SettingsStore(context).load()
        for (kind in Kind.entries) {
            val need = needFor(s.whenOf(kind), s.mobileData)
            if (need == null || !s.enabled) {
                wm.cancelUniqueWork(uploadName(kind))
                continue
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (need.network == NetNeed.Unmetered) NetworkType.UNMETERED else NetworkType.NOT_ROAMING)
                .setRequiresCharging(need.charging)
                .build()
            wm.enqueueUniqueWork(
                uploadName(kind), policy,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(UploadWorker.KIND to kind.name))
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }

    /**
     * „Jetzt sichern“: nur, solange die App sichtbar ist (Android lässt
     * vom Nutzer ausgelöste Aufträge sonst nicht zu). Falsch, wenn Android
     * ablehnt.
     */
    fun backupNow(context: Context, estimatedBytes: Long): Boolean {
        val s = SettingsStore(context).load()
        val need = needForNow(s.mobileData)
        if (Build.VERSION.SDK_INT < 34) {
            // Android 13 kennt keine vom Nutzer ausgelösten Aufträge: ein eiliger Auftrag von WorkManager.
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if (need.network == NetNeed.Unmetered) NetworkType.UNMETERED else NetworkType.NOT_ROAMING).build())
                    .setInputData(workDataOf(UploadWorker.KIND to UploadWorker.ALL))
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
            .build()
        return try {
            js.schedule(info) == JobScheduler.RESULT_SUCCESS
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Alles abbestellen (Kopplung aufgehoben). */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        listOf(SCAN, CHECK, NOW_WORK).forEach(wm::cancelUniqueWork)
        Kind.entries.forEach { wm.cancelUniqueWork(uploadName(it)) }
        context.getSystemService(JobScheduler::class.java)?.cancel(NOW_JOB_ID)
    }
}

/** Neue Aufnahmen: Uploads anstoßen und sich selbst für die nächste Änderung neu einplanen. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PhotoScheduler.enqueueUploads(applicationContext)
        // Nach dem Lauf, nicht während: APPEND_OR_REPLACE hängt den neuen Auftrag hinten an.
        PhotoScheduler.armScan(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

/** Netz unter dem Auslöser: Generation des MediaStore vergleichen, ggf. Uploads anstoßen. */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!SettingsStore(ctx).load().enabled) return Result.success()
        val state = BackupStore.get(ctx).snapshot()
        val (gens, versions) = MediaScanner(ctx).generations()
        if (mediaChanged(state.generations, state.versions, gens, versions) || state.uploads.isNotEmpty() || state.problem != null) {
            PhotoScheduler.enqueueUploads(ctx)
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
        val titan = PhotosRuntime.titan() ?: return Result.retry()
        val photos = PhotosRuntime.photos() ?: return Result.retry()
        return when (PhotoBackup(applicationContext, titan, photos).run(kinds) { isStopped }) {
            Outcome.RetryLater -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KIND = "kind"
        const val ALL = "all"
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
        val titan = PhotosRuntime.titan()
        val photos = PhotosRuntime.photos()
        if (titan == null || photos == null) return false
        job = scope.launch {
            val outcome = PhotoBackup(applicationContext, titan, photos).run(Kind.entries.toSet())
            jobFinished(params, outcome == Outcome.RetryLater)
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
