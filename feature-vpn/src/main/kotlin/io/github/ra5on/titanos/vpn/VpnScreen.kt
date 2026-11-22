package io.github.ra5on.titanos.vpn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Berechtigungen und Android-Zustände, die die Seite zeigt. */
data class VpnPerms(
    val location: Boolean = false,
    val backgroundLocation: Boolean = false,
    val locationOn: Boolean = true,
    val vpnAllowed: Boolean = false,
    val batteryExempt: Boolean = false,
    val notifications: Boolean = false,
)

/** Alles, was die Seite zeigt (ohne Schlüssel). */
data class VpnUi(
    val live: VpnLive = VpnLive(),
    val settings: VpnSettings = VpnSettings(),
    val profile: VpnProfile? = null,
    val answer: VpnAnswer? = null,
    val loading: Boolean = false,
    /** Fehlercode des NAS oder „unreachable“. */
    val error: String? = null,
    val busy: Boolean = false,
    val perms: VpnPerms = VpnPerms(),
    val currentSsid: String? = null,
    /** Der Zugang war hier bekannt, das NAS kennt ihn nicht mehr. */
    val gone: Boolean = false,
)

/** Was die Seite auslöst. */
data class VpnActions(
    val back: () -> Unit = {},
    val connect: () -> Unit = {},
    val disconnect: () -> Unit = {},
    val create: () -> Unit = {},
    val regenerate: () -> Unit = {},
    val remove: () -> Unit = {},
    val retry: () -> Unit = {},
    val update: (VpnSettings) -> Unit = {},
    val askLocation: () -> Unit = {},
    val askBackgroundLocation: () -> Unit = {},
    val openLocationSettings: () -> Unit = {},
    val askVpn: () -> Unit = {},
    val openVpnSettings: () -> Unit = {},
    val askBattery: () -> Unit = {},
    val askNotifications: () -> Unit = {},
    val addTile: () -> Unit = {},
)

/** Seite „VPN“ der App-Einstellungen; ab Split zwei Spalten. */
@Composable
fun VpnScreen(ui: VpnUi, actions: VpnActions, expanded: Boolean = false) {
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmRenew by remember { mutableStateOf(false) }
    Page(
        topBar = {
            // Die Seite liegt über der Leiste der App: die Statusleiste von Android freilassen (wie AboutScreen).
            Box(Modifier.statusBarsPadding()) {
                TopBar(stringResource(R.string.vpn_title), onBack = actions.back, backLabel = stringResource(UiR.string.ui_back), icon = { RowIcon(TitanIcons.shield, tint = Titan.colors.accent) })
            }
        },
        overlay = {
            if (confirmRemove) {
                ConfirmDialog(
                    title = stringResource(R.string.vpn_remove),
                    text = stringResource(R.string.vpn_remove_text),
                    actions = listOf(
                        DialogAction(stringResource(R.string.vpn_remove_do), danger = true) { confirmRemove = false; actions.remove() },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { confirmRemove = false },
                    ),
                    onDismiss = { confirmRemove = false },
                )
            }
            if (confirmRenew) {
                ConfirmDialog(
                    title = stringResource(R.string.vpn_regenerate),
                    text = stringResource(R.string.vpn_regenerate_text),
                    actions = listOf(
                        DialogAction(stringResource(R.string.vpn_regenerate_do)) { confirmRenew = false; actions.regenerate() },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { confirmRenew = false },
                    ),
                    onDismiss = { confirmRenew = false },
                )
            }
        },
    ) {
        val left: @Composable ColumnScope.() -> Unit = {
            StatusCard(ui, actions)
            AccessCard(ui, actions, onRemove = { confirmRemove = true }, onRenew = { confirmRenew = true })
            if (ui.profile != null) WhenGroup(ui, actions)
        }
        val right: @Composable ColumnScope.() -> Unit = {
            if (ui.profile != null) {
                HomeGroup(ui, actions, expanded)
                RouteGroup(ui, actions, expanded)
                DnsGroup(ui, actions, expanded)
                LinkGroup(ui, actions, expanded)
            }
            AndroidGroup(ui, actions, expanded)
        }
        if (currentWidth().twoPanes) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = left)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = right)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                left()
                right()
            }
        }
    }
}

