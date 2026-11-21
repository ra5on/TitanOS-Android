package io.github.ra5on.titanos.agent.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.dateTime
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.core.bool
import io.github.ra5on.titanos.agent.core.int
import io.github.ra5on.titanos.agent.core.json
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
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PageHead
import io.github.ra5on.titanos.agent.ui.PagePadding
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.SGroup
import io.github.ra5on.titanos.agent.ui.SRow
import io.github.ra5on.titanos.agent.ui.Segments
import io.github.ra5on.titanos.agent.ui.Select
import io.github.ra5on.titanos.agent.ui.Switch
import io.github.ra5on.titanos.agent.ui.Tag
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

// Die acht Funktionen des Agenten (PLAN 54, FeaturesPanel.svelte und die
// Gruppen Dokumente, Schutz, Vorausschau, Familie): die Einstellungen am NAS
// (Besitzer und Admins) und die eigenen, dazu ihre Ansichten aus dem Menü.

/** Das Einstellungsobjekt eines Teils mit geänderten Feldern. */
private fun JsonObject.put(key: String, value: Any?): JsonObject = JsonObject(this + (key to json(value)))
private fun JsonObject.sub(part: String, key: String, value: Any?): JsonObject = put(part, o(part).put(key, value))

private class FeatureState(private val model: AgentModel, private val tr: Tr) {
    var nas by mutableStateOf<JsonObject?>(null)
    var mine by mutableStateOf<JsonObject?>(null)
    var pub by mutableStateOf<JsonObject?>(null)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var saved by mutableStateOf(false)

    suspend fun load(admin: Boolean) {
        if (admin) try { nas = model.api.getJson("agent/features").obj() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
        try { val m = model.api.getJson("agent/features/me").obj(); mine = m.o("mine"); pub = m.o("nas") } catch (e: CancellationException) { throw e } catch (e: Exception) { if (error == null) error = problemText(tr, problemOf(e)) }
    }

    /** Speichert die NAS-Einstellungen mit ihrer Version; geändert anderswo: neu lesen und noch einmal. */
    /**
     * Speichert die NAS-Einstellungen mit ihrer Version. Hat sie jemand anderes
     * inzwischen geändert, gehen nur die Teile hinüber, die hier geändert wurden
     * und dort nicht (rebaseFeatures im Web, je Funktion); sonst gilt die neue
     * Fassung und die App sagt, dass die eigene Änderung verloren ist.
     */
    suspend fun saveNas(next: JsonObject) {
        error = null; notice = null
        val base = nas ?: next
        nas = next
        try {
            nas = model.api.sendJson("PUT", "agent/features", next).obj()
            saved = true
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val code = (e as? io.github.ra5on.titanos.net.ApiException)?.code
            if (code != "agent_features_changed") { nas = base; error = problemText(tr, problemOf(e)); return }
            val fresh = runCatching { model.api.getJson("agent/features").obj() }.getOrNull()
            if (fresh == null) { nas = base; error = problemText(tr, problemOf(e)); return }
            val mine = next.keys.filter { it != "rev" && next[it] != base[it] }
            val clash = mine.any { fresh[it] != base[it] }
            if (clash || mine.isEmpty()) { nas = fresh; error = tr.t("agent.f.changedLost"); return }
            val merged = JsonObject(fresh + mine.associateWith { next.getValue(it) })
            try { nas = model.api.sendJson("PUT", "agent/features", merged).obj(); notice = tr.t("agent.f.changedKept") }
            catch (e2: CancellationException) { throw e2 } catch (_: Exception) { nas = fresh; error = tr.t("agent.f.changedLost") }
        }
        runCatching { pub = model.api.getJson("agent/features/me").obj().o("nas") }
    }

    suspend fun saveMine(next: JsonObject) {
        error = null
        mine = next
        try { mine = model.api.sendJson("PUT", "agent/features/me", next).obj(); saved = true } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
    }
}

/** Funktionen in den Einstellungen: je Gruppe die Funktionen mit Schalter und ihren Einstellungen. */
@Composable
fun FeaturesPanel(model: AgentModel, onFeature: (String) -> Unit) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    val admin = model.status?.admin == true
    val st = remember { FeatureState(model, tr) }
    LaunchedEffect(admin) { st.load(admin) }
    fun nas(next: JsonObject) = scope.launch { st.saveNas(next) }
    fun mine(next: JsonObject) = scope.launch { st.saveMine(next) }
    val n = st.nas ?: JsonObject(emptyMap())
    val m = st.mine ?: JsonObject(emptyMap())
    val p = st.pub ?: JsonObject(emptyMap())
    var picking by remember { mutableStateOf<((String, String) -> Unit)?>(null) }

