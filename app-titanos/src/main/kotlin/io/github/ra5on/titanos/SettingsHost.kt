package io.github.ra5on.titanos

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.ra5on.titanos.account.AccountApp
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.settings.shell.AppLink
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanAppearance
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Systemsteuerung (`settings`) und Mein Konto (`account`) aus feature-settings/feature-account. */
internal val SETTINGS_PROGRAMS = setOf("settings", "account")

/** Rolle und Name der Sitzung; holt sie einmal, falls die Sitzung noch nicht da ist. */
@Composable
private fun rememberEnv(graph: AppGraph, onOpen: (String) -> Unit): SettingsEnv? {
    val pairing = graph.pairing ?: return null
    val nas = remember(pairing) { graph.connection() } ?: return null
    var user by remember(pairing) { mutableStateOf(graph.sessions.user) }
    LaunchedEffect(pairing) {
        if (user == null) user = runCatching { withContext(Dispatchers.IO) { graph.api?.publicSession()?.user } }.getOrNull() ?: graph.sessions.user
    }
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("") }
    return remember(pairing, user) {
        SettingsEnv(
            SettingsApi(nas), { user?.name ?: pairing.user }, { user?.role ?: "user" }, onOpen, version,
            // Cluster: „Verwalten“ wählt den Knoten wie der Umschalter von „NAS und Konten“ (AppGraph.setNode).
            switchNode = { id, name, then ->
                graph.setNode(id, name)
                if (then != null) onOpen(then)
            },
        )
    }
}

/**
 * Öffnet eine Adresse der Weboberfläche in der App: jedes Programm, das die
 * App hat, an seiner Stelle (Links.kt), sonst ein Hinweis.
 */
internal fun openWebPath(context: android.content.Context, graph: AppGraph, path: String, later: () -> Unit) =
    openLink(context, graph, path, later)

@Composable
internal fun SettingsProgram(graph: AppGraph, id: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val laterText = stringResource(R.string.app_later_toast)
    val env = rememberEnv(graph) { path -> openWebPath(context, graph, path) { Toast.makeText(context, laterText, Toast.LENGTH_SHORT).show() } } ?: return
    val link = graph.programLink
    when (id) {
        "settings" -> {
            val nav = remember(link) { SettingsNav(link?.takeIf { it.startsWith("/settings") }) }
            val links = listOf(
                AppLink(stringResource(R.string.set_link_photos), null, WebIcons["photo"]) { onClose(); graph.openPhotos = true },
                AppLink(stringResource(R.string.set_link_files), null, WebIcons["folder"]) { onClose(); graph.openTab = "files" },
                AppLink(stringResource(R.string.set_link_notify), null, WebIcons["bell"]) { onClose(); graph.openTab = "notifications" },
                AppLink(stringResource(io.github.ra5on.titanos.pim.R.string.sync_title), stringResource(io.github.ra5on.titanos.pim.R.string.sync_menu_sub), TitanIcons.sync) { graph.programLink = null; graph.openProgram = PIM_SYNC },
                AppLink(stringResource(io.github.ra5on.titanos.home.R.string.home_menu_about), null, TitanIcons.info) { onClose(); graph.openAbout = true },
            )
            SettingsApp(env, onClose, appLinks = links, nav = nav)
        }
        "account" -> AccountApp(env, onClose, start = link)
    }
}

/** Darstellung des Kontos (`/preferences` › appearance) holen und app-weit anwenden. */
@Composable
internal fun AppearanceSync(graph: AppGraph) {
    val context = LocalContext.current
    val pairing = graph.pairing ?: return
    LaunchedEffect(pairing) {
        val nas = graph.connection() ?: return@LaunchedEffect
        runCatching {
            val prefs = SettingsApi(nas).obj("preferences")
            val appearance = prefs["appearance"] as? kotlinx.serialization.json.JsonObject ?: return@runCatching
            TitanAppearance.takeAccount(context, org.json.JSONObject(appearance.toString()))
        }
    }
}

/**
 * „Beim Öffnen der App“ (Fingerabdruck-Liste): jedes Mal, wenn die App in den
 * Vordergrund kommt, erst nach Fingerabdruck oder Geräte-PIN weiter.
 */
@Composable
internal fun AppLock(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var unlocked by rememberSaveable { mutableStateOf(!Guard.enabled(context, GuardAction.AppOpen)) }
    LifecycleResumeEffect(Unit) {
        if (Guard.enabled(context, GuardAction.AppOpen) && !unlocked) {
            Guard.require(context, GuardAction.AppOpen) { unlocked = true }
        }
        onPauseOrDispose {
            // Wieder sperren, sobald die App in den Hintergrund geht.
            if (Guard.enabled(context, GuardAction.AppOpen)) unlocked = false
        }
    }
    if (unlocked) {
        content()
    } else {
        Box(Modifier.fillMaxSize().background(Titan.colors.bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(24.dp)) {
                EmptyState(stringResource(R.string.app_locked_title), stringResource(R.string.app_locked_text))
                TitanButton(stringResource(R.string.app_unlock), { Guard.require(context, GuardAction.AppOpen) { unlocked = true } }, kind = ButtonKind.Primary)
            }
        }
    }
}
