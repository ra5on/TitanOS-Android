package io.github.ra5on.titanos.settings.pages.security

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons

/**
 * Nutzer (routes/settings/users): im Web dieselbe Seite wie das Programm
 * „Benutzer“ (/users). Einstellungen hat sie keine eigenen; die App zeigt die
 * Konten mit Rolle und Zustand, und jedes Konto wie „Neues Konto“ führt in
 * die Benutzerverwaltung (`/users`), die dort alles kann.
 */
@Composable
fun UsersPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val state = rememberLoad { env.api.list("users").objects() }
    Loading(state) { accounts ->
        PageIntro(if (accounts.size == 1) t("users.summaryOne") else t("users.summary", "count" to accounts.size))
        SettingGroup(t("users.list"), tools = {
            SmallButton(t("users.new"), { env.open("/users") }, kind = ButtonKind.Primary, icon = WebIcons["plus"])
        }) {
            for (acc in accounts) {
                val sub = buildString {
                    append(tr.or("role.${acc.str("role")}", acc.str("role")))
                    if (acc.bool("disabled")) append(" · ").append(tr.t("users.statusLocked"))
                    if (acc.bool("twoFactor")) append(" · ").append(tr.t("users.twoFactor"))
                }
                SettingRow(acc.str("name"), desc = sub, icon = WebIcons["users"], onClick = { env.open("/users") })
            }
        }
        // Kamerarechte je Konto (im Web in der Kontoansicht unter Benutzer, nvr.rights.*).
        // TODO(D1, feature-nvr): Die Seite baut Helfer D1 „Rechnen“ (feat/1.00-compute) in
        // feature-nvr. Bis sie zusammengeführt ist, führt der Eintrag zur Adresse /nvr/rights,
        // die die App noch mit „kommt später“ beantwortet; danach dort die Seite öffnen.
        if (env.admin) {
            SettingGroup(t("nvr.rights.title")) {
                SettingRow(t("nvr.rights.title"), desc = t("nvr.rights.hint"), icon = WebIcons["camera"], onClick = { env.open("/nvr/rights") })
            }
        }
    }
}
