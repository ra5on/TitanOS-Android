package io.github.ra5on.titanos.settings.pages.security

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.R
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Runner
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.numOrNull
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.DangerZone
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.components.ValueLine
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Die Wege zwischen Knoten, wie das Backend sie nennt (lib/cluster.svelte.ts); "" ist automatisch. */
internal val WAYS = listOf("home", "tunnel", "wireguard", "tailscale", "netbird", "extra", "internet")
private val FOUND_WAYS = listOf("home", "tailscale", "netbird", "wireguard")

internal fun Tr.wayLabel(way: String?): String = t("cluster.way.${way?.ifEmpty { null } ?: "auto"}")

/** Ordnet zwei Versionen wie 0.42.0 < 0.43.0; unbekannte gelten als gleich (cluster-node.ts). */
internal fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.')
    val pb = b.split('.')
    for (i in 0 until 3) {
        val x = pa.getOrNull(i)?.let { it.toIntOrNull() ?: return 0 } ?: 0
        val y = pb.getOrNull(i)?.let { it.toIntOrNull() ?: return 0 } ?: 0
        if (x != y) return x - y
    }
    return 0
}

private fun otherVersion(n: JsonObject, version: String): Boolean {
    val v = n.o("status").str("version")
    return !n.bool("self") && v.isNotEmpty() && v != version
}

private fun older(n: JsonObject, version: String) = otherVersion(n, version) && compareVersions(n.o("status").str("version"), version) < 0

private fun manageable(n: JsonObject) = n.bool("self") || n.bool("online")

private fun volumeProblems(n: JsonObject) = n.o("status").objs("volumes").count { !it.bool("mounted") || it.str("state") !in listOf("ok", "clean", "active", "healthy") }

/**
 * Cluster (routes/settings/cluster): mehrere TitanOS, die einander voll
 * vertrauen. Außerhalb: erstellen oder beitreten (mit Vorschau). Innerhalb:
 * die Knoten mit Zustand, Details, Meldungen und Aktionen, die Einstellungen
 * des Clusters (ein Formular), Konten nur auf diesem NAS, die
 * Beitrittsinformation (nach Passwort und Code) und das Verlassen.
 * Änderungen hinter dem Fingerabdruck „Cluster“. Die Routen verlangen eine
 * eigene Sitzung mit Cookie (`requireOwnSession`) – die der App gilt.
 */
