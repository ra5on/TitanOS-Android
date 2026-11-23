package io.github.ra5on.titanos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.NasProfile
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.notify.NotifyRuntime
import io.github.ra5on.titanos.photos.PhotoScheduler
import io.github.ra5on.titanos.photos.SettingsStore
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request

/** Ein Knoten des Clusters, wie `GET /api/v1/cluster/nodes` ihn für den Umschalter liefert (web lib/cluster.svelte.ts SwitchNode). */
@Serializable
data class ClusterNode(
    val id: String,
    val name: String = "",
    val self: Boolean = false,
    val online: Boolean = false,
    val version: String = "",
    val compatible: Boolean = true,
)

@Serializable
private data class ClusterNodes(val nodes: List<ClusterNode> = emptyList(), val version: String = "", val manages: Boolean = false)

/**
 * Die Knoten zum Umschalten. Wie im Web: nur, wenn dieses NAS die anderen
 * verwalten darf (`manages`); sonst leer, ebenso außerhalb eines Clusters
 * oder bei einem NAS ohne Cluster.
 */
suspend fun clusterNodes(nas: NasConnection?): List<ClusterNode> = withContext(Dispatchers.IO) {
    nas ?: return@withContext emptyList()
    runCatching {
        val r = nas.execute { Request.Builder().url(it.newBuilder().encodedPath("/api/v1/cluster/nodes").build()).build() }.decode<ClusterNodes>()
        if (r.manages && r.nodes.size > 1) r.nodes else emptyList()
    }.getOrDefault(emptyList())
}

/** „konto auf nas“ für die Zeile eines NAS. */
@Composable
private fun accountLine(p: NasProfile): String =
    stringResource(R.string.nas_account, p.pairing.user, p.pairing.hostname.ifBlank { p.pairing.addresses.firstOrNull()?.server.orEmpty() })

/**
 * Umschalter oben auf der Startseite: alle gekoppelten NAS, die Knoten eines
 * Clusters (wie der Umschalter im Web), „NAS hinzufügen“ und die Verwaltung.
 */
@Composable
internal fun BoxScope.NasSwitcherSheet(graph: AppGraph, onDismiss: () -> Unit, onAdd: () -> Unit, onManage: () -> Unit) {
    val active = graph.activeProfile
    var nodes by remember { mutableStateOf<List<ClusterNode>>(emptyList()) }
    LaunchedEffect(graph.activeId, graph.nasEpoch) { nodes = clusterNodes(graph.connection()) }
    Sheet(onDismiss = onDismiss) {
        SectionHeader(stringResource(R.string.nas_title), top = 4.dp)
        Card(background = Titan.colors.bg) {
            graph.profiles.forEachIndexed { i, p ->
                val chosen = p.id == active?.id
                val sub = accountLine(p) + if (chosen) " · " + stringResource(R.string.nas_chosen) else ""
                ListRow(
                    p.name,
                    sub = sub,
                    icon = { RowIcon(if (chosen) TitanIcons.check else TitanIcons.nodes, tint = if (chosen) Titan.colors.accent else Titan.colors.muted) },
                    divider = i > 0,
                    onClick = {
                        onDismiss()
                        graph.switchTo(p.id)
                    },
                )
            }
        }
        if (nodes.isNotEmpty() && active != null) {
            SectionHeader(stringResource(R.string.nas_cluster, active.name))
            Card(background = Titan.colors.bg) {
                val current = active.pairing.node
                nodes.forEachIndexed { i, n ->
                    val chosen = if (n.self) current.isEmpty() else current == n.id
                    val usable = n.self || n.online
                    val sub = listOfNotNull(
                        if (n.self) stringResource(R.string.nas_cluster_self) else null,
                        if (!usable) stringResource(R.string.nas_cluster_offline) else null,
                        if (chosen) stringResource(R.string.nas_chosen) else null,
                    ).joinToString(" · ").ifEmpty { null }
                    ListRow(
                        n.name.ifBlank { n.id },
                        sub = sub,
                        icon = { RowIcon(if (chosen) TitanIcons.check else TitanIcons.hdd, tint = if (chosen) Titan.colors.accent else Titan.colors.muted) },
                        divider = i > 0,
                        onClick = if (usable) ({
                            onDismiss()
                            graph.setNode(if (n.self) "" else n.id, n.name)
                        }) else null,
                    )
                }
            }
        }
        Card(Modifier.padding(top = 12.dp), background = Titan.colors.bg) {
            ListRow(
                stringResource(R.string.nas_add),
                sub = stringResource(R.string.nas_add_sub),
                icon = { RowIcon(TitanIcons.plus) },
                chevron = true,
                onClick = { onDismiss(); onAdd() },
            )
            ListRow(
                stringResource(R.string.nas_manage),
                sub = stringResource(R.string.nas_manage_sub),
                icon = { RowIcon(TitanIcons.gear) },
                chevron = true,
                divider = true,
                onClick = { onDismiss(); onManage() },
            )
        }
    }
}

/**
 * Seite „NAS und Konten“: je NAS Name, Konto, Mitteilungen an/aus,
 * Foto-Sicherung an/aus (eigene Einstellungen je NAS), VPN und Kopplung aufheben.
 */
