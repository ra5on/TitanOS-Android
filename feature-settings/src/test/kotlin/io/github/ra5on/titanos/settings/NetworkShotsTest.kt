package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Die Seiten der Gruppe „Netzwerk“: je Seite und Reiter hell, dunkel, breit und mit 200 % Schrift. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [36])
class NetworkShotsTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        nas.on("GET network", NetworkFixtures.network)
        nas.on("GET plugins", NetworkFixtures.plugins)
        nas.on("GET plugins/tailscale", NetworkFixtures.tailscale)
        nas.on("POST network/diagnostics", NetworkFixtures.diagnostics)
        nas.start()
    }

    @After
    fun stop() {
        nas.stop()
        shots.assertClean()
    }

    private fun open(nav: SettingsNav, path: String, wait: String) {
        val before = nas.requests.size
        nav.openPath(path)
        shots.until { nas.requests.drop(before).any { it.second.startsWith(wait) } || nas.requests.any { it.second.startsWith(wait) } }
    }

    private fun network(dark: Boolean) {
        val nav = SettingsNav()
        shots.show(dark) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        open(nav, "/settings/network", "network")
        shots.capture("net-01-uebersicht", dark)
        open(nav, "/settings/network?tab=interfaces", "network")
        shots.capture("net-02-schnittstellen", dark)
        open(nav, "/settings/network?tab=diagnostics", "network")
        shots.capture("net-03-diagnose", dark)
        open(nav, "/settings/network?tab=remote", "plugins/tailscale")
        shots.capture("net-04-fernzugriff", dark)
    }

    private fun networkTrial(dark: Boolean) {
        nas.on("GET network", NetworkFixtures.networkPending)
        val nav = SettingsNav()
        shots.show(dark) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        open(nav, "/settings/network", "network")
        shots.capture("net-05-probe", dark)
    }

    @Test fun hell() = network(false)

    @Test fun hellProbe() = networkTrial(false)
}
