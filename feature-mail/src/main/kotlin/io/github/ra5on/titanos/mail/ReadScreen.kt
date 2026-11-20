package io.github.ra5on.titanos.mail

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.MediaStore
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Divider
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
import io.github.ra5on.titanos.ui.theme.warnInk
import io.github.ra5on.titanos.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Ebene „Nachricht“ (MessageView.svelte am Handy): Kopf, Empfänger auf
 * Wunsch, blockierte Bilder, Anhänge (Öffnen, Teilen, Herunterladen, In
 * Dateien speichern), der Inhalt eingepasst, die echten Linkziele und die
 * Leiste unten. Öffnen markiert als gelesen (titand tut es mit der Anfrage).
 */
@Composable
fun ReadScreen(
    model: MailModel,
    titan: TitanApi?,
    account: String,
    folder: String,
    uid: Long,
    backLabel: String,
    onBack: () -> Unit,
    onCompose: (ComposeMode, Message) -> Unit,
    onMailto: (String) -> Unit,
    onRemoved: () -> Unit,
    preset: Message? = null,
) {
    val c = Titan.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember(account, folder, uid) { mutableStateOf(preset) }
    var error by remember(account, folder, uid) { mutableStateOf<Throwable?>(null) }
    var images by remember(account, folder, uid) { mutableStateOf(false) }
    var showAll by remember(account, folder, uid) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<Throwable?>(null) }
    var notice by remember { mutableStateOf("") }
    var replyMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Attachment?>(null) }
    var linksOpen by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<MailLink?>(null) }
    val prefs = model.prefs
    val f = model.findFolder(account, folder)
    val inTrash = f?.role == "trash"
    val inDrafts = f?.role == "drafts"
    val acc = model.account(account)

    suspend fun load() {
        try {
            val got = model.api.message(account, folder, uid, images)
            // Öffnen markiert als gelesen; nur eine Änderung wird weitergegeben.
            if (!got.flags.seen) {
                model.applyFlags(account, folder, listOf(uid)) { it.copy(seen = true) }
                model.adjust(account, folder, unread = -1)
            }
            msg = got.copy(flags = got.flags.copy(seen = true))
            error = null
        } catch (e: Exception) {
            error = e
        }
    }
    LaunchedEffect(account, folder, uid, images) {
        if (preset != null && !images) return@LaunchedEffect
        // Bilder automatisch nach der Einstellung dieses Handys (ab Werk: nur auf Tippen).
        load()
        val m = msg ?: return@LaunchedEffect
        if (!images && m.externalImages) {
            val auto = when (prefs.images) {
                ImagesMode.Always -> true
                ImagesMode.Contacts -> m.from.addr.lowercase() in model.avatarIds
                ImagesMode.Ask -> false
            }
            if (auto) images = true
        }
    }

    fun act(block: suspend () -> Unit) {
        busy = true
        actionError = null
        notice = ""
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                actionError = e
            } finally {
                busy = false
            }
        }
    }
    val row = MessageRow(uid = uid, account = account, folder = folder, flags = msg?.flags ?: Flags(seen = true))
    fun setFlag(flag: String, on: Boolean) = act {
        model.setFlag(listOf(row), null, flag, on)
        msg = msg?.let { m -> m.copy(flags = if (flag == "seen") m.flags.copy(seen = on) else m.flags.copy(flagged = on)) }
    }
    fun delete() = act { model.delete(listOf(row), null); onRemoved() }

    val attachmentSaved = stringResource(R.string.mail_attachment_saved, "%1\$s", "%2\$s")
    val savedDownloads = stringResource(R.string.mailx_saved_downloads, "%1\$s")

    Page(
        topBar = { LevelHead(msg?.subject?.trim()?.ifEmpty { null } ?: stringResource(R.string.mail_noSubject), back = backLabel, onBack = onBack) },
        bottomBar = {
            if (!(replyMenu || more || moving || saving != null)) Column(Modifier.fillMaxWidth().background(c.header)) {
                Divider()
                val m = msg
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (inDrafts) {
                        BarButton(TitanIcons.edit, stringResource(R.string.mailx_edit_draft), { m?.let { onCompose(ComposeMode.Draft, it) } }, enabled = m != null)
                    } else {
                        BarButton(MailIcons.reply, stringResource(R.string.mail_action_reply), { replyMenu = true }, enabled = m != null && !busy)
                    }
                    BarButton(TitanIcons.move, stringResource(R.string.mail_action_move), { moving = true }, enabled = m != null && !busy)
                    BarButton(TitanIcons.archive, stringResource(R.string.mail_action_archive), {
                        act { model.archive(listOf(row), null); onRemoved() }
                    }, enabled = m != null && !busy && f?.role != "archive")
                    BarButton(TitanIcons.trash, stringResource(if (inTrash) R.string.mail_action_deleteForever else R.string.mail_action_delete), {
                        if (inTrash || prefs.confirmDelete) confirmDelete = true else delete()
                    }, enabled = m != null && !busy, danger = true)
                    BarButton(MailIcons.flag, stringResource(if (m?.flags?.flagged == true) R.string.mail_action_unflag else R.string.mail_action_flag), {
                        setFlag("flagged", m?.flags?.flagged != true)
                    }, enabled = m != null && !busy)
                    BarButton(TitanIcons.more, stringResource(R.string.mail_action_more), { more = true }, enabled = m != null)
                }
                Box(Modifier.navigationBarsPadding())
            }
        },
        overlay = {
            val m = msg
            if (replyMenu && m != null) {
                Sheet(onDismiss = { replyMenu = false }) {
                    Card(background = c.bg) {
                        ListRow(stringResource(R.string.mail_action_reply), icon = { RowIcon(MailIcons.reply) }, onClick = { replyMenu = false; onCompose(ComposeMode.Reply, m) })
                        ListRow(stringResource(R.string.mail_action_replyAll), icon = { RowIcon(MailIcons.replyAll) }, divider = true, onClick = { replyMenu = false; onCompose(ComposeMode.ReplyAll, m) })
                        ListRow(stringResource(R.string.mail_action_forward), icon = { RowIcon(MailIcons.forward) }, divider = true, onClick = { replyMenu = false; onCompose(ComposeMode.Forward, m) })
                    }
                }
            }
            if (more && m != null) {
                Sheet(onDismiss = { more = false }) {
                    Card(background = c.bg) {
                        ListRow(stringResource(if (m.flags.seen) R.string.mail_action_markUnread else R.string.mail_action_markRead), icon = { RowIcon(TitanIcons.mail) }, onClick = {
                            more = false
                            setFlag("seen", !m.flags.seen)
                        })
                        if (model.folderOf(account, "junk") != null && f?.role != "junk") {
                            ListRow(stringResource(R.string.mail_action_junk), icon = { RowIcon(MailIcons.junk) }, divider = true, onClick = {
                                more = false
                                act { model.junk(listOf(row), null); onRemoved() }
                            })
                        }
                        ListRow(stringResource(R.string.mail_action_replyAll), icon = { RowIcon(MailIcons.replyAll) }, divider = true, onClick = { more = false; onCompose(ComposeMode.ReplyAll, m) })
                        ListRow(stringResource(R.string.mail_action_forward), icon = { RowIcon(MailIcons.forward) }, divider = true, onClick = { more = false; onCompose(ComposeMode.Forward, m) })
                        ListRow(stringResource(R.string.mailx_share_text), icon = { RowIcon(TitanIcons.share) }, divider = true, onClick = {
                            more = false
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, m.subject).putExtra(Intent.EXTRA_TEXT, m.text)
                            ctx.startActivity(Intent.createChooser(send, null))
                        })
                    }
                }
            }
            if (moving) MoveSheet(model, account, folder, onPick = { to, _ -> moving = false; act { model.move(listOf(row), null, to.path); onRemoved() } }, onDismiss = { moving = false })
            if (confirmDelete) {
                ConfirmDialog(
                    title = stringResource(if (inTrash) R.string.mail_delete_title else R.string.mailx_delete_ask),
                    text = if (inTrash) stringResource(R.string.mail_delete_text, m?.subject.orEmpty()) + " " + stringResource(R.string.mail_delete_forever) else stringResource(R.string.mailx_delete_to_trash),
                    actions = listOf(
                        DialogAction(stringResource(if (inTrash) R.string.mail_action_deleteForever else R.string.mail_action_delete), danger = true) { confirmDelete = false; delete() },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { confirmDelete = false },
                    ),
                    onDismiss = { confirmDelete = false },
                )
            }
            saving?.let { part ->
                if (titan != null) {
                    NasPicker(titan, stringResource(R.string.mail_attachment_saveTitle, part.name.ifBlank { stringResource(R.string.mail_attachment_noName) }), pickFiles = false, onFolder = { root, path ->
                        saving = null
                        val name = part.name.ifBlank { "attachment" }.replace(Regex("[/\\\\]"), "_")
                        act {
                            model.api.savePart(account, folder, uid, part.part, root.id, if (path.isEmpty()) name else "$path/$name")
                            notice = attachmentSaved.format(part.name, (listOf(root.name) + path.split('/').filter { it.isNotEmpty() }).joinToString(" › "))
                        }
                    }, onDismiss = { saving = null })
                }
            }
            link?.let { l ->
                ConfirmDialog(
                    title = stringResource(R.string.mailx_link_open),
                    text = l.href,
                    actions = listOf(
                        DialogAction(stringResource(R.string.mailx_link_go)) {
                            link = null
                            if (l.href.startsWith("mailto:", true)) onMailto(l.href.substringAfter(':').substringBefore('?'))
                            else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, l.href.toUri())) }
                        },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { link = null },
                    ),
                    onDismiss = { link = null },
                ) {
                    if (l.warn.isNotEmpty()) Problem(stringResource(R.string.mail_link_warn, l.warn), Modifier.padding(bottom = 10.dp))
                }
            }
        },
    ) {
        error?.let { Problem(errorText(it), Modifier.padding(bottom = 10.dp)) }
        val m = msg
        if (m == null) {
            if (error == null) Text(stringResource(UiR.string.ui_loading), style = TitanType.body, color = c.muted, modifier = Modifier.padding(8.dp))
            return@Page
        }
        // Kopf: Von, Datum, An (alle auf Wunsch).
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            if (prefs.avatars) SenderAvatar(model, m.from, 44.dp)
            Column(Modifier.weight(1f)) {
                HeadLine(stringResource(R.string.mail_head_from), addressText(m.from))
                Text(fullDate(m.date, currentLocale()), style = TitanType.rowSub, color = c.muted)
                val all = m.to + m.cc
                val names = all.take(2).joinToString(", ") { senderName(it) }
                val recipients = if (all.size > 2) stringResource(R.string.mail_head_andMore, names, (all.size - 2).toString()) else names
                HeadLine(stringResource(R.string.mail_head_to), recipients.ifEmpty { "–" })
                if (m.flags.flagged) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(MailIcons.flag, null, tint = c.warnInk, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.mail_mark_flagged), style = TitanType.rowSub, color = c.warnInk)
                    }
                }
            }
        }
        TextAction(stringResource(if (showAll) R.string.mail_head_less else R.string.mail_head_more), { showAll = !showAll })
        if (showAll) {
            Card(Modifier.padding(bottom = 10.dp)) {
                Column(Modifier.padding(14.dp)) {
                    HeadLine(stringResource(R.string.mail_head_to), m.to.joinToString(", ", transform = ::addressText).ifEmpty { "–" })
                    if (m.cc.isNotEmpty()) HeadLine(stringResource(R.string.mail_head_cc), m.cc.joinToString(", ", transform = ::addressText))
                    if (m.bcc.isNotEmpty()) HeadLine(stringResource(R.string.mail_head_bcc), m.bcc.joinToString(", ", transform = ::addressText))
                    if (m.replyTo.isNotEmpty()) HeadLine(stringResource(R.string.mail_head_replyTo), m.replyTo.joinToString(", ", transform = ::addressText))
                }
            }
        }
        actionError?.let { Problem(errorText(it), Modifier.padding(bottom = 10.dp)) }
        if (notice.isNotEmpty()) Done(notice, Modifier.padding(bottom = 10.dp))
        if (m.externalImages && !images) {
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(c.warnBg).border(1.dp, c.warnBorder, shape).padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(TitanIcons.image, null, tint = c.warnText, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.mail_images_blocked), style = TitanType.body, color = c.warnText)
                }
                TitanButton(stringResource(R.string.mail_images_load), { images = true }, Modifier.padding(top = 10.dp))
            }
        }
        if (m.rawText) Note(stringResource(R.string.mail_rawText), Modifier.padding(bottom = 10.dp), icon = TitanIcons.warn)
        else if (m.truncated) Note(stringResource(R.string.mail_truncated), Modifier.padding(bottom = 10.dp), icon = TitanIcons.warn)

        val files = m.attachments.filter { !it.inline || !it.type.startsWith("image/") }
        if (files.isNotEmpty()) {
            SectionHeader(stringResource(R.string.mail_attachment_title), top = 4.dp)
            Card(Modifier.padding(bottom = 12.dp)) {
                files.forEachIndexed { i, a ->
                    val name = a.name.ifBlank { stringResource(R.string.mail_attachment_noName) }
                    ListRow(name, sub = sizeText(a.size), icon = { RowIcon(MailIcons.clip) }, divider = i > 0, bottom = {
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TitanButton(stringResource(R.string.mailx_open), { act { openPart(ctx, model, account, folder, uid, a, share = false) } }, icon = MailIcons.open, enabled = !busy)
                            TitanButton(stringResource(R.string.mailx_share), { act { openPart(ctx, model, account, folder, uid, a, share = true) } }, icon = TitanIcons.share, enabled = !busy)
                            TitanButton(stringResource(R.string.mail_attachment_download), {
                                act {
                                    downloadPart(ctx, model, account, folder, uid, a)
                                    notice = savedDownloads.format(name)
                                }
                            }, icon = TitanIcons.download, enabled = !busy)
                            if (titan != null) TitanButton(stringResource(R.string.mail_attachment_save), { saving = a }, icon = TitanIcons.folder, enabled = !busy)
                        }
                    })
                }
            }
        }

        if (m.html.isNotBlank()) {
            MailBody(m.html, images) { href ->
                val l = mailLinks(m.html).firstOrNull { it.href == href } ?: MailLink(href, href, hostOf(href), "")
                link = l
            }
            val links = mailLinks(m.html)
            if (links.isNotEmpty()) {
                val warn = links.any { it.warn.isNotEmpty() }
                TextAction(
                    stringResource(R.string.mail_link_title, links.size.toString()) + if (warn) " · " + stringResource(R.string.mail_link_someWarn) else "",
                    { linksOpen = !linksOpen },
                    danger = warn,
                )
                if (linksOpen) {
                    Card {
                        links.forEachIndexed { i, l ->
                            ListRow(l.text, sub = l.href + if (l.warn.isNotEmpty()) "\n⚠ " + stringResource(R.string.mail_link_warn, l.warn) else "",
                                icon = { RowIcon(if (l.warn.isNotEmpty()) TitanIcons.warn else MailIcons.link, tint = if (l.warn.isNotEmpty()) c.dangerInk else c.muted) },
                                divider = i > 0, onClick = { link = l })
                        }
                    }
                }
            }
        } else {
            PlainBody(m.text) { href -> link = MailLink(href, href, hostOf(href), "") }
        }
        Gap(20.dp)
    }
}

