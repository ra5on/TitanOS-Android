package io.github.ra5on.titanos.docker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.ComputeUi
import io.github.ra5on.titanos.compute.ErrorBox
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.Loading
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.NasImage
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.SmallButton
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Locale

/** Liest Daten einer Seite mit Fehler und „Erneut versuchen“; [reload] erhöhen lädt neu. */
@Composable
private fun <T> rememberLoad(reload: Int, block: suspend () -> T): Pair<T?, Throwable?> {
    var value by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(reload) {
        try {
            value = block()
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    return value to error
}

private fun usedByText(used: List<UsedBy>): String =
    used.map { u -> if (u.app.isNotEmpty() && u.container.isNotEmpty() && u.container != u.app) "${u.app} (${u.container})" else u.app.ifEmpty { u.container } }
        .filter { it.isNotEmpty() }.distinct().joinToString(", ")

private fun shortId(id: String) = "sha256:" + id.removePrefix("sha256:").take(10)

private fun protectedKey(reason: String) = when (reason) {
    "viewer", "sandbox", "nvr", "speech" -> "dres.protected.$reason"
    else -> "dres.protected.titanos"
}

private fun prune(ui: ComputeUi, api: DockerApi, path: String, body: JsonObject, done: (PruneResult) -> Unit) {
    ui.run { done(api.prune(path, body)) }
}

/** Images (routes/docker/images): Größe, wer sie nutzt, löschen, ungenutzte löschen. */
@Composable
internal fun ImagesPage(api: DockerApi) {
    val ui = LocalComputeUi.current
    var reload by remember { mutableIntStateOf(0) }
    val (list, error) = rememberLoad(reload) { api.images() }
    var search by rememberSaveable { mutableStateOf("") }
    ScrollPage {
        Text(t("dres.images.intro"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 6.dp))
        error?.let { ErrorBox(errorText(it), { reload++ }) }
        val l = list ?: run { if (error == null) Loading(); return@ScrollPage }
        val images = l.images
        val unused = images.filter { it.usedBy.isEmpty() && it.protected.isEmpty() }
        val unusedSize = unused.sumOf { it.freed }
        l.totals?.let { Text(t("dres.images.count", "count" to it.count, "size" to Format.bytes(it.size)), style = TitanType.small, color = Titan.colors.muted) }
        if (unused.isNotEmpty()) {
            Note(t("dres.images.unusedTitle", "count" to unused.size) + " – " + t("dres.images.unusedText", "size" to Format.bytes(unusedSize)))
            SmallButton(t("dres.images.deleteUnused", "size" to Format.bytes(unusedSize)), TitanIcons.trash, {
                ui.confirm(Ask(t2(ui, "dres.images.pruneTitle"), ui.text("dres.images.pruneText", "count" to unused.size, "size" to Format.bytes(unusedSize)), ui.text("dres.delete")) {
                    prune(ui, api, "/docker/images/prune", buildJsonObject { put("unused", JsonPrimitive(true)) }) { r ->
                        ui.toast(ui.text("dres.images.deleted", "size" to Format.bytes(r.reclaimed)) + failedText(ui, r))
                        reload++
                    }
                })
            }, danger = true)
        } else if (images.isNotEmpty()) {
            Text(t("dres.images.allUsed"), style = TitanType.small, color = Titan.colors.muted)
        }
        Field(t("dres.images.search"), search, { search = it })
        val q = search.trim().lowercase()
        val shown = images.filter { q.isEmpty() || (it.tags + it.id).joinToString(" ").lowercase().contains(q) }
        if (images.isEmpty()) EmptyState(t("dres.images.empty"), t("dres.images.emptyText"))
        else if (shown.isEmpty()) Text(t("dres.images.noMatch"), style = TitanType.body, color = Titan.colors.muted)
        else Card {
            shown.forEachIndexed { i, img ->
                if (i > 0) Divider()
                val name = img.tags.firstOrNull() ?: shortId(img.id)
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, style = TitanType.rowTitle, color = Titan.colors.text)
                        if (img.tags.size > 1) Text(img.tags.drop(1).joinToString(", "), style = TitanType.rowSub, color = Titan.colors.muted)
                        Text("${t("dres.col.size")}: ${Format.bytes(img.size)}", style = TitanType.rowSub, color = Titan.colors.muted)
                        when {
                            img.protected.isNotEmpty() -> StateMark(t(protectedKey(img.protected)), StateKind.Info)
                            img.usedBy.isNotEmpty() -> StateMark(t("dres.inUse") + ": " + usedByText(img.usedBy), StateKind.Ok)
                            else -> StateMark(t("dres.unused"), StateKind.Off)
                        }
                    }
                    if (img.usedBy.isEmpty() && img.protected.isEmpty()) {
                        IconButton(TitanIcons.trash, t("dres.deleteNamed", "name" to name), {
                            ui.confirm(Ask(ui.text("dres.images.deleteTitle"), ui.text("dres.images.deleteText", "name" to name, "size" to Format.bytes(img.freed)), ui.text("dres.delete")) {
                                ui.run { api.removeImage(img.id); reload++ }
                            })
                        }, tint = Titan.colors.text)
                    }
                }
            }
        }
    }
}

