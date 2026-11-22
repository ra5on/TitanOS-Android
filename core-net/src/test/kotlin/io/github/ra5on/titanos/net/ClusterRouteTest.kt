package io.github.ra5on.titanos.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Anfragen an einen anderen Knoten des Clusters wie das Web (lib/cluster-node.ts apiPath). */
class ClusterRouteTest {
    @Test
    fun pathsGoThroughThisNasToTheNode() {
        assertEquals("/api/v1/cluster/nodes/n%202/api/storage", ClusterRoute.route("/api/v1/storage", "n 2"))
        assertEquals("/api/v1/cluster/nodes/b/api/docker/containers/x/start", ClusterRoute.route("/api/v1/docker/containers/x/start", "b"))
        // Wie LOCAL im Web: Anmeldung, Konto, Cluster, Agent, Mail, Kalender, Kontakte.
        for (p in listOf("/api/v1/session", "/api/v1/account/devices", "/api/v1/cluster/nodes", "/api/v1/agent/chats", "/api/v1/mail/boxes", "/api/v1/calendar", "/api/v1/contacts/x", "/api/v1/preferences")) {
            assertNull(p, ClusterRoute.route(p, "b"))
        }
        // Die App-Routen (Kopplung, Sitzung, Mitteilungen, Fotos) bleiben immer hier.
        for (p in listOf("/api/v1/app/challenge", "/api/v1/app/session", "/api/v1/app/notifications", "/api/v1/app/photos/check")) {
            assertNull(p, ClusterRoute.route(p, "b"))
        }
        assertNull(ClusterRoute.route("/api/v1/storage", ""))
        // Ähnliche Namen gehören nicht dazu.
        assertEquals("/api/v1/cluster/nodes/b/api/accounts", ClusterRoute.route("/api/v1/accounts", "b"))
    }

    @Test
    fun connectionOfAPairingWithNodeRewritesRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            val pairing = Pairing("zeus", "d", emptyList(), node = "nas2")
            val client = OkHttpClient.Builder().addInterceptor(ClusterRoute(pairing.node)).build()
            val nas = NasConnection(listOf(Endpoint(server.url("/"), client)), null)
            nas.execute { Request.Builder().url(it.resolve("/api/v1/system?x=1")!!).build() }.close()
            nas.execute { Request.Builder().url(it.resolve("/api/v1/app/notifications")!!).build() }.close()
            assertEquals("/api/v1/cluster/nodes/nas2/api/system?x=1", server.takeRequest().target)
            assertEquals("/api/v1/app/notifications", server.takeRequest().target)
        }
    }
}
