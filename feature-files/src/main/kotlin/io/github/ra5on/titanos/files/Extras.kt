package io.github.ra5on.titanos.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun Top(title: String, onBack: () -> Unit) {
    Box(Modifier.statusBarsPadding()) { TopBar(title, onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) }
}

private fun FilesModel.err(e: Exception) = errorOf(e)

/** Speicher-Analyse (StorageAnalysis im Web): größte Dateien, Unterordner, alte Dateien. */
@Composable
fun UsageScreen(model: FilesModel, location: Location, onOpen: (String, Boolean) -> Unit, onBack: () -> Unit) {
    val api = model.files ?: return
    var usage by remember { mutableStateOf<Usage?>(null) }
    var error by remember { mutableStateOf<FilesError?>(null) }
    LaunchedEffect(location) {
        try {
            usage = api.usage(location.root.id, location.path, model.prefs.showHidden)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.err(e)
        }
    }
    val c = Titan.colors
    Page(topBar = { Top(stringResource(R.string.usage_title), onBack) }) {
        Text(location.place, style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 12.dp))
        error?.let { Problem(errorText(it), Modifier.padding(top = 12.dp)) }
        val u = usage
        if (u == null && error == null) Text(stringResource(R.string.usage_running), style = TitanType.body, color = c.muted, modifier = Modifier.padding(top = 16.dp))
        if (u != null) {
            Text(
                stringResource(R.string.usage_total, Format.bytes(u.bytes), u.files, u.folders),
                style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(top = 12.dp),
            )
            @Composable
            fun list(title: Int, items: List<UsageItem>) {
                if (items.isEmpty()) return
                SectionHeader(stringResource(title))
                Card {
                    items.forEachIndexed { i, it ->
                        ListRow(
                            it.path.substringAfterLast('/').ifEmpty { it.path },
                            sub = listOfNotNull(it.path, Format.bytes(it.bytes), if (it.dir) pluralStringResource(R.plurals.usage_files, it.files.toInt(), it.files.toInt()) else null, it.modified?.let { m -> entryDate(m) }).joinToString(" · "),
                            icon = { RowIcon(if (it.dir) TitanIcons.folder else iconFor(it.path)) },
                            divider = i > 0,
                            onClick = { onOpen(it.path, it.dir) },
                            bottom = if (u.bytes > 0) { { Bar((it.bytes.toDouble() / u.bytes).toFloat(), Modifier.padding(top = 6.dp)) } } else null,
                        )
                    }
                }
            }
            list(R.string.usage_children, u.children)
            list(R.string.usage_largest, u.largest)
            list(R.string.usage_old, u.old)
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

/** Doppelte Dateien (DuplicatesDialog im Web): gleiche Prüfsumme; Löschen nur nach Auswahl. */
@Composable
fun DuplicatesScreen(model: FilesModel, location: Location, onDelete: (List<String>) -> Unit, onBack: () -> Unit) {
    val api = model.files ?: return
    var result by remember { mutableStateOf<Duplicates?>(null) }
    var error by remember { mutableStateOf<FilesError?>(null) }
    var marked by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(location) {
        try {
            result = api.duplicates(location.root.id, location.path, model.prefs.showHidden)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.err(e)
        }
    }
    val c = Titan.colors
    Page(topBar = { Top(stringResource(R.string.dupes_title), onBack) }) {
        Text(location.place, style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 12.dp))
        error?.let { Problem(errorText(it), Modifier.padding(top = 12.dp)) }
        val r = result
        if (r == null && error == null) Text(stringResource(R.string.dupes_running), style = TitanType.body, color = c.muted, modifier = Modifier.padding(top = 16.dp))
        if (r != null) {
            Text(pluralStringResource(R.plurals.dupes_groups, r.groups.size, r.groups.size, r.scanned), style = TitanType.body, color = c.text, modifier = Modifier.padding(top = 12.dp))
            r.groups.forEach { g ->
                SectionHeader(stringResource(R.string.dupes_group, g.paths.size, Format.bytes(g.size)))
                Card {
                    g.paths.forEachIndexed { i, p ->
                        ListRow(p.substringAfterLast('/'), sub = p, icon = { RadioMark(p in marked) }, divider = i > 0, onClick = { marked = if (p in marked) marked - p else marked + p })
                    }
                }
            }
            if (location.root.writable && marked.isNotEmpty()) {
                TitanButton(
                    pluralStringResource(R.plurals.dupes_delete, marked.size, marked.size), { onDelete(marked.toList()) },
                    Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.Danger,
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

/** Mehrere umbenennen (RenameBatchDialog im Web): Suchen/Ersetzen, Vorne, Hinten, Nummer; erst Vorschau. */
@Composable
fun BatchRenameScreen(model: FilesModel, location: Location, names: List<String>, onDone: () -> Unit, onBack: () -> Unit) {
    val api = model.files ?: return
    var req by remember { mutableStateOf(RenamePreviewRequest(location.root.id, location.path, names)) }
    var preview by remember { mutableStateOf<List<RenameChange>?>(null) }
    var error by remember { mutableStateOf<FilesError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(req) {
        kotlinx.coroutines.delay(300)
        try {
            preview = api.renamePreview(req)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.err(e)
        }
    }
    val c = Titan.colors
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    Page(topBar = { Top(stringResource(R.string.batch_title), onBack) }) {
        Spacer(Modifier.height(12.dp))
        TextInput(req.find, { req = req.copy(find = it) }, stringResource(R.string.batch_find), placeholder = stringResource(R.string.batch_find))
        Spacer(Modifier.height(8.dp))
        TextInput(req.replace, { req = req.copy(replace = it) }, stringResource(R.string.batch_replace), placeholder = stringResource(R.string.batch_replace))
        Spacer(Modifier.height(8.dp))
        TextInput(req.prefix, { req = req.copy(prefix = it) }, stringResource(R.string.batch_prefix), placeholder = stringResource(R.string.batch_prefix))
        Spacer(Modifier.height(8.dp))
        TextInput(req.suffix, { req = req.copy(suffix = it) }, stringResource(R.string.batch_suffix), placeholder = stringResource(R.string.batch_suffix))
        Card(Modifier.padding(top = 10.dp)) {
            ListRow(stringResource(R.string.batch_number), sub = stringResource(R.string.batch_number_text), right = { Toggle(req.number, { req = req.copy(number = it) }, on, off) }, onClick = { req = req.copy(number = !req.number) })
        }
        if (req.number) {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.48f)) {
                    TextInput(req.start.toString(), { v -> v.toIntOrNull()?.let { req = req.copy(start = it.coerceIn(0, 999_999)) } }, stringResource(R.string.batch_start), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Box(Modifier.fillMaxWidth(0.48f)) {
                    TextInput(req.digits.toString(), { v -> v.toIntOrNull()?.let { req = req.copy(digits = it.coerceIn(1, 9)) } }, stringResource(R.string.batch_digits), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        }
        error?.let { Problem(errorText(it), Modifier.padding(top = 12.dp)) }
        preview?.let { list ->
            SectionHeader(stringResource(R.string.batch_preview))
            Card {
                list.forEachIndexed { i, ch ->
                    val problem = when (ch.problem) {
                        "invalid" -> stringResource(R.string.batch_problem_invalid)
                        "protected" -> stringResource(R.string.batch_problem_protected)
                        "exists" -> stringResource(R.string.files_exists)
                        "duplicate" -> stringResource(R.string.batch_problem_duplicate)
                        else -> null
                    }
                    ListRow(
                        ch.to,
                        sub = ch.from + (problem?.let { " · $it" } ?: ""),
                        titleColor = if (problem != null) c.dangerInk else c.text,
                        divider = i > 0,
                    )
                }
            }
            val ok = list.isNotEmpty() && list.none { it.problem.isNotEmpty() } && list.any { it.from != it.to }
            TitanButton(
                stringResource(R.string.batch_do), {
                    busy = true
                    scope.launch {
                        try {
                            val r = api.renameBatch(location.root.id, list.filter { it.from != it.to })
                            if (r.undo.isNotEmpty()) model.undo = UndoOffer(r.undo, UndoKind.Rename, r.done)
                            if (r.error.isNotEmpty()) error = FilesError.Other(r.error)
                            model.reload()
                            if (r.error.isEmpty()) onDone()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = model.err(e)
                        } finally {
                            busy = false
                        }
                    }
                },
                Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.Primary, enabled = ok && !busy,
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

/** ZIP ansehen und entpacken (ArchiveDialog im Web): Inhalt, Auswahl, in einen neuen Ordner. */
@Composable
fun ExtractScreen(model: FilesModel, location: Location, entry: FileEntry, onStarted: () -> Unit, onBack: () -> Unit) {
    val api = model.files ?: return
    var list by remember { mutableStateOf<ArchiveList?>(null) }
    var error by remember { mutableStateOf<FilesError?>(null) }
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    var folder by remember { mutableStateOf(entry.name.substringBeforeLast('.')) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(entry) {
        try {
            list = api.archive(location.root.id, location.full(entry.name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.err(e)
        }
    }
    val c = Titan.colors
    Page(topBar = { Top(stringResource(R.string.extract_title), onBack) }) {
        Text(entry.name, style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(top = 12.dp))
        error?.let { Problem(errorText(it), Modifier.padding(top = 12.dp)) }
        val l = list
        if (l == null && error == null) Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(top = 12.dp))
        if (l != null) {
            val all = l.entries.filter { !it.dir }.map { it.path }
            val chosen = picked ?: all.toSet()
            Text(pluralStringResource(R.plurals.extract_count, all.size, all.size, Format.bytes(l.bytes)), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            TextInput(folder, { folder = it }, stringResource(R.string.extract_folder), placeholder = stringResource(R.string.extract_folder))
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.files_select_all), onClick = { picked = all.toSet() })
                Chip(stringResource(R.string.extract_none), onClick = { picked = emptySet() })
            }
            TitanButton(
                stringResource(R.string.extract_do), {
                    scope.launch {
                        try {
                            val job = api.extract(ExtractRequest(location.root.id, location.full(entry.name), l.version, chosen.toList(), location.root.id, location.path, folder.trim()))
                            model.watchJobs()
                            if (job.id.isNotEmpty()) onStarted()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = model.err(e)
                        }
                    }
                },
                Modifier.fillMaxWidth().padding(top = 12.dp), kind = ButtonKind.Primary, enabled = chosen.isNotEmpty() && folder.isNotBlank() && location.root.writable,
            )
            Card(Modifier.padding(top = 12.dp)) {
                l.entries.filter { !it.dir }.take(2000).forEachIndexed { i, e ->
                    ListRow(e.path, sub = Format.bytes(e.size), icon = { RadioMark(e.path in chosen) }, divider = i > 0, onClick = {
                        picked = if (e.path in chosen) chosen - e.path else chosen + e.path
                    })
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}
