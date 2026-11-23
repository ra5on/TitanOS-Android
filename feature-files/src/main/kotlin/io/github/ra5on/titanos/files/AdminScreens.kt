package io.github.ra5on.titanos.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Dateisysteme wie im Web (drive-format.ts). */
val FILESYSTEMS = listOf("exfat", "vfat", "ntfs", "ext4", "xfs", "btrfs")

@Composable
private fun AdminTop(title: String, onBack: () -> Unit) {
    Box(Modifier.statusBarsPadding()) { TopBar(title, onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) }
}

@Composable
private fun jobText(j: FormatJob): String = when {
    !j.error.isNullOrEmpty() -> stringResource(R.string.usb_job_failed, j.error)
    j.phase == "done" -> stringResource(R.string.usb_job_done)
    else -> stringResource(R.string.usb_job_running, j.name.ifEmpty { j.device }, j.phase)
}

/** Was formatiert oder geändert werden soll; der Dialog fragt vorher. */
private data class FormatAsk(val disk: Disk?, val slot: Slot?, val gap: Gap?, val table: Boolean = false)

/**
 * USB-Geräte (Besitzer und Administratoren, wie im Web): einbinden,
 * auswerfen, öffnen; Laufwerke formatieren und ihre Partitionen bearbeiten.
 */
@Composable
fun UsbScreen(model: FilesModel, onOpen: (FileRoot) -> Unit, onPartitions: (Disk) -> Unit, onBack: () -> Unit) {
    val admin = model.admin ?: return
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<UsbDevice>?>(null) }
    var disks by remember { mutableStateOf<DisksAnswer?>(null) }
    var error by remember { mutableStateOf<FilesError?>(null) }
    var ask by remember { mutableStateOf<Disk?>(null) }
    var busy by remember { mutableStateOf("") }
    suspend fun load() {
        try {
            devices = admin.usb()
            disks = admin.disks()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.errorOf(e)
        }
    }
    LaunchedEffect(Unit) { load() }
    // Ein laufender Auftrag (Formatieren): alle zwei Sekunden nachsehen.
    LaunchedEffect(disks?.job?.phase, disks?.job?.finished) {
        while (disks?.job?.running == true) {
            delay(2000)
            runCatching { disks = admin.disks() }
        }
        if (disks?.job?.phase == "done") runCatching { devices = admin.usb() }
    }
    val c = Titan.colors
    Box(Modifier.fillMaxSize()) {
        Page(topBar = { AdminTop(stringResource(R.string.usb_title), onBack) }) {
            error?.let { Problem(errorText(it), Modifier.padding(top = 12.dp)) }
            disks?.job?.let { Note(jobText(it), Modifier.padding(top = 12.dp)) }
            SectionHeader(stringResource(R.string.usb_devices), top = 14.dp)
            val list = devices
            when {
                list == null && error == null -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted)
                list.isNullOrEmpty() -> Text(stringResource(R.string.usb_none), style = TitanType.body, color = c.muted)
                else -> Card {
                    list.forEachIndexed { i, d ->
                        ListRow(
                            d.label.ifEmpty { d.name.ifEmpty { d.model } },
                            sub = listOfNotNull(
                                d.model.takeIf { it.isNotEmpty() }, d.fsType.takeIf { it.isNotEmpty() }, Format.bytes(d.size),
                                if (d.mounted) stringResource(R.string.usb_mounted, Format.bytes(d.freeBytes)) else stringResource(R.string.usb_not_mounted),
                                if (d.readOnly == true) stringResource(R.string.files_read_only) else null,
                                if (!d.supported) stringResource(R.string.usb_unsupported) else null,
                            ).joinToString(" · "),
                            icon = { RowIcon(TitanIcons.hdd) },
                            divider = i > 0,
                            bottom = {
                                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (d.mounted && d.root.isNotEmpty()) {
                                        Chip(stringResource(R.string.entry_open), onClick = { scope.launch { model.loadRoots(); model.roots.roots?.firstOrNull { it.id == d.root }?.let(onOpen) } })
                                        Chip(stringResource(R.string.usb_eject), onClick = {
                                            busy = d.id
                                            scope.launch { runCatching { devices = admin.usbAction(d.id, "eject") }.onFailure { error = model.errorOf(it) }; busy = "" }
                                        })
                                    } else if (d.supported) {
                                        Chip(stringResource(R.string.usb_mount), onClick = {
                                            busy = d.id
                                            scope.launch { runCatching { devices = admin.usbAction(d.id, "mount") }.onFailure { error = model.errorOf(it) }; busy = "" }
                                        })
                                    }
                                    if (busy == d.id) Text(stringResource(UiR.string.ui_loading), style = TitanType.small, color = c.muted)
                                }
                            },
                        )
                    }
                }
            }
            SectionHeader(stringResource(R.string.usb_drives))
            val dl = disks?.disks
            if (dl.isNullOrEmpty()) {
                Text(stringResource(R.string.usb_none), style = TitanType.body, color = c.muted)
            } else {
                Card {
                    dl.forEachIndexed { i, d ->
                        ListRow(
                            d.model.ifEmpty { d.device },
                            sub = listOfNotNull(d.device, Format.bytes(d.size), d.transport.takeIf { it.isNotEmpty() }, d.table.takeIf { it.isNotEmpty() }?.uppercase(),
                                pluralStringResource(R.plurals.usb_partitions, d.partitions.size, d.partitions.size),
                                d.reason?.takeIf { !d.formattable }).joinToString(" · "),
                            icon = { RowIcon(TitanIcons.hdd) },
                            divider = i > 0,
                            bottom = {
                                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (d.formattable) Chip(stringResource(R.string.usb_format), onClick = { ask = d })
                                    Chip(stringResource(R.string.usb_partitions_open), onClick = { onPartitions(d) })
                                }
                            },
                        )
                    }
                }
            }
            Note(stringResource(R.string.usb_note), Modifier.padding(top = 14.dp))
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
        ask?.let { d ->
            FormatDialog(
                title = stringResource(R.string.usb_format_title, d.model.ifEmpty { d.device }),
                text = stringResource(R.string.usb_format_text, Format.bytes(d.size)),
                withScheme = true,
                onDo = { fs, label, scheme, mount ->
                    ask = null
                    scope.launch { runCatching { disks = admin.format(d.id, FormatRequest(fs, label, scheme, mount)) }.onFailure { error = model.errorOf(it) } }
                },
                onDismiss = { ask = null },
            )
        }
    }
}

