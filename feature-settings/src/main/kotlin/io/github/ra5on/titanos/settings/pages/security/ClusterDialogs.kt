package io.github.ra5on.titanos.settings.pages.security

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.breakIds
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.components.ValueLine
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

/** „Wege prüfen“ (ProbeDialog.svelte): jeder andere Knoten auf jeder Adresse – erreicht und wie schnell. */
@Composable
internal fun BoxScope.ProbeDialog(close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<JsonObject>?>(null) }
    var checked by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun check() {
        busy = true; error = null
        scope.launch {
            try {
                val res = env.api.post("cluster/probe").o()
                nodes = res.objs("nodes"); checked = res.str("checked")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { check() }
    FormDialog(
        tr.t("cluster.probeTitle"),
        onDismiss = close,
        actions = listOf(
            FormAction(tr.t("cluster.probeAgain"), ButtonKind.Primary, enabled = !busy) { check() },
            FormAction(tr.t("common.close")) { close() },
        ),
    ) {
        error?.let { Banner(it, tone = Tone.Error) }
        val list = nodes
        when {
            list == null -> Muted(t("cluster.probing"))
            list.isEmpty() -> Muted(t("cluster.probeNone"))
            else -> {
                for (n in list) {
                    Text(n.str("name"), style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = Titan.colors.text)
                    for (p in n.objs("probes")) ProbeCard(tr, p)
                }
                Muted(t("cluster.probeChecked", "time" to Fmt.time(tr, Fmt.instant(checked))))
            }
        }
    }
}

@Composable
private fun ProbeCard(tr: Tr, p: JsonObject) {
    val c = Titan.colors
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.border, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Buttons {
            Text(tr.wayLabel(p.str("way")), style = TitanType.body, color = c.text)
            if (p.bool("used")) Badge(tr.t("cluster.probeUsed"))
        }
        ValueLine(tr.t("cluster.probeAddress"), breakIds(p.str("address")), mono = true)
        Buttons {
            StatusChip((if (p.bool("ok")) "✓ " + tr.t("cluster.probeYes") else "✕ " + tr.t("cluster.probeNo")), if (p.bool("ok")) Tone.Ok else Tone.Error)
            Value(if (p.bool("ok")) "${p.long("millis")} ms" else tr.or("cluster.probeProblem.${p.str("problem").ifEmpty { "failed" }}", tr.t("cluster.probeProblem.failed")), mono = true)
        }
    }
}

/** Der Weg zu einem Knoten als Ausnahme (RouteDialog.svelte); „Wie der Cluster“ hebt sie auf. */
@Composable
internal fun BoxScope.RouteDialog(node: JsonObject, route: JsonObject, ways: JsonObject, close: () -> Unit, onSaved: (JsonObject) -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manage by remember { mutableStateOf(route.str("manage")) }
    var only by remember { mutableStateOf(route.bool("only")) }
    var backup by remember { mutableStateOf(route.str("backup")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hasRoute = route.isNotEmpty()
    fun save(next: JsonObject) {
        Guard.require(context, GuardAction.Cluster) {
            busy = true; error = null
            scope.launch {
                try {
                    onSaved(env.api.put("cluster/nodes/${enc(node.str("id"))}/route", next).o())
                    close()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr.errorOf(e)
                } finally { busy = false }
            }
        }
    }
    FormDialog(
        tr.t("cluster.routeTitle", "name" to node.str("name")),
        onDismiss = close,
        locked = busy,
        actions = listOf(
            FormAction(tr.t("cluster.routeApply"), ButtonKind.Primary, enabled = !busy) {
                save(route.with("manage" to manage, "only" to (manage.isNotEmpty() && only), "backup" to backup))
            },
            FormAction(tr.t("cluster.routeReset"), enabled = !busy && hasRoute) { save(JsonObject(emptyMap())) },
            FormAction(tr.t("common.cancel")) { close() },
        ),
    ) {
        Text(tr.t("cluster.routeManage"), style = TitanType.body, color = Titan.colors.text)
        Muted(tr.t("cluster.routeManageHint", "way" to tr.wayLabel(ways.str("manage"))))
        Select(manage, listOf("" to tr.t("cluster.routeLikeCluster")) + WAYS.map { it to tr.wayLabel(it) }, { manage = it }, label = tr.t("cluster.routeManage"))
        Text(tr.t("cluster.onlyWay"), style = TitanType.body, color = Titan.colors.text)
        Buttons {
            Toggle(only && manage.isNotEmpty(), { only = it }, onText(), offText(), enabled = manage.isNotEmpty(), label = tr.t("cluster.onlyWay"))
            Muted(tr.t(if (only && manage.isNotEmpty()) "cluster.onlyOn" else "cluster.onlyOff"))
        }
        Text(tr.t("cluster.routeBackup"), style = TitanType.body, color = Titan.colors.text)
        Select(backup, listOf("" to tr.t("cluster.routeLikeCluster")) + WAYS.map { it to tr.t("cluster.onlyThis", "way" to tr.wayLabel(it)) }, { backup = it }, label = tr.t("cluster.routeBackup"))
        error?.let { Banner(it, tone = Tone.Error) }
    }
}

/** „Alle Knoten aktualisieren“ (UpdateAllDialog.svelte): Version und gefundenes Update je Knoten, Reihenfolge, Start. */
@Composable
internal fun BoxScope.UpdateAllDialog(nodes: List<JsonObject>, close: () -> Unit, onStarted: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = remember { mutableStateMapOf<String, Triple<String, String, String>>() } // available, kind, "loading"|"failed"|""
    val chosen = remember { mutableStateListOf<String>() }
    var parallel by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        for (n in nodes) {
            val id = n.str("id")
            info[id] = Triple("", "", "loading")
            launch {
                try {
                    val st = env.api.obj(if (n.bool("self")) "update" else "cluster/nodes/${enc(id)}/api/update")
                    val avail = st.o("available").str("version").takeIf { it.isNotEmpty() && it != st.str("version") }.orEmpty()
                    info[id] = Triple(avail, st.str("kind"), "")
                    if (avail.isNotEmpty() && id !in chosen) chosen += id
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    info[id] = Triple("", "", "failed")
                }
            }
        }
    }
    FormDialog(
        tr.t("cluster.updateAllTitle"),
        onDismiss = close,
        locked = busy,
        actions = listOf(
            FormAction(tr.t("cluster.updateStart", "count" to chosen.size), ButtonKind.Primary, enabled = !busy && chosen.isNotEmpty()) {
                Guard.require(context, GuardAction.Cluster) {
                    busy = true; error = null
                    scope.launch {
                        try {
                            env.api.post("cluster/update", jobj("nodes" to chosen.toList(), "parallel" to parallel))
                            close()
                            onStarted()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = tr.errorOf(e)
                        } finally { busy = false }
                    }
                }
            },
            FormAction(tr.t("common.cancel"), enabled = !busy) { close() },
        ),
    ) {
        for (n in nodes) {
            val id = n.str("id")
            val (avail, kind, st) = info[id] ?: Triple("", "", "loading")
            val online = n.bool("online")
            val state = when {
                !online -> tr.t("cluster.offline")
                st == "loading" -> tr.t("common.loading")
                st == "failed" -> tr.t("cluster.updateUnknown")
                avail.isNotEmpty() -> tr.t(if (kind == "app") "cluster.updateKindApp" else "cluster.updateKindSystem")
                else -> tr.t("cluster.updateCurrent")
            }
            Divider()
            CheckRow(
                id in chosen,
                { on -> if (on) { if (id !in chosen) chosen += id } else chosen -= id },
                n.str("name") + if (n.bool("self")) " · " + tr.t("cluster.thisNode") else "",
                desc = tr.t("cluster.updateNow") + ": " + n.o("status").str("version").ifEmpty { "–" } + " · " +
                    tr.t("cluster.updateNew") + ": " + (if (st == "loading") tr.t("common.loading") else avail.ifEmpty { "–" }) + " · " +
                    tr.t("cluster.updateKind") + ": " + state,
                enabled = online,
            )
        }
        Text(tr.t("cluster.updateOrder"), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(top = 6.dp))
        RadioList(parallel, listOf(Triple(false, tr.t("cluster.updateSerial"), tr.t("cluster.updateSerialHint")), Triple(true, tr.t("cluster.updateParallel"), tr.t("cluster.updateParallelHint"))), { parallel = it })
        Muted(tr.t("cluster.updateAllHint"))
        error?.let { Banner(it, tone = Tone.Error) }
    }
}

/** Beitrittsinformation zeigen: erst Passwort (und Code). Passkeys gibt es in der App nicht. */
@Composable
internal fun BoxScope.RevealDialog(close: () -> Unit, onInfo: (String) -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) { session.value = runCatching { env.api.obj("session") }.getOrNull() }
    val twoFactor = session.value.o("user").bool("twoFactor")
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog(
        tr.t("cluster.showJoinInfo"),
        onDismiss = close,
        locked = busy,
        actions = listOf(
            FormAction(tr.t("cluster.showJoinInfo"), ButtonKind.Primary, enabled = !busy && reconfirmComplete(password, code, twoFactor)) {
                Guard.require(context, GuardAction.Cluster) {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val res = env.api.post("cluster/join-info", jobj("password" to password, "code" to code)).o()
                            onInfo(res.str("joinInfo"))
                            close()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = if (e.apiCode == "cluster_version") tr.t("cluster.versionMismatch", "version" to e.apiDetail.replace(Regex("^.*version "), "")) else tr.errorOf(e)
                        } finally {
                            busy = false; password = ""; code = ""
                        }
                    }
                }
            },
            FormAction(tr.t("common.cancel"), enabled = !busy) { close() },
        ),
    ) {
        ReconfirmFields(password, { password = it }, code, { code = it }, twoFactor, enabled = !busy)
        error?.let { Banner(it, tone = Tone.Error) }
    }
}

