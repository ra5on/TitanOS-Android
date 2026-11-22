package io.github.ra5on.titanos.settings.pages.security

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.breakIds
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loaded
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
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

/**
 * Zugang (routes/settings/access): SSH mit erlaubten Netzen, WebDAV, das
 * Zertifikat der Weboberfläche, „Angemeldet bleiben“, „Sperren bei Leerlauf“
 * und Terminal und Konsolen – jede Einstellung eine Zeile, gespeichert beim
 * Ändern wie im Web, jeweils hinter dem Fingerabdruck „Zugang“.
 */
@Composable
fun AccessPage(a: PageArgs) {
    SettingGroup(t("access.groupRemote")) {
        SshRows()
        WebDavRow()
    }
    SettingGroup(t("access.groupWeb")) {
        CertificateRow(a)
        StayLoggedInRow()
        IdleLockRow()
    }
    TerminalGroup()
}

// --- SSH ---------------------------------------------------------------

/** Wie lib/ssh-networks.ts: eine Liste aus Zeilen, Kommas, Leerzeichen; doppelte weg. */
internal fun parseNetworks(text: String): List<String> = text.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.distinct()

internal fun addNetworks(text: String, more: List<String>): String = (parseNetworks(text) + more).distinct().joinToString("\n")

@Composable
private fun SshRows() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val ssh = rememberLoad { env.api.obj("system/ssh") }
    val system = rememberLoad { env.api.obj("system") }
    val run = rememberRunner()
    val netRun = rememberRunner()
    // Jede Änderung beginnt eine neue Runde: eine ältere Abfrage stellt den alten Stand nicht wieder her.
    var round by remember { mutableIntStateOf(0) }
    var polls by remember { mutableIntStateOf(0) }
    var stuck by remember { mutableStateOf(false) }
    val s = ssh.value
    LaunchedEffect(s, stuck) {
        if (s == null || s.bool("enabled") == s.bool("running") || stuck) return@LaunchedEffect
        delay(2000)
        if (++polls >= 30) stuck = true else {
            val r = round
            val next = runCatching { env.api.obj("system/ssh") }.getOrNull()
            if (next != null && r == round) ssh.value = next
        }
    }

    if (s != null) {
        SettingRow(
            t("access.sshEnable"),
            desc = t("access.sshShort"),
            icon = WebIcons["key"],
            id = "access-ssh",
            control = {
                StatusChip(if (s.bool("running")) t("access.sshRunning") else t("access.sshOff"), if (s.bool("running")) Tone.Ok else Tone.Inactive)
                Toggle(s.bool("enabled"), { on ->
                    run.run(guard = GuardAction.Access, ok = tr.t("ui.saved")) {
                        polls = 0; stuck = false; round++
                        ssh.value = env.api.put("system/ssh", jobj("enabled" to on)).o()
                    }
                }, onText(), offText(), enabled = !run.running, label = t("access.sshEnable"))
            },
        ) {
            Muted(t("access.sshText"))
            if (s.bool("enabled")) {
                val accounts = s.strings("accounts")
                val net = system.value.o("network")
                val host = net.str("ipv4").substringBefore('/').ifEmpty { system.value.str("hostname") }.ifEmpty { "nas" }
                SettingSub(t("access.sshWho"), control = { Value(if (accounts.isNotEmpty()) accounts.joinToString(", ") else t("access.sshNobody")) })
                SettingSub(t("access.sshCommand"), control = { Value("ssh ${env.user()}@$host", mono = true) })
                SettingSub(t("access.sshFingerprint"), control = { Value(s.str("fingerprint").ifEmpty { "–" }, mono = true) })
                if (accounts.isEmpty()) Banner(t("access.sshNoKeys"), tone = Tone.Warn, action = t("menu.account"), onAction = { env.open("/account") })
                Muted(t("access.sshNote"))
            }
        }
        RunnerState(run)
    }
    ssh.error?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { ssh.reload() }) }
    if (s == null && ssh.loading) LoadingLine(t("common.loading"))
    if (stuck && s != null && s.bool("enabled") != s.bool("running")) {
        Banner(t(if (s.bool("enabled")) "access.sshNotStarted" else "access.sshNotStopped"), tone = Tone.Warn, action = t("ui.retry"), onAction = {
            polls = 0; stuck = false; ssh.reload()
        })
    }

    if (s != null) {
        val allowed = s.strings("allowedNetworks")
        var text by remember { mutableStateOf(allowed.joinToString("\n")) }
        var edited by remember { mutableStateOf(false) }
        if (!edited && parseNetworks(text) != allowed) text = allowed.joinToString("\n")
        val changed = parseNetworks(text) != allowed
        val home = s.strings("homeNetworks")
        fun save(confirm: Boolean) {
            netRun.run(guard = GuardAction.Access, ok = tr.t("ui.saved")) {
                round++
                try {
                    val next = env.api.put("system/ssh/networks", jobj("networks" to parseNetworks(text), "confirm" to confirm)).o()
                    ssh.value = next
                    edited = false
                    text = next.strings("allowedNetworks").joinToString("\n")
                } catch (e: Exception) {
                    if (e.apiCode == "ssh_network_excludes_you" && !confirm) {
                        ov.show { close ->
                            FormDialog(
                                tr.t("access.sshNetworksConfirmTitle"),
                                onDismiss = close,
                                text = tr.t("access.sshNetworksConfirmText", "address" to s.str("clientAddress")),
                                actions = listOf(
                                    FormAction(tr.t("access.sshNetworksConfirmSubmit"), ButtonKind.Danger) { close(); save(true) },
                                    FormAction(tr.t("common.cancel")) { close() },
                                ),
                            )
                        }
                    } else throw e
                }
            }
        }
        SettingRow(
            t("access.sshNetworks"),
            desc = t("access.sshNetworksShort"),
            icon = WebIcons["globe"],
            id = "access-ssh-networks",
            summary = if (allowed.isNotEmpty()) allowed.joinToString(", ") else t("access.sshNetworksAllShort"),
        ) {
            Muted(t("access.sshNetworksText"))
            Value(if (allowed.isNotEmpty()) t("access.sshNetworksActive", "list" to allowed.joinToString(", ")) else t("access.sshNetworksAll"))
            if (s.has("networksError")) Banner(t("access.sshNetworksError", "detail" to s.str("networksError")), tone = Tone.Error)
            Field(text, { text = it; edited = true }, t("access.sshNetworks"), Modifier.fillMaxWidth().widthIn(max = 520.dp), placeholder = "192.168.1.0/24", mono = true, enabled = !netRun.running, minHeight = 96.dp)
            Buttons {
                SmallButton(t("access.sshNetworksHome"), { text = addNetworks(text, home); edited = true }, enabled = !netRun.running && home.isNotEmpty())
                SmallButton(t("access.sshNetworksTailscale"), { text = addNetworks(text, s.strings("tailscaleNetworks")); edited = true }, enabled = !netRun.running)
                SmallButton(t("access.sshNetworksNetbird"), { text = addNetworks(text, s.strings("netbirdNetworks")); edited = true }, enabled = !netRun.running)
                SmallButton(t("access.sshNetworksSave"), { save(false) }, kind = ButtonKind.Primary, enabled = !netRun.running && changed)
            }
            RunnerState(netRun)
            if (home.isEmpty()) Muted(t("access.sshNetworksNoHome"))
            if (s.str("clientAddress").isNotEmpty()) Muted(t("access.sshNetworksYou", "address" to s.str("clientAddress")))
            Muted(t("access.sshNetworksRecovery"))
        }
    }
}

