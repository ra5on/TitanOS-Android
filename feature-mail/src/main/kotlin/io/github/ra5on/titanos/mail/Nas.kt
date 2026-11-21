package io.github.ra5on.titanos.mail

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.R as UiR

/**
 * Dateien des NAS mit den Rechten des Kontos (GET /files/roots, /files/list):
 * einen Ordner wählen („In Dateien speichern“) oder Dateien („Aus Dateien des NAS“).
 */
@Composable
fun BoxScope.NasPicker(
    titan: TitanApi,
    title: String,
    pickFiles: Boolean,
    onFolder: (root: FileRoot, path: String) -> Unit = { _, _ -> },
    onFiles: (List<NasFile>) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var roots by remember { mutableStateOf<List<FileRoot>?>(null) }
    var root by remember { mutableStateOf<FileRoot?>(null) }
    var path by remember { mutableStateOf(listOf<String>()) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var chosen by remember { mutableStateOf(listOf<NasFile>()) }
    val c = Titan.colors
    LaunchedEffect(Unit) {
        try {
            roots = titan.roots().filter { !pickFiles || true }
        } catch (e: Exception) {
            error = e
        }
    }
    LaunchedEffect(root, path) {
        val r = root ?: return@LaunchedEffect
        entries = null
        try {
            entries = titan.list(r.id, path.joinToString("/")).sortedWith(compareBy<FileEntry>({ !it.dir }, { it.name.lowercase() }))
            error = null
        } catch (e: Exception) {
            error = e
            entries = emptyList()
        }
    }
    Sheet(onDismiss = onDismiss) {
        Text(title, style = TitanType.title, color = c.text, modifier = Modifier.padding(bottom = 6.dp))
        val place = root?.let { listOf(it.name) + path }?.joinToString(" › ")
        if (place != null) Text(place, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
        error?.let { Problem(errorText(it), Modifier.padding(bottom = 8.dp)) }
        Card(background = c.bg) {
            val r = root
            if (r == null) {
                val list = roots
                if (list == null) {
                    Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(14.dp))
                } else {
                    list.filter { pickFiles || it.writable }.forEachIndexed { i, fr ->
                        ListRow(fr.name, icon = { RowIcon(TitanIcons.hdd) }, chevron = true, divider = i > 0, onClick = { root = fr; path = emptyList() })
                    }
                }
            } else {
                ListRow(stringResource(R.string.mailx_nas_up), icon = { RowIcon(TitanIcons.back) }, onClick = {
                    if (path.isEmpty()) root = null else path = path.dropLast(1)
                })
                val list = entries
                if (list == null) {
                    Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(14.dp))
                } else {
                    for (e in list) {
                        if (e.dir) {
                            ListRow(e.name, icon = { RowIcon(TitanIcons.folder) }, chevron = true, divider = true, onClick = { path = path + e.name })
                        } else if (pickFiles) {
                            val ref = NasFile(r.id, (path + e.name).joinToString("/"))
                            val on = ref in chosen
                            ListRow(
                                e.name,
                                sub = Format.bytes(e.size),
                                icon = { RowIcon(TitanIcons.file) },
                                right = { RadioMark(on) },
                                divider = true,
                                onClick = { chosen = if (on) chosen - ref else chosen + ref },
                            )
                        }
                    }
                }
            }
        }
        Gap(12.dp)
        val r = root
        if (pickFiles) {
            TitanButton(
                if (chosen.isEmpty()) stringResource(R.string.mailx_nas_attach_none) else stringResource(R.string.mailx_nas_attach, chosen.size.toString()),
                { onFiles(chosen) },
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Primary,
                enabled = chosen.isNotEmpty(),
            )
        } else if (r != null) {
            TitanButton(stringResource(R.string.mailx_nas_here), { onFolder(r, path.joinToString("/")) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = r.writable)
            if (!r.writable) Text(stringResource(R.string.mailx_nas_readonly), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
