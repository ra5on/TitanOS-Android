package io.github.ra5on.titanos.files

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.TitanApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Ein als „offline verfügbar“ markierter Ordner oder eine Datei (Entwurf 24). */
@Serializable
data class OfflinePin(
    val root: String,
    /** Name der Freigabe für die Anzeige („Documents“). */
    val rootName: String,
    /** Pfad in der Freigabe, „“ = ganze Freigabe. */
    val path: String,
    val dir: Boolean,
    /** Stand des letzten Abgleichs. */
    val files: Int = 0,
    val bytes: Long = 0,
    val missing: Int = 0,
    val problem: String? = null,
) {
    val name: String get() = path.substringAfterLast('/').ifEmpty { rootName }
    val parent: String get() = (listOf(rootName) + path.split('/').filter { it.isNotEmpty() }.dropLast(1)).joinToString("/")
    fun covers(r: String, p: String) = root == r && (p == path || (dir && (path.isEmpty() || p.startsWith("$path/"))))
}

/** Eine abgelegte Datei: Pfad in der Freigabe, Stand am NAS. */
@Serializable
data class OfflineCopy(val root: String, val path: String, val size: Long, val modified: String)

@Serializable
private data class OfflineFile(
    val pins: List<OfflinePin> = emptyList(),
    val copies: List<OfflineCopy> = emptyList(),
    val lastSyncMs: Long = 0,
    val wifiOnly: Boolean = true,
    val limitBytes: Long = OfflineStore.DEFAULT_LIMIT,
)

/**
 * Offline-Dateien: was markiert ist, liegt unter `files/offline/<freigabe>/…`
 * im privaten Speicher der App und wird beim Abgleich auf den Stand des NAS
 * gebracht. Am Handy geöffnet werden die Kopien nur lesend; Änderungen am
 * Handy gehen in dieser Testfassung nicht zurück aufs NAS.
 */
class OfflineStore(private val base: File) {
    private val stateFile = File(base, "offline.json")
    val dir = File(base, "offline")
    val pins = mutableStateListOf<OfflinePin>()
    private val copies = HashMap<String, OfflineCopy>()
    var lastSyncMs by mutableLongStateOf(0L)
        private set
    var wifiOnly by mutableStateOf(true)
        private set
    var limitBytes by mutableLongStateOf(DEFAULT_LIMIT)
        private set
    var syncing by mutableStateOf(false)
        internal set

    init {
        runCatching { json.decodeFromString(OfflineFile.serializer(), stateFile.readText()) }.getOrNull()?.let { f ->
            pins.addAll(f.pins)
            f.copies.forEach { copies[key(it.root, it.path)] = it }
            lastSyncMs = f.lastSyncMs
            wifiOnly = f.wifiOnly
            limitBytes = f.limitBytes
        }
    }

    private fun key(root: String, path: String) = "$root\u0000$path"

    /** Ablage einer Datei; ein Pfad, der aus dem Ordner führen würde („..“), wird abgelehnt. */
    fun local(root: String, path: String): File {
        val f = File(File(dir, safe(root)), path)
        if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw IOException("path outside: $path")
        return f
    }

    /** Liegt diese Datei offline vor (abgelegt und markiert)? */
    fun copyOf(root: String, path: String): File? = runCatching { local(root, path) }.getOrNull()
        ?.takeIf { copies.containsKey(key(root, path)) && it.isFile && pins.any { p -> p.covers(root, path) } }

    fun isPinned(root: String, path: String) = synchronized(this) { pins.any { it.root == root && it.path == path } }

    fun covered(root: String, path: String) = synchronized(this) { pins.any { it.covers(root, path) } }

    val usedBytes: Long get() = synchronized(this) { copies.values.sumOf { it.size } }

    fun pin(root: String, rootName: String, path: String, dir: Boolean) {
        synchronized(this) {
            if (pins.none { it.root == root && it.path == path }) pins.add(OfflinePin(root, rootName, path, dir))
            save()
        }
    }

    /** Markierung aufheben: Kopien, die kein anderer Eintrag mehr deckt, löschen. */
    fun unpin(root: String, path: String) {
        synchronized(this) {
            pins.removeAll { it.root == root && it.path == path }
            val gone = copies.values.filter { c -> pins.none { it.covers(c.root, c.path) } }
            gone.forEach { c ->
                local(c.root, c.path).delete()
                copies.remove(key(c.root, c.path))
            }
            pruneDirs(dir)
            save()
        }
    }

    fun changeWifiOnly(on: Boolean) = synchronized(this) { wifiOnly = on; save() }

    internal fun recorded(root: String, path: String): OfflineCopy? = synchronized(this) { copies[key(root, path)] }

    internal fun record(c: OfflineCopy) = synchronized(this) { copies[key(c.root, c.path)] = c }

    internal fun drop(root: String, path: String) = synchronized(this) {
        copies.remove(key(root, path))
        local(root, path).delete()
    }

    internal fun copiesUnder(pin: OfflinePin) = synchronized(this) { copies.values.filter { pin.covers(it.root, it.path) } }

    /** Die abgelegten Dateien eines Eintrags (für die Ansicht ohne NAS). */
    fun copiesFor(pin: OfflinePin): List<OfflineCopy> = copiesUnder(pin).filter { runCatching { local(it.root, it.path).isFile }.getOrDefault(false) }

