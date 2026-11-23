package io.github.ra5on.titanos.settings.pages.security

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.R
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.withPath
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.NumberField
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Benachrichtigungen (routes/settings/notifications): Mitteilungen auf den
 * Geräten (Web Push und diese App), Bericht über das NAS, Allgemeines
 * (Stufe, Sprache, tägliche Sammlung) und die Wege E-Mail, ntfy, Telegram,
 * Discord und Home Assistant mit Testmeldung – ein Formular mit einem
 * „Speichern“ wie im Web. Gespeicherte Geheimnisse kommen nie zurück.
 */
@Composable
fun NotificationsPage(a: PageArgs) {
    val env = LocalEnv.current
    PageIntro(t("notify.intro"))
    LinkText(t("notify.personalHint"), { env.open("/account#bell") })
    SettingGroup(t("notify.groupDevices")) {
        SettingRow(t("push.title"), desc = t("push.intro"), icon = WebIcons["bell"], id = "push") { PushDevices() }
        HealthReportRow()
    }
    ChannelsForm(a)
}

// --- Geräte ------------------------------------------------------------

@Composable
private fun PushDevices() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val state = rememberLoad { env.api.obj("notifications/push").objs("devices") }
    val mail = rememberLoad { runCatching { env.api.obj("mail/access").bool("enabled") }.getOrDefault(false) }
    val run = rememberRunner()
    var message by remember { mutableStateOf<String?>(null) }
    SettingSub(stringResource(R.string.sec_push_app), desc = stringResource(R.string.sec_push_app_hint), control = {
        SmallButton(stringResource(R.string.sec_push_app_settings), {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, icon = WebIcons["bell"])
    })
    message?.let { Muted(it) }
    RunnerState(run)
    Loading(state) { devices ->
        if (devices.isEmpty()) Muted(t("push.none"))
        for (d in devices) {
            val id = d.str("id")
            val name = d.str("name").ifEmpty { d.str("service") }
            val off = d.strings("off")
            Divider()
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Buttons {
                    Text(name, style = TitanType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = Titan.colors.text)
                    Badge(t(if (d.str("app") == "agent") "push.appAgent" else "push.appTitanOS"))
                }
                Muted(t("push.since", "date" to Fmt.date(tr, Fmt.instant(d.str("created")))))
                val kinds = buildList {
                    add("nas" to "push.kindNas")
                    add("agent" to "push.kindAgent")
                    add("camera" to "push.kindCamera")
                    if (mail.value == true) add("mail" to "push.kindMail")
                }
                for ((kind, label) in kinds) {
                    CheckRow(kind !in off, { on ->
                        val next = if (on) off - kind else off + kind
                        run.run(guard = GuardAction.Settings) {
                            val body = jobj("nas" to ("nas" !in next), "agent" to ("agent" !in next), "camera" to ("camera" !in next)).let {
                                if (mail.value == true) it.with("mail" to ("mail" !in next)) else it
                            }
                            env.api.put("notifications/push/$id", body)
                            state.refresh()
                        }
                    }, t(label), enabled = !run.running)
                }
                Buttons {
                    SmallButton(t("push.test"), {
                        message = null
                        run.run {
                            try {
                                env.api.post("notifications/push/$id/test")
                                message = tr.t("push.testSent", "name" to d.str("name"))
                            } catch (e: Exception) {
                                state.refresh()
                                throw e
                            }
                        }
                    }, enabled = !run.running)
                    SmallButton(t("push.remove"), {
                        ov.confirm(tr.t("push.remove"), tr.t("push.removeText", "name" to name), action = tr.t("push.remove"), cancel = tr.t("common.cancel")) {
                            run.run(guard = GuardAction.Settings) {
                                env.api.delete("notifications/push/$id")
                                state.refresh()
                            }
                        }
                    }, kind = ButtonKind.Danger, enabled = !run.running)
                }
            }
        }
    }
}

// --- Bericht über das NAS ----------------------------------------------

private val WEEKDAYS = listOf(1, 2, 3, 4, 5, 6, 0)

