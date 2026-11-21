package io.github.ra5on.titanos.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.LabeledValue
import io.github.ra5on.titanos.admin.ListDetail
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.admin.uiLocale
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Protokoll (tools/logs): Zeilen des Journals nach Quelle, Stufe, Zeitraum
 * und Suchtext; „Mitlaufen“ holt alle drei Sekunden nach, was seit dem
 * letzten Stand dazukam, und scrollt nur mit, solange man am Ende steht.
 * Ab Split steht die gewählte Zeile ganz daneben.
 */
@Composable
internal fun LogsPage(ctx: ToolsCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableIntStateOf(6) }
    var search by rememberSaveable { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf("") }
    var follow by rememberSaveable { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var cursor by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf("") }
    var noPrevious by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val atEnd by remember { derivedStateOf { val info = list.layoutInfo; val last = info.visibleItemsInfo.lastOrNull(); last == null || last.index >= info.totalItemsCount - 1 } }
    val searchFocus = remember { FocusRequester() }
    val saver = rememberSaver()

    fun fail(e: Throwable) = context.errorText(if (e is ApiException) e else ApiException(0, "logs_failed", ""))

    // Eine neue Wahl lädt neu; Tippen wartet einen Moment.
    LaunchedEffect(source, priority, search, range, reload) {
        val request = ++generation
        delay(250)
        loading = true
        error = ""
        try {
            val res = ctx.api.logs(ctx.api.logsQuery(source, priority, search, range))
            if (request != generation) return@LaunchedEffect
            entries = res.entries
            cursor = res.cursor
            selected = null
            if (entries.isNotEmpty()) list.requestScrollToItem(entries.lastIndex + HEADER_ITEMS)
        } catch (e: Exception) {
            if (request == generation) error = fail(e)
        }
        if (request == generation) loading = false
    }
    Polling(follow to generation, if (follow) 3_000 else 0) {
        if (!follow || cursor.isEmpty() || loading) return@Polling
        val request = generation
        try {
            val res = ctx.api.logs(ctx.api.logsQuery(source, priority, search, range, after = cursor))
            if (request != generation) return@Polling
            error = ""
            cursor = res.cursor
            if (res.entries.isNotEmpty()) {
                val wasAtEnd = atEnd
                val dropped = maxOf(0, entries.size + res.entries.size - LOG_KEEP)
                entries = (entries + res.entries).takeLast(LOG_KEEP)
                selected = selected?.let { it - dropped }?.takeIf { it >= 0 }
                if (wasAtEnd) list.requestScrollToItem(entries.lastIndex + HEADER_ITEMS)
            }
        } catch (e: Exception) {
            if (request == generation) error = fail(e)
        }
    }

    fun copyLines() {
        copied = try {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText(context.msg(Msg("tools.logs")), logText(entries) + "\n"))
            "ok"
        } catch (_: Exception) {
            "failed"
        }
        scope.launch {
            delay(4000)
            copied = ""
        }
    }
    // Bis zu 50 000 Zeilen dieses Starts für Filter und Zeitraum, nicht nur die gezeigten.
    fun exportLines() {
        val path = ctx.api.logsExport(source, priority, range)
        saver.save("titanos-log.txt", "text/plain") { out, progress -> ctx.api.download(path, out, progress) }
    }
    // Das aufbewahrte Protokoll des Starts davor (vom Volume); erst holen, dann speichern.
    fun exportPrevious() {
        noPrevious = false
        scope.launch {
            val buffer = ByteArrayOutputStream()
            try {
                ctx.api.download("logs/export?boot=previous", buffer)
                val bytes = buffer.toByteArray()
                saver.save("titanos-log-previous-start.txt", "text/plain") { out, _ -> out.write(bytes) }
            } catch (_: Exception) {
                noPrevious = true
            }
        }
    }
    fun toEnd() {
        scope.launch { if (entries.isNotEmpty()) list.animateScrollToItem(entries.lastIndex + HEADER_ITEMS) }
    }

    PageKeys(ctx) { e ->
        when {
            e.refresh() -> { reload++; true }
            e.ctrlKey(Key.F) -> { runCatching { searchFocus.requestFocus() }; true }
            e.down() && e.ctrl() && e.isShiftPressed && e.key == Key.C -> { if (entries.isNotEmpty()) copyLines(); true }
            e.ctrlKey(Key.S) -> { if (entries.isNotEmpty()) exportLines(); true }
            e.down() && e.ctrl() && e.key == Key.MoveEnd -> { toEnd(); true }
            else -> false
        }
    }
    val chosen = selected?.let { entries.getOrNull(it) }
    PageUp(ctx, if (chosen != null && !io.github.ra5on.titanos.ui.currentWidth().twoPanes) ({ selected = null }) else null)

    ListDetail(
        list = {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                            Card {
                                ChoiceRow(t("tools.source"), LOG_SOURCES.map { it to t("tools.source.${it.ifEmpty { "all" }}") }, source, { source = it }, icon = "filter")
                                ChoiceRow(t("tools.level"), LOG_LEVELS.map { it.first.toString() to t(it.second) }, priority.toString(), { priority = it.toInt() }, icon = "flag", divider = true)
                                ChoiceRow(t("tools.range"), LOG_RANGES.map { it.first to t(it.second) }, range, { range = it }, icon = "clock", divider = true)
                                CheckRow(t("tools.follow"), follow, { follow = it }, divider = true)
                            }
                            Field(t("tools.search"), search, { search = it.take(200) }, modifier = Modifier.focusRequester(searchFocus))
                            ButtonRow {
                                ActionButton(t("tools.copyLines"), ::copyLines, enabled = entries.isNotEmpty(), icon = "copy")
                                ActionButton(t("tools.exportLines"), ::exportLines, enabled = entries.isNotEmpty() && !saver.state.busy, icon = "download")
                                ActionButton(t("tools.exportPrevious"), ::exportPrevious, enabled = !saver.state.busy)
                            }
                            val status = when {
                                copied == "ok" -> t("tools.copied")
                                copied == "failed" -> t("tools.copyFailed")
                                entries.isNotEmpty() -> t("tools.lineCount", "count" to entries.size)
                                else -> ""
                            }
                            if (status.isNotEmpty()) Muted(status)
                            SaveStatus(saver.state)
                            if (error.isNotEmpty()) Notice(error, tone = Tone.Error, actions = { ActionButton(t("ui.retry"), { reload++ }, enabled = !loading) })
                            if (noPrevious) Notice(t("tools.noPrevious"), tone = Tone.Warn)
                        }
                    }
                    if (entries.isEmpty()) {
                        item { if (loading) Loading(text = t("tools.loading")) else Muted(t("tools.noLines")) }
                    }
                    itemsIndexed(entries, key = { i, _ -> i }) { i, e -> LogLine(e, i == selected) { selected = i } }
                    item {
                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Muted(t("tools.logsNote"))
                            StatusLine(
                                listOf(
                                    t("tools.statLines", "count" to entries.size) to false,
                                    "${t("notify.severity.error")}: ${entries.count { it.priority <= 3 }}" to entries.any { it.priority <= 3 },
                                    "${t("notify.severity.warning")}: ${entries.count { it.priority == 4 }}" to false,
                                ),
                            )
                        }
                    }
                }
                if (!atEnd && entries.isNotEmpty()) {
                    ActionButton("${t("tools.toEnd")} ↓", ::toEnd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), primary = true)
                }
            }
        },
        detail = chosen?.let { e -> { LogDetail(e) } },
        listWidth = 520.dp,
        empty = { PageBody { Muted(androidx.compose.ui.res.stringResource(R.string.tools_pick)) } },
    )
}

