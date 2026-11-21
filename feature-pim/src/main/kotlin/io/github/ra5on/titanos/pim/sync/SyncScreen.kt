package io.github.ra5on.titanos.pim.sync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.core.content.ContextCompat
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.pim.ChoiceRows
import io.github.ra5on.titanos.pim.ColorDot
import io.github.ra5on.titanos.pim.R
import io.github.ra5on.titanos.pim.SettingsGroup
import io.github.ra5on.titanos.pim.ToggleRow
import io.github.ra5on.titanos.pim.hexColor
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.TitanIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import io.github.ra5on.titanos.ui.R as UiR

val PIM_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

fun pimPermissionsGranted(context: Context): Boolean =
    PIM_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Text zu einem Grund aus [Problems]. */
@Composable
fun problemText(code: String): String = stringResource(
    when (code) {
        Problems.NOT_PAIRED -> R.string.sync_problem_not_paired
        Problems.NETWORK -> R.string.sync_problem_network
        Problems.ROAMING -> R.string.sync_problem_roaming
        Problems.METERED -> R.string.sync_problem_metered
        Problems.CHARGING -> R.string.sync_problem_charging
        Problems.UNREACHABLE -> R.string.sync_problem_unreachable
        Problems.CERTIFICATE -> R.string.sync_problem_certificate
        Problems.NEEDS_119 -> R.string.sync_problem_needs_119
        Problems.UNAVAILABLE -> R.string.sync_problem_unavailable
        Problems.PERMISSION -> R.string.sync_problem_permission
        else -> R.string.sync_problem_error
    },
)

/**
 * „Kontakte und Kalender“ (Entwurf 40): das Konto „TitanOS“ in den Apps des
 * Handys, welche Sammlungen, wie oft, welches Netz, Konflikte.
 */