@Composable
fun ClusterPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val page = rememberLoad { env.api.obj("cluster") }
    val run = rememberRunner()

    var clusterName by remember { mutableStateOf("") }
    var internet by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf("") }
    var ways by remember { mutableStateOf(JsonObject(emptyMap())) }
    var tunnel by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var joinInfo by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var nodeCount by remember { mutableStateOf(-1) }
    var newName by remember { mutableStateOf("") }
    var joinText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<JsonObject?>(null) }

    fun fill(next: JsonObject, force: Boolean = true) {
        val c = next.o("cluster")
        val count = c.objs("nodes").size
        if (nodeCount >= 0 && count != nodeCount) { joinInfo = ""; copied = false }
        nodeCount = count
        page.value = next
        if (force || !edited) {
            clusterName = c.str("name")
            internet = c.bool("internet")
            extra = c.strings("extra").joinToString("\n")
            val w = c.o("ways")
            ways = w.with(
                "manage" to w.str("manage"),
                "backup" to w.str("backup"),
                "return" to w.int("return"),
                "alert" to w.int("alert"),
            )
            tunnel = c.o("tunnel").bool("enabled")
            edited = false
        }
    }
    LaunchedEffect(page.value == null) { page.value?.let { if (nodeCount < 0) fill(it) } }

    /** Wie act() im Web: läuft mit dem Fingerabdruck, übernimmt die Antwort; `cluster_version` in Worten. */
    fun act(guard: GuardAction? = GuardAction.Cluster, block: suspend () -> JsonObject?) {
        saved = false
        run.run(guard = guard) {
            try {
                block()?.let { fill(it) }
            } catch (e: Exception) {
                if (e.apiCode == "cluster_version") {
                    run.error = tr.t("cluster.versionMismatch", "version" to e.apiDetail.replace(Regex("^.*version "), ""))
                } else throw e
            }
        }
    }

    val p = page.value
    val cluster = p.o("cluster")
    val inCluster = cluster.str("name").isNotEmpty()
    // Alle 20 Sekunden neu, solange nichts läuft und das NAS in einem Cluster ist.
    LaunchedEffect(inCluster) {
        while (inCluster) {
            delay(20_000)
            if (!run.running) runCatching { env.api.obj("cluster") }.getOrNull()?.let { fill(it, force = false) }
        }
    }
    LaunchedEffect(joinInfo) {
        if (joinInfo.isNotEmpty()) { delay(5 * 60 * 1000L); joinInfo = ""; copied = false }
    }

    PageIntro(t("cluster.intro"))
    page.error?.let { if (p == null) Banner(tr.errorOf(it), tone = Tone.Error, action = t("ui.retry"), onAction = { page.reload() }) }
    if (p == null) {
        if (page.loading) LoadingLine(t("common.loading"))
        return
    }
    val owner = env.owner
    val manages = cluster.bool("manages")
    val isAdmin = cluster.str("admin").isNotEmpty() && cluster.str("admin") == cluster.str("self")
    val version = cluster.str("version")
    val nodes = p.objs("nodes")
    val adminNode = nodes.firstOrNull { it.str("id") == cluster.str("admin") }

    if (!inCluster) {
        if (!owner) Banner(t("cluster.ownerOnly"), tone = Tone.Warn)
        SettingGroup {
            SettingRow(t("cluster.create"), desc = t("cluster.createHint"), icon = WebIcons["plus"], control = if (!owner) null else ({
                Field(newName, { if (it.length <= 63) newName = it }, t("cluster.name"), Modifier.widthIn(min = 160.dp, max = 280.dp), placeholder = t("cluster.namePlaceholder"), enabled = !run.running, singleLine = true)
                SmallButton(t("cluster.createButton"), { act { env.api.post("cluster", jobj("name" to newName.trim())).o() } }, kind = ButtonKind.Primary, enabled = !run.running && newName.isNotBlank())
            }))
            SettingRow(t("cluster.join"), desc = t("cluster.joinHint"), icon = WebIcons["tailnet"], id = "cluster-join", startOpen = true, content = if (!owner) null else ({
                LabeledField(t("cluster.joinInfo"), joinText, { joinText = it }, placeholder = "titanos-cluster:…", mono = true, enabled = !run.running, minHeight = 96.dp)
                Buttons {
                    SmallButton(t("cluster.check"), {
                        preview = null
                        act(guard = null) { preview = env.api.post("cluster/preview", jobj("info" to joinText.trim())).o(); null }
                    }, enabled = !run.running && joinText.isNotBlank())
                }
                preview?.let { pv ->
                    Value(t("cluster.previewCluster", "name" to pv.str("cluster"), "nodes" to pv.strings("nodes").joinToString(", ")))
                    if (pv.strings("replaced").isNotEmpty()) Banner(t("cluster.previewReplaced", "names" to pv.strings("replaced").joinToString(", ")), tone = Tone.Warn)
                    if (pv.strings("local").isNotEmpty()) Value(t("cluster.previewLocal", "names" to pv.strings("local").joinToString(", ")))
                    if (pv.strings("taken").isNotEmpty()) Value(t("cluster.previewTaken", "names" to pv.strings("taken").joinToString(", ")))
                    Buttons {
                        SmallButton(t("cluster.joinButton"), {
                            ov.show { close ->
                                FormDialog(
                                    tr.t("cluster.joinConfirmTitle", "name" to pv.str("cluster")),
                                    onDismiss = close,
                                    text = tr.t("cluster.joinConfirm"),
                                    actions = listOf(
                                        FormAction(tr.t("cluster.joinButton"), ButtonKind.Danger) {
                                            close()
                                            act {
                                                val next = env.api.post("cluster/join", jobj("info" to joinText.trim())).o()
                                                preview = null; joinText = ""
                                                next
                                            }
                                        },
                                        FormAction(tr.t("common.cancel")) { close() },
                                    ),
                                ) {
                                    if (pv.strings("replaced").isNotEmpty()) Banner(tr.t("cluster.previewReplaced", "names" to pv.strings("replaced").joinToString(", ")), tone = Tone.Warn)
                                }
                            }
                        }, kind = ButtonKind.Primary, enabled = !run.running)
                    }
                }
            }))
        }
        RunnerState(run)
        return
    }

    // --- Knoten ---
    SettingGroup(t("cluster.nodesOf", "name" to cluster.str("name")), tools = if (!manages) null else ({
        SmallButton(t("cluster.probe"), { ov.show { close -> ProbeDialog(close) } }, icon = WebIcons["refresh"])
        SmallButton(t("cluster.updateAll"), { ov.show { close -> UpdateAllDialog(nodes, close) { a.openPage("updates") } } }, icon = WebIcons["download"])
    })) {
        for (node in nodes) NodeRow(node, cluster, ways, owner, manages, isAdmin, version, adminNode, run, ::act, onChange = { page.reload() }, onRoute = {
            ov.show { close -> RouteDialog(node, cluster.o("routes").o(node.str("id")), cluster.o("ways"), close) { fill(it) } }
        })
        if (manages && nodes.any { !it.bool("self") }) Muted(stringResource(R.string.sec_node_web))
        Muted(t(if (manages) "cluster.trust" else "cluster.memberHint"))
    }
    RunnerState(run)

    // --- Einstellungen (ein Formular, ein „Speichern“) ---
    val busy = run.running
    fun setWays(vararg pairs: Pair<String, Any?>) { ways = ways.with(*pairs); edited = true }
    SettingGroup(t("cluster.settings")) {
        SettingRow(t("cluster.name"), icon = WebIcons["edit"], control = {
            Field(clusterName, { if (it.length <= 63) { clusterName = it; edited = true } }, t("cluster.name"), Modifier.widthIn(min = 160.dp, max = 280.dp), enabled = !busy && manages, singleLine = true)
        })
    }
    SettingGroup(t("cluster.connection")) {
        SettingRow(t("cluster.manageWay"), desc = t("cluster.manageWayHint"), icon = WebIcons["globe"], control = {
            Select(ways.str("manage"), listOf("" to t("cluster.wayAuto")) + WAYS.map { it to tr.t("cluster.wayFirst", "way" to tr.wayLabel(it)) }, { setWays("manage" to it) }, label = t("cluster.manageWay"), enabled = !busy && manages)
        })
        val onlyOn = ways.bool("only") && ways.str("manage").isNotEmpty()
        SettingRow(t("cluster.onlyWay"), desc = t(if (onlyOn) "cluster.onlyOn" else "cluster.onlyOff"), icon = WebIcons["lock"], control = {
            Toggle(onlyOn, { setWays("only" to it) }, onText(), offText(), enabled = !busy && manages && ways.str("manage").isNotEmpty(), label = t("cluster.onlyWay"))
        })
        SettingRow(t("cluster.returnWay"), desc = t("cluster.returnWayHint"), icon = WebIcons["undo"], control = {
            Select(ways.int("return"), listOf(0 to t("cluster.everyMinute"), 5 to t("cluster.everyMinutes", "count" to 5), 15 to t("cluster.everyMinutes", "count" to 15), -1 to t("cluster.never")).let { l -> if (l.any { it.first == ways.int("return") }) l else l + (ways.int("return") to ways.int("return").toString()) }, { setWays("return" to it) }, label = t("cluster.returnWay"), enabled = !busy && manages)
        })
        SettingRow(t("cluster.backupWay"), desc = t("cluster.backupWayHint"), icon = WebIcons["archive"], control = {
            Select(ways.str("backup"), listOf("" to t("cluster.backupLikeManage")) + WAYS.map { it to tr.t("cluster.onlyThis", "way" to tr.wayLabel(it)) }, { setWays("backup" to it) }, label = t("cluster.backupWay"), enabled = !busy && manages)
        })
    }
    SettingGroup(t("cluster.waysTitle")) {
        for (way in FOUND_WAYS) {
            val found = cluster.strings("found").filter { cluster.o("foundWays").str(it) == way }
            SettingRow(
                tr.wayLabel(way),
                desc = if (way == "tailscale" && found.any { it.contains(".ts.net") }) t("cluster.tailscaleNameHint") else null,
                icon = WebIcons[if (way == "home") "home" else "tailnet"],
                control = {
                    if (found.isNotEmpty()) Value(found.joinToString(" · "), mono = true)
                    StatusChip(t(if (found.isNotEmpty()) "cluster.wayFound" else "cluster.wayNotFound"), if (found.isNotEmpty()) Tone.Ok else Tone.Inactive)
                },
            )
        }
        val tv = cluster.o("tunnel")
        SettingRow(
            t("cluster.tunnel"),
            desc = if (tv.str("error").isNotEmpty()) tv.str("error") else t("cluster.tunnelHint", "port" to (tv.long("port").takeIf { it != 0L } ?: 51821)),
            icon = WebIcons["shield"],
            control = {
                if (tunnel && tv.bool("enabled") && tv.str("address").isNotEmpty()) Value(t("cluster.tunnelOn", "address" to tv.str("address")))
                Toggle(tunnel, { tunnel = it; edited = true }, onText(), offText(), enabled = !busy && manages, label = t("cluster.tunnel"))
            },
        )
        if (tv.str("error").isNotEmpty()) Banner(tv.str("error"), tone = Tone.Error)
        SettingRow(t("cluster.internetWay"), desc = t(if (internet) "cluster.internetWarn" else "cluster.internetHint"), icon = WebIcons["external"], control = {
            if (internet) StatusChip(t("cluster.internetWay"), Tone.Warn)
            Toggle(internet, { internet = it; edited = true }, onText(), offText(), enabled = !busy && manages, label = t("cluster.internetWay"))
        })
        SettingRow(t("cluster.extra"), desc = t("cluster.extraHint"), icon = WebIcons["list"], control = {
            Field(extra, { extra = it; edited = true }, t("cluster.extra"), Modifier.widthIn(min = 200.dp, max = 320.dp), placeholder = "nas.example.org", mono = true, enabled = !busy, minHeight = 64.dp)
        })
    }
    SettingGroup(t("cluster.notices")) {
        SettingRow(t("cluster.alert"), desc = t("cluster.alertHint"), icon = WebIcons["bell"], control = {
            val options = listOf(5 to t("cluster.alertAfter", "count" to 5), 0 to t("cluster.alertAfter", "count" to 10), 30 to t("cluster.alertAfter", "count" to 30), 60 to t("cluster.alertAfter", "count" to 60), -1 to t("cluster.never"))
            Select(ways.int("alert"), options.let { l -> if (l.any { it.first == ways.int("alert") }) l else l + (ways.int("alert") to ways.int("alert").toString()) }, { setWays("alert" to it) }, label = t("cluster.alert"), enabled = !busy && manages)
        })
        SettingRow(t("cluster.clock"), desc = t("cluster.clockHint"), icon = WebIcons["clock"], control = {
            Toggle(!ways.bool("clockOff"), { setWays("clockOff" to !it) }, onText(), offText(), enabled = !busy && manages, label = t("cluster.clock"))
        })
        Buttons {
            SmallButton(t("common.save"), {
                act {
                    val next = env.api.put("cluster", clusterSettingsBody(clusterName, internet, extra, ways, tunnel, manages)).o()
                    saved = true
                    next
                }
            }, kind = ButtonKind.Primary, enabled = !busy && clusterName.isNotBlank())
            if (saved) Muted(t("ui.saved"))
        }
        RunnerState(run)
    }

    // --- Konten nur auf diesem NAS ---
    val local = cluster.strings("localAccounts")
    if (owner && local.isNotEmpty()) {
        SettingGroup(t("cluster.local.title"), desc = t("cluster.local.hint")) {
            for (name in local) SettingRow(name, icon = WebIcons["users"], control = {
                SmallButton(t("cluster.local.share"), {
                    ov.confirm(tr.t("cluster.local.share"), tr.t("cluster.local.confirm", "name" to name), action = tr.t("cluster.local.share"), cancel = tr.t("common.cancel")) {
                        act { env.api.post("cluster/accounts/" + enc(name) + "/share").o() }
                    }
                }, enabled = !busy)
            })
        }
    }

    // --- Beitrittsinformation ---
    if (manages) {
        SettingGroup(t("cluster.groupJoin")) {
            SettingRow(
                t("cluster.joinInfo"),
                desc = t("cluster.joinInfoHint"),
                icon = WebIcons["key"],
                id = "cluster-join-info",
                control = {
                    if (joinInfo.isEmpty()) SmallButton(t("cluster.showJoinInfo"), {
                        ov.show { close -> RevealDialog(close) { info -> joinInfo = info } }
                    }, kind = ButtonKind.Primary, enabled = !busy)
                    SmallButton(t("cluster.newKey"), {
                        ov.confirm(tr.t("cluster.newKey"), tr.t("cluster.newKeyConfirm"), action = tr.t("cluster.newKey"), cancel = tr.t("common.cancel")) {
                            act {
                                val next = env.api.post("cluster/join-key").o()
                                joinInfo = ""; copied = false
                                next
                            }
                        }
                    }, enabled = !busy)
                },
            )
            if (joinInfo.isNotEmpty()) {
                CodeBlock(joinInfo)
                Muted(t("cluster.joinInfoHides"))
                Buttons {
                    SmallButton(t(if (copied) "cluster.copied" else "cluster.copy"), { copyText(context, joinInfo, sensitive = true); copied = true }, kind = ButtonKind.Primary, icon = WebIcons["copy"])
                    SmallButton(t("cluster.hideJoinInfo"), { joinInfo = ""; copied = false })
                }
            }
        }
    }

    if (owner) {
        DangerZone(t("ui.dangerZone"), t("cluster.leaveHint")) {
            SmallButton(t("cluster.leaveButton"), {
                ov.confirm(tr.t("cluster.leave"), tr.t("cluster.leaveConfirm"), action = tr.t("cluster.leaveButton"), cancel = tr.t("common.cancel")) {
                    act {
                        val next = env.api.post("cluster/leave").o()
                        joinInfo = ""; copied = false
                        next
                    }
                }
            }, kind = ButtonKind.Danger, enabled = !busy)
        }
    }
}

