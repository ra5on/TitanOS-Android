package io.github.ra5on.titanos.settings.pages.smarthome

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.NumberField
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

// Reiter „Funktionen“ der Systemsteuerung › KI-Agent (FeaturesPanel
// admin=true, PLAN 54): die Funktionen in vier Gruppen, je Zeile der
// NAS-weite Schalter; ein Tippen öffnet ihre Einstellungen. Dazu die eigenen
// Teile des Kontos (Ordner, Postfach) wie im Web. Die Ansichten der
// Funktionen (Fristenliste, Papierkram, Meldungen, Berater-Bericht,
// Backup-Partner, Katalog) stehen in der KI-Agent-App.

private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** Ein Ordner für Menschen: „KI-Agent › Dokumente“ (place in features/docs.ts). */
private fun placeText(root: String, path: String): String {
    val share = root.removePrefix("share/")
    return if (path.isNotEmpty()) share + " › " + path.split('/').joinToString(" › ") else share
}

private fun placeOf(f: JsonObject) = placeText(f.str("root"), f.str("path"))

private fun folderRef(root: String, path: String) = jobj("root" to root, "path" to path)

private val DEFAULT_FOLDERS = listOf(folderRef("share/KI-Agent", "Dokumente"))

/** Felder, die sich zwischen zwei Fassungen unterscheiden (ohne `rev`). */
private fun changedKeys(base: JsonObject, mine: JsonObject) = mine.keys.filter { it != "rev" && mine[it] != base[it] }

/** rebaseFeatures im Web: eigene Änderungen auf die neue Fassung, null wenn dieselbe Funktion anderswo geändert wurde. */
private fun rebase(base: JsonObject, mine: JsonObject, theirs: JsonObject): JsonObject? {
    val changed = changedKeys(base, mine)
    if (changed.any { theirs[it] != base[it] }) return null
    if (changed.isEmpty()) return theirs
    return JsonObject(theirs + changed.associateWith { mine.getValue(it) })
}

/**
 * Die Einstellungen der Funktionen: NAS-weit (`/agent/features`, mit `rev`)
 * und die eigenen (`/agent/features/me`). Änderungen zeigen sich sofort,
 * Speichervorgänge warten aufeinander (Durchsicht 102, A-22).
 */
@Stable
private class FeaturesState(private val env: SettingsEnv, private val tr: Tr, private val scope: CoroutineScope, private val context: Context) {
    var nas by mutableStateOf<JsonObject?>(null)
    var mine by mutableStateOf<JsonObject?>(null)
    var pub by mutableStateOf<JsonObject?>(null)
    var me by mutableStateOf<JsonObject?>(null)
    var error by mutableStateOf("")
    var notice by mutableStateOf("")
    var loaded by mutableStateOf(false)

    private var savedNas: JsonObject? = null
    private var savedMine: JsonObject? = null
    private val nasLock = Mutex()
    private val mineLock = Mutex()
    private var nasQueued = 0
    private var mineQueued = 0
    private var nasLost = 0

    suspend fun load() {
        try {
            nas = env.api.obj("agent/features").also { savedNas = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
        try {
            val m = env.api.obj("agent/features/me")
            mine = m.o("mine").also { savedMine = it }
            pub = m.o("nas")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (error.isEmpty()) error = tr.errorOf(e)
        }
        loadMe()
        loaded = true
    }

    /** Eine Aktion ohne Einstellung (jetzt einlesen, Postfach prüfen): Fehler an [onError]. */
    fun act(onError: (String) -> Unit, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(tr.errorOf(e))
            }
        }
    }

    suspend fun loadMe() {
        me = runCatching { env.api.obj("agent/protect/me") }.getOrNull()
    }

    /** Speichert die NAS-Einstellungen; [onError] bekommt den Fehler (für ein offenes Blatt). */
    fun saveNas(next: JsonObject, onDone: (Boolean) -> Unit = {}) {
        Guard.require(context, GuardAction.Settings, onDenied = { onDone(false) }) {
            error = ""
            notice = ""
            if (savedNas == null) savedNas = nas
            val callBase = nas
            val seen = nasLost
            nas = next
            nasQueued++
            scope.launch {
                val ok = try {
                    nasLock.withLock { saveNasNow(next, callBase, seen) }
                } finally {
                    nasQueued--
                }
                onDone(ok)
            }
        }
    }