    @Composable
    fun nasSwitch(part: String, key: String = "enabled") {
        if (admin && st.nas != null) Switch(n.o(part).bool(key), { nas(n.sub(part, key, it)) })
        else Tag(if (p.bool(if (part == "docs" && key == "enabled") "docs" else if (part == "docs") key else part)) tr.t("agent.on") else tr.t("agent.off"))
    }

    Box {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr.t("agent.f.title"), style = AgentText.pageTitle, color = c.text)
            Text(tr.t("agent.f.intro"), style = AgentText.sm, color = c.muted)
            if (!admin) Text(tr.t("agent.f.adminOnly"), style = AgentText.sm, color = c.muted)
            st.error?.let { Notice(it, tone = "error") }
            st.notice?.let { Notice(it, tone = "ok") }

            // ---------- Dokumente ----------
            SGroup(tr.t("agent.f.group.docs")) {
                val docs = m.o("docs")
                SRow(tr.t("agent.f.docs.memory"), desc = tr.t("agent.f.docs.memorySub"), icon = "fileText", control = { nasSwitch("docs") }) {
                    if (!p.bool("docs")) Text(tr.t("agent.f.notThere"), style = AgentText.sm, color = c.muted) else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr.t("agent.f.docs.memory"), style = AgentText.md, color = c.text, modifier = Modifier.weight(1f))
                            Switch(docs.bool("on"), { mine(m.sub("docs", "on", it)) }, label = tr.t("agent.f.docs.memory"))
                        }
                        Text(tr.t("agent.f.docs.nightly", "at" to n.o("docs").str("at", "03:00")), style = AgentText.sm, color = c.muted)
                        Text(tr.t("agent.f.docs.folders"), style = AgentText.sm, color = c.muted)
                        for (f in docs["folders"].objs()) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(place(f), style = AgentText.md, color = c.text, modifier = Modifier.weight(1f))
                            PillButton(tr.t("common.delete"), { mine(m.sub("docs", "folders", docs["folders"].objs().filter { it != f })) }, small = true)
                        }
                        PillButton(tr.t("agent.f.docs.addFolder"), { picking = { root, path -> mine(m.sub("docs", "folders", docs["folders"].objs() + jsonOf("root" to root, "path" to path)).sub("docs", "custom", true)) } }, icon = "plus", small = true)
                        Text(tr.t("agent.f.docs.addHint"), style = AgentText.sm, color = c.muted)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.docs.scans"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.docs.scansSub"), style = AgentText.sm, color = c.muted) }
                            Switch(!docs.bool("noScans"), { mine(m.sub("docs", "noScans", !it)) }, label = tr.t("agent.f.docs.scans"))
                        }
                        PillButton(tr.t("agent.f.docs.runNow"), { scope.launch { runCatching { model.api.sendJson("POST", "agent/docs/run") }; st.notice = tr.t("agent.f.docs.state.running") } }, small = true)
                    }
                }
                SRow(tr.t("agent.f.docs.deadlines"), desc = if (docs.str("mode") == "remind") tr.t("agent.f.docs.deadlinesSubRemind") else tr.t("agent.f.docs.deadlinesSub"), icon = "clock", control = { nasSwitch("docs", "deadlines") }) {
                    if (!p.bool("deadlines")) Text(tr.t("agent.f.notThere"), style = AgentText.sm, color = c.muted) else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.docs.findDeadlines"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.docs.findHint"), style = AgentText.sm, color = c.muted) }
                            Switch(docs.bool("deadlines"), { mine(m.sub("docs", "deadlines", it)) }, label = tr.t("agent.f.docs.findDeadlines"))
                        }
                        Segments(listOf("suggest" to tr.t("agent.f.docs.modeSuggest"), "remind" to tr.t("agent.f.docs.modeRemind")), docs.str("mode").ifEmpty { "suggest" }, { mine(m.sub("docs", "mode", it)) }, fill = true)
                        var lead by remember(docs) { mutableStateOf(docs["lead"].let { (it as? JsonArray)?.joinToString(", ") { x -> x.toString() } ?: "7, 1" }) }
                        Field(lead, { lead = it; val days = it.split(Regex("[,\\s]+")).mapNotNull { s -> s.toIntOrNull() }.filter { d -> d in 0..90 }.take(2); if (days.isNotEmpty()) mine(m.sub("docs", "lead", days)) }, label = tr.t("agent.f.docs.remind"), keyboard = KeyboardType.Number)
                        Text(tr.t("agent.f.docs.leadHint"), style = AgentText.sm, color = c.muted)
                        PillButton(tr.t("agent.f.docs.show"), { onFeature("deadlines") }, small = true)
                    }
                }
                val paper = m.o("paper")
                SRow(tr.t("agent.f.docs.paper"), desc = if (paper.str("filing") == "ask") tr.t("agent.f.docs.paperSubAsk") else tr.t("agent.f.docs.paperSub"), icon = "list", control = { nasSwitch("docs", "paper") }) {
                    if (!p.bool("paper")) Text(tr.t("agent.f.notThere"), style = AgentText.sm, color = c.muted) else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr.t("agent.f.docs.paperOwn"), style = AgentText.md, color = c.text, modifier = Modifier.weight(1f))
                            Switch(paper.bool("on"), { mine(m.sub("paper", "on", it)) }, label = tr.t("agent.f.docs.paperOwn"))
                        }
                        Text(tr.t("agent.f.docs.inboxSub"), style = AgentText.sm, color = c.muted)
                        Text(tr.t("agent.f.docs.filing"), style = AgentText.sm, color = c.muted)
                        Segments(listOf("auto" to tr.t("agent.f.docs.filingAuto"), "ask" to tr.t("agent.f.docs.filingAsk")), paper.str("filing").ifEmpty { "auto" }, { mine(m.sub("paper", "filing", it)) }, fill = true)
                        Text(tr.t("agent.f.docs.filingHint"), style = AgentText.sm, color = c.muted)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.docs.target"), style = AgentText.md, color = c.text); Text(paper.o("target").takeIf { it.isNotEmpty() }?.let(::place) ?: "–", style = AgentText.sm, color = c.muted) }
                            PillButton(tr.t("agent.f.docs.change"), { picking = { root, path -> mine(m.sub("paper", "target", jsonOf("root" to root, "path" to path))) } }, small = true)
                        }
                        Text(tr.t("agent.f.docs.scheme"), style = AgentText.sm, color = c.muted)
                        Select(listOf("sender" to tr.t("agent.f.docs.schemeSender"), "type" to tr.t("agent.f.docs.schemeType"), "year" to tr.t("agent.f.docs.schemeYear")), paper.str("scheme").ifEmpty { "sender" }, { mine(m.sub("paper", "scheme", it)) }, tr.t("agent.f.docs.scheme"))
                        PaperMail(model, m, ::mine)
                        PillButton(tr.t("agent.f.docs.openPaper"), { onFeature("paper") }, small = true)
                    }
                }
            }

            // ---------- Schutz ----------
            SGroup(tr.t("agent.f.group.protect")) {
                if (!admin) Text(tr.t("agent.f.protect.adminOnly"), style = AgentText.sm, color = c.muted)
                val g = n.o("guard")
                SRow(tr.t("agent.f.protect.guard"), icon = "shield", control = { nasSwitch("guard") }) {
                    if (admin) {
                        Text(tr.t("agent.f.protect.s.mode"), style = AgentText.sm, color = c.muted)
                        Segments(listOf("report", "lock", "auto").map { it to tr.t("agent.f.protect.g.mode.$it") }, g.str("mode", "report").ifEmpty { "report" }, { nas(n.sub("guard", "mode", it)) }, fill = true)
                        Text(tr.t("agent.f.protect.s.modeHint.${g.str("mode", "report").ifEmpty { "report" }}"), style = AgentText.sm, color = c.muted)
                        for ((k, label) in listOf("known" to "agent.f.protect.s.known", "renames" to "agent.f.protect.s.renames", "rewrites" to "agent.f.protect.s.rewrites", "notes" to "agent.f.protect.s.notes", "windowSec" to "agent.f.protect.s.window", "quietSec" to "agent.f.protect.s.quiet")) {
                            var v by remember(g) { mutableStateOf(g.long(k).takeIf { it > 0 }?.toString().orEmpty()) }
                            Field(v, { s -> v = s.filter(Char::isDigit).take(6); s.toLongOrNull()?.let { nas(n.sub("guard", k, it)) } }, label = tr.t(label), placeholder = tr.t(label), keyboard = KeyboardType.Number)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.protect.s.pre"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.protect.s.preHint"), style = AgentText.sm, color = c.muted) }
                            Switch(!g.bool("noPreSnapshots"), { nas(n.sub("guard", "noPreSnapshots", !it)) }, label = tr.t("agent.f.protect.s.pre"))
                        }
                        var ex by remember(g) { mutableStateOf(g.strs("exclude").joinToString("\n")) }
                        Field(ex, { ex = it }, multiline = true, label = tr.t("agent.f.protect.s.exclude"), placeholder = tr.t("agent.f.protect.s.exclude"))
                        Text(tr.t("agent.f.protect.s.excludeHint"), style = AgentText.sm, color = c.muted)
                        PillButton(tr.t("common.save"), { nas(n.sub("guard", "exclude", ex.lines().map { it.trim() }.filter { it.isNotEmpty() })) }, small = true)
                    }
                    PillButton(tr.t("agent.f.menu.guard"), { onFeature("guard") }, small = true)
                }
                val h = n.o("handbook")
                SRow(tr.t("agent.f.protect.handbook"), desc = tr.t("agent.f.protect.h.rowSub"), icon = "fileText", control = { nasSwitch("handbook") }) {
                    if (admin) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.protect.s.probes"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.protect.s.probesHint"), style = AgentText.sm, color = c.muted) }
                            Switch(h.bool("probes"), { nas(n.sub("handbook", "probes", it)) }, label = tr.t("agent.f.protect.s.probes"))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.protect.s.contacts"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.protect.s.contactsHint"), style = AgentText.sm, color = c.muted) }
                            Switch(h.bool("withContacts"), { nas(n.sub("handbook", "withContacts", it)) }, label = tr.t("agent.f.protect.s.contacts"))
                        }
                        if (h.bool("withContacts")) {
                            val contacts = h["contacts"].objs()
                            contacts.forEachIndexed { i, ct ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for ((k, label) in listOf("name" to "agent.f.protect.s.cName", "phone" to "agent.f.protect.s.cPhone", "note" to "agent.f.protect.s.cNote")) {
                                        var v by remember(ct) { mutableStateOf(ct.str(k)) }
                                        Field(v, { v = it }, label = tr.t(label), placeholder = tr.t(label), onDone = { nas(n.sub("handbook", "contacts", contacts.mapIndexed { j, x -> if (j == i) x.put(k, v) else x })) })
                                    }
                                    Buttons {
                                        PillButton(tr.t("common.save"), { }, small = true)
                                        PillButton(tr.t("agent.f.protect.s.remove"), { nas(n.sub("handbook", "contacts", contacts.filterIndexed { j, _ -> j != i })) }, kind = Btn.Danger, small = true)
                                    }
                                }
                            }
                            PillButton(tr.t("agent.f.protect.s.addContact"), { nas(n.sub("handbook", "contacts", contacts + jsonOf("name" to "", "phone" to "", "note" to ""))) }, icon = "plus", small = true)
                        }
                    }
                    PillButton(tr.t("agent.f.menu.handbook"), { onFeature("handbook") }, small = true)
                }
                val em = n.o("emergency")
                SRow(tr.t("agent.f.protect.emergency"), desc = tr.t("agent.f.protect.e.rowSub", "days" to (n.o("emergency")["persons"].objs().firstOrNull()?.let { it["waitDays"]?.toString()?.toIntOrNull() } ?: 3)), icon = "key", control = { nasSwitch("emergency") }) {
                    if (admin) {
                        val persons = em["persons"].objs()
                        persons.forEachIndexed { i, pr ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                var acc by remember(pr) { mutableStateOf(pr.str("account")) }
                                var wait by remember(pr) { mutableStateOf(pr.int("waitDays", 3).toString()) }
                                var shares by remember(pr) { mutableStateOf(pr.strs("shares").joinToString(", ")) }
                                Field(acc, { acc = it }, label = tr.t("agent.f.protect.s.account"), placeholder = tr.t("agent.f.protect.s.account"))
                                Field(wait, { wait = it.filter(Char::isDigit).take(3) }, label = tr.t("agent.f.protect.s.wait"), placeholder = tr.t("agent.f.protect.s.wait"), keyboard = KeyboardType.Number)
                                Field(shares, { shares = it }, label = tr.t("agent.f.protect.s.shares"), placeholder = tr.t("agent.f.protect.s.shares"))
                                Buttons {
                                    PillButton(tr.t("common.save"), { nas(n.sub("emergency", "persons", persons.mapIndexed { j, x -> if (j == i) jsonOf("account" to acc.trim(), "waitDays" to (wait.toIntOrNull() ?: 3), "shares" to shares.split(',').map { it.trim() }.filter { it.isNotEmpty() }) else x })) }, kind = Btn.Primary, small = true)
                                    PillButton(tr.t("agent.f.protect.s.remove"), { nas(n.sub("emergency", "persons", persons.filterIndexed { j, _ -> j != i })) }, kind = Btn.Danger, small = true)
                                }
                            }
                        }
                        PillButton(tr.t("agent.f.protect.s.addPerson"), { nas(n.sub("emergency", "persons", persons + jsonOf("account" to "", "waitDays" to 3, "shares" to emptyList<String>()))) }, icon = "plus", small = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.protect.s.code"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.protect.s.codeHint"), style = AgentText.sm, color = c.muted) }
                            Switch(em.bool("code"), { nas(n.sub("emergency", "code", it)) }, label = tr.t("agent.f.protect.s.code"))
                        }
                    }
                    PillButton(tr.t("agent.f.menu.emergency"), { onFeature("emergency") }, small = true)
                }
            }

            // ---------- Vorausschau ----------
            SGroup(tr.t("agent.f.group.foresight")) {
                SRow(tr.t("agent.f.foresight.whatIf"), desc = tr.t("agent.f.foresight.whatIfSub"), icon = "activity", control = { nasSwitch("whatIf") }) {
                    if (p.bool("whatIf") && admin) PillButton("🧪 " + tr.t("agent.f.foresight.play"), { onFeature("whatif") }, small = true)
                    Text(tr.t("agent.f.foresight.whatIfIntro"), style = AgentText.sm, color = c.muted)
                    Text(tr.t("agent.f.foresight.whatIfWhere"), style = AgentText.sm, color = c.muted)
                    Text(tr.t("agent.f.foresight.whatIfData"), style = AgentText.sm, color = c.muted)
                }
                val a = n.o("advisor")
                SRow(tr.t("agent.f.foresight.advisor"), desc = tr.t("agent.f.foresight.advisorSub"), icon = "chip", control = { nasSwitch("advisor") }) {
                    if (admin) for (k in listOf("predict", "bottlenecks", "prices", "monthly")) Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(tr.t("agent.f.foresight.set.$k"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.foresight.set.${k}Sub"), style = AgentText.sm, color = c.muted) }
                        Switch(a.bool(k, true), { nas(n.sub("advisor", k, it)) }, label = tr.t("agent.f.foresight.set.$k"))
                    }
                    if (admin && a.bool("monthly", true)) {
                        io.github.ra5on.titanos.agent.ui.TimeField(a.str("at", "09:00"), { nas(n.sub("advisor", "at", it)) }, tr.t("agent.f.foresight.set.at"), width = 140.dp)
                        Text(tr.t("agent.f.foresight.set.atHint"), style = AgentText.sm, color = c.muted)
                    }
                    PillButton(tr.t("agent.f.foresight.openReport"), { onFeature("advisor") }, small = true)
                }
            }

            // ---------- Familie ----------
            SGroup(tr.t("agent.f.group.family")) {
                val b = n.o("buddy")
                SRow(tr.t("agent.f.family.buddy.title"), desc = tr.t("agent.f.family.buddy.intro"), icon = "users", control = { nasSwitch("buddy") }) {
                    if (admin) {
                        var address by remember(b) { mutableStateOf(b.str("address")) }
                        Field(address, { address = it }, label = tr.t("agent.f.family.buddy.address"), placeholder = tr.t("agent.f.family.buddy.address"), onDone = { nas(n.sub("buddy", "address", address.trim())) })
                        Text(tr.t("agent.f.family.buddy.addressHint"), style = AgentText.sm, color = c.muted)
                        var volume by remember(b) { mutableStateOf(b.str("volume")) }
                        Field(volume, { volume = it }, label = tr.t("agent.f.family.buddy.volume"), placeholder = tr.t("agent.f.family.buddy.volume"), onDone = { nas(n.sub("buddy", "volume", volume.trim())) })
                        Text(tr.t("agent.f.family.buddy.volumeHint"), style = AgentText.sm, color = c.muted)
                        Text(tr.t("agent.f.family.buddy.every"), style = AgentText.sm, color = c.muted)
                        Segments(listOf("daily", "weekly", "monthly").map { it to tr.t("agent.f.family.buddy.every.$it") }, b.str("every", "weekly").ifEmpty { "weekly" }, { nas(n.sub("buddy", "every", it)) }, fill = true)
                        if (b.str("every", "weekly") == "weekly") Select((0..6).map { it.toString() to (tr.t("agent.weekdays").split(",").getOrNull(it) ?: "$it") }, b.int("weekday", 0).toString(), { nas(n.sub("buddy", "weekday", it.toInt())) }, tr.t("agent.f.family.buddy.weekday"))
                        io.github.ra5on.titanos.agent.ui.TimeField(b.str("time", "02:00"), { nas(n.sub("buddy", "time", it)) }, tr.t("agent.f.family.buddy.time"), width = 140.dp)
                        Text(tr.t("agent.f.family.buddy.probe"), style = AgentText.sm, color = c.muted)
                        Segments(listOf("monthly", "off").map { it to tr.t("agent.f.family.buddy.probe.$it") }, b.str("probe", "monthly").ifEmpty { "monthly" }, { nas(n.sub("buddy", "probe", it)) }, fill = true)
                        var files by remember(b) { mutableStateOf(b.int("probeFiles", 20).toString()) }
                        Field(files, { files = it.filter(Char::isDigit).take(4); it.toIntOrNull()?.let { x -> nas(n.sub("buddy", "probeFiles", x)) } }, label = tr.t("agent.f.family.buddy.probeFiles"), keyboard = KeyboardType.Number)
                        var days by remember(b) { mutableStateOf(b.int("protectDays", 30).toString()) }
                        Field(days, { days = it.filter(Char::isDigit).take(4); it.toIntOrNull()?.let { x -> nas(n.sub("buddy", "protectDays", x)) } }, label = tr.t("agent.f.family.buddy.protect"), keyboard = KeyboardType.Number)
                        Text(tr.t("agent.f.family.buddy.protectHint"), style = AgentText.sm, color = c.muted)
                    }
                    PillButton(tr.t("agent.f.family.buddy.open"), { onFeature("buddy") }, small = true)
                }
                SRow(tr.t("agent.f.family.catalog.title"), desc = tr.t("agent.f.family.catalog.sub"), icon = "zap", control = { nasSwitch("catalog") }) {
                    Text(tr.t("agent.f.family.catalog.intro"), style = AgentText.sm, color = c.muted)
                    if (p.bool("catalog")) PillButton(tr.t("agent.f.family.catalog.open"), { onFeature("catalog") }, small = true)
                    else Text(tr.t("agent.f.family.catalog.offHint"), style = AgentText.sm, color = c.muted)
                }
            }
            if (st.saved) Text(tr.t("agent.f.saved"), style = AgentText.sm, color = c.muted)
        }
        val pick = picking
        if (pick != null) FolderPicker(model, onClose = { picking = null }) { root, path -> picking = null; pick(root, path) }
    }
}

