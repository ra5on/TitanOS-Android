package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Antworten für WireGuard, DDNS, Let's Encrypt, HTTPS-Namen und Firewall (Beispieldaten, Geheimnisse nur als „gesetzt“). */
object NetworkPagesFixtures {
    const val wireguard = """{"enabled":true,"running":true,"disabled":false,"port":51820,"endpoint":"titan.example.org","dns":"","network":"10.8.0.0/24","fullTunnel":false,"error":"",
      "peers":[{"name":"Laptop Alex","publicKey":"pk1","address":"10.8.0.2/32","lastHandshake":"2026-11-22T09:00:00Z","received":120000000,"sent":4500000000},
               {"name":"Pixel 9","publicKey":"pk2","address":"10.8.0.3/32","account":"alex","active":true,"lastHandshake":"","received":0,"sent":0},
               {"name":"Tablet Kim","publicKey":"pk3","address":"10.8.0.4/32","account":"kim","active":false}]}"""
    const val letsencrypt = """{"disabled":false,"running":false,"phase":"done","failures":0,"lastSuccess":"2026-11-01T03:00:00Z","renewAt":"2027-01-01T03:00:00Z","nextAttempt":"","lastAttempt":"2026-11-01T03:00:00Z","lastError":"",
      "certificateStaging":false,"firewall":true,"httpPort":true,"internetHttp":false,"appDomain":"apps.example.org","secrets":{"token":true},
      "settings":{"enabled":true,"email":"","domains":["titan.example.org"],"challenge":"dns","staging":false,"apps":true,"propagation":120,"directory":"","provider":{"type":"cloudflare"}}}"""
    const val ddns = """{"disabled":false,"running":true,"lastCheck":"2026-11-22T09:55:00Z","lastUpdate":"2026-11-20T08:00:00Z","nextCheck":"2026-11-22T10:00:00Z","lastError":"","secrets":{"token":true},
      "settings":{"enabled":true,"interval":5,"hostnames":["titan.example.org"],"interface":"","lookupURL":"","provider":{"type":"cloudflare"}},"heartbeat":{}}"""
    const val proxy = """{"off":false,"auto":true,"domain":"example.org","localName":"titan","defaultLocalName":"titan","suffixes":[".example.org",".local"],
      "apps":[{"app":"immich","enabled":true,"urls":["https://immich.example.org"],"localOnly":true,"networks":[],"scheme":"http","verifyTLS":true}],"targets":[]}"""
    const val firewall = """{"active":true,"error":"","dockerError":"","clientAddress":"192.168.178.30","clientZone":"home","homeNetworks":["192.168.178.0/24"],"vpnNetworks":["10.8.0.0/24"],
      "services":[{"id":"web","name":"Weboberfläche","ports":[{"proto":"tcp","ports":"443"}]},{"id":"smb","name":"SMB","ports":[{"proto":"tcp","ports":"445"}]}],
      "apps":[],"blocked":[],
      "config":{"zones":[{"id":"home","name":"Heimnetz","builtin":true,"networks":["192.168.178.0/24"]},{"id":"internet","name":"Internet","builtin":true,"networks":[]}],"policies":[],"autoblock":{"enabled":true}}}"""
}

/** WireGuard (mit App-Zugängen ab TitanOS 1.19), DDNS, Let's Encrypt, HTTPS-Namen und Firewall; hell, dunkel, 200 %, breit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h1400dp-xhdpi", sdk = [36])
class NetworkPagesShotsTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        nas.on("GET network/wireguard", NetworkPagesFixtures.wireguard)
        nas.on("GET system/letsencrypt", NetworkPagesFixtures.letsencrypt)
        nas.on("GET network/ddns", NetworkPagesFixtures.ddns)
        nas.on("GET docker/proxy", NetworkPagesFixtures.proxy)
        nas.on("GET firewall", NetworkPagesFixtures.firewall)
        nas.on("GET network", NetworkFixtures.network)
        nas.start()
    }

    @After
    fun stop() {
        nas.stop()
        shots.assertClean()
    }

    private fun all(dark: Boolean) {
        val nav = SettingsNav()
        shots.show(dark) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        for ((page, wait) in listOf("wireguard" to "network/wireguard", "ddns" to "network/ddns", "letsencrypt" to "system/letsencrypt", "proxy" to "docker/proxy", "firewall" to "firewall")) {
            val before = nas.requests.size
            nav.openPage(page)
            shots.until { nas.requests.drop(before).any { it.second.substringBefore('?') == wait } }
            shots.settle(1200)
            shots.capture("netpages-$page", dark)
        }
    }

    @Test fun hell() = all(false)
    @Test fun dunkel() = all(true)
    @Test @Config(qualifiers = "de-w1280dp-h1400dp-xhdpi") fun breit() { shots.prefix = "breit-"; all(true) }
    @Test fun schrift200() { org.robolectric.RuntimeEnvironment.setFontScale(2f); shots.prefix = "schrift200-"; all(false) }

    @Test
    fun appPeersShowTheirAccount() {
        val nav = SettingsNav()
        shots.show(false) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        nav.openPage("wireguard")
        shots.until { nas.requests.any { it.second == "network/wireguard" } }
        shots.settle(1200)
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Android-App von alex", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Android-App von kim, gerade nicht im Tunnel", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }
}