    private suspend fun saveNasNow(nextIn: JsonObject, callBase: JsonObject?, seen: Int): Boolean {
        var next = nextIn
        val base = savedNas
        if (nasLost != seen && callBase != null && base != null) {
            val own = rebase(callBase, next, base)
            if (own == null) {
                nas = base
                error = tr.t("agent.f.changedLost")
                return false
            }
            next = own
        }
        try {
            val body = next.with("rev" to (base?.get("rev") ?: next["rev"]))
            val saved = env.api.put("agent/features", body).o()
            savedNas = saved
            if (nasQueued <= 1) nas = saved
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ApiException)?.code != "agent_features_changed" || base == null) {
                error = tr.errorOf(e)
                if (nasQueued <= 1) nas = savedNas
                return false
            }
            // Von einem anderen Tab oder Admin überholt: neu lesen, eigene
            // Änderung an einer anderen Funktion darüberlegen.
            val fresh = try {
                env.api.obj("agent/features")
            } catch (e2: kotlinx.coroutines.CancellationException) {
                throw e2
            } catch (_: Exception) {
                error = tr.errorOf(e)
                if (nasQueued <= 1) nas = savedNas
                return false
            }
            savedNas = fresh
            val merged = rebase(base, next, fresh)
            if (merged == null) {
                nasLost++
                nas = fresh
                error = tr.t("agent.f.changedLost")
                return false
            }
            try {
                savedNas = if (merged === fresh) fresh else env.api.put("agent/features", merged).o()
                if (nasQueued <= 1) nas = savedNas
                notice = tr.t("agent.f.changedKept")
            } catch (e3: kotlinx.coroutines.CancellationException) {
                throw e3
            } catch (e3: Exception) {
                nas = fresh
                error = tr.errorOf(e3)
                return false
            }
        }
        // Was die Konten davon sehen, ist nur ein zweiter Blick.
        try {
            pub = env.api.obj("agent/features/me").o("nas")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
        loadMe()
        return true
    }

    /** Die eigenen Einstellungen des Kontos, ebenso nacheinander. */
    fun saveMine(next: JsonObject, onDone: (Boolean) -> Unit = {}) {
        Guard.require(context, GuardAction.Settings, onDenied = { onDone(false) }) {
            error = ""
            if (savedMine == null) savedMine = mine
            mine = next
            mineQueued++
            scope.launch {
                val ok = try {
                    mineLock.withLock {
                        try {
                            savedMine = env.api.put("agent/features/me", next).o()
                            if (mineQueued <= 1) mine = savedMine
                            true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = tr.errorOf(e)
                            if (mineQueued <= 1) mine = savedMine
                            false
                        }
                    }
                } finally {
                    mineQueued--
                }
                onDone(ok)
            }
        }
    }
}

/** Reiter „Funktionen“ (FeaturesPanel admin=true). */
@Composable
internal fun AgentFeaturesTab() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val st = remember { FeaturesState(env, tr, scope, context) }
    LaunchedEffect(Unit) { st.load() }

    SettingGroup(t("agent.f.title"), desc = t("agent.f.intro")) {}
    if (!st.loaded) LoadingLine(t("common.loading"))
    if (st.error.isNotEmpty()) Banner(st.error, tone = Tone.Error)
    if (st.notice.isNotEmpty()) Banner(st.notice, tone = Tone.Ok)
    val nas = st.nas
    val n = nas ?: JsonObject(emptyMap())
    val pub = st.pub ?: JsonObject(emptyMap())
    val mine = st.mine ?: JsonObject(emptyMap())

    // ---------- Dokumente und Fristen ----------
    SettingGroup(t("agent.f.group.docs")) {
        val nd = n.o("docs")
        fun setDocs(part: String, on: Boolean) {
            val cur = st.nas ?: return
            var next = cur.o("docs").with(part to on)
            if (on) next = next.with("enabled" to true)
            st.saveNas(cur.with("docs" to next))
        }
        FeatureRow(
            t("agent.f.docs.memory"), t("agent.f.docs.memorySub"), "file",
            on = nd.bool("enabled"), toggle = nas != null, onToggle = { setDocs("enabled", it) },
            onOpen = { ov.show { close -> DocsMemorySheet(st, close) } },
        )
        FeatureRow(
            t("agent.f.docs.deadlines"),
            t(if (mine.o("docs").str("mode") == "remind") "agent.f.docs.deadlinesSubRemind" else "agent.f.docs.deadlinesSub"),
            "clock",
            on = nd.bool("enabled") && nd.bool("deadlines"), toggle = nas != null, onToggle = { setDocs("deadlines", it) },
            onOpen = { ov.show { close -> DocsMemorySheet(st, close) } },
        )
        FeatureRow(
            t("agent.f.docs.paper"),
            t(if (mine.o("paper").str("filing") == "ask") "agent.f.docs.paperSubAsk" else "agent.f.docs.paperSub"),
            "note",
            on = nd.bool("enabled") && nd.bool("paper"), toggle = nas != null, onToggle = { setDocs("paper", it) },
            onOpen = { ov.show { close -> PaperSheet(st, close) } },
        )
    }

    // ---------- Schutz ----------
    SettingGroup(t("agent.f.group.protect")) {
        val me = st.me ?: JsonObject(emptyMap())
        fun toggle(id: String, on: Boolean) {
            val cur = st.nas ?: return
            st.saveNas(cur.with(id to cur.o(id).with("enabled" to on)))
        }
        val alerts = me.int("alerts")
        val guardSub = tr.or("agent.f.protect.g.mode.${n.o("guard").str("mode").ifEmpty { "lock" }}", n.o("guard").str("mode")) +
            if (alerts > 0) " · " + tr.t(if (alerts == 1) "agent.f.protect.g.openAlert" else "agent.f.protect.g.openAlerts", "count" to alerts) else ""
        val days = n.o("emergency").a("persons").objects().firstOrNull()?.int("waitDays", 3) ?: 3
        val requests = me.int("requests")
        val emergencySub = tr.t("agent.f.protect.e.rowSub", "days" to days) +
            if (requests > 0) " · " + tr.t(if (requests == 1) "agent.f.protect.e.openRequest" else "agent.f.protect.e.openRequests", "count" to requests) else ""
        FeatureRow(t("agent.f.protect.guard"), guardSub, "shield", on = n.o("guard").bool("enabled"), toggle = nas != null, onToggle = { toggle("guard", it) },
            onOpen = { ov.show { close -> ProtectSheet(st, "guard", t("agent.f.protect.guard"), close) } })
        FeatureRow(t("agent.f.protect.handbook"), t("agent.f.protect.h.rowSub"), "book", on = n.o("handbook").bool("enabled"), toggle = nas != null, onToggle = { toggle("handbook", it) },
            onOpen = { ov.show { close -> ProtectSheet(st, "handbook", t("agent.f.protect.handbook"), close) } })
        FeatureRow(t("agent.f.protect.emergency"), emergencySub, "key", on = n.o("emergency").bool("enabled"), toggle = nas != null, onToggle = { toggle("emergency", it) },
            onOpen = { ov.show { close -> ProtectSheet(st, "emergency", t("agent.f.protect.emergency"), close) } })
    }

    // ---------- Vorausschau ----------
    SettingGroup(t("agent.f.group.foresight")) {
        FeatureRow(t("agent.f.foresight.whatIf"), t("agent.f.foresight.whatIfSub"), "activity", on = n.o("whatIf").bool("enabled"), toggle = nas != null,
            onToggle = { on -> st.nas?.let { st.saveNas(it.with("whatIf" to jobj("enabled" to on))) } },
            onOpen = { ov.show { close -> WhatIfSheet(st, close) } })
        FeatureRow(t("agent.f.foresight.advisor"), t("agent.f.foresight.advisorSub"), "chip", on = n.o("advisor").bool("enabled"), toggle = nas != null,
            onToggle = { on -> st.nas?.let { st.saveNas(it.with("advisor" to advisorOf(it).with("enabled" to on))) } },
            onOpen = { ov.show { close -> AdvisorSheet(st, close) } })
    }

    // ---------- Gemeinschaft und Familie ----------
    SettingGroup(t("agent.f.group.family")) {
        val buddy = n.o("buddy")
        val buddySub = if (buddy.bool("enabled") && buddy.str("every").isNotEmpty()) tr.or("agent.f.family.buddy.every.${buddy.str("every")}", buddy.str("every")) else tr.t("agent.f.family.buddy.sub")
        FeatureRow(t("agent.f.family.buddy.title"), buddySub, "users", on = buddy.bool("enabled"), toggle = nas != null,
            onToggle = { on ->
                st.nas?.let { cur ->
                    st.saveNas(cur.with("buddy" to cur.o("buddy").with("enabled" to on))) { ok ->
                        // Ohne Volume geht es nicht: die Einstellungen öffnen sich.
                        if (!ok && on) ov.show { close -> BuddySheet(st, close) }
                    }
                }
            },
            onOpen = { ov.show { close -> BuddySheet(st, close) } })
        FeatureRow(t("agent.f.family.catalog.title"), t("agent.f.family.catalog.sub"), "grid", on = n.o("catalog").bool("enabled"), toggle = nas != null,
            onToggle = { on -> st.nas?.let { st.saveNas(it.with("catalog" to it.o("catalog").with("enabled" to on))) } },
            onOpen = { ov.show { close -> CatalogSheet(st, close) } })
    }
}

