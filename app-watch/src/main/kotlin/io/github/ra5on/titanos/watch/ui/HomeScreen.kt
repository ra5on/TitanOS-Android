package io.github.ra5on.titanos.watch.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.watch.pair.LOCAL_NETWORK
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.data.WatchStatus
import io.github.ra5on.titanos.watch.graph
import io.github.ra5on.titanos.watch.tile.StatusTileService
import kotlinx.coroutines.delay
import java.io.IOException

/** Die Startseite: Zustand und was die Uhr kann. */
@Composable
fun HomeScreen(status: WatchStatus?, approvals: Int, problem: Int?, onOpen: (String) -> Unit) {
    Page {
        item { Header(status?.hostname?.takeIf { it.isNotBlank() } ?: stringResource(R.string.w_app_name)) }
        item { StatusCard(status, problem, onClick = { onOpen(Routes.NOTIFICATIONS) }) }
        item { RowButton(stringResource(R.string.w_agent), onClick = { onOpen(Routes.AGENT) }, icon = TitanIcons.mic, filled = true) }
        if (approvals > 0) {
            item {
                RowButton(
                    stringResource(R.string.w_approvals),
                    sub = pluralStringResource(R.plurals.w_approvals_waiting, approvals, approvals),
                    onClick = { onOpen(Routes.APPROVALS) },
                    icon = TitanIcons.shield,
                )
            }
        }
        item { RowButton(stringResource(R.string.w_switch), onClick = { onOpen(Routes.SWITCH) }, icon = TitanIcons.bolt) }
        item { RowButton(stringResource(R.string.w_cameras), onClick = { onOpen(Routes.CAMERAS) }, icon = TitanIcons.video) }
        item { RowButton(stringResource(R.string.w_messages), onClick = { onOpen(Routes.NOTIFICATIONS) }, icon = TitanIcons.bell) }
        item { RowButton(stringResource(R.string.w_settings), onClick = { onOpen(Routes.SETTINGS) }, icon = TitanIcons.gear) }
    }
}

@Composable
fun StatusCard(s: WatchStatus?, problem: Int?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        when {
            s == null && problem == null -> Busy(stringResource(R.string.w_loading))
            s == null -> Problem(stringResource(problem!!))
            else -> {
                StateLine(s.ok, stringResource(statusWord(s)))
                for (p in s.problems) {
                    WText(stringResource(R.string.w_volume_line, p.name, stringResource(volumeStateText(p.state))), color = WatchColors.warn, modifier = Modifier.padding(horizontal = 8.dp))
                }
                s.usedPercent?.let {
                    WText(stringResource(R.string.w_storage_line, it, Format.bytes(s.freeBytes)), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                if (s.newMessages > 0) {
                    WText(pluralStringResource(R.plurals.w_new_messages, s.newMessages, s.newMessages), modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (problem != null) {
                    WText(stringResource(R.string.w_stale, time(s.at)), color = WatchColors.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

/** Startseite mit Abfrage nur, solange sie sichtbar ist (jede Minute). */
@Composable
fun HomeRoute(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val g = context.graph
    var status by remember { mutableStateOf(g.status.cached()) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var approvals by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Einmal nach der Kopplung fragen, wie das Handy beim Einrichten: Heimnetz-Adressen
    // (Android 17, „lokales Netzwerk“) und Mitteilungen (Hinweis auf ein Update).
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g.settings.askedPermissions = true }
    LaunchedEffect(Unit) {
        if (g.settings.askedPermissions) return@LaunchedEffect
        val lan = g.pairing?.addresses?.any { it.address?.kind == AddressKind.LAN } == true
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 37 && lan) add(LOCAL_NETWORK)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) g.settings.askedPermissions = true else ask.launch(wanted.toTypedArray())
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val api = g.api() ?: break
                try {
                    status = g.status.refresh(api)
                    problem = null
                    StatusTileService.requestUpdate(context)
                } catch (e: IOException) {
                    problem = R.string.w_err_reach
                }
                approvals = runCatching { api.chats().count { it.state == "waiting" } }.getOrDefault(0)
                delay(60_000)
            }
        }
    }
    HomeScreen(status, approvals, problem, onOpen)
}
