package io.github.ra5on.titanos.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Eine geteilte Datei: Name und Größe aus dem Dokument. */
data class Incoming(val uri: Uri, val name: String, val size: Long)

/** Wohin geteilte Dateien gehen; das zuletzt benutzte Ziel merkt sich die App. */
data class Target(val root: String, val rootName: String, val path: String, val writable: Boolean = true) {
    val place: String get() = (listOf(rootName) + path.split('/').filter { it.isNotEmpty() }).joinToString("/")
}

/**
 * „Teilen → Auf TitanOS speichern“ (Entwurf 81). Die Dateien werden sofort
 * in den eigenen Speicher kopiert (die Leseerlaubnis des Teilens endet mit
 * der Activity) und dann als Aufträge hochgeladen.
 */
class ShareModel(private val context: Context, private val api: TitanApi, val items: List<Incoming>) {
    private val prefs = context.getSharedPreferences("share", Context.MODE_PRIVATE)

    var target by mutableStateOf(
        prefs.getString("root", null)?.let { Target(it, prefs.getString("rootName", it)!!, prefs.getString("path", "")!!) },
    )
        private set
    var replace by mutableStateOf(false)
    var browsing by mutableStateOf(target == null)
    var roots by mutableStateOf<List<FileRoot>?>(null)
        private set
    var browse by mutableStateOf<Target?>(null)
        private set
    var entries by mutableStateOf<List<FileEntry>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    val totalBytes: Long get() = items.sumOf { maxOf(it.size, 0) }

    suspend fun loadRoots() {
        roots = runCatching { api.roots().filter { it.kind == "share" || it.kind.isEmpty() } }.getOrElse { error = "offline"; emptyList() }
    }

    suspend fun open(t: Target?) {
        browse = t
        entries = null
        if (t == null) return
        entries = try {
            api.list(t.root, t.path).filter { it.dir }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            error = "offline"
            emptyList()
        }
    }

    fun choose(t: Target) {
        target = t
        browsing = false
        prefs.edit().putString("root", t.root).putString("rootName", t.rootName).putString("path", t.path).apply()
    }

    /** Kopieren und als Aufträge einreihen; liefert die Zahl der Dateien. */
    suspend fun upload(): Int {
        val t = target ?: return 0
        busy = true
        return try {
            withContext(Dispatchers.IO) {
                for (item in items) {
                    val copy = Outbox.copy(context, item.uri, item.name)
                    TransferQueue.enqueueUpload(
                        context, t.root, t.path, t.place, item.name, copy.length(), Uri.fromFile(copy),
                        conflict = if (replace) "replace" else "rename", ownCopy = true,
                    )
                }
            }
            items.size
        } catch (e: IOException) {
            error = "read"
            0
        } finally {
            busy = false
        }
    }

    companion object {
        fun describe(context: Context, uris: List<Uri>): List<Incoming> = uris.map { uri ->
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
            var size = -1L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            Incoming(uri, name, size)
        }
    }
}

@Composable
fun ShareTargetScreen(model: ShareModel, onCancel: () -> Unit, onUpload: () -> Unit, onOpen: (Target?) -> Unit) {
    val c = Titan.colors
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.share_title), onBack = onCancel, backLabel = stringResource(UiR.string.ui_cancel)) } },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                TitanButton(
                    stringResource(R.string.share_upload),
                    onUpload,
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Primary,
                    enabled = model.target != null && !model.busy && !model.browsing,
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.share_count, model.items.size, Format.bytes(model.totalBytes)),
            style = TitanType.body,
            color = c.muted,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            model.items.take(12).forEach { Chip(it.name, on = false, onClick = {}) }
            if (model.items.size > 12) Chip(stringResource(R.string.share_more, model.items.size - 12), on = false, onClick = {})
        }
        model.error?.let { Problem(stringResource(if (it == "read") R.string.share_read_failed else R.string.files_offline_nas), Modifier.padding(vertical = 8.dp)) }
        SectionHeader(stringResource(R.string.share_where))
        if (!model.browsing) {
            val t = model.target
            Card {
                ListRow(
                    t?.place ?: stringResource(R.string.share_none),
                    sub = stringResource(R.string.share_last),
                    icon = { AppIcon("shares", "yellow", 36.dp) },
                )
                ListRow(stringResource(R.string.share_other), icon = { RowIcon(TitanIcons.folder) }, chevron = true, divider = true, onClick = { model.browsing = true; onOpen(null) })
            }
        } else {
            val b = model.browse
            Card {
                if (b == null) {
                    val roots = model.roots
                    if (roots == null) ListRow(stringResource(UiR.string.ui_loading))
                    roots?.forEachIndexed { i, r ->
                        ListRow(
                            r.name,
                            sub = if (r.writable) null else stringResource(R.string.files_read_only),
                            icon = { AppIcon("shares", "yellow", 36.dp) },
                            chevron = r.writable,
                            divider = i > 0,
                            onClick = if (r.writable) ({ onOpen(Target(r.id, r.name, "")) }) else null,
                        )
                    }
                } else {
                    ListRow(
                        stringResource(R.string.share_here, b.place),
                        icon = { Icon(TitanIcons.check, null, tint = c.accent, modifier = Modifier.size(22.dp)) },
                        onClick = { model.choose(b) },
                    )
                    ListRow(
                        stringResource(R.string.share_up),
                        icon = { RowIcon(TitanIcons.back) },
                        divider = true,
                        onClick = {
                            val parent = b.path.substringBeforeLast('/', "")
                            onOpen(if (b.path.isEmpty()) null else b.copy(path = parent))
                        },
                    )
                    model.entries?.forEach { e ->
                        ListRow(
                            e.name,
                            icon = { AppIcon("files", "orange", 36.dp) },
                            chevron = true,
                            divider = true,
                            onClick = { onOpen(b.copy(path = if (b.path.isEmpty()) e.name else "${b.path}/${e.name}")) },
                        )
                    }
                }
            }
        }
        SectionHeader(stringResource(R.string.share_same_names))
        Card {
            ListRow(
                stringResource(R.string.share_rename),
                sub = stringResource(R.string.share_rename_text),
                icon = { RadioMark(!model.replace) },
                onClick = { model.replace = false },
            )
            ListRow(
                stringResource(R.string.share_replace),
                sub = stringResource(R.string.share_replace_text),
                icon = { RadioMark(model.replace) },
                divider = true,
                onClick = { model.replace = true },
            )
        }
        Box(Modifier.padding(bottom = 16.dp))
    }
}

