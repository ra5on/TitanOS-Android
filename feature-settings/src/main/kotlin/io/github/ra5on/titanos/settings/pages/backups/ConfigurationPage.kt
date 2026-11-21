package io.github.ra5on.titanos.settings.pages.backups

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.without
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.settings.pages.backups.Bk.withKeepChoice
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.guard.rememberGuard
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Systemkonfiguration (routes/settings/backups/configuration): Stand der
 * Sicherung des Sticks, ihre Einstellungen (ein Formular, ein Speichern) und
 * die vorhandenen Kopien zum Herunterladen. Herunterladen verlangt wie im
 * Web das eigene Passwort erneut; die Datei legt Android über „Speichern
 * unter“ ab, die App behält nichts davon.
 */
@Composable
internal fun ConfigurationPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var volumes by remember { mutableStateOf(emptyList<String>()) }
    var draft by remember { mutableStateOf<JsonObject?>(null) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var ran by remember { mutableStateOf(false) }
    var passwordSet by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var encrypted by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var wanted by remember { mutableStateOf<JsonObject?>(null) }

    fun problem(e: Throwable) {
        val code = (e as? ApiException)?.code ?: "internal"
        error = tr.error(code) to if (code == "backup_failed") apiMessage(e) else ""
    }
    fun take(res: JsonObject, keepDraft: Boolean = false) {
        status = res.o("backup")
        volumes = res.strings("volumes")
        passwordSet = res.bool("passwordSet")
        if (draft == null || !keepDraft) draft = res.o("backup").o("settings")
    }
    suspend fun load() {
        error = null
        try {
            take(BkApi.config(env.api))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            problem(e)
        }
        loaded = true
    }
    LaunchedEffect(Unit) {
        load()
        encrypted = runCatching { BkApi.storage(env.api).a("volumes").objects().associate { it.str("name") to it.has("encryption") } }.getOrNull()
    }
    LaunchedEffect(saved) { if (saved) { delay(5_000); saved = false } }

    // Herunterladen: Android fragt nach dem Ort, danach holt die App die Datei und schreibt sie dorthin.
    fun write(backup: JsonObject, uri: Uri, again: Boolean = false) {
        scope.launch {
            try {
                val bytes = BkApi.downloadConfig(env.api, backup.str("volume"), backup.str("name"))
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!again && (e as? ApiException)?.code == "confirm_required") {
                    ov.show { close -> ConfirmPasswordDialog(tr.t("stick.confirmReason"), onConfirmed = { close(); write(backup, uri, again = true) }, onClose = close) }
                } else {
                    problem(e)
                }
            }
        }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        val b = wanted
        wanted = null
        if (uri != null && b != null) write(b, uri)
    }
    fun download(backup: JsonObject) {
        error = null
        guard(GuardAction.Settings) {
            scope.launch {
                try {
                    if (BkApi.confirmed(env.api)) {
                        wanted = backup
                        saveAs.launch(backup.str("name"))
                    } else {
                        ov.show { close ->
                            ConfirmPasswordDialog(tr.t("stick.confirmReason"), onConfirmed = { close(); wanted = backup; saveAs.launch(backup.str("name")) }, onClose = close)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem(e)
                }
            }
        }
    }

    PageIntro(t("stick.intro"))
    error?.let { (text, detail) ->
        Notice(Tone.Error) {
            NoticeText(text, Tone.Error)
            if (detail.isNotEmpty()) Mono(detail, color = noticeInk(Tone.Error))
            if (status == null) SmallButton(t("ui.retry"), { scope.launch { load() } })
        }
    }
    val s = status
    val d = draft
    if (s == null || d == null) {
        if (error == null && !loaded) LoadingLine(t("common.loading"))
        return
    }
    val settings = s.o("settings")
    val backups = s.a("backups").objects()
    val newest = backups.firstOrNull()
    val plainTarget = !settings.bool("encrypt") && s.str("target").isNotEmpty() && encrypted?.get(s.str("target")) == false
    fun volumeLabel(v: String): String {
        val enc = encrypted?.get(v) ?: return v
        return "$v · ${tr.t(if (enc) "stick.volumeEncrypted" else "stick.volumePlain")}"
    }
    fun normal(o: JsonObject) = o.withKeepChoice(o.long("keep"), o.o("versions").takeIf { o.has("versions") }, 1, 100).with("encrypt" to o.bool("encrypt"))
    val changed = normal(d) != normal(settings) || password.isNotEmpty()
    val passwordProblem = when {
        password.isEmpty() && repeat.isEmpty() -> ""
        password.length < 8 -> tr.t("stick.passwordShort")
        password != repeat -> tr.t("stick.passwordMismatch")
        else -> ""
    }
    fun set(vararg pairs: Pair<String, Any?>) { draft = (draft ?: return).with(*pairs) }

    SettingGroup(t("stick.state"), tools = {
        if (ran) Muted(t("stick.ran"))
        SmallButton(t(if (busy == "run") "stick.running" else "stick.run"), {
            guard(GuardAction.Settings) {
                busy = "run"
                error = null
                ran = false
                scope.launch {
                    try {
                        take(BkApi.runConfig(env.api), keepDraft = true)
                        ran = true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problem(e)
                    }
                    busy = null
                }
            }
        }, enabled = busy == null && s.str("target").isNotEmpty())
    }) {
        when {
            !settings.bool("enabled") -> NoticeLine(t("stick.off"), Tone.Warn)
            s.str("target").isEmpty() -> NoticeLine(tr.t(if (settings.str("volume").isNotEmpty()) "stick.volumeMissing" else "stick.noVolume", "volume" to settings.str("volume")), Tone.Warn)
            s.str("lastError").isNotEmpty() -> NoticeLine(t("stick.failed"), Tone.Error, s.str("lastError"))
            newest != null -> NoticeLine(tr.t("stick.ok", "time" to Fmt.dateTime(tr, newest.str("time")), "volume" to newest.str("volume")), Tone.Ok)
            else -> Muted(t("stick.none"))
        }
        if (plainTarget) NoticeLine(tr.t("stick.plainVolume", "volume" to s.str("target")), Tone.Warn)
        SettingRow(t("stick.target"), icon = WebIcons["folder"], control = { Mono(if (s.str("target").isNotEmpty()) "/mnt/${s.str("target")}/.titanos/backups" else "–") })
        SettingRow(t("stick.lastCheck"), icon = WebIcons["check"], control = { Value(s.str("lastCheck").let { if (it.isEmpty()) "–" else Fmt.dateTime(tr, it) }) })
        if (s.str("next").isNotEmpty()) SettingRow(t("stick.next"), icon = WebIcons["clock"], control = { Value(Fmt.dateTime(tr, s.str("next"))) })
        SettingRow(t("stick.count"), icon = WebIcons["archive"], control = { Value(backups.size.toString()) })
    }

    SettingGroup(t("stick.settings")) {
        SettingRow(t("stick.enabled"), desc = t("stick.enabledHint"), icon = WebIcons["power"], control = {
            Toggle(d.bool("enabled"), { set("enabled" to it) }, tr.t("appform.on"), tr.t("appform.off"), label = tr.t("stick.enabled"))
        })
        val schedule = d.str("schedule")
        SettingRow(
            t("stick.schedule"),
            desc = t("stick.schedule.hint"),
            icon = WebIcons["clock"],
            id = "stick-schedule-$schedule",
            startOpen = schedule.isNotEmpty(),
            control = {
                Select(schedule, listOf("" to tr.t("stick.schedule.off"), "daily" to tr.t("stick.schedule.daily"), "weekly" to tr.t("stick.schedule.weekly")), { v ->
                    set("schedule" to v, "time" to d.str("time").ifEmpty { "03:00" })
                }, t("stick.schedule"))
            },
            content = if (schedule.isEmpty()) null else {
                {
                    SettingSub(t("stick.schedule.time"), control = { TimeField(d.str("time"), { set("time" to it) }, t("stick.schedule.time")) })
                    if (schedule == "weekly") {
                        SettingSub(t("stick.schedule.day"), control = {
                            Select(d.int("weekday"), listOf(1, 2, 3, 4, 5, 6, 0).map { it to tr.t("backup.weekday.$it") }, { set("weekday" to it) }, t("stick.schedule.day"))
                        })
                    }
                }
            },
        )
        SettingRow(t("stick.pace"), desc = t("stick.pace.hint"), icon = WebIcons["refresh"], control = {
            Select(d.str("pace"), listOf("daily" to tr.t("sysdata.config.pace.daily"), "change" to tr.t("sysdata.config.pace.change")), { set("pace" to it) }, t("stick.pace"))
        })
        SettingRow(t("stick.volume"), icon = WebIcons["disk"], control = {
            Select(d.str("volume"), listOf("" to tr.t("stick.volumeAuto")) + volumes.map { it to volumeLabel(it) }, { set("volume" to it) }, t("stick.volume"))
        })
        SettingRow(t("stick.keep"), icon = WebIcons["archive"], control = {
            KeepVersions(jobj("last" to d.long("keep", 14), "daily" to 0, "weekly" to 0, "monthly" to 0), { k ->
                draft = (draft ?: return@KeepVersions).with("keep" to k.long("last")).without("versions")
            }, unit = "backups", rolling = false, min = 1, max = 100)
        })
        val encrypt = d.bool("encrypt")
        SettingRow(
            t("stick.encrypt"),
            desc = t("stick.encryptHint"),
            icon = WebIcons["lock"],
            id = "stick-encrypt-$encrypt",
            startOpen = encrypt,
            control = { Toggle(encrypt, { set("encrypt" to it) }, tr.t("appform.on"), tr.t("appform.off"), label = tr.t("stick.encrypt")) },
            content = if (!encrypt) null else {
                {
                    // Ein gespeichertes Passwort kommt nie zurück; dass eines gespeichert ist, steht unter dem Namen.
                    SettingSub(t(if (passwordSet) "stick.passwordNew" else "stick.password"), desc = if (passwordSet) t("notify.secretStored") else null, control = {
                        Field(password, { password = it }, t(if (passwordSet) "stick.passwordNew" else "stick.password"), Modifier.fillMaxWidth(), password = true)
                    })
                    SettingSub(t("stick.passwordRepeat"), control = { Field(repeat, { repeat = it }, t("stick.passwordRepeat"), Modifier.fillMaxWidth(), password = true) })
                    if (passwordProblem.isNotEmpty()) NoticeLine(passwordProblem, Tone.Warn)
                }
            },
        )
        val scheduleOk = schedule.isEmpty() || Bk.TIME.matches(d.str("time"))
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SmallButton(t(if (busy == "save") "ui.saving" else "notify.save"), {
                val body = (draft ?: return@SmallButton).let { dd ->
                    val b = dd.withKeepChoice(dd.long("keep"), dd.o("versions").takeIf { dd.has("versions") }, 1, 100)
                    if (password.isNotEmpty()) b.with("password" to password) else b.without("password")
                }
                guard(GuardAction.Settings) {
                    busy = "save"
                    saved = false
                    error = null
                    scope.launch {
                        try {
                            take(BkApi.saveConfig(env.api, body))
                            password = ""
                            repeat = ""
                            saved = true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problem(e)
                        }
                        busy = null
                    }
                }
            }, kind = ButtonKind.Primary, enabled = changed && busy == null && passwordProblem.isEmpty() && !(encrypt && !passwordSet && password.isEmpty()) && scheduleOk)
            if (saved && !changed) Muted(t("ui.saved"))
        }
    }

    SettingGroup(t("stick.backups")) {
        val asleep = s.o("unsearched").strings("volumes")
        if (asleep.isNotEmpty()) {
            Notice(Tone.Info) {
                NoticeText(tr.t(if (asleep.size == 1) "stick.asleepOne" else "stick.asleepMany", "volumes" to asleep.joinToString(", ")), Tone.Info)
                SmallButton(t("stick.asleepSearch"), {
                    searching = true
                    error = null
                    scope.launch {
                        try {
                            take(BkApi.config(env.api, asleep = true), keepDraft = true)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problem(e)
                        }
                        searching = false
                    }
                }, enabled = !searching)
            }
        }
        if (backups.isEmpty()) {
            Muted(t("stick.none"))
        } else {
            Muted(t("stick.downloadHint"))
            if (backups.any { !it.bool("encrypted") }) NoticeLine(t("stick.downloadPlain"), Tone.Warn) else Muted(t("stick.downloadEncrypted"))
            Panel {
                backups.forEachIndexed { i, b ->
                    if (i > 0) Divider()
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Body(Fmt.dateTime(tr, b.str("time")))
                            Muted("${tr.t("stick.volume")}: ${b.str("volume")} · ${tr.t("stick.version")}: ${b.str("version")} · ${tr.t("stick.size")}: ${Fmt.bytes(tr, b.long("sizeBytes"))}")
                        }
                        if (b.bool("encrypted")) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Icon(WebIcons["lock"], null, tint = Titan.colors.okText, modifier = Modifier.padding(0.dp))
                                Muted(t("stick.encryptedTitle"))
                            }
                        }
                        SmallButton(t("stick.download"), { download(b) }, icon = WebIcons["download"])
                    }
                }
            }
        }
    }
}