private fun t2(ui: ComputeUi, key: String) = ui.text(key)

private fun failedText(ui: ComputeUi, r: PruneResult) = if (r.failed.isNotEmpty()) "\n" + ui.text("dres.failed", "names" to r.failed.joinToString(", ") { it.id }) else ""

/** Volumes (routes/docker/volumes): Größe, Nutzung, löschen, anonyme ungenutzte löschen. */
@Composable
internal fun VolumesPage(api: DockerApi) {
    val ui = LocalComputeUi.current
    var reload by remember { mutableIntStateOf(0) }
    val (data, error) = rememberLoad(reload) { api.volumes().volumes }
    var search by rememberSaveable { mutableStateOf("") }
    ScrollPage {
        Text(t("dres.volumes.intro"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 6.dp))
        error?.let { ErrorBox(errorText(it), { reload++ }) }
        val volumes = data ?: run { if (error == null) Loading(); return@ScrollPage }
        val deletable = volumes.filter { it.usedBy.isEmpty() && !it.titanos }
        val anonymous = deletable.filter { it.anonymous }
        if (deletable.isNotEmpty()) {
            Note(t("dres.volumes.unusedTitle", "count" to deletable.size) + if (anonymous.isNotEmpty()) " – " + t("dres.volumes.anonymousText", "count" to anonymous.size) else "")
            if (anonymous.isNotEmpty()) {
                SmallButton(t("dres.volumes.deleteAnonymous", "count" to anonymous.size), TitanIcons.trash, {
                    ui.confirm(Ask(ui.text("dres.volumes.anonymousTitle"), ui.text("dres.volumes.anonymousPrune", "count" to anonymous.size) + "\n" + anonymous.joinToString("\n") { it.name.take(24) }, ui.text("dres.volumes.deleteButton")) {
                        prune(ui, api, "/docker/volumes/prune", buildJsonObject { put("anonymousOnly", JsonPrimitive(true)) }) { r ->
                            ui.toast(ui.text("dres.cleanup.done", "size" to Format.bytes(r.reclaimed)) + failedText(ui, r))
                            reload++
                        }
                    })
                }, danger = true)
            }
        } else if (volumes.isNotEmpty()) {
            Text(t("dres.volumes.allUsed"), style = TitanType.small, color = Titan.colors.muted)
        }
        Field(t("dres.volumes.search"), search, { search = it })
        val q = search.trim().lowercase()
        val shown = volumes.filter { q.isEmpty() || (it.name + " " + it.app).lowercase().contains(q) }
        if (volumes.isEmpty()) EmptyState(t("dres.volumes.empty"), t("dres.volumes.emptyText"))
        else if (shown.isEmpty()) Text(t("dres.volumes.noMatch"), style = TitanType.body, color = Titan.colors.muted)
        else Card {
            shown.forEachIndexed { i, v ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // Anonyme Volumes haben lange Kennungen ohne Leerzeichen: gekürzt ist der Name ein Wort, ganz steht er im Bildschirmleser-Text.
                        Text(if (v.anonymous) v.name.chunked(16).joinToString(" ") else v.name, style = TitanType.rowTitle, color = Titan.colors.text)
                        Text(listOfNotNull(v.driver, if (v.size >= 0) Format.bytes(v.size) else t("dres.volumes.sizeUnknown"), v.app.ifEmpty { null }, if (v.anonymous) t("dres.volumes.anonymousChip") else null).joinToString(" · "), style = TitanType.rowSub, color = Titan.colors.muted)
                        when {
                            v.titanos -> StateMark(t("dres.volumes.titanos"), StateKind.Info)
                            v.usedBy.isNotEmpty() -> StateMark(t("dres.inUse") + ": " + usedByText(v.usedBy), StateKind.Ok)
                            else -> StateMark(t("dres.unused"), StateKind.Off)
                        }
                    }
                    if (v.usedBy.isEmpty() && !v.titanos) {
                        IconButton(TitanIcons.trash, t("dres.deleteNamed", "name" to v.name), {
                            ui.confirm(Ask(ui.text("dres.volumes.deleteTitle"), ui.text("dres.volumes.deleteText", "name" to v.name), ui.text("dres.volumes.deleteButton")) {
                                ui.run { api.removeVolume(v.name); reload++ }
                            })
                        })
                    }
                }
            }
        }
    }
}

