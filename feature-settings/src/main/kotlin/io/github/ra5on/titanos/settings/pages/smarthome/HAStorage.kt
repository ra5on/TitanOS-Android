package io.github.ra5on.titanos.settings.pages.smarthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.components.ValueLine
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

private val USAGES = listOf("backup", "media", "share")
private val AGES = listOf(0, 1, 2, 3, 7, 14, 30)
private const val GB = 1_000_000_000.0

private fun placeOf(entry: JsonObject) = entry.str("folder").takeIf { it.isNotEmpty() }?.let { "${entry.str("share")}/$it" } ?: entry.str("share")

private fun problemText(tr: Tr, text: String) = if (text == "no_supervisor") tr.t("hastore.noSupervisor") else text

/** Was „Speicher einrichten“ abfragt (blankForm im Web). */
private class StoreForm {
    var account by mutableStateOf("homeassistant")
    var existing by mutableStateOf(false)
    var volume by mutableStateOf("")
    var share by mutableStateOf("homeassistant")
    var existingShare by mutableStateOf("")
    var folder by mutableStateOf("")
    var quotaGB by mutableStateOf("")
    var usage by mutableStateOf("backup")
    var readOnly by mutableStateOf(false)
    var register by mutableStateOf(true)
}

/**
 * Speicher für Home Assistant (HAStorage.svelte): Freigaben (oder Ordner
 * darin), die Home Assistant OS als Netzwerkspeicher einbindet – für Backups,
 * Medien oder Add-ons, alle mit einem eigenen Konto. Eine Zeile der
 * Verbindung, die zu ihren Einträgen und Aktionen aufgeht.
 */