fun place(f: JsonObject): String {
    val share = f.str("root").removePrefix("share/")
    return if (f.str("path").isNotEmpty()) share + " › " + f.str("path").split('/').joinToString(" › ") else share
}

/** Das Postfach für Papierkram (PaperMail): Server, Port, Sicherheit, Name, Passwort (nur neu gesendet), was genommen wird, Test. */
@Composable
private fun PaperMail(model: AgentModel, mine: JsonObject, save: (JsonObject) -> Unit) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    val paper = mine.o("paper")
    var mail by remember(paper) { mutableStateOf(paper.o("mail")) }
    var password by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr.t("agent.f.docs.mailbox"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.docs.mailOn"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.docs.mailSub"), style = AgentText.sm, color = c.muted) }
            Switch(mail.bool("enabled"), { mail = mail.put("enabled", it) }, label = tr.t("agent.f.docs.mailOn"))
        }
        if (mail.bool("enabled")) {
            Field(mail.str("host"), { mail = mail.put("host", it.trim()) }, label = tr.t("agent.f.docs.host"), placeholder = tr.t("agent.f.docs.host"))
            Field(mail.int("port", 993).toString(), { mail = mail.put("port", it.filter(Char::isDigit).toIntOrNull() ?: 0) }, label = tr.t("agent.f.docs.port"), keyboard = KeyboardType.Number)
            Segments(listOf("tls" to "TLS", "starttls" to "STARTTLS"), mail.str("security").ifEmpty { "tls" }, { mail = mail.put("security", it) }, fill = true)
            Field(mail.str("user"), { mail = mail.put("user", it.trim()) }, label = tr.t("agent.f.docs.mailUser"), placeholder = tr.t("agent.f.docs.mailUser"), keyboard = KeyboardType.Email)
            Field(password, { password = it }, password = true, label = tr.t("agent.f.docs.password"), placeholder = if (mail.bool("passwordSet")) tr.t("agent.f.docs.passwordSet") else tr.t("agent.f.docs.password"))
            Text(tr.t("agent.f.docs.mailTake"), style = AgentText.sm, color = c.muted)
            Segments(listOf("both" to tr.t("agent.f.docs.mailBoth"), "attachments" to tr.t("agent.f.docs.mailAttach")), mail.str("mode").ifEmpty { "both" }, { mail = mail.put("mode", it) }, fill = true)
            Text(tr.t("agent.f.docs.mailHint"), style = AgentText.sm, color = c.muted)
        }
        result?.let { Notice(it, tone = if (it.startsWith("!")) "warn" else "ok") }
        Buttons {
            PillButton(tr.t("common.save"), { val m2 = if (password.isNotEmpty()) mail.put("password", password) else mail; save(mine.sub("paper", "mail", m2)); password = "" }, kind = Btn.Primary, small = true)
            if (mail.bool("enabled")) PillButton(tr.t("agent.f.docs.mailTest"), {
                scope.launch {
                    result = try { model.api.sendJson("POST", "agent/paper/mail/test"); tr.t("agent.f.docs.mailOk") } catch (e: CancellationException) { throw e } catch (e: Exception) { "!" + tr.t("agent.f.docs.mailFailed", "error" to problemText(tr, problemOf(e))) }
                }
            }, small = true)
        }
    }
}

