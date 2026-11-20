package io.github.ra5on.titanos.pim

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.TopBar

/** Kalender wie im Web am Handy (web/src/routes/calendar, lib/pim/cal). */
@Composable
fun CalendarApp(nas: NasConnection, stream: NasConnection, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Page(topBar = { TopBar(stringResource(R.string.cal_title), onBack = onClose) }) {
        EmptyState(stringResource(R.string.cal_title), "")
    }
}

/** Kontakte wie im Web am Handy (web/src/routes/contacts, lib/pim/con). */
@Composable
fun ContactsApp(nas: NasConnection, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Page(topBar = { TopBar(stringResource(R.string.con_title), onBack = onClose) }) {
        EmptyState(stringResource(R.string.con_title), "")
    }
}
