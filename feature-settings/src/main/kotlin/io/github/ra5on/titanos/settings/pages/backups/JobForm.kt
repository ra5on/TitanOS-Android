package io.github.ra5on.titanos.settings.pages.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.withPath
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.NumberField
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
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Das Formular eines Auftrags, neu oder geändert (JobForm.svelte): was
 * gesichert wird, wohin, wann, wie schnell und wie viele Versionen bleiben.
 * Steht wie im Web unter der Liste; beim Öffnen rückt es ins Bild.
 */
@Composable
internal fun JobForm(ctl: DataCtl, overview: JsonObject) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val draft = ctl.draft ?: return
    val shares = remember(overview) { Bk.shares(overview) }
    val opened = ctl.opened
    val original = remember(opened) { if (ctl.isNew) null else draft.o("destination") }
    var patterns by remember(opened) { mutableStateOf(draft.strings("patterns").joinToString("\n")) }
    var bandwidth by remember(opened) { mutableStateOf(Bk.bandwidthForm(draft.o("bandwidth").takeIf { draft.has("bandwidth") })) }
    var chosen by remember(opened) { mutableStateOf(Bk.chosenOf(draft, shares)) }
    var pairCode by remember(opened) { mutableStateOf("") }
    var more by remember(opened) { mutableStateOf(false) }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(opened) { delay(100); requester.bringIntoView() }

    val selection = Bk.selectionOfIn(shares, chosen)
    val dest = draft.o("destination")
    val type = dest.str("type")
    val plain = Bk.plainCopy(type)
    fun set(vararg pairs: Pair<String, Any?>) { ctl.draft = (ctl.draft ?: return).with(*pairs) }
    fun setDest(key: String, value: Any?) { ctl.draft = (ctl.draft ?: return).withPath("destination", key, value = value) }

    // Ein gespeichertes Geheimnis bleibt nur für denselben Server und dasselbe Konto.
    val secretSet = ctl.secretSet
    val keptSecret = original != null && dest.str("type") == original.str("type") && secretSet.bool("secretKey") &&
        dest.str("endpoint").trim() == original.str("endpoint") && dest.str("accessKey").trim() == original.str("accessKey")
    val keptPassword = original != null && dest.str("type") == original.str("type") && secretSet.bool("password") &&
        dest.str("url").trim() == original.str("url") && dest.str("username").trim() == original.str("username")

    fun pick(index: Int) {
        val start = chosen.getOrNull(index)?.takeIf { index >= 0 && it.volume.isNotEmpty() }
        ov.show { close ->
            FolderPicker(overview.strings("volumes"), shares, start, onPick = { c ->
                chosen = if (index < 0) chosen.filter { it.volume.isNotEmpty() } + c else chosen.toMutableList().also { it[index] = c }
                close()
            }, onClose = close)
        }
    }

    Panel(Modifier.bringIntoViewRequester(requester)) {
        GroupTitle(if (ctl.isNew) t("backup.add") else t("backup.editTitle", "name" to draft.str("id")), Modifier.padding(top = 0.dp))
        if (ctl.isNew) {
            Labeled(t("backup.name"), hint = t("backup.nameHint")) {
                Field(draft.str("id"), { v -> set("id" to v.filter { it.isLetterOrDigit() || it == '-' }.take(31)) }, t("backup.name"), Modifier.fillMaxWidth(), mono = true,
                    keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii))
            }
        }
        Section(t("backup.sources")) {
            Muted(t("backup.pick.hint"))
            chosen.forEachIndexed { i, c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickField(c, shares, Modifier.weight(1f)) { pick(i) }
                    if (chosen.size > 1 || c.volume.isNotEmpty()) {
                        IconButton(WebIcons["close"], t("backup.pick.remove"), { chosen = if (chosen.size > 1) chosen.filterIndexed { n, _ -> n != i } else listOf(Chosen("", "")) })
                    }
                }
            }
            SmallButton(t("backup.pick.add"), { pick(-1) }, icon = WebIcons["plus"])
            Muted(t("backup.neverSaved"))
            Disclosure(t("backup.moreOptions"), more) { more = !more }
            if (more) {
                if (!plain) {
                    SubHead(t("backup.extras"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val extras = draft.strings("extras")
                        for (extra in Bk.EXTRAS) {
                            CheckChip(t("backup.extra.$extra"), extra in extras, { set("extras" to if (extra in extras) extras - extra else extras + extra) })
                        }
                    }
                    if ("config" in draft.strings("extras")) NoticeLine(t("backup.extra.configHint"), Tone.Warn)
                    ApplicationSelection(draft.a("applications").objects(), draft.bool("allowStop"), { set("applications" to it) }, { set("allowStop" to it) })
                }
                val skip = draft.strings("skip")
                if (skip.isNotEmpty()) {
                    SubHead(t("backup.skipList"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (entry in skip) {
                            Row(
                                Modifier.clip(RoundedCornerShape(10.dp)).background(Titan.colors.chip).padding(start = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Mono(entry)
                                IconButton(WebIcons["close"], t("backup.pick.remove") + " " + entry, { set("skip" to skip - entry) }, size = 18.dp)
                            }
                        }
                    }
                }
                Labeled(t("backup.patterns")) {
                    Field(patterns, { patterns = it }, t("backup.patterns"), Modifier.fillMaxWidth(), placeholder = "*.tmp", mono = true, minHeight = 64.dp)
                }
                Muted(t(if (plain) "backup.copyPatterns" else "backup.patternsHint"))
            }
        }
        JobDestination(ctl, draft, pairCode, { pairCode = it }, overview, keptSecret, keptPassword)
        ScheduleFields(draft) { ctl.draft = it }
        Section(t("backup.bandwidth")) {
            CheckRow(bandwidth.on, { bandwidth = bandwidth.copy(on = it) }, t("backup.bandwidthLimit"))
            if (bandwidth.on) {
                Labeled(t("backup.bandwidthMax")) {
                    NumberField(bandwidth.limit.toLongOrNull(), { bandwidth = bandwidth.copy(limit = (it ?: 0).coerceIn(0, Bk.MAX_BANDWIDTH).toString()) }, t("backup.bandwidthMax"), allowEmpty = true)
                }
                CheckRow(bandwidth.window, { bandwidth = bandwidth.copy(window = it) }, t("backup.bandwidthWindow"))
                if (bandwidth.window) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Labeled(t("backup.windowFrom")) { TimeField(bandwidth.windowFrom, { bandwidth = bandwidth.copy(windowFrom = it) }, t("backup.windowFrom")) }
                        Labeled(t("backup.windowTo")) { TimeField(bandwidth.windowTo, { bandwidth = bandwidth.copy(windowTo = it) }, t("backup.windowTo")) }
                        Labeled(t("backup.bandwidthWindowLimit")) {
                            NumberField(bandwidth.windowLimit.toLongOrNull(), { bandwidth = bandwidth.copy(windowLimit = (it ?: 0).coerceIn(0, Bk.MAX_BANDWIDTH).toString()) }, t("backup.bandwidthWindowLimit"), allowEmpty = true)
                        }
                    }
                }
                Muted(t(if (plain) "backup.bandwidthHintCopy" else "backup.bandwidthHintRestic"))
            }
        }
        if (plain) VmDisksChoice(draft.o("vmDisks").takeIf { draft.has("vmDisks") }, selection) { v -> set("vmDisks" to v) }
        if (plain) {
            val copy = draft.o("copy")
            Section(t("backup.copy.deleted")) {
                if (copy.bool("dated")) {
                    Muted(t("backup.copy.datedDeleted"))
                } else {
                    RadioLine(!copy.bool("mirror"), t("backup.copy.keepDeleted"), { set("copy" to copy.with("mirror" to false)) }, desc = t("backup.copy.keepDeletedHint"))
                    RadioLine(copy.bool("mirror"), t("backup.copy.mirror"), {
                        set("copy" to copy.with("mirror" to true, "mirrorDays" to copy.long("mirrorDays").takeIf { it != 0L }.let { it ?: 30L }))
                    }, desc = t("backup.copy.mirrorHint"))
                    if (copy.bool("mirror")) {
                        Row(Modifier.padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField(copy.long("mirrorDays", 30), { n -> if (n != null) set("copy" to copy.with("mirrorDays" to n.coerceIn(1, 3650))) }, t("backup.copy.mirrorDays"))
                            Body(t("backup.copy.mirrorDays"))
                        }
                    }
                }
            }
        }
        Section(t("backup.keeps")) {
            if (plain && !draft.o("copy").bool("dated")) Muted(t("backup.copy.noVersions"))
            else KeepVersions(draft.o("keep"), { set("keep" to it) }, target = Bk.fullTarget(type))
        }
        ctl.error?.let { (text, detail) ->
            Notice(Tone.Error) {
                NoticeText(text, Tone.Error)
                if (detail.isNotEmpty()) Mono(detail, color = noticeInk(Tone.Error))
            }
        }
        val apps = draft.a("applications").objects()
        val ready = (chosen.any { it.volume.isNotEmpty() } || draft.strings("extras").isNotEmpty() || apps.isNotEmpty()) &&
            (!ctl.isNew || Bk.NAME.matches(draft.str("id").trim())) &&
            destinationReady(draft, keptSecret, pairCode) && scheduleReady(draft) &&
            (apps.none { it.str("method") == "stop" } || draft.bool("allowStop") || plain)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(t("common.cancel"), { ctl.draft = null; ctl.error = null }, enabled = !ctl.busy)
            SmallButton(t(if (ctl.isNew) "backup.create" else "notify.save"), {
                val d = ctl.draft ?: return@SmallButton
                if (d.o("destination").str("type") == "titanos" && Bk.decodeCode(pairCode) == null) {
                    ctl.error = tr.t("backup.pairCodeInvalid") to ""
                    return@SmallButton
                }
                val job = Bk.jobPayload(d, selection, patterns, bandwidth, ctl.usbDrives, pairCode)
                ctl.guard(GuardAction.Settings) {
                    ctl.busy = true
                    ctl.error = null
                    ctl.scope.launch {
                        try {
                            val next = if (ctl.isNew) BkApi.createJob(ctl.api, job) else BkApi.updateJob(ctl.api, job.str("id"), job)
                            ctl.saved(next, if (ctl.isNew) job else null)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ctl.problem(e)
                        }
                        ctl.busy = false
                    }
                }
            }, kind = ButtonKind.Primary, enabled = !ctl.busy && ready)
        }
    }
}