/** Einen Ordner des NAS wählen (DocsFolderPicker). */
@Composable
fun FolderPicker(model: AgentModel, onClose: () -> Unit, onPick: (String, String) -> Unit) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<JsonObject>?>(null) }
    var root by remember { mutableStateOf<JsonObject?>(null) }
    var dir by remember { mutableStateOf("") }
    var dirs by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { roots = runCatching { model.api.getJson("files/roots").objs().filter { it.str("kind") != "stick" } }.getOrDefault(emptyList()) }
    fun open(r: JsonObject, path: String) {
        root = r; dir = path; dirs = null
        scope.launch { dirs = runCatching { model.api.getJson("files/list", listOf("root" to r.str("id"), "path" to path)).objs().filter { it.bool("dir") }.map { it.str("name") } }.getOrDefault(emptyList()) }
    }
    Box(Modifier.fillMaxSize()) {
        AgentDialog(true, tr.t("agent.f.docs.addFolder"), onClose, text = root?.let { it.str("name") + if (dir.isNotEmpty()) " › " + dir.replace("/", " › ") else "" }, actions = {
            if (root != null) PillButton(tr.t("agent.f.docs.pickThis"), { onPick(root!!.str("id"), dir) }, kind = Btn.Primary, small = true)
            PillButton(tr.t("common.cancel"), onClose, small = true)
        }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val r = root
                if (r == null) for (x in roots.orEmpty()) SRow(x.str("name"), icon = "folder", onClick = { open(x, "") })
                else {
                    if (dir.isNotEmpty()) SRow("..", icon = "chevronLeft", onClick = { open(r, dir.substringBeforeLast('/', "")) })
                    val list = dirs
                    if (list != null && list.isEmpty()) Text(tr.t("agent.f.docs.pickEmpty"), style = AgentText.sm, color = c.muted)
                    for (d in list.orEmpty()) SRow(d, icon = "folder", onClick = { open(r, if (dir.isEmpty()) d else "$dir/$d") })
                }
            }
        }
    }
}

