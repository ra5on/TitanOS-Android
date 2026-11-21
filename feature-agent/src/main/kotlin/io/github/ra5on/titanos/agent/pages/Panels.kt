package io.github.ra5on.titanos.agent.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.Project
import io.github.ra5on.titanos.agent.chat.SmallIcon
import io.github.ra5on.titanos.agent.chat.dateTime
import io.github.ra5on.titanos.agent.chat.problemOf
import io.github.ra5on.titanos.agent.chat.problemText
import io.github.ra5on.titanos.agent.core.bool
import io.github.ra5on.titanos.agent.core.jsonOf
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.objs
import io.github.ra5on.titanos.agent.core.os
import io.github.ra5on.titanos.agent.core.str
import io.github.ra5on.titanos.agent.core.strs
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.agent.ui.AgentColors
import io.github.ra5on.titanos.agent.ui.AgentDialog
import io.github.ra5on.titanos.agent.ui.AgentSheet
import io.github.ra5on.titanos.agent.ui.AgentText
import io.github.ra5on.titanos.agent.ui.Btn
import io.github.ra5on.titanos.agent.ui.Buttons
import io.github.ra5on.titanos.agent.ui.Check
import io.github.ra5on.titanos.agent.ui.Field
import io.github.ra5on.titanos.agent.ui.FloatButton
import io.github.ra5on.titanos.agent.ui.Notice
import io.github.ra5on.titanos.agent.ui.PageHead
import io.github.ra5on.titanos.agent.ui.PagePadding
import io.github.ra5on.titanos.agent.ui.PillButton
import io.github.ra5on.titanos.agent.ui.SGroup
import io.github.ra5on.titanos.agent.ui.SRow
import io.github.ra5on.titanos.agent.ui.Segments
import io.github.ra5on.titanos.agent.ui.Select
import io.github.ra5on.titanos.agent.ui.SheetItem
import io.github.ra5on.titanos.agent.ui.SheetRule
import io.github.ra5on.titanos.agent.ui.Switch
import io.github.ra5on.titanos.agent.ui.WIcon
import io.github.ra5on.titanos.agent.core.hexColor
import io.github.ra5on.titanos.agent.ui.srowSurface
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// ---------- Projekte (ProjectList, ProjectSheet, ProjectDialog) ----------

/** Die Projekte als Liste am Platz des Chats (Leiste unten „Projekte“). */
@Composable
fun ProjectList(model: AgentModel, onOpen: (Project) -> Unit, onNew: () -> Unit) {
    val c = Titan.colors
    val tr = tr()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item { Text(tr.t("agent.projects"), style = AgentText.pageTitle, color = c.text, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)) }
        if (model.projects.isEmpty()) item { Text(tr.t("agent.tabs.noProjects"), style = AgentText.md, color = c.muted, modifier = Modifier.padding(8.dp)) }
        for (p in model.projects) item(p.id) {
            val mine = model.chats.filter { it.project == p.id && !it.archived }
            val last = mine.maxOfOrNull { it.updated }
            val count = if (mine.size == 1) tr.t("agent.tabs.chatOne") else tr.t("agent.tabs.chats", "count" to mine.size)
            Row(
                Modifier.srowSurface().clickable(role = Role.Button) { onOpen(p) }.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(p.icon?.ifEmpty { null } ?: "📁", style = AgentText.lg.copy(fontSize = 20.sp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Text(if (last != null) "$count · ${dateTime(last)}" else count, style = AgentText.sm, color = c.muted)
                }
                WIcon("chevronRight", 16.dp, c.muted)
            }
        }
        item {
            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.border, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClick = onNew).heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                WIcon("plus", 18.dp, c.text)
                Text(tr.t("agent.newProject"), style = AgentText.md, color = c.text)
            }
        }
    }
}

