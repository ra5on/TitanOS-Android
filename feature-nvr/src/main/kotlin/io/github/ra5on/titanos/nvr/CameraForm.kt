package io.github.ra5on.titanos.nvr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Was der Prüfung und Suche des Formulars vorliegt (für Bildtests vorgebbar). */
data class FormExtras(
    val recipients: RecipientsAnswer? = null,
    val mainOff: Boolean = false,
    val haConnections: List<HAConnection>? = null,
    val haEntities: Map<String, List<HAEntity>> = emptyMap(),
)

@Composable
private fun OnOff(checked: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Toggle(
        checked,
        if (enabled) onChange else null,
        androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.compute.R.string.compute_on) + ", " + label,
        androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.compute.R.string.compute_off) + ", " + label,
        enabled,
    )
}

/**
 * Eine Kamera als Zeilen (CameraForm.svelte): wie sie erreicht wird, welche
 * Ströme wofür, was sie sucht, was sie aufnimmt, Ton und Bewegung, Bereiche
 * im Bild und wem sie es meldet. Dieselben Zeilen fügen hinzu und ändern.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraForm(
    initial: Camera?,
    hardware: NvrHardware?,
    objects: List<String>,
    taken: List<String>,
    me: String,
    api: NvrApi?,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    extras: FormExtras = FormExtras(),
) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val adding = initial == null
    var d by remember(initial?.id) { mutableStateOf(initial?.let(CameraDraft::of) ?: CameraDraft()) }
    var askPublic by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Discovered>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<Throwable?>(null) }
    var probe by remember { mutableStateOf<ProbeResult?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<Throwable?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var nameError by remember { mutableStateOf(false) }
    var widenedFrom by remember { mutableStateOf("") }

    val onvif = d.kind == "onvif"
    val nameTaken = d.name.isNotBlank() && taken.any { it.trim().equals(d.name.trim(), ignoreCase = true) }
    val zonesValid = d.zones.withIndex().all { (i, z) -> z.name.isNotBlank() && d.zones.withIndex().none { (j, o) -> j != i && o.name.trim().equals(z.name.trim(), true) } }
    val ready = d.name.isNotBlank() && d.mainURL.isNotBlank() && !nameTaken && zonesValid
    val choosable = if (onvif && probe?.outcome == "ok") probe!!.streamList.filter { it.outcome == "ok" } else emptyList()
    // Ohne zweiten Strom nimmt nichts den niedrigaufgelösten (das NAS würde ablehnen).
    if (!d.hasSub && (d.streamRecord != "main" || d.streamLive == "sub")) d = d.copy(streamRecord = "main", streamLive = if (d.streamLive == "sub") "auto" else d.streamLive)

    fun knownOutcome(o: String) = if (o in setOf("ok", "unauthorized", "locked", "unreachable", "not_found", "no_stream")) o else "failed"

    val shrinkTitle = t("nvr.shrink.title")
    val shrinkConfirm = t("nvr.shrink.confirm")
    val shrinkGeneric = t("nvr.shrink.generic")
    val shrinkWhen = t("nvr.shrink.when")
    val shrinkMotion = t("nvr.shrink.motion")
    val shrinkEvents = t("nvr.shrink.events")

    fun send(body: JsonObject) {
        if (api == null) return
        saving = true
        error = null
        ui.run(onError = { e ->
            saving = false
            if (e is ApiException && e.code == "nvr_recordings_shrink" && !body.containsKey("confirmShrink")) {
                val next = d.record()
                val lines = shrinks(initial?.record, next).map { what ->
                    when (what) {
                        "days" -> context.tx("nvr.shrink.days", "days" to recordDaysOf(next).toString())
                        "eventDays" -> context.tx("nvr.shrink.eventDays", "days" to eventDaysOf(next).toString())
                        "motion" -> shrinkMotion
                        else -> shrinkEvents
                    }
                }.ifEmpty { listOf(shrinkGeneric) } + shrinkWhen
                ui.confirm(Ask(shrinkTitle, lines.joinToString("\n"), shrinkConfirm) { send(JsonObject(body + ("confirmShrink" to kotlinx.serialization.json.JsonPrimitive(true)))) })
                return@run
            }
            if (e is ApiException && e.code == "nvr_target_public") askPublic = true
            if (e is ApiException && e.code == "nvr_camera_name_exists") nameError = true
            error = e
        }) {
            if (adding) api.addCamera(body) else api.changeCamera(initial!!.id, body)
            saving = false
            onSaved()
        }
    }

    ScrollPage {
        if (adding) {
            Text(t("nvr.add.title"), style = TitanType.title, color = Titan.colors.text, modifier = Modifier.padding(top = 8.dp))
            Sub(t("nvr.add.intro"))
            Group(t("nvr.add.find")) {
                Row2(t("nvr.add.search"), TitanIcons.search, t("nvr.add.searchHint")) {
                    TitanButton(if (searching) t("nvr.add.searching") else t("nvr.add.searchNow"), {
                        if (api == null) return@TitanButton
                        searching = true
                        searchError = null
                        ui.run(onError = { searching = false; searchError = it }) {
                            found = api.discover()
                            searching = false
                        }
                    }, enabled = !searching)
                }
                searchError?.let { Column(Modifier.padding(14.dp)) { Problem(errorText(it)) } }
                found?.let { list ->
                    if (list.isEmpty()) Row2(t("nvr.add.none"), divider = true)
                    for (hit in list) {
                        val picked = onvif && d.host == hit.address
                        Row2(
                            hit.name.ifEmpty { hit.hardware.ifEmpty { hit.address } },
                            desc = hit.address + if (hit.hardware.isNotEmpty() && hit.name.isNotEmpty()) " · ${hit.hardware}" else "",
                            divider = true,
                            summary = if (hit.known) t("nvr.add.known") else null,
                            onClick = {
                                d = d.copy(kind = "onvif", host = hit.address, onvifPort = hit.onvifPort.takeIf { it > 0 } ?: 80, name = d.name.ifEmpty { hit.name.ifEmpty { hit.hardware } }, mainURL = "", subURL = "")
                                probe = null
                            },
                        ) { if (picked) StateMark(t("nvr.probe.connected"), StateKind.Ok) }
                    }
                }
            }
        }

        Group(t("nvr.camera.connection")) {
            Row2(t("nvr.camera.kind"), TitanIcons.globe, t("nvr.camera.kindHint")) {
                Segments(listOf("onvif" to t("nvr.camera.kindOnvif"), "rtsp" to t("nvr.camera.kindRtsp")), d.kind, t("nvr.camera.kind"), {
                    probe = null
                    d = d.copy(kind = it, ptz = if (it == "rtsp") false else d.ptz)
                })
            }
            if (onvif) {
                Row2(t("nvr.camera.host"), NvrIcons.camera, t("nvr.camera.hostHint"), divider = true) {
                    Box(Modifier.widthIn(min = 180.dp, max = 320.dp)) { TextInput(d.host, { d = d.copy(host = it.trim()); probe = null }, t("nvr.camera.host"), placeholder = "192.168.1.60") }
                    NumberField(t("nvr.camera.port"), d.onvifPort, { d = d.copy(onvifPort = it) }, unit = t("nvr.camera.port"), range = 1..65535)
                }
            } else {
                Row2(t("nvr.camera.mainUrl"), TitanIcons.video, t("nvr.camera.mainUrlHint"), divider = true) {
                    TextInput(d.mainURL, { d = d.copy(mainURL = it); probe = null }, t("nvr.camera.mainUrl"), placeholder = "rtsp://192.168.1.60:554/stream1")
                }
                Row2(t("nvr.camera.subUrl"), TitanIcons.video, t("nvr.camera.subUrlHint"), divider = true) {
                    TextInput(d.subURL, { d = d.copy(subURL = it) }, t("nvr.camera.subUrl"), placeholder = "rtsp://192.168.1.60:554/stream2")
                }
            }
            Row2(t("nvr.camera.login"), TitanIcons.key, t("nvr.camera.loginHint"), divider = true) {
                Box(Modifier.widthIn(min = 160.dp, max = 260.dp)) { TextInput(d.user, { d = d.copy(user = it) }, t("nvr.camera.user"), placeholder = t("nvr.camera.user")) }
                Box(Modifier.widthIn(min = 160.dp, max = 260.dp)) {
                    TextInput(
                        d.password, { d = d.copy(password = it) }, t("nvr.camera.password"),
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = if (initial?.source?.hasPassword == true) t("nvr.camera.passwordKeep") else t("nvr.camera.password"),
                    )
                }
            }
            if (askPublic) Row2(t("nvr.camera.public"), TitanIcons.shield, t("nvr.camera.publicHint"), divider = true) {
                OnOff(d.allowPublic, t("nvr.camera.public")) { d = d.copy(allowPublic = it) }
            }
            Row2(t("nvr.probe.title"), TitanIcons.image, divider = true, descContent = {
                val p = probe
                when {
                    probing -> Sub(t("nvr.probe.running"))
                    probeError != null -> Sub(errorText(probeError!!), danger = true)
                    p != null -> {
                        Sub(t("nvr.probe.${knownOutcome(p.outcome)}"), danger = p.outcome != "ok")
                        if (p.outcome == "ok") {
                            p.device?.let { dev -> listOf(dev.manufacturer, dev.model).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }?.let { Sub(it) } }
                            for (s in p.streamList) {
                                val role = if (s.role == "other") s.profile ?: t("nvr.stream.other") else t(if (s.role == "main") "nvr.probe.main" else "nvr.probe.sub")
                                val detail = if (s.outcome == "ok") listOf(if (s.width > 0 && s.height > 0) "${s.width} × ${s.height}" else "", s.codec, if (s.audio.isNotEmpty()) t("nvr.probe.withSound") else t("nvr.probe.noSound")).filter { it.isNotEmpty() }.joinToString(" · ")
                                else t("nvr.probe.${knownOutcome(s.outcome)}")
                                Sub("$role: $detail")
                            }
                            if (onvif) Sub(if (p.ptz) t("nvr.probe.ptzYes") else t("nvr.probe.ptzNo"))
                            if (d.streamDetect == "sub" && d.hasSub && p.streamList.any { it.url == d.subURL && it.outcome == "ok" }) Sub(t("nvr.probe.subUsed"))
                        }
                    }
                    else -> Sub(t("nvr.probe.hint"))
                }
            }) {
                if (probe?.outcome == "ok") StateMark(t("nvr.probe.connected"), StateKind.Ok)
                TitanButton(t("nvr.probe.check"), {
                    if (api == null) return@TitanButton
                    probing = true
                    probeError = null
                    probe = null
                    ui.run(onError = { e ->
                        probing = false
                        if (e is ApiException && e.code == "nvr_target_public") askPublic = true
                        probeError = e
                    }) {
                        val res = api.probe(buildJsonObject {
                            put("kind", d.kind)
                            put("host", d.host.trim())
                            put("onvifPort", if (onvif) d.onvifPort else 0)
                            put("mainURL", if (!onvif) d.mainURL.trim() else "")
                            put("subURL", if (!onvif) d.subURL.trim() else "")
                            put("user", d.user.trim())
                            put("password", d.password)
                            put("camera", initial?.id ?: "")
                            put("allowPublic", d.allowPublic)
                        })
                        probe = res
                        probing = false
                        if (res.outcome == "ok") {
                            if (onvif) d = d.copy(mainURL = res.mainURL, subURL = res.subURL, onvifPort = if (res.onvifPort > 0) res.onvifPort else d.onvifPort, ptz = if (adding) res.ptz else d.ptz)
                            if (!res.ptz) d = d.copy(ptz = false)
                        }
                    }
                }, enabled = !probing && (if (onvif) d.host.isNotBlank() else d.mainURL.isNotBlank()))
            }
            Row2(t("nvr.camera.name"), TitanIcons.edit, divider = true, descContent = { if (nameTaken || nameError) Sub(t("nvr.camera.nameExists"), danger = true) }) {
                TextInput(d.name, { d = d.copy(name = it.take(60)); nameError = false }, t("nvr.camera.name"))
            }
        }

        Group(t("nvr.stream.group")) {
            var first = true
            fun div() = (!first).also { first = false }
            fun sizeText(w: Int, h: Int, unknown: String) = if (w > 0 && h > 0) "$w × $h" else unknown
            val unknown = t("nvr.stream.unknownSize")
            if (choosable.isNotEmpty()) {
                fun choiceText(s: ProbeStream): String {
                    val label = listOf(sizeText(s.width, s.height, unknown), s.codec).filter { it.isNotEmpty() }.joinToString(" · ")
                    val alike = choosable.filter { it.width == s.width && it.height == s.height && it.codec == s.codec }
                    return if (alike.size > 1) "$label · ${s.profile ?: (alike.indexOf(s) + 1)}" else label
                }
                Row2(t("nvr.stream.high"), TitanIcons.video, t("nvr.stream.highHint"), divider = div()) {
                    Choice(t("nvr.stream.high"), choosable.map { it.url to choiceText(it) }, d.mainURL, { url -> d = d.copy(mainURL = url, subURL = if (d.subURL == url) "" else d.subURL) })
                }
                Row2(t("nvr.stream.low"), TitanIcons.video, t("nvr.stream.lowHint"), divider = div()) {
                    Choice(t("nvr.stream.low"), listOf("" to t("nvr.stream.none")) + choosable.filter { it.url != d.mainURL }.map { it.url to choiceText(it) }, d.subURL, { d = d.copy(subURL = it) })
                }
            } else {
                val src = initial?.source
                if (src != null && onvif && src.mainURL == d.mainURL) {
                    val high = sizeText(src.mainWidth, src.mainHeight, unknown)
                    val text = if (src.subURL.isNotEmpty() && src.subURL == d.subURL) t("nvr.stream.currentBoth", "high" to high, "low" to sizeText(src.subWidth, src.subHeight, unknown)) else t("nvr.stream.currentHigh", "high" to high)
                    Row2(t("nvr.stream.current"), TitanIcons.video, "$text. ${t("nvr.stream.currentHint")}", divider = div())
                }
            }
            val single = t("nvr.stream.single")
            Row2(t("nvr.stream.record"), TitanIcons.archive, if (d.hasSub) t("nvr.stream.recordHint") else single, divider = div()) {
                Segments(listOf("main" to t("nvr.stream.highShort"), "sub" to t("nvr.stream.lowShort")), d.streamRecord, t("nvr.stream.record"), { d = d.copy(streamRecord = it) }, enabled = d.hasSub)
            }
            Row2(t("nvr.stream.detect"), NvrIcons.eye, if (!d.hasSub) single else if (d.streamDetect == "main") t("nvr.stream.detectHighHint") else t("nvr.stream.detectHint"), divider = div()) {
                Segments(listOf("sub" to t("nvr.stream.lowShort"), "main" to t("nvr.stream.highShort")), d.streamDetect, t("nvr.stream.detect"), { d = d.copy(streamDetect = it) }, enabled = d.hasSub)
            }
            Row2(t("nvr.stream.live"), TitanIcons.image, if (d.hasSub) t("nvr.stream.liveHint") else single, divider = div()) {
                Segments(listOf("auto" to t("nvr.stream.auto"), "main" to t("nvr.stream.highShort"), "sub" to t("nvr.stream.lowShort")), d.streamLive, t("nvr.stream.live"), { d = d.copy(streamLive = it) }, enabled = d.hasSub)
            }
            Row2(t("nvr.stream.keepOpen"), TitanIcons.play, if (d.keepOpen) t("nvr.stream.keepOpenHint") else t("nvr.stream.keepOpenOffHint"), divider = div()) {
                OnOff(d.keepOpen, t("nvr.stream.keepOpen")) { d = d.copy(keepOpen = it) }
            }
        }

        Group(t("nvr.camera.detection")) {
            val enrich = hardware?.enrichmentsPossible != false
            val why = if (enrich) null else t(when (hardware?.enrichmentsReason) { "memory" -> "nvr.camera.enrichMemory"; "avx2" -> "nvr.camera.enrichAvx"; else -> "nvr.camera.enrichNo" })
            Row2(t("nvr.detect.title"), NvrIcons.eye, t("nvr.detect.hint") + if (why != null) " $why" else "") {
                val chosen = if (d.detectOn) groupsOf(d.objects) + listOfNotNull(if (d.plates) "plates" else null, if (d.faces) "faces" else null) else emptyList()
                Pills(
                    OBJECT_GROUP_IDS.map { Pill(it, t("nvr.detect.$it")) } + listOf(
                        Pill("plates", t("nvr.detect.plates"), enrich || d.plates, why),
                        Pill("faces", t("nvr.detect.faces"), enrich || d.faces, why),
                    ),
                    chosen,
                    t("nvr.detect.title"),
                    { value, on ->
                        var n = d
                        when (value) {
                            "plates" -> n = n.copy(plates = on)
                            "faces" -> n = n.copy(faces = on)
                            else -> n = n.copy(objects = toggleGroup(if (n.detectOn) n.objects else emptyList(), value, on, objects))
                        }
                        if (on && value == "plates" && "vehicles" !in groupsOf(n.objects)) n = n.copy(objects = toggleGroup(n.objects, "vehicles", true, objects))
                        if (on && value == "faces" && "person" !in n.objects) n = n.copy(objects = toggleGroup(n.objects, "persons", true, objects))
                        n = n.copy(detectOn = n.objects.isNotEmpty())
                        if (!n.detectOn) n = n.copy(plates = false, faces = false, mode = if (n.mode == "events") "motion" else n.mode)
                        d = n
                    },
                )
            }
            Row2(t("nvr.detect.single"), desc = t("nvr.detect.singleHint"), divider = true) {
                Pills(objects.map { Pill(it, labelName(it)) }, if (d.detectOn) d.objects else emptyList(), t("nvr.detect.single"), { o, on ->
                    val next = if (on) d.objects + o else d.objects - o
                    var n = d.copy(objects = if (objects.isNotEmpty()) objects.filter { it in next } else next)
                    n = n.copy(detectOn = n.objects.isNotEmpty())
                    if (!n.detectOn) n = n.copy(plates = false, faces = false, mode = if (n.mode == "events") "motion" else n.mode)
                    d = n
                })
            }
        }

        Group(t("nvr.camera.recording")) {
            Row2(t("nvr.record.title"), TitanIcons.video, t("nvr.record.hint.${d.mode}")) {
                Choice(
                    t("nvr.record.title"),
                    listOfNotNull(if (d.detectOn) "events" to t("nvr.record.events") else null, "motion" to t("nvr.record.motion"), "continuous" to t("nvr.record.continuous"), "off" to t("nvr.record.off")),
                    d.mode,
                    { d = d.copy(mode = it) },
                )
            }
            if (d.mode != "off") {
                if (d.mode != "events") Row2(t("nvr.record.recordDays"), NvrIcons.clock, t(if (d.mode == "continuous") "nvr.record.recordDaysHint" else "nvr.record.recordDaysMotionHint"), divider = true) {
                    NumberField(t("nvr.record.recordDays"), d.recordDays, { d = d.copy(recordDays = it) }, t("nvr.record.days"), 1..365)
                }
                Row2(t("nvr.record.eventDays"), NvrIcons.clock, t(if (d.mode == "events") "nvr.record.eventDaysOnlyHint" else "nvr.record.eventDaysHint"), divider = true) {
                    NumberField(t("nvr.record.eventDays"), d.eventDays, { d = d.copy(eventDays = it) }, t("nvr.record.days"), 1..365)
                }
                if (d.mode != "continuous") Row2(t("nvr.record.around"), NvrIcons.clock, if (d.mode == "events") "${t("nvr.record.aroundHint")} ${t("nvr.record.aroundEvents")}" else t("nvr.record.aroundHint"), divider = true) {
                    Text(t("nvr.record.before"), style = TitanType.body, color = Titan.colors.text)
                    NumberField(t("nvr.record.before"), d.preSeconds, { d = d.copy(preSeconds = it) }, range = 0..60)
                    Text(t("nvr.record.after"), style = TitanType.body, color = Titan.colors.text)
                    NumberField(t("nvr.record.after"), d.postSeconds, { d = d.copy(postSeconds = it) }, range = 0..60)
                }
            }
        }

        Group(t("nvr.camera.soundMove")) {
            val probedSound = if (probe?.outcome == "ok") probe!!.streamList.any { it.audio.isNotEmpty() } else null
            Row2(t("nvr.audio.title"), NvrIcons.volume, if (probedSound == false) t("nvr.audio.none") else t("nvr.audio.hint")) {
                OnOff(d.audio, t("nvr.audio.title")) { d = d.copy(audio = it, twoWay = if (!it) false else d.twoWay) }
            }
            Row2(t("nvr.audio.twoWay"), TitanIcons.mic, if (d.audio) t("nvr.audio.twoWayHint") else t("nvr.audio.twoWayNeedsSound"), divider = true) {
                OnOff(d.twoWay, t("nvr.audio.twoWay"), enabled = d.audio) { d = d.copy(twoWay = it) }
            }
            val ptzBlocked = !onvif || (probe?.outcome == "ok" && probe?.ptz == false)
            Row2(t("nvr.ptz.title"), TitanIcons.move, if (!onvif) t("nvr.ptz.needsOnvif") else if (probe?.outcome == "ok" && probe?.ptz == false) t("nvr.ptz.notFound") else t("nvr.ptz.hint"), divider = true) {
                OnOff(d.ptz, t("nvr.ptz.title"), enabled = !ptzBlocked) { d = d.copy(ptz = it) }
            }
        }

        Group(t("nvr.areas.group"), t("nvr.areas.hint")) {
            if (adding) {
                Row2(t("nvr.areas.newCamera"))
            } else {
                Row2(t("nvr.areas.only"), TitanIcons.grid, if (d.zones.isNotEmpty()) t("nvr.areas.onlyHint") else t("nvr.areas.onlyNeedsZone")) {
                    OnOff(d.zonesOnly, t("nvr.areas.only"), enabled = d.zones.isNotEmpty()) { d = d.copy(zonesOnly = it) }
                }
                ZoneEditor(
                    initial!!.id,
                    d.zones,
                    d.masks,
                    onZones = { z -> d = d.copy(zones = z, zonesOnly = d.zonesOnly && z.isNotEmpty()) },
                    onMasks = { m -> d = d.copy(masks = m) },
                    onZoneGone = { id, name ->
                        val before = d.notify.zones.orEmpty()
                        val after = before - id
                        d = d.copy(notify = d.notify.copy(zones = after))
                        if (id in before && after.isEmpty()) widenedFrom = name
                    },
                )
                if (widenedFrom.isNotEmpty() && d.notify.zones.isNullOrEmpty()) Column(Modifier.padding(14.dp)) { Problem(t("nvr.areas.messagesWidened", "zone" to widenedFrom)) }
            }
        }

        NotifyGroup(
            notify = d.notify,
            onNotify = { d = d.copy(notify = it) },
            camera = initial?.id,
            objects = objects,
            zones = d.zones.filter { it.id != null && it.name.isNotBlank() },
            faces = d.detectOn && d.faces,
            me = me,
            api = api,
            extras = extras,
        )

        error?.let { Problem(errorText(it, detail = true), Modifier.padding(top = 12.dp)) }
        if (d.mainURL.isBlank() && onvif) Sub(t("nvr.camera.checkFirst"))
        FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TitanButton(t("common.cancel"), onCancel, enabled = !saving)
            TitanButton(if (adding) t("nvr.add.submit") else t("common.save"), { send(d.body(adding)) }, kind = ButtonKind.Primary, enabled = !saving && ready, icon = if (adding) TitanIcons.plus else TitanIcons.check)
        }
    }
}

/**
 * Wem eine Kamera was meldet (NotifyGroup.svelte): Arten, Konten mit ihren
 * eigenen Wegen (Glocke, Push, E-Mail), Home Assistant und KI-Agent, Pause,
 * Zeitfenster und die Bedingung in Home Assistant.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotifyGroup(
    notify: CameraNotify,
    onNotify: (CameraNotify) -> Unit,
    camera: String?,
    objects: List<String>,
    zones: List<CameraZone>,
    faces: Boolean,
    me: String,
    api: NvrApi?,
    extras: FormExtras,
) {
    val legacy = remember { notify.recipients == null && camera != null }
    val legacyEmail = remember { legacy && notify.email }
    var accounts by remember { mutableStateOf(extras.recipients?.accounts) }
    var mailServer by remember { mutableStateOf(extras.recipients?.mailServer ?: true) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var mainOff by remember { mutableStateOf(extras.mainOff) }
    val current by androidx.compose.runtime.rememberUpdatedState(notify)
    LaunchedEffect(Unit) {
        if (camera == null && current.recipients == null && me.isNotEmpty()) onNotify(current.copy(recipients = listOf(NotifyRecipient(me, bell = true, push = true))))
        if (api != null) {
            try {
                val answer = api.recipients()
                accounts = answer.accounts.orEmpty()
                mailServer = answer.mailServer
                if (current.recipients == null) onNotify(current.copy(recipients = legacyRecipients(current, answer.accounts.orEmpty())))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e
            }
            mainOff = runCatching { api.settings().notifyOff == true }.getOrDefault(false)
        }
    }
    val list = notify.recipients.orEmpty()
    val acc = accounts
    val choosable = acc.orEmpty().filter { eligible(it, camera) }
    val stale = if (acc != null) list.mapNotNull { e -> staleReason(e.user, acc, camera).takeIf { it.isNotEmpty() }?.let { e to it } } else emptyList()
    val summary = when (list.size) { 0 -> t("nvr.notify.nobody"); 1 -> t("nvr.notify.recipientsOne"); else -> t("nvr.notify.recipientsCount", "count" to list.size) }

    Group(t("nvr.camera.notifications")) {
        if (mainOff) Column(Modifier.padding(14.dp)) { Problem(t("nvr.notify.mainOff")) }
        Row2(t("nvr.notify.kinds"), TitanIcons.info, t("nvr.notify.kindsHint")) {
            val chosen = kindPillsOf(notify.kinds.orEmpty()) + if (notify.faceUnknown) listOf("faceUnknown") else emptyList()
            Pills(
                KIND_PILLS.map { Pill(it, t("nvr.notify.kind.$it")) } + Pill("faceUnknown", t("nvr.notify.kind.faceUnknown"), faces || notify.faceUnknown, if (faces) null else t("nvr.notify.faceUnknownNeedsFaces")),
                chosen,
                t("nvr.notify.kinds"),
                { pill, on ->
                    if (pill == "faceUnknown") {
                        onNotify(notify.copy(faceUnknown = on))
                    } else {
                        val members = kindMembers(pill)
                        val rest = notify.kinds.orEmpty().filter { it !in members }
                        val add = members.filter { it in setOf("plate", "face", "offline") || objects.isEmpty() || it in objects }
                        onNotify(notify.copy(kinds = if (on) rest + add else rest))
                    }
                },
            )
        }
        if (zones.isNotEmpty()) {
            val chosenZones = notify.zones.orEmpty()
            Row2(t("nvr.notify.zones"), TitanIcons.grid, if (chosenZones.isNotEmpty()) t("nvr.notify.zonesHint") else "${t("nvr.notify.zonesHint")} ${t("nvr.notify.zonesWhole")}", divider = true) {
                Pills(zones.map { Pill(it.id!!, it.name) }, chosenZones, t("nvr.notify.zones"), { z, on -> onNotify(notify.copy(zones = if (on) chosenZones + z else chosenZones - z)) })
            }
        }
        Row2(t("nvr.notify.recipients"), TitanIcons.user, if (camera != null) t("nvr.notify.recipientsHint") else t("nvr.notify.recipientsNewHint"), divider = true, summary = summary, descContent = {
            if (legacy) {
                Sub(t("nvr.notify.legacy"))
                if (legacyEmail) Sub(t("nvr.notify.legacyEmail"))
            }
            when {
                loadError != null -> Sub(errorText(loadError!!), danger = true)
                acc == null -> Sub(t("nvr.notify.loading"))
            }
        })
        for (a in choosable) {
            val entry = list.firstOrNull { it.user == a.name }
            val hints = buildList {
                if (a.pushDevices == 0) add(t_(if (a.pushDevicesOff > 0) "nvr.notify.devicesOff" else "nvr.notify.noDevice"))
                if (!mailServer) add(t_("nvr.notify.noMailServer")) else if (!a.email) add(t_("nvr.notify.noAddress"))
                if (missesNames(a, camera, notify.kinds.orEmpty(), notify.faceUnknown)) add(t_("nvr.notify.noNames"))
            }
            Row2(
                if (a.name == me) t("nvr.notify.you", "name" to a.name) else a.name,
                desc = if (a.admin) t("nvr.notify.admin") else t("nvr.notify.userNoBell"),
                divider = true,
                descContent = { for (h in hints) Sub(h, warn = true) },
            ) {
                Pills(waysFor(a.admin).map { Pill(it, t("nvr.notify.way.$it")) }, waysOf(entry), t("nvr.notify.waysFor", "name" to a.name), { way, on ->
                    onNotify(notify.copy(recipients = toggleWay(list, a.name, way, on, a.admin)))
                })
            }
        }
        for ((entry, why) in stale) {
            Row2(entry.user, divider = true, descContent = { Sub(t("nvr.notify.stale.$why"), warn = true) }) {
                TitanButton(t("nvr.notify.remove"), { onNotify(notify.copy(recipients = list.filter { it.user != entry.user })) })
            }
        }
        Row2(t("nvr.notify.homeAssistant"), TitanIcons.home, t("nvr.notify.homeAssistantHint"), divider = true) {
            OnOff(notify.homeAssistant, t("nvr.notify.homeAssistant")) { onNotify(notify.copy(homeAssistant = it)) }
        }
        Row2(t("nvr.notify.agent"), TitanIcons.sparkle, t("nvr.notify.agentHint"), divider = true) {
            OnOff(notify.agent, t("nvr.notify.agent")) { onNotify(notify.copy(agent = it)) }
        }
        Row2(t("nvr.notify.pauseTitle"), NvrIcons.clock, t("nvr.notify.pauseHint"), divider = true) {
            Choice(t("nvr.notify.pauseTitle"), PAUSE_CHOICES.map { it.toString() to t("nvr.notify.pause.$it") }, (notify.pauseSeconds ?: DEFAULT_PAUSE).toString(), { onNotify(notify.copy(pauseSeconds = it.toInt())) })
        }
        val win = notify.window ?: NotifyWindow()
        Row2(t("nvr.notify.window"), NvrIcons.clock, t("nvr.notify.windowHint"), divider = true) {
            OnOff(win.on, t("nvr.notify.window")) { onNotify(notify.copy(window = windowOn(notify.window, it))) }
        }
        if (win.on) {
            val days = win.days.orEmpty()
            Row2(t("nvr.notify.days"), TitanIcons.cal, t("nvr.notify.daysHint"), divider = true) {
                Pills(WEEK_DAYS.map { Pill(it, t("nvr.notify.day.$it"), !(days.size == 1 && days[0] == it)) }, days, t("nvr.notify.days"), { day, on ->
                    val next = if (on) days + day else days - day
                    onNotify(notify.copy(window = win.copy(days = WEEK_DAYS.filter { it in next })))
                })
            }
            Row2(t("nvr.notify.time"), NvrIcons.clock, t("nvr.notify.timeHint"), divider = true) {
                TimeField(t("nvr.notify.from"), win.from) { onNotify(notify.copy(window = win.copy(from = it))) }
                TimeField(t("nvr.notify.to"), win.to) { onNotify(notify.copy(window = win.copy(to = it))) }
            }
        }
        HAConditionRows(notify, onNotify, api, extras)
    }
}

@Composable
private fun t_(key: String) = t(key)

/** Eine Uhrzeit HH:MM; übernommen, sobald sie gültig ist. */
@Composable
fun TimeField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.widthIn(min = 120.dp, max = 160.dp)) {
        Text(label, style = TitanType.small, color = Titan.colors.muted)
        TextInput(text, { s ->
            text = s.take(5)
            if (Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(text)) onChange(text)
        }, label, placeholder = "22:00")
    }
}

