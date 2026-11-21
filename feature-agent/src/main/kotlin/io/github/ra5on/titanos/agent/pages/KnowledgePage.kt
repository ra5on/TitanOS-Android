package io.github.ra5on.titanos.agent.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.Project
import io.github.ra5on.titanos.agent.chat.dateTime
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.core.a
import io.github.ra5on.titanos.agent.core.arr
import io.github.ra5on.titanos.agent.core.bool
import io.github.ra5on.titanos.agent.core.json
import io.github.ra5on.titanos.agent.core.jsonOf
import io.github.ra5on.titanos.agent.core.o
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.objs
import io.github.ra5on.titanos.agent.core.os
import io.github.ra5on.titanos.agent.core.str
import io.github.ra5on.titanos.agent.core.strs
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.ui.AgentColors
import io.github.ra5on.titanos.agent.ui.AgentDialog
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.Buttons
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.Lead
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PagePadding
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.SGroup
import io.github.ra5on.titanos.agent.ui.SRow
import io.github.ra5on.titanos.agent.ui.Switch
import io.github.ra5on.titanos.agent.ui.Tag
import io.github.ra5on.titanos.agent.ui.rememberDoc
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private data class SkillEdit(val id: String, val name: String, val whenText: String, val steps: String)

/** Ein mitgelieferter Skill auf Deutsch (skillIn im Web). */
fun skillIn(s: JsonObject, de: Boolean): JsonObject {
    val d = s.o("de")
    if (!s.bool("builtIn") || d.isEmpty() || !de) return s
    return JsonObject(s + mapOf("name" to json(d.str("name")), "when" to json(d.str("when")), "steps" to json(d.str("steps"))))
}