@Composable
private fun whyText(live: VpnLive, hasAccess: Boolean): String = stringResource(
    when {
        // Ohne Zugang sagt die Zeile das, nicht was die Automatik täte.
        !hasAccess -> R.string.vpn_why_no_access
        // Sollte laufen, läuft aber nicht (das Problem steht darunter).
        live.status != TunnelStatus.On && live.why in setOf(Why.Away, Why.AlwaysMode, Why.AlwaysOnAndroid) -> R.string.vpn_why_should
        else -> whyRes(live.why)
    },
)

private fun whyRes(why: Why): Int = when (why) {
        Why.Off -> R.string.vpn_why_off
        Why.NoNetwork -> R.string.vpn_why_no_network
        Why.AtHome -> R.string.vpn_why_home
        Why.Away -> R.string.vpn_why_away
        Why.AlwaysMode -> R.string.vpn_why_always
        Why.Manual -> R.string.vpn_why_manual
        Why.ManualUntilChange -> R.string.vpn_why_manual_until
        Why.NeverMode -> R.string.vpn_why_never
        Why.OtherVpn -> R.string.vpn_why_other_vpn
        Why.AlwaysOnAndroid -> R.string.vpn_why_always_on
}

@Composable
private fun netText(net: NetState): String = when {
    !net.connected -> stringResource(R.string.vpn_net_none)
    net.onWifi && net.ssid != null -> stringResource(R.string.vpn_net_wifi, net.ssid)
    net.onWifi -> stringResource(R.string.vpn_net_wifi_unknown)
    else -> stringResource(R.string.vpn_net_mobile)
}

private fun dateTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

