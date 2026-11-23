package io.github.ra5on.titanos.net

import io.github.ra5on.titanos.model.PhotoCheckRequest
import io.github.ra5on.titanos.model.PhotoItem
import io.github.ra5on.titanos.model.PhotoVerifyRequest
import io.github.ra5on.titanos.model.UploadStatus
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Foto-Sicherung mit gewähltem Cluster-Knoten (1.01, Meldung des Inhabers:
 * „gesichert“, im Ordner liegt aber nichts): Jede Anfrage der Sicherung geht
 * an das gekoppelte NAS selbst, keine wird auf `/cluster/nodes/<knoten>/api/…`
 * umgeschrieben. Vorher gingen Ordner und Upload an den Knoten, Prüfen und
 * Abschließen ans gekoppelte NAS.
 */
class PhotoBackupRouteTest {
    private val cert = HeldCertificate.Builder().commonName("titanos").addSubjectAlternativeName("nas.invalid").build()
    private lateinit var server: MockWebServer
    private lateinit var pairing: Pairing

    @Before
    fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
        // Wie in der App: gepinnte Adresse, im Web ein anderer Knoten gewählt.
        pairing = Pairing("zeus", "dev", listOf(PinnedAddress("localhost:${server.port}", Spki.of(cert.certificate))), "titanos", node = "nas2", nodeName = "NAS 2")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    private val sum = "a".repeat(64)
    private val upload = """{"id":"u1","offset":0,"size":4,"name":"IMG_1.jpg"}"""

    @Test
    fun everyCallOfTheBackupStaysOnThePairedNas() = runBlocking {
        val nas = NasConnection.toPairedNas(OkHttpClient(), pairing, null)
        val titan = TitanApi(nas)
        val photos = PhotosApi(nas)
        ok("""{"items":[]}""") // check
        ok("{}") // mkdir
        ok(upload) // uploadStart
        ok("""{"id":"u1","offset":4,"size":4}""") // uploadChunk
        ok(upload) // uploadStatus
        ok("[]") // uploads (nach einem Neustart des NAS)
        ok("""{"id":"u1","path":"Handy/IMG_1.jpg","sha256":"$sum"}""") // finish
        ok("") // uploadAbort
        ok("""{"items":[]}""") // verify
        photos.check(PhotoCheckRequest("share/Fotos", "Handy", listOf(PhotoItem(sum, 4, "IMG_1.jpg"))))
        photos.mkdir("share/Fotos", "Handy")
        val start: UploadStatus = titan.uploadStart("share/Fotos", "Handy", "IMG_1.jpg", 4)
        titan.uploadChunk(start.id, 0, ByteArray(4), 4)
        titan.uploadStatus(start.id)
        photos.uploads("share/Fotos", "Handy")
        photos.finish(start.id, sum)
        titan.uploadAbort(start.id)
        photos.verify(PhotoVerifyRequest(listOf(PhotoItem(sum, 4))))
        val paths = (1..9).map { server.takeRequest().url.encodedPath }
        assertEquals(
            listOf(
                "/api/v1/app/photos/check",
                "/api/v1/files/mkdir",
                "/api/v1/files/uploads",
                "/api/v1/files/uploads/u1",
                "/api/v1/files/uploads/u1",
                "/api/v1/files/uploads",
                "/api/v1/app/photos/uploads/u1/finish",
                "/api/v1/files/uploads/u1",
                "/api/v1/app/photos/verify",
            ),
            paths,
        )
    }

    /** Gegenprobe: die normale Verbindung (Dateimanager) geht mit gewähltem Knoten weiter an ihn. */
    @Test
    fun theFileManagerStillGoesToTheChosenNode() = runBlocking {
        val nas = NasConnection(Endpoint.forPairing(OkHttpClient(), pairing), null)
        ok("{}")
        PhotosApi(nas).mkdir("share/Fotos", "Handy")
        assertEquals("/api/v1/cluster/nodes/nas2/api/files/mkdir", server.takeRequest().url.encodedPath)
    }

    @Test
    fun withoutNodeKeepsEverythingElse() {
        val local = pairing.withoutNode()
        assertEquals("", local.node)
        assertEquals("", local.nodeName)
        assertEquals(pairing.copy(node = "", nodeName = ""), local)
        val plain = Pairing("zeus", "dev", emptyList())
        assertEquals(plain, plain.withoutNode())
    }
}
