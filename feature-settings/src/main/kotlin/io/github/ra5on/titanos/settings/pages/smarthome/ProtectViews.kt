package io.github.ra5on.titanos.settings.pages.smarthome

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Die Ansichten der Gruppe „Schutz“ (ProtectViews im Web): Ransomware-Wächter,
// Notfallhandbuch und Notfallzugang. In der Systemsteuerung stehen sie über
// den Einstellungen der Funktion, das Notfallhandbuch und der Notfallzugang
// außerdem in „Mein Konto“.

private fun seg(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

private fun dayOrDate(tr: Tr, i: Instant): String {
    val zone = ZoneId.systemDefault()
    return if (i.atZone(zone).toLocalDate() == Instant.now().atZone(zone).toLocalDate()) tr.t("agent.f.protect.today") else Fmt.date(tr, i)
}

// ---------------------------------------------------------------- Wächter

/**
 * Ransomware-Wächter (ProtectGuard): der letzte Alarm mit was, wann, Gerät,
 * Konto und den betroffenen Dateien, dazu die drei Wege hinaus –
 * wiederherstellen (Snapshot, sonst Sicherung), Freigabe wieder öffnen,
 * Fehlalarm. Jede Aktion fragt einmal; Wiederherstellen ist der gefährliche Knopf.
 */
@Composable
fun GuardPanel() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val run = rememberRunner()
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    fun isOpen(a: JsonObject) = a.str("state") in setOf("active", "stopped", "restoring")
    LaunchedEffect(round) {
        while (true) {
            try {
                status = env.api.obj("agent/protect/guard")
                error = ""
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
            // Solange ein Angriff läuft oder Dateien zurückkommen, folgt die Ansicht alle 5 s.
            val follow = status?.a("alerts")?.objects().orEmpty().any {
                it.str("state") == "active" || it.str("state") == "restoring" || (it.str("state") == "stopped" && it.str("mode") == "auto")
            }
            if (!follow) break
            delay(5000)
        }
    }
    fun whenText(a: JsonObject): String {
        val first = Fmt.instant(a.str("first")) ?: return ""
        val last = Fmt.instant(a.str("last")) ?: first
        val from = Fmt.time(tr, first)
        val to = Fmt.time(tr, last)
        val day = dayOrDate(tr, first)
        return if (from == to) "$day $from" else "$day $from–$to"
    }
    fun device(a: JsonObject): String {
        val c = a.o("client")
        if (c.str("machine").isNotEmpty() && c.str("ip").isNotEmpty() && c.str("machine") != c.str("ip")) return "${c.str("machine")} (${c.str("ip")})"
        return c.str("ip").ifEmpty { c.str("machine").ifEmpty { c.str("process").ifEmpty { "?" } } }
    }
    fun ext(a: JsonObject): String {
        val f = a.a("files").objects().firstOrNull { it.str("now") != it.str("was") } ?: return ""
        val now = f.str("now")
        val i = now.lastIndexOf('.')
        return if (i > 0) now.substring(i) else ""
    }
    fun count(n: Long) = Fmt.number(tr, n.toDouble(), 0)
    fun whatText(a: JsonObject): String {
        var text = tr.or("agent.f.protect.g.what.${a.str("reason")}", a.str("reason"), "when" to whenText(a), "count" to count(a.long("count")), "ext" to ext(a))
        text += tr.t("agent.f.protect.g.from", "device" to device(a))
        if (a.o("client").str("user").isNotEmpty()) text += tr.t("agent.f.protect.g.account", "user" to a.o("client").str("user"))
        return "$text."
    }
    fun ask(a: JsonObject, action: String) {
        ov.confirm(
            tr.t("agent.f.protect.g.confirm.$action"),
            tr.t("agent.f.protect.g.confirmText.$action", "share" to a.str("share"), "count" to count(if (a.has("restorable")) a.long("restorable") else a.long("count"))),
            action = tr.t("agent.f.protect.g.confirmBtn.$action"),
            cancel = tr.t("common.cancel"),
            danger = action == "restore",
        ) {
            run.run(guard = when (action) { "restore" -> GuardAction.Backups; "reopen" -> GuardAction.Access; else -> null }) {
                env.api.post("agent/protect/guard/${seg(a.str("id"))}/$action")
                round++
            }
        }
    }
    if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
    val s = status ?: run {
        if (error.isEmpty()) LoadingLine(t("agent.f.protect.loading"))
        return
    }
    if (!s.bool("enabled")) {
        Muted(t("agent.f.notThere"))
        return
    }
    if (s.str("error").isNotEmpty()) Banner(tr.t("agent.f.protect.g.notWatching", "error" to s.str("error")), tone = Tone.Error)
    val alerts = s.a("alerts").objects()
    val alert = alerts.firstOrNull { isOpen(it) } ?: alerts.firstOrNull()
    if (alert == null) {
        Banner(
            (if (s.bool("watching")) tr.t("agent.f.protect.g.watching") else tr.t("agent.f.protect.g.starting")) + " " + tr.or("agent.f.protect.g.mode.${s.str("mode")}", s.str("mode")),
            tone = Tone.Ok,
            title = t("agent.f.protect.g.calm"),
        )
    } else {
        val open = isOpen(alert)
        val snapTime = Fmt.instant(alert.str("snapshotAt").ifEmpty { alert.str("first") })?.let { Fmt.time(tr, it) }.orEmpty()
        val stateText = when {
            alert.bool("locked") -> if (alert.str("snapshot").isNotEmpty()) tr.t("agent.f.protect.g.didSnap", "time" to snapTime) else tr.t("agent.f.protect.g.didLock")
            alert.str("state") == "restored" -> tr.t("agent.f.protect.g.didRestore", "count" to count(alert.o("restored").long("restored")), "bin" to count(alert.o("restored").long("recycled")))
            alert.str("mode") == "report" && open -> tr.t("agent.f.protect.g.onlyTold")
            else -> tr.or("agent.f.protect.g.state.${alert.str("state")}", alert.str("state"))
        }
        val body = buildString {
            append(whatText(alert))
            if (alert.str("state") == "active") append("\n").append(tr.t("agent.f.protect.g.ongoing"))
            append("\n").append(stateText)
            if (alert.str("error").isNotEmpty()) append("\n").append(alert.str("error"))
        }
        Banner(
            body,
            tone = if (open) Tone.Error else Tone.Info,
            title = tr.t(if (alert.bool("locked")) "agent.f.protect.g.locked" else "agent.f.protect.g.suspect", "share" to alert.str("share")),
        )
        SettingGroup(t("agent.f.protect.g.affected")) {
            val files = alert.a("files").objects()
            val shown = if (all) files else files.take(2)
            for (f in shown) {
                val now = f.str("now")
                val was = f.str("was")
                ProtectRow(now, if (now != was) tr.t("agent.f.protect.g.before", "name" to was.substringAfterLast('/')) else tr.t("agent.f.protect.g.rewritten"))
            }
            val rest = alert.long("count") - shown.size
            if (rest > 0 && !all) SettingRow(tr.t("agent.f.protect.g.more", "count" to count(rest)), onClick = { all = true })
            else if (rest > 0) Muted(tr.t("agent.f.protect.g.notListed", "count" to count(rest)))
        }
        if (open || alert.bool("locked")) {
            SettingGroup {
                val active = alert.str("state") == "active"
                if (alert.bool("btrfs") && (alert.str("snapshot").isNotEmpty() || alert.str("held").isNotEmpty())) {
                    ProtectRow(
                        tr.t("agent.f.protect.g.restore", "time" to snapTime),
                        if (active) tr.t("agent.f.protect.g.restoreWait") else tr.t("agent.f.protect.g.restoreSub", "count" to count(if (alert.has("restorable")) alert.long("restorable") else alert.long("count"))),
                    ) {
                        SmallButton(t("agent.f.protect.g.restoreBtn"), { ask(alert, "restore") }, kind = ButtonKind.Primary, enabled = !active && !run.running && alert.str("state") !in setOf("restored", "restoring"))
                    }
                } else {
                    ProtectRow(t("agent.f.protect.g.fromBackup"), t(if (alert.bool("btrfs")) "agent.f.protect.g.noSnapshot" else "agent.f.protect.g.noBtrfs")) {
                        SmallButton(t("agent.f.protect.g.backupBtn"), { env.open("/settings/backups/restore") })
                    }
                }
                if (alert.bool("locked")) {
                    ProtectRow(t("agent.f.protect.g.reopen"), t("agent.f.protect.g.reopenSub")) {
                        SmallButton(t("agent.f.protect.g.reopenBtn"), { ask(alert, "reopen") }, enabled = !active && !run.running)
                    }
                }
                if (open) {
                    ProtectRow(t("agent.f.protect.g.wanted"), t("agent.f.protect.g.wantedSub")) {
                        SmallButton(t("agent.f.protect.g.falseAlarm"), { ask(alert, "dismiss") }, enabled = !run.running)
                    }
                }
            }
        }
        RunnerState(run)
    }
    val earlier = alerts.filter { it !== alert }
    if (earlier.isNotEmpty()) {
        SettingGroup(t("agent.f.protect.g.earlier")) {
            for (a in earlier) {
                ProtectRow("${a.str("share")} · ${whenText(a)}", "${count(a.long("count"))} · ${device(a)} · ${tr.or("agent.f.protect.g.state.${a.str("state")}", a.str("state"))}")
            }
        }
    }
}

/** Eine Zeile einer Schutz-Ansicht: fett, darunter klein, Knöpfe brechen um. */
@Composable
private fun ProtectRow(title: String, sub: String?, end: (@Composable () -> Unit)? = null) {
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f)) {
            Plain(title, bold = true)
            if (!sub.isNullOrEmpty()) Muted(sub)
        }
        end?.invoke()
    }
}

