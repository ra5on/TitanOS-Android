package io.github.ra5on.titanos.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.components.ValueLine
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun countText(count: Int, none: String, one: String, many: String, tr: Tr): String = when (count) {
    0 -> tr.t(none)
    1 -> tr.t(one)
    else -> tr.t(many, "count" to count)
}

// ---------------------------------------------------------------- Passkeys

/**
 * Passkeys (Passkeys.svelte): Liste und Entfernen wie im Web. Anlegen geht in
 * der App nicht – ein Passkey gehört zur Adresse der Weboberfläche (RP-ID),
 * und Android gäbe ihn einer App nur mit Digital Asset Links auf dieser
 * Domain. Statt eines Knopfs, der scheitern würde, steht dort der Grund.
 */
@Composable
internal fun PasskeysRow(ctx: AccountCtx) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val state = rememberLoad { ctx.calls.passkeys() }
    val run = rememberRunner()
    val v = state.value
    val keys = v.a("passkeys").objects()
    SettingRow(
        t("passkeys.title"),
        desc = t("passkeys.intro"),
        icon = WebIcons["key"],
        summary = if (v != null) countText(keys.size, "passkeys.countNone", "passkeys.countOne", "passkeys.count", tr) else if (state.error == null) t("common.loading") else null,
        id = "acc-passkeys",
    ) {
        Loading(state) { answer ->
            val list = answer.a("passkeys").objects()
            if (list.isEmpty()) Block { Muted(t("passkeys.none")) }
            for (key in list) {
                val name = key.str("name")
                val desc = buildString {
                    append(key.str("rpId"))
                    append(" · ")
                    append(t("passkeys.added", "date" to Fmt.date(tr, Fmt.instant(key.str("created")))))
                    if (key.bool("synced")) append(" · ").append(t("passkeys.synced"))
                }
                SettingSub(name, desc = desc, control = {
                    SmallButton(t("passkeys.remove"), {
                        ov.confirm(tr.t("passkeys.removeTitle"), tr.t("passkeys.removeText", "name" to name), action = tr.t("passkeys.remove"), cancel = tr.t("common.cancel")) {
                            run.run(guard = GuardAction.AccountSecurity) { state.value = ctx.calls.removePasskey(key.str("id")).let { r -> answer.withPasskeys(r) } }
                        }
                    }, enabled = !run.running)
                })
            }
            Block {
                if (!answer.bool("usable")) Banner(t("passkeys.notHere"), tone = Tone.Warn)
                Banner(stringResource(R.string.acc_passkeys_app))
            }
            RunnerState(run)
        }
    }
}

private fun JsonObject.withPasskeys(answer: JsonObject): JsonObject =
    if (answer.containsKey("passkeys")) JsonObject(this + ("passkeys" to answer.a("passkeys"))) else this

// ---------------------------------------------------------------- SSH