@Composable
private fun HeadLine(label: String, value: String) {
    Text(
        buildAnnotatedString {
            pushStyle(SpanStyle(color = Titan.colors.muted))
            append(label)
            pop()
            append(" ")
            append(value)
        },
        style = TitanType.body,
        color = Titan.colors.text,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** Klartext mit Links (nur http/https), nichts wird zu HTML. */
@Composable
private fun PlainBody(text: String, onLink: (String) -> Unit) {
    val c = Titan.colors
    val styled = buildAnnotatedString {
        for (p in linkify(text)) {
            val href = p.href
            if (href == null) {
                append(p.text)
            } else {
                withLink(LinkAnnotation.Clickable(href, TextLinkStyles(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline))) { onLink(href) }) { append(p.text) }
            }
        }
    }
    Text(styled, style = TitanType.body, color = c.text)
}

/**
 * Die HTML-Mail, wie titand sie bereinigt hat, in einer WebView ohne
 * JavaScript, ohne Datei- und Inhaltszugriff. Bilder der Mail selbst sind
 * schon data:-Adressen; Bilder aus dem Internet lädt sie nur nach „Bilder
 * laden“ (sonst ist jedes Laden aus dem Netz gesperrt). Links öffnen nach
 * Rückfrage mit dem echten Ziel.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MailBody(html: String, images: Boolean, onLink: (String) -> Unit) {
    val c = Titan.colors
    val shape = RoundedCornerShape(12.dp)
    val doc = fitMailHtml(html)
    if (LocalInspectionMode.current) {
        Box(Modifier.fillMaxWidth().clip(shape).background(Color.White).border(1.dp, c.border, shape).padding(12.dp)) {
            Text(html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim(), color = Color.Black, style = TitanType.body)
        }
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.setSupportMultipleWindows(false)
                setBackgroundColor(android.graphics.Color.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        onLink(request.url.toString())
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.requestLayout()
                    }
                }
            }
        },
        update = { w ->
            w.settings.blockNetworkLoads = !images
            w.settings.blockNetworkImage = !images
            val key = doc.hashCode() * 31 + if (images) 1 else 0
            if (w.tag != key) {
                w.tag = key
                w.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
            }
        },
    )
}

private fun cacheFile(ctx: Context, a: Attachment): File {
    val dir = File(ctx.cacheDir, "mail").apply { mkdirs() }
    val name = a.name.ifBlank { "attachment" }.replace(Regex("[/\\\\\u0000]"), "_")
    return File(dir, name)
}

/** Holt einen Anhang in den Cache und öffnet bzw. teilt ihn über den MailFileProvider. */
private suspend fun openPart(ctx: Context, model: MailModel, account: String, folder: String, uid: Long, a: Attachment, share: Boolean) {
    val file = cacheFile(ctx, a)
    withContext(Dispatchers.IO) { file.outputStream().use { model.api.part(account, folder, uid, a.part, it) } }
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".mail", file)
    val type = a.type.ifBlank { "application/octet-stream" }
    val intent = if (share) {
        Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** In „Downloads“ des Handys (MediaStore, ohne Berechtigung). */
private suspend fun downloadPart(ctx: Context, model: MailModel, account: String, folder: String, uid: Long, a: Attachment) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, a.name.ifBlank { "attachment" })
        put(MediaStore.Downloads.MIME_TYPE, a.type.ifBlank { "application/octet-stream" })
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = ctx.contentResolver
    val uri = withContext(Dispatchers.IO) { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) } ?: throw java.io.IOException("no download")
    try {
        withContext(Dispatchers.IO) { resolver.openOutputStream(uri)!!.use { model.api.part(account, folder, uid, a.part, it) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        withContext(Dispatchers.IO) { resolver.update(uri, values, null, null) }
    } catch (e: Exception) {
        withContext(Dispatchers.IO) { resolver.delete(uri, null, null) }
        throw e
    }
}