@Composable
private fun StatusCard(ui: VpnUi, actions: VpnActions) {
    val c = Titan.colors
    val live = ui.live
    val on = live.status == TunnelStatus.On
    val stateText = stringResource(
        when (live.status) {
            TunnelStatus.On -> R.string.vpn_state_on
            TunnelStatus.Connecting -> R.string.vpn_state_connecting
            TunnelStatus.Off -> R.string.vpn_state_off
        },
    )
    Card {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            // Zustand als Symbol und in Worten, nicht nur über die Farbe.
            Icon(if (on) TitanIcons.lock else TitanIcons.shield, contentDescription = null, tint = if (on) c.okInk else c.muted, modifier = Modifier.size(34.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stateText, style = TitanType.title, color = if (on) c.okInk else c.text, modifier = Modifier.semantics { heading() })
                Text(whyText(live, ui.profile != null), style = TitanType.body, color = c.text)
                Text(netText(live.net), style = TitanType.rowSub, color = c.muted)
                if (on) {
                    Text(stringResource(R.string.vpn_traffic, Format.bytes(live.rx), Format.bytes(live.tx)), style = TitanType.rowSub, color = c.muted)
                    Text(
                        if (live.handshakeMs > 0) stringResource(R.string.vpn_handshake, dateTime(live.handshakeMs)) else stringResource(R.string.vpn_handshake_never),
                        style = TitanType.rowSub, color = c.muted,
                    )
                    live.sinceMs?.let { Text(stringResource(R.string.vpn_since, dateTime(it)), style = TitanType.rowSub, color = c.muted) }
                }
            }
        }
        if (live.problem != VpnProblem.None && live.problem != VpnProblem.NoAccess) {
            Problem(stringResource(problemText(live.problem)), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
        if (ui.profile != null) {
            if (ui.settings.mode == VpnMode.Never) {
                Note(stringResource(R.string.vpn_never_hint), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
            } else {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    if (on || live.status == TunnelStatus.Connecting) {
                        TitanButton(stringResource(R.string.vpn_disconnect), actions.disconnect, Modifier.fillMaxWidth(), icon = TitanIcons.x, enabled = !ui.busy)
                    } else {
                        TitanButton(stringResource(R.string.vpn_connect), actions.connect, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = TitanIcons.lock, enabled = !ui.busy)
                    }
                }
            }
        }
    }
}

@Composable
private fun reasonText(code: String): String = when (code) {
    VpnReason.OFF -> stringResource(R.string.vpn_reason_off)
    VpnReason.NO_ENDPOINT -> stringResource(R.string.vpn_reason_no_endpoint)
    VpnReason.ADMIN_ONLY -> stringResource(R.string.vpn_reason_admin_only)
    "wireguard_key_used" -> stringResource(R.string.vpn_reason_key_used)
    "wireguard_too_many" -> stringResource(R.string.vpn_reason_too_many)
    "unreachable" -> stringResource(R.string.vpn_reason_unreachable)
    else -> stringResource(R.string.vpn_reason_other, code)
}

@Composable
private fun AccessCard(ui: VpnUi, actions: VpnActions, onRemove: () -> Unit, onRenew: () -> Unit) {
    val c = Titan.colors
    SectionHeader(stringResource(R.string.vpn_access), top = 4.dp)
    val profile = ui.profile
    val reason = ui.answer?.reason?.takeIf { ui.answer.available.not() }
    Card {
        if (profile == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.vpn_setup_title), style = TitanType.rowTitle, color = c.text, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.vpn_setup_text), style = TitanType.body, color = c.muted)
                if (ui.gone) Note(stringResource(R.string.vpn_access_gone), icon = TitanIcons.warn)
                when {
                    ui.loading -> Text(stringResource(R.string.vpn_loading), style = TitanType.rowSub, color = c.muted)
                    ui.error != null -> {
                        Problem(reasonText(ui.error))
                        TitanButton(stringResource(R.string.vpn_retry), actions.retry, Modifier.fillMaxWidth(), icon = TitanIcons.restart)
                    }
                    reason != null -> {
                        Note(reasonText(reason), icon = TitanIcons.warn)
                        TitanButton(stringResource(R.string.vpn_retry), actions.retry, Modifier.fillMaxWidth(), icon = TitanIcons.restart)
                    }
                    else -> TitanButton(stringResource(R.string.vpn_setup_do), actions.create, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = TitanIcons.key, enabled = !ui.busy)
                }
            }
        } else {
            ListRow(stringResource(R.string.vpn_access_name), sub = profile.peerName, icon = { RowIcon(TitanIcons.phone) })
            ListRow(stringResource(R.string.vpn_access_address), sub = profile.address.removeSuffix("/32"), icon = { RowIcon(TitanIcons.nodes) }, divider = true)
            if (profile.nasServer.isNotEmpty()) ListRow(stringResource(R.string.vpn_access_nas), sub = profile.nasServer, icon = { RowIcon(TitanIcons.hdd) }, divider = true)
            Format.instant(profile.created)?.let { at ->
                ListRow(stringResource(R.string.vpn_access_created), sub = dateTime(at.toEpochMilli()), icon = { RowIcon(TitanIcons.cal) }, divider = true)
            }
            if (ui.answer?.peer?.active == false) Note(stringResource(R.string.vpn_access_inactive), Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp), icon = TitanIcons.warn)
            if (ui.error != null) Problem(reasonText(ui.error), Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
            Divider()
            // Untereinander statt nebeneinander: die Beschriftungen bleiben ganze Wörter, auch bei 200 % Schrift.
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TitanButton(stringResource(R.string.vpn_regenerate), onRenew, Modifier.fillMaxWidth(), icon = TitanIcons.restart, enabled = !ui.busy && ui.answer?.mayCreate != false)
                TitanButton(stringResource(R.string.vpn_remove), onRemove, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = TitanIcons.trash, enabled = !ui.busy)
            }
        }
    }
}

