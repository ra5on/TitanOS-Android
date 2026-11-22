package io.github.ra5on.titanos.account

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loaded
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.withPath
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SliderField
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

// ---------------------------------------------------------------- Töne (sounds.ts)

internal val TONES = listOf("bell", "wood", "soft", "chime", "double")
private val NOTES: Map<String, List<Triple<Double, Double, Double>>> = mapOf(
    "bell" to listOf(Triple(523.25, 0.0, 0.8), Triple(784.0, 0.03, 0.65)),
    "wood" to listOf(Triple(261.63, 0.0, 0.22), Triple(392.0, 0.01, 0.15)),
    "soft" to listOf(Triple(349.23, 0.0, 0.65)),
    "chime" to listOf(Triple(392.0, 0.0, 0.6), Triple(523.25, 0.18, 0.65), Triple(659.25, 0.36, 0.7)),
    "double" to listOf(Triple(440.0, 0.0, 0.3), Triple(523.25, 0.36, 0.45)),
)

/** `sanitizeSound`: Klang, Lautstärke 0–100 (ab Werk 35), „wichtig“ oder „alle“. */
internal fun sanitizeSound(raw: JsonObject): Triple<String, Int, String> {
    val tone = raw.str("tone").takeIf { it in TONES } ?: "none"
    val volume = raw["volume"]?.let { raw.long("volume", 35).toInt().coerceIn(0, 100) } ?: 35
    val severity = if (raw.str("severity") == "all") "all" else "important"
    return Triple(tone, volume, severity)
}

/** Spielt den Klang wie `playSound` im Web: Sinustöne mit kurzem Anstieg und abklingender Hüllkurve. */
private suspend fun playTone(tone: String, volume: Int) = withContext(Dispatchers.Default) {
    val notes = NOTES[tone] ?: return@withContext
    val rate = 44100
    val total = notes.maxOf { it.second + it.third } + 0.05
    val pcm = ShortArray((total * rate).toInt())
    val peak = 0.065 * 3 * volume / 100.0
    for ((freq, offset, duration) in notes) {
        val start = (offset * rate).toInt()
        val n = (duration * rate).toInt()
        val attack = (0.025 * rate).toInt()
        val decay = ln(0.0001 / peak.coerceAtLeast(0.0002)) / (n - attack).coerceAtLeast(1)
        for (i in 0 until n) {
            val env = if (i < attack) peak * i / attack else peak * exp(decay * (i - attack))
            val idx = start + i
            if (idx >= pcm.size) break
            val v = pcm[idx] + (sin(2 * PI * freq * i / rate) * env * Short.MAX_VALUE)
            pcm[idx] = v.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
    }
    runCatching {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        kotlinx.coroutines.delay((total * 1000).toLong() + 200)
        track.release()
    }
}

/** Benachrichtigungstöne (SoundSettings.svelte): Klang, Lautstärke, Anlass, Ton testen, Speichern. */
@Composable
internal fun SoundRow(ctx: AccountCtx, prefs: Loaded<JsonObject>) {
    val tr = LocalTr.current
    val stored = prefs.value.o("sound")
    val (sTone, sVolume, sSeverity) = sanitizeSound(stored)
    var tone by remember(stored) { mutableStateOf(sTone) }
    var volume by remember(stored) { mutableStateOf(sVolume) }
    var severity by remember(stored) { mutableStateOf(sSeverity) }
    var saved by remember { mutableStateOf(false) }
    val run = rememberRunner()
    val preview = rememberRunner()
    val toneName: (String) -> String = { if (it == "none") tr.t("sound.none") else tr.t("sound.$it") }
    SettingRow(t("sound.title"), desc = t("sound.hint"), icon = WebIcons["volume"], summary = toneName(sTone), id = "sounds") {
        SettingSub(t("sound.tone"), control = {
            Select(tone, listOf("none" to t("sound.none")) + TONES.map { it to t("sound.$it") }, { tone = it; saved = false }, label = t("sound.tone"))
        })
        SettingSub("${t("sound.volume")} · $volume%", control = {
            SliderField(volume.toFloat(), { volume = (it / 5).toInt() * 5; saved = false }, 0f..100f, t("sound.volume"), "$volume%", steps = 19)
        })
        SettingSub(t("sound.when"), control = {
            Select(severity, listOf("important" to t("sound.important"), "all" to t("sound.all")), { severity = it; saved = false }, label = t("sound.when"))
        })
        Block {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SmallButton(t("sound.preview"), { preview.run { playTone(tone, volume) } }, enabled = tone != "none" && volume > 0 && !preview.running)
                SmallButton(t("common.save"), {
                    run.run(guard = GuardAction.Settings) {
                        val value = stored.with("tone" to tone, "volume" to volume, "severity" to severity)
                        ctx.calls.saveSound(value)
                        prefs.value = prefs.value.orEmpty().with("sound" to value)
                        saved = true
                    }
                }, kind = ButtonKind.Primary, enabled = !run.running)
                if (saved) Text(t("sound.saved"), style = TitanType.rowSub, color = Titan.colors.text)
            }
            RunnerState(run)
        }
    }
}