internal fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** Der Inhalt von `PUT /cluster` wie saveSettings() im Web; Wege und Tunnel nur, wenn dieser Knoten verwaltet. */
internal fun clusterSettingsBody(name: String, internet: Boolean, extra: String, ways: JsonObject, tunnel: Boolean, manages: Boolean): JsonObject {
    val lines = extra.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    val base = jobj("name" to name.trim(), "internet" to internet, "extra" to lines)
    return if (!manages) base else base.with("ways" to ways.with("only" to (ways.str("manage").isNotEmpty() && ways.bool("only"))), "tunnel" to tunnel)
}

@Composable
private fun NodeRow(
    node: JsonObject,
    cluster: JsonObject,
    ways: JsonObject,
    owner: Boolean,
    manages: Boolean,
    isAdmin: Boolean,
    version: String,
    adminNode: JsonObject?,
    run: Runner,
    act: (GuardAction?, suspend () -> JsonObject?) -> Unit,
    onChange: () -> Unit,
    onRoute: () -> Unit,
) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val id = node.str("id")
    val self = node.bool("self")
    val status = node.o("status")
    val hasStatus = node.has("status")
    val route = cluster.o("routes").o(id)
    val readable = self || (manages && manageable(node))
    val noticed = status.int("errors") > 0 || status.int("warnings") > 0
    val isAdminNode = id == cluster.str("admin")
    val takeOver = owner && !isAdmin && isAdminNode && !node.bool("online")
    val role = t(if (isAdminNode) "cluster.roleAdmin" else if (node.bool("manage")) "cluster.roleManage" else "cluster.roleMember")
    val desc = buildList {
        when {
            self -> add(node.strings("addresses").joinToString(" · "))
            node.bool("online") && node.str("way").isNotEmpty() -> {
                add(tr.t("cluster.via", "way" to tr.wayLabel(node.str("way"))) + (if (node.long("millis") > 0) " · ${node.long("millis")} ms" else "") + " · " + node.str("address"))
                if (node.bool("fallback")) add(tr.t(if (ways.int("return") < 0) "cluster.fallbackStays" else "cluster.fallback", "way" to tr.wayLabel(node.str("preferred"))))
            }
            node.str("error").isNotEmpty() -> add(tr.error(node.str("error")) + " · " + node.strings("addresses").joinToString(" · "))
        }
        val skew = node.long("clockSkew")
        if (skew != 0L) add(tr.t(if (skew > 0) "cluster.clockAhead" else "cluster.clockBehind", "minutes" to maxOf(1, (abs(skew) / 60.0).roundToInt())))
    }.filter { it.isNotBlank() }.joinToString("\n").ifEmpty { null }
    SettingRow(
        node.str("name"),
        desc = desc,
        icon = WebIcons["tailnet"],
        badge = role,
        id = "node-$id",
        control = {
            if (self) Badge(t("cluster.thisNode"))
            StatusChip(t(if (node.bool("online")) "cluster.online" else "cluster.offline"), if (node.bool("online")) Tone.Ok else Tone.Error)
            if (hasStatus && otherVersion(node, version)) StatusChip(t(if (older(node, version)) "cluster.olderVersion" else "cluster.newerVersion"), Tone.Warn)
            if (route.str("manage").isNotEmpty()) Badge(t("cluster.ownWay", "way" to tr.wayLabel(route.str("manage"))))
            if (status.int("errors") > 0) StatusChip(t("cluster.errors", "count" to status.int("errors")), Tone.Error)
            if (status.int("warnings") > 0) StatusChip(t("cluster.warnings", "count" to status.int("warnings")), Tone.Warn)
        },
    ) {
        ValueLine(t("cluster.detail.addresses"), node.strings("addresses").joinToString("\n").ifEmpty { "–" }, mono = true)
        if (hasStatus) {
            ValueLine(t("cluster.detail.version"), status.str("version") + if (older(node, version)) " → $version" else "")
            ValueLine(t("cluster.detail.uptime"), Fmt.duration(tr, status.long("uptimeSeconds")))
            status.numOrNull("cpuPercent")?.let { ValueLine(t("cluster.detail.cpu"), "${it.roundToInt()} %") }
            val mem = status.o("memory")
            if (mem.long("totalBytes") > 0) ValueLine(t("cluster.detail.memory"), t("cluster.detail.memoryValue", "used" to Fmt.bytes(tr, mem.long("totalBytes") - mem.long("availableBytes")), "total" to Fmt.bytes(tr, mem.long("totalBytes"))))
            val vp = volumeProblems(node)
            ValueLine(t("cluster.detail.volumes"), status.objs("volumes").size.toString() + if (vp > 0) " (" + t("cluster.volumeProblems", "count" to vp) + ")" else "")
        }
        if (noticed && readable) NodeNotices(id, self, node.str("name"), onChange)
        if (manages && (!self || (owner && !isAdmin && adminNode != null && !adminNode.bool("online")))) {
            Buttons {
                if (!self) SmallButton(t("cluster.routeMenu"), onRoute, icon = WebIcons["globe"])
                if (owner && isAdmin && !self) {
                    SmallButton(t(if (node.bool("manage")) "cluster.manageRevoke" else "cluster.manageAllow"), {
                        act(GuardAction.Cluster) { env.api.put("cluster/nodes/${enc(id)}/manage", jobj("manage" to !node.bool("manage"))).o() }
                    }, icon = WebIcons["shield"], enabled = !run.running)
                    SmallButton(t("cluster.handOver", "name" to node.str("name")), {
                        ov.confirm(tr.t("cluster.handOver", "name" to node.str("name")), tr.t("cluster.handOverConfirm", "name" to node.str("name")), action = tr.t("cluster.handOverButton"), cancel = tr.t("common.cancel")) {
                            act(GuardAction.Cluster) { env.api.post("cluster/nodes/${enc(id)}/admin").o() }
                        }
                    }, icon = WebIcons["users"], enabled = !run.running)
                }
                if (takeOver) SmallButton(t("cluster.takeOver"), {
                    val selfId = cluster.str("self")
                    ov.confirm(tr.t("cluster.takeOver"), tr.t("cluster.takeOverConfirm", "name" to (adminNode?.str("name") ?: "")), action = tr.t("cluster.takeOver"), cancel = tr.t("common.cancel")) {
                        act(GuardAction.Cluster) { env.api.post("cluster/nodes/${enc(selfId)}/admin").o() }
                    }
                }, icon = WebIcons["users"], enabled = !run.running)
                if (owner && !isAdminNode && !self) SmallButton(t("cluster.remove"), {
                    ov.confirm(tr.t("cluster.remove"), tr.t("cluster.removeConfirm", "name" to node.str("name")), action = tr.t("cluster.remove"), cancel = tr.t("common.cancel")) {
                        act(GuardAction.Cluster) { env.api.delete("cluster/nodes/${enc(id)}").o() }
                    }
                }, kind = ButtonKind.Danger, icon = WebIcons["trash"], enabled = !run.running)
            }
        }
    }
}