// ---------------------------------------------------------------- Notfallhandbuch

/**
 * Notfallhandbuch (ProtectHandbook): der Aufbau des NAS und „Was tun, wenn …“
 * mit Herunterladen als PDF (Administratoren auch Markdown und „Neu erstellen“).
 * Nie Passwörter oder Schlüssel.
 */
@Composable
fun HandbookPanel() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var book by remember { mutableStateOf<JsonObject?>(null) }
    var off by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf("") }
    var round by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val lang = if (tr.de) "de" else "en"
    LaunchedEffect(round, lang) {
        try {
            book = env.api.obj("agent/protect/handbook?lang=$lang")
            off = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ApiException)?.code == "feature_off") off = true else error = tr.errorOf(e)
        }
    }
    fun save(launch: (String) -> Unit, format: String) {
        error = ""
        scope.launch {
            try {
                pending = env.api.bytes("agent/protect/handbook/download?lang=$lang&format=$format")
                launch(tr.t("agent.f.protect.handbook") + ".$format")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
        }
    }
    val write: (android.net.Uri?) -> Unit = { uri ->
        val bytes = pending
        pending = null
        if (uri != null && bytes != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.onFailure { error = tr.errorOf(it) }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), write)
    val md = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), write)
    if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
    if (off) {
        Muted(t("agent.f.notThere"))
        return
    }
    val b = book ?: run {
        if (error.isEmpty()) LoadingLine(t("agent.f.protect.loading"))
        return
    }
    val made = Fmt.instant(b.str("made"))?.let { "${dayOrDate(tr, it)} ${Fmt.time(tr, it)}" }.orEmpty()
    SettingGroup {
        ProtectRow(t("agent.f.protect.handbook"), tr.t("agent.f.protect.h.made", "when" to made)) {
            SmallButton("PDF", { save({ pdf.launch(it) }, "pdf") })
        }
        if (LocalEnv.current.admin) {
            Muted(t("agent.f.protect.h.nightly"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton(t("agent.f.protect.h.refresh"), {
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            env.api.post("agent/protect/handbook/refresh")
                            round++
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = tr.errorOf(e)
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy)
                SmallButton("Markdown", { save({ md.launch(it) }, "md") })
            }
        }
    }
    val facts = b.o("facts")
    fun names(key: String) = facts.a(key).objects().joinToString(", ") { it.str("name") }
    fun detailed(key: String) = facts.a(key).objects().joinToString(", ") { if (it.str("detail").isNotEmpty()) "${it.str("name")} (${it.str("detail")})" else it.str("name") }
    SettingGroup(t("agent.f.protect.h.build")) {
        val volumes = b.a("volumes").objects()
        if (volumes.isEmpty()) Muted(t("agent.f.protect.h.noVolumes"))
        for (v in volumes) ProtectRow(tr.t("agent.f.protect.h.volume", "name" to v.str("name")), v.str("line"))
        ProtectRow(t("agent.f.protect.h.shares"), names("shares").ifEmpty { t("agent.f.protect.h.none") })
        val apps = (facts.a("apps").objects() + facts.a("vms").objects()).joinToString(", ") { it.str("name") }
        ProtectRow(t("agent.f.protect.h.apps"), apps.ifEmpty { t("agent.f.protect.h.none") })
        if (facts.a("network").isNotEmpty()) ProtectRow(t("agent.f.protect.h.network"), detailed("network"))
        ProtectRow(t("agent.f.protect.h.remote"), detailed("remote").ifEmpty { t("agent.f.protect.h.noRemote") })
        ProtectRow(t("agent.f.protect.h.backups"), detailed("backups").ifEmpty { t("agent.f.protect.h.noBackups") })
        if (facts.a("probes").isNotEmpty()) ProtectRow(t("agent.f.protect.h.probes"), detailed("probes"))
        val contacts = facts.a("contacts").objects()
        if (contacts.isNotEmpty()) ProtectRow(t("agent.f.protect.h.contacts"), contacts.joinToString(", ") { if (it.str("phone").isNotEmpty()) "${it.str("name")} (${it.str("phone")})" else it.str("name") })
    }
    SettingGroup(t("agent.f.protect.h.whatIf")) {
        for (s in b.a("sections").objects()) {
            val steps = s.strings("steps")
            SettingRow(
                s.str("title"),
                desc = tr.t("agent.f.protect.h.steps", "count" to steps.size) + " · " + s.str("sub"),
                icon = WebIcons[when (s.str("id")) { "disk" -> "disk"; "boot" -> "power"; "stick" -> "key"; else -> "folder" }],
                id = "hb-" + s.str("id"),
                startOpen = opened == s.str("id"),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEachIndexed { i, step -> Plain("${i + 1}. $step") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Notfallzugang

/** Anteil der vergangenen Wartezeit (`waited` in features/protect.ts). */
fun waitedPart(req: JsonObject, now: Instant): Float {
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
 * Notfallzugang (ProtectEmergency): Besitzer und Administratoren sehen
 * Anträge, Freigaben, eingetragene Personen und den Verlauf; eine eingetragene
 * Person beantragt mit Grund (und Einmalcode), sieht die Wartezeit und kann
 * zurückziehen. Ist der Zugang gewährt, öffnet sie das Notfallhandbuch.
 * [onlyPerson]: wie in „Mein Konto“ nur zeigen, wenn das Konto eingetragen ist.
 */
@Composable
fun EmergencyPanel(name: String, admin: Boolean, onlyPerson: Boolean = false) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val run = rememberRunner()
    var me by remember { mutableStateOf<JsonObject?>(null) }
    var view by remember { mutableStateOf<JsonObject?>(null) }
    var reason by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(Instant.now()) }
    var round by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var showBook by remember { mutableStateOf(false) }
    LaunchedEffect(round) {
        try {
            me = env.api.obj("agent/protect/me")
            if (admin && (!onlyPerson || me.bool("emergency"))) view = env.api.obj("agent/protect/emergency")
            problem = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = tr.errorOf(e)
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    val m = me
    if (m == null) {
        if (!onlyPerson) problem?.let { Banner(it, tone = Tone.Error) } ?: LoadingLine(t("agent.f.protect.loading"))
        return
    }
    if (onlyPerson && !m.bool("emergency")) return

    @Composable
    fun progress(r: JsonObject) {
        val part = waitedPart(r, now)
        val label = t("agent.f.protect.e.waited", "percent" to (part * 100).toInt())
        LinearProgressIndicator(progress = { part }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = label }, color = Titan.colors.accent, trackColor = Titan.colors.track)
        Muted(label)
    }
    val act: (JsonObject, String) -> Unit = { r, action ->
        run.run(guard = if (action == "grant") GuardAction.Users else null) { env.api.post("agent/protect/emergency/${seg(r.str("id"))}/$action"); round++ }
    }
    fun ask(r: JsonObject, action: String) {
        val shares = (if (action == "end" && r.strings("added").isNotEmpty()) r.strings("added") else r.strings("shares")).joinToString(", ")
        val who = r.str("account")
        ov.confirm(
            if (action == "grant") tr.t("agent.f.protect.e.grantNow") else tr.t(if (action == "decline") "agent.f.protect.e.declineTitle" else "agent.f.protect.e.endTitle", "name" to who),
            tr.t(when (action) { "grant" -> "agent.f.protect.e.grantConfirm"; "decline" -> "agent.f.protect.e.declineConfirm"; else -> "agent.f.protect.e.endConfirm" }, "name" to who, "shares" to shares),
            action = tr.t(when (action) { "grant" -> "agent.f.protect.e.grantNow"; "decline" -> "agent.f.protect.e.decline"; else -> "agent.f.protect.e.endNow" }),
            cancel = tr.t("common.cancel"),
            danger = action != "grant",
        ) { act(r, action) }
    }
    problem?.let { Banner(it, tone = Tone.Error) }
    val v = view
    if (admin && v != null) {
        if (!v.bool("enabled")) Muted(t("agent.f.notThere"))
        val requests = v.a("requests").objects()
        for (r in requests.filter { it.str("state") == "waiting" }) {
            SettingRow(t("agent.f.protect.e.asks", "name" to r.str("account")), icon = WebIcons["key"], desc = dayText(tr, r.str("created"), now) + " · " + t("agent.f.protect.e.reason", "reason" to r.str("reason").ifEmpty { "–" })) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Plain(t("agent.f.protect.e.without", "when" to dueText(tr, r.str("due"))) + " " + r.strings("shares").joinToString(", ") + " " + t("agent.f.protect.e.andBook"))
                    progress(r)
                    if (r.str("account") != name) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Legend(t("agent.f.protect.e.persons"))
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
                            val c = env.api.post("agent/protect/emergency/code/${seg(p.str("account"))}").o().str("code")
                            round++
                            ov.show { close -> CodeSheet(p.str("account"), c, close) }
                        }
                    }, enabled = !run.running)
                }),
            )
        }
        Muted(t("agent.f.protect.e.footer"))
        val history = requests.filter { it.str("state") != "waiting" && it.str("state") != "granted" }.take(10)
        if (history.isNotEmpty()) {
            Legend(t("agent.f.protect.e.history"))
            for (r in history) {
                SettingRow(
                    r.str("account") + " · " + dayText(tr, r.str("created"), now),
                    desc = t("agent.f.protect.e.state.${r.str("state")}") + if (r.str("by").isNotEmpty()) " · ${r.str("by")}" else "",
                )
            }
        }
    } else if (!m.bool("emergency")) {
        Muted(t("agent.f.protect.e.notRegistered"))
    } else {
        val mine = m["request"] as? JsonObject
        when (mine?.str("state")) {
            "waiting" -> SettingRow(t("agent.f.protect.e.mineWaiting"), icon = WebIcons["key"], desc = t("agent.f.protect.e.mineWaitingText", "when" to dueText(tr, mine.str("due"))), startOpen = true, id = "em-waiting") {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    progress(mine)
                    SmallButton(t("agent.f.protect.e.withdraw"), { act(mine, "cancel") }, enabled = !run.running)
                }
            }
            "granted" -> {
                SettingRow(
                    t("agent.f.protect.e.mineGranted"),
                    icon = WebIcons["unlock"],
                    desc = t("agent.f.protect.e.mineGrantedText", "shares" to mine.strings("shares").joinToString(", "), "until" to (Fmt.instant(mine.str("ends"))?.let { Fmt.date(tr, it) } ?: "–")),
                    control = if (m.bool("handbook")) ({ SmallButton(t("agent.f.protect.e.openBook"), { showBook = !showBook }) }) else null,
                )
                if (showBook) HandbookPanel()
            }
            else -> {
                if (mine?.str("state") == "declined") Banner(t("agent.f.protect.e.mineDeclined"), tone = Tone.Error)
                val person = m.o("person")
                SettingRow(t("agent.f.protect.e.rowAsk"), icon = WebIcons["key"], desc = t("agent.f.protect.e.explain", "days" to person.int("waitDays", 3), "shares" to person.strings("shares").joinToString(", ")), startOpen = true, id = "em-ask") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(reason, { if (it.length <= 500) reason = it }, t("agent.f.protect.e.reasonLabel"), Modifier.fillMaxWidth(), minHeight = 80.dp)
                        if (m.bool("codes")) Field(code, { code = it }, t("agent.f.protect.e.codeLabel"), Modifier.fillMaxWidth(), singleLine = true)
                        SmallButton(t("agent.f.protect.e.ask"), {
                            run.run {
                                env.api.post("agent/protect/emergency/request", jobj("reason" to reason.trim(), "code" to code.trim()))
                                reason = ""
                                code = ""
                                round++
                            }
                        }, kind = ButtonKind.Primary, enabled = !run.running && reason.isNotBlank())
                    }
                }
            }
        }
        Muted(t("agent.f.protect.e.footer"), Modifier.padding(top = 4.dp))
    }
    RunnerState(run)
}