/** Aufklappbare Gruppe (wie die Systemsteuerung): Kopf mit Zustand in Worten für TalkBack. */
@Composable
private fun Group(title: String, summary: String?, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Titan.colors
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val said = stringResource(if (open) R.string.vpn_open else R.string.vpn_closed)
    Card {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.semantics { stateDescription = said }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = TitanType.rowTitle, color = c.text, modifier = Modifier.semantics { heading() })
                if (summary != null) Text(summary, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
        }
        if (open) {
            Divider()
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), content = content)
        }
    }
}

/** Auswahlzeile mit Kreis; ganzer Bereich tippbar, Zustand für TalkBack über RadioMark. */
@Composable
private fun Choice(title: String, sub: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Titan.colors
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioMark(selected, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = TitanType.rowTitle, color = if (enabled) c.text else c.muted)
            if (sub != null) Text(sub, style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = TitanType.section, color = Titan.colors.muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(
        title,
        sub = sub,
        right = { Toggle(checked, onChange, stringResource(UiR.string.ui_on), stringResource(UiR.string.ui_off), label = title) },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun WhenGroup(ui: VpnUi, actions: VpnActions) {
    val s = ui.settings
    val modes = listOf(
        Triple(VpnMode.Auto, R.string.vpn_mode_auto, R.string.vpn_mode_auto_text),
        Triple(VpnMode.Always, R.string.vpn_mode_always, R.string.vpn_mode_always_text),
        Triple(VpnMode.Manual, R.string.vpn_mode_manual, R.string.vpn_mode_manual_text),
        Triple(VpnMode.Never, R.string.vpn_mode_never, R.string.vpn_mode_never_text),
    )
    val current = modes.first { it.first == s.mode }
    Group(stringResource(R.string.vpn_group_when), stringResource(current.second), initiallyOpen = true) {
        for ((mode, title, sub) in modes) {
            Choice(stringResource(title), stringResource(sub), s.mode == mode) { actions.update(s.copy(mode = mode)) }
        }
    }
}

@Composable
private fun PermRow(title: String, text: String, ok: Boolean, okText: String = stringResource(R.string.vpn_granted), button: String?, onClick: () -> Unit) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = TitanType.rowTitle, color = c.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Zustand mit Symbol und Wort.
            Icon(if (ok) TitanIcons.check else TitanIcons.warn, null, tint = if (ok) c.okInk else c.warnInk, modifier = Modifier.size(18.dp))
            Text(if (ok) okText else stringResource(R.string.vpn_not_granted), style = TitanType.rowSub, color = if (ok) c.okInk else c.warnInk)
        }
        Text(text, style = TitanType.rowSub, color = c.muted)
        if (!ok && button != null) TitanButton(button, onClick, Modifier.fillMaxWidth())
    }
}

