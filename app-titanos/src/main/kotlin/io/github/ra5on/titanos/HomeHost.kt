package io.github.ra5on.titanos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.widget.Toast
import io.github.ra5on.titanos.home.DOCK_FILES
import io.github.ra5on.titanos.home.DOCK_HOME
import io.github.ra5on.titanos.home.DOCK_NOTIFICATIONS
import io.github.ra5on.titanos.home.DOCK_PHOTOS
import io.github.ra5on.titanos.home.DockChoice
import io.github.ra5on.titanos.home.HomeDockSettingsPage
import io.github.ra5on.titanos.home.HomeLayoutStore
import io.github.ra5on.titanos.home.HomeLocal
import io.github.ra5on.titanos.home.PhonePrefs
import io.github.ra5on.titanos.home.Program
import io.github.ra5on.titanos.home.programIdOf
import io.github.ra5on.titanos.home.programKey
import io.github.ra5on.titanos.home.programLabel
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.launch

/*
 * Startbildschirm, Dock, Seitenmenü und App-Einstellungen der Handy-Gestaltung
 * (PLAN 117, Entwürfe 02–07). Die Einrichtung liegt je NAS und Konto am NAS
 * (`androiddesktop.phone`, feature-home HomeLayoutStore).
 */

/** Die Ablage des Startbildschirms für das gewählte NAS und Konto. `/preferences` bleibt am gekoppelten NAS (ClusterRoute). */
@Composable
internal fun rememberHomeStore(graph: AppGraph): HomeLayoutStore {
    val context = LocalContext.current
    val user = graph.pairing?.user.orEmpty()
    val store = remember(graph.activeId, user, graph.nasEpoch) {
        HomeLayoutStore(graph.connection(), HomeLocal(context), graph.activeId + "/" + user)
    }
    LaunchedEffect(store) { store.load() }
    return store
}

/** Speichert und sagt es, wenn das NAS die Einrichtung nicht annahm. */
internal fun saveHome(context: android.content.Context, graph: AppGraph, store: HomeLayoutStore, next: PhonePrefs) {
    val failed = context.getString(io.github.ra5on.titanos.home.R.string.home_save_failed)
    graph.scope.launch {
        if (!store.save(next) && store.loaded) Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
    }
}

/** Strichsymbol eines Platzes im Dock (Programme mit dem Symbol ihres Bereichs). */
internal fun dockIcon(key: String): ImageVector = when (key) {
    DOCK_HOME -> TitanIcons.home
    DOCK_FILES -> TitanIcons.folder
    DOCK_PHOTOS -> TitanIcons.photo
    DOCK_NOTIFICATIONS -> TitanIcons.bell
    else -> when (programIdOf(key)) {
        "docker" -> TitanIcons.docker
        "vms", "monitor" -> TitanIcons.monitor
        "lxc", "plugins" -> TitanIcons.box
        "mail" -> TitanIcons.mail
        "calendar", "planner" -> TitanIcons.cal
        "contacts", "users", "account" -> TitanIcons.user
        "agent" -> TitanIcons.sparkle
        "storage" -> TitanIcons.hdd
        "shares" -> TitanIcons.share
        "backup" -> TitanIcons.archive
        "nvr" -> TitanIcons.video
        "terminal" -> TitanIcons.key
        "security" -> TitanIcons.shield
        "logs" -> TitanIcons.file
        "settings" -> TitanIcons.gear
        else -> TitanIcons.grid
    }
}

/** Alle möglichen Plätze im Dock: Start, Dateien, Fotos, Mitteilungen und die Programme, die das Konto öffnen darf. */
@Composable
internal fun dockChoices(programs: List<Program>): List<DockChoice> {
    val fixed = listOf(
        DockChoice(DOCK_HOME, stringResource(UiR.string.ui_nav_home), TitanIcons.home),
        DockChoice(DOCK_FILES, stringResource(UiR.string.ui_nav_files), TitanIcons.folder),
        DockChoice(DOCK_PHOTOS, stringResource(UiR.string.ui_nav_photos), TitanIcons.photo),
        DockChoice(DOCK_NOTIFICATIONS, stringResource(UiR.string.ui_nav_notifications), TitanIcons.bell),
    )
    return fixed + programs.filter { it.id != "files" && it.id != "photos" }.map { p ->
        DockChoice(programKey(p.id), programLabel(p.id), dockIcon(programKey(p.id)))
    }
}