/** Der Einmalcode einer Person: nur jetzt sichtbar, zum Abschreiben, Kopieren oder Drucken. */
@Composable
private fun BoxScope.CodeSheet(account: String, code: String, close: () -> Unit) {
    val clip = LocalClipboardManager.current
    val context = LocalContext.current
    val tr = LocalTr.current
    DialogSheet(
        t("agent.f.protect.e.codeTitle", "name" to account),
        onDismiss = close,
        buttons = listOf(
            SheetButton(t("account.recoveryCopy")) { clip.setText(AnnotatedString(code)) },
            SheetButton(t("agent.f.protect.e.print")) {
                printCode(context, tr.t("agent.f.protect.e.codeTitle", "name" to account), tr.t("agent.f.protect.e.codeText"), code)
            },
            SheetButton(t("common.close"), primary = true) { close() },
        ),
    ) {
        Muted(t("agent.f.protect.e.codeText"))
        Mono(code)
    }
}

/** Druckt den Code über den Druckdienst des Handys (window.print() im Web). */
private fun printCode(context: android.content.Context, title: String, text: String, code: String) {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val web = android.webkit.WebView(context)
    web.webViewClient = object : android.webkit.WebViewClient() {
        override fun onPageFinished(view: android.webkit.WebView, url: String?) {
            val pm = context.getSystemService(android.content.Context.PRINT_SERVICE) as? android.print.PrintManager ?: return
            pm.print(title, view.createPrintDocumentAdapter(title), null)
        }
    }
    web.loadDataWithBaseURL(null, "<html><body style='font-family:sans-serif'><h2>${esc(title)}</h2><p>${esc(text)}</p><p style='font:32px monospace'>${esc(code)}</p></body></html>", "text/html", "utf-8", null)
}
