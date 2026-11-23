package io.github.ra5on.titanos.docker

import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.net.Endpoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DockerApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DockerApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = DockerApi(ComputeAccess(listOf(Endpoint(server.url("/"), OkHttpClient())), null))
    }

    @After
    fun tearDown() = server.close()

    private fun ok(body: String = "") = server.enqueue(MockResponse.Builder().body(body).build())

    @Test
    fun statusAndGroups() = runBlocking {
        ok(
            """{"settings":{"enabled":true,"volume":"Data"},"running":true,"version":"27","apps":["immich"],"jobs":{"immich":{"state":"failed","output":"x"}},
            "containers":[
              {"id":"a","name":"immich-server","image":"immich:1","state":"running","app":"immich","service":"server","managed":true,"cpuPercent":4.1,"memoryBytes":1000,"ports":[{"host":2283,"container":2283,"protocol":"tcp"}],"webui":"2283","updateAvailable":true},
              {"id":"b","name":"immich-db","image":"pg","state":"exited","app":"immich","service":"db","managed":true},
              {"id":"c","name":"vaultwarden","image":"vw","state":"exited","ports":[{"host":8081,"container":80,"protocol":"udp"}]}
            ]}""",
        )
        val s = api.status()
        val groups = groupsOf(s.containers, s.jobs)
        assertEquals(listOf("immich", "vaultwarden"), groups.map { it.name })
        assertEquals(RowState.Partial, groups[0].state)
        assertTrue(groups[0].update)
        assertEquals("immich", groups[0].app)
        assertEquals(listOf("8081/udp"), groups[1].ports)
        assertEquals(RowState.Stopped, groups[1].state)
        assertEquals("immich · db", s.containers[1].label("distinct"))
        assertEquals("http://nas.local:2283", appUrl("2283", "nas.local"))
        assertEquals("http://[fd00::1]:8080/x", appUrl("8080/x", "fd00::1"))
        assertEquals("https://nas.local:8443", appUrl("https://[host]:8443", "nas.local"))
        assertEquals("/api/v1/docker", server.takeRequest().url.encodedPath)
    }

    @Test
    fun actionsUseTheWebRoutes() = runBlocking {
        repeat(6) { ok() }
        api.containerAction("immich-server", "restart")
        api.removeApp("my app", deleteData = true)
        api.removeContainer("c1")
        api.importApp("whoami", "services: {}")
        api.saveSettings(true, "Data")
        api.updateApp("immich")
        val r1 = server.takeRequest()
        assertEquals("POST /api/v1/docker/containers/immich-server/restart", "${r1.method} ${r1.url.encodedPath}")
        val r2 = server.takeRequest()
        assertEquals("DELETE", r2.method)
        assertEquals("/api/v1/docker/apps/my%20app", r2.url.encodedPath)
        assertEquals("my app", r2.url.queryParameter("deleteData"))
        assertEquals("DELETE", server.takeRequest().method)
        val r4 = server.takeRequest()
        assertEquals("""{"name":"whoami","compose":"services: {}"}""", r4.body?.utf8())
        val r5 = server.takeRequest()
        assertEquals("PUT /api/v1/docker/settings", "${r5.method} ${r5.url.encodedPath}")
        assertEquals("""{"enabled":true,"volume":"Data"}""", r5.body?.utf8())
        assertEquals("/api/v1/docker/apps/immich/update", server.takeRequest().url.encodedPath)
    }

    @Test
    fun pruneAndResources() = runBlocking {
        ok("""{"deleted":["a","b"],"reclaimed":2048,"failed":[{"id":"c","error":"busy"}]}""")
        val r = api.prune("/docker/images/prune", JsonObject(mapOf("unused" to JsonPrimitive(true))))
        assertEquals(2, r.count)
        assertEquals(2048, r.reclaimed)
        assertEquals("""{"unused":true}""", server.takeRequest().body?.utf8())
        ok("""{"images":[{"id":"sha256:1","repoTags":["<none>:<none>"],"size":100,"sharedSize":40,"usedBy":[]}],"totals":{"count":1,"size":100}}""")
        val images = api.images()
        assertEquals(60, images.images[0].freed)
        assertTrue(images.images[0].tags.isEmpty())
        ok("""{"volumes":[{"name":"v","anonymous":true}]}""")
        assertEquals(-1, api.volumes().volumes[0].size)
        ok("""{"images":{"count":3,"size":10,"reclaimable":5},"measuring":true}""")
        val df = api.df(fresh = true)
        assertTrue(df.measuring)
        server.takeRequest(); server.takeRequest()
        assertEquals("fresh=1", server.takeRequest().url.encodedQuery)
        ok("""{"settings":{"apps":["immich"],"weekdays":[1,3],"time":"03:30"},"results":{"immich":{"at":"2026-11-20T03:30:00Z","outcome":"updated"}}}""")
        assertEquals("03:30", api.autoUpdate().settings.time)
    }

    @Test
    fun controlsStartOnlyStopped() = runBlocking {
        val status = """{"containers":[{"id":"a","name":"x-1","app":"x","managed":true,"state":"running"},{"id":"b","name":"x-2","app":"x","managed":true,"state":"exited"}]}"""
        ok(status); ok(status); ok()
        val controls = DockerControls(api.access)
        val items = controls.items()
        assertEquals(1, items.size)
        assertEquals("app:x", items[0].id)
        controls.start("app:x")
        server.takeRequest(); server.takeRequest()
        assertEquals("/api/v1/docker/containers/b/start", server.takeRequest().url.encodedPath)
    }
}