/** Eine Funktion in der Liste (FeatureRow.svelte): Symbol, Name, was sie tut, Schalter; Tippen öffnet die Einstellungen. */
@Composable
private fun FeatureRow(title: String, sub: String?, icon: String, on: Boolean, toggle: Boolean, onToggle: (Boolean) -> Unit, onOpen: (() -> Unit)?) {
    SettingRow(
        title,
        desc = sub,
        icon = WebIcons[icon],
        onClick = onOpen,
        control = if (toggle) ({ OnOff(on, onToggle, label = title) }) else null,
    )
}

/** Eine Zeile mit Titel, Erklärung und Schalter in einem Blatt. */
@Composable
private fun SwitchLine(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Plain(title, bold = true)
            if (desc != null) Muted(desc)
        }
        OnOff(checked, onChange, label = title, enabled = enabled)
    }
}

@Composable
private fun SheetError(st: FeaturesState, local: String = "") {
    val text = local.ifEmpty { st.error }
    if (text.isNotEmpty()) Banner(text, tone = Tone.Error)
}

// ---------- Dokumente: Gedächtnis und Fristen ----------

@Composable
private fun BoxScope.DocsMemorySheet(st: FeaturesState, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val pub = st.pub ?: JsonObject(emptyMap())
    val mine = st.mine ?: JsonObject(emptyMap())
    val docs = mine.o("docs")
    val nd = st.nas?.o("docs") ?: JsonObject(emptyMap())
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var local by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(0) }
    // Den Stand lesen, solange ein Lauf dauert, alle zwei Sekunden.
    LaunchedEffect(round, pub.bool("docs")) {
        if (!pub.bool("docs")) return@LaunchedEffect
        while (true) {
            try {
                status = env.api.obj("agent/docs")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                local = tr.errorOf(e)
                break
            }
            if (status?.bool("running") != true) break
            delay(2000)
        }
    }
    fun saveDocs(next: JsonObject) = st.saveMine(mine.with("docs" to next)) { if (it) round++ }
    val stored = if (docs.bool("custom")) docs.a("folders").objects() else DEFAULT_FOLDERS
    val folders = status?.takeIf { it.has("folders") }?.a("folders")?.objects() ?: stored
    DialogSheet(t("agent.f.docs.memory"), onDismiss = close, buttons = listOf(SheetButton(t("common.close"), primary = true) { close() })) {
        if (!pub.bool("docs")) {
            Muted(t("agent.f.notThere"))
            return@DialogSheet
        }
        SheetError(st, local)
        SwitchLine(t("agent.f.docs.memory"), tr.t("agent.f.docs.nightly", "at" to (status?.str("at")?.ifEmpty { null } ?: nd.str("at").ifEmpty { "03:00" })), docs.bool("on"), { saveDocs(docs.with("on" to it)) })
        Divider()
        Legend(t("agent.f.docs.folders"))
        for (f in folders) {
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Plain(placeOf(f), bold = true)
                    Muted(folderSub(tr, f))
                }
                val (tone, key) = stateTag(f.str("state"))
                StatusChip(tr.t(key), tone)
                IconButton(WebIcons["close"], tr.t("agent.f.docs.remove", "name" to placeOf(f)), {
                    val list = stored.filter { it.str("root") != f.str("root") || it.str("path") != f.str("path") }
                    saveDocs(docs.with("custom" to true, "folders" to list))
                }, size = 20.dp)
            }
        }
        LinkButton("+ " + t("agent.f.docs.addFolder"), {
            ov.show { c ->
                FolderPickerSheet(t("agent.f.docs.addFolder"), writable = false, close = c) { root, path ->
                    if (stored.none { it.str("root") == root && it.str("path") == path }) saveDocs(docs.with("custom" to true, "folders" to stored + folderRef(root, path)))
                }
            }
        })
        Muted(t("agent.f.docs.addHint"))
        val running = status?.bool("running") == true
        SmallButton(t(if (running) "agent.f.docs.state.running" else "agent.f.docs.runNow"), {
            st.act({ local = it }) { env.api.post("agent/docs/run"); delay(400); round++ }
        }, enabled = docs.bool("on") && !running)
        if (pub.bool("deadlines")) {
            Divider()
            Legend(t("agent.f.docs.deadlines"))
            SwitchLine(t("agent.f.docs.findDeadlines"), t("agent.f.docs.findHint"), docs.bool("deadlines"), { saveDocs(docs.with("deadlines" to it)) })
            Plain(t("agent.f.docs.found"), bold = true)
            Segments(if (docs.str("mode") == "remind") "remind" else "suggest", listOf("suggest" to t("agent.f.docs.modeSuggest"), "remind" to t("agent.f.docs.modeRemind")), { saveDocs(docs.with("mode" to it)) })
            val lead = docs.a("lead").mapNotNull { it.toString().toIntOrNull() }.ifEmpty { listOf(14, 2) }
            var leadText by remember(lead) { mutableStateOf(lead.joinToString(", ")) }
            Plain(t("agent.f.docs.remind"), bold = true)
            Muted(if (lead.size == 2) tr.t("agent.f.docs.leadTwo", "a" to lead[0], "b" to lead[1]) else lead.joinToString(", ") { tr.t("agent.f.docs.leadOne", "n" to it) })
            Field(leadText, { leadText = it }, label = t("agent.f.docs.remind"), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Muted(t("agent.f.docs.leadHint"))
            // Wie onchange im Web: gespeichert, wenn eine Weile nicht getippt wird.
            LaunchedEffect(leadText) {
                val list = leadText.split(Regex("[,;\\s]+")).mapNotNull { it.toIntOrNull() }.filter { it in 0..365 }.take(3)
                if (list == lead || leadText == lead.joinToString(", ")) return@LaunchedEffect
                delay(900)
                saveDocs(docs.with("lead" to list))
            }
        }
        Divider()
        Legend(t("agent.f.docs.ocr"))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Plain(t("agent.f.docs.pdfText"), bold = true)
                Muted(t("agent.f.docs.pdfTextSub"))
            }
            StatusChip(t("agent.f.docs.always"), Tone.Ok)
        }
        SwitchLine(t("agent.f.docs.scans"), t("agent.f.docs.scansSub"), !docs.bool("noScans"), { saveDocs(docs.with("noScans" to !it)) })
    }
}

