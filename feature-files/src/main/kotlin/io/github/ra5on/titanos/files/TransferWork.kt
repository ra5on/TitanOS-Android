package io.github.ra5on.titanos.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.ra5on.titanos.model.UploadStatus
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.net.Uploader
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Was Worker ohne Activity brauchen. Die App setzt es beim Start
 * (TitanApp/AppGraph); ohne Kopplung ist [api] null.
 */
object FilesRuntime {
    @Volatile var api: () -> TitanApi? = { null }
    @Volatile var store: TransferStore? = null
    @Volatile var offline: OfflineStore? = null
    /** Nach einem fertigen Upload: die offene Ordneransicht lädt neu. */
    @Volatile var onUploaded: ((root: String, dir: String) -> Unit)? = null
    /** Kopplung und Sitzung für die weiteren Dateirouten und die eingebetteten Office-Programme. */
    @Volatile var access: () -> FilesAccess? = { null }
}

/** Uploads und „In Downloads speichern“ als WorkManager-Aufträge. */
object TransferQueue {
    const val TAG = "titanos-transfer"
    private fun unique(id: Long) = "transfer-$id"

    /**
     * Hochladen von [source] nach [root]/[dir]. Die Quelle muss lesbar
     * bleiben, bis der Auftrag fertig ist (dauerhafte Leseerlaubnis oder eine
     * eigene Kopie mit [ownCopy]). Liefert die Id der Übertragung.
     */
    fun enqueueUpload(
        context: Context,
        root: String,
        dir: String,
        place: String,
        name: String,
        size: Long,
        source: Uri,
        conflict: String = "rename",
        ownCopy: Boolean = false,
        userInitiated: Boolean = true,
    ): Long {
        val store = FilesRuntime.store ?: error("FilesRuntime.store not set")
        val t = store.add(
            Transfer(0, name, Direction.Upload, place, root, dir, total = maxOf(size, 0), source = source.toString(), conflict = conflict, ownCopy = ownCopy),
        )
        schedule(context, t.id, userInitiated)
        return t.id
    }

    fun enqueueDownload(context: Context, root: String, dir: String, place: String, name: String, size: Long): Long {
        val store = FilesRuntime.store ?: error("FilesRuntime.store not set")
        val t = store.add(Transfer(0, name, Direction.Download, place, root, dir, total = size))
        schedule(context, t.id, userInitiated = true)
        return t.id
    }

    /** Neuer Versuch einer gescheiterten Übertragung (macht beim Stand des NAS weiter). */
    fun retry(context: Context, id: Long) {
        val store = FilesRuntime.store ?: return
        store.update(id) { it.copy(state = TransferState.Waiting, reason = null, offline = false, finishedAtMs = null) }
        schedule(context, id, userInitiated = true)
    }

    fun cancel(context: Context, id: Long, api: TitanApi?) {
        val store = FilesRuntime.store ?: return
        val t = store.get(id) ?: return
        store.update(id) { it.copy(state = TransferState.Cancelled, finishedAtMs = System.currentTimeMillis()) }
        WorkManager.getInstance(context).cancelUniqueWork(unique(id))
        if (t.ownCopy) t.source?.let { runCatching { File(Uri.parse(it).path!!).delete() } }
        val upload = t.uploadId
        if (upload != null && api != null) {
            // Am NAS aufräumen; scheitert es, räumt das NAS liegengebliebene Uploads selbst ab.
            Thread { runCatching { kotlinx.coroutines.runBlocking { api.uploadAbort(upload) } } }.start()
        }
    }

