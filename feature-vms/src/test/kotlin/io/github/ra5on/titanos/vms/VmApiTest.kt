package io.github.ra5on.titanos.vms

import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ControlState
import io.github.ra5on.titanos.net.Endpoint
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VmApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: VmApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = VmApi(ComputeAccess(listOf(Endpoint(server.url("/"), OkHttpClient())), null))
    }

    @After
    fun tearDown() = server.close()

    private fun ok(body: String = "") = server.enqueue(MockResponse.Builder().code(if (body.isEmpty()) 204 else 200).body(body).build())

    private fun take(): RecordedRequest = server.takeRequest()

    private val RecordedRequest.where: String get() = method + " " + url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")

    @Test
    fun listsVmsAsTheWebGetsThem() = runBlocking {
        ok(
            """{"vms":[{"name":"source","displayName":"Source VM","state":"running","memoryMiB":512,"vcpus":1,"disks":[],
            "cpuPin":null,"folders":null,"pci":null,"usb":null,"network":"user","mac":"52:54:00:00:00:01","available":true,
            "job":null,"cpuPercent":12.5,"memoryBytes":100,"since":"","powerSchedule":{"enabled":true,"rules":[{"action":"start","days":[1],"time":"07:00"}]},
            "unknown":1}],"kvm":true,"bridge":false,"host":{"cpus":8,"memoryBytes":16000000000,"usedMemoryBytes":3000000000,"cpuModel":"Test"}}""",
        )
        val status = api.status()
        assertEquals("GET /api/v1/vms", take().where)
        assertEquals("Source VM", status.vms[0].label)
        assertTrue(status.vms[0].running)
        assertEquals(8, status.host.cpus)
        assertEquals(1, status.vms[0].powerSchedule?.rules?.size)
    }

    @Test
    fun actionsAndConfirmFields() = runBlocking {
        ok(); api.action("my vm", "shutdown")
        assertEquals("POST /api/v1/vms/my%20vm/shutdown", take().where)

        ok(); api.remove("web", deleteDisks = true)
        take().let {
            assertEquals("DELETE /api/v1/vms/web", it.where)
            assertEquals("""{"deleteDisks":true,"confirm":"web"}""", it.body?.utf8())
        }
        ok(); api.remove("web", deleteDisks = false)
        assertEquals("""{"deleteDisks":false,"confirm":""}""", take().body?.utf8())

        ok(); api.setMedium("web", "")
        take().let {
            assertEquals("PUT /api/v1/vms/web/medium", it.where)
            assertEquals("""{"path":""}""", it.body?.utf8())
        }
        ok(); api.clone("web", "web-2", "Data")
        assertEquals("""{"name":"web-2","volume":"Data"}""", take().body?.utf8())
        ok(); api.grow("web", 1, 80 * GB)
        assertEquals("""{"disk":1,"sizeBytes":80000000000}""", take().body?.utf8())
        ok("""{"xml":"<domain/>"}"""); assertEquals("<domain/>", api.xml("web"))
        assertEquals("GET /api/v1/vms/web/xml", take().where)
    }

    @Test
    fun snapshotsSendTheNamesAsConfirmation() = runBlocking {
        ok(); api.snapshotRestore("web", "s1", memory = true)
        take().let {
            assertEquals("POST /api/v1/vms/web/snapshots/s1/restore", it.where)
            assertEquals("""{"confirm":"web","memory":true}""", it.body?.utf8())
        }
        ok(); api.snapshotDelete("web", Snapshot(id = "s1", name = "Vor Update"))
        take().let {
            assertEquals("DELETE /api/v1/vms/web/snapshots/s1", it.where)
            assertEquals("""{"confirm":"Vor Update"}""", it.body?.utf8())
        }
        ok(); api.snapshotRecover("web", discardMemory = false)
        assertEquals("""{"confirm":"web","discardMemory":false}""", take().body?.utf8())
        ok(); api.snapshotCreate("web", "A", memory = false, allowPause = false)
        assertEquals("""{"name":"A","memory":false,"allowPause":false}""", take().body?.utf8())
        ok(); api.snapshotSchedule("web", SnapshotSchedule())
        take().let {
            assertEquals("PUT /api/v1/vms/web/snapshot-schedule", it.where)
            assertEquals("""{"enabled":true,"time":"03:00","days":[0,1,2,3,4,5,6],"keep":7,"memory":true,"allowPause":false}""", it.body?.utf8())
        }
    }

    @Test
    fun createAndUpdateCarryEveryField() = runBlocking {
        ok("""{"started":false,"startError":{"code":"vm_failed","message":"no kvm"}}""")
        val made = api.create(
            VmCreate(
                name = "web", displayName = "Web", description = "", template = "linux", firmware = "uefi", autostart = false,
                powerSchedule = schedulePayload(ScheduleDraft()), tpm = false, secureBoot = false, vcpus = 2, memoryMiB = 1907,
                cpuPin = emptyList(), exclusive = false, iso = "", virtioISO = "", folders = emptyList(), pci = emptyList(),
                autoGPU = false, usb = emptyList(), network = "user",
                disks = listOf(NewDisk(format = "qcow2", sizeBytes = 64 * GB, volume = "Data"), NewDisk(path = "/dev/disk/by-id/x")), start = true,
            ),
        )
        assertEquals("vm_failed", made.startError?.code)
        val body = take().body!!.utf8()
        assertTrue(body, body.contains(""""autostart":false"""))
        assertTrue(body, body.contains(""""disks":[{"format":"qcow2","sizeBytes":64000000000,"volume":"Data"},{"path":"/dev/disk/by-id/x"}]"""))
        assertTrue(body, !body.contains("directory"))
        assertTrue(body, body.contains(""""powerSchedule":{"enabled":false,"rules":[],"stopTimeout":300,"forceOff":false}"""))
    }

    @Test
    fun mediaUploadInPieces() = runBlocking {
        ok("""{"id":"j1","volume":"Data","name":"a.iso","phase":"uploading","bytes":0,"total":10}""")
        val job = api.startImport(MediaImportRequest("Data", "a.iso", "", "auto", uploadName = "a.iso", uploadSize = 10))
        take().let {
            assertEquals("POST /api/v1/vms/imports", it.where)
            assertEquals("""{"volume":"Data","name":"a.iso","sha256":"","compression":"auto","uploadName":"a.iso","uploadSize":10}""", it.body?.utf8())
        }
        ok(); api.putChunk(job.id, 8, ByteArray(16) { it.toByte() }, size = 2)
        take().let {
            assertEquals("PUT /api/v1/vms/imports/j1/data?offset=8", it.where)
            assertEquals(2L, it.body?.size?.toLong())
        }
        ok(); api.importAction("j1", "finish")
        take().let {
            assertEquals("POST /api/v1/vms/imports/j1/finish", it.where)
            assertEquals("{}", it.body?.utf8())
        }
        ok(); api.verify("j1", listOf(ChunkProof(0, 8, "ab")))
        assertEquals("""{"chunks":[{"offset":0,"size":8,"sha256":"ab"}]}""", take().body?.utf8())
    }

    @Test
    fun adoptAndFiles() = runBlocking {
        ok("""{"name":"old-2","changes":["pci"]}""")
        val r = api.adopt(AdoptCandidate(volume = "Data", directory = "old", name = "old", nameTaken = true), "old-2")
        assertEquals(listOf("pci"), r.changes)
        assertEquals("""{"volume":"Data","directory":"old","name":"old-2"}""", take().body?.utf8())
        ok("""[{"name":"iso","dir":true},{"name":"a.iso","dir":false,"size":5}]""")
        assertEquals(2, api.listFiles("Data", "iso/sub dir").size)
        assertEquals("GET /api/v1/files/list?root=volume%2FData&path=iso%2Fsub%20dir", take().where)
    }

    @Test
    fun lxcRequests() = runBlocking {
        ok(); api.lxcRemove("web", deleteData = true)
        take().let {
            assertEquals("DELETE /api/v1/lxc/web", it.where)
            assertEquals("""{"deleteData":true,"confirm":"web"}""", it.body?.utf8())
        }
        ok(); api.lxcRemove("web", deleteData = false)
        assertEquals(0L, take().body?.size?.toLong() ?: 0L)
        ok(); api.lxcAction("web", "kill")
        assertEquals("POST /api/v1/lxc/web/kill", take().where)
        ok(); api.lxcRestore("web", "2026")
        assertEquals("POST /api/v1/lxc/web/snapshots/2026/restore", take().where)
        ok("""{"config":"lxc.net.0.type = veth"}"""); assertEquals("lxc.net.0.type = veth", api.lxcConfig("web"))
        assertEquals("GET /api/v1/lxc/web/config", take().where)
    }

    @Test
    fun controlsForDeviceControls() = runBlocking {
        ok("""{"vms":[{"name":"a","state":"running"},{"name":"b","state":"stopped","available":false},{"name":"c","job":{"state":"running","text":"x"}}]}""")
        val items = VmControls(api.access).items()
        assertEquals(listOf(ControlState.Running, ControlState.Problem, ControlState.Busy), items.map { it.state })
        take()
        ok(); VmControls(api.access).stop("a")
        assertEquals("POST /api/v1/vms/a/shutdown", take().where)
    }
}
