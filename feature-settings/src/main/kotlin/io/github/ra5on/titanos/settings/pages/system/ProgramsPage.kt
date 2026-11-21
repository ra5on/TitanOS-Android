package io.github.ra5on.titanos.settings.pages.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.i18n.t

/**
 * Programme (routes/settings/programs): gibt es seit PLAN 75-20 nicht mehr –
 * wer welche App nutzen darf, steht je Konto unter Benutzer. Alte Verweise
 * führen wie im Web dorthin.
 */
@Composable
fun ProgramsPage(a: PageArgs) {
    val env = LocalEnv.current
    LoadingLine(t("common.loading"))
    LaunchedEffect(Unit) { env.open("/users") }
}
