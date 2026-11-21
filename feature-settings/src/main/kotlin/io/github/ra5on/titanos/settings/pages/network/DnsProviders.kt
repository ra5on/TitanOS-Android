package io.github.ra5on.titanos.settings.pages.network

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject

// DNS-Anbieter für Let's Encrypt (TXT-Einträge der DNS-Prüfung) und
// dynamisches DNS (A/AAAA), wie web/src/lib/dns-providers.ts. Geheimnisse
// kommen nie vom NAS zurück; das Formular schickt eines nur, wenn es
// eingetippt wurde, und nennt verworfene in clearSecrets.

internal class ProviderField(
    val name: String,
    val label: String,
    val secret: Boolean = false,
    val required: Boolean = false,
    val placeholder: String? = null,
    val choices: List<String>? = null,
    val only: String? = null,
    val requiredForChallenge: Boolean = false,
)

internal val TSIG_ALGORITHMS = listOf("hmac-sha256", "hmac-sha512", "hmac-sha384", "hmac-sha224", "hmac-sha1")

internal val PROVIDER_FIELDS: Map<String, List<ProviderField>> = mapOf(
    "cloudflare" to listOf(ProviderField("token", "apiToken", secret = true, required = true)),
    "desec" to listOf(ProviderField("token", "apiToken", secret = true, required = true)),
    "hetzner" to listOf(ProviderField("token", "apiToken", secret = true, required = true)),
    "ionos" to listOf(ProviderField("token", "ionosKey", secret = true, required = true, placeholder = "prefix.secret")),
    "netcup" to listOf(
        ProviderField("customer", "customer", required = true, placeholder = "12345"),
        ProviderField("token", "apiKey", secret = true, required = true),
        ProviderField("password", "apiPassword", secret = true, required = true),
    ),
    "inwx" to listOf(ProviderField("user", "user", required = true), ProviderField("password", "password", secret = true, required = true)),
    "duckdns" to listOf(ProviderField("token", "apiToken", secret = true, required = true)),
    // IPv64.net: der API-Schlüssel des Kontos kann alles, der Update-Token nur DynDNS; für dynamisches DNS reicht einer.
    "ipv64" to listOf(
        ProviderField("token", "ipv64ApiKey", secret = true, requiredForChallenge = true),
        ProviderField("updateToken", "ipv64UpdateToken", secret = true, only = "address"),
        ProviderField("ipv6Prefix", "ipv6Prefix", placeholder = "auto", only = "address"),
    ),
    "rfc2136" to listOf(
        ProviderField("server", "nameServer", required = true, placeholder = "ns1.example.org:53"),
        ProviderField("zone", "zone", required = true, placeholder = "example.org"),
        ProviderField("keyName", "keyName", required = true, placeholder = "titanos"),
        ProviderField("algorithm", "algorithm", required = true, choices = TSIG_ALGORITHMS),
        ProviderField("secret", "tsigSecret", secret = true, required = true),
    ),
    "dyndns2" to listOf(
        ProviderField("server", "updateUrl", required = true, placeholder = "https://dynupdate.no-ip.com/nic/update"),
        ProviderField("user", "user"),
        ProviderField("password", "password", secret = true),
    ),
)

/** Die Anbieter für einen Zweck ("challenge" oder "address"); dyndns2 ändert nur Adressen. */
internal fun providerTypes(purpose: String): List<String> {
    val all = listOf("cloudflare", "desec", "hetzner", "ionos", "netcup", "inwx", "duckdns", "ipv64", "rfc2136")
    return if (purpose == "address") all + "dyndns2" else all
}

internal fun fieldsFor(type: String, purpose: String): List<ProviderField> = PROVIDER_FIELDS[type].orEmpty()
    .filter { it.only == null || it.only == purpose }
    .map { f -> if (f.requiredForChallenge && purpose == "challenge") ProviderField(f.name, f.label, f.secret, true, f.placeholder, f.choices, f.only) else f }

private val ACCOUNT_FIELDS = listOf("customer", "user", "server", "keyName")

/** Ob die gespeicherten Geheimnisse noch zum Konto im Formular gehören. */
internal fun sameAccount(form: JsonObject, stored: JsonObject): Boolean =
    form.str("type") == stored.str("type") && ACCOUNT_FIELDS.all { form.str(it) == stored.str(it) }

/** Pflichtfelder, die leer sind; ein Geheimnis zählt als gefüllt, wenn das NAS eins für dasselbe Konto hat und es nicht verworfen ist. */
internal fun missingFields(form: JsonObject, stored: JsonObject, secrets: JsonObject, cleared: List<String>, purpose: String = "challenge"): List<String> {
    val type = form.str("type")
    if (type.isEmpty()) return emptyList()
    val keep = sameAccount(form, stored)
    fun filled(f: ProviderField): Boolean {
        if (form.str(f.name).trim().isNotEmpty()) return true
        return f.secret && keep && secrets.bool(f.name) && f.name !in cleared
    }
    val fields = fieldsFor(type, purpose)
    val missing = fields.filter { it.required && !filled(it) }.map { it.name }.toMutableList()
    if (type == "ipv64" && purpose == "address" && fields.none { it.secret && filled(it) }) missing += listOf("token", "updateToken")
    return missing
}

