package io.github.ra5on.titanos.settings.pages.hardware

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject

/**
 * Ein-/Ausschalter am Gehäuse (routes/settings/power-button): was ein
 * kurzer Druck tut. Die Wahl wird sofort gespeichert (`PUT
 * /system/power-button` mit den ganzen Einstellungen).
 */
@Composable
fun PowerButtonPage(a: PageArgs) {
    val env = LocalEnv.current
    val state = rememberLoad { env.api.obj("system/power-button") }
    val run = rememberRunner()
    val savedText = t("powerButton.saved")
    PageIntro(t("powerButton.intro"))
    Loading(state) { status ->
        if (status.str("error").isNotEmpty()) Banner(status.str("error"), tone = Tone.Warn)
        if (status.bool("readOnly")) Banner(t("energy.readOnly"), tone = Tone.Warn)
        val settings = status.o("settings")
        val action = settings.str("action", "poweroff")
        SettingGroup {
            SettingRow(
                t("powerButton.press"),
                desc = t("powerButton.shutdownHint") + "\n" + t("powerButton.longPress"),
                icon = WebIcons["power"],
                control = {
                    Select(
                        action,
                        listOf("ignore", "reboot", "poweroff").map { it to t("powerButton.$it") },
                        { picked ->
                            run.run(guard = GuardAction.Settings, ok = savedText, detail = true) {
                                state.value = env.api.put("system/power-button", settings.with("action" to picked)) as? JsonObject ?: status
                            }
                        },
                        label = t("powerButton.press"),
                        enabled = !run.running && !status.bool("readOnly"),
                    )
                },
            )
            RunnerState(run)
        }
    }
}
