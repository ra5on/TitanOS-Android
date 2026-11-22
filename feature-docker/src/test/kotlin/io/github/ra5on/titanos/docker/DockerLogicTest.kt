package io.github.ra5on.titanos.docker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logik der App-Seite und der Ansicht wie lib/docker-layout.ts, lib/app-proxy.ts und lib/docker-opens-command.ts. */
class DockerLogicTest {
    private fun c(id: String, app: String, service: String, webui: String = "", ports: List<Port> = emptyList()) =
        DockerContainer(id = id, name = id, app = app, service = service, managed = true, state = "running", webui = webui, ports = ports)

    private val groups = groupsOf(listOf(c("a1", "immich", "server", webui = "2283"), c("a2", "immich", "db"), c("b", "jellyfin", "jellyfin"), c("c", "zigbee", "z")))

    @Test
    fun layoutSanitizeAndOrder() {
        val l = DockerLayout.sanitize(Json.parseToJsonElement("""{"view":"cards","sort":"x","size":"l","order":["app:zigbee","app:zigbee","bad","app:immich"],"details":false,"primaryServices":{"app:immich":"db","app:x":"bad name"}}"""))
        assertEquals("cards", l.view)
        assertEquals("own", l.sort)
        assertEquals("l", l.size)
        assertFalse(l.details)
        assertEquals(listOf("app:zigbee", "app:immich"), l.order)
        assertEquals(mapOf("app:immich" to "db"), l.primaryServices)
        assertEquals(listOf("zigbee", "immich", "jellyfin"), arrangedGroups(groups, l).map { it.name })
        assertEquals(listOf("immich", "jellyfin", "zigbee"), arrangedGroups(groups, l.copy(sort = "az")).map { it.name })
        // A–Z ändert die gespeicherte Reihenfolge nicht; Verschieben behält fehlende Einträge.
        assertEquals(listOf("app:gone", "app:jellyfin", "app:immich", "app:zigbee"), moveGroup(listOf("app:gone", "app:immich", "app:jellyfin"), groups, "app:jellyfin", "app:immich"))
        val back = DockerLayout.sanitize(l.toJson())
        assertEquals(l, back)
    }

    @Test
    fun primaryServiceAndLinks() {
        val immich = groups.first { it.name == "immich" }
        assertEquals("server", stackPrimary(immich, emptyMap())?.service)
        assertEquals("db", stackPrimary(immich, mapOf(immich.id to "db"))?.service)
        assertNull(stackPrimary(immich, mapOf(immich.id to "weg")))
        assertEquals(listOf("http://192.168.1.5:2283/"), webLinks(immich.members.first(), "192.168.1.5").map { it.url })
    }

    @Test
    fun proxyFormBodyAndProblems() {
        val f = proxyForm(null, suggestedProxyName("My_App!")).copy(port = 8080, aliases = "Foto, bilder", access = "networks", networks = "192.168.1.0/24\n10.0.0.1")
        assertEquals("my-app", f.name)
        val body = proxyBody(f)
        assertEquals(listOf("foto", "bilder"), body["aliases"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", body["localOnly"]!!.jsonPrimitive.content)
        assertEquals(2, body["networks"]!!.jsonArray.size)
        assertTrue(proxyProblems(f, emptyList(), "nas").isEmpty())
        val taken = proxyProblems(f, listOf(ProxyRoute(app = "other", name = "bilder")), "nas")
        assertEquals(listOf("proxy.nameTaken"), taken.map { it.first })
        assertEquals(listOf("proxy.networksInvalid"), proxyProblems(f.copy(networks = "300.1.1.1"), emptyList(), "").map { it.first })
        assertEquals(2283, suggestedProxyPort(groups.first().members))
        assertEquals(9000, suggestedProxyPort(listOf(c("x", "x", "x", ports = listOf(Port(53, 53, "udp"), Port(9000, 80, "tcp"))))))
        assertEquals("ready", tailnetStep(ProxyRoute(enabled = true, tailscale = true, tailnet = RouteTailnet("running")), TailnetCheck(reachable = true)))
        assertEquals("unreachable", tailnetStep(ProxyRoute(enabled = true, tailscale = true, tailnet = RouteTailnet("running")), null, failed = 12))
        assertEquals("off", tailnetStep(ProxyRoute(enabled = true), null))
    }

    @Test
    fun opensCommandAndShares() {
        assertEquals(listOf("bambu-studio", "--open", "{file} name"), splitCommand("""bambu-studio --open "{file} name""""))
        assertEquals("""a "b c" """"", joinCommand(listOf("a", "b c", "")))
        val o = AppOpens(mounts = listOf(OpensMount("/mnt/Data/Fotos", "/shares/Fotos", true), OpensMount("/x", "/data", false), OpensMount("/y", "/shares/a/b", false)))
        assertEquals(listOf(ShareMount("Fotos", true)), mountedShares(o))
    }
}