@Composable
internal fun HAStorageRow(connection: String) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val state = rememberLoad(connection) { env.api.obj(haOn(connection, "homeassistant/storage")) }
    var override by remember(connection) { mutableStateOf<JsonObject?>(null) }
    val run = rememberRunner()
    var note by remember(connection) { mutableStateOf("") }
    val status = override ?: state.value
    val entries = status?.a("entries")?.objects().orEmpty()
    val volumes = status?.texts("volumes").orEmpty()
    val shares = status?.a("shares")?.objects().orEmpty()
    val homeAssistant = status?.bool("homeAssistant") == true
    val hasBackups = entries.any { it.str("usage") == "backup" }
    val account = status?.str("account").orEmpty()

    fun showResult(res: JsonObject) = ov.show { close -> ResultSheet(res, close) }
    fun reload() { override = null; state.reload() }

    SettingRow(
        t("hastore.title"),
        desc = t("hastore.intro"),
        icon = WebIcons["disk"],
        id = "ha-storage-$connection",
        summary = status?.let { if (entries.isNotEmpty()) tr.t("hastore.entryCount", "count" to entries.size) else tr.t("ha.conn.empty") },
    ) {
        val err = state.error
        when {
            err != null && status == null -> Banner(tr.errorOf(err), tone = Tone.Error, action = t("common.retry"), onAction = { reload() })
            status == null -> LoadingLine(t("common.loading"))
            else -> {
                if (account.isNotEmpty()) {
                    Plain(tr.t("hastore.accountLine", "account" to account, "server" to status.str("server")), Modifier.padding(vertical = 6.dp))
                    if (status.bool("accountMissing")) Banner(tr.t("hastore.accountMissing", "account" to account), tone = Tone.Warn)
                }
                for (entry in entries) {
                    Divider()
                    EntryItem(entry, busy = run.running, onRemove = {
                        ov.confirm(
                            tr.t("hastore.removeTitle"),
                            tr.t("hastore.removeText", "place" to placeOf(entry), "account" to account),
                            action = tr.t("hastore.remove"),
                            cancel = tr.t("common.cancel"),
                        ) {
                            run.run("remove", guard = GuardAction.Settings) {
                                val done = env.api.delete(haOn(connection, "homeassistant/storage/${URLEncoder.encode(entry.str("mount"), "UTF-8").replace("+", "%20")}")).o()
                                val removed = done.str("removedError")
                                note = if (removed.isNotEmpty()) tr.t("hastore.removeHAFailed", "error" to problemText(tr, removed)) else ""
                                reload()
                            }
                        }
                    }, onAge = { days ->
                        run.run("age", guard = GuardAction.Settings) {
                            val next = env.api.put(haOn(connection, "homeassistant/storage/${URLEncoder.encode(entry.str("mount"), "UTF-8").replace("+", "%20")}"), jobj("maxAgeDays" to days)).o()
                            if (next.has("entries")) override = next else reload()
                        }
                    })
                }
                if (entries.isNotEmpty()) Divider()
                if (volumes.isEmpty() && shares.isEmpty()) Banner(t("hastore.noVolume"), tone = Tone.Warn)
                run.error?.let { Banner(it, tone = Tone.Error) }
                if (note.isNotEmpty()) Banner(note, tone = Tone.Error)
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(t(if (entries.isNotEmpty()) "hastore.addMore" else "hastore.add"), {
                        val form = StoreForm().apply {
                            volume = volumes.firstOrNull().orEmpty()
                            existingShare = shares.firstOrNull()?.str("name").orEmpty()
                            if (entries.isNotEmpty()) {
                                existing = shares.isNotEmpty()
                                usage = "media"
                            }
                            register = homeAssistant
                        }
                        ov.show { close -> AddStorageSheet(connection, status, form, close) { res -> reload(); showResult(res) } }
                    }, kind = if (entries.isEmpty()) ButtonKind.Primary else ButtonKind.Normal, enabled = !run.running && (volumes.isNotEmpty() || shares.isNotEmpty()))
                    if (entries.isNotEmpty()) {
                        SmallButton(t("hastore.registerAgain"), {
                            ov.confirm(tr.t("hastore.registerAgain"), tr.t("hastore.registerAgainText", "account" to account), action = tr.t("hastore.registerAgain"), cancel = tr.t("common.cancel")) {
                                run.run("register", guard = GuardAction.Settings) {
                                    val res = env.api.post(haOn(connection, "homeassistant/storage/register")).o()
                                    reload()
                                    showResult(res)
                                }
                            }
                        }, enabled = !run.running && homeAssistant)
                    }
                    if (hasBackups) {
                        SmallButton(t(if (run.busy == "check") "hastore.checking" else "hastore.check"), {
                            run.run("check") {
                                val next = env.api.post(haOn(connection, "homeassistant/storage/check")).o()
                                if (next.has("entries")) override = next else reload()
                            }
                        }, enabled = !run.running && homeAssistant)
                    }
                }
                if (hasBackups) Muted(t("hastore.maxAgeHint"))
            }
        }
    }
}

@Composable
private fun EntryItem(entry: JsonObject, busy: Boolean, onRemove: () -> Unit, onAge: (Int) -> Unit) {
    val tr = LocalTr.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Mono(placeOf(entry))
            Badge(tr.or("hastore.usage.${entry.str("usage")}", entry.str("usage")))
            if (entry.bool("readOnly")) Badge(t("hastore.readOnlyShort"))
            SmallButton(t("hastore.remove"), onRemove, enabled = !busy)
        }
        Muted(tr.t("hastore.mountLine", "mount" to entry.str("mount")))
        if (entry.str("usage") == "backup") {
            val b = entry.o("backup")
            when {
                !entry.has("backup") -> Unit
                b.str("error").isNotEmpty() -> Muted(t("hastore.backupUnknown"))
                b.str("latest").isNotEmpty() -> {
                    val text = tr.t("hastore.backupLatest", "name" to b.str("name"), "date" to Fmt.dateTime(tr, b.str("latest")))
                    if (b.bool("overdue")) Banner(text, tone = Tone.Warn) else Plain(text)
                }
                else -> if (b.bool("overdue")) Banner(t("hastore.backupNone"), tone = Tone.Warn) else Muted(t("hastore.backupNone"))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Muted(t("hastore.maxAge"))
                val current = entry.int("maxAgeDays")
                val options = (AGES + current).distinct().sorted().map { d -> d to if (d > 0) tr.t("hastore.days", "count" to d) else tr.t("hastore.off") }
                Select(current, options, onAge, label = t("hastore.maxAge"), enabled = !busy)
            }
        }
    }
}

