package io.github.ra5on.titanos.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Was ein Browser von sich sagt, auf das gekürzt, was man wiedererkennt (`browser()` im Web). */
internal fun browserName(tr: Tr, agent: String): String {
    Regex("""^TitanOS-Android/(\w+) (.+)$""").find(agent)?.let { m ->
        return tr.t(if (m.groupValues[1] == "agent") "androidApps.sessionAgent" else "androidApps.sessionTitanos", "name" to m.groupValues[2])
    }
    val name = Regex("""Firefox/|Edg/|Chrome/|Safari/""").find(agent)?.value?.removeSuffix("/").orEmpty()
    val system = Regex("""Windows|Android|iPhone|iPad|Mac OS X|Linux""").find(agent)?.value.orEmpty()
    return listOf(if (name == "Edg") "Edge" else name, system).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { agent.ifEmpty { "–" } }
}

/** Der Fingerabdruck in Vierergruppen, wie die App ihn zeigt (`groups()` im Web). */
internal fun spkiGroups(spki: String): String = runCatching {
    val bytes = java.util.Base64.getUrlDecoder().decode(spki.replace('+', '-').replace('/', '_').trimEnd('='))
    bytes.take(16).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
}.getOrDefault("")

/** Angemeldete Geräte: jede Sitzung, einzeln abmelden, „Alle anderen abmelden“, dazu die Android-Apps. */
@Composable
internal fun DevicesGroup(ctx: AccountCtx) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val run = rememberRunner()
    val state = ctx.sessions
    val sessions = state.value.a("sessions").objects()
    val others = sessions.count { !it.bool("current") }
    SettingGroup(
        t("account.devices"),
        tools = if (others > 0) ({
            SmallButton(t("account.endOthers"), {
                ov.show { close -> EndOthersDialog(ctx, others, close) }
            })
        }) else null,
    ) {
        when {
            state.error != null && state.value == null -> Banner(tr.errorOf(state.error!!), tone = Tone.Error, action = t("common.retry"), onAction = { state.reload() })
            state.value == null -> LoadingLine(t("common.loading"))
            else -> for (d in sessions) {
                val title = browserName(tr, d.str("userAgent"))
                val desc = buildString {
                    append(d.str("remoteIP"))
                    append(" · ").append(t("account.since", "time" to Fmt.dateTime(tr, d.str("created"))))
                    append(" · ").append(t("account.lastSeen", "time" to Fmt.dateTime(tr, d.str("lastSeen"))))
                    if (d.bool("kept")) {
                        append(" · ")
                        append(if (d.str("until").isNotEmpty()) t("account.keptUntil", "time" to Fmt.dateTime(tr, d.str("until"))) else t("account.keptForever"))
                    }
                }
                SettingRow(
                    title,
                    desc = desc,
                    icon = WebIcons["monitor"],
                    badge = if (d.bool("current")) t("account.thisDevice") else null,
                    id = "acc-session-${d.str("id")}",
                    control = if (d.bool("current")) null else ({
                        SmallButton(t("account.end"), {
                            run.run(guard = GuardAction.AccountSecurity) { state.value = ctx.calls.endSession(d.str("id")) }
                        }, enabled = !run.running)
                    }),
                )
            }
        }
        RunnerState(run)
        Muted(t("account.devicesNote"), Modifier.padding(horizontal = 2.dp, vertical = 4.dp))
        AndroidAppsRow(ctx)
    }
}

@Composable
private fun BoxScope.EndOthersDialog(ctx: AccountCtx, count: Int, close: () -> Unit) {
    val run = rememberRunner()
    AccountDialog(
        t("account.endOthersTitle"),
        onDismiss = close,
        locked = run.running,
        text = t("account.endOthersText", "count" to count),
        actions = listOf(
            DialogAction(t("account.endOthersSubmit"), danger = true) {
                if (!run.running) run.run(guard = GuardAction.AccountSecurity) {
                    ctx.sessions.value = ctx.calls.endOthers()
                    close()
                }
            },
            DialogAction(t("common.cancel")) { if (!run.running) close() },
        ),
    ) { RunnerState(run) }
}