/** Wie noticeText() in lib/notifications.svelte.ts: übersetzter Titel und Text einer Meldung. */
internal fun Tr.noticeText(n: JsonObject): Pair<String, String> {
    val fill = { s: String -> s.replace("{subject}", n.str("subject")) }
    val kind = n.str("kind")
    val translated = or("notify.kind.$kind.text", "")
    val note = if (translated.isNotEmpty() && n.str("note").isNotEmpty()) or("notify.note.${n.str("note")}", "") else ""
    return fill(or("notify.kind.$kind.title", n.str("title"))) to (if (translated.isNotEmpty()) fill(translated) + (if (note.isNotEmpty()) " $note" else "") else fill(n.str("text")))
}

/** Warnungen und Fehler eines Knotens der letzten 24 Stunden (NodeNotices.svelte), lesen und löschen. */
@Composable
internal fun NodeNotices(node: String, self: Boolean, name: String, onChange: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val base = if (self) "notifications" else "cluster/nodes/${enc(node)}/api/notifications"
    var notices by remember(base) { mutableStateOf<List<JsonObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(base) {
        try { notices = env.api.obj(base).objs("messages") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = tr.errorOf(e) }
    }
    fun dismiss(ids: List<Long>) {
        busy = true; error = null
        scope.launch {
            try {
                notices = env.api.post("$base/dismiss", jobj("ids" to ids)).o().objs("messages")
                onChange()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            } finally { busy = false }
        }
    }
    val now = Instant.now()
    val shown = notices.orEmpty().filter { n ->
        n.str("severity") != "info" && Fmt.instant(n.str("time"))?.let { Duration.between(it, now).toHours() < 24 } == true
    }
    error?.let { Banner(it, tone = Tone.Error) }
    when {
        notices == null && error == null -> Muted(t("cluster.noticesLoading"))
        notices != null && shown.isEmpty() -> Muted(t("cluster.noticesNone"))
        shown.isNotEmpty() -> {
            for (n in shown) {
                val (title, text) = tr.noticeText(n)
                val sev = n.str("severity")
                Divider()
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Buttons {
                        StatusChip(tr.or("notify.severity.$sev", sev), if (sev == "error") Tone.Error else Tone.Warn)
                        Value(Fmt.dateTime(tr, n.str("time")), muted = true)
                        SmallButton(t("common.delete"), { dismiss(listOf(n.long("id"))) }, icon = WebIcons["trash"], enabled = !busy)
                    }
                    Text(title, style = TitanType.body, color = Titan.colors.text)
                    if (text.isNotEmpty()) Muted(text)
                }
            }
            SmallButton(t("notify.deleteAll"), {
                ov.confirm(tr.t("notify.deleteAllTitle"), tr.t("cluster.noticesDeleteText", "count" to shown.size, "name" to name), action = tr.t("notify.deleteAllConfirm"), cancel = tr.t("common.cancel")) {
                    dismiss(shown.map { it.long("id") })
                }
            }, enabled = !busy)
        }
    }
}
