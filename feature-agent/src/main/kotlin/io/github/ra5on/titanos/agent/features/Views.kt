package io.github.ra5on.titanos.agent.features

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.dateTime
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.bool
import io.github.ra5on.titanos.agent.core.int
import io.github.ra5on.titanos.agent.core.jsonOf
import io.github.ra5on.titanos.agent.core.long
import io.github.ra5on.titanos.agent.core.num
import io.github.ra5on.titanos.agent.core.o
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.objs
import io.github.ra5on.titanos.agent.core.os
import io.github.ra5on.titanos.agent.core.str
import io.github.ra5on.titanos.agent.core.strs
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.pages.dateOnly
import io.github.ra5on.titanos.agent.ui.AgentDialog
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.Buttons
import io.github.ra5on.titanos.agent.ui.Check
import io.github.ra5on.titanos.agent.ui.DateField
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.SGroup
import io.github.ra5on.titanos.agent.ui.SRow
import io.github.ra5on.titanos.agent.ui.Select
import io.github.ra5on.titanos.agent.ui.Tag
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale

private val PAPER_TYPES = listOf("energy", "telecom", "insurance", "shopping", "health", "housing", "tax", "other")

/** Druckt einen kleinen Zettel über den Druckdienst von Android (window.print() im Web). */
fun printSheet(context: Context, job: String, html: String) {
    val web = WebView(context)
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val pm = context.getSystemService(PrintManager::class.java) ?: return
            pm.print(job, view.createPrintDocumentAdapter(job), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        }
    }
    web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Papierkram: Monat, nach Art, offen (bezahlt), zuletzt (rückgängig, zuordnen), jetzt nachsehen. */