/** Dateisystem, Bezeichnung und (für ganze Laufwerke) Partitionsschema; gefährlicher Knopf links. */
@Composable
private fun BoxScope.FormatDialog(
    title: String,
    text: String,
    withScheme: Boolean,
    onDo: (String, String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    labelOnly: Boolean = false,
    initialLabel: String = "",
) {
    var fs by remember { mutableStateOf("exfat") }
    var label by remember { mutableStateOf(initialLabel) }
    var scheme by remember { mutableStateOf("auto") }
    var mount by remember { mutableStateOf(true) }
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    ConfirmDialog(
        title = title,
        text = text,
        actions = listOf(
            DialogAction(stringResource(if (labelOnly) R.string.edit_save else R.string.usb_format_do), danger = !labelOnly) { onDo(fs, label.trim(), scheme, mount) },
            DialogAction(stringResource(UiR.string.ui_cancel), onClick = onDismiss),
        ),
        onDismiss = onDismiss,
    ) {
        if (!labelOnly) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                FILESYSTEMS.forEach { f -> Chip(f, on = fs == f, onClick = { fs = f }) }
            }
        }
        TextInput(label, { label = it }, stringResource(R.string.usb_label), placeholder = stringResource(R.string.usb_label))
        if (withScheme && !labelOnly) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("auto", "gpt", "mbr").forEach { s -> Chip(s.uppercase(), on = scheme == s, onClick = { scheme = s }) }
            }
            ListRow(stringResource(R.string.usb_mount_after), right = { Toggle(mount, { mount = it }, on, off) }, onClick = { mount = !mount })
        }
        Box(Modifier.padding(bottom = 10.dp))
    }
}

