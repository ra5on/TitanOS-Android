package io.github.ra5on.titanos.settings.pages.hardware

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.num
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject

/**
 * Arbeitsspeicher (routes/settings/memory): welcher Anteil des RAM als
 * Schreibcache dient, und der Stand jetzt (alle 5 s, nicht während des
 * Speicherns). Die Wahl wird sofort gespeichert.
 */
@Composable
fun MemoryPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val state = rememberLoad { env.api.obj("system/memory") }
    val run = rememberRunner()
    val savedText = t("memoryPolicy.saved")
    var pollError by remember { mutableStateOf("") }
    // Der Anteil wird nur einmal übernommen; danach gilt die Wahl hier.
    var percent by remember { mutableIntStateOf(-1) }
    var revision by remember { mutableIntStateOf(0) }
    Poll(5000, skip = { run.running }) {
        val started = revision
        try {
            val res = env.api.obj("system/memory")
            if (started == revision) { state.value = res; pollError = "" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (started == revision) pollError = tr.errorOf(e, detail = true)
        }
    }
    PageIntro(t("memoryPolicy.intro"))
    if (pollError.isNotEmpty()) Banner(pollError, tone = Tone.Error)
    Loading(state) { status ->
        if (percent < 0) percent = status.o("settings").int("cachePercent", 20)
        val memory = status.o("memory")
        if (status.str("error").isNotEmpty()) Banner(status.str("error"), tone = Tone.Warn)
        if (status.bool("readOnly")) Banner(t("memoryPolicy.readOnly"), tone = Tone.Warn)
        if (memory.bool("lowAvailable")) Banner(t("memoryPolicy.lowAvailable"), tone = Tone.Warn)
        SettingGroup(t("memoryPolicy.group.budget")) {
            SettingRow(
                t("memoryPolicy.percent"),
                desc = t("memoryPolicy.hint"),
                icon = WebIcons["memory"],
                control = {
                    Select(
                        percent,
                        (10..90 step 10).map { it to "$it %" },
                        { picked ->
                            percent = picked
                            revision++
                            run.run(guard = GuardAction.Settings, ok = savedText, detail = true) {
                                val cur = state.value ?: status
                                state.value = env.api.put("system/memory", cur.o("settings").with("cachePercent" to picked)) as? JsonObject ?: cur
                            }
                        },
                        label = t("memoryPolicy.percent"),
                        enabled = !run.running && !status.bool("readOnly"),
                    )
                },
            )
            RunnerState(run)
        }
        SettingGroup(t("memoryPolicy.group.now")) {
            SettingRow(t("memoryPolicy.budget"), control = { Mono(Fmt.bytes(tr, status.long("budgetBytes")), muted = false) })
            SettingRow(t("memoryPolicy.reserve"), control = { Mono(Fmt.bytes(tr, status.long("reserveBytes")), muted = false) })
            SettingRow(t("memoryPolicy.available"), control = { Mono(Fmt.bytes(tr, memory.long("availableBytes")), muted = false) })
            SettingRow(t("memoryPolicy.dirty"), control = { Mono(Fmt.bytes(tr, memory.long("dirtyBytes") + memory.long("writebackBytes")), muted = false) })
            SettingRow(
                t("memoryPolicy.pressure"),
                desc = t("memoryPolicy.pressureHint"),
                control = {
                    val p = memory.o("pressure")
                    Mono(
                        if (memory.has("pressure")) "${fixed1(p.num("someAvg10"))} % / ${fixed1(p.num("fullAvg10"))} %" else t("common.unavailable"),
                        muted = false,
                    )
                },
            )
            if (status.bool("paused")) Banner(t("error.upload_memory_pressure"), tone = Tone.Warn)
        }
    }
}

/** toFixed(1) wie im Web (immer eine Nachkommastelle). */
internal fun fixed1(v: Double): String = "%.1f".format(java.util.Locale.ROOT, v)
