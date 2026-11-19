package io.github.ra5on.titanos.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.BigTitle
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType

/** Stand einer Laufzeit-Berechtigung. */
enum class Grant { Granted, NotAsked, Denied }

data class PermissionsState(
    val localNetwork: Grant = Grant.NotAsked,
    val notifications: Grant = Grant.NotAsked,
)

/**
 * Was soll die App tun? (Entwurf 04). In dieser Testfassung fragt die App nur
 * Heimnetz und Mitteilungen wirklich ab; Fotos, Kontakte und Kalender sagen,
 * dass sie später kommen.
 */
@Composable
fun PermissionsScreen(
    state: PermissionsState,
    onLocalNetwork: () -> Unit,
    onNotifications: () -> Unit,
    onNext: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.perm_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                TitanButton(stringResource(R.string.perm_next), onNext, Modifier.fillMaxWidth(), ButtonKind.Primary)
            }
        },
    ) {
        BigTitle(stringResource(R.string.perm_heading))
        Text(stringResource(R.string.perm_intro), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 14.dp))
        Card {
            val later = @Composable { title: Int, text: Int, glyph: String, gradient: String, divider: Boolean ->
                ListRow(
                    title = stringResource(title),
                    sub = stringResource(R.string.perm_later_suffix, stringResource(text)),
                    icon = { AppIcon(glyph, gradient, 40.dp) },
                    right = { Toggle(false, null, on, off, enabled = false) },
                    divider = divider,
                )
            }
            later(R.string.perm_photos, R.string.perm_photos_text, "photos", "pink", false)
            later(R.string.perm_contacts, R.string.perm_contacts_text, "contacts", "green", true)
            later(R.string.perm_calendar, R.string.perm_calendar_text, "calendar", "violet", true)
            ListRow(
                title = stringResource(R.string.perm_local),
                sub = subFor(state.localNetwork, R.string.perm_local_text),
                icon = { AppIcon("shares", "yellow", 40.dp) },
                right = { Toggle(state.localNetwork == Grant.Granted, { onLocalNetwork() }, on, off) },
                divider = true,
                onClick = onLocalNetwork,
            )
            ListRow(
                title = stringResource(R.string.perm_notify),
                sub = subFor(state.notifications, R.string.perm_notify_text),
                icon = { AppIcon("bell", "red", 40.dp) },
                right = { Toggle(state.notifications == Grant.Granted, { onNotifications() }, on, off) },
                divider = true,
                onClick = onNotifications,
            )
        }
        Note(stringResource(R.string.perm_note), Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun subFor(g: Grant, text: Int): String =
    if (g == Grant.Denied) stringResource(R.string.perm_denied_suffix, stringResource(text)) else stringResource(text)

/** Fragt Heimnetz (ab Android 17 Pflicht) und Mitteilungen wirklich ab. */
@Composable
fun PermissionsRoute(onNext: () -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    fun grant(p: String, asked: Boolean): Grant = when {
        p == LOCAL_NETWORK && localNetworkGranted(context) -> Grant.Granted
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED -> Grant.Granted
        asked -> Grant.Denied
        else -> Grant.NotAsked
    }
    var state by remember {
        mutableStateOf(PermissionsState(grant(LOCAL_NETWORK, false), grant(Manifest.permission.POST_NOTIFICATIONS, false)))
    }
    val local = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state = state.copy(localNetwork = grant(LOCAL_NETWORK, true))
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state = state.copy(notifications = grant(Manifest.permission.POST_NOTIFICATIONS, true))
    }
    PermissionsScreen(
        state,
        onLocalNetwork = { if (state.localNetwork != Grant.Granted) local.launch(LOCAL_NETWORK) },
        onNotifications = { if (state.notifications != Grant.Granted) notify.launch(Manifest.permission.POST_NOTIFICATIONS) },
        onNext = onNext,
        onBack = onBack,
    )
}

/** android.permission.ACCESS_LOCAL_NETWORK, eine Laufzeit-Berechtigung erst ab Android 17 (API 37). */
const val LOCAL_NETWORK: String = "android.permission.ACCESS_LOCAL_NETWORK"

/** Vor Android 17 gibt es die Berechtigung nicht; dann gilt das Heimnetz als erlaubt. */
fun localNetworkGranted(context: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
