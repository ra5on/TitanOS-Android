package io.github.ra5on.titanos.settings.pages.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Was `PUT system/letsencrypt` bekommt: die gespeicherten Einstellungen mit den geänderten Feldern; der Anbieter nur bei der DNS-Prüfung. */
internal fun letsEncryptBody(
    settings: JsonObject,
    enabled: Boolean,
    email: String,
    staging: Boolean,
    domains: List<String>,
    apps: Boolean,
    challenge: String,
    provider: JsonObject,
    propagation: Long,
    directory: String,
    cleared: List<String>,
): JsonObject = settings.with(
    "enabled" to enabled,
    "email" to email.trim(),
    "staging" to staging,
    "domains" to domains,
    "apps" to (challenge == "dns-01" && apps),
    "challenge" to challenge,
    "provider" to if (challenge == "dns-01") providerPayload(provider) else jobj("type" to ""),
    "propagation" to propagation,
    "directory" to directory.trim(),
    "clearSecrets" to cleared,
)

/**
 * Zertifikate von Let's Encrypt (Paket letsencrypt): Namen, wie sie
 * nachgewiesen werden (DNS-Anbieter oder Port 80), Stand der Erneuerung.
 */
@Composable
fun LetsEncryptPage(a: PageArgs) {
    val env = LocalEnv.current
    val state = rememberLoad { env.api.obj("system/letsencrypt") }
    Loading(state) { loaded -> LetsEncryptContent(env.api, loaded, a) }
}

