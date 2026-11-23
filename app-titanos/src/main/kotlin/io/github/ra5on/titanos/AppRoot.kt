package io.github.ra5on.titanos

import android.Manifest
import android.provider.CalendarContract
import android.provider.ContactsContract
import io.github.ra5on.titanos.pim.sync.PimSync
import io.github.ra5on.titanos.pim.sync.SyncStore
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import io.github.ra5on.titanos.widgets.redraw
import io.github.ra5on.titanos.photos.upToDate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.ra5on.titanos.files.FilesModel
import io.github.ra5on.titanos.home.HomeModel
import io.github.ra5on.titanos.home.HomeScreen
import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.net.PairLink
import io.github.ra5on.titanos.net.ServerAddress
import io.github.ra5on.titanos.notify.NotifyRuntime
import io.github.ra5on.titanos.notify.NotifyScheduler
import io.github.ra5on.titanos.notify.NotifyTab
import io.github.ra5on.titanos.notify.notificationsOn
import io.github.ra5on.titanos.notify.setNotificationsOn
import io.github.ra5on.titanos.pairing.ConfirmScreen
import io.github.ra5on.titanos.pairing.LOCAL_NETWORK
import io.github.ra5on.titanos.pairing.localNetworkGranted
import io.github.ra5on.titanos.pairing.ManualScreen
import io.github.ra5on.titanos.pairing.PairingModel
import io.github.ra5on.titanos.pairing.PermissionsRoute
import io.github.ra5on.titanos.pairing.WelcomeScreen
import io.github.ra5on.titanos.photos.Access
import io.github.ra5on.titanos.photos.MediaAccess
import io.github.ra5on.titanos.photos.PhotosTab
import io.github.ra5on.titanos.photos.SettingsStore
import io.github.ra5on.titanos.photos.homeTileText
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.BottomNav
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.NavItem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import kotlinx.coroutines.launch
import io.github.ra5on.titanos.home.R as HomeR
import io.github.ra5on.titanos.pairing.R as PairR
import io.github.ra5on.titanos.ui.theme.dangerInk

private enum class SetupStep { Welcome, Manual, Confirm }