@Composable
private fun HealthReportRow() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val state = rememberLoad { env.api.obj("health-report") }
    val run = rememberRunner()
    var draft by remember { mutableStateOf<JsonObject?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    val answer = state.value
    if (answer != null && draft == null) draft = answer.o("settings")
    val stored = answer?.o("settings")
    val summary = stored?.let {
        if (!it.bool("enabled")) tr.t("report.off") else tr.t(if (it.str("every") == "weekly") "report.weekly" else "report.monthly") + " · " + it.str("time")
    }
    fun take(next: JsonObject, keep: Boolean) {
        val edited = keep && draft != null && answer != null && draft != answer.o("settings")
        state.value = next
        if (!edited) draft = next.o("settings")
    }
    SettingRow(t("report.title"), desc = t("report.intro"), icon = WebIcons["activity"], id = "health-report", summary = summary) {
        val d = draft
        when {
            d != null -> {
                SettingSub(t("report.enabled"), control = {
                    Toggle(d.bool("enabled"), { draft = d.with("enabled" to it) }, onText(), offText(), enabled = !run.running, label = t("report.enabled"))
                })
                SettingSub(t("report.every"), control = {
                    Select(d.str("every", "weekly"), listOf("weekly" to t("report.weekly"), "monthly" to t("report.monthly")), { draft = d.with("every" to it) }, label = t("report.every"), enabled = !run.running)
                })
                if (d.str("every", "weekly") == "weekly") {
                    SettingSub(t("report.weekday"), control = {
                        Select(d.int("weekday"), WEEKDAYS.map { it to tr.t("report.day.$it") }, { draft = d.with("weekday" to it) }, label = t("report.weekday"), enabled = !run.running)
                    })
                } else {
                    SettingSub(t("report.dayOfMonth"), control = {
                        NumberField(d.int("day", 1), { v -> if (v != null) draft = d.with("day" to v.coerceIn(1, 28)) }, t("report.dayOfMonth"), enabled = !run.running)
                    })
                }
                SettingSub(
                    t("report.time"),
                    desc = answer?.str("next")?.takeIf { it.isNotEmpty() }?.let { t("report.next", "time" to Fmt.dateTime(tr, it)) },
                    control = { TimeField(d.str("time"), { draft = d.with("time" to it) }, t("report.time"), !run.running) },
                )
                RunnerState(run)
                Buttons {
                    SmallButton(t("common.save"), {
                        message = null
                        run.run(guard = GuardAction.Settings) { take(env.api.put("health-report", d).o(), false); message = tr.t("ui.saved") }
                    }, kind = ButtonKind.Primary, enabled = !run.running)
                    SmallButton(t("report.sendNow"), {
                        message = null
                        run.run { take(env.api.post("health-report/send").o(), true); message = tr.t("report.sent") }
                    }, enabled = !run.running)
                    SmallButton(t("report.preview"), { preview = !preview })
                    message?.let { Muted(it) }
                }
                if (preview && answer != null) ReportPreview(answer.o("preview"))
            }
            state.error != null -> Banner(tr.errorOf(state.error!!), tone = Tone.Error, action = t("ui.retry"), onAction = { state.reload() })
            else -> LoadingLine(t("common.loading"))
        }
    }
}

@Composable
private fun ReportPreview(p: JsonObject) {
    val c = Titan.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .border(1.dp, c.border, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(p.str("title"), style = TitanType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.text)
        for (s in p.objs("sections")) {
            val status = s.str("status")
            val mark = when (status) { "warning" -> "⚠"; "error" -> "✖"; else -> "✔" }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$mark ${s.str("title")}", style = TitanType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = when (status) { "warning" -> c.warnText; "error" -> c.errorText; else -> c.text })
                for (line in s.strings("lines")) Text(line, style = TitanType.rowSub, color = c.text, modifier = Modifier.padding(start = 20.dp))
            }
        }
    }
}

/** Uhrzeit „HH:MM“ (wie `<input type="time">`): nur Ziffern und Doppelpunkt. */
@Composable
internal fun TimeField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    var text by remember(value) { mutableStateOf(value) }
    Field(
        text,
        { v ->
            text = v.filter { it.isDigit() || it == ':' }.take(5)
            if (Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(text)) onChange(text)
        },
        label,
        Modifier.widthIn(min = 90.dp, max = 140.dp),
        placeholder = "08:00",
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
        enabled = enabled,
        singleLine = true,
    )
}

// --- Allgemein und Wege ------------------------------------------------

