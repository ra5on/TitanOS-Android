package io.github.ra5on.titanos.settings.pages.system

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.longOrNull
import io.github.ra5on.titanos.settings.common.num
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.without
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.NumberField
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.i18n.tr
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private const val MANUAL = -1
private val PRESETS = listOf(360, 720, 0, 10080)
private const val MIN_EVERY = 60
private const val MAX_EVERY = 30 * 24 * 60

/** Die Einstellungen mit fehlendem Abstand als Standard (normal im Web), damit der Entwurf gleich vergleicht. */
private fun normal(settings: JsonObject): JsonObject {
    val every = settings.long("checkEvery", 0)
    return settings.with("checkEvery" to if (every == 24L * 60) 0 else every, "kernel" to if (settings.str("kernel") == "new") "new" else "")
}

private fun versionNumbers(v: String): List<Int> = v.split('-')[0].split('.').map { it.toIntOrNull() ?: 0 }

private fun olderThan(a: String, b: String): Boolean {
    val x = versionNumbers(a.removePrefix("v"))
    val y = versionNumbers(b.removePrefix("v"))
    for (i in 0 until 3) if (x.getOrElse(i) { 0 } != y.getOrElse(i) { 0 }) return x.getOrElse(i) { 0 } < y.getOrElse(i) { 0 }
    return a.contains('-') && !b.contains('-')
}

private fun beforeEncryption(target: String): Boolean {
    val p = target.removePrefix("v").split('.')
    val major = p.getOrNull(0)?.toIntOrNull() ?: return false
    val minor = p.getOrNull(1)?.toIntOrNull() ?: return false
    return major == 0 && minor < 31
}

private fun mbps(tr: Tr, bytesPerSecond: Double): String {
    val v = bytesPerSecond / 1_000_000
    return Fmt.number(tr, v, if (v >= 100) 0 else if (v >= 10) 1 else 2)
}

/**
 * Updates (routes/settings/updates, docs/rules/UPDATES.md): eine neue Version
 * kommt in den anderen Steckplatz und startet auf Probe; die vorige bleibt als
 * Rückfall. Prüfen, Installieren (System oder nur App), Version wählen,
 * Rückwechsel, „Doch nicht“, Steckplätze, Neuigkeiten, Automatik mit Uhrzeit
 * und Häufigkeit, Kernel-Wahl und die Knoten eines Clusters.
 */
