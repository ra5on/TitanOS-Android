package io.github.ra5on.titanos.watch.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.github.ra5on.titanos.watch.WatchGraph
import io.github.ra5on.titanos.watch.pair.PairScreen

object Routes {
    const val HOME = "home"
    const val AGENT = "agent"
    const val APPROVALS = "approvals"
    const val SWITCH = "switch"
    const val CAMERAS = "cameras"
    const val CAMERA = "camera"
    const val NOTIFICATIONS = "notifications"
    const val SETTINGS = "settings"
}

/** Ungekoppelt die Kopplung, sonst die Seiten der Uhr (Wischen nach rechts geht zurück). */
@Composable
fun WatchRoot(graph: WatchGraph, start: String = Routes.HOME) {
    WatchTheme {
        AppScaffold {
            if (graph.pairing == null) {
                PairScreen(unpairedByNas = graph.unpairedByNas)
                return@AppScaffold
            }
            val nav = rememberSwipeDismissableNavController()
            SwipeDismissableNavHost(navController = nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { HomeRoute(onOpen = { nav.navigate(it) }) }
                composable(Routes.AGENT) { AgentRoute() }
                composable(Routes.APPROVALS) { ApprovalsRoute() }
                composable(Routes.SWITCH) { SwitchRoute() }
                composable(Routes.CAMERAS) { CamerasRoute(onCamera = { nav.navigate("${Routes.CAMERA}/$it") }) }
                composable("${Routes.CAMERA}/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    CameraRoute(it.arguments?.getString("id").orEmpty())
                }
                composable(Routes.NOTIFICATIONS) { NotificationsRoute() }
                composable(Routes.SETTINGS) { SettingsRoute() }
            }
            if (start != Routes.HOME) {
                androidx.compose.runtime.LaunchedEffect(start) { nav.navigate(start) }
            }
        }
    }
}