/**
 * Android-Apps (AndroidApps.svelte): die gekoppelten Apps mit „Beenden“ und
 * das Koppeln eines weiteren Handys mit QR-Code oder Code von Hand; bestätigt
 * wird hier mit Passwort (und Code). Die Routen nehmen jede Sitzung des
 * Kontos an, auch die dieser App (nur API-Tokens und der Agent nicht).
 */
@Composable
internal fun AndroidAppsRow(ctx: AccountCtx) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val state = rememberLoad { ctx.calls.apps() }
    val run = rememberRunner()
    // Die App dieser Sitzung: ihre Sitzung trägt die Kennung der App (`device`).
    val mine = ctx.sessions.value.a("sessions").objects().firstOrNull { it.bool("current") }?.str("device").orEmpty()
    val apps = state.value.a("apps").objects()
    val appName: (String) -> String = { tr.t(if (it == "agent") "androidApps.appAgent" else "androidApps.appTitanos") }
    SettingRow(
        t("androidApps.title"),
        desc = t("androidApps.intro"),
        icon = WebIcons["monitor"],
        id = "acc-apps",
        control = {
            SmallButton(t("androidApps.pair"), {
                ov.show { close -> PairDialog(ctx, close, appName) { state.reload(); ctx.sessions.reload() } }
            }, kind = ButtonKind.Primary)
        },
    ) {
        state.error?.let { Banner(tr.errorOf(it), tone = Tone.Error, action = t("common.retry"), onAction = { state.reload() }) }
        if (state.value == null && state.error == null) LoadingLine(t("common.loading"))
        if (state.value != null && apps.isEmpty()) Block { Muted(t("androidApps.none")) }
        for (app in apps) {
            val name = app.str("name")
            val desc = buildString {
                append(appName(app.str("app")))
                append(" · ").append(t("androidApps.since", "time" to Fmt.dateTime(tr, app.str("created"))))
                append(" · ")
                append(if (app.str("expires").isNotEmpty()) t("androidApps.until", "time" to Fmt.dateTime(tr, app.str("expires"))) else t("androidApps.forever"))
            }
            SettingSub(name, desc = desc, control = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (app.str("id") == mine && mine.isNotEmpty()) Badge(t("account.thisDevice"), Tone.Ok)
                    SmallButton(t("androidApps.end"), {
                        ov.confirm(tr.t("androidApps.endTitle", "name" to name), tr.t("androidApps.endText"), action = tr.t("androidApps.end"), cancel = tr.t("common.cancel")) {
                            run.run(guard = GuardAction.AccountSecurity) {
                                ctx.calls.endApp(app.str("id"))
                                state.refresh()
                                ctx.sessions.refresh()
                            }
                        }
                    }, enabled = !run.running)
                }
            })
        }
        RunnerState(run)
    }
}

