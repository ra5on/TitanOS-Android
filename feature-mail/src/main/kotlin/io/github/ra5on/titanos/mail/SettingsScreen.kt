package io.github.ra5on.titanos.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import io.github.ra5on.titanos.ui.R as UiR
import kotlinx.coroutines.launch

/** Eine Wahl, die ein Blatt mit Möglichkeiten öffnet. */
private class Ask(val title: String, val options: List<Pair<String, String>>, val value: String, val pick: (String) -> Unit)

/**
 * Mail-Einstellungen (routes/mail/settings): Postfächer, Ansicht, Meldungen,
 * Signaturen, Regeln – dazu, was nur dieses Handy betrifft (Wischen, Bilder,
 * Nachfragen, Entwürfe, Zitat).
 */
@Composable
fun SettingsScreen(model: MailModel, onBack: () -> Unit, onAccount: (String) -> Unit, onSetup: () -> Unit) {
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(model.settings) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var saveError by remember { mutableStateOf<Throwable?>(null) }
    var notifyError by remember { mutableStateOf<Throwable?>(null) }
    var changed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var removeSig by remember { mutableStateOf<Signature?>(null) }
    var removeRule by remember { mutableStateOf<MailRule?>(null) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        if (!model.autoload) return@LaunchedEffect
        try {
            settings = model.loadSettings()
        } catch (e: Exception) {
            loadError = e
        }
    }
    fun touch(next: MailSettings) {
        settings = next
        changed = true
        saved = false
    }
    fun save(body: MailSettings) {
        busy = true
        saveError = null
        scope.launch {
            try {
                // Die Signatur ab Werk steht beim Postfach; die Listen hier sagen dasselbe.
                val out = body.copy(signatures = body.signatures.map { s -> s.copy(default = model.accounts.orEmpty().filter { it.signature == s.id }.map { it.id }) })
                settings = model.api.saveSettings(out)
                model.settings = settings
                model.loadAccounts()
                changed = false
                saved = true
            } catch (e: Exception) {
                saveError = e
            } finally {
                busy = false
            }
        }
    }
    val p = model.prefs
    val swipeOptions = listOf(
        SwipeAction.Seen.name to stringResource(R.string.mailx_swipe_seen),
        SwipeAction.Flag.name to stringResource(R.string.mail_action_flag),
        SwipeAction.Archive.name to stringResource(R.string.mail_action_archive),
        SwipeAction.Delete.name to stringResource(R.string.mail_action_delete),
        SwipeAction.Move.name to stringResource(R.string.mail_action_move),
        SwipeAction.Junk.name to stringResource(R.string.mail_action_junk),
        SwipeAction.None.name to stringResource(R.string.mailx_swipe_none),
    )
    val imagesOptions = listOf(
        ImagesMode.Ask.name to stringResource(R.string.mailx_images_ask),
        ImagesMode.Contacts.name to stringResource(R.string.mailx_images_contacts),
        ImagesMode.Always.name to stringResource(R.string.mailx_images_always),
    )
    val afterOptions = listOf(
        AfterRemove.List.name to stringResource(R.string.mailx_after_list),
        AfterRemove.Next.name to stringResource(R.string.mailx_after_next),
    )
    val autosaveOptions = MailPrefs.AUTOSAVE.map { s ->
        s.toString() to when {
            s == 0 -> stringResource(R.string.mailx_autosave_off)
            s < 60 -> stringResource(R.string.mailx_autosave_seconds, s.toString())
            else -> stringResource(R.string.mailx_autosave_minutes, (s / 60).toString())
        }
    }
    val swipeRightTitle = stringResource(R.string.mailx_swipe_right)
    val swipeLeftTitle = stringResource(R.string.mailx_swipe_left)
    val imagesTitle = stringResource(R.string.mailx_images)
    val afterTitle = stringResource(R.string.mailx_after)
    val autosaveTitle = stringResource(R.string.mailx_autosave)
    val startTitle = stringResource(R.string.mailx_start_at)
    val startOptions = listOf(
        StartAt.Boxes.name to stringResource(R.string.mailx_start_boxes),
        StartAt.AllInboxes.name to stringResource(R.string.mail_box_allInboxes),
        StartAt.MainInbox.name to stringResource(R.string.mailx_start_main),
    )
    var keysOpen by remember { mutableStateOf(false) }

    Page(
        topBar = { LevelHead(stringResource(R.string.mail_settings_title), back = stringResource(R.string.mail_box_title), onBack = onBack) },
        overlay = {
            if (keysOpen) KeysSheet { keysOpen = false }
            ask?.let { a ->
                Sheet(onDismiss = { ask = null }) {
                    Text(a.title, style = TitanType.title, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
                    Card(background = c.bg) {
                        a.options.forEachIndexed { i, (k, label) -> ChoiceRow(label, k == a.value, { a.pick(k); ask = null }, divider = i > 0) }
                    }
                }
            }
            removeSig?.let { s ->
                ConfirmDialog(
                    stringResource(R.string.mail_signature_removeTitle), stringResource(R.string.mail_signature_removeText, s.name),
                    listOf(
                        DialogAction(stringResource(R.string.mail_signature_remove), danger = true) { settings?.let { touch(it.copy(signatures = it.signatures - s)) }; removeSig = null },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { removeSig = null },
                    ),
                    onDismiss = { removeSig = null },
                )
            }
            removeRule?.let { r ->
                ConfirmDialog(
                    stringResource(R.string.mail_rule_removeTitle), stringResource(R.string.mail_rule_removeText, ruleText(r)),
                    listOf(
                        DialogAction(stringResource(R.string.mail_rule_remove), danger = true) { settings?.let { touch(it.copy(rules = it.rules - r)) }; removeRule = null },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { removeRule = null },
                    ),
                    onDismiss = { removeRule = null },
                )
            }
        },
    ) {
        ExpandGroup(stringResource(R.string.mail_settings_accounts), startOpen = true) {
        Card {
            val list = model.orderedAccounts()
            list.forEachIndexed { i, a ->
                ListRow(
                    a.label,
                    sub = a.email + if (a.status.state != "ok") " · " + statusText(a.status) else "",
                    icon = { RowIcon(if (a.status.state == "ok") TitanIcons.mail else TitanIcons.warn, tint = if (a.status.state == "ok") c.muted else c.warnInk) },
                    chevron = true,
                    divider = i > 0,
                    onClick = { onAccount(a.id) },
                )
            }
            ListRow(stringResource(R.string.mail_side_add), icon = { RowIcon(TitanIcons.plus) }, chevron = true, divider = list.isNotEmpty(), onClick = onSetup)
        }
        if (model.accounts.orEmpty().isEmpty()) Text(stringResource(R.string.mail_empty_text), style = TitanType.small, color = c.muted, modifier = Modifier.padding(4.dp))

        }
        ExpandGroup(stringResource(R.string.mail_settings_view), stringResource(R.string.mailx_device_only)) {
        Card {
            ToggleRow(stringResource(R.string.mail_thread_toggle), p.threads, { model.updatePrefs(p.copy(threads = it)) }, sub = stringResource(R.string.mail_settings_threadsDesc))
            ToggleRow(stringResource(R.string.mailx_avatars), p.avatars, { model.updatePrefs(p.copy(avatars = it)) }, sub = stringResource(R.string.mailx_avatars_desc), divider = true)
            ToggleRow(stringResource(R.string.mailx_preview), p.preview, { model.updatePrefs(p.copy(preview = it)) }, sub = stringResource(R.string.mailx_preview_desc), divider = true)
            ListRow(imagesTitle, sub = imagesOptions.first { it.first == p.images.name }.second, chevron = true, divider = true,
                onClick = { ask = Ask(imagesTitle, imagesOptions, p.images.name) { k -> model.updatePrefs(model.prefs.copy(images = ImagesMode.valueOf(k))) } })
            ListRow(swipeRightTitle, sub = swipeOptions.first { it.first == p.swipeRight.name }.second, chevron = true, divider = true,
                onClick = { ask = Ask(swipeRightTitle, swipeOptions, p.swipeRight.name) { k -> model.updatePrefs(model.prefs.copy(swipeRight = SwipeAction.valueOf(k))) } })
            ListRow(swipeLeftTitle, sub = swipeOptions.first { it.first == p.swipeLeft.name }.second, chevron = true, divider = true,
                onClick = { ask = Ask(swipeLeftTitle, swipeOptions, p.swipeLeft.name) { k -> model.updatePrefs(model.prefs.copy(swipeLeft = SwipeAction.valueOf(k))) } })
            ToggleRow(stringResource(R.string.mailx_confirm_delete), p.confirmDelete, { model.updatePrefs(p.copy(confirmDelete = it)) }, sub = stringResource(R.string.mailx_confirm_delete_desc), divider = true)
            ListRow(afterTitle, sub = afterOptions.first { it.first == p.afterRemove.name }.second, chevron = true, divider = true,
                onClick = { ask = Ask(afterTitle, afterOptions, p.afterRemove.name) { k -> model.updatePrefs(model.prefs.copy(afterRemove = AfterRemove.valueOf(k))) } })
            ListRow(autosaveTitle, sub = autosaveOptions.first { it.first == p.autosaveSeconds.toString() }.second, chevron = true, divider = true,
                onClick = { ask = Ask(autosaveTitle, autosaveOptions, p.autosaveSeconds.toString()) { k -> model.updatePrefs(model.prefs.copy(autosaveSeconds = k.toInt())) } })
            ToggleRow(stringResource(R.string.mailx_quote), p.quoteReply, { model.updatePrefs(p.copy(quoteReply = it)) }, sub = stringResource(R.string.mailx_quote_desc), divider = true)
        }

        }
        ExpandGroup(stringResource(R.string.mailx_group_list), stringResource(R.string.mailx_device_only)) {
            Card {
                ListRow(startTitle, sub = startOptions.first { it.first == p.startAt.name }.second, chevron = true,
                    onClick = { ask = Ask(startTitle, startOptions, p.startAt.name) { k -> model.updatePrefs(model.prefs.copy(startAt = StartAt.valueOf(k))) } })
                ToggleRow(stringResource(R.string.mailx_compact), p.compact, { model.updatePrefs(p.copy(compact = it)) }, sub = stringResource(R.string.mailx_compact_desc), divider = true)
                ToggleRow(stringResource(R.string.mailx_folder_counts), p.folderCounts, { model.updatePrefs(p.copy(folderCounts = it)) }, divider = true)
                ToggleRow(stringResource(R.string.mailx_all_recipients), p.showAllRecipients, { model.updatePrefs(p.copy(showAllRecipients = it)) }, sub = stringResource(R.string.mailx_all_recipients_desc), divider = true)
                ToggleRow(stringResource(R.string.mailx_ask_subject), p.askEmptySubject, { model.updatePrefs(p.copy(askEmptySubject = it)) }, divider = true)
            }
        }
        ExpandGroup(stringResource(R.string.mailx_group_input), stringResource(R.string.mailx_group_input_desc)) {
            Card {
                ToggleRow(stringResource(R.string.mailx_shortcuts), p.shortcuts, { model.updatePrefs(p.copy(shortcuts = it)) }, sub = stringResource(R.string.mailx_shortcuts_desc))
                ListRow(stringResource(R.string.mail_keys_title), chevron = true, divider = true, onClick = { keysOpen = true })
                ToggleRow(stringResource(R.string.mailx_drag), p.dragDrop, { model.updatePrefs(p.copy(dragDrop = it)) }, sub = stringResource(R.string.mailx_drag_desc), divider = true)
                ListRow(stringResource(R.string.mailx_reset_columns), sub = stringResource(R.string.mailx_reset_columns_desc, p.sidebarDp.toString(), p.listDp.toString()), divider = true,
                    onClick = { model.updatePrefs(p.copy(sidebarDp = MailPrefs().sidebarDp, listDp = MailPrefs().listDp)) })
            }
        }
        loadError?.let { Problem(errorText(it), Modifier.padding(top = 10.dp)) }
        val s = settings
        ExpandGroup(stringResource(R.string.mail_settings_notify), stringResource(R.string.mail_settings_notifyDesc)) {
        notifyError?.let { Problem(errorText(it), Modifier.padding(bottom = 8.dp)) }
        Card {
            if (s != null) {
                ToggleRow(stringResource(R.string.mail_settings_pushContent), s.pushContent, { on -> save(s.copy(pushContent = on)) }, sub = stringResource(R.string.mail_settings_pushContentDesc), enabled = !busy, icon = TitanIcons.info)
            }
            model.orderedAccounts().forEachIndexed { i, a ->
                ToggleRow(a.label, a.notify, { on ->
                    notifyError = null
                    scope.launch {
                        try {
                            model.api.updateAccount(a.id, AccountInput(a.name, a.email, a.senderName, a.user, a.imap, a.smtp, on, a.signature, ""))
                            model.loadAccounts()
                        } catch (e: Exception) {
                            notifyError = e
                        }
                    }
                }, divider = s != null || i > 0, icon = TitanIcons.bell)
            }
        }
        Text(stringResource(R.string.mailx_android_notify), style = TitanType.small, color = c.muted, modifier = Modifier.padding(4.dp))


        }
        if (s != null) {
            ExpandGroup(stringResource(R.string.mail_settings_signatures), stringResource(R.string.mail_settings_signaturesDesc)) {
            if (s.signatures.isEmpty()) Text(stringResource(R.string.mail_signature_none), style = TitanType.body, color = c.muted, modifier = Modifier.padding(4.dp))
            for (sig in s.signatures) {
                Card(Modifier.padding(bottom = 10.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        LabeledField(stringResource(R.string.mail_signature_name), sig.name, { v -> touch(s.copy(signatures = s.signatures.map { if (it.id == sig.id) it.copy(name = v) else it })) })
                        Text(stringResource(R.string.mail_signature_text), style = TitanType.small, color = c.muted, modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 6.dp))
                        TextInput(sig.text, { v -> touch(s.copy(signatures = s.signatures.map { if (it.id == sig.id) it.copy(text = v) else it })) }, stringResource(R.string.mail_signature_text))
                        TextAction(stringResource(R.string.mail_signature_remove), { removeSig = sig }, danger = true)
                    }
                }
            }
            val newSig = stringResource(R.string.mail_signature_newName)
            TitanButton(stringResource(R.string.mail_signature_add), { touch(s.copy(signatures = s.signatures + Signature(newId(), newSig, ""))) }, icon = TitanIcons.plus)

            }
            ExpandGroup(stringResource(R.string.mail_settings_rules), stringResource(R.string.mail_settings_rulesDesc)) {
            if (s.rules.isEmpty()) Text(stringResource(R.string.mail_rule_none), style = TitanType.body, color = c.muted, modifier = Modifier.padding(4.dp))
            s.rules.forEachIndexed { n, r ->
                fun set(next: MailRule) = touch(s.copy(rules = s.rules.map { if (it.id == r.id) next else it }))
                SectionHeader(stringResource(R.string.mail_rule_legend, (n + 1).toString()), top = 6.dp)
                Card(Modifier.padding(bottom = 10.dp)) {
                    ToggleRow(stringResource(if (r.enabled) R.string.mail_rule_on else R.string.mail_rule_off), r.enabled, { set(r.copy(enabled = it)) })
                    val accTitle = stringResource(R.string.mail_rule_account)
                    val accOptions = model.orderedAccounts().map { it.id to it.label }
                    ListRow(accTitle, sub = model.account(r.account)?.label ?: r.account, chevron = true, divider = true,
                        onClick = { ask = Ask(accTitle, accOptions, r.account) { k -> set(r.copy(account = k, then = r.then.copy(moveTo = ""))) } })
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text(stringResource(R.string.mail_rule_when), style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(top = 6.dp))
                        LabeledField(stringResource(R.string.mail_rule_from), r.`when`.from, { set(r.copy(`when` = r.`when`.copy(from = it))) })
                        LabeledField(stringResource(R.string.mail_rule_to), r.`when`.to, { set(r.copy(`when` = r.`when`.copy(to = it))) })
                        LabeledField(stringResource(R.string.mail_rule_subject), r.`when`.subject, { set(r.copy(`when` = r.`when`.copy(subject = it))) })
                        Text(stringResource(R.string.mail_rule_then), style = TitanType.rowTitle, color = c.text, modifier = Modifier.padding(top = 14.dp))
                    }
                    val moveTitle = stringResource(R.string.mail_rule_moveTo)
                    val noMove = stringResource(R.string.mail_rule_noMove)
                    val folderOptions = listOf("" to noMove) + sortFolders(model.folders[r.account].orEmpty()).map { it.path to folderLabel(it) }
                    ListRow(moveTitle, sub = folderOptions.firstOrNull { it.first == r.then.moveTo }?.second ?: r.then.moveTo, chevron = true, divider = true,
                        onClick = { ask = Ask(moveTitle, folderOptions, r.then.moveTo) { k -> set(r.copy(then = r.then.copy(moveTo = k))) } })
                    ToggleRow(stringResource(R.string.mail_rule_markRead), r.then.markRead, { set(r.copy(then = r.then.copy(markRead = it))) }, divider = true)
                    ToggleRow(stringResource(R.string.mail_rule_flag), r.then.flag, { set(r.copy(then = r.then.copy(flag = it))) }, divider = true)
                    ListRow(stringResource(R.string.mail_rule_remove), icon = { RowIcon(TitanIcons.trash, tint = c.dangerInk) }, titleColor = c.dangerInk, divider = true, onClick = { removeRule = r })
                }
            }
            TitanButton(stringResource(R.string.mail_rule_add), {
                val first = model.orderedAccounts().firstOrNull()?.id.orEmpty()
                touch(s.copy(rules = s.rules + MailRule(newId(), first, true)))
            }, icon = TitanIcons.plus, enabled = model.accounts.orEmpty().isNotEmpty())
            Note(stringResource(R.string.mail_settings_rulesNew), Modifier.padding(top = 10.dp))
            }
            Gap(14.dp)
            saveError?.let { Problem(errorText(it), Modifier.padding(bottom = 8.dp)) }
            if (saved) Done(stringResource(R.string.mail_settings_saved), Modifier.padding(bottom = 8.dp))
            TitanButton(stringResource(R.string.mail_settings_saveAll), { settings?.let(::save) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = changed && !busy)
        }
        Gap(24.dp)
    }
}

