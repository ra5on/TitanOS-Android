package io.github.ra5on.titanos.mail

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.TopBar

@Composable
internal fun MailPlaceholder(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Page(topBar = { TopBar(stringResource(R.string.mail_title), onBack = onClose) }) {
        EmptyState(stringResource(R.string.mail_title), "")
    }
}
