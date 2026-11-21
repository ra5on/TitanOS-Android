package io.github.ra5on.titanos.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.net.Spki
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.components.wallpaper
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.warnInk
import io.github.ra5on.titanos.ui.theme.okInk

/**
 * Erster Start (Entwurf 01). Die Agent-App zeigt hier ihren eigenen Namen,
 * Text und ihr Symbol ([title], [text], [glyph], [gradient]).
 */
@Composable
fun WelcomeScreen(
    onScan: () -> Unit,
    onManual: () -> Unit,
    problem: String? = null,
    title: String? = null,
    text: String? = null,
    glyph: String = "storage",
    gradient: String = "dark",
) {
    val c = Titan.colors
    BoxWithConstraints(Modifier.fillMaxSize().wallpaper(c.dark).statusBarsPadding().navigationBarsPadding()) {
        val minHeight = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minHeight)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 32.dp).heightIn(min = minHeight * 0.62f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            ) {
                AppIcon(glyph, gradient, 96.dp)
                Text(title ?: stringResource(R.string.pair_app_name), style = TitanType.big.copy(fontSize = TitanType.big.fontSize * (30f / 28f)), color = c.text)
                Text(
                    text ?: stringResource(R.string.pair_welcome_text),
                    style = TitanType.body.copy(fontSize = TitanType.rowTitle.fontSize, lineHeight = TitanType.rowTitle.lineHeight),
                    color = c.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (problem != null) Problem(problem)
                TitanButton(stringResource(R.string.pair_scan), onScan, Modifier.fillMaxWidth(), ButtonKind.Primary, icon = TitanIcons.qr)
                TitanButton(stringResource(R.string.pair_manual), onManual, Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.pair_where_qr),
                    style = TitanType.rowSub,
                    color = c.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
fun errorText(e: PairError): String = when (e) {
    PairError.Code -> stringResource(R.string.pair_err_code)
    PairError.Name -> stringResource(R.string.pair_err_name)
    is PairError.Rate -> e.seconds?.let { stringResource(R.string.pair_err_rate, it) } ?: stringResource(R.string.pair_err_rate_plain)
    PairError.Rejected -> stringResource(R.string.pair_err_rejected)
    PairError.Expired -> stringResource(R.string.pair_err_expired)
    // Android 17: ohne „Geräte im lokalen Netzwerk“ ist jede Heimnetz-Adresse gesperrt – das sagen statt „nicht erreichbar“.
    PairError.Unreachable -> if (localNetworkGranted(androidx.compose.ui.platform.LocalContext.current)) {
        stringResource(R.string.pair_err_unreachable)
    } else {
        stringResource(R.string.pair_err_local_network)
    }
    PairError.Certificate -> stringResource(R.string.pair_err_cert)
    PairError.Busy -> stringResource(R.string.pair_err_busy)
    PairError.BadAddress -> stringResource(R.string.pair_manual_bad_address)
    PairError.BadCode -> stringResource(R.string.pair_manual_bad_code)
    is PairError.Other -> stringResource(R.string.pair_err_other, e.detail)
}

/** Ohne QR-Code (Entwurf 05): Adresse, Fingerabdruck in Vierergruppen, Kopplungscode. */
@Composable
fun ManualScreen(
    state: ManualState,
    onAddress: (String) -> Unit,
    onProbe: () -> Unit,
    onCode: (String) -> Unit,
    onMismatch: () -> Unit,
    onAccept: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Titan.colors
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.pair_manual_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } },
        bottomBar = {
            Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.probed == null) {
                    TitanButton(
                        stringResource(if (state.probing) R.string.pair_manual_probing else R.string.pair_manual_connect),
                        onProbe,
                        Modifier.weight(1f),
                        ButtonKind.Primary,
                        enabled = !state.probing && state.address.isNotBlank(),
                    )
                } else {
                    TitanButton(stringResource(R.string.pair_manual_wrong), onMismatch, Modifier.weight(1f))
                    TitanButton(stringResource(R.string.pair_manual_ok), onAccept, Modifier.weight(1.4f), ButtonKind.Primary)
                }
            }
        },
    ) {
        SectionHeader(stringResource(R.string.pair_manual_address), top = 14.dp)
        TextInput(
            state.address,
            onAddress,
            stringResource(R.string.pair_manual_address),
            placeholder = stringResource(R.string.pair_manual_address_hint),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
        val seen = state.probed
        if (seen != null) {
            SectionHeader(stringResource(R.string.pair_manual_compare))
            Card {
                Column(Modifier.padding(14.dp)) {
                    if (seen.publiclyValid) {
                        Text(stringResource(R.string.pair_manual_public), style = TitanType.body.copy(fontSize = TitanType.small.fontSize * (14f / 13f)), color = c.muted)
                    } else {
                        Text(stringResource(R.string.pair_manual_shown), style = TitanType.body.copy(fontSize = TitanType.small.fontSize * (14f / 13f)), color = c.muted)
                        Column(Modifier.padding(vertical = 10.dp)) {
                            for (line in Spki.displayLines(seen.hash)) Text(line, style = TitanType.mono, color = c.text)
                        }
                        Text(stringResource(R.string.pair_manual_where), style = TitanType.body.copy(fontSize = TitanType.small.fontSize * (14f / 13f)), color = c.muted)
                    }
                }
            }
            SectionHeader(stringResource(R.string.pair_manual_code))
            TextInput(
                state.code,
                onCode,
                stringResource(R.string.pair_manual_code),
                style = TitanType.code,
                placeholder = "XXXX-XXXX",
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            )
        }
        Note(stringResource(R.string.pair_manual_ts_note), Modifier.padding(top = 16.dp))
        state.error?.let {
            Problem(if (it == PairError.Certificate) stringResource(R.string.pair_manual_mismatch) else errorText(it), Modifier.padding(top = 12.dp))
            LocalNetworkFix(it)
        }
    }
}