/**
 * Eine aufklappbare Gruppe wie SettingGroup der Systemsteuerung: Kopf mit
 * Titel, Beschreibung und Zustand in Worten (auf-/zugeklappt).
 */
@Composable
fun ExpandGroup(title: String, desc: String? = null, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(startOpen) }
    val c = Titan.colors
    val stateWord = stringResource(if (open) R.string.mailx_shown else R.string.mailx_collapsed)
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open }
            .semantics { stateDescription = stateWord }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TitanType.rowTitle, color = c.text)
            if (desc != null) Text(desc, style = TitanType.small, color = c.muted)
        }
        androidx.compose.material3.Icon(if (open) TitanIcons.down else TitanIcons.chev, null, tint = c.accent, modifier = Modifier.padding(start = 8.dp))
    }
    if (open) Column(Modifier.padding(top = 4.dp)) { content() }
}

/** Ein Ordner mit Rolle in Worten (Regeln). */
@Composable
private fun folderLabel(f: MailFolder): String = folderName(f).let { if (f.role.isEmpty()) f.path else it }

@Composable
private fun ruleText(r: MailRule): String {
    val parts = listOfNotNull(
        r.`when`.from.takeIf { it.isNotBlank() }?.let { stringResource(R.string.mail_rule_fromHas, it) },
        r.`when`.to.takeIf { it.isNotBlank() }?.let { stringResource(R.string.mail_rule_toHas, it) },
        r.`when`.subject.takeIf { it.isNotBlank() }?.let { stringResource(R.string.mail_rule_subjectHas, it) },
    )
    return parts.joinToString(", ").ifEmpty { stringResource(R.string.mail_rule_empty) }
}

private fun newId(): String = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
