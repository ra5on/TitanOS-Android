package io.github.ra5on.titanos.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Anteil der vergangenen Wartezeit (`waited` in features/protect.ts). */
internal fun waitedPart(req: JsonObject, now: Instant): Float {
    val from = Fmt.instant(req.str("created"))?.toEpochMilli() ?: return 0f
    val to = Fmt.instant(req.str("due"))?.toEpochMilli() ?: return 1f
    if (to <= from) return 1f
    return ((now.toEpochMilli() - from).toFloat() / (to - from)).coerceIn(0f, 1f)
}

private fun dueText(tr: Tr, at: String): String {
    val i = Fmt.instant(at) ?: return "–"
    val day = DateTimeFormatter.ofPattern("EEE d.M.", Fmt.locale(tr)).withZone(ZoneId.systemDefault()).format(i)
    return "$day " + tr.t("agent.f.protect.e.at", "time" to Fmt.time(tr, i))
}

private fun dayText(tr: Tr, at: String, now: Instant): String {
    val i = Fmt.instant(at) ?: return "–"
    val zone = ZoneId.systemDefault()
    return if (i.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) "${tr.t("agent.f.protect.today")} ${Fmt.time(tr, i)}" else Fmt.dateTime(tr, i)
}

/**
 * Notfallzugang (ProtectEmergency.svelte) – nur, wenn das Konto dafür
 * eingetragen ist (`/agent/protect/me`): beantragen mit Grund (und Einmalcode),
 * Wartezeit, Zurückziehen; Besitzer und Administratoren sehen dazu Anträge,
 * Freigaben und eingetragene Personen.
 */
