package io.github.ra5on.titanos.settings.pages.network

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.ra5on.titanos.settings.R
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject
import java.text.Normalizer
import java.time.Instant

/** Ein Gerät gilt als verbunden nach einem Handshake in den letzten drei Minuten (WireGuard erneuert alle zwei). */
internal fun wgConnected(peer: JsonObject, now: Instant = Instant.now()): Boolean {
    val at = Fmt.instant(peer.str("lastHandshake")) ?: return false
    return now.toEpochMilli() - at.toEpochMilli() < 3 * 60 * 1000
}

/** Ein Dateiname für die Konfiguration eines Geräts: wg-quick nimmt den Namen der Schnittstelle daraus. */
internal fun wgFileName(name: String): String {
    val base = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "")
        .replace(Regex("[^A-Za-z0-9_=+.-]+"), "-").trim('-').take(15)
    return "${base.ifEmpty { "titanos" }}.conf"
}

/** Grobe Prüfung der Adresse, die Geräte wählen; das NAS prüft noch einmal. */
internal fun validEndpoint(host: String): Boolean {
    val v = host.trim()
    if (v.isEmpty()) return true
    if (v.contains(':')) return Regex("^[0-9a-fA-F:]+$").matches(v)
    return Regex("^[A-Za-z0-9.-]{1,253}$").matches(v) && !v.startsWith(".") && !v.endsWith(".") && !v.contains("..")
}

/** Was `PUT network/wireguard` nimmt: die Einstellungen ohne die Geräte (die verwaltet das NAS selbst). */
internal fun wgBody(status: JsonObject, enabled: Boolean, endpoint: String, fullTunnel: Boolean, dns: String): JsonObject = jobj(
    "enabled" to enabled,
    "endpoint" to endpoint.trim(),
    "port" to status.long("port"),
    "network" to status.str("network"),
    "fullTunnel" to fullTunnel,
    "dns" to dns.trim(),
)

/** Ein QR-Code, dunkel auf hell in jedem Thema (Scanner erwarten das), mit Ruhezone. */
internal fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

/**
 * Einstellungen › WireGuard (Paket wgserver): der VPN-Server des NAS, so
 * einfach wie möglich – ein Schalter, Geräte mit Namen und QR-Code. Der
 * private Schlüssel eines neuen Geräts steht nur einmal da und wird nirgends gespeichert.
 */
@Composable
fun WireGuardPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val state = rememberLoad { env.api.obj("network/wireguard") }
    val run = rememberRunner()
    PageIntro(t("wg.intro"))
    Loading(state) { loaded ->
        var status by remember(loaded) { mutableStateOf(loaded) }
        var enabled by remember(status) { mutableStateOf(status.bool("enabled")) }
        var endpoint by remember(status) { mutableStateOf(status.str("endpoint")) }
        var fullTunnel by remember(status) { mutableStateOf(status.bool("fullTunnel")) }
        var dns by remember(status) { mutableStateOf(status.str("dns")) }
        val endpointOk = validEndpoint(endpoint)
        val canSave = !run.running && endpointOk && (!enabled || endpoint.isNotBlank())
        var saveError by remember { mutableStateOf("") }
        var saved by remember { mutableStateOf(false) }
        val problem = { e: Throwable -> tr.errorWithReason(e, setOf("invalid_wireguard"), Regex("^wireguard: invalid settings: ?")) }

        SettingGroup(t("wg.server")) {
            if (status.bool("disabled")) Banner(t("wg.devMode"), tone = Tone.Warn)
            SettingRow(t("wg.enabled"), icon = WebIcons["shield"], control = {
                if (status.bool("enabled")) StatusChip(t(if (status.bool("running")) "wg.running" else "wg.notRunning"), if (status.bool("running")) Tone.Ok else Tone.Warn)
                Switch(enabled, { enabled = it }, t("wg.enabled"), enabled = !run.running)
            })
            SettingRow(t("wg.endpoint"), desc = t("wg.endpointHint"), icon = WebIcons["globe"], control = {
                Field(endpoint, { endpoint = it }, t("wg.endpoint"), placeholder = "nas.example.org", mono = true, singleLine = true, enabled = !run.running)
            })
            if (!endpointOk) Banner(t("wg.endpointInvalid"), tone = Tone.Warn)
            SettingRow(t("wg.access"), icon = WebIcons["tailnet"], id = "wg-access", summary = t(if (fullTunnel) "wg.accessAllShort" else "wg.accessHomeShort"), startOpen = true) {
                RadioList(fullTunnel, listOf(Triple(false, t("wg.accessHome"), null), Triple(true, t("wg.accessAll"), null)), { fullTunnel = it }, enabled = !run.running)
            }
            SettingRow(t("wg.dns"), desc = t("wg.dnsHint"), icon = WebIcons["search"], control = {
                Field(dns, { dns = it }, t("wg.dns"), placeholder = "192.168.178.1", mono = true, singleLine = true, enabled = !run.running)
            })
            if (status.str("network").isNotEmpty()) Hint(t("wg.details", "network" to status.str("network"), "port" to status.long("port").toString()))
            Hint(t("wg.router", "port" to status.long("port").toString()))
            Note(status.str("error"))
            run.error?.let { Banner(it, tone = Tone.Error) }
            Note(saveError)
            Buttons {
                SmallButton(t("common.save"), {
                    val body = wgBody(status, enabled, endpoint, fullTunnel, dns)
                    saveError = ""
                    run.run(guard = GuardAction.Network) {
                        try {
                            status = env.api.put("network/wireguard", body) as? JsonObject ?: status
                            saved = true
                        } catch (e: io.github.ra5on.titanos.net.ApiException) {
                            saveError = problem(e)
                        }
                    }
                }, kind = ButtonKind.Primary, enabled = canSave)
                if (run.running || saved) Hint(t(if (run.running) "ui.saving" else "ui.saved"))
            }
        }

        SettingGroup(
            t("wg.devices"),
            tools = {
                SmallButton(t("wg.add"), {
                    ov.show { close ->
                        AddDevice(onClose = close) { created ->
                            close()
                            state.reload()
                            ov.show { done -> CreatedDevice(created.str("name"), created.str("config"), done) }
                        }
                    }
                }, enabled = !run.running && status.bool("enabled") && status.str("network").isNotEmpty())
            },
        ) {
            if (!status.bool("enabled")) Hint(t("wg.enableFirst"))
            val peers = status.objs("peers")
            if (peers.isEmpty() && status.bool("enabled")) Hint(t("wg.none"))
            for (peer in peers) {
                val connected = wgConnected(peer)
                val seen = if (peer.str("lastHandshake").isNotEmpty()) t("wg.lastSeen", "time" to tr.whenOr(peer.str("lastHandshake"))) else t("wg.never")
                val traffic = if (peer.long("received") > 0 || peer.long("sent") > 0) " · ↓ ${Fmt.bytes(tr, peer.long("sent"))} · ↑ ${Fmt.bytes(tr, peer.long("received"))}" else ""
                SettingRow(
                    peer.str("name"),
                    desc = seen + traffic,
                    icon = WebIcons["monitor"],
                    control = {
                        Mono(peer.str("address").removeSuffix("/32"))
                        StatusChip(t(if (connected) "wg.connected" else "wg.notConnected"), if (connected) Tone.Ok else Tone.Inactive)
                        SmallButton(t("wg.remove"), {
                            ov.confirm(tr.t("wg.remove"), tr.t("wg.removeText", "name" to peer.str("name")), action = tr.t("wg.remove"), cancel = tr.t("common.cancel")) {
                                run.run(guard = GuardAction.Network) {
                                    status = env.api.post("network/wireguard/peers/remove", jobj("publicKey" to peer.str("publicKey"), "name" to peer.str("name"))) as? JsonObject ?: status
                                }
                            }
                        }, enabled = !run.running)
                    },
                )
            }
            Hint(t("wg.apps"))
        }
    }
}

