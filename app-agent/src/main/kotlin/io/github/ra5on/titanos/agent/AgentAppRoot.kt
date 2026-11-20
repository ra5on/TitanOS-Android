package io.github.ra5on.titanos.agent

import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.ra5on.titanos.agent.chat.AgentRoot
import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.net.PairLink
import io.github.ra5on.titanos.net.ServerAddress
import io.github.ra5on.titanos.pairing.ConfirmScreen
import io.github.ra5on.titanos.pairing.LOCAL_NETWORK
import io.github.ra5on.titanos.pairing.ManualScreen
import io.github.ra5on.titanos.pairing.PairingModel
import io.github.ra5on.titanos.pairing.WelcomeScreen
import io.github.ra5on.titanos.pairing.localNetworkGranted
import io.github.ra5on.titanos.pairing.R as PairR

private enum class SetupStep { Welcome, Manual, Confirm }

/** Einrichten (ohne Kopplung) oder der Agent. Gekoppelt wird mit `app = "agent"`. */
@Composable
fun AgentAppRoot(graph: AgentGraph, link: PairLink?, onLinkTaken: () -> Unit) {
    val context = LocalContext.current
    val pairingModel: PairingModel = viewModel {
        PairingModel(graph.key, graph.store, graph.shared, defaultDeviceName(context), app = APP) { p -> graph.paired(p) }
    }
    var step by rememberSaveable { mutableStateOf(SetupStep.Welcome) }
    LaunchedEffect(link) {
        if (link != null) {
            pairingModel.startFromLink(link)
            step = SetupStep.Confirm
            onLinkTaken()
        }
    }
    val model = graph.model
    if (graph.pairing == null || model == null) {
        SetupFlow(graph, pairingModel, step) { step = it }
    } else {
        AgentRoot(
            model,
            ask = graph.pendingAsk,
            onAskTaken = { graph.pendingAsk = null },
            versionName = versionName(context),
            hostname = graph.pairing?.hostname.orEmpty(),
            onUnpair = {
                graph.forget()
                step = SetupStep.Welcome
            },
        )
    }
}

@Composable
private fun SetupFlow(graph: AgentGraph, model: PairingModel, step: SetupStep, go: (SetupStep) -> Unit) {
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
        SetupStep.Welcome -> WelcomeScreen(
            onScan = {
                scan.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(scanPrompt)
                        .setBeepEnabled(false).setOrientationLocked(false),
                )
            },
            onManual = { go(SetupStep.Manual) },
            problem = when {
                invalidScan -> stringResource(PairR.string.pair_scan_invalid)
                graph.unpairedByNas -> stringResource(R.string.agent_unpaired)
                else -> null
            },
            title = stringResource(R.string.agent_app_name),
            text = stringResource(R.string.agent_welcome_text),
            glyph = "agent",
            gradient = "ember",
        )
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
            val back = { model.cancel(); go(SetupStep.Welcome) }
            BackHandler(onBack = back)
            LaunchedEffect(Unit) {
                val lan = model.confirm.addresses.any { it.kind == AddressKind.LAN }
                withLocalNetwork(lan) { model.recheck() }
            }
            ConfirmScreen(model.confirm, model::setDeviceName, model::pair, onCancel = back)
        }
    }
}

/** Die App meldet sich am NAS als „agent“ (docs/ANDROID-APP.md, Ablauf 2). */
const val APP = "agent"

/** Vorschlag für den Gerätenamen: der Name aus den Android-Einstellungen, sonst das Modell. */
private fun defaultDeviceName(context: android.content.Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

private fun versionName(context: android.content.Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