@Composable
internal fun EmergencyGroup(ctx: AccountCtx) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val run = rememberRunner()
    var me by remember { mutableStateOf<JsonObject?>(null) }
    var view by remember { mutableStateOf<JsonObject?>(null) }
    var reason by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(Instant.now()) }
    var round by remember { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(round) {
        try {
            me = ctx.calls.protectMe()
            if (ctx.admin && me.bool("emergency")) view = ctx.calls.emergency()
            problem = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (me != null) problem = tr.errorOf(e)
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    val m = me ?: return
    if (!m.bool("emergency")) return

    @Composable
    fun progress(r: JsonObject) {
        val part = waitedPart(r, now)
        val label = t("agent.f.protect.e.waited", "percent" to (part * 100).toInt())
        LinearProgressIndicator(progress = { part }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = label }, color = Titan.colors.accent, trackColor = Titan.colors.track)
        Muted(label)
    }
    val act: (JsonObject, String) -> Unit = { r, action ->
        run.run { ctx.calls.emergencyAction(r.str("id"), action); round++ }
    }
    fun ask(r: JsonObject, action: String) {
        val shares = (if (action == "end" && r.strings("added").isNotEmpty()) r.strings("added") else r.strings("shares")).joinToString(", ")
        val name = r.str("account")
        ov.confirm(
            if (action == "grant") tr.t("agent.f.protect.e.grantNow") else tr.t(if (action == "decline") "agent.f.protect.e.declineTitle" else "agent.f.protect.e.endTitle", "name" to name),
            tr.t(when (action) { "grant" -> "agent.f.protect.e.grantConfirm"; "decline" -> "agent.f.protect.e.declineConfirm"; else -> "agent.f.protect.e.endConfirm" }, "name" to name, "shares" to shares),
            action = tr.t(when (action) { "grant" -> "agent.f.protect.e.grantNow"; "decline" -> "agent.f.protect.e.decline"; else -> "agent.f.protect.e.endNow" }),
            cancel = tr.t("common.cancel"),
            danger = action != "grant",
        ) { act(r, action) }
    }

    SettingGroup(t("agent.f.menu.emergency")) {
        problem?.let { Banner(it, tone = Tone.Error) }
        val v = view
        if (ctx.admin && v != null) {
            if (!v.bool("enabled")) Muted(t("agent.f.notThere"))
            val requests = v.a("requests").objects()
            for (r in requests.filter { it.str("state") == "waiting" }) {
                SettingRow(t("agent.f.protect.e.asks", "name" to r.str("account")), icon = WebIcons["key"], desc = dayText(tr, r.str("created"), now) + " · " + t("agent.f.protect.e.reason", "reason" to r.str("reason").ifEmpty { "–" })) {
                    Block {
                        Text(
                            t("agent.f.protect.e.without", "when" to dueText(tr, r.str("due"))) + " " + r.strings("shares").joinToString(", ") + " " + t("agent.f.protect.e.andBook"),
                            style = TitanType.body, color = Titan.colors.text,
                        )
                        progress(r)
                        if (r.str("account") != ctx.name) Buttons {
                            SmallButton(t("agent.f.protect.e.decline"), { ask(r, "decline") }, kind = ButtonKind.Danger, enabled = !run.running)
                            SmallButton(t("agent.f.protect.e.grantNow"), { ask(r, "grant") }, enabled = !run.running)
                        }
                    }
                }
            }
            for (r in requests.filter { it.str("state") == "granted" }) {
                val shares = (if (r.strings("added").isNotEmpty()) r.strings("added") else r.strings("shares")).joinToString(", ")
                SettingRow(
                    t("agent.f.protect.e.active", "name" to r.str("account")),
                    icon = WebIcons["unlock"],
                    desc = t("agent.f.protect.e.activeText", "shares" to shares, "until" to (Fmt.instant(r.str("ends"))?.let { Fmt.date(tr, it) } ?: "–")),
                    control = { SmallButton(t("agent.f.protect.e.end"), { ask(r, "end") }, enabled = !run.running) },
                )
            }
            Text(t("agent.f.protect.e.persons"), style = TitanType.rowSub.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.text, modifier = Modifier.padding(top = 8.dp))
            val persons = v.a("persons").objects()
            if (persons.isEmpty()) Muted(t("agent.f.protect.e.noPersons"))
            for (p in persons) {
                SettingRow(
                    p.str("account"),
                    icon = WebIcons["person"],
                    desc = t("agent.f.protect.e.personSub", "account" to p.str("account"), "days" to p.int("waitDays")) + " · " + p.strings("shares").joinToString(", "),
                    control = if (!v.bool("code")) null else ({
                        SmallButton(t(if (p.bool("codeSet")) "agent.f.protect.e.newCode" else "agent.f.protect.e.printCode"), {
                            run.run {
                                val c = ctx.calls.emergencyCode(p.str("account"))
                                round++
                                ov.show { close -> CodeDialog(p.str("account"), c, close) }
                            }
                        }, enabled = !run.running)
                    }),
                )
            }
            Muted(t("agent.f.protect.e.footer"))
            val history = requests.filter { it.str("state") != "waiting" && it.str("state") != "granted" }.take(10)
            if (history.isNotEmpty()) {
                Text(t("agent.f.protect.e.history"), style = TitanType.rowSub.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.text, modifier = Modifier.padding(top = 8.dp))
                for (r in history) {
                    SettingRow(
                        r.str("account") + " · " + dayText(tr, r.str("created"), now),
                        desc = t("agent.f.protect.e.state.${r.str("state")}") + if (r.str("by").isNotEmpty()) " · ${r.str("by")}" else "",
                    )
                }
            }
        } else {
            val mine = m["request"] as? JsonObject
            when (mine?.str("state")) {
                "waiting" -> SettingRow(t("agent.f.protect.e.mineWaiting"), icon = WebIcons["key"], desc = t("agent.f.protect.e.mineWaitingText", "when" to dueText(tr, mine.str("due"))), startOpen = true, id = "em-waiting") {
                    Block {
                        progress(mine)
                        Buttons { SmallButton(t("agent.f.protect.e.withdraw"), { act(mine, "cancel") }, enabled = !run.running) }
                    }
                }
                "granted" -> SettingRow(
                    t("agent.f.protect.e.mineGranted"),
                    icon = WebIcons["unlock"],
                    desc = t("agent.f.protect.e.mineGrantedText", "shares" to mine.strings("shares").joinToString(", "), "until" to (Fmt.instant(mine.str("ends"))?.let { Fmt.date(tr, it) } ?: "–")),
                )
                else -> {
                    if (mine?.str("state") == "declined") Banner(t("agent.f.protect.e.mineDeclined"), tone = Tone.Error)
                    val person = m.o("person")
                    SettingRow(t("agent.f.protect.e.rowAsk"), icon = WebIcons["key"], desc = t("agent.f.protect.e.explain", "days" to person.int("waitDays", 3), "shares" to person.strings("shares").joinToString(", ")), startOpen = true, id = "em-ask") {
                        Block {
                            Labeled(t("agent.f.protect.e.reasonLabel")) {
                                Field(reason, { if (it.length <= 500) reason = it }, t("agent.f.protect.e.reasonLabel"), Modifier.fillMaxWidth(), minHeight = 80.dp)
                            }
                            if (m.bool("codes")) Labeled(t("agent.f.protect.e.codeLabel")) {
                                Field(code, { code = it }, t("agent.f.protect.e.codeLabel"), Modifier.fillMaxWidth(), singleLine = true)
                            }
                            Buttons {
                                SmallButton(t("agent.f.protect.e.ask"), {
                                    run.run {
                                        ctx.calls.askEmergency(reason.trim(), code.trim())
                                        reason = ""; code = ""
                                        round++
                                    }
                                }, kind = ButtonKind.Primary, enabled = !run.running && reason.isNotBlank())
                            }
                        }
                    }
                }
            }
            Muted(t("agent.f.protect.e.footer"), Modifier.padding(top = 4.dp))
        }
        RunnerState(run)
    }
}

/** Der Einmalcode einer Person: nur jetzt sichtbar, zum Abschreiben oder Kopieren. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CodeDialog(account: String, code: String, close: () -> Unit) {
    val clip = LocalClipboardManager.current
    AccountDialog(
        t("agent.f.protect.e.codeTitle", "name" to account),
        onDismiss = close,
        text = t("agent.f.protect.e.codeText"),
        actions = listOf(
            DialogAction(t("account.recoveryCopy")) { clip.setText(AnnotatedString(code)) },
            DialogAction(t("wg.done")) { close() },
        ),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) { MonoText(code, big = true) }
    }
}
