package io.github.ra5on.titanos.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.R as UiR
import java.io.File

/** Die Ebenen von Mail (levels.ts) und die Seiten darüber. */
sealed interface MailRoute {
    data object Boxes : MailRoute
    data class Folder(val account: String, val folder: String) : MailRoute
    data class Read(val account: String, val folder: String, val uid: Long, val listAccount: String, val listFolder: String) : MailRoute
    data class Compose(val start: ComposeStart) : MailRoute
    data object Settings : MailRoute
    data class Account(val id: String) : MailRoute
    data object Setup : MailRoute
}

/**
 * Mail wie im Web am Handy (web/src/routes/mail, lib/tmail). [nas] für
 * Anfragen, [stream] mit langer Lesezeit für `GET /mail/events`.
 * [onClose] führt zurück zur Startseite.
 */
@Composable
fun MailApp(nas: NasConnection, stream: NasConnection, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(nas) { MailModel(MailApi(nas, stream), context.applicationContext, scope) }
    val titan = remember(nas) { TitanApi(nas) }
    val stack = remember { mutableStateListOf<MailRoute>(MailRoute.Boxes) }
    LaunchedEffect(model) {
        // Geöffnete oder geteilte Anhänge vom letzten Mal weg.
        runCatching { File(context.cacheDir, "mail").deleteRecursively() }
        model.start()
    }
    // Live nur, solange Mail zu sehen ist (wie die Seite, die im Hintergrund schließt).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, model.access) {
        if (model.access == true) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.watch() }
    }
    MailHost(model, titan, stack, onClose)
}

/** Zeigt die oberste Ebene; „Zurück“ geht eine Ebene hinauf. */
@Composable
fun MailHost(model: MailModel, titan: TitanApi?, stack: SnapshotStateList<MailRoute>, onClose: () -> Unit) {
    fun push(r: MailRoute) {
        stack.add(r)
    }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose()
    }
    val top = stack.last()
    // Seiten mit eigenem Zurück (Schreiben fragt, Einrichten geht Schritte zurück) behandeln es selbst.
    if (top !is MailRoute.Compose && top !is MailRoute.Setup && top !is MailRoute.Settings && top !is MailRoute.Account) BackHandler { pop() }

    when {
        model.accessError != null -> Page(topBar = { LevelHead(stringResource(R.string.desktop_app_mail), back = stringResource(R.string.mailx_home), onBack = onClose) }) {
            Problem(errorText(model.accessError), Modifier.fillMaxWidth())
            TitanButton(stringResource(UiR.string.ui_retry), { model.accessError = null; model.start() }, Modifier.fillMaxWidth().then(Modifier))
        }
        model.access == false -> Page(topBar = { LevelHead(stringResource(R.string.desktop_app_mail), back = stringResource(R.string.mailx_home), onBack = onClose) }) {
            EmptyState(stringResource(R.string.mail_disabled_title), stringResource(R.string.mail_disabled_text), icon = { RowIcon(TitanIcons.lock, size = 40.dp) })
        }
        else -> when (top) {
            MailRoute.Boxes -> BoxesScreen(
                model,
                onClose = onClose,
                onOpen = { a, f -> push(MailRoute.Folder(a, f)) },
                onCompose = { push(MailRoute.Compose(ComposeStart(account = model.mainAccount()?.id.orEmpty()))) },
                onSetup = { push(MailRoute.Setup) },
                onSettings = { push(MailRoute.Settings) },
            )
            is MailRoute.Folder -> {
                val list = model.list(top.account, top.folder)
                ListScreen(
                    model, list,
                    onBack = ::pop,
                    onOpen = { r ->
                        val acc = r.account.ifEmpty { top.account }
                        val folder = r.folder.ifEmpty { top.folder }
                        // Ein Entwurf öffnet zum Weiterschreiben.
                        push(MailRoute.Read(acc, folder, r.uid, top.account, top.folder))
                    },
                    onCompose = { push(MailRoute.Compose(ComposeStart(account = model.mainAccount(top.account)?.id.orEmpty()))) },
                )
            }
            is MailRoute.Read -> {
                val listName = when {
                    top.listAccount == ALL && top.listFolder == "flagged" -> stringResource(R.string.mail_box_flagged)
                    top.listAccount == ALL -> stringResource(R.string.mail_box_allInboxes)
                    else -> model.findFolder(top.listAccount, top.listFolder)?.let { folderName(it) } ?: top.listFolder
                }
                // Die Nachricht danach, gemerkt solange die offene noch in der Liste steht.
                val nextHolder = remember(top) { arrayOfNulls<MessageRow>(1) }
                nextAfter(model.list(top.listAccount, top.listFolder).rows, top)?.let { nextHolder[0] = it }
                ReadScreen(
                    model, titan, top.account, top.folder, top.uid,
                    backLabel = listName,
                    onBack = ::pop,
                    onCompose = { mode, msg ->
                        push(MailRoute.Compose(ComposeStart(mode, top.account, top.folder, msg)))
                    },
                    onMailto = { addr -> push(MailRoute.Compose(ComposeStart(account = top.account, to = addr))) },
                    onRemoved = {
                        // Nach dem Entfernen: die nächste Nachricht der Liste oder zurück (Einstellung).
                        stack.removeAt(stack.lastIndex)
                        val r = nextHolder[0]
                        if (model.prefs.afterRemove == AfterRemove.Next && r != null) {
                            push(MailRoute.Read(r.account.ifEmpty { top.listAccount }, r.folder.ifEmpty { top.listFolder }, r.uid, top.listAccount, top.listFolder))
                        }
                    },
                )
            }
            is MailRoute.Compose -> ComposeScreen(model, titan, top.start, onDone = ::pop)
            MailRoute.Settings -> SettingsScreen(model, onBack = ::pop, onAccount = { push(MailRoute.Account(it)) }, onSetup = { push(MailRoute.Setup) })
            is MailRoute.Account -> AccountScreen(model, top.id, onBack = ::pop, onRemoved = ::pop)
            MailRoute.Setup -> SetupScreen(model, onBack = ::pop, onSaved = { a ->
                pop()
                model.refreshFolders(a.id, now = true)
                model.accounts = (model.accounts.orEmpty().filter { it.id != a.id } + a)
            })
        }
    }
}

/** Die Zeile nach der entfernten (sonst die davor), wie after() der Liste. */
private fun nextAfter(rows: List<MessageRow>, gone: MailRoute.Read): MessageRow? {
    val at = rows.indexOfFirst { it.uid == gone.uid && it.folder.ifEmpty { gone.listFolder } == gone.folder }
    if (at < 0) return null
    return rows.getOrNull(at + 1) ?: rows.getOrNull(at - 1)
}