internal val BELL_GROUPS = listOf("storage", "disks", "backups", "updates", "ups", "usb", "cameras", "system")

/** Glocke (BellSettings.svelte): ab welcher Stufe, welche Bereiche. */
@Composable
internal fun BellRow(ctx: AccountCtx, prefs: Loaded<JsonObject>) {
    val stored = prefs.value.o("bell")
    val sMin = stored.str("minSeverity").takeIf { it == "warning" || it == "error" } ?: "info"
    var min by remember(stored) { mutableStateOf(sMin) }
    var groups by remember(stored) { mutableStateOf(BELL_GROUPS.associateWith { stored.o("groups")[it]?.toString() != "false" }) }
    var saved by remember { mutableStateOf(false) }
    val run = rememberRunner()
    val on = stringResource(io.github.ra5on.titanos.ui.R.string.ui_on)
    val off = stringResource(io.github.ra5on.titanos.ui.R.string.ui_off)
    SettingRow(t("bell.title"), desc = t("bell.intro"), icon = WebIcons["bell"], summary = t("notify.min.$sMin"), id = "bell") {
        SettingSub(t("bell.minSeverity"), control = {
            Select(min, listOf("info", "warning", "error").map { it to t("notify.min.$it") }, { min = it; saved = false }, label = t("bell.minSeverity"))
        })
        Block { Muted(t("bell.groups")) }
        for (g in BELL_GROUPS) {
            val label = t("bell.group.$g")
            SettingSub(label, control = { Toggle(groups[g] == true, { groups = groups + (g to it); saved = false }, on, off, label = label) })
        }
        Block {
            Muted(t("bell.storageErrors"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SmallButton(t("common.save"), {
                    run.run(guard = GuardAction.Settings) {
                        val value = stored.with("minSeverity" to min, "groups" to stored.o("groups").with(*groups.map { (k, v) -> k to v }.toTypedArray()))
                        ctx.calls.saveBell(value)
                        prefs.value = prefs.value.orEmpty().with("bell" to value)
                        saved = true
                    }
                }, kind = ButtonKind.Primary, enabled = !run.running)
                if (saved) Text(t("notify.saved"), style = TitanType.rowSub, color = Titan.colors.text)
            }
            RunnerState(run)
        }
    }
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

// ---------------------------------------------------------------- Mitteilungen (PushDevices.svelte, persönlich)

/**
 * Mitteilungen auf Geräten (für die Rolle „Benutzer“): die eingeschalteten
 * Browser mit ihren Arten, Testmitteilung und Entfernen. Einschalten gibt es
 * hier nicht – das ist Web Push eines Browsers; diese App bekommt ihre
 * Mitteilungen über ihre Kopplung.
 */
@Composable
internal fun PushRow(ctx: AccountCtx) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val state = rememberLoad { ctx.calls.push() }
    var mail by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mail = runCatching { ctx.calls.services().bool("mail") }.getOrDefault(false) }
    val run = rememberRunner()
    var message by remember { mutableStateOf<String?>(null) }
    SettingRow(t("push.title"), desc = t("push.introPersonal"), icon = WebIcons["bell"], id = "push", startOpen = true) {
        Block { Banner(stringResource(R.string.acc_push_app)) }
        state.error?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("common.retry"), onAction = { state.reload() }) }
        val devices = state.value.a("devices").objects()
        if (state.value != null && devices.isEmpty()) Block { Muted(t("push.none")) }
        for (d in devices) {
            val name = d.str("name").ifEmpty { d.str("service") }
            val off = d.strings("off").toSet()
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.text)
                    Badge(t(if (d.str("app") == "agent") "push.appAgent" else "push.appTitanOS"))
                }
                Muted(t("push.since", "date" to Fmt.date(tr, Fmt.instant(d.str("created")))))
                val kinds = buildList {
                    add("agent" to "push.kindAgent")
                    add("camera" to "push.kindCamera")
                    if (mail) add("mail" to "push.kindMail")
                }
                for ((kind, key) in kinds) {
                    CheckRow(kind !in off, { on ->
                        run.run(guard = GuardAction.Settings) {
                            val next = if (on) off - kind else off + kind
                            val updated = ctx.calls.pushKinds(d.str("id"), next, mail)
                            state.value = state.value.orEmpty().with("devices" to devices.map { if (it.str("id") == updated.str("id")) updated else it })
                        }
                    }, t(key), enabled = !run.running)
                }
                Buttons {
                    SmallButton(t("push.test"), {
                        message = null
                        run.run { ctx.calls.pushTest(d.str("id")); message = tr.t("push.testSent", "name" to name) }
                    }, enabled = !run.running)
                    SmallButton(t("push.remove"), {
                        ov.confirm(tr.t("push.remove"), tr.t("push.removeText", "name" to name), action = tr.t("push.remove"), cancel = tr.t("common.cancel")) {
                            run.run(guard = GuardAction.Settings) { ctx.calls.pushRemove(d.str("id")); state.refresh() }
                        }
                    }, enabled = !run.running)
                }
            }
        }
        message?.let { Muted(it) }
        RunnerState(run)
    }
}