/** Die Antwort des NAS als Entwurf: Geheimnisse beginnen leer, fehlende Werte mit dem Standard (take() im Web). */
internal fun notifyDraft(settings: JsonObject): JsonObject {
    val email = settings.o("email")
    val digest = settings.o("digest")
    return settings.with(
        "email" to email.with("port" to (email.int("port").takeIf { it != 0 } ?: 587), "security" to email.str("security").ifEmpty { "starttls" }, "password" to ""),
        "ntfy" to settings.o("ntfy").with("token" to ""),
        "telegram" to settings.o("telegram").with("token" to ""),
        "discord" to settings.o("discord").with("webhook" to ""),
        "homeAssistant" to (if (settings.has("homeAssistant")) settings.o("homeAssistant") else jobj("enabled" to false, "service" to "")),
        "digest" to (if (digest.str("time").isNotEmpty()) digest else jobj("enabled" to false, "time" to "19:00", "errorsNow" to true)),
    )
}

/** Das erste Pflichtfeld eines eingeschalteten Wegs, das noch leer ist (reportValidity im Web), als Textschlüssel. */
internal fun missingField(d: JsonObject, secretSet: JsonObject): String? {
    val e = d.o("email")
    if (e.bool("enabled")) {
        if (e.str("host").isBlank()) return "notify.host"
        if (e.int("port") !in 1..65535) return "notify.port"
        if (e.str("from").isBlank()) return "notify.from"
        if (e.str("to").isBlank()) return "notify.to"
    }
    if (d.o("ntfy").bool("enabled") && d.o("ntfy").str("url").isBlank()) return "notify.ntfyUrl"
    val tg = d.o("telegram")
    if (tg.bool("enabled")) {
        if (tg.str("token").isBlank() && !secretSet.bool("telegram")) return "notify.botToken"
        if (tg.str("chatID").isBlank()) return "notify.chatId"
    }
    if (d.o("discord").bool("enabled") && d.o("discord").str("webhook").isBlank() && !secretSet.bool("discord")) return "notify.webhook"
    if (d.o("homeAssistant").bool("enabled") && d.o("homeAssistant").str("service").isBlank()) return "notify.haService"
    return null
}

/** Der Inhalt von `PUT /notifications/settings`: der ganze Entwurf, die Portnummer als Zahl, zu löschende Geheimnisse. */
internal fun notifyBody(d: JsonObject, clear: List<String>): JsonObject =
    d.withPath("email", "port", value = d.o("email").int("port")).with("clearSecrets" to clear)

