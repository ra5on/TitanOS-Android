package io.github.ra5on.titanos.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject

private const val MIN_PASSWORD_LENGTH = 10

/** Passwort (Zeile mit „Passwort ändern“ und PasswordDialog.svelte für das eigene Konto). */
@Composable
internal fun PasswordRow(ctx: AccountCtx) {
    val ov = LocalOverlays.current
    SettingRow(
        t("account.password"),
        desc = t("account.passwordText"),
        icon = WebIcons["lock"],
        id = "acc-password",
        control = { SmallButton(t("account.changePassword"), { ov.show { close -> PasswordDialog(ctx) { close(); ctx.sessions.reload() } } }) },
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PasswordDialog(ctx: AccountCtx, close: () -> Unit) {
    val tr = LocalTr.current
    val run = rememberRunner()
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    AccountDialog(
        t("users.passwordTitle", "name" to ctx.name),
        onDismiss = close,
        locked = run.running,
        actions = listOf(
            DialogAction(t("common.cancel")) { if (!run.running) close() },
            DialogAction(t("users.savePassword")) {
                problem = null
                when {
                    password.codePointCount(0, password.length) < MIN_PASSWORD_LENGTH -> problem = tr.error("password_too_short")
                    password != repeat -> problem = tr.t("auth.passwordMismatch")
                    !run.running -> run.run(guard = GuardAction.AccountSecurity) {
                        ctx.calls.changePassword(ctx.name, current, password)
                        close()
                    }
                }
            },
        ),
    ) {
        Labeled(t("users.currentPassword")) { Field(current, { current = it }, t("users.currentPassword"), Modifier.fillMaxWidth(), password = true) }
        Labeled(t("users.newPassword")) { Field(password, { password = it }, t("users.newPassword"), Modifier.fillMaxWidth(), password = true) }
        Muted(t("auth.passwordHint", "min" to MIN_PASSWORD_LENGTH))
        Labeled(t("auth.passwordRepeat")) { Field(repeat, { repeat = it }, t("auth.passwordRepeat"), Modifier.fillMaxWidth(), password = true) }
        Muted(t("users.passwordNote"))
        problem?.let { Banner(it, tone = Tone.Warn) }
        RunnerState(run)
    }
}

private enum class Confirm { Begin, Disable, Renew }

/**
 * Zwei-Faktor-Anmeldung: einrichten (Passwort, dann QR-Code und Kontrollcode),
 * neue Wiederherstellungscodes, ausschalten – wie +page.svelte.
 */
@Composable
internal fun TwoFactorRow(ctx: AccountCtx) {
    val ov = LocalOverlays.current
    val run = rememberRunner()
    var setup by remember { mutableStateOf<JsonObject?>(null) }
    var code by remember { mutableStateOf("") }
    val showCodes: (List<String>) -> Unit = { codes -> ov.show { close -> RecoveryDialog(ctx.name, codes, close) } }
    val open: (Confirm) -> Unit = { kind ->
        ov.show { close -> TwoFactorConfirm(ctx, kind, close, onBegun = { setup = it; code = "" }, onCodes = showCodes) }
    }
    val s = setup
    SettingRow(
        t("account.twoFactor"),
        desc = t(if (ctx.twoFactor) "account.twoFactorOn" else "account.twoFactorOff"),
        icon = WebIcons["shield"],
        id = if (s != null) "acc-2fa-setup" else "acc-2fa",
        startOpen = s != null,
        control = { Controls {
            StatusChip(t(if (ctx.twoFactor) "plugins.active" else "plugins.off"), if (ctx.twoFactor) Tone.Ok else Tone.Inactive)
            if (ctx.twoFactor) {
                SmallButton(t("account.recoveryRenew"), { open(Confirm.Renew) })
                SmallButton(t("account.twoFactorDisable"), { open(Confirm.Disable) })
            } else if (s == null) {
                SmallButton(t("account.twoFactorSetup"), { open(Confirm.Begin) }, kind = ButtonKind.Primary)
            }
        } },
        content = if (s == null) null else {
            {
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    QrImage(s.str("uri"), t("account.twoFactor"))
                    Column(Modifier.widthIn(min = 200.dp, max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Muted(t("account.scan"))
                        Labeled(t("account.secret")) {
                            val c = Titan.colors
                            MonoText(
                                s.str("secret"),
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bg).border(1.dp, c.border, RoundedCornerShape(10.dp)).padding(12.dp),
                                big = true,
                            )
                        }
                        CodeField(code, { code = it }, t("account.enterCode"))
                        RunnerState(run)
                        Buttons {
                            SmallButton(t("common.cancel"), { setup = null; code = ""; run.error = null })
                            SmallButton(t("account.enable"), {
                                if (!run.running && code.isNotBlank()) run.run(guard = GuardAction.AccountSecurity) {
                                    val codes = ctx.calls.twoFactorEnable(code.trim())
                                    setup = null
                                    code = ""
                                    ctx.refreshSession()
                                    showCodes(codes)
                                }
                            }, kind = ButtonKind.Primary, enabled = !run.running)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TwoFactorConfirm(
    ctx: AccountCtx,
    kind: Confirm,
    close: () -> Unit,
    onBegun: (JsonObject) -> Unit,
    onCodes: (List<String>) -> Unit,
) {
    val run = rememberRunner()
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val begin = kind == Confirm.Begin
    val ready = password.isNotEmpty() && (begin || code.isNotBlank())
    val submit = {
        if (ready && !run.running) run.run(guard = GuardAction.AccountSecurity) {
            try {
                when (kind) {
                    Confirm.Begin -> onBegun(ctx.calls.twoFactorBegin(password))
                    Confirm.Disable -> { ctx.calls.twoFactorDisable(password, code.trim()); ctx.refreshSession() }
                    Confirm.Renew -> onCodes(ctx.calls.recoveryRenew(password, code.trim()))
                }
                close()
            } finally {
                password = ""
                code = ""
            }
        }
    }
    AccountDialog(
        t(when (kind) { Confirm.Begin -> "account.beginTitle"; Confirm.Disable -> "account.disableTitle"; Confirm.Renew -> "account.renewTitle" }),
        onDismiss = close,
        locked = run.running,
        text = t(when (kind) { Confirm.Begin -> "account.beginText"; Confirm.Disable -> "account.disableText"; Confirm.Renew -> "account.renewText" }),
        actions = listOf(
            DialogAction(t("common.cancel")) { if (!run.running) close() },
            DialogAction(t(when (kind) { Confirm.Begin -> "account.beginSubmit"; Confirm.Disable -> "account.disableSubmit"; Confirm.Renew -> "account.renewSubmit" }), danger = !begin) { submit() },
        ),
    ) {
        Labeled(t("auth.password")) { Field(password, { password = it }, t("auth.password"), Modifier.fillMaxWidth(), password = true) }
        if (!begin) CodeField(code, { code = it })
        RunnerState(run)
    }
}

/** Die Wiederherstellungscodes: nur jetzt, zum Kopieren oder als Textdatei (die App macht sie selbst, nichts geht über das NAS). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.RecoveryDialog(name: String, codes: List<String>, close: () -> Unit) {
    val clip = LocalClipboardManager.current
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    val copied = t("account.recoveryCopied")
    val text = codes.joinToString("\n") + "\n"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
        }
    }
    AccountDialog(
        t("account.recoveryTitle"),
        onDismiss = {},
        locked = true,
        text = t("account.recoveryText"),
        actions = listOf(DialogAction(t("account.recoveryDone")) { close() }),
    ) {
        val c = Titan.colors
        FlowRow(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bg).border(1.dp, c.border, RoundedCornerShape(10.dp)).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 2,
        ) {
            for (code in codes) MonoText(code, big = true)
        }
        Buttons {
            SmallButton(t("account.recoveryCopy"), { clip.setText(AnnotatedString(codes.joinToString("\n"))); note = copied })
            SmallButton(t("account.recoveryDownload"), { save.launch("titanos-$name-recovery-codes.txt") })
        }
        note?.let { Text(it, style = io.github.ra5on.titanos.ui.theme.TitanType.rowSub, color = c.muted) }
    }
}

/** Ein falscher oder schon benutzter Code kann nicht noch einmal geschickt werden (Web: Feld leeren). */
internal fun Throwable.invalidCode(): Boolean = (this as? ApiException)?.code == "invalid_code"