/** Partitionen eines USB-Laufwerks (PartitionEditor im Web). */
@Composable
fun PartitionsScreen(model: FilesModel, disk: Disk, onBack: () -> Unit) {
    val admin = model.admin ?: return
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf<Layout?>(null) }
    var error by remember { mutableStateOf<FilesError?>(null) }
    var ask by remember { mutableStateOf<FormatAsk?>(null) }
    var rename by remember { mutableStateOf<Slot?>(null) }
    var delete by remember { mutableStateOf<Slot?>(null) }
    var job by remember { mutableStateOf<FormatJob?>(null) }
    suspend fun load() {
        try {
            layout = admin.layout(disk.id)
            job = layout?.job
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.errorOf(e)
        }
    }
    fun run(block: suspend () -> DisksAnswer) {
        scope.launch {
            try {
                job = block().job
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = model.errorOf(e)
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(job?.phase, job?.finished) {
        while (job?.running == true) {
            delay(2000)
            load()
        }
    }
    val c = Titan.colors
    Box(Modifier.fillMaxSize()) {
        Page(topBar = { AdminTop(stringResource(R.string.part_title, disk.model.ifEmpty { disk.device }), onBack) }) {
            error?.let { Problem(errorText(it), Modifier.padding(top = 12.dp)) }
            job?.let { Note(jobText(it), Modifier.padding(top = 12.dp)) }
            val l = layout
            if (l == null && error == null) Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(top = 12.dp))
            if (l != null) {
                Text(
                    listOfNotNull(Format.bytes(l.disk.size), l.table.uppercase().ifEmpty { stringResource(R.string.part_no_table) }).joinToString(" · "),
                    style = TitanType.body, color = c.text, modifier = Modifier.padding(top = 12.dp),
                )
                l.reason?.let { Problem(it, Modifier.padding(top = 8.dp)) }
                SectionHeader(stringResource(R.string.part_partitions))
                Card {
                    if (l.partitions.isEmpty() && l.gaps.isEmpty()) ListRow(stringResource(R.string.part_none))
                    var first = true
                    val items = (l.partitions.map { it.start to it } + l.gaps.map { it.start to it }).sortedBy { it.first }
                    for ((_, item) in items) {
                        when (item) {
                            is Slot -> ListRow(
                                listOfNotNull("${item.number}", item.label.ifEmpty { null }, item.fsType.ifEmpty { null }).joinToString(" · "),
                                sub = listOfNotNull(item.device, Format.bytes(item.size), if (item.mounted) stringResource(R.string.part_mounted) else null, item.reason).joinToString(" · "),
                                icon = { RowIcon(TitanIcons.hdd) },
                                divider = !first,
                                bottom = if (l.editable && !item.mounted) {
                                    {
                                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Chip(stringResource(R.string.usb_format), onClick = { ask = FormatAsk(null, item, null) })
                                            Chip(stringResource(R.string.part_label), onClick = { rename = item })
                                            if (item.growBy > 0) Chip(stringResource(R.string.part_grow, Format.bytes(item.growBy)), onClick = { run { admin.growPartition(disk.id, item.number, item.size + item.growBy) } })
                                            Chip(stringResource(R.string.files_delete), onClick = { delete = item })
                                        }
                                    }
                                } else null,
                            )
                            is Gap -> ListRow(
                                stringResource(R.string.part_free),
                                sub = Format.bytes(item.size),
                                icon = { RowIcon(TitanIcons.plus) },
                                divider = !first,
                                bottom = if (l.editable && l.table.isNotEmpty()) {
                                    { FlowRow(Modifier.padding(top = 8.dp)) { Chip(stringResource(R.string.part_create), onClick = { ask = FormatAsk(null, null, item) }) } }
                                } else null,
                            )
                        }
                        first = false
                    }
                }
                if (l.editable) {
                    TitanButton(stringResource(R.string.part_new_table), { ask = FormatAsk(null, null, null, table = true) }, Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.Danger)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
        ask?.let { a ->
            when {
                a.table -> TableDialog(onDo = { scheme -> ask = null; run { admin.table(disk.id, scheme) } }, onDismiss = { ask = null })
                a.slot != null -> FormatDialog(
                    stringResource(R.string.part_format_title, a.slot.number), stringResource(R.string.part_format_text), withScheme = false,
                    onDo = { fs, label, _, _ -> ask = null; run { admin.formatPartition(disk.id, a.slot.number, PartitionRequest(filesystem = fs, label = label)) } },
                    onDismiss = { ask = null },
                )
                a.gap != null -> FormatDialog(
                    stringResource(R.string.part_create), stringResource(R.string.part_create_text, Format.bytes(a.gap.size)), withScheme = false,
                    onDo = { fs, label, _, _ -> ask = null; run { admin.createPartition(disk.id, PartitionRequest(a.gap.start, a.gap.size, fs, label)) } },
                    onDismiss = { ask = null },
                )
            }
        }
        rename?.let { s ->
            FormatDialog(
                stringResource(R.string.part_label), "", withScheme = false, labelOnly = true, initialLabel = s.label,
                onDo = { _, label, _, _ -> rename = null; run { admin.labelPartition(disk.id, s.number, label) } },
                onDismiss = { rename = null },
            )
        }
        delete?.let { s ->
            ConfirmDialog(
                title = stringResource(R.string.part_delete_title, s.number),
                text = stringResource(R.string.part_delete_text),
                actions = listOf(
                    DialogAction(stringResource(R.string.files_delete), danger = true) { delete = null; run { admin.deletePartition(disk.id, s.number) } },
                    DialogAction(stringResource(UiR.string.ui_cancel)) { delete = null },
                ),
                onDismiss = { delete = null },
            )
        }
    }
}

@Composable
private fun BoxScope.TableDialog(onDo: (String) -> Unit, onDismiss: () -> Unit) {
    var scheme by remember { mutableStateOf("gpt") }
    ConfirmDialog(
        title = stringResource(R.string.part_new_table),
        text = stringResource(R.string.part_new_table_text),
        actions = listOf(
            DialogAction(stringResource(R.string.part_new_table_do), danger = true) { onDo(scheme) },
            DialogAction(stringResource(UiR.string.ui_cancel), onClick = onDismiss),
        ),
        onDismiss = onDismiss,
    ) {
        listOf("gpt", "mbr").forEach { s -> ListRow(s.uppercase(), icon = { RadioMark(scheme == s) }, onClick = { scheme = s }) }
        Box(Modifier.padding(bottom = 10.dp))
    }
}

/** Mit einer Docker-App öffnen (Openers im Web); bei Bedarf die Freigabe vorher einbinden. */
@Composable
fun BoxScope.DockerOpenSheet(model: FilesModel, location: Location, name: String, onDismiss: () -> Unit) {
    val admin = model.admin ?: return
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<FileOpener>?>(null) }
    var error by remember { mutableStateOf<FilesError?>(null) }
    var mountAsk by remember { mutableStateOf<FileOpener?>(null) }
    var readOnly by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    val path = location.full(name)
    LaunchedEffect(path) {
        list = try { admin.openers(location.root.id, path) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = model.errorOf(e); emptyList() }
    }
    fun open(o: FileOpener) {
        scope.launch {
            try {
                admin.openInApp(o.app, location.root.id, path)
                done = o.name.ifEmpty { o.app }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = model.errorOf(e)
            }
        }
    }
    val c = Titan.colors
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    Sheet(onDismiss) {
        Text(stringResource(R.string.docker_title), style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(bottom = 6.dp))
        Text(name, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(bottom = 12.dp))
        error?.let { Problem(errorText(it), Modifier.padding(bottom = 10.dp)) }
        done?.let { Note(stringResource(R.string.docker_opened, it), Modifier.padding(bottom = 10.dp)) }
        val l = list
        when {
            l == null -> Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted)
            l.isEmpty() -> Note(stringResource(R.string.docker_none))
            else -> Card(background = c.bg) {
                l.forEachIndexed { i, o ->
                    ListRow(
                        o.name.ifEmpty { o.app },
                        sub = listOfNotNull(
                            if (o.running) stringResource(R.string.docker_running) else stringResource(R.string.docker_stopped),
                            if (!o.mounted) stringResource(R.string.docker_not_mounted, o.share.orEmpty()) else null,
                        ).joinToString(" · "),
                        icon = { RowIcon(TitanIcons.docker) },
                        divider = i > 0,
                        onClick = { if (o.mounted) open(o) else if (o.canMount == true) mountAsk = o else error = FilesError.Other("not_mounted") },
                    )
                }
            }
        }
        mountAsk?.let { o ->
            Card(background = c.bg, modifier = Modifier.padding(top = 12.dp)) {
                ListRow(stringResource(R.string.docker_mount_title, o.share.orEmpty(), o.name.ifEmpty { o.app }), sub = stringResource(R.string.docker_mount_text, "/shares/" + o.share.orEmpty()))
                ListRow(stringResource(R.string.docker_read_only), right = { Toggle(readOnly, { readOnly = it }, on, off) }, divider = true, onClick = { readOnly = !readOnly })
            }
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TitanButton(stringResource(R.string.docker_mount_do), {
                    scope.launch {
                        try {
                            admin.mountShare(o.app, o.share.orEmpty(), readOnly)
                            mountAsk = null
                            open(o)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = model.errorOf(e)
                        }
                    }
                }, kind = ButtonKind.Primary)
                TitanButton(stringResource(UiR.string.ui_cancel), { mountAsk = null })
            }
        }
    }
}