// --- WebDAV ------------------------------------------------------------

@Composable
private fun WebDavRow() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val dav = rememberLoad { env.api.obj("system/webdav") }
    val run = rememberRunner()
    val v = dav.value
    val origin = remember { env.api.origin() }
    SettingRow(
        t("webdav.enabled"),
        desc = t("webdav.intro") + if (v?.bool("enabled") == true) "\n" + t("webdav.address") + " $origin/dav/" else "",
        icon = WebIcons["folder"],
        id = "access-webdav",
        control = if (v == null) null else ({
            Toggle(v.bool("enabled"), { on ->
                run.run(guard = GuardAction.Access) { dav.value = env.api.put("system/webdav", v.with("enabled" to on)).o() }
            }, onText(), offText(), enabled = !run.running, label = t("webdav.enabled"))
        }),
    )
    if (v?.bool("enabled") == true) LinkText(t("webdav.passwordLink"), { env.open("/account#webdav") })
    dav.error?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { dav.reload() }) }
    RunnerState(run)
}

// --- Zertifikat --------------------------------------------------------

private class CertState(val cert: JsonObject?, val noTls: Boolean)

@Composable
private fun CertificateRow(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state: Loaded<CertState> = rememberLoad {
        try {
            CertState(env.api.obj("system/certificate"), false)
        } catch (e: Exception) {
            if (e.apiCode == "read_only") CertState(null, true) else throw e
        }
    }
    val run = rememberRunner()
    var replacing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var certPem by remember { mutableStateOf("") }
    var keyPem by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("cert") }
    val chooseFile = androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.settings.R.string.sec_choose_file)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() }
            if (text != null) { if (target == "cert") certPem = text else keyPem = text }
        }
    }
    state.error?.let { Banner(tr.errorOf(it, detail = true), tone = Tone.Error, action = t("ui.retry"), onAction = { state.reload() }) }
    if (saved) Banner(t("access.certSaved"), tone = Tone.Ok)
    val st = state.value
    val c = st?.cert
    val kind = c?.str("kind").orEmpty()
    val kindText = when {
        c == null -> null
        kind == "titanos-ca" -> t("appaccess.certKindCa")
        else -> tr.or("access.certKind.$kind", kind)
    }
    val daysLeft = c?.let { Fmt.instant(it.str("notAfter"))?.let { end -> Duration.between(Instant.now(), end).toDays() } } ?: 0L
    val fingerprint = c?.str("fingerprint").orEmpty().chunked(2).joinToString(":")
    val parts: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = if (c == null || st.noTls) null else ({
        when {
            kind == "self-signed" -> Muted(t("access.certSelf"))
            kind == "titanos-ca" -> {
                Muted(t("appaccess.certCa"))
                LinkText(t("appaccess.certCaLink"), { a.openPage("proxy") })
            }
            daysLeft < 14 -> Banner(t("access.certExpiring", "days" to maxOf(daysLeft, 0)), tone = Tone.Warn)
        }
        val le = c.o("letsEncrypt")
        if (c.has("letsEncrypt")) {
            Banner(
                t("access.letsEncryptActive", "names" to le.strings("names").joinToString(", "), "date" to Fmt.dateTime(tr, le.str("notAfter"))),
                tone = Tone.Ok,
                action = t("letsencrypt.title"),
                onAction = { a.openPage("letsencrypt") },
            )
        } else LinkText(t("access.letsEncryptLink"), { a.openPage("letsencrypt") })
        SettingSub(t("access.certKind"), control = { Value(kindText.orEmpty()) })
        SettingSub(t("access.certIssuer"), control = { Value(c.str("issuer").ifEmpty { "–" }) })
        SettingSub(t("access.certUntil"), control = { Value(Fmt.dateTime(tr, c.str("notAfter"))) })
        SettingSub(t("access.certNames"), control = { Value(c.strings("names").joinToString(", "), mono = true) })
        SettingSub(t("access.certFingerprint"), control = { Value(breakIds(fingerprint), mono = true) })
        if (replacing) {
            Muted(t("access.certFormHint"))
            LabeledField(t("access.certChain"), certPem, { certPem = it }, placeholder = "-----BEGIN CERTIFICATE-----", mono = true, minHeight = 120.dp, enabled = !run.running)
            SmallButton(chooseFile, { target = "cert"; pick.launch(arrayOf("*/*")) }, icon = WebIcons["file"])
            LabeledField(t("access.certKey"), keyPem, { keyPem = it }, placeholder = "-----BEGIN PRIVATE KEY-----", mono = true, minHeight = 120.dp, enabled = !run.running)
            SmallButton(chooseFile, { target = "key"; pick.launch(arrayOf("*/*")) }, icon = WebIcons["file"])
            Muted(t("access.certKeyNote"))
            Buttons {
                SmallButton(t("common.cancel"), { replacing = false; certPem = ""; keyPem = "" }, enabled = !run.running)
                SmallButton(t("access.certUse"), {
                    saved = false
                    run.run(guard = GuardAction.Access, detail = true) {
                        val next = env.api.put("system/certificate", jobj("certificate" to certPem, "key" to keyPem)).o()
                        certPem = ""; keyPem = ""; replacing = false; saved = true
                        state.value = CertState(next, false)
                    }
                }, kind = ButtonKind.Primary, enabled = !run.running && certPem.isNotBlank() && keyPem.isNotBlank())
            }
        } else {
            Buttons {
                SmallButton(t(if (kind == "custom") "access.certReplace" else "access.certOwn"), { replacing = true; saved = false })
                if (kind == "custom") SmallButton(t("access.certRemove"), {
                    ov.confirm(tr.t("access.certRemoveTitle"), tr.t("access.certRemoveText"), action = tr.t("access.certRemoveSubmit"), cancel = tr.t("common.cancel")) {
                        saved = false
                        run.run(guard = GuardAction.Access) {
                            state.value = CertState(env.api.delete("system/certificate").o(), false)
                            saved = true
                        }
                    }
                }, kind = ButtonKind.Danger, enabled = !run.running)
            }
        }
        RunnerState(run)
    })
    SettingRow(
        t("access.cert"),
        desc = if (st?.noTls == true) t("access.certNone") else null,
        icon = WebIcons["lock"],
        id = "access-cert",
        summary = kindText,
        control = if (kind == "custom" && daysLeft < 14) ({ StatusChip(t("access.certExpiringShort"), Tone.Warn) }) else null,
        content = parts,
    )
}

