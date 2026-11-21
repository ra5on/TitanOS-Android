package io.github.ra5on.titanos.storage

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.Segments
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatBytes
import io.github.ra5on.titanos.admin.formatDecimal
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Einstellungen › Arbeitsspeicher (settings/memory): Anteil für Schreibpuffer, wird beim Wählen gespeichert. */
@Composable
internal fun MemoryPage(env: AdminEnv, pollMs: Long = 5000) {
    if (!env.admin) return Forbidden()
    val scope = rememberCoroutineScope()
    val api = remember(env) { StorageApi(env.http) }
    var status by remember { mutableStateOf<MemoryStatus?>(null) }
    var percent by remember { mutableIntStateOf(20) }
    var initialized by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var pollError by remember { mutableStateOf<Throwable?>(null) }
    var saved by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    Polling(env, pollMs) {
        if (busy) return@Polling
        val started = revision
        try {
            val result = api.memory()
            if (started == revision) {
                status = result
                if (!initialized) {
                    percent = result.settings.cachePercent
                    initialized = true
                }
                pollError = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (started == revision) pollError = e
        }
    }
    PageBody {
        Text(t("memoryPolicy.intro"), style = TitanType.body, color = Titan.colors.muted)
        error?.let { Notice(errorText(it, detail = true), tone = Tone.Error) }
        pollError?.let { Notice(errorText(it, detail = true), tone = Tone.Error) }
        val s = status
        if (s == null) {
            if (error == null) Loading()
            return@PageBody
        }
        s.error?.takeIf { it.isNotEmpty() }?.let { Notice(it, tone = Tone.Warn) }
        if (s.readOnly) Notice(t("memoryPolicy.readOnly"), tone = Tone.Warn)
        if (s.memory.lowAvailable == true) Notice(t("memoryPolicy.lowAvailable"), tone = Tone.Warn)
        SettingGroup(t("memoryPolicy.group.budget")) {
            // Alle Anteile als Knöpfe untereinander umbrechend; der lange Titel hat die ganze Breite.
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("memoryPolicy.percent"), style = TitanType.rowTitle, color = Titan.colors.text)
                Text(t("memoryPolicy.hint"), style = TitanType.rowSub, color = Titan.colors.muted)
                Segments(
                    (10..90 step 10).map { it.toString() to "$it %" },
                    percent.toString(),
                    { value ->
                        percent = value.toInt()
                        scope.launch {
                            busy = true
                            revision++
                            error = null
                            saved = false
                            try {
                                status = api.saveMemory(percent)
                                saved = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && !s.readOnly,
                )
                if (saved) Text(t("memoryPolicy.saved"), style = TitanType.small, color = Titan.colors.muted)
            }
        }
        SettingGroup(t("memoryPolicy.group.now")) {
            SettingRow(t("memoryPolicy.budget"), value = formatBytes(s.budgetBytes))
            SettingRow(t("memoryPolicy.reserve"), value = formatBytes(s.reserveBytes), divider = true)
            SettingRow(t("memoryPolicy.available"), value = formatBytes(s.memory.availableBytes), divider = true)
            SettingRow(t("memoryPolicy.dirty"), value = formatBytes((s.memory.dirtyBytes ?: 0) + (s.memory.writebackBytes ?: 0)), divider = true)
            SettingRow(
                t("memoryPolicy.pressure"),
                desc = t("memoryPolicy.pressureHint"),
                value = s.memory.pressure?.let { "${formatDecimal(it.someAvg10, 1)} % / ${formatDecimal(it.fullAvg10, 1)} %" } ?: t("common.unavailable"),
                divider = true,
            )
            if (s.paused) Notice(t("error.upload_memory_pressure"), Modifier.padding(14.dp), tone = Tone.Warn)
        }
    }
}