/** „Gerät hinzufügen“: nur ein Name; das NAS erzeugt die Schlüssel. */
@Composable
private fun BoxScope.AddDevice(onClose: () -> Unit, onCreated: (JsonObject) -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    var name by remember { mutableStateOf("") }
    val run = rememberRunner()
    FormDialog(
        t("wg.add"),
        onClose,
        listOf(
            Act(t("common.cancel"), onClick = onClose),
            Act(t("wg.create"), ButtonKind.Primary, enabled = !run.running && name.isNotBlank()) {
                val wanted = name.trim()
                run.run(guard = GuardAction.Network) {
                    val created = env.api.post("network/wireguard/peers", jobj("name" to wanted)) as? JsonObject ?: return@run
                    onCreated(created)
                }
            },
        ),
    ) {
        LabeledField(t("wg.deviceName"), name, { name = it.take(40) }, placeholder = t("wg.deviceNamePlaceholder"))
        run.error?.let { Banner(it, tone = Tone.Error) }
    }
}

/**
 * Das neue Gerät: QR-Code zum Scannen, die Konfiguration zum Aufklappen,
 * Speichern als Datei und Kopieren. Nur jetzt sichtbar: geschlossen ist der
 * private Schlüssel weg (er liegt nur in diesem Dialog im Speicher).
 */
@Composable
private fun BoxScope.CreatedDevice(name: String, config: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val qr = remember(config) { runCatching { qrBitmap(config) }.getOrNull() }
    val copied = stringResource(R.string.net_copied)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(config.toByteArray()) } }
    }
    FormDialog(
        t("wg.createdTitle", "name" to name),
        onClose,
        listOf(
            Act(t("wg.download")) { save.launch(wgFileName(name)) },
            Act(stringResource(R.string.net_copy)) {
                copyText(context, name, config, secret = true)
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            },
            Act(t("wg.done"), ButtonKind.Primary, onClick = onClose),
        ),
    ) {
        Hint(t("wg.createdHint"))
        if (qr != null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Image(
                    qr.asImageBitmap(),
                    contentDescription = t("wg.qrLabel", "name" to name),
                    modifier = Modifier.size(240.dp).clip(RoundedCornerShape(8.dp)).background(Color.White),
                )
            }
        }
        LinkText(t("wg.showConfig")) { show = !show }
        if (show) Mono(config)
        Banner(t("wg.onceOnly"), tone = Tone.Warn)
    }
}