/** Kopf der Liste vor der ersten Zeile (Filter). */
private const val HEADER_ITEMS = 1

@Composable
private fun clockFormat(): DateTimeFormatter {
    val locale = uiLocale()
    return remember(locale) { DateTimeFormatter.ofPattern(if (locale.language == "de") "dd.MM. HH:mm:ss" else "MM/dd HH:mm:ss", locale).withZone(ZoneId.systemDefault()) }
}

/** Eine Zeile: Zeit, Quelle, Stufe als Text und die Meldung ganz (waagrecht scrollbar). Harmlose Meldungen leise mit Grund. */
@Composable
private fun LogLine(e: LogEntry, selected: Boolean, onClick: () -> Unit) {
    val c = Titan.colors
    val clock = clockFormat()
    val note = harmlessNote(e.message)
    val level = if (e.priority <= 3) "error" else if (e.priority == 4) "warn" else ""
    val color = when {
        note != null -> c.muted
        level == "error" -> c.dangerInk
        level == "warn" -> c.warnInk
        else -> c.text
    }
    Column(
        Modifier.fillMaxWidth().background(if (selected) c.accent.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 44.dp).padding(vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val mono = TitanType.small.copy(fontFamily = TitanFonts.mono)
            Text(clock.format(instantOf(e.time) ?: java.time.Instant.EPOCH), style = mono, color = c.muted, softWrap = false)
            Text(e.source, style = mono, color = c.muted, softWrap = false)
            if (level.isNotEmpty()) Text(t(if (level == "error") "notify.severity.error" else "notify.severity.warning"), style = mono.copy(fontWeight = FontWeight.Bold), color = color, softWrap = false)
            Text(e.message.trim().ifEmpty { t("tools.noText") }, style = mono, color = color, softWrap = false)
        }
        if (note != null) Text(t(note), style = TitanType.small, color = c.muted)
        Divider(Modifier.padding(top = 6.dp))
    }
}

/** Die gewählte Zeile ganz, umbrochen. */
@Composable
private fun LogDetail(e: LogEntry) {
    PageBody {
        Text(e.source, style = TitanType.title.copy(fontSize = TitanType.rowTitle.fontSize), color = Titan.colors.text, modifier = Modifier.semantics { heading() })
        LabeledValue(t("activity.col.time"), formatDateTime(e.time))
        LabeledValue(t("tools.level"), t(LOG_LEVELS.lastOrNull { it.first <= e.priority }?.second ?: "tools.level.error") + " (${e.priority})")
        if (e.priority <= 4) StatusChip(t(if (e.priority <= 3) "notify.severity.error" else "notify.severity.warning"), if (e.priority <= 3) Tone.Error else Tone.Warn)
        harmlessNote(e.message)?.let { Notice(t(it)) }
        Text(e.message.trim().ifEmpty { t("tools.noText") }, style = TitanType.body.copy(fontFamily = TitanFonts.mono), color = Titan.colors.text)
    }
}

/** Fortschritt und Ergebnis einer Datei, die gerade gespeichert wird. */
@Composable
internal fun SaveStatus(state: SaveState) {
    when {
        state.busy -> Loading(
            text = if (state.total > 0) "${io.github.ra5on.titanos.admin.formatBytesOf(state.bytes, state.total)}"
            else io.github.ra5on.titanos.admin.formatBytes(state.bytes),
        )
        state.error != null -> Notice(errorText(state.error!!), tone = Tone.Error)
        state.done != null -> Notice(androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.admin.R.string.admin_saved_file, state.done!!), tone = Tone.Ok)
    }
}
