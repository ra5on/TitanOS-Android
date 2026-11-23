package io.github.ra5on.titanos.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminDialog
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.RadioRow
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.formatDiskBytes
import io.github.ra5on.titanos.admin.formatDuration
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

private fun fixed(v: Double) = String.format(Locale.ROOT, if (v < 10) "%.1f" else "%.0f", v)

/**
 * Platten vorbereiten (tools/disks, DiskTests.svelte): Platten prüfen, bevor
 * sie in ein Volume kommen. Ob eine Platte bestanden hat, sagt nur das NAS;
 * die App fragt den Stand ab (2 s, solange etwas läuft, sonst 10 s).
 */
@Composable
internal fun DisksPage(ctx: ToolsCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var disks by remember { mutableStateOf<List<Disk>>(emptyList()) }
    var tests by remember { mutableStateOf<List<DiskTest>>(emptyList()) }
    var notices by remember { mutableStateOf<List<DiskTestNotice>>(emptyList()) }
    var autoResume by remember { mutableStateOf(true) }
    var resuming by remember { mutableStateOf<DiskTest?>(null) }
    var stopping by remember { mutableStateOf<DiskTest?>(null) }
    var chosen by remember { mutableStateOf<Disk?>(null) }
    var mode by remember { mutableStateOf("read") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Pair<String, String>?>(null) }
    var loaded by remember { mutableStateOf<Instant?>(null) }
    var stale by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var first by remember { mutableStateOf(true) }

    fun show(list: TestList) {
        tests = list.tests
        notices = list.notices.orEmpty()
    }
    fun problem(e: Throwable) {
        val code = (e as? ApiException)?.code ?: "internal"
        error = context.errorText(e) to if ((code == "storage_failed" || code == "disk_unavailable") && e is ApiException) e.message?.substringAfter(": ").orEmpty() else ""
    }
    val active = tests.any { it.result.isEmpty() }
    Polling(tick to active, if (active) 2_000 else 10_000) {
        try {
            val storage = ctx.api.storage()
            val running = ctx.api.diskTests()
            disks = storage.disks
            show(running)
            loaded = Instant.now()
            stale = false
            if (error != null && chosen == null && stopping == null && resuming == null) error = null
        } catch (e: Exception) {
            // Was einmal da ist, bleibt stehen, als womöglich veraltet markiert.
            if (loaded != null) stale = true else problem(e)
        }
        first = false
    }
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                problem(e)
            } finally {
                busy = false
            }
        }
    }
    PageKeys(ctx) { e -> if (e.refresh()) { tick++; true } else false }

    val candidates = testCandidates(disks, tests)

    @Composable
    fun ErrorBox() {
        error?.let { (text, detail) ->
            Notice(text, tone = Tone.Error, actions = { if (detail.isNotEmpty()) MonoBlock(detail) })
        }
    }

    PageBody {
        Muted(t("disktest.intro"))
        if (chosen == null && stopping == null && resuming == null && error != null) {
            ErrorBox()
            ActionButton(t("ui.retry"), { tick++ })
        }
        if (first && error == null) Loading()
        notices.forEachIndexed { index, n ->
            val parts = listOfNotNull(
                n.model?.ifEmpty { null },
                n.serial?.ifEmpty { null },
                n.mode?.ifEmpty { null }?.let { t("disktest.mode.$it") },
                t("disktest.startedAt", "time" to formatDateTime(n.started)),
            )
            Notice(
                tKnown("disktest.notice.${n.kind}", n.kind),
                tone = Tone.Warn,
                actions = {
                    Muted(parts.joinToString(" · "))
                    if (index == notices.lastIndex) ActionButton(t("disktest.dismiss"), { run { show(ctx.api.dismissDiskNotices()) } }, enabled = !busy)
                },
            )
        }
        if (stale && loaded != null) {
            Notice(t("disktest.stale", "time" to io.github.ra5on.titanos.admin.formatTime(loaded.toString())), tone = Tone.Warn, actions = { ActionButton(t("ui.retry"), { tick++ }) })
        }
        if (tests.isNotEmpty()) {
            SettingGroup(t("disktest.running")) {
                tests.forEachIndexed { i, test ->
                    if (i > 0) Divider()
                    TestRow(test, busy, onStop = { error = null; stopping = test }, onDismiss = { run { show(ctx.api.stopDiskTest(test.disk)) } }, onResume = {
                        error = null
                        if (resumeKind(test) == "confirm") resuming = test else run { show(ctx.api.resumeDiskTest(test)) }
                    })
                }
            }
        }
        SettingGroup(t("disktest.candidates")) {
            if (candidates.isEmpty()) Text(t("disktest.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
            candidates.forEachIndexed { i, d ->
                SettingRow(
                    d.name,
                    icon = "disk",
                    divider = i > 0,
                    desc = listOf(d.model, d.serial, formatDiskBytes(d.sizeBytes)).filter { it.isNotEmpty() }.joinToString(" · ") + " · " +
                        (if (!d.contents.isNullOrEmpty()) t("disktest.contents", "contents" to d.contents.joinToString(", ")) else t("disktest.empty")),
                    below = {
                        ActionButton(t("disktest.start"), { chosen = d; mode = "read"; autoResume = true; error = null }, modifier = Modifier.padding(top = 8.dp))
                    },
                )
            }
        }
    }

    chosen?.let { disk ->
        // Gestartet nur über den Knopf: Enter im Dialog löscht nie eine Platte.
        AdminDialog(
            title = t("disktest.dialog", "disk" to disk.name),
            text = listOf(disk.model, disk.serial, formatDiskBytes(disk.sizeBytes)).filter { it.isNotEmpty() }.joinToString(" · "),
            actions = listOf(
                DialogAction(t("disktest.begin"), danger = mode == "write") { if (!busy) run { show(ctx.api.startDiskTest(disk.name, mode, autoResume)); chosen = null } },
                DialogAction(t("common.cancel")) { if (!busy) chosen = null },
            ),
            onDismiss = { if (!busy) chosen = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("disktest.mode"), style = TitanType.rowTitle, color = Titan.colors.text)
                Card {
                    Column(Modifier.selectableGroup()) {
                        RadioRow(t("disktest.mode.read"), mode == "read", { mode = "read" }, desc = t("disktest.mode.readHint"))
                        RadioRow(t("disktest.mode.write"), mode == "write", { mode = "write" }, desc = t("disktest.mode.writeHint"), divider = true)
                    }
                }
                Muted(t("disktest.estimate", "size" to formatDiskBytes(disk.sizeBytes), "hours" to testHours(disk, mode)))
                if (mode == "write") {
                    val text = t("disktest.eraseWarning", "disk" to disk.name, "model" to listOf(disk.model, disk.serial).filter { it.isNotEmpty() }.joinToString(" · ")) +
                        if (!disk.contents.isNullOrEmpty()) "\n" + t("disktest.eraseContents", "contents" to disk.contents.joinToString(", ")) else ""
                    Notice(text, tone = Tone.Warn)
                    Muted(t("disktest.writeResumeNote"))
                } else {
                    Card { CheckRow(t("disktest.autoResume"), autoResume, { autoResume = it }, desc = t("disktest.autoResumeHint")) }
                }
                Muted(t("disktest.restartNote"))
                ErrorBox()
            }
        }
    }
    resuming?.let { test ->
        AdminDialog(
            title = t("disktest.resumeTitle", "disk" to test.disk),
            text = null,
            actions = listOf(
                DialogAction(t("disktest.resumeConfirm"), danger = true) { if (!busy) run { show(ctx.api.resumeDiskTest(test)); resuming = null } },
                DialogAction(t("common.cancel")) { if (!busy) resuming = null },
            ),
            onDismiss = { if (!busy) resuming = null },
        ) {
            Notice(
                t("disktest.resumeWrite", "disk" to test.disk, "model" to listOf(test.model, test.serial).filter { it.isNotEmpty() }.joinToString(" · "), "percent" to Math.round(test.percent)),
                tone = Tone.Warn,
            )
            ErrorBox()
        }
    }
    stopping?.let { test ->
        AdminDialog(
            title = t("disktest.cancelTitle", "disk" to test.disk),
            text = t(if (test.mode == "write") "disktest.cancelWrite" else "disktest.cancelRead", "disk" to test.disk),
            actions = listOf(
                DialogAction(t("disktest.cancelConfirm"), danger = true) { if (!busy) run { show(ctx.api.stopDiskTest(test.disk)); stopping = null } },
                DialogAction(t("disktest.keepRunning")) { if (!busy) stopping = null },
            ),
            onDismiss = { if (!busy) stopping = null },
        ) { ErrorBox() }
    }
}

