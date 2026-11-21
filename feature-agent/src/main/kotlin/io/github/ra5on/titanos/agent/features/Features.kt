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
                            Switch(docs.bool("on"), { mine(m.sub("docs", "on", it)) })
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
                            Switch(!docs.bool("noScans"), { mine(m.sub("docs", "noScans", !it)) })
                        }
                        PillButton(tr.t("agent.f.docs.runNow"), { scope.launch { runCatching { model.api.sendJson("POST", "agent/docs/run") }; st.notice = tr.t("agent.f.docs.state.running") } }, small = true)
                    }
                }
                SRow(tr.t("agent.f.docs.deadlines"), desc = if (docs.str("mode") == "remind") tr.t("agent.f.docs.deadlinesSubRemind") else tr.t("agent.f.docs.deadlinesSub"), icon = "clock", control = { nasSwitch("docs", "deadlines") }) {
                    if (!p.bool("deadlines")) Text(tr.t("agent.f.notThere"), style = AgentText.sm, color = c.muted) else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.docs.findDeadlines"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.docs.findHint"), style = AgentText.sm, color = c.muted) }
                            Switch(docs.bool("deadlines"), { mine(m.sub("docs", "deadlines", it)) })
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
                            Switch(paper.bool("on"), { mine(m.sub("paper", "on", it)) })
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
                            Switch(!g.bool("noPreSnapshots"), { nas(n.sub("guard", "noPreSnapshots", !it)) })
                        }
                        var ex by remember(g) { mutableStateOf(g.strs("exclude").joinToString("\n")) }
                        Field(ex, { ex = it }, multiline = true, label = tr.t("agent.f.protect.s.exclude"), placeholder = tr.t("agent.f.protect.s.exclude"))
                        Text(tr.t("agent.f.protect.s.excludeHint"), style = AgentText.sm, color = c.muted)
                        PillButton(tr.t("common.save"), { nas(n.sub("guard", "exclude", ex.lines().map { it.trim() }.filter { it.isNotEmpty() })) }, small = true)
                    }
                    PillButton(tr.t("agent.f.protect.g.openAlerts"), { onFeature("guard") }, small = true)
                }
                val h = n.o("handbook")
                SRow(tr.t("agent.f.protect.handbook"), desc = tr.t("agent.f.protect.h.rowSub"), icon = "fileText", control = { nasSwitch("handbook") }) {
                    if (admin) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.protect.s.probes"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.protect.s.probesHint"), style = AgentText.sm, color = c.muted) }
                            Switch(h.bool("probes"), { nas(n.sub("handbook", "probes", it)) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(tr.t("agent.f.protect.s.contacts"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.protect.s.contactsHint"), style = AgentText.sm, color = c.muted) }
                            Switch(h.bool("withContacts"), { nas(n.sub("handbook", "withContacts", it)) })
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
                SRow(tr.t("agent.f.protect.emergency"), desc = tr.t("agent.f.protect.e.rowSub"), icon = "key", control = { nasSwitch("emergency") }) {
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
                            Switch(em.bool("code"), { nas(n.sub("emergency", "code", it)) })
                        }
                    }
                    PillButton(tr.t("agent.f.menu.emergency"), { onFeature("emergency") }, small = true)
                }
            }

            // ---------- Vorausschau ----------
            SGroup(tr.t("agent.f.group.foresight")) {
                SRow(tr.t("agent.f.foresight.whatIf"), desc = tr.t("agent.f.foresight.whatIfSub"), icon = "activity", control = { nasSwitch("whatIf") }) {
                    Text(tr.t("agent.f.foresight.whatIfIntro"), style = AgentText.sm, color = c.muted)
                    Text(tr.t("agent.f.foresight.whatIfWhere"), style = AgentText.sm, color = c.muted)
                    Text(tr.t("agent.f.foresight.whatIfData"), style = AgentText.sm, color = c.muted)
                }
                val a = n.o("advisor")
                SRow(tr.t("agent.f.foresight.advisor"), desc = tr.t("agent.f.foresight.advisorSub"), icon = "chip", control = { nasSwitch("advisor") }) {
                    if (admin) for (k in listOf("predict", "bottlenecks", "prices", "monthly")) Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(tr.t("agent.f.foresight.set.$k"), style = AgentText.md, color = c.text); Text(tr.t("agent.f.foresight.set.${k}Sub"), style = AgentText.sm, color = c.muted) }
                        Switch(a.bool(k, true), { nas(n.sub("advisor", k, it)) })
                    }
                    if (admin && a.bool("monthly", true)) {
                        var at by remember(a) { mutableStateOf(a.str("at", "09:00")) }
                        Field(at, { at = it.take(5); if (Regex("^\\d\\d:\\d\\d$").matches(it)) nas(n.sub("advisor", "at", it)) }, Modifier.width(120.dp), label = tr.t("agent.f.foresight.set.at"), keyboard = KeyboardType.Number)
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
                        var time by remember(b) { mutableStateOf(b.str("time", "02:00")) }
                        Field(time, { time = it.take(5); if (Regex("^\\d\\d:\\d\\d$").matches(it)) nas(n.sub("buddy", "time", it)) }, Modifier.width(120.dp), label = tr.t("agent.f.family.buddy.time"), keyboard = KeyboardType.Number)
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
            Switch(mail.bool("enabled"), { mail = mail.put("enabled", it) })
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

private fun euro(cents: Long, de: Boolean): String = NumberFormat.getCurrencyInstance(if (de) Locale.GERMANY else Locale.UK).apply { currency = Currency.getInstance("EUR") }.format(cents / 100.0)

/**
 * Die Ansicht einer Funktion aus dem Menü (FeatureView): Papierkram, Fristen,
 * Wächter, Notfallhandbuch, Notfallzugang, Berater, Backup-Partner, Katalog.
 */
@Composable
fun FeatureView(model: AgentModel, id: String, onClose: () -> Unit) {
    val tr = tr()
    val title = tr.t("agent.f.menu.$id")
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHead(title, onClose)
        when (id) {
            "paper" -> PaperView(model)
            "deadlines" -> DeadlinesView(model)
            "guard" -> GuardView(model)
            "handbook" -> HandbookView(model)
            "emergency" -> EmergencyView(model)
            "advisor" -> AdvisorView(model)
            "buddy" -> BuddyView(model)
            "catalog" -> CatalogView(model)
        }
    }
}

/** Lädt eine Ansicht und zeigt Fehler; [body] baut die Liste. */
@Composable
private fun Loaded(model: AgentModel, path: String, reload: Int = 0, body: LazyListScope.(JsonObject, () -> Unit) -> Unit) {
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

private fun act(model: AgentModel, scope: kotlinx.coroutines.CoroutineScope, tr: Tr, onError: (String) -> Unit, then: () -> Unit, block: suspend () -> Unit) {
    scope.launch { try { block(); then() } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(problemText(tr, problemOf(e))) } }
}

@Composable
private fun PaperView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var month by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    Loaded(model, "agent/paper" + if (month.isNotEmpty()) "?month=$month" else "") { s, reload ->
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
                    PillButton(tr.t("agent.f.docs.paid"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/paper/${e.str("id")}/paid", jsonOf("paid" to true)) } }, small = true)
                })
            }
        }
        item {
            SGroup(tr.t("agent.f.docs.recent")) {
                val recent = s.os("recent")
                if (recent.isEmpty()) Text(tr.t("agent.f.docs.noneRecent"), style = AgentText.md, color = c.muted)
                for (e in recent) SRow(e.str("name"), desc = listOf(e.str("sender"), tr.t("agent.f.docs.type.${e.str("type")}"), dateTime(e.str("at"))).filter { it.isNotEmpty() }.joinToString(" · "), icon = "fileText", control = {
                    Buttons { PillButton(tr.t("agent.f.docs.undo"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/paper/${e.str("id")}/undo", JsonObject(emptyMap())) } }, small = true) }
                })
            }
        }
        item { PillButton(tr.t("agent.f.docs.lookNow"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/paper/run") } }, small = true) }
    }
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
            item { Notice(if (g.bool("watching")) tr.t("agent.f.protect.g.watching") else tr.t("agent.f.protect.g.notWatching"), tone = if (g.bool("watching")) "ok" else "warn") }
            if (g.str("error").isNotEmpty()) item { Notice(g.str("error"), tone = "error") }
            val alerts = g.os("alerts")
            if (alerts.isEmpty()) item { Text(tr.t("agent.f.protect.g.calm"), style = AgentText.md, color = c.muted) }
            for (a in alerts) item {
                val client = a.o("client")
                SRow(
                    tr.t("agent.f.protect.g.what.${a.str("reason")}") + " · " + a.str("share"),
                    desc = listOf(tr.t("agent.f.protect.g.state.${a.str("state")}"), dateTime(a.str("last")), listOf(client.str("user"), client.str("ip"), client.str("machine")).filter { it.isNotEmpty() }.joinToString(" · ")).filter { it.isNotEmpty() }.joinToString(" · "),
                    icon = "shield", badge = { if (a.bool("locked")) Tag(tr.t("agent.f.protect.g.locked"), tone = "warn") },
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
private fun HandbookView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    Loaded(model, "agent/protect/handbook?lang=" + tr.lang) { h, reload ->
        err?.let { item { Notice(it, tone = "error") } }
        item {
            Text(tr.t("agent.f.protect.h.made", "when" to dateTime(h.str("made"))), style = AgentText.sm, color = c.muted)
            if (model.status?.admin == true) PillButton(tr.t("agent.f.protect.h.refresh"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/protect/handbook/refresh") } }, small = true, modifier = Modifier.padding(top = 6.dp))
        }
        for (s in h.os("sections")) item {
            SRow(s.str("title"), desc = s.str("sub"), icon = "fileText") {
                s.strs("steps").forEachIndexed { i, step -> Text("${i + 1}. $step", style = AgentText.md, color = c.text) }
            }
        }
        val facts = h.o("facts")
        for ((k, label) in listOf("shares" to "agent.f.protect.h.shares", "apps" to "agent.f.protect.h.apps", "network" to "agent.f.protect.h.network", "remote" to "agent.f.protect.h.remote", "backups" to "agent.f.protect.h.backups", "probes" to "agent.f.protect.h.probes")) {
            val list = facts[k].objs()
            if (list.isNotEmpty()) item {
                SGroup(tr.t(label)) { for (f in list) SRow(f.str("name"), desc = f.str("detail").ifEmpty { null }) }
            }
        }
        val contacts = facts["contacts"].objs()
        if (contacts.isNotEmpty()) item { SGroup(tr.t("agent.f.protect.h.contacts")) { for (ct in contacts) SRow(ct.str("name"), desc = listOf(ct.str("phone"), ct.str("note")).filter { it.isNotEmpty() }.joinToString(" · ")) } }
    }
}

@Composable
private fun EmergencyView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var codeShown by remember { mutableStateOf<String?>(null) }
    val admin = model.status?.admin == true
    Loaded(model, if (admin) "agent/protect/emergency" else "agent/protect/me") { v, reload ->
        err?.let { item { Notice(it, tone = "error") } }
        codeShown?.let { item { Notice(tr.t("agent.f.protect.e.codeText") + "\n" + it, tone = "ok") } }
        if (admin) {
            item { Text(tr.t("agent.f.protect.e.explain"), style = AgentText.sm, color = c.muted) }
            item {
                SGroup(tr.t("agent.f.protect.e.persons")) {
                    val persons = v.os("persons")
                    if (persons.isEmpty()) Text(tr.t("agent.f.protect.e.noPersons"), style = AgentText.md, color = c.muted)
                    for (p in persons) SRow(p.str("account"), desc = tr.t("agent.f.protect.e.personSub", "days" to p.int("waitDays"), "shares" to p.strs("shares").joinToString(", ")), icon = "user", control = {
                        if (v.bool("code")) PillButton(tr.t("agent.f.protect.e.newCode"), { act(model, scope, tr, { err = it }, {}) { codeShown = model.api.sendJson("POST", "agent/protect/emergency/code/${p.str("account")}").obj().str("code") } }, small = true)
                    })
                }
            }
            item {
                SGroup(tr.t("agent.f.protect.e.openRequests")) {
                    for (r in v.os("requests")) SRow(r.str("account"), desc = listOf(tr.t("agent.f.protect.e.state.${r.str("state")}"), r.str("reason"), dateTime(r.str("created"))).filter { it.isNotEmpty() }.joinToString(" · "), icon = "key", control = {
                        Buttons {
                            if (r.str("state") == "waiting") {
                                PillButton(tr.t("agent.f.protect.e.grantNow"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/protect/emergency/${r.str("id")}/grant") } }, kind = Btn.Primary, small = true)
                                PillButton(tr.t("agent.f.protect.e.decline"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/protect/emergency/${r.str("id")}/decline") } }, small = true)
                            }
                            if (r.str("state") == "granted") PillButton(tr.t("agent.f.protect.e.endNow"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/protect/emergency/${r.str("id")}/end") } }, kind = Btn.Danger, small = true)
                        }
                    })
                }
            }
        } else {
            val req = v.o("request")
            item {
                if (req.isNotEmpty()) SRow(tr.t("agent.f.protect.e.state.${req.str("state")}"), desc = req.str("reason") + " · " + dateTime(req.str("due")), icon = "key", control = {
                    if (req.str("state") == "waiting") PillButton(tr.t("agent.f.protect.e.withdraw"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/protect/emergency/${req.str("id")}/cancel") } }, small = true)
                }) else if (v.o("person").isEmpty()) Text(tr.t("agent.f.protect.e.notRegistered"), style = AgentText.md, color = c.muted)
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(reason, { reason = it }, multiline = true, label = tr.t("agent.f.protect.e.reasonLabel"), placeholder = tr.t("agent.f.protect.e.reason"))
                    PillButton(tr.t("agent.f.protect.e.ask"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/protect/emergency/request", jsonOf("reason" to reason, "code" to "")) } }, kind = Btn.Primary, enabled = reason.isNotBlank())
                }
            }
        }
    }
}

@Composable
private fun AdvisorView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    fun tk(k: String, vararg p: Pair<String, Any?>) = tr.t("agent.f.foresight.$k", *p)
    Loaded(model, "agent/advisor") { v, reload ->
        err?.let { item { Notice(it, tone = "error") } }
        val saved = v.o("saved")
        item {
            Text(if (saved.isNotEmpty()) tk("lastRun", "when" to dateTime(saved.str("lastRun"))) else tk("empty"), style = AgentText.sm, color = c.muted)
            PillButton(if (v.bool("running")) tk("running") else tk("run"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/advisor/run", JsonObject(emptyMap())) } }, enabled = !v.bool("running"), small = true, modifier = Modifier.padding(top = 6.dp))
        }
        val findings = saved.o("report").os("findings").filter { !(it.str("area") == "disk" && it.str("level") == "ok") }
        val icon = mapOf("disk" to "💽", "memory" to "🧠", "volume" to "📦", "cpu" to "⚙️", "gpu" to "🎮", "temperature" to "🌡️")
        for (f in findings) item {
            fun n(k: String) = String.format(Locale.getDefault(), "%.1f", f.num(k))
            val p = arrayOf("from" to n("from"), "to" to n("to"), "weeks" to n("weeks"), "percent" to n("percent"), "days" to n("days"), "hours" to n("hours"), "label" to f.str("label"), "celsius" to n("celsius"))
            val text = when (f.str("code")) {
                "wornOut" -> tk(if (f.containsKey("days")) "c.wornOutDays" else "c.wornOut", *p)
                "gpuOK", "gpuFull" -> if (!f.containsKey("bytes")) tk("c.gpuUnknown", "total" to Format.bytes(f.long("totalBytes"))) else tk(if (f.str("code") == "gpuFull") "c.gpuFull" else if (f.bool("ollama")) "c.gpuOllama" else "c.gpuOK", "used" to Format.bytes(f.long("bytes")), "total" to Format.bytes(f.long("totalBytes")))
                else -> tk("c." + f.str("code"), *p)
            }
            val tone = when (f.str("level")) { "ok" -> "ok"; "fail" -> "error"; else -> "warn" }
            SRow((icon[f.str("area")] ?: "") + " " + tk("area." + f.str("area"), "name" to f.str("subject")), desc = text, control = { Tag(tk("lv." + f.str("level")), tone = tone) })
        }
        item { Text(if (v.bool("web")) tk("footer") else tk("footerNoPrices"), style = AgentText.sm, color = c.muted) }
    }
}

@Composable
private fun BuddyView(model: AgentModel) {
    val tr = tr()
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var quota by remember { mutableStateOf("100") }
    fun tk(k: String, vararg p: Pair<String, Any?>) = tr.t("agent.f.family.buddy.$k", *p)
    Loaded(model, "agent/buddy") { v, reload ->
        err?.let { item { Notice(it, tone = "error") } }
        if (v.str("problem").isNotEmpty()) item { Notice(tr.or("agent.f.family.buddy.problem.${v.str("problem")}", v.str("problem")), tone = "warn") }
        item {
            SGroup(tk("partners").let { if (it.startsWith("agent.")) tk("title") else it }) {
                for (p in v.os("partners")) {
                    val job = p.o("job")
                    SRow(p.str("name"), desc = listOfNotNull(p.str("address"), Format.bytes(p.long("used")) + " / " + Format.bytes(p.long("quota")), job.str("lastOK").takeIf { it.isNotEmpty() }?.let { dateTime(it) }, job.str("lastError").ifEmpty { null }).joinToString(" · "), icon = "users", control = {
                        PillButton(tr.t("common.delete"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("DELETE", "agent/buddy/partners/${p.str("id")}") } }, kind = Btn.Danger, small = true)
                    })
                }
            }
        }
        item {
            SGroup(tk("code").let { if (it.startsWith("agent.")) "Code" else it }) {
                val cd = v.o("code")
                if (cd.isNotEmpty()) Notice(cd.str("code").replace(Regex("(.{3})(?=.)"), "$1-") + " · " + dateTime(cd.str("expires")), tone = "ok")
                Field(quota, { quota = it.filter(Char::isDigit) }, label = "GB", keyboard = KeyboardType.Number)
                Buttons {
                    PillButton(tr.or("agent.f.family.buddy.makeCode", "Code"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/buddy/code", jsonOf("quota" to (quota.toLongOrNull() ?: 100) * 1_000_000_000L, "shares" to v.strs("shares"))) } }, small = true)
                    if (cd.isNotEmpty()) PillButton(tr.t("common.cancel"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("DELETE", "agent/buddy/code") } }, small = true)
                }
                Field(address, { address = it }, label = tk("address"), placeholder = tk("address"))
                Field(code, { code = it }, label = "Code", placeholder = "ABC-DEF-GHI")
                PillButton(tr.or("agent.f.family.buddy.pair", "Koppeln"), { act(model, scope, tr, { err = it }, reload) { model.api.sendJson("POST", "agent/buddy/pair", jsonOf("address" to address, "code" to code.replace("-", ""), "quota" to (quota.toLongOrNull() ?: 100) * 1_000_000_000L, "shares" to v.strs("shares"))) } }, kind = Btn.Primary, enabled = address.isNotBlank() && code.isNotBlank(), small = true)
            }
        }
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
