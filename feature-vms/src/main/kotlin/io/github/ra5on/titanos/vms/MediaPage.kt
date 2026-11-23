package io.github.ra5on.titanos.vms

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Chips
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.compute.webText
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

/** Schon gelesene Daten der Seite Medien (Bildtests). */
class MediaPreload(val jobs: List<MediaJob>, val media: List<MediaEntry>, val vms: List<Vm>, val volumes: List<Volume>)

/** Eine gewählte Datei auf dem Gerät. */
data class PickedFile(val uri: Uri, val name: String, val size: Long)

fun Context.picked(uri: Uri): PickedFile? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
    if (!c.moveToFirst()) return null
    PickedFile(uri, c.getString(0) ?: "upload", c.getLong(1))
}

/**
 * Hochladen in Stücken wie media/+page.svelte: Bereits hochgeladenes erst
 * prüfen (SHA-256 je Stück, 32 auf einmal), dann den Rest Stück für Stück,
 * zuletzt `finish`. Pausieren hält nach dem laufenden Stück an.
 */
@Stable
class MediaUploader(private val api: VmApi, private val context: Context) {
    var uploading by mutableStateOf("")
    var verifyProgress by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    val paused = mutableStateListOf<String>()

    suspend fun send(job: MediaJob, file: PickedFile, chunk: Long, reload: suspend () -> Unit) {
        if (file.name != job.uploadName || file.size != job.uploadSize) {
            error = context.webText("media.originalFile")
            return
        }
        paused.remove(job.id)
        uploading = job.id
        try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(file.uri)?.use { input -> upload(job, file, chunk, input, reload) } ?: error("no stream")
            }
            if (job.id !in paused) api.importAction(job.id, "finish")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = context.errorText(e, detail = true)
        } finally {
            uploading = ""
            verifyProgress = ""
            reload()
        }
    }

    private suspend fun upload(job: MediaJob, file: PickedFile, chunk: Long, input: InputStream, reload: suspend () -> Unit) {
        val buf = ByteArray(chunk.toInt())
        if (job.bytes > 0) {
            val prefix = context.webText("media.verifyPrefix")
            verifyProgress = prefix
            val proofs = mutableListOf<ChunkProof>()
            var offset = 0L
            while (offset < job.bytes) {
                val size = minOf(chunk, job.bytes - offset).toInt()
                readFully(input, buf, size)
                val hash = MessageDigest.getInstance("SHA-256").apply { update(buf, 0, size) }.digest().joinToString("") { "%02x".format(it) }
                proofs += ChunkProof(offset, size.toLong(), hash)
                offset += size
                if (proofs.size == 32 || offset == job.bytes) {
                    api.verify(job.id, proofs.toList())
                    proofs.clear()
                    verifyProgress = "$prefix ${formatBytes(offset)} / ${formatBytes(job.bytes)}"
                }
            }
            verifyProgress = ""
        }
        // Angehalten, abgebrochen oder verlassen während der Prüfung: kein Fortsetzen.
        if (job.id in paused) return
        if (job.phase == "paused" || job.phase == "failed") api.importAction(job.id, "resume")
        var at = job.bytes
        while (at < file.size) {
            if (job.id in paused) break
            val n = minOf(chunk, file.size - at).toInt()
            readFully(input, buf, n)
            api.putChunk(job.id, at, buf, n)
            at += n
            reload()
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, n: Int) {
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) error("short file")
            read += r
        }
    }
}

