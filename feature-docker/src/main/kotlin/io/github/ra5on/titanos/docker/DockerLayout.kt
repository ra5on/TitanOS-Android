package io.github.ra5on.titanos.docker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ComputeUi
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.MenuEntry
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.rememberWidestWord
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.Collator

/**
 * Ansicht der Docker-Seite je Konto wie lib/docker-layout.ts: Liste oder
 * Karten, eigene Reihenfolge oder A–Z, Kartengröße, Details und der
 * Hauptdienst eines Stacks (Verknüpfungen). Gespeichert unter /preferences/docker.
 */
data class DockerLayout(
    val view: String = "list",
    val sort: String = "own",
    val size: String = "m",
    val order: List<String> = emptyList(),
    val details: Boolean = true,
    val primaryServices: Map<String, String> = emptyMap(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", 1)
        put("view", view)
        put("sort", sort)
        put("size", size)
        put("order", buildJsonArray { order.forEach { add(JsonPrimitive(it)) } })
        put("details", details)
        put("primaryServices", buildJsonObject { primaryServices.forEach { (k, v) -> put(k, v) } })
    }

    companion object {
        private val ID = Regex("^(app|container):[^\\x00-\\x1f\\x7f]{1,256}$")
        private val SERVICE = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")

        fun sanitize(value: JsonElement?): DockerLayout {
            val o = value as? JsonObject ?: return DockerLayout()
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return DockerLayout(
                view = if (str("view") == "cards") "cards" else "list",
                sort = if (str("sort") == "az") "az" else "own",
                size = str("size")?.takeIf { it == "s" || it == "l" } ?: "m",
                details = (o["details"] as? JsonPrimitive)?.booleanOrNull ?: true,
                order = runCatching { o["order"]!!.jsonArray.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { ID.matches(it) }.distinct().take(2000) }.getOrDefault(emptyList()),
                primaryServices = runCatching {
                    o["primaryServices"]!!.jsonObject.entries.take(2000).mapNotNull { (k, v) ->
                        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (ID.matches(k) && s != null && SERVICE.matches(s)) k to s else null
                    }.toMap()
                }.getOrDefault(emptyMap()),
            )
        }
    }
}

suspend fun DockerApi.layout(): DockerLayout = DockerLayout.sanitize(runCatching { access.get<JsonObject>("/preferences")["docker"] }.getOrNull())

/** Die Reihenfolge der Seite; A–Z ändert die gespeicherte Reihenfolge nie. */
fun arrangedGroups(groups: List<DockerGroup>, layout: DockerLayout, locale: java.util.Locale = java.util.Locale.getDefault()): List<DockerGroup> {
    if (layout.sort != "az") {
        val ranks = layout.order.withIndex().associate { it.value to it.index }
        return groups.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }
    }
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return groups.sortedWith { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
}

/** Verschiebt in der ganzen gespeicherten Reihenfolge; Einträge, die gerade fehlen, bleiben. */
fun moveGroup(order: List<String>, groups: List<DockerGroup>, from: String, to: String): List<String> {
    val ids = (order + groups.map { it.id }).distinct().toMutableList()
    if (from == to || from !in ids || to !in ids) return ids
    val target = ids.indexOf(to)
    ids.remove(from)
    ids.add(target, from)
    return ids
}

/** Der Hauptdienst eines Stacks: der gewählte (fehlt er, keiner), sonst der mit WebUI oder Adresse. */
fun stackPrimary(g: DockerGroup, services: Map<String, String>): DockerContainer? {
    services[g.id]?.let { chosen -> return g.members.firstOrNull { it.service == chosen } }
    return g.members.firstOrNull { it.webui.isNotBlank() } ?: g.members.firstOrNull { it.publicUrl.isNotBlank() }
        ?: g.members.firstOrNull { it.proxyUrl.isNotBlank() } ?: g.members.firstOrNull { it.tailnetUrl.isNotBlank() }
}

/** Die Adressen eines Containers, wie webLinks() im Web: WebUI, HTTPS-Name, Tailnet, öffentlich. */
data class WebLink(val text: String, val url: String)

fun webLinks(c: DockerContainer, host: String): List<WebLink> = buildList {
    io.github.ra5on.titanos.docker.compose.appURL(c.webui, host).takeIf { it.isNotEmpty() && c.running }?.let { add(WebLink("WebUI", it)) }
    io.github.ra5on.titanos.docker.compose.publicURL(c.proxyUrl).takeIf { it.isNotEmpty() }?.let { add(WebLink("HTTPS", it)) }
    io.github.ra5on.titanos.docker.compose.publicURL(c.tailnetUrl).takeIf { it.isNotEmpty() }?.let { add(WebLink("Tailnet", it)) }
    io.github.ra5on.titanos.docker.compose.publicURL(c.publicUrl).takeIf { it.isNotEmpty() }?.let { add(WebLink(it.substringAfter("://").substringBefore('/'), it)) }
}.distinctBy { it.url }

