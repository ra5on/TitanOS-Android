package io.github.ra5on.titanos.vms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.vms.screen.ScreenSession
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.ui.theme.TitanTheme
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.serialization.decodeFromString
import java.util.Locale
import java.util.TimeZone

/**
 * Bildtests der Seiten VMs und LXC gegen ein nachgebautes NAS (MockWebServer
 * mit Beispieldaten, RFB-Gegenstelle für den Bildschirm): 390 dp hell und
 * dunkel, 200 % Schrift, breit 1280 dp. Bilder:
 * `./gradlew :feature-vms:recordRoborazziDebug` → feature-vms/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xhdpi")
class VmsScreenshotTest {
    private lateinit var server: MockWebServer

    private val vmsJson = """{"kvm":true,"bridge":true,"networks":[],"host":{"cpus":8,"memoryBytes":16000000000,"usedMemoryBytes":5200000000,"availableMemoryBytes":9800000000,"cpuModel":"Intel N100"},
      "vms":[
       {"name":"debian","displayName":"Debian","description":"Testgast mit Desktop","template":"linux","memoryMiB":4096,"vcpus":2,"volume":"Data",
        "disks":[{"file":"vdisk1.qcow2","format":"qcow2","sizeBytes":64000000000,"volume":"Data"}],"iso":"","autostart":true,"cpuPin":[],"exclusive":false,
        "folders":[{"path":"/mnt/Data/Dokumente","tag":"dokumente","readOnly":false}],"tpm":false,"secureBoot":false,"firmware":"uefi","pci":[],"usb":[],
        "network":"bridge","mac":"52:54:00:12:34:56","job":null,"state":"running","available":true,"cpuPercent":23.5,"memoryBytes":2900000000,"since":"2026-11-21T08:00:00Z",
        "powerSchedule":{"enabled":true,"rules":[{"action":"start","days":[1,2,3,4,5],"time":"07:00"},{"action":"stop","days":[1,2,3,4,5],"time":"22:00"}],"stopTimeout":300,"forceOff":false},
        "schedule":{"nextStart":"2026-11-23T06:00:00Z","nextStop":"2026-11-21T21:00:00Z"}},
       {"name":"win11","displayName":"Windows 11 Büro","description":"","template":"windows","memoryMiB":8192,"vcpus":4,"volume":"Data",
        "disks":[{"file":"vdisk1.qcow2","format":"qcow2","sizeBytes":128000000000,"volume":"Data"}],"iso":"/mnt/Data/iso/Win11_24H2_German_x64.iso","virtioISO":"/mnt/Data/iso/virtio-win.iso",
        "autostart":false,"cpuPin":[2,3,6,7],"exclusive":true,"folders":[],"tpm":true,"secureBoot":true,"firmware":"uefi","pci":[],"usb":[],
        "network":"user","mac":"52:54:00:ab:cd:ef","job":null,"state":"stopped","available":true,"cpuPercent":0,"memoryBytes":0,"since":""},
       {"name":"ha","displayName":"Home Assistant","description":"","template":"linux","memoryMiB":2048,"vcpus":2,"volume":"Data","disks":[],"iso":"",
        "autostart":true,"network":"bridge","mac":"52:54:00:00:00:02","state":"stopped","available":true,"backupHeld":true,"cpuPercent":0,"memoryBytes":0,"since":""}]}"""

    // Ein laufender Auftrag zeigt einen sich drehenden Kreis; den zeichnet Robolectric beim
    // Aufnehmen endlos (Lauf hing in nSyncAndDrawFrame). Deshalb hier ein gescheiterter Auftrag.
    private val lxcJson = """{"installed":true,"dev":false,"host":{"cpus":8,"memoryBytes":16000000000},"bridge":true,"networks":[],
      "containers":[
       {"name":"pihole","displayName":"Pi-hole","description":"DNS für das Heimnetz","volume":"Data","distribution":"debian","release":"trixie","arch":"amd64",
        "cpus":1,"memoryMiB":512,"network":"bridge","mac":"00:16:3e:12:34:56","mounts":[{"path":"/mnt/Data/Backup","target":"/srv/backup","readOnly":true}],
        "autostart":true,"privileged":false,"btrfs":true,"created":"2026-11-02T10:00:00Z","state":"running","available":true,"cpuPercent":1.2,"memoryBytes":120000000},
       {"name":"build","volume":"Data","distribution":"ubuntu","release":"24.04","arch":"amd64","network":"none","mac":"","autostart":false,
        "created":"2026-11-20T10:00:00Z","state":"stopped","available":true,"cpuPercent":0,"memoryBytes":0,"job":{"state":"failed","phase":"download","started":"2026-11-21T10:00:00Z","code":"timeout"}}]}"""

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    /** Ein kleines Testbild als RFB-Strom: Verlauf mit hellem Fenster. */
    private fun rfbStream(): ByteArray {
        val w = 320
        val h = 200
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v) }
        fun s32(v: Int) = out.write(ByteBuffer.allocate(4).putInt(v).array())
        out.write("RFB 003.008\n".toByteArray()); out.write(1); out.write(1); s32(0)
        u16(w); u16(h); out.write(byteArrayOf(32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0)); s32(5); out.write("Gast".toByteArray())
        out.write(0); out.write(0); u16(1); u16(0); u16(0); u16(w); u16(h); s32(0)
        for (y in 0 until h) for (x in 0 until w) {
            val inWindow = x in 20..140 && y in 110..170
            val r = if (inWindow) 232 else 10 + y / 8
            val g = if (inWindow) 236 else 25 + y / 6
            val b = if (inWindow) 240 else 60 + x / 4
            out.write(b); out.write(g); out.write(r); out.write(0)
        }
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (val path = request.url.encodedPath.removePrefix("/api/v1")) {
                "/vms" -> json(vmsJson)
                "/storage" -> json("""{"volumes":[{"name":"Data","mountPoint":"/mnt/Data","mounted":true,"sizeBytes":4000000000000,"usedBytes":1500000000000}]}""")
                "/terminal" -> json("""{"enabled":true}""")
                "/vms/host" -> json("""{"cpus":[${(0..7).joinToString(",") { """{"id":$it,"kind":"","siblings":[${it % 4},${it % 4 + 4}],"usedBy":[]}""" }}],"images":[],"disks":[]}""")
                "/hardware" -> json("""{"hardware":{"iommu":true,"pci":[],"usb":[{"id":"1a86:7523","name":"Zigbee-Stick","port":"1-2","stick":false,"duplicate":false}]},"usedBy":{}}""")
                "/shares" -> json("""[{"name":"Dokumente","volume":"Data","path":"/mnt/Data/Dokumente","available":true}]""")
                "/vms/media" -> json("""{"media":[{"path":"/mnt/Data/iso/debian-13.iso","sizeBytes":680000000},{"path":"/mnt/Data/iso/Win11_24H2_German_x64.iso","sizeBytes":5800000000}]}""")
                "/vms/imports" -> json("""{"jobs":[{"id":"j1","volume":"Data","name":"ubuntu-24.04.iso","url":"https://releases.ubuntu.com/ubuntu-24.04.iso","phase":"downloading","bytes":2100000000,"total":6100000000}],"chunkBytes":8388608}""")
                "/vms/adopt" -> json("""{"candidates":[{"volume":"Data","directory":"alt","name":"alt","displayName":"Alte VM","template":"linux","memoryMiB":2048,"vcpus":2,"disks":["/mnt/Data/vms/alt/vdisk1.qcow2"],"created":"2026-10-01T10:00:00Z","removed":true,"removedAt":"2026-11-01T10:00:00Z","nameTaken":false,"dropped":["pci"],"missing":[]}]}""")
                "/network" -> json("""{"network":{"config":{"bridge":true},"configured":true,"bridgeReady":true,"pending":null,"pendingSeconds":0,"interfaces":[]}}""")
                "/lxc" -> json(lxcJson)
                "/lxc/pihole/snapshots" -> json("""{"snapshots":[{"id":"s1","created":"2026-11-20T03:00:00Z"}]}""")
                "/vms/debian/screen" -> MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val stream = rfbStream()
                        var i = 0
                        while (i < stream.size) {
                            val end = minOf(stream.size, i + 60_000)
                            webSocket.send(stream.copyOfRange(i, end).toByteString())
                            i = end
                        }
                    }
                }).build()
                else -> when {
                    path.endsWith("/snapshots") -> json("""{"snapshots":[{"id":"s1","name":"Vor dem Update","created":"2026-11-20T18:00:00Z","memory":true,"automatic":false,"bytes":1200000000}],"schedule":{"enabled":true,"time":"03:00","days":[0,1,2,3,4,5,6],"keep":7,"memory":false,"allowPause":false},"pending":false,"reason":"","job":null}""")
                    path.endsWith("/addresses") -> json("""{"addresses":["192.168.1.42","fd00::42"],"source":"agent"}""")
                    else -> MockResponse.Builder().code(404).body("""{"error":{"code":"not_found","message":"x"}}""").build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private val env by lazy { ComputeEnv(ComputeAccess(listOf(Endpoint(server.url("/"), OkHttpClient())), null), { "owner" }) }
    private val api by lazy { VmApi(env.access) }
    private val status by lazy { TitanJson.decodeFromString<VmStatus>(vmsJson) }
    private val volumes = listOf(Volume("Data", "/mnt/Data", true, "ok", 4_000_000_000_000, 1_500_000_000_000))
    private val snapshots = SnapshotStatus(
        listOf(Snapshot("s1", "Vor dem Update", "2026-11-20T18:00:00Z", memory = true, bytes = 1_200_000_000), Snapshot("s2", "Nachts", "2026-11-21T02:00:00Z", automatic = true, bytes = 300_000_000)),
        SnapshotSchedule(true, "03:00", listOf(0, 1, 2, 3, 4, 5, 6), 7, false, false),
    )

    private fun vms(selected: String? = null, tab: VmTab? = null) = VmsState(api).apply {
        status = this@VmsScreenshotTest.status
        volumes = this@VmsScreenshotTest.volumes
        this.selected = selected
        detailOpen = selected != null
        tabChosen = tab
        this.snapshots["win11"] = this@VmsScreenshotTest.snapshots
        this.snapshots["debian"] = this@VmsScreenshotTest.snapshots
        addresses["debian"] = Addresses(listOf("192.168.1.42", "fd00::42"), "agent")
        history["debian"] = listOf(12.0, 18.0, 25.0, 40.0, 33.0, 21.0, 19.0, 24.0, 30.0, 23.5)
    }

    private fun preload(): VmsPreload {
        val image = IntArray(320 * 200) { i ->
            val x = i % 320
            val y = i / 320
            if (x in 20..140 && y in 110..170) 0xffe8ecf0.toInt() else (0xff000000.toInt() or ((10 + y / 8) shl 16) or ((25 + y / 6) shl 8) or (60 + x / 4))
        }
        val screen = ScreenSession(api, "debian").apply { preview(android.graphics.Bitmap.createBitmap(image, 320, 200, android.graphics.Bitmap.Config.ARGB_8888)) }
        return VmsPreload(
            media = MediaPreload(
                listOf(MediaJob("j1", "Data", "ubuntu-24.04.iso", url = "https://releases.ubuntu.com/ubuntu-24.04.iso", phase = "downloading", bytes = 2_100_000_000, total = 6_100_000_000)),
                listOf(MediaEntry("/mnt/Data/iso/debian-13.iso", 680_000_000), MediaEntry("/mnt/Data/iso/Win11_24H2_German_x64.iso", 5_800_000_000)),
                status.vms,
                volumes,
            ),
            adopt = listOf(AdoptCandidate("Data", "alt", "alt", "Alte VM", template = "linux", memoryMiB = 2048, vcpus = 2, disks = listOf("/mnt/Data/vms/alt/vdisk1.qcow2"), removed = true, removedAt = "2026-11-01T10:00:00Z", dropped = listOf("pci"))),
            form = FormPreload(
                status,
                VmHost((0..7).map { HostCpu(it, "", listOf(it % 4, it % 4 + 4)) }),
                Hardware(HardwareList(true, emptyList(), listOf(UsbDevice("1a86:7523", "Zigbee-Stick", "1-2")))),
                listOf(Share("Dokumente", "Data", "/mnt/Data/Dokumente")),
                emptyList(),
                volumes,
            ),
            screen = screen,
        )
    }

    private fun shot(name: String, both: Boolean = true, content: @Composable () -> Unit) {
        for (dark in if (both) listOf(false, true) else listOf(false)) {
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun big(name: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(2f)
        try {
            shot("$name-schrift-200", both = false, content = content)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun list() = shot("vms-liste") { VmsProgram(env, vms(), {}) }

    @Test
    fun overviewStopped() = shot("vm-uebersicht") { VmsProgram(env, vms("win11"), {}) }

    @Test
    fun overviewRunning() = shot("vm-uebersicht-laeuft") { VmsProgram(env, vms("debian", VmTab.Overview), {}) }

    @Test
    fun hardware() = shot("vm-hardware") { VmsProgram(env, vms("win11", VmTab.Hardware), {}) }

    @Test
    fun scheduleTab() = shot("vm-zeitplan", both = false) { VmsProgram(env, vms("debian", VmTab.Schedule), {}) }

    @Test
    fun heldVm() = shot("vm-gesperrt", both = false) { VmsProgram(env, vms("ha"), {}) }

    @Test
    fun screen() = shot("vm-bildschirm") { VmsProgram(env, vms("debian"), {}, preload = preload()) }

    @Test
    fun snapshotsTab() = shot("vm-snapshots") { VmsProgram(env, vms("win11", VmTab.Snapshots), {}) }

    @Test
    fun newVm() = shot("vm-neu") { VmsProgram(env, vms(), {}, initialSection = "new", preload = preload()) }

    @Test
    fun media() = shot("vm-medien") { VmsProgram(env, vms(), {}, initialSection = "media", preload = preload()) }

    @Test
    fun adopt() = shot("vm-uebernehmen", both = false) { VmsProgram(env, vms(), {}, initialSection = "adopt", preload = preload()) }

    @Test
    fun overviewBigFont() = big("vm-uebersicht") { VmsProgram(env, vms("win11"), {}) }

    @Test
    fun screenBigFont() = big("vm-bildschirm") { VmsProgram(env, vms("debian"), {}, preload = preload()) }

    @Test
    fun wide() {
        RuntimeEnvironment.setQualifiers("+w1280dp-h800dp")
        shot("vms-breit") { VmsProgram(env, vms("debian", VmTab.Overview), {}) }
    }

    @Test
    fun wideScreen() {
        RuntimeEnvironment.setQualifiers("+w1280dp-h800dp")
        shot("vm-bildschirm-breit", both = false) { VmsProgram(env, vms("debian"), {}, preload = preload()) }
    }

    @Test
    fun wideForm() {
        RuntimeEnvironment.setQualifiers("+w1280dp-h800dp")
        shot("vm-neu-breit", both = false) { VmsProgram(env, vms(), {}, initialSection = "new", preload = preload()) }
    }

    private fun lxc(selected: String? = null) = LxcState(api).apply {
        list = TitanJson.decodeFromString<LxcList>(lxcJson)
        this.selected = selected
        detailOpen = selected != null
        snapshots = listOf(LxcSnapshot("s1", "2026-11-20T03:00:00Z"))
        snapshotsFor = "pihole"
    }

    @Test
    fun lxcList() = shot("lxc-liste") { LxcProgram(env, lxc(), {}) }

    @Test
    fun lxcDetail() = shot("lxc-detail") { LxcProgram(env, lxc("pihole"), {}) }

    @Test
    fun lxcBigFont() = big("lxc-detail") { LxcProgram(env, lxc("pihole"), {}) }

    @Test
    fun lxcWide() {
        RuntimeEnvironment.setQualifiers("+w1280dp-h800dp")
        shot("lxc-breit") { LxcProgram(env, lxc("pihole"), {}) }
    }

    @Test
    fun withoutRights() = shot("vms-ohne-rechte", both = false) {
        VmsRoot(ComputeEnv(env.access, { "user" })) {}
    }
}
