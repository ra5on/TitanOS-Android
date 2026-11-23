package io.github.ra5on.titanos.settings.pages.smarthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.num
import io.github.ra5on.titanos.settings.common.numOrNull
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Currency

// Die Ansichten der Funktionen des KI-Agenten, die das Web aus der
// Systemsteuerung › KI-Agent › Funktionen öffnet (DocsViews, ForesightAdvisor
// mit ForesightWhatIf, FamilyBuddy, FamilyCatalog). Die Ansichten der Gruppe
// „Schutz“ stehen in ProtectViews.kt.

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** Cent als Euro wie `euro()` in features/docs.ts: 1.284,37 €. */
internal fun euro(tr: Tr, cents: Long): String =
    NumberFormat.getCurrencyInstance(Fmt.locale(tr)).apply { currency = Currency.getInstance("EUR") }.format(cents / 100.0)

/** Ein Fehler oben in einer Ansicht (role=alert im Web). */
@Composable
private fun ViewError(text: String) {
    if (text.isNotEmpty()) Banner(text, tone = Tone.Error)
}

/** Eine Ansicht als Blatt mit „Schließen“. */
@Composable
internal fun BoxScope.ViewSheet(title: String, close: () -> Unit, body: @Composable ColumnScope.() -> Unit) =
    DialogSheet(title, onDismiss = close, buttons = listOf(SheetButton(t("common.close"), primary = true) { close() }), body = body)

/** Eine Zeile einer Ansicht: fett, darunter klein, rechts Knöpfe oder ein Zustand. */
@Composable
private fun ViewRow(title: String, sub: String? = null, end: (@Composable () -> Unit)? = null) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (title.isNotEmpty()) Plain(title, bold = true)
            if (!sub.isNullOrEmpty()) Muted(sub)
        }
        end?.invoke()
    }
}

// ---------------------------------------------------------------- Papierkram

private val PAPER_TYPES = listOf("energy", "telecom", "insurance", "shopping", "health", "housing", "tax", "other")

private fun monthName(tr: Tr, month: String, long: Boolean): String {
    val d = runCatching { LocalDate.parse("$month-15") }.getOrNull() ?: return month
    val name = d.month.getDisplayName(if (long) TextStyle.FULL else TextStyle.SHORT, Fmt.locale(tr))
    return if (long) "$name ${d.year}" else name
}

private fun shortDay(tr: Tr, day: String): String =
    runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern(if (tr.de) "dd.MM." else "dd/MM", Fmt.locale(tr))) }.getOrDefault(day)

private fun ago(tr: Tr, at: String): String {
    val i = Fmt.instant(at) ?: return ""
    val min = maxOf(0L, (System.currentTimeMillis() - i.toEpochMilli()) / 60_000)
    return when {
        min < 60 -> tr.t("agent.f.docs.agoMin", "n" to maxOf(1L, min))
        min < 48 * 60 -> tr.t("agent.f.docs.agoH", "n" to Math.round(min / 60.0))
        else -> tr.t("agent.f.docs.agoD", "n" to Math.round(min / 1440.0))
    }
}

private fun entrySub(tr: Tr, e: JsonObject): String {
    val typeName = { k: String -> tr.or("agent.f.docs.type.$k", k) }
    when (e.str("state")) {
        "waiting" -> return tr.t("agent.f.docs.waitingModel")
        "ask" -> {
            val doubt = e.strings("doubt")
            if (doubt.size == 2) return tr.t("agent.f.docs.unsureOf", "a" to typeName(doubt[0]), "b" to typeName(doubt[1]))
            return tr.t(if (e.str("error").isNotEmpty()) "agent.f.docs.unsureError" else "agent.f.docs.unsure")
        }
    }
    val from = when (e.str("origin")) {
        "mailtext" -> tr.t("agent.f.docs.fromMailText")
        "mail" -> tr.t("agent.f.docs.fromMail")
        else -> tr.t("agent.f.docs.fromInbox")
    }
    return "$from → ${e.str("to")} · ${ago(tr, e.str("at"))}"
}

