package io.github.ra5on.titanos.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.data.Chat
import io.github.ra5on.titanos.watch.data.isHidden
import io.github.ra5on.titanos.watch.graph
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Freigaben des Agenten: was er tun möchte, erlauben oder ablehnen. */
@Composable
fun ApprovalsRoute() {
    val context = LocalContext.current
    val g = context.graph
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf<List<Chat>?>(null) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        val api = g.api() ?: return
        try {
            waiting = coroutineScope {
                api.chats().filter { it.state == "waiting" }.map { async { api.chat(it.id) } }.awaitAll().filter { it.pending != null }
            }
            problem = null
        } catch (e: Exception) {
            problem = if (e.isHidden()) R.string.w_agent_err_rights else R.string.w_err_reach
        }
    }
    LaunchedEffect(Unit) { load() }
    ApprovalsScreen(waiting, problem, busy) { chat, approve ->
        val api = g.api() ?: return@ApprovalsScreen
        val pending = chat.pending ?: return@ApprovalsScreen
        busy = chat.id
        scope.launch {
            runCatching { api.decide(chat.id, pending.id, approve) }.onFailure { problem = R.string.w_err_reach }
            busy = null
            load()
        }
    }
}

@Composable
fun ApprovalsScreen(waiting: List<Chat>?, problem: Int?, busy: String?, onDecide: (Chat, Boolean) -> Unit) {
    Page {
        item { Header(stringResource(R.string.w_approvals)) }
        if (problem != null) item { Problem(stringResource(problem)) }
        when {
            waiting == null && problem == null -> item { Busy(stringResource(R.string.w_loading)) }
            waiting != null && waiting.isEmpty() -> item { WText(stringResource(R.string.w_approvals_none), center = true) }
        }
        for (chat in waiting.orEmpty()) {
            val p = chat.pending ?: continue
            item { WText(chat.title, center = true, color = WatchColors.muted) }
            item { ApprovalCard(p.summary.ifBlank { p.tool }, p.level) }
            if (busy == chat.id) {
                item { Busy(stringResource(R.string.w_agent_working)) }
            } else {
                item { RowButton(stringResource(R.string.w_allow), onClick = { onDecide(chat, true) }, icon = TitanIcons.check, filled = true) }
                item { RowButton(stringResource(R.string.w_deny), onClick = { onDecide(chat, false) }, icon = TitanIcons.x) }
            }
        }
    }
}