/** Netzwerke (routes/docker/networks): Docker-Netze und LAN-Netze für Container. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetworksPage(api: DockerApi) {
    val ui = LocalComputeUi.current
    var reload by remember { mutableIntStateOf(0) }
    val (all, error) = rememberLoad(reload) { api.networks().networks }
    val (lans, lanError) = rememberLoad(reload) { api.lans() }
    ScrollPage {
        Text(t("dnet.intro"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 6.dp))
        error?.let { ErrorBox(errorText(it), { reload++ }) }
        val networks = all
        if (networks == null) {
            if (error == null) Loading()
        } else {
            val unused = networks.filter { it.kind == "user" && it.usedBy.isEmpty() && !it.protected }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(t("dnet.new"), TitanIcons.plus, { ui.sheet = { BridgeSheet(api) { reload++ } } }, primary = true)
                if (unused.isNotEmpty()) SmallButton(t("dnet.prune", "count" to unused.size), TitanIcons.trash, {
                    ui.confirm(Ask(ui.text("dnet.pruneTitle"), ui.text("dnet.pruneText", "count" to unused.size), ui.text("dres.delete")) {
                        prune(ui, api, "/docker/networks/prune", JsonObject(emptyMap())) { r ->
                            ui.toast(ui.text("dnet.pruned", "count" to (r.count ?: unused.size)))
                            reload++
                        }
                    })
                }, danger = true)
            }
            Section(t("dnet.list"))
            Card {
                networks.forEachIndexed { i, n ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(n.name, style = TitanType.rowTitle, color = Titan.colors.text)
                            Text(listOf(n.driver, n.subnets.joinToString(", "), if (n.internal) t("dnet.internal") else "").filter { it.isNotEmpty() }.joinToString(" · "), style = TitanType.rowSub, color = Titan.colors.muted)
                            val reason = when (n.kind) {
                                "app" -> t("dnet.kind.app", "app" to n.app.ifEmpty { "–" })
                                "lan" -> t("dnet.kind.lan")
                                "titanos" -> t("dnet.kind.titanos")
                                "system" -> t("dnet.kind.system")
                                else -> ""
                            }
                            if (reason.isNotEmpty()) StateMark(reason, StateKind.Info)
                            if (n.usedBy.isNotEmpty()) StateMark(t("dres.inUse") + ": " + usedByText(n.usedBy), StateKind.Ok)
                            else if (n.kind == "user") StateMark(t("dres.unused"), StateKind.Off)
                        }
                        if (n.kind == "user" && n.usedBy.isEmpty() && !n.protected) {
                            IconButton(TitanIcons.trash, t("dnet.remove") + " " + n.name, {
                                ui.confirm(Ask(ui.text("dnet.remove"), ui.text("dnet.removeText", "name" to n.name), ui.text("dres.delete")) {
                                    ui.run { api.removeNetwork(n.name); ui.toast(ui.text("dnet.removed", "name" to n.name)); reload++ }
                                })
                            })
                        }
                    }
                }
            }
        }
        // LAN-Netze für Container (eigene IP-Adresse im Heimnetz).
        Section(t("lan.title"))
        Text(t("lan.intro"), style = TitanType.body, color = Titan.colors.muted)
        lanError?.let { Problem(errorText(it)) }
        val l = lans
        if (l != null) {
            if (l.parents.isEmpty()) Note(t("lan.needParent"))
            SmallButton(t("lan.add"), TitanIcons.plus, { ui.sheet = { LanSheet(api, l.parents) { reload++ } } }, enabled = l.parents.isNotEmpty())
            if (l.networks.isEmpty()) Text(t("lan.empty"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 8.dp))
            else Card(Modifier.padding(top = 8.dp)) {
                l.networks.forEachIndexed { i, n ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(n.name, style = TitanType.rowTitle, color = Titan.colors.text)
                            Text((l.parents.firstOrNull { it.id == n.parent }?.name ?: n.parent) + " · " + n.driver, style = TitanType.rowSub, color = Titan.colors.muted)
                            Text(listOf(n.subnet, n.gateway, n.range).filter { it.isNotEmpty() }.joinToString(" · "), style = TitanType.rowSub, color = Titan.colors.muted)
                            if (n.hostAccess) Text(t("lan.hostAccess") + if (n.hostAddress.isNotEmpty()) ": " + n.hostAddress else "", style = TitanType.rowSub, color = Titan.colors.muted)
                            if (n.error.isNotEmpty()) Problem(n.error)
                        }
                        IconButton(TitanIcons.trash, t("dnet.remove") + " " + n.name, {
                            ui.confirm(Ask(ui.text("dnet.remove"), ui.text("lan.removeHint", "name" to n.name), ui.text("dres.delete")) {
                                ui.run { api.removeLan(n.name); reload++ }
                            })
                        })
                    }
                }
            }
        }
    }
}

private val SUBNET = Regex("^(\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$")

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BridgeSheet(api: DockerApi, done: () -> Unit) {
    val ui = LocalComputeUi.current
    var name by remember { mutableStateOf("") }
    var subnet by remember { mutableStateOf("") }
    var internal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val subnetOk = subnet.isBlank() || SUBNET.matches(subnet.trim())
    SheetTitle(t("dnet.new"))
    Field(t("dres.col.name"), name, { name = it })
    Field(t("dnet.subnetOptional"), subnet, { subnet = it }, hint = t("dnet.subnetHint"), error = if (!subnetOk) t("dnet.subnetBad") else null)
    SwitchRow(t("dnet.internal"), internal, { internal = it }, sub = t("dnet.internalLong"))
    error?.let { Problem(it) }
    SheetButtons(t("dnet.create"), enabled = name.isNotBlank() && subnetOk) {
        ui.run(onError = { error = ui.errorOf(it) }) {
            api.createBridge(name.trim(), subnet, internal)
            ui.toast(ui.text("dnet.created", "name" to name.trim()))
            ui.sheet = null
            done()
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LanSheet(api: DockerApi, parents: List<NasNetwork>, done: () -> Unit) {
    val ui = LocalComputeUi.current
    var lan by remember { mutableStateOf(ContainerLan(parent = parents.firstOrNull()?.id.orEmpty())) }
    var error by remember { mutableStateOf<String?>(null) }
    SheetTitle(t("lan.add"))
    Field(t("dres.col.name"), lan.name, { lan = lan.copy(name = it) })
    Choice(t("lan.parent"), parents.map { it.id to it.name.ifEmpty { it.id } }, lan.parent, { lan = lan.copy(parent = it) })
    Choice(t("lan.driver"), listOf("ipvlan" to "ipvlan", "macvlan" to "macvlan"), lan.driver, { lan = lan.copy(driver = it) }, hint = t("lan.driverHint"))
    Field(t("lan.subnet"), lan.subnet, { lan = lan.copy(subnet = it) }, hint = t("lan.autoHint"))
    Field(t("lan.range"), lan.range, { lan = lan.copy(range = it) }, hint = t("lan.rangeHint"))
    Field(stringResource(R.string.docker_gateway), lan.gateway, { lan = lan.copy(gateway = it) })
    SwitchRow(t("lan.hostAccess"), lan.hostAccess, { lan = lan.copy(hostAccess = it) }, sub = t("lan.hostHint"))
    if (lan.hostAccess) Field(t("lan.hostAddress"), lan.hostAddress, { lan = lan.copy(hostAddress = it) })
    error?.let { Problem(it) }
    SheetButtons(t("lan.add"), enabled = lan.name.isNotBlank() && lan.parent.isNotEmpty()) {
        ui.run(onError = { error = ui.errorOf(it); done() }) {
            api.createLan(lan)
            ui.sheet = null
            done()
        }
    }
}

/** Aufräumen (routes/docker/cleanup): Belegung und was frei werden kann, einzeln löschen. */
@Composable
internal fun CleanupPage(api: DockerApi) {
    val ui = LocalComputeUi.current
    var reload by remember { mutableIntStateOf(0) }
    var fresh by remember { mutableStateOf(false) }
    val (df, error) = rememberLoad(reload) { api.df(fresh) }
    val (images, _) = rememberLoad(reload) { api.images() }
    val (volumes, _) = rememberLoad(reload) { api.volumes().volumes }
    val (networks, _) = rememberLoad(reload) { api.networks().networks }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    ScrollPage {
        Text(t("dres.cleanup.intro"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 6.dp))
        error?.let { ErrorBox(errorText(it), { reload++ }) }
        val d = df ?: run { if (error == null) Loading(); return@ScrollPage }
        if (d.measuring) StateMark(t("dres.cleanup.measuring"), StateKind.Info)
        val parts = listOf("dres.cleanup.images" to d.images, "dres.cleanup.containers" to d.containers, "dres.cleanup.volumes" to d.volumes, "dres.cleanup.buildCache" to d.buildCache).filter { it.second != null }
        Card {
            parts.forEachIndexed { i, (key, p) ->
                if (i > 0) Divider()
                io.github.ra5on.titanos.compute.InfoRow(t(key), "${p!!.count} · ${Format.bytes(p.size)} · " + t("dres.cleanup.free", "size" to Format.bytes(p.reclaimable)))
            }
        }
        Text(t("dres.cleanup.total") + ": " + Format.bytes(parts.sumOf { it.second?.size ?: 0 }) + " · " + t("dres.cleanup.reclaimable") + ": " + Format.bytes(parts.sumOf { it.second?.reclaimable ?: 0 }), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 6.dp))
        SmallButton(t("dres.cleanup.recount"), TitanIcons.restart, { fresh = true; reload++ })
        message?.let { Note(it) }
        val imgs = images?.images.orEmpty()
        val dangling = imgs.filter { it.dangling && it.usedBy.isEmpty() && it.protected.isEmpty() }
        val unusedImages = imgs.filter { it.usedBy.isEmpty() && it.protected.isEmpty() }
        val anonymous = volumes.orEmpty().filter { it.usedBy.isEmpty() && !it.titanos && it.anonymous }
        val spareNets = networks.orEmpty().filter { it.kind == "user" && it.usedBy.isEmpty() && !it.protected }
        data class Row(val kind: String, val title: String, val hint: String, val count: Int, val size: Long, val path: String, val body: JsonObject, val askTitle: String, val askText: String)
        val rows = listOf(
            Row("dangling", "dres.cleanup.dangling", "dres.cleanup.danglingHint", dangling.size, dangling.sumOf { it.freed }, "/docker/images/prune", buildJsonObject { put("unused", JsonPrimitive(false)) }, "dres.cleanup.danglingTitle", "dres.cleanup.danglingText"),
            Row("images", "dres.cleanup.unusedImages", "dres.cleanup.unusedImagesHint", unusedImages.size, unusedImages.sumOf { it.freed }, "/docker/images/prune", buildJsonObject { put("unused", JsonPrimitive(true)) }, "dres.images.pruneTitle", "dres.images.pruneText"),
            Row("volumes", "dres.cleanup.unusedVolumes", "dres.cleanup.unusedVolumesHint", anonymous.size, anonymous.sumOf { maxOf(0, it.size) }, "/docker/volumes/prune", buildJsonObject { put("anonymousOnly", JsonPrimitive(true)) }, "dres.volumes.anonymousTitle", "dres.volumes.anonymousPrune"),
            Row("cache", "dres.cleanup.cache", "dres.cleanup.cacheHint", d.buildCache?.count ?: 0, d.buildCache?.reclaimable ?: 0, "/docker/builder/prune", JsonObject(emptyMap()), "dres.cleanup.cacheTitle", "dres.cleanup.cacheText"),
            Row("networks", "dnet.cleanup", "dnet.cleanupHint", spareNets.size, 0, "/docker/networks/prune", JsonObject(emptyMap()), "dnet.pruneTitle", "dnet.pruneText"),
        )
        Section(t("dres.cleanup.actions"))
        Card {
            rows.forEachIndexed { i, r ->
                if (i > 0) Divider()
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t(r.title), style = TitanType.rowTitle, color = Titan.colors.text)
                    Text(t(r.hint), style = TitanType.rowSub, color = Titan.colors.muted)
                    Text("${r.count} · ${Format.bytes(r.size)}", style = TitanType.rowSub, color = Titan.colors.muted)
                    if (busy == r.kind) StateMark(t("dres.cleanup.run"), StateKind.Busy)
                    else SmallButton(if (r.size > 0) t("dres.cleanup.runSize", "size" to Format.bytes(r.size)) else t("dres.cleanup.run"), TitanIcons.trash, {
                        ui.confirm(Ask(ui.text(r.askTitle), ui.text(r.askText, "count" to r.count, "size" to Format.bytes(r.size)), ui.text("dres.delete")) {
                            busy = r.kind
                            message = null
                            ui.run(onError = { e -> busy = null; message = ui.errorOf(e) }) {
                                val res = api.prune(r.path, r.body)
                                val n = res.count
                                message = when {
                                    r.kind == "networks" -> ui.text("dnet.pruned", "count" to (n ?: r.count))
                                    r.kind == "cache" || n == null -> ui.text("dres.cleanup.done", "size" to Format.bytes(if (res.reclaimed > 0) res.reclaimed else r.size))
                                    else -> ui.text("dres.cleanup.doneCount", "count" to n, "size" to Format.bytes(if (res.reclaimed > 0) res.reclaimed else r.size))
                                } + failedText(ui, res)
                                busy = null
                                reload++
                            }
                        })
                    }, danger = true, enabled = r.count > 0 && busy == null)
                }
            }
        }
    }
}