internal fun euro(cents: Long, de: Boolean): String = NumberFormat.getCurrencyInstance(if (de) Locale.GERMANY else Locale.UK).apply { currency = Currency.getInstance("EUR") }.format(cents / 100.0)

/**
 * Die Ansicht einer Funktion aus dem Menü (FeatureView): Papierkram, Fristen,
 * Wächter, Notfallhandbuch, Notfallzugang, Berater, Backup-Partner, Katalog.
 */
@Composable
fun FeatureView(model: AgentModel, id: String, onClose: () -> Unit) {
    val tr = tr()
    var play by remember { mutableStateOf<String?>(null) }
    val title = if (id == "whatif" || play != null) tr.t("agent.f.foresight.whatIf") else tr.t("agent.f.menu.$id")
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHead(title, { if (play != null) play = null else onClose() })
        if (play != null) { WhatIfView(model, play!!.ifEmpty { null }); return@Column }
        when (id) {
            "whatif" -> WhatIfView(model, null)
            "paper" -> PaperView(model)
            "deadlines" -> DeadlinesView(model)
            "guard" -> GuardView(model)
            "handbook" -> HandbookView(model)
            "emergency" -> EmergencyView(model)
            "advisor" -> AdvisorView(model, onPlay = { disk -> play = disk })
            "buddy" -> BuddyView(model)
            "catalog" -> CatalogView(model)
        }
    }
}

