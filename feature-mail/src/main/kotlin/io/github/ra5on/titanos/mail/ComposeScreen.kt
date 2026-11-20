package io.github.ra5on.titanos.mail

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import io.github.ra5on.titanos.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Woraus eine neue E-Mail entsteht. */
data class ComposeStart(
    val mode: ComposeMode = ComposeMode.New,
    val account: String = "",
    val folder: String = "",
    val source: Message? = null,
    val to: String = "",
)

/** Anhänge einer vorhandenen Mail, die mitgehen (Weiterleiten, ein wieder geöffneter Entwurf). */
private data class Carried(val folder: String?, val uid: Long?, val parts: List<Attachment>, val draft: Boolean)

/** Höchstens 100 Empfänger und 25 MB Anhänge (Vertrag der Weboberfläche). */
private const val MAX_RECIPIENTS = 100
private const val MAX_BYTES = 25L * 1000 * 1000

/**
 * Schreiben (routes/mail/compose): aus einem Postfach des Kontos, An/Cc/Bcc
 * mit Vorschlägen, Betreff, Text, Signatur, Dateien vom Handy oder vom NAS.
 * Der Entwurf speichert sich, solange man schreibt (ab Werk alle 30 s);
 * „Zurück“ behält das Geschriebene als Entwurf, „Verwerfen“ fragt.
 */