/** Katalog (routes/apps): Apps der Quellen, Kategorien, Suche, Quellen verwalten, installieren. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CatalogPage(state: DockerState, onInstalled: () -> Unit) {
    val ui = LocalComputeUi.current
    var reload by remember { mutableIntStateOf(0) }
    var store by remember { mutableStateOf<AppStore?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(reload) {
        try {
            store = state.api.store()
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("all") }
    var source by rememberSaveable { mutableStateOf("") }
    val lang = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    ScrollPage {
        error?.let { ErrorBox(errorText(it), { reload++ }) }
        val s = store ?: run { if (error == null) Loading(); return@ScrollPage }
        val installed = state.status?.apps.orEmpty()
        Text(
            if (s.sources.size == 1) t("apps.summaryOne", "apps" to s.apps.size) else t("apps.summary", "apps" to s.apps.size, "sources" to s.sources.size),
            style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 6.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(t("apps.refresh"), TitanIcons.sync, { ui.run { store = state.api.refreshStore() } })
            SmallButton(t("apps.sources"), TitanIcons.globe, { ui.sheet = { SourcesSheet(state, s) { store = it } } })
        }
        Field(t("apps.search"), search, { search = it })
        val categories = (listOf("all") + s.apps.map { it.category.ifEmpty { "other" } }.distinct().sorted())
        io.github.ra5on.titanos.compute.Chips(categories.map { it to tOr("apps.category.$it", it) }, category, { category = it }, Modifier.padding(vertical = 6.dp))
        if (s.sources.size > 1) Choice(t("apps.allSources"), listOf("" to t("apps.allSources")) + s.sources.map { it.id to it.name }, source, { source = it })
        val q = search.trim().lowercase()
        val shown = s.apps.filter { a ->
            (category == "all" || a.category.ifEmpty { "other" } == category) && (source.isEmpty() || a.source == source) &&
                (q.isEmpty() || (a.name + " " + a.image + " " + a.description.values.joinToString(" ")).lowercase().contains(q))
        }
        Text(t("apps.showing", "shown" to shown.size, "total" to s.apps.size), style = TitanType.small, color = Titan.colors.muted)
        if (shown.isEmpty()) Text(t("apps.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 12.dp))
        Card(Modifier.padding(top = 8.dp)) {
            shown.take(300).forEachIndexed { i, a ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (a.icon.startsWith("/")) NasImage(state.api.access, a.icon.removePrefix("/api/v1"), null, Modifier.size(40.dp)) else Avatar(a.name)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(a.name, style = TitanType.rowTitle, color = Titan.colors.text)
                        val desc = a.description[lang] ?: a.description["en"] ?: a.description[""] ?: a.description.values.firstOrNull().orEmpty()
                        if (desc.isNotEmpty()) Text(desc, style = TitanType.rowSub, color = Titan.colors.muted)
                        Text(listOf(a.image, s.sources.firstOrNull { it.id == a.source }?.name.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "), style = TitanType.rowSub, color = Titan.colors.muted)
                        if (a.id in installed) StateMark(t("apps.installed"), StateKind.Ok)
                    }
                    SmallButton(t("apps.install"), TitanIcons.download, { state.installing = InstallTarget(a.source, a.id, a.name) })
                }
            }
        }
        if (shown.size > 300) Text(t("apps.showMore", "count" to shown.size - 300), style = TitanType.small, color = Titan.colors.muted)
    }
}

@Composable
private fun tOr(key: String, fallback: String) = io.github.ra5on.titanos.compute.tOr(key, fallback)

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SourcesSheet(state: DockerState, store: AppStore, onStore: (AppStore) -> Unit) {
    val ui = LocalComputeUi.current
    var current by remember { mutableStateOf(store) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    SheetTitle(t("apps.sources"))
    Text(t("apps.sourcesNote"), style = TitanType.small, color = Titan.colors.muted)
    for (s in current.sources) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = TitanType.rowTitle, color = Titan.colors.text)
                Text(listOfNotNull(t("apps.count", "count" to s.count), tOr("apps.format.${s.format}", s.format).ifEmpty { null }, if (s.builtin) t("apps.builtin") else null).joinToString(" · "), style = TitanType.rowSub, color = Titan.colors.muted)
                if (s.error.isNotEmpty()) Problem(tOr("apps.sourceError.${s.error}", s.error))
            }
            if (!s.builtin) IconButton(TitanIcons.trash, t("apps.sourceRemove", "name" to s.name), {
                ui.confirm(Ask(ui.text("apps.sourceRemove", "name" to s.name), null, ui.text("apps.remove")) {
                    ui.run { current = state.api.removeSource(s.id); onStore(current) }
                })
            })
        }
    }
    Section(t("apps.sourceAdd"))
    Note(t("apps.sourceWarning"))
    Field(t("apps.sourceUrl"), url, { url = it }, hint = t("apps.sourceHint"))
    Field(t("apps.sourceName"), name, { name = it })
    error?.let { Problem(it) }
    SheetButtons(t("apps.sourceAdd"), enabled = url.startsWith("https://") || url.startsWith("http://")) {
        ui.run(onError = { error = ui.errorOf(it) }) {
            current = state.api.addSource(url.trim(), name.trim())
            onStore(current)
            url = ""
            name = ""
        }
    }
}