@Composable
private fun LetsEncryptContent(api: SettingsApi, loaded: JsonObject, a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val run = rememberRunner()
    val renew = rememberRunner()
    var status by remember(loaded) { mutableStateOf(loaded) }
    var round by remember { mutableStateOf(0) }
    val set = status.o("settings")
    var enabled by remember(loaded, round) { mutableStateOf(set.bool("enabled")) }
    var names by remember(loaded, round) { mutableStateOf(set.strings("domains").joinToString("\n")) }
    var apps by remember(loaded, round) { mutableStateOf(set.bool("apps")) }
    var challenge by remember(loaded, round) { mutableStateOf(set.str("challenge").ifEmpty { "dns-01" }) }
    var provider by remember(loaded, round) { mutableStateOf(providerForm(set.o("provider"))) }
    var cleared by remember(loaded, round) { mutableStateOf(listOf<String>()) }
    var email by remember(loaded, round) { mutableStateOf(set.str("email")) }
    var staging by remember(loaded, round) { mutableStateOf(set.bool("staging")) }
    var propagation by remember(loaded, round) { mutableStateOf(set.long("propagation").takeIf { it > 0 }?.toString() ?: "") }
    var directory by remember(loaded, round) { mutableStateOf(set.str("directory")) }
    var saveError by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { delay(5000); saved = false } }
    // Während ein Zertifikat geholt wird, folgt die Seite.
    LaunchedEffect(status.bool("running")) {
        while (status.bool("running")) {
            delay(3000)
            runCatching { status = api.obj("system/letsencrypt") }
        }
    }
    val domains = parseNames(names)
    val wildcard = challenge == "dns-01"
    val bad = domains.filter { !validHostName(it, wildcard) }
    val missing = if (challenge == "dns-01") missingFields(provider, set.o("provider"), status.o("secrets"), cleared) else emptyList()
    val prop = if (propagation.isBlank()) 0L else propagation.trim().toLongOrNull() ?: -1L
    val propBad = prop < 0 || prop > 3600
    val valid = bad.isEmpty() && (!enabled || domains.isNotEmpty()) && (challenge != "dns-01" || (provider.str("type").isNotEmpty() && missing.isEmpty())) && !propBad

    val cert = status["certificate"] as? JsonObject
    val badge = when {
        !set.bool("enabled") -> t("letsencrypt.state.off") to Tone.Inactive
        status.bool("running") -> t("letsencrypt.state.running") to Tone.Info
        status.str("lastError").isNotEmpty() -> t("letsencrypt.state.error") to Tone.Warn
        cert != null -> t("letsencrypt.state.ok") to Tone.Ok
        else -> t("letsencrypt.state.waiting") to Tone.Inactive
    }
    SettingGroup(t("letsencrypt.statusTitle"), desc = t("letsencrypt.intro")) {
        if (status.bool("disabled")) Banner(t("letsencrypt.devMode"), tone = Tone.Warn)
        SettingRow(t("letsencrypt.certRow"), icon = WebIcons["lock"], id = "le-cert", startOpen = true, control = {
            if (status.bool("running") && status.str("phase").isNotEmpty()) Hint(tr.or("letsencrypt.phase.${status.str("phase")}", status.str("phase")))
            StatusChip(badge.first, badge.second)
        }) {
            if (cert != null) {
                if (status.bool("certificateStaging")) Banner(t("letsencrypt.stagingCert"), tone = Tone.Warn)
                val until = Fmt.instant(cert.str("notAfter"))
                val days = until?.let { ((it.toEpochMilli() - Instant.now().toEpochMilli()) / 86_400_000L) } ?: 0L
                if (days < 14) Banner(t("letsencrypt.expiring", "days" to maxOf(days, 0L)), tone = Tone.Warn)
                Fact(t("access.certIssuer"), cert.str("issuer").ifEmpty { "–" })
                Fact(t("access.certUntil"), tr.whenOr(cert.str("notAfter")))
                if (status.str("renewAt").isNotEmpty()) Fact(t("letsencrypt.renewAt"), tr.whenOr(status.str("renewAt")))
                Fact(t("access.certNames"), cert.strings("names").joinToString(", "), mono = true)
            } else if (set.bool("enabled")) {
                Hint(t("letsencrypt.noCert"))
            }
            if (status.str("lastError").isNotEmpty()) {
                val next = status.str("nextAttempt").takeIf { it.isNotEmpty() }?.let { "\n" + tr.t("letsencrypt.nextAttempt", "time" to tr.whenOr(it)) }.orEmpty()
                Banner(t("letsencrypt.lastError", "count" to status.long("failures")) + "\n" + status.str("lastError") + next, tone = Tone.Error)
            }
            if (status.str("lastAttempt").isNotEmpty()) Fact(t("letsencrypt.lastAttempt"), tr.whenOr(status.str("lastAttempt")))
            if (status.str("lastSuccess").isNotEmpty()) Fact(t("letsencrypt.lastSuccess"), tr.whenOr(status.str("lastSuccess")))
            Buttons {
                SmallButton(t("letsencrypt.renewNow"), {
                    renew.run(guard = GuardAction.Network) { status = api.post("system/letsencrypt/renew") as? JsonObject ?: status }
                }, enabled = set.bool("enabled") && !status.bool("running") && !status.bool("disabled") && !renew.running)
                SmallButton(t("letsencrypt.toAccess"), { a.openPage("access") })
            }
        }
        renew.error?.let { Banner(it, tone = Tone.Error) }
    }

    SettingGroup(t("letsencrypt.settingsTitle")) {
        SettingRow(t("letsencrypt.enable"), desc = t("letsencrypt.enableHint"), icon = WebIcons["power"], control = {
            Switch(enabled, { enabled = it }, t("letsencrypt.enable"), enabled = !run.running)
        })
        SettingRow(t("letsencrypt.names"), desc = t("letsencrypt.namesHint"), icon = WebIcons["list"]) {
            Field(names, { names = it }, t("letsencrypt.names"), Modifier.fillMaxWidth(), placeholder = "nas.example.org", mono = true, enabled = !run.running, minHeight = 84.dp)
        }
        if (bad.isNotEmpty()) Banner(t(if (wildcard) "letsencrypt.namesInvalid" else "letsencrypt.namesInvalidHttp", "names" to bad.joinToString(", ")), tone = Tone.Warn)
        SettingRow(
            t("letsencrypt.challenge"),
            icon = WebIcons["shield"],
            id = "le-challenge",
            summary = t(if (challenge == "dns-01") "letsencrypt.challengeDnsShort" else "letsencrypt.challengeHttpShort"),
            startOpen = true,
        ) {
            RadioList(
                challenge,
                listOf(
                    Triple("dns-01", t("letsencrypt.challengeDns"), t("letsencrypt.challengeDnsHint")),
                    Triple("http-01", t("letsencrypt.challengeHttp"), t("letsencrypt.challengeHttpHint")),
                ),
                { challenge = it },
                enabled = !run.running,
            )
            if (challenge == "http-01" && !status.bool("httpPort")) Banner(t("letsencrypt.noHttpPort"), tone = Tone.Warn)
            if (challenge == "http-01" && status.bool("httpPort") && status.bool("firewall") && !status.bool("internetHttp")) {
                ActionBox(null, t("letsencrypt.firewallHttp") + "\n" + t("letsencrypt.firewallHttpWays")) {
                    SmallButton(t("letsencrypt.toFirewall"), { env.open("/settings/firewall?tab=policies") })
                    SmallButton(t("letsencrypt.useDns"), { challenge = "dns-01" }, enabled = !run.running)
                }
            }
        }
        if (challenge == "dns-01") {
            DnsProviderFields(provider, { provider = it }, cleared, { cleared = it }, set.o("provider"), status.o("secrets"), "challenge", missing, run.running, "le-provider")
            SettingRow(t("letsencrypt.propagation"), desc = t("letsencrypt.propagationHint"), icon = WebIcons["clock"], control = {
                Field(propagation, { propagation = it.filter(Char::isDigit).take(4) }, t("letsencrypt.propagation"), placeholder = t("letsencrypt.propagationDefault"), mono = true, singleLine = true, enabled = !run.running,
                    keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            })
            if (propBad) Banner(t("letsencrypt.propagationInvalid"), tone = Tone.Warn)
            val domain = status.str("appDomain")
            SettingRow(
                t("letsencrypt.apps"),
                desc = if (domain.isNotEmpty()) t("letsencrypt.appsHint", "wildcard" to "*.$domain") else t("letsencrypt.appsNoDomain"),
                icon = WebIcons["box"],
                control = { Switch(apps, { apps = it }, t("letsencrypt.apps"), enabled = !run.running) },
            )
            if (domain.isEmpty()) LinkText(t("proxy.settingsTitle")) { a.openPage("proxy") }
        }
        val emailTitle = t("letsencrypt.email") + " (" + t("dnsProvider.optional") + ")"
        SettingRow(emailTitle, desc = t("letsencrypt.emailHint"), icon = WebIcons["send"], control = {
            Field(email, { email = it }, emailTitle, singleLine = true, enabled = !run.running,
                keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email))
        })
        SettingRow(t("letsencrypt.staging"), desc = t("letsencrypt.stagingHint"), icon = WebIcons["tool"], control = {
            Switch(staging, { staging = it }, t("letsencrypt.staging"), enabled = !run.running && directory.isBlank())
        })
        val dirTitle = t("letsencrypt.directory") + " (" + t("dnsProvider.optional") + ")"
        SettingRow(dirTitle, desc = t("letsencrypt.directoryHint"), icon = WebIcons["external"]) {
            Field(directory, { directory = it }, dirTitle, Modifier.fillMaxWidth(), placeholder = "https://acme-v02.api.letsencrypt.org/directory", mono = true, singleLine = true, enabled = !run.running)
        }
        Note(saveError)
        run.error?.let { Banner(it, tone = Tone.Error) }
        Buttons {
            SmallButton(t("common.save"), {
                val body = letsEncryptBody(set, enabled, email, staging, domains, apps, challenge, provider, maxOf(prop, 0L), directory, cleared)
                saveError = ""
                saved = false
                run.run(guard = GuardAction.Network) {
                    try {
                        status = api.put("system/letsencrypt", body) as? JsonObject ?: status
                        round++
                        saved = true
                    } catch (e: io.github.ra5on.titanos.net.ApiException) {
                        saveError = tr.errorWithReason(e, setOf("invalid_letsencrypt"), Regex("^letsencrypt: invalid settings: "))
                    }
                }
            }, kind = ButtonKind.Primary, enabled = !run.running && valid)
            if (run.running || saved) Hint(t(if (run.running) "ui.saving" else "ui.saved"))
        }
    }
}