// ---------------------------------------------------------------- E-Mail für Kamera-Meldungen (CameraMailRow.svelte)

/** Für die Rolle „Benutzer“, die mindestens eine Kamera sieht: die eigene Adresse für Kamera-Mails. */
@Composable
internal fun CameraMailRow(ctx: AccountCtx) {
    var answer by remember { mutableStateOf<JsonObject?>(null) }
    var address by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val run = rememberRunner()
    LaunchedEffect(Unit) {
        runCatching {
            if (ctx.calls.cameras().isEmpty()) return@runCatching
            val a = ctx.calls.notifications()
            answer = a
            address = a.o("settings").o("email").str("to")
        }
    }
    val a = answer ?: return
    val desc = buildString {
        append(t("nvr.account.mailHint"))
        if (!a.bool("mailServer")) append("\n").append(t("nvr.account.mailNoServer"))
    }
    SettingRow(t("nvr.account.mail"), desc = desc, icon = WebIcons["send"], id = "camera-mail", control = {
        Column(Modifier.widthIn(min = 200.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(address, { address = it; saved = false }, t("nvr.account.mail"), Modifier.fillMaxWidth(), placeholder = t("nvr.account.mailPlaceholder"), keyboard = KeyboardOptions(keyboardType = KeyboardType.Email))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SmallButton(t("common.save"), {
                    run.run(guard = GuardAction.Settings) {
                        val settings = a.o("settings")
                        val next = ctx.calls.saveNotifications(settings.with("email" to settings.o("email").with("to" to address.trim())))
                        answer = next
                        address = next.o("settings").o("email").str("to")
                        saved = true
                    }
                }, enabled = !run.running)
                if (saved) Text(t("nvr.account.mailSaved"), style = TitanType.rowSub, color = Titan.colors.text)
            }
        }
    })
    RunnerState(run)
}

// ---------------------------------------------------------------- Meine Benachrichtigungen (PersonalNotifications.svelte)

private val EVENTS = listOf("storage", "backups", "updates", "system")
private val CHANNELS = listOf("email", "ntfy", "telegram", "discord", "homeAssistant")