/**
 * Meldungen nur, solange Home Assistant es sagt (HAConditionRows.svelte):
 * Schalter, Verknüpfung, je Bedingung Verbindung, Entität und Zustände.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HAConditionRows(notify: CameraNotify, onNotify: (CameraNotify) -> Unit, api: NvrApi?, extras: FormExtras) {
    var connections by remember { mutableStateOf(extras.haConnections) }
    var entities by remember { mutableStateOf(extras.haEntities) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf(mapOf<Int, String>()) }
    var custom by remember { mutableStateOf(mapOf<Int, String>()) }
    var choosing by remember { mutableStateOf(mapOf<Int, Boolean>()) }
    val context = LocalContext.current
    val rules = notify.ha?.rules.orEmpty()
    val on = notify.ha.active()
    val firstConnection = connections?.firstOrNull()?.id ?: ""
    suspend fun load(connection: String) {
        if (api == null || entities.containsKey(connection)) return
        try {
            entities = entities + (connection to api.haEntities(connection))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = context.tx("nvr.ha.loadFailed", "error" to context.errorText(e))
        }
    }
    LaunchedEffect(Unit) {
        if (api != null) {
            connections = runCatching { api.haConnections() }.getOrDefault(emptyList())
            if (connections!!.isNotEmpty()) for (c in setOf(firstConnection) + rules.map { it.connection }) load(c)
        }
    }
    LaunchedEffect(rules.map { it.connection }) { for (c in rules.map { it.connection }.toSet()) load(c) }
    fun entitiesOf(connection: String) = entities[connection].orEmpty()
    fun setRules(next: List<HARule>) = onNotify(notify.copy(ha = HACondition(notify.ha?.mode ?: "all", next)))
    fun edit(i: Int, change: (HARule) -> HARule) = setRules(rules.mapIndexed { j, r -> if (j == i) change(r) else r })
    val stateLabel = @Composable { s: String -> io.github.ra5on.titanos.compute.tOr("nvr.ha.state.$s", s) }

    Row2(t("nvr.ha.title"), TitanIcons.home, divider = true, descContent = {
        Sub(t("nvr.ha.hint"))
        Sub(t("nvr.ha.unknownHint"))
        if (connections?.isEmpty() == true) Sub(t("nvr.ha.none"), warn = true)
    }) {
        OnOff(on, t("nvr.ha.title"), enabled = on || !connections.isNullOrEmpty()) {
            onNotify(notify.copy(ha = if (it) HACondition("all", kotlin.collections.listOf(newRule(firstConnection, entitiesOf(firstConnection)))) else HACondition("all", emptyList())))
        }
    }
    if (!on) return
    loadError?.let { Column(Modifier.padding(14.dp)) { Problem(it) } }
    if (rules.size > 1) Row2(t("nvr.ha.mode"), desc = t("nvr.ha.modeHint"), divider = true) {
        Choice(t("nvr.ha.mode"), kotlin.collections.listOf("all" to t("nvr.ha.modeAll"), "any" to t("nvr.ha.modeAny")), notify.ha?.mode ?: "all", { onNotify(notify.copy(ha = HACondition(it, rules))) })
    }
    rules.forEachIndexed { i, rule ->
        val current = entitiesOf(rule.connection).firstOrNull { it.id == rule.entity }
        val summary = (current?.name ?: rule.entity).takeIf { it.isNotEmpty() }?.let { n -> "$n: " + rule.states.orEmpty().map { stateLabel(it) }.joinToString(", ") } ?: t("nvr.ha.pick")
        Row2(t("nvr.ha.rule", "n" to i + 1), TitanIcons.home, t("nvr.ha.ruleHint"), divider = true, summary = summary)
        Column(Modifier.fillMaxWidth().padding(start = 46.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val conns = connections.orEmpty()
            if (conns.size > 1) Choice(t("nvr.ha.connection"), conns.map { it.id to it.name.ifEmpty { it.id } }, rule.connection, { c ->
                edit(i) { newRule(c, entitiesOf(c)) }
            })
            Text(t("nvr.ha.entity"), style = TitanType.small, color = Titan.colors.muted)
            if (rule.entity.isNotEmpty()) {
                Text((current?.name ?: rule.entity) + (current?.area?.let { " · $it" } ?: ""), style = TitanType.body, color = Titan.colors.text)
                Sub(rule.entity + (current?.let { " · " + t("nvr.ha.now", "state" to stateLabel(it.state)) } ?: ""), mono = true)
                if (choosing[i] != true) TitanButton(t("nvr.ha.change"), { choosing = choosing + (i to true) })
            }
            if (rule.entity.isEmpty() || choosing[i] == true) {
                TextInput(search[i].orEmpty(), { search = search + (i to it) }, t("nvr.ha.search"), placeholder = t("nvr.ha.search"), leading = TitanIcons.search)
                val shown = entityMatches(entitiesOf(rule.connection), search[i].orEmpty())
                if (shown.isEmpty()) Sub(t("nvr.ha.noMatch"))
                for (e in shown) {
                    val sel = e.id == rule.entity
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) {
                                edit(i) { r -> r.copy(entity = e.id, states = firstStates(e.id, e.state)) }
                                choosing = choosing + (i to false)
                            }
                            .semantics { selected = sel }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(e.name, style = TitanType.body, color = Titan.colors.text)
                        Sub((e.area?.let { "$it · " } ?: "") + e.id)
                    }
                }
            }
            if (rule.entity.isNotEmpty()) {
                val states = rule.states.orEmpty()
                Text(t("nvr.ha.states"), style = TitanType.small, color = Titan.colors.muted)
                if (states.size == 1) Sub(t("nvr.ha.lastState"))
                Pills(stateChoices(rule.entity, states, current?.state).map { Pill(it, stateLabel(it), !(states.size == 1 && states[0] == it)) }, states, t("nvr.ha.states"), { s, chosen ->
                    edit(i) { r -> r.copy(states = if (chosen) states + s else states - s) }
                })
                Text(t("nvr.ha.custom"), style = TitanType.small, color = Titan.colors.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.widthIn(min = 160.dp, max = 260.dp)) { TextInput(custom[i].orEmpty(), { custom = custom + (i to it.take(255)) }, t("nvr.ha.custom")) }
                    TitanButton(t("nvr.ha.customAdd"), {
                        val v = custom[i].orEmpty().trim()
                        if (v.isNotEmpty() && v !in states) edit(i) { r -> r.copy(states = states + v) }
                        custom = custom + (i to "")
                    })
                }
            }
            TitanButton(t("nvr.ha.remove", "n" to i + 1), { setRules(rules.filterIndexed { j, _ -> j != i }) }, icon = TitanIcons.trash)
        }
    }
    if (rules.size < MAX_RULES) Column(Modifier.padding(14.dp)) {
        TitanButton(t("nvr.ha.add"), { setRules(rules + newRule(firstConnection, emptyList())) }, icon = TitanIcons.plus)
    }
}