@Composable
private fun BoxScope.PairDialog(ctx: AccountCtx, close: () -> Unit, appName: (String) -> String, changed: () -> Unit) {
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val run = rememberRunner()
    var pairing by remember { mutableStateOf<JsonObject?>(null) }
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(0) }
    val stateName = status.str("state")

    LaunchedEffect(round) {
        problem = null
        status = null
        manual = false
        password = ""; code = ""
        try {
            pairing = ctx.calls.pairStart()
            status = kotlinx.serialization.json.buildJsonObject { put("state", kotlinx.serialization.json.JsonPrimitive("waiting")) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = tr.errorOf(e)
        }
    }
    // Alle 2 Sekunden fragen, bis die App den Code nimmt oder er abläuft.
    LaunchedEffect(pairing, stateName) {
        val id = pairing.str("id")
        while (id.isNotEmpty() && (stateName == "waiting" || stateName == "claimed")) {
            delay(2000)
            try {
                val next = ctx.calls.pairStatus(id)
                if (next.str("state") != stateName) { status = next; break }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = kotlinx.serialization.json.buildJsonObject { put("state", kotlinx.serialization.json.JsonPrimitive("expired")) }
                if (!(e is ApiException && e.status == 404)) problem = tr.errorOf(e)
                break
            }
        }
    }
    val finish = {
        val id = pairing.str("id")
        val open = stateName == "waiting" || stateName == "claimed"
        close()
        if (open && id.isNotEmpty()) scope.launch { runCatching { ctx.calls.pairCancel(id) } }
    }
    val actions = when (stateName) {
        "claimed" -> listOf(
            DialogAction(t("androidApps.reject")) {
                if (!run.running) run.run { ctx.calls.pairReject(pairing.str("id")); status = JsonObject(status.orEmptyObj() + ("state" to kotlinx.serialization.json.JsonPrimitive("rejected"))) }
            },
            DialogAction(t("androidApps.confirm")) {
                if (!run.running && reconfirmComplete(ctx.twoFactor, password, code)) run.run(guard = GuardAction.AccountSecurity) {
                    try {
                        ctx.calls.pairConfirm(pairing.str("id"), password, code)
                        status = JsonObject(status.orEmptyObj() + ("state" to kotlinx.serialization.json.JsonPrimitive("confirmed")))
                        password = ""; code = ""
                        changed()
                    } catch (e: Exception) {
                        if (e.invalidCode()) code = ""
                        throw e
                    }
                }
            },
        )
        "confirmed" -> listOf(DialogAction(t("common.done")) { close() })
        "rejected", "expired" -> listOf(DialogAction(t("common.cancel")) { close() }, DialogAction(t("androidApps.again")) { round++ })
        else -> listOf(DialogAction(t("common.cancel")) { finish() })
    }
    AccountDialog(t("androidApps.pairTitle"), onDismiss = { finish() }, locked = run.running, actions = actions) {
        val p = pairing
        when (stateName) {
            "claimed" -> {
                Text(t("androidApps.asks", "name" to status.str("name")), style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.text)
                Muted(t("androidApps.asksHint", "app" to appName(status.str("app"))))
                ReconfirmFields(ctx.twoFactor, password, { password = it }, code, { code = it })
            }
            "confirmed" -> Text(t("androidApps.confirmed", "name" to status.str("name")), style = TitanType.body, color = Titan.colors.text)
            "rejected", "expired" -> Text(t(if (stateName == "rejected") "androidApps.rejected" else "androidApps.expired"), style = TitanType.body, color = Titan.colors.text)
            else -> if (p == null) {
                if (problem == null) LoadingLine(t("common.loading"))
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Muted(stringResource(R.string.acc_pair_scan))
                    QrImage(p.str("link"), t("androidApps.qrLabel"), size = 220.dp)
                    Muted(t("androidApps.validity"))
                    SmallButton(t("androidApps.withoutQr"), { manual = !manual })
                }
                if (manual) {
                    Muted(t("androidApps.manualHint"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(t("androidApps.manualCode"), style = TitanType.body, color = Titan.colors.text)
                        MonoText(p.str("manualCode"), big = true)
                    }
                    for (address in p.a("addresses").objects()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            MonoText(address.str("server"))
                            MonoText(
                                when {
                                    address.str("certificate") == "valid" -> t("androidApps.publicCert")
                                    address.str("spki").isNotEmpty() -> spkiGroups(address.str("spki"))
                                    else -> "–"
                                },
                                color = Titan.colors.muted,
                            )
                        }
                    }
                }
            }
        }
        problem?.let { Banner(it, tone = Tone.Error) }
        RunnerState(run)
    }
}

private fun JsonObject?.orEmptyObj(): JsonObject = this ?: JsonObject(emptyMap())
