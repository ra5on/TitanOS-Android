package io.github.ra5on.titanos.settings.pages.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.i18n.t

/**
 * USB-Stick (routes/settings/stick): seine Einstellungen stehen unter
 * Sicherungen › Konfiguration; alte Verweise führen wie im Web dorthin.
 */
@Composable
fun StickPage(a: PageArgs) {
    LoadingLine(t("common.loading"))
    LaunchedEffect(Unit) { a.nav.openPath("/settings/backups/configuration") }
}
