package io.github.ra5on.titanos.nvr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Was die Einstellungen neben den Einstellungen selbst brauchen. */
data class SettingsData(
    val settings: NvrSettings,
    val status: NvrStatus?,
    val volumes: List<StorageVolume>,
    val cameras: List<Camera>,
    val faces: Faces?,
    val facesMissing: Boolean = false,
    val recognized: List<RecognizedPlate>? = null,
    val accounts: List<RecipientAccount>? = null,
    val host: String = "",
)

class SettingsActions(
    val save: (JsonObject, String) -> Unit = { _, _ -> },
    val switchOff: () -> Unit = {},
    val diagnosis: () -> Unit = {},
    val editCamera: (String) -> Unit = {},
    val faces: FaceActions = FaceActions(),
    val loadPlates: () -> Unit = {},
)

class FaceActions(
    val add: (String) -> Unit = {},
    val rename: (String, String) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    val addPicture: (String) -> Unit = {},
    val deletePicture: (String, String) -> Unit = { _, _ -> },
    val assign: (String, String) -> Unit = { _, _ -> },
    val discard: (String) -> Unit = {},
)

@Composable
fun SettingsPage(model: NvrModel, nav: NvrNav) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    var settings by remember { mutableStateOf<NvrSettings?>(null) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var volumes by remember { mutableStateOf<List<StorageVolume>>(emptyList()) }
    var faces by remember { mutableStateOf<Faces?>(null) }
    var facesMissing by remember { mutableStateOf(false) }
    var recognized by remember { mutableStateOf<List<RecognizedPlate>?>(null) }
    var accounts by remember { mutableStateOf<List<RecipientAccount>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        try {
            settings = model.api.settings()
            loadError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e
        }
    }
    LaunchedEffect(Unit) {
        volumes = runCatching { model.api.storage().volumes.orEmpty().filter { it.mounted } }.getOrDefault(emptyList())
        accounts = runCatching { model.api.recipients().accounts.orEmpty() }.getOrNull()
        try {
            faces = model.api.faces()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            facesMissing = true
            if (!(e is ApiException && e.status == 404)) error = e
        }
    }
    val sleepTitle = t("nvr.setup.sleepTitle")
    val sleepOk = t("nvr.setup.sleepOk")
    val dockerTitle = t("nvr.setup.dockerTitle")
    val dockerText = t("nvr.setup.dockerText")
    val dockerOn = t("nvr.setup.dockerOn")
    val offTitle = t("nvr.settings.offTitle")
    val offText = t("nvr.settings.offText") + "\n" + t("nvr.settings.offKeeps")
    val offWord = t("nvr.settings.off")
    val faceDeleteText = t("nvr.faces.deleteText")
    val pictureTitle = t("nvr.faces.deletePictureTitle")
    val deleteWord = t("common.delete")

    fun save(body: JsonObject, what: String) {
        busy = true
        error = null
        saved = false
        ui.run(onError = { e ->
            busy = false
            when {
                e is ApiException && e.code == "nvr_volume_sleeps" && !body.containsKey("confirmSleepingVolume") ->
                    ui.confirm(Ask(sleepTitle, context.tx("nvr.setup.sleepText", "volume" to ((body["volume"] as? JsonPrimitive)?.content ?: "")), sleepOk, danger = false) {
                        save(JsonObject(body + ("confirmSleepingVolume" to JsonPrimitive(true))), what)
                    })
                e is ApiException && e.code == "nvr_docker_off" && !body.containsKey("enableDocker") ->
                    ui.confirm(Ask(dockerTitle, dockerText, dockerOn, danger = false) { save(JsonObject(body + ("enableDocker" to JsonPrimitive(true))), what) })
                else -> {
                    error = e
                    reload++
                }
            }
        }) {
            settings = model.api.saveSettings(body)
            busy = false
            saved = true
            model.refresh()
        }
    }
    fun faceRun(block: suspend () -> Faces?) {
        busy = true
        error = null
        ui.run(onError = { busy = false; error = it }) {
            faces = block() ?: model.api.faces()
            facesMissing = false
            busy = false
        }
    }
    var pictureFor by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val name = pictureFor
        pictureFor = null
        if (uri == null || name == null) return@rememberLauncherForActivityResult
        faceRun {
            val type = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@faceRun null
            model.api.addFacePicture(name, bytes, type)
            null
        }
    }
    val s = settings
    if (s == null) {
        ScrollPage {
            if (loadError != null) io.github.ra5on.titanos.compute.ErrorBox(errorText(loadError!!), { reload++ }) else io.github.ra5on.titanos.compute.Loading()
        }
        return
    }
    SettingsScreen(
        SettingsData(s, model.status, volumes, model.cameras, faces, facesMissing, recognized, accounts, model.api.access.connection.endpoints.firstOrNull()?.base?.host.orEmpty()),
        busy = busy,
        error = error,
        saved = saved,
        actions = SettingsActions(
            save = ::save,
            switchOff = {
                ui.confirm(Ask(offTitle, offText, offWord) {
                    busy = true
                    ui.run(onError = { busy = false; error = it }) {
                        model.api.disable()
                        busy = false
                        model.refresh()
                    }
                })
            },
            diagnosis = { nav.go("diagnosis") },
            editCamera = { nav.editCamera(it) },
            loadPlates = { if (recognized == null) ui.run(onError = { recognized = emptyList() }) { recognized = model.api.recognizedPlates() } },
            faces = FaceActions(
                add = { n -> faceRun { model.api.addFace(n) } },
                rename = { a, b -> faceRun { model.api.renameFace(a, b) } },
                delete = { n -> ui.confirm(Ask(context.tx("nvr.faces.deleteTitle", "name" to n), faceDeleteText, deleteWord) { faceRun { model.api.deleteFace(n); null } }) },
                addPicture = { n -> pictureFor = n; pick.launch("image/*") },
                deletePicture = { n, f -> ui.confirm(Ask(pictureTitle, context.tx("nvr.faces.deletePictureText", "name" to n), deleteWord) { faceRun { model.api.deleteFacePicture(n, f); null } }) },
                assign = { f, n -> faceRun { model.api.assignAttempt(f, n) } },
                discard = { f -> faceRun { model.api.discardAttempt(f); null } },
            ),
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(data: SettingsData, busy: Boolean, error: Throwable?, saved: Boolean, actions: SettingsActions) {
    val loc = locale()
    val s = data.settings
    val status = data.status
    val hardware = status?.hardware
    val devices = hardware?.deviceList.orEmpty()
    val store = status?.storage
    val decoders = hardware?.decoderList.orEmpty()
    var limit by remember(s.storage.limitGB) { mutableIntStateOf(s.storage.limitGB.toInt()) }
    ScrollPage {
        ErrorLine(error)
        Group(t("nvr.settings.storage")) {
            Row2(t("nvr.storage.volume"), TitanIcons.hdd, t("nvr.storage.volumeHint")) {
                val opts = data.volumes.map { v -> v.name to t("nvr.storage.volumeFree", "name" to v.name, "free" to formatBytes((v.sizeBytes - v.usedBytes).coerceAtLeast(0), loc)) } +
                    if (s.volume.isNotEmpty() && data.volumes.none { it.name == s.volume }) listOf(s.volume to s.volume) else emptyList()
                Choice(t("nvr.storage.volume"), opts, s.volume, { actions.save(buildJsonObject { put("volume", it) }, "volume") }, enabled = !busy)
            }
            Row2(t("nvr.storage.cap"), TitanIcons.archive, divider = true, descContent = {
                Sub(t("nvr.storage.capHint"))
                val used = if (store?.usedBytes == null) t("nvr.storage.usedUnknown") else buildList {
                    add(t_s("nvr.storage.used", "used" to formatBytes(store.usedBytes, loc)))
                    if (store.recordingsBytes != null && store.exportsBytes != null) add(t_s("nvr.storage.usedParts", "recordings" to formatBytes(store.recordingsBytes, loc), "exports" to formatBytes(store.exportsBytes, loc)))
                    store.spanDays?.let { d -> add(if (decimal(d, 1, loc) == decimal(1.0, 1, loc)) t_s("nvr.storage.spanOne") else t_s("nvr.storage.span", "days" to decimal(d, 1, loc))) }
                }.joinToString(" · ")
                Sub(used)
                when (store?.cap) {
                    "cleaning" -> Sub(t("nvr.storage.capCleaning"), warn = true)
                    "exports_exceed" -> Sub(t("nvr.storage.capExports"), warn = true)
                }
                store?.lastCleanup?.let { Sub(t("nvr.storage.lastCleanup", "time" to dayClock(it, loc))) }
            }) {
                NumberField(t("nvr.storage.cap"), limit, { limit = it }, "GB")
                TitanButton(t("common.save"), { actions.save(buildJsonObject { put("storage", buildJsonObject { put("limitGB", limit.coerceAtLeast(0)) }) }, "cap") }, enabled = !busy && limit.toLong() != s.storage.limitGB)
            }
        }

        Group(t("nvr.settings.detection")) {
            Row2(t("nvr.detector.title"), TitanIcons.bolt, descContent = {
                Sub(t("nvr.detector.found", "devices" to devices.map { deviceName(it.kind, devices) }.joinToString(", ").ifEmpty { "–" }))
                val det = status?.detector
                if (det != null && status.state == "running") Sub(t("nvr.detector.active", "device" to deviceName(det.active, devices)) + (det.inferenceMs?.let { " · " + t_s("nvr.diag.ms", "ms" to decimal(it, 1, loc)) } ?: ""))
                if (s.detector.kind == "cpu") Sub(t("nvr.detector.cpuHint"))
            }) {
                DetectorChoice(s.detector.kind, hardware, allowCurrent = true) { actions.save(buildJsonObject { put("detector", buildJsonObject { put("kind", it) }) }, "detector") }
            }
            Row2(t("nvr.decoder.title"), TitanIcons.chip, divider = true, descContent = {
                Sub(t("nvr.decoder.hint"))
                status?.decoder?.let { dec ->
                    Sub(t("nvr.decoder.active", "gpu" to decoderName(dec.active, decoders)))
                    dec.reason?.let { Sub(decoderReason(it), warn = true) }
                }
            }) {
                val value = s.decoder?.ifEmpty { null } ?: "auto"
                val opts = buildList {
                    add("auto" to t_s("nvr.decoder.auto"))
                    for (d in decoders) add(d.id to decoderName(d.id, decoders) + if (d.available || s.decoder == d.id) "" else " – " + deviceReason(d.reason))
                    if (value !in setOf("auto", "cpu") && decoders.none { it.id == value }) add(value to t_s("nvr.decoder.goneOption", "id" to value))
                    add("cpu" to t_s("nvr.decoder.cpu"))
                }
                val usable = setOf("auto", "cpu", value) + decoders.filter { it.available }.map { it.id }
                Choice(t("nvr.decoder.title"), opts, value, { if (it in usable) actions.save(buildJsonObject { put("decoder", it) }, "decoder") }, enabled = !busy)
            }
            if (devices.any { it.kind == "coral" && !it.available && it.reason == "plugin" }) Row2(t("nvr.detector.coralPlugin"), TitanIcons.chip, t("nvr.detector.coralPluginHint"), divider = true)
            Row2(t("nvr.menu.diagnosis"), TitanIcons.info, t("nvr.settings.diagnosisHint"), divider = true, onClick = actions.diagnosis)
        }

        Group(t("nvr.settings.known")) {
            FacesSection(data.faces, data.facesMissing, busy, actions.faces)
            PlatesSection(s.plates, s.plates != null, data.cameras, data.recognized, busy, actions.loadPlates) { actions.save(buildJsonObject { put("plates", platesJson(it)) }, "plates") }
        }

        Group(t("nvr.settings.homeAssistant")) {
            Row2(t("nvr.rtsp.title"), TitanIcons.home, t("nvr.rtsp.hint")) {
                Toggle(s.rtspOut, if (busy) null else { on -> actions.save(buildJsonObject { put("rtspOut", on) }, "rtsp") }, t("nvr.rtsp.title") + " ✓", t("nvr.rtsp.title"), !busy)
            }
            if (s.rtspOut) {
                val clipboard = LocalClipboardManager.current
                var copied by remember { mutableStateOf(false) }
                Row2(t("nvr.rtsp.login"), TitanIcons.key, divider = true, descContent = {
                    Sub(t("nvr.rtsp.loginHint"))
                    Sub("rtsp://${data.host}:8554/${data.cameras.firstOrNull()?.id ?: t_s("nvr.rtsp.cameraIdExample")}", mono = true)
                    Sub("${t("nvr.rtsp.user")}: ${s.rtspUser} · ${t("nvr.rtsp.password")}: ${s.rtspPassword}", mono = true)
                }) {
                    TitanButton(if (copied) t("nvr.diag.copied") else t("nvr.rtsp.copy"), { clipboard.setText(AnnotatedString(s.rtspPassword)); copied = true }, icon = NvrIcons.copy)
                }
            }
        }

        NotifyOverview(data.cameras, s.notifyOff == true, busy, data.accounts, { on -> actions.save(buildJsonObject { put("notifyOff", !on) }, "notify") }, actions.editCamera)

        Group(t("nvr.settings.general")) {
            Row2(t("nvr.privacy.title"), TitanIcons.shield, t("nvr.privacy.text")) {
                if (s.privacyNoticeDismissed) TitanButton(t("nvr.privacy.showAgain"), { actions.save(buildJsonObject { put("privacyNoticeDismissed", false) }, "privacy") }, enabled = !busy)
            }
            Row2(t("nvr.settings.backup"), TitanIcons.archive, t("nvr.settings.backupHint"), divider = true)
            val power = status?.let { st ->
                t("nvr.settings.powerState", "state" to t("nvr.diag.stateName.${st.state}"), "cameras" to st.cameras.enabled) +
                    (st.detector?.inferenceMs?.let { " · " + t("nvr.diag.ms", "ms" to decimal(it, 1, loc)) } ?: "")
            }
            Row2(t("nvr.settings.power"), NvrIcons.power, power, divider = true) {
                TitanButton(t("nvr.settings.off"), actions.switchOff, kind = ButtonKind.Danger, enabled = !busy)
            }
        }
        if (saved) Sub(t("nvr.settings.saved"))
    }
}

@Composable
private fun t_s(key: String, vararg params: Pair<String, Any?>) = t(key, *params)

private fun platesJson(list: List<PlateEntry>) = JsonArray(list.map { p -> buildJsonObject { put("name", p.name); put("plates", JsonArray(p.list.map { JsonPrimitive(it) })) } })

/** Bekannte Gesichter (FacesRow.svelte): Personen mit Bildern, unbekannte Gesichter zuordnen oder verwerfen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FacesSection(faces: Faces?, missing: Boolean, busy: Boolean, actions: FaceActions) {
    val loc = locale()
    var open by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf("") }
    var renameTo by remember { mutableStateOf("") }
    var assign by remember { mutableStateOf(mapOf<String, String>()) }
    val people = faces?.faces.orEmpty()
    val attempts = faces?.attempts.orEmpty()
    val summary = when {
        missing -> t("nvr.faces.missingShort")
        faces == null -> ""
        people.isEmpty() -> t("nvr.faces.none")
        else -> (if (people.size == 1) t("nvr.faces.countOne") else t("nvr.faces.count", "count" to people.size)) +
            if (attempts.isNotEmpty()) " · " + (if (attempts.size == 1) t("nvr.faces.unknownCountOne") else t("nvr.faces.unknownCount", "count" to attempts.size)) else ""
    }
    @Composable
    fun problem(name: String): String = faceNameProblem(name).let { if (it.isEmpty()) "" else t("nvr.faces.nameProblem.$it") }
    Row2(t("nvr.faces.title"), TitanIcons.user, if (faces != null && !faces.enabled) t("nvr.faces.unused") else t("nvr.faces.hint"), summary = summary, onClick = { open = !open })
    if (!open) return
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            missing -> Sub(t("nvr.faces.missing"))
            faces == null -> io.github.ra5on.titanos.compute.Loading()
            else -> {
                for (face in people) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (renaming == face.name) {
                            val p = problem(renameTo)
                            TextInput(renameTo, { renameTo = it.take(40) }, t("nvr.faces.name"))
                            Sub(p.ifEmpty { t("nvr.faces.nameRule") }, danger = p.isNotEmpty())
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TitanButton(t("common.save"), {
                                    if (renameTo.trim() == face.name) renaming = "" else { actions.rename(face.name, renameTo.trim()); renaming = "" }
                                }, enabled = !busy && p.isEmpty())
                                TitanButton(t("common.cancel"), { renaming = "" })
                            }
                        } else {
                            Text(face.name, style = TitanType.rowTitle, color = Titan.colors.text)
                            Sub(if (face.images.orEmpty().size == 1) t("nvr.faces.picturesOne") else t("nvr.faces.pictures", "count" to face.images.orEmpty().size))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (file in face.images.orEmpty()) {
                                Box {
                                    NvrPicture(NvrApi.facePicturePath(face.name, file), t("nvr.faces.pictureOf", "name" to face.name), Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)))
                                    Box(Modifier.align(Alignment.TopEnd)) {
                                        IconButton(TitanIcons.x, t("nvr.faces.removePicture", "name" to face.name), { if (!busy) actions.deletePicture(face.name, file) }, size = 16.dp)
                                    }
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TitanButton(t("nvr.faces.addPicture"), { actions.addPicture(face.name) }, enabled = !busy, icon = TitanIcons.upload)
                            TitanButton(t("nvr.faces.rename"), { renaming = face.name; renameTo = face.name }, icon = TitanIcons.edit)
                            TitanButton(t("common.delete"), { actions.delete(face.name) }, kind = ButtonKind.Danger, icon = TitanIcons.trash)
                        }
                    }
                }
                val p = if (newName.isNotBlank() || tried) problem(newName) else ""
                TextInput(newName, { newName = it.take(40) }, t("nvr.faces.name"), placeholder = t("nvr.faces.name"))
                Sub(p.ifEmpty { t("nvr.faces.nameRule") }, danger = p.isNotEmpty())
                TitanButton(t("nvr.faces.add"), {
                    tried = true
                    if (faceNameProblem(newName).isEmpty()) {
                        actions.add(newName.trim())
                        newName = ""
                        tried = false
                    }
                }, enabled = !busy && newName.isNotBlank() && p.isEmpty(), icon = TitanIcons.plus)
                Sub(t("nvr.faces.pictureHint"))
                if (attempts.isNotEmpty()) {
                    Text(t("nvr.faces.unknown"), style = TitanType.section, color = Titan.colors.muted)
                    for (a in attempts) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                NvrPicture(NvrApi.attemptPath(a.file), t("nvr.faces.unknownOne"), Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)))
                                Sub(dayClock(a.at, loc) + if (a.guess.isNotEmpty()) " · " + t("nvr.faces.guess", "name" to a.guess, "percent" to (a.score * 100).toInt()) else "")
                            }
                            val chosen = assign[a.file] ?: a.guess
                            Box(Modifier.widthIn(max = 280.dp)) {
                                Choice(t("nvr.faces.assignTo"), listOf("" to t("nvr.faces.assignTo")) + people.map { it.name to it.name }, chosen, { assign = assign + (a.file to it) })
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TitanButton(t("nvr.faces.assign"), { actions.assign(a.file, chosen) }, enabled = !busy && chosen.isNotEmpty(), icon = TitanIcons.check)
                                TitanButton(t("nvr.faces.discard"), { actions.discard(a.file) }, enabled = !busy, icon = TitanIcons.trash)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Bekannte Kennzeichen (PlatesRow.svelte): Name und Kennzeichen; gelesene mit einem Tippen übernehmen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatesSection(plates: List<PlateEntry>?, supported: Boolean, cameras: List<Camera>, recognized: List<RecognizedPlate>?, busy: Boolean, onOpen: () -> Unit, onSave: (List<PlateEntry>) -> Unit) {
    val loc = locale()
    val saved = plates.orEmpty()
    var open by remember { mutableStateOf(false) }
    var draft by remember(saved) { mutableStateOf(saved.map { it.name to it.list.joinToString(", ") }) }
    fun clean(rows: List<Pair<String, String>>) = rows.map { (n, p) -> PlateEntry(n.trim(), p.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }.filter { it.name.isNotEmpty() && it.list.isNotEmpty() }
    val dirty = clean(draft) != saved.map { PlateEntry(it.name, it.list) }
    val used = cameras.any { it.detect?.plates == true }
    Row2(
        t("nvr.plates.title"),
        TitanIcons.file,
        if (used) t("nvr.plates.hint") else t("nvr.plates.unused"),
        divider = true,
        summary = when (saved.size) { 0 -> t("nvr.plates.none"); 1 -> t("nvr.plates.countOne"); else -> t("nvr.plates.count", "count" to saved.size) },
        onClick = { open = !open; if (open) onOpen() },
    )
    if (!open) return
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!supported) {
            Sub(t("nvr.plates.missing"))
            return@Column
        }
        val problems = draft.map { (_, p) ->
            val list = p.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val bad = list.firstOrNull { !plateOk(it) }
            if (bad != null) t_s("nvr.plates.badPlate", "plate" to bad) else if (list.size > 20) t_s("nvr.plates.tooMany") else ""
        }
        draft.forEachIndexed { i, (name, list) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextInput(name, { v -> draft = draft.mapIndexed { j, r -> if (j == i) v.take(40) to r.second else r } }, t("nvr.plates.name"), placeholder = t("nvr.plates.name"))
                TextInput(list, { v -> draft = draft.mapIndexed { j, r -> if (j == i) r.first to v else r } }, t("nvr.plates.plates"), placeholder = "M-AB 1234, MAB12*")
                if (problems[i].isNotEmpty()) Sub(problems[i], danger = true)
                TitanButton(t("nvr.plates.remove"), { draft = draft.filterIndexed { j, _ -> j != i } }, icon = TitanIcons.trash)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TitanButton(t("nvr.plates.add"), { draft = draft + ("" to "") }, icon = TitanIcons.plus)
            TitanButton(t("common.save"), { onSave(clean(draft)) }, kind = ButtonKind.Primary, enabled = !busy && dirty && problems.all { it.isEmpty() })
        }
        Sub(t("nvr.plates.patternHint"))
        if (!recognized.isNullOrEmpty()) {
            val known = clean(draft).flatMap { it.list }.toSet()
            Text(t("nvr.plates.seen"), style = TitanType.section, color = Titan.colors.muted)
            for (p in recognized) {
                Row2(p.plate, desc = t("nvr.plates.seenInfo", "count" to (if (p.count > 0) p.count.toString() else "–"), "time" to (p.lastSeen?.let { dayClock(it, loc) } ?: "–"), "camera" to (cameras.firstOrNull { it.id == p.camera }?.name ?: p.camera.ifEmpty { "–" }))) {
                    if (p.plate !in known) TitanButton(t("nvr.plates.take"), { draft = draft + ("" to p.plate) }) else StateMark(t("nvr.plates.known"), StateKind.Ok)
                }
            }
            if (recognized.any { it.count == 0 || it.lastSeen == null || it.camera.isEmpty() }) Sub(t("nvr.plates.seenOld"))
        }
    }
}

/** Hauptschalter der Meldungen und je Kamera, wer was bekommt (NotifyOverview.svelte). */
@Composable
fun NotifyOverview(cameras: List<Camera>, off: Boolean, busy: Boolean, accounts: List<RecipientAccount>?, onSwitch: (Boolean) -> Unit, onEdit: (String) -> Unit) {
    Group(t("nvr.settings.notify")) {
        Row2(t("nvr.notify.main"), TitanIcons.bell, if (off) t("nvr.notify.mainOffHint") else t("nvr.notify.mainOnHint")) {
            Toggle(!off, if (busy) null else onSwitch, t("nvr.notify.main") + " ✓", t("nvr.notify.main"), !busy)
        }
        val shown = cameras.filter { !it.deleted && it.notify != null }
        if (shown.isEmpty()) Row2(t("nvr.notify.overviewNone"), divider = true)
        for (c in shown) {
            val n = c.notify!!
            val lines = buildList {
                val kinds = kindPillsOf(n.kinds.orEmpty()).map { t_s("nvr.notify.kind.$it") } + if (n.faceUnknown) listOf(t_s("nvr.notify.kind.faceUnknown")) else emptyList()
                add(if (kinds.isNotEmpty()) t_s("nvr.notify.overviewKinds", "kinds" to kinds.joinToString(", ")) else t_s("nvr.notify.overviewNoKinds"))
                val zoneNames = n.zones.orEmpty().mapNotNull { id -> c.zones?.firstOrNull { it.id == id }?.name }
                if (zoneNames.isNotEmpty()) add(t_s("nvr.notify.overviewZones", "zones" to zoneNames.joinToString(", ")))
                val rec = n.recipients
                if (rec == null) {
                    val ways = listOfNotNull(if (n.titan) t_s("nvr.notify.way.bell") else null, if (n.push) t_s("nvr.notify.way.push") else null, if (n.email) t_s("nvr.notify.overviewLegacyEmail") else null)
                    add(if (ways.isNotEmpty()) t_s("nvr.notify.overviewLegacy", "ways" to ways.joinToString(", ")) else t_s("nvr.notify.overviewNobody"))
                } else if (rec.isEmpty()) {
                    add(t_s("nvr.notify.overviewNobody"))
                } else {
                    add(t_s("nvr.notify.overviewRecipients", "list" to rec.map { r -> "${r.user} (" + waysOf(r).map { w -> t_s("nvr.notify.way.$w") }.joinToString(", ") + ")" }.joinToString(", ")))
                    if (accounts != null) for (r in rec) {
                        val why = staleReason(r.user, accounts, c.id)
                        val a = accounts.firstOrNull { it.name == r.user }
                        if (why.isNotEmpty()) add(t_s("nvr.notify.overviewNothing", "name" to r.user, "why" to t_s(if (why == "gone") "nvr.notify.overviewGone" else "nvr.notify.overviewNoRight")))
                        else if (a != null && missesNames(a, c.id, n.kinds.orEmpty(), n.faceUnknown)) add(t_s("nvr.notify.overviewNoNames", "name" to r.user))
                    }
                }
                val also = listOfNotNull(if (n.homeAssistant) t_s("nvr.notify.homeAssistant") else null, if (n.agent) t_s("nvr.notify.agent") else null)
                if (also.isNotEmpty()) add(t_s("nvr.notify.overviewAlso", "list" to also.joinToString(", ")))
                val w = n.window
                val window = if (w?.on != true) t_s("nvr.notify.always") else t_s("nvr.notify.windowShort", "days" to daysText(w.days.orEmpty()), "from" to w.from, "to" to w.to)
                add(t_s("nvr.notify.overviewPause", "pause" to t_s("nvr.notify.pause.${n.pauseSeconds ?: DEFAULT_PAUSE}")) + " · " + window)
            }
            Row2(c.name, NvrIcons.camera, divider = true, descContent = { for (l in lines) Sub(l) }) {
                TitanButton(t("nvr.notify.overviewEdit"), { onEdit(c.id) }, Modifier.padding(0.dp), icon = TitanIcons.edit)
            }
        }
    }
}

/** Tage als Wörter: „täglich“, „Mo–Fr“, „Sa, So“. */
@Composable
fun daysText(days: List<Int>): String {
    val runs = dayRuns(days)
    if (runs.size == 1 && runs[0] == 1 to 7) return t("nvr.notify.daily")
    val names = (1..7).associateWith { t("nvr.notify.day.$it") }
    return runs.flatMap { (a, b) -> if (b - a >= 2) listOf("${names[a]}–${names[b]}") else (a..b).map { names.getValue(it) } }.joinToString(", ")
}