// --- Angemeldet bleiben -----------------------------------------------

@Composable
private fun StayLoggedInRow() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val login = rememberLoad { env.api.obj("system/login") }
    val run = rememberRunner()
    val v = login.value
    fun label(days: Int): String = when {
        days == 0 -> tr.t("stay.offShort")
        days < 0 -> tr.t("stay.forever")
        days == 365 -> tr.t("stay.year")
        else -> tr.t("stay.days", "count" to days)
    }
    val days = v?.int("stayDays") ?: 0
    SettingRow(
        t("stay.title"),
        desc = t("stay.intro") + if (v != null && days == 0) " " + t("stay.offHint") else "",
        icon = WebIcons["clock"],
        id = "access-stay",
        control = if (v == null) null else ({
            val choices = v.ints("choices")
            Select(days, (if (days in choices) choices else choices + days).map { it to label(it) }, { picked ->
                run.run(guard = GuardAction.Access, ok = tr.t("appearance.saved")) { login.value = env.api.put("system/login", v.with("stayDays" to picked)).o() }
            }, label = t("stay.duration"), enabled = !run.running)
        }),
    )
    if (v != null && days != 0) Muted(t("stay.hint"))
    if (v != null && days < 0) Banner(t("stay.foreverWarn"), tone = Tone.Warn)
    login.error?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { login.reload() }) }
    RunnerState(run)
}

