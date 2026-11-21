package io.github.ra5on.titanos.notify

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons

/** Mitteilungen eingeschaltet und von Android erlaubt (Schalter beim Einrichten, Entwurf 04). */
fun notificationsOn(context: Context): Boolean = NotifySettingsStore(context).load().enabled && Poster.permitted(context)

/**
 * Schalter „Mitteilungen“ beim Einrichten: an schaltet Abruf und
 * Dauerverbindung ein (die Berechtigung fragt der Bildschirm selbst),
 * aus schaltet beides ab; die Liste füllt sich weiter alle 15 Minuten.
 */
fun setNotificationsOn(context: Context, on: Boolean) {
    val store = NotifySettingsStore(context)
    store.save(store.load().copy(enabled = on))
    NotifyScheduler.arm(context)
}

/** Reiter „Mitteilungen“: Liste (Entwurf 70) und Einstellungen (Entwurf 72). */
@Composable
fun NotifyTab(nav: @Composable () -> Unit) {
    val context = LocalContext.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var menu by remember { mutableStateOf(false) }
    val changes by NotifyRuntime.changes.collectAsStateWithLifecycle()
    val offline by NotifyRuntime.offline.collectAsStateWithLifecycle()
    val live by NotifyRuntime.live.collectAsStateWithLifecycle()
    // Neu lesen, wenn die App wieder sichtbar wird (Akku-Ausnahme, Berechtigung in den Android-Einstellungen).
    var resumed by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { NotifyRuntime.fetch(context) }

    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        var settings by remember { mutableStateOf(NotifySettingsStore(context).load()) }
        var asked by rememberSaveable { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            asked = true
            resumed++
            // Erlaubt: jetzt auch die Dauerverbindung, wenn sie an ist.
            NotifyScheduler.arm(context)
        }
        val permitted = remember(resumed) { Poster.permitted(context) }
        val exempt = remember(resumed) { NotifyScheduler.batteryExempt(context) }
        NotifySettingsScreen(
            settings,
            ConnectionView(live, exempt, if (permitted) true else if (asked) false else null),
            onChange = { s ->
                settings = s
                NotifySettingsStore(context).save(s)
                NotifyScheduler.arm(context)
            },
            onBattery = { NotifyScheduler.askBatteryExemption(context) },
            onPermission = {
                if (asked) {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onBack = { settingsOpen = false },
        )
        return
    }

    val state = remember(changes) { NotifyRuntime.inbox(context).snapshot() }
    // Was man in der Liste gesehen hat, gilt als gelesen; die Leiste wird leer.
    LaunchedEffect(changes) {
        if (Inbox.unread(state) > 0) {
            Poster.cancelAll(context)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val inbox = NotifyRuntime.inbox(context)
            inbox.update { s -> Inbox.markRead(s, s.items.map { it.id }) to Unit }
        }
    }
    Box {
        NotificationsScreen(
            NotesView(state.items, state.read, offline),
            filter,
            onFilter = { filter = it },
            onNote = { n ->
                NotifyRuntime.inbox(context).update { Inbox.markRead(it, listOf(n.id)) to Unit }
                NotifyRuntime.changed()
                Poster.cancel(context, n.notificationTag)
                if (n.app == "agent" || n.kind == Category.Agent) {
                    Poster.openIntent(context, n)?.let { runCatching { it.send() } }
                } else {
                    NotifyRuntime.requestOpen(n.link)
                }
            },
            onMenu = { menu = true },
            bottomBar = nav,
        ) {
            if (menu) {
                Sheet(onDismiss = { menu = false }) {
                    Card(background = Titan.colors.bg) {
                        ListRow(
                            stringResource(R.string.notify_menu_settings),
                            icon = { RowIcon(TitanIcons.gear) },
                            onClick = { menu = false; settingsOpen = true },
                        )
                        ListRow(
                            stringResource(R.string.notify_menu_read_all),
                            icon = { RowIcon(TitanIcons.check) },
                            divider = true,
                            onClick = {
                                menu = false
                                NotifyRuntime.inbox(context).update { s -> Inbox.markRead(s, s.items.map { it.id }) to Unit }
                                NotifyRuntime.changed()
                                Poster.cancelAll(context)
                            },
                        )
                    }
                }
            }
        }
    }
}