/** Abschnitt Medien (routes/vms/media): Importe per Adresse oder Hochladen, vorhandene Medien. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaPage(api: VmApi, preload: MediaPreload? = null) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploader = remember { MediaUploader(api, context) }
    var jobs by remember { mutableStateOf(preload?.jobs.orEmpty()) }
    var loading by remember { mutableStateOf(preload == null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var chunk by remember { mutableStateOf(8L shl 20) }
    var media by remember { mutableStateOf(preload?.media) }
    var mediaError by remember { mutableStateOf<String?>(null) }
    var vms by remember { mutableStateOf(preload?.vms.orEmpty()) }
    var volumes by remember { mutableStateOf(preload?.volumes.orEmpty()) }
    var adding by remember { mutableStateOf(false) }
    val controlling = remember { mutableStateListOf<String>() }
    var reselectFor by remember { mutableStateOf<MediaJob?>(null) }

    suspend fun loadMedia() {
        try {
            media = api.media()
            vms = api.status().vms
            mediaError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mediaError = context.errorText(e)
        }
    }
    suspend fun load() {
        try {
            val res = api.imports()
            val next = res.jobs.orEmpty()
            val finished = next.any { j -> j.phase == "done" && jobs.any { it.id == j.id && it.phase != "done" } }
            jobs = next
            chunk = res.chunkBytes.takeIf { it > 0 } ?: (8L shl 20)
            loadError = null
            if (finished) loadMedia()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = context.errorText(e)
        } finally {
            loading = false
        }
    }
    val active = uploader.uploading.isNotEmpty() || jobs.any { it.active || it.phase == "uploading" }
    LaunchedEffect(Unit) {
        launch { loadMedia() }
        launch { runCatching { volumes = api.storage().volumes.filter { it.mounted } } }
    }
    LaunchedEffect(active) {
        while (true) {
            load()
            delay(if (active) 1500 else 15000)
        }
    }
    // Wie im Web: beim Verlassen pausiert ein laufendes Hochladen.
    DisposableEffect(Unit) {
        onDispose {
            val id = uploader.uploading
            if (id.isNotEmpty()) {
                uploader.paused += id
                CoroutineScope(Dispatchers.IO).launch { runCatching { api.importAction(id, "pause") } }
            }
        }
    }
    val reselect = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val job = reselectFor
        reselectFor = null
        val file = uri?.let { context.picked(it) }
        if (job != null && file != null) scope.launch { uploader.send(job, file, chunk) { load() } }
    }

    fun control(job: MediaJob, action: String) {
        if (job.id in controlling) return
        controlling += job.id
        uploader.error = null
        if (action == "pause" || action == "cancel") uploader.paused += job.id
        scope.launch {
            try {
                api.importAction(job.id, action)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uploader.error = context.errorText(e, detail = true)
            } finally {
                controlling.remove(job.id)
            }
        }
    }
    fun ask(job: MediaJob, action: String) {
        val remove = action == "remove"
        ui.confirm(
            Ask(
                ui.text(if (remove) "media.removeTitle" else "media.cancelTitle"),
                ui.text(if (remove) "media.removeHint" else "media.cancelHint", "name" to job.name),
                ui.text(if (remove) "media.remove" else "media.cancelImport"),
            ) { control(job, action) },
        )
    }
    val phases = setOf("uploading", "downloading", "extracting", "verifying", "publishing", "paused", "pausing", "failed", "review", "cancelled", "done")
    fun usedBy(path: String) = vms.filter { it.iso == path || it.virtioISO == path }.map { it.label }

    ScrollPage {
        Muted(t("media.intro"), Modifier.padding(top = 6.dp))
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tool(t("media.add"), TitanIcons.plus, { uploader.error = null; adding = true }, enabled = volumes.isNotEmpty(), primary = true)
        }
        if (!adding) uploader.error?.let { NoticeBox(NoticeLevel.Error, it) }
        loadError?.let { NoticeBox(NoticeLevel.Warn, it) { Tool(t("common.retry"), TitanIcons.restart, { scope.launch { load() } }) } }
        if (uploader.verifyProgress.isNotEmpty()) Muted(uploader.verifyProgress)
        when {
            loading -> Muted(t("common.loading"))
            jobs.isEmpty() -> EmptyState(t("media.empty"), "")
            else -> jobs.forEach { job ->
                Group(job.name) {
                    FactRow(
                        t(if (job.phase in phases) "media.phase.${job.phase}" else "media.phase.review"),
                        job.volume + " · " + formatBytes(job.bytes) + (if (job.total > 0) " / " + formatBytes(job.total) else "") +
                            if (job.written > 0) " · ${t("media.unpacked")}: ${formatBytes(job.written)}" else "",
                    )
                    if (job.total > 0 && job.phase in setOf("uploading", "downloading", "paused")) {
                        Bar((job.bytes.toFloat() / job.total).coerceIn(0f, 1f), Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    }
                    job.error?.let {
                        NoticeBox(if (job.phase == "review") NoticeLevel.Warn else NoticeLevel.Error, s(if (job.phase == "review") R.string.vms_job_review else R.string.vms_job_failed), Modifier.padding(horizontal = 10.dp), detail = it)
                    }
                    job.output?.let { FactRow(t("media.col.file"), it, divider = true, mono = true) }
                    job.sha256?.takeIf { job.output != null }?.let { FactRow("SHA-256", it, divider = true, mono = true) }
                    val busy = job.id in controlling
                    val mine = uploader.uploading == job.id
                    FlowRow(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if ((job.active && job.phase != "publishing" && job.phase != "pausing") || mine) Tool(t("media.pause"), TitanIcons.stop, { control(job, "pause") }, enabled = !busy)
                        if (job.phase == "review") Tool(t("media.review"), TitanIcons.check, { control(job, "review") }, enabled = !busy)
                        if (job.url != null && job.phase == "paused") Tool(t("media.resume"), TitanIcons.play, { control(job, "resume") }, enabled = !busy)
                        if (job.url != null && job.phase == "failed") Tool(t("common.retry"), TitanIcons.restart, { control(job, "retry") }, enabled = !busy)
                        if (job.url == null && job.sourceReady != true && job.phase in setOf("uploading", "paused", "failed") && !mine) {
                            Tool(t("media.reselect"), TitanIcons.upload, { reselectFor = job; reselect.launch(arrayOf("*/*")) })
                        }
                        if (job.url == null && job.sourceReady == true && job.phase in setOf("paused", "failed")) Tool(t("media.resume"), TitanIcons.play, { control(job, "resume") }, enabled = !busy)
                        if (job.phase !in setOf("done", "cancelled", "review", "publishing", "pausing")) Tool(t("media.cancelImport"), TitanIcons.x, { ask(job, "cancel") }, enabled = !busy, danger = true)
                        if (!job.active && !mine) Tool(t("media.remove"), TitanIcons.trash, { ask(job, "remove") }, enabled = !busy, danger = true)
                    }
                }
            }
        }
        Muted(t("media.background"), Modifier.padding(vertical = 8.dp))
        Group(t("media.files"), tools = { Tool(t("media.filesRefresh"), TitanIcons.restart, { scope.launch { loadMedia() } }) }) {
            mediaError?.let { NoticeBox(NoticeLevel.Error, it, Modifier.padding(10.dp)) { Tool(t("common.retry"), TitanIcons.restart, { scope.launch { loadMedia() } }) } }
            val list = media
            when {
                list == null && mediaError == null -> Muted(t("common.loading"), Modifier.padding(14.dp))
                list != null && list.isEmpty() -> Muted(t("media.filesEmpty"), Modifier.padding(14.dp))
                list != null -> list.forEachIndexed { i, m ->
                    val users = usedBy(m.path)
                    FactRow(m.path, formatBytes(m.sizeBytes) + " · " + if (users.isNotEmpty()) t("media.usedBy", "vms" to users.joinToString(", ")) else t("media.unused"), divider = i > 0)
                }
            }
        }
        media?.let { list ->
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Muted(t("media.statFiles", "count" to list.size) + " · " + formatBytes(list.sumOf { it.sizeBytes }))
                Muted(t("media.unused") + ": " + list.count { usedBy(it.path).isEmpty() })
            }
        }
    }

    if (adding) AddMediaSheet(api, volumes, uploader, onClose = { adding = false }) { job, file ->
        adding = false
        scope.launch {
            load()
            if (file != null) uploader.send(job, file, chunk) { load() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddMediaSheet(api: VmApi, volumes: List<Volume>, uploader: MediaUploader, onClose: () -> Unit, onStarted: (MediaJob, PickedFile?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("url") }
    var url by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf(volumes.firstOrNull()?.name.orEmpty()) }
    var name by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var compression by remember { mutableStateOf("auto") }
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf(false) }
    var autoName by remember { mutableStateOf(true) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val file = uri?.let { context.picked(it) }
        if (file != null) {
            picked = file
            name = mediaOutputName(file.name)
        }
    }
    val shaValid = sha.isEmpty() || Regex("^[a-fA-F0-9]{64}$").matches(sha)
    OverlaySheet(onClose) {
        Text(t("media.add"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.semantics { heading() })
        error?.let { NoticeBox(NoticeLevel.Error, it) }
        Chips(listOf("url" to t("media.url"), "upload" to t("media.upload")), source, { source = it })
        if (source == "url") {
            Field(t("media.url"), url, { v ->
                url = v
                if (autoName) name = runCatching { Uri.parse(v).path.orEmpty() }.getOrDefault("").takeIf { it.length > 1 }?.let { mediaOutputName(it) }.orEmpty()
            }, placeholder = "https://…")
        } else {
            FactRow(t("media.upload"), picked?.let { "${it.name} · ${formatBytes(it.size)}" } ?: "–") {
                Tool(s(R.string.vms_pick_file), TitanIcons.folder, { pick.launch(arrayOf("*/*")) })
            }
        }
        Choice(t("media.volume"), volumes.map { it.name to "${it.name} · ${formatBytes(it.free)}" }, volume, { volume = it })
        Field(t("media.filename"), name, { name = it.take(120); autoName = false })
        Tool(t("media.options"), if (options) TitanIcons.down else TitanIcons.chev, { options = !options })
        if (options) {
            Field(t("media.sourceHash"), sha, { sha = it.trim() }, error = if (!shaValid) s(R.string.vms_sha_invalid) else null)
            Choice(t("media.compression"), listOf("auto" to t("netx.automatic"), "none" to t("media.none"), "gz" to "gzip", "xz" to "xz", "zip" to "ZIP"), compression, { compression = it })
        }
        Muted(t("media.archiveHint"))
        Buttons(
            submit = t("media.start"),
            enabled = !busy && volume.isNotEmpty() && name.isNotBlank() && shaValid && (if (source == "upload") picked != null else url.isNotBlank()),
            busy = busy,
            onCancel = onClose,
        ) {
            busy = true
            error = null
            scope.launch {
                try {
                    val file = picked.takeIf { source == "upload" }
                    val job = api.startImport(
                        MediaImportRequest(
                            volume = volume,
                            name = name,
                            sha256 = sha,
                            compression = compression,
                            url = url.takeIf { source == "url" },
                            uploadName = file?.name,
                            uploadSize = file?.size,
                        ),
                    )
                    onStarted(job, file)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = context.errorText(e, detail = true)
                } finally {
                    busy = false
                }
            }
        }
    }
}