/** Ein Projekt (ProjectSheet): Chats, Anweisungen, Dateien, Gedächtnis; bearbeiten, neuer Chat darin. */
@Composable
fun ProjectSheet(model: AgentModel, project: Project, onClose: () -> Unit, onOpen: (String) -> Unit, onEdit: () -> Unit, onNew: () -> Unit) {
    val c = Titan.colors
    val tr = tr()
    var tab by remember { mutableStateOf("chats") }
    var files by remember { mutableStateOf<List<JsonObject>?>(null) }
    var memory by remember { mutableStateOf<List<JsonObject>?>(null) }
    val list = model.chats.filter { it.project == project.id }
    val p = model.projects.firstOrNull { it.id == project.id } ?: project
    LaunchedEffect(tab) {
        if (tab == "files" && files == null) files = runCatching { model.api.getJson("agent/projects/${p.id}/files").obj().os("files") }.getOrDefault(emptyList())
        if (tab == "memory" && memory == null) memory = runCatching { model.api.getJson("agent/memory").obj().os("memory").filter { it.str("project") == p.id } }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHead((if (!p.icon.isNullOrEmpty()) p.icon + " " else "") + p.name, onClose)
        Segments(
            listOf("chats" to tr.t("agent.chats") + if (list.isNotEmpty()) " ${list.size}" else "", "instructions" to tr.t("agent.sheetInstructions"), "files" to tr.t("agent.sheetFiles"), "memory" to tr.t("agent.sheetMemory")),
            tab, { tab = it }, Modifier.padding(horizontal = 12.dp), compact = true, fill = true,
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            when (tab) {
                "chats" -> {
                    if (list.isEmpty()) item { Text(tr.t("agent.projectEmpty"), style = AgentText.md, color = c.muted) }
                    for (ch in list) item(ch.id) { SRow(ch.title.ifEmpty { tr.t("agent.newChat") }, desc = ch.preview, onClick = { onOpen(ch.id) }) }
                }
                "instructions" -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.instructions?.ifEmpty { null } ?: tr.t("agent.sheetNoInstructions"), style = AgentText.md, color = if (p.instructions.isNullOrEmpty()) c.muted else c.text)
                        if (!p.location.isNullOrEmpty()) Text(tr.t("agent.projectLocation") + ": " + p.location, style = AgentText.sm, color = c.muted)
                    }
                }
                "files" -> {
                    val f = files
                    if (f.isNullOrEmpty()) item { Text(if (f == null) tr.t("agent.loading") else tr.t("agent.sheetNoFiles"), style = AgentText.md, color = c.muted) }
                    for (x in f.orEmpty()) item { SRow(x.str("path").substringAfterLast('/'), desc = x.str("root").removePrefix("share/") + " › " + x.str("path") + " · " + dateTime(x.str("at")), icon = "fileText") }
                }
                else -> {
                    val m = memory
                    if (m.isNullOrEmpty()) item { Text(if (m == null) tr.t("agent.loading") else tr.t("agent.memoryEmpty"), style = AgentText.md, color = c.muted) }
                    for (x in m.orEmpty()) item { SRow(x.str("text"), icon = "bulb") }
                }
            }
        }
        Buttons(Modifier.padding(12.dp)) {
            PillButton(tr.t("agent.projectEdit"), onEdit)
            PillButton(tr.t("agent.newChatIn", "name" to p.name), onNew, kind = Btn.Primary)
        }
    }
}

private val COLORS = listOf("", "#3b82f6", "#8b5cf6", "#10b981", "#f59e0b", "#ec4899", "#64748b")

