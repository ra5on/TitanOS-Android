package io.github.ra5on.titanos.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.R as UiR
import kotlinx.coroutines.launch

private val DEFAULT_PORT = mapOf(
    "imap" to mapOf("tls" to 993, "starttls" to 143),
    "smtp" to mapOf("tls" to 465, "starttls" to 587),
)

/** Ein Wechsel der TLS-Art verschiebt einen Port, der die alte Vorgabe war, auf die neue (ServerFields). */
fun withSecurity(kind: String, s: MailServer, value: String): MailServer {
    val old = DEFAULT_PORT.getValue(kind)[s.security]
    return s.copy(security = value, port = if (s.port == old) DEFAULT_PORT.getValue(kind).getValue(value) else s.port)
}

/** Benutzername, IMAP und SMTP mit Server, Port und TLS-Art (nur TLS oder STARTTLS). */
@Composable
fun ServerFields(user: String, onUser: (String) -> Unit, imap: MailServer, onImap: (MailServer) -> Unit, smtp: MailServer, onSmtp: (MailServer) -> Unit) {
    LabeledField(stringResource(R.string.mail_setup_user), user, onUser, keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false))
    for ((kind, server, set) in listOf(Triple("imap", imap, onImap), Triple("smtp", smtp, onSmtp))) {
        SectionHeader(stringResource(if (kind == "imap") R.string.mail_setup_imap else R.string.mail_setup_smtp))
        LabeledField(stringResource(R.string.mail_setup_host), server.host, { set(server.copy(host = it.trim())) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        LabeledField(stringResource(R.string.mail_setup_port), server.port.toString(), { v -> set(server.copy(port = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text(stringResource(R.string.mail_setup_security), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 6.dp))
        Card {
            ChoiceRow(stringResource(R.string.mail_setup_tls), server.security == "tls", { set(withSecurity(kind, server, "tls")) })
            ChoiceRow(stringResource(R.string.mail_setup_starttls), server.security == "starttls", { set(withSecurity(kind, server, "starttls")) }, divider = true)
        }
    }
    Text(stringResource(R.string.mail_setup_tlsOnly), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(4.dp))
}

/** Ergebnis von „Verbindung prüfen“ in Worten mit Haken bzw. Warnzeichen. */
@Composable
fun TestResultView(r: TestResult) {
    Card(Modifier.padding(top = 10.dp)) {
        for ((i, part) in listOf(stringResource(R.string.mail_setup_imapShort) to r.imap, stringResource(R.string.mail_setup_smtpShort) to r.smtp).withIndex()) {
            val (label, p) = part
            ListRow(
                label,
                sub = if (p.ok) stringResource(R.string.mail_setup_ok) else if (p.code.isNotEmpty()) codeText(p.code) else stringResource(R.string.mail_setup_failed),
                icon = { RowIcon(if (p.ok) TitanIcons.check else TitanIcons.warn, tint = if (p.ok) Titan.colors.okInk else Titan.colors.dangerInk) },
                divider = i > 0,
            )
        }
    }
}

/**
 * Einrichten in Schritten (SetupWizard.svelte): Adresse → vorgeschlagene
 * Server (feste Liste des NAS) → Passwort mit dem Hinweis des Anbieters →
 * „Verbindung prüfen“ → Speichern. Das Passwort lebt nur in diesem Formular.
 */
@Composable
fun SetupScreen(model: MailModel, onBack: () -> Unit, onSaved: (MailAccount) -> Unit) {
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var step by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var senderName by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf<Provider?>(null) }
    var user by remember { mutableStateOf("") }
    var imap by remember { mutableStateOf(MailServer(port = 993)) }
    var smtp by remember { mutableStateOf(MailServer(port = 465)) }
    var editServers by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var apiError by remember { mutableStateOf<Throwable?>(null) }
    var result by remember { mutableStateOf<TestResult?>(null) }
    val passed = result?.imap?.ok == true && result?.smtp?.ok == true
    val back: () -> Unit = { if (step > 1) step -= 1 else onBack() }
    BackHandler(onBack = back)
    val badAddress = stringResource(R.string.mail_setup_badAddress)

    fun body() = AccountInput(name.trim().ifEmpty { email.trim() }, email.trim(), senderName.trim(), user.trim().ifEmpty { email.trim() }, imap, smtp, true, "", password)

    Page(topBar = { LevelHead(stringResource(R.string.mail_setup_title), stringResource(R.string.mail_setup_step, step.toString(), "3"), back = stringResource(R.string.mail_setup_back), onBack = back) }) {
        Note(stringResource(R.string.mail_setup_intro), Modifier.padding(bottom = 8.dp), icon = TitanIcons.lock)
        error?.let { Problem(it, Modifier.padding(vertical = 6.dp)) }
        apiError?.let { Problem(errorText(it), Modifier.padding(vertical = 6.dp)) }
        when (step) {
            1 -> {
                SectionHeader(stringResource(R.string.mail_setup_addressTitle))
                LabeledField(stringResource(R.string.mail_setup_email), email, { email = it; error = null }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false))
                LabeledField(stringResource(R.string.mail_setup_name), name, { name = it }, hint = stringResource(R.string.mail_setup_nameHint))
                LabeledField(stringResource(R.string.mail_setup_senderName), senderName, { senderName = it }, hint = stringResource(R.string.mail_setup_senderNameHint))
                Gap(14.dp)
                TitanButton(stringResource(R.string.mail_setup_next), {
                    if (!validAddress(email)) {
                        error = badAddress
                        return@TitanButton
                    }
                    busy = true
                    error = null
                    apiError = null
                    user = user.ifBlank { email.trim() }
                    scope.launch {
                        val p = runCatching { model.api.provider(email.trim()) }.getOrNull()
                        if (p != null && p.known) {
                            imap = p.imap
                            smtp = p.smtp
                            provider = p
                            editServers = false
                        } else {
                            // Nicht in der Liste: die üblichen Namen zum Anfangen, offen zum Ändern.
                            val d = domainOf(email)
                            imap = MailServer("imap.$d", 993, "tls")
                            smtp = MailServer("smtp.$d", 465, "tls")
                            provider = null
                            editServers = true
                        }
                        busy = false
                        step = 2
                    }
                }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !busy && email.isNotBlank())
            }
            2 -> {
                SectionHeader(stringResource(R.string.mail_setup_serversTitle))
                val p = provider
                if (p != null) {
                    Note(stringResource(R.string.mail_setup_known, p.name.ifBlank { domainOf(email) }))
                    if (!p.supported) Problem(stringResource(R.string.mail_setup_unsupported, p.name), Modifier.padding(top = 8.dp))
                    if (p.hint.isNotEmpty()) Note(hintText(p.hint, p.name), Modifier.padding(top = 8.dp))
                } else {
                    Note(stringResource(R.string.mail_setup_unknown, domainOf(email)))
                }
                if (editServers) {
                    ServerFields(user, { user = it; result = null }, imap, { imap = it; result = null }, smtp, { smtp = it; result = null })
                } else {
                    Card(Modifier.padding(top = 10.dp)) {
                        ListRow(stringResource(R.string.mail_setup_imapShort), sub = "${imap.host}:${imap.port} · ${secText(imap.security)}")
                        ListRow(stringResource(R.string.mail_setup_smtpShort), sub = "${smtp.host}:${smtp.port} · ${secText(smtp.security)}", divider = true)
                        ListRow(stringResource(R.string.mail_setup_user), sub = user, divider = true)
                    }
                    TitanButton(stringResource(R.string.mail_setup_editServers), { editServers = true }, Modifier.padding(top = 10.dp))
                }
                Gap(14.dp)
                TitanButton(stringResource(R.string.mail_setup_next), { step = 3 }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = provider?.supported != false && imap.host.isNotBlank() && smtp.host.isNotBlank())
            }
            else -> {
                SectionHeader(stringResource(R.string.mail_setup_passwordTitle))
                val app = provider?.appPassword == true
                if (app) Note(stringResource(R.string.mail_setup_appPassword), Modifier.padding(bottom = 6.dp), icon = TitanIcons.key)
                LabeledField(stringResource(if (app) R.string.mail_setup_appPasswordLabel else R.string.mail_setup_password), password, { password = it; result = null }, password = true)
                Gap(14.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TitanButton(stringResource(R.string.mail_setup_check), {
                        busy = true
                        apiError = null
                        result = null
                        scope.launch {
                            try {
                                val b = body()
                                result = model.api.test(TestRequest(b.name, b.email, b.senderName, b.user, b.imap, b.smtp, b.notify, b.signature, b.password))
                            } catch (e: Exception) {
                                apiError = e
                            } finally {
                                busy = false
                            }
                        }
                    }, enabled = !busy && password.isNotEmpty())
                    TitanButton(stringResource(if (result == null || passed) R.string.mail_setup_save else R.string.mail_setup_saveAnyway), {
                        busy = true
                        apiError = null
                        scope.launch {
                            try {
                                val saved = model.api.createAccount(body())
                                password = ""
                                onSaved(saved)
                            } catch (e: Exception) {
                                apiError = e
                            } finally {
                                busy = false
                            }
                        }
                    }, kind = ButtonKind.Primary, enabled = !busy && password.isNotEmpty())
                }
                if (busy) Text(stringResource(R.string.mail_setup_working), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                result?.let { TestResultView(it) }
            }
        }
        Gap(24.dp)
    }
}

@Composable
fun secText(s: String) = stringResource(if (s == "starttls") R.string.mail_setup_starttls else R.string.mail_setup_tls)

@Composable
fun hintText(hint: String, name: String): String = when (hint) {
    "imap_enable" -> stringResource(R.string.mail_hint_imap_enable, name)
    "app_password" -> stringResource(R.string.mail_hint_app_password, name)
    "app_password_2fa" -> stringResource(R.string.mail_hint_app_password_2fa, name)
    "mail_password" -> stringResource(R.string.mail_hint_mail_password, name)
    "oauth_only" -> stringResource(R.string.mail_hint_oauth_only, name)
    else -> hint
}

/**
 * Ein Postfach ändern (AccountEditor.svelte): Name, Absendername, Server,
 * Passwort (leer behält das gespeicherte; nach neuem Server oder Benutzer
 * neu nötig), Signatur ab Werk, Meldungen, prüfen, entfernen.
 */
@Composable
fun AccountScreen(model: MailModel, id: String, onBack: () -> Unit, onRemoved: () -> Unit) {
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    val start = model.account(id) ?: run { onBack(); return }
    var name by remember { mutableStateOf(start.name) }
    var senderName by remember { mutableStateOf(start.senderName) }
    var user by remember { mutableStateOf(start.user) }
    var imap by remember { mutableStateOf(start.imap) }
    var smtp by remember { mutableStateOf(start.smtp) }
    var signature by remember { mutableStateOf(start.signature) }
    var notify by remember { mutableStateOf(start.notify) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var saved by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TestResult?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var pickSig by remember { mutableStateOf(false) }
    var signatures by remember { mutableStateOf(model.settings?.signatures.orEmpty()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (model.autoload) runCatching { model.loadSettings() }.onSuccess { signatures = it.signatures } }
    BackHandler(onBack = onBack)
    // Ein gespeichertes Passwort geht nie an einen anderen Server.
    val moved = imap.host.trim() != start.imap.host || smtp.host.trim() != start.smtp.host || user.trim() != start.user
    val needsPassword = moved && password.isEmpty()
    fun body() = AccountInput(name.trim().ifEmpty { start.email }, start.email, senderName.trim(), user.trim(), imap, smtp, notify, signature, password)

    Page(
        topBar = { LevelHead(start.label, start.email, back = stringResource(R.string.mail_settings_title), onBack = onBack) },
        overlay = {
            if (confirmRemove) {
                ConfirmDialog(
                    title = stringResource(R.string.mail_settings_removeTitle),
                    text = stringResource(R.string.mail_settings_removeText, start.label) + " " + stringResource(R.string.mail_settings_removeKeeps),
                    actions = listOf(
                        DialogAction(stringResource(R.string.mail_settings_remove), danger = true) {
                            confirmRemove = false
                            busy = true
                            scope.launch {
                                try {
                                    model.api.deleteAccount(id)
                                    model.loadAccounts()
                                    onRemoved()
                                } catch (e: Exception) {
                                    error = e
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { confirmRemove = false },
                    ),
                    onDismiss = { confirmRemove = false },
                )
            }
            if (pickSig) {
                Sheet(onDismiss = { pickSig = false }) {
                    Text(stringResource(R.string.mail_settings_defaultSignature), style = TitanType.title, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
                    Card(background = c.bg) {
                        ChoiceRow(stringResource(R.string.mail_compose_noSignature), signature.isEmpty(), { signature = ""; pickSig = false; saved = false })
                        for (s in signatures) ChoiceRow(s.name, signature == s.id, { signature = s.id; pickSig = false; saved = false }, divider = true)
                    }
                }
            }
        },
    ) {
        if (start.status.state == "auth_failed") Problem(stringResource(R.string.mail_settings_authFailed), Modifier.padding(bottom = 8.dp))
        else if (start.status.state != "ok") Note("${statusText(start.status)}${if (start.status.code.isNotEmpty()) " · " + codeText(start.status.code) else ""}", Modifier.padding(bottom = 8.dp), icon = TitanIcons.warn)
        error?.let { Problem(errorText(it), Modifier.padding(bottom = 8.dp)) }
        if (saved) Done(stringResource(R.string.mail_settings_saved), Modifier.padding(bottom = 8.dp))
        LabeledField(stringResource(R.string.mail_setup_name), name, { name = it; saved = false }, hint = stringResource(R.string.mail_setup_nameHint))
        LabeledField(stringResource(R.string.mail_setup_senderName), senderName, { senderName = it; saved = false }, hint = stringResource(R.string.mail_setup_senderNameHint))
        ServerFields(user, { user = it; result = null; saved = false }, imap, { imap = it; result = null; saved = false }, smtp, { smtp = it; result = null; saved = false })
        LabeledField(stringResource(R.string.mail_settings_password), password, { password = it; result = null; saved = false }, password = true,
            hint = stringResource(if (needsPassword) R.string.mail_settings_passwordAgain else R.string.mail_settings_passwordHint))
        SectionHeader(stringResource(R.string.mail_settings_defaultSignature))
        Card {
            ListRow(signatures.firstOrNull { it.id == signature }?.name ?: stringResource(R.string.mail_compose_noSignature), chevron = true, onClick = { pickSig = true })
        }
        SectionHeader(stringResource(R.string.mail_settings_notify))
        Card { ToggleRow(stringResource(R.string.mailx_notify_this), notify, { notify = it; saved = false }, sub = stringResource(R.string.mail_settings_notifyDesc)) }
        Gap(14.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TitanButton(stringResource(R.string.mail_setup_check), {
                busy = true
                error = null
                result = null
                scope.launch {
                    try {
                        val b = body()
                        result = model.api.test(
                            if (password.isNotEmpty()) TestRequest(b.name, b.email, b.senderName, b.user, b.imap, b.smtp, b.notify, b.signature, b.password)
                            else TestRequest(b.name, b.email, b.senderName, b.user, b.imap, b.smtp, b.notify, b.signature, id = id),
                        )
                    } catch (e: Exception) {
                        error = e
                    } finally {
                        busy = false
                    }
                }
            }, enabled = !busy && !needsPassword)
            TitanButton(stringResource(R.string.mail_setup_save), {
                busy = true
                error = null
                scope.launch {
                    try {
                        model.api.updateAccount(id, body())
                        password = ""
                        saved = true
                        model.loadAccounts()
                    } catch (e: Exception) {
                        error = e
                    } finally {
                        busy = false
                    }
                }
            }, kind = ButtonKind.Primary, enabled = !busy && !needsPassword)
        }
        result?.let { TestResultView(it) }
        SectionHeader(stringResource(R.string.mail_settings_remove))
        Text(stringResource(R.string.mail_settings_removeHint), style = TitanType.small, color = c.muted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        TitanButton(stringResource(R.string.mail_settings_remove), { confirmRemove = true }, kind = ButtonKind.Danger, icon = TitanIcons.trash, enabled = !busy)
        Gap(24.dp)
    }
}
