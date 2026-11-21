package io.github.ra5on.titanos.settings.pages.smarthome

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.longOrNull
import io.github.ra5on.titanos.settings.common.num
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SliderField
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.components.ValueLine
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale

private val STT_LANGUAGES = listOf("auto", "de", "en", "fr", "es", "it", "nl", "pl", "pt", "tr", "ru", "uk")

private fun phaseText(tr: Tr, s: JsonObject): String {
    val phase = s.str("phase")
    val msg = s.str("message")
    return when {
        phase == "error" -> if (msg in setOf("volume", "docker", "device")) tr.t("speech.error.$msg") else tr.t("speech.phase.error")
        phase == "pulling" && s.long("pullTotal") > 0 -> tr.t("speech.phase.pullingOf", "done" to Fmt.bytes(tr, s.long("pullDone")), "total" to Fmt.bytes(tr, s.long("pullTotal")))
        else -> tr.or("speech.phase.$phase", phase)
    }
}

private fun phaseTone(s: JsonObject) = when (s.str("phase")) {
    "running" -> Tone.Ok
    "error" -> Tone.Error
    "off" -> Tone.Inactive
    else -> Tone.Info
}

private fun decimal(tr: Tr, v: Double): String =
    NumberFormat.getNumberInstance(Fmt.locale(tr)).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }.format(v)

/**
 * Sprachdienste (routes/settings/speech, PLAN 92): Spracherkennung
 * (faster-whisper) und Sprachausgabe (Piper) auf diesem NAS für Home
 * Assistant (Wyoming) und den KI-Agenten – Schalter, Grafikkarte oder
 * Prozessor, Volume, Modelle und Stimmen laden, aktivieren und entfernen,
 * Hörprobe, Mikrofon ausprobieren, Adressen für Home Assistant und wer sie
 * erreichen darf.
 */
