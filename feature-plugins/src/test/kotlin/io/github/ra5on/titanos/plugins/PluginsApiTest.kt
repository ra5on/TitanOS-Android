package io.github.ra5on.titanos.plugins

import io.github.ra5on.titanos.admin.testing.FakeNas
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.UUID

/** Die Anfragen der Plugin-Seite: Methode, Pfad und Körper wie das Web. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginsApiTest {
    private val nas = FakeNas().pluginFixtures().start()
    private val api = PluginsApi(nas.env().http)

    @After fun end() = nas.close()

    private fun last(): Pair<String, JsonObject?> {
        val s = nas.seen.last()
        return "${s.method} ${s.path}" to s.body.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun status() = runBlocking {
        val s = api.status()
        assertEquals(8, s.plugins.size)
        assertEquals("sourceReady", s.driverUpdate?.state)
        assertEquals(1, s.recommendations?.size)
        assertEquals("op1", api.progress().operations?.first()?.id)
    }

    @Test fun catalog() = runBlocking {
        api.refreshCatalog(true)
        assertEquals("POST plugins/catalog" to json("""{"force":true}"""), last())
    }

    @Test fun installOfficialDownloadWithoutConfirm() = runBlocking {
        api.install(InstallBody(name = "wangxun-nic", volume = "Data", activate = true))
        assertEquals("POST plugins" to json("""{"name":"wangxun-nic","volume":"Data","activate":true}"""), last())
    }

    @Test fun installFromShareSendsNameItself() = runBlocking {
        api.install(InstallBody(path = "/mnt/Data/x.plugin.json", volume = "Data", confirm = "x", activate = false))
        assertEquals("POST plugins" to json("""{"path":"/mnt/Data/x.plugin.json","volume":"Data","confirm":"x","activate":false}"""), last())
    }

    @Test fun communityFromSourceAndLink() = runBlocking {
        api.installExternal(ExternalBody(source = "s1", name = "community-fan", volume = "Data", confirm = "community-fan", activate = true))
        assertEquals("POST plugins/external" to json("""{"source":"s1","name":"community-fan","volume":"Data","confirm":"community-fan","activate":true}"""), last())
        api.inspect("https://example.org/x.plugin.json")
        assertEquals("POST plugins/external/inspect" to json("""{"url":"https://example.org/x.plugin.json"}"""), last())
        api.installExternal(ExternalBody(url = "https://example.org/x.plugin.json", volume = "Data", confirm = "linkplugin", activate = true))
        assertEquals("POST plugins/external" to json("""{"url":"https://example.org/x.plugin.json","volume":"Data","confirm":"linkplugin","activate":true}"""), last())
    }

    @Test fun updateEnableRemove() = runBlocking {
        api.update("werkzeug", "/mnt/Data/media/werkzeug.plugin.json")
        assertEquals("POST plugins/werkzeug/update" to json("""{"path":"/mnt/Data/media/werkzeug.plugin.json","confirm":"werkzeug"}"""), last())
        api.enable("netbird", false)
        assertEquals("POST plugins/netbird/disable", last().first)
        api.remove("intel-gpu-tools")
        assertEquals("DELETE plugins/intel-gpu-tools", last().first)
    }

    @Test fun sourcesAndRecommendations() = runBlocking {
        api.addSource("https://example.org/plugins.json")
        assertEquals("POST plugins/sources" to json("""{"url":"https://example.org/plugins.json"}"""), last())
        api.removeSource("s2")
        assertEquals("DELETE plugins/sources/s2", last().first)
        api.dismissRecommendation("wangxun-nic")
        assertEquals("POST plugins/recommendations/wangxun-nic/dismiss" to json("{}"), last())
        api.restoreRecommendations()
        assertEquals("DELETE plugins/recommendations/dismissed", last().first)
        api.dismissProgress("op1")
        assertEquals("DELETE plugins/progress/op1", last().first)
    }

    @Test fun nvidia() = runBlocking {
        api.driver(DriverBody("choose", version = "620.12.03"))
        assertEquals("POST plugins/nvidia/updates" to json("""{"action":"choose","version":"620.12.03"}"""), last())
        api.driver(DriverBody("select", sha256 = "cancel"))
        assertEquals("POST plugins/nvidia/updates" to json("""{"action":"select","sha256":"cancel"}"""), last())
        api.driver(DriverBody("automatic", automatic = false))
        assertEquals("POST plugins/nvidia/updates" to json("""{"action":"automatic","automatic":false}"""), last())
    }

    @Test fun cleanup() = runBlocking {
        val r = api.releases().releases
        api.cleanup(CleanupBody(r.filter { !it.protected }.map { CleanupItem(it.version, it.fingerprint) }))
        assertEquals("POST plugins/cleanup" to json("""{"selection":[{"version":"0.55.0","fingerprint":"old"}]}"""), last())
    }

    @Test fun tailscale() = runBlocking {
        api.tailscaleSave(TailscaleBody(listOf("192.168.10.0/24"), false, "100.64.0.2", true, false))
        assertEquals("PUT plugins/tailscale" to json("""{"routes":["192.168.10.0/24"],"advertiseExit":false,"exitNode":"100.64.0.2","acceptRoutes":true,"acceptDNS":false}"""), last())
        api.tailscaleControl("confirm")
        assertEquals("POST plugins/tailscale/control/confirm" to json("{}"), last())
        api.tailscaleHttps(false)
        assertEquals("PUT plugins/tailscale/https" to json("""{"enabled":false}"""), last())
    }

    @Test fun netbirdKeyOnlyInBody() = runBlocking {
        val key = UUID.randomUUID().toString()
        api.netbirdKey(key)
        assertEquals("PUT plugins/netbird/setup-key" to json("""{"setupKey":"$key"}"""), last())
        assertFalse(ShadowLog.getLogs().any { it.msg?.contains(key) == true })
        api.netbirdSave(NetbirdBody("https://nb.example.org", true, false, true))
        assertEquals("PUT plugins/netbird" to json("""{"managementUrl":"https://nb.example.org","acceptRoutes":true,"serveRoutes":false,"acceptDNS":true}"""), last())
        assertTrue(api.netbird().state == "NeedsLogin")
    }
}
