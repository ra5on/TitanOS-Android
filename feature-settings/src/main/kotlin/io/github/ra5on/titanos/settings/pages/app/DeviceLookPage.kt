package io.github.ra5on.titanos.settings.pages.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.ra5on.titanos.settings.R
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SliderField
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.TitanAppearance
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlin.math.roundToInt

/**
 * Darstellung nur für dieses Gerät (Inhaber 21.11.2026): Thema und Akzent
 * kommen aus dem Konto, je Gerät überschreibbar; Hell/Dunkel folgt ab Werk
 * dem Handy. Gilt sofort für alle Seiten der App ([TitanAppearance]).
 */
@Composable
fun DeviceLookPage() {
    val context = LocalContext.current
    val save = { TitanAppearance.saveDevice(context) }
    PageIntro(stringResource(R.string.look_device_intro))
    SettingGroup(t("look.mode")) {
        SettingRow(
            stringResource(R.string.look_mode),
            desc = stringResource(R.string.look_mode_desc),
            icon = WebIcons["palette"],
            control = {
                Select(
                    TitanAppearance.mode,
                    listOf(
                        "system" to stringResource(R.string.look_mode_system),
                        "account" to stringResource(R.string.look_mode_account),
                        "light" to stringResource(R.string.look_mode_light),
                        "dark" to stringResource(R.string.look_mode_dark),
                    ),
                    { TitanAppearance.mode = it; save() },
                    label = stringResource(R.string.look_mode),
                )
            },
        )
        SettingRow(
            stringResource(R.string.look_dark),
            desc = stringResource(R.string.look_dark_desc),
            icon = WebIcons["eye"],
            control = {
                Select(
                    TitanAppearance.darkVariant,
                    listOf("account" to stringResource(R.string.look_like_account)) +
                        listOf("graphit", "schwarz", "mitternacht").map { it to t("theme.$it") },
                    { TitanAppearance.darkVariant = it; save() },
                    label = stringResource(R.string.look_dark),
                )
            },
        )
        SettingRow(
            stringResource(R.string.look_accent),
            icon = WebIcons["star"],
            control = {
                Select(
                    TitanAppearance.accent,
                    listOf("account" to stringResource(R.string.look_like_account)) +
                        TitanAppearance.ACCENTS.keys.map { it to t("accent.$it") },
                    { TitanAppearance.accent = it; save() },
                    label = stringResource(R.string.look_accent),
                )
            },
        )
        val pct = (TitanAppearance.fontScale * 100).roundToInt()
        SettingRow(
            stringResource(R.string.look_font),
            desc = stringResource(R.string.look_font_desc),
            icon = WebIcons["fileText"],
            control = {
                SliderField(
                    TitanAppearance.fontScale,
                    { TitanAppearance.fontScale = (it * 20).roundToInt() / 20f },
                    0.9f..1.6f,
                    label = stringResource(R.string.look_font),
                    valueText = if (pct == 100) stringResource(R.string.look_font_system) else "$pct %",
                    steps = 13,
                    onDone = save,
                )
            },
        )
        SettingRow(stringResource(R.string.look_reset), control = {
            SmallButton(stringResource(R.string.look_reset), {
                TitanAppearance.mode = "system"; TitanAppearance.darkVariant = "account"; TitanAppearance.accent = "account"; TitanAppearance.fontScale = 1f
                save()
            })
        })
    }
}