@Composable
private fun HomeGroup(ui: VpnUi, actions: VpnActions, expanded: Boolean) {
    val s = ui.settings
    val c = Titan.colors
    val summary = if (s.homeWifis.isEmpty()) stringResource(R.string.vpn_home_none) else s.homeWifis.joinToString(", ")
    Group(stringResource(R.string.vpn_group_home), summary, expanded) {
        Text(stringResource(R.string.vpn_home_intro), style = TitanType.body, color = c.muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp))
        PermRow(stringResource(R.string.vpn_location), stringResource(R.string.vpn_location_text), ui.perms.location, button = stringResource(R.string.vpn_location_ask), onClick = actions.askLocation)
        if (ui.perms.location) {
            PermRow(stringResource(R.string.vpn_location_background), stringResource(R.string.vpn_location_background_text), ui.perms.backgroundLocation, button = stringResource(R.string.vpn_location_background_ask), onClick = actions.askBackgroundLocation)
        }
        if (!ui.perms.locationOn) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Note(stringResource(R.string.vpn_location_off), icon = TitanIcons.warn)
                TitanButton(stringResource(R.string.vpn_location_off_ask), actions.openLocationSettings, Modifier.fillMaxWidth())
            }
        }
        Divider()
        if (s.homeWifis.isEmpty()) {
            Text(stringResource(R.string.vpn_home_none), style = TitanType.body, color = c.muted, modifier = Modifier.padding(14.dp))
        }
        s.homeWifis.forEachIndexed { i, ssid ->
            val label = stringResource(R.string.vpn_home_remove, ssid)
            ListRow(
                ssid,
                icon = { RowIcon(TitanIcons.wifi) },
                right = {
                    IconButton(TitanIcons.x, label, {
                        actions.update(s.copy(homeWifis = s.homeWifis - ssid, forgottenWifis = (s.forgottenWifis + ssid).distinct()))
                    }, tint = c.muted)
                },
                divider = i > 0,
            )
        }
        val current = ui.currentSsid
        if (current != null && current !in s.homeWifis) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                TitanButton(stringResource(R.string.vpn_home_add_current, current), {
                    actions.update(s.copy(homeWifis = s.homeWifis + current, forgottenWifis = s.forgottenWifis - current))
                }, Modifier.fillMaxWidth(), icon = TitanIcons.plus)
            }
        }
        var name by rememberSaveable { mutableStateOf("") }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(name, { name = it }, stringResource(R.string.vpn_home_name), placeholder = stringResource(R.string.vpn_home_name), leading = TitanIcons.wifi)
            TitanButton(stringResource(R.string.vpn_home_add), {
                val n = name.trim()
                if (n.isNotEmpty() && n !in s.homeWifis) actions.update(s.copy(homeWifis = s.homeWifis + n, forgottenWifis = s.forgottenWifis - n))
                name = ""
            }, Modifier.fillMaxWidth(), icon = TitanIcons.plus, enabled = name.isNotBlank())
        }
        Divider()
        SwitchRow(stringResource(R.string.vpn_home_learn), stringResource(R.string.vpn_home_learn_text), s.learnWifis) { actions.update(s.copy(learnWifis = it)) }
        Label(stringResource(R.string.vpn_unknown_wifi))
        Choice(stringResource(R.string.vpn_unknown_away), null, s.unknownWifi == UnknownWifi.Away) { actions.update(s.copy(unknownWifi = UnknownWifi.Away)) }
        Choice(stringResource(R.string.vpn_unknown_home), null, s.unknownWifi == UnknownWifi.Home) { actions.update(s.copy(unknownWifi = UnknownWifi.Home)) }
    }
}

@Composable
private fun RouteGroup(ui: VpnUi, actions: VpnActions, expanded: Boolean) {
    val s = ui.settings
    Group(stringResource(R.string.vpn_group_route), stringResource(routingText(s.routing)), expanded) {
        Choice(stringResource(R.string.vpn_route_nas), stringResource(R.string.vpn_route_nas_text), s.routing == VpnRouting.Nas) { actions.update(s.copy(routing = VpnRouting.Nas)) }
        Choice(stringResource(R.string.vpn_route_home), stringResource(R.string.vpn_route_home_text), s.routing == VpnRouting.Home) { actions.update(s.copy(routing = VpnRouting.Home)) }
        Choice(stringResource(R.string.vpn_route_all), stringResource(R.string.vpn_route_all_text), s.routing == VpnRouting.All) { actions.update(s.copy(routing = VpnRouting.All)) }
        Label(stringResource(R.string.vpn_apps))
        Choice(stringResource(R.string.vpn_apps_all), stringResource(R.string.vpn_apps_all_text), s.apps == VpnApps.All) { actions.update(s.copy(apps = VpnApps.All)) }
        Choice(stringResource(R.string.vpn_apps_titan), stringResource(R.string.vpn_apps_titan_text), s.apps == VpnApps.TitanOnly) { actions.update(s.copy(apps = VpnApps.TitanOnly)) }
    }
}