@Composable
fun ComposeScreen(model: MailModel, titan: TitanApi?, start: ComposeStart, onDone: () -> Unit) {
    val c = Titan.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = currentLocale()
    val prefs = model.prefs
    val src = start.source
    var accountId by remember { mutableStateOf(start.account.takeIf { it.isNotEmpty() && it != ALL } ?: model.mainAccount()?.id.orEmpty()) }
    val account = model.account(accountId)
    var to by remember { mutableStateOf(if (start.to.isNotBlank()) listOf(start.to) else emptyList()) }
    var cc by remember { mutableStateOf(listOf<String>()) }
    var bcc by remember { mutableStateOf(listOf<String>()) }
    var showCopies by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var signature by remember { mutableStateOf<String?>(null) }
    var signatures by remember { mutableStateOf<List<Signature>>(emptyList()) }
    var files by remember { mutableStateOf(listOf<Upload>()) }
    var nasFiles by remember { mutableStateOf(listOf<NasFile>()) }
    var carried by remember { mutableStateOf<Carried?>(null) }
    var inReplyTo by remember { mutableStateOf("") }
    var references by remember { mutableStateOf("") }
    var draftUid by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<Throwable?>(null) }
    var problem by remember { mutableStateOf("") }
    var draftNote by remember { mutableStateOf("") }
    var draftError by remember { mutableStateOf<Throwable?>(null) }
    var edits by remember { mutableIntStateOf(0) }
    var savedEdits by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var pickAccount by remember { mutableStateOf(false) }
    var pickSignature by remember { mutableStateOf(false) }
    var pickNas by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val wrote = stringResource(R.string.mail_compose_wrote, "%1\$s", "%2\$s")
    val forwardHead = stringResource(R.string.mail_compose_forwardHead)
    val headFrom = stringResource(R.string.mail_head_from)
    val headDate = stringResource(R.string.mail_compose_date)
    val headSubject = stringResource(R.string.mail_compose_subject)
    val headTo = stringResource(R.string.mail_head_to)
    val headCc = stringResource(R.string.mail_head_cc)

    fun touch() {
        edits++
    }

    // Wie startFrom(): Antworten, Allen antworten, Weiterleiten, Entwurf.
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        if (src != null) {
            val me = account?.email.orEmpty()
            when (start.mode) {
                ComposeMode.Reply, ComposeMode.ReplyAll -> {
                    val (t, cs) = replyRecipients(src, me, start.mode == ComposeMode.ReplyAll)
                    to = t
                    cc = cs
                    showCopies = cs.isNotEmpty()
                    subject = replySubject(src.subject)
                    inReplyTo = src.messageId
                    references = "${src.references} ${src.messageId}".trim()
                    if (prefs.quoteReply) text = "\n\n" + wrote.format(fullDate(src.date, locale), senderName(src.from)) + "\n" + quote(src.text) + "\n"
                }
                ComposeMode.Forward -> {
                    subject = forwardSubject(src.subject)
                    carried = Carried(start.folder, src.uid, src.attachments.filter { !it.inline }, draft = false)
                    val lines = mutableListOf(forwardHead, "$headFrom ${addressText(src.from)}", "$headDate ${fullDate(src.date, locale)}", "$headSubject: ${src.subject}", "$headTo ${src.to.joinToString(", ", transform = ::addressText)}")
                    if (src.cc.isNotEmpty()) lines += "$headCc ${src.cc.joinToString(", ", transform = ::addressText)}"
                    text = "\n\n${lines.joinToString("\n")}\n\n${src.text}\n"
                }
                ComposeMode.Draft -> {
                    to = src.to.map(::addressText)
                    cc = src.cc.map(::addressText)
                    bcc = src.bcc.map(::addressText)
                    showCopies = cc.isNotEmpty() || bcc.isNotEmpty()
                    subject = src.subject
                    text = src.text
                    draftUid = src.uid
                    inReplyTo = src.inReplyTo
                    references = src.references
                    carried = Carried(null, null, src.attachments.filter { !it.inline }, draft = true)
                }
                ComposeMode.New -> Unit
            }
        }
        signatures = model.settings?.signatures ?: if (model.autoload) runCatching { model.loadSettings().signatures }.getOrDefault(emptyList()) else emptyList()
    }
    // Die Signatur des Postfachs, bis eine gewählt ist.
    val chosenSignature = signatures.firstOrNull { it.id == (signature ?: account?.signature) }

    val recipients = to + cc + bcc
    val bytes = files.sumOf { it.bytes.size.toLong() } + carried?.parts.orEmpty().sumOf { it.size }

    fun meta(): SendMeta = SendMeta(
        to = to.map { it.trim() }, cc = cc.map { it.trim() }, bcc = bcc.map { it.trim() },
        subject = subject, text = text, inReplyTo = inReplyTo, references = references,
        signature = signature ?: account?.signature.orEmpty(), draftUid = draftUid,
        attachFrom = carried?.takeIf { it.parts.isNotEmpty() }?.let {
            if (it.draft) AttachFrom(parts = it.parts.map { p -> p.part }) else AttachFrom(it.folder, it.uid, it.parts.map { p -> p.part })
        },
        answers = if (src != null && (start.mode == ComposeMode.Reply || start.mode == ComposeMode.ReplyAll)) MailRef(start.folder, src.uid) else null,
        forwards = if (src != null && start.mode == ComposeMode.Forward) MailRef(start.folder, src.uid) else null,
    )

    val draftSaved = stringResource(R.string.mail_compose_draftSaved, "%1\$s")
    suspend fun saveDraft() {
        if (accountId.isEmpty() || done) return
        val at = edits
        val sentFiles = files
        val sentNas = nasFiles
        try {
            val res = model.api.draft(accountId, meta(), sentFiles, sentNas)
            if (res.uid > 0) {
                draftUid = res.uid
                // Alles Angehängte liegt jetzt im Entwurf: die nächste Sicherung nimmt es von dort.
                res.attachments?.let { list ->
                    carried = Carried(null, null, list.filter { !it.inline }, draft = true)
                    files = files - sentFiles.toSet()
                    nasFiles = nasFiles - sentNas.toSet()
                }
            }
            savedEdits = at
            draftError = null
            draftNote = draftSaved.format(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", locale)))
            model.folderOf(accountId, "drafts")?.let { model.refreshFolders(accountId) }
        } catch (e: Exception) {
            draftError = e
            throw e
        }
    }
    // Automatisch sichern, solange sich etwas ändert (0: aus).
    LaunchedEffect(edits) {
        val every = prefs.autosaveSeconds
        if (every <= 0 || edits == savedEdits || edits == 0) return@LaunchedEffect
        delay(every * 1000L)
        runCatching { saveDraft() }
    }

    val noAccount = stringResource(R.string.mail_compose_noAccount)
    val noRecipient = stringResource(R.string.mail_compose_noRecipient)
    val badRecipients = stringResource(R.string.mail_compose_badRecipients, "%1\$s")
    val tooMany = stringResource(R.string.mail_compose_tooMany, MAX_RECIPIENTS.toString())
    val tooLarge = stringResource(R.string.mail_compose_tooLarge, sizeText(bytes), sizeText(MAX_BYTES))
    fun problemText(): String {
        if (account == null) return noAccount
        if (recipients.isEmpty()) return noRecipient
        val bad = recipients.filter { !validAddress(addrOf(it)) }
        if (bad.isNotEmpty()) return badRecipients.format(bad.joinToString(", "))
        if (recipients.size > MAX_RECIPIENTS) return tooMany
        if (bytes > MAX_BYTES) return tooLarge
        return ""
    }
    fun send() {
        problem = problemText()
        if (problem.isNotEmpty()) return
        sending = true
        sendError = null
        scope.launch {
            try {
                model.api.send(accountId, meta(), files, nasFiles)
                done = true
                if (src != null) {
                    model.applyFlags(accountId, start.folder, listOf(src.uid)) {
                        if (start.mode == ComposeMode.Forward) it.copy(forwarded = true) else if (start.mode != ComposeMode.Draft) it.copy(answered = true) else it
                    }
                }
                model.refreshFolders(accountId)
                onDone()
            } catch (e: Exception) {
                sendError = e
            } finally {
                sending = false
            }
        }
    }
    fun leave() {
        // Zurück behält das Geschriebene als Entwurf.
        if (done || edits == savedEdits || edits == 0) {
            onDone()
            return
        }
        scope.launch {
            runCatching { saveDraft() }
            done = true
            onDone()
        }
    }
    BackHandler(enabled = !pickAccount && !pickSignature && !pickNas && !confirmDiscard) { leave() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val read = withContext(Dispatchers.IO) { uris.mapNotNull { readUpload(ctx, it) } }
            files = files + read.filter { r -> files.none { it.name == r.name && it.bytes.size == r.bytes.size } }
            if (read.isNotEmpty()) touch()
        }
    }

    Page(
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 6.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextAction(stringResource(R.string.mail_compose_discard), { confirmDiscard = true }, danger = true, enabled = !sending)
                    Text(
                        stringResource(R.string.mail_compose_title),
                        style = TitanType.topTitle,
                        color = c.text,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    TitanButton(stringResource(R.string.mail_compose_send), ::send, kind = ButtonKind.Primary, icon = TitanIcons.send, enabled = !sending)
                }
            }
        },
        overlay = {
            if (pickAccount) {
                Sheet(onDismiss = { pickAccount = false }) {
                    Text(stringResource(R.string.mail_compose_from), style = TitanType.title, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
                    Card(background = c.bg) {
                        model.orderedAccounts().forEachIndexed { i, a ->
                            ChoiceRow(a.label, a.id == accountId, { accountId = a.id; signature = null; pickAccount = false; touch() }, divider = i > 0, sub = a.email)
                        }
                    }
                }
            }
            if (pickSignature) {
                Sheet(onDismiss = { pickSignature = false }) {
                    Text(stringResource(R.string.mail_compose_signature), style = TitanType.title, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
                    Card(background = c.bg) {
                        ChoiceRow(stringResource(R.string.mail_compose_noSignature), chosenSignature == null, { signature = ""; pickSignature = false; touch() })
                        for (s in signatures) ChoiceRow(s.name, chosenSignature?.id == s.id, { signature = s.id; pickSignature = false; touch() }, divider = true, sub = s.text.lines().firstOrNull())
                    }
                }
            }
            if (pickNas && titan != null) {
                NasPicker(titan, stringResource(R.string.mail_compose_fromNas), pickFiles = true, onFiles = { list ->
                    pickNas = false
                    nasFiles = (nasFiles + list).distinct()
                    touch()
                }, onDismiss = { pickNas = false })
            }
            if (confirmDiscard) {
                ConfirmDialog(
                    title = stringResource(R.string.mail_compose_discardTitle),
                    text = stringResource(if (draftUid > 0) R.string.mail_compose_discardDraft else R.string.mail_compose_discardText),
                    actions = listOf(
                        DialogAction(stringResource(R.string.mail_compose_discard), danger = true) {
                            confirmDiscard = false
                            done = true
                            scope.launch {
                                // Der gespeicherte Entwurf kommt in den Papierkorb.
                                val drafts = model.folderOf(accountId, "drafts")
                                if (draftUid > 0 && drafts != null) runCatching { model.api.delete(accountId, drafts.path, listOf(draftUid)) }
                                model.refreshFolders(accountId)
                                onDone()
                            }
                        },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { confirmDiscard = false },
                    ),
                    onDismiss = { confirmDiscard = false },
                )
            }
        },
    ) {
        if (problem.isNotEmpty()) Problem(problem, Modifier.padding(bottom = 8.dp))
        sendError?.let { Problem(errorText(it), Modifier.padding(bottom = 8.dp)) }
        // Von
        FieldTitle(stringResource(R.string.mail_compose_from))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)
                .combinedClickable(role = Role.Button) { if (model.accounts.orEmpty().size > 1) pickAccount = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(account?.let { "${it.label} <${it.email}>" } ?: noAccount, style = TitanType.body, color = c.text, modifier = Modifier.weight(1f))
            if (model.accounts.orEmpty().size > 1) Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(20.dp))
        }
        RecipientField(model, stringResource(R.string.mail_head_to), to, { to = it; touch() })
        if (showCopies) {
            RecipientField(model, stringResource(R.string.mail_head_cc), cc, { cc = it; touch() })
            RecipientField(model, stringResource(R.string.mail_head_bcc), bcc, { bcc = it; touch() })
        } else {
            TitanButton(stringResource(R.string.mail_compose_showCopies), { showCopies = true }, Modifier.padding(top = 10.dp))
        }
        LabeledField(stringResource(R.string.mail_compose_subject), subject, { subject = it; touch() })
        FieldTitle(stringResource(R.string.mail_compose_text))
        BodyField(text, { text = it; touch() }, stringResource(R.string.mail_compose_text))
        // Signatur
        FieldTitle(stringResource(R.string.mail_compose_signature))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)
                .combinedClickable(role = Role.Button) { pickSignature = true }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(chosenSignature?.name ?: stringResource(R.string.mail_compose_noSignature), style = TitanType.body, color = c.text, modifier = Modifier.weight(1f))
            Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(20.dp))
        }
        chosenSignature?.let {
            Text(stringResource(R.string.mail_compose_signaturePreview), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
            Text(it.text, style = TitanType.body, color = c.muted, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
        }
        // Anhänge
        FieldTitle(stringResource(R.string.mail_compose_attachments))
        val any = carried?.parts.orEmpty().isNotEmpty() || files.isNotEmpty() || nasFiles.isNotEmpty()
        if (any) {
            Card {
                var first = true
                for (a in carried?.parts.orEmpty()) {
                    AttachmentRow(a.name, sizeText(a.size) + " · " + stringResource(if (carried?.draft == true) R.string.mail_compose_fromDraft else R.string.mail_compose_forwarded), !first) {
                        carried = carried?.copy(parts = carried!!.parts - a); touch()
                    }
                    first = false
                }
                for (f in files) {
                    AttachmentRow(f.name, sizeText(f.bytes.size.toLong()), !first) { files = files - f; touch() }
                    first = false
                }
                for (n in nasFiles) {
                    AttachmentRow(n.path.substringAfterLast('/'), stringResource(R.string.mailx_from_nas), !first) { nasFiles = nasFiles - n; touch() }
                    first = false
                }
            }
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TitanButton(stringResource(R.string.mailx_from_phone), { pick.launch(arrayOf("*/*")) }, icon = TitanIcons.upload)
            if (titan != null) TitanButton(stringResource(R.string.mail_compose_fromNas), { pickNas = true }, icon = TitanIcons.folder)
        }
        Gap(12.dp)
        if (draftNote.isNotEmpty() && draftError == null) Text(draftNote, style = TitanType.small, color = c.muted)
        draftError?.let { Text(stringResource(R.string.mail_compose_draftFailed, errorText(it)), style = TitanType.small, color = c.warnInk) }
        if (prefs.autosaveSeconds > 0) Text(stringResource(R.string.mailx_draft_auto, prefs.autosaveSeconds.toString()), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 4.dp))
        Gap(24.dp)
    }
}