/** Lädt eine Ansicht und zeigt Fehler; [body] baut die Liste. */
@Composable
internal fun Loaded(model: AgentModel, path: String, reload: Int = 0, body: LazyListScope.(JsonObject, () -> Unit) -> Unit) {
    val tr = tr()
    val c = Titan.colors
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var turn by remember { mutableStateOf(0) }
    LaunchedEffect(path, turn, reload) {
        try { data = model.api.getJson(path).obj(); error = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { item { Notice(it, tone = "error") } }
        val d = data
        if (d == null && error == null) item { Text(tr.t("common.loading"), style = AgentText.md, color = c.muted) }
        if (d != null) body(d) { turn++ }
    }
}

internal fun act(model: AgentModel, scope: kotlinx.coroutines.CoroutineScope, tr: Tr, onError: (String) -> Unit, then: () -> Unit, block: suspend () -> Unit) {
    scope.launch { try { block(); then() } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(problemText(tr, problemOf(e))) } }
}

@Composable
private fun DeadlinesView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    Loaded(model, "agent/inbox") { r, reload ->
        err?.let { item { Notice(it, tone = "error") } }
        val items = r.os("items").filter { it.str("kind") == "deadline" }
        if (items.isEmpty()) item { Text(tr.t("agent.f.docs.noneDeadlines"), style = AgentText.md, color = c.muted) }
        for (it in items) item {
            SRow(it.str("title"), desc = it.str("body").ifEmpty { null } ?: dateTime(it.str("created")), icon = "clock", control = {
                Buttons {
                    PillButton(tr.t("agent.inbox.take"), { act(model, scope, tr, { e -> err = e }, reload) { model.api.sendJson("POST", "agent/inbox/${it.str("id")}", jsonOf("accept" to true)) } }, kind = Btn.Primary, small = true)
                    PillButton(tr.t("agent.f.docs.reject"), { act(model, scope, tr, { e -> err = e }, reload) { model.api.sendJson("POST", "agent/inbox/${it.str("id")}", jsonOf("accept" to false)) } }, small = true)
                }
            })
        }
    }
}