/** „Startbildschirm und Dock“ mit der Ablage des gewählten NAS (auch als Fenster am Arbeitsplatz). */
@Composable
internal fun HomeDockSettingsHost(graph: AppGraph, store: HomeLayoutStore, programs: List<Program>, onEdit: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    HomeDockSettingsPage(
        prefs = store.own,
        choices = dockChoices(programs),
        onNas = store.onNas,
        onChange = { saveHome(context, graph, store, it) },
        onEdit = onEdit,
        onBack = onBack,
    )
}

/** Wie die Rolle heißt (Seitenmenü, Entwurf 05). */
@Composable
private fun roleName(role: String?): String = stringResource(
    when (role) {
        "owner" -> R.string.role_owner
        "admin" -> R.string.role_admin
        else -> R.string.role_user
    },
)

/**
 * Seitenmenü (Entwurf 05): Konto, die gekoppelten NAS (umschalten), die Knoten
 * des Clusters des gewählten NAS (wie der Umschalter im Web, ersetzt den
 * Knopf oben rechts), NAS hinzufügen, App-Einstellungen, Übertragungen, Über
 * die App. Zurück oder Tippen daneben schließt es.
 */
@Composable
internal fun BoxScope.SideMenu(
    graph: AppGraph,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onTransfers: () -> Unit,
    onAbout: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val c = Titan.colors
    val active = graph.activeProfile
    var nodes by remember { mutableStateOf<List<ClusterNode>>(emptyList()) }
    LaunchedEffect(graph.activeId, graph.nasEpoch) { nodes = clusterNodes(graph.connection()) }
    val closeLabel = stringResource(R.string.side_close)
    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = .45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .semantics { contentDescription = closeLabel },
    )
    BoxWithConstraints(Modifier.matchParentSize()) {
        val width = minOf(maxWidth * 0.86f, 340.dp)
        val shape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
        Column(
            Modifier.width(width).fillMaxHeight().clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            // Konto: Name und Rolle.
            Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AppIcon("account", "grey", 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(graph.pairing?.user.orEmpty(), style = TitanType.rowTitle, color = c.text)
                    Text(roleName(graph.sessions.user?.role), style = TitanType.rowSub, color = c.muted)
                }
            }
            MenuHeader(stringResource(R.string.side_nas))
            for (p in graph.profiles) {
                val chosen = p.id == active?.id
                MenuRow(
                    title = p.name,
                    sub = stringResource(R.string.nas_account, p.pairing.user, p.pairing.hostname.ifBlank { p.pairing.addresses.firstOrNull()?.server.orEmpty() }),
                    chosen = chosen,
                    icon = { AppIcon("storage", "dark", 36.dp) },
                    onClick = { onDismiss(); graph.switchTo(p.id) },
                )
            }
            if (nodes.isNotEmpty() && active != null) {
                MenuHeader(stringResource(R.string.side_cluster, active.name))
                val current = active.pairing.node
                for (n in nodes) {
                    val chosen = if (n.self) current.isEmpty() else current == n.id
                    val usable = n.self || n.online
                    val sub = listOfNotNull(
                        if (n.self) stringResource(R.string.side_node_self) else stringResource(R.string.side_node_via, active.name),
                        if (!usable) stringResource(R.string.nas_cluster_offline) else null,
                    ).joinToString(" · ")
                    MenuRow(
                        title = n.name.ifBlank { n.id },
                        sub = sub,
                        chosen = chosen,
                        icon = { Icon(TitanIcons.nodes, null, tint = if (usable) c.text else c.muted, modifier = Modifier.size(24.dp)) },
                        onClick = if (usable && !chosen) ({ onDismiss(); graph.setNode(if (n.self) "" else n.id, n.name) }) else null,
                    )
                }
            }
            Divider(Modifier.padding(vertical = 10.dp))
            MenuRow(stringResource(R.string.nas_add), null, false, { RowIcon(TitanIcons.plus, tint = c.text) }) { onDismiss(); graph.beginAdd() }
            MenuRow(stringResource(R.string.side_settings), null, false, { RowIcon(TitanIcons.gear, tint = c.text) }) { onDismiss(); onSettings() }
            val files = graph.files
            val transfersSub = files?.let {
                val running = it.transfers.running
                val finished = it.transfers.finished
                if (running == 0 && finished == 0) stringResource(R.string.side_transfers_none) else stringResource(R.string.side_transfers_sub, running, finished)
            }
            MenuRow(stringResource(R.string.side_transfers), transfersSub, false, { RowIcon(TitanIcons.download, tint = c.text) }) { onDismiss(); onTransfers() }
            MenuRow(stringResource(io.github.ra5on.titanos.home.R.string.home_menu_about), null, false, { RowIcon(TitanIcons.info, tint = c.text) }) { onDismiss(); onAbout() }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(text, style = TitanType.section, color = Titan.colors.muted, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp).semantics { heading() })
}