/** Ob die Pflichtfelder des Ziels gefüllt sind (required im Web). */
private fun destinationReady(draft: JsonObject, keptSecret: Boolean, pairCode: String): Boolean {
    val d = draft.o("destination")
    return when (d.str("type")) {
        "smb", "smb-copy" -> d.str("targetId").isNotEmpty()
        "usb", "usb-copy" -> d.str("deviceId").isNotEmpty()
        "local", "local-copy" -> d.str("volume").isNotEmpty()
        "s3" -> d.str("endpoint").isNotBlank() && d.str("bucket").isNotBlank() && d.str("accessKey").isNotBlank() && (keptSecret || d.str("secretKey").isNotEmpty())
        "sftp" -> d.str("host").isNotBlank() && d.str("user").isNotBlank() && d.str("path").isNotBlank() && d.long("port") in 1..65535
        "titanos" -> pairCode.isNotBlank()
        else -> d.str("url").isNotBlank()
    }
}

private fun scheduleReady(draft: JsonObject): Boolean {
    if (!draft.bool("enabled")) return true
    val times = draft.strings("times")
    if (times.isEmpty() || times.any { !Bk.TIME.matches(it) }) return false
    if (draft.str("windowFrom").isNotEmpty() && (!Bk.TIME.matches(draft.str("windowFrom")) || !Bk.TIME.matches(draft.str("windowTo")))) return false
    return true
}