/** Ordner vergleichen (CompareDialog im Web): was links, rechts oder verschieden ist. */
@Composable
fun CompareScreen(model: FilesModel, location: Location, onOpen: (Location) -> Unit, onBack: () -> Unit) {
    val admin = model.admin
    val api = model.files
    val scope = rememberCoroutineScope()
    val roots = model.roots.roots.orEmpty()
    var target by remember { mutableStateOf(location.root) }
    var dir by remember { mutableStateOf("") }
    var checksum by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CompareResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<FilesError?>(null) }
    val c = Titan.colors
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    Page(topBar = { AdminTop(stringResource(R.string.compare_title), onBack) }) {
        Text(stringResource(R.string.compare_left, location.place), style = TitanType.body, color = c.text, modifier = Modifier.padding(top = 12.dp))
        SectionHeader(stringResource(R.string.compare_with))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            roots.forEach { r -> Chip(r.name, on = r.id == target.id, onClick = { target = r }) }
        }
        TextInput(dir, { dir = it }, stringResource(R.string.compare_folder), Modifier.padding(top = 8.dp), placeholder = stringResource(R.string.compare_folder_hint))
        Card(Modifier.padding(top = 10.dp)) {
            ListRow(stringResource(R.string.compare_checksum), sub = stringResource(R.string.compare_checksum_text), right = { Toggle(checksum, { checksum = it }, on, off) }, onClick = { checksum = !checksum })
        }
        TitanButton(
            stringResource(if (running) R.string.compare_running else R.string.compare_do), {
                running = true
                error = null
                scope.launch {
                    try {
                        result = (admin ?: return@launch).compare(CompareRequest(location.root.id, location.path, target.id, dir.trim().trim('/'), checksum, model.prefs.showHidden))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = model.errorOf(e)
                    } finally {
                        running = false
                    }
                }
            },
            Modifier.fillMaxWidth().padding(top = 12.dp), kind = ButtonKind.Primary, enabled = !running && api != null,
        )
        error?.let { Problem(errorText(it), Modifier.padding(top = 12.dp)) }
        result?.let { r ->
            Text(pluralStringResource(R.plurals.compare_summary, r.entries.size, r.entries.size, r.same), style = TitanType.body, color = c.text, modifier = Modifier.padding(top = 12.dp))
            for ((state, title) in listOf("left" to R.string.compare_only_left, "right" to R.string.compare_only_right, "different" to R.string.compare_different)) {
                val items = r.entries.filter { it.state == state }
                if (items.isEmpty()) continue
                SectionHeader(stringResource(title) + " (${items.size})")
                Card {
                    items.take(500).forEachIndexed { i, e ->
                        val l = e.left
                        val rr = e.right
                        ListRow(
                            e.path,
                            sub = listOfNotNull(l?.let { stringResource(R.string.compare_side_left, Format.bytes(it.size), entryDate(it.modified).orEmpty()) }, rr?.let { stringResource(R.string.compare_side_right, Format.bytes(it.size), entryDate(it.modified).orEmpty()) }).joinToString("\n"),
                            icon = { RowIcon(if ((l ?: rr)?.dir == true) TitanIcons.folder else iconFor(e.path)) },
                            divider = i > 0,
                            onClick = {
                                val parent = e.path.substringBeforeLast('/', "")
                                onOpen(if (state == "right") Location(target, listOf(dir.trim().trim('/'), parent).filter { it.isNotEmpty() }.joinToString("/")) else Location(location.root, listOf(location.path, parent).filter { it.isNotEmpty() }.joinToString("/")))
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}