@Composable
internal fun PaperView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var month by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var assigning by remember { mutableStateOf<JsonObject?>(null) }
    var reload by remember { mutableStateOf(0) }
    Box {
        Loaded(model, "agent/paper" + if (month.isNotEmpty()) "?month=$month" else "", reload) { s, re ->
            err?.let { item { Notice(it, tone = "error") } }
            item {
                val months = s.strs("months")
                if (months.isNotEmpty()) Select(months.map { it to it }, s.str("month"), { month = it }, tr.t("agent.f.docs.month"))
            }
            item {
                SGroup(tr.t("agent.f.docs.spentIn", "month" to s.str("month"))) {
                    Text(euro(s.long("total"), tr.de), style = AgentText.pageTitle, color = c.text)
                    Text(tr.t("agent.f.docs.bills", "count" to s.int("count")), style = AgentText.sm, color = c.muted)
                }
            }
            item {
                SGroup(tr.t("agent.f.docs.byType")) {
                    val types = s.os("types")
                    if (types.isEmpty()) Text(tr.t("agent.f.docs.noneMonth"), style = AgentText.md, color = c.muted)
                    for (t in types) SRow(tr.t("agent.f.docs.type.${t.str("type")}"), desc = tr.t("agent.f.docs.bills", "count" to t.int("count")) + " · " + t.strs("senders").joinToString(", "), summary = euro(t.long("cents"), tr.de))
                }
            }
            item {
                SGroup(tr.t("agent.f.docs.open")) {
                    val open = s.os("open")
                    if (open.isEmpty()) Text(tr.t("agent.f.docs.noneOpen"), style = AgentText.md, color = c.muted)
                    for (e in open) SRow(e.str("sender").ifEmpty { e.str("name") }, desc = listOfNotNull(euro(e.long("cents"), tr.de), e.str("due").takeIf { it.isNotEmpty() }?.let { tr.t("agent.f.docs.dueOn", "date" to dateOnly(it + "T00:00:00Z")) }).joinToString(" · "), icon = "fileText", control = {
                        PillButton(tr.t("agent.f.docs.paid"), { act(model, scope, tr, { err = it }, re) { model.api.sendJson("POST", "agent/paper/${e.str("id")}/paid", jsonOf("paid" to true)) } }, small = true)
                    })
                }
            }
            item {
                SGroup(tr.t("agent.f.docs.recent")) {
                    val recent = s.os("recent")
                    if (recent.isEmpty()) Text(tr.t("agent.f.docs.noneRecent"), style = AgentText.md, color = c.muted)
                    for (e in recent) SRow(
                        e.str("name"),
                        desc = listOf(e.str("sender"), tr.t("agent.f.docs.type.${e.str("type")}"), dateTime(e.str("at"))).filter { it.isNotEmpty() }.joinToString(" · "),
                        icon = "fileText",
                        badge = { if (e.str("state") == "ask" || e.a("doubt").isNotEmpty()) Tag(tr.t("agent.f.docs.unsure"), tone = "warn") },
                        control = {
                            Buttons {
                                PillButton(tr.t("agent.f.docs.assign"), { assigning = e }, kind = if (e.str("state") == "ask") Btn.Primary else Btn.Normal, small = true)
                                PillButton(tr.t("agent.f.docs.undo"), { act(model, scope, tr, { err = it }, re) { model.api.sendJson("POST", "agent/paper/${e.str("id")}/undo", JsonObject(emptyMap())) } }, small = true)
                            }
                        },
                    )
                }
            }
            item { PillButton(tr.t("agent.f.docs.lookNow"), { act(model, scope, tr, { err = it }, re) { model.api.sendJson("POST", "agent/paper/run") } }, small = true) }
        }
        val a = assigning
        var sender by remember(a) { mutableStateOf(a?.str("sender").orEmpty()) }
        var type by remember(a) { mutableStateOf(a?.let { it.strs("doubt").firstOrNull() ?: it.str("type") }?.ifEmpty { "other" } ?: "other") }
        var kind by remember(a) { mutableStateOf(a?.str("kind").orEmpty()) }
        var date by remember(a) { mutableStateOf(a?.str("due").orEmpty()) }
        AgentDialog(a != null, tr.t("agent.f.docs.assignTitle", "name" to (a?.str("name") ?: "")), { assigning = null }, actions = {
            PillButton(tr.t("common.cancel"), { assigning = null })
            PillButton(tr.t("agent.f.docs.file"), {
                val id = a?.str("id") ?: return@PillButton
                assigning = null
                act(model, scope, tr, { err = it }, { reload++ }) { model.api.sendJson("POST", "agent/paper/$id/assign", jsonOf("sender" to sender.trim(), "type" to type, "kind" to kind.trim().ifEmpty { null }, "date" to date.ifEmpty { null })) }
            }, kind = Btn.Primary, enabled = sender.isNotBlank())
        }) {
            Field(sender, { sender = it }, label = tr.t("agent.f.docs.sender"), placeholder = tr.t("agent.f.docs.sender"))
            Select(PAPER_TYPES.map { it to tr.t("agent.f.docs.type.$it") }, type, { type = it }, tr.t("agent.f.docs.typeLabel"))
            Field(kind, { kind = it }, label = tr.t("agent.f.docs.kind"), placeholder = tr.t("agent.f.docs.kind"))
            Text(tr.t("agent.f.docs.kindHint"), style = AgentText.sm, color = c.muted)
            DateField(date, { date = it }, tr.t("agent.f.docs.date"))
        }
    }
}

private fun JsonObject.a(key: String) = this[key].objs().let { if (it.isEmpty()) strs(key) else it.map { o -> o.toString() } }

/** Notfallhandbuch: Stand, neu erstellen, als PDF oder Markdown speichern, die Abschnitte und Fakten. */
@Composable
internal fun HandbookView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    var format by remember { mutableStateOf("pdf") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (bytes, _) = model.api.bytes("agent/protect/handbook/download", listOf("lang" to tr.lang, "format" to format))
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                model.say(tr.t("agent.exported"))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { err = problemText(tr, problemOf(e)) }
        }
    }
    val name = if (tr.de) "Notfallhandbuch" else "Emergency handbook"
    Loaded(model, "agent/protect/handbook?lang=" + tr.lang) { h, reload ->
        err?.let { item { Notice(it, tone = "error") } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr.t("agent.f.protect.h.made", "when" to dateTime(h.str("made"))), style = AgentText.sm, color = c.muted)
                Buttons {
                    PillButton("PDF", { format = "pdf"; save.launch("$name.pdf") }, icon = "download", kind = Btn.Primary, small = true)
                    PillButton("Markdown", { format = "md"; save.launch("$name.md") }, icon = "download", small = true)
                    if (model.status?.admin == true) PillButton(tr.t("agent.f.protect.h.refresh"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/protect/handbook/refresh") } }, small = true)
                }
            }
        }
        val vols = h["volumes"].objs()
        if (vols.isNotEmpty()) item { SGroup(tr.t("agent.f.protect.h.build")) { for (v in vols) SRow(tr.t("agent.f.protect.h.volume", "name" to v.str("name")), desc = v.str("line")) } }
        for (s in h.os("sections")) item {
            SRow(s.str("title"), desc = s.str("sub"), icon = "fileText") {
                s.strs("steps").forEachIndexed { i, step -> Text("${i + 1}. $step", style = AgentText.md, color = c.text) }
            }
        }
        val facts = h.o("facts")
        for ((k, label) in listOf("shares" to "agent.f.protect.h.shares", "apps" to "agent.f.protect.h.apps", "network" to "agent.f.protect.h.network", "remote" to "agent.f.protect.h.remote", "backups" to "agent.f.protect.h.backups", "probes" to "agent.f.protect.h.probes")) {
            val list = facts[k].objs()
            if (list.isNotEmpty()) item { SGroup(tr.t(label)) { for (f in list) SRow(f.str("name"), desc = f.str("detail").ifEmpty { null }) } }
        }
        val contacts = facts["contacts"].objs()
        if (contacts.isNotEmpty()) item { SGroup(tr.t("agent.f.protect.h.contacts")) { for (ct in contacts) SRow(ct.str("name"), desc = listOf(ct.str("phone"), ct.str("note")).filter { it.isNotEmpty() }.joinToString(" · ")) } }
    }
}

