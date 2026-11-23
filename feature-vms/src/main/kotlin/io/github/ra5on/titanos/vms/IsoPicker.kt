package io.github.ra5on.titanos.vms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Chips
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Ein Blatt von unten, solange diese Composable da ist; Wischen oder Zurück
 * schließt es und meldet [onClose]. Der Inhalt folgt dem Zustand der Seite.
 */
@Composable
fun OverlaySheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalComputeUi.current
    val current by rememberUpdatedState(content)
    val close by rememberUpdatedState(onClose)
    val token: @Composable ColumnScope.() -> Unit = remember { { current(this) } }
    DisposableEffect(token) {
        ui.sheet = token
        onDispose { if (ui.sheet === token) ui.sheet = null }
    }
    LaunchedEffect(token) {
        snapshotFlow { ui.sheet === token }.dropWhile { !it }.first { !it }
        close()
    }
}

/** Installationsmedium wählen: Ordner der Volumes durchgehen oder ISO-Dateien suchen (IsoPicker.svelte). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IsoPickerSheet(
    api: VmApi,
    title: String,
    text: String = "",
    initial: String = "",
    submit: String,
    busy: Boolean = false,
    error: String? = null,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var volumes by remember { mutableStateOf<List<String>>(emptyList()) }
    var volume by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var media by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(initial) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var serial by remember { mutableIntStateOf(0) }

    suspend fun open(next: String, path: String, quiet: Boolean = false): Boolean {
        val mine = ++serial
        loadError = null
        return try {
            val list = api.listFiles(next, path)
            if (mine == serial) {
                volume = next
                dir = path
                entries = list.filter { !it.name.startsWith(".") && (it.dir || isIso(it.name)) }
                    .sortedWith(compareBy<FileEntry> { !it.dir }.thenComparator { a, b -> naturalCompare(a.name, b.name) })
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!quiet && mine == serial) loadError = context.errorText(e)
            false
        }
    }

    LaunchedEffect(Unit) {
        launch { media = runCatching { api.media() }.getOrDefault(emptyList()) }
        try {
            volumes = api.fileRoots().filter { it.kind == "volume" }.map { it.name }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = context.errorText(e)
            return@LaunchedEffect
        }
        val at = isoLocation(initial, volumes)
        if (at != null) open(at.first, at.second)
        else if (volumes.isNotEmpty() && !open(volumes[0], "iso", quiet = true)) open(volumes[0], "")
    }

    OverlaySheet(onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.semantics { heading() })
            if (text.isNotEmpty()) Text(text, style = TitanType.body, color = Titan.colors.muted)
            TextInput(query, { query = it }, t("vms.iso.search"), leading = TitanIcons.search, placeholder = t("vms.iso.search"))
            if (query.isNotBlank()) {
                Card {
                    val found = media?.let { matchMedia(it, query) }
                    when {
                        found == null -> ListRow(t("vms.ov.cdSearching"))
                        found.isEmpty() -> ListRow(t("vms.iso.noMatch"))
                        else -> found.forEachIndexed { i, m ->
                            ListRow(m.path, sub = formatBytes(m.sizeBytes), divider = i > 0, icon = { RowIcon(TitanIcons.file) }, onClick = { chosen = m.path })
                        }
                    }
                }
            } else {
                if (volumes.size > 1) Chips(volumes.map { it to it }, volume, { v -> scope.launch { open(v, "") } })
                if (volume.isNotEmpty()) {
                    val crumbs = dir.split('/').filter { it.isNotEmpty() }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (crumbs.isNotEmpty()) Tool(t("vms.iso.up"), TitanIcons.back, { scope.launch { open(volume, crumbs.dropLast(1).joinToString("/")) } })
                        Tool(volume, TitanIcons.hdd, { scope.launch { open(volume, "") } })
                        crumbs.forEachIndexed { i, c -> Tool(c, null, { scope.launch { open(volume, crumbs.take(i + 1).joinToString("/")) } }) }
                    }
                }
                Card {
                    val list = entries
                    when {
                        volumes.isEmpty() && loadError == null -> ListRow(t("vms.iso.noVolumes"))
                        list == null -> ListRow("…")
                        list.isEmpty() -> ListRow(t("vms.iso.empty"))
                        else -> list.forEachIndexed { i, e ->
                            if (e.dir) {
                                ListRow(e.name, divider = i > 0, icon = { RowIcon(TitanIcons.folder) }, chevron = true, onClick = {
                                    scope.launch { open(volume, if (dir.isEmpty()) e.name else "$dir/${e.name}") }
                                })
                            } else {
                                val path = isoPath(volume, dir, e.name)
                                ListRow(
                                    e.name,
                                    sub = formatBytes(e.size),
                                    divider = i > 0,
                                    icon = { RowIcon(if (path == chosen) TitanIcons.check else TitanIcons.file, if (path == chosen) Titan.colors.accent else Titan.colors.muted) },
                                    onClick = { chosen = path },
                                )
                            }
                        }
                    }
                }
            }
            loadError?.let {
                NoticeBox(NoticeLevel.Error, it) { if (volume.isNotEmpty()) Tool(t("common.retry"), TitanIcons.restart, { scope.launch { open(volume, dir) } }) }
            }
            Field(t("vms.iso.chosen"), chosen, { chosen = it }, placeholder = "/mnt/…/….iso")
            error?.let { NoticeBox(NoticeLevel.Error, it) }
            FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                TitanButton(t("common.cancel"), onClose, enabled = !busy)
                TitanButton(submit, { if (!busy && chosen.isNotBlank()) onPick(chosen.trim()) }, kind = ButtonKind.Primary, enabled = !busy && chosen.isNotBlank())
            }
        }
    }
}