/** Ein gewählter Ordner als Feld; Tippen öffnet die Ordnerwahl. */
@Composable
private fun PickField(c: Chosen, shares: List<Share>, modifier: Modifier, onClick: () -> Unit) {
    val col = Titan.colors
    val shape = RoundedCornerShape(10.dp)
    val label = t("backup.pick.change")
    FlowRow(
        modifier.heightIn(min = 44.dp).clip(shape).background(col.bg).border(1.dp, col.border, shape)
            .clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (c.volume.isNotEmpty()) {
            Icon(WebIcons["folder"], null, tint = col.muted, modifier = Modifier.size(18.dp))
            Mono(c.volume + if (c.path.isNotEmpty()) " › " + c.path.replace("/", " › ") else "")
            val share = Bk.shareOfIn(shares, c)
            if (c.path.isEmpty()) Muted(t("backup.pick.whole"))
            else if (share != null && share.second.isEmpty()) io.github.ra5on.titanos.ui.components.Badge(t("backup.pick.share"))
        } else {
            Muted(t("backup.pick.choose"))
        }
    }
}

/**
 * Ordner wählen (FolderPicker.svelte): erst die Volumes, Freigaben sind
 * Ordner auf ihrem Volume. Nur die Antwort für den gezeigten Ordner zählt.
 */