/** Notfallzugang: Personen mit Code (drucken), Anfragen entscheiden; als Person: anfragen und zurückziehen. */
@Composable
internal fun EmergencyView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var printed by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirm by remember { mutableStateOf<Pair<JsonObject, String>?>(null) }
    var reload by remember { mutableStateOf(0) }
    val admin = model.status?.admin == true
    Box {
        Loaded(model, if (admin) "agent/protect/emergency" else "agent/protect/me", reload) { v, re ->
            err?.let { item { Notice(it, tone = "error") } }
            if (admin) {
                item {
                    SGroup(tr.t("agent.f.protect.e.persons")) {
                        val persons = v.os("persons")
                        if (persons.isEmpty()) Text(tr.t("agent.f.protect.e.noPersons"), style = AgentText.md, color = c.muted)
                        for (p in persons) SRow(p.str("account"), desc = tr.t("agent.f.protect.e.personSub", "account" to p.str("account"), "days" to p.int("waitDays")) + p.strs("shares").takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " · ").orEmpty(), icon = "user", control = {
                            if (v.bool("code")) PillButton(if (p.bool("codeSet")) tr.t("agent.f.protect.e.newCode") else tr.t("agent.f.protect.e.printCode"), {
                                act(model, scope, tr, { err = it }, re) { printed = p.str("account") to model.api.sendJson("POST", "agent/protect/emergency/code/${p.str("account")}").obj().str("code") }
                            }, small = true)
                        })
                    }
                }
                item {
                    val reqs = v.os("requests")
                    val waiting = reqs.count { it.str("state") == "waiting" }
                    SGroup(if (waiting == 1) tr.t("agent.f.protect.e.openRequest") else tr.t("agent.f.protect.e.openRequests", "count" to waiting)) {
                        if (reqs.isEmpty()) Text("–", style = AgentText.md, color = c.muted)
                        for (r in reqs) SRow(r.str("account"), desc = listOf(tr.t("agent.f.protect.e.state.${r.str("state")}"), dateTime(r.str("created")), tr.t("agent.f.protect.e.reason", "reason" to r.str("reason").ifEmpty { "–" }), r.str("due").takeIf { r.str("state") == "waiting" }?.let { tr.t("agent.f.protect.e.without", "when" to dateTime(it)) + " " + r.strs("shares").joinToString(", ") }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "), icon = "key", control = {
                            Buttons {
                                if (r.str("state") == "waiting") {
                                    PillButton(tr.t("agent.f.protect.e.grantNow"), { confirm = r to "grant" }, kind = Btn.Primary, small = true)
                                    PillButton(tr.t("agent.f.protect.e.decline"), { confirm = r to "decline" }, small = true)
                                }
                                if (r.str("state") == "granted") PillButton(tr.t("agent.f.protect.e.endNow"), { confirm = r to "end" }, kind = Btn.Danger, small = true)
                            }
                        })
                    }
                }
            } else {
                val req = v.o("request")
                item {
                    when {
                        req.isNotEmpty() -> SRow(tr.t("agent.f.protect.e.state.${req.str("state")}"), desc = req.str("reason") + " · " + dateTime(req.str("due")), icon = "key", control = {
                            if (req.str("state") == "waiting") PillButton(tr.t("agent.f.protect.e.withdraw"), { act(model, scope, tr, { err = it }, re) { model.api.sendJson("POST", "agent/protect/emergency/${req.str("id")}/cancel") } }, small = true)
                        })
                        v.o("person").isEmpty() -> Text(tr.t("agent.f.protect.e.notRegistered"), style = AgentText.md, color = c.muted)
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val person = v.o("person")
                            Text(tr.t("agent.f.protect.e.explain", "days" to (person["waitDays"]?.let { person.int("waitDays") } ?: 3), "shares" to person.strs("shares").joinToString(", ")), style = AgentText.sm, color = c.muted)
                            Field(reason, { reason = it }, multiline = true, label = tr.t("agent.f.protect.e.reasonLabel"), placeholder = tr.t("agent.f.protect.e.reasonLabel"))
                            if (v.bool("codes")) Field(code, { code = it }, label = tr.t("agent.f.protect.e.codeLabel"), placeholder = tr.t("agent.f.protect.e.codeLabel"))
                            PillButton(tr.t("agent.f.protect.e.ask"), { act(model, scope, tr, { err = it }, re) { model.api.sendJson("POST", "agent/protect/emergency/request", jsonOf("reason" to reason, "code" to code)) } }, kind = Btn.Primary, enabled = reason.isNotBlank())
                        }
                    }
                }
            }
        }
        val pr = printed
        AgentDialog(pr != null, tr.t("agent.f.protect.e.codeTitle", "name" to (pr?.first ?: "")), { printed = null }, text = tr.t("agent.f.protect.e.codeText"), actions = {
            PillButton(tr.t("common.close"), { printed = null })
            PillButton(tr.t("agent.f.protect.e.print"), {
                val x = pr ?: return@PillButton
                printSheet(context, tr.t("agent.f.protect.e.codeTitle", "name" to x.first),
                    "<html><body style='font-family:sans-serif;padding:32px'><h2>${esc(tr.t("agent.f.protect.e.codeTitle", "name" to x.first))}</h2><p>${esc(tr.t("agent.f.protect.e.codeText"))}</p><p style='font-size:28px;font-family:monospace;letter-spacing:4px'>${esc(x.second)}</p></body></html>")
            }, kind = Btn.Primary)
        }) { Text(pr?.second.orEmpty(), style = AgentText.lg.copy(fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = c.text) }
        val cf = confirm
        AgentDialog(cf != null, cf?.let { tr.t(when (it.second) { "decline" -> "agent.f.protect.e.declineTitle"; "end" -> "agent.f.protect.e.endTitle"; else -> "agent.f.protect.e.grantNow" }) } ?: "", { confirm = null },
            text = cf?.let { (req, action) ->
                val shares = (if (action == "end" && req.strs("added").isNotEmpty()) req.strs("added") else req.strs("shares")).joinToString(", ")
                tr.t(when (action) { "decline" -> "agent.f.protect.e.declineConfirm"; "end" -> "agent.f.protect.e.endConfirm"; else -> "agent.f.protect.e.grantConfirm" }, "name" to req.str("account"), "shares" to shares)
            }, actions = {
                PillButton(cf?.let { tr.t(when (it.second) { "decline" -> "agent.f.protect.e.decline"; "end" -> "agent.f.protect.e.endNow"; else -> "agent.f.protect.e.grantNow" }) } ?: "", {
                    val x = confirm ?: return@PillButton
                    confirm = null
                    act(model, scope, tr, { err = it }, { reload++ }) { model.api.sendJson("POST", "agent/protect/emergency/${x.first.str("id")}/${x.second}") }
                }, kind = if (cf?.second == "grant") Btn.Primary else Btn.Danger)
                PillButton(tr.t("common.cancel"), { confirm = null })
            })
    }
}