// --- Sperren bei Leerlauf ---------------------------------------------

private val LOCK_CHOICES = listOf(0, 5, 10, 15, 30, 60)
private val LOCK_CHOICES_PHONE = listOf(0, 5, 15, 30, 60, 240, 1440)

/**
 * Die automatische Sperre des eigenen Kontos (Desktop-Einstellungen
 * lockMinutes/lockMinutesPhone, lib/desktop/lock-link.svelte.ts): gelesen aus
 * `GET /preferences` (desktop), gespeichert mit `PUT /preferences/desktop` –
 * das ganze Objekt, nur die zwei Felder geändert.
 */
@Composable
private fun IdleLockRow() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val prefs = rememberLoad { env.api.obj("preferences").o("desktop") }
    val run = rememberRunner()
    val d = prefs.value
    fun label(m: Int) = if (m == 0) tr.t("desktop.settings.never") else tr.t("desktop.settings.minutes", "count" to m)
    fun withValue(list: List<Int>, v: Int) = if (v in list) list else (list + v).sorted()
    val minutes = d?.let { if (it.has("lockMinutes")) it.int("lockMinutes").coerceIn(0, 1440) else 15 }
    val phone = d?.let { if (it.has("lockMinutesPhone")) it.int("lockMinutesPhone").coerceIn(0, 1440) else 0 }
    fun save(m: Int, p: Int) {
        val cur = d ?: return
        run.run(guard = GuardAction.Access, ok = tr.t("ui.saved")) {
            val next = cur.with("lockMinutes" to m, "lockMinutesPhone" to p)
            env.api.put("preferences/desktop", next)
            prefs.value = next
        }
    }
    SettingRow(
        t("idleLock.title"),
        desc = t("idleLock.hint"),
        icon = WebIcons["lock"],
        id = "access-idle-lock",
        summary = if (minutes == null) null else t("idleLock.summary", "computer" to label(minutes), "phone" to label(phone ?: 0)),
    ) {
        if (minutes != null && phone != null) {
            SettingSub(t("desktop.settings.lock"), desc = t("desktop.settings.lockHint"), control = {
                Select(minutes, withValue(LOCK_CHOICES, minutes).map { it to label(it) }, { save(it, phone) }, label = t("desktop.settings.lock"), enabled = !run.running)
            })
            SettingSub(t("desktop.settings.lockPhone"), desc = t("desktop.settings.lockPhoneHint"), control = {
                Select(phone, withValue(LOCK_CHOICES_PHONE, phone).map { it to label(it) }, { save(minutes, it) }, label = t("desktop.settings.lockPhone"), enabled = !run.running)
            })
        }
        prefs.error?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { prefs.reload() }) }
        RunnerState(run)
    }
}