/**
 * Wissen (KnowledgePage.svelte): eigene Anweisung und Projekt-Anweisungen,
 * Skills mit An/Aus, Neu, Bearbeiten, Löschen, die NAS-Landkarte, wo er
 * sucht, und (für Admins) das Protokoll seiner Änderungen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnowledgePage(model: AgentModel, onEditProject: (Project) -> Unit, onOpenProject: (Project) -> Unit) {
    val c = Titan.colors
    val tr = tr()
    val scope = rememberCoroutineScope()
    val admin = model.status?.admin == true
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var skills by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var profile by remember { mutableStateOf("") }
    var savedProfile by remember { mutableStateOf("") }
    var log by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var roots by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var editing by remember { mutableStateOf<SkillEdit?>(null) }
    var removing by remember { mutableStateOf<JsonObject?>(null) }
    val ini = rememberDoc(model, "agent/initiative", initiativeDefaults(), wait = 0)

    suspend fun loadSkills() {
        try { skills = model.api.getJson("agent/skills").obj().os("skills") } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
    }
    fun run(block: suspend () -> Unit) {
        scope.launch { error = null; try { block(); editing = null; loadSkills() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } }
    }
    LaunchedEffect(Unit) {
        loadSkills()
        runCatching { profile = model.api.getJson("agent/profile").obj().str("instruction"); savedProfile = profile }
        runCatching { roots = model.api.getJson("files/roots").objs().filter { it.str("kind") != "stick" } }
        if (admin) runCatching { log = model.api.getJson("activity", listOf("via" to "agent", "days" to "30", "limit" to "200")).obj().os("entries") }
    }
    val q = query.trim().lowercase()
    fun hit(vararg texts: String?) = q.isEmpty() || texts.any { (it ?: "").lowercase().contains(q) }
    val shownSkills = skills.map { skillIn(it, tr.de) }.filter { hit(it.str("name"), it.str("when"), it.str("steps")) }
    val withText = model.projects.filter { !it.instructions.isNullOrBlank() }
    val shownProjects = model.projects.filter { it.owner == null && hit(it.name, it.instructions) }
    fun lines(t: String) = t.split("\n").count { it.isNotBlank() }
    val search = ini.obj["search"].objs()
    fun searched(id: String) = search.any { it.str("root") == id && it.str("path").isEmpty() }

    @Composable
    fun skillForm(e: SkillEdit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(e.name, { editing = e.copy(name = it.take(80)) }, label = tr.t("agent.skillName"), placeholder = tr.t("agent.skillName"))
            Field(e.whenText, { editing = e.copy(whenText = it) }, label = tr.t("agent.skillWhen"), placeholder = tr.t("agent.skillWhen"))
            Field(e.steps, { editing = e.copy(steps = it) }, multiline = true, minHeight = 120.dp, label = tr.t("agent.skillSteps"), placeholder = tr.t("agent.skillSteps"))
            Buttons {
                PillButton(tr.t("common.save"), { run { model.api.sendJson("PUT", "agent/skills/${e.id}", jsonOf("name" to e.name, "when" to e.whenText, "steps" to e.steps)) } }, kind = Btn.Primary)
                PillButton(tr.t("common.cancel"), { editing = null })
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Lead(tr.t("agent.know.lead")) }
            error?.let { item { Notice(it, tone = "error") } }
            item { Field(query, { query = it }, placeholder = tr.t("agent.know.search"), leading = "search") }
            if (hit(tr.t("agent.me"), profile) || shownProjects.isNotEmpty()) item("given") {
                SGroup(tr.t("agent.know.given")) {
                    if (hit(tr.t("agent.me"), profile)) SRow(
                        tr.t("agent.me"), icon = "chat",
                        desc = savedProfile.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { "„$it“" } ?: tr.t("agent.meIntro"),
                    ) {
                        Text(tr.t("agent.meIntro"), style = AgentText.sm, color = c.muted)
                        Field(profile, { profile = it.take(4000) }, multiline = true, minHeight = 120.dp, label = tr.t("agent.me"), placeholder = tr.t("agent.meHint"))
                        PillButton(tr.t("common.save"), { scope.launch { try { model.api.sendJson("PUT", "agent/profile", jsonOf("instruction" to profile)); savedProfile = profile } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } } }, kind = Btn.Primary, enabled = profile != savedProfile)
                    }
                    if (model.projects.any { it.owner == null } && (shownProjects.isNotEmpty() || q.isEmpty())) SRow(
                        tr.t("agent.know.projects"), icon = "folder", initiallyOpen = q.isNotEmpty(),
                        desc = if (withText.isNotEmpty()) withText.joinToString(", ") { it.name } else tr.t("agent.know.projectsNone"),
                    ) {
                        for (p in shownProjects) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${p.icon ?: "📁"} ${p.name}", style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                            Text(p.instructions?.trim()?.ifEmpty { null } ?: tr.t("agent.know.projectEmpty"), style = AgentText.sm, color = c.muted)
                            Buttons {
                                PillButton(tr.t("agent.know.projectOpen"), { onOpenProject(p) }, small = true)
                                PillButton(tr.t("agent.edit"), { onEditProject(p) }, small = true)
                            }
                        }
                    }
                }
            }
            if (shownSkills.isNotEmpty() || q.isEmpty()) item("skills") {
                SGroup(tr.t("agent.know.skills"), tools = { PillButton(tr.t("agent.know.skillAdd"), { editing = SkillEdit("new", "", "", "") }, icon = "plus", small = true) }) {
                    editing?.takeIf { it.id == "new" }?.let { e -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AgentColors.srowBg).padding(16.dp)) { skillForm(e) } }
                    if (shownSkills.isEmpty()) Text(tr.t("agent.know.noSkill"), style = AgentText.md, color = c.muted)
                    for (s in shownSkills) {
                        val n = lines(s.str("steps"))
                        SRow(
                            s.str("name"), icon = "zap",
                            desc = s.str("when") + " · " + if (n == 1) tr.t("agent.know.stepOne") else tr.t("agent.know.steps", "count" to n),
                            initiallyOpen = editing?.id == s.str("id"),
                            badge = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (s.bool("untrusted")) Tag(tr.t("agent.untrusted"), tone = "warn")
                                    if (s.bool("disabled")) Tag(tr.t("agent.off"))
                                }
                            },
                            control = {
                                Switch(!s.bool("disabled"), { on ->
                                    val raw = skills.firstOrNull { it.str("id") == s.str("id") } ?: s
                                    run { model.api.sendJson("PUT", "agent/skills/${s.str("id")}", JsonObject(raw + ("disabled" to json(!on)))) }
                                })
                            },
                        ) {
                            val e = editing
                            if (e?.id == s.str("id")) skillForm(e) else {
                                Text(s.str("steps"), style = AgentText.sm.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp), color = c.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.chip).padding(10.dp))
                                Text(if (s.bool("builtIn")) tr.t("agent.builtIn") else if (s.bool("untrusted")) tr.t("agent.untrusted") else tr.t("agent.know.own"), style = AgentText.sm, color = c.muted)
                                if (!s.bool("builtIn")) Buttons {
                                    PillButton(tr.t("agent.edit"), { editing = SkillEdit(s.str("id"), s.str("name"), s.str("when"), s.str("steps")) }, small = true)
                                    PillButton(tr.t("common.delete"), { removing = s }, kind = Btn.Danger, small = true)
                                }
                            }
                        }
                    }
                }
            }
            if (q.isEmpty() || hit(tr.t("agent.map"), tr.t("agent.know.docs"), tr.t("agent.log"))) item("nas") {
                SGroup(tr.t("agent.know.nas")) {
                    if (hit(tr.t("agent.map"))) SRow(tr.t("agent.map"), desc = tr.t("agent.know.mapDesc"), icon = "disk") { NasMap(model, admin) }
                    if (hit(tr.t("agent.know.docs"))) SRow(tr.t("agent.know.docs"), desc = tr.t("agent.init.searchHint"), icon = "fileText",
                        summary = tr.t("agent.know.docsOf", "count" to roots.count { searched(it.str("id")) }, "total" to roots.size)) {
                        if (roots.isEmpty()) Text(tr.t("agent.know.docsNone"), style = AgentText.sm, color = c.muted)
                        for (r in roots) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.str("name"), style = AgentText.md, color = c.text, modifier = Modifier.weight(1f))
                            Switch(searched(r.str("id")), { on ->
                                val id = r.str("id")
                                val next = search.filter { it.str("root") != id } + if (on) listOf(jsonOf("root" to id, "path" to "")) else emptyList()
                                ini.set("search" to JsonArray(next), now = true)
                            }, enabled = ini.loaded, label = r.str("name"))
                        }
                    }
                    if (admin && hit(tr.t("agent.log"))) SRow(tr.t("agent.log"), desc = tr.t("agent.logIntro"), icon = "list", summary = tr.t("agent.know.logCount", "count" to log.size)) {
                        if (log.isEmpty()) Text(tr.t("agent.logEmpty"), style = AgentText.sm, color = c.muted)
                        for (e in log) Column {
                            Text(e.str("action") + " " + e.str("target"), style = AgentText.md, color = c.text)
                            Text(dateTime(e.str("at")) + " · " + e.str("user") + " · " + if (e.bool("ok")) "✓" else tr.t("agent.failed"), style = AgentText.sm, color = c.muted)
                        }
                    }
                }
            }
        }
        AgentDialog(removing != null, tr.t("agent.know.skillDelete"), { removing = null }, text = "„" + (removing?.str("name") ?: "") + "“", actions = {
            PillButton(tr.t("common.delete"), { val id = removing?.str("id"); removing = null; if (id != null) run { model.api.sendJson("DELETE", "agent/skills/$id") } }, kind = Btn.Danger)
            PillButton(tr.t("common.cancel"), { removing = null })
        })
    }
}

/** Die NAS-Landkarte (NASMap.svelte): Freigaben mit Ordnern, Apps, VMs, Systemcontainer, Sicherungen, Räume, mit Beschreibungen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NasMap(model: AgentModel, admin: Boolean) {
    val c = Titan.colors
    val tr = tr()
    val scope = rememberCoroutineScope()
    var map by remember { mutableStateOf<JsonObject?>(null) }
    var built by remember { mutableStateOf(false) }
    var desc by remember { mutableStateOf(JsonObject(emptyMap())) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    fun load(refresh: Boolean) {
        scope.launch {
            busy = true; error = null
            try {
                val r = (if (refresh) model.api.sendJson("POST", "agent/map/refresh") else model.api.getJson("agent/map")).obj()
                map = r.o("map"); built = r.bool("built"); desc = r.o("descriptions")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { load(false) }

    @Composable
    fun note(key: String) {
        if (editing == key) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(draft, { draft = it.take(300) }, label = tr.t("agent.mapDescribe"))
                Buttons {
                    PillButton(tr.t("common.save"), {
                        scope.launch {
                            try { desc = model.api.sendJson("PUT", "agent/descriptions", jsonOf("key" to key, "text" to draft)).obj().o("descriptions"); editing = "" } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
                        }
                    }, kind = Btn.Primary, small = true)
                    PillButton(tr.t("common.cancel"), { editing = "" }, small = true)
                }
            }
        } else {
            val d = desc.o(key)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (d.isNotEmpty()) {
                    Text("– " + d.str("text"), style = AgentText.sm, color = c.muted)
                    if (d.str("by").startsWith("agent")) Tag(tr.t("agent.mapByAgent"))
                }
                if (admin) Text(if (d.isNotEmpty()) tr.t("agent.edit") else tr.t("agent.mapDescribe"), style = AgentText.sm, color = AgentColors.link, modifier = Modifier.clickable { editing = key; draft = d.str("text") }.padding(vertical = 6.dp))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr.t("agent.mapIntro"), style = AgentText.sm, color = c.muted)
        val m = map
        Text(if (built && m != null) tr.t("agent.mapBuilt", "when" to dateTime(m.str("built"))) else tr.t("agent.mapNotBuilt"), style = AgentText.sm, color = c.muted)
        if (admin) PillButton(if (busy) tr.t("agent.loading") else tr.t("agent.mapRefresh"), { load(true) }, enabled = !busy, small = true)
        error?.let { Notice(it, tone = "error") }
        if (m != null) {
            val shares = m.os("shares")
            if (shares.isNotEmpty()) {
                Text(tr.t("agent.mapShares"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                for (s in shares) {
                    Text(s.str("name") + "  " + s.str("volume"), style = AgentText.md, color = c.text)
                    note("share:" + s.str("name"))
                    for (f in s.strs("folders")) { Text("📁 $f", style = AgentText.sm, color = c.text, modifier = Modifier.padding(start = 14.dp)); Box(Modifier.padding(start = 14.dp)) { note("folder:" + s.str("root") + "/" + f) } }
                }
            }
            for ((label, prefix) in listOf("apps" to "app:", "vms" to "vm:", "lxc" to "lxc:", "backups" to "backup:")) {
                val items = m.os(label)
                if (items.isEmpty()) continue
                Text(tr.t("agent.map.$label"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                for (it in items) {
                    Text(it.str("name"), style = AgentText.md, color = c.text)
                    val more = listOf(it.str("state"), it.str("address"), it.str("detail")).filter { s -> s.isNotEmpty() }.joinToString(" · ")
                    if (more.isNotEmpty()) Text(more, style = AgentText.sm, color = c.muted)
                    note(prefix + it.str("name"))
                }
            }
            val rooms = m.strs("rooms")
            if (rooms.isNotEmpty()) {
                Text(tr.t("agent.mapRooms"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                for (r in rooms) { Text(r, style = AgentText.md, color = c.text); note("room:$r") }
            }
        }
    }
}
