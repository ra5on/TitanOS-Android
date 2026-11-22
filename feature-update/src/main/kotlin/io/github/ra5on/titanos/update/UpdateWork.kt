package io.github.ra5on.titanos.update

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Sucht nach neuen Versionen (beim Öffnen von „Über die App“, beim Start und täglich). */
object UpdateCheck {
    private const val DAILY = "update-check"

    /** Der Client für GitHub: normale Zertifikatsprüfung, keine Verbindung zum NAS. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    suspend fun run(context: Context): AppRelease? {
        val settings = UpdateSettings(context)
        UpdateState.step = UpdateStep.Checking
        return try {
            val source = ReleaseSource(client)
            val all = source.releases()
            val own = AppKind.own(context.packageName)
            val mine = ReleaseSource.pick(all, Installer.versionCode(context, own), settings.includeTest, own)
            val agentCode = Installer.versionCode(context, AppKind.Agent)
            // Die Agent-App nur, wenn sie installiert ist (installieren wäre eine eigene Entscheidung).
            val agent = if (agentCode > 0 && own != AppKind.Agent) ReleaseSource.pick(all, agentCode, settings.includeTest, AppKind.Agent) else null
            UpdateState.available = mine
            UpdateState.agentAvailable = agent
            UpdateState.lastError = null
            UpdateState.lastCheckMs = System.currentTimeMillis()
            mine
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateState.lastError = e.message ?: e.javaClass.simpleName
            null
        } finally {
            if (UpdateState.step == UpdateStep.Checking) UpdateState.step = UpdateStep.Idle
        }
    }

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!UpdateSettings(context).autoCheck) {
            wm.cancelUniqueWork(DAILY)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Tägliche Suche; findet sie eine neue Version, sagt eine Mitteilung es. Installiert wird erst nach dem Tippen. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val found = UpdateCheck.run(applicationContext) ?: return Result.success()
        val prefs = applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)
        // Jede Version nur einmal melden.
        if (prefs.getInt("told", 0) >= found.versionCode) return Result.success()
        prefs.edit().putInt("told", found.versionCode).apply()
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.putExtra(EXTRA_OPEN_ABOUT, true) ?: Intent()
        Notifier.available(applicationContext, found, open)
        return Result.success()
    }

    companion object {
        const val EXTRA_OPEN_ABOUT = "io.github.ra5on.titanos.OPEN_ABOUT"
    }
}