/** Projekt anlegen oder bearbeiten (ProjectDialog): Name, Symbol, Farbe, Ort, Anweisungen, Mitglieder, Löschen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectDialog(model: AgentModel, project: Project?, onClose: () -> Unit) {
    val c = Titan.colors
    val tr = tr()
    val scope = rememberCoroutineScope()
    var name by remember(project) { mutableStateOf(project?.name.orEmpty()) }
    var icon by remember(project) { mutableStateOf(project?.icon.orEmpty()) }
    var color by remember(project) { mutableStateOf(project?.color.orEmpty()) }
    var location by remember(project) { mutableStateOf(project?.location.orEmpty()) }
    var instructions by remember(project) { mutableStateOf(project?.instructions.orEmpty()) }
    var members by remember(project) { mutableStateOf(project?.members.orEmpty()) }
    var accounts by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val shared = project?.owner != null
    LaunchedEffect(Unit) { accounts = runCatching { model.api.getJson("agent/accounts").obj().strs("accounts") }.getOrDefault(emptyList()) }
    fun save() {
        busy = true; error = null
        scope.launch {
            try {
                val body = jsonOf("name" to name, "icon" to icon, "color" to color, "location" to location, "instructions" to instructions, "members" to members)
                val saved = if (project != null) model.api.sendJson("PUT", "agent/projects/${project.id}", body) else model.api.sendJson("POST", "agent/projects", body)
                model.loadLists()
                saved.obj().str("id").takeIf { it.isNotEmpty() }?.let { model.project = it }
                onClose()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageHead(if (project != null) tr.t("agent.projectEdit") else tr.t("agent.newProject"), onClose)
        LazyColumn(Modifier.weight(1f), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SGroup {
                    Column(Modifier.srowSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr.t("agent.projectName"), style = AgentText.sm, color = c.muted)
                        Field(name, { name = it.take(80) }, label = tr.t("agent.projectName"))
                        Text(tr.t("agent.projectIcon"), style = AgentText.sm, color = c.muted)
                        Field(icon, { icon = it.take(4) }, Modifier.width(110.dp), label = tr.t("agent.projectIcon"), placeholder = "🏠")
                        Text(tr.t("agent.projectColor"), style = AgentText.sm, color = c.muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (col in COLORS) {
                                val on = color == col
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(hexColor(col, c.accent)).border(if (on) 3.dp else 1.dp, if (on) c.text else c.border, CircleShape)
                                        .clickable(role = Role.RadioButton) { color = col }.semantics { selected = on; contentDescription = col.ifEmpty { tr.t("agent.colorTheme") } },
                                    contentAlignment = Alignment.Center,
                                ) { if (on) WIcon("check", 16.dp, Color.White) }
                            }
                        }
                        Text(tr.t("agent.projectLocation"), style = AgentText.sm, color = c.muted)
                        Select(listOf("" to tr.t("agent.projectLocationNone")) + model.locations.map { it.str("name") to it.str("name") }, location, { location = it }, tr.t("agent.projectLocation"))
                    }
                }
            }
            item {
                SGroup(tr.t("agent.projectInstructions")) {
                    Field(instructions, { instructions = it.take(8000) }, multiline = true, minHeight = 120.dp, label = tr.t("agent.projectInstructions"), placeholder = tr.t("agent.projectInstructionsHint"))
                }
            }
            item {
                if (shared) Notice(tr.t("agent.projectShared", "name" to (project?.owner ?: "")), tone = "warn")
                else SGroup(tr.t("agent.members")) {
                    val acc = accounts
                    when {
                        acc == null -> Text("…", style = AgentText.md, color = c.muted)
                        acc.isEmpty() -> Text(tr.t("agent.membersNone"), style = AgentText.md, color = c.muted)
                        else -> for (a in acc) Row(Modifier.srowSurface().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(a, style = AgentText.md, color = c.text, modifier = Modifier.weight(1f))
                            Switch(a in members, { on -> members = if (on) members + a else members - a })
                        }
                    }
                    Text(tr.t("agent.membersHint"), style = AgentText.sm, color = c.muted)
                }
            }
            error?.let { item { Notice(it, tone = "error") } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Buttons {
                        PillButton(tr.t("common.save"), ::save, kind = Btn.Primary, enabled = !busy && !shared && name.isNotBlank())
                        PillButton(tr.t("common.cancel"), onClose, enabled = !busy)
                        if (project != null && !shared) {
                            if (confirmRemove) PillButton(tr.t("agent.projectRemoveNow"), {
                                busy = true
                                scope.launch {
                                    try { model.api.sendJson("DELETE", "agent/projects/${project.id}"); model.loadLists(); if (model.project == project.id) model.project = null; onClose() }
                                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)); busy = false }
                                }
                            }, kind = Btn.Danger, enabled = !busy)
                            else PillButton(tr.t("agent.projectRemove"), { confirmRemove = true }, kind = Btn.Danger)
                        }
                    }
                    if (confirmRemove) Text(tr.t("agent.projectRemoveHint"), style = AgentText.sm, color = c.muted)
                }
            }
        }
    }
}

// ---------- Notizen (NotesPanel) ----------

private const val PINNED = "\u0000pinned"

private data class OpenNote(val path: String, val title: String, val content: TextFieldValue, val modified: String)

private sealed interface NoteDialog {
    data object Folder : NoteDialog
    data class Rename(val path: String, val isFolder: Boolean) : NoteDialog
    data class Move(val path: String) : NoteDialog
    data class Delete(val path: String, val isFolder: Boolean) : NoteDialog
}

fun noteName(name: String) = name.replace(Regex("[\\\\/<>:\"|?*\\u0000-\\u001f]"), "-").replace(Regex("^\\.+"), "").trim().take(117)
fun notePath(folder: String, title: String) = (if (folder.isNotEmpty()) "$folder/" else "") + (noteName(title).ifEmpty { "Notiz" }) + ".md"
fun noteMove(parent: String, name: String, isFolder: Boolean): String {
    val clean = noteName(name.replace(Regex("\\.md$", RegexOption.IGNORE_CASE), "")).ifEmpty { if (isFolder) "Ordner" else "Notiz" }
    return (if (parent.isNotEmpty()) "$parent/" else "") + clean + if (isFolder) "" else ".md"
}

/** Ein Knopf der Formatleiste (applyFormat im Web): fett/kursiv umschließen, Zeilen umschalten, Linie. */
fun applyFormat(text: String, start: Int, end: Int, f: String): Triple<String, Int, Int> {
    if (f == "bold" || f == "italic") {
        val mark = if (f == "bold") "**" else "_"
        return Triple(text.substring(0, start) + mark + text.substring(start, end) + mark + text.substring(end), start + mark.length, end + mark.length)
    }
    if (f == "rule") {
        val before = text.substring(0, start)
        val ins = (if (before.isEmpty() || before.endsWith("\n")) "" else "\n") + "\n---\n\n"
        val at = start + ins.length
        return Triple(before + ins + text.substring(end), at, at)
    }
    val lineStart = text.lastIndexOf('\n', maxOf(start - 1, -1)).let { if (start == 0) 0 else it + 1 }
    var lineEnd = text.indexOf('\n', maxOf(end - if (end > start && text.getOrNull(end - 1) == '\n') 1 else 0, start))
    if (lineEnd < 0) lineEnd = text.length
    val lines = text.substring(lineStart, lineEnd).split("\n")
    val marker = if (f == "heading") "## " else if (f == "list") "- " else "- [ ] "
    fun has(l: String) = when (f) {
        "heading" -> Regex("^#{1,6} ").containsMatchIn(l)
        "list" -> Regex("^[-*] (?!\\[[ xX]] )").containsMatchIn(l)
        else -> Regex("^[-*] \\[[ xX]] ").containsMatchIn(l)
    }
    fun strip(l: String) = l.replace(Regex("^#{1,6} "), "").replace(Regex("^[-*] (\\[[ xX]] )?"), "")
    val all = lines.all { it.isBlank() || has(it) }
    val block = lines.joinToString("\n") { if (all) strip(it) else if (it.isBlank() && lines.size > 1) it else marker + strip(it) }
    val out = text.substring(0, lineStart) + block + text.substring(lineEnd)
    val delta = block.length - (lineEnd - lineStart)
    return if (lines.size == 1 && start == end) Triple(out, lineStart + block.length, lineStart + block.length) else Triple(out, lineStart, lineEnd + delta)
}