/** NAS koppeln (Entwurf 03): Adressen, festgehaltenes Zertifikat, Name, dann Warten auf den Browser. */
@Composable
fun ConfirmScreen(
    state: ConfirmState,
    onName: (String) -> Unit,
    onPair: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Titan.colors
    val waiting = state.phase != PairPhase.Ready
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.pair_confirm_title), onBack = onCancel, backLabel = stringResource(UiR.string.ui_back)) } },
        bottomBar = {
            Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TitanButton(stringResource(UiR.string.ui_cancel), onCancel, Modifier.weight(1f))
                TitanButton(
                    stringResource(if (waiting) R.string.pair_waiting else R.string.pair_do),
                    onPair,
                    Modifier.weight(1.4f),
                    ButtonKind.Primary,
                    enabled = !waiting,
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon("storage", "dark", 72.dp)
            Text(state.hostname ?: "…", style = TitanType.title, color = c.text, textAlign = TextAlign.Center)
            Text(
                state.user?.let { stringResource(R.string.pair_confirm_product_user, it) } ?: stringResource(R.string.pair_confirm_product),
                style = TitanType.body,
                color = c.muted,
                textAlign = TextAlign.Center,
            )
        }
        SectionHeader(stringResource(R.string.pair_connection))
        Card {
            state.addresses.forEachIndexed { i, a ->
                ListRow(
                    title = a.server,
                    sub = stringResource(
                        when (a.kind) {
                            AddressKind.TAILSCALE -> R.string.pair_kind_tailscale
                            AddressKind.LAN -> R.string.pair_kind_lan
                            AddressKind.OTHER -> R.string.pair_kind_other
                        },
                    ),
                    icon = { RowIcon(if (a.kind == AddressKind.LAN) TitanIcons.wifi else TitanIcons.globe) },
                    right = { ReachMark(a.status) },
                    divider = i > 0,
                )
            }
            ListRow(
                title = stringResource(if (state.fingerprint != null) R.string.pair_cert_title else R.string.pair_cert_public_title),
                sub = state.fingerprint?.let { stringResource(R.string.pair_cert_text, it) } ?: stringResource(R.string.pair_cert_public_text),
                icon = { RowIcon(TitanIcons.shield) },
                divider = state.addresses.isNotEmpty(),
            )
        }
        SectionHeader(stringResource(R.string.pair_device_name))
        TextInput(state.deviceName, onName, stringResource(R.string.pair_device_name), keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Note(
            stringResource(if (waiting) R.string.pair_note_waiting else R.string.pair_note_before, state.deviceName),
            Modifier.padding(top = 16.dp),
        )
        state.error?.let {
            Problem(errorText(it), Modifier.padding(top = 12.dp))
            LocalNetworkFix(it)
        }
    }
}

/** Erreichbarkeit mit Symbol und Wort (nie nur Farbe). */
@Composable
private fun ReachMark(s: CheckStatus) {
    val c = Titan.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (s) {
            CheckStatus.Reachable -> {
                val label = stringResource(R.string.pair_reachable)
                Icon(TitanIcons.check, label, tint = c.okInk, modifier = Modifier.size(18.dp))
            }
            CheckStatus.Unreachable -> {
                Icon(TitanIcons.x, null, tint = c.warnInk, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.pair_unreachable), style = TitanType.small, color = c.muted)
            }
            CheckStatus.Checking -> Text(stringResource(R.string.pair_checking), style = TitanType.small, color = c.muted)
        }
    }
}

/**
 * Fehlt „Geräte im lokalen Netzwerk“ (Android 17), ein Knopf, der Android
 * erneut fragt; hat Android die Frage schon endgültig abgelehnt, öffnet er die
 * Einstellungen der App.
 */
@Composable
private fun LocalNetworkFix(error: PairError) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (error != PairError.Unreachable || localNetworkGranted(context)) return
    val activity = context as? android.app.Activity
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted && activity != null && !activity.shouldShowRequestPermissionRationale(LOCAL_NETWORK)) {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null)),
            )
        }
    }
    io.github.ra5on.titanos.ui.components.TitanButton(
        stringResource(R.string.pair_local_network_allow),
        { ask.launch(LOCAL_NETWORK) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        io.github.ra5on.titanos.ui.components.ButtonKind.Primary,
    )
}