@Composable
private fun BoxScope.FolderPicker(volumes: List<String>, shares: List<Share>, start: Chosen?, onPick: (Chosen) -> Unit, onClose: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    var volume by remember { mutableStateOf(start?.volume.orEmpty()) }
    var path by remember { mutableStateOf(start?.path.orEmpty()) }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(volume, path) {
        folders = null
        error = ""
        if (volume.isEmpty()) return@LaunchedEffect
        try {
            folders = BkApi.folders(env.api, volume, path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
            folders = emptyList()
        }
    }
    val parts = if (path.isEmpty()) emptyList() else path.split('/')
    fun go(v: String, p: String) { volume = v; path = p }
    BkDialog(t("backup.pick.title"), onClose, actions = {
        SmallButton(
            if (path.isNotEmpty()) tr.t("backup.pick.this", "name" to parts.last()) else if (volume.isNotEmpty()) tr.t("backup.pick.wholeVolume", "volume" to volume) else tr.t("backup.pick.chooseVolume"),
            { onPick(Chosen(volume, path)) },
            kind = ButtonKind.Primary,
            enabled = volume.isNotEmpty(),
        )
        SmallButton(t("common.cancel"), onClose)
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            LinkText(t("backup.pick.volumes"), { go("", "") })
            if (volume.isNotEmpty()) { Muted("›"); LinkText(volume, { go(volume, "") }) }
            parts.forEachIndexed { i, part -> Muted("›"); LinkText(part, { go(volume, parts.take(i + 1).joinToString("/")) }) }
        }
        val f = folders
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                volume.isEmpty() -> {
                    if (volumes.isEmpty()) Muted(t("backup.noVolume"))
                    for (v in volumes) PickRow(WebIcons["disk"], tr.t("backup.pick.volume", "volume" to v), null) { go(v, "") }
                }
                f == null -> Muted(t("common.loading"))
                else -> {
                    if (f.isEmpty()) Muted(t("backup.pick.empty"))
                    for (name in f) {
                        val isShare = path.isEmpty() && shares.any { it.volume == volume && it.name == name }
                        PickRow(WebIcons["folder"], breakable(name), if (isShare) tr.t("backup.pick.share") else null) { go(volume, if (path.isNotEmpty()) "$path/$name" else name) }
                    }
                }
            }
        }
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    }
}

@Composable
private fun PickRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, badge: String?, onClick: () -> Unit) {
    val c = Titan.colors
    FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.muted, modifier = Modifier.size(20.dp))
        Body(text)
        if (badge != null) io.github.ra5on.titanos.ui.components.Badge(badge)
    }
}

/** Anwendungen eines Auftrags sauber stoppen oder exportieren (ApplicationSelection.svelte). */
@Composable
private fun ApplicationSelection(applications: List<JsonObject>, allowStop: Boolean, onApps: (List<JsonObject>) -> Unit, onAllowStop: (Boolean) -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    var options by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var error by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val (opts, err) = BkApi.applicationOptions(env.api)
        options = opts
        if (err != null) error = tr.operationError(err)
    }
    fun kindName(kind: String) = if (kind == "vm") "VM" else "Docker"
    SubHead(t("ops.appBackups"))
    Muted(t("ops.appHint"))
    if (error.isNotEmpty()) NoticeLine(error, Tone.Warn)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Select(choice, listOf("" to tr.t("ops.choose")) + options.map { (k, n) -> "$k:$n" to "${kindName(k)} · $n" }, { choice = it }, t("ops.appAdd"))
        SmallButton(t("ops.appAdd"), {
            val kind = choice.substringBefore(':')
            val name = choice.substringAfter(':', "")
            if (name.isNotEmpty() && applications.none { it.str("kind") == kind && it.str("name") == name }) {
                onApps(applications + jobj("kind" to kind, "name" to name, "method" to "stop"))
            }
            choice = ""
        }, enabled = choice.isNotEmpty() && applications.size < 50)
    }
    applications.forEachIndexed { i, app ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Body("${kindName(app.str("kind"))} · ${app.str("name")}")
            val methods = listOf("stop" to tr.t("ops.appStop")) + if (app.str("kind") == "docker") listOf("postgres-export" to tr.t("ops.pgExport")) else emptyList()
            Select(app.str("method"), methods, { m -> onApps(applications.toMutableList().also { it[i] = app.with("method" to m) }) }, app.str("name"))
            SmallButton(t("ops.remove"), { onApps(applications.filterIndexed { n, _ -> n != i }) })
        }
    }
    if (applications.any { it.str("method") == "stop" }) CheckRow(allowStop, onAllowStop, t("ops.allowStop"))
}