@Composable
private fun ChannelsForm(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val state = rememberLoad { env.api.obj("notifications/settings") }
    val run = rememberRunner()
    var draft by remember { mutableStateOf<JsonObject?>(null) }
    var secretSet by remember { mutableStateOf(JsonObject(emptyMap())) }
    var clear by remember { mutableStateOf(listOf<String>()) }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var tested by remember { mutableStateOf<Triple<String, Boolean, String>?>(null) }
    var missing by remember { mutableStateOf<String?>(null) }
    var haServices by remember { mutableStateOf<List<String>?>(null) }
    var haConns by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var formError by remember { mutableStateOf<String?>(null) }

    fun loadHa(connection: String) {
        scope.launch {
            try {
                val q = if (connection.isNotEmpty()) "?connection=" + java.net.URLEncoder.encode(connection, "UTF-8") else ""
                val res = env.api.obj("homeassistant/notify-services$q")
                if (connection == (draft?.o("homeAssistant")?.str("connection") ?: "") || draft == null) haServices = res.strings("services")
                if (connection.isEmpty()) haConns = res.objs("connections").map { it.str("id") to it.str("name") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                haServices = emptyList()
            }
        }
    }
    fun take(res: JsonObject) {
        val d = notifyDraft(res.o("settings"))
        draft = d
        secretSet = res.o("secretSet")
        clear = emptyList()
        val conn = d.o("homeAssistant").str("connection")
        if (conn.isNotEmpty()) loadHa(conn)
    }
    LaunchedEffect(state.value) { state.value?.let { take(it) } }
    LaunchedEffect(Unit) { loadHa("") }

    suspend fun store(): Boolean {
        val d = draft ?: return false
        formError = null
        saved = false
        return try {
            take(env.api.put("notifications/settings", notifyBody(d, clear)).o())
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            formError = tr.errorOf(e, detail = e.apiCode == "invalid_notify_settings")
            false
        }
    }
    fun complete(): Boolean {
        val d = draft ?: return false
        missing = missingField(d, secretSet)
        return missing == null
    }
    fun save() {
        if (!complete()) return
        run.run(guard = GuardAction.Settings) {
            busy = "save"; tested = null
            try { saved = store() } finally { busy = null }
        }
    }
    fun test(channel: String) {
        if (!complete()) return
        run.run(guard = GuardAction.Settings) {
            busy = channel; tested = null
            try {
                if (store()) {
                    tested = try {
                        env.api.post("notifications/test", jobj("channel" to channel))
                        Triple(channel, true, "")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Triple(channel, false, e.apiDetail)
                    }
                }
            } finally { busy = null }
        }
    }

    if (state.value == null) {
        state.error?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { state.reload() }) }
        if (state.loading) LoadingLine(t("common.loading"))
        return
    }
    val d = draft ?: return
    fun set(vararg path: String, value: Any?) { draft = d.withPath(*path, value = value) }
    val locked = busy != null

    @Composable
    fun onOff(channel: String, key: String, title: String, disabled: Boolean = false) {
        Toggle(d.o(key).bool("enabled"), { set(key, "enabled", value = it) }, onText(), offText(), enabled = !disabled, label = title)
    }

    @Composable
    fun secretNote(channel: String): String? = if (!secretSet.bool(channel)) null else if (channel in clear) null else t("notify.secretStored")

    @Composable
    fun secretToggle(channel: String) {
        if (secretSet.bool(channel)) LinkText(t(if (channel in clear) "notify.secretRemoved" else "notify.secretRemove"), {
            clear = if (channel in clear) clear - channel else clear + channel
        })
    }

    @Composable
    fun textSub(title: String, value: String, onChange: (String) -> Unit, placeholder: String? = null, password: Boolean = false, desc: String? = null, keyboard: KeyboardOptions = KeyboardOptions.Default) {
        SettingSub(title, desc = desc, control = {
            Field(value, onChange, title, Modifier.fillMaxWidth(), placeholder = placeholder, password = password, mono = true, singleLine = true, keyboard = keyboard)
        })
    }

    @Composable
    fun testRow(channel: String) {
        Buttons {
            SmallButton(t(if (busy == channel) "notify.testing" else "notify.test"), { test(channel) }, enabled = !locked)
        }
        tested?.takeIf { it.first == channel }?.let { (_, ok, detail) ->
            if (ok) Banner(t("notify.testOk"), tone = Tone.Ok) else Banner(t("notify.testFailed") + if (detail.isNotEmpty()) "\n$detail" else "", tone = Tone.Error)
        }
    }

    SettingGroup(t("notify.general")) {
        SettingRow(t("notify.minSeverity"), icon = WebIcons["info"], control = {
            Select(d.str("minSeverity", "warning"), listOf("info" to t("notify.min.info"), "warning" to t("notify.min.warning"), "error" to t("notify.min.error")), { set("minSeverity", value = it) }, label = t("notify.minSeverity"))
        })
        SettingRow(t("notify.language"), icon = WebIcons["globe"], control = {
            Select(d.str("language", "de"), listOf("de" to "Deutsch", "en" to "English"), { set("language", value = it) }, label = t("notify.language"))
        })
        val digest = d.o("digest")
        SettingRow(
            t("digest.enabled"),
            icon = WebIcons["clock"],
            control = { Toggle(digest.bool("enabled"), { set("digest", "enabled", value = it) }, onText(), offText(), label = t("digest.enabled")) },
            content = if (!digest.bool("enabled")) null else ({
                SettingSub(t("digest.time"), control = { TimeField(digest.str("time"), { set("digest", "time", value = it) }, t("digest.time"), true) })
                SettingSub(t("digest.errorsNow"), control = {
                    Toggle(digest.bool("errorsNow"), { set("digest", "errorsNow", value = it) }, onText(), offText(), label = t("digest.errorsNow"))
                })
                Muted(t("digest.hint"))
            }),
        )
    }

    SettingGroup(t("notify.channels")) {
        val email = d.o("email")
        SettingRow(t("notify.email"), desc = t("notify.emailHint"), icon = WebIcons["send"], id = "notify-email", control = { onOff("email", "email", t("notify.email")) }) {
            textSub(t("notify.host"), email.str("host"), { set("email", "host", value = it) }, placeholder = "smtp.example.org")
            SettingSub(t("notify.port"), control = {
                NumberField(email.int("port").takeIf { it != 0 }, { v -> set("email", "port", value = (v ?: 0L).coerceIn(0, 65535)) }, t("notify.port"), allowEmpty = true)
            })
            SettingSub(t("notify.security"), control = {
                Select(email.str("security", "starttls"), listOf("starttls" to t("notify.security.starttls"), "tls" to t("notify.security.tls"), "none" to t("notify.security.none")), { set("email", "security", value = it) }, label = t("notify.security"))
            })
            if (email.str("security") == "none") Banner(t("notify.securityNone"), tone = Tone.Warn)
            textSub(t("notify.username"), email.str("username"), { set("email", "username", value = it) })
            textSub(t("notify.password"), email.str("password"), { set("email", "password", value = it) }, password = true, desc = secretNote("email"))
            secretToggle("email")
            textSub(t("notify.from"), email.str("from"), { set("email", "from", value = it) }, placeholder = "nas@example.org")
            textSub(t("notify.to"), email.str("to"), { set("email", "to", value = it) }, placeholder = "me@example.org")
            testRow("email")
        }
        val ntfy = d.o("ntfy")
        SettingRow(t("notify.ntfy"), desc = t("notify.ntfyHint"), icon = WebIcons["bell"], id = "notify-ntfy", control = { onOff("ntfy", "ntfy", t("notify.ntfy")) }) {
            textSub(t("notify.ntfyUrl"), ntfy.str("url"), { set("ntfy", "url", value = it) }, placeholder = "https://ntfy.sh/…", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
            textSub(t("notify.token"), ntfy.str("token"), { set("ntfy", "token", value = it) }, password = true, desc = secretNote("ntfy"))
            secretToggle("ntfy")
            testRow("ntfy")
        }
        val tg = d.o("telegram")
        SettingRow(t("notify.telegram"), desc = t("notify.telegramHint"), icon = WebIcons["chat"], id = "notify-telegram", control = { onOff("telegram", "telegram", t("notify.telegram")) }) {
            textSub(t("notify.botToken"), tg.str("token"), { set("telegram", "token", value = it) }, password = true, desc = secretNote("telegram"))
            secretToggle("telegram")
            textSub(t("notify.chatId"), tg.str("chatID"), { set("telegram", "chatID", value = it) })
            testRow("telegram")
        }
        val dc = d.o("discord")
        SettingRow(t("notify.discord"), desc = t("notify.discordHint"), icon = WebIcons["chat"], id = "notify-discord", control = { onOff("discord", "discord", t("notify.discord")) }) {
            textSub(t("notify.webhook"), dc.str("webhook"), { set("discord", "webhook", value = it) }, password = true, desc = secretNote("discord"))
            secretToggle("discord")
            testRow("discord")
        }
        val ha = d.o("homeAssistant")
        val services = haServices
        SettingRow(
            t("notify.homeassistant"),
            desc = t("notify.haHint"),
            icon = WebIcons["home"],
            id = "notify-homeassistant",
            control = { onOff("homeassistant", "homeAssistant", t("notify.homeassistant"), disabled = services.isNullOrEmpty() && !ha.bool("enabled")) },
        ) {
            if (services != null && services.isEmpty()) {
                Muted(t("notify.haNotSetUp"))
                LinkText(t("ha.title"), { a.openPage("homeassistant") })
            }
            if (haConns.size > 1) {
                SettingSub(t("notify.haConnection"), control = {
                    Select(ha.str("connection"), haConns.map { (id, name) -> (if (id == "main") "" else id) to name.ifEmpty { t("ha.conn.first") } }, { c ->
                        set("homeAssistant", "connection", value = c)
                        loadHa(c)
                    }, label = t("notify.haConnection"))
                })
            }
            SettingSub(t("notify.haService"), control = {
                val list = (services.orEmpty() + ha.str("service")).filter { it.isNotEmpty() }.distinct()
                Select(ha.str("service"), listOf("" to "–") + list.map { it to it }, { set("homeAssistant", "service", value = it) }, label = t("notify.haService"))
            })
            testRow("homeassistant")
        }
        missing?.let { Banner(stringResource(R.string.sec_required, tr.t(it)), tone = Tone.Warn) }
        formError?.let { Banner(it, tone = Tone.Error) }
        RunnerState(run)
        Buttons {
            SmallButton(t(if (busy == "save") "notify.saving" else "notify.save"), { save() }, kind = ButtonKind.Primary, enabled = !locked)
        }
        if (saved) Banner(t("notify.saved"), tone = Tone.Ok)
    }
}