/** Zeile des Seitenmenüs; die gewählte hat Fläche und Haken (nie nur Farbe). */
@Composable
private fun MenuRow(title: String, sub: String?, chosen: Boolean, icon: @Composable () -> Unit, onClick: (() -> Unit)?) {
    val c = Titan.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp))
            .background(if (chosen) c.accent.copy(alpha = .16f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { selected = chosen }
            .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (chosen) TitanType.rowTitle else TitanType.rowTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = c.text)
            if (sub != null) Text(sub, style = TitanType.rowSub, color = c.muted)
        }
        if (chosen) Icon(TitanIcons.check, null, tint = c.text, modifier = Modifier.size(20.dp))
    }
}

/**
 * App-Einstellungen: was bis 1.00 im Menü „⋯“ der Startseite stand, dazu
 * „Startbildschirm und Dock“. Nichts davon ist nur über eine Geste erreichbar.
 */
@Composable
internal fun AppSettingsPage(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onNasList: () -> Unit,
    onPim: () -> Unit,
    onVpn: () -> Unit,
    onWorkspace: () -> Unit,
    onWatch: () -> Unit,
    onUnpair: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val c = Titan.colors
    Page(topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.appset_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } }) {
        Card(Modifier.padding(top = 16.dp)) {
            ListRow(stringResource(io.github.ra5on.titanos.home.R.string.hs_title), sub = stringResource(R.string.appset_home_sub), icon = { RowIcon(TitanIcons.grid) }, chevron = true, onClick = onHome)
            ListRow(stringResource(R.string.nas_title), sub = stringResource(R.string.nas_manage_sub), icon = { RowIcon(TitanIcons.nodes) }, chevron = true, divider = true, onClick = onNasList)
            ListRow(stringResource(io.github.ra5on.titanos.pim.R.string.sync_title), sub = stringResource(io.github.ra5on.titanos.pim.R.string.sync_menu_sub), icon = { RowIcon(TitanIcons.sync) }, chevron = true, divider = true, onClick = onPim)
            ListRow(stringResource(io.github.ra5on.titanos.vpn.R.string.vpn_title), sub = stringResource(io.github.ra5on.titanos.vpn.R.string.vpn_menu_sub), icon = { RowIcon(TitanIcons.shield) }, chevron = true, divider = true, onClick = onVpn)
            ListRow(stringResource(io.github.ra5on.titanos.workspace.R.string.ws_title), sub = stringResource(io.github.ra5on.titanos.workspace.R.string.ws_mode_hint), icon = { RowIcon(TitanIcons.grid) }, chevron = true, divider = true, onClick = onWorkspace)
            ListRow(stringResource(io.github.ra5on.titanos.pairing.R.string.pair_watch_title), sub = stringResource(R.string.appset_watch_sub), icon = { RowIcon(TitanIcons.monitor) }, chevron = true, divider = true, onClick = onWatch)
        }
        Card(Modifier.padding(top = 16.dp, bottom = 16.dp)) {
            ListRow(
                stringResource(io.github.ra5on.titanos.home.R.string.home_menu_unpair),
                sub = stringResource(io.github.ra5on.titanos.home.R.string.home_menu_unpair_text),
                icon = { RowIcon(TitanIcons.x, tint = c.dangerInk) },
                titleColor = c.dangerInk,
                onClick = onUnpair,
            )
        }
    }
}

/** Ob Android Animationen aus hat („Animationen entfernen“): dann wackelt nichts. */
internal fun reduceMotion(context: android.content.Context): Boolean =
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