/** Wohin ein Backup geht: Ort, Art und die Felder des Ziels (JobDestination.svelte). */
@Composable
private fun JobDestination(ctl: DataCtl, draft: JsonObject, pairCode: String, onPairCode: (String) -> Unit, overview: JsonObject, keptSecret: Boolean, keptPassword: Boolean) {
    val tr = LocalTr.current
    val dest = draft.o("destination")
    val type = dest.str("type")
    val plain = Bk.plainCopy(type)
    val isNew = ctl.isNew
    fun setDest(key: String, value: Any?) { ctl.draft = (ctl.draft ?: return).withPath("destination", key, value = value) }
    fun setKind(kind: String) {
        var d = (ctl.draft ?: return).withPath("destination", "type", value = kind)
        if (Bk.plainCopy(kind)) {
            d = d.with("applications" to emptyList<Any>(), "allowStop" to false, "extras" to emptyList<String>())
            if (!d.has("copy")) d = d.with("copy" to jobj())
        }
        ctl.draft = d
        if (kind == "sftp") ctl.showKey()
        if (kind == "titanos") onPairCode("")
    }
    val placeholderFolder = "backups/${draft.str("id").ifEmpty { "name" }}"
    Section(t("backup.destination")) {
        SegmentRow(Bk.placeOf(type), Bk.PLACES.map { place ->
            val kind = Bk.kindOf(place, plain)
            Triple(place, tr.t("backup.place.$place"), isNew || Bk.plainCopy(kind) == plain)
        }) { place -> setKind(Bk.kindOf(place, plain)) }
        val place = Bk.placeOf(type)
        if (Bk.twoFormats(place)) {
            Muted(t("backup.placeHint.$place"))
            for (readable in listOf(true, false)) {
                val on = plain == readable
                val shape = RoundedCornerShape(10.dp)
                Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, if (on) Titan.colors.accent else Titan.colors.border, shape).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    RadioLine(on, t(if (readable) "backup.format.readable" else "backup.format.encrypted"), { setKind(Bk.kindOf(place, readable)) },
                        enabled = isNew || on, desc = t(if (readable) "backup.format.readableHint" else "backup.format.encryptedHint"))
                    if (readable && on) {
                        CheckRow(draft.o("copy").bool("dated"), { v -> ctl.draft = draft.with("copy" to draft.o("copy").with("dated" to v)) }, t("backup.copy.dated"), Modifier.padding(start = 30.dp))
                    }
                }
            }
            if (!isNew) Muted(t("backup.format.fixed"))
        } else {
            Muted(Bk.known(tr, "backup.kindHint.$type"))
        }
        when (type) {
            "smb", "smb-copy" -> {
                Labeled(t("backup.smb.target")) {
                    val targets = overview.a("smbTargets").objects()
                    Select(dest.str("targetId"), listOf("" to "–") + targets.map { it.str("id") to "${it.str("id")} · ${it.str("host")}/${it.str("share")}" }, { if (it.isNotEmpty()) setDest("targetId", it) }, t("backup.smb.target"))
                }
                Muted(t("backup.smb.first"))
                Labeled(t("backup.smb.jobFolder")) { Field(dest.str("folder"), { setDest("folder", it) }, t("backup.smb.jobFolder"), Modifier.fillMaxWidth(), placeholder = "backups/${draft.str("id")}") }
            }
            "usb", "usb-copy" -> {
                if (ctl.usbError.isNotEmpty()) Notice(Tone.Warn) { NoticeText(ctl.usbError, Tone.Warn); SmallButton(t("ui.retry"), { ctl.loadUSB() }) }
                Labeled(t("backup.usbDevice")) {
                    val id = dest.str("deviceId")
                    val drives = ctl.usbDrives
                    val missing = if (id.isNotEmpty() && drives.none { it.str("id") == id }) listOf(id to "${dest.str("deviceName").ifEmpty { id }} · ${tr.t("backup.usbMissing")}") else emptyList()
                    Select(id, listOf("" to "–") + missing + drives.filter { it.bool("supported") }.map { it.str("id") to "${it.str("name")} · ${it.str("id")}" }, { if (it.isNotEmpty()) setDest("deviceId", it) }, t("backup.usbDevice"))
                }
                Labeled(t("backup.folder")) { Field(dest.str("folder"), { setDest("folder", it) }, t("backup.folder"), Modifier.fillMaxWidth(), placeholder = placeholderFolder, mono = true) }
            }
            "local", "local-copy" -> {
                val volumes = overview.strings("volumes")
                Labeled(t("backup.volume")) {
                    Select(dest.str("volume"), listOf("" to "–") + volumes.map { it to it }, { if (it.isNotEmpty()) setDest("volume", it) }, t("backup.volume"))
                }
                Labeled(t("backup.folder")) { Field(dest.str("folder"), { setDest("folder", it) }, t("backup.folder"), Modifier.fillMaxWidth(), placeholder = placeholderFolder, mono = true) }
                if (volumes.isEmpty()) NoticeLine(t("backup.noVolume"), Tone.Warn)
            }
            "s3" -> {
                Labeled(t("backup.endpoint")) { Field(dest.str("endpoint"), { setDest("endpoint", it) }, t("backup.endpoint"), Modifier.fillMaxWidth(), placeholder = "https://s3.eu-central-1.amazonaws.com", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)) }
                Labeled(t("backup.bucket")) { Field(dest.str("bucket"), { setDest("bucket", it) }, t("backup.bucket"), Modifier.fillMaxWidth(), mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false)) }
                Labeled(t("backup.prefix")) { Field(dest.str("prefix"), { setDest("prefix", it) }, t("backup.prefix"), Modifier.fillMaxWidth(), mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false)) }
                Labeled(t("backup.accessKey")) { Field(dest.str("accessKey"), { setDest("accessKey", it) }, t("backup.accessKey"), Modifier.fillMaxWidth(), mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false)) }
                Labeled(t("backup.secretKey"), hint = if (keptSecret) t("notify.secretStored") else null) {
                    Field(dest.str("secretKey"), { setDest("secretKey", it) }, t("backup.secretKey"), Modifier.fillMaxWidth(), password = true)
                }
                if (ctl.secretSet.bool("secretKey") && !keptSecret) NoticeLine(t("backup.secretAgain"), Tone.Warn)
            }
            "sftp" -> {
                Labeled(t("backup.host")) { Field(dest.str("host"), { setDest("host", it) }, t("backup.host"), Modifier.fillMaxWidth(), mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)) }
                Labeled(t("notify.port")) { NumberField(dest.long("port", 22), { setDest("port", it ?: 0) }, t("notify.port")) }
                Labeled(t("notify.username")) { Field(dest.str("user"), { setDest("user", it) }, t("notify.username"), Modifier.fillMaxWidth(), mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false)) }
                Labeled(t("backup.remotePath")) { Field(dest.str("path"), { setDest("path", it) }, t("backup.remotePath"), Modifier.fillMaxWidth(), placeholder = "backups/nas", mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false)) }
                if (ctl.publicKey.isNotEmpty()) {
                    Labeled(t("backup.publicKey"), hint = t("backup.publicKeyHint")) {
                        SecretBox(ctl.publicKey, t("backup.publicKey"))
                    }
                    val context = androidx.compose.ui.platform.LocalContext.current
                    SmallButton(t("backup.targetCopy"), { copyText(context, tr.t("backup.publicKey"), ctl.publicKey) })
                }
            }
            "titanos" -> {
                ClusterPair { onPairCode(it) }
                Labeled(t("backup.pairCode")) {
                    Field(pairCode, onPairCode, t("backup.pairCode"), Modifier.fillMaxWidth(), placeholder = "titanos-backup:…", mono = true, minHeight = 76.dp, keyboard = KeyboardOptions(autoCorrectEnabled = false))
                }
                val pairing = Bk.decodeCode(pairCode)
                if (pairing != null) NoticeLine(tr.t("backup.pairCodeOk", "url" to pairing.url), Tone.Ok)
                else if (pairCode.isNotBlank()) NoticeLine(t("backup.pairCodeInvalid"), Tone.Warn)
                Muted(t("backup.pairCodeHint"))
            }
            else -> {
                Labeled(t("backup.restUrl")) { Field(dest.str("url"), { setDest("url", it) }, t("backup.restUrl"), Modifier.fillMaxWidth(), placeholder = "https://backup.example.org:8000/nas", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)) }
                Labeled(t("notify.username")) { Field(dest.str("username"), { setDest("username", it) }, t("notify.username"), Modifier.fillMaxWidth(), mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false)) }
                Labeled(t("notify.password"), hint = if (keptPassword) t("notify.secretStored") else null) {
                    Field(dest.str("password"), { setDest("password", it) }, t("notify.password"), Modifier.fillMaxWidth(), password = true)
                }
                if (ctl.secretSet.bool("password") && !keptPassword) NoticeLine(t("backup.secretAgain"), Tone.Warn)
            }
        }
    }
}