    /** Stand nach einem Abgleich übernehmen. */
    fun finish(updated: List<OfflinePin>) = synchronized(this) {
        updated.forEach { u ->
            val i = pins.indexOfFirst { it.root == u.root && it.path == u.path }
            if (i >= 0) pins[i] = u
        }
        lastSyncMs = System.currentTimeMillis()
        pruneDirs(dir)
        save()
    }

    private fun pruneDirs(d: File) {
        d.listFiles()?.filter { it.isDirectory }?.forEach { pruneDirs(it); if (it.list()?.isEmpty() == true) it.delete() }
    }

    private fun save() {
        base.mkdirs()
        val tmp = File(base, "offline.json.tmp")
        runCatching {
            tmp.writeText(json.encodeToString(OfflineFile.serializer(), OfflineFile(pins.toList(), copies.values.toList(), lastSyncMs, wifiOnly, limitBytes)))
            tmp.renameTo(stateFile)
        }
    }

    companion object {
        /** 5 GB, wie das Handy sie anzeigt (dezimal). */
        const val DEFAULT_LIMIT = 5_000_000_000L
        private val json = Json { ignoreUnknownKeys = true }
        /** Ein Name der Freigabe als Ordnername ohne „/“ und „..“. */
        fun safe(s: String) = s.replace('/', '_').replace("..", "_")
    }
}

/**
 * Abgleich der Offline-Dateien. Holt neue und geänderte Dateien (Größe oder
 * Änderungszeit anders), löscht am Handy, was am NAS fehlt, und bleibt unter
 * der Obergrenze. Ein Fehler bei einem Eintrag hält die anderen nicht auf.
 */
class OfflineSync(private val store: OfflineStore, private val api: TitanApi) {

    suspend fun run() {
        store.syncing = true
        try {
            var budget = store.limitBytes
            val updated = store.pins.toList().map { pin ->
                try {
                    val remote = collect(pin)
                    var bytes = 0L
                    var missing = 0
                    for ((path, e) in remote) {
                        val had = store.recorded(pin.root, path)
                        val fresh = had != null && had.size == e.size && had.modified == e.modified && store.local(pin.root, path).isFile
                        if (!fresh) {
                            if (budget - e.size < 0) {
                                missing++
                                continue
                            }
                            fetch(pin.root, path, e)
                        }
                        budget -= e.size
                        bytes += e.size
                    }
                    // Am NAS gelöscht oder umbenannt: am Handy weg.
                    store.copiesUnder(pin).filter { it.path !in remote.keys }.forEach { store.drop(it.root, it.path) }
                    pin.copy(files = remote.size - missing, bytes = bytes, missing = missing, problem = if (missing > 0) "limit" else null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    pin.copy(problem = if (e.status == 404) "gone" else "${e.status} ${e.code}".trim())
                } catch (e: IOException) {
                    pin.copy(problem = "offline")
                }
            }
            store.finish(updated)
        } finally {
            store.syncing = false
        }
    }

    /** Alle Dateien eines Eintrags: Pfad in der Freigabe → Eintrag des NAS. */
    private suspend fun collect(pin: OfflinePin): Map<String, FileEntry> {
        val out = LinkedHashMap<String, FileEntry>()
        if (!pin.dir) {
            val parent = pin.path.substringBeforeLast('/', "")
            val e = api.list(pin.root, parent).firstOrNull { !it.dir && it.name == pin.name }
                ?: throw ApiException(404, "not_found", pin.path)
            out[pin.path] = e
            return out
        }
        val todo = ArrayDeque<Pair<String, Int>>().apply { add(pin.path to 0) }
        while (todo.isNotEmpty() && out.size < MAX_FILES) {
            val (path, depth) = todo.removeFirst()
            for (e in api.list(pin.root, path)) {
                val full = if (path.isEmpty()) e.name else "$path/${e.name}"
                if (e.dir) {
                    if (depth < MAX_DEPTH) todo.add(full to depth + 1)
                } else {
                    out[full] = e
                }
            }
        }
        return out
    }

    private suspend fun fetch(root: String, path: String, e: FileEntry) {
        val target = store.local(root, path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.part")
        try {
            tmp.outputStream().use { out -> api.download(root, path, out) }
            if (!tmp.renameTo(target)) throw IOException("rename")
            store.record(OfflineCopy(root, path, e.size, e.modified))
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val MAX_FILES = 10_000
        const val MAX_DEPTH = 20
        private const val PERIODIC = "offline-sync"
        private const val NOW = "offline-sync-now"

        private fun constraints(store: OfflineStore) = Constraints.Builder()
            .setRequiredNetworkType(if (store.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        /** Alle 6 Stunden; bei geänderter Einstellung neu. */
        fun schedule(context: Context, store: OfflineStore) {
            val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints(store)).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Gleich abgleichen (nach dem Markieren, beim Öffnen der App). */
        fun now(context: Context, store: OfflineStore) {
            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>().setConstraints(constraints(store)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

class OfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = FilesRuntime.offline ?: return Result.retry()
        if (store.pins.isEmpty()) return Result.success()
        val api = FilesRuntime.api() ?: return Result.success()
        OfflineSync(store, api).run()
        return Result.success()
    }
}