/** Berater: Befunde mit Stufe, kaputte Platten mit „Durchspielen“, jetzt prüfen. */
@Composable
internal fun AdvisorView(model: AgentModel, onPlay: (String) -> Unit) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    fun tk(k: String, vararg p: Pair<String, Any?>) = tr.t("agent.f.foresight.$k", *p)
    Loaded(model, "agent/advisor") { v, reload ->
        err?.let { item { Notice(it, tone = "error") } }
        val saved = v.o("saved")
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (saved.isNotEmpty()) tk("lastRun", "time" to dateTime(saved.str("lastRun"))) else tk("empty"), style = AgentText.sm, color = c.muted)
                PillButton(if (v.bool("running")) tk("running") else tk("run"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/advisor/run", JsonObject(emptyMap())) } }, enabled = !v.bool("running"), small = true)
            }
        }
        val findings = saved.o("report").os("findings")
        for (f in findings.filter { it.str("area") == "disk" && it.str("level") == "fail" }) item {
            Notice("💽 " + tk("failHead", "disk" to f.str("subject")), tone = "error") {
                Text(findingText(tr, f), style = AgentText.sm, color = c.text)
                PillButton(tk("playThrough"), { onPlay(f.str("subject")) }, small = true)
            }
        }
        val icon = mapOf("disk" to "💽", "memory" to "🧠", "volume" to "📦", "cpu" to "⚙️", "gpu" to "🎮", "temperature" to "🌡️")
        for (f in findings.filter { !(it.str("area") == "disk" && (it.str("level") == "ok" || it.str("level") == "fail")) }) item {
            val tone = when (f.str("level")) { "ok" -> "ok"; "fail" -> "error"; else -> "warn" }
            SRow((icon[f.str("area")] ?: "") + " " + tk("area." + f.str("area"), "name" to f.str("subject")), desc = findingText(tr, f), control = { Tag(tk("badge." + f.str("level")), tone = tone) })
        }
        item { Text(if (v.bool("web")) tk("footer") else tk("footerNoPrices"), style = AgentText.sm, color = c.muted) }
    }
}