@Composable
private fun GuardView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reload by remember { mutableStateOf(0) }
    Box {
        Loaded(model, "agent/protect/guard", reload) { g, _ ->
            err?.let { item { Notice(it, tone = "error") } }
            item { Notice(if (g.bool("watching")) tr.t("agent.f.protect.g.watching") else tr.t("agent.f.protect.g.notWatching", "error" to g.str("error")), tone = if (g.bool("watching")) "ok" else "warn") }
            if (g.str("error").isNotEmpty()) item { Notice(g.str("error"), tone = "error") }
            val alerts = g.os("alerts")
            if (alerts.isEmpty()) item { Text(tr.t("agent.f.protect.g.calm"), style = AgentText.md, color = c.muted) }
            for (a in alerts) item {
                val client = a.o("client")
                SRow(
                    tr.t("agent.f.protect.g.what.${a.str("reason")}") + " · " + a.str("share"),
                    desc = listOf(tr.t("agent.f.protect.g.state.${a.str("state")}"), dateTime(a.str("last")), listOf(client.str("user"), client.str("ip"), client.str("machine")).filter { it.isNotEmpty() }.joinToString(" · ")).filter { it.isNotEmpty() }.joinToString(" · "),
                    icon = "shield", badge = { if (a.bool("locked")) Tag(tr.t("agent.f.protect.g.locked", "share" to a.str("share")), tone = "warn") },
                ) {
                    for (f in a.os("files").take(20)) Text(f.str("was") + " → " + f.str("now"), style = AgentText.sm, color = c.text)
                    if (a.int("more") > 0) Text(tr.t("agent.f.protect.g.more", "count" to a.int("more")), style = AgentText.sm, color = c.muted)
                    if (model.status?.admin == true) Buttons {
                        if (a.str("state") == "active" || a.str("state") == "stopped") PillButton(tr.t("agent.f.protect.g.restoreBtn"), { confirm = a.str("id") to "restore" }, kind = Btn.Primary, small = true)
                        if (a.bool("locked")) PillButton(tr.t("agent.f.protect.g.reopenBtn"), { confirm = a.str("id") to "reopen" }, small = true)
                        PillButton(tr.t("agent.f.protect.g.falseAlarm"), { confirm = a.str("id") to "dismiss" }, small = true)
                    }
                }
            }
        }
        val cf = confirm
        AgentDialog(cf != null, cf?.let { tr.t("agent.f.protect.g.confirm.${it.second}") } ?: "", { confirm = null }, text = cf?.let { tr.t("agent.f.protect.g.confirmText.${it.second}") }, actions = {
            PillButton(cf?.let { tr.t("agent.f.protect.g.confirmBtn.${it.second}") } ?: "", { val x = confirm; confirm = null; if (x != null) act(model, scope, tr, { err = it }, { reload++ }) { model.api.sendJson("POST", "agent/protect/guard/${x.first}/${x.second}") } }, kind = Btn.Danger)
            PillButton(tr.t("common.cancel"), { confirm = null })
        })
    }
}

