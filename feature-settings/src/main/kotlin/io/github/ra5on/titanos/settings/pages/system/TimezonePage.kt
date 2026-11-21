package io.github.ra5on.titanos.settings.pages.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import java.time.ZoneId

/**
 * Datum und Uhrzeit (routes/settings/timezone): die Zeitzone des NAS aus
 * `GET /network`, gespeichert mit `PUT /system/timezone`. Vorlage für die
 * Seiten: dieselben Textschlüssel wie das Web, laden mit [rememberLoad],
 * ändern mit einem Runner (Fehler in Worten, „Wird gespeichert …“).
 */
@Composable
fun TimezonePage(a: PageArgs) {
    val env = LocalEnv.current
    val state = rememberLoad { env.api.obj("network").str("timezone") }
    val run = rememberRunner()
    Loading(state) { current ->
        var zone by remember(current) { mutableStateOf(current) }
        val zones = remember(current) { (listOf(current, "UTC") + ZoneId.getAvailableZoneIds().sorted()).filter { it.isNotEmpty() }.distinct() }
        SettingGroup {
            SettingRow(
                t("settings.timezone"),
                desc = t("settings.timeHint"),
                icon = WebIcons["globe"],
                control = {
                    Select(zone, zones.map { it to it }, { picked ->
                        run.run(ok = null) {
                            env.api.put("system/timezone", jobj("timezone" to picked))
                            zone = picked
                        }
                    }, label = t("settings.timezone"), enabled = !run.running)
                },
            )
            RunnerState(run)
        }
    }
}