/** Der Entwurf wie `take()` im Web: Geheimnisse leer (gespeicherte kommen nie zurück), Ruhezeit mit Standardzeiten. */
internal fun draftOf(settings: JsonObject): JsonObject {
    val quiet = settings.o("quiet")
    return settings
        .with("ntfy" to settings.o("ntfy").with("token" to ""))
        .with("telegram" to settings.o("telegram").with("token" to ""))
        .with("discord" to settings.o("discord").with("webhook" to ""))
        .with("homeAssistant" to (settings["homeAssistant"] as? JsonObject ?: io.github.ra5on.titanos.settings.common.jobj("enabled" to false, "service" to "")))
        .with("quiet" to quiet.with("enabled" to quiet.bool("enabled"), "from" to quiet.str("from").ifEmpty { "22:00" }, "to" to quiet.str("to").ifEmpty { "07:00" }))
}

/** Was gespeichert wird: der Entwurf mit Adresse für Links und den zu löschenden Geheimnissen. */
internal fun notifyBody(draft: JsonObject, origin: String, clear: List<String>): JsonObject =
    draft.with("origin" to origin.ifEmpty { draft.str("origin") }, "clearSecrets" to clear)

/**
 * Meine Benachrichtigungen (Besitzer und Administratoren): was das Konto auf
 * eigenen Wegen erreicht, Ruhezeit, Wege mit Test, Zustellung, ein Speichern.
 */
