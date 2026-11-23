package io.github.ra5on.titanos.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tabs
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk

/** Neue Kennung für Ordner und Widgets (wie im Web: kurz, eindeutig genug je Konto). */
internal fun newId(prefix: String): String = prefix + System.currentTimeMillis().toString(36) + (0..35).random().toString(36)

/**
 * „Bewegen …“ im Bearbeiten (Entwurf 03, jeder Weg ohne Ziehen): eine Stelle
 * vor oder zurück, auf eine andere oder neue Seite, in einen Ordner,
 * Größe eines Widgets, Ordner öffnen oder auflösen, vom Startbildschirm nehmen.
 */
@Composable
internal fun BoxScope.ItemSheet(
    key: String,
    draft: PhonePrefs,
    pages: List<List<String>>,
    folders: List<PhoneFolder>,
    byKey: Map<String, Program>,
    onChange: (PhonePrefs) -> Unit,
    onDismiss: () -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    val c = Titan.colors
    var expand by remember(key) { mutableStateOf<String?>(null) }
    val isFolder = key.startsWith("folder:")
    val widget = draft.widgets.firstOrNull { "widget:" + it.id == key }
    val title = when {
        isFolder -> folders.firstOrNull { "folder:" + it.id == key }?.name.orEmpty()
        widget != null -> stringResource(widgetTitleRes(widget.kind))
        else -> byKey[key]?.let { programLabel(it.id) }.orEmpty()
    }
    val folderName = stringResource(R.string.home_folder_default)
    fun apply(next: PhonePrefs) {
        onChange(next)
        onDismiss()
    }
    Sheet(onDismiss = onDismiss) {
        Text(title, style = TitanType.topTitle, color = c.text, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp).semantics { heading() })
        if (widget != null) {
            Text(stringResource(R.string.home_size), style = TitanType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Segments(widget.size, listOf("s" to stringResource(R.string.home_half), "l" to stringResource(R.string.home_full)), { onChange(resizeWidget(draft, widget.id, it)) }, Modifier.padding(bottom = 12.dp))
        }
        Card(background = c.bg) {
            ListRow(stringResource(R.string.home_move_earlier), icon = { RowIcon(TitanIcons.back) }, onClick = { apply(moveBy(draft, key, -1)) })
            ListRow(stringResource(R.string.home_move_later), icon = { RowIcon(TitanIcons.chev) }, divider = true, onClick = { apply(moveBy(draft, key, +1)) })
            ListRow(
                stringResource(R.string.home_move_page),
                icon = { RowIcon(TitanIcons.move) },
                chevron = expand != "page",
                divider = true,
                onClick = { expand = if (expand == "page") null else "page" },
            )
            if (expand == "page") {
                val here = placeOf(draft, key)?.first
                pages.indices.forEach { i ->
                    ListRow(
                        stringResource(R.string.home_page_n, i + 1),
                        modifier = Modifier.padding(start = 24.dp),
                        icon = { RowIcon(if (i == here) TitanIcons.check else TitanIcons.grid, tint = if (i == here) c.accent else c.muted) },
                        divider = true,
                        onClick = if (i == here) null else ({ apply(moveTo(draft, key, i, Int.MAX_VALUE)) }),
                    )
                }
                ListRow(
                    stringResource(R.string.home_page_new),
                    modifier = Modifier.padding(start = 24.dp),
                    icon = { RowIcon(TitanIcons.plus) },
                    divider = true,
                    onClick = { apply(moveTo(addPage(draft), key, draft.pages.size, 0)) },
                )
            }
            if (!isFolder && widget == null) {
                ListRow(
                    stringResource(R.string.home_into_folder),
                    icon = { RowIcon(TitanIcons.folder) },
                    chevron = expand != "folder",
                    divider = true,
                    onClick = { expand = if (expand == "folder") null else "folder" },
                )
                if (expand == "folder") {
                    for (f in folders) {
                        ListRow(
                            f.name,
                            modifier = Modifier.padding(start = 24.dp),
                            icon = { FolderIcon(f, byKey, 30) },
                            divider = true,
                            onClick = { apply(combine(draft, key, "folder:" + f.id, folderName, newId("f"))) },
                        )
                    }
                    // Mit einem anderen Programm einen neuen Ordner (wie Aufeinanderziehen).
                    for (other in pages.flatten().filter { it != key && byKey[it] != null }) {
                        val p = byKey[other] ?: continue
                        ListRow(
                            stringResource(R.string.home_folder_with, programLabel(p.id)),
                            modifier = Modifier.padding(start = 24.dp),
                            icon = { AppIcon(p.glyph, p.gradient, 30.dp) },
                            divider = true,
                            onClick = { apply(combine(draft, key, other, folderName, newId("f"))) },
                        )
                    }
                }
            }
            if (isFolder) {
                ListRow(stringResource(R.string.home_folder_open), icon = { RowIcon(TitanIcons.folder) }, chevron = true, divider = true, onClick = { onDismiss(); onOpenFolder(key.removePrefix("folder:")) })
                ListRow(stringResource(R.string.home_dissolve), icon = { RowIcon(TitanIcons.x, tint = c.dangerInk) }, titleColor = c.dangerInk, divider = true, onClick = { apply(dissolveFolder(draft, key.removePrefix("folder:"))) })
            } else {
                ListRow(
                    stringResource(if (widget != null) R.string.home_remove_widget else R.string.home_remove),
                    icon = { RowIcon(TitanIcons.x, tint = c.dangerInk) },
                    titleColor = c.dangerInk,
                    divider = true,
                    onClick = { apply(if (widget != null) removeWidget(draft, widget.id) else removeProgram(draft, key)) },
                )
            }
        }
    }
}