@Composable
internal fun NasListPage(graph: AppGraph, onBack: () -> Unit, onAdd: () -> Unit, onPhotos: () -> Unit) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf<NasProfile?>(null) }
    var newName by remember { mutableStateOf("") }
    var unpairing by remember { mutableStateOf<NasProfile?>(null) }
    // Schalter lesen ihre Ablage; nach einer Änderung neu lesen.
    var tick by remember { mutableIntStateOf(0) }
    val vpnOwner = remember(tick, graph.profiles) { graph.vpnOwner()?.id }
    val vpnAccess = remember(tick) { runCatching { io.github.ra5on.titanos.vpn.VpnRuntime.store.profile() != null }.getOrDefault(false) }
    val on = stringResource(R.string.nas_on)
    val off = stringResource(R.string.nas_off)
    Page(
        topBar = {
            Box(Modifier.statusBarsPadding()) {
                TopBar(stringResource(R.string.nas_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back), icon = { RowIcon(TitanIcons.nodes, tint = Titan.colors.accent) })
            }
        },
        overlay = {
            renaming?.let { p ->
                ConfirmDialog(
                    title = stringResource(R.string.nas_rename),
                    text = stringResource(R.string.nas_name_hint),
                    actions = listOf(
                        DialogAction(stringResource(R.string.nas_save)) { graph.rename(p.id, newName); renaming = null },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { renaming = null },
                    ),
                    onDismiss = { renaming = null },
                ) {
                    TextInput(newName, { newName = it.take(64) }, stringResource(R.string.nas_name), Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = p.pairing.hostname)
                }
            }
            unpairing?.let { p ->
                ConfirmDialog(
                    title = stringResource(R.string.nas_unpair),
                    text = stringResource(R.string.nas_unpair_text, p.name),
                    actions = listOf(
                        DialogAction(stringResource(R.string.app_unpair_do), danger = true) { unpairing = null; graph.forget(p.id) },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { unpairing = null },
                    ),
                    onDismiss = { unpairing = null },
                )
            }
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Note(stringResource(R.string.nas_intro), Modifier.padding(top = 12.dp))
            for (p in graph.profiles) {
                val chosen = p.id == graph.activeId
                SectionHeader(p.name)
                Card {
                    ListRow(
                        if (chosen) stringResource(R.string.nas_chosen) else stringResource(R.string.nas_choose),
                        sub = accountLine(p),
                        icon = { RowIcon(if (chosen) TitanIcons.check else TitanIcons.nodes, tint = if (chosen) Titan.colors.accent else Titan.colors.muted) },
                        onClick = if (chosen) null else ({ graph.switchTo(p.id); tick++ }),
                    )
                    ListRow(
                        stringResource(R.string.nas_name),
                        sub = p.name,
                        icon = { RowIcon(TitanIcons.edit) },
                        chevron = true,
                        divider = true,
                        onClick = { newName = p.label; renaming = p },
                    )
                    val notifyOn = remember(tick, p.id) { NotifyRuntime.nasOn(context, p.id) }
                    val notifyLabel = stringResource(R.string.nas_notify)
                    ListRow(
                        notifyLabel,
                        icon = { RowIcon(TitanIcons.bell) },
                        divider = true,
                        right = { Toggle(notifyOn, { NotifyRuntime.setNasOn(context, p.id, it); tick++ }, on, off, label = notifyLabel) },
                    )
                    val photo = remember(tick, p.id) { SettingsStore(context, p.id).load() }
                    val photoLabel = stringResource(R.string.nas_photos)
                    if (photo.hasTarget) {
                        ListRow(
                            photoLabel,
                            sub = stringResource(R.string.nas_photos_target, listOf(photo.rootName.ifBlank { photo.root.substringAfterLast('/') }, photo.dir).filter { it.isNotBlank() }.joinToString(" / ")),
                            icon = { RowIcon(TitanIcons.photo) },
                            divider = true,
                            right = {
                                Toggle(photo.enabled, { v ->
                                    SettingsStore(context, p.id).save(photo.copy(enabled = v))
                                    PhotoScheduler.arm(context, p.id, settingsChanged = true)
                                    if (p.id == graph.activeId) graph.photos?.refresh()
                                    tick++
                                }, on, off, label = photoLabel)
                            },
                        )
                    } else {
                        ListRow(
                            photoLabel,
                            sub = stringResource(R.string.nas_photos_set),
                            icon = { RowIcon(TitanIcons.photo) },
                            chevron = true,
                            divider = true,
                            onClick = { graph.switchTo(p.id); onPhotos() },
                        )
                    }
                    if (vpnAccess && vpnOwner == p.id) {
                        ListRow(
                            stringResource(R.string.nas_vpn),
                            sub = stringResource(R.string.nas_vpn_sub),
                            icon = { RowIcon(TitanIcons.shield) },
                            divider = true,
                        )
                    }
                    ListRow(
                        stringResource(R.string.nas_unpair),
                        icon = { RowIcon(TitanIcons.x, tint = Titan.colors.dangerInk) },
                        titleColor = Titan.colors.dangerInk,
                        divider = true,
                        onClick = { unpairing = p },
                    )
                }
            }
            Card(Modifier.padding(top = 18.dp)) {
                ListRow(
                    stringResource(R.string.nas_add),
                    sub = stringResource(R.string.nas_add_sub),
                    icon = { RowIcon(TitanIcons.plus) },
                    chevron = true,
                    onClick = onAdd,
                )
            }
        }
    }
}

/** Kurz für Bildtests: die Knotenliste aus JSON (wie vom NAS). */
internal fun clusterNodesOf(json: String): List<ClusterNode> =
    TitanJson.decodeFromString(ClusterNodes.serializer(), json).let { if (it.manages) it.nodes else emptyList() }