@Composable
fun UpdatesPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = tr()
    val context = LocalContext.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<JsonObject?>(null) }
    var draft by remember { mutableStateOf<JsonObject?>(null) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf<List<JsonObject>?>(null) }
    var versionsFailed by remember { mutableStateOf(false) }
    var floor by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf("") }
    var restartFrom by remember { mutableStateOf("") }
    var restartedTo by remember { mutableStateOf("") }
    var flow by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var round by remember { mutableIntStateOf(0) }
    // Häufigkeit der Prüfung: Vorgabe, benutzerdefiniert (Stunden/Tage) oder nur von Hand.
    var mode by remember { mutableStateOf("0") }
    var amount by remember { mutableStateOf(1L) }
    var unit by remember { mutableStateOf("hours") }

    fun showEvery(every: Long) {
        when {
            every == MANUAL.toLong() -> mode = "manual"
            every.toInt() in PRESETS -> mode = every.toString()
            else -> {
                mode = "custom"
                unit = if (every % (24 * 60) == 0L) "days" else "hours"
                amount = every / if (unit == "days") 24 * 60 else 60
            }
        }
    }
    fun take(res: JsonObject) {
        val s = res.with("settings" to normal(res.o("settings")))
        status = s
        if (draft == null) {
            draft = s.o("settings")
            showEvery(draft.long("checkEvery"))
        }
        val progress = s.o("progress")
        val phase = progress.str("phase")
        if (phase == "restart" && restartFrom.isEmpty()) restartFrom = s.str("version")
        if (phase == "download") flow = if (progress.str("file") == "titand") "app" else "system"
        else if (phase == "switch") flow = "system"
        if ((phase == "download" || phase == "switch") && s["available"] is JsonObject) target = s.o("available").str("version")
    }
    fun problem(e: Throwable) {
        val code = (e as? ApiException)?.code ?: "internal"
        val detail = if (code in listOf("update_failed", "update_too_old", "update_no_space") && e is ApiException) e.message?.substringAfter(": ", "").orEmpty() else ""
        error = tr.error(code) to detail
    }
    suspend fun load() {
        val mine = round
        try {
            val next = env.api.obj("update")
            if (mine != round) return
            if (restartFrom.isNotEmpty() && next.o("progress").str("phase") != "restart") {
                restartedTo = next.str("version")
                restartFrom = ""
            }
            take(next)
        } catch (e: Exception) {
            // Während titand oder das NAS neu startet, ist die Verbindung weg: ruhig weiter fragen.
            if (status == null) problem(e)
        }
    }
    suspend fun loadVersions() {
        versionsFailed = false
        try {
            val res = env.api.obj("update/versions")
            val list = res.a("versions").objects()
            versions = list
            floor = res.str("floor")
            if (list.none { it.str("version") == picked }) picked = (list.firstOrNull { !it.bool("running") } ?: list.firstOrNull())?.str("version").orEmpty()
        } catch (_: Exception) {
            versionsFailed = true
        }
    }

    val st = status
    val phase = st?.o("progress")?.str("phase").orEmpty()
    val working = phase == "download" || phase == "switch" || phase == "restart" || restartFrom.isNotEmpty()
    LaunchedEffect(Unit) { launch { loadVersions() }; load() }
    LaunchedEffect(working) {
        while (true) {
            delay(if (working) 1500 else 15_000)
            load()
        }
    }
    LaunchedEffect(working, busy) { if (!working && busy.isEmpty()) flow = "" }

    // Eine Änderung zählt Antworten älterer Abfragen nicht mehr (round wie im Web).
    suspend fun <T> change(block: suspend () -> T): T {
        round++
        try { return block() } finally { round++ }
    }
    fun act(action: String) {
        val go = {
            val s = status
            val appUpdate = s?.str("kind") == "app" && s.o("available").has("app")
            if (action != "check") {
                flow = if (action == "install") (if (appUpdate) "app" else "system") else ""
                if (action == "install") target = s?.o("available")?.str("version").orEmpty()
            }
            busy = if (action == "check") "check" else "action"
            error = null
            checked = false
            restartedTo = ""
            scope.launch {
                try {
                    (change { env.api.post("update/$action") } as? JsonObject)?.let(::take)
                    checked = action == "check"
                } catch (e: Exception) {
                    problem(e); load()
                }
                busy = ""
            }
        }
        if (action == "check") go() else Guard.require(context, GuardAction.Updates) { go() }
    }
    fun installVersion() = Guard.require(context, GuardAction.Updates) {
        flow = "system"
        target = picked
        busy = "action"
        error = null
        restartedTo = ""
        scope.launch {
            try {
                (change { env.api.post("update/install-version", jobj("version" to picked)) } as? JsonObject)?.let(::take)
                // Die Einstellungen können sich geändert haben: automatische Installation aus.
                draft = status?.o("settings")
            } catch (e: Exception) {
                problem(e); load()
            }
            busy = ""
        }
    }

    error?.let { (text, detail) -> Banner(if (detail.isNotEmpty()) "$text\n$detail" else text, tone = Tone.Error) }
    val d = draft
    if (st == null || d == null) {
        if (error == null) LoadingLine(t("common.loading"))
        return
    }

    val settings = st.o("settings")
    val available = st["available"] as? JsonObject
    val pending = st["pending"] as? JsonObject
    val app = st.o("app")
    val canVerify = st.bool("canVerify")
    val unconfirmed = st.bool("unconfirmed")
    val progress = st.o("progress")
    val fallbackRemoved = progress.bool("fallbackRemoved")
    val appUpdate = st.str("kind") == "app" && available?.has("app") == true
    val chosen = if (settings.str("kernel") == "new" && available?.o("variants")?.get("new") is JsonObject) available.o("variants").o("new") else available
    val size = if (appUpdate) available.o("app").long("size") else chosen?.a("files")?.objects()?.sumOf { it.long("size") } ?: 0
    val slots = st.a("slots").objects()
    val running = slots.firstOrNull { it.bool("running") }
    val other = slots.firstOrNull { !it.bool("running") }
    val loaderKey = when (st.str("bootLoader")) { "grub-i386-pc" -> "update.boot.bios"; "grub-i386-efi" -> "update.boot.ia32"; else -> "" }
    val appTarget = app.str("previous").ifEmpty { t("update.app.base", "version" to st.str("base")) }
    val canCheck = canVerify && !working
    val news = remember(available?.str("changelog")) { parseChangelog(available?.str("changelog").orEmpty()) }
    val ranText = versions?.firstOrNull { it.bool("running") }?.str("changelog").orEmpty()
    val ran = remember(ranText) { parseChangelog(ranText) }

    // Die Schritte einer Installation nach den Phasen von titand (update_install.go, app.go).
    val stageKind: String
    val stageAt: Int
    val hasStage: Boolean
    when {
        working -> { hasStage = true; stageKind = flow; stageAt = if (flow.isEmpty()) 0 else if (phase == "download") 1 else 3 }
        pending?.str("kind") == "update" -> { hasStage = true; stageKind = "system"; stageAt = 4 }
        app.bool("trial") -> { hasStage = true; stageKind = "app"; stageAt = 4 }
        unconfirmed && pending == null -> { hasStage = true; stageKind = "system"; stageAt = 5 }
        else -> { hasStage = false; stageKind = ""; stageAt = 0 }
    }
    val fallbackVersion = if (stageKind != "system" || fallbackRemoved) "" else if (stageAt == 5) (if (other?.bool("complete") == true) other.str("version") else "") else st.str("version")

    // Was geschah oder im Weg ist, über allem anderen.
    if (restartedTo.isNotEmpty()) Banner(t("update.app.restarted", "version" to restartedTo), tone = Tone.Ok, action = t("update.app.reloadUi"), onAction = {
        restartedTo = ""
        scope.launch { load(); loadVersions() }
    })
    if (!canVerify) Banner(t("update.noKey"), tone = Tone.Warn)
    if (pending?.str("kind") == "rollback") {
        Banner(t("update.pendingRollback", "version" to pending.str("version")), tone = Tone.Ok)
        Buttons {
            SmallButton(t("update.restartNow"), { ov.showRestart(env) }, kind = ButtonKind.Primary)
            SmallButton(t("update.cancel"), { act("cancel") }, enabled = busy.isEmpty())
        }
    }
    val failedVersion = st.str("failedVersion")
    if (failedVersion.isNotEmpty() && available != null && available.str("version").removePrefix("v") == failedVersion) Banner(t("update.failedBefore", "version" to available.str("version")), tone = Tone.Warn)
    if (canVerify) {
        if (working) {
            if (fallbackRemoved) Banner(t("update.fallbackRemoved"), tone = Tone.Warn)
        } else if (phase == "failed") {
            Banner(listOfNotNull(t("update.failed"), progress.str("error").takeIf { it.isNotEmpty() }, if (fallbackRemoved) t("update.fallbackRemovedFailed") else null).joinToString("\n"), tone = Tone.Error)
        } else if (phase == "done" && fallbackRemoved) Banner(t("update.fallbackRemoved"), tone = Tone.Warn)
        if (!working && available == null && st.str("kernelMissing").isNotEmpty()) Banner(t("update.kernel.missing", "version" to st.str("kernelMissing")), tone = Tone.Warn)
        if (!working && st.str("checkError").isNotEmpty()) Banner(t("update.checkFailed") + "\n" + st.str("checkError"), tone = Tone.Warn)
    }

    // Das Update selbst zuerst: die Karte der neuen Version, was neu ist, und wo eine Installation steht.
    if ((available != null && !working) || (available != null && news.has) || hasStage) {
        SettingGroup {
            if (available != null && !working) {
                Hero(
                    st = st, available = available, kernelName = chosen?.str("kernel")?.ifEmpty { null } ?: available.str("kernel"),
                    appUpdate = appUpdate, size = size,
                    buttonText = if (pending != null) t("update.replacePending", "version" to available.str("version"), "waiting" to pending.str("version"))
                    else t(if (st.bool("kernelSwitch")) "update.kernel.switch" else "update.install"),
                    enabled = busy.isEmpty() && !unconfirmed && !app.bool("trial"),
                    onInstall = {
                        val kernelSwitch = st.bool("kernelSwitch")
                        ov.confirm(
                            title = if (kernelSwitch) tr.t("update.kernel.switchTitle") else tr.t("update.confirmTitle", "version" to available.str("version")),
                            text = tr.t(if (appUpdate) "update.app.confirmText" else "update.confirmText", "size" to Fmt.bytes(tr, size)),
                            action = tr.t(if (kernelSwitch) "update.kernel.switch" else "update.install"),
                            cancel = tr.t("common.cancel"),
                            danger = false,
                            body = {
                                if (kernelSwitch) Text(tr.t("update.kernel.confirm", "kernel" to chosen?.str("kernel").orEmpty()), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(bottom = 10.dp))
                                Muted(tr.t(if (appUpdate) "update.app.confirmSafe" else "update.confirmSafe"), Modifier.padding(bottom = 12.dp))
                            },
                        ) { act("install") }
                    },
                )
            }
            if (available != null && news.has) {
                SettingRow(t("update.news.title", "version" to Fmt.osVersion(available.str("version"))), desc = countText(news).ifEmpty { null }, icon = WebIcons["sparkle"], id = "news") {
                    Block { Changelog(news, available.str("version")) }
                }
            }
            if (hasStage) {
                val stepsTitle = when {
                    working -> if (stageKind.isEmpty()) t("update.steps.restarting") else if (target.isNotEmpty()) t("update.steps.title", "version" to Fmt.osVersion(target)) else t("update.steps.titlePlain")
                    pending != null -> t("update.steps.waiting", "version" to Fmt.osVersion(pending.str("version")))
                    else -> t("update.steps.trial", "version" to Fmt.osVersion(st.str("version")))
                }
                SettingRow(
                    stepsTitle,
                    desc = if (!working || stageKind.isEmpty()) null else t(if (stageKind == "app") "update.steps.appRuns" else "update.steps.reachable"),
                    icon = WebIcons["clock"],
                    id = "steps",
                    startOpen = true,
                    control = if (phase == "download") ({ Mono("${Fmt.percent(tr, progress.num("percent"))} · ${mbps(tr, progress.num("speedBytesPerSecond"))} MB/s") }) else null,
                ) {
                    Block {
                        if (stageKind.isNotEmpty()) {
                            val names = if (stageKind == "app") listOf("load", "check", "service", "appTrial") else listOf("load", "check", "switch", "restart", "trialStart")
                            Steps(names.mapIndexed { i, n -> t("update.steps.$n") to (if (i + 1 < stageAt) "done" else if (i + 1 == stageAt) "now" else "next") })
                        }
                        if (working) {
                            Progress((progress.num("percent") / 100).toFloat(), t("update.downloading"))
                            Muted(
                                when {
                                    phase == "download" -> t("update.downloading")
                                    phase == "restart" || restartFrom.isNotEmpty() -> t("update.app.restarting")
                                    else -> t("update.switching")
                                },
                            )
                        } else if (pending != null) {
                            Banner(t("update.pendingUpdate", "version" to pending.str("version")), tone = Tone.Ok)
                            Buttons {
                                SmallButton(t("update.restartNow"), { ov.showRestart(env) }, kind = ButtonKind.Primary)
                                SmallButton(t("update.cancel"), { act("cancel") }, enabled = busy.isEmpty())
                            }
                        } else if (unconfirmed) Banner(t("update.unconfirmed"), tone = Tone.Warn)
                        if (app.bool("trial")) Banner(t("update.app.trial"), tone = Tone.Warn)
                        if (stageKind == "system" && fallbackVersion.isNotEmpty()) Muted(t("update.steps.fallback", "version" to Fmt.osVersion(fallbackVersion)))
                        else if (stageKind == "app" && working) Muted(t("update.steps.appFallback"))
                    }
                }
            }
        }
    }

    SettingGroup(t("update.nas")) {
        if (canVerify && !working && available == null) {
            val current = st.str("kernelMissing").isEmpty() && st.str("checkError").isEmpty() && (checked || st.has("lastCheck"))
            SettingRow(
                if (current) t("update.upToDate") else t("update.check"),
                desc = st.str("lastCheck").takeIf { it.isNotEmpty() }?.let { t("update.lastCheck", "time" to Fmt.dateTime(tr, it)) },
                icon = WebIcons[if (current) "check" else "search"],
                control = {
                    SmallButton(t(if (busy == "check") "update.checking" else "update.check"), { act("check") }, kind = ButtonKind.Primary, icon = WebIcons["search"], enabled = canCheck && busy.isEmpty())
                },
            )
        }
        val installedDesc = buildString {
            append(t(if (st.str("variant") == "new") "update.kernel.runningNew" else "update.kernel.runningStable", "kernel" to running?.str("kernel").orEmpty().ifEmpty { "–" }))
            if (loaderKey.isNotEmpty()) append(" · " + t(loaderKey))
            if (st.str("base").isNotEmpty() && st.str("base") != st.str("version")) append("\n" + t("update.app.onBase", "version" to Fmt.osVersion(st.str("base"))))
        }
        val title = "TitanOS ${Fmt.osVersion(st.str("version"))}"
        val badge = if (st.bool("release")) null else t("update.devBuild")
        if (ran.has) {
            SettingRow(title, desc = installedDesc, icon = WebIcons["box"], badge = badge, summary = t("update.news.was"), id = "installed") {
                Block { Changelog(ran, st.str("version")) }
            }
        } else {
            SettingRow(title, desc = installedDesc, icon = WebIcons["box"], badge = badge)
        }
        if (st.bool("canRollback") && pending == null && other != null) {
            val v = other.str("version").ifEmpty { "?" }
            SettingRow(t("update.back.title", "version" to v), desc = t("update.back.desc"), icon = WebIcons["undo"], control = {
                SmallButton(t("update.back.do"), { ov.show { close -> RollbackDialog(env, v, close) { act("rollback") } } }, enabled = busy.isEmpty() && !working)
            })
        }
        if (app.str("current").isNotEmpty() || app.str("previous").isNotEmpty()) {
            val base = t("update.app.base", "version" to Fmt.osVersion(st.str("base")))
            SettingRow(t("update.app.title"), desc = t("update.app.text", "version" to Fmt.osVersion(st.str("base"))), icon = WebIcons["sparkle"], summary = app.str("current").ifEmpty { base }, id = "app") {
                SettingSub(t("update.app.running"), control = { Mono(app.str("current").ifEmpty { base }) })
                if (app.str("previous").isNotEmpty()) SettingSub(t("update.app.previous"), control = { Mono(app.str("previous")) })
            }
            if (app.bool("canRollback") && pending == null) {
                SettingRow(t("update.app.backTitle", "target" to appTarget), desc = t("update.app.backDesc"), icon = WebIcons["undo"], control = {
                    SmallButton(t("update.back.do"), {
                        ov.confirm(tr.t("update.app.rollbackTitle", "target" to appTarget), tr.t("update.app.rollbackText"), tr.t("update.rollbackDo"), tr.t("common.cancel"), danger = false, body = {
                            Banner(tr.t("update.rollbackWarn"), tone = Tone.Warn, modifier = Modifier.padding(bottom = 10.dp))
                        }) { act("app-rollback") }
                    }, enabled = busy.isEmpty() && !working)
                })
            }
        }
        if (slots.isNotEmpty()) {
            fun shown(s: JsonObject) = if (s.bool("complete") || s.bool("running")) s.str("version").ifEmpty { "–" } else "–"
            SettingRow(t("update.slots"), desc = t("update.slotsText"), icon = WebIcons["disk"], summary = slots.joinToString(" · ") { "${it.str("name").uppercase()}: ${shown(it)}" }, id = "slots") {
                for (slot in slots) {
                    val stateText = when {
                        slot.bool("running") -> t(if (slot.bool("next")) "update.slot.running" else "update.slot.runningNotNext")
                        !slot.bool("complete") -> t("update.slot.empty")
                        slot.longOrNull("triesLeft") == 0L -> t("update.slot.bad")
                        else -> t(if (slot.bool("next")) "update.slot.next" else "update.slot.fallback")
                    }
                    val tries = slot.longOrNull("triesLeft")?.takeIf { it > 0 }?.let { " · " + t("update.slot.tries", "count" to it) }.orEmpty()
                    SettingSub(
                        t("update.slot", "name" to slot.str("name").uppercase()),
                        desc = slot.str("kernel").takeIf { it.isNotEmpty() }?.let { t(if (slot.str("variant") == "new") "update.kernel.slotNew" else "update.kernel.slotStable", "kernel" to it) },
                        control = {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Mono(shown(slot))
                                Value(stateText + tries)
                            }
                        },
                    )
                }
            }
        }
        if (canVerify) {
            SettingRow(t("update.choose.title"), desc = t("update.choose.text", "floor" to floor.ifEmpty { "0.46.2" }), icon = WebIcons["list"], id = "choose") {
                val list = versions
                when {
                    versionsFailed -> Block { Muted(t("update.choose.failed")) }
                    list == null -> Block { Muted(t("update.choose.loading")) }
                    list.isEmpty() -> Block { Muted(t("update.choose.none", "floor" to floor)) }
                    else -> {
                        SettingSub(t("update.choose.version"), control = {
                            Select(picked, list.map { v ->
                                v.str("version") to buildString {
                                    append("TitanOS ${Fmt.osVersion(v.str("version"))} · ${Fmt.date(tr, Fmt.instant(v.str("released")))}")
                                    if (v.bool("running")) append(" · " + t("update.choose.running")) else if (v.bool("newest")) append(" · " + t("update.choose.newest"))
                                }
                            }, { picked = it }, label = t("update.choose.version"))
                        })
                        list.firstOrNull { it.str("version") == picked }?.let { pick ->
                            Block {
                                Muted(t("update.choose.meta", "date" to Fmt.date(tr, Fmt.instant(pick.str("released"))), "kernel" to pick.str("kernel"), "size" to Fmt.bytes(tr, pick.long("size"))))
                                leadOf(pick.str("changelog")).takeIf { it.isNotEmpty() }?.let { Value(it) }
                                SmallButton(
                                    t("update.choose.install", "version" to pick.str("version")),
                                    {
                                        val older = olderThan(pick.str("version"), st.str("version"))
                                        val pickOlder = older || !pick.bool("newest")
                                        ov.confirm(tr.t("update.choose.confirmTitle", "version" to pick.str("version")), tr.t("update.confirmText", "size" to Fmt.bytes(tr, pick.long("size"))), tr.t("update.choose.do"), tr.t("common.cancel"), danger = false, body = {
                                            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (older) Banner(tr.t("update.rollbackWarn"), tone = Tone.Warn)
                                                if (pickOlder && settings.bool("autoInstall")) Banner(tr.t("update.choose.autoOff"), tone = Tone.Warn)
                                                Muted(tr.t("update.confirmSafe"))
                                            }
                                        }) { installVersion() }
                                    },
                                    enabled = !pick.bool("running") && busy.isEmpty() && !working && pending == null && !unconfirmed && !app.bool("trial"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Automatik: ein Formular mit einem Speichern.
    val manual = mode == "manual"
    val chosenEvery: Long = when (mode) {
        "manual" -> MANUAL.toLong()
        "custom" -> amount * if (unit == "days") 24 * 60 else 60
        else -> mode.toLong()
    }
    val customInvalid = mode == "custom" && (chosenEvery < MIN_EVERY || chosenEvery > MAX_EVERY)
    fun chooseEvery(m: String = mode, am: Long = amount, un: String = unit) {
        val every = when (m) { "manual" -> MANUAL.toLong(); "custom" -> am * if (un == "days") 24 * 60 else 60; else -> m.toLong() }
        val invalid = m == "custom" && (every < MIN_EVERY || every > MAX_EVERY)
        var next = draft ?: return
        if (!invalid) next = next.with("checkEvery" to if (every == 24L * 60) 0 else every)
        if (m == "manual") next = next.with("autoInstall" to false)
        draft = next
    }
    val changed = d != settings
    val nextCheck = st.str("nextCheck").takeIf { it.isNotEmpty() && d.long("checkEvery") == settings.long("checkEvery") }?.let { Fmt.instant(it) }
    SettingGroup(t("update.auto.group"), desc = t("update.singleChannel")) {
        val checkDesc = buildList {
            st.str("lastCheck").takeIf { it.isNotEmpty() }?.let { add(t("update.lastCheck", "time" to Fmt.dateTime(tr, it))) }
            if (nextCheck != null && !manual) add(if (nextCheck.toEpochMilli() <= System.currentTimeMillis()) t("update.nextCheckSoon") else t("update.nextCheck", "time" to Fmt.dateTime(tr, nextCheck)))
            add(t(if (manual) "update.interval.manualHint" else "update.interval.hint"))
        }.joinToString("\n")
        SettingRow(t("update.check"), desc = checkDesc, icon = WebIcons["search"], control = {
            Select(mode, PRESETS.map { it.toString() to t("update.interval.$it") } + listOf("custom" to t("update.interval.custom"), "manual" to t("update.interval.manual")), { m ->
                var am = amount
                var un = unit
                if (m == "custom") {
                    val every = d.long("checkEvery").let { if (it == 0L) 24L * 60 else it }
                    val days = every > 0 && every % (24 * 60) == 0L
                    un = if (days || every < 0) "days" else "hours"
                    am = if (every < 0) 1 else every / if (days) 24 * 60 else 60
                }
                mode = m; amount = am; unit = un
                chooseEvery(m, am, un)
            }, label = t("update.interval"))
            if (mode == "custom") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Muted(t("update.interval.every"))
                    NumberField(amount, { v -> if (v != null) { amount = v; chooseEvery(am = v) } }, t("update.interval.every"))
                }
                Select(unit, listOf("hours" to t("update.interval.hours"), "days" to t("update.interval.days")), { unit = it; chooseEvery(un = it) }, label = t("update.interval.unit"))
            }
            SmallButton(t(if (busy == "check") "update.checking" else "update.checkNow"), { act("check") }, enabled = canCheck && busy.isEmpty())
        })
        if (customInvalid) Banner(t("update.interval.range"), tone = Tone.Error)
        val auto = d.bool("autoInstall")
        SettingRow(
            t("update.auto.title"),
            desc = t(if (manual) "update.autoNeedsChecks" else if (auto) "update.autoHint" else "update.manualHint"),
            icon = WebIcons["clock"],
            control = {
                if (auto && !manual) {
                    val time = d.str("installTime").ifEmpty { "03:30" }
                    SmallButton(time, {
                        val (h, m) = time.split(':').map { it.toIntOrNull() ?: 0 }.let { it.getOrElse(0) { 3 } to it.getOrElse(1) { 30 } }
                        TimePickerDialog(context, { _, hh, mm -> draft = draft?.with("installTime" to "%02d:%02d".format(hh, mm)) }, h, m, true).show()
                    }, icon = WebIcons["clock"], modifier = Modifier.semantics { contentDescription = tr.t("update.autoTime") + " " + time })
                }
                Toggle(auto, { draft = d.with("autoInstall" to it) }, t("look.on"), t("look.off"), enabled = !manual, label = t("update.auto"))
            },
        )
        SettingRow(
            t("update.kernel.title"),
            desc = "${t("update.kernel.stable")}: ${t("update.kernel.stableText")}\n${t("update.kernel.new")}: ${t("update.kernel.newText")}\n${t("update.kernel.hint")}",
            icon = WebIcons["chip"],
            control = {
                Select(d.str("kernel"), listOf("" to t("update.kernel.stable"), "new" to t("update.kernel.new")), { draft = d.with("kernel" to it) }, label = t("update.kernel.title"))
            },
        )
        Buttons {
            SmallButton(t(if (busy == "save") "ui.saving" else "notify.save"), {
                Guard.require(context, GuardAction.Settings) {
                    busy = "save"
                    saved = false
                    error = null
                    scope.launch {
                        try {
                            // Stabil ist der Standard und bleibt weg, wie vor der Wahl.
                            val body = if (d.str("kernel").isEmpty()) d.without("kernel") else d
                            (change { env.api.put("update/settings", body) } as? JsonObject)?.let(::take)
                            draft = status?.o("settings")
                            saved = true
                            showEvery(draft.long("checkEvery"))
                        } catch (e: Exception) {
                            problem(e)
                        }
                        busy = ""
                    }
                }
            }, kind = ButtonKind.Primary, enabled = changed && !customInvalid && busy.isEmpty())
            if (saved && !changed) Muted(t("ui.saved"))
        }
    }

    ClusterUpdates()
}

/** Die Karte der neuen Version (`.hero`): Symbol, Titel, Datum, Sprung, Art, Größe, Kernel, Knopf. */
@Composable
private fun Hero(st: JsonObject, available: JsonObject, kernelName: String, appUpdate: Boolean, size: Long, buttonText: String, enabled: Boolean, onInstall: () -> Unit) {
    val c = Titan.colors
    val tr = tr()
    val shape = RoundedCornerShape(12.dp)
    val kernelSwitch = st.bool("kernelSwitch")
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.accent.copy(alpha = .09f).compositeOver(c.surface)).border(1.dp, c.accent.copy(alpha = .45f), shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(c.accent.copy(alpha = .22f).compositeOver(c.surface)), contentAlignment = Alignment.Center) {
                Icon(WebIcons["download"], null, tint = c.accent, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (kernelSwitch) "TitanOS ${Fmt.osVersion(available.str("version"))} · " + t(if (st.o("settings").str("kernel") == "new") "update.kernel.withNew" else "update.kernel.withStable")
                    else t("update.hero.title", "version" to Fmt.osVersion(available.str("version"))),
                    style = TitanType.rowTitle.copy(fontSize = TitanType.title.fontSize * .85f, lineHeight = TitanType.title.lineHeight * .9f),
                    color = c.text,
                    modifier = Modifier.semantics { heading() },
                )
                Muted(t("update.hero.released", "date" to Fmt.date(tr, Fmt.instant(available.str("released")))))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (!kernelSwitch) {
                val from = Fmt.osVersion(st.str("version"))
                val to = Fmt.osVersion(available.str("version"))
                Row(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = tr.t("update.hero.jump", "from" to from, "to" to to) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(from, style = TitanType.rowSub.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Icon(WebIcons["arrowRight"], null, tint = c.muted, modifier = Modifier.size(15.dp))
                    Text(to, style = TitanType.rowSub.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                }
            }
            ToneChip(t(if (kernelSwitch) "update.kind.kernel" else if (appUpdate) "update.kind.app" else "update.kind.system"), if (appUpdate) Tone.Ok else Tone.Info)
            ToneChip(Fmt.bytes(tr, size), Tone.Inactive)
            if (kernelName.isNotEmpty()) ToneChip(t("update.hero.kernel", "kernel" to kernelName), Tone.Inactive)
            if (available.str("channel") == "beta") ToneChip(t("update.channel.beta"), Tone.Warn)
        }
        SmallButton(buttonText, onInstall, kind = ButtonKind.Primary, enabled = enabled)
    }
}

/** Die Schritte einer Installation: erledigt (Haken), jetzt (Nummer, betont), danach. */
@Composable
private fun Steps(steps: List<Pair<String, String>>) {
    val c = Titan.colors
    Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, (label, state) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val done = state == "done"
                val now = state == "now"
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(if (done || now) c.accent else c.chip).border(1.dp, if (done || now) c.accent else c.border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(TitanIcons.check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                    else Text("${i + 1}", style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = if (now) c.onAccent else c.muted)
                }
                Text(
                    "$label (${t("update.steps.$state")})",
                    style = TitanType.rowSub.copy(fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (state == "next") c.muted else c.text,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Rückwechsel auf die Version im anderen Steckplatz, mit Warnung zu verschlüsselten Volumes vor 0.31. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.RollbackDialog(env: io.github.ra5on.titanos.settings.common.SettingsEnv, version: String, close: () -> Unit, onConfirm: () -> Unit) {
    val tr = tr()
    var encrypted by remember { mutableStateOf<List<String>>(emptyList()) }
    var unknown by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            encrypted = env.api.obj("storage").a("volumes").objects().filter { it.has("encryption") && it["encryption"].toString() != "false" }.map { it.str("name") }
        } catch (e: Exception) {
            unknown = tr.errorOf(e)
        }
    }
    ConfirmDialog(
        title = t("update.rollbackTitle", "version" to version),
        text = t("update.rollbackText"),
        actions = listOf(
            DialogAction(t("update.rollbackDo")) { close(); onConfirm() },
            DialogAction(t("common.cancel")) { close() },
        ),
        onDismiss = close,
        body = {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Banner(t("update.rollbackWarn"), tone = Tone.Warn)
                if (encrypted.isNotEmpty() && beforeEncryption(version)) Banner(t("update.rollbackEncrypted", "version" to version, "volumes" to encrypted.joinToString(", ")), tone = Tone.Error)
                else if (unknown.isNotEmpty() && beforeEncryption(version)) Banner(t("update.rollbackEncryptedUnknown", "version" to version, "reason" to unknown), tone = Tone.Warn)
            }
        },
    )
}

/** Die Knoten des Clusters (ClusterUpdates.svelte): Version, neuere Version, Stand von „Alle Knoten aktualisieren“. */
@Composable
private fun ClusterUpdates() {
    val env = LocalEnv.current
    val ov = LocalOverlays.current
    var cluster by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var available by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var run by remember { mutableStateOf<JsonObject?>(null) }
    suspend fun load() {
        try {
            val res = env.api.obj("cluster/nodes")
            nodes = if (res.bool("manages")) res.a("nodes").objects() else emptyList()
            if (nodes.isNotEmpty() && cluster.isEmpty()) cluster = env.api.obj("cluster").o("cluster").str("name")
            run = env.api.obj("cluster/update")["run"] as? JsonObject
        } catch (_: Exception) {
            nodes = emptyList()
        }
    }
    LaunchedEffect(Unit) {
        load()
        for (node in nodes) {
            if (!node.bool("online")) continue
            runCatching { nodeUpdate(env, node) }.getOrNull()?.let { available = available + (node.str("id") to it.second) }
        }
        while (true) {
            delay(4000)
            if (run?.bool("running") == true) load()
        }
    }
    if (nodes.size <= 1) return
    SettingGroup(t("cluster.updatesOf", "name" to cluster), tools = {
        SmallButton(t("cluster.updateAll"), { ov.show { close -> UpdateAllDialog(nodes, close) { run = it } } }, kind = ButtonKind.Primary, icon = WebIcons["download"], enabled = run?.bool("running") != true)
    }) {
        for (node in nodes) {
            val r = run?.a("nodes")?.objects()?.firstOrNull { it.str("id") == node.str("id") }
            val next = r?.str("to")?.ifEmpty { null } ?: available[node.str("id")].orEmpty()
            SettingRow(
                node.str("name"),
                icon = WebIcons["tailnet"],
                badge = if (node.bool("self")) t("cluster.thisNode") else null,
                desc = "${t("cluster.updateNow")}: ${node.str("version").ifEmpty { "–" }}" + if (next.isNotEmpty()) " · ${t("cluster.updateNew")}: $next" else "",
                control = {
                    when {
                        r != null -> Text(
                            t("cluster.run.${r.str("state")}") + r.str("error").let { if (it.isNotEmpty()) ": $it" else "" },
                            style = TitanType.rowSub, color = if (r.str("state") == "failed") Titan.colors.errorText else Titan.colors.text,
                        )
                        !node.bool("online") -> ToneChip(t("cluster.offline"), Tone.Error)
                        available[node.str("id")].orEmpty().isNotEmpty() -> ToneChip(t("cluster.updateAvailable"), Tone.Info)
                        else -> ToneChip(t("cluster.updateCurrent"), Tone.Ok)
                    }
                },
            )
        }
        if (run?.bool("running") == true) Muted(t(if (run.bool("parallel")) "cluster.runningParallel" else "cluster.runningSerial"))
    }
}

/** Version und neuere Version eines Knotens (nodeUpdate im Web): (version, available, kind). */
private suspend fun nodeUpdate(env: io.github.ra5on.titanos.settings.common.SettingsEnv, node: JsonObject): Triple<String, String, String> {
    val st = env.api.obj(if (node.bool("self")) "update" else "cluster/nodes/" + java.net.URLEncoder.encode(node.str("id"), "UTF-8").replace("+", "%20") + "/api/update")
    val v = st.str("version")
    val av = (st["available"] as? JsonObject)?.str("version")?.takeIf { it != v }.orEmpty()
    return Triple(v, av, st.str("kind"))
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.UpdateAllDialog(nodes: List<JsonObject>, close: () -> Unit, onStarted: (JsonObject) -> Unit) {
    val env = LocalEnv.current
    val tr = tr()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(nodes.associate { it.str("id") to Triple("", "", "loading") }) }
    var chosen by remember { mutableStateOf<List<String>>(emptyList()) }
    var parallel by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        for (n in nodes) launch {
            val id = n.str("id")
            try {
                val (_, av, kind) = nodeUpdate(env, n)
                rows = rows + (id to Triple(av, kind, "ok"))
                if (av.isNotEmpty() && id !in chosen) chosen = chosen + id
            } catch (_: Exception) {
                rows = rows + (id to Triple("", "", "failed"))
            }
        }
    }
    ConfirmDialog(
        title = t("cluster.updateAllTitle"),
        text = null,
        actions = listOf(
            DialogAction(t("cluster.updateStart", "count" to chosen.size)) {
                if (busy || chosen.isEmpty()) return@DialogAction
                Guard.require(context, GuardAction.Updates) {
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            val answer = env.api.post("cluster/update", jobj("nodes" to chosen, "parallel" to parallel)) as? JsonObject
                            answer?.get("run")?.let { it as? JsonObject }?.let(onStarted)
                            close()
                        } catch (e: Exception) {
                            error = tr.errorOf(e)
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            DialogAction(t("common.cancel")) { if (!busy) close() },
        ),
        onDismiss = { if (!busy) close() },
        body = {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in nodes) {
                    val id = n.str("id")
                    val (av, kind, state) = rows[id] ?: Triple("", "", "loading")
                    val info = when {
                        !n.bool("online") -> t("cluster.offline")
                        state == "loading" -> t("common.loading")
                        state == "failed" -> t("cluster.updateUnknown")
                        av.isNotEmpty() -> t(if (kind == "app") "cluster.updateKindApp" else "cluster.updateKindSystem")
                        else -> t("cluster.updateCurrent")
                    }
                    CheckRow(
                        id in chosen,
                        { on -> chosen = if (on) chosen + id else chosen - id },
                        n.str("name") + if (n.bool("self")) " · " + t("cluster.thisNode") else "",
                        desc = "${t("cluster.updateNow")}: ${n.str("version").ifEmpty { "–" }} · ${t("cluster.updateNew")}: ${if (state == "loading") "–" else av.ifEmpty { "–" }} · $info",
                        enabled = n.bool("online"),
                    )
                }
                Text(t("cluster.updateOrder"), style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.text)
                RadioList(parallel, listOf(
                    Triple(false, t("cluster.updateSerial"), t("cluster.updateSerialHint")),
                    Triple(true, t("cluster.updateParallel"), t("cluster.updateParallelHint")),
                ), { parallel = it })
                Muted(t("cluster.updateAllHint"))
                if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
            }
        },
    )
}