@Composable
private fun DnsGroup(ui: VpnUi, actions: VpnActions, expanded: Boolean) {
    val s = ui.settings
    val nasDns = ui.profile?.dns.orEmpty()
    val summary = stringResource(
        when (s.dns) {
            VpnDns.Phone -> R.string.vpn_dns_phone
            VpnDns.Nas -> R.string.vpn_dns_nas
            VpnDns.Custom -> R.string.vpn_dns_custom
        },
    )
    Group(stringResource(R.string.vpn_group_dns), summary, expanded) {
        Choice(stringResource(R.string.vpn_dns_phone), stringResource(R.string.vpn_dns_phone_text), s.dns == VpnDns.Phone) { actions.update(s.copy(dns = VpnDns.Phone)) }
        Choice(
            stringResource(R.string.vpn_dns_nas),
            if (nasDns.isEmpty()) stringResource(R.string.vpn_dns_nas_none) else stringResource(R.string.vpn_dns_nas_text, nasDns.joinToString(", ")),
            s.dns == VpnDns.Nas,
            enabled = nasDns.isNotEmpty() || s.dns == VpnDns.Nas,
        ) { actions.update(s.copy(dns = VpnDns.Nas)) }
        Choice(stringResource(R.string.vpn_dns_custom), null, s.dns == VpnDns.Custom) { actions.update(s.copy(dns = VpnDns.Custom)) }
        if (s.dns == VpnDns.Custom) {
            var text by rememberSaveable { mutableStateOf(s.customDns.joinToString(", ")) }
            val parts = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            val bad = parts.firstOrNull { !Plans.isIp(it) }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextInput(text, {
                    text = it
                    val next = it.split(',', ' ').map { p -> p.trim() }.filter { p -> p.isNotEmpty() }
                    if (next.all { p -> Plans.isIp(p) }) actions.update(s.copy(customDns = next))
                }, stringResource(R.string.vpn_dns_custom_field), placeholder = stringResource(R.string.vpn_dns_custom_field), keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
                if (bad != null) Problem(stringResource(R.string.vpn_dns_custom_bad, bad))
            }
        }
    }
}