@Composable
fun SpeechPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberLoad {
        val answer = env.api.obj("speech")
        // Eine Antwort ohne die Teile, die die Seite zeigt, ist keine Antwort.
        if (!answer.o("status").has("whisper") || !answer.o("status").has("piper") || !answer.has("settings") || !answer.has("jobs")) {
            throw IllegalStateException("invalid_response")
        }
        answer
    }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var voiceLanguage by remember { mutableStateOf(if (tr.de) "de" else "en") }
    var heard by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var hearing by remember { mutableStateOf(false) }
    var sampling by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf("") }
    val player = remember { BytesPlayer() }
    val mic = remember { MicRecorder() }
    DisposableEffect(Unit) { onDispose { player.stop(); mic.stop() } }

    val data = state.value
    val moving = data != null && (
        data.o("jobs").values.any { (it as? JsonObject)?.str("error").isNullOrEmpty() } ||
            data.o("status").o("whisper").str("phase") in setOf("pulling", "starting") ||
            data.o("status").o("piper").str("phase") in setOf("pulling", "starting")
        )
    // Solange sich etwas bewegt (Download, Abbild, Start), folgt die Seite.
    LaunchedEffect(moving) {
        while (true) {
            delay(if (moving) 2000 else 15000)
            state.refresh()
        }
    }

    fun save(change: JsonObject) {
        val d = state.value ?: return
        busy = true
        error = ""
        Guard.require(context, GuardAction.Settings, onDenied = { busy = false }) {
            scope.launch {
                try {
                    env.api.put("speech/settings", d.o("settings").with(*change.entries.map { it.key to it.value }.toTypedArray()))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr.errorOf(e)
                }
                state.refresh()
                busy = false
            }
        }
    }

    fun act(block: suspend () -> Unit) {
        error = ""
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
            state.refresh()
        }
    }

    fun download(kind: String, id: String) = act { env.api.post("speech/download", jobj("kind" to kind, "id" to id)) }
    fun cancel(kind: String, id: String) = act { env.api.delete("speech/download", jobj("kind" to kind, "id" to id)) }

    fun sample(voice: String) {
        player.stop()
        if (sampling == voice) { sampling = ""; return }
        sampling = voice
        error = ""
        scope.launch {
            try {
                val bytes = env.api.bytes("speech/say", "POST", jobj("text" to tr.t(if (voice.startsWith("de")) "speech.sampleDe" else "speech.sampleEn"), "voice" to voice))
                player.play(bytes) { if (sampling == voice) sampling = "" }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
                sampling = ""
            }
        }
    }

    fun startRecording() {
        heard = ""
        recording = mic.start(context)
        if (!recording) error = tr.t("agent.voiceNoMic")
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else error = tr.t("agent.voiceNoMic")
    }
    fun listen() {
        error = ""
        if (!recording) {
            if (MicRecorder.allowed(context)) startRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val wav = mic.stop()
        recording = false
        hearing = true
        scope.launch {
            try {
                heard = env.api.postRaw("speech/hear", wav, "audio/wav").o().str("text").ifEmpty { tr.t("speech.heardNothing") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            } finally {
                hearing = false
            }
        }
    }

    PageIntro(t("speech.intro"))

    val loadError = state.error?.let { e -> if (e.message == "invalid_response") tr.t("error.invalid_response") else tr.errorOf(e) }.orEmpty()
    if (error.isNotEmpty() || loadError.isNotEmpty()) {
        Banner(error.ifEmpty { loadError }, tone = Tone.Error, action = if (data == null) t("common.retry") else null, onAction = { state.reload() })
    }
    if (data == null) {
        if (error.isEmpty() && state.loading) LoadingLine(t("agent.loading"))
        return
    }

    val s = data.o("settings")
    val status = data.o("status")
    val whisper = status.o("whisper")
    val piper = status.o("piper")
    val jobs = data.o("jobs")
    val devices = data.a("devices").objects()
    val volumes = data.texts("volumes")
    val hosts = data.a("hosts").objects()
    val lanHosts = hosts.filter { it.str("kind") == "lan" }.map { it.str("host") }

    @Composable
    fun Item(kind: String, id: String, title: String, installed: Boolean, active: Boolean) {
        val job = jobs.o("$kind/$id").takeIf { jobs.has("$kind/$id") }
        when {
            job != null && job.str("error").isEmpty() -> {
                val total = job.long("total")
                val pct = if (total > 0) (100 * job.long("done") / total).toInt() else 0
                LinearProgressIndicator(
                    progress = { if (total > 0) job.long("done").toFloat() / total else 0f },
                    color = Titan.colors.accent,
                    trackColor = Titan.colors.track,
                    modifier = Modifier.widthIn(min = 80.dp, max = 120.dp).semantics { contentDescription = tr.t("speech.downloading", "name" to title) },
                )
                Plain("$pct %")
                SmallButton(t("common.cancel"), { cancel(kind, id) })
            }
            active -> StatusChip(t("speech.active"), Tone.Ok)
            installed -> {
                SmallButton(t("speech.activate"), { save(if (kind == "model") jobj("whisperModel" to id) else jobj("voice" to id)) }, enabled = !busy)
                io.github.ra5on.titanos.ui.components.IconButton(WebIcons["trash"], tr.t("speech.removeName", "name" to title), {
                    ov.confirm(tr.t("speech.removeTitle"), tr.t("speech.removeText", "name" to title), action = tr.t("common.delete"), cancel = tr.t("common.cancel")) {
                        Guard.require(context, GuardAction.Settings) { act { env.api.delete("speech/$kind/${URLEncoder.encode(id, "UTF-8").replace("+", "%20")}") } }
                    }
                }, size = 20.dp)
            }
            else -> {
                if (job != null && job.str("error").isNotEmpty()) StatusChip(t("speech.downloadFailed"), Tone.Error)
                SmallButton(if (job?.str("error").isNullOrEmpty()) t("speech.download") else t("common.retry"), { download(kind, id) }, icon = WebIcons["download"], enabled = s.str("volume").isNotEmpty())
            }
        }
    }

    if (!status.bool("available")) Banner(t("speech.unavailable"), tone = Tone.Warn)

    // Dienst
    SettingGroup(t("speech.service")) {
        SettingRow(
            t("speech.enable"),
            desc = if (s.str("volume").isNotEmpty()) t("speech.enableDesc") else t("speech.enableNoVolume"),
            icon = WebIcons["mic"],
            control = {
                if (s.bool("enabled")) {
                    StatusChip("${t("speech.stt")}: ${phaseText(tr, whisper)}", phaseTone(whisper))
                    StatusChip("${t("speech.tts")}: ${phaseText(tr, piper)}", phaseTone(piper))
                }
                OnOff(s.bool("enabled"), { save(jobj("enabled" to it)) }, label = t("speech.enable"), enabled = !busy && (s.bool("enabled") || s.str("volume").isNotEmpty()))
            },
        )
        for ((n, svc) in listOf(whisper, piper).withIndex()) {
            if (s.bool("enabled") && svc.str("phase") == "error" && svc.str("message") !in setOf("volume", "docker", "device")) {
                SettingRow(tr.t("speech.errorOf", "service" to tr.t(if (n == 0) "speech.stt" else "speech.tts")), icon = WebIcons["info"], desc = svc.str("message"))
            }
        }
        SettingRow(t("speech.device"), desc = t("speech.deviceDesc"), icon = WebIcons["chip"], id = "speech-device", startOpen = true) {
            val options = devices.map { d ->
                val text = when {
                    !d.bool("available") -> tr.t(if (d.str("reason") == "vm") "speech.deviceVM" else "speech.deviceDriver")
                    d.long("memoryTotalBytes") > 0 && d.longOrNull("memoryUsedBytes") != null -> tr.t(
                        "speech.deviceMemory",
                        "used" to Fmt.bytes(tr, d.long("memoryUsedBytes")),
                        "free" to Fmt.bytes(tr, maxOf(0, d.long("memoryTotalBytes") - d.long("memoryUsedBytes"))),
                        "total" to Fmt.bytes(tr, d.long("memoryTotalBytes")),
                    )
                    else -> tr.t("speech.deviceReady")
                }
                Triple(d.str("id"), d.str("title"), text)
            } + Triple("cpu", tr.t("speech.cpu"), tr.t(if (devices.isNotEmpty()) "speech.cpuDesc" else "speech.cpuOnly"))
            RadioList(s.str("device"), options, { id ->
                val dev = devices.firstOrNull { it.str("id") == id }
                if (id != s.str("device") && (dev == null || dev.bool("available"))) save(jobj("device" to id))
            }, enabled = !busy)
            Muted(t("speech.deviceNote"))
        }
        SettingRow(
            t("speech.volume"),
            desc = t("speech.volumeDesc") + if (s.bool("enabled")) "\n" + t("speech.volumeLocked") else "",
            icon = WebIcons["disk"],
            control = {
                val v = s.str("volume")
                val options = (if (v.isEmpty()) listOf("" to t("speech.volumeChoose")) else emptyList()) + volumes.map { it to it } + (if (v.isNotEmpty() && v !in volumes) listOf(v to v) else emptyList())
                Select(v, options, { if (it != v) save(jobj("volume" to it, "whisperModel" to "", "voice" to "")) }, label = t("speech.volume"), enabled = !busy && !s.bool("enabled"))
            },
        )
    }

    // Spracherkennung
    SettingGroup(t("speech.sttTitle"), desc = t("speech.sttDesc")) {
        for (m in data.a("models").objects()) {
            val id = m.str("id")
            val vram = m.long("vramBytes")
            SettingRow(
                id,
                desc = "${tr.or("speech.model.$id", id)} · ${Fmt.bytes(tr, m.long("bytes"))}" + if (vram > 0) " · " + tr.t("speech.vram", "size" to Fmt.bytes(tr, vram)) else "",
                icon = WebIcons[if (s.str("whisperModel") == id) "check" else "box"],
                badge = if (m.bool("recommended")) t("speech.recommended") else null,
                control = { Item("model", id, id, m.bool("installed"), s.str("whisperModel") == id) },
            )
        }
        SettingRow(t("speech.language"), desc = t("speech.languageDesc"), icon = WebIcons["globe"], control = {
            val options = STT_LANGUAGES.map { code -> code to if (code == "auto") tr.t("speech.languageAuto") else Locale.forLanguageTag(code).getDisplayLanguage(Fmt.locale(tr)).ifEmpty { code } }
            Select(s.str("language"), options, { save(jobj("language" to it)) }, label = t("speech.language"), enabled = !busy)
        })
        val running = whisper.str("phase") == "running"
        SettingRow(
            t("speech.try"),
            desc = if (heard.isNotEmpty()) "„$heard“" else t("speech.tryDesc") + if (!running) "\n" + t("speech.needsRunning") else "",
            icon = WebIcons["play"],
            control = {
                SmallButton(
                    if (hearing) t("speech.hearing") else if (recording) t("speech.stop") else t("speech.record"),
                    { listen() },
                    icon = WebIcons[if (recording) "stop" else "mic"],
                    enabled = !hearing && running,
                )
            },
        )
    }

    // Sprachausgabe
    val voices = data.a("voices").objects()
    val languages = voices.map { it.str("language").take(2) to it.str("languageName") }.filter { it.second.isNotEmpty() }
        .distinctBy { it.first }.sortedWith(compareBy(java.text.Collator.getInstance(Fmt.locale(tr))) { it.second })
    val shownVoices = voices.filter { v -> v.bool("installed") || v.str("language").startsWith(voiceLanguage) || v.str("language").isEmpty() }
    SettingGroup(
        t("speech.ttsTitle"),
        desc = t("speech.ttsDesc"),
        tools = if (languages.isNotEmpty()) ({ Select(voiceLanguage, languages, { voiceLanguage = it }, label = t("speech.voiceLanguage")) }) else null,
    ) {
        if (data.str("voicesError").isNotEmpty()) Banner(t("speech.voicesError"), tone = Tone.Warn)
        if (shownVoices.isEmpty()) Muted(t("speech.noVoices"))
        for (v in shownVoices) {
            val id = v.str("id")
            val quality = v.str("quality")
            val title = if (quality.isNotEmpty()) "${v.str("name")} (${tr.or("speech.quality.$quality", quality)})" else id
            val desc = if (v.str("languageName").isNotEmpty()) {
                v.str("languageName") + (v.str("country").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") +
                    (if (v.int("speakers") > 1) " · " + tr.t("speech.speakers", "count" to v.int("speakers")) else "") + " · " + Fmt.bytes(tr, v.long("bytes"))
            } else id
            SettingRow(
                title,
                desc = desc,
                icon = WebIcons[if (s.str("voice") == id) "check" else "volume"],
                control = {
                    if (v.bool("installed")) {
                        val label = if (piper.str("phase") != "running") tr.t("speech.needsRunning") else tr.t("speech.sample", "name" to v.str("name"))
                        SmallButton(
                            tr.t("speech.sample", "name" to v.str("name")),
                            { sample(id) },
                            icon = WebIcons[if (sampling == id) "stop" else "play"],
                            enabled = piper.str("phase") == "running",
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                    Item("voice", id, v.str("name"), v.bool("installed"), s.str("voice") == id)
                },
            )
        }
        SettingRow(t("speech.speed"), desc = t("speech.speedDesc"), icon = WebIcons["activity"]) {
            var speed by remember(s.num("speed", 1.0)) { mutableFloatStateOf(s.num("speed", 1.0).toFloat()) }
            SliderField(
                speed,
                { speed = (Math.round(it * 10) / 10f) },
                0.5f..2f,
                label = t("speech.speed"),
                valueText = "${decimal(tr, speed.toDouble())}×",
                steps = 14,
                onDone = { save(jobj("speed" to (Math.round(speed * 10) / 10.0))) },
                enabled = !busy,
            )
        }
    }

    // Verwenden
    SettingGroup(t("speech.use")) {
        SettingRow(t("speech.ha"), desc = t("speech.haDesc"), icon = WebIcons["home"], id = "speech-ha", startOpen = true) {
            for ((svc, title, hint) in listOf(Triple(whisper, t("speech.haStep1"), t("speech.haStep1Hint")), Triple(piper, t("speech.haStep2"), t("speech.haStep2Hint")))) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Plain(title, bold = true)
                    Muted(hint)
                    for (h in lanHosts) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            ValueLine(t("speech.host"), h, mono = true, modifier = Modifier.widthIn(max = 260.dp))
                            ValueLine(t("speech.port"), svc.int("port").toString(), mono = true, modifier = Modifier.widthIn(max = 160.dp))
                        }
                    }
                    Muted(
                        when {
                            svc.str("portError").isNotEmpty() -> tr.t("speech.portBusy", "port" to svc.int("port"))
                            !svc.has("seen") -> tr.t("speech.seenNever")
                            else -> tr.t(if (svc.o("seen").bool("ok")) "speech.seenOk" else "speech.seenRefused", "when" to Fmt.dateTime(tr, svc.o("seen").str("at")), "from" to svc.o("seen").str("from"))
                        },
                    )
                    for (h in lanHosts) {
                        SmallButton(if (copied == h) t("agent.copiedShort") else t("speech.copyHost"), {
                            if (copyText(context, h)) {
                                copied = h
                                scope.launch { delay(1800); if (copied == h) copied = "" }
                            }
                        }, icon = WebIcons[if (copied == h) "check" else "copy"])
                    }
                }
            }
            SettingSub(t("speech.haStep3"), desc = t("speech.haStep3Hint"))
            val tailscale = hosts.filter { it.str("kind") == "tailscale" }.map { it.str("host") }
            SettingSub(t("speech.otherHosts"), desc = t("speech.otherHostsApp") + tailscale.joinToString("") { " " + tr.t("speech.otherHostsTailscale") + " " + it })
            SettingSub(t("speech.reach"), desc = t("speech.reachDesc"), control = {
                Segments(s.str("reach"), listOf("ha" to t("speech.reachHA"), "lan" to t("speech.reachLAN")), { if (it != s.str("reach")) save(jobj("reach" to it)) }, enabled = !busy)
            })
        }
        SettingRow(t("speech.agent"), desc = t("speech.agentDesc"), icon = WebIcons["sparkle"], control = {
            OnOff(s.bool("agent"), { save(jobj("agent" to it)) }, label = t("speech.agent"), enabled = !busy)
        })
    }
}