@Composable
private fun BoxScope.AddStorageSheet(connection: String, status: JsonObject, form: StoreForm, close: () -> Unit, onMade: (JsonObject) -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val run = rememberRunner()
    val entries = status.a("entries").objects()
    val shares = status.a("shares").objects()
    val volumes = status.texts("volumes")
    val homeAssistant = status.bool("homeAssistant")
    val account = status.str("account")
    val ready = !run.running && (account.isNotEmpty() || form.account.isNotEmpty()) &&
        (if (form.existing) form.existingShare.isNotEmpty() else form.volume.isNotEmpty() && form.share.isNotEmpty())
    DialogSheet(
        t(if (entries.isNotEmpty()) "hastore.addMore" else "hastore.add"),
        onDismiss = { if (!run.running) close() },
        buttons = listOf(
            SheetButton(t("common.cancel"), enabled = !run.running) { close() },
            SheetButton(t(if (run.running) "hastore.settingUp" else "hastore.setUp"), primary = true, enabled = ready) {
                val register = form.register && homeAssistant
                val readOnly = form.readOnly && form.usage != "backup"
                val body = if (form.existing) {
                    jobj("account" to form.account, "existing" to true, "share" to form.existingShare, "folder" to form.folder, "usage" to form.usage, "readOnly" to readOnly, "register" to register)
                } else {
                    jobj(
                        "account" to form.account, "volume" to form.volume, "share" to form.share,
                        "quotaBytes" to Math.round((form.quotaGB.replace(',', '.').toDoubleOrNull() ?: 0.0) * GB),
                        "usage" to form.usage, "readOnly" to readOnly, "register" to register,
                    )
                }
                run.run(guard = GuardAction.Settings) {
                    val made = env.api.post(haOn(connection, "homeassistant/storage"), body).o()
                    close()
                    onMade(made)
                }
            },
        ),
    ) {
        if (shares.isNotEmpty()) {
            RadioList(form.existing, listOf(Triple(false, t("hastore.newShare"), null), Triple(true, t("hastore.existingShare"), null)), { form.existing = it })
        }
        if (form.existing) {
            Muted(t("hastore.share"))
            Select(form.existingShare, shares.map { it.str("name") to "${it.str("name")} (${it.str("volume")})" }, { form.existingShare = it; form.folder = "" }, label = t("hastore.share"))
            Muted(t("hastore.folder"))
            Mono(if (form.folder.isNotEmpty()) "${form.existingShare}/${form.folder}" else tr.t("hastore.wholeShare"))
            SmallButton(t("hastore.chooseFolder"), {
                ov.show { c -> FolderPicker(form.existingShare, form.folder, c) { form.folder = it } }
            }, enabled = form.existingShare.isNotEmpty())
        } else {
            Muted(t("hastore.volume"))
            Select(form.volume, volumes.map { it to it }, { form.volume = it }, label = t("hastore.volume"))
            Field(form.share, { form.share = it }, label = t("hastore.share"), placeholder = t("hastore.share"), mono = true, singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
            Muted(t("hastore.quota"))
            Field(form.quotaGB, { form.quotaGB = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' } }, label = t("hastore.quota"), placeholder = t("hastore.quotaNone"), singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = "GB", modifier = Modifier.fillMaxWidth())
        }
        if (account.isEmpty()) {
            Muted(t("hastore.account"))
            Field(form.account, { form.account = it }, label = t("hastore.account"), mono = true, singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        }
        Legend(t("hastore.usage"))
        RadioList(form.usage, USAGES.map { Triple(it, tr.t("hastore.usage.$it"), tr.t("hastore.usageHint.$it")) }, { form.usage = it })
        if (form.usage != "backup") CheckRow(form.readOnly, { form.readOnly = it }, t("hastore.readOnly"))
        if (form.existing) Muted(t("hastore.existingHint"))
        CheckRow(form.register, { form.register = it }, t("hastore.register"), enabled = homeAssistant)
        Muted(t(if (!homeAssistant) "hastore.registerOff" else if (entries.isNotEmpty()) "hastore.registerHintMore" else "hastore.registerHint"))
        run.error?.let { Banner(it, tone = Tone.Error) }
    }
}

/** Ordner einer Freigabe wählen, eine Ebene nach der anderen. */
@Composable
private fun BoxScope.FolderPicker(share: String, start: String, close: () -> Unit, onPick: (String) -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    var dir by remember { mutableStateOf(start) }
    val list = rememberLoad(dir) {
        val q = "root=" + URLEncoder.encode("share/$share", "UTF-8") + "&path=" + URLEncoder.encode(dir, "UTF-8")
        env.api.list("files/list?$q").objects().filter { it.bool("dir") && !it.str("name").startsWith(".") }.map { it.str("name") }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    DialogSheet(
        tr.t("hastore.chooseFolderIn", "share" to share),
        onDismiss = close,
        buttons = listOf(
            SheetButton(t("common.cancel")) { close() },
            SheetButton(t("hastore.useThisFolder"), primary = true) { onPick(dir); close() },
        ),
    ) {
        Mono(share + if (dir.isNotEmpty()) "/$dir" else "", muted = true)
        list.error?.let { Banner(tr.errorOf(it), tone = Tone.Error) }
        val names = list.value
        if (names == null && list.loading) LoadingLine(t("common.loading"))
        if (names != null) {
            if (dir.isNotEmpty()) SmallButton("‹ " + t("hastore.up"), { dir = if ('/' in dir) dir.substringBeforeLast('/') else "" })
            if (names.isEmpty()) Muted(t("hastore.noSubfolders"))
            for (name in names) SmallButton(name, { dir = if (dir.isNotEmpty()) "$dir/$name" else name }, icon = WebIcons["folder"], modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Ergebnis nach Einrichten oder erneutem Eintragen; das Passwort steht nur hier und nur einmal. */
@Composable
private fun BoxScope.ResultSheet(result: JsonObject, close: () -> Unit) {
    val tr = LocalTr.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val results = result.a("results").objects()
    DialogSheet(t("hastore.resultTitle"), onDismiss = close, buttons = listOf(SheetButton(t("common.close"), primary = true) { close() })) {
        for (r in results) {
            if (r.bool("ok")) Banner(tr.t("hastore.registered", "name" to r.str("mount")), tone = Tone.Ok)
            else Banner(tr.t("hastore.registerFailedFor", "name" to r.str("mount"), "error" to problemText(tr, r.str("error"))), tone = Tone.Warn)
        }
        if (result.has("entry")) {
            val entry = result.o("entry")
            val setup = result.o("setup")
            Plain(t(if (results.all { it.bool("ok") }) "hastore.valuesKept" else "hastore.valuesHowTo"))
            ValueLine(t("hastore.mount"), entry.str("mount"), mono = true)
            ValueLine(t("hastore.usage"), tr.or("hastore.usage.${entry.str("usage")}", entry.str("usage")))
            ValueLine(t("hastore.server"), setup.str("server"), mono = true)
            ValueLine(t("hastore.protocol"), "Samba/Windows (CIFS)")
            ValueLine(t("hastore.share"), placeOf(entry), mono = true)
            ValueLine(t("hastore.account"), setup.str("account"), mono = true)
        }
        val password = result.str("password")
        if (password.isNotEmpty()) {
            ValueLine(t("hastore.password"), password, mono = true)
            SmallButton(t(if (copied) "hastore.copied" else "hastore.copy"), { copied = copyText(context, password) }, icon = WebIcons["copy"])
            Banner(t("hastore.passwordOnce"), tone = Tone.Warn)
        } else if (result.has("entry")) {
            Muted(t("hastore.passwordSame"))
        }
    }
}