private fun findingText(tr: Tr, f: JsonObject): String {
    fun tk(k: String, vararg p: Pair<String, Any?>) = tr.t("agent.f.foresight.$k", *p)
    fun n(k: String) = String.format(if (tr.de) Locale.GERMAN else Locale.ENGLISH, "%.1f", f.num(k))
    val p = arrayOf("from" to n("from"), "to" to n("to"), "weeks" to n("weeks"), "percent" to n("percent"), "days" to n("days"), "hours" to n("hours"), "label" to f.str("label"), "celsius" to n("celsius"))
    val text = when (f.str("code")) {
        "wornOut" -> tk(if (f.containsKey("days")) "c.wornOutDays" else "c.wornOut", *p)
        "gpuOK", "gpuFull" -> if (!f.containsKey("bytes")) tk("c.gpuUnknown", "total" to Format.bytes(f.long("totalBytes"))) else tk(if (f.str("code") == "gpuFull") "c.gpuFull" else if (f.bool("ollama")) "c.gpuOllama" else "c.gpuOK", "used" to Format.bytes(f.long("bytes")), "total" to Format.bytes(f.long("totalBytes")))
        else -> tk("c." + f.str("code"), *p)
    }
    val parts = mutableListOf(text)
    if (f.long("suggestBytes") > 0) parts += tk("c.suggest", "bytes" to "${Math.round(f.long("suggestBytes") / 1073741824.0)} GB")
    if (f.str("area") == "disk" && f.num("hours") > 0) parts += tk("age", "years" to String.format(Locale.getDefault(), "%.1f", f.num("hours") / 8766.0))
    val also = f.strs("also")
    if (also.isNotEmpty()) parts += tk("also", "list" to also.joinToString(", ") { tk("short.$it") })
    return parts.joinToString(" · ")
}