/** Die Notizen (NotesPanel.svelte): Ordner, Angeheftet, Suche, Notiz mit Formatleiste, Speichern im Hintergrund, Umbenennen, Verschieben, Löschen. */
@Composable
fun NotesPanel(model: AgentModel, onClose: () -> Unit) {
    val c = Titan.colors
    val tr = tr()
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var notes by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<OpenNote?>(null) }
    var saving by remember { mutableStateOf("idle") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<NoteDialog?>(null) }
    var input by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(0) }

    suspend fun load() {
        try { val r = model.api.getJson("agent/notes").obj(); folders = r.strs("folders"); notes = r.os("notes"); error = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
        loaded = true
    }
    suspend fun save() {
        val n = open ?: return
        try {
            val r = model.api.sendJson("PUT", "agent/notes/file", jsonOf("path" to n.path, "content" to n.content.text, "modified" to n.modified)).obj()
            if (open?.path == n.path) open = open?.copy(modified = r.str("modified"))
            saving = "saved"
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            saving = if ((e as? io.github.ra5on.titanos.net.ApiException)?.status == 409) "conflict" else "idle"
            if (saving != "conflict") error = problemText(tr, problemOf(e))
        }
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(dirty) { if (dirty > 0) { delay(700); save() } }
    DisposableEffect(Unit) { onDispose { if (saving == "saving") scope.launch { save() } } }
    fun run(block: suspend () -> Unit) { scope.launch { try { block(); dialog = null; load() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } } }
    fun openNote(path: String) {
        scope.launch {
            if (saving == "saving") save()
            try {
                val r = model.api.getJson("agent/notes/file", listOf("path" to path)).obj()
                open = OpenNote(r.str("path"), notes.firstOrNull { it.str("path") == path }?.str("title") ?: path, TextFieldValue(r.str("content")), r.str("modified"))
                saving = "idle"
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
        }
    }
    fun newNote() {
        scope.launch {
            val base = tr.t("agent.notes.untitled")
            val inFolder = if (folder.isNotEmpty() && folder != PINNED) folder else ""
            var path = notePath(inFolder, base)
            var i = 2
            while (notes.any { it.str("path") == path }) path = notePath(inFolder, "$base ${i++}")
            try {
                val r = model.api.sendJson("PUT", "agent/notes/file", jsonOf("path" to path, "content" to "")).obj()
                load()
                open = OpenNote(r.str("path"), notes.firstOrNull { it.str("path") == r.str("path") }?.str("title") ?: base, TextFieldValue(""), r.str("modified"))
                saving = "idle"
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        val o = open
        if (o != null) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatButton("chevronLeft", tr.t("agent.set.back"), { scope.launch { if (saving == "saving") save(); open = null; load() } })
                Text(o.title, style = AgentText.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                Text(when (saving) { "saving" -> tr.t("agent.notes.saving"); "saved" -> tr.t("agent.notes.saved"); else -> "" }, style = AgentText.sm, color = c.muted)
            }
            if (saving == "conflict") Notice(tr.t("agent.notes.conflict"), tone = "warn", modifier = Modifier.padding(horizontal = 12.dp)) { PillButton(tr.t("agent.notes.reload"), { openNote(o.path) }, small = true) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((f, label, icon) in listOf(Triple("heading", "agent.notes.heading", "H"), Triple("bold", "agent.notes.bold", "B"), Triple("italic", "agent.notes.italic", "I"), Triple("list", "agent.notes.list", "•"), Triple("check", "agent.notes.check", "☐"), Triple("rule", "agent.notes.rule", "—"))) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) {
                        val v = o.content
                        val (t, s, e) = applyFormat(v.text, v.selection.min, v.selection.max, f)
                        open = o.copy(content = TextFieldValue(t, TextRange(s, e))); saving = "saving"; dirty++
                    }.semantics { contentDescription = tr.t(label) }, contentAlignment = Alignment.Center) {
                        Text(icon, style = AgentText.lg.copy(fontWeight = if (f == "bold") FontWeight.Bold else FontWeight.Normal), color = c.text)
                    }
                }
            }
            BasicTextField(
                o.content, { v -> val changed = v.text != o.content.text; open = o.copy(content = v); if (changed) { saving = "saving"; dirty++ } },
                textStyle = AgentText.lg.copy(color = c.text, lineHeight = 24.sp), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp).semantics { contentDescription = tr.t("agent.notes.text") },
                decorationBox = { inner -> Box { if (o.content.text.isEmpty()) Text(tr.t("agent.notes.placeholder"), style = AgentText.lg, color = c.muted); inner() } },
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatButton("close", tr.t("common.close"), onClose)
                Text(tr.t("agent.notes.title"), style = AgentText.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                FloatButton("folder", tr.t("agent.notes.newFolder"), { input = ""; dialog = NoteDialog.Folder })
                FloatButton("edit", tr.t("agent.notes.new"), ::newNote)
            }
            Field(query, { query = it }, Modifier.padding(horizontal = 12.dp), placeholder = tr.t("agent.notes.search"), leading = "search")
            error?.let { Notice(it, tone = "error", modifier = Modifier.padding(12.dp)) }
            val q = query.trim().lowercase()
            val shown = notes.filter {
                when {
                    folder == PINNED -> it.bool("pinned")
                    folder.isNotEmpty() -> it.str("folder") == folder || it.str("folder").startsWith("$folder/")
                    else -> true
                }
            }.filter { q.isEmpty() || (it.str("title") + " " + it.str("preview")).lowercase().contains(q) }.sortedByDescending { it.bool("pinned") }
            LazyColumn(Modifier.weight(1f), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        @Composable
                        fun frow(key: String, label: String, icon: String, count: Int, depth: Int = 0, menu: Boolean = false) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (folder == key) c.accent.copy(alpha = 0.16f) else Color.Transparent)
                                    .clickable(role = Role.Tab) { folder = key }.semantics { selected = folder == key }.heightIn(min = 44.dp).padding(start = (12 + depth * 16).dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                WIcon(icon, 16.dp, c.muted)
                                Text(label, style = AgentText.md, color = c.text, modifier = Modifier.weight(1f))
                                Text("$count", style = AgentText.sm, color = c.muted)
                                if (menu) SmallIcon("more", tr.t("agent.notes.folderMenu")) { menuFor = "folder:$key" } else Box(Modifier.width(8.dp))
                            }
                        }
                        frow("", tr.t("agent.notes.all"), "fileText", notes.size)
                        if (notes.any { it.bool("pinned") }) frow(PINNED, tr.t("agent.notes.pinned"), "pin", notes.count { it.bool("pinned") })
                        for (f in folders) frow(f, f.substringAfterLast('/'), "folder", notes.count { it.str("folder") == f || it.str("folder").startsWith("$f/") }, f.count { it == '/' }, menu = true)
                    }
                }
                if (loaded && shown.isEmpty()) item { Text(if (notes.isEmpty()) tr.t("agent.notes.empty") else tr.t("agent.notes.none"), style = AgentText.md, color = c.muted, modifier = Modifier.padding(16.dp)) }
                for (n in shown) item(n.str("path")) {
                    Row(Modifier.srowSurface().clickable(role = Role.Button) { openNote(n.str("path")) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WIcon(if (n.bool("pinned")) "pin" else "fileText", 17.dp, if (n.bool("pinned")) c.accent else c.muted)
                        Column(Modifier.weight(1f)) {
                            Text(n.str("title"), style = AgentText.md.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                            Text(listOf(dateTime(n.str("modified")), n.str("preview")).filter { it.isNotEmpty() }.joinToString(" · "), style = AgentText.sm, color = c.muted)
                        }
                        SmallIcon("more", tr.t("agent.notes.noteMenu")) { menuFor = n.str("path") }
                    }
                }
            }
        }
    }
    // Menü einer Notiz oder eines Ordners, dazu die Dialoge.
    Box(Modifier.fillMaxSize()) {
        val target = menuFor
        AgentSheet(target != null, { menuFor = null }, tr.t("agent.notes.noteMenu")) {
            val t = target ?: return@AgentSheet
            if (t.startsWith("folder:")) {
                val f = t.removePrefix("folder:")
                SheetItem("edit", tr.t("agent.notes.rename"), { menuFor = null; input = f.substringAfterLast('/'); dialog = NoteDialog.Rename(f, true) })
                SheetItem("trash", tr.t("common.delete"), { menuFor = null; dialog = NoteDialog.Delete(f, true) }, danger = true)
            } else {
                val n = notes.firstOrNull { it.str("path") == t }
                SheetItem("pin", if (n?.bool("pinned") == true) tr.t("agent.unpin") else tr.t("agent.notes.pin"), {
                    menuFor = null
                    scope.launch { try { model.api.sendJson("PUT", "agent/notes/pin", jsonOf("path" to t, "pinned" to !(n?.bool("pinned") ?: false))); load() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) } }
                })
                SheetItem("edit", tr.t("agent.notes.rename"), { menuFor = null; input = n?.str("title").orEmpty(); dialog = NoteDialog.Rename(t, false) })
                SheetItem("folder", tr.t("agent.notes.move"), { menuFor = null; dialog = NoteDialog.Move(t) })
                SheetRule()
                SheetItem("trash", tr.t("common.delete"), { menuFor = null; dialog = NoteDialog.Delete(t, false) }, danger = true)
            }
        }
        val d = dialog
        AgentDialog(d is NoteDialog.Folder, tr.t("agent.notes.newFolder"), { dialog = null }, actions = {
            PillButton(tr.t("common.cancel"), { dialog = null })
            PillButton(tr.t("agent.notes.create"), { run { model.api.sendJson("POST", "agent/notes/folders", jsonOf("path" to noteMove(if (folder.isNotEmpty() && folder != PINNED) folder else "", input.trim(), true))) } }, kind = Btn.Primary, enabled = input.isNotBlank())
        }) { Field(input, { input = it }, label = tr.t("agent.notes.folderName"), placeholder = tr.t("agent.notes.folderName")) }
        AgentDialog(d is NoteDialog.Rename, tr.t("agent.notes.rename"), { dialog = null }, actions = {
            PillButton(tr.t("common.cancel"), { dialog = null })
            PillButton(tr.t("common.save"), {
                val r = d as? NoteDialog.Rename ?: return@PillButton
                val to = noteMove(r.path.substringBeforeLast('/', ""), input.trim(), r.isFolder)
                run { model.api.sendJson("POST", "agent/notes/move", jsonOf("from" to r.path, "to" to to)); if (folder == r.path) folder = to }
            }, kind = Btn.Primary, enabled = input.isNotBlank())
        }) { Field(input, { input = it }, label = tr.t("agent.notes.name")) }
        AgentDialog(d is NoteDialog.Move, tr.t("agent.notes.moveTitle"), { dialog = null }, actions = { PillButton(tr.t("common.cancel"), { dialog = null }) }) {
            val m = d as? NoteDialog.Move
            for (into in listOf("") + folders) {
                SheetItem("folder", if (into.isEmpty()) tr.t("agent.notes.top") else into, {
                    val path = m?.path ?: return@SheetItem
                    val name = path.substringAfterLast('/')
                    run { model.api.sendJson("POST", "agent/notes/move", jsonOf("from" to path, "to" to if (into.isEmpty()) name else "$into/$name")) }
                })
            }
        }
        AgentDialog(d is NoteDialog.Delete, if ((d as? NoteDialog.Delete)?.isFolder == true) tr.t("agent.notes.deleteFolderTitle") else tr.t("agent.notes.deleteTitle"), { dialog = null },
            text = if ((d as? NoteDialog.Delete)?.isFolder == true) tr.t("agent.notes.deleteFolderText") else tr.t("agent.notes.deleteText"), actions = {
                PillButton(tr.t("common.delete"), {
                    val x = d as? NoteDialog.Delete ?: return@PillButton
                    run { model.api.sendJson("DELETE", "agent/notes", query = listOf("path" to x.path)); if (open?.path?.let { it == x.path || it.startsWith(x.path + "/") } == true) open = null; if (folder == x.path) folder = "" }
                }, kind = Btn.Danger)
                PillButton(tr.t("common.cancel"), { dialog = null })
            })
    }
}

