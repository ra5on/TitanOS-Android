package io.github.ra5on.titanos.pim

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.TopBar

/** Kalender wie im Web am Handy (web/src/routes/calendar, lib/pim/cal), am großen Bildschirm wie am PC. */
@Composable
fun CalendarApp(nas: NasConnection, stream: NasConnection, onClose: () -> Unit) {
    var sync by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (sync) {
        io.github.ra5on.titanos.pim.sync.SyncScreen(nas) { sync = false }
        return
    }
    io.github.ra5on.titanos.pim.cal.CalendarScreen(nas, stream, onClose, onSyncSettings = { sync = true })
}

/** Kontakte wie im Web am Handy (web/src/routes/contacts, lib/pim/con). */
@Composable
fun ContactsApp(nas: NasConnection, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Page(topBar = { TopBar(stringResource(R.string.con_title), onBack = onClose) }) {
        EmptyState(stringResource(R.string.con_title), "")
    }
}