/** Backup-Partner: Partner mit Kontingent und Freigaben (ändern, trennen mit/ohne Daten), Code, koppeln. */
@Composable
internal fun BuddyView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var quota by remember { mutableStateOf("100") }
    var shares by remember { mutableStateOf<Set<String>?>(null) }
    var removing by remember { mutableStateOf<JsonObject?>(null) }
    var withData by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    fun tk(k: String, vararg p: Pair<String, Any?>) = tr.t("agent.f.family.buddy.$k", *p)
    Box {
        Loaded(model, "agent/buddy", reload) { v, re ->
            err?.let { item { Notice(it, tone = "error") } }
            if (v.str("problem").isNotEmpty()) item { Notice(tr.or("agent.f.family.buddy.problem.${v.str("problem")}", v.str("problem")), tone = "warn") }
            val all = v.strs("shares")
            item {
                SGroup(tr.or("agent.f.family.buddy.partners", tk("title"))) {
                    val partners = v.os("partners")
                    if (partners.isEmpty()) Text(tr.or("agent.f.family.buddy.none", "–"), style = AgentText.md, color = c.muted)
                    for (p in partners) {
                        val job = p.o("job")
                        SRow(p.str("name"), icon = "users", desc = listOfNotNull(p.str("address"), Format.bytes(p.long("used")) + " / " + Format.bytes(p.long("quota")), job.str("lastOK").takeIf { it.isNotEmpty() }?.let { dateTime(it) }, job.str("lastError").ifEmpty { null }).joinToString(" · ")) {
                            var q by remember(p) { mutableStateOf((p.long("quota") / 1_000_000_000L).toString()) }
                            var sel by remember(p) { mutableStateOf(p.strs("shares").toSet()) }
                            Field(q, { q = it.filter(Char::isDigit) }, label = "GB", placeholder = "GB", keyboard = KeyboardType.Number)
                            for (s in all) Check(s, s in sel, { on -> sel = if (on) sel + s else sel - s })
                            Buttons {
                                PillButton(tr.t("common.save"), { act(model, scope, tr, { err = it }, re) { model.api.sendJson("PUT", "agent/buddy/partners/${p.str("id")}", jsonOf("quota" to (q.toLongOrNull() ?: 0L) * 1_000_000_000L, "shares" to sel.toList())) } }, kind = Btn.Primary, small = true)
                                PillButton(tr.t("common.delete"), { removing = p; withData = false }, kind = Btn.Danger, small = true)
                            }
                        }
                    }
                }
            }
            item {
                SGroup(tr.or("agent.f.family.buddy.invite", "Code")) {
                    val chosen = shares ?: all.toSet()
                    val cd = v.o("code")
                    if (cd.isNotEmpty()) Notice(cd.str("code").let { k -> if ('-' in k) k else k.replace(Regex("(.{3})(?=.)"), "$1-") } + " · " + dateTime(cd.str("expires")), tone = "ok")
                    Field(quota, { quota = it.filter(Char::isDigit) }, label = "GB", placeholder = "GB", keyboard = KeyboardType.Number)
                    for (s in all) Check(s, s in chosen, { on -> shares = if (on) chosen + s else chosen - s })
                    Buttons {
                        PillButton(tr.or("agent.f.family.buddy.makeCode", "Code"), { act(model, scope, tr, { err = it }, re) { model.api.sendJson("POST", "agent/buddy/code", jsonOf("quota" to (quota.toLongOrNull() ?: 100) * 1_000_000_000L, "shares" to chosen.toList())) } }, small = true)
                        if (cd.isNotEmpty()) PillButton(tr.t("common.cancel"), { act(model, scope, tr, { err = it }, re) { model.api.sendJson("DELETE", "agent/buddy/code") } }, small = true)
                    }
                    Field(address, { address = it }, label = tk("address"), placeholder = tk("address"))
                    Field(code, { code = it }, label = "Code", placeholder = "ABC-DEF-GHI")
                    PillButton(tr.or("agent.f.family.buddy.pair", if (tr.de) "Koppeln" else "Pair"), { act(model, scope, tr, { err = it }, re) { model.api.sendJson("POST", "agent/buddy/pair", jsonOf("address" to address, "code" to code.replace("-", ""), "quota" to (quota.toLongOrNull() ?: 100) * 1_000_000_000L, "shares" to chosen.toList())) } }, kind = Btn.Primary, enabled = address.isNotBlank() && code.isNotBlank(), small = true)
                }
            }
        }
        val rm = removing
        AgentDialog(rm != null, tr.t("common.delete") + ": " + (rm?.str("name") ?: ""), { removing = null }, actions = {
            PillButton(tr.t("common.delete"), { val id = rm?.str("id"); removing = null; if (id != null) act(model, scope, tr, { err = it }, { reload++ }) { model.api.sendJson("DELETE", "agent/buddy/partners/$id" + if (withData) "?data=1" else "") } }, kind = Btn.Danger)
            PillButton(tr.t("common.cancel"), { removing = null })
        }) { Check(tr.or("agent.f.family.buddy.withData", if (tr.de) "Auch die Daten des Partners hier löschen" else "Also delete the partner's data here"), withData, { withData = it }) }
    }
}

