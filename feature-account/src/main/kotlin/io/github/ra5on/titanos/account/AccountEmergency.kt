package io.github.ra5on.titanos.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.pages.smarthome.EmergencyPanel
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.i18n.t

/**
 * Notfallzugang (ProtectEmergency.svelte) – nur, wenn das Konto dafür
 * eingetragen ist (`/agent/protect/me`). Die Ansicht selbst ist dieselbe wie in
 * Systemsteuerung › KI-Agent › Funktionen › Notfallzugang (feature-settings).
 */
@Composable
internal fun EmergencyGroup(ctx: AccountCtx) {
    var person by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { person = runCatching { ctx.calls.protectMe().bool("emergency") }.getOrDefault(false) }
    if (!person) return
    SettingGroup(t("agent.f.menu.emergency")) {
        EmergencyPanel(ctx.name, ctx.admin, onlyPerson = true)
    }
}