@Composable
internal fun PersonalNotifications(ctx: AccountCtx) {
    val tr = LocalTr.current
    val env = LocalEnv.current
    val run = rememberRunner()
    val on = stringResource(io.github.ra5on.titanos.ui.R.string.ui_on)
    val off = stringResource(io.github.ra5on.titanos.ui.R.string.ui_off)
    var draft by remember { mutableStateOf<JsonObject?>(null) }
    var secretSet by remember { mutableStateOf(JsonObject(emptyMap())) }
    var mailServer by remember { mutableStateOf(false) }
    var delivery by remember { mutableStateOf(JsonObject(emptyMap())) }
    var clear by remember { mutableStateOf(listOf<String>()) }
    var haServices by remember { mutableStateOf(listOf<String>()) }
    var haConns by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf<Triple<String, Boolean, String>?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    val take: (JsonObject) -> Unit = { res ->
        draft = draftOf(res.o("settings"))
        secretSet = res.o("secretSet")
        delivery = res.o("delivery")
        mailServer = res.bool("mailServer")
        clear = emptyList()
    }
    LaunchedEffect(Unit) {
        try { take(ctx.calls.notifications()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { problem = tr.errorOf(e, detail = true) }
        runCatching { ctx.calls.haServices("") }.onSuccess { r ->
            haServices = r.strings("services")
            haConns = r.a("connections").objects().map { it.str("id") to it.str("name") }
        }
    }
    val conn = draft.o("homeAssistant").str("connection")
    LaunchedEffect(conn) {
        if (conn.isNotEmpty()) runCatching { ctx.calls.haServices(conn) }.onSuccess { haServices = it.strings("services") }
    }
    val origin = remember(env) { nasOrigin(env.api) }
    suspend fun store(d: JsonObject) {
        take(ctx.calls.saveNotifications(notifyBody(d, origin, clear)))
    }
    fun set(vararg path: String, value: Any?) { draft = draft?.withPath(*path, value = value); saved = false }

    SettingGroup(t("personalNotify.title"), desc = t("personalNotify.intro")) {
        problem?.let { Banner(it, tone = Tone.Error) }
        val d = draft
        if (d == null) {
            if (problem == null) io.github.ra5on.titanos.ui.components.LoadingLine(t("common.loading"))
            return@SettingGroup
        }
        val events = d.o("events")
        SettingRow(
            t("personalNotify.events"),
            icon = WebIcons["bell"],
            summary = t("personalNotify.eventsSummary", "count" to EVENTS.count { events.bool(it) }, "total" to EVENTS.size),
            id = "pn-events",
        ) {
            for (e in EVENTS) {
                val label = t("personalNotify.event.$e")
                SettingSub(label, control = { Toggle(events.bool(e), { set("events", e, value = it) }, on, off, label = label) })
            }
        }
        SettingRow(t("personalNotify.content"), icon = WebIcons["fileText"], control = {
            Select(d.str("content", "summary"), listOf("summary" to t("personalNotify.content.summary"), "none" to t("personalNotify.content.none")), { set("content", value = it) }, label = t("personalNotify.content"))
        })
        SettingRow(t("notify.language"), icon = WebIcons["globe"], control = {
            Select(d.str("language", "en"), listOf("de" to "Deutsch", "en" to "English"), { set("language", value = it) }, label = t("notify.language"))
        })
        val quiet = d.o("quiet")
        SettingRow(
            t("personalNotify.quiet"),
            desc = t("personalNotify.quietHint"),
            icon = WebIcons["clock"],
            id = if (quiet.bool("enabled")) "pn-quiet-on" else "pn-quiet",
            startOpen = quiet.bool("enabled"),
            control = { Toggle(quiet.bool("enabled"), { set("quiet", "enabled", value = it) }, on, off, label = t("personalNotify.quiet")) },
            content = if (!quiet.bool("enabled")) null else {
                {
                    SettingSub(t("personalNotify.quietFrom"), control = { TimeField(quiet.str("from"), t("personalNotify.quietFrom")) { set("quiet", "from", value = it) } })
                    SettingSub(t("personalNotify.quietTo"), control = { TimeField(quiet.str("to"), t("personalNotify.quietTo")) { set("quiet", "to", value = it) } })
                }
            },
        )
    }

    val d = draft ?: return
    @Composable
    fun secretNote(channel: String) {
        if (secretSet.bool(channel)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (channel !in clear) Muted(t("notify.secretStored"))
                LinkText(t(if (channel in clear) "notify.secretRemoved" else "notify.secretRemove")) {
                    clear = if (channel in clear) clear - channel else clear + channel
                }
            }
        }
    }
    @Composable
    fun testRow(channel: String) {
        if (!d.o(channel).bool("enabled")) return
        Block {
            SmallButton(t(if (testing == channel) "notify.testing" else "notify.test"), {
                tested = null
                testing = channel
                run.run {
                    try {
                        store(draft ?: return@run)
                        try {
                            ctx.calls.notifyTest(if (channel == "homeAssistant") "homeassistant" else channel)
                            tested = Triple(channel, true, "")
                        } catch (e: ApiException) {
                            tested = Triple(channel, false, e.message?.substringAfter(": ", "").orEmpty())
                        }
                    } finally {
                        testing = null
                    }
                }
            }, enabled = !run.running)
            tested?.takeIf { it.first == channel }?.let { (_, ok, detail) ->
                if (ok) Banner(t("notify.testOk"), tone = Tone.Ok)
                else Banner(t("notify.testFailed") + if (detail.isNotEmpty()) "\n$detail" else "", tone = Tone.Error)
            }
        }
    }
    @Composable
    fun onOff(channel: String, disabled: Boolean = false) {
        val label = t(if (channel == "homeAssistant") "notify.homeassistant" else "notify.$channel")
        Toggle(d.o(channel).bool("enabled"), { set(channel, "enabled", value = it) }, on, off, enabled = !disabled, label = label)
    }

    SettingGroup(t("personalNotify.channels")) {
        SettingRow(t("notify.email"), icon = WebIcons["send"], id = "pn-email", control = { onOff("email", disabled = !mailServer) }) {
            if (!mailServer) Block { Muted(t("personalNotify.noMailServer")) }
            SettingSub(t("notify.to"), control = {
                Field(d.o("email").str("to"), { set("email", "to", value = it) }, t("notify.to"), Modifier.fillMaxWidth(), placeholder = "me@example.org", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Email))
            })
            testRow("email")
        }
        SettingRow(t("notify.ntfy"), icon = WebIcons["bell"], id = "pn-ntfy", control = { onOff("ntfy") }) {
            SettingSub(t("notify.ntfyUrl"), control = {
                Field(d.o("ntfy").str("url"), { set("ntfy", "url", value = it) }, t("notify.ntfyUrl"), Modifier.fillMaxWidth(), placeholder = "https://ntfy.sh/thema", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
            })
            SettingSub(t("notify.token"), control = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    secretNote("ntfy")
                    Field(d.o("ntfy").str("token"), { set("ntfy", "token", value = it) }, t("notify.token"), Modifier.fillMaxWidth(), password = true, mono = true)
                }
            })
            testRow("ntfy")
        }
        SettingRow(t("notify.telegram"), icon = WebIcons["send"], id = "pn-telegram", control = { onOff("telegram") }) {
            SettingSub(t("notify.botToken"), control = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    secretNote("telegram")
                    Field(d.o("telegram").str("token"), { set("telegram", "token", value = it) }, t("notify.botToken"), Modifier.fillMaxWidth(), password = true, mono = true)
                }
            })
            SettingSub(t("notify.chatId"), control = {
                Field(d.o("telegram").str("chatID"), { set("telegram", "chatID", value = it) }, t("notify.chatId"), Modifier.fillMaxWidth(), mono = true)
            })
            testRow("telegram")
        }
        SettingRow(t("notify.discord"), icon = WebIcons["chat"], id = "pn-discord", control = { onOff("discord") }) {
            SettingSub(t("notify.webhook"), control = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    secretNote("discord")
                    Field(d.o("discord").str("webhook"), { set("discord", "webhook", value = it) }, t("notify.webhook"), Modifier.fillMaxWidth(), password = true, mono = true)
                }
            })
            testRow("discord")
        }
        val ha = d.o("homeAssistant")
        SettingRow(t("notify.homeassistant"), icon = WebIcons["home"], id = "pn-homeassistant", control = { onOff("homeAssistant", disabled = haServices.isEmpty() && !ha.bool("enabled")) }) {
            if (haServices.isEmpty()) Block { Muted(t("notify.haNotSetUp")) }
            if (haConns.size > 1) {
                SettingSub(t("notify.haConnection"), control = {
                    Select(ha.str("connection"), haConns.map { (id, name) -> (if (id == "main") "" else id) to name.ifEmpty { t("ha.conn.first") } }, { set("homeAssistant", "connection", value = it) }, label = t("notify.haConnection"))
                })
            }
            SettingSub(t("notify.haService"), control = {
                val services = (haServices + ha.str("service")).filter { it.isNotEmpty() }.distinct()
                Select(ha.str("service"), listOf("" to "–") + services.map { it to it }, { set("homeAssistant", "service", value = it) }, label = t("notify.haService"))
            })
            testRow("homeAssistant")
        }
    }

    SettingGroup(t("personalNotify.delivery")) {
        SettingRow(t("personal.persistQuiet"), desc = t("personal.persistHint"), icon = WebIcons["archive"], control = {
            Toggle(d.bool("persistQuiet"), { set("persistQuiet", value = it) }, on, off, label = t("personal.persistQuiet"))
        })
        SettingRow(t("personal.fallback"), icon = WebIcons["refresh"], control = {
            val choices = listOf("" to t("personal.noFallback")) + CHANNELS.filter { d.o(it).bool("enabled") }.map { (if (it == "homeAssistant") "homeassistant" else it) to it }
            Select(d.str("fallback"), choices, { set("fallback", value = it) }, label = t("personal.fallback"))
        })
        SettingRow(t("personal.delivery"), icon = WebIcons["activity"], id = "pn-delivery") {
            for ((channel, status) in delivery) {
                val st = status.o()
                val ok = st.bool("ok")
                Block {
                    Banner(
                        "$channel: ${t(if (ok) "personal.delivered" else "personal.failed")} · ${Fmt.dateTime(tr, st.str("time"))} (${st.int("attempts")})",
                        tone = if (ok) Tone.Ok else Tone.Error,
                    )
                }
            }
            Block {
                Buttons {
                    SmallButton(t("personal.refreshDelivery"), {
                        run.run { delivery = ctx.calls.notifications().o("delivery") }
                    }, enabled = !run.running)
                }
            }
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SmallButton(t(if (run.running && testing == null) "notify.saving" else "notify.save"), {
                    tested = null
                    run.run(guard = GuardAction.Settings) { store(draft ?: return@run); saved = true }
                }, kind = ButtonKind.Primary, enabled = !run.running)
                if (saved) Banner(t("notify.saved"), tone = Tone.Ok, modifier = Modifier.widthIn(max = 320.dp))
            }
            RunnerState(run)
        }
    }
}

/** Uhrzeit „HH:MM“ (wie `<input type="time">`). */
@Composable
private fun TimeField(value: String, label: String, onChange: (String) -> Unit) {
    Field(
        value,
        { v -> onChange(v.filter { it.isDigit() || it == ':' }.take(5)) },
        label,
        Modifier.widthIn(min = 110.dp, max = 160.dp),
        placeholder = "22:00",
        mono = true,
        singleLine = true,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