private fun stateTag(state: String): Pair<Tone, String> = when (state) {
    "done" -> Tone.Ok to "agent.f.docs.state.done"
    "running" -> Tone.Warn to "agent.f.docs.state.running"
    "asleep" -> Tone.Warn to "agent.f.docs.state.asleep"
    "noAccess" -> Tone.Error to "agent.f.docs.state.noAccess"
    "full" -> Tone.Error to "agent.f.docs.state.full"
    "error" -> Tone.Error to "agent.f.docs.state.error"
    else -> Tone.Inactive to "agent.f.docs.state.new"
}

private fun folderSub(tr: Tr, f: JsonObject): String {
    val parts = mutableListOf(tr.t("agent.f.docs.files", "count" to f.long("files")))
    if (f.str("state") == "running" && f.has("percent")) parts += "${f.long("percent")} %"
    else if (f.str("lastRun").isNotEmpty()) parts += tr.t("agent.f.docs.lastRun", "at" to Fmt.dateTime(tr, f.str("lastRun")))
    if (f.long("pending") > 0) parts += tr.t("agent.f.docs.pending", "count" to f.long("pending"))
    return parts.joinToString(" · ")
}

// ---------- Dokumente: Papierkram ----------

@Composable
private fun BoxScope.PaperSheet(st: FeaturesState, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val pub = st.pub ?: JsonObject(emptyMap())
    val mine = st.mine ?: JsonObject(emptyMap())
    val paper = mine.o("paper")
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var local by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round, pub.bool("docs")) {
        if (!pub.bool("docs")) return@LaunchedEffect
        while (true) {
            try {
                status = env.api.obj("agent/docs")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                local = tr.errorOf(e)
                break
            }
            if (status?.bool("running") != true) break
            delay(2000)
        }
    }
    // Das Postfach wird einmal beim Öffnen gefüllt und mit eigenem Knopf gespeichert.
    var mail by remember { mutableStateOf(paper.o("mail")) }
    var password by remember { mutableStateOf("") }
    fun savePaper(next: JsonObject, onDone: (Boolean) -> Unit = {}) = st.saveMine(mine.with("paper" to next)) { ok -> if (ok) round++; onDone(ok) }
    val info = status?.o("paper") ?: JsonObject(emptyMap())
    DialogSheet(t("agent.f.docs.paper"), onDismiss = close, buttons = listOf(SheetButton(t("common.close"), primary = true) { close() })) {
        if (!pub.bool("paper")) {
            Muted(t("agent.f.notThere"))
            return@DialogSheet
        }
        SheetError(st, local)
        if (note.isNotEmpty()) Banner(note, tone = Tone.Ok)
        SwitchLine(t("agent.f.docs.paper"), t("agent.f.docs.paperOwn"), paper.bool("on"), { savePaper(paper.with("on" to it)) })
        Divider()
        Legend(t("agent.f.docs.inbox"))
        Plain(info.str("folder").ifEmpty { "KI-Agent › Eingang" }, bold = true)
        Muted(t("agent.f.docs.inboxSub"))
        if (info.long("ask") > 0 || info.long("waiting") > 0) Muted(tr.t("agent.f.docs.inboxCounts", "ask" to info.long("ask"), "waiting" to info.long("waiting")))
        SmallButton(t("agent.f.docs.lookNow"), {
            note = ""
            st.act({ local = it }) { env.api.post("agent/paper/run"); note = tr.t("agent.f.docs.paperLooking"); delay(1500); round++ }
        }, enabled = paper.bool("on"))
        Divider()
        Legend(t("agent.f.docs.filing"))
        Segments(if (paper.str("filing") == "ask") "ask" else "auto", listOf("auto" to t("agent.f.docs.filingAuto"), "ask" to t("agent.f.docs.filingAsk")), { savePaper(paper.with("filing" to it)) })
        Muted(t("agent.f.docs.filingHint"))
        val target = paper.o("target").takeIf { it.str("root").isNotEmpty() } ?: folderRef("share/KI-Agent", "Dokumente/Ablage")
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Plain(placeOf(target), bold = true)
                Muted(t("agent.f.docs.target"))
            }
            SmallButton(t("agent.f.docs.change"), {
                ov.show { c -> FolderPickerSheet(tr.t("agent.f.docs.target"), writable = true, close = c) { root, path -> savePaper(paper.with("target" to folderRef(root, path))) } }
            })
        }
        Plain(t("agent.f.docs.scheme"), bold = true)
        Select(
            paper.str("scheme").ifEmpty { "sender" },
            listOf("sender" to t("agent.f.docs.schemeSender"), "type" to t("agent.f.docs.schemeType"), "year" to t("agent.f.docs.schemeYear")),
            { savePaper(paper.with("scheme" to it)) },
            label = t("agent.f.docs.scheme"),
        )
        Divider()
        Legend(t("agent.f.docs.mailbox"))
        SwitchLine(t("agent.f.docs.mailOn"), t("agent.f.docs.mailSub"), mail.bool("enabled"), { mail = mail.with("enabled" to it) })
        Field(mail.str("host"), { mail = mail.with("host" to it) }, label = t("agent.f.docs.host"), placeholder = "imap.example.org", singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        Muted(t("agent.f.docs.port"))
        Field(
            mail.long("port").takeIf { it > 0 }?.toString().orEmpty(),
            { v -> mail = mail.with("port" to (v.filter(Char::isDigit).take(5).toLongOrNull() ?: 0L)) },
            label = t("agent.f.docs.port"),
            placeholder = if (mail.str("security") == "starttls") "143" else "993",
            singleLine = true,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Muted(t("agent.f.docs.security"))
        Segments(if (mail.str("security") == "starttls") "starttls" else "tls", listOf("tls" to "TLS", "starttls" to "STARTTLS"), { mail = mail.with("security" to it) })
        Field(mail.str("user"), { mail = mail.with("user" to it) }, label = t("agent.f.docs.mailUser"), placeholder = t("agent.f.docs.mailUser"), singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        if (mail.bool("passwordSet")) Muted(t("agent.f.docs.passwordSet"))
        Field(password, { password = it }, label = t("agent.f.docs.password"), placeholder = t("agent.f.docs.password"), password = true, modifier = Modifier.fillMaxWidth())
        Muted(t("agent.f.docs.mailTake"))
        Segments(if (mail.str("mode") == "attachments") "attachments" else "both", listOf("both" to t("agent.f.docs.mailBoth"), "attachments" to t("agent.f.docs.mailAttach")), { mail = mail.with("mode" to it) })
        Muted(t("agent.f.docs.mailHint"))
        if (info.str("mailError").isNotEmpty()) Banner(tr.t("agent.f.docs.mailFailed", "error" to info.str("mailError")), tone = Tone.Error)
        else if (info.str("mailAt").isNotEmpty()) Muted(tr.t("agent.f.docs.mailAt", "at" to Fmt.dateTime(tr, info.str("mailAt"))))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(t("agent.f.docs.mailTest"), {
                note = ""
                local = ""
                st.act({ local = it }) { env.api.post("agent/paper/mail/test"); note = tr.t("agent.f.docs.mailOk") }
            }, enabled = mail.bool("passwordSet"))
            SmallButton(t("common.save"), {
                note = ""
                var next = JsonObject(mail - "passwordSet").with("port" to mail.long("port"))
                if (password.isNotEmpty()) next = next.with("password" to password)
                savePaper(paper.with("mail" to next)) { ok ->
                    if (ok) {
                        mail = (st.mine ?: JsonObject(emptyMap())).o("paper").o("mail")
                        password = ""
                        note = tr.t("agent.f.saved")
                    }
                }
            }, kind = ButtonKind.Primary)
        }
    }
}