/**
 * Kopplungscode von einem anderen Knoten des Clusters holen
 * (ClusterPair.svelte); außerhalb eines Clusters unsichtbar.
 */
@Composable
internal fun ClusterPair(onPair: (String) -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var others by remember { mutableStateOf(emptyList<JsonObject>()) }
    var node by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { others = runCatching { BkApi.clusterOthers(env.api) }.getOrDefault(emptyList()) }
    if (others.isEmpty()) return
    Labeled(t("cluster.pairNode")) {
        Select(node, listOf("" to tr.t("cluster.pairNone")) + others.filter { it.bool("online") }.map { it.str("id") to it.str("name") }, { node = it }, t("cluster.pairNode"), enabled = !busy)
    }
    SmallButton(t("cluster.pairButton"), {
        busy = true
        error = ""
        scope.launch {
            try {
                onPair(BkApi.backupPair(env.api, node))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
            busy = false
        }
    }, enabled = !busy && node.isNotEmpty())
    Muted(t("cluster.pairHint"))
    if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
}

/** Wann ein Auftrag läuft (ScheduleFields.svelte). */
@Composable
private fun ScheduleFields(job: JsonObject, onChange: (JsonObject) -> Unit) {
    val tr = LocalTr.current
    fun set(vararg pairs: Pair<String, Any?>) = onChange(job.with(*pairs))
    val every = job.str("every", "daily")
    val times = job.strings("times")
    Section(t("backup.schedule")) {
        CheckRow(job.bool("enabled"), { set("enabled" to it) }, t("backup.enabled"))
        if (!job.bool("enabled")) return@Section
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Labeled(t("backup.every")) {
                Select(every, listOf("hourly", "daily", "weekly", "monthly").map { it to tr.t("backup.every.$it") }, { set("every" to it) }, t("backup.every"))
            }
            if (every == "hourly") {
                Labeled(t("backup.interval")) {
                    Select(job.long("interval", 6), listOf(1L, 2L, 3L, 4L, 6L, 12L).map { it to tr.t("backup.intervalHours", "hours" to it) }, { set("interval" to it) }, t("backup.interval"))
                }
            }
            if (every == "monthly") {
                Labeled(t("backup.monthDay")) {
                    Select(job.long("monthDay", 1), (1L..28L).map { it to tr.t("backup.monthDayOption", "day" to it) }, { set("monthDay" to it) }, t("backup.monthDay"))
                }
            }
        }
        if (every == "weekly") {
            val days = job.a("weekdays").mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            Labeled(t("backup.weekdays")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (day in listOf(1L, 2L, 3L, 4L, 5L, 6L, 0L)) {
                        CheckChip(tr.t("backup.dayShort.$day"), day in days, {
                            // Mindestens ein Tag bleibt.
                            set("weekdays" to if (day in days) (if (days.size > 1) days - day else days) else days + day)
                        })
                    }
                }
            }
        }
        if (every != "hourly") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.Bottom) {
                times.forEachIndexed { i, time ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (i == 0) Muted(t("update.autoTime"))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TimeField(time, { v -> set("times" to times.toMutableList().also { it[i] = v }) }, t("update.autoTime"))
                            if (times.size > 1) IconButton(WebIcons["close"], t("backup.removeTime"), { set("times" to times.filterIndexed { n, _ -> n != i }) })
                        }
                    }
                }
                if (times.size < 8) SmallButton(t("backup.addTime"), { set("times" to times + "12:00") })
            }
        } else {
            Labeled(t("backup.minute")) {
                TimeField(times.firstOrNull().orEmpty(), { v -> set("times" to listOf(v) + times.drop(1)) }, t("backup.minute"))
            }
        }
        val windowOn = job.str("windowFrom").isNotEmpty()
        CheckRow(windowOn, { on -> set("windowFrom" to if (on) "01:00" else "", "windowTo" to if (on) "06:00" else "") }, t("backup.window"))
        if (windowOn) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Labeled(t("backup.windowFrom")) { TimeField(job.str("windowFrom"), { set("windowFrom" to it) }, t("backup.windowFrom")) }
                Labeled(t("backup.windowTo")) { TimeField(job.str("windowTo"), { set("windowTo" to it) }, t("backup.windowTo")) }
            }
            Muted(t("backup.windowHint"))
        }
        Muted(t("backup.catchUp"))
    }
}