@Composable
private fun FieldTitle(text: String) {
    Text(text, style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun AttachmentRow(name: String, sub: String, divider: Boolean, onRemove: () -> Unit) {
    ListRow(
        name.ifBlank { stringResource(R.string.mail_attachment_noName) },
        sub = sub,
        icon = { RowIcon(MailIcons.clip) },
        divider = divider,
        right = { IconButton(TitanIcons.x, stringResource(R.string.mail_compose_removeFile, name), onRemove) },
    )
}

/** Der Text der E-Mail: mehrzeilig, wächst mit, mindestens zehn Zeilen hoch. */
@Composable
private fun BodyField(value: String, onChange: (String) -> Unit, label: String) {
    val c = Titan.colors
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TitanType.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)
            .semantics { contentDescription = label }.padding(14.dp),
    )
}

/**
 * Empfänger (AddressField.svelte): eingegebene Adressen als Chips, ungültige
 * mit Warnzeichen und Text, beim Tippen Vorschläge aus den Kontakten zuerst,
 * dann gelernte (lange drücken: vergessen).
 */
@Composable
private fun RecipientField(model: MailModel, label: String, list: List<String>, onChange: (List<String>) -> Unit) {
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<MailContact>>(emptyList()) }
    var forget by remember { mutableStateOf<MailContact?>(null) }
    fun commit(raw: String) {
        val parts = splitRecipients(raw)
        if (parts.isNotEmpty()) onChange(unique(list + parts))
        typed = ""
        suggestions = emptyList()
    }
    LaunchedEffect(typed) {
        val q = typed.trim()
        if (q.length < 1) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        suggestions = runCatching { model.api.contacts(q) }.getOrDefault(emptyList())
            .filter { s -> list.none { addrOf(it).equals(s.addr, true) } }.take(8)
    }
    FieldTitle(label)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (list.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                for (r in list) {
                    val ok = validAddress(addrOf(r))
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(if (ok) c.chip else c.warnBg).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!ok) Icon(TitanIcons.warn, null, tint = c.warnText, modifier = Modifier.size(16.dp).padding(end = 2.dp))
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(r, style = TitanType.small, color = if (ok) c.text else c.warnText)
                            if (!ok) Text(stringResource(R.string.mail_compose_invalid), style = TitanType.small, color = c.warnText)
                        }
                        IconButton(TitanIcons.x, stringResource(R.string.mail_compose_removeRecipient, r), { onChange(list - r) }, size = 16.dp)
                    }
                }
            }
        }
        BasicTextField(
            value = typed,
            onValueChange = { v ->
                if (v.endsWith(",") || v.endsWith(";") || v.endsWith("\n")) commit(v) else typed = v
            },
            textStyle = TitanType.body.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { commit(typed) }),
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics { contentDescription = label }.padding(vertical = 6.dp, horizontal = 4.dp)
                .then(Modifier),
        )
    }
    // Beim Verlassen des Feldes zählt, was getippt ist.
    LaunchedEffect(list) { }
    if (suggestions.isNotEmpty()) {
        Text(stringResource(R.string.mail_compose_suggestions), style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        Card(Modifier.padding(top = 4.dp)) {
            suggestions.forEachIndexed { i, s ->
                Column(Modifier.combinedClickable(onClick = {
                    commit(if (s.name.isNotBlank()) "${s.name} <${s.addr}>" else s.addr)
                }, onLongClick = { if (s.source != "contact") forget = s })) {
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SenderAvatar(model, Address(s.name, s.addr), 32.dp)
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifBlank { s.addr }, style = TitanType.body, color = c.text)
                            if (s.name.isNotBlank()) Text(s.addr, style = TitanType.rowSub, color = c.muted)
                        }
                        if (s.source == "contact") Text(stringResource(R.string.mail_compose_fromContacts), style = TitanType.small, color = c.muted)
                    }
                }
            }
        }
        if (typed.isNotBlank()) TextAction(stringResource(R.string.mailx_take_typed, typed.trim()), { commit(typed) })
    } else if (typed.isNotBlank()) {
        TextAction(stringResource(R.string.mailx_take_typed, typed.trim()), { commit(typed) })
    }
    forget?.let { s ->
        Note(stringResource(R.string.mailx_forget_text, s.addr), Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            TitanButton(stringResource(R.string.mailx_forget), {
                forget = null
                scope.launch {
                    runCatching { model.api.forgetContact(s.addr) }
                    suggestions = suggestions - s
                }
            }, kind = ButtonKind.Danger)
            TitanButton(stringResource(UiR.string.ui_cancel), { forget = null })
        }
    }
}

/** Liest eine Datei vom Handy (Name, Art, Inhalt). */
private fun readUpload(ctx: android.content.Context, uri: Uri): Upload? = runCatching {
    val r = ctx.contentResolver
    var name = "attachment"
    r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) cur.getString(0)?.let { name = it }
    }
    val type = r.getType(uri).orEmpty()
    val bytes = r.openInputStream(uri)!!.use { it.readBytes() }
    Upload(name, type, bytes)
}.getOrNull()
