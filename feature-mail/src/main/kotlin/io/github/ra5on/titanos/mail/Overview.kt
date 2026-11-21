package io.github.ra5on.titanos.mail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.BigTitle
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import java.time.Instant

/**
 * Leerer Lesebereich als Übersicht (MailOverview.svelte): ungelesene Mails
 * des Postfachs je Ordner, markierte dieses Ordners, wann die Liste zuletzt
 * gelesen wurde und ob neue Mail von selbst kommt, ein paar Verweise.
 */
@Composable
fun MailOverview(
    model: MailModel,
    place: MailRoute.Folder?,
    onShow: (String) -> Unit,
    onFolder: (String, String) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = Titan.colors
    val account = place?.account?.takeIf { it != ALL }?.let { model.account(it) } ?: model.mainAccount()
    val unreadFolders = sortFolders(model.folders[account?.id.orEmpty()].orEmpty()).filter { it.unread > 0 && it.role != "junk" && it.role != "trash" }
    val unread = unreadFolders.sumOf { it.unread }
    var flagged by remember(place, model.refreshedAt) { mutableStateOf<List<MessageRow>?>(null) }
    LaunchedEffect(place, model.refreshedAt) {
        val p = place ?: return@LaunchedEffect
        if (!model.autoload) return@LaunchedEffect
        flagged = runCatching {
            if (p.account == ALL) model.api.unified("flagged", Filters(), limit = 5).messages
            else model.api.messages(p.account, p.folder, Filters(flagged = true), limit = 5).messages
        }.getOrNull()
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        BigTitle(stringResource(R.string.mail_overview_title))
        Text(stringResource(R.string.mail_overview_hint), style = TitanType.body, color = c.muted)
        if (account != null) {
            SectionHeader(
                if (unread == 0) stringResource(R.string.mail_overview_unreadNone, account.label)
                else stringResource(R.string.mail_overview_unreadSome, unread.toString(), account.label),
            )
            if (unreadFolders.isNotEmpty()) {
                Card {
                    unreadFolders.forEachIndexed { i, f ->
                        ListRow(folderName(f), icon = { RowIcon(folderIcon(f.role), tint = c.accent) }, right = { Count(f.unread) }, chevron = true, divider = i > 0, onClick = { onFolder(account.id, f.path) })
                    }
                    ListRow(stringResource(R.string.mail_overview_showUnread), icon = { RowIcon(MailIcons.unread) }, divider = true, onClick = { onShow("unread") })
                }
            }
        }
        val here = place?.let { p -> if (p.account == ALL) null else model.findFolder(p.account, p.folder) }
        SectionHeader(stringResource(R.string.mail_overview_flagged, here?.let { folderName(it) } ?: stringResource(R.string.mail_box_allInboxes)))
        Card {
            val list = flagged
            if (list.isNullOrEmpty()) {
                ListRow(stringResource(R.string.mail_overview_flaggedNone), icon = { RowIcon(MailIcons.flag) })
            } else {
                list.forEachIndexed { i, r ->
                    ListRow(r.subject.ifBlank { stringResource(R.string.mail_noSubject) }, sub = senderName(r.from), icon = { RowIcon(MailIcons.flag, tint = c.accent) }, divider = i > 0)
                }
                ListRow(stringResource(R.string.mail_overview_flaggedMore), divider = true, chevron = true, onClick = { onShow("flagged") })
            }
        }
        SectionHeader(stringResource(R.string.mail_overview_links))
        Card {
            val at = model.refreshedAt
            ListRow(
                if (at == null) stringResource(R.string.mail_overview_notYet) else stringResource(R.string.mail_overview_refreshed, listDate(at.toString(), currentLocale(), now = Instant.now())),
                sub = stringResource(if (model.live == Live.Connected) R.string.mail_overview_live else R.string.mail_overview_poll),
                icon = { RowIcon(TitanIcons.sync) },
            )
            ListRow(stringResource(R.string.mail_action_compose), icon = { RowIcon(TitanIcons.edit, tint = c.accent) }, divider = true, onClick = onCompose)
            ListRow(stringResource(R.string.mail_overview_settings), icon = { RowIcon(TitanIcons.gear) }, chevron = true, divider = true, onClick = onSettings)
        }
    }
}
