package io.github.ra5on.titanos.vpn

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/** Beispieldaten nur für die Bildtests (keine echten Schlüssel: nur öffentliche Platzhalter). */
internal object VpnFixtures {
    val profile = VpnProfile(
        peerName = "Pixel 10 Pro XL von Alex", address = "10.253.0.3/32", created = "2026-11-21T19:42:00Z",
        serverKey = "c2VydmVyLXB1YmxpYy1rZXktcGxhY2Vob2xkZXI=", port = 51820, network = "10.253.0.0/24", serverAddress = "10.253.0.1",
        endpoints = listOf("nas.beispiel.example", "titan.dyndns.example"), homeNetworks = listOf("192.168.178.0/24"),
        nasAddresses = listOf("192.168.178.20"), dns = listOf("192.168.178.1"), nasServer = "10.253.0.1:443", nasSpki = "spki",
    )
    val settings = VpnSettings(homeWifis = listOf("Zuhause 5G", "FRITZ!Box 7590 Gast"))
    val perms = VpnPerms(location = true, backgroundLocation = false, locationOn = true, vpnAllowed = true, batteryExempt = false, notifications = true)
    val on = VpnLive(
        status = TunnelStatus.On, why = Why.Away, sinceMs = 1_763_750_000_000, rx = 48_300_000, tx = 3_100_000, handshakeMs = 1_763_753_500_000,
        net = NetState(connected = true, onWifi = false, ssid = null), hasAccess = true,
    )
    val home = VpnLive(status = TunnelStatus.Off, why = Why.AtHome, net = NetState(connected = true, onWifi = true, ssid = "Zuhause 5G"), hasAccess = true)
    val answer = VpnAnswer(available = true, mayCreate = true, peer = VpnPeerJson(name = profile.peerName, address = profile.address, active = true))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h2400dp-xhdpi")
class VpnScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before
    fun german() {
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    private fun shot(name: String, dark: Boolean, ui: VpnUi, expanded: Boolean = true) {
        rule.setContent {
            TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { VpnScreen(ui, VpnActions(), expanded = expanded) } }
        }
        rule.waitForIdle()
        wholeWords()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png")
    }

    /**
     * Kein Text wird mitten im Wort getrennt (Inhaber: „Ganze Wörter“): jede
     * Zeile endet vor einem Leerzeichen, einem Bindestrich oder am Textende.
     */
    private fun wholeWords() {
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        val broken = mutableListOf<String>()
        for (node in nodes) {
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            for (layout in results) {
                val text = layout.layoutInput.text.text
                for (line in 0 until layout.lineCount - 1) {
                    val end = layout.getLineEnd(line)
                    if (end <= 0 || end >= text.length) continue
                    val before = text[end - 1]
                    val after = text[end]
                    if (!before.isWhitespace() && before != '-' && before != '/' && !after.isWhitespace()) broken += "„$text“ nach „${text.substring(0, end)}“"
                }
            }
        }
        assertTrue("mitten im Wort getrennt: $broken", broken.isEmpty())
    }

    private val connected = VpnUi(live = VpnFixtures.on, settings = VpnFixtures.settings, profile = VpnFixtures.profile, answer = VpnFixtures.answer, perms = VpnFixtures.perms)
    private val atHome = connected.copy(live = VpnFixtures.home, currentSsid = "Zuhause 5G")
    private val setup = VpnUi(live = VpnLive(), answer = VpnAnswer(available = true, mayCreate = true), perms = VpnPerms())
    private val off = VpnUi(live = VpnLive(), answer = VpnAnswer(available = false, reason = VpnReason.OFF, mayCreate = true))
    private val noRights = VpnUi(live = VpnLive(), answer = VpnAnswer(available = false, reason = VpnReason.ADMIN_ONLY))

    @Test fun connectedLight() = shot("vpn-verbunden", false, connected)
    @Test fun connectedDark() = shot("vpn-verbunden", true, connected)
    @Test fun atHomeLight() = shot("vpn-zuhause", false, atHome.copy(perms = VpnPerms()), expanded = false)
    @Test fun atHomeDark() = shot("vpn-zuhause", true, atHome.copy(perms = VpnPerms()), expanded = false)
    @Test fun setupLight() = shot("vpn-einrichten", false, setup)
    @Test fun setupDark() = shot("vpn-einrichten", true, setup)
    @Test fun offLight() = shot("vpn-wireguard-aus", false, off)
    @Test fun offDark() = shot("vpn-wireguard-aus", true, off)
    @Test fun noRightsLight() = shot("vpn-ohne-recht", false, noRights)
    @Test fun problemDark() = shot("vpn-problem", true, connected.copy(live = VpnLive(why = Why.Away, problem = VpnProblem.Background, hasAccess = true)))

    @Test @Config(qualifiers = "de-w390dp-h3600dp-xhdpi", fontScale = 2.0f) fun largeFontLight() = shot("vpn-schrift-200", false, connected)
    @Test @Config(qualifiers = "de-w390dp-h3600dp-xhdpi", fontScale = 2.0f) fun largeFontDark() = shot("vpn-schrift-200", true, connected)
    @Test @Config(qualifiers = "de-w390dp-h4200dp-xhdpi", fontScale = 2.0f) fun largeFontSetup() = shot("vpn-einrichten-schrift-200", false, setup)
    @Test @Config(qualifiers = "de-w840dp-h1600dp-mdpi") fun splitLight() = shot("vpn-split", false, connected)
    @Test @Config(qualifiers = "de-w1280dp-h1400dp-mdpi") fun wideLight() = shot("vpn-breit", false, connected)
    @Test @Config(qualifiers = "de-w1280dp-h1400dp-mdpi") fun wideDark() = shot("vpn-breit", true, connected)
    @Test @Config(qualifiers = "en-w390dp-h2400dp-xhdpi") fun englishLight() = shot("vpn-englisch", false, connected)
    @Test @Config(qualifiers = "en-w390dp-h3600dp-xhdpi", fontScale = 2.0f) fun englishLargeFont() = shot("vpn-englisch-schrift-200", true, connected)
}