/** Einrichten (ohne Kopplung) oder die App mit der Leiste unten. */
@Composable
fun AppRoot(graph: AppGraph, link: PairLink?, onLinkTaken: () -> Unit) {
    val pairing = graph.pairing
    var permissionsPending by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val pairingModel: PairingModel = viewModel {
        PairingModel(graph.key, graph.store, graph.shared, defaultDeviceName(context)) { p ->
            graph.paired(p)
            permissionsPending = true
        }
    }
    var step by rememberSaveable { mutableStateOf(SetupStep.Welcome) }
    // Ein Kopplungslink, während schon ein NAS gekoppelt ist: erst fragen, dann ein weiteres NAS hinzufügen.
    var addLink by remember { mutableStateOf<PairLink?>(null) }
    var askLink by remember { mutableStateOf<PairLink?>(null) }
    LaunchedEffect(link) {
        if (link != null) {
            if (graph.pairing == null) {
                pairingModel.startFromLink(link)
                step = SetupStep.Confirm
            } else {
                // Ein fremder Link koppelt nie still: deutlicher Hinweis vorher, das neue NAS wird nicht gewählt.
                askLink = link
            }
            onLinkTaken()
        }
    }
    val adding = graph.adding
    var disclaimerOk by remember { mutableStateOf(io.github.ra5on.titanos.ui.components.DisclaimerStore.accepted(context)) }
    // Beim Koppeln bestätigt (SetupFlow): danach nicht noch einmal fragen.
    if (pairing != null && !disclaimerOk && io.github.ra5on.titanos.ui.components.DisclaimerStore.accepted(context)) disclaimerOk = true
    when {
        pairing == null -> SetupFlow(graph, pairingModel, step, { step = it })
        adding != null -> AddFlow(graph, adding, addLink, onLinkUsed = { addLink = null }) { if (adding.activate) permissionsPending = true }
        permissionsPending -> PermissionsRoute(
            onNext = { permissionsPending = false; step = SetupStep.Welcome },
            photoPermissions = MediaAccess.PERMISSIONS,
            // Je NAS eigene Einstellungen: die des gerade gekoppelten (gewählten) NAS.
            photosOn = { SettingsStore(context, graph.activeId).load().enabled && MediaAccess.access(context) != Access.None },
            photosPartial = { MediaAccess.access(context) == Access.Partial },
            // Eingeschaltet wird hier; das Ziel wählt man danach unter Fotos.
            onPhotos = { on ->
                graph.photos?.update(SettingsStore(context, graph.activeId).load().copy(enabled = on)) ?: SettingsStore(context, graph.activeId).let { it.save(it.load().copy(enabled = on)) }
            },
            // Mitteilungen ohne Google; danach die Bitte um die Akku-Ausnahme (TECHNIK §5).
            notifyOn = { notificationsOn(context) },
            onNotify = { on ->
                setNotificationsOn(context, on)
                if (on) NotifyScheduler.askBatteryExemption(context)
            },
            // Kontakte und Kalender über das Android-Konto der Kopplung (feature-pim).
            contactsPermissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
            calendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            pimOn = { kind -> SyncStore(context, graph.activeProfile?.let(graph::pimName)).load().let { if (kind == "contacts") it.contacts else it.calendars } },
            onPim = { kind, on ->
                val store = SyncStore(context, graph.activeProfile?.let(graph::pimName))
                store.save(store.load().let { if (kind == "contacts") it.copy(contacts = on) else it.copy(calendars = on) })
                PimSync.configure(context)
                if (on) PimSync.requestNow(context, manual = false, only = if (kind == "contacts") ContactsContract.AUTHORITY else CalendarContract.AUTHORITY)
            },
        )
        // Bestehende Installationen (vor 1.01 gekoppelt): den Haftungsausschluss einmal bestätigen.
        // Nur die Oberfläche wartet; Mitteilungen, Foto-Sicherung und Abgleich laufen weiter.
        !disclaimerOk -> io.github.ra5on.titanos.ui.components.DisclaimerGate {
            io.github.ra5on.titanos.ui.components.DisclaimerStore.accept(context)
            disclaimerOk = true
        }
        // Je NAS fängt die Oberfläche neu an (keine Seite behält die Verbindung des vorigen NAS);
        // die Sperre beim Öffnen liegt außen, damit ein Wechsel des NAS nicht neu sperrt.
        else -> AppLock {
            Box(Modifier.fillMaxSize()) {
                androidx.compose.runtime.key(graph.nasEpoch) { MainShell(graph) }
                // „Schnell finden“ (Palette.kt): über der Startseite wie über dem Arbeitsplatz.
                if (graph.openPalette) CommandPalette(graph) { graph.openPalette = false }
                askLink?.let { l ->
                    ConfirmDialog(
                        title = stringResource(R.string.nas_link_title),
                        text = stringResource(R.string.nas_link_text, l.addresses.joinToString(", ") { it.address.server }),
                        actions = listOf(
                            DialogAction(stringResource(R.string.nas_link_go)) {
                                askLink = null
                                if (graph.adding == null) graph.beginAdd(fromLink = true)
                                addLink = l
                            },
                            DialogAction(stringResource(UiR.string.ui_cancel)) { askLink = null },
                        ),
                        onDismiss = { askLink = null },
                    )
                }
            }
        }
    }
}

/** „NAS hinzufügen“: derselbe Ablauf wie beim ersten Koppeln, mit eigenem Schlüssel; Zurück bricht ab. */
@Composable
private fun AddFlow(graph: AppGraph, adding: io.github.ra5on.titanos.net.AddingPairingStore, link: PairLink?, onLinkUsed: () -> Unit, onPaired: () -> Unit) {
    val context = LocalContext.current
    val model: PairingModel = viewModel(key = "add-" + adding.id) {
        PairingModel(graph.addingKey(adding), adding, graph.shared, defaultDeviceName(context)) { _ ->
            graph.added(adding)
            onPaired()
        }
    }
    var step by rememberSaveable(adding.id) { mutableStateOf(SetupStep.Welcome) }
    LaunchedEffect(link) {
        if (link != null) {
            model.startFromLink(link)
            step = SetupStep.Confirm
            onLinkUsed()
        }
    }
    SetupFlow(graph, model, step, { step = it }, onCancelAdd = { graph.cancelAdd() })
}