/** Was wäre wenn (ForesightWhatIf.svelte): Volume und Fall wählen, das NAS spielt ihn durch. */
@Composable
internal fun WhatIfView(model: AgentModel, startDisk: String?) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    fun tk(k: String, vararg p: Pair<String, Any?>) = tr.or("agent.f.foresight.$k", k, *p)
    fun level(l: String) = if (l.isEmpty()) "" else tr.or("agent.f.foresight.level.$l", l)
    var volumes by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var levels by remember { mutableStateOf<List<String>>(emptyList()) }
    var scenario by remember { mutableStateOf<JsonObject?>(null) }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        try {
            levels = model.api.getJson("agent/whatif").obj().strs("levels")
            volumes = model.api.getJson("storage").obj().os("volumes")
            val vol = volumes.firstOrNull { v -> startDisk != null && v.os("members").any { it.str("disk") == startDisk } } ?: volumes.firstOrNull()
            scenario = when {
                startDisk != null -> jsonOf("kind" to "disk", "disk" to startDisk, "volume" to vol?.str("name"))
                vol != null -> vol.os("members").firstOrNull { it.str("disk").isNotEmpty() }?.let { jsonOf("kind" to "disk", "disk" to it.str("disk"), "volume" to vol.str("name")) } ?: jsonOf("kind" to "full", "volume" to vol.str("name"))
                else -> null
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { err = problemText(tr, problemOf(e)) }
    }
    LaunchedEffect(scenario) {
        val sc = scenario ?: return@LaunchedEffect
        result = null; err = null
        try { result = model.api.sendJson("POST", "agent/whatif", JsonObject(sc.filterValues { it.toString() != "null" })).obj() } catch (e: CancellationException) { throw e } catch (e: Exception) { err = problemText(tr, problemOf(e)) }
    }
    fun scenarios(v: JsonObject): List<JsonObject> {
        val out = v.os("members").filter { it.str("disk").isNotEmpty() }.map { jsonOf("kind" to "disk", "disk" to it.str("disk"), "volume" to v.str("name")) }.toMutableList()
        out += jsonOf("kind" to "full", "volume" to v.str("name"))
        for (l in levels) if (l != v.str("level") && !(v.str("filesystem") != "btrfs" && l == "raid1c3")) out += jsonOf("kind" to "level", "volume" to v.str("name"), "level" to l)
        return out
    }
    fun label(s: JsonObject) = when (s.str("kind")) {
        "disk" -> tk("opt.disk", "disk" to s.str("disk"))
        "full" -> tk("opt.full", "volume" to s.str("volume"))
        else -> tk("opt.level", "level" to level(s.str("level")))
    }
    androidx.compose.foundation.lazy.LazyColumn(contentPadding = io.github.ra5on.titanos.agent.ui.PagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        err?.let { item { Notice(it, tone = "error") } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tk("pick"), style = AgentText.sm, color = c.muted)
                val opts = volumes.flatMap { v -> scenarios(v).map { v.str("name") + ": " + label(it) to it } }
                val key = scenario?.toString().orEmpty()
                if (opts.isNotEmpty()) Select(opts.map { it.second.toString() to it.first }, key, { k -> scenario = opts.firstOrNull { it.second.toString() == k }?.second }, tk("pick"))
            }
        }
        val r = result
        if (r == null && err == null && scenario != null) item { Text(tr.t("common.loading"), style = AgentText.md, color = c.muted) }
        if (r != null) {
            item {
                val p = arrayOf("volume" to r.str("volume"), "disk" to r.str("disk") + if (r.str("serial").isNotEmpty()) " (${r.str("serial")})" else "", "level" to level(r.str("level")), "n" to r.int("disks"), "bytes" to Format.bytes(r.long("dataAtRiskBytes")))
                val (head, sub) = when {
                    r.o("full").isNotEmpty() -> {
                        val f = r.o("full")
                        tk("full.head", "volume" to r.str("volume"), "pct" to Math.round(f.num("usedPct"))) to (if (f.containsKey("daysToFull") && f.long("dailyBytes") > 0) tk("full.days", "perDay" to Format.bytes(f.long("dailyBytes")), "days" to Math.round(f.num("daysToFull")), "free" to Format.bytes(f.long("freeBytes"))) else tk("full.noGrowth", "free" to Format.bytes(f.long("freeBytes"))))
                    }
                    r.o("compare").isNotEmpty() -> {
                        val cm = r.o("compare")
                        tk("lv.head", "to" to level(cm.o("to").str("level")), "from" to level(cm.o("from").str("level"))) to (if (cm.str("way") == "profile") tk("lv.profile") else tk("lv.newVolume"))
                    }
                    else -> { val o = r.str("outcome").ifEmpty { "redundant" }; tk("o.$o", *p) to tk("o.${o}Sub", *p) }
                }
                Notice(head + "\n" + sub, tone = when (r.o("risk").str("level")) { "high" -> "error"; "elevated" -> "warn"; else -> "ok" })
            }
            r.o("risk").takeIf { it.isNotEmpty() }?.let { risk -> item {
                SGroup(tk("risk")) {
                    SRow(tk("risk.${risk.str("level")}"), icon = "shield")
                    for (x in risk.os("reasons")) SRow(tk("r.${x.str("code")}", "disk" to x.str("disk"), "value" to x.long("value"), "years" to String.format(if (tr.de) Locale.GERMAN else Locale.ENGLISH, "%.1f", x.num("value") / 8766.0)))
                }
            } }
            r.o("rebuild").takeIf { it.isNotEmpty() }?.let { rb -> item {
                val time = (rb.long("seconds") / 3600).let { h -> if (h >= 1) "$h h" else "${rb.long("seconds") / 60} min" }
                SGroup(tk("rebuild")) { SRow(tk(if (r.str("filesystem") == "btrfs") "rebuildBtrfs" else "rebuildSub", "size" to Format.bytes(rb.long("bytes")), "time" to time, "how" to tk(if (rb.bool("measured")) "measured" else "estimated"), "speed" to rb.long("bps") / 1_000_000)) }
            } }
            val effects = r.o("full").strs("effects")
            if (effects.isNotEmpty()) item { SGroup(tk("full.effects")) { for (e in effects) SRow(tk("e.$e")) } }
            val gaps = r.os("gaps")
            if (gaps.isNotEmpty()) item {
                SGroup(tk("gap")) {
                    for (g in gaps) SRow(when {
                        g.bool("never") -> tk("gap.never", "share" to g.str("share"))
                        g.bool("unknown") -> tk("gap.unknown", "share" to g.str("share"))
                        g.bool("notYet") -> tk("gap.notYet", "share" to g.str("share"), "job" to g.str("job"))
                        else -> tk("gap.days", "share" to g.str("share"), "size" to if (g.long("bytes") > 0) " (" + Format.bytes(g.long("bytes")) + ")" else "", "days" to g.int("days"))
                    }, icon = "archive", badge = { Tag(tk("gapBadge"), tone = "warn") })
                }
            }
            val safe = r.os("safe")
            if (safe.isNotEmpty()) item { SGroup(tk("safe")) { for (s in safe) SRow(tk("safeItem", "share" to s.str("share"), "time" to dateTime(s.str("at"))), icon = "check") } }
            val advice = r.os("advice")
            if (advice.isNotEmpty()) item {
                SGroup(tk("advice")) {
                    for (a in advice) {
                        val size = if (a.long("bytes") > 0) Format.bytes(a.long("bytes")) else ""
                        val (title, sub) = when (a.str("code")) {
                            "startBackup" -> tk("a.startBackup", "share" to a.str("share")) to null
                            "createBackup" -> tk("a.createBackup", "share" to a.str("share")) to null
                            "replacement" -> tk("a.replacement") to tk(if (a.str("kind") == "hdd") "a.replacementCMR" else "a.replacementSub", "size" to size)
                            "checkCable" -> tk("a.checkCable", "disk" to a.str("disk")) to tk("a.checkCableSub")
                            "addSpare" -> tk("a.addSpare") to tk("a.addSpareSub")
                            "addDisks" -> tk("a.addDisks", "count" to a.int("count"), "level" to level(a.str("level"))) to null
                            else -> tk("a." + a.str("code")) to null
                        }
                        val job = a.str("job")
                        SRow(title, desc = sub, icon = "info", control = if (a.str("code") == "startBackup" && job.isNotEmpty()) ({
                            if (job in started) Tag(tk("a.started"), tone = "ok")
                            else PillButton(tk("a.start"), { act(model, scope, tr, { err = it }, { started = started + job }) { model.api.sendJson("POST", "backups/$job/run") } }, small = true)
                        }) else null)
                    }
                }
            }
            item { Text(tk("footerWhatIf"), style = AgentText.sm, color = c.muted, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
        }
    }
}
