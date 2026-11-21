package io.github.ra5on.titanos.settings.pages.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.NumberField
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject

/**
 * USV (routes/settings/ups): Zustand der USV (alle 5 s) und ein Formular
 * mit einem Speichern-Knopf – halb getippte Felder werden nie gespeichert.
 * Gespeichert wird `PUT /ups` mit den ganzen Einstellungen.
 */
@Composable
fun UpsPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val run = rememberRunner()
    val savedText = t("notify.saved")
    var answer by remember { mutableStateOf<JsonObject?>(null) }
    var draft by remember { mutableStateOf<JsonObject?>(null) }
    var byTime by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Pair<String, String>?>(null) }
    var found by remember { mutableStateOf<List<JsonObject>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var host by remember { mutableStateOf("") }

    fun take(res: JsonObject, keepDraft: Boolean = false) {
        answer = res
        if (draft == null || !keepDraft) {
            val s = res.o("settings")
            byTime = s.long("shutdownAfter") > 0
            draft = s.with(
                "driver" to s.str("driver").ifEmpty { "usbhid-ups" },
                "port" to s.str("port").ifEmpty { "auto" },
                "password" to "",
            ).let { if (!byTime) it.with("shutdownAfter" to 5) else it }
        }
    }

    fun fail(e: Throwable) {
        val code = (e as? ApiException)?.code ?: "internal"
        problem = tr.errorOf(e) to if (code == "invalid_ups_settings") e.message.orEmpty().substringAfter("ups: invalid setting: ", e.message.orEmpty().substringAfter(": ")) else ""
    }

    suspend fun load() {
        val mine = round
        try {
            val res = env.api.obj("ups")
            if (mine == round) take(res, keepDraft = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (answer == null) fail(e)
        }
    }
    LaunchedEffect(Unit) { load() }
    Poll(5000) { load() }

    PageIntro(t("ups.intro"))
    problem?.let { (text, detail) -> Banner(if (detail.isNotEmpty()) "$text\n$detail" else text, tone = Tone.Error) }
    val res = answer
    if (res == null && problem == null) LoadingLine(t("common.loading"))
    val status = res.o("status")
    if (res != null && status.str("mode", "off") != "off") {
        SettingGroup(t("ups.state")) {
            val connected = status.bool("connected")
            SettingRow(
                t("ups.model"),
                icon = WebIcons["battery"],
                desc = if (!connected && status.str("error").isNotEmpty()) status.str("error") else null,
                summary = status.str("model").ifEmpty { "–" },
                control = {
                    when {
                        !connected -> StatusChip(t("ups.notConnected"), Tone.Warn)
                        status.bool("lowBattery") -> StatusChip(t("ups.low"), Tone.Error)
                        status.bool("onBattery") -> StatusChip(t("ups.onBattery"), Tone.Warn)
                        else -> StatusChip(t("ups.online"), Tone.Ok)
                    }
                },
            )
            if (connected) {
                val charge = status.int("charge", -1)
                SettingRow(t("ups.charge"), icon = WebIcons["zap"], control = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        if (charge >= 0) {
                            Bar(charge / 100f, Modifier.width(140.dp).semantics { contentDescription = tr.t("ups.charge"); stateDescription = "$charge %" })
                        }
                        Mono(if (charge >= 0) "$charge %" else "–", muted = false)
                    }
                })
                val runtime = status.long("runtime", -1)
                SettingRow(t("ups.runtime"), icon = WebIcons["clock"], control = { Plain(if (runtime >= 0) Fmt.duration(tr, runtime) else "–") })
                val load = status.int("load", -1)
                SettingRow(t("ups.load"), icon = WebIcons["activity"], control = { Mono(if (load >= 0) "$load %" else "–", muted = false) })
                if (status.bool("shuttingDown")) Banner(t("ups.shuttingDown"), tone = Tone.Error)
            }
        }
    }

    val d = draft
    if (res != null && d != null) {
        val mode = d.str("mode", "off")
        val driver = d.str("driver")
        val usb = driver == "usbhid-ups" || driver == "blazer_usb"
        val passwordSet = res.bool("passwordSet")
        fun set(vararg p: Pair<String, Any?>) { draft = d.with(*p) }
        val complete = when (mode) {
            "local" -> usb || d.str("port").isNotBlank()
            "network" -> d.str("host").isNotBlank() && d.str("name").isNotBlank() && d.str("user").isNotBlank() && (passwordSet || d.str("password").isNotEmpty())
            else -> true
        }
        val saved = res.o("settings")
        if (mode == "local" && d.bool("serve") && saved.bool("serve") && saved.str("remotePassword").isNotEmpty()) {
            LaunchedEffect(Unit) {
                runCatching {
                    val info = env.api.obj("system")
                    host = info.o("network").str("ipv4").substringBefore('/').ifEmpty { info.str("hostname") }
                }
            }
        }
        SettingGroup(t("ups.settings")) {
            SettingRow(
                t("ups.connection"),
                desc = t("ups.modeHint.$mode"),
                icon = WebIcons["battery"],
                id = "ups-mode",
                startOpen = true,
                control = {
                    Select(mode, listOf("off", "local", "network").map { it to t("ups.mode.$it") }, { set("mode" to it) }, label = t("ups.connection"))
                },
                content = if (mode == "off") null else ({
                    if (mode == "local") {
                        Block {
                            Actions {
                                SmallButton(t("ups.scan"), {
                                    scanning = true
                                    problem = null
                                    run.run(id = "scan") {
                                        try {
                                            found = (env.api.post("ups/scan") as? JsonObject).a("found").objects()
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            fail(e)
                                        } finally {
                                            scanning = false
                                        }
                                    }
                                }, enabled = !run.running)
                                if (found?.isEmpty() == true) Muted(t("ups.scanNone"))
                            }
                            for (dev in found.orEmpty()) {
                                Framed {
                                    Plain(dev.str("product").ifEmpty { dev.str("driver") })
                                    Mono("${dev.str("driver")} · ${dev.str("port")}")
                                    SmallButton(t("ups.use"), { set("driver" to dev.str("driver"), "port" to dev.str("port")) })
                                }
                            }
                        }
                        SettingSub(t("ups.driver"), control = {
                            val drivers = res.strings("drivers").let { if (driver.isNotEmpty() && driver !in it) listOf(driver) + it else it }
                            Select(driver, drivers.map { it to it }, { set("driver" to it) }, label = t("ups.driver"))
                        })
                        if (!usb) {
                            SettingSub(t("ups.port"), control = {
                                Field(d.str("port"), { set("port" to it) }, label = t("ups.port"), placeholder = "/dev/ttyS0", mono = true, singleLine = true, modifier = Modifier.fillMaxWidth())
                            })
                        }
                        SettingSub(t("ups.serve"), control = {
                            Toggle(d.bool("serve"), { set("serve" to it) }, t("appform.on"), t("appform.off"), label = t("ups.serve"))
                        })
                        if (d.bool("serve") && saved.bool("serve") && saved.str("remotePassword").isNotEmpty()) {
                            Block {
                                Framed {
                                    Fact(t("ups.serveHost")) { Mono(host, muted = false) }
                                    Fact(t("ups.serveName")) { Mono("ups", muted = false) }
                                    Fact(t("notify.username")) { Mono("monitor", muted = false) }
                                    Fact(t("notify.password")) { Mono(saved.str("remotePassword"), muted = false) }
                                }
                                Muted(t("ups.serveHint"))
                            }
                        } else if (d.bool("serve")) {
                            Block { Muted(t("ups.serveAfterSave")) }
                        }
                    } else if (mode == "network") {
                        SettingSub(t("ups.server"), control = {
                            Field(d.str("host"), { set("host" to it) }, label = t("ups.server"), mono = true, singleLine = true, modifier = Modifier.fillMaxWidth())
                        })
                        SettingSub(t("ups.name"), control = {
                            Field(d.str("name"), { set("name" to it) }, label = t("ups.name"), placeholder = "ups", mono = true, singleLine = true, modifier = Modifier.fillMaxWidth())
                        })
                        SettingSub(t("notify.username"), control = {
                            Field(d.str("user"), { set("user" to it) }, label = t("notify.username"), mono = true, singleLine = true, modifier = Modifier.fillMaxWidth())
                        })
                        SettingSub(t("notify.password"), desc = if (passwordSet) t("notify.secretStored") else null, control = {
                            Field(d.str("password"), { set("password" to it) }, label = t("notify.password"), password = true, mono = true, modifier = Modifier.fillMaxWidth())
                        })
                    }
                }),
            )
            if (mode != "off") {
                val minutes = d.long("shutdownAfter").takeIf { it > 0 } ?: 1
                SettingRow(
                    t("ups.when"),
                    desc = t("ups.whenHint"),
                    icon = WebIcons["power"],
                    id = "ups-when",
                    startOpen = true,
                    summary = if (byTime) "${t("ups.whenTime")} $minutes ${t("ups.minutes")}" else t("ups.low"),
                ) {
                    Block {
                        RadioList(byTime, listOf(Triple(false, t("ups.whenLow"), null), Triple(true, t("ups.whenTime"), null)), { byTime = it })
                        Column(Modifier.fillMaxWidth()) {
                            NumberField(d.long("shutdownAfter"), { n -> if (n != null) set("shutdownAfter" to n.coerceIn(1, 240)) }, label = t("ups.minutes"), suffix = t("ups.minutes"), enabled = byTime)
                        }
                    }
                }
            }
            Actions {
                SmallButton(t("notify.save"), {
                    problem = null
                    round++
                    val body = d.with("shutdownAfter" to if (byTime) (d.long("shutdownAfter").takeIf { it > 0 } ?: 1) else 0)
                    run.run(id = "save", guard = GuardAction.Settings, ok = savedText) {
                        try {
                            val r = env.api.put("ups", body) as? JsonObject
                            if (r != null) take(r)
                        } catch (e: ApiException) {
                            if (e.code == "invalid_ups_settings") fail(e) else throw e
                        } finally {
                            // Abfragen, die während des Speicherns unterwegs waren, zählen auch nicht.
                            round++
                        }
                    }
                }, kind = ButtonKind.Primary, enabled = !run.running && complete)
            }
            if (!scanning) RunnerState(run)
        }
    }
}