@Composable
private fun SetupFlow(graph: AppGraph, model: PairingModel, step: SetupStep, go: (SetupStep) -> Unit, onCancelAdd: (() -> Unit)? = null) {
    val context = LocalContext.current
    var invalidScan by remember { mutableStateOf(false) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val link = PairLink.parse(text)
        if (link == null) {
            invalidScan = true
        } else {
            invalidScan = false
            model.startFromLink(link)
            go(SetupStep.Confirm)
        }
    }
    val scanPrompt = stringResource(PairR.string.pair_scan_prompt)
    // Ab Android 17 laufen Verbindungen ins Heimnetz ohne diese Berechtigung still ins Leere.
    var afterLocal by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askLocal = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterLocal?.invoke()
        afterLocal = null
    }
    fun withLocalNetwork(needed: Boolean, then: () -> Unit) {
        if (needed && !localNetworkGranted(context)) {
            afterLocal = then
            askLocal.launch(LOCAL_NETWORK)
        } else {
            then()
        }
    }
    when (step) {
        SetupStep.Welcome -> Box(Modifier.fillMaxSize()) {
            if (onCancelAdd != null) BackHandler(onBack = onCancelAdd)
            WelcomeScreen(
                onScan = {
                    scan.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(scanPrompt)
                            .setBeepEnabled(false).setOrientationLocked(false),
                    )
                },
                onManual = { go(SetupStep.Manual) },
                problem = when {
                    invalidScan -> stringResource(PairR.string.pair_scan_invalid)
                    graph.unpairedByNas && onCancelAdd == null -> stringResource(R.string.app_unpaired)
                    else -> null
                },
                title = if (onCancelAdd != null) stringResource(R.string.nas_add) else null,
                text = if (onCancelAdd != null) stringResource(R.string.nas_add_text) else null,
            )
            // Weiteres NAS: sichtbarer Weg zurück (nicht nur die Zurück-Geste).
            if (onCancelAdd != null) {
                Box(Modifier.statusBarsPadding().padding(start = 6.dp, top = 4.dp)) {
                    io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.back, stringResource(UiR.string.ui_back), onCancelAdd)
                }
            }
        }
        SetupStep.Manual -> {
            BackHandler { go(SetupStep.Welcome) }
            ManualScreen(
                state = model.manual,
                onAddress = model::setManualAddress,
                onProbe = {
                    val lan = ServerAddress.parse(model.manual.address)?.kind == AddressKind.LAN
                    withLocalNetwork(lan) { model.probe() }
                },
                onCode = model::setManualCode,
                onMismatch = model::rejectManual,
                onAccept = { if (model.acceptManual()) go(SetupStep.Confirm) },
                onBack = { go(SetupStep.Welcome) },
            )
        }
        SetupStep.Confirm -> {
            // Haftungsausschluss: einmal je Installation vor dem ersten Koppeln (Inhaber 23.11.2026).
            var accepted by remember { mutableStateOf(io.github.ra5on.titanos.ui.components.DisclaimerStore.accepted(context)) }
            var checked by rememberSaveable { mutableStateOf(false) }
            var reading by rememberSaveable { mutableStateOf(false) }
            if (reading) {
                io.github.ra5on.titanos.ui.components.DisclaimerPage(onBack = { reading = false })
                return
            }
            val back = { model.cancel(); go(SetupStep.Welcome) }
            BackHandler(onBack = back)
            LaunchedEffect(Unit) {
                val lan = model.confirm.addresses.any { it.kind == AddressKind.LAN }
                withLocalNetwork(lan) { model.recheck() }
            }
            ConfirmScreen(
                model.confirm,
                model::setDeviceName,
                onPair = {
                    if (accepted || checked) {
                        if (!accepted) {
                            io.github.ra5on.titanos.ui.components.DisclaimerStore.accept(context)
                            accepted = true
                        }
                        model.pair()
                    }
                },
                onCancel = back,
                disclaimer = if (accepted) null else checked,
                onDisclaimer = { checked = it },
                onReadDisclaimer = { reading = true },
            )
        }
    }
}

/** Vorschlag für den Gerätenamen: der Name aus den Android-Einstellungen, sonst das Modell. */
private fun defaultDeviceName(context: android.content.Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