@Composable
private fun CatalogView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    fun tk(k: String, vararg p: Pair<String, Any?>) = tr.t("agent.f.family.catalog.$k", *p)
    Loaded(model, "agent/catalog") { v, reload ->
        err?.let { item { Notice(it, tone = "error") } }
        item { Text(tk("lead"), style = AgentText.sm, color = c.muted) }
        val skills = v.os("skills")
        if (skills.isEmpty()) item { Text(tk("empty"), style = AgentText.md, color = c.muted) }
        for (s in skills) item {
            SRow(s.str("name"), desc = listOf(tk("from", "name" to s.str("sourceName")), s.str("author").takeIf { it.isNotEmpty() }?.let { tk("by", "name" to it) }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "), icon = "zap",
                badge = { if (s.bool("installed")) Tag(tk("isInstalled"), tone = "ok") }) {
                if (s.str("description").isNotEmpty()) Text(s.str("description"), style = AgentText.md, color = c.text)
                Text(tk("when") + ": " + s.str("when"), style = AgentText.sm, color = c.muted)
                Text(s.str("steps"), style = AgentText.sm, color = c.text)
                val tools = s.strs("tools")
                Text(if (tools.isEmpty()) tk("noTools") else tk("uses", "list" to tools.joinToString(", ")), style = AgentText.sm, color = c.muted)
                if (s.strs("unknown").isNotEmpty()) Notice(tk("unknown", "list" to s.strs("unknown").joinToString(", ")), tone = "warn")
                PillButton(if (s.bool("installed")) tk("update") else tk("install"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/catalog/install", jsonOf("key" to s.str("key"), "digest" to s.str("digest"))) } }, kind = Btn.Primary, small = true)
            }
        }
    }
}
