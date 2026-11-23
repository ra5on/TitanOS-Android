package io.github.ra5on.titanos.vpn

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ra5on.titanos.net.ApiException
import kotlinx.coroutines.launch
import java.io.IOException

/** Berechtigungen und Android-Zustände jetzt. */
fun permsOf(context: Context): VpnPerms = VpnPerms(
    location = NetworkWatch.locationGranted(context),
    backgroundLocation = NetworkWatch.backgroundLocationGranted(context),
    locationOn = NetworkWatch.locationOn(context),
    vpnAllowed = VpnService.prepare(context) == null,
    batteryExempt = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
    notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
    backgroundAllowed = !io.github.ra5on.titanos.ui.Distribution.play(context),
)

private fun codeOf(e: Throwable): String = when (e) {
    is ApiException -> e.code.ifEmpty { "http_${e.status}" }
    is IOException -> "unreachable"
    else -> "unreachable"
}

/**
 * Seite „VPN“ mit allem, was Android dazu fragt: Erlaubnis für das VPN,
 * Standort (mit Erklärung vorher auf der Seite), Mitteilungen, Akku-Ausnahme.
 */
@Composable
fun VpnPage(onBack: () -> Unit, ownerNas: String = "", forNas: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    val live by VpnRuntime.state.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(VpnRuntime.store.settings()) }
    var profile by remember { mutableStateOf(VpnRuntime.store.profile()) }
    var answer by remember { mutableStateOf<VpnAnswer?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    var perms by remember { mutableStateOf(permsOf(context)) }
    var ssid by remember { mutableStateOf(NetworkWatch.currentSsid(context)) }
    var connectAfterConsent by remember { mutableStateOf(false) }
    val tileLabel = stringResource(R.string.vpn_tile_label)

    fun reread() {
        perms = permsOf(context)
        ssid = NetworkWatch.currentSsid(context)
        settings = VpnRuntime.store.settings()
        profile = VpnRuntime.store.profile()
    }

    fun load() {
        scope.launch {
            loading = true
            val had = VpnRuntime.store.profile() != null
            try {
                answer = VpnRuntime.fetch()
                error = null
                gone = had && answer?.peer == null
            } catch (e: Exception) {
                error = codeOf(e)
            }
            loading = false
            reread()
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        perms = permsOf(context)
        if (connectAfterConsent && perms.vpnAllowed) VpnRuntime.userSet(true)
        connectAfterConsent = false
    }
    fun askVpn(thenConnect: Boolean) {
        val intent = VpnService.prepare(context)
        if (intent == null) {
            if (thenConnect) VpnRuntime.userSet(true)
            perms = permsOf(context)
            return
        }
        connectAfterConsent = thenConnect
        consent.launch(intent)
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        reread()
        VpnRuntime.evaluate("permission")
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reread() }
    val settingsReturn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        reread()
        VpnRuntime.evaluate("settings")
    }

    LaunchedEffect(Unit) { load() }
    DisposableEffect(Unit) {
        VpnRuntime.watch(true)
        onDispose { VpnRuntime.watch(false) }
    }
    LifecycleResumeEffect(Unit) {
        reread()
        onPauseOrDispose { }
    }

    val ui = VpnUi(
        ownerNas = ownerNas,
        forNas = forNas,
        live = live, settings = settings, profile = profile, answer = answer, loading = loading, error = error, busy = busy,
        perms = perms, currentSsid = ssid, gone = gone,
    )
    VpnScreen(
        ui,
        VpnActions(
            back = onBack,
            connect = { askVpn(thenConnect = true) },
            disconnect = { VpnRuntime.userSet(false) },
            create = {
                scope.launch {
                    busy = true
                    try {
                        answer = VpnRuntime.createAccess()
                        error = null
                        gone = false
                        reread()
                        // Gleich die Erlaubnis von Android holen; Automatisch verbindet danach von selbst.
                        askVpn(thenConnect = false)
                    } catch (e: Exception) {
                        error = codeOf(e)
                    }
                    busy = false
                }
            },
            regenerate = {
                scope.launch {
                    busy = true
                    try {
                        answer = VpnRuntime.createAccess()
                        error = null
                    } catch (e: Exception) {
                        error = codeOf(e)
                    }
                    reread()
                    busy = false
                }
            },
            remove = {
                scope.launch {
                    busy = true
                    try {
                        VpnRuntime.removeAccess(remote = true)
                        answer = runCatching { VpnRuntime.fetch() }.getOrNull()
                        error = null
                    } catch (e: Exception) {
                        error = codeOf(e)
                    }
                    reread()
                    busy = false
                }
            },
            retry = { load() },
            update = { next ->
                settings = next
                VpnRuntime.updateSettings(next)
            },
            askLocation = {
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            askBackgroundLocation = {
                // Android 11+: „Immer zulassen“ gibt es nur in den Einstellungen der App.
                settingsReturn.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            },
            openLocationSettings = { settingsReturn.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            askVpn = { askVpn(thenConnect = false) },
            openVpnSettings = { settingsReturn.launch(Intent(Settings.ACTION_VPN_SETTINGS)) },
            askBattery = {
                // Nur nach Tippen; von Google Play nur die Liste der Akku-Optimierung (core-ui Distribution).
                val intent = io.github.ra5on.titanos.ui.Distribution.batteryExemptionIntent(context)
                runCatching { settingsReturn.launch(intent) }
            },
            askNotifications = { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) },
            addTile = {
                val sbm = context.getSystemService(StatusBarManager::class.java)
                runCatching {
                    sbm?.requestAddTileService(
                        ComponentName(context, VpnTileService::class.java),
                        tileLabel,
                        Icon.createWithResource(context, R.drawable.ic_vpn_shield),
                        context.mainExecutor,
                    ) { }
                }
            },
        ),
    )
}