/**
 * Ein Ordner (Entwurf 03): seine Programme zum Öffnen; im Bearbeiten
 * umbenennen, einzelne herausnehmen oder den Ordner auflösen.
 */
@Composable
internal fun BoxScope.FolderSheet(
    folder: PhoneFolder,
    byKey: Map<String, Program>,
    editing: Boolean,
    onOpen: (Program) -> Unit,
    onChange: ((PhonePrefs) -> PhonePrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Titan.colors
    var name by remember(folder.id) { mutableStateOf(folder.name) }
    Sheet(onDismiss = onDismiss) {
        if (editing) {
            TextInput(name, { v -> name = v.take(60); onChange { renameFolder(it, folder.id, name) } }, stringResource(R.string.home_folder_name), Modifier.fillMaxWidth().padding(bottom = 12.dp))
        } else {
            Text(folder.name, style = TitanType.topTitle, color = c.text, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp).semantics { heading() })
        }
        val apps = folder.apps.mapNotNull { k -> byKey[k]?.let { k to it } }
        for (row in apps.chunked(4)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((key, p) in row) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).then(if (!editing) Modifier.clickable(role = Role.Button) { onOpen(p) } else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppIcon(p.glyph, p.gradient, 52.dp)
                        Text(programLabel(p.id), style = TitanType.appLabel, color = c.text, textAlign = TextAlign.Center)
                        if (editing) {
                            SmallButton(stringResource(R.string.home_folder_take_out), { onChange { outOfFolder(it, key, folder.id) } })
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (editing) {
            TitanButton(stringResource(R.string.home_dissolve), { onChange { dissolveFolder(it, folder.id) }; onDismiss() }, Modifier.fillMaxWidth(), icon = TitanIcons.x)
        }
    }
}

/**
 * „Hinzufügen“ (Entwurf 04): Widgets und die Programme, die noch nicht auf
 * dem Startbildschirm stehen. Beides kommt auf die gerade gezeigte Seite.
 */
@Composable
internal fun BoxScope.AddSheet(
    draft: PhonePrefs,
    programs: List<Program>,
    keys: List<String>,
    widgetsAllowed: List<String>,
    page: Int,
    onChange: (PhonePrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Titan.colors
    var tab by remember { mutableStateOf("widgets") }
    val on = editPages(draft, keys).flatten().toSet() + draft.folders.flatMap { it.apps }
    val off = programs.filter { programKey(it.id) !in on }
    Sheet(onDismiss = onDismiss) {
        Tabs(tab, listOf("widgets" to stringResource(R.string.home_add_widgets), "programs" to stringResource(R.string.home_add_programs)), { tab = it }, Modifier.padding(bottom = 6.dp))
        if (tab == "widgets") {
            SectionHeader(stringResource(R.string.home_add_widgets), top = 8.dp)
            // Zwei Karten nebeneinander nur, wenn jedes Wort in eine passt (ganze Wörter, auch bei großer Schrift).
            val words = widgetsAllowed.flatMap { listOf(stringResource(widgetTitleRes(it)), stringResource(widgetSubRes(it))) }
            val widest = io.github.ra5on.titanos.ui.rememberWidestWord(words, TitanType.rowTitle)
            val density = androidx.compose.ui.platform.LocalDensity.current
            val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
            val half = with(density) { ((screen - 32.dp - 10.dp) / 2 - 28.dp - 52.dp).toPx() }
            val perRow = if (widest <= half) 2 else 1
            for (row in widgetsAllowed.chunked(perRow)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (kind in row) {
                        val (glyph, gradient) = widgetIcon(kind)
                        val title = stringResource(widgetTitleRes(kind))
                        val addLabel = stringResource(R.string.home_add_named, title)
                        Box(Modifier.weight(1f)) {
                            Card(background = c.bg, modifier = Modifier.clickable(role = Role.Button, onClickLabel = addLabel) {
                                onChange(addWidget(draft, kind, page.coerceAtMost(draft.pages.size), newId("w")))
                                onDismiss()
                            }) {
                                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AppIcon(glyph, gradient, 40.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(title, style = TitanType.rowTitle, color = c.text)
                                        Text(stringResource(widgetSubRes(kind)), style = TitanType.rowSub, color = c.muted)
                                    }
                                }
                            }
                        }
                    }
                    if (row.size < perRow) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            SectionHeader(stringResource(R.string.home_add_programs_off), top = 8.dp)
            if (off.isEmpty()) {
                Text(stringResource(R.string.home_add_none), style = TitanType.body, color = c.muted, modifier = Modifier.padding(4.dp))
            } else {
                Card(background = c.bg) {
                    off.forEachIndexed { i, p ->
                        val label = programLabel(p.id)
                        ListRow(
                            label,
                            icon = { AppIcon(p.glyph, p.gradient, 40.dp) },
                            right = {
                                val addLabel = stringResource(R.string.home_add_named, label)
                                SmallButton(stringResource(R.string.home_add_do), {
                                    onChange(addProgram(draft, programKey(p.id), page.coerceAtMost(draft.pages.size)))
                                }, Modifier.semantics { contentDescription = addLabel }, icon = TitanIcons.plus)
                            },
                            divider = i > 0,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(1.dp).padding(bottom = 8.dp))
    }
}