// ---------- Dateien des NAS als Anhang (NasPicker) ----------

/** Dateien des NAS wählen (NasPicker.svelte): Freigaben, Ordner, Suche; nichts wird kopiert, nur genannt. */
@Composable
fun NasPicker(model: AgentModel, onClose: () -> Unit, onPick: (List<String>) -> Unit) {
    val c = Titan.colors
    val tr = tr()
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<JsonObject>?>(null) }
    var root by remember { mutableStateOf<JsonObject?>(null) }
    var dir by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<JsonObject>?>(null) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun open(r: JsonObject, path: String) {
        root = r; dir = path; rows = null; found = false
        scope.launch {
            try { rows = model.api.getJson("files/list", listOf("root" to r.str("id"), "path" to path)).objs().map { e -> io.github.ra5on.titanos.agent.core.jsonOf("name" to e.str("name"), "dir" to e.bool("dir"), "path" to if (path.isEmpty()) e.str("name") else "$path/${e.str("name")}") } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
        }
    }
    LaunchedEffect(Unit) { roots = runCatching { model.api.getJson("files/roots").objs().filter { it.str("kind") != "stick" } }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageHead(tr.t("agent.nasTitle"), { if (root != null && dir.isNotEmpty()) open(root!!, dir.substringBeforeLast('/', "")) else if (root != null) { root = null; rows = null } else onClose() })
        error?.let { Notice(it, tone = "error", modifier = Modifier.padding(12.dp)) }
        val r = root
        if (r == null) {
            Text(tr.t("agent.nasShares"), style = AgentText.md, color = c.muted, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val list = roots
                if (list != null && list.isEmpty()) item { Text(tr.t("agent.nasNone"), style = AgentText.md, color = c.muted) }
                for (x in list.orEmpty()) item { SRow(x.str("name"), icon = "folder", onClick = { open(x, "") }) }
            }
        } else {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(query, { query = it }, Modifier.weight(1f), placeholder = tr.t("agent.nasSearch"), leading = "search", onDone = {
                    scope.launch {
                        try { rows = model.api.sendJson("POST", "files/search", jsonOf("root" to r.str("id"), "path" to dir, "query" to query.trim())).obj().os("entries"); found = true }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemText(tr, problemOf(e)) }
                    }
                })
            }
            Text(tr.t("agent.nasWhere") + ": " + r.str("name") + (if (dir.isNotEmpty()) " › " + dir.replace("/", " › ") else ""), style = AgentText.sm, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val list = rows
                if (list == null) item { Text(tr.t("agent.loading"), style = AgentText.md, color = c.muted) }
                else if (list.isEmpty()) item { Text(if (found) tr.t("agent.nasNoHits") else tr.t("agent.nasEmpty"), style = AgentText.md, color = c.muted) }
                for (e in list.orEmpty()) item {
                    val ref = r.str("id") + ":" + e.str("path")
                    if (e.bool("dir")) SRow(e.str("name"), icon = "folder", onClick = { open(r, e.str("path")) })
                    else Check(e.str("name"), ref in chosen, { on -> chosen = if (on) chosen + ref else chosen - ref })
                }
            }
        }
        Text(tr.t("agent.nasChosen", "count" to chosen.size) + " · " + tr.t("agent.nasRights"), style = AgentText.sm, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp))
        Buttons(Modifier.padding(12.dp)) {
            PillButton(if (chosen.isEmpty()) tr.t("agent.nasTakeNone") else tr.t("agent.nasTake", "count" to chosen.size), { onPick(chosen) }, kind = Btn.Primary, enabled = chosen.isNotEmpty())
            PillButton(tr.t("common.cancel"), onClose)
        }
    }
}