@Composable
fun SyncScreen(nas: NasConnection, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val store = remember { SyncStore(context) }
    var prefs by remember { mutableStateOf(store.load()) }
    var status by remember { mutableStateOf(store.status()) }
    var granted by remember { mutableStateOf(pimPermissionsGranted(context)) }
    var remote by remember { mutableStateOf<Map<String, List<SyncCollection>>?>(null) }
    var loadProblem by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = pimPermissionsGranted(context)
        if (granted) PimSync.requestNow(context)
    }
    fun update(p: SyncPrefs) {
        prefs = p
        store.save(p)
        PimSync.configure(context)
    }
    LaunchedEffect(Unit) {
        val api = PimSyncApi(nas)
        withContext(Dispatchers.IO) {
            runCatching { mapOf("contacts" to api.collections("contacts"), "calendar" to api.collections("calendar")) }
        }.onSuccess { remote = it }.onFailure { loadProblem = if (it is SyncNotSupportedException) Problems.NEEDS_119 else Problems.UNREACHABLE }
    }
    // Stand alle zwei Sekunden neu lesen, solange die Seite offen ist (der SyncAdapter läuft in einem eigenen Faden).
    LaunchedEffect(tick) {
        while (true) {
            status = store.status()
            running = PimSync.running(context)
            delay(2000)
        }
    }
    val account = PimAccount.current(context)
    Page(topBar = { TopBar(stringResource(R.string.sync_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) }) {
        Note(stringResource(R.string.sync_intro, account?.name ?: "TitanOS"), Modifier.padding(top = 12.dp))
        if (!granted) {
            Problem(stringResource(R.string.sync_permission_text), Modifier.padding(top = 10.dp))
            TitanButton(stringResource(R.string.sync_permission_do), { ask.launch(PIM_PERMISSIONS) }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Primary)
        }
        (loadProblem ?: status.problem)?.let { Problem(problemText(it), Modifier.padding(top = 10.dp)) }

        // Stand und „Jetzt abgleichen“.
        SectionHeader(stringResource(R.string.sync_state))
        Card {
            val last = status.lastOk.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
            ListRow(
                when {
                    running -> stringResource(R.string.sync_running)
                    last != null -> stringResource(R.string.sync_last, last)
                    else -> stringResource(R.string.sync_never)
                },
                sub = if (status.lastOk > 0) stringResource(R.string.sync_counts, status.downloaded, status.uploaded) +
                    (if (status.conflicts > 0) " · " + pluralStringResource(R.plurals.sync_conflicts, status.conflicts, status.conflicts) else "") else null,
                icon = { RowIcon(TitanIcons.sync) },
            )
        }
        TitanButton(stringResource(R.string.sync_now), { PimSync.requestNow(context); running = true; tick++ }, Modifier.fillMaxWidth().padding(top = 10.dp),
            icon = TitanIcons.sync, enabled = granted && account != null)

        // Adressbücher.
        SectionHeader(stringResource(R.string.sync_books))
        Card {
            ToggleRow(stringResource(R.string.sync_contacts_on), prefs.contacts, { update(prefs.copy(contacts = it)) },
                sub = stringResource(if (prefs.contacts) R.string.sync_contacts_on_sub else R.string.sync_contacts_off_sub))
            if (prefs.contacts) {
                for (col in remote?.get("contacts").orEmpty()) {
                    val st = status.collections.firstOrNull { it.kind == "contacts" && it.id == col.id }
                    ToggleRow(
                        col.name.ifBlank { col.id },
                        col.id !in prefs.offContacts,
                        { on -> update(prefs.copy(offContacts = if (on) prefs.offContacts - col.id else prefs.offContacts + col.id)) },
                        sub = collectionSub(col, st, R.plurals.sync_n_contacts),
                        divider = true,
                        icon = { RowIcon(TitanIcons.user) },
                    )
                }
            }
        }

        // Kalender.
        SectionHeader(stringResource(R.string.sync_calendars))
        Card {
            ToggleRow(stringResource(R.string.sync_calendars_on), prefs.calendars, { update(prefs.copy(calendars = it)) },
                sub = stringResource(if (prefs.calendars) R.string.sync_calendars_on_sub else R.string.sync_calendars_off_sub))
            if (prefs.calendars) {
                for (col in remote?.get("calendar").orEmpty()) {
                    val st = status.collections.firstOrNull { it.kind == "calendar" && it.id == col.id }
                    ToggleRow(
                        col.name.ifBlank { col.id },
                        col.id !in prefs.offCalendars,
                        { on -> update(prefs.copy(offCalendars = if (on) prefs.offCalendars - col.id else prefs.offCalendars + col.id)) },
                        sub = collectionSub(col, st, R.plurals.sync_n_events),
                        divider = true,
                        icon = { ColorDot(hexColor(col.color), 14.dp) },
                    )
                }
            }
            ListRow(stringResource(R.string.sync_tasks), sub = stringResource(R.string.sync_tasks_sub), divider = true, icon = { RowIcon(TitanIcons.check) })
        }

        // Abgleich: wie oft, Netz, Konflikte …
        SectionHeader(stringResource(R.string.sync_how))
        val intervals = listOf(0, 15, 30, 60, 120, 360, 720, 1440)
        SettingsGroup(stringResource(R.string.sync_interval), intervalLabel(prefs.intervalMinutes), initiallyOpen = true) {
            ChoiceRows(intervals.map { it to intervalLabel(it) }, prefs.intervalMinutes, { update(prefs.copy(intervalMinutes = it)) })
        }
        SettingsGroup(stringResource(R.string.sync_live), stringResource(if (prefs.liveWhileOpen || prefs.uploadOnChange) UiR.string.ui_on else UiR.string.ui_off)) {
            ToggleRow(stringResource(R.string.sync_live_open), prefs.liveWhileOpen, { update(prefs.copy(liveWhileOpen = it)) }, sub = stringResource(R.string.sync_live_open_sub))
            ToggleRow(stringResource(R.string.sync_upload_change), prefs.uploadOnChange, { update(prefs.copy(uploadOnChange = it)) }, sub = stringResource(R.string.sync_upload_change_sub), divider = true)
        }
        val nets = listOf(NetworkRule.Any to stringResource(R.string.sync_net_any), NetworkRule.NoRoaming to stringResource(R.string.sync_net_no_roaming), NetworkRule.Unmetered to stringResource(R.string.sync_net_unmetered))
        SettingsGroup(stringResource(R.string.sync_network), nets.first { it.first == prefs.network }.second) {
            ChoiceRows(nets, prefs.network, { update(prefs.copy(network = it)) }, subs = mapOf(NetworkRule.Unmetered to stringResource(R.string.sync_net_unmetered_sub)))
            ToggleRow(stringResource(R.string.sync_charging), prefs.chargingOnly, { update(prefs.copy(chargingOnly = it)) }, divider = true)
        }
        val rules = listOf(ConflictRule.Nas to stringResource(R.string.sync_conflict_nas), ConflictRule.Phone to stringResource(R.string.sync_conflict_phone))
        SettingsGroup(stringResource(R.string.sync_conflict), rules.first { it.first == prefs.conflict }.second) {
            ChoiceRows(rules, prefs.conflict, { update(prefs.copy(conflict = it)) },
                subs = mapOf(ConflictRule.Nas to stringResource(R.string.sync_conflict_nas_sub), ConflictRule.Phone to stringResource(R.string.sync_conflict_phone_sub)))
        }
        val reminders = listOf(-1, 0, 5, 10, 15, 30, 60, 120, 1440)
        SettingsGroup(stringResource(R.string.sync_default_reminder), reminderLabel(prefs.defaultReminder)) {
            ChoiceRows(reminders.map { it to reminderLabel(it) }, prefs.defaultReminder, { update(prefs.copy(defaultReminder = it)) })
        }
        SettingsGroup(stringResource(R.string.sync_more), null) {
            ToggleRow(stringResource(R.string.sync_photos), prefs.photos, { update(prefs.copy(photos = it)) }, sub = stringResource(R.string.sync_photos_sub))
            ListRow(stringResource(R.string.sync_android_settings), sub = stringResource(R.string.sync_android_settings_sub), divider = true, chevron = true,
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {}
        Note(stringResource(R.string.sync_remove_note), Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun collectionSub(col: SyncCollection, st: CollectionState?, plural: Int): String {
    val parts = mutableListOf<String>()
    if (col.readOnly) parts += stringResource(R.string.sync_read_only)
    parts += pluralStringResource(plural, st?.count ?: col.count, st?.count ?: col.count)
    st?.lastSync?.takeIf { it > 0 }?.let { parts += stringResource(R.string.sync_synced_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) }
    return parts.joinToString(" · ")
}

@Composable
fun intervalLabel(min: Int): String = when {
    min <= 0 -> stringResource(R.string.sync_interval_manual)
    min < 60 -> pluralStringResource(R.plurals.sync_every_minutes, min, min)
    min < 1440 -> pluralStringResource(R.plurals.sync_every_hours, min / 60, min / 60)
    else -> stringResource(R.string.sync_interval_daily)
}

@Composable
fun reminderLabel(min: Int): String = when {
    min < 0 -> stringResource(R.string.sync_reminder_none)
    min == 0 -> stringResource(R.string.sync_reminder_at_start)
    min < 60 -> pluralStringResource(R.plurals.sync_reminder_minutes, min, min)
    min < 1440 -> pluralStringResource(R.plurals.sync_reminder_hours, min / 60, min / 60)
    else -> pluralStringResource(R.plurals.sync_reminder_days, min / 1440, min / 1440)
}