@Composable
private fun LinkGroup(ui: VpnUi, actions: VpnActions, expanded: Boolean) {
    val s = ui.settings
    val profile = ui.profile ?: return
    val auto = profile.endpoints.firstOrNull().orEmpty()
    val chosen = Plans.endpointHost(profile, s).orEmpty()
    Group(stringResource(R.string.vpn_group_link), chosen, expanded) {
        Label(stringResource(R.string.vpn_endpoint))
        Choice(stringResource(R.string.vpn_endpoint_auto, auto), stringResource(R.string.vpn_endpoint_port, profile.port), s.endpoint.isEmpty() || s.endpoint !in profile.endpoints) {
            actions.update(s.copy(endpoint = ""))
        }
        if (profile.endpoints.size > 1) {
            for (e in profile.endpoints) {
                Choice(e, null, s.endpoint == e) { actions.update(s.copy(endpoint = e)) }
            }
        }
        Label(stringResource(R.string.vpn_mtu))
        val presets = listOf(1420, 1380, 1340, 1280)
        Choice(stringResource(R.string.vpn_mtu_auto), null, s.mtu == 0) { actions.update(s.copy(mtu = 0)) }
        for (m in presets) Choice(m.toString(), null, s.mtu == m) { actions.update(s.copy(mtu = m)) }
        var own by rememberSaveable { mutableStateOf(s.mtu.takeIf { it != 0 && it !in presets }?.toString().orEmpty()) }
        Choice(stringResource(R.string.vpn_mtu_own), null, s.mtu != 0 && s.mtu !in presets) {
            own.toIntOrNull()?.takeIf { it in 576..9000 }?.let { actions.update(s.copy(mtu = it)) }
        }
        Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            TextInput(own, {
                own = it.filter { ch -> ch.isDigit() }.take(4)
                own.toIntOrNull()?.takeIf { v -> v in 576..9000 }?.let { v -> actions.update(s.copy(mtu = v)) }
            }, stringResource(R.string.vpn_mtu_field), placeholder = stringResource(R.string.vpn_mtu_field), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Label(stringResource(R.string.vpn_keepalive))
        Text(stringResource(R.string.vpn_keepalive_text), style = TitanType.rowSub, color = Titan.colors.muted, modifier = Modifier.padding(horizontal = 14.dp))
        Choice(stringResource(R.string.vpn_keepalive_off), null, s.keepalive == 0) { actions.update(s.copy(keepalive = 0)) }
        for (k in listOf(15, 25, 60)) Choice(stringResource(R.string.vpn_keepalive_s, k), null, s.keepalive == k) { actions.update(s.copy(keepalive = k)) }
        Spacer(Modifier.height(6.dp))
        Divider()
        SwitchRow(stringResource(R.string.vpn_replace_other), stringResource(R.string.vpn_replace_other_text), s.replaceOtherVpn) { actions.update(s.copy(replaceOtherVpn = it)) }
        SwitchRow(stringResource(R.string.vpn_traffic_note), null, s.trafficInNotification) { actions.update(s.copy(trafficInNotification = it)) }
    }
}

@Composable
private fun AndroidGroup(ui: VpnUi, actions: VpnActions, expanded: Boolean) {
    val c = Titan.colors
    Group(stringResource(R.string.vpn_group_android), null, expanded) {
        val always = stringResource(
            when {
                ui.live.lockdown -> R.string.vpn_always_on_state_lockdown
                ui.live.alwaysOn -> R.string.vpn_always_on_state_on
                else -> R.string.vpn_always_on_state_unknown
            },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.vpn_always_on), style = TitanType.rowTitle, color = c.text)
            Text(always, style = TitanType.rowSub, color = c.text)
            Text(stringResource(R.string.vpn_always_on_text), style = TitanType.rowSub, color = c.muted)
            TitanButton(stringResource(R.string.vpn_always_on_open), actions.openVpnSettings, Modifier.fillMaxWidth(), icon = TitanIcons.gear)
        }
        Divider()
        PermRow(stringResource(R.string.vpn_permission), stringResource(if (ui.perms.vpnAllowed) R.string.vpn_permission_ok else R.string.vpn_permission_missing), ui.perms.vpnAllowed, okText = stringResource(R.string.vpn_permission_ok), button = stringResource(R.string.vpn_permission_ask), onClick = actions.askVpn)
        Divider()
        PermRow(stringResource(R.string.vpn_battery), stringResource(if (ui.perms.batteryExempt) R.string.vpn_battery_ok else R.string.vpn_battery_missing), ui.perms.batteryExempt, button = stringResource(R.string.vpn_battery_ask), onClick = actions.askBattery)
        Divider()
        PermRow(stringResource(R.string.vpn_notifications), stringResource(R.string.vpn_notifications_text), ui.perms.notifications, button = stringResource(R.string.vpn_notifications_ask), onClick = actions.askNotifications)
        Divider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.vpn_tile), style = TitanType.rowTitle, color = c.text)
            Text(stringResource(R.string.vpn_tile_text), style = TitanType.rowSub, color = c.muted)
            TitanButton(stringResource(R.string.vpn_tile_add), actions.addTile, Modifier.fillMaxWidth(), icon = TitanIcons.plus)
        }
        Divider()
        Text(stringResource(R.string.vpn_tailscale), style = TitanType.rowSub, color = c.muted, modifier = Modifier.padding(14.dp))
    }
}