/** Papierkram (DocsViews id=paper): Monate, Summen nach Art, offene Zahlungen, zuletzt abgelegt mit Rückgängig und Zuordnen. */
@Composable
internal fun BoxScope.PaperViewSheet(close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    var month by remember { mutableStateOf("") }
    var round by remember { mutableIntStateOf(0) }
    var sum by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(month, round) {
        try {
            val r = env.api.obj("agent/paper" + if (month.isNotEmpty()) "?month=${enc(month)}" else "")
            sum = r
            error = ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
    }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                round++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
        }
    }
    ViewSheet(t("agent.f.menu.paper"), close) {
        ViewError(error)
        val s = sum
        if (s == null) {
            if (error.isEmpty()) LoadingLine(t("common.loading"))
            return@ViewSheet
        }
        val months = s.strings("months")
        val shown = s.str("month")
        if (months.isNotEmpty()) {
            Muted(t("agent.f.docs.month"))
            Segments(shown, months.mapIndexed { i, m -> m to monthName(tr, m, i == months.size - 1) }, { month = it })
        }
        SettingGroup {
            Muted(tr.t("agent.f.docs.spentIn", "month" to monthName(tr, shown, true).substringBefore(' ')))
            Plain(euro(tr, s.long("total")), bold = true)
            val prev = months.getOrNull(1) ?: shown
            Muted(tr.t("agent.f.docs.bills", "count" to s.long("count")) + " · " + monthName(tr, prev, true).substringBefore(' ') + " " + euro(tr, s.long("prevTotal")))
        }
        SettingGroup(t("agent.f.docs.byType")) {
            val types = s.a("types").objects()
            if (types.isEmpty()) Muted(t("agent.f.docs.noneMonth"))
            for (ty in types) {
                val count = ty.long("count")
                val senders = ty.strings("senders")
                val sub = if (count > 2 || senders.isEmpty()) tr.t("agent.f.docs.bills", "count" to count)
                else senders.joinToString(", ") + if (count > 1) " · " + tr.t("agent.f.docs.bills", "count" to count) else ""
                ViewRow(tr.or("agent.f.docs.type.${ty.str("type")}", ty.str("type")), sub) { Plain(euro(tr, ty.long("cents")), bold = true) }
            }
        }
        SettingGroup(t("agent.f.docs.open")) {
            val open = s.a("open").objects()
            if (open.isEmpty()) Muted(t("agent.f.docs.noneOpen"))
            for (e in open) {
                ViewRow(listOf(e.str("sender"), e.str("kind")).filter { it.isNotEmpty() }.joinToString(" "), tr.t("agent.f.docs.dueOn", "date" to shortDay(tr, e.str("due")))) {
                    Plain(euro(tr, e.long("cents")), bold = true)
                    SmallButton(t("agent.f.docs.paid"), { act { env.api.post("agent/paper/${enc(e.str("id"))}/paid", jobj("paid" to true)) } })
                }
            }
        }
        SettingGroup(t("agent.f.docs.recent")) {
            val recent = s.a("recent").objects()
            if (recent.isEmpty()) Muted(t("agent.f.docs.noneRecent"))
            for (e in recent) {
                ViewRow(e.str("name"), entrySub(tr, e)) {
                    if (e.str("state") == "filed") {
                        SmallButton(t("agent.f.docs.undo"), { act { env.api.post("agent/paper/${enc(e.str("id"))}/undo") } })
                    } else {
                        SmallButton(t("agent.f.docs.assign"), { ov.show { c -> AssignSheet(e, c) { round++ } } })
                    }
                }
            }
        }
    }
}