/** SSH-Schlüssel (SshKeys.svelte): Liste, Hinzufügen mit Passwort (und Code), Entfernen. */
@Composable
internal fun SshKeysRow(ctx: AccountCtx) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val state = rememberLoad { ctx.calls.sshKeys() }
    val run = rememberRunner()
    var adding by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    val v = state.value
    LaunchedEffect(v?.bool("sshEnabled")) {
        if (v?.bool("sshEnabled") == true && host.isEmpty()) {
            val sys = runCatching { ctx.calls.api.obj("system") }.getOrNull()
            host = sys.o("network").str("ipv4").substringBefore('/').ifEmpty { sys.str("hostname") }.ifEmpty { "nas" }
        }
    }
    val keys = v.a("keys").objects()
    val desc = when {
        v == null -> null
        !v.bool("mayLogin") -> t("ssh.onlyAdmins")
        !v.bool("sshEnabled") -> t("ssh.offHint")
        else -> t("ssh.onHint") + " ssh ${ctx.name}@$host"
    }
    SettingRow(
        t("ssh.keys"),
        desc = desc,
        icon = WebIcons["terminal"],
        summary = if (v != null) countText(keys.size, "ssh.countNone", "ssh.countOne", "ssh.count", tr) else if (state.error == null) t("common.loading") else null,
        id = "acc-ssh",
        control = if (v != null && v.bool("mayLogin") && !v.bool("sshEnabled")) ({ LinkText(t("ssh.settingsLink")) { env.open("/settings/access") } }) else null,
    ) {
        Loading(state) { answer ->
            val list = answer.a("keys").objects()
            if (list.isEmpty()) Block { Muted(t("ssh.noKeys")) }
            for (key in list) {
                val name = key.str("comment").ifEmpty { key.str("type") }
                SettingSub(name, desc = "${key.str("type")} · ${key.str("fingerprint")}", control = {
                    SmallButton(t("ssh.remove"), {
                        ov.confirm(
                            tr.t("ssh.removeTitle"),
                            tr.t("ssh.removeText", "name" to name),
                            action = tr.t("ssh.remove"),
                            cancel = tr.t("common.cancel"),
                            body = {
                                MonoText("${key.str("type")} · ${key.str("fingerprint")}", Modifier.padding(bottom = 10.dp), color = Titan.colors.muted)
                                if (list.size == 1) Banner(t("ssh.removeLast"), tone = Tone.Warn)
                            },
                        ) {
                            run.run(guard = GuardAction.AccountSecurity) { state.value = ctx.calls.removeSshKey(key.str("id")) }
                        }
                    }, enabled = !run.running)
                })
            }
            if (adding) {
                Block {
                    Labeled(t("ssh.keyLabel")) {
                        Field(
                            line, { line = it }, t("ssh.keyLabel"), Modifier.fillMaxWidth(),
                            placeholder = "ssh-ed25519 AAAAC3Nza name@host", mono = true, minHeight = 96.dp,
                            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        )
                    }
                    Muted(t("ssh.keyHint"))
                    ReconfirmFields(ctx.twoFactor, password, { password = it }, code, { code = it })
                    RunnerState(run)
                    Buttons {
                        SmallButton(t("common.cancel"), { adding = false; password = ""; code = ""; run.error = null }, enabled = !run.running)
                        SmallButton(t("ssh.add"), {
                            if (line.isNotBlank() && !run.running) run.run(guard = GuardAction.AccountSecurity) {
                                try {
                                    state.value = ctx.calls.addSshKey(line, password, code)
                                    line = ""; password = ""; code = ""; adding = false
                                } catch (e: Exception) {
                                    if (e.invalidCode()) code = ""
                                    throw e
                                }
                            }
                        }, kind = ButtonKind.Primary, enabled = !run.running && line.isNotBlank() && reconfirmComplete(ctx.twoFactor, password, code))
                    }
                }
            } else {
                Block {
                    RunnerState(run)
                    Buttons { SmallButton(t("ssh.addKey"), { adding = true }) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- WebDAV

/** WebDAV (WebDav.svelte): das Passwort für Apps, vom NAS erzeugt und nur einmal gezeigt. */
@Composable
internal fun WebDavRow(ctx: AccountCtx) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val env = LocalEnv.current
    val state = rememberLoad { ctx.calls.webdav() }
    val run = rememberRunner()
    val info = state.value ?: return
    if (!info.bool("enabled") && !info.bool("set")) return
    val set = info.bool("set")
    val server = remember(env) { nasOrigin(env.api) + "/dav/" }
    val desc = buildString {
        append(t("webdav.accountIntro"))
        append("\n").append(t("webdav.address")).append(" ").append(server)
        if (set && info.str("created").isNotEmpty()) append("\n").append(t("webdav.since", "time" to Fmt.dateTime(tr, info.str("created"))))
    }
    val title = t(if (set) "webdav.renew" else "webdav.create")
    SettingRow(
        "WebDAV",
        desc = desc,
        icon = WebIcons["globe"],
        id = "acc-webdav",
        control = { Controls {
            SmallButton(title, {
                ov.show { close ->
                    ReconfirmDialog(ctx, title, title, close) { password, code ->
                        val shown = ctx.calls.webdavCreate(password, code)
                        state.refresh()
                        ov.show { done -> WebDavShown(server, shown, done) }
                    }
                }
            }, enabled = !run.running && info.bool("enabled"))
            if (set) SmallButton(t("webdav.remove"), {
                ov.confirm(tr.t("webdav.remove"), tr.t("webdav.removeText"), action = tr.t("webdav.remove"), cancel = tr.t("common.cancel")) {
                    run.run(guard = GuardAction.AccountSecurity) { ctx.calls.webdavRemove(); state.refresh() }
                }
            }, enabled = !run.running)
        } },
    )
    RunnerState(run)
}

@Composable
private fun BoxScope.WebDavShown(server: String, shown: JsonObject, close: () -> Unit) {
    val clip = LocalClipboardManager.current
    AccountDialog(
        t("webdav.createdTitle"),
        onDismiss = close,
        text = t("webdav.createdHint"),
        actions = listOf(
            DialogAction(t("webdav.copy")) { clip.setText(AnnotatedString(shown.str("password"))) },
            DialogAction(t("wg.done")) { close() },
        ),
    ) {
        ValueLine(t("webdav.server"), server, mono = true)
        ValueLine(t("webdav.user"), shown.str("user"), mono = true)
        ValueLine(t("webdav.password"), shown.str("password"), mono = true)
    }
}

/**
 * Dialog mit Passwort und (mit 2FA) Code vor einer Aktion, die einen neuen Weg
 * ins Konto öffnet; [submit] bekommt beides. Ein falscher Code wird geleert.
 */
@Composable
internal fun BoxScope.ReconfirmDialog(
    ctx: AccountCtx,
    title: String,
    submitLabel: String,
    close: () -> Unit,
    text: String? = null,
    extra: (@Composable () -> Unit)? = null,
    submit: suspend (password: String, code: String) -> Unit,
) {
    val run = rememberRunner()
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AccountDialog(
        title,
        onDismiss = close,
        locked = run.running,
        text = text,
        actions = listOf(
            DialogAction(t("common.cancel")) { if (!run.running) close() },
            DialogAction(submitLabel) {
                if (!run.running && reconfirmComplete(ctx.twoFactor, password, code)) run.run(guard = GuardAction.AccountSecurity) {
                    try {
                        submit(password, code)
                        password = ""; code = ""
                        close()
                    } catch (e: Exception) {
                        if (e.invalidCode()) code = ""
                        throw e
                    }
                }
            },
        ),
    ) {
        extra?.invoke()
        ReconfirmFields(ctx.twoFactor, password, { password = it }, code, { code = it })
        RunnerState(run)
    }
}

/** Die Adresse des NAS, über die die App gerade spricht (`location.origin` im Web). */
internal fun nasOrigin(api: io.github.ra5on.titanos.settings.common.SettingsApi): String = api.origin

// ---------------------------------------------------------------- Gerätepasswörter

/** „Zuletzt abgeglichen“ wie pim/devices.ts `lastSync`. */
@Composable
private fun syncText(d: JsonObject): String {
    val tr = LocalTr.current
    val at = Fmt.instant(d.str("lastSync")) ?: return stringResource(R.string.acc_dav_sync_never)
    val zone = ZoneId.systemDefault()
    val day = at.atZone(zone).toLocalDate()
    val dayMonth = DateTimeFormatter.ofPattern(if (tr.de) "d.M." else "d/M").format(day)
    if (d.bool("lastSyncBeforeRestart")) return stringResource(R.string.acc_dav_sync_before_restart, dayMonth)
    val time = Fmt.time(tr, at)
    val today = LocalDate.now(zone)
    val text = when (day) {
        today -> stringResource(R.string.acc_dav_sync_today, time)
        today.minusDays(1) -> stringResource(R.string.acc_dav_sync_yesterday, time)
        else -> stringResource(R.string.acc_dav_sync_on, dayMonth, time)
    }
    return when (d.str("lastKind")) {
        "caldav" -> "$text (${stringResource(R.string.acc_dav_kind_caldav)})"
        "carddav" -> "$text (${stringResource(R.string.acc_dav_kind_carddav)})"
        else -> text
    }
}

/**
 * Gerätepasswörter für Kalender und Kontakte (pim/Devices.svelte): die Geräte
 * mit letztem Abgleich und „Entziehen …“, ein neues Passwort mit Kontopasswort
 * (und Code), nur einmal gezeigt und nirgends gespeichert.
 */
@Composable
internal fun DavDevicesRow(ctx: AccountCtx) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val clip = LocalClipboardManager.current
    val state = rememberLoad { ctx.calls.davDevices() }
    val run = rememberRunner()
    var name by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<JsonObject?>(null) }
    var copied by remember { mutableStateOf(false) }
    val v = state.value
    val list = v.a("devices").objects()
    val max = v.int("max", 20).takeIf { it > 0 } ?: 20
    val full = list.size >= max
    val revokeTitle = stringResource(R.string.acc_dav_revoke_title)
    val revokeConfirm = stringResource(R.string.acc_dav_revoke_confirm)
    val revokeText = stringResource(R.string.acc_dav_revoke_text)
    val createConfirm = stringResource(R.string.acc_dav_create_confirm)
    val createTitle = stringResource(R.string.acc_dav_create_title)
    SettingRow(
        stringResource(R.string.acc_dav_title),
        desc = stringResource(R.string.acc_dav_desc),
        icon = WebIcons["calendar"],
        summary = if (v != null) list.size.toString() + " / " + max else null,
        id = "acc-dav",
    ) {
        Loading(state) { _ ->
            for (d in list) {
                val created = stringResource(R.string.acc_dav_created, Fmt.date(tr, Fmt.instant(d.str("created"))))
                SettingSub(d.str("name"), desc = "$created · ${syncText(d)}", control = {
                    SmallButton(stringResource(R.string.acc_dav_revoke), {
                        ov.confirm(revokeTitle, revokeText.format(d.str("name")), action = revokeConfirm, cancel = tr.t("common.cancel")) {
                            run.run(guard = GuardAction.AccountSecurity) {
                                ctx.calls.davRevoke(d.str("id"))
                                if (shown?.str("id") == d.str("id")) shown = null
                                state.refresh()
                            }
                        }
                    }, enabled = !run.running)
                })
            }
            shown?.let { s ->
                val c = Titan.colors
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(c.bg).border(1.dp, c.border, RoundedCornerShape(10.dp)).padding(12.dp),
                ) {
                    Text(stringResource(R.string.acc_dav_once_title, s.str("name")), style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = c.text)
                    MonoText(s.str("password"), Modifier.padding(vertical = 8.dp), big = true)
                    Buttons {
                        SmallButton(
                            stringResource(if (copied) R.string.acc_dav_copied else R.string.acc_dav_copy),
                            { clip.setText(AnnotatedString(s.str("password"))); copied = true },
                            icon = WebIcons[if (copied) "check" else "copy"],
                        )
                    }
                    Muted(stringResource(R.string.acc_dav_once_hint), Modifier.padding(top = 6.dp))
                }
            }
            SettingSub(
                stringResource(R.string.acc_dav_new),
                desc = if (full) stringResource(R.string.acc_dav_full, max) else stringResource(R.string.acc_dav_new_desc),
                control = {
                    Column {
                        Field(name, { if (it.length <= 64) name = it }, stringResource(R.string.acc_dav_name_placeholder), Modifier.fillMaxWidth(), placeholder = stringResource(R.string.acc_dav_name_placeholder), enabled = !full)
                        Buttons {
                            SmallButton(stringResource(R.string.acc_dav_create), {
                                val n = name.trim()
                                ov.show { close ->
                                    ReconfirmDialog(ctx, createTitle.format(n), createConfirm, close) { password, code ->
                                        shown = ctx.calls.davCreate(n, password, code)
                                        copied = false
                                        name = ""
                                        state.refresh()
                                    }
                                }
                            }, kind = ButtonKind.Primary, enabled = name.isNotBlank() && !full && !run.running, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                },
            )
            RunnerState(run)
        }
    }
}