/** Was geschickt wird: nur die Felder des gewählten Anbieters, Geheimnisse nur, wenn eingetippt. */
internal fun providerPayload(form: JsonObject, purpose: String = "challenge"): JsonObject {
    val type = form.str("type")
    if (type.isEmpty()) return jobj("type" to "")
    val pairs = mutableListOf<Pair<String, Any?>>("type" to type)
    for (f in fieldsFor(type, purpose)) {
        val v = form.str(f.name).trim()
        if (v.isNotEmpty()) pairs += f.name to v
    }
    return jobj(*pairs.toTypedArray())
}

/** Ein Formular für den gespeicherten Anbieter: Geheimnisse leer, ein TSIG-Verfahren gewählt. */
internal fun providerForm(stored: JsonObject): JsonObject {
    var form = stored.with("type" to stored.str("type"), "token" to "", "password" to "", "secret" to "", "updateToken" to "")
    if (form.str("type") == "rfc2136" && form.str("algorithm").isEmpty()) form = form.with("algorithm" to "hmac-sha256")
    return form
}

/** Namen aus einem Textfeld: einer je Zeile (oder durch Leerzeichen/Kommas getrennt), klein, ohne Punkt am Ende. */
internal fun parseNames(text: String): List<String> = text.split(Regex("[\\s,;]+"))
    .map { it.trim().lowercase().removeSuffix(".") }.filter { it.isNotEmpty() }.distinct()

private val LABEL = Regex("^(?!-)[a-z0-9_-]{1,63}(?<!-)$")

/** Ob ein Hostname taugt; Platzhalter („*.example.org“) nur, wo erlaubt. */
internal fun validHostName(name: String, wildcard: Boolean = false): Boolean {
    var host = name
    if (wildcard && host.startsWith("*.")) host = host.drop(2)
    if (host.length > 253 || !host.contains('.') || host.endsWith(".local")) return false
    return host.split('.').all { LABEL.matches(it) }
}

internal val IPV64_ZONES = listOf("ipv64.net", "ipv64.de", "home64.de", "nas64.de", "dyndns64.de")

internal fun validIpv64Wish(wish: String): Boolean = Regex("^(?!-)[a-z0-9-]{1,63}(?<!-)$").matches(wish)

/**
 * Der DNS-Anbieter und seine Anmeldung (DnsProviderFields.svelte), geteilt von
 * Let's Encrypt und dynamischem DNS: eine Zeile mit der Wahl, die Felder des
 * gewählten Anbieters als ihre Teile. Geheimnisse werden nie angezeigt.
 */
@Composable
internal fun DnsProviderFields(
    form: JsonObject,
    onForm: (JsonObject) -> Unit,
    cleared: List<String>,
    onCleared: (List<String>) -> Unit,
    stored: JsonObject,
    secrets: JsonObject,
    purpose: String,
    missing: List<String>,
    disabled: Boolean,
    id: String,
) {
    val type = form.str("type")
    val fields = if (type.isNotEmpty()) fieldsFor(type, purpose) else emptyList()
    val keeps = sameAccount(form, stored)
    fun kept(name: String) = keeps && secrets.bool(name) && name !in cleared
    val control: @Composable () -> Unit = {
        Select(
            type,
            (if (type.isEmpty()) listOf("" to t("dnsProvider.choose")) else emptyList()) + providerTypes(purpose).map { it to t("dnsProvider.type.$it") },
            { onForm(form.with("type" to it)) },
            t("dnsProvider.title"),
            enabled = !disabled,
        )
    }
    if (type.isEmpty()) {
        SettingRow(t("dnsProvider.title"), icon = WebIcons["globe"], id = "$id-row", control = control)
        return
    }
    SettingRow(t("dnsProvider.title"), icon = WebIcons["globe"], id = "$id-row", startOpen = true, control = control) {
        Hint(t("dnsProvider.help.$type"))
        for (f in fields) {
            val title = t("dnsProvider.field.${f.label}") + if (f.required) "" else " (${t("dnsProvider.optional")})"
            SettingSub(title, desc = if (f.secret && kept(f.name)) t("dnsProvider.secretKept") else null, control = {
                when {
                    f.choices != null -> Select(form.str(f.name), f.choices.map { it to it }, { onForm(form.with(f.name to it)) }, title, enabled = !disabled)
                    else -> Field(form.str(f.name), { onForm(form.with(f.name to it)) }, title, placeholder = f.placeholder, password = f.secret, mono = true, singleLine = true, enabled = !disabled)
                }
            })
            if (f.secret && kept(f.name) && !f.required) {
                SmallButton(t("dnsProvider.secretDrop"), { onCleared(cleared + f.name) }, enabled = !disabled)
            }
        }
        if (type == "dyndns2") Hint(t("dnsProvider.dyndns2Placeholders"))
        if (type == "ipv64") Hint(t(if (purpose == "address") "dnsProvider.ipv64KeysAddress" else "dnsProvider.ipv64KeysChallenge"))
        if (!keeps && secrets.values.any { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" }) Hint(t("dnsProvider.secretsNotMoved"))
        if (missing.isNotEmpty()) Banner(t("dnsProvider.missing"), tone = Tone.Warn)
    }
}