/**
 * Laufende VMs in den Quellen einer lesbaren Kopie (VmDisksChoice.svelte):
 * erscheint nur, wenn die gewählten Quellen VM-Platten enthalten.
 */
@Composable
private fun VmDisksChoice(vmDisks: JsonObject?, selection: Bk.Selection, onChange: (JsonObject) -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val query = Bk.vmDisksQuery(selection, vmDisks)
    var answer by remember { mutableStateOf<JsonObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isEmpty()) { answer = null; return@LaunchedEffect }
        // Tippen im Formular fragt nicht bei jedem Zeichen.
        delay(250)
        try {
            answer = BkApi.vmDisks(env.api, query)
            failed = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val disks = answer?.a("disks")?.objects().orEmpty()
    if (disks.isEmpty()) {
        if (failed) Muted(t("backup.vm.loadFailed"))
        return
    }
    val running = disks.filter { it.str("state") == "running" }
    val noSnapshot = listOf("vm_disk_raw_image", "vm_disk_snapshot_unproven")
    val raw = running.any { it.str("reason") in noSnapshot || it.str("way") == "pause" }
    val libvirt = vmDisks?.bool("libvirtSnapshot") == true || running.any { it.str("way") == "libvirt_snapshot" || it.str("reason") == "vm_disk_snapshot_unproven" }
    val mode = vmDisks?.str("mode").orEmpty()
    fun choose(mode: String? = null, rawChoice: String? = null, libvirtSnapshot: Boolean? = null) {
        val m = mode ?: vmDisks?.str("mode")?.ifEmpty { null } ?: "consistent"
        if (m == "skip") { onChange(jobj("mode" to "skip")); return }
        val r = rawChoice ?: vmDisks?.str("raw")?.ifEmpty { null } ?: "skip"
        val lv = libvirtSnapshot ?: (vmDisks?.bool("libvirtSnapshot") == true)
        onChange(if (lv) jobj("mode" to "consistent", "raw" to r, "libvirtSnapshot" to true) else jobj("mode" to "consistent", "raw" to r))
    }
    val reasons = setOf("vm_disk_busy", "vm_disk_passthrough", "vm_disk_backing_chain", "vm_disk_no_space", "vm_disk_snapshot_failed", "vm_disk_raw_image", "vm_disk_snapshot_unproven")
    fun wayText(d: JsonObject): String = if (d.str("way") == "libvirt_snapshot") tr.t(if (d.bool("agent")) "backup.vm.way.libvirt_snapshot.agent" else "backup.vm.way.libvirt_snapshot") else Bk.known(tr, "backup.vm.way.${d.str("way")}", d.str("way"))
    fun wayTone(d: JsonObject): Tone = when {
        d.str("way") == "btrfs_snapshot" || (d.str("way") == "libvirt_snapshot" && d.bool("agent")) -> Tone.Ok
        d.str("way") in listOf("copy", "pause", "libvirt_snapshot") -> Tone.Info
        else -> Tone.Warn
    }
    fun describe(d: JsonObject): String {
        val parts = mutableListOf(Fmt.bytes(tr, d.long("sizeBytes")), tr.t(if (d.str("state") == "running") "backup.vm.state.running" else "backup.vm.state.stopped"), if (d.str("format") == "raw") tr.t("backup.vm.format.raw") else d.str("format"))
        if (d.str("way") == "libvirt_snapshot" && d.bool("agent")) parts += tr.t("backup.vm.agent")
        val reason = d.str("reason")
        if (reason.isNotEmpty()) {
            val text = tr.t("backup.vm.reason.${if (reason in reasons) reason else "other"}", "need" to Fmt.bytes(tr, d.long("needBytes")), "free" to Fmt.bytes(tr, d.long("freeBytes")))
            if (!(reason == "vm_disk_raw_image" && d.str("format") == "raw")) parts += text
        }
        return parts.filter { it.isNotEmpty() }.joinToString(" · ")
    }
    SettingGroup(t("backup.vm.title"), desc = t("backup.vm.intro")) {
        if (vmDisks == null && (answer?.bool("choiceNeeded") == true || running.isNotEmpty())) NoticeLine(t("backup.vm.choiceNeeded"), Tone.Warn)
        SettingRow(
            t("backup.vm.row"),
            desc = if (disks.size == 1) t("backup.vm.count.one") else t("backup.vm.count.many", "count" to disks.size),
            icon = WebIcons["monitor"],
            id = "vm-disks",
            startOpen = true,
            control = {
                val options = (if (vmDisks == null) listOf("" to tr.t("backup.vm.choose")) else emptyList()) + listOf("consistent" to tr.t("backup.vm.mode.consistent"), "skip" to tr.t("backup.vm.mode.skip"))
                Select(mode, options, { if (it.isNotEmpty()) choose(mode = it) }, t("backup.vm.row"))
            },
        ) {
            for (d in disks) SettingSub("${d.str("vm")} · ${Bk.baseName(d.str("file"))}", desc = describe(d), control = { StatusChip(wayText(d), wayTone(d)) })
            if (libvirt && mode == "consistent") {
                SettingSub(t("backup.vm.libvirt"), desc = t("backup.vm.libvirt.desc"), control = {
                    Toggle(vmDisks?.bool("libvirtSnapshot") == true, { choose(libvirtSnapshot = it) }, tr.t("appform.on"), tr.t("appform.off"), label = tr.t("backup.vm.libvirt"))
                })
            }
            if (raw && mode == "consistent") {
                SettingSub(t("backup.vm.raw"), desc = t("backup.vm.raw.desc"), control = {
                    Select(vmDisks?.str("raw")?.ifEmpty { null } ?: "skip", listOf("skip" to tr.t("backup.vm.raw.skip"), "pause" to tr.t("backup.vm.raw.pause")), { choose(rawChoice = it) }, t("backup.vm.raw"))
                })
            }
        }
        if (mode == "skip") NoticeLine(t("backup.vm.skipNote"), Tone.Warn)
    }
}