@Composable
private fun TestRow(test: DiskTest, busy: Boolean, onStop: () -> Unit, onDismiss: () -> Unit, onResume: () -> Unit) {
    val c = Titan.colors
    val phase = if (test.phase.isNotEmpty()) t("disktest.phase.${test.phase}") else t("disktest.starting")
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MonoLine(test.disk, color = c.text, small = false)
        Muted(listOf(test.model, test.serial, formatDiskBytes(test.sizeBytes)).filter { it.isNotEmpty() }.joinToString(" · "))
        when (test.result) {
            "" -> StatusChip(t("disktest.mode.${test.mode}"), Tone.Info)
            "passed" -> StatusChip(t("disktest.result.passed"), Tone.Ok)
            "failed" -> StatusChip(t("disktest.result.failed"), Tone.Error)
            "interrupted" -> StatusChip(t("disktest.result.interrupted"), Tone.Warn)
            else -> StatusChip(tKnown("disktest.result.${test.result}", test.result), Tone.Neutral)
        }
        Muted(
            t("disktest.startedAt", "time" to formatDateTime(test.started)) +
                (test.resumed?.let { " · " + t("disktest.resumedAt", "time" to formatDateTime(it), "count" to (test.resumes ?: 1)) } ?: ""),
        )
        when {
            test.result == "interrupted" -> {
                Text(
                    t(if (test.mode == "write") "disktest.interruptedWrite" else "disktest.interruptedRead", "phase" to phase, "percent" to Math.round(test.percent)),
                    style = TitanType.body,
                    color = c.text,
                )
                ButtonRow {
                    ActionButton(t("disktest.discard"), onDismiss, enabled = !busy)
                    ActionButton(t("disktest.resume"), onResume, primary = true, enabled = !busy)
                }
            }
            test.result.isEmpty() -> {
                Bar((test.percent / 100).toFloat().coerceIn(0f, 1f))
                val progress = progressText(test)
                Text(if (progress.isNotEmpty() && test.phase.isNotEmpty()) "$phase · $progress" else phase, style = TitanType.body, color = c.text)
                ActionButton(t("disktest.cancel"), onStop)
                restartHint(test)?.let { Muted(t("disktest.restart.$it")) }
            }
            else -> {
                if (test.result == "passed") Text(t("disktest.passedText"), style = TitanType.body, color = c.text)
                if (test.result == "failed") Text(t("disktest.failedText"), style = TitanType.body, color = c.dangerInk)
                ActionButton(t("disktest.dismiss"), onDismiss)
            }
        }
        if (test.errors > 0) Text(t("disktest.errors", "count" to test.errors), style = TitanType.body, color = c.dangerInk)
        for (note in test.notes) Muted(tKnown("disktest.note.${note.kind}", note.kind).replace("{detail}", note.detail))
    }
}

@Composable
private fun progressText(test: DiskTest): String {
    val percent = fixed(test.percent)
    return when {
        test.phase.isEmpty() -> t("disktest.starting")
        test.phase == "smart" -> if (test.percent > 0) t("disktest.progressSmart", "percent" to percent) else ""
        test.remainingSeconds < 0 -> t("disktest.progressSmart", "percent" to percent)
        else -> t("disktest.progress", "percent" to percent, "speed" to Math.round(test.speedBytesPerSecond / 1e6), "remaining" to formatDuration(test.remainingSeconds))
    }
}