/** Menü „Seite anpassen“: Ansicht, Sortierung, Kartengröße, Details. */
internal fun layoutMenu(ui: ComputeUi, layout: DockerLayout, save: (DockerLayout) -> Unit) {
    val check = { on: Boolean -> if (on) TitanIcons.check else null }
    ui.menu(
        ui.text("docker.layout.edit"),
        listOf(
            MenuEntry(ui.text("docker.layout.list"), check(layout.view == "list")) { save(layout.copy(view = "list")) },
            MenuEntry(ui.text("docker.layout.cards"), check(layout.view == "cards")) { save(layout.copy(view = "cards")) },
            MenuEntry(ui.text("docker.layout.sortOwn"), check(layout.sort == "own")) { save(layout.copy(sort = "own")) },
            MenuEntry(ui.text("docker.layout.sortAz"), check(layout.sort == "az")) { save(layout.copy(sort = "az")) },
            MenuEntry(ui.text("docker.layout.size") + ": " + ui.text("docker.layout.sizeS"), check(layout.size == "s")) { save(layout.copy(size = "s")) },
            MenuEntry(ui.text("docker.layout.size") + ": " + ui.text("docker.layout.sizeM"), check(layout.size == "m")) { save(layout.copy(size = "m")) },
            MenuEntry(ui.text("docker.layout.size") + ": " + ui.text("docker.layout.sizeL"), check(layout.size == "l")) { save(layout.copy(size = "l")) },
            MenuEntry(ui.text("docker.layout.details"), check(layout.details)) { save(layout.copy(details = !layout.details)) },
        ),
    )
}

/** Einträge zum Verschieben in der eigenen Reihenfolge (statt Ziehen am Griff; auch für TalkBack). */
internal fun moveEntries(ui: ComputeUi, g: DockerGroup, shown: List<DockerGroup>, layout: DockerLayout, save: (DockerLayout) -> Unit): List<MenuEntry> {
    if (layout.sort != "own") return emptyList()
    val i = shown.indexOfFirst { it.id == g.id }
    return listOfNotNull(
        if (i > 0) MenuEntry(ui.text("docker.layout.before", "name" to g.name), null) { save(layout.copy(order = moveGroup(layout.order, shown, g.id, shown[i - 1].id))) } else null,
        if (i in 0 until shown.size - 1) MenuEntry(ui.text("docker.layout.after", "name" to g.name), null) { save(layout.copy(order = moveGroup(layout.order, shown, g.id, shown[i + 1].id))) } else null,
    )
}

/**
 * Kartenansicht: alle Karten gleich groß, so viele nebeneinander wie die
 * Größe und das längste Wort der Namen erlauben (nie mitten im Wort trennen).
 */
@Composable
internal fun GroupCards(env: ComputeEnv, state: DockerState, groups: List<DockerGroup>, layout: DockerLayout, menu: (DockerGroup) -> List<MenuEntry>, onOpen: (DockerGroup) -> Unit) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val widest = rememberWidestWord(groups.map { it.name }, TitanType.rowTitle)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val min = when (layout.size) { "s" -> 150.dp; "l" -> 300.dp; else -> 210.dp }
        val gap = 10.dp
        val word = with(density) { widest.toDp() } + 28.dp + 48.dp
        val cardMin = maxOf(min, word)
        val columns = ((maxWidth + gap) / (cardMin + gap)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in groups.chunked(columns)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (g in row) {
                        val actions = t("docker.actionsFor", "name" to g.name)
                        val entries = menu(g)
                        Column(
                            Modifier.weight(1f).heightIn(min = 120.dp).clip(RoundedCornerShape(16.dp)).background(Titan.colors.surface)
                                .border(1.dp, Titan.colors.border, RoundedCornerShape(16.dp))
                                .onSecondaryClick { ui.menu(actions, entries) }
                                .clickable(role = Role.Button) { onOpen(g) }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Avatar(g.name)
                                Text(g.name, style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.weight(1f))
                            }
                            stateMark(g)
                            if (layout.details) {
                                val image = g.members.firstOrNull()?.image.orEmpty()
                                if (image.isNotEmpty()) Text(image, style = TitanType.rowSub, color = Titan.colors.muted)
                                if (g.on) Text("${t("docker.cpu")} ${pct(g.cpu)} · ${t("docker.ram")} ${Format.bytes(g.ram)}", style = TitanType.rowSub, color = Titan.colors.muted)
                            }
                            Spacer(Modifier.weight(1f, fill = false))
                            val primary = stackPrimary(g, layout.primaryServices)
                            val link = primary?.let { webLinks(it, nasHost(env)).firstOrNull() }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (link != null) io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.globe, t("docker.webui") + " " + g.name, { openUrl(context, link.url) })
                                Spacer(Modifier.weight(1f))
                                io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.more, actions, { ui.menu(actions, entries) })
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Hauptdienst eines Stacks wählen (Verknüpfungen am Stack). */
@Composable
internal fun ColumnScope.StackLinksSheet(state: DockerState, g: DockerGroup, layout: DockerLayout, save: suspend (DockerLayout) -> Unit) {
    val ui = LocalComputeUi.current
    var chosen by remember { mutableStateOf(layout.primaryServices[g.id].orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    SheetTitle(t("docker.stackLinksTitle", "name" to g.name))
    Text(t("docker.stackLinksHint"), style = TitanType.rowSub, color = Titan.colors.muted)
    val services = g.members.map { it.service.ifEmpty { it.name } }.distinct()
    Choice(t("docker.primaryService"), listOf("" to t("docker.primaryAuto")) + services.map { s ->
        val c = g.members.first { it.service.ifEmpty { it.name } == s }
        s to if (c.webui.isBlank() && c.publicUrl.isBlank() && c.proxyUrl.isBlank() && c.tailnetUrl.isBlank()) "$s · ${ui.text("docker.primaryUnavailable")}" else s
    }, chosen, { chosen = it })
    error?.let { io.github.ra5on.titanos.ui.components.Problem(it) }
    SheetButtons(t("docker.layout.save"), enabled = !busy) {
        busy = true
        ui.run(onError = { e -> error = ui.errorOf(e); busy = false }) {
            val map = layout.primaryServices.toMutableMap()
            if (chosen.isNotEmpty()) map[g.id] = chosen else map.remove(g.id)
            save(layout.copy(primaryServices = map))
            ui.sheet = null
        }
    }
}