    private fun schedule(context: Context, id: Long, userInitiated: Boolean) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(TransferWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .apply { if (userInitiated) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(unique(id), ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * Führt eine Übertragung aus. Ein Upload macht nach einem Abbruch (App
 * beendet, Netz weg, NAS neu gestartet) beim Stand des NAS weiter; kennt das
 * NAS den Upload nicht mehr, beginnt er neu. Ein Download beginnt immer neu.
 */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val store = FilesRuntime.store ?: return Result.retry()
        val t = store.get(id) ?: return Result.failure()
        if (t.state == TransferState.Cancelled || t.state == TransferState.Done) return Result.success()
        val api = FilesRuntime.api() ?: run {
            fail(store, id, "not paired", offline = false)
            return Result.failure()
        }
        store.update(id) { it.copy(state = TransferState.Running, waiting = WaitReason.None) }
        return try {
            when (t.direction) {
                Direction.Upload -> upload(store, api, t)
                Direction.Download -> download(store, api, t)
                Direction.Share -> Unit
            }
            store.update(id) { it.copy(state = TransferState.Done, done = it.total, finishedAtMs = System.currentTimeMillis()) }
            if (t.ownCopy) t.source?.let { runCatching { File(Uri.parse(it).path!!).delete() } }
            FilesRuntime.onUploaded?.invoke(t.root, t.dir)
            Result.success()
        } catch (e: CancellationException) {
            // Von WorkManager gestoppt (Netz weg, Akku) oder vom Nutzer abgebrochen.
            if (store.get(id)?.state != TransferState.Cancelled) {
                store.update(id) { it.copy(state = TransferState.Waiting, waiting = WaitReason.Network) }
            }
            throw e
        } catch (e: ApiException) {
            if (retryable(e) && runAttemptCount < MAX_ATTEMPTS) {
                store.update(id) { it.copy(state = TransferState.Waiting, waiting = WaitReason.Nas) }
                Result.retry()
            } else {
                fail(store, id, "${e.status} ${e.code}".trim(), offline = false)
                Result.failure()
            }
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                store.update(id) { it.copy(state = TransferState.Waiting, waiting = WaitReason.Nas) }
                Result.retry()
            } else {
                fail(store, id, null, offline = true)
                Result.failure()
            }
        }
    }

    private fun retryable(e: ApiException) = e.status >= 500 || e.status == 423 || e.status == 429 || e.status == 401

    private fun fail(store: TransferStore, id: Long, reason: String?, offline: Boolean) {
        store.update(id) { it.copy(state = TransferState.Failed, reason = reason, offline = offline, finishedAtMs = System.currentTimeMillis()) }
    }

    private suspend fun upload(store: TransferStore, api: TitanApi, t: Transfer) {
        val source = Uri.parse(t.source ?: throw IOException("no source"))
        val open: () -> InputStream = {
            // file:// nur für die eigenen Kopien im Ausgang (Outbox); alles andere über den ContentResolver.
            if (source.scheme == "file") FileInputStream(Outbox.own(applicationContext, source) ?: throw IOException("not in outbox"))
            else applicationContext.contentResolver.openInputStream(source) ?: throw IOException("cannot read")
        }
        val total = if (t.total > 0) t.total else open().use { it.skip(Long.MAX_VALUE) }
        store.update(t.id) { it.copy(total = total) }
        var status: UploadStatus? = t.uploadId?.let { known ->
            try {
                api.uploadStatus(known)
            } catch (e: ApiException) {
                if (e.status == 404) null else throw e
            }
        }
        if (status == null) {
            status = api.uploadStart(t.root, t.dir, t.name, total, conflict = t.conflict)
            val started = status
            store.update(t.id) { it.copy(uploadId = started.id, name = started.name.ifEmpty { it.name }) }
        }
        val done = Uploader(api).upload(status, open) { d, all ->
            store.update(t.id) { it.copy(done = d, total = if (all > 0) all else it.total) }
        }
        store.update(t.id) { it.copy(name = done.name.ifEmpty { it.name }) }
    }

    private suspend fun download(store: TransferStore, api: TitanApi, t: Transfer) {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, t.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(t.name))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("no Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                val path = if (t.dir.isEmpty()) t.name else "${t.dir}/${t.name}"
                api.download(t.root, path, out) { d, all -> store.update(t.id) { it.copy(done = d, total = if (all > 0) all else it.total) } }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    companion object {
        const val KEY_ID = "id"
        /** Danach gilt eine Übertragung als gescheitert (mit Backoff gut einen Tag). */
        const val MAX_ATTEMPTS = 12

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