/** „Zuordnen“ eines Belegs, bei dem der Agent unsicher war: Absender, Art, Bezeichnung, Datum. */
@Composable
private fun BoxScope.AssignSheet(e: JsonObject, close: () -> Unit, done: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var sender by remember { mutableStateOf(e.str("sender")) }
    var type by remember { mutableStateOf(e.strings("doubt").firstOrNull() ?: e.str("type").ifEmpty { "other" }) }
    var kind by remember { mutableStateOf(e.str("kind").takeIf { it != "Dokument" }.orEmpty()) }
    var date by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    DialogSheet(
        tr.t("agent.f.docs.assignTitle", "name" to e.str("name")),
        onDismiss = close,
        buttons = listOf(
            SheetButton(t("common.cancel")) { close() },
            SheetButton(t("common.save"), primary = true, enabled = !busy) {
                busy = true
                scope.launch {
                    try {
                        env.api.post("agent/paper/${enc(e.str("id"))}/assign", jobj("sender" to sender.trim(), "type" to type, "kind" to kind.trim(), "date" to date.trim()))
                        done()
                        close()
                    } catch (x: kotlinx.coroutines.CancellationException) {
                        throw x
                    } catch (x: Exception) {
                        error = tr.errorOf(x)
                    } finally {
                        busy = false
                    }
                }
            },
        ),
    ) {
        ViewError(error)
        Field(sender, { sender = it }, label = t("agent.f.docs.sender"), singleLine = true, modifier = Modifier.fillMaxWidth())
        Select(type, PAPER_TYPES.map { it to tr.t("agent.f.docs.type.$it") }, { type = it }, label = t("agent.f.docs.typeLabel"))
        Field(kind, { kind = it }, label = t("agent.f.docs.kind"), placeholder = t("agent.f.docs.kindHint"), singleLine = true, modifier = Modifier.fillMaxWidth())
        Field(date, { date = it.filter { c -> c.isDigit() || c == '-' }.take(10) }, label = t("agent.f.docs.date"), placeholder = "2026-11-22", singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- Fristen

/** Gefundene Fristen (DocsViews id=deadlines): übernehmen, bezahlt, Datei öffnen, verwerfen. */
@Composable
internal fun BoxScope.DeadlinesViewSheet(close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JsonObject>?>(null) }
    var error by remember { mutableStateOf("") }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        try {
            items = env.api.obj("agent/inbox").a("items").objects().filter { it.str("kind") == "deadline" }.reversed()
            error = ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
    }
    fun decide(item: JsonObject, accept: Boolean, before: (suspend () -> Unit)? = null) {
        error = ""
        scope.launch {
            try {
                before?.invoke()
                env.api.post("agent/inbox/${enc(item.str("id"))}", jobj("accept" to accept))
                items = items?.filter { it.str("id") != item.str("id") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
                round++
            }
        }
    }
    ViewSheet(t("agent.f.docs.suggestions"), close) {
        ViewError(error)
        val list = items
        if (list == null) {
            if (error.isEmpty()) LoadingLine(t("common.loading"))
            return@ViewSheet
        }
        if (list.isEmpty()) Muted(t("agent.f.docs.noneDeadlines"))
        for (item in list) {
            val data = item.o("data")
            SettingGroup {
                Plain(item.str("title"), bold = true)
                val body = item.str("body")
                if (body.isNotEmpty()) {
                    Muted(body + if (data.str("deadline") == "payment") " · " + tr.t(if (data.str("debit") == "1") "agent.f.docs.debit" else "agent.f.docs.noDebit") else "")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton(t("agent.inbox.take"), { decide(item, true) }, kind = ButtonKind.Primary)
                    if (data.str("deadline") == "payment" && data.str("paper").isNotEmpty()) {
                        SmallButton(t("agent.f.docs.paid"), { decide(item, false) { env.api.post("agent/paper/${enc(data.str("paper"))}/paid", jobj("paid" to true)) } })
                    } else if (data.str("root").isNotEmpty() && data.str("path").isNotEmpty()) {
                        SmallButton(t("agent.f.docs.openFile"), {
                            val dir = data.str("path").split('/').dropLast(1).joinToString("/")
                            close()
                            env.open("/files?root=${enc(data.str("root"))}&path=${enc(dir)}")
                        })
                    }
                    SmallButton(t("agent.f.docs.reject"), { decide(item, false) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Hardware-Berater

private fun fk(tr: Tr, key: String, vararg p: Pair<String, Any?>): String = tr.or("agent.f.foresight.$key", key, *p)

private fun yearsOf(hours: Double): Double = Math.round(hours / (24 * 365) * 10) / 10.0

private fun findingTone(level: String): Tone = when (level) {
    "ok" -> Tone.Ok
    "fail" -> Tone.Error
    else -> Tone.Warn
}

private fun findingSub(tr: Tr, f: JsonObject): String {
    val n = { k: String -> Fmt.number(tr, f.num(k), 1) }
    val p = arrayOf<Pair<String, Any?>>(
        "from" to n("from"), "to" to n("to"), "weeks" to n("weeks"), "percent" to n("percent"),
        "days" to n("days"), "hours" to n("hours"), "label" to f.str("label"), "celsius" to n("celsius"),
    )
    val code = f.str("code")
    val text = when (code) {
        "wornOut" -> fk(tr, if (f.has("days")) "c.wornOutDays" else "c.wornOut", *p)
        "gpuOK", "gpuFull" -> {
            val total = Fmt.bytes(tr, f.long("totalBytes"))
            if (!f.has("bytes")) fk(tr, "c.gpuUnknown", "total" to total)
            else fk(tr, if (code == "gpuFull") "c.gpuFull" else if (f.bool("ollama")) "c.gpuOllama" else "c.gpuOK", "used" to Fmt.bytes(tr, f.long("bytes")), "total" to total)
        }
        else -> fk(tr, "c.$code", *p)
    }
    val parts = mutableListOf(text)
    if (f.long("suggestBytes") > 0) parts += fk(tr, "c.suggest", "bytes" to "${Math.round(f.long("suggestBytes") / (1L shl 30).toDouble())} GB")
    if (f.str("area") == "disk" && f.num("hours") > 0) parts += fk(tr, "age", "years" to Fmt.number(tr, yearsOf(f.num("hours")), 1))
    val also = f.strings("also")
    if (also.isNotEmpty()) parts += fk(tr, "also", "list" to also.joinToString(", ") { fk(tr, "short.$it") })
    return parts.joinToString(" · ")
}

/** Bericht des Hardware-Beraters (ForesightAdvisor): Platten, die bald ausfallen, dann Speicher, Volumes, Grafik, Temperaturen. */
@Composable
internal fun BoxScope.AdvisorReportSheet(close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var whatIf by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        whatIf = runCatching { env.api.obj("agent/whatif").bool("enabled") }.getOrDefault(false)
    }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                view = env.api.obj("agent/advisor")
                error = ""
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
            // Läuft gerade ein Lauf der Nacht, wird bis zum Ende nachgefragt.
            if (view?.bool("running") != true) break
            delay(3000)
        }
    }
    fun run() {
        busy = true
        error = ""
        scope.launch {
            try {
                view = env.api.post("agent/advisor/run") as? JsonObject ?: view
                while (view?.bool("running") == true) {
                    delay(3000)
                    view = env.api.obj("agent/advisor")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            } finally {
                busy = false
            }
        }
    }
    fun playDisk(disk: String) {
        scope.launch {
            try {
                val vols = env.api.obj("storage").a("volumes").objects()
                val vol = vols.firstOrNull { v -> v.a("members").objects().any { it.str("disk") == disk } }
                val start = jobj("kind" to "disk", "disk" to disk, "volume" to vol?.str("name"))
                ov.show { c -> WhatIfPlaySheet(listOf(start), start, c) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
        }
    }
    ViewSheet(fk(tr, "advisor"), close) {
        ViewError(error)
        val v = view
        if (v == null) {
            if (error.isEmpty()) LoadingLine(t("common.loading"))
            return@ViewSheet
        }
        val saved = v.o("saved").takeIf { v.has("saved") }
        if (saved == null) Muted(fk(tr, "empty"))
        val findings = saved?.o("report")?.a("findings")?.objects().orEmpty()
        val failing = findings.filter { it.str("area") == "disk" && it.str("level") == "fail" }
        val rows = findings.filter { !(it.str("area") == "disk" && (it.str("level") == "fail" || it.str("level") == "ok")) }
        for (f in failing) {
            Banner(findingSub(tr, f), tone = Tone.Error, title = fk(tr, "failHead", "disk" to f.str("subject")))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton(fk(tr, "history"), { close(); env.open("/storage/smart?disk=${enc(f.str("subject"))}") })
                if (whatIf) SmallButton(fk(tr, "playThrough"), { playDisk(f.str("subject")) })
            }
            val offers = saved?.o("offers")?.a(f.str("subject"))?.objects().orEmpty()
            if (offers.isNotEmpty()) {
                Muted(fk(tr, "offers"))
                for (o in offers) {
                    val price = o.str("price").takeIf { it.isNotEmpty() }?.let { " · $it €" }.orEmpty()
                    LinkButton(o.str("title") + price, { env.open(o.str("url")) })
                }
            }
        }
        if (rows.isNotEmpty()) {
            SettingGroup {
                for (f in rows) {
                    ViewRow(fk(tr, "area.${f.str("area")}", "name" to f.str("subject")), findingSub(tr, f)) {
                        StatusChip(fk(tr, "badge.${f.str("level")}"), findingTone(f.str("level")))
                    }
                }
            }
        }
        if (saved != null) {
            Muted(fk(tr, "lastRun", "time" to Fmt.dateTime(tr, saved.str("lastRun"))) + if (!saved.bool("history")) " · " + fk(tr, "noHistory") else "")
        }
        val running = busy || v.bool("running")
        SmallButton(if (running) fk(tr, "running") else fk(tr, "run"), { run() }, enabled = !running)
        if (v.o("settings").bool("prices") && !v.bool("web")) Muted(fk(tr, "set.pricesNoWeb"))
        Muted(fk(tr, "footer") + if (!v.o("settings").bool("prices")) " " + fk(tr, "footerNoPrices") else "")
    }
}

// ---------------------------------------------------------------- Was wäre wenn

private fun scenarioKey(s: JsonObject) = "${s.str("kind")}:${s.str("disk")}:${s.str("volume")}:${s.str("level")}"

private fun levelName(tr: Tr, l: String) = if (l.isEmpty()) "" else tr.or("agent.f.foresight.level.$l", l)

private fun outcomeTone(r: JsonObject): Tone {
    when (r.str("outcome")) {
        "lost", "possiblyLost", "cacheLost" -> return Tone.Error
        "noRedundancy", "alreadyFailed" -> return Tone.Warn
    }
    if (r.has("full")) {
        val f = r.o("full")
        return if ((f.numOrNull("daysToFull") ?: Double.MAX_VALUE) < 60 || f.num("usedPct") >= 90) Tone.Warn else Tone.Ok
    }
    if (r.has("compare")) return if (r.o("compare").bool("fits")) Tone.Ok else Tone.Warn
    return Tone.Ok
}

/**
 * Durchspielen (ForesightWhatIf): was passiert, wenn eine Platte ausfällt,
 * ein Volume voll läuft oder eine andere Stufe gewählt wäre. Am NAS ändert
 * sich nichts; „Starten“ startet nur einen gewählten Sicherungsauftrag.
 */
@Composable
internal fun BoxScope.WhatIfPlaySheet(scenarios: List<JsonObject>, start: JsonObject, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(scenarioKey(start)) }
    val scenario = scenarios.firstOrNull { scenarioKey(it) == current } ?: start
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(current) {
        result = null
        error = ""
        try {
            result = env.api.post("agent/whatif", JsonObject(scenario.filterValues { it.toString() != "null" && it.toString() != "\"\"" })) as? JsonObject
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
    }
    fun optionLabel(s: JsonObject) = when (s.str("kind")) {
        "disk" -> fk(tr, "opt.disk", "disk" to s.str("disk"))
        "full" -> fk(tr, "opt.full", "volume" to s.str("volume"))
        else -> fk(tr, "opt.level", "level" to levelName(tr, s.str("level")))
    }
    val title = when (scenario.str("kind")) {
        "disk" -> fk(tr, "title.disk", "disk" to scenario.str("disk"))
        "full" -> fk(tr, "title.full", "volume" to scenario.str("volume"))
        else -> fk(tr, "title.level", "volume" to scenario.str("volume"), "level" to levelName(tr, scenario.str("level")))
    }
    ViewSheet(title, close) {
        if (scenarios.size > 1) {
            Select(current, scenarios.map { scenarioKey(it) to optionLabel(it) }, { current = it }, label = fk(tr, "pick"))
        }
        ViewError(error)
        val r = result
        if (r == null) {
            if (error.isEmpty()) LoadingLine(fk(tr, "loading"))
            return@ViewSheet
        }
        val (head, sub) = whatIfHead(tr, r)
        Banner(sub, tone = outcomeTone(r), title = head)
        SettingGroup {
            if (r.has("rebuild")) {
                val rb = r.o("rebuild")
                val speed = Fmt.number(tr, rb.num("bps") / 1_000_000.0, 0) + " MB/s"
                var text = fk(tr, if (r.str("filesystem") == "btrfs") "rebuildBtrfs" else "rebuildSub", "size" to Fmt.diskBytes(tr, rb.long("bytes")), "time" to Fmt.duration(tr, rb.long("seconds")), "how" to fk(tr, if (rb.bool("measured")) "measured" else "estimated"), "speed" to speed)
                if (rb.bool("auto")) text += "\n" + fk(tr, "auto")
                ViewRow(fk(tr, "rebuild"), text)
            }
            if (r.has("risk")) {
                val risk = r.o("risk")
                val reasons = risk.a("reasons").objects()
                val text = if (reasons.isEmpty()) fk(tr, "r.none") else reasons.joinToString(" · ") { x ->
                    fk(tr, "r.${x.str("code")}", "disk" to x.str("disk"), "value" to x.long("value"), "years" to Fmt.number(tr, yearsOf(x.num("value")), 1))
                }
                val level = risk.str("level")
                ViewRow(fk(tr, "risk"), text) { StatusChip(fk(tr, "risk.$level"), if (level == "normal") Tone.Ok else if (level == "high") Tone.Error else Tone.Warn) }
            }
            if (r.has("full")) {
                ViewRow(fk(tr, "full.effects"), r.o("full").strings("effects").joinToString(" · ") { fk(tr, "e.$it") })
            }
            if (r.has("compare")) {
                val c = r.o("compare")
                ViewRow(fk(tr, "lv.tolerance"), fk(tr, "lv.toleranceSub", "a" to c.o("from").long("tolerates"), "b" to c.o("to").long("tolerates"), "to" to levelName(tr, c.o("to").str("level"))))
                var space = fk(tr, "lv.spaceSub", "from" to Fmt.diskBytes(tr, c.o("from").long("usableBytes")), "to" to Fmt.diskBytes(tr, c.o("to").long("usableBytes")))
                if (!c.bool("fits")) space += " · " + fk(tr, "lv.noFit", "used" to Fmt.diskBytes(tr, c.long("usedBytes")))
                ViewRow(fk(tr, "lv.space"), space) { StatusChip(if (c.bool("fits")) fk(tr, "badge.ok") else fk(tr, "badge.tight"), if (c.bool("fits")) Tone.Ok else Tone.Warn) }
            }
            for (g in r.a("gaps").objects()) {
                val share = g.str("share")
                val text = when {
                    g.bool("never") -> fk(tr, "gap.never", "share" to share)
                    g.bool("unknown") -> fk(tr, "gap.unknown", "share" to share)
                    g.bool("notYet") -> fk(tr, "gap.notYet", "share" to share, "job" to g.str("job"))
                    else -> fk(tr, "gap.days", "share" to share, "days" to g.long("days"), "size" to if (g.long("bytes") > 0) " (${Fmt.diskBytes(tr, g.long("bytes"))})" else "")
                }
                ViewRow(fk(tr, "gap"), text) { StatusChip(fk(tr, "gapBadge"), if (g.bool("unknown")) Tone.Warn else Tone.Error) }
            }
            val safe = r.a("safe").objects()
            if (safe.isNotEmpty()) {
                ViewRow(fk(tr, "safe"), safe.joinToString(", ") { fk(tr, "safeItem", "share" to it.str("share"), "time" to Fmt.dateTime(tr, it.str("at"))) }) {
                    StatusChip(fk(tr, "badge.ok"), Tone.Ok)
                }
            }
        }
        if (r.str("filesystem") == "btrfs" && r.o("scenario").str("kind") == "disk") Muted(fk(tr, "snapshots"))
        val advice = r.a("advice").objects()
        if (advice.isNotEmpty()) {
            SettingGroup(fk(tr, "advice")) {
                for (a in advice) {
                    val (atitle, asub, act) = adviceOf(tr, a)
                    ViewRow(atitle, asub) {
                        when {
                            act?.startsWith("run:") == true -> {
                                val job = act.removePrefix("run:")
                                SmallButton(if (job in started) fk(tr, "a.started") else fk(tr, "a.start"), {
                                    scope.launch {
                                        try {
                                            env.api.post("backups/${enc(job)}/run")
                                            started = started + job
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            error = tr.errorOf(e)
                                        }
                                    }
                                }, kind = ButtonKind.Primary, enabled = job !in started)
                            }
                            act != null -> SmallButton(fk(tr, "a.open"), { close(); env.open(act) })
                        }
                    }
                }
            }
        }
        Muted(fk(tr, "footerWhatIf"))
    }
}

private fun whatIfHead(tr: Tr, r: JsonObject): Pair<String, String> {
    val disk = r.str("disk").let { if (it.isNotEmpty() && r.str("serial").isNotEmpty()) "$it (${r.str("serial")})" else it }
    val p = arrayOf<Pair<String, Any?>>(
        "volume" to r.str("volume"), "disk" to disk, "level" to levelName(tr, r.str("level")),
        "n" to r.long("disks"), "bytes" to Fmt.diskBytes(tr, r.long("dataAtRiskBytes")),
    )
    if (r.has("full")) {
        val f = r.o("full")
        val free = Fmt.diskBytes(tr, f.long("freeBytes"))
        val sub = if (f.has("daysToFull") && f.long("dailyBytes") > 0) fk(tr, "full.days", "perDay" to Fmt.bytes(tr, f.long("dailyBytes")), "days" to Math.round(f.num("daysToFull")), "free" to free)
        else fk(tr, "full.noGrowth", "free" to free)
        return fk(tr, "full.head", "volume" to r.str("volume"), "pct" to Math.round(f.num("usedPct"))) to sub
    }
    if (r.has("compare")) {
        val c = r.o("compare")
        return fk(tr, "lv.head", "to" to levelName(tr, c.o("to").str("level")), "from" to levelName(tr, c.o("from").str("level"))) to
            fk(tr, if (c.str("way") == "profile") "lv.profile" else "lv.newVolume")
    }
    val o = r.str("outcome").ifEmpty { "redundant" }
    return fk(tr, "o.$o", *p) to fk(tr, "o.${o}Sub", *p)
}

/** Titel, Erklärung und Ziel (`run:<job>` oder eine Adresse) eines Rats. */
private fun adviceOf(tr: Tr, a: JsonObject): Triple<String, String?, String?> {
    val size = if (a.long("bytes") > 0) Fmt.diskBytes(tr, a.long("bytes")) else ""
    return when (val code = a.str("code")) {
        "startBackup" -> Triple(fk(tr, "a.startBackup", "share" to a.str("share")), null, "run:" + a.str("job"))
        "createBackup" -> Triple(fk(tr, "a.createBackup", "share" to a.str("share")), null, "/backups")
        "replacement" -> Triple(fk(tr, "a.replacement"), fk(tr, if (a.str("kind") == "hdd") "a.replacementCMR" else "a.replacementSub", "size" to size), null)
        "restore" -> Triple(fk(tr, "a.restore"), null, "/backups")
        "checkCable" -> Triple(fk(tr, "a.checkCable", "disk" to a.str("disk")), fk(tr, "a.checkCableSub"), null)
        "addSpare" -> Triple(fk(tr, "a.addSpare"), fk(tr, "a.addSpareSub"), "/storage")
        "addDisks" -> Triple(fk(tr, "a.addDisks", "count" to a.long("count"), "level" to levelName(tr, a.str("level"))), null, null)
        "mirror", "grow", "cleanSnapshots", "checkUsage", "cacheReadOnly", "profile", "newVolume" -> Triple(fk(tr, "a.$code"), null, "/storage")
        else -> Triple(code, null, null)
    }
}

// ---------------------------------------------------------------- Backup-Partner

private const val GB = 1_000_000_000L

private fun formatCode(raw: String): String {
    val c = raw.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }.take(9)
    return c.chunked(3).joinToString("-")
}

private fun quotaPercent(used: Long, quota: Long): Int = if (quota <= 0) 0 else (used * 100.0 / quota).toInt().coerceIn(0, 100)

/** Belegter Platz beim Partner als Balken mit Wert für Bildschirmleser (role=meter im Web). */
@Composable
private fun QuotaBar(label: String, percent: Int) {
    LinearProgressIndicator(
        progress = { percent / 100f },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics {
            contentDescription = "$label: $percent %"
            progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f)
        },
        color = Titan.colors.accent,
        trackColor = Titan.colors.chip,
    )
}

/**
 * Backup-Partner (FamilyBuddy): die Partner, was dieses NAS bei jedem hält
 * und jeder hier, die Probe-Wiederherstellung und das Koppeln mit Einmalcode.
 * Kein Token und kein Schlüssel erreicht die App.
 */
@Composable
internal fun BoxScope.BuddyViewSheet(close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var quotaGB by remember { mutableStateOf("500") }
    var shares by remember { mutableStateOf(setOf<String>()) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        try {
            data = env.api.obj("agent/buddy")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
    }
    fun run(work: suspend () -> Unit, then: () -> Unit = {}) {
        error = ""
        busy = true
        scope.launch {
            try {
                work()
                round++
                then()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            } finally {
                busy = false
            }
        }
    }
    fun whenText(iso: String) = if (iso.isEmpty()) "" else Fmt.dateTime(tr, iso)
    DialogSheet(t("agent.f.family.buddy.title"), onDismiss = close, buttons = listOf(SheetButton(t("agent.f.family.close"), primary = true) { close() })) {
        ViewError(error)
        val d = data
        if (d == null) {
            if (error.isEmpty()) LoadingLine(t("agent.f.family.loading"))
            return@DialogSheet
        }
        val problem = d.str("problem")
        if (problem.isNotEmpty()) Banner(tr.or("agent.f.family.buddy.problem.$problem", problem), tone = Tone.Warn)
        val settings = d.o("settings")
        for (p in d.a("partners").objects()) {
            val name = p.str("name")
            val job = p.o("job").takeIf { p.has("job") }
            val failing = job != null && job.str("lastError").isNotEmpty() && (job.str("lastOK").isEmpty() || job.str("lastRun") > job.str("lastOK"))
            val last = when {
                job == null -> tr.t("agent.f.family.buddy.noJob")
                job.bool("running") -> tr.t("agent.f.family.buddy.running")
                job.str("lastOK").isNotEmpty() -> tr.t("agent.f.family.buddy.last", "when" to whenText(job.str("lastOK")))
                else -> tr.t("agent.f.family.buddy.never")
            }
            SettingGroup {
                ViewRow(tr.t("agent.f.family.buddy.paired", "name" to name), "${p.str("address")} · $last") {
                    if (failing) StatusChip(t("agent.f.family.buddy.failed"), Tone.Error)
                    else if (job?.str("lastOK")?.isNotEmpty() == true) StatusChip(t("agent.f.family.ok"), Tone.Ok)
                }
            }
            SettingGroup(tr.t("agent.f.family.buddy.iAtThem", "name" to name)) {
                if (p.str("jobError").isNotEmpty()) Banner(tr.t("agent.f.family.buddy.jobError", "error" to p.str("jobError")), tone = Tone.Error)
                val used = job?.long("bytes") ?: 0L
                val mine = p.strings("shares")
                ViewRow(
                    if (mine.isEmpty()) tr.t("agent.f.family.buddy.nothingChosen") else mine.joinToString(", "),
                    tr.t("agent.f.family.buddy.ofQuota", "used" to Fmt.bytes(tr, used), "quota" to Fmt.bytes(tr, p.long("theirQuota")), "name" to name),
                )
                val spaceLabel = tr.t("agent.f.family.buddy.space", "name" to name)
                Muted(spaceLabel)
                QuotaBar(spaceLabel, quotaPercent(used, p.long("theirQuota")))
                val probe = job?.o("probe")?.takeIf { job.has("probe") }
                ViewRow(
                    tr.t("agent.f.family.buddy.probeTitle"),
                    if (probe != null) tr.t("agent.f.family.buddy.probeLast", "when" to whenText(probe.str("at")), "n" to probe.long("files")) else tr.t("agent.f.family.buddy.probeNone"),
                ) {
                    if (probe != null) {
                        if (probe.str("error").isNotEmpty()) StatusChip(t("agent.f.family.buddy.failed"), Tone.Error) else StatusChip(t("agent.f.family.ok"), Tone.Ok)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton(t("agent.f.family.buddy.change"), { ov.show { c -> BuddyEditSheet(p, d.strings("shares"), c) { round++ } } })
                    SmallButton(t("agent.f.family.buddy.end"), { ov.show { c -> BuddyEndSheet(p, c) { round++ } } }, kind = ButtonKind.Danger)
                }
            }
            SettingGroup(tr.t("agent.f.family.buddy.themAtMe", "name" to name)) {
                val volume = settings.str("volume")
                ViewRow(
                    if (p.bool("usedKnown")) tr.t("agent.f.family.buddy.hereOf", "used" to Fmt.bytes(tr, p.long("used")), "quota" to Fmt.bytes(tr, p.long("quota")), "volume" to volume)
                    else tr.t("agent.f.family.buddy.hereQuota", "quota" to Fmt.bytes(tr, p.long("quota")), "volume" to volume),
                    tr.t("agent.f.family.buddy.theirKey", "name" to name),
                )
            }
        }
        SettingGroup(t("agent.f.family.buddy.new")) {
            val offered = d.o("code").takeIf { d.has("code") }
            if (offered != null) {
                Muted(t("agent.f.family.buddy.codeHint"))
                Mono(offered.str("code"))
                Muted(tr.t("agent.f.family.buddy.codeUntil", "when" to whenText(offered.str("expires"))) + " · " + tr.t("agent.f.family.buddy.myAddress", "address" to settings.str("address").ifEmpty { "–" }))
                SmallButton(t("agent.f.family.buddy.codeCancel"), { run({ env.api.delete("agent/buddy/code") }) }, enabled = !busy)
            } else {
                Field(quotaGB, { quotaGB = it.filter(Char::isDigit).take(7) }, label = t("agent.f.family.buddy.offer"), suffix = "GB", singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Legend(t("agent.f.family.buddy.mine"))
                val all = d.strings("shares")
                if (all.isEmpty()) Muted(t("agent.f.family.buddy.noShares"))
                for (s in all) CheckRow(s in shares, { on -> shares = if (on) shares + s else shares - s }, s)
                val quota = (quotaGB.toLongOrNull() ?: 0L).coerceIn(1, 1_000_000) * GB
                SmallButton(t("agent.f.family.buddy.showCode"), { run({ env.api.post("agent/buddy/code", jobj("quota" to quota, "shares" to shares.toList())) }) }, kind = ButtonKind.Primary, enabled = !busy && problem != "off")
                Divider()
                Muted(t("agent.f.family.buddy.enterHint"))
                Field(address, { address = it.trim() }, label = t("agent.f.family.buddy.theirAddress"), placeholder = "nas-tom.tail1234.ts.net", singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                Field(code, { code = formatCode(it) }, label = t("agent.f.family.buddy.theirCode"), placeholder = "K7Q-4MZ-92X", mono = true, singleLine = true, keyboard = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                SmallButton(t("agent.f.family.buddy.pair"), {
                    run({
                        val r = env.api.post("agent/buddy/pair", jobj("address" to address, "code" to code, "quota" to quota, "shares" to shares.toList())) as? JsonObject
                        val jobError = r?.str("jobError").orEmpty()
                        address = ""
                        code = ""
                        if (jobError.isNotEmpty()) error = tr.t("agent.f.family.buddy.jobError", "error" to jobError)
                    })
                }, kind = ButtonKind.Primary, enabled = !busy && address.isNotEmpty() && code.length == 11 && problem != "off")
            }
        }
        Muted(tr.t("agent.f.family.buddy.footer", "days" to settings.long("protectDays", 30)))
    }
}

/** Platz und Freigaben für einen Partner ändern. */
@Composable
private fun BoxScope.BuddyEditSheet(p: JsonObject, all: List<String>, close: () -> Unit, done: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val stored = p.long("quota")
    val field = Math.round(stored / GB.toDouble())
    var quota by remember { mutableStateOf(field.toString()) }
    var chosen by remember { mutableStateOf(p.strings("shares").toSet()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val name = p.str("name")
    DialogSheet(
        tr.t("agent.f.family.buddy.editTitle", "name" to name),
        onDismiss = close,
        buttons = listOf(
            SheetButton(t("agent.f.family.cancel")) { close() },
            SheetButton(t("agent.f.family.save"), primary = true, enabled = !busy) {
                // Unverändert gelassen bleibt der gespeicherte Wert (vor 1.03 ganze GiB).
                val gb = quota.toLongOrNull() ?: field
                val bytes = if (gb == field) stored else gb * GB
                busy = true
                scope.launch {
                    try {
                        env.api.put("agent/buddy/partners/${enc(p.str("id"))}", jobj("quota" to bytes, "shares" to chosen.toList()))
                        done()
                        close()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = tr.errorOf(e)
                    } finally {
                        busy = false
                    }
                }
            },
        ),
    ) {
        Field(quota, { quota = it.filter(Char::isDigit).take(7) }, label = tr.t("agent.f.family.buddy.offerTo", "name" to name), suffix = "GB", singleLine = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Legend(tr.t("agent.f.family.buddy.iAtThem", "name" to name))
        for (s in all) CheckRow(s in chosen, { on -> chosen = if (on) chosen + s else chosen - s }, s)
        ViewError(error)
    }
}

/** Partnerschaft beenden; auf Wunsch mit seinen Daten hier. Der gefährliche Knopf steht links. */
@Composable
private fun BoxScope.BuddyEndSheet(p: JsonObject, close: () -> Unit, done: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var withData by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val name = p.str("name")
    DialogSheet(
        tr.t("agent.f.family.buddy.endTitle", "name" to name),
        onDismiss = close,
        buttons = listOf(
            SheetButton(t("agent.f.family.buddy.end"), danger = true, enabled = !busy) {
                busy = true
                scope.launch {
                    try {
                        env.api.delete("agent/buddy/partners/${enc(p.str("id"))}" + if (withData) "?data=1" else "")
                        done()
                        close()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = tr.errorOf(e)
                    } finally {
                        busy = false
                    }
                }
            },
            SheetButton(t("agent.f.family.cancel")) { close() },
        ),
    ) {
        Plain(tr.t("agent.f.family.buddy.endText", "name" to name))
        CheckRow(withData, { withData = it }, tr.t("agent.f.family.buddy.endData", "name" to name))
        ViewError(error)
    }
}

// ---------------------------------------------------------------- Skill-Katalog

/** Skill-Katalog (FamilyCatalog): Skills aus den Quellen des App-Stores ansehen und übernehmen, eigene ausgeben. */
@Composable
internal fun BoxScope.CatalogViewSheet(close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<JsonObject>?>(null) }
    var own by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var done by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = exportBytes
        exportBytes = null
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.onFailure { error = tr.errorOf(it) }
        }
    }
    LaunchedEffect(round) {
        try {
            skills = env.api.obj("agent/catalog").a("skills").objects()
            error = ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            skills = emptyList()
            error = tr.errorOf(e)
        }
        own = runCatching { env.api.obj("agent/skills").a("skills").objects().filter { !it.bool("builtIn") } }.getOrDefault(emptyList())
    }
    LaunchedEffect(exporting) {
        val id = exporting ?: return@LaunchedEffect
        try {
            exportBytes = env.api.bytes("agent/catalog/export/${enc(id)}")
            save.launch("skill.json")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        } finally {
            exporting = null
        }
    }
    DialogSheet(t("agent.f.family.catalog.title"), onDismiss = close, buttons = listOf(SheetButton(t("agent.f.family.close"), primary = true) { close() })) {
        Muted(t("agent.f.family.catalog.lead"))
        ViewError(error)
        if (done.isNotEmpty()) Banner(done, tone = Tone.Ok)
        val list = skills
        when {
            list == null -> LoadingLine(t("agent.f.family.loading"))
            list.isEmpty() && error.isEmpty() -> Muted(t("agent.f.family.catalog.empty"))
            list.isNotEmpty() -> SettingGroup {
                for (s in list) {
                    val key = s.str("key")
                    SettingRow(
                        s.str("name"),
                        desc = listOf(s.str("description"), s.str("sourceName")).filter { it.isNotEmpty() }.joinToString(" · "),
                        icon = WebIcons["zap"],
                        badge = t(if (s.bool("installed")) "agent.f.family.catalog.isInstalled" else "agent.f.family.catalog.notInstalled"),
                        onClick = { asking = if (asking == key) null else key },
                    )
                    if (asking == key) {
                        Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Plain(tr.t("agent.f.family.catalog.ask", "name" to s.str("name")), bold = true)
                            val tools = s.strings("tools")
                            Muted(t("agent.f.family.catalog.uses") + ": " + if (tools.isEmpty()) t("agent.f.family.catalog.noTools") else tools.joinToString(", "))
                            Muted(tr.t("agent.f.family.catalog.from", "source" to s.str("sourceName")) + if (s.str("author").isNotEmpty()) " · " + tr.t("agent.f.family.catalog.by", "who" to s.str("author")) else "")
                            Muted(t("agent.f.family.catalog.when") + ": " + s.str("when"))
                            Mono(s.str("steps"))
                            val unknown = s.strings("unknown")
                            if (unknown.isNotEmpty()) Banner(tr.t("agent.f.family.catalog.unknown", "tools" to unknown.joinToString(", ")), tone = Tone.Error)
                            if (s.bool("secret")) Banner(t("agent.f.family.catalog.secret"), tone = Tone.Error)
                            Muted(t("agent.f.family.catalog.askHint"))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SmallButton(t(if (s.bool("installed")) "agent.f.family.catalog.update" else "agent.f.family.catalog.install"), {
                                    busy = true
                                    error = ""
                                    scope.launch {
                                        try {
                                            env.api.post("agent/catalog/install", jobj("key" to key, "digest" to s.str("digest")))
                                            done = tr.t("agent.f.family.catalog.installed", "name" to s.str("name"))
                                            asking = null
                                            round++
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            error = tr.errorOf(e)
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }, kind = ButtonKind.Primary, enabled = !busy && unknown.isEmpty() && !s.bool("secret"))
                                SmallButton(t("agent.f.family.cancel"), { asking = null })
                            }
                        }
                    }
                }
            }
        }
        Legend(t("agent.f.family.catalog.share"))
        Muted(t("agent.f.family.catalog.shareHint"))
        if (own.isEmpty()) Muted(t("agent.f.family.catalog.noOwn"))
        for (s in own) {
            ViewRow(s.str("name")) { SmallButton(t("agent.f.family.catalog.export"), { exporting = s.str("id") }, enabled = exporting == null) }
        }
    }
}