// --- Terminal und Konsolen ---------------------------------------------

private val IDLE = listOf(5, 15, 30, 60, 120, 0)

@Composable
private fun TerminalGroup() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val term = rememberLoad { env.api.obj("terminal") }
    val run = rememberRunner()
    SettingGroup(t("settings.consoles")) {
        if (term.value == null && term.loading) LoadingLine(t("common.loading"))
        term.error?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { term.reload() }) }
        val v = term.value ?: return@SettingGroup
        for (key in listOf("enabled", "confirm")) {
            val title = t(if (key == "enabled") "settings.terminal" else "settings.terminalConfirm")
            SettingRow(
                title,
                desc = t(if (key == "enabled") "settings.terminalDesc" else "settings.terminalConfirmDesc"),
                icon = WebIcons[if (key == "enabled") "terminal" else "lock"],
                control = {
                    Toggle(v.bool(key), { on ->
                        run.run(guard = GuardAction.Access, ok = tr.t("ui.saved")) { term.value = env.api.put("terminal", v.with(key to on)).o().let { if (it.isEmpty()) v.with(key to on) else it } }
                    }, onText(), offText(), enabled = !run.running, label = title)
                },
            )
        }
        val idle = if (v.has("idleMinutes")) v.int("idleMinutes") else 15
        SettingRow(
            t("settings.consoleIdle"),
            desc = t("settings.consoleIdleDesc"),
            icon = WebIcons["clock"],
            control = {
                Select(idle, (if (idle in IDLE) IDLE else IDLE + idle).map { m -> m to (if (m != 0) tr.t("settings.consoleIdleMinutes", "minutes" to m) else tr.t("settings.consoleIdleNever")) }, { picked ->
                    run.run(guard = GuardAction.Access, ok = tr.t("ui.saved")) { term.value = env.api.put("terminal", v.with("idleMinutes" to picked)).o().let { if (it.isEmpty()) v.with("idleMinutes" to picked) else it } }
                }, label = t("settings.consoleIdle"), enabled = !run.running)
            },
        )
        RunnerState(run)
    }
}
