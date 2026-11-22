package io.github.ra5on.titanos.settings.pages.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.ra5on.titanos.ui.R
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.web.WebIcons

/**
 * Welche Aktionen den Fingerabdruck verlangen (Entscheidung des Inhabers
 * 21.11.2026). Konsolen stehen nicht in der Liste und sind nicht wählbar.
 */
@Composable
fun GuardPage() {
    val context = LocalContext.current
    var round by remember { mutableIntStateOf(0) }
    val on = stringResource(R.string.ui_on)
    val off = stringResource(R.string.ui_off)
    PageIntro(stringResource(R.string.guard_intro))
    if (!Guard.available(context)) Banner(stringResource(R.string.guard_unavailable), tone = Tone.Warn)
    Banner(stringResource(R.string.guard_consoles))
    SettingGroup(stringResource(R.string.guard_title)) {
        for (action in GuardAction.entries) {
            val checked = remember(round) { Guard.enabled(context, action) }
            val label = stringResource(action.label)
            SettingRow(
                label,
                desc = stringResource(action.hint),
                icon = WebIcons[if (action == GuardAction.AppOpen) "lock" else "shield"],
                control = { Toggle(checked, { Guard.set(context, action, it); round++ }, on, off, label = label) },
            )
        }
    }
    SettingGroup {
        val grace = remember(round) { Guard.graceSeconds(context) }
        val choices = Guard.GRACE_CHOICES.map { s ->
            s to when {
                s == 0 -> stringResource(R.string.guard_grace_0)
                s < 60 -> stringResource(R.string.guard_grace_seconds, s)
                s == 60 -> stringResource(R.string.guard_grace_seconds, 60)
                else -> stringResource(R.string.guard_grace_minutes, s / 60)
            }
        }
        SettingRow(
            stringResource(R.string.guard_grace),
            desc = stringResource(R.string.guard_grace_hint),
            icon = WebIcons["clock"],
            control = { Select(grace, choices, { Guard.setGraceSeconds(context, it); round++ }, label = stringResource(R.string.guard_grace)) },
        )
        SettingRow(stringResource(R.string.guard_reset), control = { SmallButton(stringResource(R.string.guard_reset), { Guard.reset(context); round++ }) })
    }
}
