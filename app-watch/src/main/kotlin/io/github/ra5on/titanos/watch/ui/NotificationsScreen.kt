package io.github.ra5on.titanos.watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.data.AppNotification
import io.github.ra5on.titanos.watch.graph
import io.github.ra5on.titanos.watch.tile.StatusTileService

/** Meldungen des NAS (dieselben wie die Mitteilungen am Handy), neueste zuerst. */
@Composable
fun NotificationsRoute() {
    val context = LocalContext.current
    val g = context.graph
    var items by remember { mutableStateOf<List<AppNotification>?>(null) }
    var problem by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val api = g.api() ?: return@LaunchedEffect
        try {
            val all = api.notifications(null)
            items = all.items.reversed()
            // Angesehen: auf Kachel und Startseite nicht mehr „neu“.
            g.status.seen = all.latest
            runCatching { g.status.refresh(api) }
            StatusTileService.requestUpdate(context)
        } catch (_: Exception) {
            problem = R.string.w_err_reach
        }
    }
    NotificationsScreen(items, problem)
}

@Composable
fun NotificationsScreen(items: List<AppNotification>?, problem: Int?) {
    Page {
        item { Header(stringResource(R.string.w_messages)) }
        problem?.let { item { Problem(stringResource(it)) } }
        when {
            items == null && problem == null -> item { Busy(stringResource(R.string.w_loading)) }
            items != null && items.isEmpty() -> item { WText(stringResource(R.string.w_messages_none), center = true) }
        }
        items?.forEach { n ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    if (n.severity == "warning" || n.severity == "error") {
                        StateLine(false, stringResource(if (n.severity == "error") R.string.w_sev_error else R.string.w_sev_warning))
                    }
                    WText(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 8.dp))
                    if (n.text.isNotBlank()) WText(n.text, modifier = Modifier.padding(horizontal = 8.dp))
                    Format.instant(n.time)?.let { at ->
                        WText(time(at.toEpochMilli()), color = WatchColors.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }
    }
}