/** Einen Ordner einer Freigabe wählen, die das Konto lesen (oder schreiben) darf (DocsFolderPicker). */
@Composable
private fun BoxScope.FolderPickerSheet(title: String, writable: Boolean, close: () -> Unit, onPick: (String, String) -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    var roots by remember { mutableStateOf<List<JsonObject>?>(null) }
    var root by remember { mutableStateOf<JsonObject?>(null) }
    var dir by remember { mutableStateOf("") }
    var dirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        roots = try {
            env.api.list("files/roots").objects().filter { it.str("kind") == "share" && (!writable || it.bool("writable")) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
            emptyList()
        }
    }
    LaunchedEffect(root, dir) {
        val r = root ?: return@LaunchedEffect
        error = ""
        dirs = emptyList()
        dirs = try {
            env.api.list("files/list?root=${enc(r.str("id"))}&path=${enc(dir)}").objects()
                .filter { it.bool("dir") && !it.str("name").startsWith(".") }.map { it.str("name") }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
            emptyList()
        }
    }
    val r = root
    DialogSheet(
        title,
        onDismiss = close,
        buttons = listOfNotNull(
            SheetButton(t("common.cancel")) { close() },
            r?.let { SheetButton(t("agent.f.docs.pickThis"), primary = true) { onPick(it.str("id"), dir); close() } },
        ),
    ) {
        if (r != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("‹ " + t("agent.f.docs.back"), { if (dir.isEmpty()) root = null else dir = dir.substringBeforeLast('/', "") })
                Plain(placeText(r.str("id"), dir), bold = true, modifier = Modifier.weight(1f))
            }
        }
        if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
        if (r == null) {
            val list = roots
            if (list == null) LoadingLine(t("common.loading"))
            else if (list.isEmpty()) Muted(t("agent.f.docs.pickNone"))
            else for (x in list) SmallButton(x.str("name"), { root = x; dir = "" }, icon = WebIcons["folder"], modifier = Modifier.fillMaxWidth())
        } else {
            if (dirs.isEmpty()) Muted(t("agent.f.docs.pickEmpty"))
            for (d in dirs) SmallButton(d, { dir = if (dir.isEmpty()) d else "$dir/$d" }, icon = WebIcons["folder"], modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------- Schutz: Einstellungen (ProtectSettings.svelte) ----------

private val GUARD_NUMBERS = listOf(
    "known" to "agent.f.protect.s.known",
    "renames" to "agent.f.protect.s.renames",
    "rewrites" to "agent.f.protect.s.rewrites",
    "notes" to "agent.f.protect.s.notes",
    "windowSec" to "agent.f.protect.s.window",
    "quietSec" to "agent.f.protect.s.quiet",
)

private fun toggleIn(list: List<String>, name: String, on: Boolean): List<String> = list.filter { it != name } + if (on) listOf(name) else emptyList()

@Composable
private fun BoxScope.ProtectSheet(st: FeaturesState, id: String, title: String, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val start = st.nas ?: JsonObject(emptyMap())
    var part by remember { mutableStateOf(start.o(id)) }
    var shares by remember { mutableStateOf<List<String>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        shares = runCatching { env.api.list("shares").objects().map { it.str("name") }.sorted() }.getOrDefault(emptyList())
        if (id == "emergency") accounts = runCatching { env.api.list("users").objects().filter { !it.bool("disabled") }.map { it.str("name") }.sorted() }.getOrDefault(emptyList())
    }
    fun save() {
        val cur = st.nas ?: return
        busy = true
        saved = false
        local = ""
        st.saveNas(cur.with(id to part.with("enabled" to cur.o(id).bool("enabled")))) { ok ->
            busy = false
            saved = ok
            if (!ok) local = st.error
        }
    }
    DialogSheet(
        title,
        onDismiss = close,
        buttons = listOf(
            SheetButton(t("common.close")) { close() },
            SheetButton(t("common.save"), primary = true, enabled = !busy && st.nas != null) { save() },
        ),
    ) {
        if (st.nas == null) {
            SheetError(st)
            return@DialogSheet
        }
        Legend(t("agent.f.protect.s.title"))
        if (local.isNotEmpty()) Banner(local, tone = Tone.Error)
        when (id) {
            "guard" -> {
                Plain(t("agent.f.protect.s.mode"), bold = true)
                RadioList(
                    part.str("mode").ifEmpty { "lock" },
                    listOf("report", "lock", "auto").map { m -> Triple(m, tr.t("agent.f.protect.g.mode.$m"), tr.t("agent.f.protect.s.modeHint.$m")) },
                    { part = part.with("mode" to it) },
                )
                for ((key, label) in GUARD_NUMBERS) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Plain(t(label), bold = true, modifier = Modifier.weight(1f))
                        NumberField(part.long(key).takeIf { it > 0 }, { v -> part = part.with(key to (v ?: 0L).coerceIn(0, if (key.endsWith("Sec")) 3600 else 100000)) }, label = t(label), allowEmpty = true)
                    }
                }
                SwitchLine(t("agent.f.protect.s.pre"), t("agent.f.protect.s.preHint"), !part.bool("noPreSnapshots"), { part = part.with("noPreSnapshots" to !it) })
                Plain(t("agent.f.protect.s.exclude"), bold = true)
                Muted(t("agent.f.protect.s.excludeHint"))
                val exclude = part.texts("exclude")
                for (name in shares) CheckRow(name in exclude, { on -> part = part.with("exclude" to toggleIn(exclude, name, on)) }, name)
            }
            "handbook" -> {
                SwitchLine(t("agent.f.protect.s.probes"), t("agent.f.protect.s.probesHint"), part.bool("probes"), { part = part.with("probes" to it) })
                SwitchLine(t("agent.f.protect.s.contacts"), t("agent.f.protect.s.contactsHint"), part.bool("withContacts"), { part = part.with("withContacts" to it) })
                if (part.bool("withContacts")) {
                    val contacts = part.a("contacts").objects()
                    contacts.forEachIndexed { i, c ->
                        fun set(k: String, v: String) { part = part.with("contacts" to contacts.mapIndexed { j, x -> if (j == i) x.with(k to v) else x }) }
                        Divider()
                        Field(c.str("name"), { set("name", it.take(80)) }, label = t("agent.f.protect.s.cName"), placeholder = t("agent.f.protect.s.cName"), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Field(c.str("phone"), { set("phone", it.take(40)) }, label = t("agent.f.protect.s.cPhone"), placeholder = t("agent.f.protect.s.cPhone"), singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                        Field(c.str("note"), { set("note", it.take(200)) }, label = t("agent.f.protect.s.cNote"), placeholder = t("agent.f.protect.s.cNote"), modifier = Modifier.fillMaxWidth())
                        SmallButton(t("agent.f.protect.s.remove"), { part = part.with("contacts" to contacts.filterIndexed { j, _ -> j != i }) })
                    }
                    SmallButton(t("agent.f.protect.s.addContact"), { part = part.with("contacts" to contacts + jobj("name" to "")) }, enabled = contacts.size < 10)
                }
            }
            else -> {
                val persons = part.a("persons").objects()
                persons.forEachIndexed { i, p ->
                    fun set(k: String, v: Any?) { part = part.with("persons" to persons.mapIndexed { j, x -> if (j == i) x.with(k to v) else x }) }
                    Divider()
                    Muted(t("agent.f.protect.s.account"))
                    val options = (accounts + listOfNotNull(p.str("account").takeIf { it.isNotEmpty() && it !in accounts })).map { it to it }
                    Select(p.str("account"), options, { set("account", it) }, label = t("agent.f.protect.s.account"))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Plain(t("agent.f.protect.s.wait"), modifier = Modifier.weight(1f))
                        NumberField(p.long("waitDays", 3).takeIf { it > 0 }, { v -> set("waitDays", (v ?: 0L).coerceIn(0, 60)) }, label = t("agent.f.protect.s.wait"), allowEmpty = true)
                    }
                    Muted(t("agent.f.protect.s.shares"))
                    val own = p.texts("shares")
                    for (name in shares) CheckRow(name in own, { on -> set("shares", toggleIn(own, name, on)) }, name)
                    SmallButton(t("agent.f.protect.s.remove"), { part = part.with("persons" to persons.filterIndexed { j, _ -> j != i }) })
                }
                SmallButton(t("agent.f.protect.s.addPerson"), {
                    val free = accounts.firstOrNull { a -> persons.none { it.str("account") == a } }
                    if (free != null) part = part.with("persons" to persons + jobj("account" to free, "waitDays" to 3, "shares" to emptyList<String>()))
                }, enabled = accounts.isNotEmpty())
                SwitchLine(t("agent.f.protect.s.code"), t("agent.f.protect.s.codeHint"), part.bool("code"), { part = part.with("code" to it) })
                Muted(t("agent.f.protect.s.readOnly"))
            }
        }
        if (saved) StatusChip(t("agent.f.saved"), Tone.Ok)
    }
}

// ---------- Vorausschau ----------

/** Der Berater mit den Werten ab Werk (ForesightGroup im Web). */
private fun advisorOf(nas: JsonObject): JsonObject {
    val a = nas.o("advisor")
    return a.with(
        "enabled" to a.bool("enabled"),
        "predict" to a.bool("predict", true),
        "bottlenecks" to a.bool("bottlenecks", true),
        "prices" to a.bool("prices"),
        "monthly" to a.bool("monthly"),
        "at" to a.str("at").ifEmpty { "03:30" },
    )
}

@Composable
private fun BoxScope.WhatIfSheet(st: FeaturesState, close: () -> Unit) {
    val env = LocalEnv.current
    var local by remember { mutableStateOf("") }
    val on = st.nas?.o("whatIf")?.bool("enabled") == true
    DialogSheet(t("agent.f.foresight.whatIf"), onDismiss = close, buttons = listOf(SheetButton(t("common.close"), primary = true) { close() })) {
        if (local.isNotEmpty()) Banner(local, tone = Tone.Error)
        SwitchLine(t("agent.f.foresight.whatIf"), t(if (on) "agent.f.foresight.on" else "agent.f.foresight.off"), on, { v ->
            st.nas?.let { st.saveNas(it.with("whatIf" to jobj("enabled" to v))) { ok -> local = if (ok) "" else st.error } }
        }, enabled = st.nas != null)
        Muted(t("agent.f.foresight.whatIfIntro"))
        Muted(t("agent.f.foresight.whatIfWhere"))
        Muted(t("agent.f.foresight.whatIfData"))
        SmallButton(t("agent.f.foresight.openStorage"), { close(); env.open("/storage") })
    }
}

@Composable
private fun BoxScope.AdvisorSheet(st: FeaturesState, close: () -> Unit) {
    var local by remember { mutableStateOf("") }
    val nas = st.nas
    val adv = advisorOf(nas ?: JsonObject(emptyMap()))
    fun set(vararg change: Pair<String, Any?>) {
        val cur = st.nas ?: return
        st.saveNas(cur.with("advisor" to advisorOf(cur).with(*change))) { ok -> local = if (ok) "" else st.error }
    }
    var at by remember { mutableStateOf(adv.str("at")) }
    DialogSheet(t("agent.f.foresight.advisor"), onDismiss = close, buttons = listOf(SheetButton(t("common.close"), primary = true) { close() })) {
        if (local.isNotEmpty()) Banner(local, tone = Tone.Error)
        val on = adv.bool("enabled")
        SwitchLine(t("agent.f.foresight.advisor"), t(if (on) "agent.f.foresight.on" else "agent.f.foresight.off"), on, { set("enabled" to it) }, enabled = nas != null)
        Divider()
        for (k in listOf("predict", "bottlenecks", "prices", "monthly")) {
            SwitchLine(t("agent.f.foresight.set.$k"), t("agent.f.foresight.set.${k}Sub"), adv.bool(k), { set(k to it) }, enabled = nas != null)
        }
        Plain(t("agent.f.foresight.set.at"), bold = true)
        Field(at, { at = it.filter { c -> c.isDigit() || c == ':' }.take(5) }, label = t("agent.f.foresight.set.at"), placeholder = "03:30", singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        Muted(t("agent.f.foresight.set.atHint"))
        LaunchedEffect(at) {
            if (!TIME.matches(at) || at == adv.str("at")) return@LaunchedEffect
            delay(900)
            set("at" to at)
        }
    }
}

// ---------- Gemeinschaft und Familie ----------

@Composable
private fun BoxScope.BuddySheet(st: FeaturesState, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    var buddy by remember { mutableStateOf(st.nas?.o("buddy") ?: JsonObject(emptyMap())) }
    var volumes by remember { mutableStateOf<List<String>>(emptyList()) }
    var local by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { volumes = runCatching { env.api.obj("agent/buddy").texts("volumes") }.getOrDefault(emptyList()) }
    fun save() {
        val cur = st.nas ?: return
        if (buddy.str("time").isNotEmpty() && !TIME.matches(buddy.str("time"))) { local = tr.t("error.invalid"); return }
        busy = true
        saved = false
        local = ""
        st.saveNas(cur.with("buddy" to buddy)) { ok ->
            busy = false
            saved = ok
            if (!ok) local = st.error
        }
    }
    DialogSheet(
        t("agent.f.family.buddy.title"),
        onDismiss = close,
        buttons = listOf(
            SheetButton(t("common.close")) { close() },
            SheetButton(t("agent.f.family.save"), primary = true, enabled = !busy && st.nas != null) { save() },
        ),
    ) {
        Muted(t("agent.f.family.buddy.intro"))
        Plain(t("agent.f.family.buddy.address"), bold = true)
        Field(buddy.str("address"), { buddy = buddy.with("address" to it.trim()) }, label = t("agent.f.family.buddy.address"), placeholder = "nas-eva.tail1234.ts.net", singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        Muted(t("agent.f.family.buddy.addressHint"))
        Plain(t("agent.f.family.buddy.volume"), bold = true)
        val v = buddy.str("volume")
        Select(v, listOf("" to t("agent.f.family.buddy.noVolume")) + volumes.map { it to it } + (if (v.isNotEmpty() && v !in volumes) listOf(v to v) else emptyList()), { buddy = buddy.with("volume" to it) }, label = t("agent.f.family.buddy.volume"))
        Muted(t("agent.f.family.buddy.volumeHint"))
        Plain(t("agent.f.family.buddy.every"), bold = true)
        val every = buddy.str("every").ifEmpty { "weekly" }
        Select(every, listOf("daily", "weekly", "monthly").map { it to tr.t("agent.f.family.buddy.every.$it") }, { buddy = buddy.with("every" to it) }, label = t("agent.f.family.buddy.every"))
        Plain(t("agent.f.family.buddy.time"), bold = true)
        Field(buddy.str("time"), { buddy = buddy.with("time" to it.filter { c -> c.isDigit() || c == ':' }.take(5)) }, label = t("agent.f.family.buddy.time"), placeholder = "02:00", singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        if (every == "weekly") {
            Plain(t("agent.f.family.buddy.weekday"), bold = true)
            Select(buddy.int("weekday"), (0..6).map { it to tr.t("agent.f.family.wd$it") }, { buddy = buddy.with("weekday" to it) }, label = t("agent.f.family.buddy.weekday"))
        }
        Plain(t("agent.f.family.buddy.probe"), bold = true)
        Select(buddy.str("probe").ifEmpty { "monthly" }, listOf("monthly" to t("agent.f.family.buddy.probe.monthly"), "off" to t("agent.f.family.buddy.probe.off")), { buddy = buddy.with("probe" to it) }, label = t("agent.f.family.buddy.probe"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Plain(t("agent.f.family.buddy.probeFiles"), bold = true, modifier = Modifier.weight(1f))
            NumberField(buddy.long("probeFiles").takeIf { it > 0 }, { buddy = buddy.with("probeFiles" to (it ?: 0L).coerceIn(0, 200)) }, label = t("agent.f.family.buddy.probeFiles"), allowEmpty = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Plain(t("agent.f.family.buddy.protect"), bold = true, modifier = Modifier.weight(1f))
            NumberField(buddy.long("protectDays").takeIf { it > 0 }, { buddy = buddy.with("protectDays" to (it ?: 0L).coerceIn(0, 365)) }, label = t("agent.f.family.buddy.protect"), allowEmpty = true)
        }
        Muted(t("agent.f.family.buddy.protectHint"))
        if (local.isNotEmpty()) Banner(local, tone = Tone.Error)
        if (saved) StatusChip(t("agent.f.saved"), Tone.Ok)
    }
}

@Composable
private fun BoxScope.CatalogSheet(st: FeaturesState, close: () -> Unit) {
    val on = st.nas?.o("catalog")?.bool("enabled") == true
    DialogSheet(t("agent.f.family.catalog.title"), onDismiss = close, buttons = listOf(SheetButton(t("common.close"), primary = true) { close() })) {
        Muted(t("agent.f.family.catalog.intro"))
        if (!on) Muted(t("agent.f.family.catalog.offHint"))
    }
}
