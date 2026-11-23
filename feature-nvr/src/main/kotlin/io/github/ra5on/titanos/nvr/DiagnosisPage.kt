package io.github.ra5on.titanos.nvr

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.Poll
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.components.TitanButton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Composable
fun DiagnosisPage(model: NvrModel) {
    val ui = LocalComputeUi.current
    var d by remember { mutableStateOf<Diagnosis?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf(false) }
    Poll(10000) {
        try {
            d = model.api.diagnosis()
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    DiagnosisScreen(d, error, busy) {
        busy = true
        ui.run(onError = { busy = false; error = it }) {
            model.api.retryDetector()
            d = model.api.diagnosis()
            model.refresh()
            busy = false
        }
    }
}

private val pretty = Json(TitanJson) { prettyPrint = true }

/**
 * Warum die Erkennung läuft oder nicht (diagnosis/+page.svelte): Zustand in
 * Worten, Ursache mit Bedeutung und Ausweg, Gerät, Bilder je Kamera,
 * Container und das Protokoll zum Lesen und Kopieren.
 */
@Composable
fun DiagnosisScreen(d: Diagnosis?, error: Throwable?, busy: Boolean, onRetry: () -> Unit) {
    val loc = locale()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf("") }
    ScrollPage {
        Sub(t("nvr.diag.intro"))
        ErrorLine(error)
        if (d == null) {
            if (error == null) io.github.ra5on.titanos.compute.Loading()
            return@ScrollPage
        }
        val devices = d.hardware.deviceList
        val decoders = d.hardware.decoderList
        val cause = d.detector.failover?.reason ?: d.lastError?.code ?: ""
        val causeDetail = d.detector.failover?.detail?.ifEmpty { null } ?: d.lastError?.detail.orEmpty()
        fun fps(v: Double) = decimal(v, 1, loc)
        @Composable
        fun whenText(iso: String?) = if (iso.isNullOrEmpty()) "–" else sinceText(iso, loc)
        Group(t("nvr.diag.state"), tools = {
            TitanButton(if (copied == "details") t("nvr.diag.copied") else t("nvr.diag.copyDetails"), {
                val tree = TitanJson.encodeToJsonElement(Diagnosis.serializer(), d).jsonObject
                val short = JsonArray(d.log.orEmpty().takeLast(60).map { JsonPrimitive(it) })
                clipboard.setText(AnnotatedString(pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(tree + ("log" to short)))))
                copied = "details"
            }, icon = NvrIcons.copy)
        }) {
            Row2(t("nvr.diag.stateName.${d.state}"), TitanIcons.info, t("nvr.diag.stateText.${d.state}"))
            if (cause.isNotEmpty()) Row2(reasonTitle(cause), TitanIcons.warn, divider = true, descContent = {
                Sub(t("nvr.diag.means") + " " + reasonMeaning(cause))
                Sub(t("nvr.diag.todo") + " " + reasonAdvice(cause))
                Sub(if (causeDetail.isNotEmpty()) "$cause: $causeDetail" else cause, mono = true)
            })
            val f = d.detector.failover
            if (f != null) {
                Row2(t("nvr.failover.title", "to" to deviceName(f.to, devices), "from" to deviceName(f.from, devices)), TitanIcons.restart, t("nvr.failover.since", "time" to whenText(f.at)) + " " + t("nvr.failover.retryHint"), divider = true) {
                    TitanButton(t("nvr.retry"), onRetry, enabled = !busy, icon = TitanIcons.restart)
                }
            } else if (d.state == "stopped") {
                Row2(t("nvr.diag.restart"), TitanIcons.restart, t("nvr.diag.restartHint"), divider = true) {
                    TitanButton(t("nvr.retry"), onRetry, enabled = !busy, icon = TitanIcons.restart)
                }
            }
        }
        Group(t("nvr.diag.detection")) {
            Row2(
                t("nvr.detector.title"), TitanIcons.bolt,
                t("nvr.diag.chosen", "device" to if (d.detector.kind == "auto") t("nvr.detector.auto", "device" to deviceName(d.hardware.auto)) else deviceName(d.detector.kind, devices)),
                summary = deviceName(d.detector.active, devices),
            )
            d.decoder?.let { dec ->
                Row2(t("nvr.decoder.title"), TitanIcons.chip, divider = true, summary = decoderName(dec.active, decoders), descContent = {
                    Sub(t("nvr.decoder.chosen", "choice" to if (dec.setting == "auto") t("nvr.decoder.auto") else decoderName(dec.setting, decoders)))
                    dec.reason?.let { Sub(decoderReason(it), warn = true) }
                })
            }
            Row2(t("nvr.diag.inference"), NvrIcons.clock, t("nvr.diag.inferenceHint"), divider = true, summary = d.detector.inferenceMs?.let { t("nvr.diag.ms", "ms" to decimal(it, 1, loc)) } ?: t("nvr.diag.unknown"))
            Row2(t("nvr.diag.process"), TitanIcons.chip, divider = true, summary = d.detectorPid?.let { t("nvr.diag.processPid", "pid" to it) } ?: t("nvr.diag.processNone"))
            Row2("/dev/accel", TitanIcons.chip, t("nvr.diag.accelHint"), divider = true, summary = if (d.accelDevice) t("nvr.diag.present") else t("nvr.diag.absent"))
            Row2(t("nvr.diag.firmware"), TitanIcons.hdd, divider = true, descContent = {
                val fw = d.npuFirmware.orEmpty()
                if (fw.isEmpty()) Sub(t("nvr.diag.firmwareNone")) else for (line in fw) Sub(line, mono = true)
            })
            Row2(t("nvr.diag.devices"), TitanIcons.grid, divider = true, descContent = {
                for (dev in devices) Sub(deviceName(dev.kind, devices) + ": " + if (dev.available) t("nvr.diag.usable") else deviceReason(dev.reason))
                for (gpu in decoders) Sub(decoderName(gpu.id, decoders) + ": " + if (gpu.available) t("nvr.diag.usable") else deviceReason(gpu.reason?.takeIf { it in setOf("driver", "vm") }))
                if (d.decoder == null) Sub(if (d.hardware.videoDecode) t("nvr.diag.decodeYes") else t("nvr.diag.decodeNo"))
                Sub(if (d.hardware.enrichmentsPossible) t("nvr.diag.enrichYes") else t("nvr.setup.enrichNo"))
            })
        }
        Group(t("nvr.menu.cameras")) {
            val cams = d.cameras.orEmpty()
            if (cams.isEmpty()) Row2(t("nvr.live.noneTitle"))
            cams.forEachIndexed { i, c ->
                Row2(
                    c.name, NvrIcons.camera,
                    t("nvr.diag.cameraFps", "fps" to fps(c.fps), "detect" to fps(c.detectionFps), "process" to fps(c.processFps), "skipped" to fps(c.skippedFps)),
                    divider = i > 0,
                    summary = when {
                        !c.known -> t("nvr.camera.state.unknown")
                        c.online -> t("nvr.camera.state.online")
                        c.offlineSince != null -> t("nvr.camera.offlineSince", "time" to whenText(c.offlineSince))
                        else -> t("nvr.camera.state.offline")
                    },
                )
            }
        }
        Group(t("nvr.diag.container")) {
            val state = d.container.state.takeIf { it in setOf("running", "exited", "missing") } ?: "other"
            Row2(
                t("nvr.diag.containerState"), TitanIcons.box,
                t("nvr.diag.containerTimes", "started" to whenText(d.container.startedAt), "finished" to whenText(d.container.finishedAt), "ends" to d.container.recentEnds),
                summary = t("nvr.diag.containerName.$state") + if (d.container.state == "exited") " (${d.container.exitCode})" else "",
            )
            Row2(t("nvr.diag.image"), TitanIcons.archive, divider = true, descContent = {
                Sub(t("nvr.diag.imageVersion", "version" to d.imageVersion) + " · " + (if (d.imagePresent) t("nvr.diag.imagePresent") else t("nvr.diag.imageAbsent")) +
                    if (d.runningVersion.isNotEmpty()) " · " + t("nvr.diag.runningVersion", "version" to d.runningVersion) else "")
                Sub(d.imageReference, mono = true)
            })
            Row2(
                t("nvr.diag.shm"), TitanIcons.hdd, t("nvr.diag.shmHint"), divider = true,
                summary = if (d.shm.totalMB == null || d.shm.usedMB == null) t("nvr.diag.unknown") else t("nvr.diag.shmUsed", "used" to Math.round(d.shm.usedMB), "total" to Math.round(d.shm.totalMB)),
            )
        }
        val log = d.log.orEmpty()
        Group(t("nvr.diag.log"), t(if (d.logSource == "frigate") "nvr.diag.logFrigate" else "nvr.diag.logContainer"), tools = {
            TitanButton(if (copied == "log") t("nvr.diag.copied") else t("nvr.diag.copyLog"), { clipboard.setText(AnnotatedString(log.joinToString("\n"))); copied = "log" }, enabled = log.isNotEmpty(), icon = NvrIcons.copy)
        }) {
            if (log.isEmpty()) {
                Row2(t("nvr.diag.logEmpty"))
            } else {
                // Das Protokoll scrollt in beide Richtungen; nichts wird abgeschnitten.
                val label = t("nvr.diag.log")
                SelectionContainer {
                    Text(
                        log.joinToString("\n"),
                        style = TitanType.small.copy(fontFamily = TitanFonts.mono),
                        color = Titan.colors.text,
                        softWrap = false,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(8.dp)).background(Titan.colors.bg)
                            .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}
